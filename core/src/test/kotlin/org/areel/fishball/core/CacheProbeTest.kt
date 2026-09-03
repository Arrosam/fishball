package org.areel.fishball.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test

/**
 * Whether marking the prefix actually buys anything, on this proxy.
 *
 * Opt-in: without `HYDROGEN_KEY` this does nothing and passes.
 *
 * It exists because the app had never marked a cache breakpoint at all, and a prefix nobody
 * marks is a prefix nobody caches - a captured reply showed `cachedInputTokens: 0` on a turn
 * carrying four thousand tokens of prompt. Rearranging the prompt so the stable half comes
 * first is necessary and, on its own, worth nothing.
 *
 * The measurement has to be a second call. A cache is written on the first request and read on
 * the ones after it, so a single call can only ever report a write - and reporting a write
 * proves the service accepted the marker, not that it will honour it.
 *
 * Requests are built here rather than through [HydrogenClient] so the result says what the
 * *service* does, and stays true whatever the client is changed to afterwards.
 */
class CacheProbeTest {

    private val key: String? = System.getenv("HYDROGEN_KEY")?.takeIf { it.isNotBlank() }

    @Test
    fun `does marking the prefix get it cached`() {
        val key = key ?: run {
            println("CacheProbeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val http = HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 120_000 }
        }

        // Long enough to be worth caching. Most services decline to cache a short prefix at
        // all, so a probe with a paragraph in it can report "no" for the wrong reason.
        val system = buildString {
            repeat(60) {
                appendLine(
                    "你是一个帮人查资料的助手。回答要先给结论，再说依据，句子里点明来源。" +
                        "第 $it 条：不确定的地方要明说，不要用模糊的话糊过去。",
                )
            }
        }

        runBlocking {
            listOf("first call (writes)", "second call (should read)").forEach { label ->
                val body = buildJsonObject {
                    put("model", MODEL)
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
                    put("messages", buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "说一个字")
                            },
                        )
                    })
                }
                val response: HttpResponse =
                    http.post("https://llm.areel.org/v1/messages") {
                        header("x-api-key", key)
                        header("anthropic-version", "2023-06-01")
                        header("Authorization", "Bearer $key")
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                val text = response.bodyAsText()
                println("  $label -> HTTP ${response.status.value}")
                if (!response.status.isSuccess()) {
                    println("    refused: " + text.take(220))
                    return@forEach
                }
                val usage = runCatching {
                    Json.parseToJsonElement(text).jsonObject["usage"]?.jsonObject
                }.getOrNull()
                println("    usage: " + (usage?.let(::readable) ?: "none reported"))
            }
        }
        http.close()
    }

    /** Every spelling of the cache counters these routes use, so none is missed. */
    private fun readable(usage: JsonObject): String = usage.entries
        .filter { (k, _) -> k.contains("token", ignoreCase = true) }
        .joinToString("  ") { (k, v) ->
            k + "=" + runCatching { v.jsonPrimitive.content }.getOrDefault("?")
        }

    private companion object {
        const val MODEL = "fishball-flash"
    }
}
