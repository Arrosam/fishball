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

    const val SEARCH = "search"
    const val READ = "read_page"
    const val HISTORY = "read_log"
    const val QUOTE = "quote"
    const val ANSWER = "answer"
    const val NOTE_USER = "note_user"
    const val NOTE_FACT = "note_fact"
    const val RECALL = "recall_terms"

    /** Spec §10 — what to look memory up by, which is not the question. */
    val recallTerms = LlmTool(
        name = RECALL,
        description = "列出回答这个问题需要先知道的事实。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("facts") {
                    put("type", "array")
                    put("description", "要查的事实，短语，最多三条。")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("about_user") {
                    put("type", "array")
                    put("description", "关于他本人的、会影响答案的事，最多三条。没有就留空。")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add("facts") }
        },
    )

    /**
     * Spec §25. The model hands over a span it believes is in the source; the verifier decides
     * whether it is. A rejection comes back as a failed tool result with the reason, so it can
     * pick again inside the same turn.
     */
    /**
     * Looking things up, as something the model does rather than something done for it.
     *
     * It used to be a step: a classifier decided the turn was factual, the engine ran one
     * search, a second model sifted the results, and the writer was handed what survived. That
     * pipeline could only ever ask one question - the one the classifier extracted - and it
     * asked it before anyone had read a word of the answer.
     *
     * As a tool it can ask several at once, read what came back, and ask again in the light of
     * it, which is what somebody actually does when they look something up. `queries` is a list
     * because the common case is several angles on one question, and they are run together
     * rather than one after another.
     *
     * Every result comes back with the tier the registry gave it. That judgement stays in code:
     * the model chooses what to look for and what to make of it, and never what a source is
     * worth.
     */
    val search = LlmTool(
        name = SEARCH,
        description = "查资料。一次可以给几条不同的查询，会同时去查；看完结果还可以再查一轮。" +
            "每条结果都带系统判定的来源等级。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("queries") {
                    put("type", "array")
                    put(
                        "description",
                        "要搜的短语，像在搜索框里打的那样，不要整句问话。一次最多 " +
                            "$MAX_QUERIES 条。",
                    )
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add("queries") }
        },
    )

    /** More than this in one call is a scattergun, not a search. */
    const val MAX_QUERIES = 5

    /**
     * Opening one of the results and reading it.
     *
     * A search result is a title and two lines a search engine chose to make the page look
     * relevant, and the gap between that and the page is where most of the wrong answers live:
     * the summary says a drug is safe in pregnancy, the page says it is safe in the first two
     * trimesters. Until this existed the model could only ever read the advertisement.
     *
     * `find` and the link list are what make it navigation rather than a single fetch. A long
     * page comes back a window at a time and `find` moves the window; the links let it carry on
     * to the notice the page is quoting rather than stopping at the page that mentions it.
     */
    val read = LlmTool(
        name = READ,
        description = "打开一条资料，看它的正文。搜索结果只有两行摘要，摘要和原文常常不是一回事；" +
            "重要的结论要点开原文看过再说。页面里的链接也会列出来，可以接着点进去。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                stringProp("url", "要打开的网址。可以是搜索结果里的，也可以是别的页面上列出的链接。")
                stringProp(
                    "find",
                    "想在这一页里找的词。页面很长时，会从这个词附近开始给你看。不填就从头看。",
                )
            }
            putJsonArray("required") { add("url") }
        },
    )

    /**
     * Going back through what was actually said, rather than guessing at it.
     *
     * The log has always been searchable with a time window - `MemoryStore.searchTurns` was
     * written for spec §9's "what did I ask you yesterday" - and until now nothing called it.
     * The old classifier had a `log_query` kind that would have, and it went with the pipeline.
     * So the model could be asked 「上次那个」 and had only the last few turns of prompt to
     * answer from.
     *
     * Both halves are optional on purpose. A keyword with no dates is "find where we discussed
     * this"; dates with no keyword is "what did we talk about on Tuesday", which is a question
     * people actually ask and which no amount of searching the web can answer.
     */
    val history = LlmTool(
        name = HISTORY,
        description = "翻你们以前说过的话。可以按词找，也可以只给一段日期，看那几天聊了什么。" +
            "他说「上次」「昨天」「之前那个」的时候用这个，别靠印象答。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                stringProp("keyword", "要找的词。只想按时间翻就留空。")
                stringProp("from", "从哪天开始，写成 2026-09-01 这样。不填就从最早的算起。")
                stringProp("to", "到哪天为止，同样的写法。不填就到今天。")
            }
        },
    )

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
    /**
     * Spec §9 — what this person just said about themselves.
     *
     * Narrow on purpose. It used to share one tool with the world-fact harvest, which meant the
     * model was asked about cached answers while looking at a message that had not been
     * answered yet. The two now fire at different moments off different material, so they are
     * two tools.
     */
    val noteUser = LlmTool(
        name = NOTE_USER,
        description = "记下他刚刚说的、关于他本人的事。没有就把 nothing 设成 true。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                boolProp("nothing", "这句话里没有关于他本人的、值得记的事。")
                putJsonObject("outdated_about_user") {
                    put("type", "array")
                    put("description", "已经不成立的旧记录的编号，来自上面列出的那几条。")
                    putJsonObject("items") { put("type", "integer") }
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

    /** Spec §10 — the answer that was just given, if it is worth keeping. */
    val noteFact = LlmTool(
        name = NOTE_FACT,
        description = "记下这一轮查到的、以后还用得上的结论。没有就把 nothing 设成 true。",
        inputSchema = obj {
            put("type", "object")
            putJsonObject("properties") {
                boolProp("nothing", "这一轮没有值得记的结论。")
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
                putJsonObject("outdated_facts") {
                    put("type", "array")
                    put("description", "已经不成立的旧结论的编号，来自上面列出的那几条。")
                    putJsonObject("items") { put("type", "integer") }
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
