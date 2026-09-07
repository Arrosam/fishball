package org.areel.fishball.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an answer looks like after the syntax is taken out of it.
 *
 * The prompt asks for plain text and mostly gets it. "Mostly" is why this exists: an answer that
 * arrives with `**孕晚期禁用**` in it should read as an answer, not as a leak, and the failure
 * before this was that the asterisks were shown to somebody who has never heard of Markdown.
 *
 * The assertions are all about the *text* rather than the styling, because the text is what a
 * person reads and what they would paste into a message to somebody else. A bold span that
 * renders slightly wrong is a blemish; a stray `**` is the app looking broken.
 */
class MarkdownTest {

    private val body = 16.sp

    @Test
    fun `plain prose is left exactly alone`() {
        val plain = "可以吃。国家药监局的说明书里写的是孕晚期禁用。"
        assertTrue(!Markdown.looksMarkedUp(plain), "plain prose was sent through the parser")
        assertEquals(plain, Markdown.render(plain, body).text)
    }

    @Test
    fun `the markers come out and the words stay`() {
        val marked = "**孕晚期禁用**，其余孕周`慎用`，~~可以随便吃~~"
        val out = Markdown.render(marked, body).text
        assertEquals("孕晚期禁用，其余孕周慎用，可以随便吃", out)
        assertTrue("*" !in out && "`" !in out && "~" !in out, "syntax survived: $out")
    }

