package org.areel.fishball.data

import android.content.Context
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.agent.MemoryBus
import org.areel.fishball.core.config.Activation
import org.areel.fishball.core.config.ActivationCode
import org.areel.fishball.core.config.Provider
import org.areel.fishball.core.llm.HydrogenClient
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.core.memory.PersistentStore
import org.areel.fishball.core.memory.SnapshotIo
import org.areel.fishball.core.search.HttpPageReader
import org.areel.fishball.core.search.SearxngGateway
import org.areel.fishball.core.trust.loadBundledRegistry
import java.io.File

/**
 * Everything the app needs that is not a pixel, assembled in one place.
 *
 * No DI framework. There are four objects, they are constructed once, and the wiring is three
 * lines long — a container that can be read top to bottom is worth more here than one that
 * can be reconfigured.
 */
class Backend private constructor(
    private val context: Context,
    val store: PersistentStore,
) {

    private val registry by lazy { loadBundledRegistry() }

    /**
     * Where answers come from and what pays for them. Null before the first sign-in.
     *
     * An areel activation code produces one of these too — `Provider.areel(token)` — so nothing
     * below here asks which kind of code was typed. See [org.areel.fishball.core.config.Provider].
     */
    var provider: Provider? = null
        private set

    /**
     * One HTTP client for whatever SearXNG instance is current.
     *
     * Shared rather than built per gateway for the reason [pages] is a singleton: a gateway owns
     * a connection pool and dispatcher threads, and a profile change that built a new one would
     * leave the old pool behind with nothing able to close it.
     */
    private val searchHttp by lazy { SearxngGateway.defaultClient() }

    /**
     * Rebuilt when the provider changes, which is the only time the instance can change.
     *
     * `lateinit` rather than defaulted to the areel instance. There is no correct value before a
     * provider exists, and a default would make "searched before anything was activated" read as
     * a search of search.areel.org with somebody else's profile loaded - silently wrong, which
     * is the failure this whole change is about. Set by [adopt], which both entry points call.
     */
    private lateinit var search: SearxngGateway

    /**
     * One reader for the life of the app, not one per conversation.
     *
     * [talk] builds a fresh Conversation every time the model changes, and the reader owns an
     * OkHttp client with its own connection pool and dispatcher threads. Letting the default
     * argument construct one each time would leave a pool behind on every switch, none of them
     * reachable to close.
     */
    private val pages by lazy { HttpPageReader() }

    /**
     * Null until the gate has a working key. Its absence is what "not signed in" means, so
     * there is no separate flag that could disagree with it.
     */
    var conversation: Conversation? = null
        private set

    val signedIn: Boolean get() = conversation != null

    /** The model in use, as stored. Empty before the first sign-in. */
    val modelId: String get() = prefs().getString(KEY_MODEL, null).orEmpty()

    /** Whether the conversation is running on the professional tier of whatever provider this is. */
    val isPro: Boolean get() = modelId == provider?.pro

    /**
     * Spoken audio, as text. Null when there is nothing usable to send on.
     *
     * Lives here rather than on [Voice] so the activation code stays inside the one class that
     * holds it: the recorder hands over a file, not a credential.
     */
    suspend fun transcribe(file: java.io.File): String? {
        val current = provider ?: return null
        val asr = current.asrModel ?: return null
        return voice.transcribe(file, current.token, current.llmUrl, asr)
    }

    /**
     * Whether this install can turn speech into text at all.
     *
     * A custom profile need not name an ASR model, and the microphone is hidden rather than left
     * to fail per recording: a button that does nothing is worse than a button that is not there,
     * particularly for the person this app is built for.
     */
    val canTranscribe: Boolean get() = provider?.asrModel != null

    /** The microphone, and the one upload it feeds. */
    val voice by lazy { Voice(context) }

    /**
     * Saying the answer has landed. Off until somebody turns it on.
     *
     * Default off because the app has never made a sound before, and a phone that starts
     * chiming at somebody who did not ask it to is a worse first impression than one that is
     * quiet until told otherwise.
     */
    var alerting: Boolean
        get() = prefs().getBoolean(KEY_ALERT, false)
        set(on) { prefs().edit().putBoolean(KEY_ALERT, on).apply() }

    val alert by lazy { Alert(context) }

    /** What stops the system reclaiming the app out from under a running turn. See [Awake]. */
    val awake by lazy { Awake(context) }

    /** Pictures, on their way from the gallery or the camera to the model. */
    val attachments by lazy { Attachments(context) }

    /** For a permission check, which needs a Context and has no business holding the rest. */
    val appContext: Context get() = context

    /**
     * Enough of the code to recognise it by, and not enough to read it off a screen.
     *
     * Anything but the default deployment leads with its host — a custom profile, and a
     * mainland code. That is what identifies which install this is: one person may hold several
     * codes, and they differ by where they point long before they differ by token.
     */
    fun keyHint(): String {
        val current = provider ?: return ""
        return if (current.worthNamingHost()) {
            current.llmHost() + "  " + current.tokenHint()
        } else {
            current.tokenHint()
        }
    }

    /**
     * Switch models, then fold the conversation so far into a summary.
     *
     * The compaction is not housekeeping. The new model has read none of this conversation, and
     * a transcript written by a different one is worse context than a paragraph describing what
     * the two of you actually settled — so the switch and the compaction are one action.
     *
     * [ModelSwitch.allowed] is false when the key is not entitled to that model, having
     * changed nothing. [ModelSwitch.compacted] says whether there was actually a conversation
     * to fold, so the thread is only told about a summary that exists.
     */
    suspend fun setModel(id: String): ModelSwitch {
        val current = provider ?: return ModelSwitch(allowed = false)
        val client = client(current, model = id, onModelChanged = ::rememberModel)
        if (!client.entitled(id)) return ModelSwitch(allowed = false)

        rememberModel(id)
        val folded = conversation?.compact() ?: false
        conversation = talk(current, client)
        return ModelSwitch(allowed = true, compacted = folded)
    }

    /**
     * One client, pointed at one provider.
     *
     * Every field a client needs beyond the model now comes from the same object, which is the
     * whole point of there being one: a call site that forgot the base URL used to still compile
     * and quietly talk to llm.areel.org with somebody else's token.
     */
    private fun client(
        provider: Provider,
        model: String,
        onModelChanged: (String) -> Unit = {},
    ) = HydrogenClient(
        baseUrl = provider.llmUrl,
        apiKey = provider.token,
        model = model,
        embeddingModel = provider.embeddingModel,
        rerankModel = provider.rerankModel,
        preferred = provider.chatModels(),
        onModelChanged = onModelChanged,
    )

    /**
     * One conversation, and the memory bus beside it.
     *
     * The bus gets its own client, pinned to the fast model and never re-pointed. Filing is
     * bookkeeping — pull a phrase out of a sentence, decide whether it is worth keeping — and
     * there is no reason for a professional-mode conversation to pay professional-mode prices
     * for it. It also means switching modes changes what answers, not what remembers.
     *
     * No `onModelChanged` on this one either: the bus discovering it cannot use the fast model
     * must not rewrite the id the *conversation* is restored from.
     */
    private fun talk(provider: Provider, client: HydrogenClient): Conversation {
        // Whatever was filing for the outgoing conversation stops now. Its scope would
        // otherwise stay alive and keep writing on behalf of a conversation nobody is having.
        conversation?.memory?.close()
        val filing = client(provider, model = provider.flash)
        return Conversation(
            llm = client,
            retrieval = client,
            search = search,
            pages = pages,
            registry = registry,
            store = store,
            // The professional tier pays for the factorisation in front of memory; the fast
            // one embeds the question as it stands. Read here rather than passed a lambda,
            // because a model switch rebuilds this whole object anyway.
            expert = modelId == provider.pro,
            memory = MemoryBus(
                llm = filing,
                retrieval = filing,
                store = store,
                systemModel = provider.systemModel,
            ),
        )
    }

    /** What a pasted code says, before anything is stored or any call is made. */
    fun read(raw: String): Activation = ActivationCode.parse(raw)

    /**
     * Spec §1 — the only thing the user is ever asked for. Checks the code against whatever
     * service it names, and on success discovers which of that service's models it can drive.
     *
     * [raw] is stored rather than the parsed object: it is what the user holds and can be asked
     * to paste again, and parsing it back is exact. Storing the pieces would mean a second
     * writer of the same facts.
     */
    suspend fun signIn(raw: String, provider: Provider): KeyCheck {
        val client = client(provider, model = "", onModelChanged = ::rememberModel)
        val check = client.validate()
        if (check is KeyCheck.Valid) {
            // commit, not apply. This is the one write the app cannot afford to lose: apply()
            // returns before the file is written, and the activation screen is precisely where
            // a user finishes and immediately backs out of the app - taking the process, and
            // the unflushed key, with them. They then reopen it and are asked to activate again.
            prefs().edit()
                .putString(KEY_ACTIVATION, raw)
                // Kept in step so a build older than this one, or a rollback, still finds a key
                // where it expects one. It is only ever read as a fallback - see [restore].
                .putString(KEY_API, provider.token)
                .putString(KEY_MODEL, check.chosen)
                .commit()
            adopt(provider)
            conversation = talk(provider, client)
        }
        return check
    }

    /** Point the app at a provider: its search instance comes with it. */
    private fun adopt(provider: Provider) {
        this.provider = provider
        search = SearxngGateway(
            baseUrl = provider.searchUrl,
            apiToken = provider.searchToken,
            http = searchHttp,
        )
    }

    /**
     * Re-establishes the session from the stored key without a round trip.
     *
     * The model id is read back from disk rather than rediscovered: making the app unusable
     * offline because it could not re-list a catalogue it already knows would be a poor trade.
     * If the id has since been retired the first turn fails, which the user can act on.
     */
    fun restore(): Boolean {
        val current = stored() ?: return false
        adopt(current)
        // A missing model is not a reason to ask for the code again. The key is what the user
        // was asked for and what they have; the model is a cache, and the client re-picks one
        // the moment a turn is refused. Sending them back to the gate over it would be the app
        // forgetting something it was told, to fix something it can work out for itself.
        val model = prefs().getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: current.flash
        conversation = talk(current, client(current, model, onModelChanged = ::rememberModel))
        return true
    }

    /**
     * The provider this install was left pointed at, or null if it was never activated.
     *
     * The fallback is the migration, and it needs no write. An install from before profiles
     * existed has a bare `api_key` and no activation string, and that *is* the signal: a token
     * with no profile beside it is an areel code, which is what it always was. Nobody is sent
     * back to the gate by this change.
     *
     * A stored string that no longer parses falls back the same way rather than failing. It
     * should be impossible - it parsed once, on the way in - but being asked to activate again
     * is the one outcome worth going to some trouble to avoid.
     */
    private fun stored(): Provider? {
        val raw = prefs().getString(KEY_ACTIVATION, null)?.takeIf { it.isNotBlank() }
        if (raw != null) {
            (ActivationCode.parse(raw) as? Activation.Ok)?.let { return it.provider }
        }
        val legacy = prefs().getString(KEY_API, null)?.takeIf { it.isNotBlank() } ?: return null
        return Provider.areel(legacy)
    }

    /**
     * A model id is a cache, not a setting. The client re-picks when the stored one is refused,
     * and this is what stops that costing a wasted round trip on every turn afterwards.
     */
    private fun rememberModel(model: String) {
        prefs().edit().putString(KEY_MODEL, model).apply()
    }

    /**
     * What the conversation weighs, not what the file does. The snapshot also carries cached
     * answers and a thousand-float embedding each, so the file barely moves when the log is
     * cleared - and a number that does not move when you delete something is worse than none.
     */
    fun historyBytes(): Long = store.turnBytes()

    fun historyTurns(): Int = store.turnCount()

    /**
     * Drop the conversation and start a fresh session.
     *
     * The conversation object is rebuilt rather than reused: it holds the session it was
     * talking in, and that session has just been deleted underneath it.
     */
    fun clearHistory() {
        store.clearTurns()
        // The pictures too. They are only reachable through the turns that name them, so
        // clearing the log without them would leave files nothing can ever open again.
        attachments.forgetAll()
        restore()
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)


    companion object {
        /*
         * SEARCH_URL, LLM_URL, FAST and PRO used to live here, and nothing refers to them now.
         * They are Provider's: a default install is `Provider.areel(token)`, and a settings
         * screen asking which tier to run on reads `provider.flash` and `provider.pro`, because
         * on a custom profile those are whatever the profile said. Leaving aliases behind would
         * only offer a way to go on hardcoding the areel deployment by accident.
         */
        private const val MEMORY_FILE = "memory.json"
        private const val PREFS = "fishball"
        private const val KEY_API = "api_key"

        /** The code exactly as it was typed. See `Backend.stored` for why the old key survives. */
        private const val KEY_ACTIVATION = "activation"
        private const val KEY_MODEL = "model"
        private const val KEY_ALERT = "alert_on_answer"

        fun create(context: Context): Backend {
            val app = context.applicationContext
            return Backend(app, PersistentStore(FileSnapshotIo(File(app.filesDir, MEMORY_FILE))))
        }
    }
}

/** What came of asking to change model. */
data class ModelSwitch(val allowed: Boolean, val compacted: Boolean = false)

/**
 * The memory file.
 *
 * Written through a temporary file and renamed, because the alternative is that a process
 * death midway through a write leaves a truncated file — and that file is the only copy of
 * everything the app has ever learned about this person.
 */
class FileSnapshotIo(private val file: File) : SnapshotIo {

    override fun read(): String? = if (file.exists()) file.readText() else null

    override fun write(contents: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(file)) {
            // Rename can fail if the destination exists on some filesystems. Falling back to a
            // direct write reopens the truncation window, but losing the write entirely is worse.
            file.writeText(contents)
            tmp.delete()
        }
    }
}
