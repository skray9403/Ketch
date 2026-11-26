package com.linroid.ketch.adm.http

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.UnsatisfiedRangeResponse
import com.linroid.ketch.core.engine.HttpBody
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.HttpReply
import com.linroid.ketch.core.engine.InactiveTaskEngineException
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AdmHttpEngineTest {

  private class RecordingTransport(
    var failureToThrow: Throwable? = null,
    val supportsProxyVal: Boolean = true,
  ) : HttpEngine {
    val closeCount = AtomicInteger(0)
    val headCount = AtomicInteger(0)
    val probeCount = AtomicInteger(0)
    val downloadCount = AtomicInteger(0)
    val downloadResourceCount = AtomicInteger(0)
    val requestCount = AtomicInteger(0)
    val recordedProxies: MutableList<Proxy> = Collections.synchronizedList(mutableListOf())

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      failureToThrow?.let { throw it }
      headCount.incrementAndGet()
      return ServerInfo(contentLength = 100L, acceptRanges = true, etag = null, lastModified = null, url = url)
    }

    override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo {
      failureToThrow?.let { throw it }
      probeCount.incrementAndGet()
      return ServerInfo(contentLength = 100L, acceptRanges = true, etag = null, lastModified = null, url = url)
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      failureToThrow?.let { throw it }
      downloadCount.incrementAndGet()
      onData(byteArrayOf(1, 2, 3))
    }

    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String {
      failureToThrow?.let { throw it }
      downloadResourceCount.incrementAndGet()
      onData(byteArrayOf(1, 2, 3))
      return url
    }

    override suspend fun request(
      method: String,
      url: String,
      headers: Map<String, String>,
      body: HttpBody?,
      maxBodyBytes: Int,
    ): HttpReply {
      failureToThrow?.let { throw it }
      requestCount.incrementAndGet()
      return HttpReply(status = 200, headers = emptyMap(), body = byteArrayOf())
    }

    override val supportsProxies: Boolean get() = supportsProxyVal

    override fun withProxy(proxy: Proxy): HttpEngine {
      if (!supportsProxyVal && proxy != Proxy.System) {
        throw KetchError.Unsupported(UnsupportedOperationException("No proxy"))
      }
      recordedProxies += proxy
      return object : HttpEngine by this {
        override fun close() {
          // Proxy view on root transport is non-owning.
        }
      }
    }

    override fun close() {
      closeCount.incrementAndGet()
    }
  }

  private suspend fun assertAllOperationsSucceed(engine: HttpEngine) {
    assertEquals(100L, engine.head("https://example.com/a").contentLength)
    assertEquals(100L, engine.probe("https://example.com/a").contentLength)
    var dl = 0
    engine.download("https://example.com/a", 0L..9L) { dl += it.size }
    assertEquals(3, dl)
    var res = 0
    assertEquals(
      "https://example.com/a",
      engine.downloadResource("https://example.com/a", emptyMap()) { res += it.size },
    )
    assertEquals(3, res)
    assertEquals(200, engine.request("GET", "https://example.com/a").status)
  }

  private suspend fun assertAllOperationsFailInactive(engine: HttpEngine) {
    assertFailsWith<InactiveTaskEngineException> { engine.head("https://example.com/a") }
    assertFailsWith<InactiveTaskEngineException> { engine.probe("https://example.com/a") }
    assertFailsWith<InactiveTaskEngineException> { engine.download("https://example.com/a", 0L..9L) {} }
    assertFailsWith<InactiveTaskEngineException> { engine.downloadResource("https://example.com/a", emptyMap()) {} }
    assertFailsWith<InactiveTaskEngineException> { engine.request("GET", "https://example.com/a") }
    assertFailsWith<InactiveTaskEngineException> { engine.withProxy(Proxy.Manual("http://127.0.0.1:8080")) }
    assertFailsWith<InactiveTaskEngineException> { engine.forTask("task-x", 1L) }
  }

  @Test
  fun distinctTaskIdsGetDistinctGenerations() = runTest {
    val root = AdmHttpEngine(RecordingTransport())
    val viewA = root.forTask("A") as AdmHttpEngine.TaskView
    val viewB = root.forTask("B") as AdmHttpEngine.TaskView

    assertEquals("A", viewA.generation.taskId)
    assertEquals("B", viewB.generation.taskId)
    assertNotEquals(viewA.generation.generationId, viewB.generation.generationId)
    assertNotSame(viewA.state, viewB.state)
  }

  @Test
  fun forTaskIsIdempotentWhileTaskRemainsActive() = runTest {
    val root = AdmHttpEngine(RecordingTransport())
    val first = root.forTask("A") as AdmHttpEngine.TaskView
    val second = root.forTask("A") as AdmHttpEngine.TaskView

    assertSame(first.state, second.state)
    assertEquals(first.generation, second.generation)
    assertAllOperationsSucceed(first)
    assertAllOperationsSucceed(second)
  }

  @Test
  fun withProxyPreservesGenerationAndPropagatesProxyToTransport() = runTest {
    val transport = RecordingTransport()
    val root = AdmHttpEngine(transport)
    val proxy = Proxy.Manual("http://proxy.test:8080")
    val taskView = root.forTask("A") as AdmHttpEngine.TaskView

    assertSame(taskView, taskView.withProxy(Proxy.System))

    val proxyView = taskView.withProxy(proxy) as AdmHttpEngine.TaskView
    assertNotSame(taskView, proxyView)
    assertEquals(taskView.generation, proxyView.generation)
    assertSame(taskView.state, proxyView.state)
    assertEquals(listOf<Proxy>(proxy), transport.recordedProxies)
    assertAllOperationsSucceed(proxyView)
  }

  @Test
  fun closingProxyViewDoesNotInvalidateParentTaskViewOrRoot() = runTest {
    val transport = RecordingTransport()
    val root = AdmHttpEngine(transport)
    val taskView = root.forTask("A") as AdmHttpEngine.TaskView
    val proxyView = taskView.withProxy(Proxy.Manual("http://proxy.test:8080"))

    proxyView.close()

    assertAllOperationsFailInactive(proxyView)
    assertAllOperationsSucceed(taskView)
    assertSame(taskView.state, root.activeTaskState("A"))
    assertEquals(0, transport.closeCount.get())
  }

  @Test
  fun closingTaskViewInvalidatesTaskAndDerivedProxyViewsWithoutAffectingOthersOrRoot() = runTest {
    val transport = RecordingTransport()
    val root = AdmHttpEngine(transport)
    val viewA = root.forTask("A") as AdmHttpEngine.TaskView
    val proxyA = viewA.withProxy(Proxy.Manual("http://proxy.test:8080"))
    val viewB = root.forTask("B")

    viewA.close()

    assertAllOperationsFailInactive(viewA)
    assertAllOperationsFailInactive(proxyA)
    assertAllOperationsSucceed(viewB)
    assertEquals(0, transport.closeCount.get())
  }

  @Test
  fun closingRootInvalidatesActiveTasksAndClosesRootTransportExactlyOnce() = runTest {
    val transport = RecordingTransport()
    val root = AdmHttpEngine(transport)
    val viewA = root.forTask("A")
    val proxyA = viewA.withProxy(Proxy.Manual("http://proxy.test:8080"))

    root.close()
    root.close()

    assertEquals(1, transport.closeCount.get())
    assertAllOperationsFailInactive(viewA)
    assertAllOperationsFailInactive(proxyA)
    assertFailsWith<InactiveTaskEngineException> { root.forTask("C") }
  }

  @Test
  fun recreatingTaskAfterCloseCreatesNewGenerationAndStaleViewsCannotEvictOrAccessIt() = runTest {
    val transport = RecordingTransport()
    val root = AdmHttpEngine(transport)

    val gen1View = root.forTask("A") as AdmHttpEngine.TaskView
    val gen1Proxy = gen1View.withProxy(Proxy.Manual("http://proxy.test:8080"))
    val gen1Id = gen1View.generation.generationId

    gen1View.close()

    val gen2View = root.forTask("A") as AdmHttpEngine.TaskView
    assertTrue(gen2View.generation.generationId > gen1Id)
    assertSame(gen2View.state, root.activeTaskState("A"))

    // Stale generation 1 views must fail and cannot evict generation 2 on repeated close.
    gen1View.close()
    gen1Proxy.close()
    assertSame(gen2View.state, root.activeTaskState("A"))
    assertAllOperationsFailInactive(gen1View)
    assertAllOperationsFailInactive(gen1Proxy)
    assertAllOperationsSucceed(gen2View)
  }

  @Test
  fun concurrentCloseAndTaskOperationsAreThreadSafeAndCloseRootOnce() = runTest {
    val transport = RecordingTransport()
    val root = AdmHttpEngine(transport)
    val pool = Executors.newFixedThreadPool(8)
    val startLatch = CountDownLatch(1)
    val doneLatch = CountDownLatch(8)
    val unexpectedErrors = Collections.synchronizedList(mutableListOf<Throwable>())

    try {
      for (worker in 0 until 8) {
        pool.submit {
          try {
            assertTrue(startLatch.await(5, TimeUnit.SECONDS))
            repeat(50) { i ->
              val taskId = "task-${i % 3}"
              try {
                val view = root.forTask(taskId)
                val proxy = view.withProxy(Proxy.Manual("http://proxy.test:8080"))
                if (i % 2 == 0) {
                  proxy.close()
                }
                if (i % 5 == 0) {
                  view.close()
                }
              } catch (_: InactiveTaskEngineException) {
                // Expected once closed concurrently.
              }
            }
            if (worker == 0 || worker == 1) {
              root.close()
            }
          } catch (t: Throwable) {
            unexpectedErrors += t
          } finally {
            doneLatch.countDown()
          }
        }
      }
      startLatch.countDown()
      assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
      root.close()
      assertEquals(emptyList(), unexpectedErrors)
      assertEquals(1, transport.closeCount.get())
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun ketchErrorsIncluding416UnsatisfiedRangePassThroughUnchanged() = runTest {
    val details = UnsatisfiedRangeResponse(
      totalBytes = 1024L,
      etag = "\"v1\"",
      lastModified = "Wed, 01 Jan 2025 00:00:00 GMT",
    )
    val error416 = KetchError.Http(416, "Range Not Satisfiable", cause = details)
    val transport = RecordingTransport(failureToThrow = error416)
    val root = AdmHttpEngine(transport)
    val taskView = root.forTask("A")
    val proxyView = taskView.withProxy(Proxy.Manual("http://proxy.test:8080"))

    val thrownTask = assertFailsWith<KetchError.Http> {
      taskView.download("https://example.com/a", 0L..9L) {}
    }
    assertSame(error416, thrownTask)
    assertNotNull(thrownTask.cause)
    assertSame(details, thrownTask.cause)

    val thrownProxy = assertFailsWith<KetchError.Http> {
      proxyView.download("https://example.com/a", 0L..9L) {}
    }
    assertSame(error416, thrownProxy)
    assertSame(details, thrownProxy.cause)
  }
}
