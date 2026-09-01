package org.areel.fishball.core.agent

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.areel.fishball.core.copy.AgentPrompt
import org.areel.fishball.core.llm.LlmClient
import org.areel.fishball.core.llm.LlmMessage
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
     * One piece of memory work at a time.
     *
     * Two turns in quick succession would otherwise have their harvests interleave, and both
     * read the same records to decide what to retire — so one can delete the row the other is
     * about to number, and the second retires whatever moved into that index. Serialising also
     * means the store only ever has one writer besides the turn itself.
     */
    private val gate = Mutex()

    /**
     * Spec §10 — the facts a question needs, asked for before anything is looked up.
     *
     * The one call here the turn waits on, because what comes back decides what memory is
     * searched with and therefore what the answer knows. It is small and it is on the fast
     * model; empty lists on any failure, which costs a search rather than a wrong answer.
     */
    suspend fun terms(
        question: String,
        context: List<LlmMessage>,
        progress: TurnProgress,
    ): Pair<List<String>, List<String>> {
        val result = runCatching {
            llm.complete(
                LlmRequest(
                    system = AgentPrompt.SYSTEM,
                    messages = context + LlmMessage.user(AgentPrompt.RECALL_TERMS + "\n\n" + question),
                    tools = listOf(Tools.recallTerms),
                    forceTool = Tools.RECALL,
                    maxTokens = 300,
                    stream = true,
                ),
                progress.forward(),
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
        scope.launch {
            runCatching {
                gate.withLock {
                    // What is already known that this could contradict. Similarity alone: a
                    // correction names the thing it corrects, so there is nothing here a
                    // reranker would be better at, and it would cost a round trip to ask.
                    val held = nearby(userText, world = false)
                    val input = ask(
                        AgentPrompt.noteUserBrief(userText) + held.numbered(),
                        Tools.noteUser,
                        Tools.NOTE_USER,
                    ) ?: return@withLock

                    input.ints("outdated_about_user").forEach { index ->
                        held.personal.getOrNull(index)?.let { store.forgetPreference(it.fact.id) }
                    }
                    if (input.bool("nothing") == true) return@withLock
                    keepPreferences(input)
                }
            }
        }
    }

    /**
     * Spec §10 and §19 — keep the answer that was just given, if it is worth keeping.
     *
     * Also launched. The reply is already on screen; nobody is waiting on this, and the caller
     * does not have to remember to run it.
     */
    fun noteAnswer(question: String, answer: String, tier: Tier, sources: List<String>) {
        if (question.isBlank() || answer.isBlank()) return
        scope.launch {
            runCatching {
                gate.withLock {
                    val held = nearby(question, personal = false)
                    val input = ask(
                        AgentPrompt.noteFactBrief(question, answer, tier.label, sources.size) +
                            held.numbered(),
                        Tools.noteFact,
                        Tools.NOTE_FACT,
                    ) ?: return@withLock

                    input.ints("outdated_facts").forEach { index ->
                        held.world.getOrNull(index)?.let { store.invalidateWorldFact(it.fact.id, now()) }
                    }
                    if (input.bool("nothing") == true) return@withLock
                    keepWorldFact(input, tier, sources)
                }
            }
        }
    }

    /** Outstanding filing, for a caller that needs it finished — tests, and nothing else. */
    suspend fun idle() {
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
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

    private suspend fun ask(brief: String, tool: org.areel.fishball.core.llm.LlmTool, force: String): JsonObject? {
        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                messages = listOf(LlmMessage.user(brief)),
                tools = listOf(tool),
                forceTool = force,
                maxTokens = 512,
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
