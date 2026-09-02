package org.areel.fishball.core.trust

import org.areel.fishball.core.text.Similarity

/** A resolved search result, ready to be counted as evidence. */
data class Evidence(
    val hit: SearchHit,
    val resolution: Resolution,
) {
    val text: String get() = (hit.title + " " + hit.snippet).trim()
}

/**
 * Spec R4/R5 — how many independent personal-tier sources are needed before a pattern may be stated,
 * and what "independent" means.
 *
 * A single comment is never evidence. The threshold scales with how alone the claim is
 * standing, and echo is not corroboration.
 */
object Corroboration {

    /**
     *  |                                    | ordinary | high-stakes |
     *  |------------------------------------|---------|---------|
     *  | no uncontested high-confidence     |    5    |    9    |
     *  | uncontested high-confidence exists |    3    |    5    |
     *
     * 9 is effectively a ban with an escape hatch, and that is intended — see spec R4.
     */
    fun requiredSources(topic: Topic, uncontestedHighConfidence: Boolean): Int = when {
        topic.important && uncontestedHighConfidence -> 5
        topic.important -> 9
        uncontestedHighConfidence -> 3
        else -> 5
    }

    /**
     * High-confidence backing means at least one AUTHORITATIVE or INSTITUTIONAL source supports the claim
     * and none of them contradicts it. A contested institutional picture is *not* backing —
     * it triggers R7 disclosure instead.
     */
    fun hasUncontestedHighConfidence(supporting: List<Evidence>, contradicting: List<Evidence>): Boolean {
        val strong = { e: Evidence -> e.resolution.tier >= Tier.HIGH }
        return supporting.any(strong) && contradicting.none(strong)
    }

    /**
     * Collapses evidence into independent groups. Two items are the same source when they
     * share a publisher, share a corporate owner, share a registrable domain, or carry
     * near-duplicate text — the last being the guard that stops content-farm-style mass reposting
     * from turning one claim into a consensus.
     */
    fun independentGroups(items: List<Evidence>, registry: SourceRegistry): List<List<Evidence>> {
        val groups = mutableListOf<MutableList<Evidence>>()
        val keys = mutableListOf<String?>()

        for (item in items) {
            val key = identityKey(item, registry)
            val existing = groups.indices.firstOrNull { i ->
                (key != null && keys[i] == key) || groups[i].any { isEcho(it.text, item.text) }
            }
            if (existing != null) {
                groups[existing].add(item)
            } else {
                groups.add(mutableListOf(item))
                keys.add(key)
            }
        }
        return groups
    }

    fun countIndependent(items: List<Evidence>, registry: SourceRegistry): Int =
        independentGroups(items, registry).size

    /** The decision spec R4 actually gates: may this personal-tier pattern be stated at all? */
    fun canStatePattern(
        personalEvidence: List<Evidence>,
        registry: SourceRegistry,
        topic: Topic,
        uncontestedHighConfidence: Boolean,
    ): Boolean = countIndependent(personalEvidence, registry) >=
        requiredSources(topic, uncontestedHighConfidence)

    private fun identityKey(item: Evidence, registry: SourceRegistry): String? {
        when (val via = item.resolution.via) {
            is Via.RegistryDomain -> return "pub:${via.publisherId}"
            is Via.PlatformPublisher -> return "pub:${via.publisherId}"
            else -> Unit
        }
        val host = normalizeHost(item.hit.url) ?: return null
        registry.ownerGroupFor(host)?.let { return "owner:$it" }
        return "domain:${registrableDomain(host) ?: host}"
    }

    private fun isEcho(a: String, b: String): Boolean = Similarity.isEcho(a, b)
}
