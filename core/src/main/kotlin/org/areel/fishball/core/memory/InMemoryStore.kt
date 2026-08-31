package org.areel.fishball.core.memory

import org.areel.fishball.core.session.Session
import org.areel.fishball.core.text.Similarity
import org.areel.fishball.core.text.cosine

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

    private companion object {
        /** Low. This pass is a net, not a decision — the reranker makes the decision. */
        const val CANDIDATE_FLOOR = 0.25
    }
    private var current: org.areel.fishball.core.session.Session? = null

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

    override fun recallCandidates(
        question: String,
        vector: List<Float>,
        now: Long,
        limit: Int,
    ): List<WorldRecall> {
        val live = world.filter { it.invalidatedAt == null }
        if (live.isEmpty()) return emptyList()

        val scored = live.map { fact ->
            // Cosine where both sides have a vector, word overlap where either does not. The
            // fallback matters on a store written before embeddings existed, or written while
            // the embedding model was unreachable.
            val score = if (vector.isNotEmpty() && fact.embedding.isNotEmpty()) {
                cosine(vector, fact.embedding)
            } else {
                Similarity.jaccard(question, fact.question)
            }
            WorldRecall(fact, score, fact.isFresh(now))
        }
        return scored
            .filter { it.similarity >= CANDIDATE_FLOOR }
            .sortedByDescending { it.similarity }
            .take(limit)
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

    override fun saveSession(session: Session) {
        current = session
    }

    override fun loadSession(): Session? = current

    override fun recentTurns(limit: Int): List<ConversationTurn> =
        turns.sortedBy { it.at }.takeLast(limit)

    override fun lastTurnAt(): Long? = turns.maxByOrNull { it.at }?.at

    // ---- snapshot ---------------------------------------------------------------------

    /** Everything the memory screen lists. Invalidated facts included — §19 keeps provenance. */
    fun worldFacts(): List<WorldFact> = world.toList()

    /**
     * The whole store, flattened. Exists so [PersistentStore] can write this implementation to
     * disk instead of there being a second one that has to behave identically.
     */
    fun snapshot() = MemorySnapshot(
        session = current?.let { SessionDto(it.id, it.startedAt, it.bridge) },
        world = world.map { it.toDto() },
        preferences = preferences.map { it.toDto() },
        turns = turns.map { it.toDto() },
        idSeq = idSeq,
    )

    fun restore(snapshot: MemorySnapshot) {
        world.clear(); world += snapshot.world.map { it.toDomain() }
        preferences.clear(); preferences += snapshot.preferences.map { it.toDomain() }
        turns.clear(); turns += snapshot.turns.map { it.toDomain() }
        // Never rewind: ids handed out before a crash must not be handed out again, so this
        // takes the larger of the recorded sequence and anything actually present.
        current = snapshot.session?.let {
            org.areel.fishball.core.session.Session(it.id, it.startedAt, it.bridge)
        }
        idSeq = maxOf(
            snapshot.idSeq,
            (world.maxOfOrNull { it.id } ?: 0L),
            (preferences.maxOfOrNull { it.id } ?: 0L),
            (turns.maxOfOrNull { it.id } ?: 0L),
        )
    }
}
