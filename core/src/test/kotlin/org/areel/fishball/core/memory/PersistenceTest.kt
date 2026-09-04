package org.areel.fishball.core.memory

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.areel.fishball.core.answer.AnswerShape
import org.areel.fishball.core.trust.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That the thread survives the app being closed.
 *
 * Tested here rather than on a device because the failure it guards against is invisible until
 * someone reopens the app the next morning and finds an empty screen — which is exactly when
 * nobody is watching a log.
 */
class PersistenceTest {

    /**
     * The panel under an answer survives being closed.
     *
     * It did not: the reasoning and the narrated lines lived in the view model and nowhere else,
     * so every answer came back from the log with a blank panel under it. Reported as "thinking
     * block looks like will be lost when reload the conversation" - it was in RAM, not in the
     * history. The search summaries go through the same field now, so this covers both.
     */
    @Test
    fun `the working panel comes back with the turn`() {
        val io = Buffer()
        PersistentStore(io).appendTurn(
            ConversationTurn(
                id = 7,
                sessionId = 1,
                at = 1_700_000_000_000,
                speaker = Speaker.ASSISTANT,
                text = "对乙酰氨基酚更安全。",
                reasoning = "说明书把孕晚期列为禁忌，其余孕期只是慎用。",
                steps = listOf("正在查……", "查了：布洛芬 孕妇\n看了 12 条"),
            ),
        )

        val back = PersistentStore(Buffer(io.contents)).recentTurns().single()
        assertEquals("说明书把孕晚期列为禁忌，其余孕期只是慎用。", back.reasoning)
        assertEquals(2, back.steps.size)
        assertTrue(back.steps[1].startsWith("查了："), "the search summary was lost: " + back.steps)
    }

    /**
     * And what the answer looked up, with what memory added on the way.
     *
     * These go back to the model on every later turn of the session, so a log that dropped them
     * would replay a reopened conversation with the looking cut out of every answer - the
     * failure `ToolHistoryAcrossTurnsTest` pins, one restart later.
     */
    @Test
    fun `the looking comes back with the turn`() {
        val io = Buffer()
        PersistentStore(io).appendTurn(
            ConversationTurn(
                id = 8,
                sessionId = 1,
                at = 1_700_000_000_000,
                speaker = Speaker.ASSISTANT,
                text = "孕晚期禁用。",
                rounds = listOf(
                    ToolRound(
                        exchanges = listOf(
                            ToolExchange(
                                id = "toolu_01",
                                name = "search",
                                input = buildJsonObject { putJsonArray("queries") { add("布洛芬 孕妇") } },
                                result = "[0] 国家药监局（等级：权威）",
                            ),
                            ToolExchange(
                                id = "toolu_02",
                                name = "read_page",
                                input = buildJsonObject { put("url", "https://www.nmpa.gov.cn/x") },
                                result = "这一页打不开",
                                isError = true,
                            ),
                        ),
                        known = listOf("- [关于他] 对青霉素过敏"),
                    ),
                ),
            ),
        )

        val back = PersistentStore(Buffer(io.contents)).recentTurns().single()
        val round = back.rounds.single()
        assertEquals(listOf("toolu_01", "toolu_02"), round.exchanges.map { it.id })
        assertEquals(
            "布洛芬 孕妇",
            round.exchanges[0].input["queries"]?.jsonArray?.single()?.jsonPrimitive?.content,
        )
        assertEquals("[0] 国家药监局（等级：权威）", round.exchanges[0].result)
        assertTrue(round.exchanges[1].isError, "a failed result came back as a good one")
        assertEquals(listOf("- [关于他] 对青霉素过敏"), round.known)
    }

    private class Buffer(var contents: String? = null) : SnapshotIo {
        override fun read() = contents
        override fun write(contents: String) {
            this.contents = contents
        }
    }

