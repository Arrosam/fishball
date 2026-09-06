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
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.core.llm.LlmClient
import org.areel.fishball.core.llm.LlmContent
import org.areel.fishball.core.llm.LlmDelta
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.search.PageContent
import org.areel.fishball.core.search.PageGateway
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.session.SESSION_IDLE_TIMEOUT_MS
import org.areel.fishball.core.trust.SearchHit
import org.areel.fishball.core.trust.loadBundledRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A turn the app was killed in the middle of is picked up on the next launch.
 *
 * The app can be taken away mid-turn - the system reclaims a backgrounded process, an OEM shell
 * kills it outright, somebody swipes it out of recents - and what was left behind was a question
 * in the thread with nothing under it and nothing that would ever put anything there. The person
 * asked, watched it think, and came back to silence.
 *
 * `inFlight` is what makes that recoverable: it is set on every checkpoint and cleared by
 * anything that ends a turn in an orderly way, so a row that still carries it on the next launch
 * is a turn nothing finished. Resuming is not a re-ask - the question keeps its row, the rounds
 * already done go back to the model as the calls and results they were, and the turn carries on.
 *
 * From `:core` a cancelled turn and a killed process are the same event, which is why these tests
 * cancel: what distinguishes them is that the app calls [Conversation.abandon] when the person
 * stopped it themselves, and that is the last test here.
 */
class ResumeTest {

    @Test
    fun `a turn the app died in the middle of is offered back, with what it had done`() {
        val store = InMemoryStore()
        died(store)

        val offered = conversation(Answers(), store).unfinished()
        assertEquals("布洛芬孕妇能吃吗", offered?.question)
        assertEquals(1, offered?.rounds, "the round it finished was not counted")
        assertTrue(
            offered?.steps.orEmpty().isNotEmpty(),
            "the narration was lost, so the placeholder would restart from nothing",
        )
    }

    @Test
    fun `resuming carries on from what the turn already found`() {
        val store = InMemoryStore()
        died(store)

        val llm = Answers()
        val reply = runBlocking { conversation(llm, store).resume() }
        assertEquals("孕晚期禁用。", reply?.text)

        // One question, one answer. Not a re-ask: the question keeps the row it already had.
        val logged = store.recentTurns()
        assertEquals(listOf(Speaker.USER, Speaker.ASSISTANT), logged.map { it.speaker })
        assertEquals(1, logged.count { it.text == "布洛芬孕妇能吃吗" })

        // And the model was shown the search it had already run rather than being asked to
        // start over: the call, and the result it came back with.
        val shown = llm.seen.single()
        val call = shown.flatMap { it.content }.filterIsInstance<LlmContent.ToolUse>().single()
        assertEquals(Tools.SEARCH, call.name)
        val result = shown.flatMap { it.content }.filterIsInstance<LlmContent.ToolResult>().single()
        assertEquals(call.id, result.toolUseId)
        assertTrue(HIT_URL in result.content, "the round came back without what it found")

        // The row is closed, so the next launch does not pick it up all over again.
        assertFalse(logged.last().inFlight, "the finished turn is still marked as running")
        assertNull(conversation(Answers(), store).unfinished())
    }

    /** Stopped on purpose is not the same as lost, and only one of them comes back. */
    @Test
    fun `a turn somebody stopped is not offered back`() {
        val store = InMemoryStore()
        died(store)

        val next = conversation(Answers(), store)
        next.abandon()

        assertNull(next.unfinished(), "a turn the user stopped was offered for resuming")
        // What it did is still in the log. Abandoning takes away the offer, not the record.
        assertEquals(1, store.recentTurns().last().rounds.size)
    }

    /**
     * But a turn that is still running is not "left over", and abandon must not touch it.
     *
     * The stop button races a send through here: `stop()` joins the turn it cancelled and then
     * abandons, while the send that caused the stop has already opened a row of its own. An
     * abandon that swept the whole session would leave the turn that is actually running
     * unmarked, so a kill a second later would not offer it back - the feature undone by the
     * button next to it.
     */
    @Test
    fun `a turn that is still running is not abandoned out from under itself`() {
        val store = InMemoryStore()
        died(store)

        val llm = Stalls()
        val conversation = conversation(llm, store)
        runBlocking {
            val second = launch { conversation.resume() }
            llm.stalled.await()
            // Where `stop()` lands: the earlier turn is over, this one is mid-round.
            conversation.abandon()
            assertTrue(
                store.recentTurns().last().inFlight,
                "the running turn was unmarked, so a kill here would lose it",
            )
            second.cancelAndJoin()
        }
        // And once it is not running any more, the same call does take the offer away.
        conversation.abandon()
        assertFalse(store.recentTurns().last().inFlight, "a stopped turn stayed resumable")
    }

