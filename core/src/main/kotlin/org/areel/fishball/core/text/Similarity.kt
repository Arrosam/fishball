package org.areel.fishball.core.text

/**
 * Character-bigram similarity.
 *
 * Bigrams rather than whitespace tokens because the corpus is Chinese, where whitespace
 * tokenisation is close to meaningless: a whole Chinese phrase is one whitespace token and
 * several useful bigrams.
 *
 * Used for two different jobs: detecting reposted text (spec R5, echo is not corroboration)
 * and matching a repeat question against cached world knowledge (spec §10).
 */
object Similarity {

    fun bigrams(text: String): Set<String> {
        val cleaned = text.filter { it.isLetterOrDigit() }.lowercase()
        if (cleaned.length < 2) return emptySet()
        return (0 until cleaned.length - 1).mapTo(mutableSetOf()) { cleaned.substring(it, it + 2) }
    }

    /** Jaccard overlap in 0.0..1.0. Zero when either side has no bigrams. */
    fun jaccard(a: String, b: String): Double {
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val intersection = x.count { it in y }
        val union = x.size + y.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * Two texts are the same content restated. Deliberately high — the cost of a false
     * positive is losing a genuine corroborating source, and the cost of a false negative is
     * mistaking twenty reposts for a consensus.
     */
    fun isEcho(a: String, b: String, threshold: Double = 0.8): Boolean =
        jaccard(a, b) >= threshold

    /**
     * Two questions are the same question. Lower than the echo threshold because phrasing
     * varies far more than reposted text does.
     *
     * This is a stopgap. A repeat question worded differently - "how big is the battery" versus
     * "what is the battery capacity" - will miss. The only cost of a miss is an unnecessary
     * search, which is the safe direction. Swap in embeddings when the app has an embedder.
     */
    fun isSameQuestion(a: String, b: String, threshold: Double = SAME_QUESTION_THRESHOLD): Boolean =
        jaccard(a, b) >= threshold

    const val SAME_QUESTION_THRESHOLD = 0.62
    const val ECHO_THRESHOLD = 0.8
}
