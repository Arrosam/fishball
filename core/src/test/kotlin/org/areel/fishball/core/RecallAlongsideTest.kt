package org.areel.fishball.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.agent.MemoryBus
import org.areel.fishball.core.agent.Tools
import org.areel.fishball.core.agent.TurnProgress
import org.areel.fishball.core.copy.AgentPrompt
import org.areel.fishball.core.copy.UiCopy
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
import org.areel.fishball.core.memory.PreferenceFact
import org.areel.fishball.core.memory.PreferenceKind
import org.areel.fishball.core.memory.PreferenceTtl
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.trust.loadBundledRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory is looked up beside the turn, and the answer still waits for it.
 *
 * Two claims that pull against each other, which is why they are tested together. Recall is
 * three network round trips and it used to sit in front of the turn, so nothing at all reached
 * the screen until it came back. Moving it off the critical path is only safe if the answer
 * cannot be written without it - a fire-and-forget recall would let the model recommend a
 * penicillin to somebody it has on file as allergic to penicillin, which is the failure the
 * memory system exists to prevent.
 *
 * Fakes rather than live, because both claims are about ordering: which call happened when, and
 * what was in front of the model when it wrote. A live run cannot pin either down.
 */
class RecallAlongsideTest {

    /** Vectors that always match, after a wait that stands in for the network. */
    private class PausedRetrieval(private val wait: Long) : Retrieval {
        override suspend fun embed(texts: List<String>): List<List<Float>> {
            delay(wait)
            return texts.map { listOf(1f, 0f) }
        }

        override suspend fun rerank(query: String, documents: List<String>) =
            documents.indices.map { Scored(it, 1.0) }
    }

    /** The bus's own model: names one fact about the user and nothing else. */
    private class Filing(private val aboutUser: String?) : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            val input: JsonObject = when (request.forceTool) {
                Tools.RECALL -> Json.parseToJsonElement(
                    if (aboutUser == null) {
                        """{"facts":[],"about_user":[]}"""
                    } else {
                        """{"facts":[],"about_user":["$aboutUser"]}"""
                    },
                ) as JsonObject

