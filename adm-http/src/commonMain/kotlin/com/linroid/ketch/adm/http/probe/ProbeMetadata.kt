package com.linroid.ketch.adm.http.probe

import com.linroid.ketch.core.engine.ServerInfo

/**
 * Configuration for [AdmProbe] and [ProbeCache].
 */
data class AdmProbeConfig(
  val maxPrefixBytes: Int = 256,
  val allowAuthoritativeHead: Boolean = false,
  val cacheTtlMs: Long = 300_000L,
) {
  init {
    require(maxPrefixBytes > 0) { "maxPrefixBytes must be positive: $maxPrefixBytes" }
    require(cacheTtlMs >= 0L) { "cacheTtlMs must be non-negative: $cacheTtlMs" }
  }
}

/**
 * Optional validator expectations checked when reusing cached [ProbeMetadata].
 *
 * `ETag` and `Last-Modified` are never treated as interchangeable.
 */
data class ProbeValidators(
  val etag: String? = null,
  val lastModified: String? = null,
  val contentEncoding: String? = null,
)

/**
 * Metadata-only probe result stored in [ProbeCache]. Never holds a response stream or prefix
 * replay handle.
 */
data class ProbeMetadata(
  val finalUrl: String,
  val supportsRanges: Boolean,
  val totalBytes: Long?,
  val etag: String?,
  val lastModified: String?,
  val contentEncoding: String?,
  val contentType: String?,
  val contentDisposition: String?,
  val statusCode: Int,
) {
  fun toServerInfo(): ServerInfo = ServerInfo(
    contentLength = totalBytes ?: -1L,
    acceptRanges = supportsRanges,
    etag = etag,
    lastModified = lastModified,
    contentDisposition = contentDisposition,
  )
}

/**
 * Result of an [AdmProbe.probe] call.
 *
 * [webPageProbe] is non-null only on a fresh range-ignored `200 OK` response and is always `null`
 * on `206` responses or [ProbeCache] hits.
 */
data class AdmProbeResult(
  val metadata: ProbeMetadata,
  val fromCache: Boolean,
  val webPageProbe: WebPageProbe? = null,
) {
  val serverInfo: ServerInfo get() = metadata.toServerInfo()
}

/**
 * Thrown when a `206 Partial Content` probe response has malformed, non-zero-start, or
 * contradictory metadata/body length.
 */
class InvalidProbeResponseException(
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)
