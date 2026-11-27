package com.linroid.ketch.adm.http

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.UnsatisfiedRangeResponse
import com.linroid.ketch.core.engine.HttpBody
import com.linroid.ketch.core.engine.InactiveTaskEngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdmRedirectHandlerTest {

  private class FakeTransport(
    private val handler: suspend (hopIndex: Int, request: AdmRequest) -> AdmResponse,
  ) : AdmHttpTransport {
    val recordedRequests = mutableListOf<AdmRequest>()
    var closed = false

    override suspend fun execute(request: AdmRequest): AdmResponse {
      recordedRequests.add(request)
      return handler(recordedRequests.size - 1, request)
    }

    override suspend fun stream(
      request: AdmRequest,
      range: LongRange?,
      onData: suspend (ByteArray) -> Unit,
    ): AdmResponse {
      recordedRequests.add(request)
      val resp = handler(recordedRequests.size - 1, request)
      if (!resp.isRedirect && resp.body.isNotEmpty()) {
        onData(resp.body)
      }
      return resp
    }

    override fun close() {
      closed = true
    }
  }

  @Test
  fun intermediateSetCookieAppliedOnNextHopAndRelativeLocationResolved() = runTest {
    val transport = FakeTransport { hop, _ ->
      when (hop) {
        0 -> AdmResponse(
          status = 302,
          headers = AdmResponse.normalizeHeaders(
            listOf(
              "Location" to "step2/file.bin",
              "Set-Cookie" to "sid=abc; Expires=Wed, 21 Oct 2035 07:28:00 GMT; Path=/downloads",
              "Set-Cookie" to "flags=on; Expires=Thu, 01 Jan 2036 00:00:00 GMT; HttpOnly",
            ),
          ),
        )
        1 -> AdmResponse(
          status = 301,
          headers = AdmResponse.normalizeHeaders(
            listOf("Location" to "/downloads/final.bin"),
          ),
        )
        else -> AdmResponse(status = 200, body = "ok".encodeToByteArray())
      }
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("dl-1")
    val chunks = mutableListOf<ByteArray>()
    val finalUrl = task.downloadResource("https://example.com/downloads/start", emptyMap()) {
      chunks.add(it)
    }

    assertEquals("https://example.com/downloads/final.bin", finalUrl)
    assertEquals(3, transport.recordedRequests.size)
    assertEquals("https://example.com/downloads/step2/file.bin", transport.recordedRequests[1].url)
    assertEquals("sid=abc; flags=on", transport.recordedRequests[1].header("Cookie"))
    assertEquals("https://example.com/downloads/final.bin", transport.recordedRequests[2].url)
    assertEquals("sid=abc; flags=on", transport.recordedRequests[2].header("Cookie"))
    assertEquals("ok", chunks.single().decodeToString())

    task.close()
    engine.close()
  }

  @Test
  fun protocolRelativeAndAbsoluteLocationsResolveCorrectly() = runTest {
    val transport = FakeTransport { hop, _ ->
      when (hop) {
        0 -> AdmResponse(status = 302, headers = mapOf("location" to listOf("//cdn.example.com/a/b")))
        1 -> AdmResponse(status = 302, headers = mapOf("location" to listOf("https://other.org:8443/c")))
        else -> AdmResponse(status = 200)
      }
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("t-rel")
    val reply = task.request("GET", "https://example.com/init")

    assertEquals("https://other.org:8443/c", reply.url)
    assertEquals("https://cdn.example.com/a/b", transport.recordedRequests[1].url)
    assertEquals("https://other.org:8443/c", transport.recordedRequests[2].url)

    task.close()
    engine.close()
  }

  @Test
  fun crossOriginStripsAuthorizationProxyAuthAndCallerCookieWhileRecalculatingJar() = runTest {
    val targets = listOf(
      "https://a.example.com/next",        // same origin (scheme, host, 443)
      "https://b.example.com/sub",         // subdomain change -> cross-origin
      "http://b.example.com/downgrade",    // scheme change -> cross-origin
      "http://b.example.com:8080/port",    // port change -> cross-origin
    )

    for (target in targets) {
      val transport = FakeTransport { hop, _ ->
        when (hop) {
          0 -> AdmResponse(
            status = 302,
            headers = AdmResponse.normalizeHeaders(
              listOf(
                "Location" to target,
                "Set-Cookie" to "jar_dom=shared; Domain=example.com; Path=/",
              ),
            ),
          )
          else -> AdmResponse(status = 200)
        }
      }

      val engine = AdmHttpEngine(transport)
      val task = engine.forTask("cross-$target")
      val reply = task.request(
        method = "GET",
        url = "https://a.example.com/start",
        headers = mapOf(
          "Authorization" to "Bearer secret",
          "Proxy-Authorization" to "Basic proxysecret",
          "Cookie" to "caller=1",
          "X-Trace" to "keep",
        ),
      )

      assertEquals(target, reply.url)
      val hop1 = transport.recordedRequests[1]
      assertEquals("keep", hop1.header("X-Trace"))

      if (target == "https://a.example.com/next") {
        assertEquals("Bearer secret", hop1.header("Authorization"))
        assertEquals("Basic proxysecret", hop1.header("Proxy-Authorization"))
        assertEquals("caller=1; jar_dom=shared", hop1.header("Cookie"))
      } else {
        assertNull(hop1.header("Authorization"))
        assertNull(hop1.header("Proxy-Authorization"))
        if (target.startsWith("https://b.example.com")) {
          assertEquals("jar_dom=shared", hop1.header("Cookie"))
        } else {
          assertEquals("jar_dom=shared", hop1.header("Cookie"))
        }
      }
      // Caller-supplied Cookie must never be persisted in TaskCookieJar
      assertTrue(engine.taskCookies("cross-$target")!!.cookiesFor("https://a.example.com/").none { it.name == "caller" })

      task.close()
      engine.close()
    }
  }

  @Test
  fun sameOriginPathCookieRecalculatedPerHop() = runTest {
    val transport = FakeTransport { hop, _ ->
      when (hop) {
        0 -> AdmResponse(
          status = 302,
          headers = AdmResponse.normalizeHeaders(
            listOf(
              "Location" to "/app/next",
              "Set-Cookie" to "scoped=yes; Path=/app",
            ),
          ),
        )
        1 -> AdmResponse(
          status = 302,
          headers = AdmResponse.normalizeHeaders(listOf("Location" to "/other")),
        )
        else -> AdmResponse(status = 200)
      }
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("path-recalc")
    task.request("GET", "https://example.com/start")

    assertEquals("scoped=yes", transport.recordedRequests[1].header("Cookie"))
    assertNull(transport.recordedRequests[2].header("Cookie"))

    task.close()
    engine.close()
  }

  @Test
  fun redirect301And302ConfigurablePostToGet() = runTest {
    val bodyBytes = "hello=world".encodeToByteArray()

    // Default: POST -> GET on 301/302, drops body, Content-Length, Content-Type
    val tDefault = FakeTransport { hop, _ ->
      if (hop == 0) {
        AdmResponse(status = 302, headers = mapOf("location" to listOf("/done")))
      } else {
        AdmResponse(status = 200)
      }
    }
    val engineDefault = AdmHttpEngine(tDefault, AdmRedirectConfig(convertPostToGetOn301And302 = true))
    val taskDefault = engineDefault.forTask("t1")
    taskDefault.request(
      method = "POST",
      url = "https://example.com/submit",
      headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", "Content-Length" to "11"),
      body = HttpBody(bodyBytes, "application/x-www-form-urlencoded"),
    )
    val hop1Default = tDefault.recordedRequests[1]
    assertEquals("GET", hop1Default.method)
    assertNull(hop1Default.body)
    assertNull(hop1Default.header("Content-Type"))
    assertNull(hop1Default.header("Content-Length"))
    taskDefault.close()
    engineDefault.close()

    // Disabled: preserves POST + body
    val tPreserve = FakeTransport { hop, _ ->
      if (hop == 0) {
        AdmResponse(status = 301, headers = mapOf("location" to listOf("/done")))
      } else {
        AdmResponse(status = 200)
      }
    }
    val enginePreserve = AdmHttpEngine(tPreserve, AdmRedirectConfig(convertPostToGetOn301And302 = false))
    val taskPreserve = enginePreserve.forTask("t2")
    taskPreserve.request(
      method = "POST",
      url = "https://example.com/submit",
      body = HttpBody(bodyBytes, "text/plain"),
    )
    val hop1Preserve = tPreserve.recordedRequests[1]
    assertEquals("POST", hop1Preserve.method)
    assertContentEquals(bodyBytes, (hop1Preserve.body as AdmRequestBody.Replayable).bytes)
    taskPreserve.close()
    enginePreserve.close()
  }

  @Test
  fun redirect303ConvertsPostToGetButKeepsHeadAsHead() = runTest {
    val transport = FakeTransport { hop, _ ->
      if (hop == 0) AdmResponse(status = 303, headers = mapOf("location" to listOf("/target")))
      else AdmResponse(status = 200)
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("t303")
    task.request(
      method = "POST",
      url = "https://example.com/post",
      headers = mapOf("Content-Type" to "text/plain", "Content-Length" to "4"),
      body = HttpBody("body".encodeToByteArray(), "text/plain"),
    )
    assertEquals("GET", transport.recordedRequests[1].method)
    assertNull(transport.recordedRequests[1].body)
    assertNull(transport.recordedRequests[1].header("Content-Type"))
    assertNull(transport.recordedRequests[1].header("Content-Length"))

    transport.recordedRequests.clear()
    task.head("https://example.com/head")
    assertEquals("HEAD", transport.recordedRequests[1].method)

    task.close()
    engine.close()
  }

  @Test
  fun redirect307And308PreserveMethodAndReplayableBodyOrFailOnOneShot() = runTest {
    val payload = "payload".encodeToByteArray()

    for (status in listOf(307, 308)) {
      val transport = FakeTransport { hop, _ ->
        if (hop == 0) AdmResponse(status = status, headers = mapOf("location" to listOf("/retry")))
        else AdmResponse(status = 200)
      }
      val engine = AdmHttpEngine(transport)
      val task = engine.forTask("t-$status")
      task.request("POST", "https://example.com/upload", body = HttpBody(payload, "text/plain"))
      assertEquals("POST", transport.recordedRequests[1].method)
      assertContentEquals(payload, (transport.recordedRequests[1].body as AdmRequestBody.Replayable).bytes)

      // One-shot body must fail with NonRepeatableBodyException
      transport.recordedRequests.clear()
      val taskView = task as AdmHttpEngine.TaskView
      assertFailsWith<NonRepeatableBodyException> {
        taskView.executeAdm(
          AdmRequest(
            method = "POST",
            url = "https://example.com/upload",
            body = AdmRequestBody.OneShot("text/plain") { payload },
          ),
        )
      }
      task.close()
      engine.close()
    }
  }

  @Test
  fun enforcesMaxRedirectsAndDetectsLoops() = runTest {
    var counter = 0
    val chainTransport = FakeTransport { _, _ ->
      counter++
      AdmResponse(status = 302, headers = mapOf("location" to listOf("/step/$counter")))
    }
    val engine1 = AdmHttpEngine(chainTransport, AdmRedirectConfig(maxRedirects = 3))
    val task1 = engine1.forTask("max-cap")
    assertFailsWith<TooManyRedirectsException> {
      task1.request("GET", "https://example.com/step/0")
    }
    assertEquals(4, chainTransport.recordedRequests.size) // initial + 3 redirects
    task1.close()
    engine1.close()

    val loopTransport = FakeTransport { _, req ->
      val next = if (req.url.endsWith("/a")) "/b" else "/a"
      AdmResponse(status = 302, headers = mapOf("location" to listOf(next)))
    }
    val engine2 = AdmHttpEngine(loopTransport, AdmRedirectConfig(maxRedirects = 10))
    val task2 = engine2.forTask("loop")
    assertFailsWith<RedirectLoopException> {
      task2.request("GET", "https://example.com/a")
    }
    assertEquals(2, loopTransport.recordedRequests.size)
    task2.close()
    engine2.close()
  }

  @Test
  fun preserves416UnsatisfiedRangeResponseAnd429Untouched() = runTest {
    val cause416 = UnsatisfiedRangeResponse(totalBytes = 100L, etag = "\"v2\"", lastModified = null)
    val transport = FakeTransport { _, req ->
      if (req.url.endsWith("/416")) {
        throw KetchError.Http(code = 416, statusMessage = "Range Not Satisfiable", cause = cause416)
      }
      AdmResponse(status = 429, headers = mapOf("retry-after" to listOf("60")))
    }

    val engine = AdmHttpEngine(transport)
    val task = engine.forTask("err-preserve")

    val thrown = assertFailsWith<KetchError.Http> {
      task.download("https://example.com/416", 200L..300L, emptyMap()) {}
    }
    assertEquals(416, thrown.code)
    assertEquals(cause416, thrown.cause)

    val reply429 = task.request("GET", "https://example.com/429")
    assertEquals(429, reply429.status)
    assertEquals("60", reply429.header("Retry-After"))

    task.close()
    engine.close()
  }

  @Test
  fun taskClosureAndCoroutineCancellationMidRedirectFailCleanly() = runTest {
    lateinit var taskToClose: com.linroid.ketch.core.engine.HttpEngine
    val closeTransport = FakeTransport { hop, _ ->
      if (hop == 0) {
        taskToClose.close()
        AdmResponse(status = 302, headers = mapOf("location" to listOf("/next")))
      } else {
        AdmResponse(status = 200)
      }
    }
    val engine = AdmHttpEngine(closeTransport)
    taskToClose = engine.forTask("close-mid")

    assertFailsWith<InactiveTaskEngineException> {
      taskToClose.request("GET", "https://example.com/start")
    }
    assertEquals(1, closeTransport.recordedRequests.size)
    engine.close()

    val cancelTransport = FakeTransport { _, _ ->
      AdmResponse(status = 302, headers = mapOf("location" to listOf("/next")))
    }
    val engine2 = AdmHttpEngine(cancelTransport)
    val task2 = engine2.forTask("cancel-mid")
    val state = engine2.activeTaskState("cancel-mid")!!

    assertFailsWith<CancellationException> {
      withContext(Job()) {
        var hops = 0
        val cancellingTransport = FakeTransport { _, _ ->
          hops++
          if (hops == 1) {
            coroutineContext.cancel()
          }
          AdmResponse(status = 302, headers = mapOf("location" to listOf("/hop$hops")))
        }
        state.redirectHandler.execute(AdmRequest("GET", "https://example.com/start"), cancellingTransport)
      }
    }
    task2.close()
    engine2.close()
  }
}
