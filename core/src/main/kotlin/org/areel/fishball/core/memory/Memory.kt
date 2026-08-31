package org.areel.fishball.core.memory

import org.areel.fishball.core.copy.Vocabulary
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.trust.Topic

const val ONE_DAY_MS = 86_400_000L
const val ONE_MONTH_MS = 30 * ONE_DAY_MS
const val SIX_MONTHS_MS = 182 * ONE_DAY_MS
const val ONE_YEAR_MS = 365 * ONE_DAY_MS

/**
 * Spec §9/§10 — how long a retrieved fact stays usable without re-searching.
 *
 * A null lifetime never expires. ALWAYS_RESEARCH exists so the classifier has somewhere to
 * put things it cannot judge: the default when unsure is to search again, because
 * re-searching costs three seconds and being confidently stale costs the user's trust in the
 * whole app.
 *
 * [label] is what the model emits and what the maintenance view shows.
 */
enum class WorldTtl(val label: String, val lifetimeMs: Long?) {
    PERMANENT(Vocabulary.TTL_PERMANENT, null),
    ONE_YEAR(Vocabulary.TTL_ONE_YEAR, org.areel.fishball.core.memory.ONE_YEAR_MS),
    ONE_MONTH(Vocabulary.TTL_ONE_MONTH, org.areel.fishball.core.memory.ONE_MONTH_MS),
    ALWAYS_RESEARCH(Vocabulary.TTL_ALWAYS_RESEARCH, 0L),
    ;

    companion object {
        /** Unknown or unparseable classification means re-search. Never guess PERMANENT. */
        fun parse(raw: String?): WorldTtl =
            values().firstOrNull { it.label == raw?.trim() || it.name.equals(raw?.trim(), true) }
                ?: ALWAYS_RESEARCH
    }
}

/** Spec §20 — preference knowledge expires too, or it becomes a slow lie about a person. */
enum class PreferenceTtl(val label: String, val lifetimeMs: Long?) {
    PERMANENT(Vocabulary.TTL_PERMANENT, null),
    SIX_MONTHS(Vocabulary.TTL_SIX_MONTHS, org.areel.fishball.core.memory.SIX_MONTHS_MS),
    ONE_MONTH(Vocabulary.TTL_ONE_MONTH, org.areel.fishball.core.memory.ONE_MONTH_MS),
    ;

    companion object {
        /** Unknown means short-lived, not permanent — the safe direction for a fact about a person. */
        fun parse(raw: String?): PreferenceTtl =
            values().firstOrNull { it.label == raw?.trim() || it.name.equals(raw?.trim(), true) }
                ?: ONE_MONTH
    }
}

enum class PreferenceKind(val defaultTtl: PreferenceTtl) {
    /** Allergies, chronic conditions, blood type — does not change. */
    MEDICAL_CONSTANT(PreferenceTtl.PERMANENT),

    /** Occupation, languages read, recurring interests — changes rarely. */
    PROFILE(PreferenceTtl.PERMANENT),

    /** Current medication, current project — changes over months, and matters medically. */
    CURRENT_STATE(PreferenceTtl.SIX_MONTHS),

    /** "Not sleeping well lately", "tired lately" — the sentence says "lately" for a reason. */
    TRANSIENT(PreferenceTtl.ONE_MONTH),
}

/** A fact retrieved from search, cached so a repeat question can be answered immediately. */
data class WorldFact(
    val id: Long,
    val question: String,
    val answer: String,
    val ttl: WorldTtl,
    val tier: Tier,
    val sources: List<String> = emptyList(),
    val recordedAt: Long,
    /** Set when the user contradicted it (§19) — kept for provenance, never served. */
    val invalidatedAt: Long? = null,
) {
    fun isFresh(now: Long): Boolean {
        if (invalidatedAt != null) return false
        val lifetime = ttl.lifetimeMs ?: return true
        if (lifetime == 0L) return false
        return now - recordedAt < lifetime
    }
}

/** Something learned about the user. */
data class PreferenceFact(
    val id: Long,
    val text: String,
    val kind: PreferenceKind,
    val ttl: PreferenceTtl,
    val recordedAt: Long,
    /** Last time the user confirmed it. Confirming resets the clock (§20). */
    val confirmedAt: Long = recordedAt,
) {
    fun isFresh(now: Long): Boolean {
        val lifetime = ttl.lifetimeMs ?: return true
        return now - confirmedAt < lifetime
    }

    /**
     * Spec §20 — an expired preference is demoted, not deleted, and must be confirmed before
     * medical reasoning leans on it. Asking whether they still take a drug is cheap; reasoning
     * about one they stopped five months ago is not.
     */
    fun needsConfirmationFor(topic: Topic, now: Long): Boolean =
        !isFresh(now) && (topic == Topic.HEALTH || topic == Topic.MEDICATION)
}

enum class Speaker { USER, ASSISTANT }

/** Spec §9 — every turn is kept and searchable, so "what did I ask you yesterday" is answerable. */
data class ConversationTurn(
    val id: Long,
    val sessionId: Long,
    val at: Long,
    val speaker: Speaker,
    val text: String,
)

/** A cached answer plus why it is or isn't usable, so the caller never has to re-derive it. */
data class WorldRecall(
    val fact: WorldFact,
    val similarity: Double,
    val fresh: Boolean,
) {
    val servableWithoutSearch: Boolean get() = fresh
}
