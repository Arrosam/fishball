package org.areel.fishball.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.search.SearchResponse
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.trust.loadBundledRegistry
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When things were said, and going back to find them.
 *
 * Every turn has carried a timestamp since spec §9 and none of it ever reached the model, so
 * 「上次问的那个」 had nothing to resolve against. `MemoryStore.searchTurns` has taken a time
 * window since it was written and nothing ever called it - the classifier that would have went
 * with the pipeline rewrite.
 *
 * The third thing pinned here is placement rather than behaviour: the standing instructions
 * belong in the system prompt, where they are the same bytes on every call of a session, and
 * the clock belongs in the question, where it is already paying for itself.
 */
class LogAndClockTest {

    @Test
    fun `the model is told what time it is, and when each turn was said`() {
        val store = seeded()
        val llm = Recorder()
        runBlocking { conversation(llm, store).ask("上次那个药叫什么") }

        val sent = llm.seen.first()

        // The clock, on the question - the one message that is new every turn anyway.
        val question = sent.messages.last().content
            .filterIsInstance<LlmContent.Text>().joinToString(" ") { it.text }
        assertTrue("现在是" in question, "no clock on the question: $question")
        assertTrue("9月3日" in question, "clock has the wrong day: $question")
        assertTrue("星期四" in question, "clock has no weekday: $question")

        // A stamp in front of what *he* said, and the words kept.
        val replayed = sent.messages.dropLast(1)
        assertTrue(replayed.isNotEmpty(), "nothing was replayed")
        // Two of them in the fixture, and both should be stamped; the first is the one whose
        // words are asserted on below.
        val users = replayed.filter { it.role == LlmMessage.Role.USER }
        assertEquals(2, users.size, "the replayed turns are not what this test seeded")
        users.forEach { m ->
            val line = m.content.filterIsInstance<LlmContent.Text>().single().text
            assertTrue(line.startsWith("（") && "月" in line.take(12), "no time on: $line")
        }
        val said = users.first().content.filterIsInstance<LlmContent.Text>().single().text
        assertTrue(said.startsWith("（") && "月" in said.take(12), "no time on his turn: $said")
        assertTrue(said.endsWith("阿莫西林"), "the words themselves were lost: $said")
    }

    /**
     * And nothing the app said carries one.
     *
     * Assistant turns were stamped too at first, which taught the model the house format for an
     * assistant turn included a timestamp - so it began writing 「（9月3日 21:53）」 at the top of
     * its answers, which went into the log as part of the answer and back into the next prompt
     * as a fresh example of itself. Three turns on a real phone before it was caught.
     *
     * The same shape as the `</think>` leak, and the second time this codebase has been bitten
     * by it: anything put in an assistant turn is a template the model may copy. The user's
     * turns are safe to stamp because it is not writing those.
     */
    @Test
    fun `nothing the app said is replayed with a time on it`() {
        val store = InMemoryStore()
        val session = store.nextId()
        store.saveSession(Session(session, at(1)))
        store.appendTurn(ConversationTurn(store.nextId(), session, at(1), Speaker.USER, "问题"))
        store.appendTurn(
            ConversationTurn(store.nextId(), session, at(1), Speaker.ASSISTANT, "答案"),
        )
        store.appendTurn(ConversationTurn(store.nextId(), session, at(2), Speaker.USER, "追问"))

        val llm = Recorder()
        runBlocking { conversation(llm, store).ask("追问") }

        val assistant = llm.seen.first().messages
            .single { it.role == LlmMessage.Role.ASSISTANT }
        val text = assistant.content.filterIsInstance<LlmContent.Text>().single().text
        assertEquals("答案", text, "the app's own turn was replayed with a time on it")
    }

    /**
     * And an answer already written with one is cleaned on the way back out.
     *
     * Those three turns are in a real log on a real phone. Without this they would keep showing
     * a timestamp to the user and keep demonstrating the pattern to the model.
     */
    @Test
    fun `a stamp an earlier build wrote into an answer is stripped`() {
        val store = InMemoryStore()
        val session = store.nextId()
        store.saveSession(Session(session, at(1)))
        store.appendTurn(ConversationTurn(store.nextId(), session, at(1), Speaker.USER, "问题"))
        store.appendTurn(
            ConversationTurn(
                store.nextId(), session, at(1), Speaker.ASSISTANT,
                "（9月3日 21:53）好，继续。",
            ),
        )
        store.appendTurn(ConversationTurn(store.nextId(), session, at(2), Speaker.USER, "追问"))

        val llm = Recorder()
        runBlocking { conversation(llm, store).ask("追问") }

        val assistant = llm.seen.first().messages
            .single { it.role == LlmMessage.Role.ASSISTANT }
        val text = assistant.content.filterIsInstance<LlmContent.Text>().single().text
        assertEquals("好，继续。", text, "the stamp went back to the model to be copied again")
    }

