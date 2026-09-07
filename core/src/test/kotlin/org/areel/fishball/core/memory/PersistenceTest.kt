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
        val disk = Disk()
        disk.open().appendTurn(
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

        val back = disk.open().recentTurns().single()
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
        val disk = Disk()
        disk.open().appendTurn(
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

        val back = disk.open().recentTurns().single()
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
        /** How many times the whole thing has been written out. The cost being measured. */
        var writes = 0
            private set

        override fun read() = contents
        override fun write(contents: String) {
            this.contents = contents
            writes++
        }
    }

    /**
     * What an install has on disk: a snapshot and a turn log, which are now two files.
     *
     * [open] is reopening the app over the same storage - the only honest way to test that
     * something written survives, now that where it survives depends on which of the two it
     * belongs in.
     */
    private class Disk(snapshot: String? = null) {
        val io = Buffer(snapshot)
        val turns = InMemoryTurnLog()

        fun open() = PersistentStore(io, turns)
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
        val disk = Disk()
        disk.open().apply {
            appendTurn(turn(1, Speaker.USER, "iPhone 17 Pro 电池容量多少？"))
            appendTurn(turn(2, Speaker.ASSISTANT, "3582mAh。", AnswerShape.CONFIDENT))
        }

        val reopened = disk.open().recentTurns()
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
        val disk = Disk()
        disk.open().appendTurn(turn(1, Speaker.ASSISTANT, "3582mAh。", AnswerShape.CONFIDENT))

        val restored = disk.open().recentTurns().single()
        assertEquals(AnswerShape.CONFIDENT, restored.shape)
        val source = restored.sources.single()
        assertEquals("苹果官网", source.displayName)
        assertEquals(Tier.AUTHORITATIVE, source.tier)
        assertEquals("电池容量 3582mAh", source.quote)
    }

    @Test
    fun `the order it was said in is the order it comes back`() {
        val disk = Disk()
        disk.open().apply {
            (1L..5L).forEach { appendTurn(turn(it, Speaker.USER, "问题 $it")) }
        }
        assertEquals(
            listOf("问题 1", "问题 2", "问题 3", "问题 4", "问题 5"),
            disk.open().recentTurns().map { it.text },
        )
    }

    @Test
    fun `only the tail is read back, oldest dropped first`() {
        val disk = Disk()
        disk.open().apply {
            (1L..10L).forEach { appendTurn(turn(it, Speaker.USER, "问题 $it")) }
        }
        val tail = disk.open().recentTurns(limit = 3)
        assertEquals(listOf("问题 8", "问题 9", "问题 10"), tail.map { it.text })
    }

    /**
     * Reading a file written before answers carried their shape. Losing the meter on old turns
     * is acceptable; refusing to open is not, and the whole log lives in this one file.
     */
    @Test
    fun `a file from an older version still loads`() {
        val old = Disk(
            """
            {"world":[],"preferences":[],"idSeq":7,
             "turns":[{"id":1,"sessionId":1,"at":1000,"speaker":"USER","text":"以前问过的"}]}
            """.trimIndent(),
        )
        val restored = old.open().recentTurns().single()
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
        val store = Disk("{ this is not json").open()
        assertTrue(store.recentTurns().isEmpty())
        assertNotNull(store.appendTurn(turn(1, Speaker.USER, "还能用吗")))
    }

    /**
     * A checkpoint writes the turn it is checkpointing, and nothing else.
     *
     * This is the whole reason turns left the snapshot. Measured on a real install before the
     * split: one nine-round question rewrote the snapshot eleven times at around 360KB a go,
     * and half of every rewrite was the embeddings on cached facts - which no turn touches.
     */
    @Test
    fun `checkpointing a turn does not rewrite everything else`() {
        val disk = Disk()
        val store = disk.open()
        store.recordWorldFact(
            WorldFact(
                id = 1,
                question = "布洛芬孕妇能吃吗",
                answer = "孕晚期禁用",
                ttl = WorldTtl.PERMANENT,
                tier = Tier.AUTHORITATIVE,
                embedding = List(1024) { 0.5f },
                recordedAt = 1000,
            ),
        )
        val settled = disk.io.writes

        // A turn, then eight checkpoints on it, exactly as a nine-round question would.
        store.appendTurn(turn(2, Speaker.ASSISTANT, "查到了。"))
        repeat(8) { store.replaceTurn(turn(2, Speaker.ASSISTANT, "查到了。" + "料".repeat(it))) }

        assertEquals(
            settled,
            disk.io.writes,
            "a checkpoint rewrote the snapshot, embeddings and all",
        )
        assertEquals(9, disk.turns.lines().size, "the checkpoints did not go to the log")
        // And what it holds is the last version of the turn, not the first.
        assertEquals("查到了。" + "料".repeat(7), disk.open().recentTurns().single { it.id == 2L }.text)
    }

    /**
     * The one-time move, for the file every install already has.
     *
     * Turns used to live in the snapshot. They are read back out of it once, written into the
     * log, and the snapshot is rewritten without them - after which only one of the two is ever
     * written to, so they cannot disagree.
     */
    @Test
    fun `turns in an old snapshot move into the log`() {
        val old = Disk(
            """
            {"world":[],"preferences":[],"idSeq":7,
             "turns":[{"id":1,"sessionId":1,"at":1000,"speaker":"USER","text":"以前问过的"}]}
            """.trimIndent(),
        )

        assertEquals("以前问过的", old.open().recentTurns().single().text)
        assertEquals(1, old.turns.lines().size, "the turn was not moved into the log")
        assertTrue(
            old.io.contents?.contains("以前问过的") == false,
            "the snapshot is still carrying the turn: " + old.io.contents,
        )
        // And it is still there on the launch after that, now read from the log alone.
        assertEquals("以前问过的", old.open().recentTurns().single().text)
    }

    /**
     * An append-only file that only ever grows is a different bug, so it does not only grow.
     *
     * Amortised against bytes rather than lines: turns differ in size by two orders of magnitude,
     * so a count of lines says nothing about whether the file has got fat.
     */
    @Test
    fun `the log is written out whole once the appends have outgrown it`() {
        val disk = Disk()
        val store = disk.open()
        val fat = "料".repeat(150_000)
        repeat(12) { store.replaceTurn(turn(1, Speaker.ASSISTANT, fat)) }

        assertTrue(
            disk.turns.lines().size < 12,
            "the log grew a line per checkpoint forever: " + disk.turns.lines().size,
        )
        assertEquals(fat, disk.open().recentTurns().single().text, "compaction lost the turn")
    }

    /**
     * And fat is measured in what the file is measured in.
     *
     * The counter used to add up `String.length`, which is UTF-16 units. Read off a real install
     * that was 1.77MB of turns.jsonl, it reported 602K of waste and sat there - because the
     * conversations are Chinese, where a character costs three bytes, so a threshold named for a
     * megabyte did not fire until three. This is that install in miniature: over the limit on
     * disk, under it by a factor of three in characters.
     */
    @Test
    fun `a log fat in bytes but not in characters is compacted anyway`() {
        val disk = Disk()
        // Three checkpoints of one answer, seeded straight into the log so the writing of them
        // is not itself what trips the threshold: 1.62MB on disk, 1.08MB of it superseded, and
        // only 360K characters of that.
        val fat = "料".repeat(180_000)
        repeat(3) {
            disk.turns.append(
                """{"id":1,"sessionId":1,"at":1000,"speaker":"ASSISTANT","text":"$fat"}""",
            )
        }

        // One small write, which is all it should take: the waste was already on disk when the
        // store opened, and a counter that starts from zero every launch never sees it.
        disk.open().appendTurn(turn(2, Speaker.USER, "还有别的吗"))

        assertEquals(
            2,
            disk.turns.lines().size,
            "the log was left fat: under a million characters, over a million bytes",
        )
        val back = disk.open().recentTurns()
        assertEquals(2, back.size, "compaction lost a turn")
        assertEquals(fat, back.first { it.id == 1L }.text, "compaction lost the answer")
    }

    /**
     * A question that quoted an answer comes back still knowing which one.
     *
     * Whole, not the few words the chip showed. The thread draws a short mention of it, and it
     * has to be able to draw that from the log after a restart rather than from a string the
     * live screen happened to be holding - which is the mismatch that has already been found
     * once here, when a mid-turn correction lived only in RAM.
     */
    @Test
    fun `what a question quoted comes back with it`() {
        val disk = Disk()
        val answer = "孕晚期禁用。\n说明书上写得很清楚，不要自己加量。"
        disk.open().appendTurn(
            turn(1, Speaker.USER, "那孕早期呢").copy(quoted = answer),
        )

        val back = disk.open().recentTurns().single()
        assertEquals("那孕早期呢", back.text, "the question and the quotation were run together")
        assertEquals(answer, back.quoted, "the quotation did not survive being written down")
    }

    /** A log written before quoting existed reads as turns that quoted nothing. */
    @Test
    fun `an older turn simply has no quotation`() {
        val old = Disk(
            """
            {"world":[],"preferences":[],"idSeq":7,
             "turns":[{"id":1,"sessionId":1,"at":1000,"speaker":"USER","text":"以前问过的"}]}
            """.trimIndent(),
        )
        assertNull(old.open().recentTurns().single().quoted)
    }

    /** Ids handed out before a crash must not be handed out again. */
    @Test
    fun `the id sequence never rewinds`() {
        val disk = Disk()
        disk.open().apply { (1L..4L).forEach { appendTurn(turn(it, Speaker.USER, "x")) } }
        assertTrue(disk.open().nextId() > 4L)
    }
}
