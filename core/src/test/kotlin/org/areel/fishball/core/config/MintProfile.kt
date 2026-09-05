package org.areel.fishball.core.config

/**
 * Mints an activation code for a custom provider.
 *
 *     ./gradlew :core:mintProfile -Purl=https://api.deepseek.com/anthropic -Pkey=sk-... \
 *         -Psys=deepseek-v4-flash -Pflash=deepseek-v4-flash -Ppro=deepseek-v4-pro \
 *         -Psearch=https://search.example.com
 *
 * Optional on top of those: `-Pembed=`, `-Prerank=`, `-Pasr=`, `-PsearchKey=`. Leave one out and
 * the profile says the provider does not offer it — which is a real statement with consequences,
 * not a blank: no `rerank` means recall keeps cosine's order instead of narrowing, and no `asr`
 * hides the microphone.
 *
 * It lives in the test source set on purpose. It is a workbench tool, it must not be reachable
 * from the shipped app, and putting a `main` in `:core`'s production code to save a line of
 * Gradle would put it there.
 *
 * The minted code is parsed straight back and its destination printed. A code is a thing you
 * hand to somebody who then cannot use the app, and checking it here costs nothing next to
 * finding out from them.
 */
object MintProfile {

    @JvmStatic
    fun main(args: Array<String>) {
        val given = args.mapNotNull { arg ->
            val at = arg.indexOf('=')
            if (arg.startsWith("--") && at > 2) arg.substring(2, at) to arg.substring(at + 1) else null
        }.toMap().filterValues { it.isNotBlank() }

        val missing = REQUIRED.filterNot { given.containsKey(it) }
        if (missing.isNotEmpty()) {
            System.err.println("missing: " + missing.joinToString(", "))
            System.err.println("required: " + REQUIRED.joinToString(", "))
            System.err.println("optional: " + OPTIONAL.joinToString(", "))
            return
        }

        val provider = Provider(
            llmUrl = given.getValue("url").trimEnd('/'),
            token = given.getValue("key"),
            systemModel = given.getValue("sys"),
            flash = given.getValue("flash"),
            pro = given.getValue("pro"),
            embeddingModel = given["embed"],
            rerankModel = given["rerank"],
            asrModel = given["asr"],
            searchUrl = given.getValue("search").trimEnd('/'),
            searchToken = given["searchKey"],
            custom = true,
        )

        val code = ActivationCode.encode(provider)

        // Read back through the same door the phone will use, so a profile that cannot be
        // activated is caught here rather than by the person holding it.
        when (val check = ActivationCode.parse(code)) {
            is Activation.Malformed -> {
                System.err.println("REFUSED: ${check.reason} ${check.detail}")
                return
            }
            is Activation.Ok -> {
                val back = check.provider
                // ASCII, deliberately. The user-facing copy in this product is Chinese and
                // lives in strings.xml; this is a workbench tool read over whatever terminal
                // the operator happens to have, and a Windows console decoding UTF-8 as GBK
                // turns a verification step into mojibake.
                println("llm      " + back.llmHost())
                println("search   " + back.searchHost())
                println("chat     " + back.flash + " / " + back.pro)
                println("system   " + back.systemModel)
                println("embed    " + (back.embeddingModel ?: "none - recall falls back to word overlap"))
                println("rerank   " + (back.rerankModel ?: "none - recall keeps cosine order, unnarrowed"))
                println("asr      " + (back.asrModel ?: "none - the microphone is hidden"))
                // Not `tokenHint()`. That one is for the settings screen and spells its gap
                // with an ellipsis, which is the right character there and another mojibake
                // here. Same job, ASCII.
                val token = back.token
                println(
                    "token    " + if (token.length <= 10) {
                        token
                    } else {
                        token.take(6) + "..." + token.takeLast(4)
                    },
                )
                println()
                println(code)
                println()
                println("${code.length} chars")
            }
        }
    }

    private val REQUIRED = listOf("url", "key", "sys", "flash", "pro", "search")
    private val OPTIONAL = listOf("embed", "rerank", "asr", "searchKey")
}
