package org.areel.fishball.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.areel.fishball.core.copy.AgentPrompt
import org.areel.fishball.core.llm.Effort
import org.areel.fishball.core.llm.HydrogenClient
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.core.llm.LlmContent
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmResult
import org.areel.fishball.core.llm.LlmTool
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the model's own reasoning comes back on the second round, in bytes.
 *
 * Sibling of [RequestShapeTest] and written for the same reason: the thinking block is the one
 * piece of an assistant turn nothing in this app can read, so an argument about whether it
 * survives a round trip cannot be settled by reading the code that builds it. Every claim below
 * is either printed off a socket or printed off the live proxy.
 *
 * What it found. The block was on the wire the whole time - the socket capture shows
 * `{"type":"thinking","thinking":"..."}` sitting in the assistant turn of round two, exactly as
 * intended - and the proxy answered 200 without complaint. What it was not doing was reliably
 * reaching the model: a number planted in the reasoning comes back 2 times in 32 when it travels
 * as `thinking` and 32 times in 32 when the identical number travels as `text`. Not never, and
 * that distinction cost two rounds of review to get right - see the counting test for the shape
 * of the unreliability and for who can fix it. Every guess about *how* the block was malformed -
 * a missing `signature` above all - was answering the wrong question. See `HydrogenClient.block`.
 *
 * A warning about measuring any of this. The obvious probe - build the arms as `LlmContent` and
 * push them through the client - stops working the moment the client is fixed, because then every
 * arm sends the same bytes and every arm passes. One was written that way, it produced a green
 * result that read as "there was never a bug", and it is gone. `count how often each block shape
 * reaches the model` builds its own request bytes for that reason. Keep it that way.
 *
 * The live tests are opt-in on `HYDROGEN_KEY` and pass silently without it, like [LiveSmokeTest].
 * `round two carries the reasoning as text` needs no key and is the one that guards the fix.
 */
class ThinkingRoundTripTest {

    private val key: String? = System.getenv("HYDROGEN_KEY")?.takeIf { it.isNotBlank() }

    private companion object {
        /**
         * Calls per arm in the counting probe.
         *
         * Eight, not one, and the number is the whole point of that test existing: the first
         * attempt at this ran once per arm, read four numbers as a result, and was wrong about
         * what it had measured. Four arms at eight trials is 32 live calls - slow, and cheap
         * next to shipping a workaround for a bug that is not there.
         */
        const val TRIALS = 8

        /** Fewer, because by this point the plain arms have already separated cleanly. */
        const val STREAM_TRIALS = 5
    }

    // ---- what the proxy sends -------------------------------------------------------------

