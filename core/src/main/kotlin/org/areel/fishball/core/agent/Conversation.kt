package org.areel.fishball.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.areel.fishball.core.answer.AnswerShape
import org.areel.fishball.core.catching
import org.areel.fishball.core.copy.AgentPrompt
import org.areel.fishball.core.copy.UiCopy
import org.areel.fishball.core.llm.LlmClient
import org.areel.fishball.core.llm.Effort
import org.areel.fishball.core.llm.LlmContent
import org.areel.fishball.core.llm.LlmDelta
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.llm.Retrieval
import org.areel.fishball.core.memory.CitedSource
import org.areel.fishball.core.memory.ConversationTurn
import org.areel.fishball.core.memory.MemoryStore
import org.areel.fishball.core.memory.PreferenceRecall
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.memory.ToolExchange
import org.areel.fishball.core.memory.ToolRound
import org.areel.fishball.core.memory.WorldRecall
import org.areel.fishball.core.quote.QuoteRequest
import org.areel.fishball.core.quote.QuoteResult
import org.areel.fishball.core.quote.QuoteVerifier
import org.areel.fishball.core.quote.SourceText
import org.areel.fishball.core.search.HttpPageReader
import org.areel.fishball.core.search.PageGateway
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.session.SESSION_IDLE_TIMEOUT_MS
import org.areel.fishball.core.session.COMPACT_AT_TOKENS
import org.areel.fishball.core.session.COMPACT_RETAIN_TOKENS
import org.areel.fishball.core.session.SESSION_COMPACT_TOKENS
import org.areel.fishball.core.session.SessionManager
import org.areel.fishball.core.session.estimateTokens
import org.areel.fishball.core.trust.ClaimContext
import org.areel.fishball.core.trust.Evidence
import org.areel.fishball.core.trust.SearchHit
import org.areel.fishball.core.trust.SourceRegistry
import org.areel.fishball.core.trust.Tier
import org.areel.fishball.core.trust.Topic
import org.areel.fishball.core.trust.TrustResolver

/**
 * The driver. `TurnEngine` decides, this one does.
 *
 * Everything that touches the world happens here — the model, the search instance, the store —
 * and every decision is handed back to the engine, which stays pure. Read the `when (step)`
 * block in [run] and you are reading spec §7–§25 in order of execution.
 *
 * Not thread-safe, and not meant to be: one conversation, one caller, one turn at a time.
 */