    /**
     * What the interrupted turn learned comes back with it.
     *
     * The 404 loop is what `Tried` defends against, and a resume walked straight back into it: a
     * fresh guard meant the dead page was fetched over the network again and the futile counter
     * started from nothing. The rounds are the record of what was tried, so they are read out of.
     */
    @Test
    fun `a page that would not open before the kill is not fetched again after it`() {
        val store = InMemoryStore()
        val pages = Broken()
        val llm = Opens()
        runBlocking {
            val turn = launch { conversation(llm, store, pages).ask("布洛芬孕妇能吃吗") }
            llm.stalled.await()
            turn.cancelAndJoin()
        }
        assertEquals(listOf(DEAD_URL), pages.opened.toList(), "the first read did not happen once")

        // Resumed, and it asks for the same page again - which it is told about rather than sent
        // to fetch.
        runBlocking { conversation(Opens(thenAnswer = true), store, pages).resume() }
        assertEquals(
            listOf(DEAD_URL),
            pages.opened.toList(),
            "a page known not to open was fetched again after the resume",
        )
    }

    /** And neither is one from long enough ago that nobody is waiting on it. */
    @Test
    fun `a turn from an hour ago is left where it is`() {
        val store = InMemoryStore()
        died(store)

        val later = Conversation(
            llm = Answers(),
            search = OneHit,
            registry = loadBundledRegistry(),
            store = store,
            now = { NOW + SESSION_IDLE_TIMEOUT_MS },
            memory = MemoryBus(Dead, null, store, now = { NOW }),
        )
        assertNull(later.unfinished(), "a stale turn would have fired a request on launch")
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** One search finished and written down, and then the app goes away mid-round. */
    private fun died(store: InMemoryStore) {
        val llm = Stalls()
        runBlocking {
            val turn = launch { conversation(llm, store).ask("布洛芬孕妇能吃吗") }
            llm.stalled.await()
            turn.cancelAndJoin()
        }
        val left = store.recentTurns().last()
        assertTrue(left.inFlight, "the turn was not left marked as running: " + left)
    }

    private fun conversation(
        llm: LlmClient,
        store: InMemoryStore,
        pages: PageGateway = Broken(),
    ) = Conversation(
        llm = llm,
        search = OneHit,
        pages = pages,
        registry = loadBundledRegistry(),
        store = store,
        now = { NOW },
        memory = MemoryBus(Dead, null, store, now = { NOW }),
    )

    /**
     * Opens the same dead page, then either stalls where a kill would land or gives up and
     * answers - which is what the resumed half of the test needs it to do so the turn can end.
     */
    private class Opens(private val thenAnswer: Boolean = false) : LlmClient {
        val stalled = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            if (calls++ > 0) {
                if (!thenAnswer) {
                    stalled.complete(Unit)
                    awaitCancellation()
                }
                val done = LlmContent.ToolUse(
                    "a1",
                    Tools.ANSWER,
                    buildJsonObject { put("text", "那一页打不开，查不到。") },
                )
                return LlmResult.Ok(
                    text = "",
                    toolCalls = listOf(done),
                    raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(done)),
                )
            }
            val call = LlmContent.ToolUse(
                "r1",
                Tools.READ,
                buildJsonObject { put("url", DEAD_URL) },
            )
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

    /** Searches, writes the round down, then never comes back — where a kill would land. */
    private class Stalls : LlmClient {
        val stalled = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            if (calls++ > 0) {
                stalled.complete(Unit)
                awaitCancellation()
            }
            val call = LlmContent.ToolUse(
                "s1",
                Tools.SEARCH,
                buildJsonObject { putJsonArray("queries") { add("布洛芬 孕妇") } },
            )
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Rejected
    }

    /** Answers on the first thing it is asked, and keeps what it was shown. */
    private class Answers : LlmClient {
        val seen = CopyOnWriteArrayList<List<LlmMessage>>()

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            seen += request.messages.toList()
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
        const val DEAD_URL = "https://www.nmpa.gov.cn/gone"
    }
}
