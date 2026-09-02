package org.areel.fishball.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.areel.fishball.core.llm.Effort
import org.areel.fishball.core.llm.HydrogenClient
import org.areel.fishball.core.llm.LlmMessage
import org.areel.fishball.core.llm.LlmRequest
import org.areel.fishball.core.llm.LlmTool
import java.net.ServerSocket
import kotlin.test.Test

/**
 * What the client actually puts on the wire for a main-loop call.
 *
 * Not a probe written by hand next to it - the client itself, talking to a socket that reads the
 * request and says nothing back. Everything above this line has been reasoned about from a
 * reconstruction of the request; this is the request.
 *
 * It exists because a hand-written approximation is exactly how the transcription bug survived
 * for weeks: the shape being argued about was not the shape being sent.
 */
class RequestShapeTest {

    @Test
    fun `print the routing call as the client sends it`() {
        val server = ServerSocket(0)
        val port = server.localPort

        runBlocking {
            launch(Dispatchers.IO) {
                // The listener is closed under this the moment the client gives up, which is a
                // normal end to the capture rather than a failure.
                runCatching {
                server.accept().use { socket ->
                    val text = socket.getInputStream().readNBytes(16_000)
                        .toString(Charsets.UTF_8)
                    println("---- request on the wire ----")
                    println(text.take(4000))
                    println("---- end ----")
                }
                }
            }
            withContext(Dispatchers.IO) {
                val client = HydrogenClient(
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:$port",
                    model = "fishball-flash",
                )
                runCatching {
                    client.complete(
                        LlmRequest(
                            system = "你是鱼丸。",
                            messages = listOf(LlmMessage.user("布洛芬常见的副作用是什么？")),
                            tools = listOf(
                                LlmTool(
                                    name = "classify_turn",
                                    description = "把这一轮对话归类。",
                                    inputSchema = kotlinx.serialization.json.buildJsonObject {
                                        put(
                                            "type",
                                            kotlinx.serialization.json.JsonPrimitive("object"),
                                        )
                                    },
                                ),
                            ),
                            forceTool = "classify_turn",
                            maxTokens = 131_072,
                            effort = Effort.MAX,
                        ),
                    ) {}
                }
            }
            server.close()
        }
    }
}
