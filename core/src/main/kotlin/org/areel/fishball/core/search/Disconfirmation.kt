package org.areel.fishball.core.search

import org.areel.fishball.core.copy.SearchTerms
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.trust.Topic

/**
 * Spec R6 — actively search for the counter-case rather than merely failing to find support.
 *
 * This is the one rule that makes the app do *more* work when it is uncertain, instead of
 * just saying less. It is also what turns "I found no reliable source" into "I checked both
 * directions", which is a materially different thing to tell someone.
 */
object Disconfirmation {

    /**
     * All three triggers are active (user decision, 2026-08-30):
     *  - nothing institutional or better was found
     *  - high-confidence sources contradict each other
     *  - the topic is health / medication / investment / safety, regardless of what was found
     */
    fun shouldRun(topic: Topic, bestTier: Tier, highConfidenceConflict: Boolean): Boolean =
        topic.important || highConfidenceConflict || bestTier < Tier.INSTITUTIONAL

    fun reason(topic: Topic, bestTier: Tier, highConfidenceConflict: Boolean): String? = when {
        topic.important -> "high-stakes topic: always counter-check"
        highConfidenceConflict -> "high-confidence sources disagree"
        bestTier < Tier.INSTITUTIONAL -> "no institutional-or-better source found"
        else -> null
    }

    /**
     * Counter-queries for a subject. Query text is Chinese because the corpus is; the patterns
     * live in SearchTerms with the rest of the product's Chinese.
     */
    fun queriesFor(subject: String, topic: Topic): List<SearchQuery> {
        val term = subject.trim()
        if (term.isEmpty()) return emptyList()

        val patterns = SearchTerms.DISCONFIRM_GENERAL.toMutableList()
        when (topic) {
            Topic.HEALTH, Topic.MEDICATION -> patterns += SearchTerms.DISCONFIRM_MEDICAL
            Topic.INVESTMENT -> patterns += SearchTerms.DISCONFIRM_INVESTMENT
            Topic.SAFETY -> patterns += SearchTerms.DISCONFIRM_SAFETY
            Topic.GENERAL -> Unit
        }
        return patterns.map { SearchQuery(SearchTerms.apply(it, term), purpose = Purpose.DISCONFIRM) }
    }

    /**
     * What the disconfirmation pass concluded. Spec R6's three consequences live here: a
     * successful disconfirmation *raises* confidence rather than muddying it, counter-evidence
     * clears the same trust bar as evidence, and both-directions-empty is itself an answer.
     */
    fun interpret(supportBest: Tier, counterBest: Tier, counterFound: Boolean): Outcome = when {
        !counterFound && supportBest < Tier.INSTITUTIONAL -> Outcome.BOTH_EMPTY
        !counterFound -> Outcome.NO_COUNTER_EVIDENCE
        counterBest >= Tier.INSTITUTIONAL && supportBest < Tier.INSTITUTIONAL -> Outcome.REFUTED
        counterBest >= Tier.INSTITUTIONAL && supportBest >= Tier.INSTITUTIONAL -> Outcome.CONTESTED
        else -> Outcome.WEAK_COUNTER
    }

    enum class Outcome {
        /** Authoritative counter-evidence, no comparable support. A confident answer, not a hedge. */
        REFUTED,

        /** Both sides have institutional backing. Spec R7 — disclose the disagreement. */
        CONTESTED,

        /** Counter-evidence exists but is weak. Mention as uncertainty, not as refutation. */
        WEAK_COUNTER,

        /** Looked and found nothing against it. Slightly strengthens the support. */
        NO_COUNTER_EVIDENCE,

        /** Nothing either way — and saying so is itself the answer. */
        BOTH_EMPTY,
    }
}
