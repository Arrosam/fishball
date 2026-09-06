package org.areel.fishball.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.agent.MemoryBus
import org.areel.fishball.core.agent.Tools
import org.areel.fishball.core.copy.AgentPrompt
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.core.llm.LlmClient
import org.areel.fishball.core.llm.LlmContent
import org.areel.fishball.core.llm.LlmDelta
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.trust.SearchHit
import org.areel.fishball.core.trust.loadBundledRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A turn that was stopped keeps what it did.
 *
 * It did not. The answer was written to the log once, at the end, out of a list that lived on the
 * stack for the length of the turn - so pressing stop after twenty rounds of searching left the
 * question in the log with nothing under it, and the next question was asked of a model that had
 * never done any of it. Reported as the app throwing the work away, which is what it was.
 *
 * The row is claimed when the question is asked and rewritten at the end of every round now. What
 * that buys is not a tidier log: it is that stopping becomes a way to redirect the agent rather
 * than a way to lose it.
 */
class StoppedTurnTest {

    @Test
    fun `a stopped turn keeps every round it finished`() {
        val store = InMemoryStore()
        val llm = Stalls()

        runBlocking {
            val turn = launch { conversation(llm, store).ask("布洛芬孕妇能吃吗") }
            llm.stalled.await()
            turn.cancelAndJoin()
        }

        val logged = store.recentTurns()
        assertEquals(
            listOf(Speaker.USER, Speaker.ASSISTANT),
            logged.map { it.speaker },
            "the stopped turn left nothing behind: " + logged,
        )
        val stopped = logged.last()
        assertTrue(stopped.text.isBlank(), "an unfinished turn was written as though it answered")

        val exchange = stopped.rounds.single().exchanges.single()
        assertEquals(Tools.SEARCH, exchange.name)
        assertTrue(HIT_URL in exchange.result, "the round lost what the search found: " + exchange.result)
    }

    /**
     * And the next question is asked of a model that can see it.
     *
     * This is the whole point of keeping it. The calls replay as they were sent, and the turn
     * that never finished says so in the one place the model reads - otherwise the follow-up is
     * a fresh start, which is what steering is supposed to stop being.
     */
    @Test
    fun `what a stopped turn looked up goes back with the next question`() {
        val store = InMemoryStore()
        val llm = Stalls()
        val conversation = conversation(llm, store)

        runBlocking {
            val turn = launch { conversation.ask("布洛芬孕妇能吃吗") }
            llm.stalled.await()
            turn.cancelAndJoin()
            conversation.ask("别查那个了，说说儿童")
        }

        // The third call to the model: the first turn replayed, then the new question.
        val next = llm.seen[2]
        val call = next.flatMap { it.content }.filterIsInstance<LlmContent.ToolUse>().single()
        assertEquals(Tools.SEARCH, call.name, "the stopped turn replayed without its looking")

        val result = next.flatMap { it.content }.filterIsInstance<LlmContent.ToolResult>().single()
        assertEquals(call.id, result.toolUseId, "the result no longer points at its call")

        val said = next.filter { it.role == LlmMessage.Role.ASSISTANT }
            .flatMap { it.content }
            .filterIsInstance<LlmContent.Text>()
            .joinToString("\n") { it.text }
        assertTrue(
            AgentPrompt.INTERRUPTED in said,
            "the model was not told the turn had been cut off: " + said,
        )
    }

    /** One row per answer, not one per round. The checkpoints are the same row, written again. */
    @Test
    fun `a turn that finishes leaves one answer, not a pile of drafts`() {
        val store = InMemoryStore()

        runBlocking { conversation(Stalls(stallAt = NEVER), store).ask("布洛芬孕妇能吃吗") }

        val logged = store.recentTurns()
        assertEquals(
            listOf(Speaker.USER, Speaker.ASSISTANT),
            logged.map { it.speaker },
            "the checkpoints were appended instead of replaced: " + logged.map { it.text },
        )
        val answer = logged.last()
        assertEquals("孕晚期禁用。", answer.text)
        assertEquals(1, answer.rounds.size, "the answer lost the round it was built on")
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun conversation(llm: LlmClient, store: InMemoryStore) = Conversation(
        llm = llm,
        search = OneHit,
        registry = loadBundledRegistry(),
        store = store,
        now = { NOW },
        memory = MemoryBus(Dead, null, store, now = { NOW }),
    )

    /**
     * Searches, then hangs where a stop would land, then answers.
     *
     * The hang is on the second call rather than inside the search, because that is the moment
     * with something worth keeping: one round finished and written down, the next in flight.
     * [stalled] fires as it goes to sleep, so the test cancels at a known point instead of at
     * whatever point a sleep happened to reach.
     */
    private class Stalls(private val stallAt: Int = 1) : LlmClient {
        val seen = CopyOnWriteArrayList<List<LlmMessage>>()
        val stalled = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            seen += request.messages.toList()
            val call = when (calls++) {
                0 -> LlmContent.ToolUse(
                    "s1",
                    Tools.SEARCH,
                    buildJsonObject { putJsonArray("queries") { add("布洛芬 孕妇") } },
                )

                stallAt -> {
                    stalled.complete(Unit)
                    // Until the caller gives up on it, which is what a stop is from in here.
                    awaitCancellation()
                }

                else -> LlmContent.ToolUse(
                    "a$calls",
                    Tools.ANSWER,
                    buildJsonObject { put("text", "孕晚期禁用。") },
                )
            }
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Rejected
    }

    private object OneHit : SearchGateway {
        override suspend fun search(query: SearchQuery) = SearchResponse(
            query = query,
            hits = listOf(SearchHit(url = HIT_URL, title = "布洛芬说明书", snippet = "孕晚期禁用")),
        )
    }

    private object Dead : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit) =
            LlmResult.Failed("no client in this test", retryable = false)

        override suspend fun validate() = KeyCheck.Rejected
    }

    private companion object {
        const val NOW = 1_760_000_000_000L

        /** No round stalls, so the turn runs to an answer. */
        const val NEVER = -1
        const val HIT_URL = "https://www.nmpa.gov.cn/ibuprofen"
    }
}
