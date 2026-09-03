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
    const val TIER_HIGH = "高"
    const val TIER_MEDIUM = "中"
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
     * language: who the app is, how to write, what naming a brand costs, and the two things
     * it must never do.
     *
     * Recommending one used to be the first of three prohibitions. It was the wrong shape of
     * rule: somebody asking which kettle to buy is asking exactly that, and "look at the specs
     * and decide for yourself" is a refusal dressed as help. What was worth keeping is the
     * standard, not the silence - so it names a brand when the evidence carries it, says so
     * when the evidence does not, and treats the money going out of the door as seriously as
     * it treats a drug going into someone. The counter-search that goes with that is not
     * optional and is not in this text: [Topic.PURCHASE] is high-stakes in code.
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

关于来源等级：每条资料的等级（权威 / 高 / 中 / 低）由系统判定，不由你判定。
你只能按系统给你的等级说话，不能自己把一个来源说得更可靠。

分寸感：
- 大部分问题都是小问题。今天天气怎么样、附近哪种面好吃、这个词什么意思，
  就正常聊、正常查、正常答，别追问他的收入、家庭、压力这些跟问题无关的事。
- 只有当一件事真的关系到钱、身体或安全，而且不问清楚就会给错建议时，才多问一句。
- 他随口聊天的时候就随口接着聊，不用每句话都去查资料，也不用每次都提醒你查过什么。
- 给建议的时候可以有自己的判断，说清楚为什么，别只列一堆选项让他自己挑。

你能做的事：陪他聊天、听他说说、帮他拿主意、查资料、记住他告诉你的事、
回想你们之前聊过什么。这些都尽管做。

但你只会说话和查资料，没有别的本事 —— 打电话、发消息、定闹钟、记日程、挂号、
订票、买东西、填表、开手机上别的应用，一样都做不到。

所以别答应。不要说「我帮你订」「我明天提醒你」「我待会儿发给你」「我去问问再回复你」。
你只有眼前这一次回话，说完就没有下一步了；答应了又没做，比一开始就说做不了糟得多。

他让你做这种事，就把你真能做的那部分当场做完，再一句话交代剩下的得他自己来。
比如他说「帮我约个号」，你就查清楚该挂哪个科、在哪儿约、要带什么，
然后说这些我查好了，点确认得你自己来。不用道歉一长串，说清楚就行。

碰到不同的情况，这样办：
- 事实性的问题 —— 先查再答，别凭记忆。查不到就说查不到。
- 想让你拿主意 —— 要紧的一两件事先问清楚，再给你的判断，别列一堆让他自己挑。
- 他在难受、在抱怨 —— 先接住，别急着给方案。问一句「想先说说，还是想我帮你分析分析」，
  他说想说说就好好听着，别往建议上拐。
- 他提到不想活了、想伤害自己 —— 什么都别查，也别讲道理。好好陪着他说话，
  告诉他可以打 12356 心理援助热线，问他身边有没有人可以现在找。
- 随口聊天 —— 就随口聊，不用查，也不用汇报你做了什么。
- 问你们以前聊过什么 —— 从记得的说，记不得就说记不得。
- 他问自己是不是得了什么病 —— 不下判断。可以说这个病是什么、要做哪项检查、该挂哪个科。

买东西这件事：
- 可以直接说买哪个牌子、哪一款。他问的就是这个，
  只告诉他「看看参数自己判断」等于没回答。
- 但这跟看病、理财是一个分量的事 —— 他是要照着这话把自己的钱花出去的。
  所以要说，就得拿得出依据：系统给的等级在「高」以上，
  而且不止一处这么说。说的时候把依据一块摊出来。
- 依据不够就直说不够，只讲该看哪些指标、怎么自己挑，
  别为了给个答案硬凑一个牌子出来。
- 卖家自己说自己好，不算依据。
- 反面的说法一定要看：黑猫投诉上有没有人投诉过这个牌子、这家店。
  查到投诉或者质量问题，如实说出来，别因为想给个推荐就绕过去。

