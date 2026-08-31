package org.areel.fishball.core.trust

import org.areel.fishball.core.copy.UiCopy

/**
 * Resolves one search result to a trust tier.
 *
 * Implements spec §5 R1–R8 and the four scope rules. The ordering is publisher-first: the
 * publisher is the source, and the domain is only one way of identifying one. See
 * docs/04-source-tiers.md.
 *
 * Resolution, most specific first:
 *   1. platform (venue, no tier of its own) -> identify the account -> publisher, capped
 *   2. registry publisher domain
 *   3. publisher pattern (*.gov.cn, *.int ...)
 *   4. brand heuristic (R3)
 *   5. LOW, promotable
 *
 * Between 1 and 2 the longer matched domain wins, so an explicit publisher on a subdomain of
 * a platform still resolves as that publisher.
 */
class TrustResolver(private val registry: SourceRegistry) {

    fun resolve(hit: SearchHit, ctx: ClaimContext = ClaimContext()): Resolution {
        val host = normalizeHost(hit.url) ?: return Resolution(
            tier = Tier.LOW,
            displayName = UiCopy.UNPARSEABLE_SOURCE,
            via = Via.Unknown,
            notes = listOf("URL could not be parsed"),
        )

        val platform = registry.platformFor(host)
        val publisher = registry.publisherFor(host)

        val base = when {
            platform != null && (publisher == null || platform.specificity >= publisher.specificity) ->
                resolveOnPlatform(hit, platform.value)

            publisher != null -> Resolution(
                tier = publisher.value.tier,
                displayName = publisher.value.displayName,
                explanation = publisher.value.explanation,
                via = Via.RegistryDomain(publisher.value.id),
            )

            else -> resolveUnlisted(host, ctx)
        }

        return applyScopeRules(base, hit, ctx)
    }

    /**
     * A platform grants no tier. The account is the source — and it may only rise above the
     * platform floor on a verification signal or an exact registry match. Name resemblance
     * grants nothing (no-promotion-by-name); a fuzzy match here would hand AUTHORITATIVE to an
     * impostor, which on a medical app is the worst failure available.
     */
    private fun resolveOnPlatform(hit: SearchHit, platform: Platform): Resolution {
        val floor = Resolution(
            tier = platform.defaultTier,
            displayName = platform.displayName,
            explanation = platform.explanation,
            via = Via.PlatformFloor(platform.domain, reason = "publisher could not be identified"),
        )

        val account = hit.account?.trim().orEmpty()
        if (account.isEmpty()) return floor

        val known = registry.publisherForAccount(account)
        val identified = known != null || hit.verified
        if (!identified) {
            return floor.copy(
                via = Via.PlatformFloor(
                    platform.domain,
                    reason = "account '$account' is unverified and not in the registry",
                ),
                notes = listOf("a name resembling an authority is not identification"),
            )
        }
        if (known == null) {
            // Verified, but we cannot say verified *as whom*. Not evidence of authority.
            return floor.copy(
                via = Via.PlatformFloor(
                    platform.domain,
                    reason = "account '$account' is verified but not in the registry",
                ),
            )
        }

        // platform-publisher-cap: a secondary channel is never the primary record.
        return Resolution(
            tier = known.tier.atMost(Tier.INSTITUTIONAL),
            displayName = known.displayName,
            explanation = known.explanation,
            via = Via.PlatformPublisher(platform.domain, known.id),
            notes = if (known.tier > Tier.INSTITUTIONAL) {
                listOf("posted on a platform rather than the publisher's own site; capped at INSTITUTIONAL")
            } else {
                emptyList()
            },
        )
    }

    private fun resolveUnlisted(host: String, ctx: ClaimContext): Resolution {
        registry.patternFor(host)?.let { rule ->
            return Resolution(rule.tier, rule.displayName, rule.explanation, Via.PatternMatch(rule.match))
        }

        // R3: a domain named after a brand in the question is that brand's official site.
        val name = registrableName(host)
        val brand = ctx.brandsInQuery.firstOrNull { it.equals(name, ignoreCase = true) }
        if (brand != null && name != null) {
            return Resolution(
                tier = Tier.AUTHORITATIVE,
                displayName = brand,
                via = Via.BrandOfficial(brand),
            )
        }

        return Resolution(
            tier = Tier.LOW,
            displayName = UiCopy.UNKNOWN_SOURCE,
            via = Via.Unknown,
            promotable = true,
            notes = listOf("unlisted domain '$host'; defaulted to LOW"),
        )
    }

    /** Claim-scoped rules. Authority attaches to a claim, never to a domain outright. */
    private fun applyScopeRules(base: Resolution, hit: SearchHit, ctx: ClaimContext): Resolution {
        var result = base

        // R3 scope: a brand is authoritative about its own product's attributes, and merely
        // interested about whether that product is any good.
        if (result.via is Via.BrandOfficial && ctx.claimKind != ClaimKind.OBJECTIVE_ATTRIBUTE) {
            result = result.copy(
                tier = result.tier.atMost(Tier.INSTITUTIONAL),
                notes = result.notes + "manufacturer making an evaluative claim about its own product",
            )
        }

        // seller-efficacy: can demote anything, including AUTHORITATIVE. A seller's page is
        // never evidence that its own product works.
        if (hit.sellsSubject && ctx.claimKind == ClaimKind.EFFICACY) {
            result = result.copy(
                tier = Tier.LOW,
                promotable = false,
                notes = result.notes + "seller asserting the efficacy of what it sells",
            )
        }

        return result
    }
}
