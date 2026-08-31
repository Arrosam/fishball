package org.areel.fishball.core

import kotlinx.coroutines.runBlocking
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.llm.HydrogenClient
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.search.SearxngGateway
import org.areel.fishball.core.trust.loadBundledRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One real turn, against the real services.
 *
 * Opt-in: without `HYDROGEN_KEY` in the environment this does nothing and passes, so it never
 * makes CI depend on somebody's proxy being up or on a key existing. Run it deliberately:
 *
 *     HYDROGEN_KEY=... ./gradlew :core:test --tests '*LiveSmokeTest*' -i
 *
 * It exists because every other test here fakes the model, and the two defects that actually
 * reached the device — a model that is listed but not permitted, and reply blocks the parser
 * dropped — were both invisible to a fake. This is the only test that can see them.
 */
class LiveSmokeTest {

    private val key: String? = System.getenv("HYDROGEN_KEY")?.takeIf { it.isNotBlank() }

    /**
     * The state a phone was actually found in: a model id stored by an earlier build, which the
     * key is no longer entitled to use. Nothing re-opens the gate once it has been passed, so
     * without recovery here every turn fails 403 forever and the only fix is clearing app data
     * — which the device in question does not permit.
     */
    @Test
    fun `a stale model id recovers instead of failing every turn`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        var persisted: String? = null
        val llm = HydrogenClient(
            apiKey = key,
            model = "fishball-pro",
            onModelChanged = { persisted = it },
        )

        val result = runBlocking {
            llm.complete(
                org.areel.fishball.core.llm.LlmRequest(
                    system = "你是一个测试。",
                    messages = listOf(org.areel.fishball.core.llm.LlmMessage.user("说一个字")),
                    maxTokens = 64,
                ),
            )
        }
        println("recovered to -> $persisted")
        println("result       -> ${result::class.simpleName}")
        assertTrue(result is org.areel.fishball.core.llm.LlmResult.Ok, "did not recover: $result")
        assertTrue(persisted == "fishball-flash", "did not re-pick and report: $persisted")
        assertTrue(llm.model == "fishball-flash", "client kept the refused model")
    }

    /**
     * The change that is easiest to break without noticing: a turn is asked inside a
     * conversation, not on its own. Before this, every question was the first one the model had
     * ever seen, so a follow-up carrying a pronoun answered about nothing at all.
     */
    @Test
    fun `a follow-up knows what it is following up on`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val store = InMemoryStore()
        val conversation = Conversation(
            llm = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        runBlocking { conversation.ask("布洛芬常见的副作用是什么？") }
        // No subject of its own. Only the previous turn says what "它" is.
        val followUp = runBlocking { conversation.ask("那它伤肝吗？") }

        println("follow-up -> ${followUp.text.take(160)}")
        assertTrue(
            followUp.text.contains("布洛芬"),
            "the follow-up lost the subject: ${followUp.text.take(200)}",
        )
        // One session, not two: nothing here should have tripped a rollover.
        assertTrue(store.recentTurns().size >= 4, "turns went missing")
    }

    @Test
    fun `a whole turn works against the live services`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }

        val llm = HydrogenClient(apiKey = key)
        val check = runBlocking { llm.validate() }
        println("validate -> $check")
        assertTrue(check is KeyCheck.Valid, "sign-in failed: $check")

        // The whole point of the entitlement probe: what it settles on has been called once
        // and answered, not merely seen in a catalogue.
        println("model    -> ${(check as KeyCheck.Valid).chosen}")

        val store = InMemoryStore()
        val conversation = Conversation(
            llm = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        val thinking = StringBuilder()
        val streamed = StringBuilder()
        val reply = runBlocking {
            conversation.ask(
                "布洛芬常见的副作用是什么？",
                object : org.areel.fishball.core.agent.TurnProgress {
                    override fun step(text: String) = println("  narration: $text")
                    override fun thinking(delta: String) { thinking.append(delta) }
                    override fun answer(delta: String) { streamed.append(delta) }
                },
            )
        }
        println("thinking -> ${thinking.length} chars: ${thinking.take(120)}")
        println("streamed -> ${streamed.length} chars")
        assertTrue(thinking.isNotEmpty(), "no thinking streamed; the wait would show nothing")

        println("shape    -> ${reply.shape}")
        println("detail   -> ${reply.detail}")
        println("sources  -> " + reply.sources.joinToString { "${it.displayName}(${it.tier})" })
        reply.sources.mapNotNull { it.quote }.forEach { println("quote    -> $it") }
        println("answer   -> ${reply.text}")

        assertTrue(reply.detail == null, "turn reported a failure: ${reply.detail}")
        assertTrue(reply.text.isNotBlank(), "empty answer")

        val logged = store.recentTurns()
        assertTrue(logged.size >= 2, "the turn was not logged")
        assertTrue(logged.first().speaker == Speaker.USER)
    }
}
