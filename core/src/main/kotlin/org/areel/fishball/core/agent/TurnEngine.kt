package org.areel.fishball.core.agent

import org.areel.fishball.core.answer.AnswerPlanner
import org.areel.fishball.core.copy.SearchTerms
import org.areel.fishball.core.copy.UiCopy
import org.areel.fishball.core.memory.MemoryStore
import org.areel.fishball.core.memory.preferencesFor
import org.areel.fishball.core.search.Disconfirmation
import org.areel.fishball.core.search.Purpose
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.trust.Evidence
import org.areel.fishball.core.trust.SourceRegistry
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.trust.Topic

/**
 * The decision layer, as a pure state machine.
 *
 * It performs no IO: each function returns the next [Step] and consumes results handed back
 * to it. That is what lets every rule in spec §7–§24 be exercised without a network, a model,
 * or a coroutine runtime — the driver in `:app` does the talking.
 */
class TurnEngine(
    private val registry: SourceRegistry,
    private val store: MemoryStore,
) {

    fun firstStep(ctx: TurnContext): Step {
        // §18 first, unconditionally. Nothing below this line should ever run for a crisis.
        if (ctx.kind == TurnKind.CRISIS) return Step.Crisis()

        if (ctx.kind == TurnKind.SMALLTALK) return Step.Chat
        if (ctx.kind == TurnKind.LOG_QUERY) {
            return Step.SearchLog(ctx.userText, ctx.logFrom, ctx.logTo)
        }

        // §15 — distress opens with comfort and a fork the person answers themselves.
        if (ctx.kind == TurnKind.EMOTIONAL && ctx.fork == null) {
            return Step.ComfortAndFork(UiCopy.COMFORT_FORK)
        }
        if (ctx.fork == ForkAnswer.VENT) return Step.Listen

        val seekingAdvice = ctx.kind == TurnKind.ADVICE || ctx.fork == ForkAnswer.WANT_ADVICE

        // §16 — one bundled clarifying turn, then answer with whatever came back.
        if (seekingAdvice && !ctx.clarified) {
            return Step.Clarify(clarifyingQuestions(ctx))
        }

        // §20 — a stale medical preference must be confirmed before it is reasoned from.
        val prefs = store.preferencesFor(ctx.topic, ctx.now)
        if (prefs.needingConfirmation.isNotEmpty()) {
            return Step.ConfirmPreferences(prefs.needingConfirmation)
        }

        // §10 — live cached knowledge answers immediately; anything expired or unclassifiable
        // goes back to search, because being confidently stale is the expensive failure.
        val recall = ctx.recalled
        if (recall != null && recall.servableWithoutSearch) {
            return Step.ServeFromMemory(recall.fact)
        }

        return Step.Search(supportQueries(ctx), UiCopy.Narration.SEARCHING)
    }

    /**
     * After the support search. Decides whether R6's counter-search runs — and per the spec
     * it runs on any of three triggers, so for high-stakes topics it always does.
     */
    fun afterSupportSearch(
        ctx: TurnContext,
        support: List<Evidence>,
        searchFailed: Boolean,
        /**
         * Set by the driver when the model reads two institutional-or-better sources as
         * pointing opposite ways. Deciding that needs stance extraction from the snippets,
         * which is a model job — the engine takes it as input rather than pretending to
         * detect it here.
         */
        conflictInSupport: Boolean = false,
    ): Step {
        if (searchFailed) {
            return Step.Answer(
                AnswerPlanner.plan(
                    support = emptyList(),
                    topic = ctx.topic,
                    diagnosticSelfQuestion = ctx.diagnosticSelfQuestion,
                    searchAvailable = false,
                    registry = registry,
                ),
            )
        }

        val best = support.maxOfOrNull { it.resolution.tier } ?: Tier.LOW
        val conflict = conflictInSupport && support.count { it.resolution.tier >= Tier.INSTITUTIONAL } >= 2

        if (Disconfirmation.shouldRun(ctx.topic, best, conflict)) {
            val subject = ctx.subject.ifBlank { ctx.userText }
            val queries = Disconfirmation.queriesFor(subject, ctx.topic)
            if (queries.isNotEmpty()) {
                return Step.Disconfirm(
                    queries = queries,
                    narration = UiCopy.Narration.DISCONFIRMING,
                    reason = Disconfirmation.reason(ctx.topic, best, conflict).orEmpty(),
                )
            }
        }
        return Step.Answer(
            planFrom(
                ctx, support, emptyList(),
                if (conflict) Disconfirmation.Outcome.CONTESTED else null,
            ),
        )
    }

    fun afterDisconfirmation(
        ctx: TurnContext,
        support: List<Evidence>,
        counter: List<Evidence>,
    ): Step {
        val supportBest = support.maxOfOrNull { it.resolution.tier } ?: Tier.LOW
        val counterBest = counter.maxOfOrNull { it.resolution.tier } ?: Tier.LOW
        val outcome = Disconfirmation.interpret(
            supportBest = supportBest,
            counterBest = counterBest,
            counterFound = counter.isNotEmpty(),
        )
        return Step.Answer(planFrom(ctx, support, counter, outcome))
    }

    private fun planFrom(
        ctx: TurnContext,
        support: List<Evidence>,
        counter: List<Evidence>,
        outcome: Disconfirmation.Outcome?,
    ) = AnswerPlanner.plan(
        support = support,
        counter = counter,
        counterOutcome = outcome,
        topic = ctx.topic,
        diagnosticSelfQuestion = ctx.diagnosticSelfQuestion,
        searchAvailable = true,
        pendingConfirmations = store.preferencesFor(ctx.topic, ctx.now)
            .needingConfirmation.map { it.text },
        registry = registry,
    )

    private fun supportQueries(ctx: TurnContext): List<SearchQuery> {
        val subject = ctx.subject.ifBlank { ctx.userText }
        val queries = mutableListOf(SearchQuery(subject, purpose = Purpose.SUPPORT))
        // Advice questions have two dimensions and one search can't serve both.
        if (ctx.kind == TurnKind.ADVICE || ctx.fork == ForkAnswer.WANT_ADVICE) {
            queries += SearchQuery(
                SearchTerms.apply(SearchTerms.ADVICE_SUFFIX, subject),
                purpose = Purpose.SUPPORT,
            )
        }
        return queries
    }

    /**
     * §16 — bundled, never one at a time. An advice question has both a practical and a
     * personal dimension, and the answer combines them.
     */
    private fun clarifyingQuestions(ctx: TurnContext): List<String> = when (ctx.topic) {
        Topic.HEALTH, Topic.MEDICATION -> UiCopy.CLARIFY_HEALTH
        Topic.INVESTMENT -> UiCopy.CLARIFY_INVESTMENT
        else -> UiCopy.CLARIFY_GENERAL
    }
}
