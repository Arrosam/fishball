package org.areel.fishball.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a retry asks again, which is everything that produced the answer being retried.
 *
 * Not "the message above it". A turn can be fed by several: the question that started it, and
 * anything said into it while it ran - those were all handed to the same turn. Re-asking only
 * the nearest one would send a correction with no question, or a question with its correction
 * dropped, and the answer would differ for a reason nobody watching could see.
 */
class RetryTest {

    private fun user(text: String, images: List<String> = emptyList(), quoted: String? = null) =
        ChatMessage(fromUser = true, text = text, images = images, quoted = quoted)

    private fun answer(text: String, detail: String? = null) =
        ChatMessage(fromUser = false, text = text, detail = detail)

    @Test
    fun `one question feeds its own answer`() {
        val thread = listOf(user("布洛芬孕妇能吃吗"), answer("孕晚期禁用。"))
        assertEquals(listOf("布洛芬孕妇能吃吗"), feeding(thread, 1).map { it.text })
    }

    /** The case the rule exists for: a correction said into a turn belongs to that turn. */
    @Test
    fun `a correction said mid-turn is re-asked with the question`() {
        val thread = listOf(
            user("布洛芬孕妇能吃吗"),
            user("别查孕妇了，就说儿童"),
            answer("儿童要看年龄。"),
        )
        assertEquals(
            listOf("布洛芬孕妇能吃吗", "别查孕妇了，就说儿童"),
            feeding(thread, 2).map { it.text },
            "the correction was dropped, so the retry asks a different question",
        )
    }

    /** And it stops at the previous answer rather than sweeping up the whole conversation. */
    @Test
    fun `an earlier exchange is not swept in`() {
        val thread = listOf(
            user("第一个问题"),
            answer("第一个答案"),
            user("第二个问题"),
            answer("第二个答案"),
        )
        assertEquals(listOf("第二个问题"), feeding(thread, 3).map { it.text })
    }

    /** Retrying an older failure gathers that failure's own question, not the newest one. */
    @Test
    fun `a failure part way up is retried with what fed it`() {
        val thread = listOf(
            user("第一个问题"),
            answer("这会儿连不上", detail = "UnknownHostException"),
            user("第二个问题"),
            answer("第二个答案"),
        )
        assertEquals(listOf("第一个问题"), feeding(thread, 1).map { it.text })
    }

    @Test
    fun `pictures and quotations come back with it`() {
        val thread = listOf(
            user("这个能吃吗", images = listOf("pic-1.jpg", "pic-2.jpg"), quoted = "孕晚期禁用。"),
            answer("看包装上的成分。"),
        )
        val feeding = feeding(thread, 1)
        assertEquals(listOf("pic-1.jpg", "pic-2.jpg"), feeding.flatMap { it.images })
        assertEquals(listOf("孕晚期禁用。"), feeding.mapNotNull { it.quoted })
    }

    /** Nothing to re-ask is not a crash and not a retry. */
    @Test
    fun `an answer with no question above it feeds nothing`() {
        assertTrue(feeding(listOf(answer("孤零零的答案")), 0).isEmpty())
        assertTrue(feeding(emptyList(), 0).isEmpty())
        assertTrue(feeding(listOf(user("在吗")), 7).isEmpty(), "an index off the end")
    }

    /** An empty bubble is not a question. A picture with no words still is. */
    @Test
    fun `blank messages are left out unless they carry a picture`() {
        val thread = listOf(
            user("", images = listOf("pic-1.jpg")),
            user("   "),
            answer("看包装。"),
        )
        assertEquals(listOf("pic-1.jpg"), feeding(thread, 2).flatMap { it.images })
        assertEquals(1, feeding(thread, 2).size, "a blank bubble was re-asked as a question")
    }
}
