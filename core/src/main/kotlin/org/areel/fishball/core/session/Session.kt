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
    /** Carried from the previous session: what was being discussed, what was unresolved. */
    val bridge: String? = null,
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
        return if (stale && large) {
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
 * today, and the only decision this feeds is a 256,000-token threshold — a number chosen to be
 * far from any borderline. CJK runs about a token a character; Latin runs about four characters
 * to the token, so each is counted on its own terms rather than averaged into something wrong
 * for both.
 */
fun estimateTokens(text: String): Int {
    var cjk = 0
    var other = 0
    for (c in text) {
        if (c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF) cjk++ else other++
    }
    return cjk + other / 4
}