    @Test
    fun `bold is actually bold, not just stripped`() {
        val rendered = Markdown.render("**孕晚期禁用**", body)
        val bold = rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold }
        assertTrue(bold, "the markers were removed without styling what they marked")
    }

    @Test
    fun `headings lose their hashes and keep their words`() {
        val out = Markdown.render("## 用药建议\n每天不超过三次。", body).text
        assertTrue("#" !in out, "a hash reached the reader: $out")
        assertTrue(out.startsWith("用药建议"), "the heading text was lost: $out")
        assertTrue("每天不超过三次。" in out, "the body was lost: $out")
    }

    @Test
    fun `bullets become the app's own mark`() {
        val out = Markdown.render("- 布洛芬\n- 对乙酰氨基酚", body).text
        assertTrue("- " !in out, "a dash reached the reader: $out")
        assertEquals(2, out.count { it == '▪' }, "not one mark per bullet: $out")
        assertTrue("布洛芬" in out && "对乙酰氨基酚" in out, "an item was lost: $out")
    }

    @Test
    fun `a numbered list keeps its numbers`() {
        val out = Markdown.render("1. 先看说明书\n2. 再问医生", body).text
        assertTrue(out.startsWith("1."), "the numbering was eaten: $out")
        assertTrue("再问医生" in out, "an item was lost: $out")
    }

    @Test
    fun `an unclosed marker is left visible rather than eating the rest`() {
        // The dangerous failure: treating a lone ** as an opener and swallowing everything
        // after it. A stray asterisk is untidy; a truncated medical answer is not.
        val out = Markdown.render("剂量是 500**毫克，每天三次。", body).text
        assertTrue("毫克，每天三次。" in out, "text after a lone marker was swallowed: $out")
    }

    @Test
    fun `a quote keeps its words and loses its arrow`() {
        val out = Markdown.render("> 世卫组织建议每天不超过5克", body).text
        assertTrue(">" !in out, "the arrow reached the reader: $out")
        assertTrue("世卫组织建议每天不超过5克" in out, "the quotation was lost: $out")
    }

    @Test
    fun `a link keeps its words and drops its address`() {
        // Sources are cards under the answer, with a name and a tier. A bare URL in the prose
        // competes with that and is not tappable anyway.
        val out = Markdown.render("参考 [维基百科](https://zh.wikipedia.org/wiki/布洛芬) 的说法。", body).text
        assertEquals("参考 维基百科 的说法。", out)
    }

    @Test
    fun `italic works in both spellings, and does not eat bold`() {
        assertEquals("斜体和另一种", Markdown.render("*斜体*和_另一种_", body).text)
        // The precedence that matters: ** must be tested before *, or bold opens on one
        // asterisk, closes on the second, and the words come out unstyled.
        val bold = Markdown.render("**粗体**", body)
        assertEquals("粗体", bold.text)
        assertTrue(
            bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold },
            "bold was parsed as two italics",
        )
    }

    @Test
    fun `a fence disappears and what it wrapped stays`() {
        val fenced = "剂量：\n```\n每公斤体重 10mg\n```"
        val out = Markdown.render(fenced, body).text
        assertTrue("`" !in out, "a backtick reached the reader: $out")
        assertTrue("每公斤体重 10mg" in out, "the code was lost: $out")
    }

    @Test
    fun `a table becomes lines a phone can read`() {
        val table = "| 药名 | 孕期 |\n|---|---|\n| 布洛芬 | 孕晚期禁用 |"
        val out = Markdown.render(table, body).text
        assertTrue("|" !in out, "a pipe reached the reader: $out")
        assertTrue("-" !in out, "the separator row survived: $out")
        // Every cell paired with its own heading, taken from the table's own first row.
        assertTrue("布洛芬" in out && "孕期 孕晚期禁用" in out, "a cell lost its heading: $out")
    }

    @Test
    fun `an image is left visible rather than silently dropped`() {
        // There is nothing useful to do with one - the bytes are not here - and deleting it
        // would hide that the model tried to show something.
        val img = "![说明书](https://example.com/a.png)"
        assertTrue("![" in Markdown.render(img, body).text, "an image vanished without trace")
    }

    /**
     * A dose is not emphasis.
     *
     * The most dangerous thing this parser can do is quietly delete a character from a number.
     * 「每天 2*500 mg」 has two asterisks in it and is not asking for italics; read as markup it
     * becomes 「每天 2500 mg」, which is a different instruction to somebody holding a packet of
     * pills. Nothing else in this file matters as much as this case.
     */
    @Test
    fun `a dose keeps its asterisks`() {
        val dose = "每天 2*500 mg，连吃 3*7 天。"
        assertEquals(dose, Markdown.render(dose, body).text)
    }

    /**
     * A URL is not emphasis either, and underscores are common in them.
     *
     * Same failure, quieter: an address that loses two underscores still looks like an address.
     */
    @Test
    fun `an address keeps its underscores`() {
        val line = "详见 https://www.nmpa.gov.cn/yaopin_zhuce_guanli.html 这一页。"
        assertEquals(line, Markdown.render(line, body).text)
    }

    @Test
    fun `a snake_case name keeps its underscores`() {
        val line = "工具是 read_page 和 note_user 两个。"
        assertEquals(line, Markdown.render(line, body).text)
    }

    /**
     * Windows line endings.
     *
     * The lines are split on \n, so a \r rides along on the end of every one of them - and a
     * heading, a rule and a table separator are all matched with `$`-anchored patterns that a
     * trailing \r defeats. The result is markup that renders on one machine and not another.
     */
    @Test
    fun `CRLF is handled like LF`() {
        val crlf = Markdown.render("## 用药建议\r\n- 布洛芬\r\n> 世卫组织说", body).text
        val lf = Markdown.render("## 用药建议\n- 布洛芬\n> 世卫组织说", body).text
        assertEquals(lf, crlf, "a carriage return changed the rendering")
        assertTrue("\r" !in crlf, "a carriage return reached the reader")
        assertTrue("#" !in crlf && ">" !in crlf, "markup survived because of the \\r: $crlf")
    }

    // ---- pictures ---------------------------------------------------------------------------

    /** An answer with nothing in it is one piece, which is the path everything took before. */
    @Test
    fun `an answer with no picture is a single piece of prose`() {
        val plain = "孕晚期禁用。"
        val pieces = Markdown.pieces(plain)
        assertEquals(1, pieces.size, pieces.toString())
        assertEquals(plain, (pieces.single() as Markdown.Piece.Words).text)
    }

    @Test
    fun `a picture is lifted out and the prose closes around it`() {
        val answer = "这是它的包装：\n\n![布洛芬药盒](https://example.org/pics/box.jpg)\n\n认准这个牌子。"
        val pieces = Markdown.pieces(answer)

        assertEquals(3, pieces.size, pieces.toString())
        assertEquals("这是它的包装：", (pieces[0] as Markdown.Piece.Words).text)
        val picture = pieces[1] as Markdown.Piece.Picture
        assertEquals("https://example.org/pics/box.jpg", picture.url)
        assertEquals("布洛芬药盒", picture.alt)
        assertEquals("认准这个牌子。", (pieces[2] as Markdown.Piece.Words).text)

        // And the syntax does not also survive in the words. Rendering the picture *and*
        // leaving `![…]` in the prose would be the worst of both.
        assertTrue(
            pieces.filterIsInstance<Markdown.Piece.Words>().none { "![" in it.text },
            pieces.toString(),
        )
    }

    /** A picture at either end leaves no empty paragraph where it was. */
    @Test
    fun `a picture alone is the only piece`() {
        val pieces = Markdown.pieces("![](https://example.org/pics/x.jpg)")
        assertEquals(1, pieces.size, pieces.toString())
        assertEquals("", (pieces.single() as Markdown.Piece.Picture).alt)
    }

    /**
     * Anything that is not a fetchable address stays visible as syntax.
     *
     * The same bargain the rest of the file strikes. A reader who can see `![](图片1)` knows the
     * app failed to show something; a reader shown nothing does not, and neither does anybody
     * they report it to.
     */
    @Test
    fun `an address that cannot be fetched is left in the prose`() {
        listOf(
            "![图](图片1)",
            "![图](/local/path.jpg)",
            "![图](data:image/png;base64,AAAA)",
        ).forEach { answer ->
            val pieces = Markdown.pieces(answer)
            assertEquals(1, pieces.size, "$answer -> $pieces")
            assertTrue(
                "![" in (pieces.single() as Markdown.Piece.Words).text,
                "the syntax was silently deleted: $answer",
            )
        }
    }

    /** A link is still a link: only the words survive, and it never becomes a picture. */
    @Test
    fun `an ordinary link is not mistaken for a picture`() {
        val answer = "见[药监局说明](https://example.org/a)。"
        val pieces = Markdown.pieces(answer)
        assertEquals(1, pieces.size, pieces.toString())
        assertEquals("见药监局说明。", Markdown.render(answer, body).text)
    }

    @Test
    fun `a checkpoint's own headings are not markdown, and pass through untouched`() {
        // The session bridge is written with 【】 precisely so it never demonstrates Markdown
        // to a model told not to write it. If that ever regresses to `##`, this notices.
        val bridge = "【他问过什么】\n布洛芬能不能吃"
        assertEquals(bridge, Markdown.render(bridge, body).text)
    }
}
