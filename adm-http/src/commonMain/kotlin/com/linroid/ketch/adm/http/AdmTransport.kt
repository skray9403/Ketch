package com.linroid.ketch.adm.http

import com.linroid.ketch.api.Proxy
import com.linroid.ketch.core.engine.HttpBody
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.HttpReply
import com.linroid.ketch.core.engine.ServerInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Request body metadata for [AdmRequest], distinguishing replayable bodies from one-shot bodies
 * that cannot be re-sent across `307`/`308` redirects.
 */
sealed interface AdmRequestBody {
  val contentType: String?
  val isReplayable: Boolean

  class Replayable(
    val bytes: ByteArray,
    override val contentType: String? = null,
  ) : AdmRequestBody {
    override val isReplayable: Boolean = true
  }

  class OneShot(
    override val contentType: String? = null,
    private val reader: suspend () -> ByteArray,
  ) : AdmRequestBody {
    override val isReplayable: Boolean = false

    suspend fun readBytes(): ByteArray = reader()
  }
}

fun HttpBody.toAdmBody(): AdmRequestBody = AdmRequestBody.Replayable(bytes = bytes, contentType = contentType)

/**
 * Pull-based streaming response body so [com.linroid.ketch.adm.http.probe.AdmProbe] can inspect
 * up to `maxPrefixBytes` without buffering the entire response in memory.
 */
interface AdmResponseBody {
  /**
   * Reads the next non-empty chunk of up to [maxBytes] bytes, or returns `null` on EOF.
   */
  suspend fun readChunk(maxBytes: Int = DEFAULT_CHUNK_SIZE): ByteArray?

  /** Closes the underlying stream. Idempotent. */
  fun close()

  companion object {
    const val DEFAULT_CHUNK_SIZE: Int = 8192

    fun fromBytes(bytes: ByteArray): AdmResponseBody = ByteArrayResponseBody(bytes)

    fun fromChunks(chunks: List<ByteArray>, onClose: () -> Unit = {}): AdmResponseBody =
      ChunkListResponseBody(chunks, onClose)
  }
}

private class ByteArrayResponseBody(
  private val bytes: ByteArray,
) : AdmResponseBody {
  private var offset = 0
  private val closed = AtomicBoolean(false)

  override suspend fun readChunk(maxBytes: Int): ByteArray? {
    if (closed.get() || offset >= bytes.size) return null
    val count = minOf(maxBytes, bytes.size - offset).coerceAtLeast(1)
    val slice = bytes.copyOfRange(offset, offset + count)
    offset += count
    return slice
  }

  override fun close() {
    closed.set(true)
  }
}

private class ChunkListResponseBody(
  chunks: List<ByteArray>,
  private val onClose: () -> Unit,
) : AdmResponseBody {
  private val queue = ArrayDeque(chunks.filter { it.isNotEmpty() }.map { it.copyOf() })
  private val closed = AtomicBoolean(false)

  override suspend fun readChunk(maxBytes: Int): ByteArray? {
    if (closed.get()) return null
    val head = queue.removeFirstOrNull() ?: return null
    if (head.size <= maxBytes) {
      return head
    }
    val prefix = head.copyOfRange(0, maxBytes)
    val rest = head.copyOfRange(maxBytes, head.size)
    queue.addFirst(rest)
    return prefix
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      queue.clear()
      onClose()
    }
  }
}

/**
 * Multi-header HTTP request used inside `:adm-http` so headers like `Cookie` or custom headers
 * are preserved without comma-joining.
 */
data class AdmRequest(
  val method: String,
  val url: String,
  val headers: List<Pair<String, String>> = emptyList(),
  val body: AdmRequestBody? = null,
  val maxBodyBytes: Int = HttpReply.DEFAULT_MAX_BODY_BYTES,
) {
  fun header(name: String): String? =
    headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

  fun headers(name: String): List<String> =
    headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

  fun withoutHeaders(vararg names: String): AdmRequest =
    copy(headers = headers.filterNot { (k, _) -> names.any { k.equals(it, ignoreCase = true) } })

  fun withHeader(name: String, value: String): AdmRequest =
    withoutHeaders(name).let { stripped -> stripped.copy(headers = stripped.headers + (name to value)) }

  fun toHeaderMap(): Map<String, String> {
    if (headers.isEmpty()) return emptyMap()
    val map = LinkedHashMap<String, String>(headers.size)
    for ((k, v) in headers) {
      map[k] = v
    }
    return map
  }
}

