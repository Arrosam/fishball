package org.areel.fishball.core.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.areel.fishball.core.answer.AnswerShape
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.trust.Tier

/**
 * Memory that survives the app being closed.
 *
 * A whole-file snapshot rather than a database. The store holds a person's cached answers,
 * a handful of facts about them and their conversation log — kilobytes, read once at launch
 * and rewritten on change. SQLite would buy indexed queries this never issues, at the cost of
 * a schema, migrations, and a second implementation of the rules `InMemoryStore` already pins.
 *
 * If the log ever grows past what is comfortable to rewrite, the thing to change is this class,
 * not the interface: everything above it is written against [MemoryStore].
 */
interface SnapshotIo {
    fun read(): String?
    fun write(contents: String)
}

/**
 * The conversation log, which is written far more often than everything else put together.
 *
 * Measured on a real install: one nine-round question rewrote the whole snapshot eleven times at
 * around 360KB a go - four megabytes of flash for one answer - and half of every one of those
 * writes was the embeddings on cached facts, which had not changed, while most of the rest was
 * thirty-six turns that had not changed either. Only one turn is ever being written.
 *
 * So turns live here instead: append one line per write, never rewrite to record a change. A
 * checkpoint costs the turn it is checkpointing and nothing else. [rewrite] exists because an
 * append-only file grows without bound - it is the compaction, and the store decides when.
 *
 * Lines, not documents, because that is what makes an append an append. What a line contains is
 * the store's business; this only has to keep them in order and hand them back the same way.
 */
interface TurnLogIo {
    fun lines(): List<String>

    fun append(line: String)

    /** The whole log, replaced by what is actually live. See [PersistentStore]. */
    fun rewrite(lines: List<String>)
}

/** For tests and for a store with nowhere to put a log. Holds what it is given, and no more. */
class InMemoryTurnLog(seed: List<String> = emptyList()) : TurnLogIo {
    private val written = mutableListOf<String>().apply { addAll(seed) }

    override fun lines(): List<String> = synchronized(this) { written.toList() }

    override fun append(line: String) = synchronized(this) { written.add(line); Unit }

    override fun rewrite(lines: List<String>): Unit = synchronized(this) {
        written.clear()
        written.addAll(lines)
    }
}

@Serializable
data class MemorySnapshot(
    val world: List<WorldFactDto> = emptyList(),
    val preferences: List<PreferenceFactDto> = emptyList(),
    /**
     * Where turns used to live, and where they are read from once more.
     *
     * Not written any more - [PersistentStore] keeps them in a [TurnLogIo] and writes the
     * snapshot without them. The field stays because every install has a file with turns in it,
     * and the first launch after this change reads them out of here and into the log. Dropping
     * it would be dropping everybody's conversation.
     */
    val turns: List<TurnDto> = emptyList(),
    val idSeq: Long = 0L,
    val session: SessionDto? = null,
)

@Serializable
data class SessionDto(
    val id: Long,
    val startedAt: Long,
    val bridge: String? = null,
    // Defaulted, so a session written before compaction had a seam reads back as one that has
    // never been compacted - which is what it is.
    val compactedThrough: Long = 0L,
)

/*
 * DTOs rather than @Serializable on the domain types.
 *
 * The domain types are the app's vocabulary and change when the product changes; the stored
 * form has to keep reading files written by older versions. Splitting them means a rename in
 * the domain is a compile error here — where it can be handled — instead of an unreadable file
 * on somebody's phone.
 */

@Serializable
data class WorldFactDto(
    val id: Long,
    val question: String,
    val answer: String,
    val ttl: String,
    val tier: String,
    val sources: List<String> = emptyList(),
    val embedding: List<Float> = emptyList(),
    val recordedAt: Long,
    val invalidatedAt: Long? = null,
)

@Serializable
data class PreferenceFactDto(
    val id: Long,
    val text: String,
    val kind: String,
    val ttl: String,
    val embedding: List<Float> = emptyList(),
    val recordedAt: Long,
    val confirmedAt: Long,
)

