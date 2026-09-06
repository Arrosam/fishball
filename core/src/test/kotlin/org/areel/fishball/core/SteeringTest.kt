package org.areel.fishball.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.agent.MemoryBus
import org.areel.fishball.core.agent.Tools
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Something said mid-turn joins the turn instead of replacing it.
 *
 * It used to replace it: the loop was cancelled and a fresh one started on the new text. The
 * model could still see the abandoned half in its replayed context and read it as finished work
 * - so it began again from nothing, re-searching what was already in front of it, and the person
 * who had typed 「not that, this」 got a different conversation instead of a corrected one.
 *
 * Now the message lands at the next seam between rounds, in the same user turn as that round's
 * tool results, and the loop carries on with everything it has already found.
 */
class SteeringTest {

    @Test
    fun `a message sent mid-turn reaches the model inside the same turn`() {
        val store = InMemoryStore()
        val llm = Searches()
        val conversation = conversation(llm, store)

        val reply = runBlocking {
            llm.onFirstCall = { conversation.steer("别查孕妇了，就说儿童") }
            conversation.ask("布洛芬孕妇能吃吗")
        }

        assertEquals("儿童要看年龄。", reply.text)

        // One turn, not two: exactly one answer, however many things were said into it.
        val logged = store.recentTurns()
        assertEquals(
            1,
            logged.count { it.speaker == Speaker.ASSISTANT },
            "the interjection started a second turn: " + logged.map { it.text },
        )
        // And the interjection is a row of its own, marked so the replay leaves it to the round
        // that carries it - see ConversationTurn.steered.
        val noted = logged.single { it.steered }
        assertEquals("别查孕妇了，就说儿童", noted.text)
        assertEquals(Speaker.USER, noted.speaker)

        // And it arrived with the tool results of the round it was said during, not as a
        // question of its own.
        val seam = llm.seen[1].last { it.role == LlmMessage.Role.USER }
        val text = seam.content.filterIsInstance<LlmContent.Text>().joinToString("\n") { it.text }
        assertTrue("别查孕妇了，就说儿童" in text, "the steer never reached the model: " + text)
        assertTrue(
            seam.content.any { it is LlmContent.ToolResult },
            "it was sent as a bare turn rather than with the round's results",
        )
        assertTrue(
            "不是另一个问题" in text,
            "it was handed over as a new question rather than a correction: " + text,
        )
    }

    /** And the work it was steering is still there, which is the whole complaint. */
    @Test
    fun `steering keeps what the turn had already found`() {
        val store = InMemoryStore()
        val llm = Searches()
        val conversation = conversation(llm, store)

        runBlocking {
            llm.onFirstCall = { conversation.steer("换个方向") }
            conversation.ask("布洛芬孕妇能吃吗")
        }

        val answer = store.recentTurns().last { it.speaker == Speaker.ASSISTANT }
        assertEquals(1, answer.rounds.size, "the round the turn had done was thrown away")
        assertTrue(
            HIT_URL in answer.rounds.single().exchanges.single().result,
            "what the search found did not survive the interruption",
        )
        assertEquals(
            listOf("换个方向"),
            answer.rounds.single().said,
            "the interjection was not recorded on the round it arrived during",
        )
    }

    /** With no loop to join it is a question, and says so rather than vanishing. */
    @Test
    fun `a message with no turn running is refused`() {
        val store = InMemoryStore()
        val conversation = conversation(Searches(), store)

        assertFalse(conversation.steer("在吗"), "a steer was accepted with nothing to steer")
        assertTrue(conversation.undelivered().isEmpty(), "a refused steer was queued anyway")
    }

    /** Accepted into a loop that then ends before the seam: taken back, not lost. */
    @Test
    fun `a steer the loop never read is handed back`() {
        val store = InMemoryStore()
        val llm = Answers()
        val conversation = conversation(llm, store)

        runBlocking {
            // Accepted during the one call this turn makes, which answers rather than looping.
            llm.onFirstCall = { conversation.steer("等一下") }
            conversation.ask("布洛芬孕妇能吃吗")
        }

        assertEquals(
            listOf("等一下"),
            conversation.undelivered(),
            "a steer that never reached a seam was dropped",
        )
        assertTrue(conversation.undelivered().isEmpty(), "handing it back did not drain it")
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

    /** Searches once, then answers. [onFirstCall] runs while the loop is provably in it. */
    private class Searches : LlmClient {
        val seen = CopyOnWriteArrayList<List<LlmMessage>>()
        var onFirstCall: (() -> Unit)? = null
        private var calls = 0

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            seen += request.messages.toList()
            val first = calls++ == 0
            if (first) onFirstCall?.invoke()
            val call = if (first) {
                LlmContent.ToolUse(
                    "s1",
                    Tools.SEARCH,
                    buildJsonObject { putJsonArray("queries") { add("布洛芬 孕妇") } },
                )
            } else {
                LlmContent.ToolUse("a1", Tools.ANSWER, buildJsonObject { put("text", "儿童要看年龄。") })
            }
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Rejected
    }

    /** Answers immediately, so the loop never reaches a seam. */
    private class Answers : LlmClient {
        var onFirstCall: (() -> Unit)? = null
        private var calls = 0

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            if (calls++ == 0) onFirstCall?.invoke()
            val call = LlmContent.ToolUse(
                "a1",
                Tools.ANSWER,
                buildJsonObject { put("text", "孕晚期禁用。") },
            )
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
        const val HIT_URL = "https://www.nmpa.gov.cn/ibuprofen"
    }
}
