package org.areel.fishball.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.agent.Reply
import org.areel.fishball.core.agent.Unfinished
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

/** A leading 「（9月3日 21:53）」, which no answer should have been written with. */
private val STAMPED = Regex("""^（\d{1,2}月\d{1,2}日 \d{2}:\d{2}）""")

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
     * How the turn was worked out: the lines it narrated, and the reasoning behind them.
     *
     * Both are written to the log now. They were held in memory only, on the argument that §9
     * keeps what was said and this is a different and much larger thing - true, but it meant
     * reopening the app left every answer with a blank panel under it, and half a record reads
     * as the app having forgotten what it did.
     */
    val steps: List<String> = emptyList(),
    val thinking: String = "",
    /**
     * Pictures asked with this question, as the names [Attachments] kept them under.
     *
     * Names rather than bitmaps: a thread of thirty messages would otherwise hold thirty
     * decoded photographs in memory for the sake of the two that are on screen.
     */
    val images: List<String> = emptyList(),
)

class ChatViewModel(
    /** Also read by the screen, which needs it for the microphone. */
    val backend: Backend,
    private val compactedNotice: String,
    /** What goes in the thread when somebody stops a turn themselves. */
    private val stoppedNotice: String,
) : ViewModel() {

    val messages = mutableStateListOf<ChatMessage>()

    init {
        /*
         * Redrawn from the log, not held in memory. The thread the user sees and the record
         * §9 keeps are the same thing, so closing the app cannot lose one without losing the
         * other - and reopening it lands them back where they were rather than on a blank
         * screen that implies the app forgot them.
         *
         * With one row left out, when there is one: the half-written answer of a turn the app
         * was killed in the middle of. Drawn, it would say 「那就先不查了」 above a placeholder
         * that is plainly still going. It is about to be picked up instead.
         */
        val unfinished = backend.conversation?.unfinished()
        messages += backend.store.recentTurns()
            .filterNot { it.id == unfinished?.id }
            .map { it.toMessage(stoppedNotice) }
        if (unfinished != null) carryOn(unfinished)
    }

    /** Spec §21 — what the app is doing right now, in plain language. */
    val narration = mutableStateListOf<String>()

    var busy by mutableStateOf(false)
        private set

    /**
     * Whether the conversation is actually in front of somebody.
     *
     * Set from the activity's lifecycle. A notification posted while they are watching the
     * answer arrive is a notification about something they can already see, and the sound has
     * that moment covered.
     */
    var watching: Boolean = true
        set(value) {
            field = value
            // Coming back to the thread answers the notification, so it should not still be
            // sitting in the shade.
            if (value) backend.alert.clear()
        }

    /**
     * The turn in flight, so it can be called off.
     *
     * Held rather than launched and forgotten. A turn is now allowed to run for as long as it
     * needs - which is the right default and also means the only person who can say it has gone
     * on long enough is the one waiting for it, and they need something to say it with.
     */
    private var turn: Job? = null

    /**
     * Which send owns the screen.
     *
     * A stopped turn cleans up after itself - narration cleared, [busy] false, [turn] forgotten -
     * and when the stop came from a *new* send, that cleanup lands underneath the turn that
     * replaced it: the placeholder disappears, the stop button reverts, and [stop] loses its
     * handle on a turn that is still running. So each send takes a number, and only the turn
     * still holding the latest one is allowed to put the screen back to idle.
     *
     * A counter rather than the job itself, because the coroutine body can begin before `launch`
     * has returned the job to assign - `viewModelScope` dispatches on `Main.immediate`, which
     * does not dispatch at all when the caller is already on the main thread.
     */
    private var generation = 0

    /** The model's reasoning for the turn in flight. Cleared when it lands, and kept in the log. */
    var thinking by mutableStateOf("")
        private set

    /** The reply as it streams in, before it becomes a message. */
    var streamed by mutableStateOf("")
        private set

    fun send(text: String, images: List<org.areel.fishball.core.llm.LlmContent.Image> = emptyList()) {
        val question = text.trim()
        if (question.isEmpty()) return
        begin(
            asked = ChatMessage(
                fromUser = true,
                text = question,
                images = images.mapNotNull { it.handle },
            ),
            said = emptyList(),
        ) { conversation, progress -> conversation.ask(question, progress, images) }
    }

    /**
     * A turn the app was killed in the middle of, carried on from where it stopped.
     *
     * Not a re-ask: the question is the one already in the thread, the rounds it got through go
     * back to the model as the calls and results they were, and what the user sees is the
     * placeholder they were looking at when the app went away - the same narration, picked up
     * mid-list. The pictures have to be handed back from here because the log keeps only their
     * names and `:core` cannot read a file.
     */
    private fun carryOn(unfinished: Unfinished) {
        begin(asked = null, said = unfinished.steps) { conversation, progress ->
            // Read here rather than at the call site: this runs on IO, and base64-ing a
            // photograph back out of a file is not something to do on the frame the app is
            // starting up in.
            conversation.resume(
                progress,
                unfinished.images.mapNotNull { backend.attachments.reload(it) },
            )
        }
    }

    /**
     * One turn on the screen, however it was started.
     *
     * [asked] is the bubble to put in the thread first, and null when the question is already
     * there - which it is for a turn being resumed. [said] is what the placeholder starts with,
     * for the same reason. [body] is the one line that differs between asking and resuming; it
     * returns null when there turned out to be nothing to resume, which is a race rather than a
     * failure and is reported as nothing at all.
     */
    private fun begin(
        asked: ChatMessage?,
        said: List<String>,
        body: suspend (Conversation, TurnProgress) -> Reply?,
    ) {
        /*
         * A turn already running is steered, not queued and not refused.
         *
         * [send] used to return on [busy], which is why the composer was dead for the whole of a
         * turn: there was nothing useful for it to do. Now sending while one is in flight stops
         * it where it stands and asks the new question in its place - and the stop is cheap,
         * because everything the turn looked up is already on the record and replays with the
         * next question. That is the whole of what steering is: the model sees how far it got,
         * and then sees what it should have been doing instead.
         */
        val mine = ++generation
        val previous = turn

        // Written straight from the IO thread. Compose snapshot state is safe to write from
        // anywhere - it is recomposition that is confined to the main thread - so hopping per
        // delta would cost a coroutine launch per token for nothing.
        val progress = object : TurnProgress {
            override fun step(text: String) {
                narration += text
            }

            override fun searched(summary: String) {
                // Into the panel with the rest of the working, not into the thread.
                //
                // It was a block of its own, so a long-horizon turn left a column of them
                // standing between the question and the answer - a dozen slabs of 查了…… that
                // outlived the moment they described and pushed the reply off the screen. It is
                // the same kind of thing as a narration step and now sits in the same place.
                narration += summary
            }

            override fun thinking(delta: String) {
                thinking += delta
            }

            override fun answer(delta: String) {
                streamed += delta
            }
        }

        turn = viewModelScope.launch {
            /*
             * The turn being replaced has to finish unwinding first, and nothing may be put on
             * the screen until it has.
             *
             * It is still writing its last round to the log, and this turn's thread is rebuilt
             * from that log - started underneath it, the new question would be asked without
             * the looking the old turn had just done, which is the one thing steering is for.
             * It also says 「那就先不查了」 on its way out, and that belongs above the new
             * question rather than after it.
             */
            previous?.cancelAndJoin()
            if (generation != mine) return@launch

            asked?.let { messages += it }
            busy = true
            narration.clear()
            narration += said
            thinking = ""
            streamed = ""
            // Held for exactly as long as the turn, so the system does not reclaim the app out
            // from under a question somebody asked and then put the phone down over.
            backend.awake.hold()

            try {
                val conversation = backend.conversation
                val reply = if (conversation == null) {
                    Reply(UiCopy.SERVICE_UNAVAILABLE)
                } else {
                    // The driver blocks on network and writes the memory file; neither belongs
                    // on the frame thread. Narration hops back to the main thread to be shown.
                    withContext(Dispatchers.IO) { body(conversation, progress) }
                }
                // Null only from a resume that found nothing left to resume - something else
                // finished or abandoned the turn in between. Nothing happened, so nothing is
                // said about it.
                if (reply != null) {
                    // Also to logcat. The tap-to-expand is for whoever is holding the phone;
                    // this is for whoever is holding a laptop, and it costs one line.
                    reply.detail?.let { Log.w("FishBall", "turn failed: $it") }
                    messages += reply.toMessage()
                        .copy(steps = narration.toList(), thinking = thinking)
                    /*
                     * Only for an answer, and only if they asked to be told.
                     *
                     * `detail` is set exactly when the turn failed, so it is the honest test: a
                     * notification headed 「鱼丸查好了」 that opens onto 「这会儿连不上」 is a
                     * small lie told to somebody who walked away trusting it. They find out when
                     * they come back, which is no worse than never having been promised
                     * anything.
                     *
                     * A stopped turn is not news either - they stopped it - and it leaves
                     * through the cancellation path without reaching here at all.
                     */
                    if (backend.alerting && reply.detail == null) {
                        backend.alert.bubble()
                        if (!watching) backend.alert.answered(reply.text)
                    }
                }
            } catch (stopped: CancellationException) {
                // Said out loud, because the alternative is a question sitting in the thread
                // with nothing under it and no way to tell a stopped turn from a lost one.
                // The user's own message stays: they did ask it, and §9 has already filed it.
                //
                // With the working under it, which is the point of stopping rather than the
                // consolation prize. The driver has already written every finished round to the
                // log, so this is the same bubble the thread redraws on the next launch - and
                // the next thing typed is answered by a model that can see how far this got.
                messages += ChatMessage(
                    fromUser = false,
                    text = stoppedNotice,
                    steps = narration.toList(),
                    thinking = thinking,
                )
                throw stopped
            } finally {
                // In `finally` so a stopped turn cleans up exactly like a finished one. The
                // placeholder is driven by [busy], and a cancelled coroutine that left it true
                // would strand a bubble on screen with nothing behind it.
                //
                // Unless something has already taken its place: a turn stopped by the next send
                // must not hand the screen back to idle on the way out. See [generation].
                if (generation == mine) {
                    narration.clear()
                    thinking = ""
                    streamed = ""
                    busy = false
                    turn = null
                    backend.awake.release()
                }
            }
            // Nothing about memory here any more. The bus files on its own, off the fast model,
            // and a screen should not have to remember to remember.
        }
    }

    /**
     * Enough. Stop.
     *
     * Cancelling the job unwinds the driver wherever it happens to be - Ktor's calls are
     * cancellable, so an HTTP read in flight comes back as a cancellation rather than being
     * waited out. What has already been written to memory stays written: those are things that
     * were true before anybody got impatient.
     */
    fun stop() {
        val running = turn ?: return
        viewModelScope.launch {
            running.cancelAndJoin()
            /*
             * Stopped on purpose, so nothing is going to pick it up again.
             *
             * The driver marks a turn as running on every checkpoint and only clears it when the
             * turn closes, which is what lets the next launch tell a turn the app lost from one
             * that ended - so a turn somebody stopped has to say so, or it would be resumed
             * under them when they next open the app. After the join, because the checkpoint the
             * cancellation writes has to land before this clears it.
             */
            withContext(Dispatchers.IO) { backend.conversation?.abandon() }
        }
    }

    /**
     * The screen is gone, and the turn on its scope went with it.
     *
     * The hold is let go here as well as in the turn's own `finally`, because a cancelled
     * `viewModelScope` may not run that: an ongoing notification for a turn that no longer
     * exists is worse than no notification at all. What the turn was doing is not lost - its
     * row is still marked as running, and the next launch picks it up.
     */
    override fun onCleared() {
        backend.awake.release()
        super.onCleared()
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
        .map {
            MemoryRowData(it.id, it.answer.ifBlank { it.question }, it.ttl.label, personal = false)
        }

    fun personalMemories(): List<MemoryRowData> = backend.store.preferences()
        .sortedByDescending { it.recordedAt }
        .map { MemoryRowData(it.id, it.text, it.ttl.label, personal = true) }

    /**
     * Spec §9 — the delete the memory screen promises, in whichever of the store's two forms
     * the row actually needs.
     *
     * They are not the same operation and must not be made into one. A preference is removed
     * outright: it is a fact about the person, and if they want it gone the record should stop
     * existing. A world fact is *invalidated* — §19's own word — and stays on the books with a
     * date on it, so the next question that would have been answered from the cache goes and
     * looks the thing up again. Deleting it instead would leave a hole the recall pass reads as
     * never having been asked, which is how a fact somebody threw away comes straight back.
     */
    fun forget(row: MemoryRowData) {
        if (row.personal) backend.store.forgetPreference(row.id)
        else backend.store.invalidateWorldFact(row.id, System.currentTimeMillis())
    }
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

/**
 * One logged turn, as a bubble.
 *
 * [stopped] is what a turn that never reached an answer is drawn as. The driver leaves those in
 * the log with their tool rounds and an empty text - that emptiness is the marker - so that the
 * next question can be asked of a model that remembers being interrupted. Drawn blank it would be
 * a bubble with nothing in it under a panel full of working, which reads as a bug rather than as
 * the turn somebody stopped.
 */
private fun ConversationTurn.toMessage(stopped: String) = ChatMessage(
    fromUser = speaker == Speaker.USER,
    // Stripped, because a build that stamped assistant turns on the way to the model taught it
    // to write the stamp into its answers, and those answers are in the log. See
    // `Conversation.unstamped` - this is the same cleaning, for the half the user reads.
    text = STAMPED.replaceFirst(text, "").ifBlank { if (speaker == Speaker.USER) "" else stopped },
    images = images,
    // The working panel, restored. Both halves: the reasoning the model wrote and the lines the
    // app narrated while it ran. Held only in memory before this, so reopening the app left an
    // answer with no account of where it came from.
    thinking = reasoning,
    steps = steps,
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
