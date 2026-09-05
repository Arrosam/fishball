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
 * A stream that ended, and whether the client can tell how.
 *
 * Reported live: `IOException: Software caused connection abort`, on a turn the proxy's own log
 * showed answering. Two separate defects met on that turn, and they are mirror images.
 *
 * The socket died on the tail of a stream that had already said `message_stop`. Every block of a
 * complete answer was discarded so the app could report 「这会儿连不上」 — an answer that was
 * paid for, and most of which the user had already watched arrive.
 *
 * And the other direction was worse. A stream that stopped *early* ended the read loop through
 * `readUTF8Line() ?: break` and was assembled as though it had finished. The reply arrives as an
 * `answer` tool call whose `text` argument is streamed in fragments, so half a stream is half a
 * JSON object — and the parse failure was swallowed with `getOrDefault(JsonObject(emptyMap()))`.
 * A cut-off answer became a blank one, silently, indistinguishable from the model having nothing
 * to say. For a product whose whole claim is that it does not put unsupported text on the screen,
 * presenting a truncated answer as a whole one is the worse half of this bug.
 *
 * Both had one root: `message_stop` was named once in a comment in the client and never parsed,
 * so "the model finished" and "the bytes stopped" were the same event.
 *
 * Served off a real socket, like [ToolEnvelopeTest] — the point is what the client's own parser
 * does with bytes, and a test that called `assemble` directly would be testing the function
 * rather than the path.
 */
class TruncatedStreamTest {

    /**
     * The regression, in the direction that loses work.
     *
     * The stream is whole and the connection then dies without the terminating chunk, which is
     * what an abort on the tail looks like from in here.
     */
    @Test
    fun `an answer that finished survives the socket dying afterwards`() {
        val result = stream(complete = true, cut = false, abruptClose = true)
        assertTrue(
            result is LlmResult.Ok,
            "a message_stop arrived and the answer was thrown away anyway: $result",
        )
        assertEquals("吃了饭再吃。", result.toolCalls.single().input["text"]?.let(::text))
    }

    /**
     * And the direction that invents work — the silent half of the bug.
     *
     * The body ends *tidily* with the JSON cut in half: a proxy that closed the stream early
     * and correctly, so there is no socket error to notice. `readUTF8Line() ?: break` treated
     * that as the end of a finished message, and `getOrDefault(JsonObject(emptyMap()))` turned
     * the unparseable remains into an `answer` call with no text. Nothing threw. Nothing logged.
     *
     * This is the arrangement that produced a blank answer rather than an error, so it is the
     * one the regression has to be pinned against. When the socket aborts as well the client
     * fails at the read and never reaches the assembly - correct, and a different path.
     */
    @Test
    fun `an answer cut in half is a failure, not an empty one`() {
        val result = stream(complete = false, cut = true, abruptClose = false)
        assertTrue(result is LlmResult.Failed, "a half-received answer was assembled: $result")
        assertTrue(
            result.reason.contains("do not parse"),
            "the reason does not say what happened: ${result.reason}",
        )
        assertTrue(
            result.reason.contains("no message_stop"),
            "and does not say the stream never finished: ${result.reason}",
        )
    }

    /**
     * The case that keeps this safe on a backend that never sends the marker.
     *
     * Content is whole, `message_stop` never comes, the socket closes cleanly. The client cannot
     * prove the message finished — but it cannot prove it did not either, and refusing here would
     * fail every turn against any service that omits the frame. The parseable arguments are the
     * evidence, which is why the truncation test reads the content rather than the marker.
     */
    @Test
    fun `a whole answer with no message_stop is still an answer`() {
        val result = stream(complete = false, cut = false, abruptClose = false)
        assertTrue(result is LlmResult.Ok, "a service that omits message_stop broke the turn: $result")
        assertEquals("吃了饭再吃。", result.toolCalls.single().input["text"]?.let(::text))
    }

