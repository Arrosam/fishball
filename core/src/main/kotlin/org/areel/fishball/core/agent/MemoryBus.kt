package org.areel.fishball.core.agent

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.areel.fishball.core.catching
import org.areel.fishball.core.copy.AgentPrompt
import org.areel.fishball.core.llm.LlmClient
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.Effort
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.llm.Retrieval
import org.areel.fishball.core.memory.MemoryStore
import org.areel.fishball.core.memory.PreferenceFact
import org.areel.fishball.core.memory.PreferenceKind
import org.areel.fishball.core.memory.WorldFact
import org.areel.fishball.core.memory.WorldTtl
import org.areel.fishball.core.trust.Tier

/**
 * Memory, off the conversation's critical path.
 *
 * Everything about remembering runs here and nowhere else: reading what a question needs,
 * recording what the person said about themselves, keeping the answer that was just given, and
 * retiring whatever those contradict. The conversation holds no memory tool at all.
 *
 * Three things follow from that, and each one is the reason for a rule below.
 *
 * **It runs on the cheap model, always.** Memory work is bookkeeping — extract a phrase,
 * classify a fact, decide whether a sentence is worth keeping — and none of it is the thing the
 * user is waiting to read. Pinning it to the fast model keeps a professional-mode conversation
 * from paying professional-mode prices for filing.
 *
 * **What the user says about themselves is recorded when they say it.** Not after the answer.
 * A fact about a person is true whether or not the turn carrying it ever finished, and waiting
 * for the reply meant a stated allergy was lost whenever the search failed, the proxy was down,
 * or they closed the app while it was thinking.
 *
 * **Nothing here is awaited by the UI**, except [terms], which the answer genuinely depends on.
 * The rest is launched and forgotten. A person who asked a question should never wait on
 * filing, and should never be shown its failures either — every path here swallows its errors,
 * because a memory that did not get written is a worse answer next week, not a broken app now.
 *
 * **And filing waits its turn.** Recall runs beside the turn because the answer needs it;
 * filing runs between turns because nothing does. See [turnStarted].
 */
