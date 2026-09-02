package org.areel.fishball.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tool call arriving in an envelope is still a tool call.
 *
 * This is the defect that made a twenty-round turn produce raw model markup instead of an
 * answer. Some models on this proxy send `{"arguments": {"url": "…"}}` where the schema asked
 * for `{"url": "…"}` - the *same* model doing it on one call and not the next - and every
 * handler in `:core` reads its parameters off the top level, so those calls came back rejected
 * with an error that did not say why. Traced live: the model could tell its calls were being
 * refused, could not tell which part was wrong, and spent the rest of the turn permuting syntax
 * instead of looking anything up. By round 24 it had wrapped the envelope in another envelope.
 *
 * Two things are pinned here, and the second matters more than it looks. The call has to come
 * out unwrapped, *and* so does the copy of it in the replayed assistant turn - a model that
 * reads its own wrapped call back out of the thread takes it for the house style and does it
 * again, which is how one envelope became two.
 */
class ToolEnvelopeTest {

    @Test
    fun `an enveloped tool call is read as though it were not`() {
        val call = single(
            """{"arguments": {"url": "https://example.test/leaflet", "find": "孕妇"}}""",
        )
        assertEquals("https://example.test/leaflet", call.input["url"]?.let(::text))
        assertEquals("孕妇", call.input["find"]?.let(::text))
    }

    @Test
    fun `so is one wrapped twice`() {
        val call = single("""{"arguments": {"arguments": {"queries": ["布洛芬 孕妇"]}}}""")
        assertTrue(call.input["queries"] != null, "still wrapped: " + call.input)
    }

    @Test
    fun `the replayed turn carries the unwrapped call`() {
        val result = complete("""{"arguments": {"url": "https://example.test/x"}}""")
        val replayed = result.raw.content.filterIsInstance<LlmContent.ToolUse>().single()
        assertEquals(
            "https://example.test/x",
            replayed.input["url"]?.let(::text),
            "the thread would have taught the model to wrap the next one too",
        )
    }

    @Test
    fun `a real argument named like an envelope is left alone`() {
        // The guard is single-key-and-an-object. Two keys is a real input, whatever they are
        // called, and unwrapping it would throw one of them away.
        val call = single("""{"arguments": "找孕妇那一段", "url": "https://example.test/x"}""")
        assertEquals("https://example.test/x", call.input["url"]?.let(::text))
        assertEquals("找孕妇那一段", call.input["arguments"]?.let(::text))

        // And a single key whose value is not an object is a value, not a wrapper.
        val scalar = single("""{"input": "布洛芬"}""")
        assertEquals("布洛芬", scalar.input["input"]?.let(::text))
    }

    @Test
    fun `an ordinary call is untouched`() {
        val call = single("""{"queries": ["布洛芬 孕妇", "对乙酰氨基酚 孕妇"]}""")
        assertEquals(1, call.input.size)
        assertTrue(call.input["queries"] != null)
    }

    // ---- one streamed tool call, off a socket -------------------------------------------

    private fun text(e: kotlinx.serialization.json.JsonElement): String =
        (e as kotlinx.serialization.json.JsonPrimitive).content

    private fun single(inputJson: String): LlmContent.ToolUse =
        complete(inputJson).toolCalls.single()

    /**
     * The client's own parser, fed a real SSE stream. Calling `unwrap` directly would test the
     * function rather than the path the calls actually take.
     */
    private fun complete(inputJson: String): LlmResult.Ok {
        val server = ServerSocket(0)
        val result = runBlocking {
            launch(Dispatchers.IO) {
                runCatching { server.accept().use { stream(it, inputJson) } }
            }
            HydrogenClient(
                apiKey = "test",
                baseUrl = "http://127.0.0.1:${server.localPort}",
                model = "test-model",
            ).complete(
                LlmRequest(
                    system = "t",
                    messages = listOf(LlmMessage.user("t")),
                    tools = listOf(
                        LlmTool("read_page", "t", kotlinx.serialization.json.JsonObject(emptyMap())),
                    ),
                    maxTokens = 64,
                ),
            )
        }
        server.close()
        assertTrue(result is LlmResult.Ok, "the stream did not parse: $result")
        return result
    }

    private fun stream(socket: Socket, inputJson: String) {
        socket.getInputStream().read(ByteArray(16_384))
        val events = buildString {
            event("message_start", """{"type":"message_start","message":{"content":[]}}""")
            event(
                "content_block_start",
                """{"type":"content_block_start","index":0,"content_block":""" +
                    """{"type":"tool_use","id":"tu_1","name":"read_page"}}""",
            )
            event(
                "content_block_delta",
                """{"type":"content_block_delta","index":0,"delta":""" +
                    """{"type":"input_json_delta","partial_json":${quote(inputJson)}}}""",
            )
            event("content_block_stop", """{"type":"content_block_stop","index":0}""")
            event("message_stop", """{"type":"message_stop"}""")
        }
        val body = events.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            write(body)
            flush()
        }
    }

    private fun StringBuilder.event(name: String, data: String) {
        append("event: ").append(name).append("\n")
        append("data: ").append(data).append("\n\n")
    }

    private fun quote(s: String) = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(s),
    )
}