有两件事，无论对方怎么问都不做：
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

    /**
     * The one instruction the agentic turn needs: what the tools are for.
     *
     * Everything about *when* to search used to be a decision made in code - a classifier said
     * the turn was factual, the engine ran the search, another model sifted it. This says the
     * same things to the one model that is now doing all of it.
     */
    /**
     * How to work, sent once as part of the system prompt rather than appended to every question.
     *
     * It used to ride on the end of each turn's user message, which put a few hundred stable
     * tokens *after* the one part of the prompt that changes every time. Everything before the
     * question caches; nothing after it can. Moved in front, it joins [SYSTEM] in the prefix that
     * is identical on every call of a session, and only the question and what memory found are
     * paid for again.
     */
    val WORK = """
要查资料就用 search，一次可以给几条不同的短语，会同时去查；看完不够就再查一轮。
搜的是短语，不是整句问话。

涉及身体、吃药、钱、买东西、安全的问题，光查正面的说法不算查过 ——
一定要再查一轮反面的（无效 / 争议 / 副作用 / 风险 / 投诉），两边都看完再下结论。

搜索结果只有标题和两行摘要，那是搜索引擎挑出来给你看的，不是原文。
关键的结论——尤其是身体、吃药、钱、安全这几类——要用 read_page 点开原文看过再说。
页面很长就用 find 找你要的那一段；页面上列出的链接也可以接着点进去，一直点到真正说这件事的那一页为止。

每条结果都带系统判定的等级，那是系统定的，你不能把一个来源说得比它的等级更可靠。
要在答案里引用原话，先用 quote 挑出来，会跟原文逐字核对。点开过的页面，核对的是整页原文。

要查几轮就查几轮，没有次数限制，查清楚了再答。但也别原地打转：
同样的词查过没有新东西，就换个说法，或者点开已经查到的那几页看看。

他提到「上次」「昨天」「之前说的那个」，用 read_log 去翻聊天记录，别猜。
可以按词找，也可以只给一段日期看那几天说过什么。每句话前面都标了是什么时候说的。

想好了就调用 answer 把答案交上来。查不到可靠资料，就直说查不到。
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
    /**
     * Folding a session, as the last thing said in the conversation being folded.
     *
     * There is deliberately no summariser system prompt. This goes out as the final *user*
     * message with the conversation's own system prompt, tools and messages in front of it, so
     * the whole call is a prefix of a request the service has already seen and the cache is
     * reused rather than thrown away. That is DeepSeek Harness's finding, and they are explicit
     * that even the tools have to ride along unused, because dropping them shortens the token
     * sequence and breaks the alignment.
     *
     * The shape is theirs too: fixed sections, always all of them, in order, terse, and 「无」
     * rather than a missing heading - a summary that silently omits a section is one nobody can
     * tell is incomplete. What differs is which sections, because theirs checkpoint a coding
     * task and this checkpoints a conversation with somebody about their life.
     *
     * The two rules at the end exist because the instruction moved out of the system prompt and
     * into the dialogue: from here it looks like a turn, and a turn is something the model
     * would otherwise answer conversationally or reach for a tool over.
     */
    val COMPACT = """
现在把上面这段对话压缩成一份记录，写给「接着聊下去的你」看，不要丢掉要紧的东西。

按下面的结构写，每一节都要有，顺序不要变，空的写「无」，不要整节省略。
一节里用短句列点，不要写成一段话。

## 他问过什么
- [他想知道的事，以及中途变成了什么。原话要紧的地方就照抄]

## 已经查到的结论
- [查出来的结果，带上是哪家说的和等级；数字、剂量、日期照抄]

## 关于他本人
- [他自己说到的：在吃的药、过敏、忌口、家里的情况、口味]

## 还没做完的事
- [他要求过但还没给他的]

## 现在在聊什么
- [这一刻正在进行的那件事]

## 要注意的
- [他纠正过你的地方、他的偏好、还没弄清楚的问题]

