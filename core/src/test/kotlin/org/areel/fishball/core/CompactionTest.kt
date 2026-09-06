package org.areel.fishball.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.agent.MemoryBus
import org.areel.fishball.core.agent.Tools
import org.areel.fishball.core.llm.Call
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.core.llm.LlmClient
import org.areel.fishball.core.llm.LlmContent
import org.areel.fishball.core.llm.LlmDelta
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.memory.ConversationTurn
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.memory.ToolExchange
import org.areel.fishball.core.memory.ToolRound
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.trust.loadBundledRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compaction, as DeepSeek Harness does it: the oldest span, and only the oldest span.
 *
 * What this replaced was all or nothing. Past a line the whole session was folded into one
 * paragraph and every tool call, every page read and every line of reasoning behind them went
 * with it - on the conversation somebody was in the middle of. Under the line, nothing happened
 * at all, so the cost arrived in a single instalment at the worst moment.
 *
 * DSH's `compaction-basic` splits it: nothing until 80% of the window, and then the oldest turns
 * are replaced by a summary of themselves while the most recent 16% stays word for word. The
 * three properties tested here are the ones that make that safe - the recent end really is
 * verbatim, the seam never separates a question from its answer, and a summary that does not
 * arrive changes nothing.
 */
class CompactionTest {

    @Test
    fun `an ordinary conversation is never compacted`() {
        val store = InMemoryStore()
        val session = fill(store, turns = 3, weight = 200)
        val llm = Folds()

        runBlocking { conversation(llm, store).ask("那儿童呢") }

        assertTrue(
            llm.folds == 0,
            "a conversation nowhere near the window was folded anyway",
        )
        assertTrue(marker(0) in sent(llm), "the oldest turn was dropped without being summarised")
        assertEquals(0L, store.loadSession()!!.compactedThrough)
        assertTrue(session > 0)
    }

    @Test
    fun `past the threshold the oldest span becomes a summary and the recent end stays verbatim`() {
        val store = InMemoryStore()
        fill(store, turns = 20, weight = 8_000)
        val llm = Folds()

        runBlocking { conversation(llm, store).ask("那儿童呢") }

        assertEquals(1, llm.folds, "the fold did not happen exactly once")

        val thread = sent(llm)
        assertTrue("<compacted-summary>" in thread, "the summary never reached the thread")
        assertTrue(SUMMARY in thread, "the summary reached the thread empty")

        // The recent end, quoted rather than described: its tool result is still there character
        // for character, which is the whole difference between this and the fold it replaced.
        assertTrue(marker(19) in thread, "the newest turn lost its looking")
        // And the old end is gone from the prompt - though not from the log, which is what the
        // summary was written from and what `read_log` still searches.
        assertFalse(marker(0) in thread, "the folded span was quoted as well as summarised")
        // Nothing left the log. The summary was written from those turns, `read_log` still
        // searches them, and what changed is only that the prompt stopped quoting them.
        assertEquals(
            20,
            store.turnsInSession(store.loadSession()!!.id).count { it.text.startsWith("答案") },
            "compaction deleted turns from the log",
        )
    }

    /**
     * The seam falls where DSH requires it to: on a balanced span.
     *
     * A question folded away from the answer it was asked of leaves an assistant turn full of
     * tool calls that nothing in the thread accounts for. Trailing questions go to the retained
     * side, never the shadowed one.
     */
    @Test
    fun `the seam never separates a question from its answer`() {
        val store = InMemoryStore()
        fill(store, turns = 20, weight = 8_000)
        val llm = Folds()

        runBlocking { conversation(llm, store).ask("那儿童呢") }

        val seam = store.loadSession()!!.compactedThrough
        val folded = store.turnsInSession(store.loadSession()!!.id).filter { it.id <= seam }
        assertEquals(
            Speaker.ASSISTANT,
            folded.last().speaker,
            "the fold stopped on a question, orphaning the answer to it",
        )
        assertEquals(0, folded.size % 2, "the folded span is not whole exchanges: " + folded.size)
    }

