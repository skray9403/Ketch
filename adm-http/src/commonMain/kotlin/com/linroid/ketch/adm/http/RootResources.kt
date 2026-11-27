package com.linroid.ketch.adm.http

import com.linroid.ketch.core.engine.HttpEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sole owner of the shared root [HttpEngine] (and optional [AdmHttpTransport]).
 *
 * Closing [RootResources] closes the underlying transport(s) at most once, safely under concurrent
 * close calls.
 */
class RootResources(
  val transport: HttpEngine,
  val admTransport: AdmHttpTransport? = null,
) {
  private val closed = AtomicBoolean(false)

  val isClosed: Boolean get() = closed.get()

  /** Closes underlying transport(s) exactly once; subsequent calls are no-ops. */
  fun close() {
    if (closed.compareAndSet(false, true)) {
      val firstResult = runCatching { transport.close() }
      val extraTransport = admTransport
      val secondResult = if (extraTransport != null) runCatching { extraTransport.close() } else null
      firstResult.getOrThrow()
      secondResult?.getOrThrow()
    }
  }
}
