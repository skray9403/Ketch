package com.linroid.ketch.adm.http

import com.linroid.ketch.adm.http.cookies.CookieMatcher
import com.linroid.ketch.adm.http.cookies.HttpOrigin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI

/** Configuration for [AdmRedirectHandler]. */
data class AdmRedirectConfig(
  val maxRedirects: Int = 10,
  val convertPostToGetOn301And302: Boolean = true,
) {
  init {
    require(maxRedirects >= 0) { "maxRedirects must be non-negative: $maxRedirects" }
  }
}

/** Result of executing a request through [AdmRedirectHandler]. */
data class AdmRedirectResult(
  val response: AdmResponse,
  val finalUrl: String,
  val redirectCount: Int,
)

/** Thrown when a redirect chain exceeds [AdmRedirectConfig.maxRedirects]. */
class TooManyRedirectsException(
  val maxRedirects: Int,
  val visitedUrls: List<String>,
) : IllegalStateException("Exceeded maximum redirects ($maxRedirects): ${visitedUrls.joinToString(" -> ")}")

/** Thrown when a redirect cycle is detected. */
class RedirectLoopException(
  val url: String,
  val hopCount: Int,
) : IllegalStateException("Redirect loop detected at $url after $hopCount hop(s)")

/** Thrown when a redirect requires replaying a non-repeatable [AdmRequestBody]. */
class NonRepeatableBodyException(
  val status: Int,
  val method: String,
  val url: String,
) : IllegalStateException("Cannot replay non-repeatable request body on HTTP $status redirect for $method $url")

/** Thrown when a `3xx` redirect response has a missing or invalid `Location` header. */
class InvalidRedirectException(
  val status: Int,
  val location: String?,
  val currentUrl: String,
) : IllegalStateException("Invalid redirect Location '$location' on HTTP $status from $currentUrl")

/**
 * Per-task redirect and cookie pipeline.
 *
 * Observes raw `3xx` responses from [AdmHttpTransport], updates [AdmTaskState.cookies] before
 * building the next hop, enforces cross-origin credential stripping, and checks task/coroutine
 * liveness before every hop.
 */
