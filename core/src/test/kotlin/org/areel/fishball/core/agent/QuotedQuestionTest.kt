package org.areel.fishball.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.areel.fishball.core.copy.AgentPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A question that quotes an earlier answer, as the model receives it.
 *
 * It used to be prose - the quotation and the new question joined into one string - and prose
 * was ambiguous in the one way that matters here: the thing being quoted is itself an answer
 * full of sentences, so there was nothing to say where it stopped and the question began. Two
 * named fields cannot be misread.
 */
class QuotedQuestionTest {

    @Test
    fun `both halves arrive under their own names`() {
        val json = Json.parseToJsonElement(
            AgentPrompt.quotedQuestion(quoted = "孕晚期禁用。", said = "那孕早期呢"),
        ).jsonObject

        assertEquals("孕晚期禁用。", json["user_quoted"]?.jsonPrimitive?.content)
        assertEquals("那孕早期呢", json["user_said"]?.jsonPrimitive?.content)
    }

    /**
     * The one that would have broken it, and the reason this is built rather than joined.
     *
     * Answers from this app are full of 「」, straight quotes and line breaks - the quoting tool
     * exists to put source text in them. A hand-assembled object breaks on the first of those,
     * and a model handed malformed JSON does not report it: it reads the parts it can and
     * guesses at the rest.
     */
    @Test
    fun `an answer full of quotes and line breaks survives being carried`() {
        val nasty = """
            说明书写的是「孕晚期禁用」。
            他们的原话:  "do not use in the third trimester"
            反斜杠也有: C:\Users\x  以及 emoji 🐟
        """.trimIndent()

        val json = Json.parseToJsonElement(
            AgentPrompt.quotedQuestion(quoted = nasty, said = "这段是什么意思"),
        ).jsonObject

        assertEquals(nasty, json["user_quoted"]?.jsonPrimitive?.content)
        assertEquals("这段是什么意思", json["user_said"]?.jsonPrimitive?.content)
    }

    /** Whole, not the few words the chip showed. The model is reasoning about the answer. */
    @Test
    fun `the quotation is carried at full length`() {
        val long = "布".repeat(4_000)
        val json = Json.parseToJsonElement(
            AgentPrompt.quotedQuestion(quoted = long, said = "为什么"),
        ).jsonObject
        assertEquals(4_000, json["user_quoted"]?.jsonPrimitive?.content?.length)
    }

    /** And the framing above it says what the object is, in the language everything else is in. */
    @Test
    fun `the label names what follows`() {
        assertTrue(
            AgentPrompt.Label.QUESTION_QUOTED.contains("JSON"),
            AgentPrompt.Label.QUESTION_QUOTED,
        )
    }
}
