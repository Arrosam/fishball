package org.areel.fishball.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
import org.areel.fishball.core.memory.ConversationTurn
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.memory.ToolExchange
import org.areel.fishball.core.memory.ToolRound
import org.areel.fishball.core.memory.WorldFact
import org.areel.fishball.core.memory.WorldTtl
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.trust.SearchHit
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.trust.loadBundledRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a turn looked up goes back to the model with the answer it produced.
 *
 * It did not. `priorTurns()` replayed each answer as a question and a reply with the looking
 * cut out from between them, so the model could see that it had said 孕晚期禁用 and not which
 * page said so - a follow-up about that page sent it searching for the page again. The same
 * cut took what memory had offered mid-turn, which rides on the back of a round's tool results:
 * the model that was told about the allergy last turn was not told this turn.
 *
 * Both are replayed as they were sent the first time: the calls as an assistant turn, their
 * results and the memory block as the user turn after them, then the answer.
 */
class ToolHistoryAcrossTurnsTest {

    @Test
    fun `what was looked up last turn goes back with the answer`() {
        val store = InMemoryStore()
        val llm = Scripted()
        val conversation = conversation(llm, store)

        runBlocking {
            conversation.ask("布洛芬孕妇能吃吗")
            conversation.ask("那儿童呢")
        }

        // The second turn's one call: the first turn replayed, then the new question.
        val second = llm.seen[2]
        assertEquals(
            listOf(
                LlmMessage.Role.USER,
                LlmMessage.Role.ASSISTANT,
                LlmMessage.Role.USER,
                LlmMessage.Role.ASSISTANT,
                LlmMessage.Role.USER,
            ),
            second.map { it.role },
            "the first turn did not replay as question, looking, answer: " + second,
        )

        val call = second[1].content.filterIsInstance<LlmContent.ToolUse>().single()
        assertEquals(Tools.SEARCH, call.name)
        assertEquals(
            "布洛芬 孕妇",
            call.input["queries"]?.jsonArray?.single()?.jsonPrimitive?.content,
            "the call did not go back with the arguments it was made with",
        )

        val result = second[2].content.filterIsInstance<LlmContent.ToolResult>().single()
        assertEquals(call.id, result.toolUseId, "the result no longer points at its call")
        assertTrue(HIT_URL in result.content, "the result lost what the search found: " + result.content)

        assertTrue(
            second[3].content.filterIsInstance<LlmContent.Text>().any { it.text.endsWith("孕晚期禁用。") },
            "the answer itself was lost: " + second[3].content,
        )
    }

    /**
     * Memory lands at the seam between rounds, on the back of that round's tool results. A
     * replay that keeps the results and drops the block keeps the evidence and forgets the
     * person it was for.
     */
    @Test
    fun `what memory offered goes back where it arrived`() {
        val store = InMemoryStore()
        store.recordWorldFact(
            WorldFact(
                id = store.nextId(),
                question = "布洛芬孕妇能吃吗",
                answer = "孕晚期禁用，其余孕期慎用",
                ttl = WorldTtl.PERMANENT,
                tier = Tier.HIGH,
                recordedAt = NOW,
            ),
        )
        val llm = Scripted()
        val conversation = conversation(llm, store)

        runBlocking {
            conversation.ask("布洛芬孕妇能吃吗")
            conversation.ask("那儿童呢")
        }

        val replayedResults = llm.seen[2]
            .single { m -> m.content.any { it is LlmContent.ToolResult } }
        val block = replayedResults.content.filterIsInstance<LlmContent.Text>()
            .joinToString("\n") { it.text }
        assertTrue(
            AgentPrompt.Label.KNOWN_LATE in block,
            "memory's block did not come back with the round it arrived in: " + replayedResults,
        )
        assertTrue("孕晚期禁用，其余孕期慎用" in block, "the remembered fact itself is missing: " + block)
    }

    /** All of them, not a recent few. What bounds the prompt is §8's fold, not a window here. */
    @Test
    fun `every answer keeps its looking, not only the last one`() {
        val store = InMemoryStore()
        val session = store.nextId()
        store.saveSession(Session(session, NOW))
        // Said before the question about to be asked, which lands at NOW and has to sort last.
        repeat(4) { i ->
            val said = NOW - 1_000 + i
            store.appendTurn(ConversationTurn(store.nextId(), session, said, Speaker.USER, "问题 $i"))
            store.appendTurn(
                ConversationTurn(
                    store.nextId(), session, said, Speaker.ASSISTANT, "答案 $i",
                    rounds = listOf(
                        ToolRound(
                            listOf(
                                ToolExchange(
                                    id = "t$i",
                                    name = Tools.SEARCH,
                                    input = buildJsonObject { putJsonArray("queries") { add("词 $i") } },
                                    result = "结果 $i",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        val llm = Scripted()
        runBlocking { conversation(llm, store).ask("最后一个问题") }

        val lookedUp = llm.seen.first().count { m ->
            m.role == LlmMessage.Role.ASSISTANT && m.content.any { it is LlmContent.ToolUse }
        }
        assertEquals(4, lookedUp, "an older answer was replayed without its looking")
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun conversation(llm: LlmClient, store: InMemoryStore) = Conversation(
        llm = llm,
        search = OneHit,
        registry = loadBundledRegistry(),
        store = store,
        now = { NOW },
        // Its own dead client, so the bus's calls cannot race into [Scripted.seen].
        memory = MemoryBus(Dead, null, store, now = { NOW }),
    )

    /**
     * Searches once, answers, and from then on only answers. Keeps a copy of every thread it
     * was shown - a copy, because the driver goes on appending to the live list.
     */
    private class Scripted : LlmClient {
        val seen = CopyOnWriteArrayList<List<LlmMessage>>()
        private var calls = 0

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            seen += request.messages.toList()
            val call = when (calls++) {
                0 -> LlmContent.ToolUse(
                    "s1",
                    Tools.SEARCH,
                    buildJsonObject { putJsonArray("queries") { add("布洛芬 孕妇") } },
                )

                1 -> LlmContent.ToolUse("a1", Tools.ANSWER, buildJsonObject { put("text", "孕晚期禁用。") })
                else -> LlmContent.ToolUse("a$calls", Tools.ANSWER, buildJsonObject { put("text", "儿童要看年龄。") })
            }
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Rejected
    }

    /** One result, after a pause long enough for the memory lookup to land during it. */
    private object OneHit : SearchGateway {
        override suspend fun search(query: SearchQuery): SearchResponse {
            delay(50)
            return SearchResponse(
                query = query,
                hits = listOf(SearchHit(url = HIT_URL, title = "布洛芬说明书", snippet = "孕晚期禁用")),
            )
        }
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
