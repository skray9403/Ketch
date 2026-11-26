package com.linroid.ketch.core.engine

import com.linroid.ketch.api.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class HttpEngineTaskScopeTest {

  private class FakeRootEngine(
    private val failureToThrow: Throwable? = null,
  ) : HttpEngine {
    val rootCloseCount = AtomicInteger(0)
    val recordedProxies = mutableListOf<Proxy>()
    val operationCount = AtomicInteger(0)

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      throw UnsupportedOperationException()

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) = throw UnsupportedOperationException()

    override fun withProxy(proxy: Proxy): HttpEngine {
      recordedProxies.add(proxy)
      return this
    }

    override fun close() {
      rootCloseCount.incrementAndGet()
    }

    fun executeSampleOp(engine: HttpEngine): String {
      if (engine is BorrowedTaskHttpEngine) {
        engine.resolveTargetForTest()
      }
      operationCount.incrementAndGet()
      failureToThrow?.let { throw it }
      return "ok"
    }
  }

  private class FakeGenerationRegistry {
    private val seq = AtomicLong(0L)
    private val activeGenerations = ConcurrentHashMap<String, Long>()
    private val closedGenerations = ConcurrentHashMap.newKeySet<TaskGeneration>()

    fun openTask(taskId: String): TaskGeneration {
      val gen = TaskGeneration(taskId = taskId, generationId = seq.incrementAndGet())
      activeGenerations[taskId] = gen.generationId
      return gen
    }

    fun closeTask(generation: TaskGeneration) {
      closedGenerations.add(generation)
      activeGenerations.remove(generation.taskId, generation.generationId)
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

  @Test
  fun taskViewPreservesTaskAndGenerationIdentityWithoutClosingRoot() {
    val root = FakeRootEngine()
    val registry = FakeGenerationRegistry()
    val genA = registry.openTask("task-a")

    val viewA = root.forTask(genA.taskId, genA.generationId, registry.gate()) as BorrowedTaskHttpEngine
    assertEquals(genA, viewA.generation)
    assertEquals("ok", root.executeSampleOp(viewA))

    viewA.close()
    viewA.close() // repeated close is safe and non-destructive

    assertEquals(0, root.rootCloseCount.get())
    assertFailsWith<InactiveTaskEngineException> {
      root.executeSampleOp(viewA)
    }
  }

  @Test
  fun proxyViewPreservesTaskGenerationAndDoesNotCloseRootOnClose() {
    val root = FakeRootEngine()
    val registry = FakeGenerationRegistry()
    val genA = registry.openTask("task-a")
    val manualProxy = Proxy.Manual("http://127.0.0.1:8080")

    val taskView = root.forTask(genA.taskId, genA.generationId, registry.gate())
    val proxiedView = taskView.withProxy(manualProxy) as BorrowedTaskHttpEngine

    assertNotSame(taskView, proxiedView)
    assertEquals(genA, proxiedView.generation)
    assertEquals("ok", root.executeSampleOp(proxiedView))
    assertEquals(listOf<Proxy>(manualProxy), root.recordedProxies)

    proxiedView.close()
    assertEquals(0, root.rootCloseCount.get())
  }

  @Test
  fun multipleTasksAreIsolatedAndClosingOneDoesNotAffectAnother() {
    val root = FakeRootEngine()
    val registry = FakeGenerationRegistry()
    val genA = registry.openTask("task-a")
    val genB = registry.openTask("task-b")

    val viewA = root.forTask(genA.taskId, genA.generationId, registry.gate())
    val viewB = root.forTask(genB.taskId, genB.generationId, registry.gate())

    registry.closeTask(genA)
    viewA.close()

    assertFailsWith<InactiveTaskEngineException> {
      root.executeSampleOp(viewA)
    }
    assertEquals("ok", root.executeSampleOp(viewB))
    assertEquals(0, root.rootCloseCount.get())
  }

  @Test
  fun staleBorrowedReferenceCannotResolveToRecreatedTaskGeneration() {
    val root = FakeRootEngine()
    val registry = FakeGenerationRegistry()

    val firstGenA = registry.openTask("task-a")
    val staleView = root.forTask(firstGenA.taskId, firstGenA.generationId, registry.gate())

    registry.closeTask(firstGenA)
    staleView.close()

    val secondGenA = registry.openTask("task-a")
    val freshView = root.forTask(secondGenA.taskId, secondGenA.generationId, registry.gate())

    assertEquals("ok", root.executeSampleOp(freshView))
    assertFailsWith<InactiveTaskEngineException> {
      root.executeSampleOp(staleView)
    }
  }

  @Test
  fun underlyingFailurePropagatesAndRootCloseIsExplicit() {
    val boom = IllegalStateException("boom")
    val root = FakeRootEngine(failureToThrow = boom)
    val view = root.forTask("task-err", 1L)

    val thrown = assertFailsWith<IllegalStateException> {
      root.executeSampleOp(view)
    }
    assertSame(boom, thrown)

    root.close()
    assertEquals(1, root.rootCloseCount.get())
  }
}
