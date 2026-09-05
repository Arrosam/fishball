package org.areel.fishball.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Where this install's answers come from, and what it pays with.
 *
 * The app used to hold this as a token and a pile of constants — one base URL compiled in, two
 * model names in `Backend`, a third in `Conversation`, an embedding and a reranker in
 * `HydrogenClient`, a search host in `Backend` again. That was right while there was exactly one
 * deployment. Spec §1 hands somebody a code in person, and for as long as every code pointed at
 * the same service the code only had to carry the half that differed, which was the token.
 *
 * A custom profile changes which service answers, so all of it has to travel together. The
 * important consequence is not that the fields moved: it is that **there is now one kind of
 * install**. An areel activation code is [areel], a Provider whose token slot is filled and
 * whose other fields happen to be the ones that used to be compiled in. Nothing below the parser
 * asks which sort of code it was, because there is no longer a question to ask.
 *
 * [custom] is the one exception, and it exists for the gate rather than for the client — a
 * pasted profile names a server the app will send this person's conversations to, and that is
 * worth showing them before it is stored. See [ActivationCode].
 */
data class Provider(
    /** No trailing slash. `/v1/messages` and the rest are appended to it. */
    val llmUrl: String,
    val token: String,
    /**
     * The model the bookkeeping runs on — reading a fork, sifting results, filing a memory.
     * Everything nobody is waiting to read. Called `fish-system` on the areel deployment.
     */
    val systemModel: String,
    /** 快速模式. */
    val flash: String,
    /** 专业模式. */
    val pro: String,
    /**
     * Null when the provider offers none.
     *
     * Not a failure: recall falls back to word overlap, which finds a cached answer only when
     * the question is asked in nearly the same words. Worse, and it still works. What matters
     * is that null is *said* rather than discovered, so the client skips a call it knows will
     * 404 instead of spending a round trip per turn learning the same thing again.
     */
    val embeddingModel: String?,
    /**
     * Null when the provider offers none — and this one is not merely degraded, it has to be
     * handled. A reranker that is absent scores nothing, and code that reads those zeros as
     * scores throws away every candidate it was given. See `Conversation.narrowAnswers`.
     */
    val rerankModel: String?,
    /** A SearXNG instance. Required, including in a custom profile — see [ActivationCode]. */
    val searchUrl: String,
    /** Sent as `X-FishBall-Token`. Null for an instance that does not ask for one. */
    val searchToken: String?,
    /**
     * Whether this came from a pasted profile rather than an areel activation code.
     *
     * Read by the gate and the settings screen, never by the client. A custom profile earns one
     * confirmation step and a different set of error sentences: when the service is somebody's
     * own, "跟给你激活码的人说一声" is advice to talk to themselves.
     */
    val custom: Boolean,
) {

    /** The host, for a screen that has to say where this install is pointed. */
    fun llmHost(): String = llmUrl.substringAfter("://").substringBefore('/')

    fun searchHost(): String = searchUrl.substringAfter("://").substringBefore('/')

    /** Enough of the token to recognise it by, and not enough to read off a screen. */
    fun tokenHint(): String =
        if (token.length <= 10) token else token.take(6) + "…" + token.takeLast(4)

    /** The two chat models, strongest first — what sign-in probes and in what order. */
    fun chatModels(): List<String> = listOf(pro, flash)

    companion object {
        const val AREEL_LLM = "https://llm.areel.org"
        const val AREEL_SEARCH = "https://search.areel.org"
        const val AREEL_SYSTEM = "fish-system"
        const val AREEL_FLASH = "fishball-flash"
        const val AREEL_PRO = "fishball-pro"
        const val AREEL_EMBED = "embedding"
        const val AREEL_RERANK = "reranker"

        /**
         * What an ordinary activation code means, which is everything it did before plus a name
         * for each of the things it used to leave implied.
         */
        fun areel(token: String): Provider = Provider(
            llmUrl = AREEL_LLM,
            token = token,
            systemModel = AREEL_SYSTEM,
            flash = AREEL_FLASH,
            pro = AREEL_PRO,
            embeddingModel = AREEL_EMBED,
            rerankModel = AREEL_RERANK,
            searchUrl = AREEL_SEARCH,
            searchToken = null,
            custom = false,
        )
    }
}

/** What came of reading what somebody pasted into the one field this app has. */
sealed interface Activation {

    data class Ok(val provider: Provider) : Activation

