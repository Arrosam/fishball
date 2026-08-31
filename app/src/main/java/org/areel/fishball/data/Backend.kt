package org.areel.fishball.data

import android.content.Context
import org.areel.fishball.core.agent.Conversation
import org.areel.fishball.core.llm.HydrogenClient
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.core.memory.PersistentStore
import org.areel.fishball.core.memory.SnapshotIo
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

    private val search by lazy { SearxngGateway(baseUrl = SEARCH_URL) }

    /**
     * Null until the gate has a working key. Its absence is what "not signed in" means, so
     * there is no separate flag that could disagree with it.
     */
    var conversation: Conversation? = null
        private set

    val signedIn: Boolean get() = conversation != null

    /** The model in use, as stored. Empty before the first sign-in. */
    val modelId: String get() = prefs().getString(KEY_MODEL, null).orEmpty()

    /** Enough of the code to recognise it by, and not enough to read it off a screen. */
    fun keyHint(): String {
        val key = prefs().getString(KEY_API, null).orEmpty()
        return if (key.length <= 10) key else key.take(6) + "…" + key.takeLast(4)
    }

    /**
     * Switch models, then fold the conversation so far into a summary.
     *
     * The compaction is not housekeeping. The new model has read none of this conversation, and
     * a transcript written by a different one is worse context than a paragraph describing what
     * the two of you actually settled — so the switch and the compaction are one action.
     *
     * Returns false when the key is not entitled to that model, having changed nothing.
     */
    suspend fun setModel(id: String): Boolean {
        val key = prefs().getString(KEY_API, null)?.takeIf { it.isNotBlank() } ?: return false
        val client = HydrogenClient(apiKey = key, model = id, onModelChanged = ::rememberModel)
        if (!client.entitled(id)) return false

        rememberModel(id)
        conversation?.compact()
        conversation = Conversation(
            llm = client,
            retrieval = client,
            search = search,
            registry = registry,
            store = store,
        )
        return true
    }

    /**
     * Spec §1 — the only thing the user is ever asked for. Checks the key against the proxy,
     * and on success discovers which model it can drive rather than assuming one.
     */
    suspend fun signIn(key: String): KeyCheck {
        val client = HydrogenClient(apiKey = key, onModelChanged = ::rememberModel)
        val check = client.validate()
        if (check is KeyCheck.Valid) {
            // commit, not apply. This is the one write the app cannot afford to lose: apply()
            // returns before the file is written, and the activation screen is precisely where
            // a user finishes and immediately backs out of the app - taking the process, and
            // the unflushed key, with them. They then reopen it and are asked to activate again.
            prefs().edit().putString(KEY_API, key).putString(KEY_MODEL, check.chosen).commit()
            conversation = Conversation(
                llm = client,
                retrieval = client,
                search = search,
                registry = registry,
                store = store,
            )
        }
        return check
    }

    /**
     * Re-establishes the session from the stored key without a round trip.
     *
     * The model id is read back from disk rather than rediscovered: making the app unusable
     * offline because it could not re-list a catalogue it already knows would be a poor trade.
     * If the id has since been retired the first turn fails, which the user can act on.
     */
    fun restore(): Boolean {
        val key = prefs().getString(KEY_API, null)?.takeIf { it.isNotBlank() } ?: return false
        // A missing model is not a reason to ask for the code again. The key is what the user
        // was asked for and what they have; the model is a cache, and the client re-picks one
        // the moment a turn is refused. Sending them back to the gate over it would be the app
        // forgetting something it was told, to fix something it can work out for itself.
        val model = prefs().getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: FAST
        val client = HydrogenClient(apiKey = key, model = model, onModelChanged = ::rememberModel)
        conversation = Conversation(
            llm = client,
            retrieval = client,
            search = search,
            registry = registry,
            store = store,
        )
        return true
    }

    /**
     * A model id is a cache, not a setting. The client re-picks when the stored one is refused,
     * and this is what stops that costing a wasted round trip on every turn afterwards.
     */
    private fun rememberModel(model: String) {
        prefs().edit().putString(KEY_MODEL, model).apply()
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)


    companion object {
        const val SEARCH_URL = "https://search.areel.org"

        /** The two the settings screen offers, named there for what they do rather than what they are. */
        const val FAST = "fishball-flash"
        const val PRO = "fishball-pro"

        const val LLM_URL = HydrogenClient.DEFAULT_BASE_URL

        private const val PREFS = "fishball"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"

        fun create(context: Context): Backend {
            val app = context.applicationContext
            return Backend(app, PersistentStore(FileSnapshotIo(File(app.filesDir, "memory.json"))))
        }
    }
}

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
