package org.areel.fishball.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.areel.fishball.ui.theme.Areel

/**
 * The small amount of Markdown an answer might arrive wearing.
 *
 * Hand-written rather than a dependency, and small on purpose. A full Markdown library brings
 * tables, footnotes, reference links and HTML passthrough to a bubble that shows a few
 * paragraphs of Chinese prose to somebody who has never heard of Markdown - and it would be a
 * third-party parser deciding what a medical answer looks like.
 *
 * What is here is what actually turns up: bold, headings, bullets, numbered lists, inline code,
 * and the horizontal rule a model reaches for when it thinks it is writing a document.
 *
 * The rendering is deliberately quiet. A heading is the body size in bold rather than a
 * display face, a bullet is a small square in the app's own vocabulary rather than a typographic
 * dot, and nothing changes colour. The point is that an answer which happens to arrive with
 * syntax in it reads as an answer rather than as a leak - not that the thread becomes a
 * document.
 *
 * There is no support for links, images, tables or block quotes. If one arrives its syntax is
 * left visible, which is the honest failure: better a stray `>` than a quotation silently
 * restyled into something the source did not say.
 */
object Markdown {

    /** Body size, so a heading is weight and space rather than scale. */
    fun render(text: String, body: TextUnit): AnnotatedString = buildAnnotatedString {
        val lines = text.split('\n')
        lines.forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            when {
                RULE.matches(raw.trim()) -> {
                    // A rule is a paragraph break somebody typed. The break is the useful part.
                    append(' ')
                }

                HEADING.matches(raw) -> {
                    val level = raw.takeWhile { it == '#' }.length
                    withHeading(body, level) { inline(raw.dropWhile { it == '#' }.trim()) }
                }

                BULLET.matches(raw) -> {
                    val indent = raw.takeWhile { it == ' ' }.length
                    append(" ".repeat(indent))
                    // The app's own bullet. A typographic dot would be the only round thing in
                    // a design that has none.
                    append("▪ ")
                    inline(raw.trimStart().drop(2).trim())
                }

                NUMBERED.matches(raw) -> {
                    val marker = NUMBERED.find(raw)?.groupValues?.get(1).orEmpty()
                    append(marker)
                    append(' ')
                    inline(raw.trimStart().drop(marker.length).trim())
                }

                else -> inline(raw)
            }
        }
    }

    /** Whether it is worth running at all — plain prose is the common case. */
    fun looksMarkedUp(text: String): Boolean = MARKERS.containsMatchIn(text)

    // ---- inline ---------------------------------------------------------------------------

    /**
     * Bold, code and strikethrough, left to right in one pass.
     *
     * A regex per style over the whole string would nest badly and reorder the output; walking
     * once and closing each span where it opens keeps the text in the order it was written,
     * which for an answer is the only thing that matters.
     */
    private fun androidx.compose.ui.text.AnnotatedString.Builder.inline(line: String) {
        var i = 0
        while (i < line.length) {
            val rest = line.substring(i)
            val opener = INLINE.entries.firstOrNull { (mark, _) ->
                rest.startsWith(mark) && rest.indexOf(mark, mark.length) > 0
            }
            if (opener == null) {
                append(line[i])
                i++
                continue
            }
            val (mark, style) = opener
            val close = rest.indexOf(mark, mark.length)
            withStyle(style) { append(rest.substring(mark.length, close)) }
            i += close + mark.length
        }
    }

    private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withHeading(
        body: TextUnit,
        level: Int,
        crossinline content: () -> Unit,
    ) {
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                // One step up for a top-level heading, nothing for the rest. A model that
                // writes ##### is not asking for five sizes.
                fontSize = if (level <= 1) (body.value + 2f).sp else body,
            ),
        ) { content() }
    }

    private val INLINE = linkedMapOf(
        "**" to SpanStyle(fontWeight = FontWeight.Bold),
        "~~" to SpanStyle(textDecoration = TextDecoration.LineThrough),
        "`" to SpanStyle(fontFamily = FontFamily.Monospace, color = Areel.Ink),
    )

    private val HEADING = Regex("""^#{1,6} +\S.*$""")
    private val BULLET = Regex("""^ *[-*] +\S.*$""")
    private val NUMBERED = Regex("""^ *(\d+[.、)]) +\S.*$""")
    private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")

    /** Cheap enough to run on every message; anything matching gets the full pass. */
    private val MARKERS = Regex("""(^|\n) *(#{1,6} |[-*] |\d+[.、)] |-{3,}$)|\*\*|~~|`""")
}
