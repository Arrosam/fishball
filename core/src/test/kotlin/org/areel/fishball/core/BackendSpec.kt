package org.areel.fishball.core

import org.areel.fishball.core.answer.AnswerShape
import org.areel.fishball.core.memory.ConversationTurn
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.ONE_DAY_MS
import org.areel.fishball.core.memory.PreferenceFact
import org.areel.fishball.core.memory.PreferenceKind
import org.areel.fishball.core.memory.PreferenceTtl
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.memory.WorldFact
import org.areel.fishball.core.memory.WorldTtl
import org.areel.fishball.core.quote.QuoteRequest
import org.areel.fishball.core.quote.QuoteResult
import org.areel.fishball.core.quote.QuoteVerifier
import org.areel.fishball.core.quote.RejectionReason
import org.areel.fishball.core.quote.SourceText
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.session.SessionDecision
import org.areel.fishball.core.session.SESSION_COMPACT_TOKENS
import org.areel.fishball.core.session.SessionManager
import org.areel.fishball.core.session.estimateTokens
import org.areel.fishball.core.trust.Evidence
import org.areel.fishball.core.trust.SearchHit
import org.areel.fishball.core.trust.SourceRegistry
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.trust.Topic
import org.areel.fishball.core.trust.TrustResolver

/**
 * The backend rules as executable assertions — spec §7–§24 plus memory, sessions and R6.
 *
 * Same pattern as TrustSpec: a plain function returning failures, so the identical checks run
 * under Gradle and under a bare compiler.
 *
 * Chinese survives only in fixture data - questions, snippets, preference text. The corpus
 * this runs against is Chinese and bigram similarity behaves differently across scripts, so
 * English fixtures would test the wrong thing. All assertion names are English.
 */
object BackendSpec {

    private const val T0 = 1_700_000_000_000L

    fun run(registry: SourceRegistry): List<String> {
        val failures = mutableListOf<String>()
        fun check(name: String, ok: Boolean, detail: String = "") {
            if (!ok) failures += "$name${if (detail.isNotEmpty()) " — $detail" else ""}"
        }

        ttlChecks(::check)
        storeChecks(::check)
        sessionChecks(::check)
        quoteChecks(::check)
        return failures
    }

    // ---------------------------------------------------------------- §25 extractive citation

    private fun quoteChecks(check: (String, Boolean, String) -> Unit) {
        val url = "https://www.nhc.gov.cn/guide"
        val source = SourceText(
            url,
            "甲状腺功能亢进症的常见表现包括心悸、手抖、多汗、体重下降。\n" +
                "确诊需要检测   甲状腺功能五项，不能仅凭症状判断。",
        )
        val verifier = QuoteVerifier()
        fun verify(text: String, at: String = url) =
            verifier.verify(QuoteRequest(at, text), listOf(source))

        val exact = verify("常见表现包括心悸、手抖、多汗")
        check("§25 a span present in the source verifies", exact is QuoteResult.Verified, "$exact")

        // The whole point: what is displayed is sliced from the document, so even a request
        // that only matches after normalisation shows the source's own wording.
        val spaced = verify("确诊需要检测 甲状腺功能五项")
        check("§25 whitespace differences do not reject a real quote",
            spaced is QuoteResult.Verified, "$spaced")
        check("§25 the returned text is the source's, not the model's",
            (spaced as? QuoteResult.Verified)?.quote?.exact?.contains("   ") == true,
            "expected the source's triple space, got ${(spaced as? QuoteResult.Verified)?.quote?.exact}")

        val fabricated = verify("甲亢可以通过食疗完全治愈")
        check("§25 a fabricated quote is rejected",
            (fabricated as? QuoteResult.Rejected)?.reason == RejectionReason.NOT_FOUND, "$fabricated")
        check("§25 rejection tells the model the text does not exist",
            (fabricated as? QuoteResult.Rejected)?.feedback?.contains("找不到") == true, "")

        // A paraphrase is the failure mode this exists for: plausible, sourced-looking, absent.
        val paraphrase = verify("常见症状有心悸和手部颤抖")
        check("§25 a paraphrase is rejected as firmly as an invention",
            (paraphrase as? QuoteResult.Rejected)?.reason == RejectionReason.NOT_FOUND, "$paraphrase")

        check("§25 a span too short to identify anything is rejected",
            (verify("心悸") as? QuoteResult.Rejected)?.reason == RejectionReason.TOO_SHORT, "")
        check("§25 republishing the page is not citation",
            (verify("心悸手抖".repeat(120)) as? QuoteResult.Rejected)?.reason == RejectionReason.TOO_LONG, "")
        check("§25 a source with no retrieved text cannot be quoted",
            (verify("常见表现包括心悸", at = "https://elsewhere.example/x") as? QuoteResult.Rejected)
                ?.reason == RejectionReason.SOURCE_UNAVAILABLE, "")

        // Normalisation must not manufacture matches between genuinely different wordings.
        val latin = SourceText("https://x.test/a", "The study found NO significant effect.")
        val v2 = QuoteVerifier()
        check("§25 case and full-width folding tolerate real quotes",
            v2.verify(QuoteRequest("https://x.test/a", "the study found no significant effect"), listOf(latin))
                is QuoteResult.Verified, "")
        check("§25 folding does not make different wordings match",
            v2.verify(QuoteRequest("https://x.test/a", "the study found a significant effect"), listOf(latin))
                is QuoteResult.Rejected, "")
    }

