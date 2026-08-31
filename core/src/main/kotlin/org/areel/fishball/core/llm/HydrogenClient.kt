package org.areel.fishball.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
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
    private val http: HttpClient = defaultClient(),
) : LlmClient {

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
                val chosen = pickModel(ids)
                if (chosen == null) {
                    // The key is fine and the proxy answered; it just is not serving either
                    // model this app is built for. Reporting that as a network problem would
                    // send the user off to check their wifi for a deployment fault.
                    KeyCheck.NoModel(ids)
                } else {
                    model = chosen
                    KeyCheck.Valid(ids, chosen)
                }
            }
        }
    } catch (e: Exception) {
        // Class name as well as message. Half of what goes wrong here throws with a null or
        // one-word message - UnknownHostException, SSLHandshakeException, SocketTimeoutException
        // - and the type is the part that says which of those it was.
        KeyCheck.Unreachable("${e::class.simpleName}: ${e.message ?: "no detail"}  @ $baseUrl")
    }

    override suspend fun complete(request: LlmRequest): LlmResult {
        if (model.isBlank()) {
            return LlmResult.Failed("no model selected; validate() first", retryable = false)
        }
        return try {
            val response: HttpResponse = http.post("${baseUrl.trimEnd('/')}/v1/messages") {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(body(request).toString())
            }
            if (!response.status.isSuccess()) {
                return LlmResult.Failed(
                    "HTTP ${response.status.value}: ${response.bodyAsText().take(200)}",
                    // 429 and 5xx are worth another go; a 400 will fail identically forever.
                    retryable = response.status.value == 429 || response.status.value >= 500,
                )
            }
            parse(Json.parseToJsonElement(response.bodyAsText()).jsonObject)
        } catch (e: Exception) {
            LlmResult.Failed("${e::class.simpleName}: ${e.message ?: "no detail"}", retryable = true)
        }
    }

    // ---- request ------------------------------------------------------------------------

    private fun body(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
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
        internal fun pickModel(ids: List<String>): String? {
            for (want in PREFERRED) {
                ids.firstOrNull { it.equals(want, ignoreCase = true) }?.let { return it }
                ids.firstOrNull { it.startsWith(want, ignoreCase = true) }?.let { return it }
            }
            return null
        }

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
