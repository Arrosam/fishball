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

        // And a stamp in front of everything replayed out of the log.
        val replayed = sent.messages.dropLast(1)
        assertTrue(replayed.isNotEmpty(), "nothing was replayed")
        replayed.forEach { message ->
            val text = message.content.filterIsInstance<LlmContent.Text>().single().text
            assertTrue(
                text.startsWith("（") && "月" in text.take(12),
                "a replayed turn carries no time: $text",
            )
        }
        // The stamp is a prefix, not a replacement.
        assertTrue(
            replayed.any { m ->
                m.content.filterIsInstance<LlmContent.Text>().single().text.endsWith("阿莫西林")
            },
            "the words themselves were lost: " + replayed,
        )
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