    // ---------------------------------------------------------------- §9 §10 §20 TTL

    private fun ttlChecks(check: (String, Boolean, String) -> Unit) {
        check("§10 unknown TTL defaults to ALWAYS_RESEARCH",
            WorldTtl.parse("???") == WorldTtl.ALWAYS_RESEARCH, "")
        check("§10 TTL parses by Chinese label",
            WorldTtl.parse("永久") == WorldTtl.PERMANENT, "")
        check("§20 unknown preference TTL defaults short, never permanent",
            PreferenceTtl.parse(null) == PreferenceTtl.ONE_MONTH, "")

        fun fact(ttl: WorldTtl, age: Long, invalidated: Long? = null) = WorldFact(
            id = 1, question = "q", answer = "a", ttl = ttl, tier = Tier.AUTHORITATIVE,
            recordedAt = T0 - age, invalidatedAt = invalidated,
        )
        check("PERMANENT stays fresh", fact(WorldTtl.PERMANENT, 100 * ONE_DAY_MS).isFresh(T0), "")
        check("ALWAYS_RESEARCH is never fresh", !fact(WorldTtl.ALWAYS_RESEARCH, 0).isFresh(T0), "")
        check("ONE_MONTH fresh at 29 days", fact(WorldTtl.ONE_MONTH, 29 * ONE_DAY_MS).isFresh(T0), "")
        check("ONE_MONTH stale at 31 days", !fact(WorldTtl.ONE_MONTH, 31 * ONE_DAY_MS).isFresh(T0), "")
        check("§19 invalidated fact is never served",
            !fact(WorldTtl.PERMANENT, 0, invalidated = T0).isFresh(T0), "")

        fun pref(age: Long) = PreferenceFact(
            id = 2, text = "在吃布洛芬", kind = PreferenceKind.CURRENT_STATE,
            ttl = PreferenceTtl.SIX_MONTHS, recordedAt = T0 - age, confirmedAt = T0 - age,
        )
        check("§20 CURRENT_STATE preference fresh at 5 months", pref(150 * ONE_DAY_MS).isFresh(T0), "")
        check("§20 CURRENT_STATE preference stale at 7 months", !pref(210 * ONE_DAY_MS).isFresh(T0), "")
        check("§20 stale medical preference must be confirmed",
            pref(210 * ONE_DAY_MS).needsConfirmationFor(Topic.MEDICATION, T0), "")
        check("§20 fresh preference needs no confirmation",
            !pref(10 * ONE_DAY_MS).needsConfirmationFor(Topic.MEDICATION, T0), "")
        check("§20 confirmation gate is medical-only",
            !pref(210 * ONE_DAY_MS).needsConfirmationFor(Topic.GENERAL, T0), "")
    }

    // ---------------------------------------------------------------- store

