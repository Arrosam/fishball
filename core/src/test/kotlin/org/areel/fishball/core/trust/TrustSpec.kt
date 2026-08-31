package org.areel.fishball.core.trust

/**
 * The trust rules as executable assertions.
 *
 * Written as a plain function returning failures rather than as JUnit assertions, so the
 * identical checks run two ways: under Gradle via TrustResolverTest, and directly against a
 * bare `kotlinc` when no build system is available. One set of assertions, two harnesses.
 *
 * Each check names the spec rule it pins.
 *
 * Chinese survives only in fixture data - account names, snippet text, expected attribution
 * strings. That is deliberate: the corpus this runs against is Chinese, and bigram
 * similarity behaves differently across scripts, so English fixtures would test the wrong
 * thing. All assertion names are English.
 */
object TrustSpec {

    fun run(registry: SourceRegistry): List<String> {
        val failures = mutableListOf<String>()
        val r = TrustResolver(registry)

        fun check(name: String, condition: Boolean, detail: String = "") {
            if (!condition) failures += "$name${if (detail.isNotEmpty()) " — $detail" else ""}"
        }

        fun tierOf(url: String, ctx: ClaimContext = ClaimContext()) = r.resolve(SearchHit(url), ctx).tier

        // ---- host normalisation ---------------------------------------------------------
        check("normalizeHost strips scheme/www/port",
            normalizeHost("https://www.nhc.gov.cn:443/x/y?q=1") == "nhc.gov.cn")
        check("registrableDomain handles multipart suffix",
            registrableDomain("www.chp.gov.hk") == "chp.gov.hk",
            "got ${registrableDomain("www.chp.gov.hk")}")
        check("registrableName finds brand label",
            registrableName("store.apple.com") == "apple")

        // ---- step 2: registry publisher by domain ---------------------------------------
        val nhc = r.resolve(SearchHit("https://www.nhc.gov.cn/guide"))
        check("R1 registry domain grants AUTHORITATIVE", nhc.tier == Tier.AUTHORITATIVE, "got ${nhc.tier}")
        check("R1 resolves via RegistryDomain", nhc.via is Via.RegistryDomain, "got ${nhc.via}")
        check("registry match is not promotable", !nhc.promotable)

        // ---- step 3: patterns, and the .gov.hk bug found by the live check ---------------
        val hk = r.resolve(SearchHit("https://www.drugoffice.gov.hk/eps/drug/x"))
        check("*.gov.hk grants AUTHORITATIVE", hk.tier == Tier.AUTHORITATIVE, "got ${hk.tier}")
        check("*.gov.hk resolves via pattern",
            (hk.via as? Via.PatternMatch)?.pattern == "*.gov.hk", "got ${hk.via}")

        // ---- specificity: an index on an authoritative domain stays an index -------------
        val pubmed = r.resolve(SearchHit("https://pubmed.ncbi.nlm.nih.gov/12345/"))
        check("PubMed beats *.gov by specificity",
            pubmed.tier == Tier.INSTITUTIONAL, "got ${pubmed.tier} via ${pubmed.via}")

        // ---- step 1: platforms are venues, not sources -----------------------------------
        val bareWeixin = r.resolve(SearchHit("https://mp.weixin.qq.com/s/abc"))
        check("platform with no account falls to floor",
            bareWeixin.tier == Tier.LOW && bareWeixin.via is Via.PlatformFloor,
            "got ${bareWeixin.tier} via ${bareWeixin.via}")

        val realAccount = r.resolve(SearchHit("https://mp.weixin.qq.com/s/abc", account = "健康中国"))
        check("registry account identifies the publisher",
            realAccount.via is Via.PlatformPublisher, "got ${realAccount.via}")
        check("platform-publisher-cap caps AUTHORITATIVE at INSTITUTIONAL",
            realAccount.tier == Tier.INSTITUTIONAL, "got ${realAccount.tier}")

        // no-promotion-by-name — the security rule
        val impostor = r.resolve(SearchHit("https://mp.weixin.qq.com/s/x", account = "中国卫生健康"))
        check("SECURITY: lookalike account gets nothing",
            impostor.tier == Tier.LOW && impostor.via is Via.PlatformFloor,
            "got ${impostor.tier} via ${impostor.via}")

        val verifiedUnknown = r.resolve(
            SearchHit("https://mp.weixin.qq.com/s/x", account = "中国卫生健康", verified = true),
        )
        check("SECURITY: verified-but-unlisted is still the floor",
            verifiedUnknown.tier == Tier.LOW,
            "verified proves the account is someone, not that it is the health authority; got ${verifiedUnknown.tier}")

        check("UGC platform floor is PERSONAL, not LOW",
            tierOf("https://www.zhihu.com/question/1") == Tier.PERSONAL)
        check("content-farm platform floor is LOW",
            tierOf("https://baijiahao.baidu.com/s?id=1") == Tier.LOW)
        check("content-farm platform floor is not promotable",
            !r.resolve(SearchHit("https://baijiahao.baidu.com/s?id=1")).promotable)

        // ---- step 4: R3 brand heuristic, scoped to the claim -----------------------------
        val brand = ClaimContext(brandsInQuery = listOf("apple"))
        check("R3 brand official is AUTHORITATIVE for attributes",
            tierOf("https://www.apple.com/iphone/specs", brand) == Tier.AUTHORITATIVE)
        check("R3 drops to INSTITUTIONAL for evaluative claims",
            tierOf("https://www.apple.com/iphone/why", brand.copy(claimKind = ClaimKind.EVALUATIVE))
                == Tier.INSTITUTIONAL)
        check("R3 does not fire without the brand in the query",
            tierOf("https://www.apple.com/iphone/specs") == Tier.LOW)

        // ---- seller-efficacy can demote anything ----------------------------------------
        val sellerHit = SearchHit("https://www.nature.com/articles/x", sellsSubject = true)
        val seller = r.resolve(sellerHit, ClaimContext(claimKind = ClaimKind.EFFICACY))
        check("seller-efficacy demotes even AUTHORITATIVE",
            seller.tier == Tier.LOW, "got ${seller.tier}")
        check("seller demotion is not promotable", !seller.promotable)
        check("seller rule does not fire on attribute claims",
            r.resolve(sellerHit, ClaimContext(claimKind = ClaimKind.OBJECTIVE_ATTRIBUTE)).tier
                == Tier.AUTHORITATIVE)

        // ---- step 5: unknown ------------------------------------------------------------
        val unknown = r.resolve(SearchHit("https://xuandao.la/post/1"))
        check("unknown domain is LOW", unknown.tier == Tier.LOW)
        check("R2 unknown is promotable", unknown.promotable)

        // ---- R8 attribution -------------------------------------------------------------
        val who = r.resolve(SearchHit("https://www.who.int/news/x"))
        check("R8 foreign source carries an intro",
            who.attribution() == "世界卫生组织（联合国下属的全球卫生机构）", "got ${who.attribution()}")
        check("R8 domestic source needs no intro",
            nhc.attribution() == "国家卫生健康委员会", "got ${nhc.attribution()}")

        failures += corroborationChecks(registry)
        return failures
    }

