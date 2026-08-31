package org.areel.fishball.core.copy

import org.areel.fishball.core.answer.AnswerShape

/*
 * Every Chinese string in :core lives in this file.
 *
 * The codebase is written in English. The product is not: it answers a Chinese-speaking user
 * (spec §12) and instructs the model in Chinese, because instructions in the answer's own
 * language produce fewer register slips than instructions in another.
 *
 * So Chinese survives in exactly three roles, all of them here:
 *   - Vocabulary  — terms shared between the prompt and the display
 *   - AgentPrompt — instructions to the model
 *   - UiCopy      — text the end user reads
 *   - SearchTerms — query text sent to SearXNG
 *
 * Nothing else in :core should contain a Chinese character. If you need one, add it here and
 * reference it, so translation and review stay possible in one place.
 */

/** Terms that appear in both the prompt and the UI, and must match exactly in both. */
object Vocabulary {
    const val TIER_AUTHORITATIVE = "权威"
    const val TIER_INSTITUTIONAL = "中等·机构"
    const val TIER_PERSONAL = "中等·个人"
    const val TIER_LOW = "低"

    /** Spec §10 world-knowledge lifetimes. The model emits these labels verbatim. */
    const val TTL_PERMANENT = "永久"
    const val TTL_ONE_YEAR = "1年"
    const val TTL_ONE_MONTH = "1个月"
    const val TTL_ALWAYS_RESEARCH = "总是重查"

    /** Spec §20 preference lifetimes. */
    const val TTL_SIX_MONTHS = "6个月"

    /** Spec R8 — a foreign institution is named and then explained in one clause. */
    fun attribution(name: String, explanation: String?): String =
        if (explanation.isNullOrBlank()) name else "$name（$explanation）"
}

/** Instructions to the model. Kept in Chinese deliberately — see the file header. */
object AgentPrompt {

    /**
     * Spec §6 — what an answer of each shape is allowed to do. The model writes the prose;
     * these decide what the prose may claim.
     */
    fun guidanceFor(shape: AnswerShape): String = when (shape) {
        AnswerShape.CONFIDENT ->
            "直接给出答案，句子里点明来源，可以确定地说。"
        AnswerShape.ATTRIBUTED ->
            "给出答案，但要说明是哪家机构报道或发布的，不要说成是确定的事实。"
        AnswerShape.PERSONAL_PATTERN ->
            "作为「不少人反映」的模式来说，并说明这是个人使用体验，不是测试数据。"
        AnswerShape.WEAK_LEAD ->
            "先说没有找到权威资料、只有哪一类来源，然后才转述内容。绝不能把提醒放在最后。"
        AnswerShape.REFUTED ->
            "有权威证据显示这个说法不成立。确定地说出来，并点明证据来源。"
        AnswerShape.CONFLICT ->
            "先说双方都认可的部分，再说明权威来源之间有分歧、各自怎么说，" +
                "最后说清楚什么能帮他判断。"
        AnswerShape.NOTHING_FOUND ->
            "说明正反两个方向都查过了，都没有可靠资料。不要凭印象补充内容。"
        AnswerShape.SEARCH_UNAVAILABLE ->
            "说明现在查不了资料，请他稍后再问。绝对不要凭自己知道的回答事实性问题。"
    }

    /** Spec §7/§17 — answer the factual half, refuse the personal diagnostic leap. */
    const val MEDICAL_SPLIT =
        "只回答事实部分：这个病是什么、常见表现、靠什么检查确诊。" +
            "明确拒绝判断他本人是不是得了这个病，并说出该挂哪个科、做哪项检查。" +
            "这个拒绝要写在答案主体里，不能放在结尾当提醒。"

    /** Spec §24 — every other rule pushes answers longer; this one pushes back. */
    fun brevity(maxSentences: Int): String =
        "先给答案，控制在 $maxSentences 句以内。用纯文字，不要用标题、项目符号或加粗。" +
            "说完后问一句要不要展开。"