    /**
     * Every `data:` line of a real streamed reply, verbatim.
     *
     * The client rebuilds blocks out of these and the rebuild is only as good as the list of
     * event types it knows about, so the list has to come from the wire rather than from
     * Anthropic's documentation - this proxy is not Anthropic.
     */
    @Test
    fun `dump the raw stream for a thinking reply`() {
        val key = key ?: run {
            println("skipped: set HYDROGEN_KEY to run it")
            return
        }
        val model = runBlocking { discover(key) } ?: return
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", 2048)
            put("temperature", 0.4)
            putJsonObject("output_config") { put("effort", "max") }
            put("system", "你是鱼丸。")
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "先想一下再答：布洛芬和对乙酰氨基酚有什么区别？用一句话。")
                                },
                            )
                        }
                    },
                )
            }
        }
        println("---- raw SSE, thinking reply ----")
        val types = mutableSetOf<String>()
        sse(key, body) { line ->
            // Long thinking deltas are noise; what matters is which fields each event carries.
            println(line.take(400))
            runCatching {
                val e = Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
                types += e["type"]?.jsonPrimitive?.content.orEmpty()
                e["delta"]?.jsonObject?.get("type")?.jsonPrimitive?.content?.let { types += "delta:$it" }
            }
        }
        println("---- event types seen: $types ----")
    }

    /**
     * The same question, unstreamed, so the two shapes can be compared side by side.
     *
     * They agree: both return `{"type":"thinking","thinking":"..."}` as the first content block,
     * with no `signature` and no top-level `reasoning` field. So the streamed path is not the odd
     * one out, and the `reasoning` field belongs to the other dialect entirely - see below.
     */
    @Test
    fun `dump the plain body for a thinking reply`() {
        val key = key ?: run {
            println("skipped: set HYDROGEN_KEY to run it")
            return
        }
        val model = runBlocking { discover(key) } ?: return
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            put("temperature", 0.4)
            putJsonObject("output_config") { put("effort", "max") }
            put("system", "你是鱼丸。")
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "先想一下再答：布洛芬和对乙酰氨基酚有什么区别？用一句话。")
                                },
                            )
                        }
                    },
                )
            }
        }
        println("---- plain body ----")
        println(post(key, body).take(4000))
        println("---- end ----")
    }

    // ---- what the client sends back -------------------------------------------------------

    /**
     * Round two, off a socket, after a scripted round one that thought first.
     *
     * The script is the shape the dump above establishes, replayed at the client so the whole
     * path runs - stream parsed, blocks rebuilt, `result.raw` appended, tool result attached,
     * request re-serialised. What comes out is what a second round really contains.
     */
    @Test
    fun `print round two after a thinking block came back`() {
        val script = sseScript(withSignature = true)
        capture(script) { client ->
            val first = client.complete(
                LlmRequest(
                    system = "你是鱼丸。",
                    messages = listOf(LlmMessage.user("布洛芬常见的副作用是什么？")),
                    tools = listOf(searchTool),
                    maxTokens = 4096,
                    effort = Effort.MAX,
                ),
            )
            check(first is LlmResult.Ok) { "round one did not parse: $first" }
            println("---- blocks the client kept from round one ----")
            first.raw.content.forEach { println(it) }
            println("---- end ----")

            val thread = mutableListOf<LlmMessage>(
                LlmMessage.user("布洛芬常见的副作用是什么？"),
                first.raw,
                LlmMessage(
                    LlmMessage.Role.USER,
                    first.toolCalls.map { LlmContent.ToolResult(it.id, "[1] 胃肠道不适。") },
                ),
            )
            runCatching {
                client.complete(
                    LlmRequest(
                        system = "你是鱼丸。",
                        messages = thread,
                        tools = listOf(searchTool),
                        maxTokens = 4096,
                        effort = Effort.MAX,
                    ),
                )
            }
        }
    }

    /**
     * The same round trip against the live proxy, which is the only thing that can say whether
     * what the client sends is *accepted*. A block the service rejects and a block it silently
     * drops look identical from here until it answers.
     */
    @Test
    fun `send a thinking block back to the live proxy`() {
        val key = key ?: run {
            println("skipped: set HYDROGEN_KEY to run it")
            return
        }
        val model = runBlocking { discover(key) } ?: return
        val client = HydrogenClient(apiKey = key, model = model)
        runBlocking {
            val first = client.complete(
                LlmRequest(
                    system = "你是鱼丸。可以用工具查。",
                    messages = listOf(LlmMessage.user("布洛芬常见的副作用是什么？先查一下。")),
                    tools = listOf(searchTool),
                    maxTokens = 8192,
                    effort = Effort.MAX,
                ),
            )
            println("round one -> $first".take(1200))
            if (first !is LlmResult.Ok || first.toolCalls.isEmpty()) {
                println("no tool call in round one; nothing to round-trip")
                return@runBlocking
            }
            println("---- blocks kept ----")
            first.raw.content.forEach { println(it.toString().take(300)) }

            val second = client.complete(
                LlmRequest(
                    system = "你是鱼丸。可以用工具查。",
                    messages = listOf(
                        LlmMessage.user("布洛芬常见的副作用是什么？先查一下。"),
                        first.raw,
                        LlmMessage(
                            LlmMessage.Role.USER,
                            first.toolCalls.map {
                                LlmContent.ToolResult(it.id, "[1] 常见：胃肠道不适、消化道出血。")
                            },
                        ),
                    ),
                    tools = listOf(searchTool),
                    maxTokens = 8192,
                    effort = Effort.MAX,
                ),
            )
            println("round two -> $second".take(1500))
        }
    }

    /**
     * The regression: round two must carry round one's reasoning somewhere the model reads.
     *
     * Asserted off the socket rather than off `result.raw`, because `raw` was right the whole
     * time the bug existed - the block was in it, and in the request, and the model still never
     * saw it. The only shape that counts is the one on the wire, and for this proxy that means a
     * `text` block. A `thinking` block here would be the bug returning.
     */
    @Test
    fun `round two carries the reasoning as text`() {
        var second: JsonObject? = null
        capture(sseScript(withSignature = false), onRequest = { round, body ->
            if (round == 2) second = body
        }) { client ->
            val first = client.complete(
                LlmRequest(
                    system = "你是鱼丸。",
                    messages = listOf(LlmMessage.user("布洛芬常见的副作用是什么？")),
                    tools = listOf(searchTool),
                    maxTokens = 4096,
                ),
            )
            check(first is LlmResult.Ok)
            runCatching {
                client.complete(
                    LlmRequest(
                        system = "你是鱼丸。",
                        messages = listOf(
                            LlmMessage.user("布洛芬常见的副作用是什么？"),
                            first.raw,
                            LlmMessage(
                                LlmMessage.Role.USER,
                                first.toolCalls.map { LlmContent.ToolResult(it.id, "[1] 胃肠道不适。") },
                            ),
                        ),
                        tools = listOf(searchTool),
                        maxTokens = 4096,
                    ),
                )
            }
        }

        val assistant = second!!["messages"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["role"]?.jsonPrimitive?.content == "assistant" }
        val blocks = assistant["content"]!!.jsonArray.map { it.jsonObject }
        val types = blocks.map { it["type"]?.jsonPrimitive?.content }

        assertFalse(
            types.contains("thinking"),
            "a thinking block went back out; this proxy strips those. blocks=$types",
        )
        val carried = blocks.filter { it["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("\n") { it["text"]?.jsonPrimitive?.content.orEmpty() }
        assertTrue(
            carried.contains("先查权威来源"),
            "round one's reasoning did not reach round two: $carried",
        )
        assertTrue(
            carried.contains(AgentPrompt.Label.EARLIER_THINKING),
            "the reasoning went back unlabelled, so it reads as something said to the user",
        )
        assertTrue(
            types.contains("tool_use"),
            "the tool call did not survive alongside the reasoning: $types",
        )
    }

    /**
     * The other dialect, for comparison - and the answer to where `reasoning` comes from.
     *
     * A captured body doing the rounds had the reasoning under `reasoning` next to `content`,
     * which is nothing like what `/v1/messages` returns, and the worry was that the app had been
     * reasoned about in the wrong dialect the whole time.
     *
     * It had not. That shape is `/v1/chat/completions`, and only when thinking is asked for the
     * OpenAI way: `reasoning_effort: high` produces `message.reasoning` alongside
     * `message.content`, while `output_config` - the spelling `/v1/messages` needs - is ignored
     * here and comes back `reasoning_tokens: 0` with no reasoning at all. Neither concerns this
     * client, which only ever speaks Messages, where reasoning is a `thinking` content block on
     * both the streamed and the plain path.
     */
    @Test
    fun `dump the openai dialect for a thinking reply`() {
        val key = key ?: run {
            println("skipped: set HYDROGEN_KEY to run it")
            return
        }
        val model = runBlocking { discover(key) } ?: return
        // Both spellings, because the two dialects ask for thinking differently and a reply with
        // `reasoning_tokens: 0` in it has not answered the question - it just did not think.
        for (asking in listOf("output_config", "reasoning_effort", "none")) {
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", 2048)
                when (asking) {
                    "output_config" -> putJsonObject("output_config") { put("effort", "max") }
                    "reasoning_effort" -> put("reasoning_effort", "high")
                }
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "先想一下再答：布洛芬和对乙酰氨基酚有什么区别？用一句话。")
                        },
                    )
                }
            }
            val c = URL("https://llm.areel.org/v1/chat/completions").openConnection()
                .let { it as HttpURLConnection }
                .apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 20_000
                    readTimeout = 180_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                    outputStream.write(body.toString().toByteArray())
                }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            println("---- /v1/chat/completions, thinking asked for via $asking ----")
            println("HTTP ${c.responseCode}")
            println(stream.bufferedReader().readText().take(2500))
        }
        println("---- end ----")
    }

    /**
     * Which block shapes in an assistant turn the model can actually read, counted.
     *
     * There was a probe before this one that drove the same four arms through [HydrogenClient],
     * and it had to be deleted rather than fixed. Once `block()` learned to rewrite a thinking
     * block into text, its arms stopped differing - every arm sent text, every arm passed - so it
     * reported the fix working and looked exactly like the bug being absent. A reviewer ran it,
     * read 「mostly FOUND」 and reasonably concluded the whole premise was invented. A test that
     * cannot fail is bad; a test that passes for the opposite of its stated reason is worse.
     *
     * So this one owns its bytes. The body is built here and posted raw, with no client in the
     * path, which means the arms differ by exactly one block shape whatever the client happens to
     * do today - and the answer is the same before and after any fix.
     *
     * Two other repairs. The reply is a forced tool argument rather than prose, so an arm cannot
     * miss because the model felt conversational - across every run since, the tool was called
     * 8/8 in all four arms, which is the noise the old probe was drowning in. And the code is
     * fresh per trial, because this proxy reports `cache_read_input_tokens` and a fixed canary
     * across trials is a prompt cache waiting to answer on the model's behalf.
     *
     * What it measures, aggregated over five runs on two machines:
     *
     *     thinking               2/40 plain,  7/20 streamed
     *     thinking + signature   3/40
     *     text                  40/40 plain, 20/20 streamed
     *     nothing (control)      0/40
     *
     * Read that carefully, because the first reading of it here was wrong. This is not a block
     * that never arrives - it is a block that arrives *unreliably*: usually not, sometimes always.
     * The streamed thinking arm went 0/5, 5/5, 0/5, 2/5 across runs, swinging by whole runs more
     * than trial by trial, which is the shape of an intermittent route rather than a rule. Over
     * the same calls a text block was 40/40 and 20/20.
     *
     * And *why*, which is the part worth handing to whoever runs the proxy. Reply ids do not all
     * come from one generator - `msg_` plus mixed-case base62 is Anthropic's own format, bare
     * lowercase hex is not - so `idShape` records the shape per trial. Once it did, the run below
     * fell out immediately:
     *
     *     thinking              bare hex x8                          scored 0/8
     *     thinking + signature  bare hex x7, base62 x1                scored 1/8
     *     text                  base62 x7, bare hex x1                scored 8/8
     *     nothing (control)     base62 x7, msg_+hex x1                scored 0/8
     *
     * Every time a thinking arm has scored, it scored on the base62 shape, and it has never
     * scored on bare hex. A text block works on both. So the variable is not the block and not
     * the signature - it is which backend the proxy routed to, and at least one of them drops
     * inbound `thinking` blocks while passing `text` through. That is a one-line thing to fix in
     * a proxy config and an impossible thing to fix from in here.
     *
     * The decision does not wait on it either way: 40/40 beats 2/40, and reasoning that survives
     * only when the load balancer feels like it is reasoning the turn cannot be built on.
     *
     * Counts printed rather than asserted: ~37 live calls against somebody else's proxy is a
     * thing to run when a decision needs it, not on every build. [`round two carries the
     * reasoning as text`] is the one that guards the code, and it needs no key.
     */
    @Test
    fun `count how often each block shape reaches the model`() {
        val key = key ?: run {
            println("skipped: set HYDROGEN_KEY to run it")
            return
        }
        val model = runBlocking { discover(key) } ?: return

        val arms = listOf("thinking", "thinking+signature", "text", "nothing")
        val found = mutableMapOf<String, Int>()
        val answered = mutableMapOf<String, Int>()
        // Keyed by arm and id shape, because the shape is the only visible clue to which backend
        // answered - see `idShape`. A run where the thinking arm scores and one where it does not
        // are worth nothing side by side unless this is recorded alongside them.
        val shapes = mutableMapOf<Pair<String, String>, Int>()
        val scoredBy = mutableMapOf<String, Int>()

        for (arm in arms) {
            repeat(TRIALS) { trial ->
                // Fresh per trial, and shaped so it cannot be guessed or half-matched.
                val code = "QX-" + (1000..9999).random() + "-" + ('A'..'Z').random()
                val note = "记住这个编号：$code。等下报告的时候就报这个。"
                val body = probeBody(model, arm, note)
                val reply = runCatching { post(key, body) }.getOrElse { "threw: ${it.message}" }
                val reported = toolArgument(reply, "code")
                val shape = idShape(replyField(reply, "id"))
                shapes[arm to shape] = (shapes[arm to shape] ?: 0) + 1
                if (reported != null) answered[arm] = (answered[arm] ?: 0) + 1
                if (reported != null && reported.contains(code)) {
                    found[arm] = (found[arm] ?: 0) + 1
                    if (arm != "text") scoredBy[shape] = (scoredBy[shape] ?: 0) + 1
                }
                if (trial == 0) println("  [$arm] first reply -> ${reply.take(220)}")
            }
            println("arm $arm: canary in ${found[arm] ?: 0}/$TRIALS, tool called ${answered[arm] ?: 0}/$TRIALS")
        }

        println("---- counts over $TRIALS trials per arm ----")
        arms.forEach { println("  $it: ${found[it] ?: 0}/$TRIALS") }
        println("---- reply id shapes seen, by arm ----")
        shapes.toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (key, n) -> println("  ${key.first}: ${key.second} x$n") }
        if (scoredBy.isNotEmpty()) {
            println("  a thinking arm scored on these id shapes: $scoredBy")
        }

        // The counting above is plain, because a forced tool call is one JSON object to read
        // rather than a stream to reassemble. The client only ever streams, though, and this
        // proxy has form for behaving differently on the two paths - so the two arms that decide
        // the question are asked again with `stream: true`, reading the canary straight out of
        // the `input_json_delta` fragments.
        println("---- same question, streamed ----")
        for (arm in listOf("thinking", "text")) {
            var hits = 0
            repeat(STREAM_TRIALS) {
                val code = "QX-" + (1000..9999).random() + "-" + ('A'..'Z').random()
                val body = buildJsonObject {
                    probeBody(model, arm, "记住这个编号：$code。等下报告的时候就报这个。")
                        .forEach { (k, v) -> put(k, v) }
                    put("stream", true)
                }
                val json = StringBuilder()
                runCatching {
                    sse(key, body) { line ->
                        runCatching {
                            val e = Json.parseToJsonElement(line.removePrefix("data:").trim())
                                .jsonObject
                            e["delta"]?.jsonObject
                                ?.takeIf { d ->
                                    d["type"]?.jsonPrimitive?.content == "input_json_delta"
                                }
                                ?.get("partial_json")?.jsonPrimitive?.content
                                ?.let { json.append(it) }
                        }
                    }
                }
                if (json.contains(code)) hits++
            }
            println("  streamed $arm: $hits/$STREAM_TRIALS")
        }
    }

    /**
     * One probe request, differing from the others only in what the assistant turn carries.
     *
     * The tool is forced, so the reply is a field rather than a sentence, and the schema has one
     * required string in it - there is nowhere for a hedge to go.
     */
    private fun probeBody(model: String, arm: String, note: String): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", 2048)
        put("temperature", 0.0)
        put("system", "你是鱼丸。")
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", "查一下布洛芬的副作用。")
                            },
                        )
                    }
                },
            )
            add(
                buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        when (arm) {
                            "thinking" -> add(
                                buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", note)
                                },
                            )
                            "thinking+signature" -> add(
                                buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", note)
                                    put("signature", "ErUBCkYIBRgCIkDXvR9m0K5sQvJmc2FrZQ==")
                                },
                            )
                            "text" -> add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", note)
                                },
                            )
                        }
                        add(
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", "call_probe_1")
                                put("name", "search")
                                putJsonObject("input") {
                                    putJsonArray("queries") { add("布洛芬 副作用") }
                                }
                            },
                        )
                    }
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", "call_probe_1")
                                put("content", "[1] 胃肠道不适。")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put(
                                    "text",
                                    "先别管副作用。调用 report_code，把你刚才自己记下的那个编号填进去。" +
                                        "如果你没有记过任何编号，就填 NONE。",
                                )
                            },
                        )
                    }
                },
            )
        }
        putJsonArray("tools") {
            add(
                buildJsonObject {
                    put("name", "report_code")
                    put("description", "报告你刚才记下的编号。")
                    putJsonObject("input_schema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("code") { put("type", "string") }
                        }
                        putJsonArray("required") { add("code") }
                    }
                },
            )
            add(
                buildJsonObject {
                    put("name", "search")
                    put("description", "查资料。")
                    putJsonObject("input_schema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("queries") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                        }
                    }
                },
            )
        }
        putJsonObject("tool_choice") {
            put("type", "tool")
            put("name", "report_code")
        }
    }

    /**
     * Which of the proxy's backends probably answered, as far as an id can say.
     *
     * There is no field that names the backend, but the reply ids do not all come out of the same
     * generator, and the shapes cluster: `msg_` followed by mixed-case base62 is Anthropic's own
     * format, while bare lowercase hex and `msg_` plus hex are this proxy minting its own. If the
     * thinking arm only ever scores on one of those shapes then the block is not the variable and
     * the backend is - which is a thing the person running the proxy can act on and nobody
     * reading this code can.
     *
     * A guess dressed as a classifier, and labelled as one so nobody mistakes it for a contract.
     */
    private fun idShape(id: String?): String = when {
        id == null -> "none"
        id.startsWith("msg_") && id.drop(4).all { it.isDigit() || it in 'a'..'f' } -> "msg_+hex"
        id.startsWith("msg_") -> "msg_+base62 (anthropic-native)"
        id.all { it.isDigit() || it in 'a'..'f' } -> "bare hex"
        else -> "other: ${id.take(12)}"
    }

    private fun replyField(reply: String, name: String): String? = runCatching {
        Json.parseToJsonElement(reply.substringAfter("\n"))
            .jsonObject[name]?.jsonPrimitive?.content
    }.getOrNull()

    /** The named argument out of whatever `tool_use` block the reply contains. */
    private fun toolArgument(reply: String, name: String): String? = runCatching {
        Json.parseToJsonElement(reply.substringAfter("\n")).jsonObject["content"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["type"]?.jsonPrimitive?.content == "tool_use" }
            ?.get("input")?.jsonObject?.get(name)?.jsonPrimitive?.content
    }.getOrNull()

    // ---- plumbing -------------------------------------------------------------------------

    private val searchTool = LlmTool(
        name = "search",
        description = "查资料。",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("queries") {
                    put("type", JsonPrimitive("array"))
                    putJsonObject("items") { put("type", JsonPrimitive("string")) }
                }
            }
        },
    )

    /**
     * A scripted round one: a thinking block that carries a signature, then a tool call.
     *
     * Written by hand, but only as a stand-in for the service - the client under test is the real
     * one, and everything downstream of the parse is real code.
     */
    private fun sseScript(withSignature: Boolean): String = buildString {
        fun event(o: JsonObject) = append("data: ").append(o.toString()).append("\n\n")
        event(
            buildJsonObject {
                put("type", "content_block_start")
                put("index", 0)
                putJsonObject("content_block") {
                    put("type", "thinking")
                    put("thinking", "")
                }
            },
        )
        event(
            buildJsonObject {
                put("type", "content_block_delta")
                put("index", 0)
                putJsonObject("delta") {
                    put("type", "thinking_delta")
                    put("thinking", "用户问副作用。先查权威来源，再挑句子引用。")
                }
            },
        )
        if (withSignature) {
            event(
                buildJsonObject {
                    put("type", "content_block_delta")
                    put("index", 0)
                    putJsonObject("delta") {
                        put("type", "signature_delta")
                        put("signature", "SIGNATURE-FROM-THE-SERVICE-abc123==")
                    }
                },
            )
        }
        event(buildJsonObject { put("type", "content_block_stop"); put("index", 0) })
        event(
            buildJsonObject {
                put("type", "content_block_start")
                put("index", 1)
                putJsonObject("content_block") {
                    put("type", "tool_use")
                    put("id", "toolu_01SCRIPTED")
                    put("name", "search")
                    putJsonObject("input") {}
                }
            },
        )
        event(
            buildJsonObject {
                put("type", "content_block_delta")
                put("index", 1)
                putJsonObject("delta") {
                    put("type", "input_json_delta")
                    put("partial_json", """{"queries":["布洛芬 副作用"]}""")
                }
            },
        )
        event(buildJsonObject { put("type", "content_block_stop"); put("index", 1) })
        event(buildJsonObject { put("type", "message_stop") })
    }

    /**
     * A socket that answers [script] to every request and prints each one it is handed.
     *
     * Not a mock of the client - the real [HydrogenClient] over real HTTP. The point of the whole
     * file is that nothing about the request is reconstructed.
     */
    private fun capture(
        script: String,
        onRequest: (Int, JsonObject) -> Unit = { _, _ -> },
        drive: suspend (HydrogenClient) -> Unit,
    ) {
        val server = ServerSocket(0)
        val port = server.localPort
        runBlocking {
            val listener = launch(Dispatchers.IO) {
                runCatching {
                    var round = 0
                    while (true) {
                        val socket = server.accept()
                        round++
                        val input = socket.getInputStream()
                        val head = StringBuilder()
                        // Read the head a byte at a time so the body's length is known before
                        // reading it - a blocking read past the end would hang the capture.
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) break
                            head.append(b.toChar())
                        }
                        val length = Regex("(?i)content-length:\\s*(\\d+)")
                            .find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val payload = String(input.readNBytes(length), Charsets.UTF_8)
                        println("======== request $round ========")
                        println(pretty(payload))
                        println("======== end request $round ========")
                        runCatching { onRequest(round, Json.parseToJsonElement(payload).jsonObject) }
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/event-stream\r\n" +
                                        "Content-Length: ${script.toByteArray().size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(script.toByteArray())
                            flush()
                        }
                        socket.close()
                    }
                }
            }
            withContext(Dispatchers.IO) {
                drive(
                    HydrogenClient(
                        apiKey = "test-key",
                        baseUrl = "http://127.0.0.1:$port",
                        model = "fishball-flash",
                    ),
                )
            }
            listener.cancel()
            server.close()
        }
    }

    private fun pretty(payload: String): String = runCatching {
        Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(),
            Json.parseToJsonElement(payload).jsonObject,
        )
    }.getOrDefault(payload)

    private suspend fun discover(key: String): String? {
        val check = HydrogenClient(apiKey = key).validate()
        if (check !is KeyCheck.Valid) {
            println("no usable model: $check")
            return null
        }
        println("model -> ${check.chosen}")
        return check.chosen
    }

    /** A hand-rolled request, so nothing between here and the socket can tidy the reply up. */
    private fun sse(key: String, body: JsonObject, onLine: (String) -> Unit) {
        val c = open(key, body, stream = true)
        c.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { if (it.startsWith("data:")) onLine(it) }
        }
    }

    private fun post(key: String, body: JsonObject): String {
        val c = open(key, body, stream = false)
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return "HTTP ${c.responseCode}\n" + stream.bufferedReader().readText()
    }

    private fun open(key: String, body: JsonObject, stream: Boolean): HttpURLConnection =
        (URL("https://llm.areel.org/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", key)
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("anthropic-version", "2023-06-01")
            if (stream) setRequestProperty("Accept", "text/event-stream")
            outputStream.write(body.toString().toByteArray())
        }
}