    @Test
    fun `the standing instructions ride in the system prompt, not on the question`() {
        val store = seeded()
        val llm = Recorder()
        runBlocking { conversation(llm, store).ask("那儿童呢") }

        val sent = llm.seen.first()
        assertTrue(
            AgentPrompt.WORK in sent.system,
            "the working instructions are not in the cached prefix",
        )
        val question = sent.messages.last().content
            .filterIsInstance<LlmContent.Text>().joinToString(" ") { it.text }
        assertTrue(
            AgentPrompt.WORK !in question,
            "the instructions are still being paid for on every question",
        )
    }

    @Test
    fun `the system prompt is byte-identical across turns of a session`() {
        val store = seeded()
        val llm = Recorder()
        val conversation = conversation(llm, store)
        runBlocking {
            conversation.ask("第一个问题")
            conversation.ask("第二个问题")
        }
        // What the cache actually needs: the same bytes, in the same order, every call.
        val systems = llm.seen.map { it.system }.distinct()
        assertEquals(1, systems.size, "the system prompt changed between turns")
    }

    @Test
    fun `read_log returns what was said in the window asked for`() {
        val store = seeded()
        val llm = Recorder()
        val conversation = conversation(llm, store)

        // Reach the handler the way the model does, through a tool call.
        llm.answerWith = LlmContent.ToolUse(
            id = "t1",
            name = Tools.HISTORY,
            input = buildJsonObject {
                put("from", "2026-09-01")
                put("to", "2026-09-01")
            },
        )
        runBlocking { conversation.ask("九月一号我们聊了什么") }

        val result = llm.seen.drop(1).first().messages.last().content
            .filterIsInstance<LlmContent.ToolResult>().single()
        assertTrue("阿莫西林" in result.content, "the day's turn is missing: " + result.content)
        assertTrue(
            "布洛芬" !in result.content,
            "a turn outside the window came back: " + result.content,
        )
    }

    @Test
    fun `an empty window says so rather than returning nothing`() {
        val store = seeded()
        val llm = Recorder()
        val conversation = conversation(llm, store)
        llm.answerWith = LlmContent.ToolUse(
            id = "t1",
            name = Tools.HISTORY,
            input = buildJsonObject {
                put("from", "2020-01-01")
                put("to", "2020-01-02")
            },
        )
        runBlocking { conversation.ask("2020年我们聊了什么") }

        val result = llm.seen.drop(1).first().messages.last().content
            .filterIsInstance<LlmContent.ToolResult>().single()
        assertEquals(AgentPrompt.LOG_EMPTY, result.content)
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** Two days of conversation, so a window can include one and exclude the other. */
    private fun seeded(): InMemoryStore {
        val store = InMemoryStore()
        val session = store.nextId()
        store.saveSession(Session(session, at(1)))
        store.appendTurn(ConversationTurn(store.nextId(), session, at(1), Speaker.USER, "医生开了阿莫西林"))
        store.appendTurn(ConversationTurn(store.nextId(), session, at(2), Speaker.USER, "布洛芬能一起吃吗"))
        return store
    }

    /** Midday on 1 and 2 September 2026, in the zone the app formats with. */
    private fun at(day: Int): Long =
        LocalDateTime.of(2026, 9, day, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun conversation(llm: LlmClient, store: InMemoryStore) = Conversation(
        llm = llm,
        search = NoSearch,
        registry = loadBundledRegistry(),
        store = store,
        now = { NOW },
        // Its own client, so the bus's own calls cannot race into [Recorder.seen].
        memory = MemoryBus(Dead, null, store, now = { NOW }),
    )

    private class Recorder : LlmClient {
        val seen = mutableListOf<LlmRequest>()

        /** Asked for on the first call only; every call after it just answers. */
        var answerWith: LlmContent.ToolUse? = null

        override suspend fun complete(
            request: LlmRequest,
            onDelta: (LlmDelta) -> Unit,
        ): LlmResult {
            seen += request
            val call = answerWith?.also { answerWith = null }
                ?: return LlmResult.Ok("知道了。", raw = LlmMessage.assistant("知道了。"))
            return LlmResult.Ok(
                text = "",
                toolCalls = listOf(call),
                raw = LlmMessage(LlmMessage.Role.ASSISTANT, listOf(call)),
            )
        }

        override suspend fun validate() = KeyCheck.Rejected
    }

    private object Dead : LlmClient {
        override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit) =
            LlmResult.Failed("no client in this test", retryable = false)

        override suspend fun validate() = KeyCheck.Rejected
    }

    private object NoSearch : SearchGateway {
        override suspend fun search(query: SearchQuery) =
            SearchResponse(query = query, failed = true)
    }

    private companion object {
        /** Thursday, 3 September 2026, 10:11 local. */
        val NOW: Long = LocalDateTime.of(2026, 9, 3, 10, 11)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
