package com.linroid.ketch.adm.http.cookies

import com.linroid.ketch.adm.http.AdmTaskState
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-task RFC-6265 cookie store bound to an [AdmTaskState] generation.
 *
 * Every public operation enforces [AdmTaskState.checkActive] so closed or stale task generations
 * throw [com.linroid.ketch.core.engine.InactiveTaskEngineException] and never leak or repopulate.
 */
class TaskCookieJar internal constructor(
  private val taskState: AdmTaskState,
  private val clock: () -> Long = System::currentTimeMillis,
  private val publicSuffixMatcher: PublicSuffixMatcher = PublicSuffixMatcher.NONE,
) {
  private val lock = Any()
  private val cookies = LinkedHashMap<CookieKey, StoredCookie>()
  private val creationSeq = AtomicLong(0L)

  private val orderingComparator =
    compareByDescending<StoredCookie> { it.path.length }.thenBy { it.createdAtOrder }

  /**
   * Parses and stores all [setCookieHeaders] received from [url].
   *
   * Expired cookies (`Max-Age <= 0` or past `Expires`) delete any matching `(name, domain, path)`
   * cookie.
   */
  fun store(url: String, setCookieHeaders: List<String>) {
    taskState.checkActive()
    if (setCookieHeaders.isEmpty()) return
    val parsedUrl = CookieMatcher.parseUrl(url) ?: return
    val nowMillis = clock()

    synchronized(lock) {
      taskState.checkActive()
      for (header in setCookieHeaders) {
        val nextOrder = creationSeq.incrementAndGet()
        val parsed = CookieParser.parse(
          setCookieHeader = header,
          requestUrl = parsedUrl,
          nowMillis = nowMillis,
          createdAtOrder = nextOrder,
          publicSuffixMatcher = publicSuffixMatcher,
        ) ?: continue

        if (parsed.isExpired(nowMillis)) {
          cookies.remove(parsed.key)
        } else {
          val existing = cookies[parsed.key]
          val toStore = if (existing != null) {
            parsed.copy(createdAtOrder = existing.createdAtOrder)
          } else {
            parsed
          }
          cookies[toStore.key] = toStore
        }
      }
    }
  }

  /**
   * Returns all matching cookies for an HTTP request to [url] (including `HttpOnly`), ordered
   * longest-path first, then earliest creation index.
   */
  fun cookiesFor(url: String): List<StoredCookie> = queryCookies(url, includeHttpOnly = true)

  /**
   * Returns matching non-`HttpOnly` cookies for script-facing consumers.
   */
  fun scriptCookiesFor(url: String): List<StoredCookie> = queryCookies(url, includeHttpOnly = false)

  /**
   * Formats the `Cookie` request header value for [url], or `null` if no cookies match.
   */
  fun cookieHeaderFor(url: String): String? {
    val matched = cookiesFor(url)
    if (matched.isEmpty()) return null
    return matched.joinToString("; ") { "${it.name}=${it.value}" }
  }

  /** Clears all stored cookies for this active task generation. */
  fun clear() {
    taskState.checkActive()
    synchronized(lock) {
      cookies.clear()
    }
  }

  internal fun clearOnClose() {
    synchronized(lock) {
      cookies.clear()
    }
  }

  private fun queryCookies(url: String, includeHttpOnly: Boolean): List<StoredCookie> {
    taskState.checkActive()
    val parsedUrl = CookieMatcher.parseUrl(url) ?: return emptyList()
    val nowMillis = clock()

    return synchronized(lock) {
      taskState.checkActive()
      val iterator = cookies.entries.iterator()
      val matched = ArrayList<StoredCookie>()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        val cookie = entry.value
        if (cookie.isExpired(nowMillis)) {
          iterator.remove()
          continue
        }
        if (CookieMatcher.matchesForRequest(cookie, parsedUrl, nowMillis, includeHttpOnly)) {
          matched.add(cookie)
        }
      }
      matched.sortWith(orderingComparator)
      matched
    }
  }
}
