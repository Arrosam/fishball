package org.areel.fishball.core.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reader against a real server, because the parts that break are not the regexes.
 *
 * A hand-rolled fake would exercise [HttpPageReader.readable] and nothing else, and every way
 * this has gone wrong before lived on the other side of that line - a header the host rejects,
 * a relative href resolved against the wrong base, a body decoded as the wrong charset. So the
 * pages are served over a socket, the way the ones on the internet are.
 */
class PageReaderTest {

    @Test
    fun `a page comes back as the words on it`() = served(
        """
        <html><head><title>布洛芬说明书</title><style>.x{color:red}</style></head>
        <body>
          <script>var ad = "买它";</script>
          <nav><a href="/index">首页</a></nav>
          <p>布洛芬用于缓解轻至中度疼痛。</p>
          <p>孕晚期&nbsp;禁用。</p>
          <a href="detail?id=7">详细说明</a>
          <a href="https://elsewhere.example/x">站外</a>
          <a href="#top">回到顶部</a>
        </body></html>
        """.trimIndent(),
    ) { page ->
        assertEquals("布洛芬说明书", page.title)
        assertFalse(page.failed)

        // The words survive; the stylesheet, the script and the tags do not.
        assertContains(page.text, "布洛芬用于缓解轻至中度疼痛。")
        assertContains(page.text, "孕晚期 禁用。")
        assertFalse(page.text.contains("买它"), "script text leaked into the page")
        assertFalse(page.text.contains("color:red"), "stylesheet leaked into the page")
        assertFalse(page.text.contains("<"), "a tag survived: " + page.text.take(120))

        // Paragraphs stay apart. Run together they read as one sentence, and a quotation
        // sliced across the join is a sentence neither paragraph contains.
        assertTrue(
            page.text.indexOf("孕晚期") > page.text.indexOf("\n"),
            "paragraphs ran together: " + page.text,
        )
    }

    @Test
    fun `links come back absolute, and only the ones worth following`() = served(
        """
        <html><body>
          <a href="detail?id=7">详细说明</a>
          <a href="/notice">通知原文</a>
          <a href="https://elsewhere.example/x">站外</a>
          <a href="#top">回到顶部</a>
          <a href="javascript:void(0)">展开</a>
          <a href="/notice">通知原文（重复）</a>
        </body></html>
        """.trimIndent(),
    ) { page ->
        val urls = page.links.map { it.url }

        // Relative hrefs resolved against the page they were found on, not against nothing.
        assertTrue(urls.any { it.endsWith("/detail?id=7") }, urls.toString())
        assertTrue(urls.any { it.endsWith("/notice") }, urls.toString())
        assertContains(urls, "https://elsewhere.example/x")

        // An anchor goes nowhere and a javascript: href is not a page.
        assertFalse(urls.any { it.contains("#top") }, urls.toString())
        assertFalse(urls.any { it.startsWith("javascript") }, urls.toString())

        // The same destination twice is one link, however many times the nav bar repeats it.
        assertEquals(urls.size, urls.distinct().size, urls.toString())
    }

    @Test
    fun `a page that will not open says so instead of throwing`() {
        val reader = HttpPageReader()
        runBlocking {
            // Nothing is listening on this port, and the turn has to survive that: a dead link
            // in a result list is ordinary, and it must come back as one bad result rather than
            // as an exception that takes the whole turn down.
            val dead = reader.read("http://127.0.0.1:1/whatever")
            assertTrue(dead.failed)
            assertTrue(dead.text.isEmpty())

            val notAPage = reader.read("not a url at all")
            assertTrue(notAPage.failed)
        }
    }

    @Test
    fun `an error status is a failure, not an empty page`() {
        val server = ServerSocket(0)
        runBlocking {
            launch(Dispatchers.IO) {
                runCatching { server.accept().use { reply(it, "404 Not Found", "<html>nope</html>") } }
            }
            val page = HttpPageReader().read("http://127.0.0.1:${server.localPort}/gone")
            assertTrue(page.failed)
            assertContains(page.reason.orEmpty(), "404")
        }
        server.close()
    }

    // ---- one page, served over a socket ---------------------------------------------------

    private fun served(html: String, check: (PageContent) -> Unit) {
        val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}/page"
        runBlocking {
            launch(Dispatchers.IO) {
                runCatching { server.accept().use { reply(it, "200 OK", html) } }
            }
            check(HttpPageReader().read(url))
        }
        server.close()
    }

    private fun reply(socket: Socket, status: String, html: String) {
        socket.getInputStream().read(ByteArray(8_192))
        val body = html.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 $status\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            write(body)
            flush()
        }
    }
}
