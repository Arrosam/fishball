package org.areel.fishball.core.trust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs TrustSpec against the registry that actually ships, loaded from the bundled
 * source-tiers.json — not against a mock. If the data and the rules disagree, this fails.
 */
class TrustResolverTest {

    private val registry = loadBundledRegistry()

    @Test
    fun `trust rules hold against the shipped registry`() {
        val failures = TrustSpec.run(registry)
        assertTrue(
            failures.isEmpty(),
            "${failures.size} trust rule failures:\n" + failures.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `registry parsed with the expected shape`() {
        assertTrue(registry.version >= 4, "registry version ${registry.version}")
        assertTrue(registry.publishers.size >= 50)
        assertTrue(registry.platforms.isNotEmpty())
        assertTrue(registry.patterns.isNotEmpty())
        assertTrue(registry.ownerGroups.isNotEmpty())
    }

    @Test
    fun `every publisher is usable for attribution`() {
        // R8 needs a Chinese name for every source; a blank one produces a citation the
        // user cannot read, which is worse than no citation.
        val nameless = registry.publishers.filter { it.displayName.isBlank() }
        assertEquals(emptyList(), nameless.map { it.id })
    }

    @Test
    fun `no domain is claimed by both a publisher and a platform`() {
        val publisherDomains = registry.publishers.flatMap { it.domains }.toSet()
        val platformDomains = registry.platforms.map { it.domain }.toSet()
        assertEquals(emptySet(), publisherDomains intersect platformDomains)
    }
}
