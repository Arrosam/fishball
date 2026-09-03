package org.areel.fishball.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
 * A table is not drawn as a table. Three columns in a bubble the width of a phone is not a
 * table anybody can read, so each row is folded into a line that pairs every cell with its own
 * heading - 「布洛芬：孕期 孕晚期禁用；儿童 3个月以上」. Nothing is invented: the headings come
 * from the table's own first row. It is the one place here that rearranges content rather than
 * only restyling it, and it earns that by being the difference between a readable answer and a
 * column of pipes.
 *
 * A link keeps its words and drops its URL. Sources in this app are cards under the answer,
 * with a name, a host and a tier the registry assigned - a bare URL in the prose competes with
 * that and is not tappable anyway.
 *
 * Images are still unhandled, and remain visible as syntax. There is nothing useful to do with
 * one: the bytes are not here, and silently deleting an `![...]` would hide that the model
 * tried to show something.
 */
object Markdown {

    /** Body size, so a heading is weight and space rather than scale. */
    fun render(text: String, body: TextUnit): AnnotatedString = buildAnnotatedString {
        // Split on \n and a Windows line keeps its \r, which then defeats every $-anchored
        // pattern below - a heading, a rule and a table separator would all render on one
        // machine and not another.
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // Carried across lines, because three constructs are not decidable from one: a table
        // row needs the heading row above it, a fence needs to know it is open, and a
        // separator row needs to be recognised so it can be dropped.
        var headings: List<String> = emptyList()
        var fenced = false
        var first = true

        lines.forEach { raw ->
            if (FENCE.matches(raw.trim())) {
                fenced = !fenced
                // The fence itself is punctuation for a machine. What it wrapped is the content.
                return@forEach
            }
            if (!first) append('\n')
            first = false
            if (fenced) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(raw) }
                return@forEach
            }
            if (TABLE_RULE.matches(raw.trim())) {
                // The |---|---| under a heading row. It exists to tell a renderer where the
                // heading ended, and this one already knows.
                first = true
                return@forEach
            }
            if (TABLE_ROW.matches(raw.trim())) {
                val cells = cellsOf(raw)
                if (headings.isEmpty()) {
                    headings = cells
                    // Through [inline] like every data cell below. A model that writes a table
                    // usually bolds its heading row, and appending it raw was the one place in
                    // here that showed the reader `**药名**`.
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        cells.forEachIndexed { i, cell ->
                            if (i > 0) append('、')
                            inline(cell)
                        }
                    }
                } else {
                    row(cells, headings)
                }
                return@forEach
            }
            headings = emptyList()
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

                QUOTE.matches(raw) -> {
                    // Indented and left in its own words. It is somebody being quoted, and the
                    // app has its own mark for a verified quotation - this is only prose.
                    append("    ")
                    inline(raw.trimStart().removePrefix(">").trim())
                }

                else -> inline(raw)
            }
        }
    }

    /**
     * One table row, folded into a sentence.
     *
     * The first cell is the thing; the rest are pairs of heading and value. On a phone a
     * three-column table is a column of pipes, and this is the same information in the order a
     * person would say it.
     */
    private fun androidx.compose.ui.text.AnnotatedString.Builder.row(
        cells: List<String>,
        headings: List<String>,
    ) {
        if (cells.isEmpty()) return
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(cells.first()) }
        val rest = cells.drop(1)
        if (rest.isEmpty()) return
        append("：")
        rest.forEachIndexed { i, cell ->
            if (i > 0) append("；")
            headings.getOrNull(i + 1)?.takeIf { it.isNotBlank() }?.let {
                append(it)
                append(' ')
            }
            inline(cell)
        }
    }

    private fun cellsOf(raw: String): List<String> =
        raw.trim().trim('|').split('|').map { it.trim() }

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
        // The words out of a link, before anything else looks at the line: `[维基百科](http…)`
        // would otherwise be a bracket, some text, and a URL full of characters that mean
        // something to this parser.
        val text = LINK.replace(line) { it.groupValues[1] }
        var i = 0
        while (i < text.length) {
            // Indexed rather than re-sliced. `text.substring(i)` once per character copies the
            // rest of the answer per character of it, and an answer is a few thousand.
            val opener = INLINE.entries.firstOrNull { (mark, _) ->
                opensAt(text, i, mark) && closerFrom(text, i, mark) >= 0
            }
            if (opener == null) {
                append(text[i])
                i++
                continue
            }
            val (mark, style) = opener
            val close = closerFrom(text, i, mark)
            withStyle(style) { append(text, i + mark.length, close) }
            i = close + mark.length
        }
    }

    /**
     * Whether [mark] opens an emphasis span at [at].
     *
     * Two things beyond the characters lining up. A one-character marker has to sit on a word
     * boundary — see [opensHere]. And no marker may be followed by a space: 「体重 * 15」 is a
     * multiplication sign with room around it, not the start of an italic, and reading it as
     * one takes the sign out of a dose. A marker at the very end of the line opens nothing.
     */
    private fun opensAt(text: String, at: Int, mark: String): Boolean {
        if (!text.startsWith(mark, at)) return false
        val after = text.getOrNull(at + mark.length) ?: return false
        if (after.isWhitespace()) return false
        return mark.length > 1 || opensHere(text, at)
    }

    /**
     * Where the span opened by [mark] at [at] closes, or -1 if nothing here closes it.
     *
     * Candidates are walked rather than taken. The first occurrence of the marker is often not
     * a closer at all, and abandoning on it would stop `_a_b_` matching anything; skipping it
     * finds the one that does.
     *
     * A candidate has to leave something between the two markers. A zero-length span is never
     * emphasis, and the case that matters is an unclosed `**` at the start of a line: the pair's
     * own second asterisk sits one character away, and closing on it made both of them vanish
     * with nothing to show the model had written anything there.
     *
     * And it has to end a word rather than sit inside one — see [closesHere].
     */
    private fun closerFrom(text: String, at: Int, mark: String): Int {
        // One past the opener, so the span cannot be empty.
        var from = at + mark.length + 1
        while (from <= text.length - mark.length) {
            val found = text.indexOf(mark, from)
            if (found < 0) return -1
            if (mark.length > 1 || closesHere(text, found + mark.length)) return found
            from = found + 1
        }
        return -1
    }

    /**
     * The mirror of [opensHere], on the other end of the span.
     *
     * A one-character marker followed by an ASCII alphanumeric is inside a word — the `_`
     * between `a` and `b` in `a_b.html` — and closing on it deletes a character out of an
     * address or an identifier, the same way opening on one did. ASCII for the same reason:
     * 和 is a letter, and guarding on it would stop emphasis closing in the language this app
     * answers in.
     */
    private fun closesHere(text: String, after: Int): Boolean {
        val c = text.getOrNull(after) ?: return true
        return !(c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z')
    }

    /**
     * Whether a one-character marker at [at] is emphasis or just a character in a word.
     *
     * This is the guard that keeps 「每天 2*500 mg」 from rendering as 「每天 2500 mg」. A single
     * `*` or `_` between two ASCII alphanumerics is arithmetic, a file name, an address or an
     * identifier - `read_page`, `yaopin_zhuce_guanli.html`, `3*7` - and reading it as emphasis
     * deletes characters out of the middle of them. Out of a dose, that is a different
     * instruction to somebody holding a packet of pills, which makes it the worst thing in this
     * file and the reason the rule is here rather than in a comment saying it rarely happens.
     *
     * The test is ASCII deliberately, not [Char.isLetterOrDigit], which is true of 和 and every
     * other CJK character - guarding on those would stop emphasis working in the language this
     * app answers in. Numbers, URLs and identifiers are ASCII; the prose around them is not.
     *
     * Two-character markers skip this: `**` and `~~` do not appear inside words, and requiring
     * a boundary would break 「是**孕晚期禁用**」 written without a space.
     */
    private fun opensHere(text: String, at: Int): Boolean {
        val before = text.getOrNull(at - 1) ?: return true
        return !(before in '0'..'9' || before in 'a'..'z' || before in 'A'..'Z')
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

    /**
     * Longest marker first, because `**` and `*` share a prefix.
     *
     * A map iterated in insertion order is the whole of the precedence rule: `**粗体**` has to
     * be tested against `**` before `*`, or the opener matches one asterisk, closes on the
     * second, and emits an empty italic span followed by the text as plain.
     */
    private val INLINE = linkedMapOf(
        "**" to SpanStyle(fontWeight = FontWeight.Bold),
        "~~" to SpanStyle(textDecoration = TextDecoration.LineThrough),
        "*" to SpanStyle(fontStyle = FontStyle.Italic),
        "_" to SpanStyle(fontStyle = FontStyle.Italic),
        "`" to SpanStyle(fontFamily = FontFamily.Monospace, color = Areel.Ink),
    )

    /**
     * `[words](url)` keeps the words.
     *
     * The lookbehind is the whole of the difference between a link and an image. Without it
     * `![说明书](…)` matches from the bracket onward, the words and the address are replaced,
     * and all that survives is a lone `!` - which is worse than either rendering it or leaving
     * it alone, because it hides that anything was there. A test caught this.
     */
    private val LINK = Regex("""(?<!!)\[([^\]]+)]\((?:[^)]*)\)""")

    private val HEADING = Regex("""^#{1,6} +\S.*$""")
    private val BULLET = Regex("""^ *[-*] +\S.*$""")
    private val NUMBERED = Regex("""^ *(\d+[.、)]) +\S.*$""")
    private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
    private val QUOTE = Regex("""^ *> ?.*$""")
    private val FENCE = Regex("""^(```|~~~).*$""")

    /** A row is anything fenced by pipes; the rule under a heading row is only dashes. */
    private val TABLE_ROW = Regex("""^\|.*\|$""")
    private val TABLE_RULE = Regex("""^\|[\s|:-]+\|$""")

    /** Cheap enough to run on every message; anything matching gets the full pass. */
    private val MARKERS =
        Regex("""(^|\n) *(#{1,6} |[-*] |\d+[.、)] |>|\||-{3,}$|```)|\*\*|~~|[*_`]|\[[^\]]+]\(""")
}
