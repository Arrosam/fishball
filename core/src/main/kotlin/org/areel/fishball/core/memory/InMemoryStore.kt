package org.areel.fishball.core.memory

import org.areel.fishball.core.session.Session
import org.areel.fishball.core.text.Similarity
import org.areel.fishball.core.text.cosine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Reference implementation of [MemoryStore], held in RAM.
 *
 * Not a mock — this is the behaviour `:app`'s SQLite implementation must reproduce, and the
 * spec tests run against it. Keeping a real, readable implementation in `:core` means the
 * storage rules are pinned somewhere testable rather than living only inside SQL.
 *
 * Safe for one writer alongside readers, which it has to be: the memory bus writes from its
 * own coroutine while the turn that triggered it is still reading. Copy-on-write rather than a
 * lock because the shape of the load is lopsided — recall reads the whole list on every turn
 * and writes arrive a handful at a time — and because a reader mid-iteration must never see a
 * ConcurrentModificationException on a background write it knows nothing about.
 */
class InMemoryStore : MemoryStore {

    private val world = CopyOnWriteArrayList<WorldFact>()
    private val preferences = CopyOnWriteArrayList<PreferenceFact>()
    private val turns = CopyOnWriteArrayList<ConversationTurn>()

    /** Ids are handed out from two coroutines now, and two facts sharing one is a lost fact. */
    private val idSeq = AtomicLong(0)

    private companion object {
        /** Low. This pass is a net, not a decision — the reranker makes the decision. */
        const val CANDIDATE_FLOOR = 0.25
    }
    private var current: org.areel.fishball.core.session.Session? = null

    override fun nextId(): Long = idSeq.incrementAndGet()

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

    override fun recallWorldCandidates(
        terms: List<String>,
        vectors: List<List<Float>>,
        now: Long,
        limit: Int,
    ): List<WorldRecall> {
        val live = world.filter { it.invalidatedAt == null }
        if (live.isEmpty()) return emptyList()

        return live
            .map { fact -> WorldRecall(fact, match(terms, vectors, fact.question, fact.embedding), fact.isFresh(now)) }
            .filter { it.similarity >= CANDIDATE_FLOOR }
            .sortedByDescending { it.similarity }
            .take(limit)
    }

    override fun recallPreferenceCandidates(
        terms: List<String>,
        vectors: List<List<Float>>,
        limit: Int,
    ): List<PreferenceRecall> {
        if (preferences.isEmpty()) return emptyList()
        return preferences
            .map { fact -> PreferenceRecall(fact, match(terms, vectors, fact.text, fact.embedding)) }
            .filter { it.similarity >= CANDIDATE_FLOOR }
            .sortedByDescending { it.similarity }
            .take(limit)
    }

    /**
     * Best match against any of the things being looked for.
     *
     * Cosine where both sides have a vector, word overlap where either does not - the fallback
     * matters on a store written before embeddings existed, or written while the embedding
     * model was unreachable.
     */
    private fun match(
        terms: List<String>,
        vectors: List<List<Float>>,
        text: String,
        embedding: List<Float>,
    ): Double {
        val byVector = if (embedding.isEmpty()) {
            0.0
        } else {
            vectors.maxOfOrNull { cosine(it, embedding) } ?: 0.0
        }
        val byWords = terms.maxOfOrNull { Similarity.jaccard(it, text) } ?: 0.0
        return maxOf(byVector, byWords)
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

    /**
     * In place, keeping the turn's position in the log.
     *
     * Position rather than timestamp, because a checkpoint and the answer it grows into share an
     * id and not a clock: the row is claimed when the question is asked and closed when the
     * answer lands, and re-appending it would move the answer behind anything filed in between.
     */
    override fun replaceTurn(turn: ConversationTurn) {
        val index = turns.indexOfFirst { it.id == turn.id }
        if (index >= 0) turns[index] = turn else turns += turn
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

    override fun turnCount(): Int = turns.size

    override fun turnBytes(): Long = turns.sumOf { turn ->
        turn.text.toByteArray(Charsets.UTF_8).size.toLong() +
            turn.sources.sumOf { source ->
                (source.displayName.length + source.url.length + (source.quote?.length ?: 0)).toLong()
            } +
            // The tool results are most of what a researched answer weighs now that they are
            // kept, and a size that left them out would barely move when the log is cleared.
            turn.rounds.sumOf { round ->
                round.exchanges.sumOf { (it.input.toString().length + it.result.length).toLong() }
            }
    }

    override fun clearTurns() {
        turns.clear()
        // The session goes too. Its bridge summarises a conversation that no longer exists, and
        // carrying it forward would let the model refer to something the user has just deleted.
        current = null
    }

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
        idSeq = idSeq.get(),
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
        idSeq.set(
            maxOf(
                snapshot.idSeq,
                (world.maxOfOrNull { it.id } ?: 0L),
                (preferences.maxOfOrNull { it.id } ?: 0L),
                (turns.maxOfOrNull { it.id } ?: 0L),
            ),
        )
    }
}
