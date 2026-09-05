package org.areel.fishball.core.search

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.areel.fishball.core.notCancellation
import org.areel.fishball.core.trust.SearchHit

/**
 * SearXNG over its JSON API.
 *
 * Two things the live instance taught us (docs/05-search-reality-check.md):
 *
 *  - **It returns 403 without a browser User-Agent.** `Python-urllib` is rejected outright,
 *    and a bare Ktor client would be too. The UA below is not decoration.
 *  - **`unresponsive_engines` is usually non-empty.** As of 2026-08-30 brave, duckduckgo and
 *    startpage fail on every query, so all results come from one engine. That is surfaced on
 *    every response rather than swallowed, because silently degraded search looks identical
 *    to healthy search from the caller's side.
 */
class SearxngGateway(
    private val baseUrl: String,
    private val apiToken: String? = null,
    private val http: HttpClient = defaultClient(),
) : SearchGateway {

    override suspend fun search(query: SearchQuery): SearchResponse = try {
        val dto: SearxDto = http.get("${baseUrl.trimEnd('/')}/search") {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
            parameter("q", query.text)
            parameter("format", "json")
            parameter("pageno", query.page)
            parameter("safesearch", 1)
            query.categories?.takeIf { it.isNotBlank() }?.let { parameter("categories", it) }
            query.language?.takeIf { it.isNotBlank() }?.let { parameter("language", it) }
            query.timeRange?.takeIf { it in VALID_TIME_RANGES }?.let { parameter("time_range", it) }
            apiToken?.let { header("X-FishBall-Token", it) }
        }.body()

        SearchResponse(
            query = query,
            hits = dto.results.map { it.toHit() },
            unresponsiveEngines = dto.unresponsiveEngines.engineNames(),
        )
    } catch (e: Exception) {
        e.notCancellation()
        // Spec §23 depends on this being reported honestly: a failed search must never be
        // mistaken for "nothing found", or the model may answer from its own knowledge.
        SearchResponse(
            query = query,
            failed = true,
            failureReason = e.message ?: e::class.simpleName,
        )
    }

    companion object {
        private val VALID_TIME_RANGES = setOf("day", "month", "year")

        /** SearXNG's bot filter rejects non-browser agents with 403. Verified 2026-08-30. */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0 Mobile Safari/537.36"

        /**
         * Public so one client can be shared across gateways, the way `:app` shares its page
         * reader. A profile changes which instance is searched, and a gateway built per profile
         * with a client of its own would leave a connection pool and its dispatcher threads
         * behind on every change, none of them reachable to close.
         */
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
    }
}

@Serializable
internal data class SearxDto(
    val query: String = "",
    val results: List<SearxResultDto> = emptyList(),
    /** Shape varies by version — array of [name, reason] pairs, or of objects. Parsed loosely. */
    @SerialName("unresponsive_engines") val unresponsiveEngines: JsonElement? = null,
)

@Serializable
internal data class SearxResultDto(
    val url: String = "",
    val title: String = "",
    val content: String = "",
    val engine: String = "",
    /**
     * Present in the schema, empty in practice with `google cse`. When a Chinese engine is
     * enabled this is where a platform account name would arrive — and the publisher-first
     * tiering in §5 depends on it. Retest before assuming it stays empty.
     */
    val author: String? = null,
    val publishedDate: String? = null,
) {
    fun toHit() = SearchHit(
        url = url,
        title = title,
        snippet = content,
        engine = engine,
        account = author?.takeIf { it.isNotBlank() },
    )
}

/** Pulls engine names out of whichever shape `unresponsive_engines` arrived in. */
internal fun JsonElement?.engineNames(): List<String> {
    val array = this as? JsonArray ?: return emptyList()
    return array.mapNotNull { entry ->
        when (entry) {
            is JsonPrimitive -> entry.content
            is JsonArray -> (entry.firstOrNull() as? JsonPrimitive)?.content
            else -> null
        }
    }
}
