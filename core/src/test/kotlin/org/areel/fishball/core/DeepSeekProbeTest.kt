package org.areel.fishball.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Can `api.deepseek.com/anthropic` actually drive this app.
 *
 * Opt-in: without `DEEPSEEK_KEY` every arm here does nothing and passes, like [LiveSmokeTest]
 * and [CacheProbeTest]. Run it deliberately, and read the output rather than the pass count:
 *
 *     DEEPSEEK_KEY=sk-... ./gradlew :core:test --tests '*DeepSeekProbeTest*' -i
 *
 * ## Why this exists
 *
 * The custom-profile feature lets someone point the app at their own provider, and that design
 * is Anthropic-dialect-only. DeepSeek publishes an Anthropic-compatible endpoint, which makes
 * it the most likely thing anyone actually brings and so the profile worth documenting.
 * "Compatible" is a claim about a protocol, though, and this app does not use the protocol
 * generally — it leans its whole weight on five corners of it. Every structured decision is a
 * forced tool call, every call is streamed, the quote loop hands thinking blocks back, the
 * prefix is marked for caching, and the budgets sit near the ceiling. A provider can be
 * honestly Anthropic-compatible and fail any one of those.
 *
 * So this measures the five, an arm each, and reports what it found. It is not a pass/fail gate
 * on DeepSeek: most arms assert nothing, because "it does not think when asked this way" is a
 * finding to write down, not a broken test.
 *
 * ## Two rules taken from the probes that came before this one
 *
 * **The bytes are built here.** [ThinkingRoundTripTest] paid for this lesson: a probe that
 * assembles its arms as `LlmContent` and pushes them through `HydrogenClient` stops measuring
 * the service the moment the client changes, because then every arm sends the same bytes and
 * every arm passes. Nothing between these methods and the socket belongs to us.
 *
 * **A cache arm needs two calls.** A cache is written on the first request and read on the ones
 * after it, so a single call can only report a write — and a write proves the marker was
 * accepted, not that it will be honoured. See [CacheProbeTest], whose shape that arm copies.
 *
 * ## What a run costs
 *
 * Eight arms, on the order of fifteen live calls, all small — `max_tokens` is 32 to 2048
 * everywhere except the ceiling arm, which is refused rather than answered. It spends real
 * quota on somebody's key. That is the point: the alternative is shipping a profile format
 * against a compatibility table.
 */
class DeepSeekProbeTest {

    private val key: String? = System.getenv("DEEPSEEK_KEY")?.takeIf { it.isNotBlank() }

    // ---- the catalogue --------------------------------------------------------------------

    /**
     * Whether `/v1/models` answers at all — and this one decides how the gate has to be built.
     *
     * `HydrogenClient.validate` opens with a catalogue listing and picks its candidates out of
     * it. Anthropic-compatible front ends often implement `/v1/messages` and nothing else,
     * because the Messages protocol is what the SDKs need and a catalogue is not part of it.
     * If this arm 404s then sign-in for a custom profile cannot start from a listing: the
     * profile already names its three ids, so the gate must probe those directly and skip
     * discovery. That is a fork in the design rather than a detail, which is why it is first.
     */
    @Test
    fun `does the catalogue answer`() {
        val key = key ?: return skip()
        val connection = (URL("$BASE/v1/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("x-api-key", key)
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
        }
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().readText()
        println("  GET /v1/models -> HTTP $code")
        if (code !in 200..299) {
            println("    no catalogue: " + body.take(220))
            println("    => a custom profile must probe its own three ids, not discover them")
            return
        }
        val ids = runCatching {
            Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content }
        }.getOrNull().orEmpty()
        println("    ids: " + ids.joinToString(", ").ifBlank { "(none)" })
    }

    // ---- the load-bearing arm -------------------------------------------------------------

