package com.linroid.ketch.adm.http

import com.linroid.ketch.core.engine.HttpEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sole owner of the shared root [HttpEngine] transport.
 *
 * Closing [RootResources] closes the underlying [transport] at most once, safely under concurrent
 * close calls.
 */
class RootResources(
  val transport: HttpEngine,
) {
  private val closed = AtomicBoolean(false)

  val isClosed: Boolean get() = closed.get()

  /** Closes [transport] exactly once; subsequent calls are no-ops. */
  fun close() {
    if (closed.compareAndSet(false, true)) {
      transport.close()
    }
  }
}
