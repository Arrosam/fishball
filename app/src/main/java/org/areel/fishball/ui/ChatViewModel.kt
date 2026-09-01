package org.areel.fishball.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.areel.fishball.core.agent.Reply
import org.areel.fishball.core.agent.TurnProgress
import org.areel.fishball.core.agent.SourceRef
import org.areel.fishball.core.answer.AnswerShape
import org.areel.fishball.core.copy.UiCopy
import org.areel.fishball.core.memory.ConversationTurn
import org.areel.fishball.core.memory.PreferenceFact
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.memory.WorldFact
import org.areel.fishball.core.trust.normalizeHost
import org.areel.fishball.data.Backend

/** One turn in the thread, as the UI draws it. */
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val sources: List<Source> = emptyList(),
    /**
     * What the meter reads. Null draws nothing at all — absence is rendered as absence, since
     * an empty bar would claim an answer exists that we have no confidence in.
     */
    val confidence: Confidence? = null,
    /** CONFLICT is not a point on the scale, so it renders the fault line instead of cells. */
    val conflict: Boolean = false,
    /** Why this turn failed, when it did. Not persisted: an error is not part of the record. */
    val detail: String? = null,
    /**
     * How the turn was worked out. Held for as long as the app is open and not written to the
     * log — §9 keeps what was said, and this is how it was arrived at, which is a different
     * thing and a much larger one.
     */
    val steps: List<String> = emptyList(),
    val thinking: String = "",
)

class ChatViewModel(
    /** Also read by the screen, which needs it for the microphone. */
    val backend: Backend,
    private val compactedNotice: String,
) : ViewModel() {

    val messages = mutableStateListOf<ChatMessage>()

    init {
        // Redrawn from the log, not held in memory. The thread the user sees and the record
        // §9 keeps are the same thing, so closing the app cannot lose one without losing the
        // other - and reopening it lands them back where they were rather than on a blank
        // screen that implies the app forgot them.
        messages += backend.store.recentTurns().map { it.toMessage() }
    }

    /** Spec §21 — what the app is doing right now, in plain language. */
    val narration = mutableStateListOf<String>()

    var busy by mutableStateOf(false)
        private set

    /** The model's reasoning for the turn in flight. Cleared when it lands; never persisted. */
    var thinking by mutableStateOf("")
        private set

    /** The reply as it streams in, before it becomes a message. */
    var streamed by mutableStateOf("")
        private set

    fun send(text: String, images: List<org.areel.fishball.core.llm.LlmContent.Image> = emptyList()) {
        val question = text.trim()
        if (question.isEmpty() || busy) return

        messages += ChatMessage(fromUser = true, text = question)
        busy = true
        narration.clear()
        thinking = ""
        streamed = ""

        // Written straight from the IO thread. Compose snapshot state is safe to write from
        // anywhere - it is recomposition that is confined to the main thread - so hopping per
        // delta would cost a coroutine launch per token for nothing.
        val progress = object : TurnProgress {
            override fun step(text: String) {
                narration += text
            }

            override fun thinking(delta: String) {
                thinking += delta
            }

            override fun answer(delta: String) {
                streamed += delta
            }
        }

        viewModelScope.launch {
            val conversation = backend.conversation
            val reply = if (conversation == null) {
                Reply(UiCopy.SERVICE_UNAVAILABLE)
            } else {
                // The driver blocks on network and writes the memory file; neither belongs on
                // the frame thread. Narration hops back to the main thread to be shown.
                withContext(Dispatchers.IO) { conversation.ask(question, progress, images) }
            }
            val workedOut = narration.toList()
            val reasoning = thinking
            narration.clear()
            thinking = ""
            streamed = ""
            // Also to logcat. The tap-to-expand is for whoever is holding the phone; this is
            // for whoever is holding a laptop, and it costs one line.
            reply.detail?.let { Log.w("FishBall", "turn failed: $it") }
            messages += reply.toMessage().copy(steps = workedOut, thinking = reasoning)
            busy = false
            // Nothing about memory here any more. The bus files on its own, off the fast model,
            // and a screen should not have to remember to remember.
        }
    }

    /**
     * The session was folded into a summary — because the model changed, or because §8's
     * rollover fired. The thread stays on screen; what changed is what the model is holding,
     * and a line saying so is better than the next answer quietly not remembering.
     */
    fun noteCompacted() {
        messages += ChatMessage(fromUser = false, text = compactedNotice)
    }

    /** Spec §9 — how much is being kept, for someone deciding whether to keep it. */
    fun historySize(): Pair<Int, Long> = backend.historyTurns() to backend.historyBytes()

    fun clearHistory() {
        backend.clearHistory()
        messages.clear()
    }

    /** Read at the moment the memory screen opens, so it always shows what is actually stored. */
    fun worldMemories(): List<MemoryRowData> = backend.store.worldFacts()
        .filter { it.invalidatedAt == null }
        .sortedByDescending { it.recordedAt }
        .map { MemoryRowData(it.answer.ifBlank { it.question }, it.ttl.label) }

    fun personalMemories(): List<MemoryRowData> = backend.store.preferences()
        .sortedByDescending { it.recordedAt }
        .map { MemoryRowData(it.text, it.ttl.label) }
}

/**
 * Spec §6 — the meter reports *sureness*, and the prose reports direction. A confident "no"
 * is a full bar, which is why REFUTED lands beside CONFIDENT rather than at the bottom.
 *
 * CONFLICT deliberately has no reading: the fault line replaces the cells, because averaging
 * two authorities that disagree into "medium confidence" would hide exactly the thing R7 exists
 * to disclose.
 */
private fun AnswerShape?.toConfidence(): Confidence? = when (this) {
    AnswerShape.CONFIDENT, AnswerShape.REFUTED -> Confidence.FULL
    AnswerShape.ATTRIBUTED -> Confidence.HIGH
    AnswerShape.PERSONAL_PATTERN -> Confidence.MEDIUM
    AnswerShape.WEAK_LEAD -> Confidence.LOW
    AnswerShape.CONFLICT, AnswerShape.NOTHING_FOUND, AnswerShape.SEARCH_UNAVAILABLE, null -> null
}

private fun Reply.toMessage() = ChatMessage(
    fromUser = false,
    text = text,
    sources = sources.map { it.toUi() },
    confidence = shape.toConfidence(),
    conflict = conflict,
    detail = detail,
)

private fun ConversationTurn.toMessage() = ChatMessage(
    fromUser = speaker == Speaker.USER,
    text = text,
    sources = sources.map {
        Source(
            name = it.displayName,
            host = normalizeHost(it.url) ?: UiCopy.UNPARSEABLE_SOURCE,
            url = it.url,
            quote = it.quote,
        )
    },
    confidence = shape.toConfidence(),
    conflict = shape == AnswerShape.CONFLICT,
)

private fun SourceRef.toUi() = Source(
    name = displayName,
    host = normalizeHost(url) ?: UiCopy.UNPARSEABLE_SOURCE,
    url = url,
    quote = quote,
)

// ---- memory screen rows --------------------------------------------------------------------

fun WorldFact.rowText(): String = answer.ifBlank { question }

fun PreferenceFact.rowText(): String = text
