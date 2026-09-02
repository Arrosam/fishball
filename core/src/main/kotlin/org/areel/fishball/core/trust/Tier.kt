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
    /**
     * Personal posts, and everything else.
     *
     * The default, and the complement of the list rather than a judgement about a site: an
     * unlisted domain is one the registry has no opinion about. A platform floor lives here
     * too - a post on 知乎 or 微博 is a person talking until the account behind it is
     * identified, and identifying it is what lifts it, not the venue it was posted on.
     */
    LOW(Vocabulary.TIER_LOW),

    /**
     * Major news institutions, major self-media, 百度百科, and institutions that are not the
     * record.
     *
     * The band that needs corroboration before anything may be stated from it. The press lives
     * here - all of it except the three state organs, which report as much as they issue and
     * are graded above by the author's decision. So do professional outlets like 丁香园,
     * databases like 知网, and reference works anyone may edit.
     */
    MEDIUM(Vocabulary.TIER_MEDIUM),

    /**
     * Governments and their departments, the state broadcasters and news agencies, Wikipedia,
     * and a major manufacturer describing its own product's specifications.
     *
     * The band a government sits in. A ministry publishing a drug's contraindications is
     * telling you what it has decided, which is exactly what you want and is not the same kind
     * of thing as a finding somebody had to establish - the band above is for the record of
     * what is known, not the record of what has been ruled.
     *
     * A manufacturer is up here for specifications and no higher, and only for those: it is the
     * best source alive for what is in the box, and an interested party on whether the thing is
     * any good. See the scope rules in TrustResolver.
     */
    HIGH(Vocabulary.TIER_HIGH),

    /**
     * The United Nations and its agencies, the national academies, PubMed, and the major
     * academic journals.
     *
     * Deliberately small, and smaller than it was. It is not "important organisations" - it is
     * the places where a finding is established and recorded. A government is not in it: what a
     * ministry publishes is a decision, and decisions change with the government that made
     * them. What is in the Lancet was true before anyone in this app looked it up.
     *
     * The only band a single source can carry an answer on its own, which is the whole reason
     * for keeping it narrow.
     */
    AUTHORITATIVE(Vocabulary.TIER_AUTHORITATIVE),
    ;

    /** Never raises. Used by rules that bound a tier without knowing what it was. */
    fun atMost(cap: Tier): Tier = if (ordinal > cap.ordinal) cap else this
}

/**
 * Topic of the question. `important` marks the high-stakes set from spec R4 — health,
 * medication, investment, safety and spending — which raises corroboration thresholds and
 * always triggers the R6 disconfirmation search.
 */
enum class Topic(val important: Boolean) {
    GENERAL(false),
    HEALTH(true),
    MEDICATION(true),
    INVESTMENT(true),
    SAFETY(true),

    /**
     * Buying something, and which one to buy.
     *
     * High-stakes on the same footing as the other four, by the author's decision. It is the
     * one where the reason is easiest to miss: nobody is harmed by a bad answer about a kettle
     * the way they are by a bad answer about a drug, but the person asking is about to spend
     * their own money on the strength of what this says, and every incentive in the corpus
     * points one way - the pages that rank are written by whoever profits from the sale. A
     * topic where the sources are motivated needs the counter-search more than one where they
     * are merely wrong.
     */
    PURCHASE(true),
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
