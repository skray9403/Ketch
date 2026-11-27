package com.linroid.ketch.adm.http.cookies

import java.net.InetAddress
import java.net.URI

/** Origin tuple used for cross-origin redirect checks. */
data class HttpOrigin(
  val scheme: String,
  val host: String,
  val effectivePort: Int,
)

/** Parsed components of an HTTP(S) URL. */
internal data class ParsedUrl(
  val scheme: String,
  val host: String,
  val explicitPort: Int?,
  val effectivePort: Int,
  val path: String,
) {
  val origin: HttpOrigin
    get() = HttpOrigin(scheme = scheme, host = host, effectivePort = effectivePort)

  val isHttps: Boolean
    get() = scheme == "https"
}

/**
 * RFC-6265 URL, domain, and path matching helpers.
 */
internal object CookieMatcher {

  private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

  fun parseUrl(rawUrl: String): ParsedUrl? {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val rawHost = uri.host ?: return null
    val host = rawHost.removePrefix("[").removeSuffix("]").lowercase()
    if (host.isEmpty()) return null

    val explicitPort = uri.port.takeIf { it > 0 }
    val effectivePort = explicitPort ?: defaultPort(scheme)
    val rawPath = uri.rawPath.orEmpty()
    val path = if (rawPath.isEmpty()) "/" else rawPath
    return ParsedUrl(
      scheme = scheme,
      host = host,
      explicitPort = explicitPort,
      effectivePort = effectivePort,
      path = path,
    )
  }

  fun defaultPort(scheme: String): Int = when (scheme.lowercase()) {
    "http" -> 80
    "https" -> 443
    else -> -1
  }

  /**
   * RFC-6265 Section 5.1.4 default-path calculation.
   */
  fun defaultPath(requestPath: String): String {
    if (requestPath.isEmpty() || !requestPath.startsWith('/')) return "/"
    val lastSlash = requestPath.lastIndexOf('/')
    if (lastSlash <= 0) return "/"
    return requestPath.substring(0, lastSlash)
  }

  /**
   * Returns `true` if [host] is an IPv4 or IPv6 literal.
   */
  fun isIpLiteral(host: String): Boolean {
    val clean = host.removePrefix("[").removeSuffix("]")
    if (clean.isEmpty()) return false
    if (':' in clean) return true // IPv6 literal
    if (IPV4_REGEX.matches(clean)) {
      val parts = clean.split('.')
      return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }
    return runCatching {
      val addr = InetAddress.getByName(clean)
      addr.hostAddress == clean
    }.getOrDefault(false)
  }

  /**
   * Validates a normalized `Domain` attribute value (leading dot already stripped, lowercased).
   */
  fun isValidDomainAttribute(domain: String): Boolean {
    if (domain.isEmpty()) return false
    if (isIpLiteral(domain)) return false
    if (domain.startsWith('.') || domain.endsWith('.')) return false
    if (".." in domain) return false
    val labels = domain.split('.')
    return labels.all { label ->
      label.isNotEmpty() &&
        label.length <= 63 &&
        !label.startsWith('-') &&
        !label.endsWith('-') &&
        label.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' }
    }
  }

  /**
   * RFC-6265 Section 5.1.3 domain-match.
   */
  fun domainMatches(requestHost: String, cookieDomain: String): Boolean {
    if (requestHost == cookieDomain) return true
    if (requestHost.endsWith(".$cookieDomain") && !isIpLiteral(requestHost)) {
      return true
    }
    return false
  }

  /**
   * RFC-6265 Section 5.1.4 path-match (`/app` matches `/app` and `/app/x`, never `/apple`).
   */
  fun pathMatches(requestPath: String, cookiePath: String): Boolean {
    if (requestPath == cookiePath) return true
    if (!requestPath.startsWith(cookiePath)) return false
    if (cookiePath.endsWith('/')) return true
    return requestPath.length > cookiePath.length && requestPath[cookiePath.length] == '/'
  }

  fun matchesForRequest(
    cookie: StoredCookie,
    url: ParsedUrl,
    nowMillis: Long,
    includeHttpOnly: Boolean,
  ): Boolean {
    if (cookie.isExpired(nowMillis)) return false
    if (cookie.secure && !url.isHttps) return false
    if (!includeHttpOnly && cookie.httpOnly) return false

    val hostMatches = if (cookie.hostOnly) {
      url.host == cookie.domain
    } else {
      domainMatches(url.host, cookie.domain)
    }
    if (!hostMatches) return false

    return pathMatches(url.path, cookie.path)
  }
}