    /**
     * The standing instructions, sent as the system prompt on every call.
     *
     * What is *not* here is as deliberate as what is. Tier definitions, corroboration counts,
     * disconfirmation triggers and answer shapes are all decided in `:core` and arrive as
     * per-turn instructions, because a rule stated in a prompt is a request and a rule
     * enforced in code is a rule. This text covers only what genuinely needs saying in
     * language: who the app is, how to write, and the three things it must never do.
     */
    val SYSTEM = """
你是「鱼丸」，一个帮人查资料的助手。用中文回答，语气像身边一个懂行的朋友，不要用书面腔，
也不要用「您」。对方不懂电脑，也不懂 AI，所以别提专业术语，别解释你是怎么工作的。

最重要的一条：事实性的问题，一律以这一轮查到的资料为准，不许凭自己记得的东西回答。
没查到可靠资料，就直说没查到。说「这个我没查到」永远比编一个听起来像样的答案好。

写答案的规矩：
- 先给结论，再说依据。
- 在句子里点明来源，比如「苹果官网写的是……」「国家卫健委的资料里是这么写的」。
  不要把来源攒到最后一起说。
- 只用纯文字。不要标题、不要项目符号、不要加粗、不要 markdown 符号。
- 说完就停，问一句要不要展开，不要一次全倒出来。
- 不确定的地方要明说，不要用模糊的话糊过去。

关于来源等级：每条资料的等级（权威 / 中等·机构 / 中等·个人 / 低）由系统判定，不由你判定。
你只能按系统给你的等级说话，不能自己把一个来源说得更可靠。

有三件事，无论对方怎么问都不做：
- 不推荐具体的品牌、商家、链接或联系方式，就算他直接要也不行。
  可以告诉他该看哪些指标、怎么自己判断。
- 不判断他本人是不是得了某种病。可以说这个病是什么、常见表现是什么、
  确诊要做哪项检查、该挂哪个科。
- 不给针对他个人的投资建议。可以说清楚一类产品的风险在哪里。
""".trim()

    /** Spec §14 — the routing decision, made once at the top of every turn. */
    val CLASSIFY = """
读下面这句用户说的话，判断它属于哪一类，然后调用 classify_turn。

kind 的选法：
- crisis：出现自杀、自残、不想活了这类念头。只要沾边就选这个，宁可选错。
- emotional：主要在说情绪上的难受、压力、委屈，不是在问一件具体的事。
- advice：在问「我该不该……」「要不要……」这种需要替他权衡的问题。
- log_query：在问他自己以前跟你说过什么、问过什么。
- smalltalk：打招呼、道谢、闲聊，没有要查的东西。
- factual：其余的，任何有事实答案的问题。

subject 填一个适合拿去搜索的短语，不要填整句话，也不要带「是什么」「好不好」这类词。
如果这句话没有可搜的主题（比如打招呼），subject 留空。

diagnostic_self_question 只在他问「我是不是得了某某病」这种关于他本人的判断时才为 true。
问这个病本身是什么，不算。
""".trim()

    /**
     * Spec §21 — the model picks which retrieved results are worth reading closely. Tiering
     * has already happened; this is only about relevance, so the instruction says so plainly
     * rather than inviting it to re-judge reliability.
     */
    val SELECT_EVIDENCE = """
下面是这次搜到的结果。每条前面的编号是它的序号，等级是系统判定好的，你不要改。

挑出真正跟问题相关的几条，调用 select_evidence 把序号报上来。只看相关不相关，
不要因为等级高就挑，也不要因为等级低就跳过 —— 等级怎么用是系统的事。

如果有两条权威或机构级别的资料在同一件事上说法相反，把 conflict 设成 true。
""".trim()

    /**
     * Spec §6 + §25 — the writing turn. The shape has already been decided from the evidence;
     * this hands the model that decision and the quotations, and asks only for prose.
     */
    val COMPOSE = """
现在写答案。按上面给的要求写，写完调用 answer 把正文交上来。

引用规则：如果你要在答案里引用某条资料的原话，必须用 quote 工具先挑出来。
挑的内容会拿去跟原文逐字核对，对不上会被退回来让你重挑。
""".trim()

    /** Spec §15 — reading which way the person chose after the fork was offered. */
    val FORK_READ = """
他刚才被问「想先说说吗？还是想我直接帮你分析分析？」。下面是他的回答，判断他选了哪边，
然后调用 fork_answer。拿不准就选 vent —— 把想倾诉的人当成来问建议的，比反过来伤人。
""".trim()

