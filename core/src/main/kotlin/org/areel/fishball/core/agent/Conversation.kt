package org.areel.fishball.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.areel.fishball.core.answer.AnswerPlan
import org.areel.fishball.core.answer.AnswerShape
import org.areel.fishball.core.copy.AgentPrompt
import org.areel.fishball.core.copy.UiCopy
import org.areel.fishball.core.llm.LlmClient
import org.areel.fishball.core.llm.LlmContent
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.memory.CitedSource
import org.areel.fishball.core.memory.ConversationTurn
import org.areel.fishball.core.memory.MemoryStore
import org.areel.fishball.core.memory.PreferenceFact
import org.areel.fishball.core.memory.PreferenceKind
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.memory.WorldFact
import org.areel.fishball.core.memory.WorldTtl
import org.areel.fishball.core.quote.QuoteRequest
import org.areel.fishball.core.quote.QuoteResult
import org.areel.fishball.core.quote.QuoteVerifier
import org.areel.fishball.core.quote.SourceText
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.session.SessionManager
import org.areel.fishball.core.trust.ClaimContext
import org.areel.fishball.core.trust.Evidence
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
    private val search: SearchGateway,
    private val registry: SourceRegistry,
    private val store: MemoryStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val engine = TurnEngine(registry, store)
    private val resolver = TrustResolver(registry)
    private val verifier = QuoteVerifier()
    private val sessions = SessionManager()

    private var session: Session? = null

    /**
     * A question the app asked and is waiting on. §15's fork and §16's clarification both put
     * the turn on hold, and the *next* thing the user types is an answer to that, not a new
     * question — so it has to be routed differently.
     */
    private var pending: Pending? = null

    private data class Pending(val original: TurnContext, val kind: Kind) {
        enum class Kind { FORK, CLARIFY, CONFIRM_PREFERENCES }
    }

    suspend fun ask(userText: String, narrate: (String) -> Unit = {}): Reply {
        val at = now()
        rollSession(at)
        store.appendTurn(
            ConversationTurn(store.nextId(), session!!.id, at, Speaker.USER, userText),
        )

        val ctx = context(userText, at) ?: return failure(narrate)
        val reply = run(engine.firstStep(ctx), ctx, narrate)

        store.appendTurn(
            ConversationTurn(
                id = store.nextId(),
                sessionId = session!!.id,
                at = now(),
                speaker = Speaker.ASSISTANT,
                text = reply.text,
                shape = reply.shape,
                sources = reply.sources.map {
                    CitedSource(it.url, it.displayName, it.explanation, it.tier, it.quote)
                },
            ),
        )
        return reply
    }

    // ---- routing ------------------------------------------------------------------------

    /** Builds the turn's context, resuming a held question if one is outstanding. */
    private suspend fun context(userText: String, at: Long): TurnContext? {
        val held = pending
        if (held != null) {
            pending = null
            return when (held.kind) {
                Pending.Kind.FORK -> held.original.copy(fork = forkAnswer(userText), now = at)

                // The clarifying answers matter to the search and to the prose, so they are
                // folded into the question rather than kept beside it.
                Pending.Kind.CLARIFY -> held.original.copy(
                    userText = "${held.original.userText}\n${userText}",
                    clarified = true,
                    now = at,
                )

                // §20 — whatever they said, the point was to unstick the aging preference.
                // Confirming every stale item is wrong; the honest reading is that the user
                // has now spoken to it, so the turn proceeds and the store is left alone for
                // the maintenance screen to settle.
                Pending.Kind.CONFIRM_PREFERENCES -> held.original.copy(now = at)
            }
        }
        return classify(userText, at)
    }

    private suspend fun classify(userText: String, at: Long): TurnContext? {
        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                messages = listOf(LlmMessage.user("${AgentPrompt.CLASSIFY}\n\n${userText}")),
                tools = listOf(Tools.classify),
                forceTool = Tools.CLASSIFY,
                maxTokens = 256,
            ),
        )
        val call = (result as? LlmResult.Ok)?.toolCalls?.firstOrNull() ?: return null
        val input = call.input
        return TurnContext(
            userText = userText,
            kind = turnKind(input.str("kind")),
            topic = topic(input.str("topic")),
            subject = input.str("subject").orEmpty(),
            diagnosticSelfQuestion = input.bool("diagnostic_self_question") ?: false,
            now = at,
        )
    }

    private suspend fun forkAnswer(userText: String): ForkAnswer {
        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                messages = listOf(LlmMessage.user("${AgentPrompt.FORK_READ}\n\n${userText}")),
                tools = listOf(Tools.fork),
                forceTool = Tools.FORK,
                maxTokens = 128,
            ),
        )
        val choice = (result as? LlmResult.Ok)?.toolCalls?.firstOrNull()?.input?.str("choice")
        // Defaulting to VENT: mistaking a request for advice as venting costs one extra turn,
        // mistaking venting as a request for advice talks over someone who wanted to be heard.
        return if (choice == "advice") ForkAnswer.WANT_ADVICE else ForkAnswer.VENT
    }

    // ---- the step machine ---------------------------------------------------------------

    private suspend fun run(step: Step, ctx: TurnContext, narrate: (String) -> Unit): Reply =
        when (step) {
            // §18 — the floor. Nothing below it runs: no search, no tiers, no citations.
            is Step.Crisis -> compose(AgentPrompt.CRISIS, ctx, plan = null, evidence = emptyList())

            Step.Chat -> compose(AgentPrompt.CHAT, ctx, plan = null, evidence = emptyList())

            Step.Listen -> compose(AgentPrompt.LISTEN, ctx, plan = null, evidence = emptyList())

            is Step.ComfortAndFork -> {
                pending = Pending(ctx, Pending.Kind.FORK)
                Reply(step.prompt)
            }

            is Step.Clarify -> {
                pending = Pending(ctx, Pending.Kind.CLARIFY)
                Reply(step.questions.joinToString("\n"))
            }

            is Step.ConfirmPreferences -> {
                pending = Pending(ctx, Pending.Kind.CONFIRM_PREFERENCES)
                Reply(step.stale.joinToString("\n") { UiCopy.confirmPreference(it.text) })
            }

            is Step.ServeFromMemory -> compose(
                "${AgentPrompt.Label.FROM_MEMORY}\n${step.fact.answer}",
                ctx,
                plan = null,
                evidence = emptyList(),
            )

            is Step.SearchLog -> {
                val turns = store.searchTurns(step.query, step.from, step.to)
                val digest = turns.joinToString("\n") { AgentPrompt.logLine(it.speaker == Speaker.USER, it.text) }
                compose(
                    "${AgentPrompt.Label.FROM_LOG}\n${digest.ifBlank { AgentPrompt.Label.NOTHING_LOGGED }}",
                    ctx,
                    plan = null,
                    evidence = emptyList(),
                )
            }

            is Step.Search -> {
                narrate(step.narration)
                val support = gather(step.queries, ctx, narrate)
                val next = engine.afterSupportSearch(
                    ctx = ctx,
                    support = support.evidence,
                    searchFailed = support.allFailed,
                    conflictInSupport = support.conflict,
                )
                runAfterSearch(next, ctx, support.evidence, narrate)
            }

            is Step.Answer -> answer(step.plan, ctx, emptyList())

            else -> Reply(AgentPrompt.Label.NOTHING_LOGGED)
        }

    private suspend fun runAfterSearch(
        step: Step,
        ctx: TurnContext,
        support: List<Evidence>,
        narrate: (String) -> Unit,
    ): Reply = when (step) {
        is Step.Disconfirm -> {
            // R6. Narrated out loud because a user watching a spinner deserves to know the app
            // is now trying to prove itself wrong, which is the least obvious thing it does.
            narrate(step.narration)
            val counter = gather(step.queries, ctx, narrate)
            answer(
                (engine.afterDisconfirmation(ctx, support, counter.evidence) as Step.Answer).plan,
                ctx,
                support + counter.evidence,
            )
        }

        is Step.Answer -> answer(step.plan, ctx, support)

        else -> answer(
            (engine.afterSupportSearch(ctx, support, false) as Step.Answer).plan,
            ctx,
            support,
        )
    }

    // ---- search -------------------------------------------------------------------------

    private class Gathered(
        val evidence: List<Evidence>,
        val allFailed: Boolean,
        val conflict: Boolean = false,
    )

    private suspend fun gather(
        queries: List<SearchQuery>,
        ctx: TurnContext,
        narrate: (String) -> Unit,
    ): Gathered {
        val responses = queries.map { search.search(it) }
        // §23 turns on this distinction: every request failing is not the same as finding
        // nothing, and only one of the two permits an answer.
        if (responses.all { it.failed }) return Gathered(emptyList(), allFailed = true)

        val claim = ClaimContext(topic = ctx.topic)
        val candidates = responses
            .filterNot { it.failed }
            .flatMap { it.hits }
            .distinctBy { it.url }
            .map { Evidence(it, resolver.resolve(it, claim)) }

        if (candidates.isEmpty()) return Gathered(emptyList(), allFailed = false)

        candidates.take(3).forEach { narrate(UiCopy.Narration.looked(it.resolution.displayName)) }

        val chosen = selectRelevant(ctx, candidates)
        return Gathered(chosen.first, allFailed = false, conflict = chosen.second)
    }

    /**
     * The model filters for relevance only. Tiering has already happened and is not up for
     * discussion — the prompt says so, and this ignores anything it might say about it.
     */
    private suspend fun selectRelevant(
        ctx: TurnContext,
        candidates: List<Evidence>,
    ): Pair<List<Evidence>, Boolean> {
        val listing = candidates.mapIndexed { i, e ->
            AgentPrompt.evidenceLine(
                index = i,
                name = e.resolution.attribution(),
                tier = e.resolution.tier.label,
                title = e.hit.title,
                snippet = e.hit.snippet,
                url = e.hit.url,
            )
        }.joinToString("\n\n")

        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                messages = listOf(
                    LlmMessage.user(
                        "${AgentPrompt.SELECT_EVIDENCE}\n\n" +
                            "${AgentPrompt.Label.QUESTION}${ctx.userText}\n\n$listing",
                    ),
                ),
                tools = listOf(Tools.select),
                forceTool = Tools.SELECT,
                maxTokens = 512,
            ),
        )
        val call = (result as? LlmResult.Ok)?.toolCalls?.firstOrNull()
            // A failed selection must not silently drop the evidence — falling back to
            // everything keeps the tier rules working on a full set rather than an empty one.
            ?: return candidates to false

        val picked = call.input["indices"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.intOrNull() }
            ?.mapNotNull { candidates.getOrNull(it) }
            .orEmpty()

        return (picked.ifEmpty { candidates }) to (call.input.bool("conflict") ?: false)
    }

    // ---- writing ------------------------------------------------------------------------

    private suspend fun answer(plan: AnswerPlan, ctx: TurnContext, evidence: List<Evidence>): Reply {
        val brief = buildString {
            appendLine(AgentPrompt.Label.REQUIREMENT)
            appendLine(AgentPrompt.guidanceFor(plan.shape))
            if (plan.medicalSplit) appendLine(AgentPrompt.MEDICAL_SPLIT)
            appendLine(AgentPrompt.brevity(plan.maxSentences))
            if (plan.attributions.isNotEmpty()) {
                appendLine("${AgentPrompt.Label.ATTRIBUTIONS}${plan.attributions.joinToString("、")}")
            }
            if (plan.confirmations.isNotEmpty()) {
                appendLine("${AgentPrompt.Label.CONFIRM}${plan.confirmations.joinToString("、")}")
            }
            if (evidence.isNotEmpty()) {
                appendLine()
                appendLine(AgentPrompt.Label.EVIDENCE)
                evidence.forEachIndexed { i, e ->
                    appendLine(
                        AgentPrompt.evidenceLine(
                            index = i,
                            name = e.resolution.attribution(),
                            tier = e.resolution.tier.label,
                            title = e.hit.title,
                            snippet = e.hit.snippet,
                            url = e.hit.url,
                        ),
                    )
                }
            }
        }
        return compose(brief, ctx, plan, evidence)
    }

    /**
     * The writing turn, including §25's quote loop.
     *
     * The model may call `quote` as often as it likes before calling `answer`. Each span is
     * checked against the retrieved text and comes back either as the source's own wording or
     * as a rejection it can act on. There is no branch here that lets an unverified quotation
     * through — the only thing that reaches [Reply] is what the verifier returned.
     */
    private suspend fun compose(
        brief: String,
        ctx: TurnContext,
        plan: AnswerPlan?,
        evidence: List<Evidence>,
    ): Reply {
        val sources = evidence.map { SourceText(it.hit.url, it.text) }
        val verified = mutableMapOf<String, String>()
        val messages = mutableListOf(
            LlmMessage.user(
                "${AgentPrompt.Label.QUESTION}${ctx.userText}\n\n$brief\n\n${AgentPrompt.COMPOSE}",
            ),
        )

        repeat(MAX_COMPOSE_ROUNDS) {
            val result = llm.complete(
                LlmRequest(
                    system = AgentPrompt.SYSTEM,
                    messages = messages,
                    tools = if (evidence.isEmpty()) listOf(Tools.answer) else listOf(Tools.quote, Tools.answer),
                    maxTokens = 2048,
                    temperature = 0.4,
                ),
            )
            if (result !is LlmResult.Ok) return Reply(UiCopy.SERVICE_UNAVAILABLE)

            val answerCall = result.toolCalls.firstOrNull { it.name == Tools.ANSWER }
            if (answerCall != null) {
                remember(answerCall.input, ctx, plan, evidence)
                return Reply(
                    text = answerCall.input.str("text").orEmpty().ifBlank { result.text },
                    shape = plan?.shape,
                    conflict = plan?.shape == AnswerShape.CONFLICT,
                    sources = evidence.map {
                        SourceRef(
                            url = it.hit.url,
                            displayName = it.resolution.displayName,
                            explanation = it.resolution.explanation,
                            tier = it.resolution.tier,
                            quote = verified[it.hit.url],
                        )
                    },
                )
            }

            val quoteCalls = result.toolCalls.filter { it.name == Tools.QUOTE }
            if (quoteCalls.isEmpty()) {
                // Prose with no tool call. Take it rather than burning another round — the
                // model has answered, it just did not use the hatch it was offered.
                return Reply(result.text, plan?.shape, conflict = plan?.shape == AnswerShape.CONFLICT)
            }

            messages += result.raw
            messages += LlmMessage(
                LlmMessage.Role.USER,
                quoteCalls.map { call ->
                    val url = call.input.str("url").orEmpty()
                    when (
                        val outcome = verifier.verify(QuoteRequest(url, call.input.str("text").orEmpty()), sources)
                    ) {
                        is QuoteResult.Verified -> {
                            verified[url] = outcome.quote.exact
                            LlmContent.ToolResult(call.id, outcome.quote.exact)
                        }

                        is QuoteResult.Rejected ->
                            LlmContent.ToolResult(call.id, outcome.feedback, isError = true)
                    }
                },
            )
        }
        return Reply(UiCopy.SERVICE_UNAVAILABLE)
    }

    // ---- memory -------------------------------------------------------------------------

    private fun remember(input: JsonObject, ctx: TurnContext, plan: AnswerPlan?, evidence: List<Evidence>) {
        input["world_fact"]?.let { it as? JsonObject }?.let { fact ->
            val question = fact.str("question").orEmpty()
            val answer = fact.str("answer").orEmpty()
            if (question.isNotBlank() && answer.isNotBlank()) {
                store.recordWorldFact(
                    WorldFact(
                        id = store.nextId(),
                        question = question,
                        answer = answer,
                        ttl = WorldTtl.parse(fact.str("ttl")),
                        tier = evidence.maxOfOrNull { it.resolution.tier } ?: Tier.LOW,
                        sources = plan?.sources.orEmpty(),
                        recordedAt = now(),
                    ),
                )
            }
        }
        input["about_user"]?.jsonArrayOrNull()?.forEach { element ->
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
                    recordedAt = now(),
                ),
            )
        }
    }

    // ---- sessions -----------------------------------------------------------------------

    private fun rollSession(at: Long) {
        // §8 — invisible to the user. Crossing the boundary bounds the prompt; it does not
        // clear anything they can see, and the log survives it untouched.
        when (val decision = sessions.decide(session, store.lastTurnAt(), at) { store.nextId() }) {
            is org.areel.fishball.core.session.SessionDecision.Start ->
                session = Session(decision.newSessionId, at)

            is org.areel.fishball.core.session.SessionDecision.RollOver ->
                session = Session(decision.newSessionId, at, bridge = decision.previous.bridge)

            is org.areel.fishball.core.session.SessionDecision.Continue -> Unit
        }
    }

    private fun failure(narrate: (String) -> Unit): Reply {
        narrate("")
        return Reply(UiCopy.SERVICE_UNAVAILABLE)
    }

    private companion object {
        /** Enough for a few rejected quotes; short enough that a loop cannot bill forever. */
        const val MAX_COMPOSE_ROUNDS = 5
    }
}

