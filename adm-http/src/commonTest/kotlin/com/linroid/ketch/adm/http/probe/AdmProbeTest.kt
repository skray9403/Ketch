package com.linroid.ketch.adm.http.probe

import com.linroid.ketch.adm.http.AdmHttpEngine
import com.linroid.ketch.adm.http.AdmHttpTransport
import com.linroid.ketch.adm.http.AdmRequest
import com.linroid.ketch.adm.http.AdmResponse
import com.linroid.ketch.adm.http.AdmResponseBody
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.UnsatisfiedRangeResponse
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdmProbeTest {

  private class FakeStreamingTransport(
    private val handler: suspend (request: AdmRequest) -> AdmResponse,
  ) : AdmHttpTransport {
    val requests = mutableListOf<AdmRequest>()

    override suspend fun execute(request: AdmRequest): AdmResponse {
      requests.add(request)
      return handler(request)
    }

    override suspend fun openStream(request: AdmRequest): AdmResponse = execute(request)

    override suspend fun stream(
      request: AdmRequest,
      range: LongRange?,
      onData: suspend (ByteArray) -> Unit,
    ): AdmResponse = execute(request)
  }

  @Test
  fun sendsGetRange0To255AndAcceptEncodingIdentityAndIgnoresHeadDisagreement() = runTest {
    val prefix = ByteArray(256) { (it % 127).toByte() }
    val transport = FakeStreamingTransport {
      AdmResponse(
        status = 206,
        headers = AdmResponse.normalizeHeaders(
          listOf(
            "Content-Range" to "bytes 0-255/1024",
            "ETag" to "\"v1\"",
            "Last-Modified" to "Wed, 21 Oct 2015 07:28:00 GMT",
            "Content-Encoding" to "identity",
            "Content-Type" to "application/octet-stream",
          ),
        ),
        streamingBody = AdmResponseBody.fromBytes(prefix),
      )
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("probe-1") as AdmHttpEngine.TaskView

    // Even when a HEAD hint claims no ranges and a different length, GET probe is authoritative.
    val headHint = ServerInfo(contentLength = 9999L, acceptRanges = false, etag = null, lastModified = null)
    val result = task.probeAdm(
      url = "https://example.com/file.bin",
      method = "HEAD",
      headHint = headHint,
    )

    assertEquals(1, transport.requests.size)
    val sent = transport.requests.single()
    assertEquals("GET", sent.method)
    assertEquals("bytes=0-255", sent.header("Range"))
    assertEquals("identity", sent.header("Accept-Encoding"))

    assertTrue(result.metadata.supportsRanges)
    assertEquals(1024L, result.metadata.totalBytes)
    assertEquals("\"v1\"", result.metadata.etag)
    assertEquals("identity", result.metadata.contentEncoding)
    assertNull(result.webPageProbe)

    task.close()
    engine.close()
  }

  @Test
  fun authoritativeHeadAllowedOnlyWhenConfigured() = runTest {
    val transport = FakeStreamingTransport {
      AdmResponse(
        status = 200,
        headers = AdmResponse.normalizeHeaders(
          listOf(
            "Content-Length" to "512",
            "Accept-Ranges" to "bytes",
          ),
        ),
      )
    }

    val engine = AdmHttpEngine(
      admTransport = transport,
      probeConfig = AdmProbeConfig(allowAuthoritativeHead = true),
    )
    val task = engine.forTask("head-auth") as AdmHttpEngine.TaskView
    val result = task.probeAdm("https://example.com/file.bin", method = "HEAD")

    assertEquals("HEAD", transport.requests.single().method)
    assertNull(transport.requests.single().header("Range"))
    assertTrue(result.metadata.supportsRanges)
    assertEquals(512L, result.metadata.totalBytes)
    assertNull(result.webPageProbe)

    task.close()
    engine.close()
  }

  @Test
  fun validates206ContentRangeAndRejectsInvalidOrContradictoryMetadata() = runTest {
    val valid256 = ByteArray(256) { 1 }

    // Valid unknown-total 206 (bytes 0-255/*)
    val okTransport = FakeStreamingTransport {
      AdmResponse(
        status = 206,
        headers = mapOf("content-range" to listOf("bytes 0-255/*")),
        streamingBody = AdmResponseBody.fromBytes(valid256),
      )
    }
    val okEngine = AdmHttpEngine(okTransport)
    val okTask = okEngine.forTask("ok-206") as AdmHttpEngine.TaskView
    val okResult = okTask.probeAdm("https://example.com/stream")
    assertTrue(okResult.metadata.supportsRanges)
    assertNull(okResult.metadata.totalBytes)
    okTask.close()
    okEngine.close()

    val badCases = listOf(
      AdmResponse(status = 206, headers = emptyMap(), streamingBody = AdmResponseBody.fromBytes(valid256)),
      AdmResponse(status = 206, headers = mapOf("content-range" to listOf("bytes 1-255/1000")), streamingBody = AdmResponseBody.fromBytes(ByteArray(255))),
      AdmResponse(status = 206, headers = mapOf("content-range" to listOf("bytes 0-255/200")), streamingBody = AdmResponseBody.fromBytes(valid256)),
      AdmResponse(status = 206, headers = mapOf("content-range" to listOf("bytes 0-300/1000")), streamingBody = AdmResponseBody.fromBytes(ByteArray(301))),
      AdmResponse(status = 206, headers = mapOf("content-range" to listOf("bytes 0-255/1000")), streamingBody = AdmResponseBody.fromBytes(ByteArray(100))),
      AdmResponse(status = 206, headers = mapOf("content-range" to listOf("bytes 0-255/1000")), streamingBody = AdmResponseBody.fromBytes(ByteArray(257))),
      AdmResponse(status = 206, headers = mapOf("content-range" to listOf("items 0-255/1000")), streamingBody = AdmResponseBody.fromBytes(valid256)),
    )

    badCases.forEachIndexed { idx, badResp ->
      val t = FakeStreamingTransport { badResp }
      val e = AdmHttpEngine(t)
      val task = e.forTask("bad-$idx") as AdmHttpEngine.TaskView
      assertFailsWith<InvalidProbeResponseException> {
        task.probeAdm("https://example.com/bad")
      }
      task.close()
      e.close()
    }
  }

  @Test
  fun handles200RangeIgnoredWithoutSecondRequest() = runTest {
    val fullBody = ByteArray(700) { (it % 97).toByte() }
    val transport = FakeStreamingTransport {
      AdmResponse(
        status = 200,
        headers = AdmResponse.normalizeHeaders(
          listOf(
            "Content-Length" to "700",
            "ETag" to "\"full-1\"",
            "Content-Encoding" to "identity",
          ),
        ),
        streamingBody = AdmResponseBody.fromChunks(
          listOf(
            fullBody.copyOfRange(0, 100),
            fullBody.copyOfRange(100, 400),
            fullBody.copyOfRange(400, 700),
          ),
        ),
      )
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("range-ignored") as AdmHttpEngine.TaskView
    val result = task.probeAdm("https://example.com/page.html")

    assertEquals(1, transport.requests.size)
    assertFalse(result.metadata.supportsRanges)
    assertEquals(700L, result.metadata.totalBytes)
    assertEquals("\"full-1\"", result.metadata.etag)

    val handoff = assertNotNull(result.webPageProbe)
    val answer = handoff.readAnswer()
    assertContentEquals(fullBody, answer.body)
    assertEquals(1, transport.requests.size)

    task.close()
    engine.close()
  }

  @Test
  fun preserves416UnsatisfiedRangeResponseThroughProbeAndEngine() = runTest {
    val cases = listOf(
      "bytes */1024" to 1024L,
      "bytes */0" to 0L,
      "bytes */*" to null,
      "malformed" to null,
      null to null,
    )

    for ((contentRange, expectedTotal) in cases) {
      val headers = buildList {
        if (contentRange != null) add("Content-Range" to contentRange)
        add("ETag" to "\"etag-416\"")
        add("Last-Modified" to "Thu, 01 Jan 2026 00:00:00 GMT")
      }
      val transport = FakeStreamingTransport {
        AdmResponse(status = 416, headers = AdmResponse.normalizeHeaders(headers))
      }

      val engine = AdmHttpEngine(transport)
      val task = engine.forTask("416-$contentRange")

      val err = assertFailsWith<KetchError.Http> {
        task.probe("https://example.com/file.bin")
      }
      assertEquals(416, err.code)
      val details = err.cause as? UnsatisfiedRangeResponse
      assertNotNull(details)
      assertEquals(expectedTotal, details.totalBytes)
      assertEquals("\"etag-416\"", details.etag)
      assertEquals("Thu, 01 Jan 2026 00:00:00 GMT", details.lastModified)
      assertEquals(0, engine.taskProbeCache("416-$contentRange")!!.size())

      task.close()
      engine.close()
    }
  }

  @Test
  fun redirectedProbeCapturesFinalUrlAndIntermediateCookies() = runTest {
    val prefix = ByteArray(256) { 7 }
    val transport = FakeStreamingTransport { req ->
      if (req.url == "https://example.com/start") {
        AdmResponse(
          status = 302,
          headers = AdmResponse.normalizeHeaders(
            listOf(
              "Location" to "/cdn/final.bin",
              "Set-Cookie" to "probe_sid=xyz; Path=/cdn",
            ),
          ),
        )
      } else {
        AdmResponse(
          status = 206,
          headers = AdmResponse.normalizeHeaders(
            listOf("Content-Range" to "bytes 0-255/2048"),
          ),
          streamingBody = AdmResponseBody.fromBytes(prefix),
        )
      }
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("redir-probe") as AdmHttpEngine.TaskView
    val result = task.probeAdm("https://example.com/start")

    assertEquals("https://example.com/cdn/final.bin", result.metadata.finalUrl)
    assertEquals(2048L, result.metadata.totalBytes)
    assertEquals("probe_sid=xyz", transport.requests[1].header("Cookie"))

    task.close()
    engine.close()
  }

  @Test
  fun preserves429AndRetryAfterWithoutCaching() = runTest {
    val transport = FakeStreamingTransport {
      AdmResponse(
        status = 429,
        headers = AdmResponse.normalizeHeaders(
          listOf(
            "Retry-After" to "45",
            "X-RateLimit-Remaining" to "0",
          ),
        ),
      )
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("rate-limit")
    val err = assertFailsWith<KetchError.Http> {
      task.probe("https://example.com/limited")
    }
    assertEquals(429, err.code)
    assertEquals(45L, err.retryAfterSeconds)
    assertEquals(0L, err.rateLimitRemaining)
    assertEquals(0, engine.taskProbeCache("rate-limit")!!.size())

    task.close()
    engine.close()
  }
}