    /**
     * The paste was meant to be a profile and was not one.
     *
     * Only reachable for something that announced itself with the prefix. Anything else is an
     * areel code by definition and is not this app's to validate — the service decides that.
     */
    data class Malformed(val reason: Reason, val detail: String) : Activation

    enum class Reason {
        /**
         * It arrived in pieces. Its own case because it is the likely one and the only one the
         * user can act on: codes travel through chat apps, chat apps wrap and elide long
         * strings, and "paste it again" fixes this and nothing else.
         */
        TRUNCATED,

        /** Present in full and not a profile — wrong prefix, corrupt bytes, not JSON. */
        UNREADABLE,

        /** A profile with a hole in it. [Malformed.detail] names the field. */
        MISSING_FIELD,

        /**
         * A URL this app will not send a token to.
         *
         * Its own case because it is the one that is somebody else's fault. A profile names a
         * server that receives this person's conversations and their provider token, and codes
         * are handed over by other people — so `http://` is refused rather than upgraded, and
         * the refusal says which URL, so a mistake can be told from an attempt.
         */
        INSECURE_URL,
    }
}

/**
 * The one field on the gate, and how it tells the two kinds of code apart.
 *
 * The rule is total and decided on the first four characters: a string beginning `fb1.` is a
 * profile, and everything else is an areel activation code handled exactly as it always was.
 * No try-parse, no guessing from shape, and no way for a real activation code to be mistaken
 * for a broken profile — areel codes are minted by us and none of them starts with the prefix.
 *
 * ## The wire format
 *
 *     fb1.<base64url(deflate(json))>.<crc16>
 *
 * Base64 rather than raw JSON in the field, for three reasons that all come from the same
 * place: this string is pasted by a person, through a Chinese chat app, into a phone. Raw JSON
 * meets an IME that turns `"` into `“`. Any delimiter scheme meets a base URL or a token that
 * contains the delimiter. And a field showing braces and quotes tells a first-time user they
 * have been handed the wrong thing.
 *
 * Deflated because JSON keys repeat and the profile is otherwise around 420 characters of
 * base64; deflate takes a realistic one to roughly half that. The checksum is the part that
 * earns its place: a code that arrives cut short loses its last segment entirely, so a paste
 * that lost its tail is reported as 不完整 rather than as an invalid code, which is the
 * difference between "paste it again" and "go back to whoever gave you this".
 *
 * Encoding is not secrecy. Whoever holds this string holds the token, exactly as with an
 * activation code, and both are stored the same way.
 */
object ActivationCode {

    const val PREFIX = "fb1."

    fun parse(raw: String): Activation {
        val cleaned = clean(raw)
        if (!cleaned.startsWith(PREFIX, ignoreCase = true)) {
            return Activation.Ok(Provider.areel(cleaned))
        }
        return when (val opened = unseal(cleaned)) {
            is Unsealed.Failed -> Activation.Malformed(opened.reason, opened.detail)
            is Unsealed.Text -> {
                val root = runCatching { Json.parseToJsonElement(opened.json).jsonObject }.getOrNull()
                    ?: return Activation.Malformed(
                        Activation.Reason.UNREADABLE,
                        "payload is not JSON",
                    )
                read(root)
            }
        }
    }

    /** The inverse, for minting a code and for the round-trip test that keeps the two honest. */
    fun encode(provider: Provider): String = seal(
        buildJsonObject {
            put("v", 1)
            putJsonObject("llm") {
                put("url", provider.llmUrl)
                put("key", provider.token)
                put("sys", provider.systemModel)
                put("flash", provider.flash)
                put("pro", provider.pro)
                provider.embeddingModel?.let { put("embed", it) }
                provider.rerankModel?.let { put("rerank", it) }
            }
            putJsonObject("search") {
                put("url", provider.searchUrl)
                provider.searchToken?.let { put("key", it) }
            }
        }.toString(),
    )

    // ---- the envelope ------------------------------------------------------------------------

