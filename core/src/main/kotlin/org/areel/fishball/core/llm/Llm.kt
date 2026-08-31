package org.areel.fishball.core.llm

import kotlinx.serialization.json.JsonObject

/**
 * The model, as the rest of the app is allowed to see it.
 *
 * `TurnEngine` decides *what* happens; this is how the parts that need language get done —
 * classifying a question, choosing search terms, selecting quotations, writing the prose. The
 * port exists so none of that is welded to one vendor's JSON, and so the driver can be tested
 * against a scripted model instead of a network.
 *
 * Tool use rather than "reply in JSON": the schema is enforced by the provider, a malformed
 * call is retried by the provider, and the model cannot wander into prose where a decision was
 * asked for. Every structured decision in this app goes through a tool.
 */
interface LlmClient {

    suspend fun complete(request: LlmRequest): LlmResult

    /**
     * Spec §1 — the gate. Checks the key is real and reports what it can drive.
     *
     * Doubles as model discovery: the proxy in front of this is somebody's own deployment and
     * the catalogue is theirs to change, so the app asks rather than shipping a guess that
     * breaks the day a model is retired.
     */
    suspend fun validate(): KeyCheck
}

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmTool> = emptyList(),
    /** Name of the tool the model must call. Null lets it answer in prose. */
    val forceTool: String? = null,
    val maxTokens: Int = 1024,
    /** Zero for decisions, warmer for prose. A classifier that improvises is a bug. */
    val temperature: Double = 0.0,
)

data class LlmMessage(val role: Role, val content: List<LlmContent>) {
    enum class Role { USER, ASSISTANT }

    companion object {
        fun user(text: String) = LlmMessage(Role.USER, listOf(LlmContent.Text(text)))
        fun assistant(text: String) = LlmMessage(Role.ASSISTANT, listOf(LlmContent.Text(text)))
    }
}

sealed class LlmContent {
    data class Text(val text: String) : LlmContent()

    /** The model asking for a tool to be run. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : LlmContent()

    /**
     * What came back. [isError] is how §25's rejections reach the model: a fabricated quote
     * returns as a failed tool result carrying the reason, in the same turn, so it can pick
     * again rather than being cut off.
     */
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : LlmContent()
}

data class LlmTool(
    val name: String,
    val description: String,
    /** JSON Schema for the tool's arguments. */
    val inputSchema: JsonObject,
)

sealed class LlmResult {
    data class Ok(
        val text: String,
        val toolCalls: List<LlmContent.ToolUse> = emptyList(),
        /** The assistant turn as the model produced it, to append before any tool results. */
        val raw: LlmMessage,
    ) : LlmResult()

    /**
     * Distinct from an empty answer, and it must stay that way: spec §23 turns on the app
     * knowing the difference between "found nothing" and "could not look".
     */
    data class Failed(val reason: String, val retryable: Boolean) : LlmResult()
}

sealed class KeyCheck {
    data class Valid(val models: List<String>, val chosen: String) : KeyCheck()

    /** The key was understood and refused. Wrong key, not a broken network. */
    object Rejected : KeyCheck()

    data class Unreachable(val reason: String) : KeyCheck()
}