    private fun corroborationChecks(registry: SourceRegistry): List<String> {
        val failures = mutableListOf<String>()
        fun check(name: String, condition: Boolean, detail: String = "") {
            if (!condition) failures += "$name${if (detail.isNotEmpty()) " — $detail" else ""}"
        }

        // R4 threshold matrix
        check("R4 ordinary, unbacked = 5",
            Corroboration.requiredSources(Topic.GENERAL, false) == 5)
        check("R4 ordinary, backed = 3",
            Corroboration.requiredSources(Topic.GENERAL, true) == 3)
        check("R4 important, unbacked = 9",
            Corroboration.requiredSources(Topic.HEALTH, false) == 9)
        check("R4 important, backed = 5",
            Corroboration.requiredSources(Topic.MEDICATION, true) == 5)

        val r = TrustResolver(registry)
        fun ev(url: String, text: String) =
            Evidence(SearchHit(url, title = text), r.resolve(SearchHit(url, title = text)))

        // R5: echo is not corroboration — same text, different domains, one source
        val echoed = listOf(
            ev("https://a-site.com/1", "奇亚籽 可以 快速 减肥 效果 显著 每天 两勺"),
            ev("https://b-site.com/1", "奇亚籽 可以 快速 减肥 效果 显著 每天 两勺"),
            ev("https://c-site.com/1", "奇亚籽 可以 快速 减肥 效果 显著 每天 两勺"),
        )
        check("R5 near-duplicate text collapses to one source",
            Corroboration.countIndependent(echoed, registry) == 1,
            "got ${Corroboration.countIndependent(echoed, registry)}")

        // R5: same corporate owner, different text, still one source
        val sameOwner = listOf(
            ev("https://baijiahao.baidu.com/s?id=1", "手机 发热 严重 用了 一个月"),
            ev("https://tieba.baidu.com/p/2", "完全 不同 的 内容 关于 续航 表现"),
        )
        check("R5 owner group collapses to one source",
            Corroboration.countIndependent(sameOwner, registry) == 1,
            "got ${Corroboration.countIndependent(sameOwner, registry)}")

        // genuinely independent
        val independent = listOf(
            ev("https://www.zhihu.com/answer/1", "我 用 下来 觉得 发烫 明显"),
            ev("https://www.douban.com/note/2", "续航 一天 半 完全 够用 没问题"),
            ev("https://www.v2ex.com/t/3", "信号 在 地铁 里 很 差 经常 断"),
        )
        check("independent sources count separately",
            Corroboration.countIndependent(independent, registry) == 3,
            "got ${Corroboration.countIndependent(independent, registry)}")

        check("R4 gate blocks 3 sources on a health question",
            !Corroboration.canStatePattern(independent, registry, Topic.HEALTH, false))
        check("R4 gate allows 3 sources on a backed ordinary question",
            Corroboration.canStatePattern(independent, registry, Topic.GENERAL, true))

        // contested institutional evidence is not "backing"
        val strong = Evidence(SearchHit("https://www.who.int/a"), r.resolve(SearchHit("https://www.who.int/a")))
        check("uncontested backing detected",
            Corroboration.hasUncontestedHighConfidence(listOf(strong), emptyList()))
        check("contested institutional evidence is not backing",
            !Corroboration.hasUncontestedHighConfidence(listOf(strong), listOf(strong)))

        return failures
    }
}
