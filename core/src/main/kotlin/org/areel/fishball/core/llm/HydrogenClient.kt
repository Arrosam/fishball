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
        if (request.stream) return streamed(request, onDelta, recover)
        return try {
            val response: HttpResponse = http.post("${baseUrl.trimEnd('/')}/v1/messages") {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(body(request).toString())
            }
            if (!response.status.isSuccess()) {
                val text = response.bodyAsText()
                if (recover && refused(response.status.value, text) && repick()) {
                    return complete(request, onDelta, recover = false)
                }
                return LlmResult.Failed(
                    "HTTP ${response.status.value}: ${text.take(200)}",
                    // 429 and 5xx are worth another go; a 400 will fail identically forever.
                    retryable = response.status.value == 429 || response.status.value >= 500,
                )
            }
            parse(Json.parseToJsonElement(response.bodyAsText()).jsonObject)
        } catch (e: Exception) {
            LlmResult.Failed("${e::class.simpleName}: ${e.message ?: "no detail"}", retryable = true)
        }
    }

    private fun refused(status: Int, body: String) = status == 403 && body.contains("permission")

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
     * turn has to go back intact for the quote loop in §25 - thinking block included, which
     * this proxy streams as `thinking_delta` and, usefully, with no signature to carry. Tool
     * arguments arrive as `input_json_delta` fragments and are only valid JSON once the block
     * closes, so they are parsed there and nowhere earlier.
     */
    private suspend fun streamed(
        request: LlmRequest,
        onDelta: (LlmDelta) -> Unit,
        recover: Boolean,
    ): LlmResult {
        val blocks = sortedMapOf<Int, Block>()
        var failure: LlmResult.Failed? = null
        var refusedModel = false

        try {
            http.preparePost("${baseUrl.trimEnd('/')}/v1/messages") {
                authHeaders()
                header("Accept", "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(body(request, stream = true).toString())
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val text = response.bodyAsText()
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
            return LlmResult.Failed(
                "${e::class.simpleName}: ${e.message ?: "no detail"}",
                retryable = true,
            )
        }

        if (recover && refusedModel && repick()) {
            return complete(request, onDelta, recover = false)
        }
        failure?.let { return it }
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
                    val call = LlmContent.ToolUse(block.id, block.name, input)
                    calls += call
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
        put("model", model)
        if (stream) put("stream", true)
        put("max_tokens", request.maxTokens)
        put("temperature", request.temperature)
        put("system", request.system)
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

        is LlmContent.Opaque -> c.raw
    }

    // ---- response -----------------------------------------------------------------------

    private fun parse(root: JsonObject): LlmResult {
        val blocks = root["content"]?.jsonArray ?: JsonArray(emptyList())
        val text = StringBuilder()
        val calls = mutableListOf<LlmContent.ToolUse>()
        val raw = mutableListOf<LlmContent>()

        for (element in blocks) {
            val block = element.jsonObject
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> {
                    val t = block["text"]?.jsonPrimitive?.content.orEmpty()
                    text.append(t)
                    raw += LlmContent.Text(t)
                }

                "tool_use" -> {
                    val call = LlmContent.ToolUse(
                        id = block["id"]?.jsonPrimitive?.content.orEmpty(),
                        name = block["name"]?.jsonPrimitive?.content.orEmpty(),
                        input = block["input"] as? JsonObject ?: JsonObject(emptyMap()),
                    )
                    calls += call
                    raw += call
                }

                // thinking, redacted_thinking, and whatever comes next. Not read, not shown,
                // and handed back untouched.
                else -> raw += LlmContent.Opaque(block)
            }
        }
        return LlmResult.Ok(
            text = text.toString().trim(),
            toolCalls = calls,
            raw = LlmMessage(LlmMessage.Role.ASSISTANT, raw),
        )
    }

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