/**
 * Multi-header HTTP response observed by [AdmRedirectHandler] before any redirect is followed.
 *
 * [headers] maps lowercase header names to ordered lists of values, preserving multiple
 * `Set-Cookie` headers without comma-joining.
 */
data class AdmResponse(
  val status: Int,
  val headers: Map<String, List<String>> = emptyMap(),
  val body: ByteArray = ByteArray(0),
  val url: String? = null,
  val serverInfo: ServerInfo? = null,
  val streamingBody: AdmResponseBody? = null,
) {
  val isRedirect: Boolean
    get() = status == 301 || status == 302 || status == 303 || status == 307 || status == 308

  fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

  fun headers(name: String): List<String> = headers[name.lowercase()].orEmpty()

  fun openBody(): AdmResponseBody = streamingBody ?: AdmResponseBody.fromBytes(body)

  fun toHttpReply(finalUrl: String? = url): HttpReply = HttpReply(
    status = status,
    headers = headers,
    body = body,
    url = finalUrl,
  )

  companion object {
    fun normalizeHeaders(pairs: List<Pair<String, String>>): Map<String, List<String>> {
      if (pairs.isEmpty()) return emptyMap()
      val grouped = LinkedHashMap<String, MutableList<String>>()
      for ((name, value) in pairs) {
        grouped.getOrPut(name.lowercase()) { ArrayList(1) }.add(value)
      }
      return grouped
    }
  }
}

/**
 * Low-level HTTP transport seam for `:adm-http` that returns raw `3xx` responses and
 * multi-valued headers without performing its own redirect handling.
 */
interface AdmHttpTransport {
  suspend fun execute(request: AdmRequest): AdmResponse

  /**
   * Opens a streaming response without buffering the entire body.
   *
   * Implementations that support lazy streaming should return an [AdmResponse] with
   * [AdmResponse.streamingBody] populated. Defaults to [execute].
   */
  suspend fun openStream(request: AdmRequest): AdmResponse = execute(request)

  /**
   * Streams a download response.
   *
   * If the server responds with a redirect (`301`, `302`, `303`, `307`, `308`), implementations
   * must return the [AdmResponse] without invoking [onData] so [AdmRedirectHandler] can follow it.
   */
  suspend fun stream(
    request: AdmRequest,
    range: LongRange?,
    onData: suspend (ByteArray) -> Unit,
  ): AdmResponse

  val supportsProxies: Boolean get() = false

  fun withProxy(proxy: Proxy): AdmHttpTransport {
    if (proxy == Proxy.System) return this
    throw UnsupportedOperationException("This AdmHttpTransport does not support proxies")
  }

  fun close() {}
}

/**
 * Bridges an [HttpEngine] to [AdmHttpTransport] for non-redirecting transport calls.
 */
internal class HttpEngineAdmTransport(
  private val engine: HttpEngine,
) : AdmHttpTransport {

  override val supportsProxies: Boolean
    get() = engine.supportsProxies

  override suspend fun execute(request: AdmRequest): AdmResponse {
    val headerMap = request.toHeaderMap()
    if (request.method.equals("HEAD", ignoreCase = true)) {
      val info = engine.head(request.url, headerMap)
      return AdmResponse(
        status = 200,
        headers = emptyMap(),
        body = ByteArray(0),
        url = request.url,
        serverInfo = info,
      )
    }
    val httpBody = when (val body = request.body) {
      null -> null
      is AdmRequestBody.Replayable -> HttpBody(body.bytes, body.contentType ?: "application/octet-stream")
      is AdmRequestBody.OneShot -> HttpBody(body.readBytes(), body.contentType ?: "application/octet-stream")
    }
    val reply = engine.request(
      method = request.method,
      url = request.url,
      headers = headerMap,
      body = httpBody,
      maxBodyBytes = request.maxBodyBytes,
    )
    return AdmResponse(
      status = reply.status,
      headers = reply.headers,
      body = reply.body,
      url = reply.url ?: request.url,
    )
  }

  override suspend fun stream(
    request: AdmRequest,
    range: LongRange?,
    onData: suspend (ByteArray) -> Unit,
  ): AdmResponse {
    val headerMap = request.toHeaderMap()
    val finalUrl = if (range == null) {
      engine.downloadResource(request.url, headerMap, onData)
    } else {
      engine.download(request.url, range, headerMap, onData)
      request.url
    }
    return AdmResponse(status = 200, url = finalUrl)
  }

  override fun withProxy(proxy: Proxy): AdmHttpTransport =
    if (proxy == Proxy.System) this else HttpEngineAdmTransport(engine.withProxy(proxy))

  override fun close() {
    engine.close()
  }
}
