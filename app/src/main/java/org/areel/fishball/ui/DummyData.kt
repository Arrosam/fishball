package org.areel.fishball.ui

/**
 * Scripted content for the frontend dummy - the second of the two places Chinese is
 * allowed in :app (the other is res/values/strings.xml). This is sample *product output*,
 * not UI chrome, so it stays here where it can be read as prose and judged as prose.
 *
 * Every reply here is written to a shape `:core`'s AnswerPlanner can actually produce — the
 * point of the dummy is to see whether those shapes read well to a non-technical person
 * before any of it is wired up. Nothing here calls the backend.
 */
data class DemoMessage(
    val fromUser: Boolean,
    val text: String,
    val sources: List<Source> = emptyList(),
    /**
     * What the meter reads. Null draws nothing at all - absence is rendered as absence, since
     * an empty bar would claim an answer exists that we have zero confidence in.
     */
    val confidence: Confidence? = null,
    /** CONFLICT is not a point on the scale, so it renders the fault line instead of cells. */
    val conflict: Boolean = false,
    /** Which AnswerPlan shape this illustrates. For reading the fixture, not shown in the UI. */
    val shape: String? = null,
)

data class DemoExchange(
    val question: String,
    val narration: List<String>,
    val reply: DemoMessage,
)

object Demo {


    /**
     * Memory-screen fixture. TTL words are the designer's proposals from
     * docs/06-design-directions.md Appendix 2 and are not yet approved copy.
     */
    val worldMemories = listOf(
        MemoryRowData("iPhone 17 Pro 电池容量 3582mAh", "长期"),
        MemoryRowData("甲亢确诊要靠甲状腺功能五项", "长期"),
        MemoryRowData("布洛芬常见副作用是胃部不适", "一年"),
        MemoryRowData("某只 ETF 今年的表现", "每次重查"),
        MemoryRowData("适量咖啡对多数健康成年人无明显害处", "一年"),
    )

    val personalMemories = listOf(
        MemoryRowData("最近在吃布洛芬", "一个月前记下"),
        MemoryRowData("对青霉素过敏", "长期"),
        MemoryRowData("看东西喜欢先问有没有权威来源", "长期"),
    )

    val exchanges = listOf(
        // AnswerShape.CONFIDENT - authoritative source, stated plainly, attributed in-sentence.
        DemoExchange(
            question = "iPhone 17 Pro 电池容量多少？",
            narration = listOf("正在查……", "看了苹果官网", "整理中"),
            reply = DemoMessage(
                fromUser = false,
                shape = "CONFIDENT",
                confidence = Confidence.FULL,
                text = "苹果官网写的是 3582mAh，这个可以放心。官网还说视频播放最长 33 小时。",
                sources = listOf(Source("苹果官网", "apple.com")),
            ),
        ),

        // AnswerShape.WEAK_LEAD after an R6 counter-search that also came back empty.
        DemoExchange(
            question = "奇亚籽能减肥吗？",
            narration = listOf(
                "正在查……",
                "只找到一些个人分享和卖家的说法",
                "在查有没有相反的说法……",
                "整理中",
            ),
            reply = DemoMessage(
                fromUser = false,
                shape = "WEAK_LEAD / BOTH_EMPTY",
                confidence = Confidence.LOW,
                text = "这个我没找到权威研究，只有一些个人分享和卖家的说法，仅供参考。" +
                    "我也反过来查了有没有说它没用的资料，同样没有权威结论 —— " +
                    "所以正反两边我都查了，都说不准。",
            ),
        ),

        // §7 medical split: the factual half in full, the personal leap refused structurally.
        DemoExchange(
            question = "我最近总是心悸、手抖、容易出汗，是不是甲亢？",
            narration = listOf("正在查……", "看了国家卫健委的资料", "在查有没有相反的说法……", "整理中"),
            reply = DemoMessage(
                fromUser = false,
                shape = "CONFIDENT + medicalSplit",
                confidence = Confidence.FULL,
                text = "甲亢确实常见心悸、手抖、容易出汗这些表现，国家卫健委的资料里是这么写的。" +
                    "不过你是不是甲亢，我不能替你判断 —— 这个得抽血查甲状腺功能五项才能确定。" +
                    "建议挂内分泌科看一下。",
                sources = listOf(Source("国家卫生健康委员会", "nhc.gov.cn")),
            ),
        ),

        // R7 — authoritative sources disagree, and the user is told so.
        DemoExchange(
            question = "每天喝咖啡对心脏好吗？",
            narration = listOf("正在查……", "看了两份权威资料", "在查有没有相反的说法……", "整理中"),
            reply = DemoMessage(
                fromUser = false,
                shape = "CONFLICT",
                conflict = true,
                text = "大家都认可的是：适量喝咖啡对多数健康成年人没有明显害处。" +
                    "但在「是不是有好处」上，权威来源本身就有分歧：" +
                    "世界卫生组织（联合国下属的全球卫生机构）的说法比较保守，" +
                    "《英国医学杂志》上的大型研究则认为有轻微益处。" +
                    "这种情况建议问医生，特别是你本来就有心律方面的问题的话。",
                sources = listOf(
                    Source("世界卫生组织", "who.int"),
                    Source("《英国医学杂志》", "bmj.com"),
                ),
            ),
        ),

        // §23 — search is down, and the model's own knowledge is never used to fill the gap.
        DemoExchange(
            question = "现在美元汇率多少？",
            narration = listOf("正在查……"),
            reply = DemoMessage(
                fromUser = false,
                shape = "SEARCH_UNAVAILABLE",
                text = "我现在查不了资料，等一下再问我吧。",
            ),
        ),
    )
}
