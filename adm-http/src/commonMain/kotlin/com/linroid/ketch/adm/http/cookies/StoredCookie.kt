package com.linroid.ketch.adm.http.cookies

/**
 * Seam for checking whether a normalized lowercase domain is a public suffix (e.g. `com`, `co.uk`).
 *
 * Inject a real PSL-backed matcher in production; [NONE] rejects nothing by itself.
 */
fun interface PublicSuffixMatcher {
  fun isPublicSuffix(domain: String): Boolean

  companion object {
    val NONE: PublicSuffixMatcher = PublicSuffixMatcher { false }
  }
}

/** Unique storage key for RFC-6265 cookie replacement: `(name, domain, path)`. */
data class CookieKey(
  val name: String,
  val domain: String,
  val path: String,
)

/**
 * An RFC-6265 cookie stored in a [TaskCookieJar].
 *
 * @property domain normalized lowercase domain without a leading dot
 * @property hostOnly `true` if the `Set-Cookie` header omitted `Domain`
 * @property path normalized request path starting with `/`
 * @property expiresAtMillis epoch millis when the cookie expires, or `null` for session cookies
 * @property createdAtOrder monotonic creation index used for deterministic `Cookie` ordering
 */
data class StoredCookie(
  val name: String,
  val value: String,
  val domain: String,
  val hostOnly: Boolean,
  val path: String,
  val expiresAtMillis: Long?,
  val secure: Boolean,
  val httpOnly: Boolean,
  val createdAtOrder: Long,
) {
  val key: CookieKey
    get() = CookieKey(name = name, domain = domain, path = path)

  fun isExpired(nowMillis: Long): Boolean =
    expiresAtMillis != null && nowMillis >= expiresAtMillis
}
