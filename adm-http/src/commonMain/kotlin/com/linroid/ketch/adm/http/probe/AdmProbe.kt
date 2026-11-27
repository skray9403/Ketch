package com.linroid.ketch.adm.http.probe

import com.linroid.ketch.adm.http.AdmHttpTransport
import com.linroid.ketch.adm.http.AdmRequest
import com.linroid.ketch.adm.http.AdmResponse
import com.linroid.ketch.adm.http.AdmResponseBody
import com.linroid.ketch.adm.http.AdmTaskState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.UnsatisfiedRangeResponse
import com.linroid.ketch.core.engine.ServerInfo
import java.io.ByteArrayOutputStream

/**
 * Range-probing pipeline routed through [AdmTaskState.redirectHandler] and [AdmTaskState.cookies],
 * backed by metadata-only [AdmTaskState.probeCache] and single-read [WebPageProbe] handoff.
 */
class AdmProbe internal constructor(
  private val taskState: AdmTaskState,
  private val config: AdmProbeConfig = AdmProbeConfig(),
) {

  suspend fun probe(
    url: String,
    transport: AdmHttpTransport,
    headers: Map<String, String> = emptyMap(),
    method: String = "GET",
    headHint: ServerInfo? = null,
    validators: ProbeValidators? = null,
  ): AdmProbeResult {
    taskState.checkActive()

    // HEAD remains non-authoritative unless explicitly enabled via config.
    val effectiveMethod = if (method.equals("HEAD", ignoreCase = true) && !config.allowAuthoritativeHead) {
      "GET"
    } else {
      method.uppercase()
    }

    val probeRequest = buildProbeRequest(url = url, method = effectiveMethod, callerHeaders = headers)
    val effectiveCookieHeader = combinePreRequestCookies(
      callerCookies = probeRequest.headers("cookie"),
      jarCookie = taskState.cookies.cookieHeaderFor(url),
    )

    val cacheKey = taskState.probeCache.buildKey(
      url = probeRequest.url,
      method = probeRequest.method,
      requestHeaders = probeRequest.headers,
      effectiveCookieHeader = effectiveCookieHeader,
    )

    val cached = taskState.probeCache.get(cacheKey, validators)
    if (cached != null) {
      taskState.checkActive()
      return AdmProbeResult(metadata = cached, fromCache = true, webPageProbe = null)
    }

    val redirectResult = taskState.redirectHandler.openStream(probeRequest, transport)
    taskState.checkActive()

    val response = redirectResult.response
    val finalUrl = redirectResult.finalUrl

    return when (response.status) {
      206 -> handle206(cacheKey, response, finalUrl)
      200 -> handle200(cacheKey, response, finalUrl, effectiveMethod, headHint)
      416 -> {
        response.streamingBody?.close()
        throw build416Error(response)
      }
      429 -> {
        response.streamingBody?.close()
        throw build429Error(response)
      }
      in 201..299 -> handle200(cacheKey, response, finalUrl, effectiveMethod, headHint)
      else -> {
        response.streamingBody?.close()
        throw KetchError.Http(
          code = response.status,
          statusMessage = "HTTP ${response.status}",
          retryAfterSeconds = parseRetryAfterSeconds(response.header("retry-after")),
          rateLimitRemaining = parseRateLimitRemaining(response),
        )
      }
    }
  }

  private fun buildProbeRequest(
    url: String,
    method: String,
    callerHeaders: Map<String, String>,
  ): AdmRequest {
    val headerList = callerHeaders.entries
      .filterNot { (k, _) ->
        k.equals("range", ignoreCase = true) || k.equals("accept-encoding", ignoreCase = true)
      }
      .map { (k, v) -> k to v }
      .toMutableList()

    if (method != "HEAD") {
      headerList.add("Range" to "bytes=0-${config.maxPrefixBytes - 1}")
    }
    headerList.add("Accept-Encoding" to "identity")

    return AdmRequest(method = method, url = url, headers = headerList)
  }

  private suspend fun handle206(
    cacheKey: ProbeCacheKey,
    response: AdmResponse,
    finalUrl: String,
  ): AdmProbeResult {
    val body = response.openBody()
    try {
      val contentRange = response.header("content-range")
        ?: throw InvalidProbeResponseException("206 response missing Content-Range header")
      val parsedRange = parse206ContentRange(contentRange)
        ?: throw InvalidProbeResponseException("Malformed 206 Content-Range: '$contentRange'")

      if (parsedRange.start != 0L) {
        throw InvalidProbeResponseException("206 probe expected start=0, got ${parsedRange.start}")
      }
      if (parsedRange.end < 0L || parsedRange.end < parsedRange.start) {
        throw InvalidProbeResponseException("206 probe invalid range ${parsedRange.start}-${parsedRange.end}")
      }

      val declaredLength = parsedRange.end - parsedRange.start + 1L
      if (declaredLength > config.maxPrefixBytes.toLong()) {
        throw InvalidProbeResponseException(
          "206 probe range length $declaredLength exceeds maxPrefixBytes=${config.maxPrefixBytes}",
        )
      }
      if (parsedRange.total != null && parsedRange.total < parsedRange.end + 1L) {
        throw InvalidProbeResponseException(
          "206 Content-Range total=${parsedRange.total} is smaller than end+1=${parsedRange.end + 1L}",
        )
      }

      val (prefixBytes, hitEof) = readPrefixBytes(body, config.maxPrefixBytes + 1)
      if (prefixBytes.size > config.maxPrefixBytes) {
        throw InvalidProbeResponseException(
          "206 response body exceeded maxPrefixBytes=${config.maxPrefixBytes}",
        )
      }
      if (!hitEof || prefixBytes.size.toLong() != declaredLength) {
        throw InvalidProbeResponseException(
          "206 response body size ${prefixBytes.size} does not match Content-Range length $declaredLength",
        )
      }

      val metadata = ProbeMetadata(
        finalUrl = finalUrl,
        supportsRanges = true,
        totalBytes = parsedRange.total,
        etag = response.header("etag"),
        lastModified = response.header("last-modified"),
        contentEncoding = response.header("content-encoding"),
        contentType = response.header("content-type"),
        contentDisposition = response.header("content-disposition"),
        statusCode = 206,
      )
      taskState.probeCache.put(cacheKey, metadata, config.cacheTtlMs)
      return AdmProbeResult(metadata = metadata, fromCache = false, webPageProbe = null)
    } finally {
      body.close()
    }
  }

  private suspend fun handle200(
    cacheKey: ProbeCacheKey,
    response: AdmResponse,
    finalUrl: String,
    effectiveMethod: String,
    @Suppress("UNUSED_PARAMETER") headHint: ServerInfo?,
  ): AdmProbeResult {
    val contentLength = response.header("content-length")?.toLongOrNull()?.takeIf { it >= 0L }
    val supportsRanges = if (effectiveMethod == "HEAD" && config.allowAuthoritativeHead) {
      response.header("accept-ranges")?.equals("bytes", ignoreCase = true) == true
    } else {
      false
    }

    val metadata = ProbeMetadata(
      finalUrl = finalUrl,
      supportsRanges = supportsRanges,
      totalBytes = contentLength,
      etag = response.header("etag"),
      lastModified = response.header("last-modified"),
      contentEncoding = response.header("content-encoding"),
      contentType = response.header("content-type"),
      contentDisposition = response.header("content-disposition"),
      statusCode = response.status,
    )

    if (effectiveMethod == "HEAD") {
      response.streamingBody?.close()
      taskState.probeCache.put(cacheKey, metadata, config.cacheTtlMs)
      return AdmProbeResult(metadata = metadata, fromCache = false, webPageProbe = null)
    }

    val body = response.openBody()
    val prefixBytes = try {
      readPrefixBytes(body, config.maxPrefixBytes).first
    } catch (t: Throwable) {
      body.close()
      throw t
    }

    // Store metadata-only in ProbeCache (never the stream or prefix replay handle).
    taskState.probeCache.put(cacheKey, metadata, config.cacheTtlMs)

    val handoff = WebPageProbe(
      metadata = metadata,
      taskState = taskState,
      prefix = prefixBytes,
      remainder = body,
    )
    return AdmProbeResult(metadata = metadata, fromCache = false, webPageProbe = handoff)
  }

  private suspend fun readPrefixBytes(
    body: AdmResponseBody,
    limit: Int,
  ): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream(minOf(limit, config.maxPrefixBytes))
    var remaining = limit
    var hitEof = false
    while (remaining > 0) {
      val chunk = body.readChunk(remaining)
      if (chunk == null) {
        hitEof = true
        break
      }
      if (chunk.isNotEmpty()) {
        val toWrite = minOf(chunk.size, remaining)
        out.write(chunk, 0, toWrite)
        remaining -= toWrite
      }
    }
    return out.toByteArray() to hitEof
  }

  private fun combinePreRequestCookies(callerCookies: List<String>, jarCookie: String?): String? {
    val caller = callerCookies.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
    return when {
      caller.isEmpty() && jarCookie.isNullOrEmpty() -> null
      caller.isEmpty() -> jarCookie
      jarCookie.isNullOrEmpty() -> caller
      else -> "$caller; $jarCookie"
    }
  }

  companion object {
    private val CONTENT_RANGE_206_REGEX =
      Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)
    private val CONTENT_RANGE_416_REGEX =
      Regex("""^bytes\s+\*/(\d+|\*)$""", RegexOption.IGNORE_CASE)

    internal data class Parsed206Range(
      val start: Long,
      val end: Long,
      val total: Long?,
    )

    internal fun parse206ContentRange(header: String): Parsed206Range? {
      val match = CONTENT_RANGE_206_REGEX.matchEntire(header.trim()) ?: return null
      val start = match.groupValues[1].toLongOrNull() ?: return null
      val end = match.groupValues[2].toLongOrNull() ?: return null
      val totalStr = match.groupValues[3]
      val total = if (totalStr == "*") null else totalStr.toLongOrNull() ?: return null
      return Parsed206Range(start = start, end = end, total = total)
    }

    internal fun parse416TotalBytes(contentRange: String?): Long? {
      val trimmed = contentRange?.trim() ?: return null
      val match = CONTENT_RANGE_416_REGEX.matchEntire(trimmed) ?: return null
      val totalStr = match.groupValues[1]
      return if (totalStr == "*") null else totalStr.toLongOrNull()
    }

    internal fun build416Error(response: AdmResponse): KetchError.Http {
      val totalBytes = parse416TotalBytes(response.header("content-range"))
      val details = UnsatisfiedRangeResponse(
        totalBytes = totalBytes,
        etag = response.header("etag"),
        lastModified = response.header("last-modified"),
      )
      return KetchError.Http(
        code = 416,
        statusMessage = "Range Not Satisfiable",
        cause = details,
      )
    }

    internal fun build429Error(response: AdmResponse): KetchError.Http = KetchError.Http(
      code = 429,
      statusMessage = "Too Many Requests",
      retryAfterSeconds = parseRetryAfterSeconds(response.header("retry-after")),
      rateLimitRemaining = parseRateLimitRemaining(response),
    )

    private fun parseRetryAfterSeconds(value: String?): Long? =
      value?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

    private fun parseRateLimitRemaining(response: AdmResponse): Long? {
      val raw = response.header("x-ratelimit-remaining") ?: response.header("ratelimit-remaining")
      return raw?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
    }
  }
}