@Serializable
data class TurnDto(
    val id: Long,
    val sessionId: Long,
    val at: Long,
    val speaker: String,
    val text: String,
    // Defaulted, so a file written before answers were recorded still reads.
    val shape: String? = null,
    val sources: List<CitedSourceDto> = emptyList(),
    // Same reason, one version later: a log written before reasoning was kept still reads,
    // and its turns simply carry none.
    val reasoning: String = "",
    // And the same again for pictures. Names only - the files live beside this one, because a
    // photograph base64'd into the log would be rewritten to disk on every single turn.
    val images: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    // And once more for the tool calls. A log written before they were kept replays its
    // answers as it always did, with nothing between question and answer.
    val rounds: List<ToolRoundDto> = emptyList(),
    // Defaulted false, which is the right reading of a log written before this existed: whatever
    // those turns were, nothing is going to resume them now.
    val inFlight: Boolean = false,
)

@Serializable
data class ToolRoundDto(
    val exchanges: List<ToolExchangeDto> = emptyList(),
    val known: List<String> = emptyList(),
    // Defaulted like the rest: a log written before a round's own reasoning was kept replays
    // its rounds as it always did, with the calls and none of the thinking behind them.
    val thinking: String = "",
)

@Serializable
data class ToolExchangeDto(
    val id: String,
    val name: String,
    val input: JsonObject,
    val result: String,
    val isError: Boolean = false,
)

@Serializable
data class CitedSourceDto(
    val url: String,
    val displayName: String,
    val explanation: String? = null,
    val tier: String,
    val quote: String? = null,
)

/**
 * [InMemoryStore]'s behaviour, written through to [io] after every change.
 *
 * Delegation rather than reimplementation, so the rules the spec tests pin — similarity
 * recall, staleness, log search — have exactly one implementation and cannot drift between
 * the tested one and the shipped one.
 */