    /**
     * A streamed, forced tool call that comes back as a structured object.
     *
     * The one arm that asserts, because it is the whole app. Every decision FishBall asks a
     * model to make — which branch a question is, which results are worth reading, which span
     * to quote, what to file — is a `tool_choice: {type: "tool"}` call read off a stream, and a
     * provider that cannot do this cannot run the product at all. `/v1/messages` was chosen
     * over the chat dialect precisely because its tool arguments arrive as an object rather
     * than a string that has to be parsed and can arrive malformed, so what is checked is that
     * the input assembles into an object carrying the schema's required field — not merely that
     * a `tool_use` block appeared.
     *
     * Streamed rather than plain, because streaming is not a preference here: the plain path is
     * where the routes behind the existing proxy go quiet, and the client has one way of asking.
     * An arm that passed unstreamed would be measuring a path the app never takes.
     */
    @Test
    fun `a streamed forced tool call comes back structured`() {
        val key = key ?: return skip()
        val body = buildJsonObject {
            put("model", FLASH)
            put("stream", true)
            put("max_tokens", 256)
            put("temperature", 0.0)
            put("system", "你是一个分类器。只调用工具，不要写话。")
            putJsonArray("messages") { add(userTurn("布洛芬孕妇能吃吗？")) }
            putJsonArray("tools") { add(classifyTool()) }
            putJsonObject("tool_choice") {
                put("type", "tool")
                put("name", "classify_turn")
            }
        }

        val (code, events) = stream(key, body)
        println("  HTTP $code")
        report(events)

        assertTrue(code in 200..299, "forced tool call refused: HTTP $code ${errorText(events)}")
        val use = events.firstNotNullOfOrNull { event ->
            event["content_block"]?.jsonObject?.takeIf {
                it["type"]?.jsonPrimitive?.content == "tool_use"
            }
        }
        assertTrue(use != null, "no tool_use block in the stream; blocks were ${blockTypes(events)}")

        // Streamed tool arguments arrive as `input_json_delta` fragments and the block itself
        // starts empty, so an assembled object is what proves the contract, not the start event.
        val assembled = assembleToolInput(events)
        println("    assembled input -> $assembled")
        assertTrue(assembled != null, "tool input never assembled from input_json_delta")
        assertTrue(
            assembled["kind"] != null,
            "tool input did not carry the schema's required field: $assembled",
        )
    }

    // ---- thinking -------------------------------------------------------------------------

    /**
     * Which spelling makes it think, if any.
     *
     * Three, because there are three in the wild and the existing proxy accepts exactly one:
     * `output_config: {effort}` works there, Anthropic's own `thinking: {type: enabled}` comes
     * back 400, and a top-level `reasoning_effort` is accepted and silently ignored — which is
     * the worst of the three, because the call succeeds and the model simply does not think. So
     * a spelling only counts as working here when a thinking block actually appears in the
     * stream. An HTTP 200 proves nothing.
     *
     * The fourth arm asks for nothing, and it is the control: these models reason on their own,
     * so without it a run could report all three spellings working and mean none of them did.
     *
     * There is a strong prior going in, and it is the reason this arm matters more than it looks.
     * DeepSeek's compatibility table lists Anthropic's own `thinking` as supported, with
     * `budget_tokens` ignored — which is the exact spelling the existing proxy answers 400 to,
     * and the existing proxy wants `output_config.effort`, which is not in DeepSeek's table at
     * all. If that holds, the spelling is not a constant the client can hold: it is a property
     * of the provider, and the profile has to carry it. Confirming or killing that is what this
     * arm is for. It still asserts nothing.
     */
    @Test
    fun `which spelling makes it think`() {
        val key = key ?: return skip()
        val arms = listOf(
            "output_config.effort=high" to buildJsonObject {
                thinkingBase()
                putJsonObject("output_config") { put("effort", "high") }
            },
            "thinking.type=enabled" to buildJsonObject {
                thinkingBase()
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", 1024)
                }
            },
            "reasoning_effort=high" to buildJsonObject {
                thinkingBase()
                put("reasoning_effort", "high")
            },
            "(nothing asked — the control)" to buildJsonObject { thinkingBase() },
        )

