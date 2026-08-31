package org.areel.fishball.core.agent

import org.areel.fishball.core.answer.AnswerPlan
import org.areel.fishball.core.memory.PreferenceFact
import org.areel.fishball.core.memory.WorldFact
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.trust.Topic

/** Spec §14 — the only turn kinds that route anywhere special. */
enum class TurnKind {
    /** Has an answer with sources behind it. */
    FACTUAL,

    /** "Should I do X" — needs clarification, then searches both dimensions. */
    ADVICE,

    /** Carries distress. Comfort first, then ask which they want (§15). */
    EMOTIONAL,

    /** Expressed intent toward self-harm. Everything else switches off (§18). */
    CRISIS,

    /** "What did I ask you yesterday" — answered from the conversation log, not the web. */
    LOG_QUERY,

    /** Greetings and thanks. No search, no machinery. */
    SMALLTALK,
}

/**
 * Spec §15 — the person decides between venting and advice, so the app never has to classify
 * "fully emotional" itself.
 */
enum class ForkAnswer { VENT, WANT_ADVICE }

data class TurnContext(
    val userText: String,
    val kind: TurnKind,
    val topic: Topic = Topic.GENERAL,
    /** The searchable subject extracted from the question. Empty when there isn't one. */
    val subject: String = "",
    /** "Do I have condition X" — triggers the §7 split. */
    val diagnosticSelfQuestion: Boolean = false,
    /** Set once the §15 fork has been answered. */
    val fork: ForkAnswer? = null,
    /** Set once the §16 clarifying turn has been answered. */
    val clarified: Boolean = false,
    /** Time window for a LOG_QUERY, if the user named one. */
    val logFrom: Long? = null,
    val logTo: Long? = null,
    val now: Long = 0L,
)

/** What the driver should do next. The engine performs no IO of its own. */
sealed class Step {
    /** §15 — comfort properly, then ask whether they want to vent or want advice. */
    data class ComfortAndFork(val prompt: String) : Step()

    /** §15 — they chose to vent. Listen; no search, no advice, no clarifying questions. */
    object Listen : Step()

    /** §16 — everything needed, bundled into one turn. Never one question at a time. */
    data class Clarify(val questions: List<String>) : Step()

    /** §18 — crisis floor. No search, no tiers, no citations. */
    data class Crisis(val offerFamilyCall: Boolean = true) : Step()

    /** §10 — live cached knowledge answers it; no search needed. */
    data class ServeFromMemory(val fact: WorldFact) : Step()

    data class Search(val queries: List<SearchQuery>, val narration: String) : Step()

    /** R6 — the counter-search. */
    data class Disconfirm(val queries: List<SearchQuery>, val narration: String, val reason: String) : Step()

    /** §9 — answer from the conversation log. */
    data class SearchLog(val query: String, val from: Long?, val to: Long?) : Step()

    /** §20 — confirm aging preferences before medical reasoning leans on them. */
    data class ConfirmPreferences(val stale: List<PreferenceFact>) : Step()

    data class Answer(val plan: AnswerPlan) : Step()

    /** Greetings and thanks — just talk. */
    object Chat : Step()
}