class PersistentStore(
    private val io: SnapshotIo,
    /**
     * Where the conversation goes, separately and by appending. See [TurnLogIo].
     *
     * Defaulted so a caller with nothing to persist turns to still gets a working store; `:app`
     * hands in a file. A store built without one keeps its turns for the life of the process
     * and no longer, which is the right behaviour for a test and the wrong one for a phone.
     */
    private val turnLog: TurnLogIo = InMemoryTurnLog(),
    private val inner: InMemoryStore = InMemoryStore(),
) : MemoryStore {

    /**
     * Bytes appended since the log was last written out whole.
     *
     * An append-only file grows by a turn on every checkpoint, so a long question can add its
     * own size several times over. Compaction is amortised against this rather than against a
     * count of lines: turns differ in size by two orders of magnitude - a question is a dozen
     * bytes and a researched answer measured 46KB on a real install - so lines are the wrong
     * unit for deciding when a file has got fat.
     */
    private var appended = 0L

    init {
        val snapshot = io.read()?.takeIf { it.isNotBlank() }?.let { text ->
            // A corrupt or half-written file must not brick the app. Losing memory is bad;
            // refusing to start is worse, and the log is not the product.
            runCatching {
                json.decodeFromString(MemorySnapshot.serializer(), text)
            }.getOrNull()
        } ?: MemorySnapshot()

        /*
         * The log wins where it has anything to say.
         *
         * Each line is one turn as it stood when it was written, and a turn written twice - a
         * checkpoint, then the answer that grew out of it - appears twice. Last one wins, and
         * the map keeps the position of the first, which is what [InMemoryStore.replaceTurn]
         * does in memory and therefore what replaying the file has to reproduce.
         */
        val logged = LinkedHashMap<Long, TurnDto>()
        turnLog.lines().forEach { line ->
            runCatching {
                json.decodeFromString(TurnDto.serializer(), line)
            }.getOrNull()?.let { logged[it.id] = it }
        }

        inner.restore(
            snapshot.copy(turns = if (logged.isEmpty()) snapshot.turns else logged.values.toList()),
        )

        /*
         * The one-time move, for a file written before turns had a log of their own.
         *
         * Every install has one. Read out of the snapshot, written into the log, and the
         * snapshot rewritten without them - after which the two never disagree, because only
         * one of them is written to.
         */
        if (logged.isEmpty() && snapshot.turns.isNotEmpty()) {
            turnLog.rewrite(snapshot.turns.map { json.encodeToString(TurnDto.serializer(), it) })
            flush()
        }
    }

    /**
     * Everything except the conversation, written out whole.
     *
     * Synchronised because there are two writers: the turn appending to the log, and the memory
     * bus recording what it learned, on its own coroutine. Each call encodes what it is given and
     * replaces the file, so two of them interleaving would race one full snapshot against another
     * and let the older one land last.
     *
     * Turns are stripped on the way out. They are the half that changes every few seconds and the
     * half this file cannot afford to carry - see [TurnLogIo] for the measurement.
     */
    private fun flush() = synchronized(this) {
        runCatching {
            io.write(
                json.encodeToString(
                    MemorySnapshot.serializer(),
                    inner.snapshot().copy(turns = emptyList()),
                ),
            )
        }
        Unit
    }

    /**
     * One turn, appended.
     *
     * The whole point: a checkpoint writes the turn it is checkpointing, not the store. When the
     * appends have outgrown what is actually live the file is written out once from memory and
     * the counter resets, so total IO stays inside a small multiple of what was appended.
     */
    private fun record(turn: ConversationTurn) = synchronized(this) {
        runCatching {
            val line = json.encodeToString(TurnDto.serializer(), turn.toDto())
            turnLog.append(line)
            appended += line.length
            if (appended > REWRITE_AFTER) compactLog()
        }
        Unit
    }

    /** The log, written out from what is live, and the counter with it. Call under the lock. */
    private fun compactLog() {
        turnLog.rewrite(
            inner.snapshot().turns.map { json.encodeToString(TurnDto.serializer(), it) },
        )
        appended = 0
    }

    override fun nextId(): Long = inner.nextId()

    override fun recordWorldFact(fact: WorldFact): Long = inner.recordWorldFact(fact).also { flush() }

    override fun recallWorldFact(question: String, now: Long): WorldRecall? =
        inner.recallWorldFact(question, now)

    override fun recallWorldCandidates(
        terms: List<String>,
        vectors: List<List<Float>>,
        now: Long,
        limit: Int,
    ): List<WorldRecall> = inner.recallWorldCandidates(terms, vectors, now, limit)

    override fun recallPreferenceCandidates(
        terms: List<String>,
        vectors: List<List<Float>>,
        limit: Int,
    ): List<PreferenceRecall> = inner.recallPreferenceCandidates(terms, vectors, limit)

    override fun invalidateWorldFact(id: Long, at: Long) {
        inner.invalidateWorldFact(id, at)
        flush()
    }

    override fun recordPreference(fact: PreferenceFact): Long =
        inner.recordPreference(fact).also { flush() }

    override fun preferences(): List<PreferenceFact> = inner.preferences()

    override fun confirmPreference(id: Long, at: Long) {
        inner.confirmPreference(id, at)
        flush()
    }

    override fun forgetPreference(id: Long) {
        inner.forgetPreference(id)
        flush()
    }

    override fun appendTurn(turn: ConversationTurn): Long =
        inner.appendTurn(turn).also { record(turn) }

    /**
     * Written through like everything else, and that is the point of it.
     *
     * This is what a turn calls at the end of every tool round, so the cost is one snapshot per
     * round on a turn that is doing real work - paid deliberately, because the alternative is
     * that a turn interrupted after twenty rounds of searching left nothing behind at all.
     */
    override fun replaceTurn(turn: ConversationTurn) {
        inner.replaceTurn(turn)
        record(turn)
    }

    override fun turnsInSession(sessionId: Long): List<ConversationTurn> = inner.turnsInSession(sessionId)

    override fun searchTurns(query: String, from: Long?, to: Long?, limit: Int): List<ConversationTurn> =
        inner.searchTurns(query, from, to, limit)

    override fun recentTurns(limit: Int): List<ConversationTurn> = inner.recentTurns(limit)

    override fun saveSession(session: Session) {
        inner.saveSession(session)
        flush()
    }

    override fun loadSession(): Session? = inner.loadSession()

    override fun turnCount(): Int = inner.turnCount()

    override fun turnBytes(): Long = inner.turnBytes()

    override fun clearTurns() {
        inner.clearTurns()
        // Both, and in this order: the log is what holds the conversation, and the session that
        // goes with it lives in the snapshot.
        synchronized(this) { compactLog() }
        flush()
    }

    override fun lastTurnAt(): Long? = inner.lastTurnAt()

    /** Everything the memory screen lists. */
    fun worldFacts(): List<WorldFact> = inner.worldFacts()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Characters appended before the log is written out whole.
         *
         * Characters rather than bytes, which for this app's Chinese is roughly a third of the
         * file size - the count is a proxy chosen because it is free, and the number is set
         * against what it actually measures rather than against a round number of megabytes.
         *
         * Amortisation, not tidiness. A researched answer measured 414K characters of appends
         * across its nine checkpoints on a real install, so this compacts about every second
         * such question, and what it rewrites is the log alone - the turns, not the embeddings.
         * That holds the total written to a small multiple of what was appended, whatever shape
         * the conversation takes, while leaving an ordinary short exchange to append and nothing
         * more.
         */
        const val REWRITE_AFTER = 1_000_000L
    }
}

