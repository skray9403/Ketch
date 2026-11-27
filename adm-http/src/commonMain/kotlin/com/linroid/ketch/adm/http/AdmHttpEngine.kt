package com.linroid.ketch.adm.http

import com.linroid.ketch.adm.http.cookies.PublicSuffixMatcher
import com.linroid.ketch.adm.http.cookies.TaskCookieJar
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.core.engine.HttpBody
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.HttpReply
import com.linroid.ketch.core.engine.InactiveTaskEngineException
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.TaskGeneration
import com.linroid.ketch.core.engine.TaskLifecycleGate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Task-scoped [HttpEngine] backed by shared [RootResources] and per-task [AdmTaskState]s.
 *
 * Owns [rootResources] and the active task registry. Task views returned by [forTask] and proxy
 * views returned by [withProxy] are borrowed views that never close [rootResources].
 */
class AdmHttpEngine(
  private val rootResources: RootResources,
  private val redirectConfig: AdmRedirectConfig = AdmRedirectConfig(),
  private val clock: () -> Long = System::currentTimeMillis,
  private val publicSuffixMatcher: PublicSuffixMatcher = PublicSuffixMatcher.NONE,
) : HttpEngine {

  constructor(
    transport: HttpEngine,
    redirectConfig: AdmRedirectConfig = AdmRedirectConfig(),
    clock: () -> Long = System::currentTimeMillis,
    publicSuffixMatcher: PublicSuffixMatcher = PublicSuffixMatcher.NONE,
  ) : this(
    rootResources = RootResources(transport = transport),
    redirectConfig = redirectConfig,
    clock = clock,
    publicSuffixMatcher = publicSuffixMatcher,
  )

  constructor(
    admTransport: AdmHttpTransport,
    redirectConfig: AdmRedirectConfig = AdmRedirectConfig(),
    clock: () -> Long = System::currentTimeMillis,
    publicSuffixMatcher: PublicSuffixMatcher = PublicSuffixMatcher.NONE,
  ) : this(
    rootResources = RootResources(
      transport = AdmTransportHttpEngineBridge(admTransport),
      admTransport = admTransport,
    ),
    redirectConfig = redirectConfig,
    clock = clock,
    publicSuffixMatcher = publicSuffixMatcher,
  )

  private val activeTasks = ConcurrentHashMap<String, AdmTaskState>()
  private val generationSeq = AtomicLong(0L)
  private val rootClosed = AtomicBoolean(false)

  internal fun activeTaskState(taskId: String): AdmTaskState? = activeTasks[taskId]

  internal fun taskCookies(taskId: String): TaskCookieJar? = activeTasks[taskId]?.cookies

  private fun activeTransport(): HttpEngine {
    if (rootClosed.get() || rootResources.isClosed) {
      throw IllegalStateException("AdmHttpEngine is closed")
    }
    return rootResources.transport
  }

  private fun getOrCreateActiveTaskState(taskId: String): AdmTaskState {
    if (rootClosed.get() || rootResources.isClosed) {
      throw InactiveTaskEngineException(TaskGeneration(taskId, 0L))
    }
    val state = activeTasks.compute(taskId) { _, existing ->
      if (existing != null && !existing.isClosed) {
        existing
      } else {
        AdmTaskState(
          taskId = taskId,
          generationId = generationSeq.incrementAndGet(),
          clock = clock,
          publicSuffixMatcher = publicSuffixMatcher,
          redirectConfig = redirectConfig,
        )
      }
    }!!
    if (rootClosed.get() || rootResources.isClosed) {
      activeTasks.remove(taskId, state)
      state.close()
      throw InactiveTaskEngineException(state.generation)
    }
    return state
  }

  override fun forTask(
    taskId: String,
    generationId: Long,
    lifecycleGate: TaskLifecycleGate,
  ): HttpEngine {
    val state = getOrCreateActiveTaskState(taskId)
    lifecycleGate.checkActive(state.generation)
    return TaskView(
      delegate = rootResources.transport,
      admDelegate = rootResources.admTransport,
      state = state,
      externalGate = lifecycleGate,
      ownsTaskState = true,
    )
  }

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
    activeTransport().head(url, headers)

  override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo =
    activeTransport().probe(url, headers)

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    activeTransport().download(url, range, headers, onData)
  }

  override suspend fun downloadResource(
    url: String,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ): String = activeTransport().downloadResource(url, headers, onData)

  override suspend fun request(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: HttpBody?,
    maxBodyBytes: Int,
  ): HttpReply = activeTransport().request(method, url, headers, body, maxBodyBytes)

  override val supportsProxies: Boolean
    get() = activeTransport().supportsProxies

  override fun withProxy(proxy: Proxy): HttpEngine {
    val transport = activeTransport()
    if (proxy == Proxy.System) return this
    return RootProxyView(transport.withProxy(proxy))
  }

  override fun close() {
    if (!rootClosed.compareAndSet(false, true)) return
    val snapshot = activeTasks.values.toList()
    activeTasks.clear()
    for (state in snapshot) {
      state.close()
    }
    rootResources.close()
  }

  private inner class RootProxyView(
    private val proxiedTransport: HttpEngine,
  ) : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      activeTransport()
      return proxiedTransport.head(url, headers)
    }

    override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo {
      activeTransport()
      return proxiedTransport.probe(url, headers)
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      activeTransport()
      proxiedTransport.download(url, range, headers, onData)
    }

    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String {
      activeTransport()
      return proxiedTransport.downloadResource(url, headers, onData)
    }

    override suspend fun request(
      method: String,
      url: String,
      headers: Map<String, String>,
      body: HttpBody?,
      maxBodyBytes: Int,
    ): HttpReply {
      activeTransport()
      return proxiedTransport.request(method, url, headers, body, maxBodyBytes)
    }

    override val supportsProxies: Boolean
      get() = activeTransport().let { proxiedTransport.supportsProxies }

    override fun withProxy(proxy: Proxy): HttpEngine = this@AdmHttpEngine.withProxy(proxy)

    override fun forTask(
      taskId: String,
      generationId: Long,
      lifecycleGate: TaskLifecycleGate,
    ): HttpEngine = this@AdmHttpEngine.forTask(taskId, generationId, lifecycleGate)

    override fun close() {}
  }

  internal inner class TaskView(
    private val delegate: HttpEngine,
    private val admDelegate: AdmHttpTransport?,
    val state: AdmTaskState,
    private val externalGate: TaskLifecycleGate,
    private val ownsTaskState: Boolean,
  ) : HttpEngine {
    val generation: TaskGeneration get() = state.generation

    private val viewClosed = AtomicBoolean(false)

    private fun checkActiveView() {
      if (viewClosed.get() || rootClosed.get() || rootResources.isClosed) {
        throw InactiveTaskEngineException(state.generation)
      }
      state.checkActive()
      externalGate.checkActive(state.generation)
    }

    private fun activeDelegate(): HttpEngine {
      checkActiveView()
      return delegate
    }

    private fun activeAdmTransport(): AdmHttpTransport? {
      checkActiveView()
      return admDelegate
    }

    private fun withJarCookie(url: String, headers: Map<String, String>): Map<String, String> {
      val hasCallerCookie = headers.keys.any { it.equals("cookie", ignoreCase = true) }
      if (hasCallerCookie) return headers
      val jarCookie = state.cookies.cookieHeaderFor(url) ?: return headers
      return headers + ("Cookie" to jarCookie)
    }

    internal suspend fun executeAdm(request: AdmRequest): AdmRedirectResult {
      val transport = activeAdmTransport() ?: HttpEngineAdmTransport(activeDelegate())
      return state.redirectHandler.execute(request, transport)
    }

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      val adm = activeAdmTransport()
      if (adm != null) {
        val result = state.redirectHandler.execute(
          AdmRequest(method = "HEAD", url = url, headers = headers.toList()),
          adm,
        )
        return result.response.serverInfo ?: ServerInfo(
          contentLength = result.response.header("content-length")?.toLongOrNull() ?: -1L,
          acceptRanges = result.response.header("accept-ranges")?.equals("bytes", ignoreCase = true) == true,
          etag = result.response.header("etag"),
          lastModified = result.response.header("last-modified"),
          contentDisposition = result.response.header("content-disposition"),
        )
      }
      val effectiveHeaders = withJarCookie(url, headers)
      return activeDelegate().head(url, effectiveHeaders)
    }

    override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo {
      val effectiveHeaders = withJarCookie(url, headers)
      return activeDelegate().probe(url, effectiveHeaders)
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      val adm = activeAdmTransport()
      if (adm != null) {
        state.redirectHandler.stream(
          AdmRequest(method = "GET", url = url, headers = headers.toList()),
          range,
          adm,
          onData,
        )
        return
      }
      val effectiveHeaders = withJarCookie(url, headers)
      activeDelegate().download(url, range, effectiveHeaders, onData)
    }

    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String {
      val adm = activeAdmTransport()
      if (adm != null) {
        val result = state.redirectHandler.stream(
          AdmRequest(method = "GET", url = url, headers = headers.toList()),
          null,
          adm,
          onData,
        )
        return result.finalUrl
      }
      val effectiveHeaders = withJarCookie(url, headers)
      return activeDelegate().downloadResource(url, effectiveHeaders, onData)
    }

    override suspend fun request(
      method: String,
      url: String,
      headers: Map<String, String>,
      body: HttpBody?,
      maxBodyBytes: Int,
    ): HttpReply {
      val adm = activeAdmTransport()
      if (adm != null) {
        val result = state.redirectHandler.execute(
          AdmRequest(
            method = method,
            url = url,
            headers = headers.toList(),
            body = body?.toAdmBody(),
            maxBodyBytes = maxBodyBytes,
          ),
          adm,
        )
        return result.response.toHttpReply(result.finalUrl)
      }
      val effectiveHeaders = withJarCookie(url, headers)
      val reply = activeDelegate().request(method, url, effectiveHeaders, body, maxBodyBytes)
      val setCookies = reply.headers("set-cookie")
      if (setCookies.isNotEmpty()) {
        state.cookies.store(reply.url ?: url, setCookies)
      }
      return reply
    }

    override val supportsProxies: Boolean
      get() = activeDelegate().supportsProxies

    override fun forTask(
      taskId: String,
      generationId: Long,
      lifecycleGate: TaskLifecycleGate,
    ): HttpEngine {
      checkActiveView()
      val combinedGate = TaskLifecycleGate { gen ->
        externalGate.checkActive(gen)
        lifecycleGate.checkActive(gen)
      }
      if (taskId == state.taskId) {
        lifecycleGate.checkActive(state.generation)
        return TaskView(
          delegate = rootResources.transport,
          admDelegate = rootResources.admTransport,
          state = state,
          externalGate = combinedGate,
          ownsTaskState = true,
        )
      }
      return this@AdmHttpEngine.forTask(taskId, generationId, lifecycleGate)
    }

    override fun withProxy(proxy: Proxy): HttpEngine {
      val active = activeDelegate()
      if (proxy == Proxy.System) {
        return this
      }
      val proxiedDelegate = active.withProxy(proxy)
      val proxiedAdm = admDelegate?.withProxy(proxy)
      return TaskView(
        delegate = proxiedDelegate,
        admDelegate = proxiedAdm,
        state = state,
        externalGate = externalGate,
        ownsTaskState = false,
      )
    }

    override fun close() {
      if (!viewClosed.compareAndSet(false, true)) return
      if (ownsTaskState) {
        state.close()
        activeTasks.remove(state.taskId, state)
      }
    }
  }
}

