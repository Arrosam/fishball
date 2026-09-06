package org.areel.fishball.core.memory

import kotlinx.serialization.json.JsonObject
import org.areel.fishball.core.copy.Vocabulary
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.answer.AnswerShape
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
    /**
     * The question, embedded. Empty when it could not be computed, in which case this fact is
     * only findable by word overlap — which is worth keeping as a fallback and useless as the
     * primary test: "布洛芬伤胃吗" and "吃布洛芬会不会胃疼" share almost no characters.
     */
    val embedding: List<Float> = emptyList(),
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
    /**
     * The text, embedded. What the user has told us about themselves is retrieved the same way
     * cached answers are - by meaning - because "他对什么过敏" has to find 对青霉素过敏 without
     * sharing a word with it.
     */
    val embedding: List<Float> = emptyList(),
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

/**
 * A source as it was cited at the time.
 *
 * Kept in the log rather than re-derived on read. Tiers are resolved against a registry that
 * changes, and a search result's snippet is gone the moment the search is over - so an answer
 * re-tiered six months later would be shown with a confidence it was never given. What the log
 * records is what the user was actually told.
 */
data class CitedSource(
    val url: String,
    val displayName: String,
    val explanation: String? = null,
    val tier: Tier,
    /** Spec §25 — the verified passage, if one was shown. */
    val quote: String? = null,
)

/** Spec §9 — every turn is kept and searchable, so "what did I ask you yesterday" is answerable. */
data class ConversationTurn(
    val id: Long,
    val sessionId: Long,
    val at: Long,
    val speaker: Speaker,
    val text: String,
    /**
     * How the answer was allowed to speak. Null on the user's own turns, and on assistant turns
     * that were not answering a factual question - a comfort or a clarification has no shape.
     */
    val shape: AnswerShape? = null,
    val sources: List<CitedSource> = emptyList(),
    /**
     * The reasoning that produced this answer, on assistant turns that had any.
     *
     * Kept because the models this app runs on want it back. A reply written after a thinking
     * block is a reply that *continues* one, and handing the next turn only the conclusion asks
     * the model to carry on from a sentence it has forgotten arriving at - which is also why
     * DeepSeek's own guidance is to send the thinking back rather than drop it.
     *
     * Empty on user turns, and on assistant turns the model answered without thinking. Never
     * shown to anybody: §9 keeps what was said, and this is how it was worked out.
     */
    val reasoning: String = "",
    /**
     * The pictures that were part of this turn, as whatever the app calls them.
     *
     * Opaque strings. §9 keeps what was said, and a question asked by holding up a photograph
     * is not fully kept by keeping the sentence beside it - "这个能吃吗" recorded on its own is
     * a record of nothing.
     */
    val images: List<String> = emptyList(),
    /**
     * What the app said it was doing while this answer was worked out.
     *
     * Kept for the same reason [reasoning] is: the panel under an answer showed how it was
     * arrived at, and reopening the app used to empty it. Half a record is worse than none -
     * somebody who saw 「查了：布洛芬 孕妇」 yesterday and finds a blank panel today reasonably
     * concludes the app forgot what it did.
     */
    val steps: List<String> = emptyList(),
    /**
     * What the model looked up on the way to this answer, round by round.
     *
     * The tool calls it made and what each one returned, in the order they happened. Kept so
     * the next question can be answered by a model that remembers what it read, not only what
     * it concluded: a follow-up like 「那篇药监局的页面还说了什么」 has nothing to resolve
     * against when the previous turn is replayed as a question and an answer with the looking
     * cut out from between them. Empty on user turns, and on answers that looked nothing up.
     */
    val rounds: List<ToolRound> = emptyList(),
    /**
     * This answer is still being worked on, as far as anything that wrote it down knows.
     *
     * Set on every checkpoint and cleared when the turn closes, so the only way it survives into
     * the next launch is for the process to have died with the turn still running - nothing that
     * ends a turn in an orderly way, an answer, a failure or the stop button, leaves it set. That
     * makes it exactly the flag for "pick this up again", and distinguishes a turn the app lost
     * from one the person stopped, which look identical from the log otherwise: both are an
     * answer with no text and some rounds behind it.
     */
    val inFlight: Boolean = false,
    /**
     * Said into a turn that was already running, rather than as a question of its own.
     *
     * It is a real row like any other so that §9 files it, `read_log` finds it and the thread
     * redraws it - all of which it lost by living only inside [ToolRound.said]. What the mark
     * changes is the replay: the model is shown this text at the seam it actually arrived at,
     * from the round that carries it, so replaying the row as well would hand over the same
     * sentence twice. See `Conversation.replay`.
     */
    val steered: Boolean = false,
)

/** One round of tool calls in a turn: what was asked for together, and what came back together. */
data class ToolRound(
    val exchanges: List<ToolExchange>,
    /**
     * What the model worked out before it asked for these, in its own words.
     *
     * Kept for the same reason [ConversationTurn.reasoning] is, and it is not the same thing:
     * that one is the thinking behind the answer, and this is the thinking behind the looking.
     * A turn that searched six times thought six times, and until this was recorded five of
     * those were dropped the moment the turn ended - the next question got the calls and the
     * results with the reason for making them cut out from between them, which is the half that
     * says why the fourth search was worded the way it was.
     *
     * Empty on rounds the model asked for a tool without thinking first, and on every round
     * logged before this was kept.
     */
    val thinking: String = "",
    /**
     * What memory offered on the back of this round's results, when it landed here.
     *
     * The lines as the model was shown them. Memory is looked up beside the turn and folded in
     * at the first seam between rounds, so it belongs to the round it arrived in - and a
     * follow-up replayed without it is a follow-up whose model has forgotten it was ever told
     * about the allergy.
     */
    val known: List<String> = emptyList(),
    /**
     * What the person said while this round was running.
     *
     * A message typed mid-turn is not a new question - it is a correction to the one already
     * being worked on - so it is delivered into the loop at the seam after the round it arrived
     * during, and it belongs to that round for the same reason [known] does. Replayed there, a
     * later turn reads the conversation in the order it actually happened: the searching, the
     * interruption, and what the searching did about it.
     */
    val said: List<String> = emptyList(),
)

/** One tool call and its result, as they went over the wire. */
data class ToolExchange(
    /** The provider's id for the call. Kept so a replayed result still points at its call. */
    val id: String,
    val name: String,
    /** The arguments as the model wrote them. The log has no opinion about their shape. */
    val input: JsonObject,
    val result: String,
    val isError: Boolean = false,
)

/** A remembered fact about the user, and how well it matched what was being looked for. */
data class PreferenceRecall(val fact: PreferenceFact, val similarity: Double)

/** A cached answer plus why it is or isn't usable, so the caller never has to re-derive it. */
data class WorldRecall(
    val fact: WorldFact,
    val similarity: Double,
    val fresh: Boolean,
) {
    val servableWithoutSearch: Boolean get() = fresh
}
