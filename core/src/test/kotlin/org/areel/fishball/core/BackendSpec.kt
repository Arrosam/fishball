package org.areel.fishball.core

import org.areel.fishball.core.agent.ForkAnswer
import org.areel.fishball.core.agent.Step
import org.areel.fishball.core.agent.TurnContext
import org.areel.fishball.core.agent.TurnEngine
import org.areel.fishball.core.agent.TurnKind
import org.areel.fishball.core.answer.AnswerPlanner
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
import org.areel.fishball.core.search.Disconfirmation
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
        disconfirmationChecks(::check)
        answerChecks(registry, ::check)
        engineChecks(registry, ::check)
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
                WorldTtl.ALWAYS_RESEARCH, Tier.INSTITUTIONAL, emptyList(), recordedAt = T0),
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

    // ---------------------------------------------------------------- R6

    private fun disconfirmationChecks(check: (String, Boolean, String) -> Unit) {
        check("R6 fires on HEALTH even with AUTHORITATIVE support",
            Disconfirmation.shouldRun(Topic.HEALTH, Tier.AUTHORITATIVE, false), "")
        check("R6 fires when nothing institutional was found",
            Disconfirmation.shouldRun(Topic.GENERAL, Tier.PERSONAL, false), "")
        check("R6 fires on institutional conflict",
            Disconfirmation.shouldRun(Topic.GENERAL, Tier.AUTHORITATIVE, true), "")
        check("R6 does not fire on a well-sourced ordinary question",
            !Disconfirmation.shouldRun(Topic.GENERAL, Tier.INSTITUTIONAL, false), "")

        val health = Disconfirmation.queriesFor("奇亚籽", Topic.HEALTH).map { it.text }
        check("R6 health queries include the side-effect pattern", health.any { it.contains("副作用") }, health.toString())
        check("R6 queries include the does-not-work pattern", health.any { it.contains("无效") }, "")
        val general = Disconfirmation.queriesFor("某手机", Topic.GENERAL).map { it.text }
        check("R6 general queries omit the side-effect pattern", general.none { it.contains("副作用") }, general.toString())
        check("R6 empty subject yields no queries",
            Disconfirmation.queriesFor("  ", Topic.GENERAL).isEmpty(), "")

        check("R6 authoritative counter with weak support = REFUTED",
            Disconfirmation.interpret(Tier.PERSONAL, Tier.AUTHORITATIVE, true)
                == Disconfirmation.Outcome.REFUTED, "")
        check("R6 strong both ways = CONTESTED",
            Disconfirmation.interpret(Tier.AUTHORITATIVE, Tier.INSTITUTIONAL, true)
                == Disconfirmation.Outcome.CONTESTED, "")
        check("R6 nothing either way = BOTH_EMPTY",
            Disconfirmation.interpret(Tier.LOW, Tier.LOW, false)
                == Disconfirmation.Outcome.BOTH_EMPTY, "")
        check("R6 nothing against solid support = NO_COUNTER_EVIDENCE",
            Disconfirmation.interpret(Tier.AUTHORITATIVE, Tier.LOW, false)
                == Disconfirmation.Outcome.NO_COUNTER_EVIDENCE, "")
    }

    // ---------------------------------------------------------------- §6 §7 §23 §24

    private fun evidence(registry: SourceRegistry, url: String, text: String): Evidence {
        val hit = SearchHit(url, title = text)
        return Evidence(hit, TrustResolver(registry).resolve(hit))
    }

    private fun answerChecks(registry: SourceRegistry, check: (String, Boolean, String) -> Unit) {
        val who = evidence(registry, "https://www.who.int/a", "世卫组织的说明")
        val reuters = evidence(registry, "https://www.reuters.com/a", "路透社报道")
        val personal = listOf(
            "https://www.zhihu.com/a" to "用下来 发烫 明显 一个 小时 就 很 热",
            "https://www.douban.com/b" to "续航 只能 撑 半天 完全 不 够用",
            "https://www.v2ex.com/c" to "信号 在 地铁 里 断 得 很 频繁",
            "https://www.reddit.com/d" to "camera focus hunts badly in low light",
            "https://www.quora.com/e" to "screen has a green tint at low brightness",
        ).map { evidence(registry, it.first, it.second) }
        val junk = listOf(evidence(registry, "https://xuandao.la/x", "某个 不知名 网站 的 说法"))

        val unavailable = AnswerPlanner.plan(
            support = emptyList(), searchAvailable = false, registry = registry,
        )
        check("§23 search down = SEARCH_UNAVAILABLE",
            unavailable.shape == AnswerShape.SEARCH_UNAVAILABLE, "${unavailable.shape}")
        check("§23 no sources are cited when search is down", unavailable.sources.isEmpty(), "")
        check("§23 keeps it to two sentences", unavailable.maxSentences == 2, "")

        val confident = AnswerPlanner.plan(support = listOf(who), registry = registry)
        check("§6 AUTHORITATIVE support = CONFIDENT", confident.shape == AnswerShape.CONFIDENT, "${confident.shape}")
        check("R8 attribution carries the foreign-source explanation",
            confident.attributions.any { it.contains("联合国") }, confident.attributions.toString())

        check("§6 INSTITUTIONAL support = ATTRIBUTED",
            AnswerPlanner.plan(support = listOf(reuters), registry = registry).shape
                == AnswerShape.ATTRIBUTED, "")

        val threePersonal = AnswerPlanner.plan(support = personal.take(3), registry = registry)
        check("R4 three personal-tier sources are not enough on their own",
            threePersonal.shape == AnswerShape.WEAK_LEAD, "${threePersonal.shape}")
        val fivePersonal = AnswerPlanner.plan(support = personal, registry = registry)
        check("R4 five independent personal-tier sources may be stated as a pattern",
            fivePersonal.shape == AnswerShape.PERSONAL_PATTERN, "${fivePersonal.shape}")
        val fiveHealth = AnswerPlanner.plan(support = personal, topic = Topic.HEALTH, registry = registry)
        check("R4 five is not enough on a health question",
            fiveHealth.shape == AnswerShape.WEAK_LEAD, "${fiveHealth.shape}")

        check("§6 only weak sources = WEAK_LEAD",
            AnswerPlanner.plan(support = junk, registry = registry).shape == AnswerShape.WEAK_LEAD, "")
        check("§6 nothing found = NOTHING_FOUND",
            AnswerPlanner.plan(support = emptyList(), registry = registry).shape
                == AnswerShape.NOTHING_FOUND, "")

        val refuted = AnswerPlanner.plan(
            support = junk, counter = listOf(who),
            counterOutcome = Disconfirmation.Outcome.REFUTED, registry = registry,
        )
        check("R6 refutation is a confident answer, not a hedge",
            refuted.shape == AnswerShape.REFUTED, "${refuted.shape}")
        check("R6 refutation cites the counter-evidence",
            refuted.sources.any { it.contains("who.int") }, refuted.sources.toString())

        val conflict = AnswerPlanner.plan(
            support = listOf(who), counter = listOf(reuters),
            counterOutcome = Disconfirmation.Outcome.CONTESTED, registry = registry,
        )
        check("R7 disagreement is disclosed", conflict.shape == AnswerShape.CONFLICT, "${conflict.shape}")
        check("R7 cites both sides",
            conflict.sources.size == 2, conflict.sources.toString())

        val medical = AnswerPlanner.plan(
            support = listOf(who), topic = Topic.HEALTH,
            diagnosticSelfQuestion = true, registry = registry,
        )
        check("§7 diagnostic question triggers the split", medical.medicalSplit, "")
        check("§7 medical answers get more room", medical.maxSentences == 6, "${medical.maxSentences}")
        check("§24 ordinary answers stay short", confident.maxSentences == 3, "")
        check("§24 markdown is never allowed", !confident.allowMarkdown, "")
        check("§7 split does not fire on a non-diagnostic health question",
            !AnswerPlanner.plan(support = listOf(who), topic = Topic.HEALTH, registry = registry)
                .medicalSplit, "")
    }

    // ---------------------------------------------------------------- §14–§21 routing

    private fun engineChecks(registry: SourceRegistry, check: (String, Boolean, String) -> Unit) {
        fun engine(store: InMemoryStore = InMemoryStore()) = TurnEngine(registry, store) to store

        val (e1, _) = engine()
        check("§18 crisis overrides everything",
            e1.firstStep(
                TurnContext("我不想活了", TurnKind.CRISIS, Topic.HEALTH,
                    diagnosticSelfQuestion = true, now = T0),
            ) is Step.Crisis, "")

        check("§15 distress opens with comfort and a fork",
            e1.firstStep(TurnContext("我压力好大", TurnKind.EMOTIONAL, now = T0))
                is Step.ComfortAndFork, "")
        check("§15 choosing to vent means listen, not search",
            e1.firstStep(
                TurnContext("我压力好大", TurnKind.EMOTIONAL, fork = ForkAnswer.VENT, now = T0),
            ) === Step.Listen, "")

        val advising = e1.firstStep(
            TurnContext("要不要辞职", TurnKind.EMOTIONAL, fork = ForkAnswer.WANT_ADVICE, now = T0),
        )
        check("§16 advice clarifies before searching", advising is Step.Clarify, "$advising")
        check("§16 clarifying questions are bundled",
            (advising as? Step.Clarify)?.questions?.size?.let { it >= 2 } == true, "")

        val afterClarify = e1.firstStep(
            TurnContext("要不要辞职", TurnKind.ADVICE, subject = "辞职", clarified = true, now = T0),
        )
        check("§16 searches after clarification", afterClarify is Step.Search, "$afterClarify")

        check("§14 smalltalk does not search",
            e1.firstStep(TurnContext("你好", TurnKind.SMALLTALK, now = T0)) === Step.Chat, "")
        check("§9 log query goes to the log",
            e1.firstStep(TurnContext("我昨天问你什么了", TurnKind.LOG_QUERY, now = T0))
                is Step.SearchLog, "")

        // §10 cache serving
        val (e2, store2) = engine()
        store2.recordWorldFact(
            WorldFact(store2.nextId(), "iPhone 17 Pro 电池容量是多少", "3582mAh",
                WorldTtl.PERMANENT, Tier.AUTHORITATIVE, emptyList(), recordedAt = T0),
        )
        check("§10 fresh cache answers without searching",
            e2.firstStep(
                TurnContext(
                    "iPhone 17 Pro 电池容量是多少",
                    TurnKind.FACTUAL,
                    now = T0,
                    // Recall moved out of the engine when it grew a network call. What the
                    // engine still owns is the rule: fresh answers, and only fresh ones.
                    recalled = store2.recallWorldFact("iPhone 17 Pro 电池容量是多少", T0),
                ),
            ) is Step.ServeFromMemory, "")

        val (e3, store3) = engine()
        store3.recordWorldFact(
            WorldFact(store3.nextId(), "某只 ETF 今年表现如何", "涨了 3%",
                WorldTtl.ALWAYS_RESEARCH, Tier.INSTITUTIONAL, emptyList(), recordedAt = T0),
        )
        check("§10 time-sensitive question re-searches",
            e3.firstStep(
                TurnContext(
                    "某只 ETF 今年表现如何",
                    TurnKind.FACTUAL,
                    now = T0,
                    recalled = store3.recallWorldFact("某只 ETF 今年表现如何", T0),
                ),
            )
                is Step.Search, "")

        // §20 confirmation gate
        val (e4, store4) = engine()
        store4.recordPreference(
            PreferenceFact(store4.nextId(), "在吃布洛芬", PreferenceKind.CURRENT_STATE,
                PreferenceTtl.SIX_MONTHS,
                recordedAt = T0 - 210 * ONE_DAY_MS, confirmedAt = T0 - 210 * ONE_DAY_MS),
        )
        check("§20 stale medical preference is confirmed before use",
            e4.firstStep(TurnContext("这个药能一起吃吗", TurnKind.FACTUAL, Topic.MEDICATION, now = T0))
                is Step.ConfirmPreferences, "")
        check("§20 gate does not fire on a non-medical question",
            e4.firstStep(TurnContext("今天天气", TurnKind.FACTUAL, Topic.GENERAL, now = T0))
                !is Step.ConfirmPreferences, "")

        // R6 wiring
        val (e5, _) = engine()
        val ctxHealth = TurnContext("奇亚籽能减肥吗", TurnKind.FACTUAL, Topic.HEALTH, subject = "奇亚籽", now = T0)
        val who = evidence(registry, "https://www.who.int/a", "世卫组织")
        check("R6 always runs on a health question",
            e5.afterSupportSearch(ctxHealth, listOf(who), searchFailed = false) is Step.Disconfirm, "")

        val ctxGeneral = TurnContext("某手机电池多大", TurnKind.FACTUAL, Topic.GENERAL, subject = "某手机", now = T0)
        val reuters = evidence(registry, "https://www.reuters.com/a", "路透社")
        check("R6 skipped for a well-sourced ordinary question",
            e5.afterSupportSearch(ctxGeneral, listOf(reuters), searchFailed = false) is Step.Answer, "")

        val failed = e5.afterSupportSearch(ctxGeneral, emptyList(), searchFailed = true)
        check("§23 failed search never falls back to model knowledge",
            (failed as? Step.Answer)?.plan?.shape == AnswerShape.SEARCH_UNAVAILABLE, "$failed")

        val done = e5.afterDisconfirmation(ctxHealth, listOf(who), emptyList())
        check("R6 completing the counter-search produces an answer", done is Step.Answer, "$done")
    }
}
