package org.areel.fishball.core

import org.areel.fishball.core.trust.loadBundledRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs BackendSpec — spec §7–§24, memory, sessions and R6 — against the registry that ships.
 */
class BackendTest {

    @Test
    fun `backend rules hold`() {
        val failures = BackendSpec.run(loadBundledRegistry())
        assertTrue(
            failures.isEmpty(),
            "${failures.size} backend rule failures:\n" + failures.joinToString("\n") { "  - $it" },
        )
    }
}
