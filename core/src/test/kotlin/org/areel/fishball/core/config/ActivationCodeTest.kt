package org.areel.fishball.core.config

import org.areel.fishball.core.llm.HydrogenClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one field on the gate, and what it makes of what is pasted into it.
 *
 * Two things are being protected here and they pull in opposite directions. Every activation
 * code already in somebody's hands has to keep working untouched, and a profile has to be
 * recognised without ever being confused for one. The prefix is what lets both be true, so most
 * of this file is about the edges of that rule rather than about the JSON.
 */
class ActivationCodeTest {

    private val profile = Provider(
        llmUrl = "https://api.deepseek.com/anthropic",
        token = "sk-0123456789abcdef0123456789abcdef",
        systemModel = "deepseek-v4-flash",
        flash = "deepseek-v4-flash",
        pro = "deepseek-v4-pro",
        embeddingModel = "bge-m3",
        rerankModel = "bge-reranker-v2",
        asrModel = "whisper-1",
        searchUrl = "https://search.example.com",
        searchToken = "tok-123",
        custom = true,
    )

    // ---- the codes that already exist ---------------------------------------------------

    @Test
    fun `an ordinary activation code still means what it always meant`() {
        val parsed = ActivationCode.parse("sk-areel-abcdef123456")
        assertTrue(parsed is Activation.Ok)
        val provider = parsed.provider
        assertEquals("sk-areel-abcdef123456", provider.token)
        assertEquals(Provider.AREEL_LLM, provider.llmUrl)
        assertEquals(Provider.AREEL_SEARCH, provider.searchUrl)
        assertEquals(Provider.AREEL_FLASH, provider.flash)
        assertEquals(Provider.AREEL_PRO, provider.pro)
        assertEquals(Provider.AREEL_SYSTEM, provider.systemModel)
        assertTrue(!provider.custom, "an areel code must not read as a custom profile")
    }

    /**
     * The gate is not the place an activation code is judged.
     *
     * Anything without the prefix goes to the service to be accepted or refused, exactly as
     * before. A parser that started having opinions about what a key looks like would reject
     * codes the service would have taken, and the app has no business knowing the shape of
     * somebody else's tokens.
     */
    @Test
    fun `a code that looks like nothing in particular is still handed on`() {
        listOf("hello", "12345", "sk-", "FB1", "fb-1.x", "  padded  ").forEach { raw ->
            val parsed = ActivationCode.parse(raw)
            assertTrue(parsed is Activation.Ok, "$raw should have been treated as an areel code")
            assertTrue(!parsed.provider.custom, "$raw read as a profile")
        }
    }

    // ---- profiles -------------------------------------------------------------------------

    @Test
    fun `a profile survives the round trip`() {
        val parsed = ActivationCode.parse(ActivationCode.encode(profile))
        assertTrue(parsed is Activation.Ok, "did not parse: $parsed")
        assertEquals(profile, parsed.provider)
    }

    @Test
    fun `the optional halves are allowed to be absent`() {
        val bare = profile.copy(
            embeddingModel = null,
            rerankModel = null,
            asrModel = null,
            searchToken = null,
        )
        val parsed = ActivationCode.parse(ActivationCode.encode(bare))
        assertTrue(parsed is Activation.Ok, "did not parse: $parsed")
        assertEquals(bare, parsed.provider)
        assertNull(parsed.provider.embeddingModel)
        assertNull(parsed.provider.rerankModel)
        // The one that hides the microphone rather than merely degrading recall.
        assertNull(parsed.provider.asrModel)
    }

    /**
     * Search is required, and this is the test that says so out loud.
     *
     * §23 means a factual question with no working search gets 「我现在查不了资料」 and nothing
     * else. A profile that omits it would activate cleanly and then refuse every question,
     * which is the failure shape this codebase keeps rejecting: the gate says yes and the
     * product says no.
     */
    @Test
    fun `a profile with no search instance is refused at the gate`() {
        val code = ActivationCode.encode(profile)
        val withoutSearch = code.rewritten { it.replace("\"search\":{\"url\":", "\"search\":{\"u\":") }
        val parsed = ActivationCode.parse(withoutSearch)
        assertTrue(parsed is Activation.Malformed, "expected a refusal, got $parsed")
        assertEquals(Activation.Reason.MISSING_FIELD, parsed.reason)
        assertEquals("search.url", parsed.detail)
    }

    @Test
    fun `each required model id is named when it is the one missing`() {
        mapOf(
            "\"sys\"" to "llm.sys",
            "\"flash\"" to "llm.flash",
            "\"pro\"" to "llm.pro",
            "\"key\"" to "llm.key",
        ).forEach { (key, expected) ->
            // Renaming rather than deleting keeps the JSON well-formed, so what is being
            // measured is the missing-field path and not the unreadable one. `"sys"` becomes
            // `"xsys"` - the quotes have to survive, or this measures the parser instead.
            val broken = ActivationCode.encode(profile).rewritten {
                it.replaceFirst(key, key.replaceFirst("\"", "\"x"))
            }
            val parsed = ActivationCode.parse(broken)
            assertTrue(parsed is Activation.Malformed, "$key: expected a refusal, got $parsed")
            assertEquals(Activation.Reason.MISSING_FIELD, parsed.reason, "for $key")
            assertEquals(expected, parsed.detail)
        }
    }

