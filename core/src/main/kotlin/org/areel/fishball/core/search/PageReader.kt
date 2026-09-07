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

/**
 * A picture on a page, so the agent can show one it actually saw.
 *
 * The point is the "actually saw". An answer may put an image in front of the reader, and the
 * only URLs it is allowed to use are these - ones lifted off a page it opened. A model asked for
 * a picture with no list to choose from writes a plausible URL, and a plausible URL is a broken
 * image in an app whose whole argument is that it does not make things up.
 */
data class PageImage(
    /** The page's own `alt`, which is the only description of it anybody wrote. May be blank. */
    val alt: String,
    val url: String,
)

/** What came back from opening one page. */
data class PageContent(
    val url: String,
    val title: String = "",
    val text: String = "",
    val links: List<PageLink> = emptyList(),
    val images: List<PageImage> = emptyList(),
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
            // Capped before anything looks at it.
            //
            // The URL is the model's, and it may have come off a link list on a page rather
            // than out of a search result - so how big the response is, is decided by somebody
            // else. Uncapped, a large document is decoded whole and then copied again by each
            // of the substitutions in [readable], and every page read is held for the rest of
            // the turn so the quote verifier has something to check against. On a phone that
            // is how a followed link becomes an OutOfMemoryError.
            //
            // Truncated rather than refused: the front of a long article is the part that
            // says what it is about, and [PAGE_WINDOW] on the far side was only ever showing
            // the model a slice of this anyway.
            val html = response.bodyAsText().take(MAX_PAGE_CHARS)
            PageContent(
                url = url,
                title = TITLE.find(html)?.groupValues?.get(1)?.let(::plain).orEmpty(),
                text = readable(html),
                links = links(html, url),
                images = images(html, url),
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

    /**
     * The pictures on a page, minus the furniture.
     *
     * A page carries far more `<img>` than it has pictures: the logo, the sharing icons, the
     * avatar beside every comment, the 1×1 that counts the visit. Handing all of those to the
     * model would bury the one diagram worth showing and invite it to illustrate a drug leaflet
     * with a site logo, so this filters hard and keeps the order the page had - which on an
     * article is the order the writer put them in, best first.
     *
     * `data-src` as well as `src`, which is not a nicety: lazy-loading is the default on the
     * Chinese CMSes this app reads, and on those pages every real photograph is behind
     * `data-src` while `src` holds a grey placeholder. Reading only `src` would have found
     * nothing but spacers on exactly the sites that matter most here.
     */
    private fun images(html: String, base: String): List<PageImage> =
        IMG.findAll(html)
            .mapNotNull { tag ->
                val attrs = tag.value
                // The placeholder is in `src` on a lazy-loaded page, so the deferred attributes
                // are preferred rather than used as a fallback.
                val raw = listOf(DATA_SRC, DATA_ORIGINAL, SRC)
                    .firstNotNullOfOrNull { it.find(attrs)?.groupValues?.get(1)?.trim() }
                    ?: return@mapNotNull null
                if (tiny(attrs)) return@mapNotNull null
                val url = absolute(raw, base) ?: return@mapNotNull null
                if (!worthShowing(url)) return@mapNotNull null
                PageImage(
                    alt = ALT.find(attrs)?.groupValues?.get(1)?.let(::plain).orEmpty().take(ALT_MAX),
                    url = url,
                )
            }
            .distinctBy { it.url }
            .take(IMAGES_KEPT)
            .toList()

    /**
     * Whether a URL looks like a picture rather than part of the page's chrome.
     *
     * Matched on path segments and the filename stem, not as a substring of the whole URL. `ad`
     * inside `download`, `icon` inside `iconic`, and a host called `logos.example.com` are all
     * ways a substring test throws away the photograph it was pointed at.
     */
    private fun worthShowing(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("").lowercase()
        // SVG is dropped for a reason that is not editorial: the renderer on the other side
        // decodes bitmaps, and a vector arrives as a blank box. Almost every SVG on a page is
        // an icon anyway.
        if (path.endsWith(".svg")) return false
        val words = path.split('/', '.', '-', '_', '@').filter { it.isNotEmpty() }
        return words.none { it in FURNITURE }
    }

    /** A declared 1×1, which is a counter rather than a picture. */
    private fun tiny(attrs: String): Boolean {
        val w = DIM_W.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
        val h = DIM_H.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
        return (w != null && w <= TINY) || (h != null && h <= TINY)
    }

    /** Relative hrefs resolved against the page, and anything that is not http dropped. */
    private fun absolute(href: String, base: String): String? {
        if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) return null
        return runCatching {
            val resolved = URI(base).resolve(href).toString()
            resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.getOrNull()
    }

    /**
     * Entities out, characters in.
     *
     * The numeric forms are decoded rather than dropped. `&#20013;` and `&ldquo;` used to match
     * one catch-all `&…;` pattern and be replaced with nothing, which took the characters out of
     * the one string §25 promises a quotation is cut from character by character - and nothing
     * failed, because the model and the verifier both read the damaged copy. The reader was
     * simply shown a quote missing the source's own quotation marks.
     *
     * A named entity this does not know is left as it was written. Visible `&hellip;` is untidy;
     * a silently shorter sentence is a different sentence.
     */
    private fun plain(raw: String): String {
        var s = raw
        for ((from, to) in ENTITIES) s = s.replace(from, to)
        s = NUMERIC.replace(s) { m ->
            val hex = m.groupValues[1].isNotEmpty()
            val code = m.groupValues[2].toIntOrNull(if (hex) 16 else 10)
            // Out of range, or a surrogate half on its own, is left exactly as written.
            if (code == null || code !in 1..0x10FFFF || code in 0xD800..0xDFFF) {
                m.value
            } else {
                String(Character.toChars(code))
            }
        }
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

        /*
         * The whole tag, then its attributes one at a time.
         *
         * Not one pattern with src and alt in it: attribute order is the page author's, and a
         * pattern that fixes an order matches the half of the web that happens to agree with it.
         */
        val IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
        val SRC = Regex("\\ssrc=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val DATA_SRC = Regex("\\sdata-src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val DATA_ORIGINAL = Regex("\\sdata-original=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val ALT = Regex("\\salt=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        val DIM_W = Regex("\\swidth=[\"']?(\\d+)", RegexOption.IGNORE_CASE)
        val DIM_H = Regex("\\sheight=[\"']?(\\d+)", RegexOption.IGNORE_CASE)

        /** A declared edge at or under this is a tracking pixel or a spacer, not a picture. */
        const val TINY = 2

        /** As much of an `alt` as is a caption rather than a paragraph. */
        const val ALT_MAX = 120

        /**
         * Enough for the one worth showing, few enough that a gallery cannot flood the round.
         *
         * These go into the tool result the model reads, and a page of thumbnails has hundreds.
         */
        const val IMAGES_KEPT = 8

        /**
         * Path words that mean chrome. Matched whole, never as substrings - see [worthShowing].
         */
        val FURNITURE = setOf(
            "logo", "logos", "icon", "icons", "favicon", "sprite", "sprites",
            "avatar", "avatars", "spacer", "blank", "pixel", "placeholder",
            "ad", "ads", "advert", "banner", "button", "btn", "arrow", "emoji",
            "qrcode", "watermark", "loading",
        )
        val SPACES = Regex("[ \\t\\u00a0]+")
        val BLANK_LINES = Regex("\\s*\\n\\s*(\\n\\s*)+")

        /** `&#20013;` and `&#x4e2d;`, decoded. See [plain]. */
        val NUMERIC = Regex("&#([xX]?)([0-9a-fA-F]{1,6});")

        /**
         * The most of a page that is read.
         *
         * A long article is a few tens of thousands of characters of prose; this is well past
         * any of them and well short of what a phone cannot hold, and it bounds the copies the
         * substitutions in [readable] make as well as the string itself. It is a ceiling on
         * somebody else's server, not a view of how long a page should be.
         */
        const val MAX_PAGE_CHARS = 600_000

        /** What is worth running through a tag stripper. Everything else is bytes. */
        val READABLE = listOf("text/html", "application/xhtml", "text/plain")

        val ENTITIES = listOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
            "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—",
            "&ndash;" to "–", "&hellip;" to "…", "&middot;" to "·",
            // The curly quotes, because a CMS writes them this way and they are the marks a
            // quotation is delimited by - the one punctuation whose loss changes what a
            // quoted span looks like to the person checking it.
            "&ldquo;" to "“", "&rdquo;" to "”",
            "&lsquo;" to "‘", "&rsquo;" to "’",
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
