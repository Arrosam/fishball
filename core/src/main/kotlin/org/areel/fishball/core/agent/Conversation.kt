package org.areel.fishball.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.areel.fishball.core.answer.AnswerShape
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
import org.areel.fishball.core.memory.WorldRecall
import org.areel.fishball.core.quote.QuoteRequest
import org.areel.fishball.core.quote.QuoteResult
import org.areel.fishball.core.quote.QuoteVerifier
import org.areel.fishball.core.quote.SourceText
import org.areel.fishball.core.search.SearchGateway
import org.areel.fishball.core.search.SearchQuery
import org.areel.fishball.core.session.Session
import org.areel.fishball.core.session.SESSION_COMPACT_TOKENS
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
    /**
     * Memory, off to one side. Defaults to running on this conversation's own client so a test
     * can pass one thing; `:app` gives it a client pinned to the fast model, because filing
     * should not cost what answering costs.
     */
    val memory: MemoryBus = MemoryBus(llm, retrieval, store, now),
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

    /** What memory offered for this turn. Read by the writing step. */
    private var remembered: Remembered = Remembered.NOTHING

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
     * A field rather than a parameter because a turn is one call to [ask] at a time and half a
     * dozen functions sit between the two ends of it. It is rebuilt from the log at the top of
     * every turn, so nothing survives a turn that should not.
     */
    private var thread = mutableListOf<LlmMessage>()

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
        val at = now()
        rollSession(at)

        store.appendTurn(
            ConversationTurn(store.nextId(), session!!.id, at, Speaker.USER, userText),
        )

        // §9 — before anything else, and without waiting for it. What someone says about
        // themselves is true whether or not this turn ever produces an answer, and it used to
        // be lost whenever the search failed or they closed the app mid-thought.
        memory.noteUser(userText)

        attached = images
        lastFailure = null
        // Rebuilt from the log every turn, so nothing an earlier turn left behind survives.
        thread = priorTurns().toMutableList()
        // What is already known, before anything is looked up. It used to be gated on the
        // classifier calling the turn factual; there is no classifier now, and a conversation
        // that forgets what it was told because somebody asked casually is worse than one bus
        // call nobody was waiting on.
        remembered = recall(userText, at, progress)
        val reply = converse(userText, progress)

        // §10 — and the answer, once there is one. Launched, not awaited: the reply is
        // already on its way to the screen. The caller no longer has to remember to file.
        memory.noteAnswer(
            question = userText,
            answer = reply.text,
            tier = reply.sources.maxOfOrNull { it.tier } ?: Tier.LOW,
            sources = reply.sources.map { it.url },
        )
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
    private suspend fun converse(userText: String, progress: TurnProgress): Reply {
        // Insertion-ordered, so the numbering the model sees is stable across rounds - [3] in
        // round two is the same page it was in round one.
        val seen = LinkedHashMap<String, Evidence>()
        val verified = mutableMapOf<String, String>()

        val known = remembered.lines()
        thread += withImage(
            buildString {
                append(AgentPrompt.Label.QUESTION).append(userText)
                if (known.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine(AgentPrompt.Label.KNOWN)
                    known.forEach { appendLine(it) }
                }
                appendLine()
                append(AgentPrompt.WORK)
            },
        )

        // Rounds that produced neither a tool call nor a word. Nothing goes into the thread on
        // one of those, so the next round is handed the identical prompt and does the identical
        // nothing - the only way out is to stop counting on it.
        var idle = 0
        var round = 0
        while (true) {
            // Three states, and the model is never told to stop - only offered less to do with
            // the turn. Given no tools at all it writes prose, and prose is an answer.
            val closing = round >= LAST_ROUND || idle >= IDLE_LIMIT
            val winding = round >= WIND_DOWN_AT
            val result = llm.complete(
                LlmRequest(
                    system = systemWithBridge(),
                    messages = thread,
                    tools = when {
                        closing -> emptyList()
                        // Looking things up is what does not fit in the time left; deciding what
                        // to say still has to. Taking quote away here would mean the last thing
                        // it wrote before the deadline could not be cited.
                        winding -> listOf(Tools.quote, Tools.answer)
                        else -> listOf(Tools.search, Tools.quote, Tools.answer)
                    },
                    maxTokens = MAIN_BUDGET,
                    effort = Effort.MAX,
                    temperature = 0.4,
                ),
                progress.forward(answer = true),
            )
            if (result !is LlmResult.Ok) {
                return Reply(
                    UiCopy.SERVICE_UNAVAILABLE,
                    detail = (result as? LlmResult.Failed)?.reason,
                )
            }

            // Either hatch: the tool it is asked for, or prose with no tool call at all, which
            // is still an answer - and on the closing round it is the only thing there can be.
            val answered = result.toolCalls.firstOrNull { it.name == Tools.ANSWER }
            val written = answered?.input?.str("text").orEmpty()
                .ifBlank { if (result.toolCalls.isEmpty()) result.text else "" }
            if (written.isNotBlank()) {
                val sources = cite(seen.values.toList(), verified)
                return Reply(text = written, shape = shapeOf(sources), sources = sources)
            }
            // Nothing said and nothing asked for. On the closing round that is a model that has
            // run out of anything to say, and there is no further round that would change it.
            if (result.toolCalls.isEmpty()) {
                if (closing) {
                    return Reply(
                        UiCopy.SERVICE_UNAVAILABLE,
                        detail = "the model wrote neither an answer nor a tool call",
                    )
                }
                idle++
                round++
                continue
            }
            idle = 0

            thread += result.raw
            thread += LlmMessage(
                LlmMessage.Role.USER,
                result.toolCalls.map { call ->
                    when (call.name) {
                        Tools.SEARCH -> lookUp(call, seen, progress)
                        Tools.QUOTE -> checkQuote(call, seen, verified)
                        else -> LlmContent.ToolResult(call.id, "不认识的工具。", isError = true)
                    }
                },
            )
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

        val fresh = responses
            .filterNot { it.failed }
            .flatMap { it.hits.take(HITS_PER_QUERY) }
            .distinctBy { it.url }
            .filterNot { seen.containsKey(it.url) }
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
     */
    private fun shapeOf(sources: List<SourceRef>): AnswerShape? = when {
        sources.isEmpty() -> null
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
    private suspend fun recall(question: String, at: Long, progress: TurnProgress): Remembered {
        // No embedding model reachable: fall back to word overlap, which finds a cached answer
        // only when the question is asked in nearly the same words. A floor, not a search.
        val engine = retrieval ?: return Remembered(listOfNotNull(store.recallWorldFact(question, at)))

        // What the question needs known, rather than the question itself. On the fast model,
        // like everything else about memory.
        val wanted = memory.terms(question, priorTurns().takeLast(CLASSIFY_CONTEXT_TURNS), progress)
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
            personal = about.filter { it.similarity >= PREFERENCE_FLOOR }.take(KEPT_MEMORIES),
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
        return engine.rerank(question, world.map { it.fact.question + "。" + it.fact.answer })
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
        val sofar = store.turnsInSession(open.id)
        // Nothing has been said in this session, so there is nothing to fold and no reason to
        // start another one - the session it would open is the session it is already in.
        // Switching model twice in a row used to roll a fresh empty session each time and
        // announce a compaction that had not happened.
        if (sofar.isEmpty()) return false
        if (estimateTokens(sofar.joinToString("\n") { it.text }) < SESSION_COMPACT_TOKENS) {
            return false
        }

        begin(Session(store.nextId(), now(), bridge = summarise(sofar)))
        return true
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
                model = SYSTEM_MODEL,
                effort = Effort.HIGH,
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
private const val WIND_DOWN_AT = 24
private const val LAST_ROUND = 30

/** Rounds in a row that said nothing and asked for nothing. See the loop for why. */
private const val IDLE_LIMIT = 3

/** How much of one search comes back. Enough to choose from, not enough to drown in. */
private const val HITS_PER_QUERY = 6
private const val HITS_PER_CALL = 20

/**
 * Bridges the model's deltas onto the turn's progress channels.
 *
 * Thinking is always forwarded; the reply text only where there is a reply being written. The
 * classifier and the evidence filter also produce text, and streaming a half-formed decision
 * into the answer slot would show the user working notes as though they were the answer.
 */
internal fun TurnProgress.forward(answer: Boolean = false): (LlmDelta) -> Unit = { delta ->
    when (delta) {
        is LlmDelta.Thinking -> thinking(delta.text)
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



