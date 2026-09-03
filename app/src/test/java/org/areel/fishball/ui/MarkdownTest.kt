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

    @Test
    fun `a checkpoint's own headings are not markdown, and pass through untouched`() {
        // The session bridge is written with 【】 precisely so it never demonstrates Markdown
        // to a model told not to write it. If that ever regresses to `##`, this notices.
        val bridge = "【他问过什么】\n布洛芬能不能吃"
        assertEquals(bridge, Markdown.render(bridge, body).text)
    }
}
