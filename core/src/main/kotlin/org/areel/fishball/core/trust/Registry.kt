package org.areel.fishball.core.trust

data class Publisher(
    val id: String,
    /** Name used in-sentence when citing this source. Product text, so Chinese. */
    val displayName: String,
    val tier: Tier,
    /** Spec R8 — one clause explaining an unfamiliar foreign institution. */
    val explanation: String? = null,
    val domains: List<String> = emptyList(),
    val accounts: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
)

data class Platform(
    val domain: String,
    val displayName: String,
    val defaultTier: Tier,
    val verification: String = "none",
    val explanation: String? = null,
)

data class PatternRule(
    /** Suffix form, e.g. "*.gov.cn". */
    val match: String,
    val tier: Tier,
    val displayName: String,
    val explanation: String? = null,
)

/** A match plus the length of the domain that matched, so the most specific one can win. */
data class Match<T>(val value: T, val specificity: Int)

/**
 * The publisher registry.
 *
 * Deliberately free of any serialization dependency: the wire format lives in RegistryJson.kt.
 * Keeping the domain model plain means the resolution rules — the part carrying all the risk —
 * can be compiled and exercised without a build system.
 */
class SourceRegistry(
    val version: Int,
    val publishers: List<Publisher>,
    val platforms: List<Platform>,
    val patterns: List<PatternRule>,
    val ownerGroups: Map<String, List<String>> = emptyMap(),
) {

    private val publisherByDomain: Map<String, Publisher> =
        publishers.flatMap { p -> p.domains.map { it.lowercase() to p } }.toMap()

    private val publisherByAccount: Map<String, Publisher> =
        publishers.flatMap { p -> p.accounts.map { it.trim() to p } }.toMap()

    private val platformByDomain: Map<String, Platform> =
        platforms.associateBy { it.domain.lowercase() }

    private val groupByDomain: Map<String, String> =
        ownerGroups.flatMap { (g, ds) -> ds.map { it.lowercase() to g } }.toMap()

    fun publisherFor(host: String): Match<Publisher>? = bestSuffixMatch(host, publisherByDomain)

    fun platformFor(host: String): Match<Platform>? = bestSuffixMatch(host, platformByDomain)

    /**
     * Exact account match only. Spec's no-promotion-by-name rule: resemblance grants nothing,
     * so no fuzzy matching here, ever.
     */
    fun publisherForAccount(account: String?): Publisher? =
        account?.trim()?.takeIf { it.isNotEmpty() }?.let { publisherByAccount[it] }

    fun patternFor(host: String): PatternRule? =
        patterns
            .filter { rule ->
                val suffix = rule.match.removePrefix("*")
                host == suffix.removePrefix(".") || host.endsWith(suffix)
            }
            .maxByOrNull { it.match.length }

    /**
     * Corporate family, for spec R5. Two hosts in one group are one source for corroboration,
     * because they carry each other's reposts.
     */
    fun ownerGroupFor(host: String): String? =
        bestSuffixMatch(host, groupByDomain)?.value

    private fun <T> bestSuffixMatch(host: String, index: Map<String, T>): Match<T>? {
        index[host]?.let { return Match(it, host.length) }
        var best: Match<T>? = null
        for ((domain, value) in index) {
            if (!host.endsWith(".$domain")) continue
            val current = best
            if (current == null || domain.length > current.specificity) {
                best = Match(value, domain.length)
            }
        }
        return best
    }
}

/** Multi-part public suffixes we need to see past to find the registrable name. */
private val MULTIPART_SUFFIXES = listOf(
    "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
    "co.uk", "org.uk", "gov.uk", "ac.uk",
    "com.hk", "org.hk", "gov.hk", "edu.hk",
    "co.jp", "go.jp", "or.jp", "ne.jp",
    "com.tw", "org.tw", "gov.tw",
)

/** Lowercased host with any port, trailing dot and leading "www." removed. Null if unparseable. */
fun normalizeHost(url: String): String? {
    val withoutScheme = url.substringAfter("://", url)
    val hostPart = withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')
        .substringBefore(':')
        .trim()
        .trimEnd('.')
        .lowercase()
    if (hostPart.isEmpty() || !hostPart.contains('.')) return null
    return hostPart.removePrefix("www.")
}

/** "www.apple.com" -> "apple"; "drugoffice.gov.hk" -> "drugoffice". Used by the R3 brand heuristic. */
fun registrableName(host: String): String? = registrableLabels(host)?.first

/** "news.sohu.com" -> "sohu.com"; "www.chp.gov.hk" -> "chp.gov.hk". Used for source identity in R5. */
fun registrableDomain(host: String): String? = registrableLabels(host)?.second

private fun registrableLabels(host: String): Pair<String, String>? {
    val labels = host.split('.').filter { it.isNotEmpty() }
    if (labels.size < 2) return null
    val lastTwo = labels.takeLast(2).joinToString(".")
    var dropped = if (lastTwo in MULTIPART_SUFFIXES) 3 else 2
    if (labels.size < dropped) return null

    // A suffix list is never finished, and the failure is loud now that unlisted domains are
    // named after this rather than shown as 来源不明: anything ending in a two-part suffix the
    // list has not heard of came out called "org", or "com", or "gov". If what is left reads
    // like a suffix rather than a name, take one more label.
    if (labels[labels.size - dropped] in SUFFIXY && labels.size > dropped) dropped += 1

    val name = labels[labels.size - dropped]
    return name to labels.takeLast(dropped).joinToString(".")
}

/** Never a publisher's name, always part of an address. */
private val SUFFIXY = setOf(
    "com", "org", "net", "gov", "edu", "co", "ac", "mil", "int", "info", "biz",
)
