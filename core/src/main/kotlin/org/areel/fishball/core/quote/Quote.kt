package org.areel.fishball.core.quote

import org.areel.fishball.core.copy.AgentPrompt

/**
 * Extractive citation. Spec §25.
 *
 * The model may not *write* a quotation — it may only *select* one. Every quote it attaches to
 * a claim is checked against the retrieved source text, and a span that does not occur there is
 * rejected with feedback telling it so. A fabricated quotation cannot reach the user, because
 * the pipeline has no path for one.
 *
 * Two properties make this airtight rather than merely discouraging:
 *
 *  1. **What gets displayed is the source's text, not the model's.** On a match the verifier
 *     returns the span sliced out of the original document, not the string the model typed.
 *     So even a near-miss that passes normalisation still shows the user the real wording.
 *  2. **Failure is loud.** A rejected quote returns a reason the model can act on, and the
 *     caller decides what to do when it runs out of attempts. Nothing degrades silently into
 *     an uncited assertion.
 *
 * What this does *not* guarantee, and should not be sold as: a real quote can still be
 * cherry-picked, or lifted from a sentence that reverses it two clauses later. This kills
 * invented quotations. It does not certify honest ones.
 */

/** Retrieved text of one source, against which quotes are checked. */
data class SourceText(val url: String, val text: String)

/** A span the model wants to cite. */
data class QuoteRequest(val url: String, val text: String)

/**
 * A quote proven to exist. [exact] is sliced from the source document, so it is the source's
 * wording and punctuation — never the model's rendering of it.
 */
data class VerifiedQuote(
    val url: String,
    val exact: String,
    val startIndex: Int,
    val endIndex: Int,
)

enum class RejectionReason {
    /** The span does not occur in the source. The case this whole mechanism exists for. */
    NOT_FOUND,

    /** No retrieved text for that URL, so nothing can be verified against it. */
    SOURCE_UNAVAILABLE,

    /** Too short to identify anything — a few characters match almost any document. */
    TOO_SHORT,

    /** Long enough that it is republication rather than citation. */
    TOO_LONG,
}

sealed class QuoteResult {
    data class Verified(val quote: VerifiedQuote) : QuoteResult()

    /** [feedback] is written for the model and is what gets returned to it on a retry. */
    data class Rejected(val reason: RejectionReason, val feedback: String) : QuoteResult()
}

class QuoteVerifier(
    private val minChars: Int = DEFAULT_MIN_CHARS,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {

    fun verify(request: QuoteRequest, sources: List<SourceText>): QuoteResult {
        val source = sources.firstOrNull { it.url == request.url }
            ?: return reject(RejectionReason.SOURCE_UNAVAILABLE)

        val needle = normalize(request.text)
        when {
            needle.text.length < minChars -> return reject(RejectionReason.TOO_SHORT)
            needle.text.length > maxChars -> return reject(RejectionReason.TOO_LONG)
        }

        val haystack = normalize(source.text)
        val at = haystack.text.indexOf(needle.text)
        if (at < 0) return reject(RejectionReason.NOT_FOUND)

        // Map back to the original document so the user is shown the source's own wording.
        val start = haystack.sourceIndex[at]
        val end = haystack.sourceIndex[at + needle.text.length - 1] + 1
        return QuoteResult.Verified(
            VerifiedQuote(
                url = source.url,
                exact = source.text.substring(start, end),
                startIndex = start,
                endIndex = end,
            ),
        )
    }

    fun verifyAll(requests: List<QuoteRequest>, sources: List<SourceText>): List<QuoteResult> =
        requests.map { verify(it, sources) }

    private fun reject(reason: RejectionReason) =
        QuoteResult.Rejected(reason, AgentPrompt.QuoteFeedback.forReason(reason))

    companion object {
        /**
         * Four Chinese characters, or a short English phrase. Below this a span is not evidence
         * of anything — it will occur by chance in most documents.
         */
        const val DEFAULT_MIN_CHARS = 8

        /** Past this it stops being a citation and becomes republishing the page. */
        const val DEFAULT_MAX_CHARS = 400
    }
}

/** Normalised text plus, per normalised character, its index in the original. */
private class Normalized(val text: String, val sourceIndex: IntArray)

/**
 * Folds away the differences that are not differences in wording.
 *
 * Exact byte matching is the wrong strictness here: retrieved HTML carries collapsed
 * whitespace, zero-width joiners and full-width punctuation, so a genuine quote would be
 * rejected constantly — and a verifier that rejects honest quotes trains the caller to stop
 * asking for them. The fold is applied identically to both sides, so it never *creates* a
 * match between different wordings; it only removes typographic noise.
 */
private fun normalize(input: String): Normalized {
    val out = StringBuilder(input.length)
    val index = IntArray(input.length)
    var pendingSpace = false

    for (i in input.indices) {
        val c = input[i]
        if (isZeroWidth(c)) continue

        if (c.isWhitespace() || c == '　') {
            if (out.isNotEmpty()) pendingSpace = true
            continue
        }
        if (pendingSpace) {
            index[out.length] = i
            out.append(' ')
            pendingSpace = false
        }
        index[out.length] = i
        out.append(fold(c))
    }
    return Normalized(out.toString(), index.copyOf(out.length))
}

private fun isZeroWidth(c: Char): Boolean =
    c == '​' || c == '‌' || c == '‍' || c == '﻿' || c == '­'

/** Full-width ASCII forms collapse to their half-width equivalents; Latin case is ignored. */
private fun fold(c: Char): Char {
    val halfWidth = if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c
    return halfWidth.lowercaseChar()
}