// ---- mapping ------------------------------------------------------------------------------

internal fun WorldFact.toDto() = WorldFactDto(
    id, question, answer, ttl.name, tier.name, sources, embedding, recordedAt, invalidatedAt,
)

internal fun WorldFactDto.toDomain() = WorldFact(
    id = id,
    question = question,
    answer = answer,
    // Unrecognised values fall to the conservative end rather than throwing: a file written by
    // a version that knew a TTL this one does not should re-search, not crash.
    ttl = enumOrNull<WorldTtl>(ttl) ?: WorldTtl.ALWAYS_RESEARCH,
    tier = enumOrNull<Tier>(tier) ?: Tier.LOW,
    sources = sources,
    embedding = embedding,
    recordedAt = recordedAt,
    invalidatedAt = invalidatedAt,
)

internal fun PreferenceFact.toDto() = PreferenceFactDto(
    id, text, kind.name, ttl.name, embedding, recordedAt, confirmedAt,
)

internal fun PreferenceFactDto.toDomain(): PreferenceFact {
    val parsedKind = enumOrNull<PreferenceKind>(kind) ?: PreferenceKind.TRANSIENT
    return PreferenceFact(
        id = id,
        text = text,
        kind = parsedKind,
        ttl = enumOrNull<PreferenceTtl>(ttl) ?: parsedKind.defaultTtl,
        embedding = embedding,
        recordedAt = recordedAt,
        confirmedAt = confirmedAt,
    )
}

internal fun ConversationTurn.toDto() = TurnDto(
    id, sessionId, at, speaker.name, text, shape?.name,
    sources.map { CitedSourceDto(it.url, it.displayName, it.explanation, it.tier.name, it.quote) },
    reasoning,
    images,
    steps,
    rounds.map { round ->
        ToolRoundDto(
            exchanges = round.exchanges.map {
                ToolExchangeDto(it.id, it.name, it.input, it.result, it.isError)
            },
            known = round.known,
            thinking = round.thinking,
        )
    },
    inFlight,
)

internal fun TurnDto.toDomain() = ConversationTurn(
    id = id,
    sessionId = sessionId,
    at = at,
    speaker = enumOrNull<Speaker>(speaker) ?: Speaker.USER,
    text = text,
    shape = shape?.let { enumOrNull<AnswerShape>(it) },
    reasoning = reasoning,
    images = images,
    steps = steps,
    rounds = rounds.map { round ->
        ToolRound(
            exchanges = round.exchanges.map {
                ToolExchange(it.id, it.name, it.input, it.result, it.isError)
            },
            known = round.known,
            thinking = round.thinking,
        )
    },
    inFlight = inFlight,
    sources = sources.map {
        CitedSource(
            url = it.url,
            displayName = it.displayName,
            explanation = it.explanation,
            tier = enumOrNull<Tier>(it.tier) ?: Tier.LOW,
            quote = it.quote,
        )
    },
)

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
    enumValues<E>().firstOrNull { it.name == name }