    private fun storeChecks(check: (String, Boolean, String) -> Unit) {
        val store = InMemoryStore()
        store.recordWorldFact(
            WorldFact(store.nextId(), "iPhone 17 Pro 电池容量是多少", "3582mAh",
                WorldTtl.PERMANENT, Tier.AUTHORITATIVE, listOf("https://www.apple.com"), recordedAt = T0),
        )
        check("§10 repeat question hits cache",
            store.recallWorldFact("iPhone 17 Pro 电池容量是多少", T0)?.servableWithoutSearch == true, "")
        check("unrelated question misses cache",
            store.recallWorldFact("布洛芬有什么副作用", T0) == null, "")

        val staleId = store.nextId()
        store.recordWorldFact(
            WorldFact(staleId, "某只 ETF 今年表现如何", "涨了 3%",
                WorldTtl.ALWAYS_RESEARCH, Tier.HIGH, emptyList(), recordedAt = T0),
        )
        check("§10 time-sensitive fact is never served from cache",
            store.recallWorldFact("某只 ETF 今年表现如何", T0)?.servableWithoutSearch == false, "")

        store.invalidateWorldFact(1, T0)
        check("§19 invalidated fact drops out of recall",
            store.recallWorldFact("iPhone 17 Pro 电池容量是多少", T0) == null, "")

        val prefId = store.nextId()
        store.recordPreference(
            PreferenceFact(prefId, "在吃布洛芬", PreferenceKind.CURRENT_STATE,
                PreferenceTtl.SIX_MONTHS,
                recordedAt = T0 - 210 * ONE_DAY_MS, confirmedAt = T0 - 210 * ONE_DAY_MS),
        )
        check("stale preference is visible before confirmation",
            store.preferences().single { it.id == prefId }.isFresh(T0).not(), "")
        store.confirmPreference(prefId, T0)
        check("§20 confirming resets the clock",
            store.preferences().single { it.id == prefId }.isFresh(T0), "")

        store.appendTurn(ConversationTurn(store.nextId(), 1, T0 - ONE_DAY_MS, Speaker.USER, "帮我查一下布洛芬的副作用"))
        store.appendTurn(ConversationTurn(store.nextId(), 1, T0, Speaker.USER, "今天天气怎么样"))
        check("§9 conversation log is searchable",
            store.searchTurns("布洛芬").any { it.text.contains("布洛芬") }, "")
        check("§9 log search respects a time window",
            store.searchTurns("布洛芬", from = T0 - 1000).isEmpty(), "")
    }

    // ---------------------------------------------------------------- §8 sessions

    private fun sessionChecks(check: (String, Boolean, String) -> Unit) {
        val sm = SessionManager()
        var id = 100L
        val next = { ++id }
        val current = Session(1, T0)

        val small = 1_000
        val large = SESSION_COMPACT_TOKENS + 1
        val hourAgo = T0 - 61 * 60_000
        val recently = T0 - 30 * 60_000

        check("§8 first ever turn starts a session",
            sm.decide(null, null, T0, small, next) is SessionDecision.Start, "")
        check("§8 active conversation continues",
            sm.decide(current, recently, T0, small, next) is SessionDecision.Continue, "")

        // Amended by the author: idle alone used to roll over, and that made the boundary
        // visible in the one way it must not be - come back after lunch, ask a follow-up, and
        // it had forgotten the thing you were following up on. Compaction now needs the
        // conversation to be both stale and big enough to be worth folding.
        check("§8 an hour idle but small carries on",
            sm.decide(current, hourAgo, T0, small, next) is SessionDecision.Continue, "")
        check("§8 large but still talking carries on",
            sm.decide(current, recently, T0, large, next) is SessionDecision.Continue, "")
        check("§8 stale and large compacts",
            sm.decide(current, hourAgo, T0, large, next) is SessionDecision.RollOver, "")

        check("§8 an empty session needs no bridge", !sm.needsBridge(1), "")
        check("§8 a real session needs a bridge", sm.needsBridge(2), "")

        // The threshold is counted in tokens, not characters, and Chinese is about one token
        // per character where English is about four characters per token.
        check("§8 CJK counts about a token a character",
            estimateTokens("布洛芬的常见副作用") in 8..10, "")
        check("§8 latin counts about four characters a token",
            estimateTokens("ibuprofen side effects") in 4..7, "")
    }

}