    /**
     * The same content lost to an abort rather than a tidy close still fails.
     *
     * It failed before this change too - everything was discarded on any exception - so this is
     * here to hold that half in place while the salvage above makes the other half conditional.
     */
    @Test
    fun `an answer cut in half by a dead socket still fails`() {
        val result = stream(complete = false, cut = true, abruptClose = true)
        assertTrue(result is LlmResult.Failed, "a half-received answer was assembled: $result")
    }

    /** A budget that ran out mid-sentence is named, because raising it is what fixes that one. */
    @Test
    fun `a model cut off by its own ceiling says so`() {
        val result = stream(complete = false, cut = true, abruptClose = false, stopReason = "max_tokens")
        assertTrue(result is LlmResult.Failed, "a truncated answer was assembled: $result")
        assertTrue(
            result.reason.contains("max_tokens"),
            "a budget failure reads as a network one: ${result.reason}",
        )
    }

    // ---- the socket ------------------------------------------------------------------------

    private fun text(e: kotlinx.serialization.json.JsonElement): String =
        (e as kotlinx.serialization.json.JsonPrimitive).content

    private fun stream(
        complete: Boolean,
        cut: Boolean,
        abruptClose: Boolean,
        stopReason: String? = null,
    ): LlmResult {
        val server = ServerSocket(0)
        val result = runBlocking {
            launch(Dispatchers.IO) {
                runCatching { server.accept().use { serve(it, complete, cut, abruptClose, stopReason) } }
            }
            HydrogenClient(
                apiKey = "test",
                baseUrl = "http://127.0.0.1:${server.localPort}",
                model = "test-model",
            ).complete(
                LlmRequest(
                    system = "t",
                    messages = listOf(LlmMessage.user("t")),
                    tools = listOf(LlmTool("answer", "t", kotlinx.serialization.json.JsonObject(emptyMap()))),
                    maxTokens = 64,
                ),
            )
        }
        server.close()
        return result
    }

    private fun serve(
        socket: Socket,
        complete: Boolean,
        cut: Boolean,
        abruptClose: Boolean,
        stopReason: String?,
    ) {
        socket.getInputStream().read(ByteArray(16_384))

        // Split across two deltas the way a real one arrives, so cutting the stream leaves
        // exactly what a cut stream leaves: a JSON object missing its tail.
        val head = """{"text": "吃了"""
        val tail = """饭再吃。"}"""

        val events = buildString {
            event("""{"type":"message_start","message":{"content":[]}}""")
            event(
                """{"type":"content_block_start","index":0,"content_block":""" +
                    """{"type":"tool_use","id":"tu_1","name":"answer"}}""",
            )
            event(
                """{"type":"content_block_delta","index":0,"delta":""" +
                    """{"type":"input_json_delta","partial_json":${quote(head)}}}""",
            )
            if (!cut) {
                event(
                    """{"type":"content_block_delta","index":0,"delta":""" +
                        """{"type":"input_json_delta","partial_json":${quote(tail)}}}""",
                )
                event("""{"type":"content_block_stop","index":0}""")
            }
            stopReason?.let {
                event("""{"type":"message_delta","delta":{"stop_reason":"$it"}}""")
            }
            if (complete) event("""{"type":"message_stop"}""")
        }

        val body = events.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            /*
             * Chunked, and for `abruptClose` the terminating `0\r\n\r\n` is never written.
             *
             * A Content-Length response that stops short is a different failure - the client
             * knows how many bytes it was promised. What an aborted connection looks like is a
             * body the client has no way to know the end of, ending without one.
             */
            write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        if (abruptClose) {
                            "Transfer-Encoding: chunked\r\n\r\n"
                        } else {
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        }
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            if (abruptClose) {
                write(Integer.toHexString(body.size).toByteArray(Charsets.ISO_8859_1))
                write("\r\n".toByteArray(Charsets.ISO_8859_1))
                write(body)
                write("\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
                // and no last chunk: the connection simply goes away
                socket.close()
            } else {
                write(body)
                flush()
            }
        }
    }

    private fun StringBuilder.event(data: String) {
        append("data: ").append(data).append("\n\n")
    }

    private fun quote(s: String) = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(s),
    )
}
