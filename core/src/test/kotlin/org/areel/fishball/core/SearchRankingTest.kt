package org.areel.fishball.core

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
import org.areel.fishball.core.llm.Retrieval
import org.areel.fishball.core.llm.Scored
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.trust.SearchHit
import org.areel.fishball.core.trust.loadBundledRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Search results reach the model ranked by the reranker, and more of them.
 *
 * They used to be the first six of each query in whatever order the engine listed them, capped
 * at twenty a call. The engine's order is the engine's business; the reranker's is whether the
 * title and snippet speak to the query, which is the judgement the model was making by eye.
 * Fakes, because the claims are about order and count and a live engine pins neither.
 */
class SearchRankingTest {

    @Test
    fun `results come back in the reranker's order, and more of them`() {
        val llm = Scripted(listOf("布洛芬 孕妇"))
        val urls = urls(llm, engine = Reversing, pages = mapOf("布洛芬 孕妇" to page("a", 8)))

        // All eight, not the old six - and the last one the engine listed is the first shown.
        assertEquals((8 downTo 1).map { "https://a$it.test/" }, urls)
    }

    @Test
    fun `engine order stands without a reranker`() {
        val llm = Scripted(listOf("布洛芬 孕妇"))
        val urls = urls(llm, engine = null, pages = mapOf("布洛芬 孕妇" to page("a", 8)))
        assertEquals((1..8).map { "https://a$it.test/" }, urls)
    }

    /** The client answers a dead reranker with the original order and zeros. That is a no-op. */
    @Test
    fun `engine order stands when the reranker fails`() {
        val llm = Scripted(listOf("布洛芬 孕妇"))
        val urls = urls(llm, engine = Dead, pages = mapOf("布洛芬 孕妇" to page("a", 8)))
        assertEquals((1..8).map { "https://a$it.test/" }, urls)
    }

    /**
     * Several queries are several angles on one question, and each angle's best comes first:
     * the first of every list, then the second, so a five-query call is not answered by the
     * first two queries filling the cap between them.
     */
    @Test
    fun `each query's best comes before any query's second`() {
        val llm = Scripted(listOf("正面", "反面"))
        val urls = urls(
            llm,
            engine = Identity,
            pages = mapOf("正面" to page("a", 3), "反面" to page("b", 3)),
        )
        assertEquals(
            listOf("https://a1.test/", "https://b1.test/", "https://a2.test/", "https://b2.test/", "https://a3.test/", "https://b3.test/"),
            urls,
        )
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** Runs one turn and reads the search result's URLs back in the order the model saw them. */
    private fun urls(llm: Scripted, engine: Retrieval?, pages: Map<String, List<SearchHit>>): List<String> {
        val store = InMemoryStore()
        val conversation = Conversation(
            llm = llm,
            retrieval = engine,
            search = Pages(pages),
            registry = loadBundledRegistry(),
            store = store,
            memory = MemoryBus(NoClient, null, store),
        )
        runBlocking { conversation.ask("布洛芬孕妇能吃吗") }
        val result = llm.seen[1].last().content.filterIsInstance<LlmContent.ToolResult>().single()
        return Regex("网址：(\\S+)").findAll(result.content).map { it.groupValues[1] }.toList()
    }

    private fun page(prefix: String, n: Int): List<SearchHit> = (1..n).map {
        SearchHit(url = "https://$prefix$it.test/", title = "标题 $prefix$it", snippet = "摘要 $prefix$it")
    }

    /** Searches with the given queries, then answers. Keeps a copy of every thread shown. */
    private class Scripted(private val queries: List<String>) : LlmClient {
        val seen = CopyOnWriteArrayList<List<LlmMessage>>()

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            seen += request.messages.toList()
            val call = if (seen.size == 1) {
                LlmContent.ToolUse(
                    "s1",
                    Tools.SEARCH,
                    buildJsonObject { putJsonArray("queries") { queries.forEach { add(it) } } },
                )
            } else {
                LlmContent.ToolUse("a1", Tools.ANSWER, buildJsonObject { put("text", "答案。") })
            }
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Rejected
    }

    private class Pages(private val pages: Map<String, List<SearchHit>>) : SearchGateway {
        override suspend fun search(query: SearchQuery) =
            SearchResponse(query = query, hits = pages[query.text].orEmpty())
    }

    /** Best last, as far as this one is concerned. */
    private object Reversing : Retrieval {
        override suspend fun embed(texts: List<String>) = texts.map { listOf(1f, 0f) }
        override suspend fun rerank(query: String, documents: List<String>) =
            documents.indices.reversed().mapIndexed { rank, index -> Scored(index, 1.0 - rank * 0.1) }
    }

    private object Identity : Retrieval {
        override suspend fun embed(texts: List<String>) = texts.map { listOf(1f, 0f) }
        override suspend fun rerank(query: String, documents: List<String>) =
            documents.indices.map { Scored(it, 1.0 - it * 0.1) }
    }

    /**
     * What `HydrogenClient.rerank` returns when there is no reranker: null, meaning nothing was
     * ranked.
     *
     * It used to return the original order with every score zeroed, and this fake said so. That
     * was the shape of a real defect rather than a harmless stand-in - a caller cannot tell
     * those zeros from a verdict that none of the documents are any good, and memory recall
     * read them as one. Search was always safe because it orders and never filters, which is
     * what the tests below check.
     */
    private object Dead : Retrieval {
        override suspend fun embed(texts: List<String>) = emptyList<List<Float>>()
        override suspend fun rerank(query: String, documents: List<String>): List<Scored>? = null
    }

    private object NoClient : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit) =
            LlmResult.Failed("no client in this test", retryable = false)

        override suspend fun validate() = KeyCheck.Rejected
    }
}