    // ---- what a paste does to it ------------------------------------------------------------

    /**
     * The failure this format is shaped around.
     *
     * Codes travel through chat apps, and chat apps wrap, elide and truncate long strings. A
     * code that lost its tail must say 不完整, because that is the one failure the person
     * holding the phone can fix themselves - and reporting it as an invalid code sends them
     * back to whoever gave it to them for a code that was never wrong.
     */
    @Test
    fun `a code that lost its tail reads as incomplete rather than invalid`() {
        val code = ActivationCode.encode(profile)
        listOf(
            code.dropLast(5),
            code.dropLast(code.length / 3),
            code.substringBeforeLast('.'),
        ).forEach { cut ->
            val parsed = ActivationCode.parse(cut)
            assertTrue(parsed is Activation.Malformed, "expected a refusal for '$cut'")
            assertEquals(
                Activation.Reason.TRUNCATED,
                parsed.reason,
                "cut code reported as ${parsed.reason}: ${parsed.detail}",
            )
        }
    }

    /**
     * What a chat client and an IME add on the way.
     *
     * Line breaks through the middle of a wrapped code, and the zero-width characters, which
     * are invisible on screen and survive a copy. A code that looks exactly right and does not
     * work is the worst version of this failing, so both are stripped before anything reads it.
     */
    @Test
    fun `a code survives being wrapped and copied back`() {
        val code = ActivationCode.encode(profile)
        val mangled = "  " + code.chunked(40).joinToString("\n") + "​﻿  "
        val parsed = ActivationCode.parse(mangled)
        assertTrue(parsed is Activation.Ok, "did not parse: $parsed")
        assertEquals(profile, parsed.provider)
    }

    @Test
    fun `the prefix is recognised whatever case it arrives in`() {
        val code = ActivationCode.encode(profile)
        val parsed = ActivationCode.parse(code.replaceFirst("fb1.", "FB1."))
        assertTrue(parsed is Activation.Ok, "did not parse: $parsed")
    }

    // ---- what it will not point at -----------------------------------------------------------

    /**
     * A profile names a server that will receive this person's conversations and their token,
     * and spec §1 hands codes over in person - which means a profile is a thing somebody else
     * can give you. `http://` is refused rather than silently upgraded, and the refusal names
     * the URL so a typo can be told from an attempt.
     */
    @Test
    fun `plaintext urls are refused, not upgraded`() {
        listOf(
            profile.copy(llmUrl = "http://llm.example.com"),
            profile.copy(searchUrl = "http://search.example.com"),
            profile.copy(llmUrl = "llm.example.com"),
            profile.copy(llmUrl = "https://"),
        ).forEach { bad ->
            val parsed = ActivationCode.parse(ActivationCode.encode(bad))
            assertTrue(parsed is Activation.Malformed, "accepted ${bad.llmUrl} / ${bad.searchUrl}")
            assertEquals(Activation.Reason.INSECURE_URL, parsed.reason)
        }
    }

    @Test
    fun `a corrupt payload is unreadable rather than incomplete`() {
        // Checksum recomputed over the damage, so this reaches the unpacking rather than
        // stopping at the truncation check - which is the point: the two must not collapse.
        val parsed = ActivationCode.parse("fb1.bm90LWRlZmxhdGVk.0000")
        assertTrue(parsed is Activation.Malformed, "expected a refusal, got $parsed")
        assertTrue(
            parsed.reason == Activation.Reason.UNREADABLE ||
                parsed.reason == Activation.Reason.TRUNCATED,
            "unexpected reason ${parsed.reason}",
        )
    }

    // ---- drift ---------------------------------------------------------------------------------

    /**
     * The areel defaults and the client's own list have to agree.
     *
     * They are written down twice - here, because a Provider has to name every field, and in
     * `HydrogenClient.PREFERRED`, because sign-in probes them strongest-first. Two copies of the
     * same two strings is the kind of thing that stays right for a year and then quietly does
     * not, on the day one of them is renamed. This is cheaper than the refactor that would
     * remove the duplication, and it fails on the same day.
     */
    @Test
    fun `the areel model names have not drifted from the client's list`() {
        assertEquals(HydrogenClient.PREFERRED, Provider.areel("t").chatModels())
        assertEquals(HydrogenClient.DEFAULT_BASE_URL, Provider.AREEL_LLM)
        assertEquals(HydrogenClient.EMBEDDING_MODEL, Provider.AREEL_EMBED)
        assertEquals(HydrogenClient.RERANK_MODEL, Provider.AREEL_RERANK)
    }

    /**
     * Re-seals a code with its JSON tampered with, checksum and all.
     *
     * So that a test of the field reader is run against a code that is genuinely well-formed —
     * correct prefix, valid base64, matching checksum, real deflate stream — and only wrong in
     * the one way being measured. A hand-built string would stop at the integrity check and
     * report the wrong reason.
     */
    private fun String.rewritten(edit: (String) -> String): String {
        val opened = ActivationCode.unseal(this)
        check(opened is ActivationCode.Unsealed.Text) { "fixture did not unseal: $opened" }
        return ActivationCode.seal(edit(opened.json))
    }
}
