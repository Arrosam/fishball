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
