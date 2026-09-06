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
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.search.PageContent
import org.areel.fishball.core.search.PageGateway
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
 * The turn does not get to make the same mistake a hundred times.
 *
 * Observed live: one page answering 404, opened again and again for eleven consecutive rounds.
 * Nothing in the loop noticed. `IDLE_LIMIT` counts rounds that said nothing and asked for
 * nothing, and these rounds asked for something every time; `LAST_ROUND` was ninety rounds away.
 * The model was told 「这一页打不开」, which is true, says nothing about what to do instead, and
 * reads like a failure worth retrying.
 *
 * So the turn remembers what it has tried - by call and by address - hands a repeat straight back
 * with a list of other things to do, and takes the looking-up tools away from a turn that has
 * done nothing but repeat itself three rounds running.
 */
class LoopBreakerTest {

    /**
     * The reported bug, in one assertion: the page is fetched once.
     *
     * Keyed on the address rather than on the call, because the second and third asks vary
     * `find` - which is what a model does when a page will not open, and which makes each one a
     * different call.
     */
    @Test
    fun `a page that will not open is fetched once, however often it is asked for`() {
        val store = InMemoryStore()
        val pages = Broken()
        val llm = Scripted(
            listOf(
                read(DEAD_URL),
                read(DEAD_URL, find = "孕妇"),
                read(DEAD_URL, find = "禁用"),
                read(DEAD_URL + "#说明"),
            ),
        )

        runBlocking { conversation(llm, store, pages).ask("布洛芬孕妇能吃吗") }

        assertEquals(
            listOf(DEAD_URL),
            pages.opened.toList(),
            "a page known not to open was fetched again",
        )
        val rounds = store.recentTurns().last().rounds
        assertTrue(
            rounds.drop(1).all { round -> round.exchanges.all { it.isError } },
            "asking for a dead page again was not reported as a mistake",
        )
        assertTrue(
            rounds[1].exchanges.single().result.let { DEAD_URL in it && "第 2 次" in it },
            "the second ask was not told it was the second: " + rounds[1].exchanges.single().result,
        )
    }

    /** And the same for any call, not only a failing one. Nothing changes inside a turn. */
    @Test
    fun `the same search is not run twice`() {
        val store = InMemoryStore()
        val search = Counted()
        val llm = Scripted(listOf(search("布洛芬 孕妇"), search("布洛芬 孕妇"), search("布洛芬 孕妇")))

        runBlocking { conversation(llm, store, Broken(), search).ask("布洛芬孕妇能吃吗") }

        assertEquals(1, search.ran.size, "the identical search was run again: " + search.ran)
    }

    /**
     * Three rounds of nothing but repetition and the looking-up tools come off the table.
     *
     * Not forced to answer - see the note on `WIND_DOWN_AT`, where a forced answer invented a
     * broken search to explain a deadline it could not see. It is simply offered less to do, and
     * told why.
     */
    @Test
    fun `a turn that only repeats itself loses the tools it is repeating with`() {
        val store = InMemoryStore()
        val llm = Scripted(List(5) { read(DEAD_URL) })

        runBlocking { conversation(llm, store, Broken()).ask("布洛芬孕妇能吃吗") }

        // Rounds 1, 2 and 3 were pure repeats; the request for round 4 is the first one made
        // after the latch, and the offer of a fifth is what would have kept the loop alive.
        val offered = llm.tools[4]
        assertTrue(Tools.READ !in offered, "the tool it was spinning on was offered again")
        assertTrue(Tools.SEARCH !in offered, "searching survived the latch")
        assertTrue(Tools.ANSWER in offered, "the turn was left with no way to finish")

        val told = llm.seen[4].last().content.filterIsInstance<LlmContent.Text>()
            .joinToString("\n") { it.text }
        assertTrue(
            "不再给你" in told,
            "the tools disappeared without a word about why: " + told,
        )
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun conversation(
        llm: LlmClient,
        store: InMemoryStore,
        pages: PageGateway,
        search: SearchGateway = Counted(),
    ) = Conversation(
        llm = llm,
        search = search,
        pages = pages,
        registry = loadBundledRegistry(),
        store = store,
        now = { NOW },
        memory = MemoryBus(Dead, null, store, now = { NOW }),
    )

    private fun read(url: String, find: String? = null) = LlmContent.ToolUse(
        "r" + url.hashCode() + (find ?: ""),
        Tools.READ,
        buildJsonObject {
            put("url", url)
            if (find != null) put("find", find)
        },
    )

    private fun search(term: String) = LlmContent.ToolUse(
        "s" + term.hashCode(),
        Tools.SEARCH,
        buildJsonObject { putJsonArray("queries") { add(term) } },
    )

    /** Makes the calls it was handed, in order, then answers. Keeps every request it was sent. */
    private class Scripted(private val script: List<LlmContent.ToolUse>) : LlmClient {
        val seen = CopyOnWriteArrayList<List<LlmMessage>>()
        val tools = CopyOnWriteArrayList<List<String>>()
        private var calls = 0

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            seen += request.messages.toList()
            tools += request.tools.map { it.name }
            val call = script.getOrElse(calls++) {
                LlmContent.ToolUse(
                    "a$calls",
                    Tools.ANSWER,
                    buildJsonObject { put("text", "查不到可靠的说法。") },
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

    /** Every page is a 404, and it remembers who asked. */
    private class Broken : PageGateway {
        val opened = CopyOnWriteArrayList<String>()

        override suspend fun read(url: String): PageContent {
            opened += url
            return PageContent(url, failed = true, reason = "HTTP 404")
        }
    }

    private class Counted : SearchGateway {
        val ran = CopyOnWriteArrayList<String>()

        override suspend fun search(query: SearchQuery): SearchResponse {
            ran += query.text
            return SearchResponse(
                query = query,
                hits = listOf(SearchHit(url = DEAD_URL, title = "布洛芬说明书", snippet = "孕晚期禁用")),
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
        const val DEAD_URL = "https://www.nmpa.gov.cn/ibuprofen"
    }
}
