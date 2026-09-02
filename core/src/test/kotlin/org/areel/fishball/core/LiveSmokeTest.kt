package org.areel.fishball.core

import kotlinx.coroutines.runBlocking
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.llm.HydrogenClient
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.core.memory.InMemoryStore
import org.areel.fishball.core.memory.Speaker
import org.areel.fishball.core.search.SearxngGateway
import org.areel.fishball.core.trust.loadBundledRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One real turn, against the real services.
 *
 * Opt-in: without `HYDROGEN_KEY` in the environment this does nothing and passes, so it never
 * makes CI depend on somebody's proxy being up or on a key existing. Run it deliberately:
 *
 *     HYDROGEN_KEY=... ./gradlew :core:test --tests '*LiveSmokeTest*' -i
 *
 * It exists because every other test here fakes the model, and the two defects that actually
 * reached the device — a model that is listed but not permitted, and reply blocks the parser
 * dropped — were both invisible to a fake. This is the only test that can see them.
 */
class LiveSmokeTest {

    private val key: String? = System.getenv("HYDROGEN_KEY")?.takeIf { it.isNotBlank() }

    /**
     * The state a phone was actually found in: a model id stored by an earlier build, which the
     * key is no longer entitled to use. Nothing re-opens the gate once it has been passed, so
     * without recovery here every turn fails 403 forever and the only fix is clearing app data
     * — which the device in question does not permit.
     */
    @Test
    fun `a stale model id recovers instead of failing every turn`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        var persisted: String? = null
        val llm = HydrogenClient(
            apiKey = key,
            model = "fishball-pro",
            onModelChanged = { persisted = it },
        )

