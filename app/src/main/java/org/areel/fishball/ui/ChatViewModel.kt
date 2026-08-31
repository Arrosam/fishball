package org.areel.fishball.ui

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
)

class ChatViewModel(private val backend: Backend) : ViewModel() {

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

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || busy) return

        messages += ChatMessage(fromUser = true, text = question)
        busy = true
        narration.clear()

        viewModelScope.launch {
            val conversation = backend.conversation
            val reply = if (conversation == null) {
                Reply(UiCopy.SERVICE_UNAVAILABLE)
            } else {
                // The driver blocks on network and writes the memory file; neither belongs on
                // the frame thread. Narration hops back to the main thread to be shown.
                withContext(Dispatchers.IO) {
                    conversation.ask(question) { line ->
                        if (line.isNotBlank()) {
                            viewModelScope.launch { narration += line }
                        }
                    }
                }
            }
            narration.clear()
            messages += reply.toMessage()
            busy = false
        }
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
)

private fun ConversationTurn.toMessage() = ChatMessage(
    fromUser = speaker == Speaker.USER,
    text = text,
    sources = sources.map {
        Source(
            name = it.displayName,
            host = normalizeHost(it.url) ?: UiCopy.UNPARSEABLE_SOURCE,
            quote = it.quote,
        )
    },
    confidence = shape.toConfidence(),
    conflict = shape == AnswerShape.CONFLICT,
)

private fun SourceRef.toUi() = Source(
    name = displayName,
    host = normalizeHost(url) ?: UiCopy.UNPARSEABLE_SOURCE,
    quote = quote,
)

// ---- memory screen rows --------------------------------------------------------------------

fun WorldFact.rowText(): String = answer.ifBlank { question }

fun PreferenceFact.rowText(): String = text