/**
 * Minimal [HttpEngine] adapter backed by an [AdmHttpTransport] for root-level calls.
 */
private class AdmTransportHttpEngineBridge(
  private val admTransport: AdmHttpTransport,
) : HttpEngine {

  override val supportsProxies: Boolean
    get() = admTransport.supportsProxies

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
    val resp = admTransport.execute(AdmRequest(method = "HEAD", url = url, headers = headers.toList()))
    return resp.serverInfo ?: ServerInfo(
      contentLength = resp.header("content-length")?.toLongOrNull() ?: -1L,
      acceptRanges = resp.header("accept-ranges")?.equals("bytes", ignoreCase = true) == true,
      etag = resp.header("etag"),
      lastModified = resp.header("last-modified"),
      contentDisposition = resp.header("content-disposition"),
    )
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    admTransport.stream(AdmRequest(method = "GET", url = url, headers = headers.toList()), range, onData)
  }

  override suspend fun downloadResource(
    url: String,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ): String {
    val resp = admTransport.stream(AdmRequest(method = "GET", url = url, headers = headers.toList()), null, onData)
    return resp.url ?: url
  }

  override suspend fun request(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: HttpBody?,
    maxBodyBytes: Int,
  ): HttpReply {
    val resp = admTransport.execute(
      AdmRequest(
        method = method,
        url = url,
        headers = headers.toList(),
        body = body?.toAdmBody(),
        maxBodyBytes = maxBodyBytes,
      ),
    )
    return resp.toHttpReply(resp.url ?: url)
  }

  override fun withProxy(proxy: Proxy): HttpEngine =
    if (proxy == Proxy.System) this else AdmTransportHttpEngineBridge(admTransport.withProxy(proxy))

  override fun close() {
    admTransport.close()
  }
}
