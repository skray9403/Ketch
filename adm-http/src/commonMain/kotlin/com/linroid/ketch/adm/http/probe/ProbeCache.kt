package com.linroid.ketch.adm.http.probe

import com.linroid.ketch.adm.http.AdmTaskState
import com.linroid.ketch.adm.http.cookies.CookieMatcher
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-request lookup key for [ProbeCache].
 *
 * Contains only pre-request dimensions (never response-derived `finalUrl`, `ETag`, `Last-Modified`,
 * or `Content-Encoding`), and stores SHA-256 digests instead of raw cookies or auth credentials.
 */
data class ProbeCacheKey(
  val taskId: String,
  val generationId: Long,
  val normalizedUrl: String,
  val method: String,
  val representationHeaders: List<Pair<String, String>>,
  val cookieDigestSha256: String,
  val authDigestSha256: String,
  val ruleVersion: Long,
) {
  override fun toString(): String =
    "ProbeCacheKey(taskId=$taskId, gen=$generationId, method=$method, url=$normalizedUrl, " +
      "repHeaders=$representationHeaders, cookieSha256=$cookieDigestSha256, " +
      "authSha256=$authDigestSha256, ruleVersion=$ruleVersion)"
}

/**
 * Metadata-only cache entry stored in [ProbeCache].
 */
data class ProbeCacheEntry(
  val metadata: ProbeMetadata,
  val createdAtMs: Long,
  val expiresAtMs: Long,
)

/**
 * Per-task metadata-only cache for range probe results.
 *
 * Bound to [AdmTaskState] and cleared on task close. Never stores response body streams or
 * prefix-replay handles.
 */
class ProbeCache internal constructor(
  private val taskState: AdmTaskState,
  private val clock: () -> Long = System::currentTimeMillis,
  private val ruleVersionProvider: () -> Long = { 0L },
) {
  private val entries = ConcurrentHashMap<ProbeCacheKey, ProbeCacheEntry>()

  fun buildKey(
    url: String,
    method: String,
    requestHeaders: List<Pair<String, String>>,
    effectiveCookieHeader: String?,
  ): ProbeCacheKey {
    val normalizedUrl = normalizeUrl(url)
    val normalizedMethod = method.trim().uppercase()
    val repHeaders = normalizeRepresentationHeaders(requestHeaders)
    val cookieDigest = if (effectiveCookieHeader.isNullOrEmpty()) "" else sha256Hex(effectiveCookieHeader)
    val authDigest = computeAuthDigest(requestHeaders)
    return ProbeCacheKey(
      taskId = taskState.taskId,
      generationId = taskState.generationId,
      normalizedUrl = normalizedUrl,
      method = normalizedMethod,
      representationHeaders = repHeaders,
      cookieDigestSha256 = cookieDigest,
      authDigestSha256 = authDigest,
      ruleVersion = ruleVersionProvider(),
    )
  }

  fun get(
    key: ProbeCacheKey,
    validators: ProbeValidators? = null,
  ): ProbeMetadata? {
    taskState.checkActive()
    val entry = entries[key] ?: return null
    val now = clock()
    if (now >= entry.expiresAtMs) {
      entries.remove(key, entry)
      return null
    }
    if (validators != null && !isCompatible(entry.metadata, validators)) {
      entries.remove(key, entry)
      return null
    }
    return entry.metadata
  }

  fun put(
    key: ProbeCacheKey,
    metadata: ProbeMetadata,
    ttlMs: Long,
  ) {
    taskState.checkActive()
    if (ttlMs <= 0L) return
    val now = clock()
    val expiresAt = if (ttlMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + ttlMs
    entries[key] = ProbeCacheEntry(
      metadata = metadata,
      createdAtMs = now,
      expiresAtMs = expiresAt,
    )
  }

  fun invalidate(key: ProbeCacheKey) {
    taskState.checkActive()
    entries.remove(key)
  }

  fun clear() {
    taskState.checkActive()
    entries.clear()
  }

  internal fun clearOnClose() {
    entries.clear()
  }

  internal fun size(): Int = entries.size

  private fun isCompatible(metadata: ProbeMetadata, expected: ProbeValidators): Boolean {
    if (expected.etag != null && metadata.etag != expected.etag) return false
    if (expected.lastModified != null && metadata.lastModified != expected.lastModified) return false
    if (expected.contentEncoding != null &&
      !metadata.contentEncoding.equals(expected.contentEncoding, ignoreCase = true)
    ) {
      return false
    }
    return true
  }

  companion object {
    private val REPRESENTATION_HEADERS = setOf(
      "accept",
      "accept-encoding",
      "accept-language",
      "range",
    )

    internal fun normalizeUrl(rawUrl: String): String {
      val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return rawUrl.trim()
      val scheme = uri.scheme?.lowercase() ?: return rawUrl.trim()
      val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return rawUrl.trim()
      val explicitPort = uri.port.takeIf { it > 0 }
      val effectivePort = explicitPort ?: CookieMatcher.defaultPort(scheme)
      val path = uri.rawPath.orEmpty().ifEmpty { "/" }
      val query = uri.rawQuery?.let { "?$it" }.orEmpty()
      val formattedHost = if (':' in host) "[$host]" else host
      val portSuffix = if (effectivePort > 0) ":$effectivePort" else ""
      return "$scheme://$formattedHost$portSuffix$path$query"
    }

    internal fun normalizeRepresentationHeaders(
      headers: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
      if (headers.isEmpty()) return emptyList()
      return headers
        .mapNotNull { (k, v) ->
          val lower = k.trim().lowercase()
          if (lower in REPRESENTATION_HEADERS) lower to v.trim() else null
        }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
    }

    internal fun computeAuthDigest(headers: List<Pair<String, String>>): String {
      val authPairs = headers
        .mapNotNull { (k, v) ->
          val lower = k.trim().lowercase()
          if (lower == "authorization" || lower == "proxy-authorization") {
            "$lower:${v.trim()}"
          } else {
            null
          }
        }
        .sorted()
      if (authPairs.isEmpty()) return ""
      return sha256Hex(authPairs.joinToString("\n"))
    }

    internal fun sha256Hex(input: String): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
      val sb = StringBuilder(digest.size * 2)
      for (b in digest) {
        val v = b.toInt() and 0xFF
        if (v < 0x10) sb.append('0')
        sb.append(v.toString(16))
      }
      return sb.toString()
    }
  }
}