    /**
     * The packaging, split from the meaning on purpose.
     *
     * [encode] and [parse] are about what a profile *says*; these two are about getting a string
     * from one phone to another intact. Keeping them apart is what lets a test tamper with the
     * JSON and re-seal it, so the field reader can be tested against a code that is genuinely
     * well-formed rather than against a hand-built string that only resembles one.
     */
    internal fun seal(json: String): String {
        val packed = deflate(json.toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
        return PREFIX + payload + "." + crc16(packed)
    }

    internal sealed interface Unsealed {
        data class Text(val json: String) : Unsealed
        data class Failed(val reason: Activation.Reason, val detail: String) : Unsealed
    }

    internal fun unseal(code: String): Unsealed {
        val cleaned = clean(code)
        if (!cleaned.startsWith(PREFIX, ignoreCase = true)) {
            return Unsealed.Failed(Activation.Reason.UNREADABLE, "no $PREFIX prefix")
        }
        val parts = cleaned.removeRange(0, PREFIX.length).split('.')
        if (parts.size != 2 || parts.any { it.isEmpty() }) {
            // Two segments after the prefix, or it is not whole. A profile that lost its tail
            // in transit lands here, which is the common failure and half the point of the
            // checksum: the tail is the checksum, so losing it is structurally detectable.
            return Unsealed.Failed(
                Activation.Reason.TRUNCATED,
                "expected ${PREFIX}<payload>.<checksum>, got ${parts.size} segment(s)",
            )
        }
        val (payload, checksum) = parts

        val packed = runCatching { Base64.getUrlDecoder().decode(payload) }.getOrNull()
            ?: return Unsealed.Failed(Activation.Reason.UNREADABLE, "payload is not base64url")

        val actual = crc16(packed)
        if (!actual.equals(checksum, ignoreCase = true)) {
            return Unsealed.Failed(
                Activation.Reason.TRUNCATED,
                "checksum $checksum does not match the payload ($actual)",
            )
        }

        val json = inflate(packed)
            ?: return Unsealed.Failed(Activation.Reason.UNREADABLE, "payload does not unpack")
        return Unsealed.Text(json)
    }

    // ---- reading the object ----------------------------------------------------------------

    private fun read(root: JsonObject): Activation {
        val llm = root["llm"]?.jsonObject
            ?: return missing("llm")
        val search = root["search"]?.jsonObject
            ?: return missing("search")

        val llmUrl = llm.text("url") ?: return missing("llm.url")
        val token = llm.text("key") ?: return missing("llm.key")
        val system = llm.text("sys") ?: return missing("llm.sys")
        val flash = llm.text("flash") ?: return missing("llm.flash")
        val pro = llm.text("pro") ?: return missing("llm.pro")

        // Required in a custom profile, and deliberately so. §23 means a factual question with
        // no working search gets 「我现在查不了资料」 and nothing else, so a profile without one
        // is an app that refuses to answer anything - discovered on the first question rather
        // than at the gate. The alternative, quietly falling back to the areel instance, spends
        // somebody else's bandwidth without either party being told.
        val searchUrl = search.text("url") ?: return missing("search.url")

        insecure(llmUrl)?.let { return it }
        insecure(searchUrl)?.let { return it }

        return Activation.Ok(
            Provider(
                llmUrl = llmUrl.trimEnd('/'),
                token = token,
                systemModel = system,
                flash = flash,
                pro = pro,
                embeddingModel = llm.text("embed"),
                rerankModel = llm.text("rerank"),
                searchUrl = searchUrl.trimEnd('/'),
                searchToken = search.text("key"),
                custom = true,
            ),
        )
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun missing(field: String) =
        Activation.Malformed(Activation.Reason.MISSING_FIELD, field)

    /** `https://` or nothing. Not upgraded — see [Activation.Reason.INSECURE_URL]. */
    private fun insecure(url: String): Activation.Malformed? =
        if (url.startsWith("https://", ignoreCase = true) && url.length > "https://".length) {
            null
        } else {
            Activation.Malformed(Activation.Reason.INSECURE_URL, url)
        }

    // ---- the bytes ---------------------------------------------------------------------------

    /**
     * Everything a paste picks up on the way, removed before anything looks at it.
     *
     * Whitespace anywhere, not merely at the ends: a long code wrapped by a chat client and
     * copied back arrives with newlines through the middle of it. And the zero-width characters,
     * which are invisible, survive a copy, and would otherwise corrupt the base64 of a code that
     * looks perfectly correct on screen.
     */
    private fun clean(raw: String): String = raw.filterNot { it.isWhitespace() || it.isInvisible() }

    private fun Char.isInvisible(): Boolean = this == '﻿' || this in '​'..'‍'

    private fun crc16(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return "%04x".format(crc.value and 0xFFFF)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Null rather than throwing: a corrupt paste is an answer, not an exception. */
    private fun inflate(bytes: ByteArray): String? {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        return try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                // A truncated deflate stream reports neither finished nor progress, and the
                // loop would spin forever on it. The checksum should have caught that already;
                // this is the guard for when it has not.
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        } catch (e: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private const val BUFFER = 1024
}
