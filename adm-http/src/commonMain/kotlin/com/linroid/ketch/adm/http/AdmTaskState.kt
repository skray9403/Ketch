package com.linroid.ketch.adm.http

import com.linroid.ketch.adm.http.cookies.PublicSuffixMatcher
import com.linroid.ketch.adm.http.cookies.TaskCookieJar
import com.linroid.ketch.adm.http.probe.AdmProbe
import com.linroid.ketch.adm.http.probe.AdmProbeConfig
import com.linroid.ketch.adm.http.probe.ProbeCache
import com.linroid.ketch.core.engine.InactiveTaskEngineException
import com.linroid.ketch.core.engine.TaskGeneration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-task execution state bound to a single [TaskGeneration].
 *
 * Owns the task-scoped [cookies] jar and [redirectHandler]. Closing one [AdmTaskState] is
 * irreversible and never invalidates a later generation created for the same [taskId].
 */
class AdmTaskState(
  val taskId: String,
  val generationId: Long,
  clock: () -> Long = System::currentTimeMillis,
  publicSuffixMatcher: PublicSuffixMatcher = PublicSuffixMatcher.NONE,
  redirectConfig: AdmRedirectConfig = AdmRedirectConfig(),
  probeConfig: AdmProbeConfig = AdmProbeConfig(),
  ruleVersionProvider: () -> Long = { 0L },
) {
  val generation: TaskGeneration = TaskGeneration(taskId = taskId, generationId = generationId)

  private val closed = AtomicBoolean(false)
  private val cleanupHooks = ConcurrentLinkedQueue<() -> Unit>()

  val cookies: TaskCookieJar = TaskCookieJar(
    taskState = this,
    clock = clock,
    publicSuffixMatcher = publicSuffixMatcher,
  )

  val redirectHandler: AdmRedirectHandler = AdmRedirectHandler(
    taskState = this,
    config = redirectConfig,
  )

  val probeCache: ProbeCache = ProbeCache(
    taskState = this,
    clock = clock,
    ruleVersionProvider = ruleVersionProvider,
  )

  val admProbe: AdmProbe = AdmProbe(
    taskState = this,
    config = probeConfig,
  )

  init {
    onClose {
      cookies.clearOnClose()
      probeCache.clearOnClose()
    }
  }

  val isClosed: Boolean get() = closed.get()

  /** Throws [InactiveTaskEngineException] if this task generation has been closed. */
  fun checkActive() {
    if (closed.get()) {
      throw InactiveTaskEngineException(generation)
    }
  }

  /**
   * Registers a task-local cleanup hook to run when this generation closes. Runs immediately if
   * already closed.
   */
  internal fun onClose(hook: () -> Unit) {
    if (closed.get()) {
      hook()
      return
    }
    cleanupHooks.add(hook)
    if (closed.get()) {
      drainCleanupHooks()
    }
  }

  /** Atomically marks this generation closed, clears task-local state, and is idempotent. */
  fun close() {
    if (closed.compareAndSet(false, true)) {
      drainCleanupHooks()
    }
  }

  private fun drainCleanupHooks() {
    while (true) {
      val hook = cleanupHooks.poll() ?: break
      hook()
    }
  }
}