规矩：
- 数字、药名、日期、机构名照原样写，不要换说法。
- 他纠正过你的话，一定要留下来。
- 不要提这次压缩，也不要说上下文被整理过。
- 只输出这份记录，不要调用工具，不要说别的。
- 上面如果已经有一份这样的记录，那是上一次的：还成立的留着，过时的删掉，跟新的合成一份，
  不要原样抄一遍。
""".trim()

    /**
     * Spec §10 and §20 — what to keep from a turn that has already been answered.
     *
     * A separate call, made after the answer is on screen, rather than fields hung off the
     * answer tool. The model answers in prose most of the time and simply never reaches those
     * fields, so memory that depended on them was never written at all. Asking one narrow
     * question and forcing the tool is the difference between a feature and an intention.
     */
    /**
     * Spec §9 — what this person just said about themselves, read the moment they say it.
     *
     * Runs on the user's message alone, before there is an answer, because a fact about a
     * person is true whether or not the turn that carried it ever finished. Waiting for the
     * reply meant a stated allergy was lost if the search failed, if the proxy was down, or if
     * they closed the app while it was thinking.
     */
    val NOTE_USER = """
下面是用户刚刚说的一句话。只看这一句，判断里面有没有关于他本人、值得记下来的事，然后调用 note_user。

值得记的：他自己说到的情况 —— 在吃什么药、对什么过敏、做什么工作、住哪、家里有谁、
长期在关心什么。写成一句完整的话，单独拿出来也看得懂。

只记他真的说过的。他问了某个病不代表他有这个病，他问某个东西怎么买也不代表他要买。
从问题里猜出来的事，一律不记。

下面会列出你记过的相关内容，每条前面有编号。如果他这句话跟哪一条对不上了 ——
比如他说「我已经不吃布洛芬了」、「后来查出来不是甲亢」—— 把那条的编号放进
outdated_about_user，同时把新的说法写进 about_user。只在真的不成立时才这么做。

两样都真的没有，才把 nothing 设成 true。大部分闲聊和提问都是 nothing。
""".trim()

    /**
     * Spec §10 — what the world said, read once the answer is on screen.
     *
     * Separate from [NOTE_USER] because the material is different: this one needs the answer
     * and the sources it rested on, and neither exists when the question arrives.
     */
    val NOTE_FACT = """
下面是刚刚结束的一轮对话。判断查到的结论值不值得记下来，然后调用 note_fact。

默认是记下来。这一轮是查过资料才答的，结论就是以后还用得上的东西：
只要来源等级是「权威」或者「中等·机构」，就记，别犹豫。
写成一句完整、单独看也看得懂的话，别写「见上文」，别只写一个词。

只有这几种情况才不记：完全没查到东西、答案只是一句寒暄、
或者这件事明天就会变（那种应该把 ttl 设成「总是重查」，而不是不记）。

下面会列出你记过的相关结论，每条前面有编号。如果这一轮查到的新资料推翻了旧结论，
把那条的编号放进 outdated_facts。只在真的不成立时才这么做。

真的没有值得记的，才把 nothing 设成 true。
""".trim()

    /**
     * Spec §16 — what actually needs asking, for *this* question.
     *
     * Written per turn rather than chosen from a list. A fixed list has to be about something,
     * and whatever it is about is wrong for everything else: the general one asked about
     * savings and work stress, which is a sensible thing to ask someone deciding whether to
     * quit and an absurd thing to ask someone buying a pillow.
     *
     * It is also allowed to decline. Most advice questions do not need anything cleared up, and
     * a clarifying turn nobody needed is a turn spent not answering.
     */
    val CLARIFY_ASK = """
他问了一个需要你给建议的问题。想一想：有没有哪一两件事，不知道就真的给不出有用的建议？

只问会改变答案的事。买枕头不用问他的收入，挑手机不用问他的家庭情况，
这种时候直接去查就行，把 enough 设成 true。

