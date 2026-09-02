package org.areel.fishball.core.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import org.areel.fishball.core.notCancellation

/** A link on a page, so the agent can carry on from where it landed. */
data class PageLink(val text: String, val url: String)

/** What came back from opening one page. */
data class PageContent(
    val url: String,
    val title: String = "",
    val text: String = "",
    val links: List<PageLink> = emptyList(),
    val failed: Boolean = false,
    val reason: String? = null,
)

/**
 * Opening a page and reading it.
 *
 * A search result is a title and two lines of summary chosen by a search engine to look
 * relevant, and the difference between that and the page is the difference between a claim and
 * the evidence for it. This is how the agent gets the second one - and how a quotation gets
 * something real to be checked against, since a quote verified against a snippet can only ever
 * be a quote of a snippet.
 */
interface PageGateway {
    suspend fun read(url: String): PageContent
}

/**
 * HTML in, readable text and links out.
 *
 * Deliberately not a parser. A DOM library would be a dependency in `:core`, which is meant to
 * stay pure JVM and testable, to do a job that four substitutions and a whitespace collapse do
 * well enough: what the model needs is the sentences, and what it does not need is any of the
 * structure they arrived in.
 */
class HttpPageReader(
    private val http: HttpClient = defaultClient(),
) : PageGateway {

    override suspend fun read(url: String): PageContent = try {
        val response = http.get(url) { header("User-Agent", USER_AGENT) }
        val kind = response.contentType()?.withoutParameters()?.toString().orEmpty()
        if (!response.status.isSuccess()) {
            PageContent(url, failed = true, reason = "HTTP ${response.status.value}")
        } else if (kind.isNotEmpty() && READABLE.none { kind.startsWith(it) }) {
            // A drug leaflet published as a PDF is a real thing to land on, and decoding one as
            // UTF-8 produces several thousand characters of font tables that read, to a model,
            // like a page that exists and says nothing. Better to be told it is a PDF: then it
            // goes and finds the same leaflet somewhere in HTML, which live it did.
            PageContent(url, failed = true, reason = "不是网页，是 $kind")
        } else {
            val html = response.bodyAsText()
            PageContent(
                url = url,
                title = TITLE.find(html)?.groupValues?.get(1)?.let(::plain).orEmpty(),
                text = readable(html),
                links = links(html, url),
            )
        }
    } catch (e: Exception) {
        e.notCancellation()
        PageContent(url, failed = true, reason = e::class.simpleName ?: "unreadable")
    }

    /** The words, with everything that is not words taken out first. */
    private fun readable(html: String): String {
        var s = html
        // Order matters: script and style hold text that would otherwise survive tag-stripping
        // and read as content, and a page's stylesheet is not something anybody wants quoted.
        for (r in listOf(SCRIPT, STYLE, COMMENT, HEAD)) s = r.replace(s, " ")
        // Block edges become line breaks before the tags go, or every paragraph on the page
        // runs into the next one as a single sentence.
        s = BREAK.replace(s, "\n")
        s = TAG.replace(s, " ")
        return plain(s)
    }

    private fun links(html: String, base: String): List<PageLink> =
        ANCHOR.findAll(html)
            .mapNotNull { m ->
                val href = absolute(m.groupValues[1].trim(), base) ?: return@mapNotNull null
                val text = plain(TAG.replace(m.groupValues[2], " "))
                if (text.length < 2 || text.length > 80) null else PageLink(text, href)
            }
            // Same destination twice is one link however many times it appears in the nav bar.
            .distinctBy { it.url }
            .filterNot { it.url == base }
            .toList()

    /** Relative hrefs resolved against the page, and anything that is not http dropped. */
    private fun absolute(href: String, base: String): String? {
        if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) return null
        return runCatching {
            val resolved = URI(base).resolve(href).toString()
            resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.getOrNull()
    }

    private fun plain(raw: String): String {
        var s = raw
        for ((from, to) in ENTITIES) s = s.replace(from, to)
        s = AMPERSAND.replace(s) { "" }
        return s.replace(SPACES, " ").replace(BLANK_LINES, "\n").trim()
    }

    private companion object {
        // The search instance rejects a bare client, and so do plenty of the pages it finds.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"

        val SCRIPT = Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val STYLE = Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val HEAD = Regex("<head\\b[^>]*>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val BREAK = Regex("</(p|div|li|tr|h[1-6]|section|article|br)\\s*>|<br\\s*/?>", RegexOption.IGNORE_CASE)
        val TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
        val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val ANCHOR = Regex("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val SPACES = Regex("[ \\t\\u00a0]+")
        val BLANK_LINES = Regex("\\s*\\n\\s*(\\n\\s*)+")
        val AMPERSAND = Regex("&[a-zA-Z#0-9]{1,8};")

        /** What is worth running through a tag stripper. Everything else is bytes. */
        val READABLE = listOf("text/html", "application/xhtml", "text/plain")

        val ENTITIES = listOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
            "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—",
            "&ndash;" to "–", "&hellip;" to "…", "&middot;" to "·",
        )

        fun defaultClient() = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                // A page is one round trip and the agent is waiting on it; a slow host should
                // become "could not read that one" rather than a stalled turn.
                requestTimeoutMillis = 25_000
            }
        }
    }
}
