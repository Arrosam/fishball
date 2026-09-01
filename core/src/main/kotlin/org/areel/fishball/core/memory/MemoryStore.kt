package org.areel.fishball.core.memory

import org.areel.fishball.core.session.Session
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

    /**
     * The few facts worth asking a reranker about, best first.
     *
     * Two passes on purpose. This one is cheap and local and casts a wide net; deciding whether
     * the closest neighbour is actually an answer to *this* question is a judgement, and it is
     * made outside — with a model, by the caller, which is the only part of it that needs a
     * network.
     */
    /**
     * Cached answers worth asking a reranker about, best first.
     *
     * [vectors] is a list because the caller no longer searches with the question. It searches
     * with the *facts the question needs*, which is usually more than one thing - "布洛芬伤胃吗"
     * needs the side effects and it needs the dosage - and a fact is a candidate if it matches
     * any of them. [terms] is the same list as plain text, for the word-overlap fallback.
     *
     * Wide on purpose. This pass is cheap, local and dumb; narrowing happens later, with a
     * model, against the actual question.
     */
    fun recallWorldCandidates(
        terms: List<String>,
        vectors: List<List<Float>>,
        now: Long,
        limit: Int = 10,
    ): List<WorldRecall>

    /** The same pass over what the user has said about themselves. */
    fun recallPreferenceCandidates(
        terms: List<String>,
        vectors: List<List<Float>>,
        limit: Int = 10,
    ): List<PreferenceRecall>

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

    /**
     * The tail of the log in the order it was said, for redrawing the thread on launch.
     *
     * Not scoped to a session. Sessions bound the prompt, not the conversation - §8 says the
     * user's mental model is one endless thread, and a thread that emptied itself every hour
     * would make the boundary visible, which is the one thing sessions must not be.
     */
    fun recentTurns(limit: Int = 100): List<ConversationTurn>

    fun lastTurnAt(): Long?

    /** How much conversation is being kept. Shown to the user before they decide to drop it. */
    fun turnCount(): Int

    /**
     * What the conversation itself weighs, in UTF-8 bytes.
     *
     * Not the size of the file it lives in. That file also holds the cached answers and their
     * embeddings - a thousand floats per fact - so quoting it beside "对话记录" would tell
     * someone their chat history was megabytes and leave them none the wiser when clearing it
     * barely moved the number.
     */
    fun turnBytes(): Long

    /**
     * Drop the conversation log, and the session with it.
     *
     * Only the log. What was learned - the cached answers and the facts about the person - is a
     * different thing that the memory screen governs, and quietly wiping it because someone
     * asked to clear their chat history would be the app deciding those meant the same thing.
     */
    fun clearTurns()

    // ---- session --------------------------------------------------------------------

    /**
     * The session in progress, so closing the app does not silently start a new one. Without
     * this the thread survives a restart but the model's sense of the conversation does not,
     * which is a worse failure than either alone: it answers as though it had never been told
     * anything, about a conversation the user can still see.
     */
    fun saveSession(session: Session)

    fun loadSession(): Session?

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
