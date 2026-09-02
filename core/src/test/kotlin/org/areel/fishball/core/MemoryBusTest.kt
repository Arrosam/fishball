package org.areel.fishball.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.trust.loadBundledRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The memory bus is a *separate* client, and it runs on the message rather than on the answer.
 *
 * Both halves are checked with fakes rather than live, because both are claims about wiring:
 * which client got which call, and when. A live run can confirm the model behaves; only this
 * can confirm the professional-mode conversation is not quietly paying for the filing.
 */
class MemoryBusTest {

    /** Records every tool it was asked to force, and answers with whatever it was handed. */
    private class Spy(private val reply: (String?) -> JsonObject?) : LlmClient {
        val forced = CopyOnWriteArrayList<String>()

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            request.forceTool?.let { forced += it }
            val input = reply(request.forceTool)
            val calls = if (input == null) {
                emptyList()
            } else {
                listOf(LlmContent.ToolUse("id", request.forceTool.orEmpty(), input))
            }
            return LlmResult.Ok(text = "ok", toolCalls = calls, raw = LlmMessage.assistant("ok"))
        }

        override suspend fun validate() = KeyCheck.Valid(listOf("spy"), "spy")
    }

    /** A chat client that cannot be reached, so the turn has no way to produce an answer. */
    private object Dead : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit) =
            LlmResult.Failed("spy is offline", retryable = false)

        override suspend fun validate() = KeyCheck.Valid(listOf("spy"), "spy")
    }

    private object NoSearch : SearchGateway {
        override suspend fun search(query: SearchQuery) =
            SearchResponse(query = query, hits = emptyList(), failed = true)
    }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    /**
     * The point of the whole rearrangement: nothing about memory reaches the chat client.
     *
     * The conversation used to make the harvest call itself, on whichever model the user had
     * chosen — so a professional-mode chat filed its own paperwork at professional-mode prices.
     */
    @Test
    fun `memory work never touches the chat client`() {
        val store = InMemoryStore()

        // Answers in prose and calls nothing. What is being asserted is what it is *not*
        // asked to do - there is no classify call left for it to be asked for.
        val chat = Spy { null }
        val filing = Spy { forced ->
            when (forced) {
                Tools.NOTE_USER -> json(
                    """{"nothing":false,"about_user":[{"text":"对青霉素过敏","kind":"medical_constant"}]}""",
                )
                else -> json("""{"nothing":true}""")
            }
        }

        val conversation = Conversation(
            llm = chat,
            retrieval = null,
            search = NoSearch,
            registry = loadBundledRegistry(),
            store = store,
            memory = MemoryBus(llm = filing, retrieval = null, store = store),
        )

        runBlocking {
            conversation.ask("我对青霉素过敏。")
            conversation.memory.idle()
        }

        // Every memory tool went to the filing client…
        assertTrue(
            Tools.NOTE_USER in filing.forced,
            "the bus never ran the user note: ${filing.forced}",
        )
        // …and none of them reached the chat client, whatever else it was asked to do.
        val leaked = chat.forced.filter { it in setOf(Tools.NOTE_USER, Tools.NOTE_FACT, Tools.RECALL) }
        assertTrue(leaked.isEmpty(), "memory work reached the chat client: $leaked")

        assertEquals(
            listOf("对青霉素过敏"),
            store.preferences().map { it.text },
            "the fact the user stated was not recorded",
        )
    }

    /**
     * Spec §9 — recorded on receipt, not on reply.
     *
     * The turn is made to fail after the message lands: search is dead and the chat client is
     * unreachable, so no answer is ever produced. What the person said about themselves has to
     * survive that, because it was true before the turn started.
     */
    @Test
    fun `what the user said survives a turn that never answers`() {
        val store = InMemoryStore()
        // Unreachable, every round. The turn ends in an apology and nothing is written.
        val chat = Dead
        val filing = Spy { forced ->
            if (forced == Tools.NOTE_USER) {
                json("""{"nothing":false,"about_user":[{"text":"在吃布洛芬","kind":"current_state"}]}""")
            } else {
                json("""{"nothing":true}""")
            }
        }

        val conversation = Conversation(
            llm = chat,
            retrieval = null,
            search = NoSearch,
            registry = loadBundledRegistry(),
            store = store,
            memory = MemoryBus(llm = filing, retrieval = null, store = store),
        )

        val reply = runBlocking {
            val r = conversation.ask("我在吃布洛芬。")
            conversation.memory.idle()
            r
        }

        assertTrue(reply.detail != null, "the turn was supposed to fail, and did not: $reply")
        assertEquals(
            listOf("在吃布洛芬"),
            store.preferences().map { it.text },
            "a fact stated on a turn that failed was lost",
        )
    }
}