    /** And a summary that could not be written leaves the conversation exactly as it was. */
    @Test
    fun `a fold that fails changes nothing`() {
        val store = InMemoryStore()
        fill(store, turns = 20, weight = 8_000)
        val llm = Folds(foldable = false)

        runBlocking { conversation(llm, store).ask("那儿童呢") }

        assertEquals(0L, store.loadSession()!!.compactedThrough, "the seam moved without a summary")
        val thread = sent(llm)
        assertFalse("<compacted-summary>" in thread, "an empty summary was put in the thread")
        assertTrue(marker(0) in thread, "the conversation was dropped when the summary failed")
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** A conversation of [turns] exchanges, each answer carrying [weight] characters of looking. */
    private fun fill(store: InMemoryStore, turns: Int, weight: Int): Long {
        val session = store.nextId()
        store.saveSession(Session(session, NOW))
        repeat(turns) { i ->
            val at = NOW - turns + i
            store.appendTurn(
                ConversationTurn(store.nextId(), session, at, Speaker.USER, "问题 $i"),
            )
            store.appendTurn(
                ConversationTurn(
                    store.nextId(), session, at, Speaker.ASSISTANT, "答案 $i",
                    reasoning = "第 $i 轮的想法。",
                    rounds = listOf(
                        ToolRound(
                            exchanges = listOf(
                                ToolExchange(
                                    id = "t$i",
                                    name = Tools.SEARCH,
                                    input = buildJsonObject { putJsonArray("queries") { add("词 $i") } },
                                    result = "料".repeat(weight) + marker(i),
                                ),
                            ),
                            thinking = "第 $i 轮为什么这么查。",
                        ),
                    ),
                ),
            )
        }
        return session
    }

    /** Everything the answering call was shown, flattened. */
    private fun sent(llm: Folds): String = llm.answering
        .flatMap { it.content }
        .joinToString("\n") {
            when (it) {
                is LlmContent.Text -> it.text
                is LlmContent.ToolUse -> it.input.toString()
                is LlmContent.ToolResult -> it.content
                is LlmContent.Opaque -> it.raw.toString()
                is LlmContent.Image -> ""
            }
        }

    private fun marker(i: Int) = "〔第${i}轮的原文〕"

    private fun conversation(llm: LlmClient, store: InMemoryStore) = Conversation(
        llm = llm,
        search = NoSearch,
        registry = loadBundledRegistry(),
        store = store,
        now = { NOW },
        memory = MemoryBus(Dead, null, store, now = { NOW }),
    )

    /**
     * Writes a summary when asked for one, answers otherwise.
     *
     * The two are told apart by [LlmRequest.call], the same header the proxy is given, rather
     * than by sniffing the messages - a summariser call is a prefix of an answering one by
     * design, so there is nothing in the body that reliably distinguishes them.
     */
    private class Folds(private val foldable: Boolean = true) : LlmClient {
        var folds = 0
            private set

        /** The thread as the turn itself was shown it, after any folding. */
        val answering = CopyOnWriteArrayList<LlmMessage>()

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            if (request.call == Call.COMPACT) {
                folds++
                if (!foldable) return LlmResult.Failed("no summariser in this test", retryable = true)
                return LlmResult.Ok(text = SUMMARY, raw = LlmMessage.assistant(SUMMARY))
            }
            answering.clear()
            answering += request.messages
            val call = LlmContent.ToolUse(
                "a1",
                Tools.ANSWER,
                buildJsonObject { put("text", "儿童要看年龄。") },
            )
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Rejected
    }

    private object NoSearch : SearchGateway {
        override suspend fun search(query: SearchQuery) = SearchResponse(query = query, failed = true)
    }

    private object Dead : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit) =
            LlmResult.Failed("no client in this test", retryable = false)

        override suspend fun validate() = KeyCheck.Rejected
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val SUMMARY = "【他问过什么】\n- 布洛芬的事"
    }
}
