package com.linroid.ketch.adm.http.cookies

import com.linroid.ketch.adm.http.AdmHttpEngine
import com.linroid.ketch.adm.http.AdmTaskState
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.InactiveTaskEngineException
import com.linroid.ketch.core.engine.ServerInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskCookieJarTest {

  private var nowMillis: Long = 1_700_000_000_000L // 2023-11-14T22:13:20Z

  private fun newState(
    taskId: String = "task-1",
    generationId: Long = 1L,
    psl: PublicSuffixMatcher = PublicSuffixMatcher.NONE,
  ): AdmTaskState = AdmTaskState(
    taskId = taskId,
    generationId = generationId,
    clock = { nowMillis },
    publicSuffixMatcher = psl,
  )

  @Test
  fun hostOnlyVsDomainMatching() {
    val state = newState()
    val jar = state.cookies

    jar.store(
      "https://sub.example.com/a",
      listOf(
        "host_cookie=1",
        "domain_cookie=2; Domain=.Example.COM",
      ),
    )

    assertEquals(
      listOf("host_cookie", "domain_cookie"),
      jar.cookiesFor("https://sub.example.com/a").map { it.name },
    )
    assertEquals(
      listOf("domain_cookie"),
      jar.cookiesFor("https://other.example.com/a").map { it.name },
    )
    assertEquals(
      listOf("domain_cookie"),
      jar.cookiesFor("https://example.com/a").map { it.name },
    )
    assertTrue(jar.cookiesFor("https://notexample.com/a").isEmpty())
  }

  @Test
  fun rejectsUnrelatedInvalidIpAndPublicSuffixDomains() {
    val psl = PublicSuffixMatcher { domain -> domain == "com" || domain == "co.uk" }
    val state = newState(psl = psl)
    val jar = state.cookies

    jar.store(
      "https://app.example.co.uk/path",
      listOf(
        "unrelated=1; Domain=evil.com",
        "empty_dom=1; Domain=",
        "dot_dom=1; Domain=.",
        "double_dot=1; Domain=bad..example.co.uk",
        "psl_uk=1; Domain=co.uk",
        "psl_com=1; Domain=com",
        "valid=ok; Domain=example.co.uk",
      ),
    )

    assertEquals(listOf("valid"), jar.cookiesFor("https://app.example.co.uk/path").map { it.name })

    jar.store(
      "http://127.0.0.1/test",
      listOf(
        "ip_domain=1; Domain=127.0.0.1",
        "ip_hostonly=2",
      ),
    )
    assertEquals(listOf("ip_hostonly"), jar.cookiesFor("http://127.0.0.1/test").map { it.name })

    jar.store(
      "http://[::1]/test",
      listOf(
        "ipv6_domain=1; Domain=::1",
        "ipv6_hostonly=2",
      ),
    )
    assertEquals(listOf("ipv6_hostonly"), jar.cookiesFor("http://[::1]/test").map { it.name })
  }

  @Test
  fun defaultPathAndPathBoundaryMatching() {
    val state = newState()
    val jar = state.cookies

    jar.store("https://example.com/app/sub/file.bin", listOf("def=1"))
    jar.store("https://example.com/anything", listOf("rootdef=2"))
    jar.store("https://example.com/", listOf("explicit=3; Path=/app"))

    // Default path for /app/sub/file.bin is /app/sub
    assertEquals(setOf("def", "rootdef", "explicit"), jar.cookiesFor("https://example.com/app/sub/other").map { it.name }.toSet())
    assertEquals(setOf("rootdef", "explicit"), jar.cookiesFor("https://example.com/app").map { it.name }.toSet())
    assertEquals(setOf("rootdef", "explicit"), jar.cookiesFor("https://example.com/app/x").map { it.name }.toSet())
    // /app must NOT match /apple
    assertEquals(listOf("rootdef"), jar.cookiesFor("https://example.com/apple").map { it.name })
  }

  @Test
  fun secureAndHttpOnlySemantics() {
    val state = newState()
    val jar = state.cookies

    jar.store(
      "https://example.com/",
      listOf(
        "sec=1; Secure",
        "http_only=2; HttpOnly",
        "both=3; Secure; HttpOnly",
        "plain=4",
      ),
    )

    assertEquals(
      listOf("sec", "http_only", "both", "plain"),
      jar.cookiesFor("https://example.com/").map { it.name },
    )
    assertEquals(
      listOf("http_only", "plain"),
      jar.cookiesFor("http://example.com/").map { it.name },
    )
    assertEquals(
      listOf("sec", "plain"),
      jar.scriptCookiesFor("https://example.com/").map { it.name },
    )
    assertEquals(
      listOf("plain"),
      jar.scriptCookiesFor("http://example.com/").map { it.name },
    )
  }

  @Test
  fun maxAgeTakesPrecedenceOverExpiresAndDeletesMatchingCookie() {
    val state = newState()
    val jar = state.cookies

    // Future Expires, but Max-Age=5 -> expires at now + 5000ms
    jar.store(
      "https://example.com/a",
      listOf("session=abc; Expires=Wed, 21 Oct 2035 07:28:00 GMT; Max-Age=5"),
    )
    assertEquals("session=abc", jar.cookieHeaderFor("https://example.com/a"))

    nowMillis += 4_999L
    assertEquals("session=abc", jar.cookieHeaderFor("https://example.com/a"))

    nowMillis += 1L
    assertNull(jar.cookieHeaderFor("https://example.com/a"))

    // Re-add then delete via Max-Age=0 (even with future Expires)
    jar.store("https://example.com/a", listOf("token=xyz"))
    assertEquals("token=xyz", jar.cookieHeaderFor("https://example.com/a"))

    jar.store(
      "https://example.com/a",
      listOf("token=gone; Expires=Wed, 21 Oct 2035 07:28:00 GMT; Max-Age=0"),
    )
    assertNull(jar.cookieHeaderFor("https://example.com/a"))

    // Re-add then delete via past Expires
    jar.store("https://example.com/a", listOf("token=xyz2"))
    jar.store(
      "https://example.com/a",
      listOf("token=gone; Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
    )
    assertNull(jar.cookieHeaderFor("https://example.com/a"))
  }

  @Test
  fun replacementKeyedByNameDomainPathAndDeterministicOrdering() {
    val state = newState()
    val jar = state.cookies

    jar.store(
      "https://example.com/",
      listOf(
        "a=1; Path=/",
        "b=2; Path=/app",
        "c=3; Path=/app/v1",
        "d=4; Path=/app",
      ),
    )

    // Longest path first (/app/v1 -> /app -> /), then earliest creation index (b before d)
    assertEquals(
      "c=3; b=2; d=4; a=1",
      jar.cookieHeaderFor("https://example.com/app/v1/item"),
    )

    // Replace b while preserving creation order and not touching distinct path
    jar.store("https://example.com/", listOf("b=22; Path=/app", "b=99; Path=/"))
    assertEquals(
      "c=3; b=22; d=4; a=1; b=99",
      jar.cookieHeaderFor("https://example.com/app/v1/item"),
    )
  }

  @Test
  fun multipleSetCookieHeadersWithCommasInExpiresAreNotCorrupted() {
    val state = newState()
    val jar = state.cookies

    jar.store(
      "https://example.com/",
      listOf(
        "first=one; Expires=Wed, 21 Oct 2035 07:28:00 GMT; Path=/",
        "second=two; Expires=Thu, 01 Jan 2036 00:00:00 GMT; Path=/; HttpOnly",
      ),
    )

    assertEquals("first=one; second=two", jar.cookieHeaderFor("https://example.com/"))
  }

  @Test
  fun taskIsolationAndRejectionAfterCloseOrGenerationBump() {
    val engine = AdmHttpEngine(NoopHttpEngine())
    val taskA = engine.forTask("task-A")
    val taskB = engine.forTask("task-B")

    val jarA = engine.taskCookies("task-A")!!
    val jarB = engine.taskCookies("task-B")!!

    jarA.store("https://example.com/", listOf("who=A"))
    jarB.store("https://example.com/", listOf("who=B"))

    assertEquals("who=A", jarA.cookieHeaderFor("https://example.com/"))
    assertEquals("who=B", jarB.cookieHeaderFor("https://example.com/"))

    taskA.close()

    assertFailsWith<InactiveTaskEngineException> {
      jarA.cookieHeaderFor("https://example.com/")
    }
    assertFailsWith<InactiveTaskEngineException> {
      jarA.store("https://example.com/", listOf("leak=1"))
    }
    assertFailsWith<InactiveTaskEngineException> {
      jarA.scriptCookiesFor("https://example.com/")
    }

    // Task B remains unaffected
    assertEquals("who=B", jarB.cookieHeaderFor("https://example.com/"))

    // Re-opening task-A creates a fresh generation with an empty jar
    val taskA2 = engine.forTask("task-A")
    val jarA2 = engine.taskCookies("task-A")!!
    assertNull(jarA2.cookieHeaderFor("https://example.com/"))
    jarA2.store("https://example.com/", listOf("who=A2"))
    assertEquals("who=A2", jarA2.cookieHeaderFor("https://example.com/"))

    // Old jarA remains closed
    assertFailsWith<InactiveTaskEngineException> {
      jarA.cookieHeaderFor("https://example.com/")
    }

    taskA2.close()
    taskB.close()
    engine.close()
  }

  private class NoopHttpEngine : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      ServerInfo(contentLength = 0L, acceptRanges = false, etag = null, lastModified = null)

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {}

    override fun close() {}
  }
}
