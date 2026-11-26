package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class HttpEngineTaskScopeTest {

  private class FakeRootEngine(
    var failureToThrow: Throwable? = null,
    val supportsProxyVal: Boolean = true,
  ) : HttpEngine {
    var rootCloseCount = 0
    val recordedProxies = mutableListOf<Proxy>()
    var headCalls = 0
    var probeCalls = 0
    var downloadCalls = 0
    var downloadResourceCalls = 0
    var requestCalls = 0

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      failureToThrow?.let { throw it }
      headCalls++
      return ServerInfo(contentLength = 100L, acceptRanges = true, etag = null, lastModified = null, url = url)
    }

    override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo {
      failureToThrow?.let { throw it }
      probeCalls++
      return ServerInfo(contentLength = 100L, acceptRanges = true, etag = null, lastModified = null, url = url)
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      failureToThrow?.let { throw it }
      downloadCalls++
      onData(byteArrayOf(1, 2, 3))
    }

    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String {
      failureToThrow?.let { throw it }
      downloadResourceCalls++
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
      requestCalls++
      return HttpReply(status = 200, headers = emptyMap(), body = byteArrayOf())
    }

    override val supportsProxies: Boolean get() = supportsProxyVal

    override fun withProxy(proxy: Proxy): HttpEngine {
      if (!supportsProxyVal && proxy != Proxy.System) {
        throw KetchError.Unsupported(UnsupportedOperationException("No proxy"))
      }
      recordedProxies.add(proxy)
      return this
    }

    override fun close() {
      rootCloseCount++
    }
  }

  private class FakeGenerationRegistry {
    private var seq = 0L
    private val activeGenerations = mutableMapOf<String, Long>()
    private val closedGenerations = mutableSetOf<TaskGeneration>()

    fun openTask(taskId: String): TaskGeneration {
      val gen = TaskGeneration(taskId = taskId, generationId = ++seq)
      activeGenerations[taskId] = gen.generationId
      return gen
    }

    fun closeTask(generation: TaskGeneration) {
      closedGenerations.add(generation)
      if (activeGenerations[generation.taskId] == generation.generationId) {
        activeGenerations.remove(generation.taskId)
      }
    }

    fun gate(): TaskLifecycleGate = TaskLifecycleGate { requested ->
      if (closedGenerations.contains(requested)) {
        throw InactiveTaskEngineException(requested)
      }
      val current = activeGenerations[requested.taskId]
      if (current != requested.generationId) {
        throw InactiveTaskEngineException(requested)
      }
    }
  }

  private suspend fun assertAllOperationsSucceed(engine: HttpEngine) {
    val headInfo = engine.head("https://example.com/test")
    assertEquals(100L, headInfo.contentLength)

    val probeInfo = engine.probe("https://example.com/test")
    assertEquals(100L, probeInfo.contentLength)

    var downloadedBytes = 0
    engine.download("https://example.com/test", 0L..10L) { bytes ->
      downloadedBytes += bytes.size
    }
    assertEquals(3, downloadedBytes)

    var resourceBytes = 0
    val finalUrl = engine.downloadResource("https://example.com/test", emptyMap()) { bytes ->
      resourceBytes += bytes.size
    }
    assertEquals("https://example.com/test", finalUrl)
    assertEquals(3, resourceBytes)

    val reply = engine.request("GET", "https://example.com/test")
    assertEquals(200, reply.status)
  }

  private suspend fun assertAllOperationsFailInactive(engine: HttpEngine) {
    assertFailsWith<InactiveTaskEngineException> {
      engine.head("https://example.com/test")
    }
    assertFailsWith<InactiveTaskEngineException> {
      engine.probe("https://example.com/test")
    }
    assertFailsWith<InactiveTaskEngineException> {
      engine.download("https://example.com/test", 0L..10L) {}
    }
    assertFailsWith<InactiveTaskEngineException> {
      engine.downloadResource("https://example.com/test", emptyMap()) {}
    }
    assertFailsWith<InactiveTaskEngineException> {
      engine.request("GET", "https://example.com/test")
    }
    assertFailsWith<InactiveTaskEngineException> {
      engine.withProxy(Proxy.Manual("http://127.0.0.1:8080"))
    }
    assertFailsWith<InactiveTaskEngineException> {
      engine.forTask("task-other", 1L)
    }
  }

  @Test
  fun taskViewDelegatesAllOperationsAndEnforcesLifecycleWithoutClosingRoot() = runTest {
    val root = FakeRootEngine()
    val registry = FakeGenerationRegistry()
    val genA = registry.openTask("task-a")

    val viewA = root.forTask(genA.taskId, genA.generationId, registry.gate()) as BorrowedTaskHttpEngine
    assertEquals(genA, viewA.generation)

    assertAllOperationsSucceed(viewA)
    assertEquals(1, root.headCalls)
    assertEquals(1, root.probeCalls)
    assertEquals(1, root.downloadCalls)
    assertEquals(1, root.downloadResourceCalls)
    assertEquals(1, root.requestCalls)

    viewA.close()
    viewA.close() // repeated close is safe and non-destructive

    assertEquals(0, root.rootCloseCount)
    assertAllOperationsFailInactive(viewA)
  }

  @Test
  fun proxyViewPreservesTaskGenerationAndEagerlyConfiguresProxyWithoutClosingRoot() = runTest {
    val root = FakeRootEngine()
    val registry = FakeGenerationRegistry()
    val genA = registry.openTask("task-a")
    val manualProxy = Proxy.Manual("http://127.0.0.1:8080")

    val taskView = root.forTask(genA.taskId, genA.generationId, registry.gate())

    // withProxy(Proxy.System) returns taskView directly
    assertSame(taskView, taskView.withProxy(Proxy.System))

    // Eagerly delegates to root engine
    val proxiedView = taskView.withProxy(manualProxy) as BorrowedTaskHttpEngine
    assertNotSame(taskView, proxiedView)
    assertEquals(genA, proxiedView.generation)
    assertEquals(listOf<Proxy>(manualProxy), root.recordedProxies)

    assertAllOperationsSucceed(proxiedView)

    // Fail-fast on unsupported proxy
    val unsupportedRoot = FakeRootEngine(supportsProxyVal = false)
    val unsupportedTaskView = unsupportedRoot.forTask("task-unsupported", 1L)
    assertFailsWith<KetchError.Unsupported> {
      unsupportedTaskView.withProxy(manualProxy)
    }

    proxiedView.close()
    assertEquals(0, root.rootCloseCount)
    assertAllOperationsFailInactive(proxiedView)
  }

  @Test
  fun multipleTasksAreIsolatedAndClosingOneDoesNotAffectAnother() = runTest {
    val root = FakeRootEngine()
    val registry = FakeGenerationRegistry()
    val genA = registry.openTask("task-a")
    val genB = registry.openTask("task-b")

    val viewA = root.forTask(genA.taskId, genA.generationId, registry.gate())
    val viewB = root.forTask(genB.taskId, genB.generationId, registry.gate())

    registry.closeTask(genA)
    viewA.close()

    assertAllOperationsFailInactive(viewA)
    assertAllOperationsSucceed(viewB)
    assertEquals(0, root.rootCloseCount)
  }

  @Test
  fun staleBorrowedReferenceCannotResolveToRecreatedTaskGeneration() = runTest {
    val root = FakeRootEngine()
    val registry = FakeGenerationRegistry()

    val firstGenA = registry.openTask("task-a")
    val staleView = root.forTask(firstGenA.taskId, firstGenA.generationId, registry.gate())

    registry.closeTask(firstGenA)
    staleView.close()

    val secondGenA = registry.openTask("task-a")
    val freshView = root.forTask(secondGenA.taskId, secondGenA.generationId, registry.gate())

    assertAllOperationsSucceed(freshView)
    assertAllOperationsFailInactive(staleView)
  }

  @Test
  fun underlyingFailurePropagatesAndRootCloseIsExplicit() = runTest {
    val boom = IllegalStateException("boom")
    val root = FakeRootEngine(failureToThrow = boom)
    val view = root.forTask("task-err", 1L)

    val thrown = assertFailsWith<IllegalStateException> {
      view.head("https://example.com/err")
    }
    assertSame(boom, thrown)

    root.close()
    assertEquals(1, root.rootCloseCount)
  }

  @Test
  fun multiNetworkHttpEngineForTaskDelegatesTaskScopingWithoutClosingRoot() = runTest {
    val rootA = FakeRootEngine()
    val rootB = FakeRootEngine()
    val multi = MultiNetworkHttpEngine(listOf(rootA, rootB))
    val registry = FakeGenerationRegistry()
    val gen = registry.openTask("task-multi")

    val taskMulti = multi.forTask(gen.taskId, gen.generationId, registry.gate())

    // Round-robin to rootA
    val infoA = taskMulti.head("https://example.com/1")
    assertEquals(100L, infoA.contentLength)
    assertEquals(1, rootA.headCalls)
    assertEquals(0, rootB.headCalls)

    // Round-robin to rootB
    val infoB = taskMulti.head("https://example.com/2")
    assertEquals(100L, infoB.contentLength)
    assertEquals(1, rootA.headCalls)
    assertEquals(1, rootB.headCalls)

    // Close taskMulti
    taskMulti.close()

    // Roots must not be closed!
    assertEquals(0, rootA.rootCloseCount)
    assertEquals(0, rootB.rootCloseCount)

    // Calling closed taskMulti fails with InactiveTaskEngineException
    assertFailsWith<InactiveTaskEngineException> {
      taskMulti.head("https://example.com/3")
    }

    // Roots are still usable through original multi
    multi.head("https://example.com/4")
    assertEquals(3, rootA.headCalls + rootB.headCalls)

    // Explicit close on root multi closes roots
    multi.close()
    assertEquals(1, rootA.rootCloseCount)
    assertEquals(1, rootB.rootCloseCount)
  }
}
