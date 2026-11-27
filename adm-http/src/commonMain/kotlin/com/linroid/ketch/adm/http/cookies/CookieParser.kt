package com.linroid.ketch.adm.http.cookies

/**
 * RFC-6265 `Set-Cookie` header parser.
 */
internal object CookieParser {

  private val MONTHS = mapOf(
    "jan" to 1,
    "feb" to 2,
    "mar" to 3,
    "apr" to 4,
    "may" to 5,
    "jun" to 6,
    "jul" to 7,
    "aug" to 8,
    "sep" to 9,
    "oct" to 10,
    "nov" to 11,
    "dec" to 12,
  )

  private val DATE_TOKEN_DELIMITERS = Regex("""[\t \x21-\x2f\x3b-\x40\x5b-\x60\x7b-\x7e]+""")
  private val TIME_REGEX = Regex("""^(\d{1,2}):(\d{1,2}):(\d{1,2})(?:\D.*)?$""")
  private val DAY_REGEX = Regex("""^(\d{1,2})(?:\D.*)?$""")
  private val YEAR_REGEX = Regex("""^(\d{2,4})(?:\D.*)?$""")

  fun parse(
    setCookieHeader: String,
    requestUrl: ParsedUrl,
    nowMillis: Long,
    createdAtOrder: Long,
    publicSuffixMatcher: PublicSuffixMatcher,
  ): StoredCookie? {
    val segments = setCookieHeader.split(';')
    if (segments.isEmpty()) return null

    val nameValue = segments[0]
    val eqIdx = nameValue.indexOf('=')
    if (eqIdx <= 0) return null

    val name = nameValue.substring(0, eqIdx).trim()
    if (name.isEmpty()) return null
    val value = nameValue.substring(eqIdx + 1).trim()

    var domainAttr: String? = null
    var hasInvalidDomainAttr = false
    var pathAttr: String? = null
    var maxAgeMillis: Long? = null
    var hasMaxAge = false
    var expiresMillis: Long? = null
    var secure = false
    var httpOnly = false

    for (i in 1 until segments.size) {
      val attr = segments[i].trim()
      if (attr.isEmpty()) continue
      val attrEq = attr.indexOf('=')
      val attrName = (if (attrEq >= 0) attr.substring(0, attrEq) else attr).trim().lowercase()
      val attrVal = if (attrEq >= 0) attr.substring(attrEq + 1).trim() else ""

      when (attrName) {
        "domain" -> {
          val normalized = attrVal.removePrefix(".").lowercase()
          if (normalized.isEmpty() || !CookieMatcher.isValidDomainAttribute(normalized)) {
            hasInvalidDomainAttr = true
          } else {
            domainAttr = normalized
          }
        }
        "path" -> {
          if (attrVal.startsWith('/')) {
            pathAttr = attrVal
          }
        }
        "max-age" -> {
          val seconds = attrVal.toLongOrNull()
          if (seconds != null) {
            hasMaxAge = true
            maxAgeMillis = if (seconds <= 0L) {
              Long.MIN_VALUE
            } else if (seconds > (Long.MAX_VALUE - nowMillis) / 1000L) {
              Long.MAX_VALUE
            } else {
              nowMillis + seconds * 1000L
            }
          }
        }
        "expires" -> {
          val parsed = parseHttpDateMillis(attrVal)
          if (parsed != null) {
            expiresMillis = parsed
          }
        }
        "secure" -> secure = true
        "httponly" -> httpOnly = true
      }
    }

    if (hasInvalidDomainAttr) return null

    val hostOnly: Boolean
    val finalDomain: String
    if (domainAttr != null) {
      if (publicSuffixMatcher.isPublicSuffix(domainAttr)) return null
      if (!CookieMatcher.domainMatches(requestUrl.host, domainAttr)) return null
      hostOnly = false
      finalDomain = domainAttr
    } else {
      hostOnly = true
      finalDomain = requestUrl.host
    }

    val finalPath = pathAttr ?: CookieMatcher.defaultPath(requestUrl.path)
    // Max-Age takes precedence over Expires per RFC 6265 Section 5.3 step 3.
    val finalExpires = if (hasMaxAge) maxAgeMillis else expiresMillis

    return StoredCookie(
      name = name,
      value = value,
      domain = finalDomain,
      hostOnly = hostOnly,
      path = finalPath,
      expiresAtMillis = finalExpires,
      secure = secure,
      httpOnly = httpOnly,
      createdAtOrder = createdAtOrder,
    )
  }

  /**
   * RFC-6265 Section 5.1.1 HTTP-date parser returning UTC epoch millis, or `null` if invalid.
   */
  internal fun parseHttpDateMillis(input: String): Long? {
    val tokens = input.trim().split(DATE_TOKEN_DELIMITERS).filter { it.isNotEmpty() }
    var hour = -1
    var minute = -1
    var second = -1
    var day = -1
    var month = -1
    var year = -1

    for (token in tokens) {
      if (hour < 0) {
        val m = TIME_REGEX.matchEntire(token)
        if (m != null) {
          hour = m.groupValues[1].toIntOrNull() ?: return null
          minute = m.groupValues[2].toIntOrNull() ?: return null
          second = m.groupValues[3].toIntOrNull() ?: return null
          continue
        }
      }
      if (day < 0) {
        val m = DAY_REGEX.matchEntire(token)
        if (m != null) {
          val d = m.groupValues[1].toIntOrNull() ?: return null
          if (d in 1..31) {
            day = d
            continue
          }
        }
      }
      if (month < 0) {
        val mon = MONTHS[token.take(3).lowercase()]
        if (mon != null) {
          month = mon
          continue
        }
      }
      if (year < 0) {
        val m = YEAR_REGEX.matchEntire(token)
        if (m != null) {
          var y = m.groupValues[1].toIntOrNull() ?: return null
          if (y in 70..99) y += 1900 else if (y in 0..69) y += 2000
          year = y
          continue
        }
      }
    }

    if (day !in 1..31 || month !in 1..12 || year < 1601) return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    if (day > daysInMonth(year, month)) return null

    val epochDays = daysFromCivil(year, month, day)
    val seconds = epochDays * 86_400L + hour * 3_600L + minute * 60L + second
    return seconds * 1_000L
  }

  private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

  private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> 0
  }

  /**
   * Howard Hinnant's civil-to-days algorithm (days since 1970-01-01).
   */
  private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = year.toLong() - if (month <= 2) 1L else 0L
    val era = if (y >= 0L) y / 400L else (y - 399L) / 400L
    val yoe = y - era * 400L
    val m = month.toLong()
    val doy = (153L * (m + if (m > 2L) -3L else 9L) + 2L) / 5L + day - 1L
    val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
    return era * 146_097L + doe - 719_468L
  }
}
