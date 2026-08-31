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
import org.areel.fishball.core.llm.LlmDelta
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.llm.Retrieval
import org.areel.fishball.core.memory.CitedSource
import org.areel.fishball.core.memory.ConversationTurn
import org.areel.fishball.core.memory.MemoryStore
import org.areel.fishball.core.memory.PreferenceFact
import org.areel.fishball.core.memory.PreferenceKind
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.memory.WorldFact
import org.areel.fishball.core.memory.WorldRecall
import org.areel.fishball.core.memory.WorldTtl
import org.areel.fishball.core.quote.QuoteRequest
import org.areel.fishball.core.quote.QuoteResult
import org.areel.fishball.core.quote.QuoteVerifier
import org.areel.fishball.core.quote.SourceText
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.session.SessionManager
import org.areel.fishball.core.session.estimateTokens
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
    /**
     * Meaning-based recall. Null falls back to word overlap, which finds a cached answer only
     * when the question is asked in nearly the same words - useful as a floor, useless as the
     * only test.
     */
    private val retrieval: Retrieval? = null,
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

    /**
     * Why the last model call failed, for the turn that is about to report it.
     *
     * A field rather than a return type because the failure can happen three call-frames down
     * inside classification, where the only thing the caller learns is that it got no context
     * back. The alternative was threading a Result through every step of the machine to carry
     * a string that is only ever read at the end of a turn that already went wrong.
     */
    private var lastFailure: String? = null

    /** The turn just finished, so [harvest] can be asked about it after the answer is on screen. */
    private var lastExchange: Pair<String, String>? = null
    private var lastSources: List<String> = emptyList()

    private data class Pending(val original: TurnContext, val kind: Kind) {
        enum class Kind { FORK, CLARIFY, CONFIRM_PREFERENCES }
    }

    suspend fun ask(userText: String, progress: TurnProgress = TurnProgress.Silent): Reply {
        val at = now()
        rollSession(at)

        store.appendTurn(
            ConversationTurn(store.nextId(), session!!.id, at, Speaker.USER, userText),
        )

        lastFailure = null
        val base = context(userText, at, progress) ?: return failure()
        // §10 — only factual turns can be served from memory, so only they pay for the lookup.
        val ctx = if (base.kind == TurnKind.FACTUAL) {
            base.copy(recalled = recall(base.userText, at))
        } else {
            base
        }
        val reply = run(engine.firstStep(ctx), ctx, progress)

        lastExchange = userText to reply.text
        lastSources = reply.sources.map { it.url }
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
    private suspend fun context(userText: String, at: Long, progress: TurnProgress): TurnContext? {
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
        return classify(userText, at, progress)
    }

    private suspend fun classify(userText: String, at: Long, progress: TurnProgress): TurnContext? {
        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                // The tail, so a pronoun has something to point at: "那它呢" is a factual
                // question about whatever was last discussed, and unaccompanied it is a
                // question about nothing.
                messages = priorTurns().takeLast(CLASSIFY_CONTEXT_TURNS) +
                    LlmMessage.user("${AgentPrompt.CLASSIFY}\n\n${userText}"),
                tools = listOf(Tools.classify),
                forceTool = Tools.CLASSIFY,
                maxTokens = 256,
                stream = true,
            ),
            progress.forward(),
        )
        if (result is LlmResult.Failed) lastFailure = result.reason
        val call = (result as? LlmResult.Ok)?.toolCalls?.firstOrNull()
            ?: run {
                // Reached the model but got no classification back: a reply with no tool call
                // at all, which means the tool contract is not being honoured.
                if (lastFailure == null) lastFailure = "no ${Tools.CLASSIFY} call in the reply"
                return null
            }
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

    /**
     * Spec §10 — has this been answered before?
     *
     * Two stages, because they answer different questions. The vector pass finds things in the
     * same neighbourhood; the reranker decides whether the nearest of them is actually an answer
     * to *this* question, which cosine cannot tell you. Serving a stale near-miss as though it
     * were the answer is the failure this guards against, and it is worse than searching again.
     */
    private suspend fun recall(question: String, at: Long): WorldRecall? {
        val engine = retrieval ?: return store.recallWorldFact(question, at)

        val vector = engine.embed(listOf(question)).firstOrNull().orEmpty()
        val candidates = store.recallCandidates(question, vector, at)
        if (candidates.isEmpty()) return null

        val ranked = engine.rerank(question, candidates.map { it.fact.question })
        val best = ranked.firstOrNull() ?: return null
        if (best.score < RERANK_FLOOR) return null
        return candidates.getOrNull(best.index)
    }

    /**
     * Spec §10 and §20 — decide what to keep, once the answer is already on screen.
     *
     * Deliberately not part of [ask]. It is a whole extra model call and nobody should wait on
     * it to read their answer; the caller runs it afterwards. It is also asked as its own
     * narrow question rather than as fields on the answer tool, which is why memory now gets
     * written at all - the model replies in prose most turns and never reached those fields.
     */
    suspend fun harvest() {
        val (question, answer) = lastExchange ?: return
        if (answer.isBlank()) return

        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                messages = listOf(
                    LlmMessage.user("${AgentPrompt.HARVEST}\n\n问：$question\n答：$answer"),
                ),
                tools = listOf(Tools.remember),
                forceTool = Tools.REMEMBER,
                maxTokens = 512,
            ),
        )
        val input = (result as? LlmResult.Ok)?.toolCalls?.firstOrNull()?.input ?: return
        if (input.bool("nothing") == true) return
        keep(input)
    }

    private suspend fun keep(input: JsonObject) {
        (input["world_fact"] as? JsonObject)?.let { fact ->
            val question = fact.str("question").orEmpty()
            val answer = fact.str("answer").orEmpty()
            if (question.isNotBlank() && answer.isNotBlank()) {
                store.recordWorldFact(
                    WorldFact(
                        id = store.nextId(),
                        question = question,
                        answer = answer,
                        ttl = WorldTtl.parse(fact.str("ttl")),
                        tier = Tier.LOW,
                        sources = lastSources,
                        // Embedded on the way in, so recall never has to embed the whole store.
                        embedding = retrieval?.embed(listOf(question))?.firstOrNull().orEmpty(),
                        recordedAt = now(),
                    ),
                )
            }
        }
        (input["about_user"] as? kotlinx.serialization.json.JsonArray)?.forEach { element ->
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

    // ---- the step machine ---------------------------------------------------------------

    private suspend fun run(step: Step, ctx: TurnContext, progress: TurnProgress): Reply =
        when (step) {
            // §18 — the floor. Nothing below it runs: no search, no tiers, no citations.
            is Step.Crisis -> compose(AgentPrompt.CRISIS, ctx, null, emptyList(), progress)

            Step.Chat -> compose(AgentPrompt.CHAT, ctx, null, emptyList(), progress)

            Step.Listen -> compose(AgentPrompt.LISTEN, ctx, null, emptyList(), progress)

            is Step.ComfortAndFork -> {
                pending = Pending(ctx, Pending.Kind.FORK)
                Reply(step.prompt)
            }

            is Step.Clarify -> {
                val asked = clarifyingQuestions(ctx, step.questions, progress)
                if (asked.isEmpty()) {
                    // Nothing worth asking. Carrying on as though it had already been asked is
                    // the honest reading of §16: the rule is one bundled turn, not a compulsory
                    // one, and a clarifying question nobody needed is a turn spent not
                    // answering.
                    run(engine.firstStep(ctx.copy(clarified = true)), ctx.copy(clarified = true), progress)
                } else {
                    pending = Pending(ctx, Pending.Kind.CLARIFY)
                    Reply(asked.joinToString("\n"))
                }
            }

            is Step.ConfirmPreferences -> {
                pending = Pending(ctx, Pending.Kind.CONFIRM_PREFERENCES)
                Reply(step.stale.joinToString("\n") { UiCopy.confirmPreference(it.text) })
            }

            is Step.ServeFromMemory -> compose(
                "${AgentPrompt.Label.FROM_MEMORY}\n${step.fact.answer}",
                ctx,
                null,
                emptyList(),
                progress,
            )

            is Step.SearchLog -> {
                val turns = store.searchTurns(step.query, step.from, step.to)
                val digest = turns.joinToString("\n") { AgentPrompt.logLine(it.speaker == Speaker.USER, it.text) }
                compose(
                    "${AgentPrompt.Label.FROM_LOG}\n${digest.ifBlank { AgentPrompt.Label.NOTHING_LOGGED }}",
                    ctx,
                    null,
                    emptyList(),
                    progress,
                )
            }

            is Step.Search -> {
                progress.step(step.narration)
                val support = gather(step.queries, ctx, progress)
                val next = engine.afterSupportSearch(
                    ctx = ctx,
                    support = support.evidence,
                    searchFailed = support.allFailed,
                    conflictInSupport = support.conflict,
                )
                runAfterSearch(next, ctx, support.evidence, progress)
            }

            is Step.Answer -> answer(step.plan, ctx, emptyList(), progress)

            else -> Reply(AgentPrompt.Label.NOTHING_LOGGED)
        }

    private suspend fun runAfterSearch(
        step: Step,
        ctx: TurnContext,
        support: List<Evidence>,
        progress: TurnProgress,
    ): Reply = when (step) {
        is Step.Disconfirm -> {
            // R6. Narrated out loud because a user watching a spinner deserves to know the app
            // is now trying to prove itself wrong, which is the least obvious thing it does.
            progress.step(step.narration)
            val counter = gather(step.queries, ctx, progress)
            answer(
                (engine.afterDisconfirmation(ctx, support, counter.evidence) as Step.Answer).plan,
                ctx,
                support + counter.evidence,
                progress,
            )
        }

        is Step.Answer -> answer(step.plan, ctx, support, progress)

        else -> answer(
            (engine.afterSupportSearch(ctx, support, false) as Step.Answer).plan,
            ctx,
            support,
            progress,
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
        progress: TurnProgress,
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

        candidates.take(3).forEach { progress.step(UiCopy.Narration.looked(it.resolution.displayName)) }

        val chosen = selectRelevant(ctx, candidates, progress)
        return Gathered(chosen.first, allFailed = false, conflict = chosen.second)
    }

    /**
     * The model filters for relevance only. Tiering has already happened and is not up for
     * discussion — the prompt says so, and this ignores anything it might say about it.
     */
    private suspend fun selectRelevant(
        ctx: TurnContext,
        candidates: List<Evidence>,
        progress: TurnProgress,
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
                stream = true,
            ),
            progress.forward(),
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

    private suspend fun answer(
        plan: AnswerPlan,
        ctx: TurnContext,
        evidence: List<Evidence>,
        progress: TurnProgress,
    ): Reply {
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
        return compose(brief, ctx, plan, evidence, progress)
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
        progress: TurnProgress,
    ): Reply {
        val sources = evidence.map { SourceText(it.hit.url, it.text) }
        val verified = mutableMapOf<String, String>()
        // The turn is asked inside the conversation, not on its own. Without this the model
        // reads every question as the first one it has ever been asked, which is what made
        // follow-ups like "那它呢" answer about nothing.
        val messages = priorTurns().toMutableList()
        messages += LlmMessage.user(
            "${AgentPrompt.Label.QUESTION}${ctx.userText}\n\n$brief\n\n${AgentPrompt.COMPOSE}",
        )

        repeat(MAX_COMPOSE_ROUNDS) { round ->
            val lastRound = round == MAX_COMPOSE_ROUNDS - 1
            val result = llm.complete(
                LlmRequest(
                    system = systemWithBridge(),
                    messages = messages,
                    tools = if (evidence.isEmpty()) listOf(Tools.answer) else listOf(Tools.quote, Tools.answer),
                    // Free to quote as often as it likes, until the last round - where the
                    // choice is between a structured answer and no answer at all.
                    forceTool = if (lastRound) Tools.ANSWER else null,
                    maxTokens = 2048,
                    temperature = 0.4,
                    stream = true,
                ),
                progress.forward(answer = true),
            )
            if (result !is LlmResult.Ok) {
                return Reply(
                    UiCopy.SERVICE_UNAVAILABLE,
                    detail = (result as? LlmResult.Failed)?.reason,
                )
            }

            val answerCall = result.toolCalls.firstOrNull { it.name == Tools.ANSWER }
            if (answerCall != null) {
                return Reply(
                    text = answerCall.input.str("text").orEmpty().ifBlank { result.text },
                    shape = plan?.shape,
                    conflict = plan?.shape == AnswerShape.CONFLICT,
                    sources = cite(evidence, verified),
                )
            }

            val quoteCalls = result.toolCalls.filter { it.name == Tools.QUOTE }
            if (quoteCalls.isEmpty()) {
                // Prose with no tool call. Take it rather than burning another round — the
                // model has answered, it just did not use the hatch it was offered. The
                // citations come along regardless: they were verified before it wrote a word,
                // and dropping them here would strip the sources off exactly those answers
                // that took the trouble to quote.
                return Reply(
                    text = result.text,
                    shape = plan?.shape,
                    conflict = plan?.shape == AnswerShape.CONFLICT,
                    sources = cite(evidence, verified),
                )
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
                } + LlmContent.Text(AgentPrompt.SUBMIT_ANSWER),
            )
        }
        return Reply(
            UiCopy.SERVICE_UNAVAILABLE,
            detail = "gave up after $MAX_COMPOSE_ROUNDS rounds without an ${Tools.ANSWER} call",
        )
    }

    /**
     * Spec §16 — what to ask before advising, written for this question.
     *
     * The engine still decides *whether* a turn like this happens; the list it carries is a
     * fallback for when the model declines to write one. Empty means it saw nothing worth
     * asking, and the turn proceeds.
     */
    private suspend fun clarifyingQuestions(
        ctx: TurnContext,
        fallback: List<String>,
        progress: TurnProgress,
    ): List<String> {
        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                messages = priorTurns().takeLast(CLASSIFY_CONTEXT_TURNS) +
                    LlmMessage.user(AgentPrompt.CLARIFY_ASK + "\n\n" + ctx.userText),
                tools = listOf(Tools.clarify),
                forceTool = Tools.CLARIFY,
                maxTokens = 400,
                stream = true,
            ),
            progress.forward(),
        )
        val input = (result as? LlmResult.Ok)?.toolCalls?.firstOrNull()?.input ?: return fallback
        if (input.bool("enough") == true) return emptyList()
        val written = input["questions"]?.jsonArrayOrNull()
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return written.ifEmpty { fallback }
    }

    /** Spec §8's bridge, when there is one: the previous session folded into a paragraph. */
    private fun systemWithBridge(): String {
        val bridge = session?.bridge?.takeIf { it.isNotBlank() } ?: return AgentPrompt.SYSTEM
        return AgentPrompt.SYSTEM + "\n\n" + AgentPrompt.Label.BRIDGE + bridge
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

    private suspend fun rollSession(at: Long) {
        // §8 — invisible to the user. Crossing the boundary bounds the prompt; it does not
        // clear anything they can see, and the log survives it untouched.
        if (session == null) session = store.loadSession()
        val sofar = session?.let { store.turnsInSession(it.id) }.orEmpty()
        val size = estimateTokens(sofar.joinToString("\n") { it.text })

        when (
            val decision =
                sessions.decide(session, store.lastTurnAt(), at, size) { store.nextId() }
        ) {
            is org.areel.fishball.core.session.SessionDecision.Start ->
                begin(Session(decision.newSessionId, at))

            is org.areel.fishball.core.session.SessionDecision.RollOver -> {
                // Both stale and large. Everything said is still in the log; what rides forward
                // is a paragraph, so a back-reference still resolves without carrying the whole
                // conversation into every future prompt.
                val bridge = if (sessions.needsBridge(sofar.size)) summarise(sofar) else null
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
     * Fold the conversation so far into one paragraph and start again from it.
     *
     * Called when the model changes, as well as on the §8 rollover: a different model has not
     * read any of this, and handing it a transcript written by another one is worse context
     * than a summary of what the two of you actually settled.
     */
    suspend fun compact() {
        val open = session ?: store.loadSession() ?: return
        val sofar = store.turnsInSession(open.id)
        val bridge = if (sofar.isEmpty()) open.bridge else summarise(sofar)
        begin(Session(store.nextId(), now(), bridge = bridge))
    }

    private suspend fun summarise(turns: List<ConversationTurn>): String? {
        val transcript = turns.joinToString("\n") {
            AgentPrompt.logLine(it.speaker == Speaker.USER, it.text)
        }
        val result = llm.complete(
            LlmRequest(
                system = AgentPrompt.SYSTEM,
                messages = listOf(LlmMessage.user("${AgentPrompt.COMPACT}\n\n$transcript")),
                maxTokens = 1024,
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
        return store.turnsInSession(open.id)
            .dropLast(1)
            .map {
                if (it.speaker == Speaker.USER) LlmMessage.user(it.text)
                else LlmMessage.assistant(it.text)
            }
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

    /** For callers that only want the result — tests, and the log-replay path. */
    object Silent : TurnProgress
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
 * Bridges the model's deltas onto the turn's progress channels.
 *
 * Thinking is always forwarded; the reply text only where there is a reply being written. The
 * classifier and the evidence filter also produce text, and streaming a half-formed decision
 * into the answer slot would show the user working notes as though they were the answer.
 */
private fun TurnProgress.forward(answer: Boolean = false): (LlmDelta) -> Unit = { delta ->
    when (delta) {
        is LlmDelta.Thinking -> thinking(delta.text)
        is LlmDelta.Text -> if (answer) answer(delta.text)
    }
}

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
