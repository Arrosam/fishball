package org.areel.fishball.core.agent

import kotlinx.serialization.json.JsonObject
import org.areel.fishball.core.agent.Tools
import org.areel.fishball.core.copy.AgentPrompt
import org.areel.fishball.core.memory.ToolRound

/**
 * What this turn has already tried, so it cannot spend the rest of itself trying it again.
 *
 * The loop has no memory of its own. Every round hands the model the same tools against a thread
 * it can read, and a model that reads "这一页打不开" as a transient failure will ask for the same
 * page again, read the same sentence, and ask again - measured live at eleven consecutive rounds
 * on one 404. Nothing in the loop noticed, because nothing was counting: [LAST_ROUND] is a
 * hundred rounds away and the rounds were not idle, they were busy doing the same thing.
 *
 * Two indexes, because there are two ways to repeat yourself here.
 *
 * The first is the whole call: same tool, same arguments. Inside one turn that is a call whose
 * answer is already in the thread, so it is not run - the model is told so and told what else
 * there is to do, which is the part that actually changes its behaviour. See
 * [AgentPrompt.repeatedCall].
 *
 * The second is the address alone. `read_page` with a different `find` is a different call and
 * the same dead fetch, and varying `find` is exactly what a model does when a page will not
 * open. So a URL that failed is remembered as a URL - see [AgentPrompt.deadPage].
 *
 * One of these per turn, and it dies with the turn: a page that was down an hour ago is worth
 * another go, and a search whose results are stale by tomorrow is worth running again.
 */
internal class Tried {

    private val calls = LinkedHashMap<String, Attempt>()
    private val graves = LinkedHashMap<String, Attempt>()

    /**
     * How many calls have been handed back unrun since the turn began.
     *
     * Read by the loop rather than pushed to it: the two rejections happen at different depths -
     * one in the dispatcher, one inside `openPage` - and a counter both of them already touch is
     * cheaper than threading a flag back through a tool result. The loop snapshots it either side
     * of a round and learns whether that round did anything at all.
     */
    var blocked: Int = 0
        private set

    /**
     * What an earlier run of this same turn already did, put back.
     *
     * A turn the app was killed in the middle of comes back with its rounds, and without this it
     * came back with none of what they taught: the page that would not open was fetched again,
     * and the three futile rounds it takes to notice were spent again. The rounds are the record
     * of exactly what this index is for, so they are read straight out of it.
     *
     * The reason a page failed is not kept on the exchange, only the sentence the model was
     * handed - so the address is buried under [AgentPrompt.EARLIER_FAILURE] rather than a
     * reconstruction of what went wrong. What matters is that it is dead, not how.
     */
    fun recall(rounds: List<ToolRound>) {
        rounds.forEach { round ->
            round.exchanges.forEach { done ->
                record(done.name, done.input)
                if (done.isError && done.name == Tools.READ) {
                    done.input["url"]?.let { url ->
                        bury(url.toString().trim('"'), AgentPrompt.EARLIER_FAILURE)
                    }
                }
            }
        }
    }

    /** The rejection for a call already made word for word, or null to go ahead and make it. */
    fun repeat(name: String, input: JsonObject): String? {
        val seen = calls[key(name, input)] ?: return null
        seen.times++
        blocked++
        return AgentPrompt.repeatedCall(name, seen.times)
    }

    /**
     * This call, made. Recorded after it runs, so a call rejected for being a repeat does not
     * come back through here and restart its own count.
     */
    fun record(name: String, input: JsonObject) {
        calls.getOrPut(key(name, input)) { Attempt() }
    }

    /** The rejection for a page already known not to open, or null to go and fetch it. */
    fun deadEnd(url: String): String? {
        val address = plain(url)
        val grave = graves[address] ?: return null
        grave.times++
        blocked++
        return AgentPrompt.deadPage(
            url = url,
            reason = grave.reason.orEmpty(),
            times = grave.times,
            // Named so a model bouncing between two dead links sees both of them at once, rather
            // than being told about each in turn for as many rounds as it has links.
            others = graves.keys.filterNot { it == address },
        )
    }

    /** This page, and why it would not open. */
    fun bury(url: String, reason: String) {
        graves.getOrPut(plain(url)) { Attempt(reason) }
    }

    private class Attempt(val reason: String? = null) {
        /** Including the one that was actually run, so the first rejection reads as the second try. */
        var times: Int = 1
    }

    private companion object {

        /**
         * A call's identity: the tool, and its arguments with the field order taken out.
         *
         * Sorted because the same call written twice is the same call however the provider
         * happened to order the object, and an index that misses on key order is an index that
         * misses on the case it exists for.
         */
        fun key(name: String, input: JsonObject): String =
            name + "\u0000" + input.entries
                .sortedBy { it.key }
                .joinToString("\u0001") { it.key + "=" + it.value }

        /**
         * An address, near enough.
         *
         * A trailing slash and a fragment are the two ways the same page arrives written
         * differently - the fragment especially, because a model that cannot open a page tries
         * the anchor it saw in the link list next.
         */
        fun plain(url: String): String =
            url.trim().substringBefore('#').trimEnd('/').ifEmpty { url.trim() }
    }
}
