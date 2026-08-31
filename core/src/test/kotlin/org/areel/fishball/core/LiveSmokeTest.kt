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

        val reply = runBlocking {
            conversation.ask("布洛芬常见的副作用是什么？") { println("  narration: $it") }
        }

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
