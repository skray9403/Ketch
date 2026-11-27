package com.linroid.ketch.adm.http.probe

import com.linroid.ketch.adm.http.AdmHttpEngine
import com.linroid.ketch.adm.http.AdmHttpTransport
import com.linroid.ketch.adm.http.AdmRequest
import com.linroid.ketch.adm.http.AdmResponse
import com.linroid.ketch.adm.http.AdmResponseBody
import com.linroid.ketch.core.engine.InactiveTaskEngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebPagePrefixHandoffTest {

  private class CountingChunkBody(
    private val chunks: List<ByteArray>,
  ) : AdmResponseBody {
    private var idx = 0
    private var offsetInChunk = 0
    val chunkReads = AtomicInteger(0)
    val closed = AtomicBoolean(false)

    override suspend fun readChunk(maxBytes: Int): ByteArray? {
      if (closed.get() || idx >= chunks.size) return null
      chunkReads.incrementAndGet()
      val current = chunks[idx]
      val avail = current.size - offsetInChunk
      val toRead = minOf(maxBytes, avail)
      val out = current.copyOfRange(offsetInChunk, offsetInChunk + toRead)
      offsetInChunk += toRead
      if (offsetInChunk >= current.size) {
        idx++
        offsetInChunk = 0
      }
      return out
    }

    override fun close() {
      closed.set(true)
    }
  }

  private fun single200Transport(
    bodyFactory: () -> AdmResponseBody,
    totalLen: Long,
  ): AdmHttpTransport = object : AdmHttpTransport {
    var requestCount = 0

    override suspend fun execute(request: AdmRequest): AdmResponse {
      requestCount++
      return AdmResponse(
        status = 200,
        headers = mapOf("content-length" to listOf(totalLen.toString())),
        streamingBody = bodyFactory(),
      )
    }

    override suspend fun openStream(request: AdmRequest): AdmResponse = execute(request)

    override suspend fun stream(
      request: AdmRequest,
      range: LongRange?,
      onData: suspend (ByteArray) -> Unit,
    ): AdmResponse = execute(request)
  }

  @Test
  fun deliversExactPrefixPlusRemainderOnceAndCacheHitNeverDoublePrepends() = runTest {
    val full = ByteArray(700) { (it % 113).toByte() }
    val backing = CountingChunkBody(
      listOf(
        full.copyOfRange(0, 100),
        full.copyOfRange(100, 300),
        full.copyOfRange(300, 700),
      ),
    )
    val transport = single200Transport({ backing }, full.size.toLong())
    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("handoff-1") as AdmHttpEngine.TaskView

    val first = task.probeAdm("https://example.com/page")
    val probe = assertNotNull(first.webPageProbe)
    val answer = probe.readAnswer()

    assertContentEquals(full, answer.body)
    assertTrue(backing.closed.get())
    assertEquals(WebPageProbe.State.CONSUMED, probe.currentState)

    // Second consume fails
    assertFailsWith<IllegalStateException> {
      probe.readAnswer()
    }

    // Subsequent probe hits metadata-only cache and never returns a WebPageProbe or double-prepends
    val second = task.probeAdm("https://example.com/page")
    assertTrue(second.fromCache)
    assertNull(second.webPageProbe)

    task.close()
    engine.close()
  }

  @Test
  fun concurrentConsumeAllowsOnlyOneWinner() = runTest {
    val full = ByteArray(400) { 5 }
    val backing = CountingChunkBody(listOf(full))
    val transport = single200Transport({ backing }, full.size.toLong())
    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("concurrent") as AdmHttpEngine.TaskView

    val probe = assertNotNull(task.probeAdm("https://example.com/page").webPageProbe)

    val a = async { runCatching { probe.readAnswer() } }
    val b = async { runCatching { probe.readAnswer() } }
    val resA = a.await()
    val resB = b.await()

    assertEquals(1, listOf(resA, resB).count { it.isSuccess })
    assertEquals(1, listOf(resA, resB).count { it.isFailure })
    val winner = listOf(resA, resB).first { it.isSuccess }.getOrThrow()
    assertContentEquals(full, winner.body)

    task.close()
    engine.close()
  }

  @Test
  fun failureCancellationAndTaskCloseSpendAndCloseStreamWithNoReusablePrefix() = runTest {
    // 1. Consumer failure mid-stream
    val bodyFail = CountingChunkBody(listOf(ByteArray(300) { 1 }, ByteArray(100) { 2 }))
    val engine1 = AdmHttpEngine(single200Transport({ bodyFail }, 400L))
    val task1 = engine1.forTask("fail-mid") as AdmHttpEngine.TaskView
    val probe1 = assertNotNull(task1.probeAdm("https://example.com/1").webPageProbe)

    var seen = 0
    assertFailsWith<IllegalArgumentException> {
      probe1.consume {
        seen++
        if (seen == 2) throw IllegalArgumentException("consumer boom")
      }
    }
    assertTrue(bodyFail.closed.get())
    assertEquals(WebPageProbe.State.CLOSED, probe1.currentState)
    assertFailsWith<IllegalStateException> { probe1.readAnswer() }
    task1.close()
    engine1.close()

    // 2. Coroutine cancellation mid-stream
    val bodyCancel = CountingChunkBody(listOf(ByteArray(300) { 3 }, ByteArray(100) { 4 }))
    val engine2 = AdmHttpEngine(single200Transport({ bodyCancel }, 400L))
    val task2 = engine2.forTask("cancel-mid") as AdmHttpEngine.TaskView
    val probe2 = assertNotNull(task2.probeAdm("https://example.com/2").webPageProbe)

    assertFailsWith<CancellationException> {
      withContext(Job()) {
        probe2.consume {
          coroutineContext.cancel()
        }
      }
    }
    assertTrue(bodyCancel.closed.get())
    assertEquals(WebPageProbe.State.CLOSED, probe2.currentState)
    assertFailsWith<IllegalStateException> { probe2.readAnswer() }
    task2.close()
    engine2.close()

    // 3. Task close spends and closes stream
    val bodyClose = CountingChunkBody(listOf(ByteArray(400) { 9 }))
    val engine3 = AdmHttpEngine(single200Transport({ bodyClose }, 400L))
    val task3 = engine3.forTask("close-mid") as AdmHttpEngine.TaskView
    val probe3 = assertNotNull(task3.probeAdm("https://example.com/3").webPageProbe)

    task3.close()
    assertTrue(bodyClose.closed.get())
    assertEquals(WebPageProbe.State.CLOSED, probe3.currentState)
    assertFailsWith<InactiveTaskEngineException> { probe3.readAnswer() }
    engine3.close()
  }
}
