package org.areel.fishball.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which model a valid key ends up driving.
 *
 * Worth pinning because it is invisible at runtime — nothing in the UI says which model
 * answered, so a silent fallback to the wrong one would show up as "the answers got worse"
 * weeks later, with nothing to point at.
 */
class ModelChoiceTest {

    @Test
    fun `pro wins when both are offered`() {
        assertEquals(
            "fishball-pro",
            HydrogenClient.pickModel(listOf("fishball-flash", "fishball-pro")),
        )
    }

    @Test
    fun `flash is the fallback, not a peer`() {
        assertEquals("fishball-flash", HydrogenClient.pickModel(listOf("fishball-flash")))
    }

    @Test
    fun `a dated or suffixed id still resolves`() {
        assertEquals(
            "fishball-pro-2026-08-01",
            HydrogenClient.pickModel(listOf("fishball-flash-2026-01-01", "fishball-pro-2026-08-01")),
        )
    }

    /**
     * The important one. Anything else on the catalogue is somebody else's model, and running
     * on it silently would change the product without a decision being made.
     */
    @Test
    fun `nothing else is ever substituted`() {
        assertNull(HydrogenClient.pickModel(listOf("gpt-4o", "claude-opus-4", "llama-3-70b")))
        assertNull(HydrogenClient.pickModel(emptyList()))
    }

    @Test
    fun `model ids are read from either dialect's listing`() {
        val anthropic = """{"data":[{"id":"fishball-pro","display_name":"Pro"}]}"""
        val openai = """{"object":"list","data":[{"id":"fishball-pro","object":"model"}]}"""
        listOf(anthropic, openai).forEach { body ->
            assertEquals(
                listOf("fishball-pro"),
                HydrogenClient.modelIds(Json.parseToJsonElement(body).jsonObject),
            )
        }
    }
}
