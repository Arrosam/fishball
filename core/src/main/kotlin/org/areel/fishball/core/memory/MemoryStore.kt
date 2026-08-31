package org.areel.fishball.core.memory

import org.areel.fishball.core.trust.Topic

/**
 * Storage for the three kinds of memory in spec §9.
 *
 * Synchronous on purpose. The store is local and small, and keeping it free of `suspend`
 * means every rule above it — staleness, confirmation gates, session bridging — can be
 * tested without a coroutine runtime. `:app` wraps calls in `Dispatchers.IO`.
 */
interface MemoryStore {

    // ---- world knowledge -------------------------------------------------------------

    fun recordWorldFact(fact: WorldFact): Long

    /** Best match for a question, whether or not it is still fresh. Null if nothing is close. */
    fun recallWorldFact(question: String, now: Long): WorldRecall?

    /** Spec §19 — a world-fact correction invalidates the cache and forces a fresh search. */
    fun invalidateWorldFact(id: Long, at: Long)

    // ---- preference knowledge --------------------------------------------------------

    fun recordPreference(fact: PreferenceFact): Long

    /** All preferences, fresh or stale. Staleness is a caller decision, not a filter. */
    fun preferences(): List<PreferenceFact>

    fun confirmPreference(id: Long, at: Long)

    fun forgetPreference(id: Long)

    // ---- conversation log ------------------------------------------------------------

    fun appendTurn(turn: ConversationTurn): Long

    fun turnsInSession(sessionId: Long): List<ConversationTurn>

    /** Spec §9 - "what did I ask you yesterday". Optional time window, newest first. */
    fun searchTurns(query: String, from: Long? = null, to: Long? = null, limit: Int = 20): List<ConversationTurn>

    fun lastTurnAt(): Long?

    fun nextId(): Long
}

/** Convenience view over preferences for a given turn. */
fun MemoryStore.preferencesFor(topic: Topic, now: Long): PreferenceView {
    val all = preferences()
    return PreferenceView(
        usable = all.filter { it.isFresh(now) },
        needingConfirmation = all.filter { it.needsConfirmationFor(topic, now) },
        stale = all.filter { !it.isFresh(now) },
    )
}

data class PreferenceView(
    val usable: List<PreferenceFact>,
    /** Must be confirmed before medical reasoning uses them (§20). */
    val needingConfirmation: List<PreferenceFact>,
    val stale: List<PreferenceFact>,
)