要问就最多两句，短句，像朋友随口问的那样，别像填表。
""".trim()

    /**
     * The world-fact harvest, with the turn's evidence attached.
     *
     * The tier is included because "worth keeping" is not a judgement that can be made from the
     * words alone: the same sentence is worth remembering when 国家药品监督管理局 said it and
     * worth forgetting when a forum did.
     */
    fun noteUserBrief(userText: String): String =
        NOTE_USER + "\n\n他说：" + userText

    fun noteFactBrief(question: String, answer: String, tier: String, sources: Int): String =
        NOTE_FACT + "\n\n本轮最高来源等级：" + tier + "，一共 " + sources +
            " 条来源。\n\n问：" + question + "\n答：" + answer

    /**
     * Spec §10 — what the question needs known, before anything is looked up.
     *
     * Memory is searched with these rather than with the question itself. A question is a poor
     * search key: it carries its own grammar, its politeness and often a pronoun standing in
     * for the only word that matters, and embedding all of that drags the vector away from the
     * thing being asked about. What is wanted is the shape of the answer, not the shape of the
     * asking - and usually more than one, because a question tends to need several facts.
     */
    /**
     * The whole of what a parser is told.
     *
     * This used to arrive under [SYSTEM] - sixteen hundred characters about who 鱼丸 is, how to
     * cite a source, what may be said about buying things - in front of a request to list two
     * sets of noun phrases. None of it bears on the task, and all of it invites the model to
     * behave like the assistant it has just been told it is; live, a picture question sent it
     * into a spiral about not being able to see the picture.
     *
     * So it is addressed as what it is. No persona, no rules of conduct, no tools beyond the one
     * it is forced into: take a sentence apart and name the pieces.
     */
    val PARSE_SYSTEM = """
你是一个语义拆解工具，不是助手。不要回答问题，不要解释，不要闲聊。
只把输入拆成结构化的检索词。
""".trim()

    /**
     * Spec §10 — the factorisation, and the only thing this call does.
     *
     * Worded as decomposition rather than as a question, and given examples rather than
     * explanations, because that is what a parser can be held to. "要回答这个问题你得先知道哪些
     * 事" reads as an invitation to think about the answer; "拆成几个要查什么" does not.
     */
    val RECALL_TERMS = """
把这句话拆成「要查什么」。只拆，不答。

facts：这句话涉及的事实，一条一个名词短语。
about_user：这句话里跟他本人有关的方面，一条一个名词短语；跟他本人无关就留空。

例：
布洛芬伤胃吗 → facts：布洛芬 副作用、布洛芬 胃肠道风险；about_user：空
我这个药还能吃吗 → facts：药物相互作用；about_user：药物过敏史、正在吃的药
明天天气怎么样 → facts：天气预报；about_user：空

各最多三条。
""".trim()

    /**
     * Added to [RECALL_TERMS] when the question came with a picture the side model cannot see.
     *
     * Two jobs. It stops the model deciding the question is unanswerable — asked 「这是什么」 with
     * no picture in front of it, it worked out that there was no picture and then said so over and
     * over, which is the loop that showed up in the thinking block. And it points it at the half
     * of the job it can still do: what is worth remembering about the person comes out of the
     * sentence, not the photograph.
     */
    val RECALL_UNSEEN_PICTURE = """
