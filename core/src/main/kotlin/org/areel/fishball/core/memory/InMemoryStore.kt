package org.areel.fishball.core.memory

import org.areel.fishball.core.text.Similarity

/**
 * Reference implementation of [MemoryStore], held in RAM.
 *
 * Not a mock — this is the behaviour `:app`'s SQLite implementation must reproduce, and the
 * spec tests run against it. Keeping a real, readable implementation in `:core` means the
 * storage rules are pinned somewhere testable rather than living only inside SQL.
 */
class InMemoryStore : MemoryStore {

    private val world = mutableListOf<WorldFact>()
    private val preferences = mutableListOf<PreferenceFact>()
    private val turns = mutableListOf<ConversationTurn>()
    private var idSeq = 0L

    override fun nextId(): Long = ++idSeq

    // ---- world knowledge -------------------------------------------------------------

    override fun recordWorldFact(fact: WorldFact): Long {
        world += fact
        return fact.id
    }

    override fun recallWorldFact(question: String, now: Long): WorldRecall? {
        val best = world
            .filter { it.invalidatedAt == null }
            .map { fact -> fact to Similarity.jaccard(question, fact.question) }
            .filter { (_, score) -> score >= Similarity.SAME_QUESTION_THRESHOLD }
            .maxByOrNull { (_, score) -> score }
            ?: return null
        val (fact, score) = best
        return WorldRecall(fact, score, fact.isFresh(now))
    }

    override fun invalidateWorldFact(id: Long, at: Long) {
        val index = world.indexOfFirst { it.id == id }
        if (index >= 0) world[index] = world[index].copy(invalidatedAt = at)
    }

    // ---- preference knowledge --------------------------------------------------------

    override fun recordPreference(fact: PreferenceFact): Long {
        preferences += fact
        return fact.id
    }

    override fun preferences(): List<PreferenceFact> = preferences.toList()

    override fun confirmPreference(id: Long, at: Long) {
        val index = preferences.indexOfFirst { it.id == id }
        if (index >= 0) preferences[index] = preferences[index].copy(confirmedAt = at)
    }

    override fun forgetPreference(id: Long) {
        preferences.removeAll { it.id == id }
    }

    // ---- conversation log ------------------------------------------------------------

    override fun appendTurn(turn: ConversationTurn): Long {
        turns += turn
        return turn.id
    }

    override fun turnsInSession(sessionId: Long): List<ConversationTurn> =
        turns.filter { it.sessionId == sessionId }.sortedBy { it.at }

    override fun searchTurns(query: String, from: Long?, to: Long?, limit: Int): List<ConversationTurn> =
        turns
            .filter { from == null || it.at >= from }
            .filter { to == null || it.at <= to }
            .map { it to Similarity.jaccard(query, it.text) }
            .filter { (turn, score) -> score > 0.12 || turn.text.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<Pair<ConversationTurn, Double>> { it.second }.thenByDescending { it.first.at })
            .take(limit)
            .map { it.first }

    override fun lastTurnAt(): Long? = turns.maxByOrNull { it.at }?.at
}
