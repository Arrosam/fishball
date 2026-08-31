package org.areel.fishball.core.trust

import org.areel.fishball.core.copy.Vocabulary

/**
 * One search result, reduced to what tiering needs.
 *
 * [account] and [verified] are the publisher-first inputs. As of 2026-08-30 the live
 * instance returns them empty for every result — SearXNG has an `author` field, `google cse`
 * never fills it (docs/05-search-reality-check.md). Platform results therefore fall to the
 * platform floor, which is the correct conservative behaviour, not a bug.
 */
data class SearchHit(
    val url: String,
    val title: String = "",
    val snippet: String = "",
    val engine: String = "",
    /** Publishing account on a platform, when the engine supplies one. */
    val account: String? = null,
    /** A verification signal was detected on the result — platform verification badge. */
    val verified: Boolean = false,
    /** This publisher sells, or takes commission on, the subject of the query. */
    val sellsSubject: Boolean = false,
)

data class ClaimContext(
    val topic: Topic = Topic.GENERAL,
    val claimKind: ClaimKind = ClaimKind.OBJECTIVE_ATTRIBUTE,
    /** Brand names extracted from the user's question, for the R3 heuristic. */
    val brandsInQuery: List<String> = emptyList(),
)

/** How a hit resolved. Carried so tests and the maintenance log can assert on the path, not just the tier. */
sealed class Via {
    data class PlatformPublisher(val platform: String, val publisherId: String) : Via()
    data class PlatformFloor(val platform: String, val reason: String) : Via()
    data class RegistryDomain(val publisherId: String) : Via()
    data class PatternMatch(val pattern: String) : Via()
    data class BrandOfficial(val brand: String) : Via()
    object Unknown : Via()
}

data class Resolution(
    val tier: Tier,
    /** How to name this source in-sentence, per spec §6. Product text, so Chinese. */
    val displayName: String,
    /** One clause explaining an unfamiliar foreign institution, per spec R8. Null when none is needed. */
    val explanation: String? = null,
    val via: Via = Via.Unknown,
    /**
     * True only for genuinely unknown domains. Spec R2 lets the model argue those up to
     * INSTITUTIONAL with a stated reason; anything the registry actually matched is fixed.
     */
    val promotable: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    /** Attribution fragment for the answer — the name, plus an explanation when it needs one. */
    fun attribution(): String = Vocabulary.attribution(displayName, explanation)
}
