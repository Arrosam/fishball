package org.areel.fishball.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.llm.LlmClient
import org.areel.fishball.core.llm.LlmContent
import org.areel.fishball.core.llm.LlmDelta
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.memory.ConversationTurn
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.trust.loadBundledRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reasoning behind an answer survives into the next question.
 *
 * It used to stop at the turn boundary, and that boundary is every message: `priorTurns()`
 * rebuilt the thread from the log as plain user/assistant text, and `ConversationTurn` had
 * nowhere to put reasoning even if it had wanted to. So a follow-up like 「那儿童呢」 reached a
 * model holding last turn's conclusion and none of the working that produced it - it could see
 * what had been ruled out, never why, and would rule it out again from scratch.
 *
 * These models write an answer as the continuation of a thinking block, which is why the block
 * goes back rather than being summarised or relabelled. Deliberately as `Opaque`, in the shape
 * it arrived in: the app tried rewriting reasoning into labelled *text* once, and the model read
 * its own replayed turn as a template and began emitting the label and a `</think>` tag into
 * answers the user could see.
 */
class ReasoningAcrossTurnsTest {

    @Test
    fun `a prior answer is replayed with the thinking that produced it`() {
        val store = InMemoryStore()
        val session = store.nextId()
        store.saveSession(org.areel.fishball.core.session.Session(session, NOW))
        store.appendTurn(ConversationTurn(store.nextId(), session, NOW, Speaker.USER, "布洛芬能吃吗"))
        store.appendTurn(
            ConversationTurn(
                store.nextId(), session, NOW, Speaker.ASSISTANT, "可以，但孕晚期不行。",
                reasoning = "说明书把孕晚期列为禁忌，其余孕期只是慎用。",
            ),
        )
        // The question being asked now. Dropped from the replay, because it is the one being
        // answered - see priorTurns().
        store.appendTurn(ConversationTurn(store.nextId(), session, NOW, Speaker.USER, "那儿童呢"))

        val llm = Recorder()
        runBlocking { conversation(llm, store).ask("那儿童呢") }

        val replayed = llm.seen.first().messages
        val assistant = replayed.single { it.role == LlmMessage.Role.ASSISTANT }

        // The thinking block comes first, because the answer continues it.
        val thinking = assistant.content.filterIsInstance<LlmContent.Opaque>().single()
        assertEquals("thinking", thinking.raw["type"]?.jsonPrimitive?.content)
        assertEquals(
            "说明书把孕晚期列为禁忌，其余孕期只是慎用。",
            thinking.raw["thinking"]?.jsonPrimitive?.content,
        )
        assertEquals(0, assistant.content.indexOf(thinking))

        // And the answer is still there, after it.
        assertTrue(
            assistant.content.filterIsInstance<LlmContent.Text>()
                .any { it.text == "可以，但孕晚期不行。" },
            "the answer itself was lost: " + assistant.content,
        )
    }

    @Test
    fun `a turn logged before reasoning was kept replays as it always did`() {
        val store = InMemoryStore()
        val session = store.nextId()
        store.saveSession(org.areel.fishball.core.session.Session(session, NOW))
        store.appendTurn(ConversationTurn(store.nextId(), session, NOW, Speaker.USER, "布洛芬能吃吗"))
        // No reasoning, exactly as every turn written by an earlier build.
        store.appendTurn(
            ConversationTurn(store.nextId(), session, NOW, Speaker.ASSISTANT, "可以。"),
        )
        store.appendTurn(ConversationTurn(store.nextId(), session, NOW, Speaker.USER, "那儿童呢"))

        val llm = Recorder()
        runBlocking { conversation(llm, store).ask("那儿童呢") }

        val assistant = llm.seen.first().messages.single { it.role == LlmMessage.Role.ASSISTANT }
        assertTrue(
            assistant.content.filterIsInstance<LlmContent.Opaque>().isEmpty(),
            "an empty reasoning became an empty thinking block: " + assistant.content,
        )
        assertEquals(1, assistant.content.size)
    }

    // ---- a client that answers immediately and keeps what it was asked ---------------------

    /**
     * The filing bus gets a client of its own, and a dead one.
     *
     * It runs beside every turn on its own scope, so left on the shared client its requests
     * race the loop's into [Recorder.seen] and `first()` is whichever won. Giving it a separate
     * dead client is not a workaround for the flake - it is the same separation `:app` makes,
     * where filing runs on the fast model and answering does not.
     */
    private fun conversation(llm: LlmClient, store: InMemoryStore) = Conversation(
        llm = llm,
        search = NoSearch,
        registry = loadBundledRegistry(),
        store = store,
        now = { NOW },
        memory = org.areel.fishball.core.agent.MemoryBus(Dead, null, store, now = { NOW }),
    )

    /** Answers nothing, so nothing it does can reach the recorder. */
    private object Dead : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit) =
            LlmResult.Failed("no client in this test", retryable = false)

        override suspend fun validate() = org.areel.fishball.core.llm.KeyCheck.Rejected
    }

    private class Recorder : LlmClient {
        val seen = mutableListOf<LlmRequest>()

        override suspend fun complete(
            request: LlmRequest,
            onDelta: (LlmDelta) -> Unit,
        ): LlmResult {
            seen += request
            return LlmResult.Ok(
                text = "儿童要看年龄。",
                raw = LlmMessage.assistant("儿童要看年龄。"),
            )
        }

        override suspend fun validate() = org.areel.fishball.core.llm.KeyCheck.Rejected
    }

    private object NoSearch : SearchGateway {
        override suspend fun search(query: SearchQuery) =
            SearchResponse(query = query, failed = true)
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
