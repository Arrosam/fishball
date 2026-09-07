package org.areel.fishball.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a quotation carries: the answer, and separately the few words that stand for it.
 *
 * [Quoted.text] is the whole answer - what the log keeps and what the model is handed, because
 * the model is being asked to reason about it. [Quoted.words] is its opening, for the chip above
 * the field and for the bubble once it is sent, which are both only saying *which* answer.
 *
 * They were one string once, on the argument that what is shown should be what is sent. That is
 * right for a chip standing in for a file and wrong here: it meant the model received forty
 * characters of an answer and the rest cut off mid-sentence.
 */
class QuotedTest {

    /** Whatever the chip shows, the answer itself is carried untouched. */
    @Test
    fun `the whole answer is kept however long it is`() {
        val whole = "孕晚期禁用。\n\n▪ 常见是 0.3g\n" + "布".repeat(500)
        assertEquals(whole, Quoted.of(whole).text, "the answer was trimmed on the way to the log")
    }

    @Test
    fun `a short answer is quoted whole, with nothing added`() {
        val short = "孕晚期禁用。"
        assertEquals(short, Quoted.of(short).words)
        assertTrue("…" !in Quoted.of(short).words, "a cut was marked where nothing was cut")
    }

    @Test
    fun `a long answer is cut, and the cut is visible`() {
        val long = "布".repeat(200)
        val words = Quoted.of(long).words
        assertEquals(Quoted.MENTION_CHARS + 1, words.length, words)
        assertTrue(words.endsWith("…"), "the answer was cut with no sign of it: $words")
    }

    /**
     * The chip is one line, and an answer is not.
     *
     * Line breaks and the app's own bullet are layout - they mean something in a plate two
     * hundred pixels wide and nothing in a chip. Left in, the mention reads as a sentence with
     * a ▪ dropped into the middle of it.
     */
    @Test
    fun `layout is flattened out of the mention`() {
        val laid = "孕晚期禁用。\n\n▪ 常见是 0.3g\n▪ 缓释胶囊另算"
        val words = Quoted.of(laid).words
        assertTrue("\n" !in words, "a line break reached the chip: $words")
        assertTrue("▪" !in words, "a bullet reached the chip: $words")
        assertEquals("孕晚期禁用。 常见是 0.3g 缓释胶囊另算", words)
    }

    /** Nothing quotable is still a Quoted, and an empty chip is better than a crash. */
    @Test
    fun `an empty answer quotes to nothing rather than throwing`() {
        assertEquals("", Quoted.of("   \n  ").words)
    }
}