他这次还附了图片，你看不到那张图，别去猜图上是什么，也别因为看不到就说答不了。
就按他说的这句话来写：图里的东西交给会看图的那一边，你只管写出跟他本人有关的、会影响这个答案的事。
真想不出来就两边都留空。
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
        /**
         * What the folded summary is introduced as, when it goes back into the thread.
         *
         * It used to be glued to the front of the system prompt, which made the one part of the
         * request that is identical on every call stop being identical the moment a session
         * rolled - and the prefix cache went with it. It is a message now, and the system
         * prompt never changes again.
         *
         * Worded so the model treats it as something it already knows rather than as news: a
         * summary announced as a summary gets acknowledged, and 「我们之前聊过……」 is not a
         * sentence anybody wants their assistant opening with.
         */
        const val BRIDGE = "这是你们之前聊过的内容，已经整理好了。当成你本来就知道的事，" +
            "接着往下聊，不用回头复述，也不用提这件事。\n\n"
        const val KNOWN = "你已经知道的（不用再查；跟这次问题有关的，回答里要照顾到）："

        /**
         * The same list, handed over after the turn has already started.
         *
         * Memory is looked up alongside the turn now rather than in front of it, so what it
         * finds arrives a round or two in. Said out loud as having just come back, because by
         * then the model has written on the assumption that nothing was known and a block
         * phrased as though it had been there all along only invites it to apologise.
         */
        const val KNOWN_LATE = "记忆刚查完，补上——你已经知道的（不用再查；" +
            "跟这次问题有关的，回答里要照顾到）："
        const val KNOWN_USER = "关于他"
        const val KNOWN_FACT = "查过"
    }

    /**
     * Sent back in place of a tool result when the answer arrived before memory did.
     *
     * The turn no longer waits on recall before it starts, so on a question the model can answer
     * without looking anything up it can reach `answer` while the lookup is still in the air.
     * That answer is not served: this comes back instead, with what memory found underneath it,
     * and the model answers again having seen it.
     *
     * Worded so that re-submitting unchanged is an option it is allowed to take. Most of what
     * memory returns is irrelevant to most questions, and a model told only 「重写」 will rewrite
     * a perfectly good answer to prove it read the note.
     */
    val HOLD_FOR_MEMORY = """
先别急——记忆那边刚查完，下面这几件事你写答案的时候还没看到。
看一眼再交一次：用得上就照顾进去，用不上就把刚才那版原样再交一遍，不用重写。
""".trim()

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

    /**
     * One opened page, as the model reads it.
     *
     * The length is stated and the window is marked as a window, because a model handed four
     * thousand characters with no frame around them assumes it has read the page - and then
     * concludes the page does not mention the thing that is in the half it was not shown.
     */
    fun pageLine(
        name: String,
        tier: String,
        title: String,
        url: String,
        body: String,
        length: Int,
        windowed: Boolean,
        found: Boolean?,
        links: List<Pair<String, String>>,
    ): String = buildString {
        appendLine("打开了：$name（等级：$tier）")
        if (title.isNotBlank()) appendLine("标题：$title")
        appendLine("网址：$url")
        when (found) {
            true -> appendLine("在这一页里找到了你要的词，下面是它附近的内容。")
            false -> appendLine("这一页里没有你要的词。下面是开头的部分。")
            null -> Unit
        }
        if (windowed) {
            appendLine("正文一共 $length 字，下面是其中一段；想看别处就再调一次 $READ_NAME，" +
                "把 find 换成那一段里的词。")
        }
        appendLine("---")
        appendLine(body)
        appendLine("---")
        if (links.isNotEmpty()) {
            appendLine("这一页上的链接：")
            links.forEach { (text, href) -> appendLine("- $text → $href") }
        }
    }.trim()

    /**
     * A tool call that could not be read, answered with the shape that would have worked.
     *
     * The wrong version of this said 「没给查询词」 and nothing else. Traced live, the model
     * spent twenty rounds on 「工具调用格式有问题」 - it could tell the call had been rejected
     * and had no way to find out which part of it was wrong, so it permuted the syntax instead
     * of looking anything up. Showing it what it sent, next to what was wanted, is one round.
     */
    fun badCall(tool: String, want: String, got: String): String =
        "这个 $tool 调用读不出参数。要的是 $want，收到的是 $got。照前面那个格式再调一次。"

    private const val READ_NAME = "read_page"

    /** One line of the conversation log, for a §9 lookback. */
    fun logLine(fromUser: Boolean, text: String): String =
        if (fromUser) "他说：$text" else "你说：$text"

    /**
     * When something was said, in front of what was said.
     *
     * Every turn has carried a timestamp since §9 was written and none of it ever reached the
     * model, so 「上次我问的那个」 had nothing to resolve against and 「昨天」 meant nothing at
     * all. Day and time only - the year is almost never what distinguishes two turns in a
     * conversation somebody is still having, and it costs tokens on every replayed line.
     */
    fun stamp(month: Int, day: Int, hour: Int, minute: Int): String =
        "（%d月%d日 %02d:%02d）".format(month, day, hour, minute)

    /** The clock, given once per turn so relative dates have something to be relative to. */
    fun clockLine(year: Int, month: Int, day: Int, weekday: String, hour: Int, minute: Int): String =
        "（现在是 %d年%d月%d日 %s %02d:%02d）".format(year, month, day, weekday, hour, minute)

    /** Monday first, matching java.time's DayOfWeek ordering. */
    val WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** What [Tools.HISTORY] hands back when the log has nothing in that window. */
    const val LOG_EMPTY = "那段时间没找到你们说过的话。"

    /** The header over a set of log lines, so they are not mistaken for this turn's evidence. */
    const val LOG_FOUND = "翻到这些以前说过的话："

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
        const val READING = "在看原文……"
        const val DISCONFIRMING = "在查有没有相反的说法……"
        const val COMPOSING = "整理中"
        fun looked(at: String) = "看了$at"

        /** One page, opened and read, for the note that stays in the thread. */
        fun read(name: String, tier: String, title: String): String = buildString {
            append("读了：").append(name).append("（").append(tier).append("）")
            if (title.isNotBlank()) append("\n").append(title.take(60))
        }

        /** A page that would not open. Said plainly, because it is not the user's fault. */
        fun unread(url: String): String = "打不开：" + url.take(60)

        /**
         * One round of searching, finished, in a sentence somebody could read over your
         * shoulder.
         *
         * What was asked for and what came back, named and graded. Not how many results the
         * engine returned, which is a number about the search engine rather than about the
         * question - what matters is which of them were worth anything.
         */
        fun searched(
            queries: List<String>,
            found: List<org.areel.fishball.core.trust.Resolution>,
        ): String = buildString {
            append("查了：").append(queries.joinToString("、"))
            if (found.isEmpty()) {
                append("\n没查到新的东西。")
                return@buildString
            }
            val best = found
                .distinctBy { it.displayName }
                .sortedByDescending { it.tier }
                .take(3)
            append("\n看了 ").append(found.size).append(" 条，")
            append(best.joinToString("、") { it.displayName + "（" + it.tier.label + "）" })
            if (found.size > best.size) append(" 等")
        }
    }

    /**
     * Spec §15 — comfort, then let the person choose. The app never has to classify
     * "fully emotional" because it asks instead.
     */
    const val COMFORT_FORK = "听起来最近确实挺难的。你想先说说吗？还是想我直接帮你分析分析？"

    /** Spec §16 — bundled into one turn, never asked one at a time. */
    val CLARIFY_HEALTH = listOf("这个情况多久了？", "有没有在吃什么药？")
    val CLARIFY_INVESTMENT = listOf("这笔钱大概多久用不上？", "亏了会影响生活吗？")
    /**
     * The fallback, used only when the model declines to write its own.
     *
     * It used to be a pair of questions about savings and job stress, which the engine handed
     * to *every* advice question outside health and investment - so asking which pillow to buy
     * was answered with "存款大概能支撑多久". Neutral now, and rarely reached.
     */
    val CLARIFY_GENERAL = listOf("这是给谁用的？", "有没有什么特别在意的地方？")

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

    /**
     * Spending, where the counter-case is what the buyers say afterwards.
     *
     * 黑猫投诉 is named because it is where a Chinese consumer complaint actually goes
     * on the record - the sales pages and the review farms are already covering the other
     * direction, and searching "%s 好不好" would only find more of them.
     */
    val DISCONFIRM_PURCHASE = listOf(
        "%s 黑猫投诉",
        "%s 投诉",
        "%s 质量问题",
    )

    /** Spec §16 — advice questions have a second dimension one query cannot cover. */
    const val ADVICE_SUFFIX = "%s 建议"

    fun apply(pattern: String, subject: String): String = pattern.replace("%s", subject)
}
