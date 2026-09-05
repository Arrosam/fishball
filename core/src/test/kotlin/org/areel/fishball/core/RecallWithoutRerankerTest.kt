package org.areel.fishball.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
import org.areel.fishball.core.llm.Retrieval
import org.areel.fishball.core.llm.Scored
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.WorldFact
import org.areel.fishball.core.memory.WorldTtl
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.trust.loadBundledRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the app remembers when the provider has no reranker.
 *
 * A custom profile is allowed to name none — plenty of providers serve chat and nothing else —
 * and the same state arrives at an areel install any time the reranker is briefly unreachable.
 * Both used to produce an app that had quietly stopped remembering anything it had looked up.
 *
 * The mechanism is worth writing down, because the defect was invisible from every direction.
 * `rerank` reported failure by handing back the documents in their original order with every
 * score set to zero. `narrowAnswers` filtered that on `RERANK_FLOOR`, which is 0.5. Zero is not
 * above 0.5, so every candidate was dropped — not fewer of them, all of them — and the turn ran
 * exactly as though nothing had ever been stored. No error, no log line, no failed call. The
 * only symptom was an app that never recalled anything, which reads like a product decision.
 *
 * So null now means *nothing ranked this*, and it is a different answer from a list of low
 * scores. Both halves are held here: without a ranker the cosine order stands, and with one the
 * floor still bites. The second test is not padding — without it the fix could have been "stop
 * filtering", which would put every loosely-related cached answer back in front of the model.
 *
 * Measured through a real turn rather than by reaching into the driver, so what is asserted is
 * what the model was actually shown.
 */
class RecallWithoutRerankerTest {

    private val stored = "空腹吃可能刺激胃黏膜。"

    /** Vectors that match, and an honest "I did not rank that". */
    private object NoReranker : Retrieval {
        override suspend fun embed(texts: List<String>) = texts.map { listOf(1f, 0f) }
        override suspend fun rerank(query: String, documents: List<String>): List<Scored>? = null
    }

    /** A ranker with opinions, so the floor can be shown still working. */
    private object Dismissive : Retrieval {
        override suspend fun embed(texts: List<String>) = texts.map { listOf(1f, 0f) }
        override suspend fun rerank(query: String, documents: List<String>) =
            documents.indices.map { Scored(it, 0.01) }
    }

    @Test
    fun `a cached answer is still recalled when nothing can rank it`() {
        val chat = askedWith(NoReranker)
        assertTrue(
            chat.everShown().contains(stored),
            "the answer was stored, cosine matched it, and nothing ranked it - it must survive",
        )
    }

    @Test
    fun `a ranker that scores everything low still empties the list`() {
        val chat = askedWith(Dismissive)
        assertTrue(
            !chat.everShown().contains(stored),
            "0.01 is below the floor - that is a verdict, and it has to be obeyed",
        )
    }

    // ---- the harness ------------------------------------------------------------------------

    /** One turn, with a cached answer already in the store, against the given retrieval. */
    private fun askedWith(retrieval: Retrieval): Scripted {
        val store = InMemoryStore().apply {
            recordWorldFact(
                WorldFact(
                    id = nextId(),
                    question = "布洛芬伤胃吗",
                    answer = stored,
                    ttl = WorldTtl.PERMANENT,
                    tier = Tier.AUTHORITATIVE,
                    sources = listOf("https://www.nmpa.gov.cn/example"),
                    embedding = listOf(1f, 0f),
                    recordedAt = 0L,
                ),
            )
        }
        val chat = Scripted("胃不好的话饭后吃。")
        val conversation = Conversation(
            llm = chat,
            retrieval = retrieval,
            search = SlowDeadSearch,
            registry = loadBundledRegistry(),
            store = store,
            memory = MemoryBus(llm = chat, retrieval = retrieval, store = store),
        )
        // Deliberately not the stored wording. Word overlap would not find this; cosine does,
        // which is what puts the fact in front of the reranker in the first place.
        runBlocking { conversation.ask("吃布洛芬会不会胃疼") }
        return chat
    }

    /**
     * Fails, slowly.
     *
     * Failing is fine — this test is about what memory contributed, not about search. Slowly is
     * the part that matters: recall runs beside the turn, and what it found is handed over on
     * the *next* tool-result message. A search that returned instantly would race the recall it
     * is meant to be giving time to.
     */
    private object SlowDeadSearch : SearchGateway {
        override suspend fun search(query: SearchQuery): SearchResponse {
            delay(200)
            return SearchResponse(query = query, failed = true, failureReason = "no search here")
        }
    }

    /**
     * Searches once, then answers — and that shape is required, not incidental.
     *
     * What memory found is attached to a tool-result turn, never sent as a message of its own,
     * because a user turn between an assistant's `tool_use` and its `tool_result` is a thread a
     * provider may reject. So a turn that answers on its first call never sees memory at all,
     * and a fake that answers immediately would report this bug fixed while it was still there.
     * The turn has to earn a round.
     */
    private class Scripted(private val says: String) : LlmClient {
        private val shown = CopyOnWriteArrayList<List<LlmMessage>>()

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            // Copied, not kept: `messages` is the driver's own live list and goes on being
            // appended to for the rest of the turn.
            shown += request.messages.toList()
            val call = if (shown.size == 1) {
                LlmContent.ToolUse(
                    "s1",
                    Tools.SEARCH,
                    buildJsonObject { put("queries", JsonArray(listOf(JsonPrimitive("布洛芬 胃")))) },
                )
            } else {
                LlmContent.ToolUse(
                    "a" + shown.size,
                    Tools.ANSWER,
                    buildJsonObject { put("text", says) },
                )
            }
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Valid(listOf("spy"), "spy")

        /** Everything the model was shown across the whole turn, flattened. */
        fun everShown(): String = shown.flatten().flatMap { it.content }.joinToString("\n") {
            when (it) {
                is LlmContent.Text -> it.text
                is LlmContent.ToolResult -> it.content
                else -> ""
            }
        }
    }
}
