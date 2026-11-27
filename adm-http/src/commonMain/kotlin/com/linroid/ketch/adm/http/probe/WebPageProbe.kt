package com.linroid.ketch.adm.http.probe

import com.linroid.ketch.adm.http.AdmResponseBody
import com.linroid.ketch.adm.http.AdmTaskState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Result of consuming a [WebPageProbe] once: metadata plus the complete `prefix + remainder` body.
 */
data class WebPageAnswer(
  val metadata: ProbeMetadata,
  val body: ByteArray,
)

/**
 * One-shot prefix+remainder handoff for a range-ignored `200 OK` probe response.
 *
 * Enforces an atomic `READY -> CONSUMING -> CONSUMED/CLOSED` state machine bound to [taskState]
 * so the underlying [remainder] stream is read at most once and the sniffed [prefix] is never
 * double-prepended or leaked across task close/cancellation.
 */
class WebPageProbe internal constructor(
  val metadata: ProbeMetadata,
  private val taskState: AdmTaskState,
  prefix: ByteArray,
  private val remainder: AdmResponseBody,
) {
  enum class State {
    READY,
    CONSUMING,
    CONSUMED,
    CLOSED,
  }

  private val state = AtomicReference(State.READY)
  private var prefixBytes: ByteArray? = prefix.copyOf()

  val currentState: State get() = state.get()

  init {
    taskState.onClose { close() }
  }

  /**
   * Streams `prefix + remainder` exactly once to [onChunk].
   *
   * Throws [IllegalStateException] if already consumed or closed, and throws
   * [com.linroid.ketch.core.engine.InactiveTaskEngineException] if [taskState] is closed.
   */
  suspend fun consume(onChunk: suspend (ByteArray) -> Unit) {
    taskState.checkActive()
    if (!state.compareAndSet(State.READY, State.CONSUMING)) {
      throw IllegalStateException("WebPageProbe already ${state.get().name.lowercase()}")
    }

    try {
      val initialPrefix = prefixBytes
      prefixBytes = null
      if (initialPrefix != null && initialPrefix.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        taskState.checkActive()
        onChunk(initialPrefix)
      }

      while (true) {
        currentCoroutineContext().ensureActive()
        taskState.checkActive()
        val chunk = remainder.readChunk() ?: break
        if (chunk.isNotEmpty()) {
          currentCoroutineContext().ensureActive()
          taskState.checkActive()
          onChunk(chunk)
        }
      }

      state.compareAndSet(State.CONSUMING, State.CONSUMED)
      remainder.close()
    } catch (t: Throwable) {
      close()
      throw t
    }
  }

  /** Consumes `prefix + remainder` into a [WebPageAnswer]. */
  suspend fun readAnswer(): WebPageAnswer {
    val out = ByteArrayOutputStream()
    consume { chunk -> out.write(chunk) }
    return WebPageAnswer(metadata = metadata, body = out.toByteArray())
  }

  /** Permanently closes the underlying stream and discards any unconsumed prefix. */
  fun close() {
    val prev = state.getAndSet(State.CLOSED)
    if (prev != State.CLOSED) {
      prefixBytes = null
      remainder.close()
    }
  }
}
