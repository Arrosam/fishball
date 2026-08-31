package org.areel.fishball.core.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

@Serializable
data class MemorySnapshot(
    val world: List<WorldFactDto> = emptyList(),
    val preferences: List<PreferenceFactDto> = emptyList(),
    val turns: List<TurnDto> = emptyList(),
    val idSeq: Long = 0L,
    val session: SessionDto? = null,
)

@Serializable
data class SessionDto(val id: Long, val startedAt: Long, val bridge: String? = null)

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
    private val inner: InMemoryStore = InMemoryStore(),
) : MemoryStore {

    init {
        io.read()?.takeIf { it.isNotBlank() }?.let { text ->
            // A corrupt or half-written file must not brick the app. Losing memory is bad;
            // refusing to start is worse, and the log is not the product.
            runCatching { inner.restore(json.decodeFromString(MemorySnapshot.serializer(), text)) }
        }
    }

    private fun flush() {
        runCatching { io.write(json.encodeToString(MemorySnapshot.serializer(), inner.snapshot())) }
    }

    override fun nextId(): Long = inner.nextId()

    override fun recordWorldFact(fact: WorldFact): Long = inner.recordWorldFact(fact).also { flush() }

    override fun recallWorldFact(question: String, now: Long): WorldRecall? =
        inner.recallWorldFact(question, now)

    override fun recallCandidates(
        question: String,
        vector: List<Float>,
        now: Long,
        limit: Int,
    ): List<WorldRecall> = inner.recallCandidates(question, vector, now, limit)

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

    override fun appendTurn(turn: ConversationTurn): Long = inner.appendTurn(turn).also { flush() }

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
        flush()
    }

    override fun lastTurnAt(): Long? = inner.lastTurnAt()

    /** Everything the memory screen lists. */
    fun worldFacts(): List<WorldFact> = inner.worldFacts()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
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
    id, text, kind.name, ttl.name, recordedAt, confirmedAt,
)

internal fun PreferenceFactDto.toDomain(): PreferenceFact {
    val parsedKind = enumOrNull<PreferenceKind>(kind) ?: PreferenceKind.TRANSIENT
    return PreferenceFact(
        id = id,
        text = text,
        kind = parsedKind,
        ttl = enumOrNull<PreferenceTtl>(ttl) ?: parsedKind.defaultTtl,
        recordedAt = recordedAt,
        confirmedAt = confirmedAt,
    )
}

internal fun ConversationTurn.toDto() = TurnDto(
    id, sessionId, at, speaker.name, text, shape?.name,
    sources.map { CitedSourceDto(it.url, it.displayName, it.explanation, it.tier.name, it.quote) },
)

internal fun TurnDto.toDomain() = ConversationTurn(
    id = id,
    sessionId = sessionId,
    at = at,
    speaker = enumOrNull<Speaker>(speaker) ?: Speaker.USER,
    text = text,
    shape = shape?.let { enumOrNull<AnswerShape>(it) },
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
