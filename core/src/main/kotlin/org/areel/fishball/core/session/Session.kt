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

class SessionManager(private val idleTimeoutMs: Long = SESSION_IDLE_TIMEOUT_MS) {

    fun decide(current: Session?, lastTurnAt: Long?, now: Long, nextId: () -> Long): SessionDecision {
        if (current == null) return SessionDecision.Start(nextId())
        if (lastTurnAt == null) return SessionDecision.Continue(current)
        return if (now - lastTurnAt >= idleTimeoutMs) {
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
