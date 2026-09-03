package org.areel.fishball.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.areel.fishball.core.notCancellation

/**
 * The user's own Hydrogen proxy (llm.areel.org), over the Anthropic Messages shape.
 *
 * Hydrogen answers both dialects — `/v1/chat/completions` and `/v1/messages`, both behind the
 * same key. `/v1/messages` is the one used here because its tool-use contract is the stricter
 * of the two: tool arguments come back as a structured object rather than a string that has to
 * be parsed as JSON and can arrive malformed. Every decision this app asks the model to make
 * is a tool call, so that difference matters more than dialect familiarity.
 *
 * JSON is assembled by hand rather than through `@Serializable` classes. The message format is
 * recursive and polymorphic — content blocks inside messages, arbitrary schemas inside tools —
 * and modelling that with sealed serializers costs more code than it saves.
 */
class HydrogenClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String,
    /**
     * Set from [validate]'s answer. Empty means "not discovered yet" and [complete] will fail
     * loudly rather than guess a model id — a guess breaks silently the day the proxy's
     * catalogue changes, and it is the user's catalogue, not ours.
     */
    var model: String = "",
    /**
     * Named separately from the chat model because they are separate deployments on the proxy
     * and do not move together: the conversation switches between fast and pro, retrieval does
     * not switch at all.
     */
    private val embeddingModel: String = EMBEDDING_MODEL,
    private val rerankModel: String = RERANK_MODEL,
    /**
     * Called when a turn had to re-pick the model, so the caller can persist the new one.
     * Without it the recovery below would repeat on every single turn forever.
     */
    private val onModelChanged: (String) -> Unit = {},
    private val http: HttpClient = defaultClient(),
) : LlmClient, Retrieval {

    /**
     * Lists the catalogue, then *tries* each candidate before settling on one.
     *
     * The listing is a catalogue, not an entitlement. `fishball-pro` appears in /v1/models for
     * a key that is then refused it with `403 permission_error` on the first real call - so
     * choosing from the list alone produced a sign-in that succeeded followed by every single
     * turn failing, which is about the worst shape a bug can take: the gate says yes and the
     * product says no. One extra request at sign-in buys a model that is known to work.
     */
    override suspend fun validate(): KeyCheck = try {
        val response: HttpResponse = http.get("${baseUrl.trimEnd('/')}/v1/models") {
            authHeaders()
        }
        when {
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden -> KeyCheck.Rejected

            !response.status.isSuccess() ->
                KeyCheck.Unreachable("HTTP ${response.status.value}")

            else -> {
                val ids = modelIds(Json.parseToJsonElement(response.bodyAsText()).jsonObject)
                var refusedOnly = true
                var working: String? = null
                val tried = mutableListOf<String>()

                for (candidate in candidates(ids)) {
                    if (working != null) break
                    val (code, body) = probe(candidate)
                    if (code in 200..299) {
                        working = candidate
                    } else {
                        // 401/403 means "not yours"; anything else means something is actually
                        // wrong, and the two must not be reported as the same thing.
                        if (code != 401 && code != 403) refusedOnly = false
                        tried += "$candidate -> HTTP $code ${body.take(140)}"
                    }
                }

                val chosen = working
                when {
                    chosen != null -> {
                        model = chosen
                        KeyCheck.Valid(ids, chosen)
                    }
                    tried.isEmpty() || refusedOnly -> KeyCheck.NoModel(ids)
                    else -> KeyCheck.Unreachable(tried.joinToString("   "))
                }
            }
        }
    } catch (e: Exception) {
        e.notCancellation()
        // Class name as well as message. Half of what goes wrong here throws with a null or
        // one-word message - UnknownHostException, SSLHandshakeException, SocketTimeoutException
        // - and the type is the part that says which of those it was.
        KeyCheck.Unreachable("${e::class.simpleName}: ${e.message ?: "no detail"}  @ $baseUrl")
    }

    override suspend fun embed(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) return emptyList()
        return try {
            val response: HttpResponse = http.post("${baseUrl.trimEnd('/')}/v1/embeddings") {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", embeddingModel)
                        putJsonArray("input") { texts.forEach { add(it) } }
                    }.toString(),
                )
            }
            if (!response.status.isSuccess()) return emptyList()
            val data = Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["data"]?.jsonArray ?: return emptyList()
            val vectors = data.mapNotNull { entry ->
                (entry as? JsonObject)?.get("embedding")?.jsonArray
                    ?.map { it.jsonPrimitive.content.toFloat() }
            }
            // All or nothing. A short list would silently pair vectors with the wrong texts,
            // and a memory recalled against the wrong vector is worse than one not recalled.
            if (vectors.size == texts.size) vectors else emptyList()
        } catch (e: Exception) {
            e.notCancellation()
            emptyList()
        }
    }

    override suspend fun rerank(query: String, documents: List<String>): List<Scored> {
        if (documents.isEmpty()) return emptyList()
        val untouched = documents.indices.map { Scored(it, 0.0) }
        return try {
            val response: HttpResponse = http.post("${baseUrl.trimEnd('/')}/v1/rerank") {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", rerankModel)
                        put("query", query)
                        putJsonArray("documents") { documents.forEach { add(it) } }
                    }.toString(),
                )
            }
            if (!response.status.isSuccess()) return untouched
            val results = Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["results"]?.jsonArray ?: return untouched
            results.mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val index = row["index"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return@mapNotNull null
                val score = row["relevance_score"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                Scored(index, score)
            }.ifEmpty { untouched }
        } catch (e: Exception) {
            e.notCancellation()
            untouched
        }
    }

    /**
     * Whether this key may drive [candidate].
     *
     * Asked before switching rather than discovered afterwards: picking 专业模式 and having it
     * silently fall back on the next question is worse than being told it is not available.
     */
    suspend fun entitled(candidate: String): Boolean = probe(candidate).first in 200..299

    /** The cheapest real call there is, to find out whether this key may drive this model. */
    private suspend fun probe(candidate: String): Pair<Int, String> = try {
        val response: HttpResponse = http.post("${baseUrl.trimEnd('/')}/v1/messages") {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("model", candidate)
                    // Streamed like everything else: a model that answers this and nothing
                    // else is a model this app cannot use, whatever the entitlement says.
                    put("stream", true)
                    // Not 1. These models emit a thinking block before anything else, and a
                    // budget that cannot fit one fails for a reason that has nothing to do
                    // with entitlement.
                    put("max_tokens", 32)
                    putJsonArray("messages") {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "hi")
                                        },
                                    )
                                }
                            },
                        )
                    }
                }.toString(),
            )
        }
        response.status.value to response.bodyAsText()
    } catch (e: Exception) {
        e.notCancellation()
        0 to "${e::class.simpleName}: ${e.message ?: "no detail"}"
    }

    override suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit): LlmResult =
        complete(request, onDelta, recover = true)

    /**
     * [recover] guards the one retry. A model id is remembered between launches, and an
     * entitlement is not a property of the id — the key can lose access, or the id can have
     * been written by a build that chose it differently. Either way the symptom is every turn
     * failing 403 with no way back, because nothing re-opens the gate once it has been passed.
     * So a permission refusal re-runs discovery, tells the caller what to store, and tries once
     * more; anything else is reported as it stands.
     */
    private suspend fun complete(
        request: LlmRequest,
        onDelta: (LlmDelta) -> Unit,
        recover: Boolean,
    ): LlmResult {
        if (model.isBlank()) {
            return LlmResult.Failed("no model selected; validate() first", retryable = false)
        }
        /*
         * Streamed, always, whatever the caller asked for.
         *
         * Not a preference. The plain path is where the endpoints behind this proxy go quiet:
         * `fish-system` answers a perfectly ordinary non-streamed request with a 200 and zero
         * content blocks, and that is how the memory harvest was silently filing nothing - it
         * was one of only two calls in the app that did not stream. The models are stable when
         * asked to stream and unreliable when not, so there is one way of asking.
         */
        return streamed(request, onDelta, recover)
    }

    private fun refused(status: Int, body: String) = status == 403 && body.contains("permission")

    /**
     * Whether it was the *override* the service objected to, rather than the request.
     *
     * A side call asking for a model the key cannot reach should quietly become an ordinary
     * call, not a failed one. `fish-system` is listed by the catalogue and answers 404 "model
     * route not found" - listed but not wired - and an unentitled id answers 403; either way
     * the work still needs doing and the conversation's own model can do it.
     *
     * Deliberately not [repick]: that changes what the whole app runs on and writes the new id
     * down. One bookkeeping call finding a door locked is not a reason to move house.
     */
    /**
     * The model saying it cannot think.
     *
     * Which model each name points at is not ours, and it moves - a route was re-pointed twice
     * in one afternoon while this was being written. A turn should not die of that, so a model
     * that will not think is asked again without the request to, and answers the way it always
     * could.
     */
    private fun cannotThink(request: LlmRequest, body: String): Boolean =
        request.effort != null && body.contains("not supported")

    /**
     * The service naming a ceiling lower than what was asked for.
     *
     * `max_tokens` is set for the model the conversation runs on, and the models here disagree
     * about how high it may go - one takes 131072, another answers
     * "field MaxTokens invalid, should be in [1, 65536]". Rather than keeping a table of limits
     * that goes stale the next time a route is re-pointed, the refusal is read: it states the
     * ceiling, so the call is made again at exactly that. Once only - the retry asks for the
     * number the service itself named, so a second refusal is a different complaint.
     */
    private fun capped(request: LlmRequest, body: String): LlmRequest? {
        val limit = ceiling.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (limit <= 0 || limit >= request.maxTokens) return null
        return request.copy(maxTokens = limit)
    }

    private val ceiling = Regex("""should be in \[\s*\d+\s*,\s*(\d+)\s*]""")

    /**
     * A service that will not take the cacheable shape.
     *
     * This proxy fronts several backends and they do not agree about anything, so a system
     * block carrying a `cache_control` marker is offered rather than assumed. A 400 is a
     * complaint about the shape of the request, and the marker is the newest thing in that
     * shape, so it is the first thing given up - once, after which a second 400 is the real
     * complaint and gets reported.
     *
     * Checked after [wrongModel] and [capped], which read the body for what they are looking
     * for; this one only knows that something about the request was refused.
     */
    private fun uncacheable(request: LlmRequest, status: Int): Boolean =
        request.cache && status == 400

    private fun wrongModel(request: LlmRequest, status: Int, body: String): Boolean {
        if (request.model == null) return false
        return status == 404 || status == 403 || (status == 400 && body.contains("model"))
    }

    /** Re-runs discovery after a refusal. True when it landed somewhere new worth trying. */
    private suspend fun repick(): Boolean {
        val stale = model
        val recheck = validate()
        if (recheck !is KeyCheck.Valid || recheck.chosen == stale) return false
        onModelChanged(recheck.chosen)
        return true
    }

    /**
     * The same call, read as Server-Sent Events.
     *
     * Blocks are rebuilt from their deltas rather than merely forwarded, because the assistant
     * turn has to go back intact for the quote loop in §25 - thinking block included, which this
     * proxy streams as `thinking_delta` and with no signature anywhere: not on
     * `content_block_start`, and there is no `signature_delta` in the stream at all. So there is
     * nothing to carry, which for a while was read as there being nothing to worry about. There
     * was: the block goes back out intact and the model is shown it only sometimes - see `block`
     * for the counts and for what was tried about it. Tool arguments arrive as `input_json_delta`
     * fragments and are only valid JSON once the block closes, so they are parsed there and
     * nowhere earlier.
     */
    private suspend fun streamed(
        request: LlmRequest,
        onDelta: (LlmDelta) -> Unit,
        recover: Boolean,
    ): LlmResult {
        val blocks = sortedMapOf<Int, Block>()
        var failure: LlmResult.Failed? = null
        var refusedModel = false
        var wrongOverride = false
        var overLimit: LlmRequest? = null
        var thoughtless = false
        var plainly = false

        try {
            http.preparePost("${baseUrl.trimEnd('/')}/v1/messages") {
                authHeaders()
                header("Accept", "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(body(request, stream = true).toString())
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val text = response.bodyAsText()
                    wrongOverride = wrongModel(request, response.status.value, text)
                    plainly = uncacheable(request, response.status.value)
                    overLimit = capped(request, text)
                    thoughtless = cannotThink(request, text)
                    refusedModel = refused(response.status.value, text)
                    failure = LlmResult.Failed(
                        "HTTP ${response.status.value}: ${text.take(200)}",
                        retryable = response.status.value == 429 || response.status.value >= 500,
                    )
                } else {
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        val event = runCatching {
                            Json.parseToJsonElement(payload).jsonObject
                        }.getOrNull() ?: continue
                        consume(event, blocks, onDelta)
                    }
                }
            }
        } catch (e: Exception) {
            // The one that mattered: a stopped turn came back as
            // 「这会儿连不上」 rather than as a turn somebody stopped.
            e.notCancellation()
            return LlmResult.Failed(
                "${e::class.simpleName}: ${e.message ?: "no detail"}",
                retryable = true,
            )
        }

        if (wrongOverride) {
            return complete(request.copy(model = null), onDelta, recover)
        }
        overLimit?.let { return complete(it, onDelta, recover) }
        if (thoughtless) return complete(request.copy(effort = null), onDelta, recover)
        if (plainly) return complete(request.copy(cache = false), onDelta, recover)
        if (recover && refusedModel && repick()) {
            return complete(request, onDelta, recover = false)
        }
        failure?.let { return it }

        /*
         * A stream that succeeded and carried nothing.
         *
         * Not a shape the protocol has a name for: HTTP 200, a clean `message_stop`, and not one
         * content block in between. It is how this proxy reports a request the model refused -
         * a picture sent to a model without vision, thinking asked of a model that has none.
         *
         * It used to be retried without streaming, where the refusal comes back as a readable
         * 400. That recovery is gone: the plain path is not trustworthy enough to fall back to,
         * and pointing at it turned one empty answer into two. Reported instead, so the turn
         * says it failed rather than showing an empty bubble - and so the reason reaches the
         * log, which is the only place it can be seen at all.
         */
        if (blocks.isEmpty()) {
            return LlmResult.Failed(
                "the service returned an empty stream - a refusal it did not report",
                retryable = true,
            )
        }
        return assemble(blocks)
    }

    private fun consume(
        event: JsonObject,
        blocks: MutableMap<Int, Block>,
        onDelta: (LlmDelta) -> Unit,
    ) {
        val index = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        when (event["type"]?.jsonPrimitive?.content) {
            "content_block_start" -> {
                val start = event["content_block"]?.jsonObject ?: return
                blocks[index] = Block(
                    type = start["type"]?.jsonPrimitive?.content.orEmpty(),
                    id = start["id"]?.jsonPrimitive?.content.orEmpty(),
                    name = start["name"]?.jsonPrimitive?.content.orEmpty(),
                )
            }

            "content_block_delta" -> {
                val delta = event["delta"]?.jsonObject ?: return
                val block = blocks.getOrPut(index) { Block("") }
                when (delta["type"]?.jsonPrimitive?.content) {
                    "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.content?.let {
                        block.text.append(it)
                        onDelta(LlmDelta.Thinking(it))
                    }

                    "text_delta" -> delta["text"]?.jsonPrimitive?.content?.let {
                        block.text.append(it)
                        onDelta(LlmDelta.Text(it))
                    }

                    "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.content?.let {
                        block.json.append(it)
                        // The reply usually arrives as an `answer` tool call rather than as
                        // text, so without this the whole answer lands in one piece at the end
                        // and "streaming" would mean watching the model think and nothing else.
                        // Only the growing tail of the `text` argument is forwarded; the rest
                        // of the object is structure the user has no business seeing.
                        val grown = block.streamedText()
                        if (grown.isNotEmpty()) onDelta(LlmDelta.Text(grown))
                    }
                }
            }
        }
    }

    private fun assemble(blocks: Map<Int, Block>): LlmResult {
        val text = StringBuilder()
        val calls = mutableListOf<LlmContent.ToolUse>()
        val raw = mutableListOf<LlmContent>()

        for (block in blocks.values) {
            when (block.type) {
                "text" -> {
                    text.append(block.text)
                    raw += LlmContent.Text(block.text.toString())
                }

                "tool_use" -> {
                    val input = runCatching {
                        Json.parseToJsonElement(block.json.toString()).jsonObject
                    }.getOrDefault(JsonObject(emptyMap()))
                    val call = LlmContent.ToolUse(block.id, block.name, unwrap(input))
                    calls += call
                    // The *unwrapped* call goes into the replayed turn as well, and that is the
                    // half that matters. A model reading its own wrapped call back out of the
                    // thread takes it for the house style and wraps the next one too - traced
                    // live, one envelope in round 2 became a double envelope by round 24 and
                    // twenty rounds of 「工具调用格式有问题」 in between.
                    raw += call
                }

                // Rebuilt, not dropped, so the turn handed back is the turn that came out.
                else -> raw += LlmContent.Opaque(
                    buildJsonObject {
                        put("type", block.type)
                        put(block.type, block.text.toString())
                    },
                )
            }
        }
        return LlmResult.Ok(
            text = text.toString().trim(),
            toolCalls = calls,
            raw = LlmMessage(LlmMessage.Role.ASSISTANT, raw),
        )
    }

    /**
     * The tool input, with the envelope taken off if the model put one on.
     *
     * Some models on this proxy hand back `{"arguments": {"url": "..."}}` where the schema asked
     * for `{"url": "..."}` - inconsistently, the same model doing it on one call and not the
     * next. Whether that is the model or the route in front of it does not matter here: what
     * arrives has to be read, and the alternative is every tool handler in `:core` learning the
     * same trick separately.
     *
     * Left alone unless the object is a single key that is one of the known envelope names
     * wrapping another object. No tool in [org.areel.fishball.core.agent.Tools] takes a
     * parameter by any of those names, so a real argument cannot be mistaken for a wrapper -
     * and if one ever does, it will be a single-parameter tool called `arguments`, which is
     * reason enough to rename the parameter.
     */
    private fun unwrap(input: JsonObject): JsonObject {
        var out = input
        repeat(UNWRAP_DEPTH) {
            if (out.size != 1) return out
            val (key, value) = out.entries.first()
            if (key !in ENVELOPES) return out
            out = value as? JsonObject ?: return out
        }
        return out
    }

    private class Block(
        val type: String,
        val id: String = "",
        val name: String = "",
        val text: StringBuilder = StringBuilder(),
        val json: StringBuilder = StringBuilder(),
    ) {
        /** How much of the `text` argument has already been handed out. */
        private var emitted = 0

        /**
         * The part of `"text": "..."` that has arrived since last asked.
         *
         * Reads the half-written object directly instead of waiting for it to parse, because
         * the point is to show words while they are still being written. Only the one field is
         * read, and only up to the last character known to be complete — a trailing backslash
         * may be the front half of an escape, and emitting it would put a stray mark on screen
         * that the finished value does not contain.
         */
        fun streamedText(): String {
            val buffer = json
            val key = buffer.indexOf(TEXT_KEY)
            if (key < 0) return ""
            // Tolerant of whitespace around the colon: this is somebody else's serialiser and
            // `"text": "` is as legal as `"text":"`.
            var i = key + TEXT_KEY.length
            while (i < buffer.length && (buffer[i] == ' ' || buffer[i] == ':')) i++
            if (i >= buffer.length || buffer[i] != '"') return ""
            i++
            val out = StringBuilder()
            var safe = 0
            while (i < buffer.length) {
                val c = buffer[i]
                if (c == '\\') {
                    if (i + 1 >= buffer.length) break
                    when (val esc = buffer[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> Unit
                        'u' -> {
                            if (i + 5 >= buffer.length) break
                            val code = buffer.substring(i + 2, i + 6).toIntOrNull(16) ?: break
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> out.append(esc)
                    }
                    i += 2
                } else if (c == '"') {
                    break
                } else {
                    out.append(c)
                    i++
                }
                safe = out.length
            }
            if (safe <= emitted) return ""
            val grown = out.substring(emitted, safe)
            emitted = safe
            return grown
        }

        private companion object {
            const val TEXT_KEY = "\"text\""
        }
    }

    // ---- request ------------------------------------------------------------------------

    private fun body(request: LlmRequest, stream: Boolean = false): JsonObject = buildJsonObject {
        put("model", request.model ?: model)
        if (stream) put("stream", true)
        put("max_tokens", request.maxTokens)
        /*
         * Temperature goes with thinking, on this proxy.
         *
         * Anthropic's contract says otherwise - a thinking model samples its own reasoning, so
         * the caller is meant to stop steering and leave temperature at 1. The models here are
         * not Anthropic's, this proxy accepts the pair and honours both halves, and the
         * classifier is a decision that wants 0. So the rule is noted and not followed. Worth
         * revisiting the day a strict upstream sits behind the same endpoint.
         */
        put("temperature", request.temperature)
        /*
         * `output_config: {effort: ...}` - and none of the other three spellings.
         *
         * A top-level `reasoning_effort` is accepted and silently ignored, which is the worst of
         * them: the call succeeds and the model simply does not think. `thinking: {type:
         * enabled}` is Anthropic's own, and the models here are not Anthropic's - it comes back
         * 400, "not supported for this model", and on the streamed path that 400 is swallowed
         * into a well-formed empty message with no error in it at all. Measured across both
         * models, streamed and plain, with tools and forced tools: this is the one that works
         * everywhere.
         */
        request.effort?.let {
            putJsonObject("output_config") {
                put("effort", it.wire)
            }
        }
        /*
         * The prompt's stable head, marked so the service can keep it.
         *
         * Nothing was ever marked before this, and a prefix nobody marks is a prefix nobody
         * caches: the user's own capture of a reply showed `cachedInputTokens: 0` and
         * `cacheCreationInputTokens: 0` on a turn with four thousand tokens of prompt. Laying
         * the standing instructions out in front of the question was the necessary half of the
         * job and bought nothing on its own.
         *
         * One breakpoint, at the end of the system block. The prefix runs tools -> system ->
         * messages, so this one marker covers every tool schema as well - and the schemas are
         * five blocks of Chinese description, which is most of what a short turn sends.
         *
         * As an array of blocks rather than a bare string because that is the only shape a
         * `cache_control` marker can be attached to.
         */
        if (request.cache) {
            putJsonArray("system") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", request.system)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    },
                )
            }
        } else {
            put("system", request.system)
        }
        putJsonArray("messages") {
            request.messages.forEach { add(message(it)) }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.inputSchema)
                        },
                    )
                }
            }
            putJsonObject("tool_choice") {
                if (request.forceTool != null) {
                    put("type", "tool")
                    put("name", request.forceTool)
                } else {
                    put("type", "auto")
                }
            }
        }
    }

    private fun message(m: LlmMessage): JsonObject = buildJsonObject {
        put("role", if (m.role == LlmMessage.Role.USER) "user" else "assistant")
        putJsonArray("content") {
            m.content.forEach { add(block(it)) }
        }
    }

    private fun block(c: LlmContent): JsonObject = when (c) {
        is LlmContent.Text -> buildJsonObject {
            put("type", "text")
            put("text", c.text)
        }

        // Verified against the proxy before it was written: an Anthropic image block reaches
        // fishball-flash and it reads what is in the picture.
        is LlmContent.Image -> buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", c.mediaType)
                put("data", c.base64)
            }
        }

        is LlmContent.ToolUse -> buildJsonObject {
            put("type", "tool_use")
            put("id", c.id)
            put("name", c.name)
            put("input", c.input)
        }

        is LlmContent.ToolResult -> buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", c.toolUseId)
            put("content", c.content)
            if (c.isError) put("is_error", true)
        }

        /*
         * Handed back exactly as it arrived, including a `thinking` block - which this proxy
         * accepts and shows the model only sometimes. See `ThinkingRoundTripTest`: 2/40 plain and
         * 7/20 streamed, against 40/40 for the same words in a `text` block.
         *
         * Rewriting it into a labelled text block was tried, measured, and withdrawn, and the
         * reason is worth keeping because the fix looks obviously correct until you run it. The
         * model reads its own replayed assistant turns to learn what an assistant turn looks like
         * here - the same imitation that `assemble` guards against by unwrapping tool calls, where
         * one envelope in round 2 was a double envelope by round 24. Given a text block containing
         * 「以下是你刚才的思考」 followed by reasoning, it produced exactly that as an answer: the
         * label, several paragraphs of English reasoning, a literal `</think>`, then the Chinese
         * answer, all in one bubble in front of a non-technical user. Any label is still text in
         * an assistant turn, and any text in an assistant turn is a pattern to copy, so rewording
         * it does not help.
         *
         * If this is picked up again: the promising direction is the *user* turn, alongside the
         * tool results, where nothing is a template for assistant output - and the thing to do
         * first is find a backend that reproduces the leak, because the one this key usually
         * draws does not, and a fix that cannot be seen to work is how this got shipped broken.
         */
        is LlmContent.Opaque -> c.raw
    }

    // ---- response -----------------------------------------------------------------------

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        header("x-api-key", apiKey)
        header("anthropic-version", ANTHROPIC_VERSION)
        // The OpenAI-dialect half of the proxy authenticates this way. Sending both costs one
        // header and means a deployment that only wired up one of them still works.
        header("Authorization", "Bearer $apiKey")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://llm.areel.org"

        /** The proxy's own names for these. Not the chat catalogue, and not switchable. */
        const val EMBEDDING_MODEL = "embedding"
        const val RERANK_MODEL = "reranker"

        private const val ANTHROPIC_VERSION = "2023-06-01"

        /** What a tool input gets wrapped in when it gets wrapped. See `unwrap`. */
        private val ENVELOPES = setOf("arguments", "input", "parameters", "args")

        /** Live, it reached two. Three is room to be wrong about that without unpacking forever. */
        private const val UNWRAP_DEPTH = 3

        /**
         * Both dialects list models as `data[].id`, so this parses either without caring which
         * one answered.
         */
        internal fun modelIds(root: JsonObject): List<String> =
            (root["data"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.get("id")?.jsonPrimitive?.content
            }

        /**
         * The two models this app is deployed against, strongest first.
         *
         * Named rather than pattern-matched. The end user is never going to choose a model, and
         * this app leans on instruction-following hard enough — eight answer shapes, five tool
         * schemas, extractive quoting — that quietly running on whatever else the catalogue
         * happened to list would change the product without anyone deciding to.
         */
        val PREFERRED = listOf("fishball-pro", "fishball-flash")

        /**
         * Exact id first, then a prefix match, so a dated or suffixed id
         * (`fishball-pro-2026-08`) still resolves. Null means neither is on offer — which is a
         * deployment answer, not a fallback to be papered over.
         */
        internal fun candidates(ids: List<String>): List<String> = PREFERRED.mapNotNull { want ->
            ids.firstOrNull { it.equals(want, ignoreCase = true) }
                ?: ids.firstOrNull { it.startsWith(want, ignoreCase = true) }
        }

        /** The one that would be tried first. Entitlement still decides which is used. */
        internal fun pickModel(ids: List<String>): String? = candidates(ids).firstOrNull()

        private fun Iterable<*>?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
            (this as? JsonArray) ?: emptyList()

        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            // Errors are read off the status line rather than thrown, so a 401 can be told
            // apart from a dead network — the gate says different things for each.
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            install(HttpTimeout) {
                // Long, because a turn can involve a model reading several pages of snippets.
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