    private fun turn(id: Long, speaker: Speaker, text: String, shape: AnswerShape? = null) =
        ConversationTurn(
            id = id,
            sessionId = 1,
            at = id * 1000,
            speaker = speaker,
            text = text,
            shape = shape,
            sources = if (shape == null) {
                emptyList()
            } else {
                listOf(
                    CitedSource(
                        url = "https://www.apple.com.cn/iphone/",
                        displayName = "苹果官网",
                        tier = Tier.AUTHORITATIVE,
                        quote = "电池容量 3582mAh",
                    ),
                )
            },
        )

    @Test
    fun `a thread written by one instance is read back by the next`() {
        val disk = Buffer()
        PersistentStore(disk).apply {
            appendTurn(turn(1, Speaker.USER, "iPhone 17 Pro 电池容量多少？"))
            appendTurn(turn(2, Speaker.ASSISTANT, "3582mAh。", AnswerShape.CONFIDENT))
        }

        val reopened = PersistentStore(disk).recentTurns()
        assertEquals(2, reopened.size)
        assertEquals("iPhone 17 Pro 电池容量多少？", reopened[0].text)
        assertEquals(Speaker.ASSISTANT, reopened[1].speaker)
    }

    /**
     * The meter and the source card are drawn from these. Without them a reopened thread would
     * render its answers as bare paragraphs — technically the same words, but stripped of the
     * one thing this app claims to do.
     */
    @Test
    fun `an answer keeps its shape and its citations`() {
        val disk = Buffer()
        PersistentStore(disk).appendTurn(turn(1, Speaker.ASSISTANT, "3582mAh。", AnswerShape.CONFIDENT))

        val restored = PersistentStore(disk).recentTurns().single()
        assertEquals(AnswerShape.CONFIDENT, restored.shape)
        val source = restored.sources.single()
        assertEquals("苹果官网", source.displayName)
        assertEquals(Tier.AUTHORITATIVE, source.tier)
        assertEquals("电池容量 3582mAh", source.quote)
    }

    @Test
    fun `the order it was said in is the order it comes back`() {
        val disk = Buffer()
        PersistentStore(disk).apply {
            (1L..5L).forEach { appendTurn(turn(it, Speaker.USER, "问题 $it")) }
        }
        assertEquals(
            listOf("问题 1", "问题 2", "问题 3", "问题 4", "问题 5"),
            PersistentStore(disk).recentTurns().map { it.text },
        )
    }

    @Test
    fun `only the tail is read back, oldest dropped first`() {
        val disk = Buffer()
        PersistentStore(disk).apply {
            (1L..10L).forEach { appendTurn(turn(it, Speaker.USER, "问题 $it")) }
        }
        val tail = PersistentStore(disk).recentTurns(limit = 3)
        assertEquals(listOf("问题 8", "问题 9", "问题 10"), tail.map { it.text })
    }

    /**
     * Reading a file written before answers carried their shape. Losing the meter on old turns
     * is acceptable; refusing to open is not, and the whole log lives in this one file.
     */
    @Test
    fun `a file from an older version still loads`() {
        val old = Buffer(
            """
            {"world":[],"preferences":[],"idSeq":7,
             "turns":[{"id":1,"sessionId":1,"at":1000,"speaker":"USER","text":"以前问过的"}]}
            """.trimIndent(),
        )
        val restored = PersistentStore(old).recentTurns().single()
        assertEquals("以前问过的", restored.text)
        assertNull(restored.shape)
        assertTrue(restored.sources.isEmpty())
    }

    /**
     * A half-written or corrupt file must not stop the app starting. Memory is worth a lot
     * less than being able to ask the next question.
     */
    @Test
    fun `a corrupt file is survived, not thrown`() {
        val store = PersistentStore(Buffer("{ this is not json"))
        assertTrue(store.recentTurns().isEmpty())
        assertNotNull(store.appendTurn(turn(1, Speaker.USER, "还能用吗")))
    }

    /** Ids handed out before a crash must not be handed out again. */
    @Test
    fun `the id sequence never rewinds`() {
        val disk = Buffer()
        PersistentStore(disk).apply { (1L..4L).forEach { appendTurn(turn(it, Speaker.USER, "x")) } }
        assertTrue(PersistentStore(disk).nextId() > 4L)
    }
}
