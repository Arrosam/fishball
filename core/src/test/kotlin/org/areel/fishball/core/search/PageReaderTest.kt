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

    /**
     * The pictures, minus the furniture that outnumbers them.
     *
     * An answer may show one of these, and the URL has to be one the page really carried - so
     * what this pins is not that images are found but that the *wrong* ones are not. A logo
     * offered as a photograph of a drug box is worse than no picture at all.
     */
    @Test
    fun `pictures come back and the page furniture does not`() = served(
        """
        <html><body>
          <img src="/logo.png" alt="站标">
          <img src="/static/icons/share.png" alt="分享">
          <img src="/i/avatar-3.jpg" alt="">
          <img src="https://count.example/p.gif" width="1" height="1">
          <img src="/img/diagram.svg" alt="示意图">
          <img src="pics/box.jpg" alt="布洛芬药盒">
          <img data-src="/pics/tablet.jpg" src="/static/placeholder.png" alt="药片">
          <img src="pics/box.jpg" alt="重复的">
          <img src="data:image/png;base64,AAAA" alt="内嵌">
        </body></html>
        """.trimIndent(),
    ) { page ->
        val urls = page.images.map { it.url }

        // The two real photographs, both absolute against the page they were on.
        assertTrue(urls.any { it.endsWith("/pics/box.jpg") }, urls.toString())
        assertTrue(urls.any { it.endsWith("/pics/tablet.jpg") }, urls.toString())
        assertEquals("布洛芬药盒", page.images.first { it.url.endsWith("box.jpg") }.alt)

        // The lazy-loaded one is the picture, not the placeholder it is sitting on. Reading
        // `src` first would have collected a grey box on every page that lazy-loads.
        assertFalse(urls.any { it.contains("placeholder") }, urls.toString())

        // Chrome, counters, vectors and inline bytes are all not pictures of anything.
        assertFalse(urls.any { it.contains("logo") }, urls.toString())
        assertFalse(urls.any { it.contains("share") }, urls.toString())
        assertFalse(urls.any { it.contains("avatar") }, urls.toString())
        assertFalse(urls.any { it.contains("count.example") }, urls.toString())
        assertFalse(urls.any { it.endsWith(".svg") }, urls.toString())
        assertFalse(urls.any { it.startsWith("data:") }, urls.toString())

        // And the same picture twice is one picture.
        assertEquals(urls.size, urls.distinct().size, urls.toString())
    }

    /** A word that merely contains a furniture word is not furniture. */
    @Test
    fun `a filename that only looks like chrome is kept`() = served(
        """
        <html><body>
          <img src="/pics/radiология.jpg" alt="x">
          <img src="/downloads/leaflet-page-1.jpg" alt="说明书第一页">
          <img src="/pics/iconic-brand-shot.jpg" alt="牌子">
        </body></html>
        """.trimIndent(),
    ) { page ->
        val urls = page.images.map { it.url }
        // "downloads" contains "ad"; "iconic" contains "icon". Both are photographs.
        assertTrue(urls.any { it.contains("leaflet-page-1") }, urls.toString())
        assertTrue(urls.any { it.contains("iconic-brand-shot") }, urls.toString())
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
