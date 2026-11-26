package com.linroid.ketch.adm.http

import com.linroid.ketch.core.engine.InactiveTaskEngineException
import com.linroid.ketch.core.engine.TaskGeneration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-task execution state bound to a single [TaskGeneration].
 *
 * Closing one [AdmTaskState] is irreversible and never invalidates a later generation created for
 * the same [taskId].
 */
class AdmTaskState(
  val taskId: String,
  val generationId: Long,
) {
  val generation: TaskGeneration = TaskGeneration(taskId = taskId, generationId = generationId)

  private val closed = AtomicBoolean(false)
  private val cleanupHooks = ConcurrentLinkedQueue<() -> Unit>()

  val isClosed: Boolean get() = closed.get()

  /** Throws [InactiveTaskEngineException] if this task generation has been closed. */
  fun checkActive() {
    if (closed.get()) {
      throw InactiveTaskEngineException(generation)
    }
  }

  /**
   * Registers a task-local cleanup hook to run when this generation closes (placeholder for
   * per-task state in later slices). Runs immediately if already closed.
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