                else -> Json.parseToJsonElement("""{"nothing":true}""") as JsonObject
            }
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(LlmContent.ToolUse("f", request.forceTool.orEmpty(), input)),
                raw = LlmMessage.assistant(""),
            )
        }

        override suspend fun validate() = KeyCheck.Valid(listOf("spy"), "spy")
    }

    /**
     * The conversation's model. Answers with the next line it was given, and keeps what it was
     * shown each time - which is the only way to see what was in front of it when it wrote.
     */
    private class Scripted(private vararg val says: String) : LlmClient {
        val shown = CopyOnWriteArrayList<List<LlmMessage>>()
        val calledAt = CopyOnWriteArrayList<Long>()

        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult {
            calledAt += System.nanoTime()
            // Copied, not kept. `LlmRequest.messages` is the driver's own live thread list,
            // which goes on being appended to for the rest of the turn - held by reference,
            // every snapshot here is the same list and shows the end of the turn, not the call.
            shown += request.messages.toList()
            val line = says.getOrElse(shown.size - 1) { says.last() }
            // A search, when the line asks for one - that is how a turn earns the round the
            // recall is meant to finish during.
            val call = if (line == SEARCH_FIRST) {
                LlmContent.ToolUse(
                    "s" + shown.size,
                    Tools.SEARCH,
                    buildJsonObject { put("queries", Json.parseToJsonElement("""["随便"]""")) },
                )
            } else {
                LlmContent.ToolUse("a" + shown.size, Tools.ANSWER, buildJsonObject { put("text", line) })
            }
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Valid(listOf("spy"), "spy")

        /** Everything the model was shown on call [n], flattened. */
        fun text(n: Int): String = shown[n].flatMap { it.content }.joinToString("\n") {
            when (it) {
                is LlmContent.Text -> it.text
                is LlmContent.ToolResult -> it.content
                else -> ""
            }
        }
    }

    /** Slow enough that recall finishes during it, and it fails, which is beside the point. */
    private class PausedSearch(private val wait: Long) : SearchGateway {
        override suspend fun search(query: SearchQuery): SearchResponse {
            delay(wait)
            return SearchResponse(query = query, hits = emptyList(), failed = true)
        }
    }

    private fun seeded(): InMemoryStore = InMemoryStore().apply {
        recordPreference(
            PreferenceFact(
                id = nextId(),
                text = ALLERGY,
                kind = PreferenceKind.MEDICAL_CONSTANT,
                ttl = PreferenceTtl.PERMANENT,
                embedding = listOf(1f, 0f),
                recordedAt = 0L,
            ),
        )
    }

    private fun conversation(
        store: InMemoryStore,
        chat: LlmClient,
        retrieval: Retrieval,
        aboutUser: String? = ALLERGY,
        search: SearchGateway = PausedSearch(0),
    ) = Conversation(
        llm = chat,
        retrieval = retrieval,
        search = search,
        registry = loadBundledRegistry(),
        store = store,
        // These are all about the factorisation in front of memory, which is the professional
        // tier's. On the fast tier the question is embedded as it stands and there is no
        // `terms` call for them to be measuring.
        expert = true,
        memory = MemoryBus(llm = Filing(aboutUser), retrieval = retrieval, store = store),
    )

    /**
     * The complaint: nothing reached the screen until three round trips had finished.
     *
     * Recall is given a wait long enough that the turn could not possibly have sat through it,
     * and the first call to the conversation's model has to land inside that wait.
     */
    @Test
    fun `the turn starts before memory has answered`() {
        val store = seeded()
        val chat = Scripted("答案")
        val conversation = conversation(store, chat, PausedRetrieval(RECALL_WAIT))

        val began = System.nanoTime()
        runBlocking { conversation.ask("吃这个药要注意什么？") }

        val waited = (chat.calledAt.first() - began) / 1_000_000
        println("first model call at +" + waited + "ms, recall takes " + RECALL_WAIT + "ms")
        assertTrue(
            waited < RECALL_WAIT / 2,
            "the turn sat through the memory lookup before starting: +" + waited + "ms",
        )
        // And the first thing it was shown really did not have the facts in it, so the wait
        // above is not being passed by accident.
        assertTrue(
            !chat.text(0).contains(ALLERGY),
            "memory was in front of the model on the first call after all",
        )
    }

    /**
     * And the other half: a turn that outruns the lookup answers anyway.
     *
     * This asserted the opposite until the user asked for it. The answer used to be held back,
     * the lookup awaited, and the model asked a second time with the facts in front of it -
     * correct on the one case that matters, and paid for with an extra round on the critical
     * path that the user sits and watches as more 思考中 for something they did not ask for.
     *
     * So the cost is now taken deliberately and pinned here so nobody re-adds the wait by
     * accident: a turn that beats its own recall answers without it. Rare in practice, because
     * round one is almost always a search and a search takes longer than the lookup - and the
     * facts stay in the store, so the next question finds them. One turn is affected, not the
     * memory.
     */
    @Test
    fun `an answer written before memory lands is served as it is`() {
        val store = seeded()
        val chat = Scripted("第一版", "第二版")
        val conversation = conversation(store, chat, PausedRetrieval(RECALL_WAIT))

        val reply = runBlocking { conversation.ask("医生开了阿莫西林，能吃吗？") }

        assertEquals(1, chat.shown.size, "the turn was made to ask again for memory's sake")
        assertEquals("第一版", reply.text, "the first answer was not the one served")
    }

    /**
     * A lookup that found nothing costs nothing. Most turns are this one, and holding every
     * answer back for a second pass over an empty list would trade one wait for another.
     */
    @Test
    fun `an empty recall does not cost a second call`() {
        val store = InMemoryStore()
        val chat = Scripted("答案")
        val conversation =
            conversation(store, chat, PausedRetrieval(RECALL_WAIT), aboutUser = null)

        val reply = runBlocking { conversation.ask("今天天气怎么样？") }

        assertEquals(1, chat.shown.size, "an empty recall still forced a second call")
        assertEquals("答案", reply.text)
    }

    /**
     * The ordinary path, and the one the design is built around: the model spends its first
     * round searching, memory lands during it, and the facts ride in on the tool results. No
     * answer is ever held back, because none was written early.
     */
    @Test
    fun `memory folds into the round the model spends searching`() {
        val store = seeded()
        val chat = Scripted(SEARCH_FIRST, "答案")
        val conversation = conversation(
            store,
            chat,
            // Instant, against a search slow enough that it has finished by the seam.
            PausedRetrieval(0),
            search = PausedSearch(SEARCH_WAIT),
        )

        val reply = runBlocking { conversation.ask("这个药能吃吗？") }

        assertEquals(2, chat.shown.size, "the turn took a round it did not need")
        val second = chat.text(1)
        assertTrue(second.contains(ALLERGY), "memory never reached the model: " + second)
        assertTrue(
            second.contains(AgentPrompt.Label.KNOWN_LATE),
            "the facts arrived without saying they were late: " + second,
        )
        assertTrue(
            !second.contains(AgentPrompt.HOLD_FOR_MEMORY),
            "an answer was held back on a turn that never wrote one early",
        )
        assertEquals("答案", reply.text)
    }

    /**
     * The lookup is a step in the panel, like a search: said when it starts, and what it found
     * said when it reaches the model. At the seam, not when it returned, so a turn that answered
     * before memory came back never shows a fact the model did not have.
     */
    @Test
    fun `the lookup is narrated, and what it found is said where it lands`() {
        val store = seeded()
        val chat = Scripted(SEARCH_FIRST, "答案")
        val conversation = conversation(
            store,
            chat,
            PausedRetrieval(0),
            search = PausedSearch(SEARCH_WAIT),
        )
        val steps = CopyOnWriteArrayList<String>()
        val watching = object : TurnProgress {
            override fun step(text: String) {
                steps += text
            }
        }

        runBlocking { conversation.ask("这个药能吃吗？", watching) }

        assertEquals(
            UiCopy.Narration.RECALLING,
            steps.first(),
            "the lookup was not the first thing narrated: $steps",
        )
        val found = UiCopy.Narration.recalled(1)
        assertTrue(found in steps, "what memory found was never said: $steps")
        assertTrue(
            steps.indexOf(found) > steps.indexOf(UiCopy.Narration.SEARCHING),
            "the finding was announced before the round it landed on: $steps",
        )
    }

    private companion object {
        const val ALLERGY = "对青霉素过敏"

        /** Long enough that sitting through it would be unmistakable, short enough to run. */
        const val RECALL_WAIT = 400L
        const val SEARCH_WAIT = 200L

        /** The scripted line that means "call search this round" rather than "say this". */
        const val SEARCH_FIRST = "<search>"
    }
}