        arms.forEach { (label, body) ->
            val (code, events) = stream(key, body)
            val thought = events.any {
                it["delta"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "thinking_delta"
            }
            println("  $label -> HTTP $code   thinking: " + if (thought) "yes" else "no")
            if (code !in 200..299) println("    refused: " + errorText(events))
        }
        println("    => the spelling to put in the client answered 200 AND thought when the control did not")
    }

    /**
     * Whether a thinking block survives being handed back.
     *
     * §25's quote loop replays the assistant turn intact, thinking block included, because the
     * model has to see what it decided before it is asked to correct a quote. The existing proxy
     * streams `thinking_delta` with no signature anywhere — not on `content_block_start`, and
     * with no `signature_delta` in the stream at all — so there is nothing to carry back, and
     * one backend behind it discards inbound thinking outright.
     *
     * Anthropic's own contract refuses an unsigned thinking block. If DeepSeek enforces that,
     * the replay 400s on every round after the first and this provider needs a strip-before-
     * replay path in the client. So the question is narrow: send one back and see.
     */
    @Test
    fun `does a thinking block survive being handed back`() {
        val key = key ?: return skip()

        val first = buildJsonObject {
            put("model", PRO)
            put("stream", true)
            put("max_tokens", 512)
            put("system", "简短回答。")
            putJsonArray("messages") { add(userTurn("三加四等于几？")) }
            putJsonObject("output_config") { put("effort", "high") }
        }
        val (code, events) = stream(key, first)
        println("  round one -> HTTP $code")
        val thinking = assembleThinking(events)
        val signature = events.firstNotNullOfOrNull {
            it["delta"]?.jsonObject?.get("signature")?.jsonPrimitive?.content
                ?: it["content_block"]?.jsonObject?.get("signature")?.jsonPrimitive?.content
        }
        println("    thinking captured: " + (thinking?.let { "${it.length} chars" } ?: "none"))
        println("    signature present: " + (signature?.let { "yes (${it.take(12)}…)" } ?: "no"))
        if (thinking == null) {
            println("    => nothing to hand back; the replay question does not arise here")
            return
        }

        val second = buildJsonObject {
            put("model", PRO)
            put("stream", true)
            put("max_tokens", 512)
            put("system", "简短回答。")
            putJsonArray("messages") {
                add(userTurn("三加四等于几？"))
                add(
                    buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", thinking)
                                    // Sent only when the service gave us one. Inventing a
                                    // signature would measure how it rejects forgeries.
                                    signature?.let { put("signature", it) }
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "七。")
                                },
                            )
                        }
                    },
                )
                add(userTurn("那再加一呢？"))
            }
        }
        val (replayCode, replayEvents) = stream(key, second)
        println("  round two (thinking replayed) -> HTTP $replayCode")
        if (replayCode !in 200..299) {
            println("    refused: " + errorText(replayEvents))
            println("    => this provider needs thinking stripped before replay")
        }
    }

    /**
     * Whether temperature is allowed alongside thinking.
     *
     * Anthropic's contract says no: a thinking model samples its own reasoning and the caller is
     * meant to leave temperature at 1. This app sends 0 for every decision, because a classifier
     * that improvises is a bug, and the existing proxy accepts the pair and honours both halves.
     * A provider that enforces the contract instead would refuse every routing call in the app —
     * which surfaces as "the product does not work" rather than as a temperature problem, so it
     * gets its own arm.
     */
    @Test
    fun `is temperature allowed alongside thinking`() {
        val key = key ?: return skip()
        listOf(0.0, 1.0).forEach { temperature ->
            val body = buildJsonObject {
                put("model", PRO)
                put("stream", true)
                put("max_tokens", 256)
                put("temperature", temperature)
                put("system", "只回答一个字。")
                putJsonArray("messages") { add(userTurn("说一个字")) }
                putJsonObject("output_config") { put("effort", "high") }
            }
            val (code, events) = stream(key, body)
            println("  temperature=$temperature + thinking -> HTTP $code")
            if (code !in 200..299) println("    refused: " + errorText(events))
        }
    }

    // ---- the shape of the request ----------------------------------------------------------

    /**
     * Whether marking the prefix buys anything here.
     *
     * Two calls, for the reason [CacheProbeTest] sets out: the first writes and only the ones
     * after it can read, so a single call reports a write and a write is not the question. The
     * system block is deliberately long — most services decline to cache a short prefix at all,
     * and a probe with one paragraph in it reports "no" for the wrong reason.
     *
     * A refusal is a finding rather than a failure. The client already gives the marker up on a
     * 400 and retries without it, so a provider that will not take the cacheable shape costs
     * money rather than correctness.
     *
     * DeepSeek's table says `cache_control` is *ignored* rather than refused, which is a third
     * outcome the arm has to be read for: no 400, no marker honoured, and cache hits appearing
     * anyway because the service caches prefixes automatically and prices them separately. That
     * would be the good ending — the discount is around thirtyfold — and it is invisible unless
     * the usage counters are read on the second call. Which is why there is a second call.
     */
    @Test
    fun `does marking the prefix get it cached`() {
        val key = key ?: return skip()
        val system = buildString {
            repeat(60) {
                appendLine(
                    "你是一个帮人查资料的助手。回答要先给结论，再说依据，句子里点明来源。" +
                        "第 $it 条：不确定的地方要明说，不要用模糊的话糊过去。",
                )
            }
        }
        listOf("first call (writes)", "second call (should read)").forEach { label ->
            val body = buildJsonObject {
                put("model", FLASH)
                put("max_tokens", 32)
                putJsonArray("system") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", system)
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        },
                    )
                }
                putJsonArray("messages") { add(userTurn("说一个字")) }
            }
            val (code, text) = post(key, body)
            println("  $label -> HTTP $code")
            if (code !in 200..299) {
                println("    refused: " + text.take(220))
                return@forEach
            }
            val usage = runCatching {
                Json.parseToJsonElement(text).jsonObject["usage"]?.jsonObject
            }.getOrNull()
            println("    usage: " + (usage?.let(::readable) ?: "none reported"))
        }
    }

    /**
     * What ceiling it names, and whether it names one in a shape the client can read.
     *
     * The budgets in this app sit near the top on purpose — a ceiling is not a spend, and every
     * way of being cut off mid-thought costs a whole turn. The routes behind the existing proxy
     * disagree about how high they go, so rather than keeping a table that goes stale the client
     * reads the refusal: "field MaxTokens invalid, should be in [1, 65536]" is parsed by
     * `HydrogenClient.capped` and the call remade at exactly that number.
     *
     * Two things measured. Whether 131072 and 65536 are accepted at all — those are `MAIN_BUDGET`
     * and `TOOL_BUDGET` — and, when they are not, whether the refusal states its limit in a form
     * that regex can find. A provider that refuses without naming a number needs its ceiling
     * written into the profile instead.
     */
    @Test
    fun `what ceiling does it name`() {
        val key = key ?: return skip()
        val ceiling = Regex("""should be in \[\s*\d+\s*,\s*(\d+)\s*]""")
        listOf(131_072, 65_536, 8_192).forEach { budget ->
            val body = buildJsonObject {
                put("model", FLASH)
                put("max_tokens", budget)
                put("system", "只回答一个字。")
                putJsonArray("messages") { add(userTurn("说一个字")) }
            }
            val (code, text) = post(key, body)
            println("  max_tokens=$budget -> HTTP $code")
            if (code !in 200..299) {
                println("    refused: " + text.take(220))
                val named = ceiling.find(text)?.groupValues?.get(1)
                println(
                    "    client-readable ceiling: " +
                        (named ?: "NO — `capped` cannot parse this; put the limit in the profile"),
                )
            }
        }
    }

    // ---- the open question ------------------------------------------------------------------

    /**
     * What the server-side web search actually hands back.
     *
     * Not a candidate for FishBall's search gateway — the app builds its own queries, the R6
     * counter-searches included, and a model-invoked tool searches what the model decided to
     * search. But one fact decides whether it could ever be wrapped as one, and it is worth
     * having measured rather than read off a blog: whether the results carry snippet text in
     * clear, or only an `encrypted_content` the client cannot read.
     *
     * `Conversation.ranked` scores `title + "\n" + snippet` and corroboration reads snippets, so
     * an encrypted-only result list means every hit must be fetched before it can be judged.
     * That is a different product shape, and this arm is how we learn which one is on offer.
     */
    @Test
    fun `what does the server-side web search hand back`() {
        val key = key ?: return skip()
        val body = buildJsonObject {
            put("model", FLASH)
            put("stream", true)
            put("max_tokens", 1024)
            put("system", "需要时可以联网查。")
            putJsonArray("messages") { add(userTurn("今天上海的天气怎么样？")) }
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        put("type", "web_search_20250305")
                        put("name", "web_search")
                        put("max_uses", 2)
                    },
                )
            }
        }
        val (code, events) = stream(key, body)
        println("  HTTP $code")
        if (code !in 200..299) {
            println("    refused: " + errorText(events))
            println("    => no server-side search on this endpoint")
            return
        }
        report(events)

        val results = events.firstNotNullOfOrNull { event ->
            event["content_block"]?.jsonObject
                ?.takeIf { it["type"]?.jsonPrimitive?.content == "web_search_tool_result" }
                ?.get("content")?.jsonArray
        }
        if (results == null) {
            println("    no web_search_tool_result block; blocks were " + blockTypes(events))
            return
        }
        println("    results: ${results.size}")
        results.firstOrNull()?.jsonObject?.let { first ->
            println("    fields on a result: " + first.keys.joinToString(", "))
            val cleartext = first.keys.any { it == "content" || it == "snippet" || it == "text" }
            println(
                "    snippet in clear: " + if (cleartext) {
                    "yes"
                } else {
                    "NO — encrypted_content only; reranking loses its input"
                },
            )
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private fun skip() {
        println("DeepSeekProbeTest skipped: set DEEPSEEK_KEY to run it")
    }

    /**
     * The base every thinking arm varies from, so the arms differ only in the spelling.
     *
     * A receiver function rather than a shared [JsonObject] because each spelling sits at a
     * different level of the request, and building one object and copying it would make the
     * arms differ in more than the thing being measured.
     */
    private fun JsonObjectBuilder.thinkingBase() {
        put("model", PRO)
        put("stream", true)
        put("max_tokens", 2048)
        put("system", "简短回答。")
        putJsonArray("messages") { add(userTurn("一加一等于几？为什么？")) }
    }

    private fun userTurn(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
    }

    /** A schema shaped like the real ones: a required field with a closed set of values. */
    private fun classifyTool(): JsonObject = buildJsonObject {
        put("name", "classify_turn")
        put("description", "判断这句话属于哪一类。")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("kind") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("factual")
                        add("chat")
                        add("advice")
                    }
                    put("description", "这句话的类别。")
                }
            }
            putJsonArray("required") { add("kind") }
        }
    }

    private fun blockTypes(events: List<JsonObject>): String = events
        .mapNotNull { it["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content }
        .joinToString(", ").ifBlank { "(none)" }

    private fun report(events: List<JsonObject>) {
        val types = events.mapNotNull { it["type"]?.jsonPrimitive?.content }
        val counted = types.groupingBy { it }.eachCount().entries
            .joinToString(" ") { (type, n) -> "$type×$n" }
        println("    events: " + counted.ifBlank { "(none)" })
        println("    blocks: " + blockTypes(events))
    }

    /** The tool arguments, rebuilt from their fragments the way the client has to rebuild them. */
    private fun assembleToolInput(events: List<JsonObject>): JsonObject? {
        val json = events
            .mapNotNull { it["delta"]?.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "input_json_delta" }
            .mapNotNull { it["partial_json"]?.jsonPrimitive?.content }
            .joinToString("")
        if (json.isBlank()) {
            // Some services send the whole input on the start event instead of as deltas. That
            // is still a structured object and still usable, so it counts.
            return events.firstNotNullOfOrNull {
                it["content_block"]?.jsonObject?.get("input") as? JsonObject
            }?.takeIf { it.isNotEmpty() }
        }
        return runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
    }

    private fun assembleThinking(events: List<JsonObject>): String? = events
        .mapNotNull { it["delta"]?.jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "thinking_delta" }
        .mapNotNull { it["thinking"]?.jsonPrimitive?.content }
        .joinToString("")
        .takeIf { it.isNotBlank() }

    /** Whatever the service said when it refused, from either an SSE frame or a plain body. */
    private fun errorText(events: List<JsonObject>): String = events
        .joinToString(" ") { it.toString() }
        .take(300)
        .ifBlank { "(no body)" }

    /** Every spelling of the cache counters these routes use, so none is missed. */
    private fun readable(usage: JsonObject): String = usage.entries
        .filter { (name, _) -> name.contains("token", ignoreCase = true) }
        .joinToString("  ") { (name, value) ->
            name + "=" + runCatching { value.jsonPrimitive.content }.getOrDefault("?")
        }

    /**
     * A streamed call, read as Server-Sent Events and handed back as parsed frames.
     *
     * A refusal does not arrive as SSE — it is a plain JSON error body on the error stream — so
     * that case is wrapped in a single frame rather than returned as an empty list, which would
     * be indistinguishable from a stream that carried nothing.
     */
    private fun stream(key: String, body: JsonObject): Pair<Int, List<JsonObject>> {
        val connection = open(key, body, stream = true)
        val code = connection.responseCode
        if (code !in 200..299) {
            val text = connection.errorStream?.bufferedReader()?.readText().orEmpty()
            val frame = runCatching { Json.parseToJsonElement(text).jsonObject }
                .getOrElse { buildJsonObject { put("raw", text.take(400)) } }
            return code to listOf(frame)
        }
        val frames = mutableListOf<JsonObject>()
        connection.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (!line.startsWith("data:")) return@forEach
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") return@forEach
                runCatching { Json.parseToJsonElement(payload).jsonObject }
                    .getOrNull()?.let { frames += it }
            }
        }
        return code to frames
    }

    private fun post(key: String, body: JsonObject): Pair<Int, String> {
        val connection = open(key, body, stream = false)
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return code to stream.bufferedReader().readText()
    }

    private fun open(key: String, body: JsonObject, stream: Boolean): HttpURLConnection =
        (URL("$BASE/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            // Both, the way the client sends both: one header costs nothing, and a deployment
            // that wired up only one of them still answers.
            setRequestProperty("x-api-key", key)
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            if (stream) setRequestProperty("Accept", "text/event-stream")
            outputStream.write(body.toString().toByteArray())
        }

    private companion object {
        /**
         * The endpoint as a profile would carry it — no trailing slash, `/v1/...` appended.
         *
         * Worth noticing that this is exactly the shape `HydrogenClient` builds URLs in, so a
         * profile whose base URL is this string needs no special case anywhere in the client.
         */
        const val BASE = "https://api.deepseek.com/anthropic"

        const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * The two chat ids, read from the environment because they move — and they already have.
         *
         * These are what a profile's `flash` and `pro` fields would hold. The defaults are the
         * ids DeepSeek's own model list carries; the previous pair, `deepseek-chat` and
         * `deepseek-reasoner`, went stale inside a week of this file being written, which is
         * the whole argument for the override and for the catalogue arm existing at all.
         *
         * There is a third, `deepseek-v4-flash-vision-exp`, and it is not idle trivia: §-level
         * attachments send Anthropic image blocks, so if the plain flash id cannot read a
         * picture then a profile needs a fourth model role rather than three. Point `DEEPSEEK_FLASH`
         * at it and re-run to find out.
         */
        val FLASH: String = System.getenv("DEEPSEEK_FLASH") ?: "deepseek-v4-flash"
        val PRO: String = System.getenv("DEEPSEEK_PRO") ?: "deepseek-v4-pro"
    }
}
