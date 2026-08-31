package org.areel.fishball.core.trust

import org.areel.fishball.core.copy.Vocabulary

/**
 * Trust tiers, ordered. Comparison is by ordinal, so LOW < PERSONAL < INSTITUTIONAL <
 * AUTHORITATIVE — several rules cap rather than set a tier and rely on that ordering.
 *
 * [label] is the term used in the prompt and shown to the user; it lives in Vocabulary so
 * the two can never drift apart.
 */
enum class Tier(val label: String) {
    LOW(Vocabulary.TIER_LOW),
    PERSONAL(Vocabulary.TIER_PERSONAL),
    INSTITUTIONAL(Vocabulary.TIER_INSTITUTIONAL),
    AUTHORITATIVE(Vocabulary.TIER_AUTHORITATIVE),
    ;

    /** Never raises. Used by rules that bound a tier without knowing what it was. */
    fun atMost(cap: Tier): Tier = if (ordinal > cap.ordinal) cap else this
}

/**
 * Topic of the question. `important` marks the high-stakes set from spec R4 — health,
 * medication, investment, safety — which raises corroboration thresholds and always triggers
 * the R6 disconfirmation search.
 */
enum class Topic(val important: Boolean) {
    GENERAL(false),
    HEALTH(true),
    MEDICATION(true),
    INVESTMENT(true),
    SAFETY(true),
}

/**
 * What kind of claim the source is being cited for. Authority is scoped to the claim, not
 * granted to the domain — see spec R3 and seller-efficacy.
 */
enum class ClaimKind {
    /** Specifications, ingredients, price, availability — checkable attributes. */
    OBJECTIVE_ATTRIBUTE,

    /** Is it any good, is it better than X — judgement. */
    EVALUATIVE,

    /** Does the thing work — efficacy and health benefit claims. */
    EFFICACY,
}
