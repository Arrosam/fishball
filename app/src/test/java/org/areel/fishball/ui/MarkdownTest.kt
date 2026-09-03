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
    fun `syntax this does not support is left as it was written`() {
        // A block quote could restyle a quotation into something the source did not say, so it
        // is deliberately not handled — and being visibly unhandled is the honest outcome.
        val quote = "> 世卫组织建议每天不超过5克"
        assertEquals(quote, Markdown.render(quote, body).text)
    }

    @Test
    fun `a checkpoint's own headings are not markdown, and pass through untouched`() {
        // The session bridge is written with 【】 precisely so it never demonstrates Markdown
        // to a model told not to write it. If that ever regresses to `##`, this notices.
        val bridge = "【他问过什么】\n布洛芬能不能吃"
        assertEquals(bridge, Markdown.render(bridge, body).text)
    }
}
