package org.areel.fishball.core.search

import org.areel.fishball.core.trust.SearchHit

enum class Purpose {
    /** Looking for evidence the claim is true. */
    SUPPORT,

    /** Spec R6 — looking for evidence it is false. */
    DISCONFIRM,
}

data class SearchQuery(
    val text: String,
    val purpose: Purpose = Purpose.SUPPORT,
    val categories: String? = null,
    /** day / month / year. Only for time-sensitive questions. */
    val timeRange: String? = null,
    val language: String? = "zh-CN",
    val page: Int = 1,
)

data class SearchResponse(
    val query: SearchQuery,
    val hits: List<SearchHit> = emptyList(),
    /**
     * Engines that failed this query. As of 2026-08-30 the live instance reports brave,
     * duckduckgo and startpage on every request — see docs/05-search-reality-check.md.
     * Surfaced rather than swallowed so degraded search is visible instead of silent.
     */
    val unresponsiveEngines: List<String> = emptyList(),
    /** The request itself failed — network, 403, timeout. Distinct from "no results". */
    val failed: Boolean = false,
    val failureReason: String? = null,
) {
    val usable: Boolean get() = !failed
}

/**
 * IO boundary. Implemented over SearXNG in `:app`; faked in tests.
 *
 * Note for implementers: the instance returns **403 without a browser User-Agent**. Send one.
 */
interface SearchGateway {
    suspend fun search(query: SearchQuery): SearchResponse
}