class AdmRedirectHandler internal constructor(
  private val taskState: AdmTaskState,
  private val config: AdmRedirectConfig = AdmRedirectConfig(),
) {

  private data class RedirectHopKey(
    val method: String,
    val url: String,
    val cookieHeader: String?,
  )

  suspend fun execute(
    request: AdmRequest,
    transport: AdmHttpTransport,
  ): AdmRedirectResult = runPipeline(request) { hopRequest ->
    transport.execute(hopRequest)
  }

  suspend fun openStream(
    request: AdmRequest,
    transport: AdmHttpTransport,
  ): AdmRedirectResult = runPipeline(request) { hopRequest ->
    transport.openStream(hopRequest)
  }

  suspend fun stream(
    request: AdmRequest,
    range: LongRange?,
    transport: AdmHttpTransport,
    onData: suspend (ByteArray) -> Unit,
  ): AdmRedirectResult = runPipeline(request) { hopRequest ->
    transport.stream(hopRequest, range, onData)
  }

  private suspend inline fun runPipeline(
    initialRequest: AdmRequest,
    crossinline send: suspend (AdmRequest) -> AdmResponse,
  ): AdmRedirectResult {
    var currentMethod = initialRequest.method.uppercase()
    var currentUrl = initialRequest.url
    var currentBody = initialRequest.body

    // Separate caller-supplied Cookie headers so they are never persisted into TaskCookieJar
    // and can be stripped cleanly on cross-origin redirects.
    var activeCallerCookie: String? = initialRequest.headers("cookie")
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .joinToString("; ")
      .takeIf { it.isNotEmpty() }

    var forwardedHeaders = initialRequest.headers.filterNot { (name, _) ->
      name.equals("cookie", ignoreCase = true)
    }

    val initialOrigin: HttpOrigin? = CookieMatcher.parseUrl(currentUrl)?.origin
    val visitedHops = LinkedHashSet<RedirectHopKey>()
    val visitedUrls = ArrayList<String>()
    var redirects = 0

    while (true) {
      currentCoroutineContext().ensureActive()
      taskState.checkActive()

      val currentOrigin = CookieMatcher.parseUrl(currentUrl)?.origin
      if (initialOrigin != null && currentOrigin != null && currentOrigin != initialOrigin) {
        activeCallerCookie = null
      }

      val jarCookie = taskState.cookies.cookieHeaderFor(currentUrl)
      val combinedCookie = combineCookies(activeCallerCookie, jarCookie)
      val hopHeaders = if (combinedCookie != null) {
        forwardedHeaders + ("Cookie" to combinedCookie)
      } else {
        forwardedHeaders
      }

      val hopKey = RedirectHopKey(method = currentMethod, url = currentUrl, cookieHeader = combinedCookie)
      if (!visitedHops.add(hopKey)) {
        throw RedirectLoopException(url = currentUrl, hopCount = redirects)
      }
      visitedUrls.add(currentUrl)

      val hopRequest = AdmRequest(
        method = currentMethod,
        url = currentUrl,
        headers = hopHeaders,
        body = currentBody,
        maxBodyBytes = initialRequest.maxBodyBytes,
      )

      val response = send(hopRequest)

      // Always store Set-Cookie headers before evaluating/building the next hop.
      val setCookies = response.headers("set-cookie")
      if (setCookies.isNotEmpty()) {
        taskState.cookies.store(currentUrl, setCookies)
      }

      if (!response.isRedirect) {
        val resolvedFinalUrl = response.url?.takeIf { it.isNotBlank() } ?: currentUrl
        return AdmRedirectResult(
          response = if (response.url == null) response.copy(url = resolvedFinalUrl) else response,
          finalUrl = resolvedFinalUrl,
          redirectCount = redirects,
        )
      }

      // Close any intermediate redirect body before following the next hop.
      response.streamingBody?.close()

      if (redirects >= config.maxRedirects) {
        throw TooManyRedirectsException(maxRedirects = config.maxRedirects, visitedUrls = visitedUrls)
      }

      val location = response.header("location")
      val nextUrl = resolveLocation(currentUrl, location)
        ?: throw InvalidRedirectException(status = response.status, location = location, currentUrl = currentUrl)

      val nextOrigin = CookieMatcher.parseUrl(nextUrl)?.origin
      if (currentOrigin != null && nextOrigin != null && nextOrigin != currentOrigin) {
        activeCallerCookie = null
        forwardedHeaders = forwardedHeaders.filterNot { (name, _) ->
          name.equals("authorization", ignoreCase = true) ||
            name.equals("proxy-authorization", ignoreCase = true)
        }
      }

      when (response.status) {
        301, 302 -> {
          if (config.convertPostToGetOn301And302 && currentMethod == "POST") {
            currentMethod = "GET"
            currentBody = null
            forwardedHeaders = stripBodyHeaders(forwardedHeaders)
          } else if (currentBody != null && !currentBody.isReplayable) {
            throw NonRepeatableBodyException(status = response.status, method = currentMethod, url = currentUrl)
          }
        }
        303 -> {
          if (currentMethod == "HEAD") {
            currentMethod = "HEAD"
          } else {
            currentMethod = "GET"
          }
          currentBody = null
          forwardedHeaders = stripBodyHeaders(forwardedHeaders)
        }
        307, 308 -> {
          if (currentBody != null && !currentBody.isReplayable) {
            throw NonRepeatableBodyException(status = response.status, method = currentMethod, url = currentUrl)
          }
        }
      }

      currentUrl = nextUrl
      redirects++
    }
  }

  private fun combineCookies(callerCookie: String?, jarCookie: String?): String? = when {
    callerCookie.isNullOrEmpty() && jarCookie.isNullOrEmpty() -> null
    callerCookie.isNullOrEmpty() -> jarCookie
    jarCookie.isNullOrEmpty() -> callerCookie
    else -> "$callerCookie; $jarCookie"
  }

  private fun stripBodyHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> =
    headers.filterNot { (name, _) ->
      name.equals("content-length", ignoreCase = true) ||
        name.equals("content-type", ignoreCase = true)
    }

  internal fun resolveLocation(currentUrl: String, location: String?): String? {
    val trimmed = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val resolved = runCatching { URI(currentUrl).resolve(trimmed) }.getOrNull() ?: return null
    val scheme = resolved.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (resolved.host.isNullOrBlank()) return null
    return resolved.toString()
  }
}
