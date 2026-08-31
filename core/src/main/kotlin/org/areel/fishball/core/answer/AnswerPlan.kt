package org.areel.fishball.core.answer

import org.areel.fishball.core.search.Disconfirmation
import org.areel.fishball.core.trust.Corroboration
import org.areel.fishball.core.trust.Evidence
import org.areel.fishball.core.trust.SourceRegistry
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.trust.Topic

/**
 * The shape an answer must take, decided from the evidence rather than left to the model.
 *
 * The model writes the prose; core decides what the prose is allowed to do. That split is
 * what makes spec §6 testable — "does a weak-evidence question lead with the warning" becomes
 * a question about [AnswerShape], not about a paragraph of Chinese.
 *
 * The Chinese instruction for each shape lives in `copy.AgentPrompt.guidanceFor`.
 */
enum class AnswerShape {
    /** Authoritative backing. State it plainly and name the source in-sentence. */
    CONFIDENT,

    /** Institutional backing. State it, attributed to the institution. */
    ATTRIBUTED,

    /** Personal sources with the R4 corroboration gate passed — statable as a pattern. */
    PERSONAL_PATTERN,

    /** Only weak sources. Spec §6 — lead with the status, never a trailing caveat. */
    WEAK_LEAD,

    /** R6 found authoritative counter-evidence. This is a confident answer, not a hedge. */
    REFUTED,

    /** R7 — authoritative sources disagree. Disclose it. */
    CONFLICT,

    /** Searched both directions, found nothing usable. */
    NOTHING_FOUND,

    /** §23 — search is down. Never assert an unsourced fact. */
    SEARCH_UNAVAILABLE,
}

data class AnswerPlan(
    val shape: AnswerShape,
    /** Ready-to-use in-sentence attributions, R8-expanded for foreign institutions. */
    val attributions: List<String> = emptyList(),
    /** URLs for the source card below the answer. */
    val sources: List<String> = emptyList(),
    /** Spec §7/§17 — answer the factual half, refuse the personal diagnostic leap. */
    val medicalSplit: Boolean = false,
    /** Spec §20 — stale preferences that must be confirmed before this answer relies on them. */
    val confirmations: List<String> = emptyList(),
    /** Spec §24 — answer first, briefly. */
    val maxSentences: Int = 3,
    val offerDepth: Boolean = true,
    val notes: List<String> = emptyList(),
) {
    /** §24 — prose only. No markdown ever reaches the user. */
    val allowMarkdown: Boolean get() = false
}

object AnswerPlanner {

    fun plan(
        support: List<Evidence>,
        counter: List<Evidence> = emptyList(),
        counterOutcome: Disconfirmation.Outcome? = null,
        topic: Topic = Topic.GENERAL,
        diagnosticSelfQuestion: Boolean = false,
        searchAvailable: Boolean = true,
        pendingConfirmations: List<String> = emptyList(),
        registry: SourceRegistry,
    ): AnswerPlan {
        val medicalSplit = diagnosticSelfQuestion &&
            (topic == Topic.HEALTH || topic == Topic.MEDICATION)

        if (!searchAvailable) {
            return AnswerPlan(
                shape = AnswerShape.SEARCH_UNAVAILABLE,
                maxSentences = 2,
                offerDepth = false,
                medicalSplit = medicalSplit,
                notes = listOf("§23 search unavailable; model knowledge must not fill the gap"),
            )
        }

        val bestSupport = support.maxTier()
        val bestCounter = counter.maxTier()
        val contested = Corroboration.hasUncontestedHighConfidence(support, counter).not() &&
            bestSupport >= Tier.INSTITUTIONAL && bestCounter >= Tier.INSTITUTIONAL

        val shape = when {
            contested || counterOutcome == Disconfirmation.Outcome.CONTESTED -> AnswerShape.CONFLICT
            counterOutcome == Disconfirmation.Outcome.REFUTED -> AnswerShape.REFUTED
            bestSupport == Tier.AUTHORITATIVE -> AnswerShape.CONFIDENT
            bestSupport == Tier.INSTITUTIONAL -> AnswerShape.ATTRIBUTED
            bestSupport == Tier.PERSONAL && personalPatternAllowed(support, counter, topic, registry) ->
                AnswerShape.PERSONAL_PATTERN
            support.isEmpty() -> AnswerShape.NOTHING_FOUND
            else -> AnswerShape.WEAK_LEAD
        }

        val cited = when (shape) {
            AnswerShape.REFUTED, AnswerShape.CONFLICT -> support + counter
            AnswerShape.NOTHING_FOUND, AnswerShape.SEARCH_UNAVAILABLE -> emptyList()
            else -> support
        }.filter { it.resolution.tier >= Tier.PERSONAL }

        return AnswerPlan(
            shape = shape,
            attributions = cited.map { it.resolution.attribution() }.distinct(),
            sources = cited.map { it.hit.url }.distinct(),
            medicalSplit = medicalSplit,
            confirmations = pendingConfirmations,
            maxSentences = sentenceBudget(shape, medicalSplit),
            offerDepth = shape != AnswerShape.SEARCH_UNAVAILABLE,
            notes = buildNotes(topic, counterOutcome, medicalSplit),
        )
    }

    /**
     * Spec R4 — a personal-source pattern may only be stated once enough *independent*
     * publishers converge, with the threshold scaled by topic importance and by whether
     * anything institutional already backs the claim.
     */
    private fun personalPatternAllowed(
        support: List<Evidence>,
        counter: List<Evidence>,
        topic: Topic,
        registry: SourceRegistry,
    ): Boolean {
        val personal = support.filter { it.resolution.tier == Tier.PERSONAL }
        val backed = Corroboration.hasUncontestedHighConfidence(support, counter)
        return Corroboration.canStatePattern(personal, registry, topic, backed)
    }

    /**
     * §24 — answer first, briefly. Medical splits and disclosed disagreements genuinely need
     * more room; nothing else does, and every other rule in the spec pushes answers longer.
     */
    private fun sentenceBudget(shape: AnswerShape, medicalSplit: Boolean): Int = when {
        medicalSplit -> 6
        shape == AnswerShape.CONFLICT -> 6
        shape == AnswerShape.SEARCH_UNAVAILABLE -> 2
        else -> 3
    }

    private fun buildNotes(
        topic: Topic,
        outcome: Disconfirmation.Outcome?,
        medicalSplit: Boolean,
    ): List<String> = buildList {
        if (medicalSplit) add("§7 factual half only; refuse the personal diagnosis, name the specialty and test")
        if (topic.important) add("R6 high-stakes topic; counter-check performed")
        if (outcome == Disconfirmation.Outcome.BOTH_EMPTY) add("both directions searched, both empty")
        if (outcome == Disconfirmation.Outcome.WEAK_COUNTER) {
            add("counter-evidence is weak; mention as uncertainty, not as refutation")
        }
    }

    private fun List<Evidence>.maxTier(): Tier =
        maxOfOrNull { it.resolution.tier } ?: Tier.LOW
}
