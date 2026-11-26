package com.linroid.ketch.adm.http

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
) : HttpEngine {

  constructor(transport: HttpEngine) : this(RootResources(transport))

  private val activeTasks = ConcurrentHashMap<String, AdmTaskState>()
  private val generationSeq = AtomicLong(0L)
  private val rootClosed = AtomicBoolean(false)

  internal fun activeTaskState(taskId: String): AdmTaskState? = activeTasks[taskId]

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
        AdmTaskState(taskId = taskId, generationId = generationSeq.incrementAndGet())
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
    val state: AdmTaskState,
    private val externalGate: TaskLifecycleGate,
    private val ownsTaskState: Boolean,
  ) : HttpEngine {
    val generation: TaskGeneration get() = state.generation

    private val viewClosed = AtomicBoolean(false)

    private fun activeDelegate(): HttpEngine {
      if (viewClosed.get() || rootClosed.get() || rootResources.isClosed) {
        throw InactiveTaskEngineException(state.generation)
      }
      state.checkActive()
      externalGate.checkActive(state.generation)
      return delegate
    }

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      activeDelegate().head(url, headers)

    override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo =
      activeDelegate().probe(url, headers)

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      activeDelegate().download(url, range, headers, onData)
    }

    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String = activeDelegate().downloadResource(url, headers, onData)

    override suspend fun request(
      method: String,
      url: String,
      headers: Map<String, String>,
      body: HttpBody?,
      maxBodyBytes: Int,
    ): HttpReply = activeDelegate().request(method, url, headers, body, maxBodyBytes)

    override val supportsProxies: Boolean
      get() = activeDelegate().supportsProxies

    override fun forTask(
      taskId: String,
      generationId: Long,
      lifecycleGate: TaskLifecycleGate,
    ): HttpEngine {
      if (viewClosed.get() || rootClosed.get() || rootResources.isClosed) {
        throw InactiveTaskEngineException(state.generation)
      }
      state.checkActive()
      externalGate.checkActive(state.generation)
      val combinedGate = TaskLifecycleGate { gen ->
        externalGate.checkActive(gen)
        lifecycleGate.checkActive(gen)
      }
      if (taskId == state.taskId) {
        lifecycleGate.checkActive(state.generation)
        return TaskView(
          delegate = rootResources.transport,
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
      return TaskView(
        delegate = proxiedDelegate,
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