        val result = runBlocking {
            llm.complete(
                org.areel.fishball.core.llm.LlmRequest(
                    system = "你是一个测试。",
                    messages = listOf(org.areel.fishball.core.llm.LlmMessage.user("说一个字")),
                    maxTokens = 64,
                ),
            )
        }
        println("recovered to -> $persisted")
        println("result       -> ${result::class.simpleName}")
        assertTrue(result is org.areel.fishball.core.llm.LlmResult.Ok, "did not recover: $result")
        assertTrue(persisted == "fishball-flash", "did not re-pick and report: $persisted")
        assertTrue(llm.model == "fishball-flash", "client kept the refused model")
    }

    /**
     * A turn that has to look past the summary, and is allowed to take as long as it takes.
     *
     * Two things are being watched, and both were broken in the same way. The loop used to force
     * an `answer` call on its tenth round, which a turn doing honest work reached routinely -
     * traced live it went search, search, quote, quote, search, quote and hit the wall. What the
     * model wrote under that forced call was 「看起来搜索工具这边有点问题，没返回查询结果」,
     * followed by citations from the sixteen results it claimed not to have. It had been cut off
     * and it explained the interruption with the only story it had.
     *
     * So: the turn must produce an answer rather than an apology, and it must do it by opening
     * at least one page instead of answering from search-engine summaries. The notes are the
     * evidence for both - each round of work leaves one, so counting them is how many rounds it
     * took, from the outside.
     */
    @Test
    fun `a hard question runs long and reads the page`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val conversation = Conversation(
            llm = llm,
            retrieval = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = InMemoryStore(),
        )

        val notes = mutableListOf<String>()
        val progress = object : org.areel.fishball.core.agent.TurnProgress {
            override fun searched(summary: String) {
                notes += summary
                println("  note " + notes.size + ": " + summary.lines().joinToString(" / "))
            }
        }

        val reply = runBlocking {
            conversation.ask("布洛芬和对乙酰氨基酚，孕妇能吃哪个？说明书上是怎么写的？", progress)
        }
        println("rounds of work -> " + notes.size)
        println("answer -> " + reply.text)
        println("sources -> " + reply.sources.joinToString { it.displayName + "/" + it.tier })

        // An answer, not the apology the round cap used to produce.
        assertTrue(
            reply.text != org.areel.fishball.core.copy.UiCopy.SERVICE_UNAVAILABLE,
            "the turn gave up: " + reply.detail,
        )
        assertTrue(reply.text.length > 40, "barely an answer: " + reply.text)

        /*
         * And an answer to the question, rather than a report on the tooling.
         *
         * The first version of this test asserted only length and not-an-apology, and passed on
         * a reply that was the model's own tool-call markup pasted into the answer slot -
         * 「我注意到工具调用格式一直有问题」 followed by a literal `<｜DSML｜tool_calls>` block.
         * A turn can fail without failing, and length does not see it.
         */
        listOf("<｜", "tool_call", "DSML", "```").forEach {
            assertTrue(!reply.text.contains(it), "raw model markup in the answer: " + reply.text)
        }
        assertTrue(
            reply.text.contains("对乙酰氨基酚") || reply.text.contains("布洛芬"),
            "answered about something else entirely: " + reply.text,
        )
        assertTrue(
            !reply.text.contains("工具"),
            "the answer is about the tools rather than the question: " + reply.text,
        )

        // And it got there by opening something. A turn that only ever searched has notes that
        // all begin 查了; reading one leaves a 读了.
        assertTrue(notes.isNotEmpty(), "no work was reported at all")
        assertTrue(
            notes.any { it.startsWith("读了") },
            "never opened a page, only read summaries: " + notes,
        )
    }

    /**
     * The change that is easiest to break without noticing: a turn is asked inside a
     * conversation, not on its own. Before this, every question was the first one the model had
     * ever seen, so a follow-up carrying a pronoun answered about nothing at all.
     */
    /**
     * The complaint this was built to answer: nothing was ever written to memory.
     *
     * The answer tool carried the fields, and the model answers in prose most turns and never
     * reached them - so the feature existed entirely in the schema. Harvesting is now its own
     * forced call, made after the answer is on screen.
     */
    @Test
    fun `a turn is remembered, and found again by meaning`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val store = InMemoryStore()
        val conversation = Conversation(
            llm = llm,
            retrieval = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        runBlocking {
            conversation.ask("我对青霉素过敏。布洛芬常见的副作用是什么？")
            // The bus files on its own coroutine; a test is the one caller that has to wait.
            conversation.memory.idle()
        }

        val facts = store.worldFacts()
        println("world facts -> " + facts.joinToString { "${it.question} = ${it.answer} [${it.ttl.label}]" })
        println("about user  -> " + store.preferences().joinToString { "${it.text} [${it.kind}]" })
        assertTrue(facts.isNotEmpty(), "nothing was remembered")
        assertTrue(facts.any { it.embedding.isNotEmpty() }, "a fact was stored without a vector")

        /*
         * The tier the answer rested on, not a placeholder.
         *
         * It was hard-coded LOW, so a fact stated outright by 国家药品监督管理局 came back later
         * marked as weakly sourced, and §10 would then treat it as something to re-search
         * rather than something known. That is the bug being guarded, and anything above LOW
         * catches it.
         *
         * The bar was HIGH and is not any more: the re-tiering put the medical outlets a
         * medication question actually lands on - 丁香园, 百度百科 - at 中, and 高 now begins at
         * governments and the press. A turn resting on those is honestly MEDIUM, and asserting
         * otherwise would be asking the test to disagree with the scale.
         */
        println("tier -> " + facts.joinToString { it.tier.name })
        assertTrue(
            facts.any { it.tier > org.areel.fishball.core.trust.Tier.LOW },
            "a well-sourced turn was remembered as weakly sourced",
        )

        // Asked again in different words. Word overlap would miss this; meaning should not.
        val asked = "吃布洛芬会不会胃疼？"
        val vector = runBlocking { llm.embed(listOf(asked)) }.first()
        val candidates = store.recallWorldCandidates(
            listOf(asked), listOf(vector), System.currentTimeMillis(),
        )
        println("candidates  -> " + candidates.joinToString { "%.3f %s".format(it.similarity, it.fact.question) })
        assertTrue(candidates.isNotEmpty(), "the paraphrase found nothing")
    }

    /**
     * Memory is searched by what the question needs, not by the question.
     *
     * "吃这个药要注意什么" shares no word with 对青霉素过敏 and is not asking about penicillin,
     * so neither word overlap nor an embedding of the question itself will find it. What finds
     * it is the model naming 药物过敏史 as something the answer depends on, and that phrase
     * being what memory is actually searched with.
     */
    @Test
    fun `what the question needs is what memory is searched by`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val store = InMemoryStore()

        // Seeded the way the harvest would have written it, vector and all.
        runBlocking {
            val text = "对青霉素过敏"
            store.recordPreference(
                org.areel.fishball.core.memory.PreferenceFact(
                    id = store.nextId(),
                    text = text,
                    kind = org.areel.fishball.core.memory.PreferenceKind.MEDICAL_CONSTANT,
                    ttl = org.areel.fishball.core.memory.PreferenceTtl.PERMANENT,
                    embedding = llm.embed(listOf(text)).first(),
                    recordedAt = System.currentTimeMillis(),
                ),
            )
        }

        val conversation = Conversation(
            llm = llm,
            retrieval = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        // §16 may hold this turn for a clarifying question, so the test answers it and reads the
        // reply that actually lands. 阿莫西林 is a penicillin: what is known about them and what
        // they have just been prescribed genuinely conflict, and an answer that does not say so
        // is the failure this guards against.
        val first = runBlocking { conversation.ask("医生给我开了消炎药，吃之前我要注意什么？") }
        println("first  -> " + first.text)
        val reply = runBlocking { conversation.ask("刚开的，阿莫西林胶囊，别的药没吃。") }
        println("answer -> " + reply.text)
        assertTrue(
            reply.text.contains("青霉素") || reply.text.contains("过敏"),
            "the answer never used what was known about them: " + reply.text,
        )
    }

    /**
     * Spec §19 — a fact the user has just contradicted stops being served.
     *
     * The turn that carries a correction is usually not a question, so it looks nothing up and
     * the record it contradicts is never in front of the model. The harvest looks for itself.
     */
    @Test
    fun `a stated change retires what it contradicts`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val store = InMemoryStore()

        val stale = store.nextId()
        runBlocking {
            val text = "在吃布洛芬"
            store.recordPreference(
                org.areel.fishball.core.memory.PreferenceFact(
                    id = stale,
                    text = text,
                    kind = org.areel.fishball.core.memory.PreferenceKind.CURRENT_STATE,
                    ttl = org.areel.fishball.core.memory.PreferenceTtl.SIX_MONTHS,
                    embedding = llm.embed(listOf(text)).first(),
                    recordedAt = System.currentTimeMillis(),
                ),
            )
        }

        val conversation = Conversation(
            llm = llm,
            retrieval = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        runBlocking {
            conversation.ask("布洛芬我已经停了，现在什么药都没吃。")
            conversation.memory.idle()
        }
        println("about user -> " + store.preferences().joinToString { it.text })
        assertTrue(
            store.preferences().none { it.id == stale },
            "the contradicted record survived: " + store.preferences().joinToString { it.text },
        )
    }

    /**
     * It has no hands, and it says so.
     *
     * The failure this guards against is the worst kind the app can produce, because nothing on
     * screen looks wrong: asked to book, send or remind, the model agreed pleasantly and then did
     * nothing, because there is nothing it could have done. A person who is told 我帮你订 has no
     * way to know it did not happen until the appointment is missed.
     */
    @Test
    fun `it does not promise work it cannot do`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val store = InMemoryStore()
        val conversation = Conversation(
            llm = llm,
            retrieval = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        // Three different shapes of the same impossible ask: act now, act later, act elsewhere.
        val asks = listOf(
            "帮我订一张明天去上海的高铁票。",
            "明天早上八点提醒我吃药。",
            "帮我给我女儿发条微信说我到家了。",
        )
        // Agreement, in the forms the model actually reaches for. Any of these is the bug.
        val promises = listOf(
            "我帮你订", "我来帮你订", "已经帮你", "帮你订好", "我这就",
            "我会提醒", "我明天提醒", "到时候提醒你", "我帮你发", "帮你发送",
            "我去发", "已经发送", "设置好了", "已经安排",
        )

        asks.forEach { ask ->
            val reply = runBlocking { conversation.ask(ask) }
            println("ask    -> " + ask)
            println("answer -> " + reply.text)
            val agreed = promises.filter { reply.text.contains(it) }
            assertTrue(
                agreed.isEmpty(),
                "promised work it cannot do (" + agreed.joinToString() + "): " + reply.text,
            )
        }

        // The other half, and the reason the rule above is worded as it is. A prompt that only
        // lists what the app cannot do produces an app that recites its limits at somebody who
        // just wanted to talk - which is a worse product than the bug it was meant to fix.
        val chat = runBlocking { conversation.ask("今天上班有点累，随便跟你说说话。") }
        println("chat   -> " + chat.text)
        val recited = listOf("做不到", "帮不了", "我只能", "无法", "没有能力", "不具备")
            .filter { chat.text.contains(it) }
        assertTrue(
            recited.isEmpty(),
            "recited its limits at a casual turn (" + recited.joinToString() + "): " + chat.text,
        )
    }

    /**
     * A picture reaches the model, and the answer is about what is in it.
     *
     * The proxy grew vision on 2026-09-01, so this is the test that says whether the app's own
     * path carries an image rather than whether the endpoint accepts one. It goes the whole way
     * through `Conversation.ask`, which is where the block is attached to the user turn.
     */
    @Test
    fun `an attached picture reaches the model`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val bytes = javaClass.getResourceAsStream("/vision-probe.jpg")!!.readBytes()
        val image = org.areel.fishball.core.llm.LlmContent.Image(
            mediaType = "image/jpeg",
            base64 = java.util.Base64.getEncoder().encodeToString(bytes),
        )

        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val conversation = Conversation(
            llm = llm,
            retrieval = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = InMemoryStore(),
        )

        val reply = runBlocking {
            conversation.ask("这张图上写的是什么？照着念一遍就行。", images = listOf(image))
        }
        println("image answer -> " + reply.text)
        println("image detail -> " + reply.detail)
        assertTrue(
            reply.text.contains("FISHBALL", ignoreCase = true) || reply.text.contains("1234"),
            "the model did not see the picture: " + reply.text,
        )
    }

    /**
     * Compaction happens once, not once per attempt.
     *
     * Switching model folds the conversation. Switching again immediately afterwards has
     * nothing left to fold - the new session is empty - and used to roll another fresh session
     * and announce another summary, so flipping between modes stacked notices in the thread
     * for work that never happened.
     */
    @Test
    fun `compacting an empty session does nothing`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val store = InMemoryStore()
        val conversation = Conversation(
            llm = llm,
            retrieval = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        // Nothing said yet: there is no session to fold, so there is nothing to announce.
        assertTrue(!runBlocking { conversation.compact() }, "compacted an empty conversation")

        // And one real exchange is still not enough. Folding is for a conversation too long to
        // hand over whole; this one fits in the window many times over, and a paragraph about
        // it would be less than the thing itself.
        runBlocking { conversation.ask("iPhone 17 Pro 电池容量多少？") }
        assertTrue(!runBlocking { conversation.compact() }, "folded a conversation under 128K")
        println("compaction -> held off, because there was nothing worth folding")
    }

    @Test
    fun `a follow-up knows what it is following up on`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val store = InMemoryStore()
        val conversation = Conversation(
            llm = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        runBlocking { conversation.ask("布洛芬常见的副作用是什么？") }
        // No subject of its own. Only the previous turn says what "它" is.
        val followUp = runBlocking { conversation.ask("那它伤肝吗？") }

        println("follow-up -> ${followUp.text.take(160)}")
        assertTrue(
            followUp.text.contains("布洛芬"),
            "the follow-up lost the subject: ${followUp.text.take(200)}",
        )
        // One session, not two: nothing here should have tripped a rollover.
        assertTrue(store.recentTurns().size >= 4, "turns went missing")
    }

    /**
     * The pillow test. Asking which pillow to buy used to be met with "存款大概能支撑多久" and
     * "压力主要是工作本身，还是人" - the general clarifying list, which had to be about
     * something and was about deciding whether to quit your job.
     */
    @Test
    fun `small advice does not get an interrogation`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }
        val llm = HydrogenClient(apiKey = key)
        runBlocking { llm.validate() }
        val conversation = Conversation(
            llm = llm,
            retrieval = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = InMemoryStore(),
        )

        val reply = runBlocking { conversation.ask("选枕头有什么建议吗？") }
        println("pillow -> ${reply.text.take(200)}")
        assertTrue(!reply.text.contains("存款"), "asked about savings: ${reply.text.take(120)}")
        assertTrue(!reply.text.contains("压力主要是"), "asked about work stress: ${reply.text.take(120)}")
    }

    @Test
    fun `a whole turn works against the live services`() {
        val key = key ?: run {
            println("LiveSmokeTest skipped: set HYDROGEN_KEY to run it")
            return
        }

        val llm = HydrogenClient(apiKey = key)
        val check = runBlocking { llm.validate() }
        println("validate -> $check")
        assertTrue(check is KeyCheck.Valid, "sign-in failed: $check")

        // The whole point of the entitlement probe: what it settles on has been called once
        // and answered, not merely seen in a catalogue.
        println("model    -> ${(check as KeyCheck.Valid).chosen}")

        val store = InMemoryStore()
        val conversation = Conversation(
            llm = llm,
            search = SearxngGateway(baseUrl = "https://search.areel.org"),
            registry = loadBundledRegistry(),
            store = store,
        )

        val thinking = StringBuilder()
        val streamed = StringBuilder()
        val narrated = mutableListOf<String>()
        val reply = runBlocking {
            conversation.ask(
                "布洛芬常见的副作用是什么？",
                object : org.areel.fishball.core.agent.TurnProgress {
                    override fun step(text: String) {
                        narrated += text
                        println("  narration: $text")
                    }
                    override fun thinking(delta: String) { thinking.append(delta) }
                    override fun answer(delta: String) { streamed.append(delta) }
                },
            )
        }
        println("thinking -> ${thinking.length} chars: ${thinking.take(120)}")
        println("streamed -> ${streamed.length} chars")
        /*
         * Something has to reach the screen while the turn runs.
         *
         * It used to be thinking specifically, which was fair when a turn made five calls and
         * at least one of them narrated its reasoning. The turn is one loop now, and what it
         * shows is what it is doing - 正在查, 看了维基百科 - with the thinking alongside when
         * the model emits any. Asserting on the thinking block alone was asserting on the
         * provider: it is on or off depending on what the route points at this week, and the
         * wait is not silent either way.
         */
        assertTrue(
            thinking.isNotEmpty() || narrated.isNotEmpty(),
            "nothing reached the screen while the turn ran",
        )

        println("shape    -> ${reply.shape}")
        println("detail   -> ${reply.detail}")
        println("sources  -> " + reply.sources.joinToString { "${it.displayName}(${it.tier})" })
        reply.sources.mapNotNull { it.quote }.forEach { println("quote    -> $it") }
        println("answer   -> ${reply.text}")

        assertTrue(reply.detail == null, "turn reported a failure: ${reply.detail}")
        assertTrue(reply.text.isNotBlank(), "empty answer")

        val logged = store.recentTurns()
        assertTrue(logged.size >= 2, "the turn was not logged")
        assertTrue(logged.first().speaker == Speaker.USER)
    }
}