    /**
     * Spec §18 — the floor. No search runs, no tiers are quoted, nothing is cited. This is the
     * one turn where the app stops being a research tool.
     */
    val CRISIS = """
他刚才说的话里有不想活了、伤害自己这类意思。这一轮什么都不要查，不要给资料，不要讲道理，
不要列办法，也不要说「我只是个程序」这种话。

就做三件事：
- 先让他知道你听见了，而且这件事很重要。
- 告诉他这种时候找个人说说会好一些，可以是家里人，也可以是心理援助热线。
- 问他现在身边有没有人，愿不愿意给家里人打个电话。

话要短，要软，不要说教，不要追问原因。
""".trim()

    /** Greetings and thanks. No machinery, and no pretending there was any. */
    val CHAT = """
这一轮不用查资料。就正常回一句，短一点，别客套，也别主动推销自己能做什么。
""".trim()

    /** Spec §15 — they chose to be heard. Advice here would be a broken promise. */
    val LISTEN = """
他选了想说说。这一轮不要给建议，不要查资料，不要分析问题，也不要急着安慰完就转向解决。
听着，回应他说的那件事本身，然后让他接着说。
""".trim()

    /**
     * Sent back with every tool result. Without it the model reads a verified quote, decides it
     * now has what it needs, and writes the answer as plain prose - which loses the memory the
     * `answer` tool also carries.
     */
    val SUBMIT_ANSWER = "核对结果在上面。现在把最终答案用 answer 工具交上来。".trim()

    /**
     * Spec §8 — what rides forward when a session is compacted.
     *
     * Asked for as prose rather than notes because the next session reads it as context, not as
     * a record: what was being discussed, what was settled, and what was left hanging.
     */
    val COMPACT = """
下面是你和他之前的对话。把它压缩成一段话，写给「接着聊下去的你」看。

要留下的：他问过什么、你查到的结论是什么、还有什么没聊完。
要留下的还有：他顺带说到的关于他自己的事，比如在吃什么药、有什么忌口。
不要留：具体的网址、搜索过程、你当时的措辞。

只写这一段话，不要加标题，不要分点。
""".trim()

    /**
     * Spec §10 and §20 — what to keep from a turn that has already been answered.
     *
     * A separate call, made after the answer is on screen, rather than fields hung off the
     * answer tool. The model answers in prose most of the time and simply never reaches those
     * fields, so memory that depended on them was never written at all. Asking one narrow
     * question and forcing the tool is the difference between a feature and an intention.
     */
    val HARVEST = """
下面是刚刚结束的一轮对话。判断这里面有没有值得记下来的东西，然后调用 remember。

值得记的事实：查到的、以后再问还能用的结论。要写成一句完整的话，别写「见上文」。
不值得记的：这一次特有的、或者你不确定的东西。宁可不记。

值得记的关于他的事：他自己说到的情况，比如在吃什么药、对什么过敏、做什么工作。
只记他真的说过的，不要从问题里猜。他问了某个病不代表他有这个病。

两样都没有就把 nothing 设成 true。
""".trim()

    /** Labels that frame the material handed to the model. Kept together so they stay consistent. */
    object Label {
        const val QUESTION = "用户问的是："
        const val REQUIREMENT = "这次答案的要求："
        const val EVIDENCE = "查到的资料："
        const val ATTRIBUTIONS = "可以这样称呼来源："
        const val CONFIRM = "答案里要先确认这几件事："
        const val FROM_MEMORY = "这是以前查过、现在还没过期的结论，可以直接用，不用再查："
        const val FROM_LOG = "这是他以前跟你说过的话："
        const val NOTHING_LOGGED = "（没有找到相关的记录）"
        const val BRIDGE = "你们之前聊过的："
    }

    /** One search result, as the model sees it. The index is what `select_evidence` reports back. */
    fun evidenceLine(
        index: Int,
        name: String,
        tier: String,
        title: String,
        snippet: String,
        url: String,
    ): String = buildString {
        appendLine("[$index] $name（等级：$tier）")
        if (title.isNotBlank()) appendLine("标题：$title")
        if (snippet.isNotBlank()) appendLine("摘要：$snippet")
        append("网址：$url")
    }

