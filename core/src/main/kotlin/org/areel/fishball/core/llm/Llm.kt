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

    /**
     * [onDelta] receives the reply as it is produced, when [LlmRequest.stream] asks for it.
     * The returned [LlmResult] is the same either way, so callers that do not care about the
     * middle of the answer can ignore it entirely.
     */
    suspend fun complete(request: LlmRequest, onDelta: (LlmDelta) -> Unit = {}): LlmResult

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
    /**
     * Which model answers this one call, when it should not be the one the client is pointed at.
     *
     * The conversation runs on the model the user chose. The work around it - reading a fork,
     * sifting search results, writing a clarifying question, filing a memory - is bookkeeping,
     * and there is no reason for it to be answered by whichever model the user is paying
     * conversation prices for. Null leaves the client's own model alone.
     *
     * An override that the key cannot use falls back to that model rather than failing the
     * call, and never re-points it: a side call is not a reason to change what the whole app
     * runs on. See `HydrogenClient`.
     */
    val model: String? = null,
    /**
     * How long to think before answering, when the model takes direction on it.
     *
     * Not a knob for its own sake. The model behind this proxy stopped thinking by default and
     * now wants asking, and the difference is visible: the same question answered with one
     * block of text, or with the reasoning in front of it. Null sends nothing and takes
     * whatever the model does on its own.
     */
    val effort: Effort? = null,
)

/**
 * How hard to think first.
 *
 * Two levels because there are two kinds of call here: the turn the user is waiting on, and
 * everything done around it. [MAX] is for the conversation itself, where the whole point of the
 * app is getting the answer right. [HIGH] is for the bookkeeping, which still wants a model that
 * reasons but is not what anybody is waiting to read.
 */
enum class Effort(val wire: String) {
    HIGH("high"),
    MAX("max"),
}

/** A piece of a reply, as it arrives. */
sealed class LlmDelta {
    /** The model's reasoning. Shown while waiting, never kept as part of the answer. */
    data class Thinking(val text: String) : LlmDelta()

    /** The reply proper. */
    data class Text(val text: String) : LlmDelta()
}

data class LlmMessage(val role: Role, val content: List<LlmContent>) {
    enum class Role { USER, ASSISTANT }

    companion object {
        fun user(text: String) = LlmMessage(Role.USER, listOf(LlmContent.Text(text)))
        fun assistant(text: String) = LlmMessage(Role.ASSISTANT, listOf(LlmContent.Text(text)))
    }
}

sealed class LlmContent {
    data class Text(val text: String) : LlmContent()

    /**
     * A picture the user attached, as bytes rather than a path.
     *
     * `:core` has no filesystem and no Android, so what crosses this boundary is already
     * base64: whoever picked the image owns reading and shrinking it, and this layer only has
     * to know the media type to declare.
     */
    data class Image(val mediaType: String, val base64: String) : LlmContent()

    /** The model asking for a tool to be run. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : LlmContent()

    /**
     * What came back. [isError] is how §25's rejections reach the model: a fabricated quote
     * returns as a failed tool result carrying the reason, in the same turn, so it can pick
     * again rather than being cut off.
     */
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : LlmContent()

    /**
     * A block this app has no opinion about, kept exactly as it arrived.
     *
     * These models answer with a `thinking` block ahead of everything else, and the quote loop
     * in §25 hands the assistant turn straight back so tool results can be attached to it. Drop
     * a block on the way in and the turn that goes back out is not the turn that came in - which
     * providers are entitled to reject, and which would corrupt the model's own record of what
     * it just decided. Round-tripping unknown blocks costs nothing and keeps that honest.
     *
     * "Kept exactly as it arrived" describes this side of the boundary, not the wire. Handing a
     * block back in the shape it came in is not the same as the model being shown it, and for
     * `thinking` on this proxy the two come apart: it is accepted, and read only sometimes -
     * 7/20 streamed, against 20/20 for the same words as text. Rewriting it into a text block to
     * close that gap was tried and withdrawn, because the model then copied the rewrite into its
     * answers. See `HydrogenClient.block` and `ThinkingRoundTripTest`.
     */
    data class Opaque(val raw: JsonObject) : LlmContent()
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

/**
 * Meaning-based recall, as two models that are not the chat model.
 *
 * Kept separate from [LlmClient] because it answers a different question. The chat model writes;
 * these two decide what the app is allowed to remember having read — and the reason memory needs
 * them is that word overlap is the wrong test: "布洛芬伤胃吗" and "吃布洛芬会不会胃疼" share
 * almost no characters and are the same question.
 */
interface Retrieval {

    /** One vector per input, in the order given. Empty on failure — never a partial list. */
    suspend fun embed(texts: List<String>): List<List<Float>>

    /**
     * Indices of [documents], best first, as judged against [query].
     *
     * A second opinion on top of the vectors: cosine finds things in the same neighbourhood,
     * which for a cache of answers is not the same as finding the question that was asked.
     * Returns the original order on failure, so a dead reranker degrades to cosine alone.
     */
    suspend fun rerank(query: String, documents: List<String>): List<Scored>
}

data class Scored(val index: Int, val score: Double)

sealed class KeyCheck {
    data class Valid(val models: List<String>, val chosen: String) : KeyCheck()

    /** The key was understood and refused. Wrong key, not a broken network. */
    object Rejected : KeyCheck()

    /**
     * Key good, proxy up, neither model available. Its own case because it is somebody's
     * deployment to fix, and collapsing it into [Unreachable] would send them to check a
     * network that is working.
     */
    data class NoModel(val offered: List<String>) : KeyCheck()

    data class Unreachable(val reason: String) : KeyCheck()
}