/** What one turn produced, in the terms the UI draws. */
data class Reply(
    val text: String,
    val shape: AnswerShape? = null,
    val sources: List<SourceRef> = emptyList(),
    val conflict: Boolean = false,
)

data class SourceRef(
    val url: String,
    val displayName: String,
    val explanation: String? = null,
    val tier: Tier,
    /** Spec §25 — the source's own wording, sliced from the retrieved text. Never the model's. */
    val quote: String? = null,
)

// ---- small JSON readers ------------------------------------------------------------------

private fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() }

private fun JsonObject.jsonArrayOrNull() = runCatching { jsonArray }.getOrNull()

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
    runCatching { jsonArray }.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.intOrNull(): Int? =
    runCatching { int }.getOrNull()

private fun turnKind(raw: String?): TurnKind = when (raw) {
    "advice" -> TurnKind.ADVICE
    "emotional" -> TurnKind.EMOTIONAL
    "crisis" -> TurnKind.CRISIS
    "log_query" -> TurnKind.LOG_QUERY
    "smalltalk" -> TurnKind.SMALLTALK
    else -> TurnKind.FACTUAL
}

private fun topic(raw: String?): Topic = when (raw) {
    "health" -> Topic.HEALTH
    "medication" -> Topic.MEDICATION
    "investment" -> Topic.INVESTMENT
    "safety" -> Topic.SAFETY
    else -> Topic.GENERAL
}

private fun preferenceKind(raw: String?): PreferenceKind = when (raw) {
    "medical_constant" -> PreferenceKind.MEDICAL_CONSTANT
    "profile" -> PreferenceKind.PROFILE
    "current_state" -> PreferenceKind.CURRENT_STATE
    // Unknown lands on the shortest life, matching PreferenceTtl.parse: a fact about a person
    // that expires too early costs a question, one that expires too late becomes a slow lie.
    else -> PreferenceKind.TRANSIENT
}