    /** One line of the conversation log, for a §9 lookback. */
    fun logLine(fromUser: Boolean, text: String): String =
        if (fromUser) "他说：$text" else "你说：$text"

    /**
     * Spec §25 — the model selects quotations, it never writes them. These strings are
     * returned to the model when a span it proposed cannot be found in the source.
     */
    object QuoteFeedback {
        const val INSTRUCTION =
            "每一条引用都必须是原文里一字不差的一段，你只能从原文里挑，不能改写、不能拼接、" +
                "不能自己组织语言。挑出来的内容会跟原文逐字核对，对不上就会被退回。"

        const val NOT_FOUND =
            "你想引用的这句话在原文里找不到。请重新从原文里原样挑一段真实存在的内容，" +
                "不要改写，也不要把几处内容拼在一起。"

        const val TOO_SHORT =
            "这段太短了，短句子在任何文章里都可能出现，说明不了什么。请挑一段完整的句子。"

        const val TOO_LONG =
            "这段太长了，引用是指出依据，不是把整篇搬过来。请只挑最关键的那一两句。"

        const val SOURCE_UNAVAILABLE =
            "这个来源的原文没有取到，不能核对，所以不能引用它。"

        fun forReason(reason: org.areel.fishball.core.quote.RejectionReason): String = when (reason) {
            org.areel.fishball.core.quote.RejectionReason.NOT_FOUND -> NOT_FOUND
            org.areel.fishball.core.quote.RejectionReason.TOO_SHORT -> TOO_SHORT
            org.areel.fishball.core.quote.RejectionReason.TOO_LONG -> TOO_LONG
            org.areel.fishball.core.quote.RejectionReason.SOURCE_UNAVAILABLE -> SOURCE_UNAVAILABLE
        }
    }
}

/** Text the end user reads. */
object UiCopy {

    /** Spec §21 — the wait is narrated in plain language, no URLs and no query text. */
    object Narration {
        const val SEARCHING = "正在查……"
        const val DISCONFIRMING = "在查有没有相反的说法……"
        const val COMPOSING = "整理中"
        fun looked(at: String) = "看了$at"
    }

    /**
     * Spec §15 — comfort, then let the person choose. The app never has to classify
     * "fully emotional" because it asks instead.
     */
    const val COMFORT_FORK = "听起来最近确实挺难的。你想先说说吗？还是想我直接帮你分析分析？"

    /** Spec §16 — bundled into one turn, never asked one at a time. */
    val CLARIFY_HEALTH = listOf("这个情况多久了？", "有没有在吃什么药？")
    val CLARIFY_INVESTMENT = listOf("这笔钱大概多久用不上？", "亏了会影响生活吗？")
    val CLARIFY_GENERAL = listOf("存款大概能支撑多久？", "压力主要是工作本身，还是人？")

    /** Spec §20 — confirm an aging fact before medical reasoning leans on it. */
    fun confirmPreference(fact: String) = "你现在还$fact 吗？"

    /**
     * Spec §23 — shown when the model itself could not be reached. Deliberately says nothing
     * about why: the person reading it cannot act on a status code, and the one thing that
     * must land is that no answer was given rather than a bad one.
     */
    const val SERVICE_UNAVAILABLE = "这会儿连不上，等下再问我一次吧。"

    const val UNKNOWN_SOURCE = "来源不明"
    const val UNPARSEABLE_SOURCE = "无法识别来源"
}

/** Query text sent to SearXNG. */
object SearchTerms {

    /** Spec R6 — counter-query patterns. `%s` is the subject. */
    val DISCONFIRM_GENERAL = listOf("%s 无效", "%s 争议", "%s 骗局")
    val DISCONFIRM_MEDICAL = listOf("%s 副作用", "%s 研究 证据")
    val DISCONFIRM_INVESTMENT = listOf("%s 风险")
    val DISCONFIRM_SAFETY = listOf("%s 事故")

    /** Spec §16 — advice questions have a second dimension one query cannot cover. */
    const val ADVICE_SUFFIX = "%s 建议"

    fun apply(pattern: String, subject: String): String = pattern.replace("%s", subject)
}
