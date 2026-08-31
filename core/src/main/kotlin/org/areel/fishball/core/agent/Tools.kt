package org.areel.fishball.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.areel.fishball.core.copy.Vocabulary
import org.areel.fishball.core.llm.LlmTool

/**
 * Every structured decision the model makes, as a tool.
 *
 * Nothing here asks it to "reply with JSON". A tool schema is enforced by the provider, so a
 * missing field is retried before it ever reaches this code, and there is no path by which a
 * classification arrives as a paragraph of Chinese that has to be parsed with a regex.
 *
 * The schemas are also where the vocabulary is pinned: TTL labels come from [Vocabulary], so
 * the enum the model is offered and the enum `:core` parses cannot drift apart.
 */
object Tools {

    const val CLASSIFY = "classify_turn"
    const val FORK = "fork_answer"
    const val SELECT = "select_evidence"
    const val QUOTE = "quote"
    const val ANSWER = "answer"
    const val REMEMBER = "remember"

    val classify = LlmTool(
        name = CLASSIFY,
        description = "判断这一轮用户说的话属于哪一类，以及可以拿去搜索的主题。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                enumProp(
                    "kind",
                    listOf("factual", "advice", "emotional", "crisis", "log_query", "smalltalk"),
                    "这句话的类别。拿不准是不是 crisis 时，选 crisis。",
                )
                enumProp(
                    "topic",
                    listOf("general", "health", "medication", "investment", "safety"),
                    "问题涉及的领域。",
                )
                stringProp("subject", "适合拿去搜索的短语，不要整句。没有可搜主题时留空。")
                boolProp(
                    "diagnostic_self_question",
                    "他是不是在问「我本人是不是得了某种病」。问病本身是什么不算。",
                )
            }
            putJsonArray("required") {
                add("kind"); add("topic"); add("subject"); add("diagnostic_self_question")
            }
        },
    )

    /** Spec §15 — the person chose; this only reads which way. */
    val fork = LlmTool(
        name = FORK,
        description = "他刚才被问「想先说说，还是想我帮你分析」。判断他选了哪个。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                enumProp("choice", listOf("vent", "advice"), "vent 是想倾诉，advice 是想要建议。")
            }
            putJsonArray("required") { add("choice") }
        },
    )

    val select = LlmTool(
        name = SELECT,
        description = "从搜到的结果里挑出跟问题真正相关的几条。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("indices") {
                    put("type", "array")
                    put("description", "相关结果的序号。不相关的不要挑，宁可少挑。")
                    putJsonObject("items") { put("type", "integer") }
                }
                boolProp("conflict", "是否有两条权威或机构级别的资料在同一件事上说法相反。")
            }
            putJsonArray("required") { add("indices"); add("conflict") }
        },
    )

    /**
     * Spec §25. The model hands over a span it believes is in the source; the verifier decides
     * whether it is. A rejection comes back as a failed tool result with the reason, so it can
     * pick again inside the same turn.
     */
    val quote = LlmTool(
        name = QUOTE,
        description = "从某条资料的原文里原样挑一段话。会跟原文逐字核对，对不上会被退回。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                stringProp("url", "这段话出自哪条资料的网址。")
                stringProp("text", "原文里一字不差的一段。不要改写，不要拼接。")
            }
            putJsonArray("required") { add("url"); add("text") }
        },
    )

    /**
     * Spec §10/§20. Asked on its own after the answer, because hanging these off `answer` meant
     * they were only ever filled on the turns where the model happened to use that tool.
     */
    val remember = LlmTool(
        name = REMEMBER,
        description = "记下这一轮里值得长期保留的东西。没有就把 nothing 设成 true。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                boolProp("nothing", "这一轮没有值得记的东西。")
                putJsonObject("world_fact") {
                    put("type", "object")
                    put("description", "查到的、以后还能用的事实。没有就不要填。")
                    putJsonObject("properties") {
                        stringProp("question", "这个事实回答的是什么问题。写成一句完整的问句。")
                        stringProp("answer", "一句话的结论。要能单独看懂。")
                        enumProp(
                            "ttl",
                            listOf(
                                Vocabulary.TTL_PERMANENT,
                                Vocabulary.TTL_ONE_YEAR,
                                Vocabulary.TTL_ONE_MONTH,
                                Vocabulary.TTL_ALWAYS_RESEARCH,
                            ),
                            "多久会过期。价格、排行、政策这类容易变的选「总是重查」。",
                        )
                    }
                    putJsonArray("required") { add("question"); add("answer"); add("ttl") }
                }
                putJsonObject("about_user") {
                    put("type", "array")
                    put("description", "他自己说到的、关于他本人的事。没有就不要填。")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            stringProp("text", "一句话，比如「对青霉素过敏」。")
                            enumProp(
                                "kind",
                                listOf("medical_constant", "profile", "current_state", "transient"),
                                "过敏、慢性病选 medical_constant；在吃的药选 current_state；" +
                                    "「最近」怎么样选 transient。",
                            )
                        }
                        putJsonArray("required") { add("text"); add("kind") }
                    }
                }
            }
            putJsonArray("required") { add("nothing") }
        },
    )

    val answer = LlmTool(
        name = ANSWER,
        description = "把写好的答案交上来。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                stringProp("text", "给用户看的答案正文。纯文字，不要 markdown。")
            }
            putJsonArray("required") { add("text") }
        },
    )

    // ---- schema helpers -----------------------------------------------------------------

    private fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(build)

    private fun kotlinx.serialization.json.JsonObjectBuilder.stringProp(
        name: String,
        description: String,
    ) = putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.boolProp(
        name: String,
        description: String,
    ) = putJsonObject(name) {
        put("type", "boolean")
        put("description", description)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.enumProp(
        name: String,
        values: List<String>,
        description: String,
    ) = putJsonObject(name) {
        put("type", "string")
        put("description", description)
        putJsonArray("enum") { values.forEach { add(it) } }
    }
}
