package com.linroid.ketch.adm.http.probe

import com.linroid.ketch.adm.http.AdmHttpEngine
import com.linroid.ketch.adm.http.AdmHttpTransport
import com.linroid.ketch.adm.http.AdmRequest
import com.linroid.ketch.adm.http.AdmResponse
import com.linroid.ketch.adm.http.AdmResponseBody
import com.linroid.ketch.core.engine.InactiveTaskEngineException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbeCacheTest {

  private var nowMs: Long = 1_000_000L
  private var ruleVersion: Long = 1L

  private class CountingTransport(
    private val onRequest: () -> AdmResponse,
  ) : AdmHttpTransport {
    var calls = 0

    override suspend fun execute(request: AdmRequest): AdmResponse {
      calls++
      return onRequest()
    }

    override suspend fun openStream(request: AdmRequest): AdmResponse = execute(request)

    override suspend fun stream(
      request: AdmRequest,
      range: LongRange?,
      onData: suspend (ByteArray) -> Unit,
    ): AdmResponse = execute(request)
  }

  private fun ok206Response(
    etag: String = "\"e1\"",
    lastModified: String = "Wed, 21 Oct 2015 07:28:00 GMT",
    encoding: String = "identity",
  ): AdmResponse = AdmResponse(
    status = 206,
    headers = AdmResponse.normalizeHeaders(
      listOf(
        "Content-Range" to "bytes 0-255/1000",
        "ETag" to etag,
        "Last-Modified" to lastModified,
        "Content-Encoding" to encoding,
      ),
    ),
    streamingBody = AdmResponseBody.fromBytes(ByteArray(256) { 1 }),
  )

  @Test
  fun hitsOnMatchingPreRequestInputsAndMissesOnEveryKeyDimension() = runTest {
    val transport = CountingTransport { ok206Response() }
    val engine = AdmHttpEngine(
      admTransport = transport,
      clock = { nowMs },
      ruleVersionProvider = { ruleVersion },
    )

    val taskA = engine.forTask("task-A") as AdmHttpEngine.TaskView
    val cacheA = engine.taskProbeCache("task-A")!!

    // Initial probe -> miss (1)
    val r1 = taskA.probeAdm("http://example.com/file?v=1")
    assertFalse(r1.fromCache)
    assertEquals(1, transport.calls)

    // Exact same inputs + explicit default port 80 -> hit (1)
    val r2 = taskA.probeAdm("http://example.com:80/file?v=1")
    assertTrue(r2.fromCache)
    assertNull(r2.webPageProbe)
    assertEquals(1, transport.calls)

    // Query parameter change -> miss (2)
    assertFalse(taskA.probeAdm("http://example.com/file?v=2").fromCache)
    assertEquals(2, transport.calls)

    // Non-default port change -> miss (3)
    assertFalse(taskA.probeAdm("http://example.com:8080/file?v=1").fromCache)
    assertEquals(3, transport.calls)

    // Representation header change -> miss (4)
    assertFalse(taskA.probeAdm("http://example.com/file?v=1", headers = mapOf("Accept-Language" to "en-US")).fromCache)
    assertEquals(4, transport.calls)

    // Cookie digest change -> miss (5)
    engine.taskCookies("task-A")!!.store("http://example.com/", listOf("session=secret-cookie"))
    assertFalse(taskA.probeAdm("http://example.com/file?v=1").fromCache)
    assertEquals(5, transport.calls)

    // Auth digest change -> miss (6)
    assertFalse(
      taskA.probeAdm(
        "http://example.com/file?v=1",
        headers = mapOf("Authorization" to "Bearer super-secret-token"),
      ).fromCache,
    )
    assertEquals(6, transport.calls)

    // Rule version change -> miss (7)
    ruleVersion = 2L
    assertFalse(taskA.probeAdm("http://example.com/file?v=1").fromCache)
    assertEquals(7, transport.calls)

    // Method dimension in key
    val kGet = cacheA.buildKey("http://example.com/file?v=1", "GET", emptyList(), null)
    val kHead = cacheA.buildKey("http://example.com/file?v=1", "HEAD", emptyList(), null)
    assertNotEquals(kGet, kHead)

    // Secret hygiene: raw cookie & auth values must never appear in key diagnostics
    val secretKey = cacheA.buildKey(
      url = "http://example.com/file?v=1",
      method = "GET",
      requestHeaders = listOf("Authorization" to "Bearer super-secret-token"),
      effectiveCookieHeader = "session=secret-cookie",
    )
    val diag = secretKey.toString()
    assertFalse("super-secret-token" in diag)
    assertFalse("secret-cookie" in diag)

    // Different taskId -> miss (8)
    val taskB = engine.forTask("task-B") as AdmHttpEngine.TaskView
    assertFalse(taskB.probeAdm("http://example.com/file?v=1").fromCache)
    assertEquals(8, transport.calls)

    // Closing task-A and recreating task-A bumps generation -> old cache cleared, miss (9)
    taskA.close()
    assertFailsWith<InactiveTaskEngineException> {
      cacheA.get(kGet)
    }
    val taskA2 = engine.forTask("task-A") as AdmHttpEngine.TaskView
    assertFalse(taskA2.probeAdm("http://example.com/file?v=1").fromCache)
    assertEquals(9, transport.calls)

    taskA2.close()
    taskB.close()
    engine.close()
  }

  @Test
  fun validatesResponseMetadataWithoutInterchangeableValidatorConfusionAndEnforcesTtl() = runTest {
    val transport = CountingTransport {
      ok206Response(
        etag = "\"etag-1\"",
        lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        encoding = "identity",
      )
    }
    val engine = AdmHttpEngine(
      admTransport = transport,
      probeConfig = AdmProbeConfig(cacheTtlMs = 5_000L),
      clock = { nowMs },
    )
    val task = engine.forTask("val-ttl") as AdmHttpEngine.TaskView

    assertFalse(task.probeAdm("https://example.com/f").fromCache)
    assertEquals(1, transport.calls)

    // Matching validators -> hit
    assertTrue(
      task.probeAdm(
        "https://example.com/f",
        validators = ProbeValidators(
          etag = "\"etag-1\"",
          lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
          contentEncoding = "identity",
        ),
      ).fromCache,
    )
    assertEquals(1, transport.calls)

    // Passing Last-Modified value as ETag must NOT match (never interchangeable)
    assertFalse(
      task.probeAdm(
        "https://example.com/f",
        validators = ProbeValidators(etag = "Wed, 21 Oct 2015 07:28:00 GMT"),
      ).fromCache,
    )
    assertEquals(2, transport.calls)

    // Content-Encoding mismatch -> miss
    assertFalse(
      task.probeAdm(
        "https://example.com/f",
        validators = ProbeValidators(contentEncoding = "gzip"),
      ).fromCache,
    )
    assertEquals(3, transport.calls)

    // TTL expiry via injected clock -> miss
    nowMs += 4_999L
    assertTrue(task.probeAdm("https://example.com/f").fromCache)
    assertEquals(3, transport.calls)

    nowMs += 1L
    assertFalse(task.probeAdm("https://example.com/f").fromCache)
    assertEquals(4, transport.calls)

    task.close()
    engine.close()
  }
}
