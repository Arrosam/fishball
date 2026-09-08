package org.areel.fishball.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import org.areel.fishball.core.trust.loadBundledRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asking again for the newest answer takes the old one off the record first.
 *
 * The alternative is a thread carrying the same question twice with two different answers under
 * it, which is a record of something that did not happen - and, since §9's log is the thread,
 * a record the model reads back on every later turn.
 */
class RewindTest {

    @Test
    fun `the last exchange comes off the record and comes back as its question`() {
        val store = InMemoryStore()
        val conversation = conversation(store)

        runBlocking { conversation.ask("布洛芬孕妇能吃吗") }
        assertEquals(2, store.recentTurns().size, "the fixture did not produce one exchange")

        val rewound = assertNotNull(conversation.rewind(), "nothing was taken back")
        assertEquals("布洛芬孕妇能吃吗", rewound.question)
        assertTrue(store.recentTurns().isEmpty(), "the exchange was left in the log: " + store.recentTurns().map { it.text })
    }

    /** A correction said into the turn belongs to that turn, and comes back with it. */
    @Test
    fun `everything that fed the answer is taken back together`() {
        val store = InMemoryStore()
        val conversation = conversation(store)

        runBlocking { conversation.ask("布洛芬孕妇能吃吗") }
        // A steered row, written the way `steer` writes one: its own turn, marked.
        store.appendTurn(
            org.areel.fishball.core.memory.ConversationTurn(
                id = store.nextId(),
                sessionId = store.recentTurns().first().sessionId,
                at = NOW + 1,
                speaker = Speaker.USER,
                text = "就说儿童",
                steered = true,
            ),
        )
        // ...and an answer after it, so the tail is [user, user, assistant].
        store.appendTurn(
            org.areel.fishball.core.memory.ConversationTurn(
                id = store.nextId(),
                sessionId = store.recentTurns().first().sessionId,
                at = NOW + 2,
                speaker = Speaker.ASSISTANT,
                text = "儿童要看年龄。",
            ),
        )

        val rewound = assertNotNull(conversation.rewind())
        assertTrue("就说儿童" in rewound.question, "the correction was dropped: " + rewound.question)
        assertEquals(
            2,
            store.recentTurns().size,
            "it took back more than the tail: " + store.recentTurns().map { it.text },
        )
    }

    /** Nothing to take back is not an error, and must not empty the log. */
    @Test
    fun `an empty conversation rewinds to nothing`() {
        val store = InMemoryStore()
        assertNull(conversation(store).rewind())
    }

    /** A question with no answer under it yet is not an exchange. */
    @Test
    fun `a trailing question alone is not taken back`() {
        val store = InMemoryStore()
        val conversation = conversation(store)
        runBlocking { conversation.ask("布洛芬孕妇能吃吗") }
        store.appendTurn(
            org.areel.fishball.core.memory.ConversationTurn(
                id = store.nextId(),
                sessionId = store.recentTurns().first().sessionId,
                at = NOW + 5,
                speaker = Speaker.USER,
                text = "还在吗",
            ),
        )
        assertNull(conversation.rewind(), "a bare question was taken for an exchange")
        assertEquals(3, store.recentTurns().size)
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun conversation(store: InMemoryStore) = Conversation(
        llm = Answers,
        search = NoHits,
        registry = loadBundledRegistry(),
        store = store,
        now = { NOW },
        memory = MemoryBus(Dead, null, store, now = { NOW }),
    )

    /** Answers on the first call, so one ask is one exchange. */
    private object Answers : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
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

    private object NoHits : SearchGateway {
        override suspend fun search(query: SearchQuery) = SearchResponse(query = query)
    }

    private object Dead : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit) =
            LlmResult.Failed("no client in this test", retryable = false)

        override suspend fun validate() = KeyCheck.Rejected
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
