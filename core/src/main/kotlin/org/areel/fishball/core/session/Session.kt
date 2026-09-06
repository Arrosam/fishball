package org.areel.fishball.core.session

const val SESSION_IDLE_TIMEOUT_MS = 3_600_000L // 1 hour, spec §8

/**
 * Spec §8 — sessions exist to bound context, and are invisible to the user.
 *
 * The user's mental model is one endless conversation. Session boundaries are an internal
 * device for keeping the prompt bounded and memory tidy; when one is crossed, a short bridge
 * summary rides forward so a back-reference ("that phone we talked about") still resolves
 * three hours later.
 */
data class Session(
    val id: Long,
    val startedAt: Long,
    /**
     * The conversation so far, folded — everything up to [compactedThrough], in one paragraph.
     *
     * Two things arrive here and they are the same thing. A session that rolled over on the
     * clock puts the whole of the previous session in it, so a back-reference still resolves
     * three hours later. A session under context pressure puts its own oldest turns in it and
     * goes on running. See `Conversation.compress`.
     */
    val bridge: String? = null,
    /**
     * The last turn folded into [bridge]. Everything after it is still replayed word for word.
     *
     * Zero means nothing has been folded, which is every session that has not yet grown large
     * enough to need it. The turns themselves are never touched: this marks where the *prompt*
     * stops quoting the log, and §9's record is the log.
     */
    val compactedThrough: Long = 0L,
)

/**
 * Spec §8, as amended by the author: idle alone is not enough.
 *
 * A session is meant to be invisible, and rolling over on the clock alone makes it visible in
 * the worst way — you come back after lunch, ask a follow-up, and it has forgotten what you
 * were talking about. So the conversation runs on until it is *both* stale and large enough to
 * be worth compacting. Either condition on its own leaves it exactly where it was.
 *
 * 128K is where large starts. It is half a context window on the models behind this proxy, so
 * a conversation that has not reached it is a conversation the model can still read in full,
 * and folding it would be throwing away detail nothing was short of.
 */
const val SESSION_COMPACT_TOKENS = 128_000

/**
 * The window a turn actually has to fit inside.
 *
 * Everything below is a fraction of this, so moving to a model with a different window is one
 * number rather than three that have to be kept in step with each other.
 */
const val CONTEXT_WINDOW_TOKENS = 128_000

/**
 * Where the conversation starts being folded, and how much of the recent end is never folded.
 *
 * These are DeepSeek Harness's `thresholdRatio` and `retainRatio`, at their shipped defaults of
 * 0.8 and 0.16, and the mechanism they belong to is theirs as well - see `Conversation.compress`.
 * The point of the pair is that compaction is not a fold: everything since the seam is still
 * sent word for word, tool calls, tool results and reasoning included, and only the oldest span
 * is replaced by a summary of itself.
 *
 * Leaving 20% of the window clear above the trigger is what makes that safe. The turn that
 * crosses the line still has to run - several rounds of search results and a page or two - and a
 * threshold at the window itself would compact only after the request it was meant to protect
 * had already been rejected.
 */
val COMPACT_AT_TOKENS = (CONTEXT_WINDOW_TOKENS * 0.8).toInt()
val COMPACT_RETAIN_TOKENS = (CONTEXT_WINDOW_TOKENS * 0.16).toInt()

/**
 * And where a session has to be given up on whether or not anybody has stopped talking.
 *
 * A backstop now, and it was the main event. Every turn goes back with the tool calls that
 * produced it - each search's results, each page that was opened, and the reasoning behind every
 * round of it - so a conversation somebody is still having can outgrow the window in an
 * afternoon, and this line existed to fold the whole thing the moment it did.
 *
 * `Conversation.compress` reaches that growth first and deals with it properly, at 80% of the
 * window and without discarding the recent end. What is measured against these two lines is the
 * compacted context, so a session only arrives here if a summary plus the retained tail is
 * itself enormous - which is a session worth starting again rather than compacting again.
 */
const val SESSION_CEILING_TOKENS = 160_000

sealed class SessionDecision {
    data class Continue(val session: Session) : SessionDecision()

    /**
     * Idle timeout crossed. The caller must produce a bridge summary from [previousTurns]
     * before the new session can answer anything referring backwards.
     */
    data class RollOver(val previous: Session, val newSessionId: Long) : SessionDecision()

    /** No prior session at all — first ever turn, nothing to bridge. */
    data class Start(val newSessionId: Long) : SessionDecision()
}

class SessionManager(
    private val idleTimeoutMs: Long = SESSION_IDLE_TIMEOUT_MS,
    private val compactTokens: Int = SESSION_COMPACT_TOKENS,
    private val ceilingTokens: Int = SESSION_CEILING_TOKENS,
) {

    fun decide(
        current: Session?,
        lastTurnAt: Long?,
        now: Long,
        /** Rough size of what would be carried forward. See [estimateTokens]. */
        contextTokens: Int,
        nextId: () -> Long,
    ): SessionDecision {
        if (current == null) return SessionDecision.Start(nextId())
        if (lastTurnAt == null) return SessionDecision.Continue(current)
        val stale = now - lastTurnAt >= idleTimeoutMs
        val large = contextTokens >= compactTokens
        val full = contextTokens >= ceilingTokens
        return if ((stale && large) || full) {
            SessionDecision.RollOver(current, nextId())
        } else {
            SessionDecision.Continue(current)
        }
    }

    /**
     * Whether a rollover needs a bridge at all. A session with nothing in it has nothing to
     * carry, and asking the model to summarise silence wastes a call and produces noise.
     */
    fun needsBridge(previousTurnCount: Int): Boolean = previousTurnCount >= 2
}

/**
 * Roughly how many tokens a piece of text will cost.
 *
 * Deliberately crude. A real tokeniser would have to match whichever model is behind the proxy
 * today, and the only decision this feeds is [SESSION_COMPACT_TOKENS] — a number chosen to be
 * far from any borderline. CJK runs about a token a character; Latin runs about four characters
 * to the token, so each is counted on its own terms rather than averaged into something wrong
 * for both.
 *
 * What is *counted* matters more than the arithmetic: it has to be everything a turn puts back
 * on the wire, reasoning included. See `Conversation.sentSize`.
 */
fun estimateTokens(text: String): Int {
    var cjk = 0
    var other = 0
    for (c in text) {
        if (c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF) cjk++ else other++
    }
    return cjk + other / 4
}
