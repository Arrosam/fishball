package org.areel.fishball.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a quotation carries, which is a mention rather than the answer.
 *
 * The point of cutting it here rather than in the chip is that one string is both what the
 * reader sees above the field and what the model is sent with the question. A chip that
 * ellipsises for display while something longer goes out would be a difference nobody notices
 * until they are trying to work out what the model was actually asked.
 */
class QuotedTest {

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