class MemoryBus(
    /**
     * Pinned to the fast model by the caller. Kept separate from the conversation's client so
     * that switching to professional mode does not silently move the filing there too.
     */
    private val llm: LlmClient,
    private val retrieval: Retrieval?,
    private val store: MemoryStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("memory-bus")),
) {

    /**
     * What is waiting to be filed.
     *
     * Unbounded and never blocking the caller: a turn hands work over and forgets it. Unbounded
     * is safe because the only producer is somebody typing, and a person cannot outrun a model
     * call by enough to matter - and dropping something they said about themselves to keep a
     * queue short would be the wrong trade in every case.
     */
    private val filings = Channel<Filing>(Channel.UNLIMITED)

    /** One piece of work for the loop. */
    private sealed interface Filing {
        data class Said(val text: String) : Filing
        data class Answered(
            val question: String,
            val answer: String,
            val tier: Tier,
            val sources: List<String>,
        ) : Filing

        /** Not work: a marker a caller can wait on. See [idle]. */
        data class Barrier(val reached: CompletableDeferred<Unit>) : Filing
    }

    /**
     * The loop, running beside the conversation for as long as the conversation exists.
     *
     * It used to be a coroutine launched per message, two of them per turn, each taking a lock
     * to keep out of the others' way. One consumer is the same serialisation without the lock,
     * and it is the structure the work actually has: a queue of things that happened, drained in
     * the order they happened, by something nobody is waiting for.
     *
     * Serialising is not incidental. Two harvests interleaving both read the same records to
     * decide what to retire, so one can delete the row the other is about to number, and the
     * second then retires whatever moved into that index. One consumer also means the store has
     * exactly one writer besides the turn itself.
     *
     * Every item is caught individually. One malformed reply from the filing model must not take
     * the loop down and silently stop the app remembering anything for the rest of the session -
     * which is exactly what a single `catching` around the whole loop would do.
     */
    init {
        scope.launch {
            for (filing in filings) {
                // Not while a turn is running. See [turnStarted].
                if (filing !is Filing.Barrier) turns.first { it == 0 }
                catching {
                    when (filing) {
                        is Filing.Said -> fileSaid(filing.text)
                        is Filing.Answered -> fileAnswered(filing)
                        is Filing.Barrier -> Unit
                    }
                }
                if (filing is Filing.Barrier) filing.reached.complete(Unit)
            }
        }
    }

    /** Turns in flight. The loop above only takes the next filing while this is zero. */
    private val turns = MutableStateFlow(0)

    /**
     * A turn is starting: hold the filing until it is over.
     *
     * Filing is a side bus, and a side bus yields. Two model calls ride on every turn - what the
     * person said about themselves, and the answer that was given - and both used to go out the
     * moment they were queued, beside the calls the person was actually waiting on. Nothing in
     * the app made the turn wait for them, but the proxy, the upstream behind it and the phone's
     * own radio are shared, and a request nobody is waiting on should not be competing with one
     * somebody is. So nothing is filed while a turn runs; what was queued during it is filed
     * the moment it ends, in order, while the person reads the answer.
     *
     * The cost is stated plainly: what someone says about themselves is now recorded when the
     * turn ends rather than when they say it. A turn that fails still ends, so a stated allergy
     * survives a dead search and a dead proxy as before; what it no longer survives is the app
     * being killed mid-turn, which it only ever survived when the filing call had already
     * finished. A filing already in flight when a turn starts is left to finish - it is one
     * call, it is bounded, and throwing it away would spend its tokens twice.
     */
    fun turnStarted() {
        turns.update { it + 1 }
    }

    fun turnEnded() {
        turns.update { (it - 1).coerceAtLeast(0) }
    }

    /**
     * Spec §10 — the facts a question needs, asked for before anything is looked up.
     *
     * What comes back decides what memory is searched with and therefore what the answer knows,
     * so the turn will not write its reply until this has landed - but it no longer waits here
     * before starting. See `Conversation.ask`. Empty lists on any failure, which costs a search
     * rather than a wrong answer.
     *
     * No progress channel, deliberately. This used to be handed the turn's, and the only thing
     * that ever came back down it was this call's own reasoning, which then rendered under the
     * answer as though the agent had thought it. Nothing the bus does is anybody's to watch.
     */
    suspend fun terms(
        question: String,
        context: List<LlmMessage>,
        /**
         * Whether the question came with a picture — as a fact, not as the picture.
         *
         * The bytes are not sent, and that is measured rather than chosen: `fish-system` handed
         * an image block does not refuse it, it hangs, and the call dies on the 120-second client
         * timeout with nothing to show for it. Twice, plainly and with the tool forced; the same
         * picture on the conversation's own model answers in 11s. Moving this call to that model
         * to buy vision would put memory work back on the model the user is paying conversation
         * prices for, which is the one thing this class exists to prevent.
         *
         * So it is told, in words, that there is a picture it cannot see. That keeps the half of
         * recall that does not need eyes - what to remember about *this person* comes out of the
         * sentence, not the photograph, and 「这个我能吃吗」 over a box of pills still has to find
         * 对青霉素过敏. And it stops the spiral: told there is a picture, the model no longer
         * concludes there is nothing to work with and say so four times over.
         */
        hasPicture: Boolean = false,
    ): Pair<List<String>, List<String>> {
        val ask = buildString {
            append(AgentPrompt.RECALL_TERMS)
            if (hasPicture) append("\n\n").append(AgentPrompt.RECALL_UNSEEN_PICTURE)
            append("\n\n").append(question)
        }
        val result = catching {
            llm.complete(
                LlmRequest(
                    // Not [AgentPrompt.SYSTEM]. Sixteen hundred characters about who 鱼丸 is
                    // and how to cite a source, in front of a request for two lists of noun
                    // phrases, is all cost and some harm: told it is an assistant, it behaves
                    // like one - live, a picture question sent it into a spiral about not being
                    // able to see the picture rather than naming what to look up.
                    system = AgentPrompt.PARSE_SYSTEM,
                    messages = context + LlmMessage.user(ask),
                    tools = listOf(Tools.recallTerms),
                    forceTool = Tools.RECALL,
                    maxTokens = TOOL_BUDGET,
                    model = SYSTEM_MODEL,
                    effort = Effort.HIGH,
                    call = org.areel.fishball.core.llm.Call.RECALL,
                ),
            )
        }.getOrNull()
        val input = (result as? LlmResult.Ok)?.toolCalls?.firstOrNull()?.input
            ?: return emptyList<String>() to emptyList()
        return input.strings("facts") to input.strings("about_user")
    }

    /**
     * Spec §9 and §19 — read this person's own words the moment they arrive.
     *
     * Launched, not awaited. It runs beside the turn it belongs to, so by the time the next
     * question is asked the fact is on disk whether or not this turn ever produced an answer.
     */
    fun noteUser(userText: String) {
        if (userText.isBlank()) return
        filings.trySend(Filing.Said(userText))
    }

    private suspend fun fileSaid(userText: String) {
        // What is already known that this could contradict. Similarity alone: a correction
        // names the thing it corrects, so there is nothing here a reranker would be better at,
        // and it would cost a round trip to ask.
        val held = nearby(userText, world = false)
        val input = ask(
            AgentPrompt.noteUserBrief(userText) + held.numbered(),
            Tools.noteUser,
            Tools.NOTE_USER,
            org.areel.fishball.core.llm.Call.NOTE_USER,
        ) ?: return

        input.ints("outdated_about_user").forEach { index ->
            held.personal.getOrNull(index)?.let { store.forgetPreference(it.fact.id) }
        }
        if (input.bool("nothing") == true) return
        keepPreferences(input)
    }

    /**
     * Spec §10 and §19 — keep the answer that was just given, if it is worth keeping.
     *
     * Also launched. The reply is already on screen; nobody is waiting on this, and the caller
     * does not have to remember to run it.
     */
    fun noteAnswer(question: String, answer: String, tier: Tier, sources: List<String>) {
        if (question.isBlank() || answer.isBlank()) return
        filings.trySend(Filing.Answered(question, answer, tier, sources))
    }

    private suspend fun fileAnswered(filing: Filing.Answered) {
        val question = filing.question
        val answer = filing.answer
        val tier = filing.tier
        val sources = filing.sources
        val held = nearby(question, personal = false)
        val input = ask(
            AgentPrompt.noteFactBrief(question, answer, tier.label, sources.size) +
                held.numbered(),
            Tools.noteFact,
            Tools.NOTE_FACT,
            org.areel.fishball.core.llm.Call.NOTE_FACT,
        ) ?: return

        input.ints("outdated_facts").forEach { index ->
            held.world.getOrNull(index)?.let { store.invalidateWorldFact(it.fact.id, now()) }
        }
        if (input.bool("nothing") == true) return
        keepWorldFact(input, tier, sources)
    }

    /**
     * Outstanding filing, for a caller that needs it finished — tests, and nothing else.
     *
     * A marker put on the end of the queue rather than a join on the loop, which never ends. The
     * loop is sequential, so reaching the marker means everything queued before it is done.
     */
    suspend fun idle() {
        val reached = CompletableDeferred<Unit>()
        if (filings.trySend(Filing.Barrier(reached)).isSuccess) reached.await()
    }

    /**
     * Stop filing. Called when the conversation this belongs to is replaced — switching model
     * builds a new one, and without this the old bus keeps its scope alive and would go on
     * writing to the store on behalf of a conversation nobody is having any more.
     */
    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    // ---- the calls ------------------------------------------------------------------------

    private suspend fun ask(
        brief: String,
        tool: org.areel.fishball.core.llm.LlmTool,
        force: String,
        call: org.areel.fishball.core.llm.Call,
    ): JsonObject? {
        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                messages = listOf(LlmMessage.user(brief)),
                tools = listOf(tool),
                forceTool = force,
                maxTokens = TOOL_BUDGET,
                model = SYSTEM_MODEL,
                effort = Effort.HIGH,
                call = call,
            ),
        )
        return (result as? LlmResult.Ok)?.toolCalls?.firstOrNull()?.input
    }

    /**
     * What memory holds near something just said, on similarity alone.
     *
     * Only the section that could actually be contradicted is fetched: a message about oneself
     * cannot retire a cached answer about ibuprofen, and offering it the chance is how a
     * correction about one thing deletes what was known about another.
     */
    private suspend fun nearby(
        text: String,
        world: Boolean = true,
        personal: Boolean = true,
    ): Remembered {
        val engine = retrieval ?: return Remembered.NOTHING
        val vector = engine.embed(listOf(text))
        val terms = listOf(text)
        return Remembered(
            world = if (!world) {
                emptyList()
            } else {
                store.recallWorldCandidates(terms, vector, now(), NEARBY_LIMIT)
                    .filter { it.similarity >= NEARBY_FLOOR }
            },
            personal = if (!personal) {
                emptyList()
            } else {
                store.recallPreferenceCandidates(terms, vector, NEARBY_LIMIT)
                    .filter { it.similarity >= NEARBY_FLOOR }
            },
        )
    }

    // ---- writing --------------------------------------------------------------------------

    private suspend fun keepPreferences(input: JsonObject) {
        (input["about_user"] as? JsonArray)?.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val text = item.str("text").orEmpty()
            if (text.isBlank()) return@forEach
            val kind = preferenceKind(item.str("kind"))
            store.recordPreference(
                PreferenceFact(
                    id = store.nextId(),
                    text = text,
                    kind = kind,
                    ttl = kind.defaultTtl,
                    // Embedded like a cached answer, and for the same reason: "药物过敏史"
                    // has to find 对青霉素过敏 without sharing a character with it.
                    embedding = retrieval?.embed(listOf(text))?.firstOrNull().orEmpty(),
                    recordedAt = now(),
                ),
            )
        }
    }

    private suspend fun keepWorldFact(input: JsonObject, tier: Tier, sources: List<String>) {
        val fact = input["world_fact"] as? JsonObject ?: return
        val question = fact.str("question").orEmpty()
        val answer = fact.str("answer").orEmpty()
        if (question.isBlank() || answer.isBlank()) return
        store.recordWorldFact(
            WorldFact(
                id = store.nextId(),
                question = question,
                answer = answer,
                ttl = WorldTtl.parse(fact.str("ttl")),
                // The tier the answer actually rested on, carried in from the turn. Hard-coded
                // LOW once, which meant a fact the 国家药品监督管理局 had stated outright came
                // back later marked as weakly sourced.
                tier = tier,
                sources = sources,
                // Embedded on the way in, so recall never has to embed the whole store.
                embedding = retrieval?.embed(listOf(question))?.firstOrNull().orEmpty(),
                recordedAt = now(),
            ),
        )
    }

    private fun preferenceKind(raw: String?): PreferenceKind = when (raw) {
        "medical_constant" -> PreferenceKind.MEDICAL_CONSTANT
        "profile" -> PreferenceKind.PROFILE
        "current_state" -> PreferenceKind.CURRENT_STATE
        // Unknown lands on the shortest life, matching PreferenceTtl.parse: a fact about a
        // person that expires too early costs a question, one that expires too late becomes a
        // slow lie.
        else -> PreferenceKind.TRANSIENT
    }

    private companion object {
        /**
         * Offered for retirement. Loose on purpose, and looser than the floor that decides what
         * gets stated back to the user as known: this number only decides what the model is
         * *shown* and may strike out, and the model reads the sentence that would strike it.
         * Precision comes from the model here, so the number should protect recall.
         */
        const val NEARBY_LIMIT = 4
        const val NEARBY_FLOOR = 0.5
    }
}
