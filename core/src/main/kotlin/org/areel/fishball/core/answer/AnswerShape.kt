package org.areel.fishball.core.answer

/**
 * How sure an answer turned out to be, read off the tiers of the sources it rests on.
 *
 * It used to be decided *before* the answer was written: a plan computed from the evidence told
 * the model what shape to write in and how many sentences it had. With the model doing its own
 * looking there is no before-the-fact left to plan in - it decides when it has read enough -
 * so this is now a description of what happened rather than an instruction about what will.
 *
 * The meter in the thread is the only thing that reads it.
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