class Conversation(
    private val llm: LlmClient,
    /**
     * Meaning-based recall. Null falls back to word overlap, which finds a cached answer only
     * when the question is asked in nearly the same words - useful as a floor, useless as the
     * only test.
     */
    private val retrieval: Retrieval? = null,
    private val search: SearchGateway,
    /**
     * Opening a result and reading it, as opposed to reading what a search engine said about
     * it. Defaults to a plain HTTP reader because there is nothing to configure about fetching
     * a page; a test hands in one that answers from a string.
     */
    private val pages: PageGateway = HttpPageReader(),
    private val registry: SourceRegistry,
    private val store: MemoryStore,
    /**
     * Whether this conversation is running on the professional tier.
     *
     * The only thing it gates is the factorisation in front of memory: on the fast tier the
     * question is embedded as it stands. Rebuilt with the conversation on a model switch, so it
     * is a value rather than a lambda.
     */
    private val expert: Boolean = false,
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * Memory, off to one side. Defaults to running on this conversation's own client so a test
     * can pass one thing; `:app` gives it a client pinned to the fast model, because filing
     * should not cost what answering costs.
     */
    // Named, not positional. MemoryBus grew a parameter in front of this one and the call still
    // compiled, binding the clock to it - which a default argument is exactly able to hide.
    val memory: MemoryBus = MemoryBus(llm, retrieval, store, now = now),
) {

    private val resolver = TrustResolver(registry)
    private val verifier = QuoteVerifier()
    private val sessions = SessionManager()

    private var session: Session? = null

    /**
     * A question the app asked and is waiting on. §15's fork and §16's clarification both put
     * the turn on hold, and the *next* thing the user types is an answer to that, not a new
     * question — so it has to be routed differently.
     */

    /**
     * Why the last model call failed, for the turn that is about to report it.
     *
     * A field rather than a return type because the failure can happen three call-frames down
     * inside classification, where the only thing the caller learns is that it got no context
     * back. The alternative was threading a Result through every step of the machine to carry
     * a string that is only ever read at the end of a turn that already went wrong.
     */
    private var lastFailure: String? = null

    /**
     * Things said while a turn was running, waiting for the next seam to be handed over at.
     *
     * Guarded by its own lock rather than the store's: it is written from whichever thread the
     * screen is on and drained from the one the loop is on, and those are never the same.
     */
    private val interjected = mutableListOf<String>()

    /** Whether [converse] is in its loop and will therefore reach another seam. */
    private var looping = false

    /**
     * The row a turn is writing to right now, or null between turns.
     *
     * [abandon] exists to take the resume offer off whatever a newer event has superseded, and
     * it used to clear every marked row in the session - including one a turn was still writing
     * to. The stop button races a send through it: `stop()` joins the cancelled turn and then
     * abandons, while the send that caused the stop has already opened and checkpointed a row of
     * its own, so the turn that is actually running comes out unmarked and is not offered back
     * if the app dies. Naming the live row is the cheapest way to make abandon mean what it
     * says: everything left over, and nothing that is still happening.
     */
    private var writing: Long? = null

    /** The pictures on this turn. Replaced by the next [ask], so they never carry over. */
    private var attached: List<LlmContent.Image> = emptyList()

    /**
     * This turn's thread with the model, from the routing question to the written answer.
     *
     * One exchange, not two calls. Routing and writing used to be strangers: the classifier was
     * asked what kind of question this was, its answer was thrown away except for six enum
     * values, and then a second call started from nothing and wrote the reply. Everything the
     * model worked out on the way to `factual` - what the pronoun pointed at, which of two
     * readings it had settled on, what it had already noticed was missing - was discarded, and
     * the writing turn re-derived whatever of it it could.
     *
     * Now the classification is a tool call in this list, the brief comes back as that call's
     * result, and the answer is written in the same conversation. These models think out loud
     * before every block and the thinking rides along in the assistant turn, so the reasoning
     * behind the routing is in front of the model while it writes - which is the whole point.
     *
     * That last part is only sometimes true, and the gap is measured rather than guessed at: the
     * thinking block goes out on the wire intact and this proxy shows it to the model 7 times in
     * 20 - see `ThinkingRoundTripTest`. So some rounds reason from the tool results alone.
     *
     * Closing that from in here was tried - `HydrogenClient` rewrote the block into a labelled
     * text block - and withdrawn, because the model reads its own replayed turns for the house
     * format and started answering in it: label, reasoning, `</think>`, then the answer, all in
     * one bubble. Worth knowing before anyone tries it a second time. The remaining fix is on the
     * proxy, which drops these on some backends and not others.
     *
     * A field rather than a parameter because a turn is one call to [ask] at a time and half a
     * dozen functions sit between the two ends of it. It is rebuilt from the log at the top of
     * every turn, so nothing survives a turn that should not.
     */
    private var thread = mutableListOf<LlmMessage>()

    /**
     * Everything a turn narrated, kept so it can be written down with the answer.
     *
     * A wrapper rather than a field updated at each call site: `step` and `searched` are emitted
     * from four places, and a list that has to be appended to beside each of them is a list that
     * will eventually miss one.
     */
    private class Recording(
        private val inner: TurnProgress,
        /** What the turn had already narrated, when it is one being picked back up. */
        said: List<String> = emptyList(),
    ) : TurnProgress {
        val lines = said.toMutableList()

        override fun step(text: String) {
            lines += text
            inner.step(text)
        }

        override fun searched(summary: String) {
            lines += summary
            inner.searched(summary)
        }

        override fun thinking(delta: String) = inner.thinking(delta)

        override fun answer(delta: String) = inner.answer(delta)
    }

    /**
     * The answer's row in the log, opened when the question is asked rather than when there is
     * an answer to put in it.
     *
     * It used to be written once, at the end. Everything a turn had looked up lived in a local
     * list until then, so a turn that was stopped - and a turn whose app was killed under it -
     * left the question in the log with nothing after it: twenty rounds of searching, reading and
     * quoting, gone, and the next question asked of a model that had never done any of it. From
     * the outside that reads as the app having thrown the work away, because it had.
     *
     * So the row is claimed up front and rewritten at the end of every round. What that buys is
     * not tidiness in the log; it is the ability to interrupt. A stopped turn now replays as what
     * it was - the calls, what came back, and a line saying it was cut off - so the next thing
     * the user types is a course correction rather than a fresh start. See [replay].
     *
     * Blank [ConversationTurn.text] is the marker for a turn that never reached an answer, and it
     * is read in two places: [recalled] puts [AgentPrompt.INTERRUPTED] in its mouth for the model,
     * and the screen draws it as the notice it showed at the time.
     */
    private inner class Answering(
        private val id: Long,
        private val sessionId: Long,
        private val startedAt: Long,
        private val recording: Recording,
        /** Rounds already on the record, when this row is one being picked back up. */
        done: List<ToolRound> = emptyList(),
    ) {

        /** Every tool round as it went over the wire. See [ConversationTurn.rounds]. */
        val rounds = done.toMutableList()

        init {
            writing = id
        }

        /** This row is nobody's live row any more, however the turn ended. See [writing]. */
        fun released() {
            if (writing == id) writing = null
        }

        /** What has happened so far, on the record before the next round is asked for. */
        fun checkpoint() = store.replaceTurn(
            ConversationTurn(
                id = id,
                sessionId = sessionId,
                at = startedAt,
                speaker = Speaker.ASSISTANT,
                text = "",
                steps = recording.lines.toList(),
                rounds = rounds.toList(),
                // Still going, as far as this row knows. Only [close] clears it, so a row that
                // still says this on the next launch is a turn the process died in the middle
                // of. See [unfinished].
                inFlight = true,
            ),
        )

        /** The turn, finished. [at] is when the answer landed, not when the question was asked. */
        fun close(at: Long, reply: Reply) = store.replaceTurn(
            ConversationTurn(
                id = id,
                sessionId = sessionId,
                at = at,
                speaker = Speaker.ASSISTANT,
                text = reply.text,
                shape = reply.shape,
                reasoning = reply.thinking,
                steps = reply.steps,
                rounds = reply.rounds,
                sources = reply.sources.map {
                    CitedSource(it.url, it.displayName, it.explanation, it.tier, it.quote)
                },
            ),
        )
    }

    suspend fun ask(
        userText: String,
        progress: TurnProgress = TurnProgress.Silent,
        /**
         * Pictures the user attached to this question.
         *
         * On the question itself, which is now the only user turn there is. They used to be
         * kept off the classifier, which stopped routing and started describing the moment it
         * was handed one - live, it answered a six-value enum with 文字识别. There is no
         * classifier left to keep them from.
         */
        images: List<LlmContent.Image> = emptyList(),
    ): Reply {
        // The bus holds its filing while the turn runs and picks it up the moment the turn
        // ends, however the turn ends. See [MemoryBus.turnStarted].
        memory.turnStarted()
        try {
            return turn(userText, progress, images)
        } finally {
            memory.turnEnded()
        }
    }

    /**
     * A turn the app was killed in the middle of, if there is one worth picking up.
     *
     * The mark is [ConversationTurn.inFlight], which only survives a process that died: an
     * answer, a failure and the stop button all clear it on the way out. What the caller gets
     * back is what it needs to put the turn on screen again and hand the pictures back - see
     * [resume], which re-derives the rows itself so the two cannot get out of step.
     *
     * Bounded by the same hour that bounds a session. Past it the session is stale and about to
     * roll anyway, and firing a model request on launch for something asked yesterday is a bill
     * nobody is waiting on the answer to.
     */
    fun unfinished(): Unfinished? {
        val (answer, asked) = resumable() ?: return null
        return Unfinished(
            id = answer.id,
            question = asked.text,
            images = asked.images,
            steps = answer.steps,
            rounds = answer.rounds.size,
        )
    }

    /**
     * The half-finished answer and the question it belongs to, or null if there is no such pair.
     *
     * One reader, because there were two and they drifted: [resume] found the row with its own
     * copy of this and left the hour bound out of it, so the guard that stops a model request
     * firing on launch for yesterday's question existed on only one of the two ways in. What
     * counts as resumable is one decision and belongs in one place.
     */
    private fun resumable(): Pair<ConversationTurn, ConversationTurn>? {
        val open = session ?: store.loadSession() ?: return null
        val turns = store.turnsInSession(open.id)
        val answer = turns.lastOrNull()?.takeIf { it.speaker == Speaker.ASSISTANT && it.inFlight }
            ?: return null
        // Past the hour a session goes stale in, nobody is waiting on this answer and it is not
        // worth a model call on launch to produce.
        if (now() - answer.at >= SESSION_IDLE_TIMEOUT_MS) return null
        val asked = turns.getOrNull(turns.size - 2)?.takeIf { it.speaker == Speaker.USER }
            ?: return null
        session = open
        return answer to asked
    }

    /**
     * Pick that turn up and carry on with it.
     *
     * Not a re-ask. The question is the one already in the log, its row is not written again,
     * and the rounds it got through before the process died go back to the model as the calls
     * and results they were - so it resumes with what it found rather than starting the search
     * over. Returns null when there is nothing to resume, which is a race rather than an error:
     * something else finished or abandoned the turn between [unfinished] and here.
     *
     * [images] are the pictures the question was asked with, handed back by the caller because
     * `:core` has no filesystem and the log keeps only their names. A resume without them is a
     * resume of 「这个能吃吗」 with nothing to look at, so a caller that cannot reload them
     * should not call this.
     */
    suspend fun resume(
        progress: TurnProgress = TurnProgress.Silent,
        images: List<LlmContent.Image> = emptyList(),
    ): Reply? {
        val (answer, asked) = resumable() ?: return null
        /*
         * A picture question with no picture is not resumable, it is a different question.
         *
         * The log keeps the names and the caller turns them back into bytes; when the files have
         * gone - cleared with the history, or reclaimed - what comes back is short, and carrying
         * on regardless would ask 「这个能吃吗」 with nothing to look at and get a confident
         * answer about nothing. Abandoned rather than left marked, so it is not offered again on
         * every launch until the hour runs out.
         */
        if (images.size < asked.images.size) {
            abandon()
            return null
        }
        memory.turnStarted()
        try {
            return turn(asked.text, progress, images, resuming = answer)
        } finally {
            memory.turnEnded()
        }
    }

    /**
     * Whatever is still marked as running, marked as not.
     *
     * Called when the person stops a turn themselves, and at the top of every new one. Both are
     * the same statement: nothing left over is going to be picked up, because something newer
     * has happened. The row keeps its text, its steps and its rounds - all this takes away is
     * the offer to carry on with it.
     */
    fun abandon() {
        val open = session ?: store.loadSession() ?: return
        store.turnsInSession(open.id)
            // Everything left over, and nothing that is still happening. See [writing].
            .filter { it.inFlight && it.id != writing }
            .forEach { store.replaceTurn(it.copy(inFlight = false)) }
    }

    /**
     * Something said while the turn is still working, delivered into it rather than after it.
     *
     * This is what a mid-turn message should do and for a long time did not. Sending one used to
     * stop the loop and start another, and the model read the abandoned half as a task that was
     * over: it began again from nothing, having thrown away six searches that were still sitting
     * in its own context. What the person meant was 「not that, this」, and what they got was a
     * different conversation.
     *
     * So the message joins the turn. It lands at the next seam between rounds - the same place
     * memory's block lands, and for the same reason: a user turn between an assistant's tool_use
     * and its tool_result is a thread the provider may reject. The loop carries on with
     * everything it has already found and the correction in front of it. See
     * [AgentPrompt.steered] for how it is framed, which is most of the work.
     *
     * False when there is no loop to join, which is the caller's signal to ask it as a question
     * instead. There is a race under that - the turn can finish between this returning true and
     * the seam being reached - and [undelivered] is how the caller closes it.
     */
    fun steer(said: String): Boolean {
        val text = said.trim()
        if (text.isEmpty()) return false
        synchronized(interjected) {
            if (!looping) return false
            interjected += text
            return true
        }
    }

    /**
     * Anything [steer] accepted that never reached the model, taken back.
     *
     * The turn can reach its answer before the next seam, and a message that was accepted into a
     * loop that then ended has to go somewhere - it was said, and the person watched it appear
     * in the thread. Drained, so the caller can ask it as an ordinary question and it cannot be
     * delivered twice.
     */
    fun undelivered(): List<String> = synchronized(interjected) {
        val left = interjected.toList()
        interjected.clear()
        left
    }

    /** One turn, start to finish. [ask] wraps it to keep the bus out of its way. */
    private suspend fun turn(
        userText: String,
        progress: TurnProgress,
        images: List<LlmContent.Image>,
        /**
         * The row this turn is continuing, when it is one being picked back up.
         *
         * Null for an ordinary turn, which writes its own question down and opens its own row.
         * A resume does neither: both are already in the log, and writing them again would put
         * the question in the thread twice.
         */
        resuming: ConversationTurn? = null,
    ): Reply = coroutineScope {
        val recording = Recording(progress, said = resuming?.steps.orEmpty())
        val at = resuming?.at ?: now()

        /*
         * Both rows on the record before anything long happens, and that ordering is the whole
         * of this block.
         *
         * [rollSession] used to come first. It now compacts, which is a full summarisation call
         * of twenty or forty seconds, and it ran before the question was written down and before
         * the answer's row existed - so a turn stopped in that window left nothing at all in the
         * log. The screen showed the question and 「那就先不查了」, and reopening the app showed
         * neither. The thread somebody can see and the record §9 keeps are supposed to be the
         * same thing.
         *
         * So the session is opened here rather than there, the question is written, and the row
         * is claimed; only then is the long call allowed to happen. [rollSession] can still roll
         * a session over underneath all three, which is a backstop compaction makes effectively
         * unreachable - see the note on SESSION_CEILING_TOKENS.
         */
        if (session == null) session = store.loadSession()
        if (session == null) begin(Session(store.nextId(), at))

        if (resuming == null) {
            // Anything left over from a turn that died is not going to be picked up now: this
            // question is what happens instead of it. See [abandon].
            abandon()
            store.appendTurn(
                ConversationTurn(
                    store.nextId(), session!!.id, at, Speaker.USER, userText,
                    images = images.mapNotNull { it.handle },
                ),
            )
        }

        // §9 — before anything else, and without waiting for it. What someone says about
        // themselves is true whether or not this turn ever produces an answer, and it used to
        // be lost whenever the search failed or they closed the app mid-thought.
        memory.noteUser(userText)

        // Claimed before the work starts and written to as it goes. See [Answering].
        val answering = if (resuming == null) {
            Answering(store.nextId(), session!!.id, at, recording)
        } else {
            Answering(resuming.id, session!!.id, resuming.at, recording, done = resuming.rounds)
        }
        answering.checkpoint()

        // Compaction and §8's backstops, now that there is something on the record to survive
        // them. See the note above.
        rollSession(at, recording)

        attached = images
        lastFailure = null
        // Rebuilt from the log every turn, so nothing an earlier turn left behind survives. The
        // last two are always this turn's own - the question, which [converse] puts back, and
        // the row being written into - so they are never history.
        thread = (bridgeMessage() + priorTurns()).toMutableList()

        /*
         * §10 — what is already known, started here and read later.
         *
         * [recall] is three network round trips in series: the bus asks what the question needs,
         * the terms are embedded, and what comes back is reranked. Awaited here, all of it landed
         * in front of the turn, and the screen stayed empty for the whole of it. Measured live on
         * 「医生给我开了阿莫西林胶囊，吃之前我要注意什么？」, time to the first narration line:
         * 27s and 74s awaited, 5s and 2s alongside. The 74 is not an outlier to be explained
         * away - `fish-system` is a shared deployment and it is sometimes just slow, and awaiting
         * it meant the app was as slow as it was on a question it could have started on at once.
         *
         * It is not fire-and-forget, and it must not become that. Recall exists so that what is
         * known is in front of the model *before* it answers; a turn that answered without
         * waiting could tell somebody to take amoxicillin without ever seeing that they are
         * allergic to penicillin, which is the failure the whole memory system was built to
         * prevent. So the turn starts at once and the facts are folded in at the first seam
         * between rounds, and [converse] will not let an answer out until they have landed. In
         * the ordinary case the first round is spent searching and they are in place long
         * before anything is written.
         *
         * Wrapped so it cannot fail the turn. As a bare `async` child, a throw from `embed` or
         * `rerank` would cancel this scope from the side at an unpredictable moment; a memory
         * lookup that did not work is a worse answer, never a broken turn.
         */
        // Narrated like a tool, because to the person watching that is what it is: the one
        // piece of memory work that happens before the model acts. What it found is said at
        // the seam it lands at - see [converse] - so the panel never claims a fact was in front
        // of the model on a turn that answered before the lookup came back.
        recording.step(UiCopy.Narration.RECALLING)
        val recalled = async {
            catching { recall(userText, at, hasPicture = images.isNotEmpty()) }
                .getOrDefault(Remembered.NOTHING)
        }
        val reply = try {
            converse(userText, recording, recalled, answering)
        } catch (stopped: CancellationException) {
            /*
             * Stopped is not lost.
             *
             * Every finished round is already on the record; this closes the row over whatever
             * the round in flight had narrated, so the thread redraws with the working panel it
             * had on screen at the moment the button was pressed. Nothing here suspends - the
             * store is a plain synchronous write - which is the only reason it can run at all
             * inside a coroutine that has already been cancelled.
             */
            answering.checkpoint()
            answering.released()
            throw stopped
        } finally {
            // Whatever is left of it is work nobody is waiting for: the turn is over, and this
            // scope would otherwise sit here until a lookup for an answer already on screen
            // finished. A completed one ignores this.
            recalled.cancel()
        }

        // §10 — and the answer, once there is one. Launched, not awaited: the reply is
        // already on its way to the screen. The caller no longer has to remember to file.
        memory.noteAnswer(
            question = userText,
            answer = reply.text,
            tier = reply.sources.maxOfOrNull { it.tier } ?: Tier.LOW,
            sources = reply.sources.map { it.url },
        )
        answering.close(now(), reply)
        answering.released()
        reply
    }

    // ---- the turn ------------------------------------------------------------------------

    /**
     * One conversation with the model, for as long as it takes.
     *
     * This replaced a pipeline: a classifier decided what kind of question it was, an engine
     * chose a step from that, one search ran, a second model sifted the results, a plan was
     * computed, and a writer was handed the survivors. Every one of those was a decision made
     * before anyone had read a word of what came back.
     *
     * Now there is one model, three tools and a loop. It can search several things at once,
     * read what returns, search again in the light of it, quote from what it found, and answer
     * when it is satisfied. That is what a person does when they look something up, and the
     * pipeline could not express it: the old shape could only ever ask the one question the
     * classifier had extracted before the search began.
     *
     * What did *not* move into the model: the tier every result carries, and the verification
     * every quotation goes through. It picks what to look for and what to make of it, and never
     * what a source is worth or whether it really says what it is quoted as saying.
     */
    private suspend fun converse(
        userText: String,
        progress: TurnProgress,
        /**
         * What memory is finding, still in flight. See [ask] for why it is not awaited there.
         *
         * Read at two places and no others: the seam between rounds takes it if it happens to
         * be finished, and the guard on writing waits for it if it is not. Between them, the
         * facts are in front of the model before a single word of the answer is served.
         */
        recalled: Deferred<Remembered>,
        /** Where each round is written down as it finishes. See [Answering]. */
        answering: Answering,
    ): Reply = try {
        talk(userText, progress, recalled, answering)
    } finally {
        // No loop, no seam: anything said from here is a question, not a correction.
        synchronized(interjected) { looping = false }
    }

    private suspend fun talk(
        userText: String,
        progress: TurnProgress,
        recalled: Deferred<Remembered>,
        answering: Answering,
    ): Reply {
        // Insertion-ordered, so the numbering the model sees is stable across rounds - [3] in
        // round two is the same page it was in round one.
        val seen = LinkedHashMap<String, Evidence>()
        val verified = mutableMapOf<String, String>()
        // Every tool round as it went over the wire, for the log and for the checkpoint the log
        // is written from. See [ConversationTurn.rounds].
        val rounds = answering.rounds
        // What has already been asked for, so a round cannot be spent asking for it again.
        // Seeded from the rounds a resumed turn is carrying, or the guard would start empty on
        // exactly the turn most likely to be stuck in a loop already.
        val tried = Tried().apply { recall(rounds) }
        // From here to the answer there is a seam to deliver at, so [steer] may accept.
        synchronized(interjected) { looping = true }

        // The question goes out without waiting for memory; what memory finds arrives below.
        thread += withImage(
            buildString {
                // The clock, and only here. It changes every turn, so anywhere earlier -
                // the system prompt above all - would rewrite the prefix each call and throw
                // away the cache that moving WORK out of this block was meant to earn.
                appendLine(clockNow())
                append(AgentPrompt.Label.QUESTION).append(userText)
            },
        )

        /*
         * And what this turn had already done, on a turn being picked back up. Empty otherwise.
         *
         * The calls and their results, exactly as they went the first time, so a resumed turn
         * carries on from what it found rather than searching for it again. What does not come
         * back is [seen]: the evidence map is built from live search results and cannot be
         * rebuilt from the text of them, so a page read before the process died has to be
         * reopened before it can be quoted, and sources cited on this turn are the ones it
         * looks up from here. The model can see all of it either way; it is the machinery
         * around quoting and tiering that starts again.
         */
        thread += exchanges(rounds)

        // Rounds that produced neither a tool call nor a word. Nothing goes into the thread on
        // one of those, so the next round is handed the identical prompt and does the identical
        // nothing - the only way out is to stop counting on it.
        var idle = 0
        // Counting from what has already been spent, so a turn resumed after forty rounds gets
        // what is left of the budget rather than a fresh one.
        var round = rounds.size
        /*
         * Rounds in a row in which every single call was handed back unrun, and whether that has
         * already cost the turn its looking-up tools.
         *
         * A repeat is not idleness - the model is talking, asking for things and getting answers
         * back - so [idle] never sees it, and [LAST_ROUND] is a hundred rounds away. Measured
         * live: one page answering 404, opened eleven times in a row, every round billed.
         *
         * The latch does not unlatch. Three rounds of pure repetition is a model that has run out
         * of ideas about where to look, and handing the tools back only lets it have the same
         * idea again; what is left worth doing is writing an answer out of what is already in
         * front of it, which is exactly what taking them away leaves.
         */
        var futile = 0
        var spinning = false
        // What memory offered, once it is in front of the model, and null until then. It is the
        // difference between "memory found nothing" and "memory has not answered yet", and the
        // guard below turns on exactly that distinction.
        //
        // Already delivered, on a turn being picked back up whose replayed rounds carry the
        // block: the seam would otherwise hand the same facts over a second time, under a
        // heading that says memory has just come back, when it came back before the app died.
        var known: Remembered? =
            if (rounds.any { it.known.isNotEmpty() }) Remembered.NOTHING else null
        while (true) {
            // Three states, and the model is never told to stop - only offered less to do with
            // the turn. Given no tools at all it writes prose, and prose is an answer.
            val closing = round >= LAST_ROUND || idle >= IDLE_LIMIT
            val winding = round >= WIND_DOWN_AT || spinning
            val result = llm.complete(
                LlmRequest(
                    system = standingPrompt(),
                    messages = thread,
                    tools = when {
                        closing -> emptyList()
                        // Looking things up is what does not fit in the time left; deciding what
                        // to say still has to. Taking quote away here would mean the last thing
                        // it wrote before the deadline could not be cited.
                        // The log stays on the table while winding down: it is one local
                        // read, it cannot run long, and a follow-up that needs last week's
                        // answer needs it most when there is no time left to search for it.
                        winding -> listOf(Tools.history, Tools.quote, Tools.answer)
                        else -> listOf(
                            Tools.search, Tools.read, Tools.history, Tools.quote, Tools.answer,
                        )
                    },
                    maxTokens = MAIN_BUDGET,
                    effort = Effort.MAX,
                    call = org.areel.fishball.core.llm.Call.ANSWER,
                    temperature = 0.4,
                ),
                // The one call on the screen's two channels. Everything else a turn does runs
                // silent - see [forward].
                progress.forward(answer = true, thinking = true),
            )
            if (result !is LlmResult.Ok) {
                // Carrying what the turn did before it failed. The rounds are checkpointed and
                // [Answering.close] is about to write this reply over them, so a Reply that
                // carried none would delete the record of six good searches because the seventh
                // call to the model timed out.
                return Reply(
                    UiCopy.SERVICE_UNAVAILABLE,
                    detail = (result as? LlmResult.Failed)?.reason,
                    steps = (progress as? Recording)?.lines.orEmpty().toList(),
                    rounds = rounds.toList(),
                )
            }

            // Either hatch: the tool it is asked for, or prose with no tool call at all, which
            // is still an answer - and on the closing round it is the only thing there can be.
            val answered = result.toolCalls.firstOrNull { it.name == Tools.ANSWER }
            val written = answered?.input?.str("text").orEmpty()
                .ifBlank { if (result.toolCalls.isEmpty()) result.text else "" }
            if (written.isNotBlank()) {
                /*
                 * The answer goes out. Memory never holds it.
                 *
                 * This used to wait: if the turn reached an answer before recall landed, it
                 * awaited the lookup and asked the model again with the facts in front of it.
                 * That bought correctness on one narrow case - the answer that should have known
                 * about an allergy - at the price of an extra round on the critical path, and
                 * the user watches that round as more 思考中 for something they did not ask for.
                 *
                 * So it is gone by their decision, and the cost is worth stating once: a turn
                 * that outruns its own recall answers without it. In practice that is rare -
                 * round one is almost always a search, which takes longer than the lookup - and
                 * the facts are still in the store, so the next question finds them. What is
                 * lost is the one turn, not the memory.
                 */
                val sources = cite(seen.values.toList(), verified)
                // §R7. Read off the call rather than inferred from the tiers: whether two
                // pages contradict each other is the one judgement only something that has
                // read both can make. What follows from it stays here - see [shapeOf].
                val disputed = answered?.input?.bool("conflict") == true
                return Reply(
                    text = written,
                    shape = shapeOf(sources, disputed),
                    sources = sources,
                    conflict = disputed,
                    thinking = thoughtIn(result.raw),
                    steps = (progress as? Recording)?.lines.orEmpty().toList(),
                    rounds = rounds.toList(),
                )
            }
            // Nothing said and nothing asked for. On the closing round that is a model that has
            // run out of anything to say, and there is no further round that would change it.
            if (result.toolCalls.isEmpty()) {
                if (closing) {
                    return Reply(
                        UiCopy.SERVICE_UNAVAILABLE,
                        detail = "the model wrote neither an answer nor a tool call",
                        steps = (progress as? Recording)?.lines.orEmpty().toList(),
                        rounds = rounds.toList(),
                    )
                }
                idle++
                round++
                continue
            }
            idle = 0

            thread += result.raw
            // Read either side of the round, so a round in which every call was turned away can
            // be told from one that did something. See [Tried.blocked].
            val turnedAway = tried.blocked
            val results = result.toolCalls.map { call ->
                /*
                 * Word for word the same call as one already in this thread. Not run: what it
                 * returns is a few lines up, and the round would buy nothing but the chance to
                 * ask for it a third time. See [Tried].
                 *
                 * Except `answer`, which is not looked up but submitted. A second attempt at it
                 * is the model fixing a call that would not read, and telling it to try a
                 * different search word instead is the unactionable feedback [AgentPrompt.badCall]
                 * exists to avoid - which is what it gets below, having been let through.
                 */
                if (call.name != Tools.ANSWER) {
                    tried.repeat(call.name, call.input)?.let { said ->
                        return@map LlmContent.ToolResult(call.id, said, isError = true)
                    }
                    tried.record(call.name, call.input)
                }
                when (call.name) {
                    Tools.SEARCH -> lookUp(call, seen, progress)
                    Tools.READ -> openPage(call, seen, progress, tried)
                    Tools.HISTORY -> readLog(call)
                    Tools.QUOTE -> checkQuote(call, seen, verified)
                    // Reached only when `answer` came back with nothing in it - a full one
                    // returns above. Told what was wrong with the call rather than that the
                    // tool does not exist, which is what the `else` used to say about a tool
                    // offered in the same request: see [AgentPrompt.badCall] for what
                    // unactionable rejection feedback costs in rounds.
                    Tools.ANSWER -> LlmContent.ToolResult(
                        call.id,
                        AgentPrompt.badCall(Tools.ANSWER, "{\"text\": \"给他看的答案\"}", call.input.toString()),
                        isError = true,
                    )

                    else -> LlmContent.ToolResult(call.id, "不认识的工具。", isError = true)
                }
            }
            /*
             * The seam. Memory rides in on the back of the tool results, in the same user turn.
             *
             * Only if it has already finished - this is the round the turn saved by not waiting,
             * and reclaiming it here would give it straight back. In practice the round the model
             * spends searching is longer than the lookup, so it is almost always ready by the
             * first one of these. If it is not, the guard above catches it before anything is
             * written.
             *
             * Attached to the tool-result turn rather than sent as a message of its own, because
             * a user turn between an assistant's tool_use and its tool_result is a thread the
             * provider is entitled to reject. Tool results first, then the text - that order is
             * the contract.
             */
            val late = if (known == null && recalled.isCompleted) {
                val arrived = recalled.await()
                known = arrived
                progress.step(UiCopy.Narration.recalled(arrived.world.size + arrived.personal.size))
                arrived.lines().takeIf { it.isNotEmpty() }
            } else {
                null
            }

            // Nothing in this round was actually run. Three of those and the looking-up tools
            // come off the table - see [futile].
            if (tried.blocked - turnedAway == result.toolCalls.size) futile++ else futile = 0
            val latched = !spinning && futile >= FUTILE_LIMIT
            if (latched) spinning = true

            // Whatever was said while that round was running. The seam is the first moment it
            // can go in without splitting a tool call from its result. See [steer].
            val said = synchronized(interjected) {
                val queued = interjected.toList()
                interjected.clear()
                queued
            }
            if (said.isNotEmpty()) progress.step(UiCopy.Narration.STEERED)

            thread += LlmMessage(
                LlmMessage.Role.USER,
                results +
                    listOfNotNull(late?.let { LlmContent.Text(knownBlock(it)) }) +
                    listOfNotNull(
                        said.takeIf { it.isNotEmpty() }
                            ?.let { LlmContent.Text(AgentPrompt.steered(it)) },
                    ) +
                    // Once, on the round it happens. The tools going away is what ends the loop;
                    // this is why, so the model does not narrate it as a broken search.
                    listOfNotNull(
                        AgentPrompt.STOP_SPINNING.takeIf { latched }?.let { LlmContent.Text(it) },
                    ),
            )
            // For the log, exactly as it went: the calls, what came back, and what memory
            // added on the back of them. See [ConversationTurn.rounds].
            rounds += ToolRound(
                exchanges = result.toolCalls.zip(results) { call, outcome ->
                    ToolExchange(call.id, call.name, call.input, outcome.content, outcome.isError)
                },
                known = late.orEmpty(),
                // And why it asked for them. Inside the turn this rides along in [result.raw];
                // across turns it only survives if it is written down here.
                thinking = thoughtIn(result.raw),
                said = said,
            )
            // And on the record before the next round is asked for, so a turn stopped from here
            // on keeps everything it has already done. See [Answering].
            answering.checkpoint()
            round++
        }
    }

    /**
     * One `search` call: every query in it, at the same time.
     *
     * The list is run concurrently rather than in sequence because the model asks for several
     * angles on one question and then waits for all of them - in sequence that is four round
     * trips of somebody watching a spinner.
     *
     * Results are tiered on the way past and remembered for the rest of the turn, so a later
     * `quote` can be checked against a page found three rounds ago.
     */
    private suspend fun lookUp(
        call: LlmContent.ToolUse,
        seen: LinkedHashMap<String, Evidence>,
        progress: TurnProgress,
    ): LlmContent.ToolResult = coroutineScope {
        val queries = call.input.strings("queries")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(Tools.MAX_QUERIES)
        if (queries.isEmpty()) {
            // What was expected and what arrived, both. An error that only says "no" leaves the
            // model guessing at which of a dozen things about the call was wrong, and a model
            // guessing at that stops looking anything up and starts debugging its own syntax.
            return@coroutineScope LlmContent.ToolResult(
                call.id,
                AgentPrompt.badCall(Tools.SEARCH, "{\"queries\": [\"词一\", \"词二\"]}", call.input.toString()),
                isError = true,
            )
        }
        progress.step(UiCopy.Narration.SEARCHING)

        val responses = queries.map { q -> async { search.search(SearchQuery(q)) } }.awaitAll()
        if (responses.all { it.failed }) {
            return@coroutineScope LlmContent.ToolResult(
                call.id,
                "搜索服务这会儿连不上，一条都没查成。",
                isError = true,
            )
        }

        // Each query's page ranked against that query rather than taken off the top in engine
        // order, and the best of every angle interleaved, so a call with five queries is not
        // answered by the first two of them. See [ranked].
        val ranked = responses
            .filterNot { it.failed }
            .map { response ->
                async {
                    val pool = response.hits
                        .distinctBy { it.url }
                        .filterNot { seen.containsKey(it.url) }
                        .take(POOL_PER_QUERY)
                    ranked(response.query.text, pool).take(HITS_PER_QUERY)
                }
            }
            .awaitAll()
        val fresh = interleave(ranked)
            .distinctBy { it.url }
            .take(HITS_PER_CALL)
            .map { Evidence(it, resolver.resolve(it)) }

        fresh.forEach { seen[it.hit.url] = it }
        fresh.take(3).forEach { progress.step(UiCopy.Narration.looked(it.resolution.displayName)) }
        progress.searched(UiCopy.Narration.searched(queries, fresh.map { it.resolution }))

        val body = buildString {
            appendLine("查了：" + queries.joinToString("、"))
            if (fresh.isEmpty()) {
                append("没有新的结果，这几条查到的都是刚才看过的。")
                return@buildString
            }
            val numbered = seen.values.toList()
            fresh.forEach { e ->
                appendLine(
                    AgentPrompt.evidenceLine(
                        index = numbered.indexOf(e),
                        name = e.resolution.attribution(),
                        tier = e.resolution.tier.label,
                        title = e.hit.title,
                        snippet = e.hit.snippet,
                        url = e.hit.url,
                    ),
                )
            }
        }
        LlmContent.ToolResult(call.id, body)
    }

    /**
     * One query's results, best first, as a cross-encoder judges them against the query.
     *
     * A search engine's page is ordered by whatever the engine optimises for, and a result's
     * place on it says little about the page: the live instance merges three engines and hands
     * back the mainland source a question needs behind five Hong Kong ones. Six results were
     * taken off the top of each page and the model was left to judge those by eye. The reranker
     * reads title and snippet against the query and orders the whole page, so what the model
     * is shown is more of the page and the right end of it.
     *
     * Ordered, never filtered. The floor memory uses was measured for "does this answer the
     * question", which is not the question here, and a floor over a result list would delete
     * every result on the day the reranker returns zeros. Engine order stands when there is no
     * reranker, when it fails, and when it takes longer than [RERANK_WAIT_MS]: a slow side call
     * must not stall the search it was meant to improve, which is the lesson recall taught.
     */
    private suspend fun ranked(query: String, hits: List<SearchHit>): List<SearchHit> {
        val engine = retrieval ?: return hits
        if (hits.size < 2) return hits
        // Two nulls, one meaning: the timeout's, and the reranker's own for "I did not rank
        // this". Engine order stands for both, which is what this function already did.
        val scored = withTimeoutOrNull(RERANK_WAIT_MS) {
            engine.rerank(query, hits.map { (it.title + "\n" + it.snippet).trim() })
        } ?: return hits
        val ordered = scored
            .sortedByDescending { it.score }
            .mapNotNull { hits.getOrNull(it.index) }
            .distinct()
        // Whatever the reranker did not mention keeps its place at the end, in engine order.
        return ordered + hits.filterNot { it in ordered }
    }

    /** The first of every list, then the second of every list, and so on. */
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = mutableListOf<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let { out += it } }
        return out
    }

    /**
     * One `read_page` call: the page, and what leads off it.
     *
     * The whole text is kept so a later `quote` is checked against the page rather than against
     * a search engine's summary of it, but only a window of it is handed back - a long article
     * would otherwise cost more of the window than every search in the turn put together, and
     * `find` moves that window, which is cheaper than reading the article to look for one line.
     *
     * A URL nobody searched for is allowed. Following a link off a page is the point of having
     * links, and the tier comes from the registry either way: a page reached by clicking through
     * is worth exactly what its domain is worth, same as one that arrived in a result list.
     */
    private suspend fun openPage(
        call: LlmContent.ToolUse,
        seen: LinkedHashMap<String, Evidence>,
        progress: TurnProgress,
        /**
         * The turn's dead addresses.
         *
         * Kept here as well as over the whole call, because `find` makes two attempts at one
         * unreachable page into two different calls - and varying `find` is precisely what a
         * model does when a page will not open.
         */
        tried: Tried,
    ): LlmContent.ToolResult {
        val url = call.input.str("url").orEmpty().trim()
        if (url.isEmpty() || !url.startsWith("http")) {
            return LlmContent.ToolResult(
                call.id,
                AgentPrompt.badCall(Tools.READ, "{\"url\": \"https://……\"}", call.input.toString()),
                isError = true,
            )
        }
        // Before the fetch, not after it. A page that would not open will not open on the third
        // ask either, and the round spent finding that out again is the loop this exists to end.
        tried.deadEnd(url)?.let { return LlmContent.ToolResult(call.id, it, isError = true) }
        progress.step(UiCopy.Narration.READING)

        val page = pages.read(url)
        if (page.failed || page.text.isBlank()) {
            val reason = page.reason ?: "没有正文"
            tried.bury(url, reason)
            progress.searched(UiCopy.Narration.unread(url))
            return LlmContent.ToolResult(call.id, AgentPrompt.unopenable(reason), isError = true)
        }

        // Already-seen keeps its slot in the numbering and gains the page; a followed link joins
        // the list as its own piece of evidence, which is what it is.
        val existing = seen[url]
        val evidence = existing?.copy(page = page.text) ?: Evidence(
            hit = org.areel.fishball.core.trust.SearchHit(url = url, title = page.title),
            resolution = resolver.resolve(
                org.areel.fishball.core.trust.SearchHit(url = url, title = page.title),
            ),
            page = page.text,
        )
        seen[url] = evidence

        val find = call.input.str("find")?.trim()?.takeIf { it.isNotEmpty() }
        val at = find?.let { page.text.indexOf(it) }?.takeIf { it >= 0 }
        val body = window(page.text, at)
        progress.step(UiCopy.Narration.looked(evidence.resolution.displayName))
        progress.searched(
            UiCopy.Narration.read(
                evidence.resolution.displayName,
                evidence.resolution.tier.label,
                page.title,
            ),
        )

        return LlmContent.ToolResult(
            call.id,
            AgentPrompt.pageLine(
                name = evidence.resolution.attribution(),
                tier = evidence.resolution.tier.label,
                title = page.title,
                url = url,
                body = body,
                length = page.text.length,
                windowed = page.text.length > body.length,
                found = if (find == null) null else at != null,
                links = page.links.take(LINKS_SHOWN).map { it.text to it.url },
            ),
        )
    }

    /**
     * What memory found, as the model reads it mid-turn.
     *
     * Labelled as having just arrived rather than as something that was always there. The model
     * has by this point written a round or two on the assumption that nothing was known, and a
     * block that reads as though it had been in front of it the whole time invites it to explain
     * why it ignored it. This one says what happened: the lookup finished, here is the result.
     */
    private fun knownBlock(lines: List<String>): String = buildString {
        appendLine(AgentPrompt.Label.KNOWN_LATE)
        lines.forEach { appendLine(it) }
    }

    /**
     * The slice of a page the model is shown, centred on what it asked for.
     *
     * Backed up a little from the match rather than started at it, because the sentence that
     * qualifies a claim usually comes before the words being searched for - "除孕晚期外" sits in
     * front of the drug name, not after it.
     */
    private fun window(text: String, at: Int?): String {
        if (text.length <= PAGE_WINDOW) return text
        val start = ((at ?: 0) - PAGE_LEAD_IN).coerceIn(0, text.length - PAGE_WINDOW)
        return text.substring(start, start + PAGE_WINDOW)
    }

    /**
     * The reasoning out of an assistant turn, as the client parsed it.
     *
     * A thinking block arrives as [LlmContent.Opaque] because `:core` has no opinion about
     * blocks it did not ask for - the shape is the provider's, and reading it here rather than
     * teaching the whole codebase about it keeps that true everywhere else.
     */
    private fun thoughtIn(message: LlmMessage): String = message.content
        .filterIsInstance<LlmContent.Opaque>()
        .mapNotNull { block ->
            val kind = block.raw["type"]
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.takeIf { it in REASONING }
                ?: return@mapNotNull null
            block.raw[kind]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        }
        .joinToString("\n")
        .trim()

    /**
     * One `read_log` call: what was actually said, in the window asked for.
     *
     * Nothing here is tiered or quoted, and that is deliberate - this is not evidence about the
     * world, it is a record of a conversation. A source card under an answer that rests on
     * something the user said last Tuesday would be citing them to themselves.
     */
    private fun readLog(call: LlmContent.ToolUse): LlmContent.ToolResult {
        val keyword = call.input.str("keyword").orEmpty().trim()
        val found = store.searchTurns(
            query = keyword,
            from = dayStart(call.input.str("from")),
            // An unparseable date is a null bound rather than an error: a half-understood
            // window still returns something the model can read, and a rejected call spends a
            // whole round teaching it a date format.
            to = dayEnd(call.input.str("to")),
            limit = LOG_HITS,
        )
            // A turn that was stopped before it wrote a word is on the record so the model can
            // replay its looking - it is not a thing that was said. See [Answering].
            .filter { it.text.isNotBlank() }
        if (found.isEmpty()) {
            return LlmContent.ToolResult(call.id, AgentPrompt.LOG_EMPTY)
        }
        val body = buildString {
            appendLine(AgentPrompt.LOG_FOUND)
            // Oldest first. The log reads as a conversation that way, and the model is being
            // asked what was said, which has an order.
            found.sortedBy { it.at }.forEach {
                appendLine(
                    stampOf(it.at) + AgentPrompt.logLine(it.speaker == Speaker.USER, it.text),
                )
            }
        }
        return LlmContent.ToolResult(call.id, body.trim())
    }

    /** One `quote` call, checked word for word against the page it claims to come from. */
    private fun checkQuote(
        call: LlmContent.ToolUse,
        seen: Map<String, Evidence>,
        verified: MutableMap<String, String>,
    ): LlmContent.ToolResult {
        val url = call.input.str("url").orEmpty()
        val sources = seen.values.map { SourceText(it.hit.url, it.text) }
        val outcome = verifier.verify(
            QuoteRequest(url, call.input.str("text").orEmpty()),
            sources,
        )
        return when (outcome) {
            is QuoteResult.Verified -> {
                verified[url] = outcome.quote.exact
                LlmContent.ToolResult(call.id, outcome.quote.exact)
            }

            is QuoteResult.Rejected ->
                LlmContent.ToolResult(call.id, outcome.feedback, isError = true)
        }
    }

    /**
     * What the meter shows, from the sources the answer actually rests on.
     *
     * Computed after the fact now rather than planned before it. The plan used to choose the
     * shape and hand the writer a sentence budget with it; with the model doing its own looking
     * there is no before-the-fact to plan in, and the honest thing left to say is how good the
     * sources it ended up with were.
     *
     * [disputed] comes first and outranks every tier, which is the whole of R7: a claim two
     * authorities disagree about is not a confident answer that happens to have a note on it.
     * The meter reads CONFLICT as no reading at all and draws the fault line instead, because
     * averaging the two into "medium" hides exactly what the reader needs to see.
     */
    private fun shapeOf(sources: List<SourceRef>, disputed: Boolean = false): AnswerShape? = when {
        sources.isEmpty() -> null
        disputed -> AnswerShape.CONFLICT
        sources.any { it.tier >= Tier.AUTHORITATIVE } -> AnswerShape.CONFIDENT
        sources.any { it.tier >= Tier.HIGH } -> AnswerShape.ATTRIBUTED
        else -> AnswerShape.WEAK_LEAD
    }

    // ---- routing ------------------------------------------------------------------------

    /**
     * Spec §10 — what is already known about this question.
     *
     * Three stages, and each one is there because the stage before it cannot do the job.
     *
     * The question is not the search key. It carries grammar, politeness, and usually a pronoun
     * standing in for the only word that matters; embedded whole, all of that drags the vector
     * away from the thing being asked about. So the model is asked first what facts the question
     * *needs*, and those are what memory is searched by - several of them, because a question
     * rarely rests on one fact.
     *
     * The wide pass is cheap, local and dumb: ten cached answers and ten things known about this
     * person, taken on cosine with a word-overlap floor. It is tuned to miss nothing, and it
     * happily returns near-misses.
     *
     * The narrowing pass is where near-misses die. It is two different tests, because the two
     * sections are two different kinds of claim - a cached answer is judged by a reranker on
     * whether it answers the question, and a fact about the user is judged on how close it is to
     * what was asked for. See [narrowAnswers] and [PREFERENCE_FLOOR] for why running one test
     * over both throws away every true thing known about the person.
     *
     * Serving a stale near-miss as though it were the answer is the failure all of this guards
     * against, and it is worse than searching again.
     */
    private suspend fun recall(question: String, at: Long, hasPicture: Boolean): Remembered {
        // No embedding model reachable: fall back to word overlap, which finds a cached answer
        // only when the question is asked in nearly the same words. A floor, not a search.
        val engine = retrieval ?: return Remembered(listOfNotNull(store.recallWorldFact(question, at)))

        // What the question needs known, rather than the question itself. On the fast model,
        // like everything else about memory - which is why the pictures are named rather than
        // sent; see [MemoryBus.terms].
        // Passed in rather than read off [attached], because this now runs on its own coroutine
        // alongside the turn and a field is the one thing a later turn could change underneath it.
        /*
         * The factorisation, and only on the professional tier.
         *
         * Taking a question apart into what it needs known is a real improvement - 「我这个药还
         * 能吃吗」 shares no word with 对青霉素过敏 and no amount of embedding the sentence will
         * find it - but it is a whole model call in front of every turn, and the fast tier
         * exists to not make calls like that.
         *
         * So the fast tier embeds the question as it stands. That finds the paraphrases, which
         * is most of what memory is for, and misses the ones where the needed fact is not named
         * in the sentence. A worse search, not an absent one.
         */
        val wanted = if (expert) {
            memory.terms(
                question,
                priorTurns().takeLast(CLASSIFY_CONTEXT_TURNS),
                hasPicture = hasPicture,
            )
        } else {
            emptyList<String>() to emptyList()
        }
        val facts = wanted.first.ifEmpty { listOf(question) }
        val personal = wanted.second

        // One embedding call for both lists; they are short and the round trip is the cost.
        val all = facts + personal
        val vectors = engine.embed(all)
        val factVectors = vectors.take(facts.size)
        val personalVectors = vectors.drop(facts.size)

        val world = store.recallWorldCandidates(facts, factVectors, at)
        val about = if (personal.isEmpty()) {
            emptyList()
        } else {
            store.recallPreferenceCandidates(personal, personalVectors)
        }
        if (world.isEmpty() && about.isEmpty()) return Remembered.NOTHING

        return Remembered(
            world = narrowAnswers(question, world),
            // Already scored, against the terms it was looked up by. See [PREFERENCE_FLOOR].
            //
            // §20 — and it has to still be true. A cached answer carries `fresh` and the
            // narrowing pass reads it; a preference carried nothing, so a six-month
            // 在吃布洛芬 recorded eight months ago cleared the cosine floor and was handed over
            // as present tense with no age on it, in front of a question about what that drug
            // interacts with. Something with a lifetime that has run out is not known any more.
            // A permanent one - an allergy, a chronic condition - is always fresh and is never
            // what this drops.
            personal = about
                .filter { it.similarity >= PREFERENCE_FLOOR && it.fact.isFresh(at) }
                .take(KEPT_MEMORIES),
        )
    }

    /**
     * The narrowing pass over cached answers, against the original question.
     *
     * The wide pass searched by the facts the question needs, so what comes back resembles those
     * terms by construction; this asks the harder thing, which is whether any of it actually
     * answers what was asked. Only a cross-encoder can tell those apart - measured live, a
     * genuine match scored 0.96, a same-topic-different-question one 0.24.
     */
    private suspend fun narrowAnswers(question: String, world: List<WorldRecall>): List<WorldRecall> {
        if (world.isEmpty()) return emptyList()
        val engine = retrieval ?: return emptyList()
        // Null is "nothing ranked this", which is not a verdict and must not be read as one.
        // The floor applied to an unranked list throws away every candidate, and that is how a
        // provider with no reranker - or a reranker that is merely down - used to end up with
        // an app that silently remembered nothing it had ever looked up. Cosine already chose
        // these and already ordered them; without a second opinion its order stands.
        val scored = engine.rerank(question, world.map { it.fact.question + "。" + it.fact.answer })
            ?: return world.take(KEPT_MEMORIES)
        return scored
            .filter { it.score >= RERANK_FLOOR }
            .take(KEPT_MEMORIES)
            .mapNotNull { world.getOrNull(it.index) }
    }

    // ---- the step machine ---------------------------------------------------------------

    // ---- search -------------------------------------------------------------------------

    private class Gathered(
        val evidence: List<Evidence>,
        val allFailed: Boolean,
        val conflict: Boolean = false,
    )

    // ---- writing ------------------------------------------------------------------------

    /**
     * A user turn, with this turn's pictures in front of the words.
     *
     * The images go first because that is the order the question is asked in: someone holds up
     * a box and then says "can I take this". A model handed the sentence first has already
     * started answering by the time it looks.
     */
    private fun withImage(text: String): LlmMessage {
        if (attached.isEmpty()) return LlmMessage.user(text)
        return LlmMessage(LlmMessage.Role.USER, attached + LlmContent.Text(text))
    }

    /** Spec §8's bridge, when there is one: the previous session folded into a paragraph. */
    /**
     * The standing prompt, and nothing else. The same bytes on every call, forever.
     *
     * The session bridge used to be appended here, which meant the one part of the request that
     * could be cached stopped being identical the moment a session rolled - and since a rollover
     * is exactly when the thread gets long enough for the cache to be worth anything, it was
     * thrown away at the least convenient moment. It is a message now; see [bridgeMessage].
     */
    private fun standingPrompt(): String = AgentPrompt.SYSTEM + "\n\n" + AgentPrompt.WORK

    /**
     * The previous session, folded, as the first thing said rather than part of the system.
     *
     * A user turn because that is what it is: something already established between the two of
     * them. DeepSeek Harness lands its checkpoints the same way and for the same reason - the
     * summary belongs in the dialogue, and the system prompt belongs to the cache.
     */
    private fun bridgeMessage(): List<LlmMessage> {
        val bridge = session?.bridge?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(LlmMessage.user(AgentPrompt.compacted(bridge)))
    }

    /**
     * What time it is, in words, once per turn.
     *
     * Without it 「昨天」 has nothing to be relative to: the model can read a timestamp on every
     * replayed line and still not know which of them was yesterday. The weekday is included
     * because people say 「上周三」 far more often than they say a date.
     */
    private fun clockNow(): String {
        val t = local(now())
        return AgentPrompt.clockLine(
            year = t.year,
            month = t.monthValue,
            day = t.dayOfMonth,
            weekday = AgentPrompt.WEEKDAYS[t.dayOfWeek.value - 1],
            hour = t.hour,
            minute = t.minute,
        )
    }

    /**
     * A stamp somebody's answer starts with, taken back off.
     *
     * Defensive, and it has already been needed. Assistant turns used to be replayed with a
     * time on the front like the user's are, and the model read its own replayed turns for the
     * house format and began writing 「（9月3日 21:53）」 at the top of its answers - which then
     * went into the log as part of the answer and back into the next prompt as another example.
     * Three turns on a real phone before it was caught.
     *
     * The stamping is fixed above; this cleans what the stamped build already wrote, so those
     * turns neither show a timestamp to the user nor keep teaching the pattern.
     */
    private fun unstamped(text: String): String = STAMPED.replaceFirst(text, "")

    /** When a turn was said, for the line that replays it. */
    private fun stampOf(at: Long): String {
        val t = local(at)
        return AgentPrompt.stamp(t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    /**
     * Epoch millis in the phone's own zone.
     *
     * The device's zone rather than UTC, because every date in this app is one a person said
     * out loud - 「昨天」 means their yesterday, and a log line stamped 23:40 UTC would be shown
     * on the wrong day to somebody in Beijing for a third of every day.
     */
    private fun local(at: Long): java.time.LocalDateTime =
        java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()

    /**
     * A day, as the first and last millisecond of it.
     *
     * `to` is inclusive of its whole day: somebody asking for 9月1日 to 9月3日 means all three
     * days, and a bound at midnight would silently drop everything said on the last one.
     */
    private fun dayStart(text: String?): Long? = day(text)?.let {
        it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun dayEnd(text: String?): Long? = day(text)?.let {
        it.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    }

    private fun day(text: String?): java.time.LocalDate? =
        text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        }

    /**
     * What goes under the answer. Spec §25 — a source carries its quote only if the verifier
     * put one there.
     *
     * Not everything that was read. A live turn on a health question read 29 results and would
     * have stacked all 29 under a three-sentence answer, most of them 来源不明 — which buries
     * the two the answer actually rests on and turns a citation into a search-results page.
     *
     * Order is quoted first, then by tier. A quoted source is one the answer leans on by name;
     * an unquoted authoritative one is context. Everything below the cut was still read, still
     * tiered, and still shaped the answer — it just is not evidence the reader needs to see.
     */
    private fun cite(evidence: List<Evidence>, verified: Map<String, String>): List<SourceRef> =
        evidence
            .distinctBy { it.hit.url }
            .sortedWith(
                compareByDescending<Evidence> { verified.containsKey(it.hit.url) }
                    .thenByDescending { it.resolution.tier },
            )
            .take(MAX_SOURCES_SHOWN)
            .map {
                SourceRef(
                    url = it.hit.url,
                    displayName = it.resolution.displayName,
                    explanation = it.resolution.explanation,
                    tier = it.resolution.tier,
                    quote = verified[it.hit.url],
                )
            }

    // ---- memory -------------------------------------------------------------------------


    // ---- sessions -----------------------------------------------------------------------

    private suspend fun rollSession(at: Long, progress: TurnProgress) {
        // §8 — invisible to the user. Crossing the boundary bounds the prompt; it does not
        // clear anything they can see, and the log survives it untouched.
        if (session == null) session = store.loadSession()
        /*
         * Compaction first, and the two lines below are backstops behind it.
         *
         * Order is the whole of it. Measured against a context that has not been compacted yet,
         * [SESSION_CEILING_TOKENS] fires on a session that needed nothing more than its oldest
         * span replacing - and folds the recent end that compaction exists to keep. Run in this
         * order a session only reaches those lines if a summary plus the retained tail is itself
         * enormous, which is a session worth starting again rather than compacting again.
         *
         * Also before the question is written down, so the seam is never asked to consider a
         * turn that has not happened yet.
         */
        // What is actually sent, which since compaction is not the whole session: the summary
        // standing in for the folded head plus the turns still quoted after it. Taken from
        // [compress], which had to measure it to decide whether to fold - measuring it again
        // here meant replaying every tool result of the session twice on the turn path.
        val size = compress(progress)

        when (
            val decision =
                sessions.decide(session, store.lastTurnAt(), at, size) { store.nextId() }
        ) {
            is org.areel.fishball.core.session.SessionDecision.Start ->
                begin(Session(decision.newSessionId, at))

            is org.areel.fishball.core.session.SessionDecision.RollOver -> {
                // Both stale and large, or past the ceiling. Everything said is still in the
                // log; what rides forward is a paragraph, so a back-reference still resolves
                // without carrying the whole conversation into every future prompt.
                // Read only on this branch, which compaction makes effectively unreachable -
                // no reason to walk the log for it on every ordinary turn.
                val sofar = session?.let { carried(it) }.orEmpty()
                val bridge = if (sessions.needsBridge(sofar.size)) {
                    // Said out loud, because it is one long model call before the turn can
                    // start and it now happens mid-conversation. Unnarrated it is a minute of
                    // 思考中 with nothing under it, which reads as the app having hung.
                    progress.step(UiCopy.Narration.FOLDING)
                    // A summary that could not be written is not a reason to throw the
                    // conversation away. This used to hand the null straight to the new session,
                    // which then had no bridge and no turns - every word of a long conversation
                    // deleted because one call failed. Staying put costs an over-budget prompt
                    // for one turn, which is the cheaper of the two by a distance.
                    summarise(sofar) ?: return
                } else {
                    null
                }
                begin(Session(decision.newSessionId, at, bridge = bridge))
            }

            is org.areel.fishball.core.session.SessionDecision.Continue -> Unit
        }
    }

    private fun begin(next: Session) {
        session = next
        store.saveSession(next)
    }

    /**
     * What a session actually costs to replay, which is not what was said in it.
     *
     * The compaction threshold has to be measured against what [priorTurns] puts on the wire,
     * and since the reasoning started riding along with each assistant turn - and now with each
     * round of each assistant turn - that is mostly not the text.
     * Measured in this file's own notes at over four thousand characters of thinking against a
     * few hundred of answer, so sizing on `text` alone undercounted the prompt several times
     * over: the rollover never fired, [compact] always returned false, and a conversation ran
     * past the window it was being kept inside.
     */
    private fun sentSize(turns: List<ConversationTurn>): Int = estimateTokens(
        replay(turns).joinToString("\n") { message ->
            message.content.joinToString("\n") {
                when (it) {
                    is LlmContent.Text -> it.text
                    is LlmContent.ToolUse -> it.input.toString()
                    is LlmContent.ToolResult -> it.content
                    is LlmContent.Opaque -> it.raw.toString()
                    is LlmContent.Image -> ""
                }
            }
        },
    )

    /**
     * Fold the conversation so far into one paragraph and start again from it.
     *
     * Called when the model changes, as well as on the §8 rollover: a different model has not
     * read any of this, and handing it a transcript written by another one is worse context
     * than a summary of what the two of you actually settled.
     *
     * **Not under [SESSION_COMPACT_TOKENS].** That argument is only true of a conversation too
     * long to hand over whole. A short one fits in any of these models' windows several times
     * over, and folding it trades everything that was actually said for a paragraph about it -
     * so switching model three messages in used to cost the three messages. Under the
     * threshold the transcript goes forward as it stands, which is strictly more than a
     * summary of it, and the caller is told nothing was folded because nothing was.
     */
    suspend fun compact(): Boolean {
        val open = session ?: store.loadSession() ?: return false
        val sofar = carried(open)
        // Nothing has been said in this session, so there is nothing to fold and no reason to
        // start another one - the session it would open is the session it is already in.
        // Switching model twice in a row used to roll a fresh empty session each time and
        // announce a compaction that had not happened.
        if (sofar.isEmpty()) return false
        if (bridgeSize(open) + sentSize(sofar) < SESSION_COMPACT_TOKENS) {
            return false
        }

        // A summary that could not be written is not a reason to throw the conversation away.
        // This used to hand the null straight to the new session, which started it with no
        // bridge and no turns - the whole conversation deleted because one call failed.
        val folded = summarise(sofar) ?: return false
        begin(Session(store.nextId(), now(), bridge = folded))
        return true
    }

    /**
     * Context pressure, handled the way DeepSeek Harness handles it.
     *
     * The fold this app had was all or nothing: past a line, the entire session was replaced by
     * one paragraph and every tool call, every page read and every line of reasoning behind them
     * went with it. That is a large price paid in one instalment, and it is paid on the
     * conversation somebody is in the middle of.
     *
     * DSH's `compaction-basic` does the same job without the cliff, and this is its shape.
     * Nothing happens until the replayed context crosses [COMPACT_AT_TOKENS] - 80% of the window
     * - and what happens then is that the *oldest* span is replaced by a summary of itself while
     * the most recent [COMPACT_RETAIN_TOKENS] stay word for word. So an ordinary conversation is
     * never compacted at all, and a long one keeps its recent looking intact and loses only the
     * detail of what it was doing an hour ago.
     *
     * Three properties are load-bearing and each was a defect in the old one.
     *
     * The seam falls on a whole turn, and never between a question and its answer. DSH states
     * this as tool pairing - no unanswered assistant tool call may cross the edge - and here a
     * turn's rounds are all inside the turn, so keeping turns whole gives it for free. Trailing
     * questions are pushed back into the retained side rather than folded away from the answer
     * they were asked of.
     *
     * The summary is written by replaying the shadowed span exactly as it was sent, under the
     * same system prompt and the same tools, with the instruction last. DSH is explicit that
     * this is what lets the provider's warm prefix be reused up to that trailing instruction;
     * a summariser prompt of its own would cost the entire cache to save a few hundred tokens.
     * See [summarise], which already worked this way.
     *
     * And a summary that fails to arrive changes nothing. The seam does not move, the context
     * stays over budget for a turn, and the conversation is still there to try again with -
     * which is the opposite of what a failed fold used to do.
     */
    private suspend fun compress(progress: TurnProgress): Int {
        val open = session ?: return 0
        val live = carried(open)
        val size = bridgeSize(open) + sentSize(live)
        if (size < COMPACT_AT_TOKENS) return size

        // The tail kept verbatim, newest first, until the retain budget is spent. The newest
        // turn is kept whatever it costs: a single turn larger than the whole budget is a turn
        // there is nothing useful to do about, and dropping it would fold away the thing being
        // talked about right now.
        var budget = COMPACT_RETAIN_TOKENS
        var kept = 0
        for (turn in live.asReversed()) {
            val cost = sentSize(listOf(turn))
            if (kept > 0 && cost > budget) break
            budget -= cost
            kept++
        }

        // Never a question without the answer it got. Anything trailing on the shadowed side
        // stays on the retained side instead, which is the only direction that is safe.
        var shadowed = live.dropLast(kept)
        while (shadowed.isNotEmpty() && shadowed.last().speaker == Speaker.USER) {
            shadowed = shadowed.dropLast(1)
        }
        if (shadowed.isEmpty()) return size

        // Said out loud: it is one long model call in front of a turn that has not started, and
        // unnarrated it reads as the app having hung.
        progress.step(UiCopy.Narration.FOLDING)
        val summary = summarise(shadowed) ?: return size
        begin(open.copy(bridge = summary, compactedThrough = shadowed.last().id))
        // What is left, measured once, for the caller that has to decide against it.
        val folded = session!!
        return bridgeSize(folded) + sentSize(carried(folded))
    }

    /** What the standing summary costs, since it is part of what is sent. */
    private fun bridgeSize(open: Session): Int = estimateTokens(open.bridge.orEmpty())

    /**
     * Fold a session, by asking the conversation itself.
     *
     * Three things here are deliberate and all three come from the same finding.
     *
     * It runs on the *answering* model, not [SYSTEM_MODEL]. Writing a summary somebody's next
     * hour depends on is not the bookkeeping that model is for, and the thread it is condensing
     * was written by this one.
     *
     * It reuses the standing prompt and the tool list verbatim rather than a summariser prompt
     * of its own. The tools ride along unused on purpose: DeepSeek Harness measured that
     * dropping them shortens the token sequence and breaks alignment with the cached request,
     * and the whole point of this shape is that the call is a prefix of one the service has
     * already seen. Its own system prompt would cost the entire cache to save a few hundred
     * tokens of persona.
     *
     * And the instruction goes last, as a user turn, for the same reason - anything in front of
     * the replayed conversation would change the prefix.
     */
    private suspend fun summarise(turns: List<ConversationTurn>): String? {
        val replayed = replay(turns)
        val result = llm.complete(
            LlmRequest(
                system = standingPrompt(),
                messages = bridgeMessage() + replayed + LlmMessage.user(AgentPrompt.COMPACT),
                // Unused, and sent anyway. See above.
                tools = listOf(
                    Tools.search, Tools.read, Tools.history, Tools.quote, Tools.answer,
                ),
                maxTokens = COMPACT_BUDGET,
                effort = Effort.HIGH,
                call = org.areel.fishball.core.llm.Call.COMPACT,
            ),
        )
        return (result as? LlmResult.Ok)?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * What has already been said this session, as turns the model can read.
     *
     * Rebuilt from the log rather than held alongside it. The log is written on every turn and
     * survives a restart, so using anything else would mean two records of one conversation and
     * a way for them to disagree — which is how the thread came back after a restart while the
     * model behaved as though nothing had been said.
     *
     * The final entry is dropped: it is the question being answered right now, already written
     * into this turn's own prompt.
     */
    private fun priorTurns(): List<LlmMessage> {
        val open = session ?: return emptyList()
        // Two, always: the question being answered right now and the row its answer is being
        // written into, both of which this turn puts back itself. See [turn].
        return replay(carried(open).dropLast(2))
    }

    /**
     * The turns still quoted in full: everything after the compaction seam.
     *
     * What is in front of the seam is not gone. It is in the log, it is searchable with
     * `read_log`, and the summary standing in its place was written from it - what changed is
     * only that the prompt has stopped quoting it word for word. See [compress].
     */
    private fun carried(open: Session): List<ConversationTurn> {
        val turns = store.turnsInSession(open.id)
        return if (open.compactedThrough == 0L) turns else turns.filter { it.id > open.compactedThrough }
    }

    /**
     * Turns from the log, as messages the model reads.
     *
     * Every answer goes back with the looking that produced it - each round's tool calls as an
     * assistant turn, their results and whatever memory added on the back of them as the user
     * turn after it, exactly as they were sent the first time - and only then the answer.
     * Without that, every answer was replayed as though it had been written from nothing: the
     * model could see that it had said 孕晚期禁用 and not which page said so, and a follow-up
     * about that page sent it searching for it again; and what memory had offered mid-turn was
     * gone with it, so the model that knew about the allergy last turn did not know this one.
     *
     * All of it, back to the compaction seam, and not a recent window. What keeps the prompt
     * inside the model's is [compress]: past 80% of the window the oldest span is replaced by a
     * summary of itself and the seam moves up, so what this function is handed is already
     * bounded. Everything it is handed, it quotes.
     *
     * One function for the thread, the checkpoint and the size, so what is measured is what is
     * sent. See [sentSize].
     */
    private fun replay(turns: List<ConversationTurn>): List<LlmMessage> = turns.flatMap { turn ->
        if (turn.speaker == Speaker.USER) listOf(LlmMessage.user(stampOf(turn.at) + turn.text))
        else exchanges(turn.rounds) + recalled(turn)
    }

    /**
     * The tool rounds of one answer, as the assistant/user pairs the provider expects.
     *
     * Each round goes back with the thinking that preceded it, the same way the answer goes back
     * with the thinking that preceded that. Without it the model reads six searches it made for
     * reasons it can no longer see, and a follow-up about why something was ruled out has only
     * the query text to work it out from.
     */
    private fun exchanges(rounds: List<ToolRound>): List<LlmMessage> = rounds
        .filter { it.exchanges.isNotEmpty() }
        .flatMap { round ->
            listOf(
                LlmMessage(
                    LlmMessage.Role.ASSISTANT,
                    listOfNotNull(thinkingBlock(round.thinking)) +
                        round.exchanges.map { LlmContent.ToolUse(it.id, it.name, it.input) },
                ),
                LlmMessage(
                    LlmMessage.Role.USER,
                    round.exchanges.map { LlmContent.ToolResult(it.id, it.result, it.isError) } +
                        listOfNotNull(
                            round.known.takeIf { it.isNotEmpty() }?.let { LlmContent.Text(knownBlock(it)) },
                            round.said.takeIf { it.isNotEmpty() }?.let {
                                LlmContent.Text(AgentPrompt.steered(it))
                            },
                        ),
                ),
            )
        }

    /**
     * One assistant turn from the log, with the reasoning that produced it back in front of it.
     *
     * These models write an answer *after* a thinking block and as a continuation of one, so a
     * turn replayed as bare prose asks the next question to carry on from a conclusion whose
     * working has been thrown away. Live, that is the difference between a follow-up that knows
     * why something was ruled out last time and one that rules it out again from scratch.
     *
     * The block goes back in the shape it arrived in - `Opaque`, holding the provider's own
     * JSON - so this replays what was received rather than a translation of it. That is not a
     * free choice: the app tried rewriting reasoning into labelled *text* once, and the model
     * read its own replayed turn as a template and began emitting the label, the reasoning and
     * a `</think>` tag into answers the user could see. Anything put in an assistant turn is
     * something the model may imitate; a thinking block is the one shape it should imitate.
     *
     * Turns logged before reasoning was kept carry none, and come back as they always did.
     */
    private fun recalled(turn: ConversationTurn): LlmMessage {
        // Blank text is a turn that never reached an answer - it was stopped, or the app was
        // killed under it - and its rounds are on the record all the same. Something has to
        // stand in its place: an assistant turn whose text block is empty is a thread the
        // provider is entitled to reject, and a silent one leaves the model to work out for
        // itself why it stopped mid-search. See [Answering] and [AgentPrompt.INTERRUPTED].
        val said = unstamped(turn.text).ifBlank { AgentPrompt.INTERRUPTED }
        val thought = thinkingBlock(turn.reasoning) ?: return LlmMessage.assistant(said)
        return LlmMessage(LlmMessage.Role.ASSISTANT, listOf(thought, LlmContent.Text(said)))
    }

    /**
     * Reasoning, in the shape the model produced it, or null when there was none.
     *
     * [LlmContent.Opaque] holding the provider's own JSON rather than labelled text, and that is
     * not a free choice: this app rewrote reasoning into a labelled *text* block once, and the
     * model read its own replayed turn as a template and began emitting the label, the reasoning
     * and a `</think>` tag into answers the user could see. Anything put in an assistant turn is
     * something the model may imitate; a thinking block is the one shape it should imitate.
     */
    private fun thinkingBlock(reasoning: String): LlmContent? {
        if (reasoning.isBlank()) return null
        return LlmContent.Opaque(
            buildJsonObject {
                put("type", THINKING)
                put(THINKING, reasoning)
            },
        )
    }

    private fun failure(): Reply = Reply(UiCopy.SERVICE_UNAVAILABLE, detail = lastFailure)

    private companion object {
        /** Enough for a few rejected quotes; short enough that a loop cannot bill forever. */
        const val MAX_COMPOSE_ROUNDS = 5

        /**
         * How sure the reranker has to be before a cached answer is served instead of a search.
         *
         * Measured against the live model: a genuine match scored 0.96, an unrelated document
         * 0.00002, and a same-topic-different-question one 0.24. Half is comfortably clear of
         * the near-misses, which are the dangerous ones.
         */
        const val RERANK_FLOOR = 0.5

        /**
         * How close a remembered fact about the user has to be to what was asked for.
         *
         * Cosine, not the reranker, and that is a measurement rather than a preference. A
         * reranker scores whether a passage *answers* a query, and a fact about a person answers
         * nothing - live, it put 对青霉素过敏 at 0.106 against 药物过敏史 and 0.0001 against the
         * question that needed it. Run over this section it does not rank the facts, it deletes
         * them.
         *
         * The number is where the two populations separate on this embedding model, which sits
         * high and needs a high floor. Over five lookup terms against seven stored facts, every
         * true match landed 0.665-0.758 and every false one at or below 0.614 - the worst being
         * 有高血压, which scores warmly against anything medical. Two thirds of the way into that
         * gap, and deliberately nearer the noise: a missed fact costs a question, an invented one
         * puts a condition the user never mentioned into a medical answer.
         */
        const val PREFERENCE_FLOOR = 0.64

        /** What survives the narrowing pass, per section, and is put in front of the model. */
        const val KEPT_MEMORIES = 4

        /**
         * Offered for retirement on a turn that looked nothing up. Few, and loose.
         *
         * Looser than [PREFERENCE_FLOOR] on purpose, because the two numbers gate different
         * things. That one decides what gets stated back to the user as known, where a near-miss
         * becomes a fabricated fact and the number is the only guard. This one decides what the
         * model is *shown* and may strike out, and it has to name an index while reading what the
         * user actually said - the model is the filter, so the number should protect recall
         * instead of duplicating a judgement made downstream of it.
         *
         * Set at 0.64 first, which is what a live correction measured: 布洛芬我已经停了 against a
         * stored 在吃布洛芬 fell in the gap, was never offered, and the contradicted record went on
         * being served.
         */
        const val NEARBY_LIMIT = 4
        const val NEARBY_FLOOR = 0.5

        /** A card the reader will actually look at. Past four it is a list, not a citation. */
        const val MAX_SOURCES_SHOWN = 4

        /** Enough for a pronoun to resolve, not so much that routing costs a full transcript. */
        const val CLASSIFY_CONTEXT_TURNS = 6

    }
}

/**
 * Everything a turn has to say while it is still running.
 *
 * Three separate channels because they are three different promises. [step] is §21's narration
 * and is written for the user. [thinking] is the model's reasoning, shown to fill a wait that
 * is otherwise a minute of nothing, and thrown away afterwards. [answer] is the reply itself,
 * arriving in pieces.
 */
interface TurnProgress {
    fun step(text: String) {}
    fun thinking(delta: String) {}
    fun answer(delta: String) {}

    /**
     * One round of looking, finished.
     *
     * Separate from [step] because it is a different kind of thing. A step is a line that
     * scrolls past inside the placeholder and is gone; this is a record of a round of work that
     * stays in the thread. A turn that searches three times leaves three of these, which is the
     * only way from the outside to see that it searched three times.
     */
    fun searched(summary: String) {}

    /** For callers that only want the result — tests, and the log-replay path. */
    object Silent : TurnProgress
}

/**
 * What memory offered for a turn, after both passes.
 *
 * Two sections because they are two different kinds of claim. A cached answer is something the
 * world said and can go stale; a preference is something this person said about themselves and
 * goes stale differently. They are searched separately, ranked together, and shown separately.
 */
data class Remembered(
    val world: List<WorldRecall> = emptyList(),
    val personal: List<PreferenceRecall> = emptyList(),
) {
    /** Spec §10 — the one cached answer good enough to stand in for a search. */
    val servable: WorldRecall? get() = world.firstOrNull { it.servableWithoutSearch }

    /** For the writing step: what is already known, so it is not asked for again. */
    fun lines(): List<String> =
        personal.map { "- [" + AgentPrompt.Label.KNOWN_USER + "] " + it.fact.text } +
            world.map { "- [" + AgentPrompt.Label.KNOWN_FACT + "] " + it.fact.answer }

    /** For the bus: the same list, numbered, so a correction can point at one. */
    fun numbered(): String {
        if (world.isEmpty() && personal.isEmpty()) return ""
        val out = StringBuilder("\n\n" + AgentPrompt.Label.KNOWN + "\n")
        world.forEachIndexed { i, it ->
            out.append("[").append(AgentPrompt.Label.KNOWN_FACT).append(" ").append(i).append("] ")
                .append(it.fact.question).append(" — ").append(it.fact.answer).append("\n")
        }
        personal.forEachIndexed { i, it ->
            out.append("[").append(AgentPrompt.Label.KNOWN_USER).append(" ").append(i).append("] ")
                .append(it.fact.text).append("\n")
        }
        return out.toString()
    }

    companion object {
        val NOTHING = Remembered()
    }
}

/** What one turn produced, in the terms the UI draws. */
data class Reply(
    val text: String,
    val shape: AnswerShape? = null,
    val sources: List<SourceRef> = emptyList(),
    val conflict: Boolean = false,
    /**
     * The underlying failure when [text] is an apology rather than an answer. Never shown
     * unasked - the user cannot act on it - but kept so it can be read on request instead of
     * being invented later from a description of the symptom.
     */
    val detail: String? = null,
    /**
     * The reasoning behind this answer, for the log to keep and the next turn to replay.
     *
     * Taken from the round that actually produced the answer rather than accumulated over the
     * whole turn: a long-horizon turn thinks before all hundred of its rounds, and what the
     * next question needs is the thinking that arrived at the conclusion, not a transcript of
     * every search that preceded it.
     */
    val thinking: String = "",
    /** What the turn narrated on its way here, in the order it said it. */
    val steps: List<String> = emptyList(),
    /** Every tool round the turn ran, for the log. See [ConversationTurn.rounds]. */
    val rounds: List<ToolRound> = emptyList(),
)

/**
 * A turn the app was killed in the middle of, as the screen needs to see it.
 *
 * Enough to put the question back in front of somebody and start the placeholder where it left
 * off, and no more: the driver re-reads the rows itself when [Conversation.resume] is called, so
 * there is one reader of the log rather than two able to disagree about which turn this is.
 */
data class Unfinished(
    val id: Long,
    val question: String,
    /** The names the app kept the pictures under, for whoever can turn those back into bytes. */
    val images: List<String> = emptyList(),
    /** What it had narrated, so the panel picks up where it stopped rather than from nothing. */
    val steps: List<String> = emptyList(),
    val rounds: Int = 0,
)

data class SourceRef(
    val url: String,
    val displayName: String,
    val explanation: String? = null,
    val tier: Tier,
    /** Spec §25 — the source's own wording, sliced from the retrieved text. Never the model's. */
    val quote: String? = null,
)

/**
 * Where the work around the conversation is done.
 *
 * The turn itself runs on the model the user chose - that choice is the whole of what the
 * professional/ordinary switch means to them. Everything else a turn needs done is bookkeeping:
 * reading which branch of a fork they took, sifting which of ten search results are worth
 * reading, writing a clarifying question, folding a session into a paragraph, filing what was
 * said. None of it is what anybody is waiting to read, and none of it should be answered at
 * conversation prices.
 *
 * If the key cannot reach it the call quietly becomes an ordinary one - see
 * `HydrogenClient.wrongModel`.
 */
internal const val SYSTEM_MODEL = "fish-system"

/**
 * Room for a short answer from a model that thinks first.
 *
 * These models put a thinking block in front of every reply, and the budget covers it. A tool
 * call is a few dozen tokens; the thinking ahead of it is hundreds, and when the budget runs out
 * mid-thought the block that would have carried the tool call is never written. The turn then
 * fails with "no classify_turn call in the reply", which reads like the model ignoring its
 * instructions and is nothing of the kind.
 *
 * The classifier ran on 256 and mostly worked, which is the worst way for this to be wrong: an
 * easy question thinks for ~500 characters and fits, a question worth thinking about does not.
 * Measured on the live model, streaming, forced to call `classify_turn`: "这张图上写的是什么"
 * at 256 gave one thinking block and `stop_reason: max_tokens`, and at 1024 gave thinking plus
 * the call.
 *
 * 1024 was then too small for the opposite reason: these calls moved to [SYSTEM_MODEL], which
 * thinks at length before it answers - measured at over four thousand characters on a question
 * with no tools at all - and a budget that stops it mid-thought loses the tool call at the end
 * of it. So this is that model's own ceiling, which it states itself when asked for more:
 * "field MaxTokens invalid, should be in [1, 65536]".
 *
 * A ceiling is not a spend. Unused budget is not billed, and every way of being wrong here
 * costs a whole turn.
 */
internal const val TOOL_BUDGET = 65_536

/**
 * And the whole window for the turn somebody is waiting on.
 *
 * Nothing is saved by capping this. A ceiling is not a spend - it is the point at which the
 * model is cut off mid-sentence, and every one of the ways that goes wrong here is expensive:
 * a routing call that runs out before it writes its tool call fails the turn outright, and an
 * answer that runs out arrives as a paragraph with its last clause missing. Against that, the
 * cost of a ceiling nobody reaches is zero.
 *
 * 131072 is what `fishball-flash` accepts - checked, with thinking on and with a forced tool,
 * because a limit the service rejects would fail every turn rather than just the long ones. It
 * is deliberately more than the side calls can have: `fish-system` answers
 * "field MaxTokens invalid, should be in [1, 65536]", and a service that names its own ceiling
 * gets taken at its word - see `HydrogenClient.capped`.
 */
internal const val MAIN_BUDGET = 131_072

/**
 * When the loop stops offering to look things up, and when it stops offering anything.
 *
 * There used to be one number here and it forced an `answer` call on the last round. That is a
 * worse failure than it sounds, and it is worth being precise about why. A turn traced round by
 * round went search, search, quote, quote, search, quote - six rounds of real work, none of it
 * wasted - and then hit the cap. What the model wrote under the forced call was
 * 「看起来搜索工具这边有点问题，没返回查询结果」, and then it cited two of the sources those
 * searches had returned. It had been interrupted, and it explained the interruption with the
 * only story available to it: that the tools had failed. Sixteen results were sitting in its own
 * context at the time. A deadline the model cannot see does not make it hurry, it makes it
 * confabulate.
 *
 * So nothing is forced now. Past [WIND_DOWN_AT] the looking-up tools are simply not offered any
 * more, which the model can see, and past [LAST_ROUND] no tools are offered at all - a model
 * with no tools writes prose, and prose off the back of twenty-four rounds of evidence is an
 * answer. The turn ends because there is nothing else for it to do, not because a counter fired.
 *
 * The numbers are a backstop against a loop that bills forever, not a research budget: an
 * ordinary question finishes in three or four rounds and never comes near them. A hard one is
 * allowed to take thirty.
 */
private const val WIND_DOWN_AT = 80
private const val LAST_ROUND = 100

/** Rounds in a row that said nothing and asked for nothing. See the loop for why. */
private const val IDLE_LIMIT = 3

/**
 * Rounds in a row in which every call was one the turn had already made.
 *
 * Three, which is two more than it takes to establish the pattern and few enough that a turn
 * cannot spend real money on it. Deliberately not one: the model asks for several things at once
 * and a round that repeats one call while making three new ones is not spinning, it is working -
 * only a round in which nothing at all was run counts here.
 */
private const val FUTILE_LIMIT = 3

/**
 * How much of one page goes back to the model, and how far in front of a `find` match it starts.
 *
 * Four thousand characters is a long article's worth of the part that matters. Whole pages were
 * the obvious alternative and are unaffordable: reading six of them would cost more window than
 * every search in the turn, and most of what is in them is navigation. The quote verifier still
 * gets the whole thing, so a window narrows what the model can quote, never what a quote is
 * checked against.
 */
private const val PAGE_WINDOW = 4_000
private const val PAGE_LEAD_IN = 600

/** Links off one page. Enough to find the way on, not the whole navigation bar. */
private const val LINKS_SHOWN = 25

/**
 * The provider's name for a reasoning block, in the one dialect this client speaks.
 *
 * A literal in two files was a literal that could disagree with itself; the reader and the
 * writer of these blocks now spell it the same way by construction.
 */
private const val THINKING = "thinking"

/**
 * Every name a reply might give its own reasoning.
 *
 * All three fish models produce `reasoning_content` natively - the OpenAI-side spelling - and
 * the proxy translates it into a `thinking` block for the Anthropic dialect this client speaks,
 * which is what arrives on the wire today. Reading only one spelling means a route that ever
 * stops translating drops the reasoning silently: the block still lands in [LlmContent.Opaque]
 * and still goes back out intact, but nothing is recorded on the turn, so the panel is empty
 * and the next question gets no working. Accepting all three costs a set lookup.
 *
 * Writing stays [THINKING] alone. This client speaks one dialect and should keep saying so.
 */
private val REASONING = setOf(THINKING, "reasoning_content", "reasoning")

/**
 * Lines of log per `read_log` call.
 *
 * Higher than a search's because these are short and cheap - one line each, already written,
 * no fetching - and because a day of conversation is not many turns. Low enough that "everything
 * we ever said" cannot arrive in one tool result.
 */
private const val LOG_HITS = 40

/**
 * Room for a structured checkpoint.
 *
 * Six sections of bullets over a whole session, and it was 1024 when it was one paragraph. A
 * summary cut off mid-section is worse than a short one: the sections are ordered, so what gets
 * lost is always the end - 现在在聊什么 and 要注意的, which are the two the next session most
 * needs.
 */
private const val COMPACT_BUDGET = 8_192

/** The stamp format, anchored at the start, for taking one back off. See `unstamped`. */
private val STAMPED = Regex("""^（\d{1,2}月\d{1,2}日 \d{2}:\d{2}）""")

/**
 * How much of one search comes back. Enough to choose from, not enough to drown in.
 *
 * Ten per query and thirty per call, up from six and twenty. The results are ranked now rather
 * than taken off the top of the engine's page, so the extra lines are the ones a cross-encoder
 * thought most relevant, not the next four the engine happened to list - and a search whose
 * seventh result was the one that mattered used to have it cut before the model saw it.
 */
private const val HITS_PER_QUERY = 10
private const val HITS_PER_CALL = 30

/** How many of a query's results are put in front of the reranker. A SearXNG page, roughly. */
private const val POOL_PER_QUERY = 30

/** How long a search waits on the reranker before going with engine order. See `ranked`. */
private const val RERANK_WAIT_MS = 8_000L

/**
 * Bridges the model's deltas onto the turn's progress channels.
 *
 * Both channels are opt-in and both default off, because the screen has one of each and a turn
 * makes more than one call. Streaming a half-formed decision into the answer slot would show
 * working notes as though they were the answer - that was always guarded.
 *
 * Thinking was not, and it produced the same bug one channel over. `MemoryBus.terms` runs on
 * the side model before the turn starts and it thinks out loud like everything else here, so its
 * reasoning streamed into the same field the main loop appends to, and the block under the
 * answer opened with the bus's working notes and only then reached the agent's own. Measured, on
 * a picture question: the block began 「用户问"这是什么"，但没提供具体的图片或上下文…… 让我用
 * recall_terms 工具来列出需要知道的事实」 - in Chinese, naming the tool - and the agent's actual
 * reasoning, in English, followed it. Confirmed again on a device with a text-only question, so
 * the leak was on every turn; a picture only made it conspicuous, because a recall call told
 * about a picture it cannot see goes round in circles about the picture not being there.
 *
 * So: the main loop opts into both, and every side call gets neither.
 */
internal fun TurnProgress.forward(
    answer: Boolean = false,
    thinking: Boolean = false,
): (LlmDelta) -> Unit = { delta ->
    when (delta) {
        is LlmDelta.Thinking -> if (thinking) thinking(delta.text)
        is LlmDelta.Text -> if (answer) answer(delta.text)
    }
}

// ---- small JSON readers ------------------------------------------------------------------

internal fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

internal fun JsonObject.bool(key: String): Boolean? =
    this[key]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() }

internal fun JsonObject.jsonArrayOrNull() = runCatching { jsonArray }.getOrNull()

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

internal fun JsonObject.ints(key: String): List<Int> =
    (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }
        .orEmpty()

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
    runCatching { jsonArray }.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.intOrNull(): Int? =
    runCatching { int }.getOrNull()



