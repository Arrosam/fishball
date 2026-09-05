package org.areel.fishball

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.areel.fishball.core.config.Activation
import org.areel.fishball.core.config.Provider
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.data.Backend
import org.areel.fishball.data.ModelSwitch
import org.areel.fishball.data.Updates
import org.areel.fishball.ui.ChatScreen
import org.areel.fishball.ui.ChatViewModel
import org.areel.fishball.ui.KeyGate
import org.areel.fishball.ui.MemoryScreen
import org.areel.fishball.ui.SettingsScreen
import org.areel.fishball.ui.Mode
import org.areel.fishball.ui.UpdateSheet
import org.areel.fishball.ui.UpdateState
import org.areel.fishball.ui.theme.FishBallTheme

/**
 * Three screens, no navigation library: the activation gate (§1), the conversation (§2), and
 * the memory browser (§13). A nav graph would be more machinery than the thing it routes.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FishBallTheme { FishBallApp() } }
    }
}

@Composable
private fun FishBallApp() {
    val context = LocalContext.current
    val backend = remember { Backend.create(context) }

    // The key is stored, so the gate is a first-run screen rather than a login. Someone who
    // has to type a key every morning will stop using the app by Thursday.
    var signedIn by remember { mutableStateOf(backend.restore()) }
    var screen by remember { mutableStateOf(Screen.THREAD) }
    var checking by remember { mutableStateOf(false) }
    var gateError by remember { mutableStateOf<String?>(null) }
    var gateDetail by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The status bar sits on two different grounds. On the gate that is concrete, and the
    // icons have to be dark to be seen; everywhere else the masthead runs up behind them and
    // they have to be light. Nothing else in the app changes what is underneath them.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        SideEffect {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !signedIn
        }
    }

    val rejected = stringResource(R.string.gate_key_rejected)
    val unreachable = stringResource(R.string.gate_unreachable)
    val noModel = stringResource(R.string.gate_no_model)
    val noModelCustom = stringResource(R.string.gate_no_model_custom)
    val codeIncomplete = stringResource(R.string.gate_code_incomplete)
    val codeUnreadable = stringResource(R.string.gate_code_unreadable)
    val codeMissing = stringResource(R.string.gate_code_missing)
    val codeInsecure = stringResource(R.string.gate_code_insecure)

    /** What a refusal from the service says, given who owns the service. */
    fun refusal(check: KeyCheck, provider: Provider): Pair<String, String?> = when (check) {
        is KeyCheck.Valid -> "" to null
        KeyCheck.Rejected -> rejected to "HTTP 401/403 from ${provider.llmUrl}/v1/models"
        is KeyCheck.NoModel -> {
            // Which models were wanted is the profile's own answer now, not a constant, and on
            // a custom profile it is the line that tells somebody they mistyped one.
            (if (provider.custom) noModelCustom else noModel) to
                "wanted " + provider.chatModels().joinToString(" | ") +
                "   offered " + check.offered.joinToString(", ").ifBlank { "(nothing)" }
        }
        is KeyCheck.Unreachable -> unreachable to check.reason
    }

    /** What a code that never reached the network says. */
    fun unusable(bad: Activation.Malformed): String = when (bad.reason) {
        Activation.Reason.TRUNCATED -> codeIncomplete
        Activation.Reason.UNREADABLE -> codeUnreadable
        Activation.Reason.MISSING_FIELD -> codeMissing
        Activation.Reason.INSECURE_URL -> codeInsecure
    }

    if (!signedIn) {
        // The code as typed, held only while its profile is on screen waiting to be confirmed.
        // Stored rather than re-derived because what gets written down is what the user pasted.
        var pendingCode by remember { mutableStateOf<String?>(null) }
        var pending by remember { mutableStateOf<Provider?>(null) }

        fun activate(raw: String, provider: Provider) {
            checking = true
            gateError = null
            gateDetail = null
            scope.launch {
                val check = backend.signIn(raw, provider)
                if (check is KeyCheck.Valid) {
                    signedIn = true
                } else {
                    val (message, detail) = refusal(check, provider)
                    gateError = message
                    gateDetail = detail
                    // Back to the field. A profile that the service refused is not a profile
                    // worth confirming again, and leaving the panel up with an error under it
                    // offers 确认连接 for something that has just been established will not.
                    pending = null
                    pendingCode = null
                }
                checking = false
            }
        }

        KeyGate(
            checking = checking,
            error = gateError,
            detail = gateDetail,
            pending = pending,
            onSubmit = { raw ->
                gateError = null
                gateDetail = null
                when (val read = backend.read(raw)) {
                    is Activation.Malformed -> {
                        gateError = unusable(read)
                        gateDetail = read.reason.name + ": " + read.detail
                    }
                    // A custom profile is shown where it points before anything is stored; an
                    // areel code goes straight through, because confirming the only destination
                    // the app has ever had is a question with no content in it.
                    is Activation.Ok -> if (read.provider.custom) {
                        pendingCode = raw
                        pending = read.provider
                    } else {
                        activate(raw, read.provider)
                    }
                }
            },
            onConfirm = {
                val raw = pendingCode
                val provider = pending
                if (raw != null && provider != null) activate(raw, provider)
            },
            onCancel = {
                pending = null
                pendingCode = null
                gateError = null
                gateDetail = null
            },
        )
        return
    }

    // §26 — the update check. After the gate, because someone who has not activated yet has
    // no app to update, and once per launch rather than on a timer: this is a phone in a
    // pocket, and a background poll would cost battery to learn something that changes monthly.
    val updates = remember { Updates(context) }
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    LaunchedEffect(Unit) {
        updates.check()?.let { update = UpdateState.Offered(it) }
    }

    // Coming back from the Settings screen where that permission is granted. There is no result
    // to listen for — it is another app's screen — so the state is re-read on resume, and if it
    // has flipped the install carries on by itself. The alternative is asking someone who has
    // just done what they were told to now press a second button.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val waiting = update as? UpdateState.NeedsPermission ?: return@LifecycleEventEffect
        if (updates.canInstall()) {
            updates.install(waiting.apk)
            update = UpdateState.Idle
        }
    }

    val compacted = stringResource(R.string.session_compacted)
    val stopped = stringResource(R.string.turn_stopped)
    // Read once out of prefs. The switch owns it from there and writes through on each change,
    // so the row redraws without a round trip to disk for every recomposition.
    var alerting by remember { mutableStateOf(backend.alerting) }
    val vm: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(backend, compacted, stopped) as T
        },
    )

    // Whether the answer will be seen as it lands, which decides if it is also worth a
    // notification. Both edges, because ON_STOP is what "they put the phone down" looks like.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.watching = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.watching = false }

    // System back leaves whichever screen is open rather than the app — the user has no concept
    // of a screen stack, so "back" has to mean the obvious thing.
    if (screen != Screen.THREAD) BackHandler { screen = Screen.THREAD }

    UpdateSheet(
        state = update,
        onInstall = {
            val offered = (update as? UpdateState.Offered)?.release ?: return@UpdateSheet
            update = UpdateState.Downloading(offered, -1f)
            scope.launch {
                val apk = updates.download(offered) { fraction ->
                    update = UpdateState.Downloading(offered, fraction)
                }
                when {
                    // Silent: they asked for an update and did not get one, which is
                    // disappointing rather than alarming, and the next launch will offer again.
                    apk == null -> update = UpdateState.Idle
                    !updates.canInstall() -> update = UpdateState.NeedsPermission(offered, apk)
                    else -> {
                        updates.install(apk)
                        // The installer is in front of them now. Leaving the sheet up behind it
                        // means cancelling the install returns to a modal insisting on itself.
                        update = UpdateState.Idle
                    }
                }
            }
        },
        onGrantPermission = { updates.openInstallPermission() },
        onDismiss = { update = UpdateState.Idle },
    )

    when (screen) {
        Screen.THREAD -> ChatScreen(
            vm = vm,
            onMemoryClick = { screen = Screen.MEMORY },
            onSettingsClick = { screen = Screen.SETTINGS },
        )

        Screen.MEMORY -> {
            // State now, not a bare `remember`: a deleted row has to leave the list it was
            // read into, and the ledgers are re-read from the store rather than edited here.
            // The store decides what a delete did - a preference goes, a world fact stays with
            // a date on it and drops out of the filter - and this is not the place to guess.
            var world by remember { mutableStateOf(vm.worldMemories()) }
            var personal by remember { mutableStateOf(vm.personalMemories()) }

            MemoryScreen(
                world = world,
                personal = personal,
                onForget = { row ->
                    vm.forget(row)
                    world = vm.worldMemories()
                    personal = vm.personalMemories()
                },
                onBack = { screen = Screen.THREAD },
            )
        }

        Screen.SETTINGS -> {
            var mode by remember { mutableStateOf(if (backend.isPro) Mode.PRO else Mode.FAST) }
            var settingsBusy by remember { mutableStateOf(false) }
            var settingsError by remember { mutableStateOf<String?>(null) }
            var keyHint by remember { mutableStateOf(backend.keyHint()) }
            var history by remember { mutableStateOf(vm.historySize()) }
            var compacting by remember { mutableStateOf(false) }
            val modeUnavailable = stringResource(R.string.mode_unavailable)
            var checkingUpdate by remember { mutableStateOf(false) }
            var updateNote by remember { mutableStateOf<String?>(null) }
            val upToDate = stringResource(R.string.update_latest)
            val cannotCheck = stringResource(R.string.update_unreachable)

            SettingsScreen(
                mode = mode,
                keyHint = keyHint,
                busy = settingsBusy,
                error = settingsError,
                history = history,
                compacting = compacting,
                version = BuildConfig.VERSION_NAME,
                checkingUpdate = checkingUpdate,
                updateNote = updateNote,
                onCheckUpdate = {
                    checkingUpdate = true
                    updateNote = null
                    scope.launch {
                        val published = updates.published()
                        when {
                            published == null -> updateNote = cannotCheck
                            // The same sheet the launch check raises, over this screen.
                            published.isNewer() -> update = UpdateState.Offered(published)
                            else -> updateNote = upToDate
                        }
                        checkingUpdate = false
                    }
                },
                onClearHistory = {
                    vm.clearHistory()
                    history = vm.historySize()
                },
                onModeChange = { wanted ->
                    settingsBusy = true
                    compacting = true
                    settingsError = null
                    scope.launch {
                        // The profile's own names for its two tiers - on a custom profile these
                        // are whatever it said, and on an areel code they are what they always
                        // were. 快速 / 专业 is a promise about behaviour, not about a model id.
                        val current = backend.provider
                        val id = if (wanted == Mode.PRO) current?.pro else current?.flash
                        val switch = id?.let { backend.setModel(it) } ?: ModelSwitch(allowed = false)
                        if (switch.allowed) {
                            mode = wanted
                            // Only when a summary was actually made. The thread is told the
                            // conversation was folded because that changes what the next answer
                            // remembers; saying it when nothing was folded is just noise, and
                            // switching back and forth used to stack one notice per switch.
                            if (switch.compacted) vm.noteCompacted()
                        } else {
                            settingsError = modeUnavailable
                        }
                        compacting = false
                        settingsBusy = false
                    }
                },
                onKeyChange = { raw ->
                    settingsError = null
                    when (val read = backend.read(raw)) {
                        is Activation.Malformed -> settingsError = unusable(read)
                        is Activation.Ok -> {
                            settingsBusy = true
                            scope.launch {
                                val check = backend.signIn(raw, read.provider)
                                if (check is KeyCheck.Valid) {
                                    keyHint = backend.keyHint()
                                    mode = if (backend.isPro) Mode.PRO else Mode.FAST
                                } else {
                                    settingsError = refusal(check, read.provider).first
                                }
                                settingsBusy = false
                            }
                        }
                    }
                },
                alerting = alerting,
                onAlertingChange = {
                    alerting = it
                    backend.alerting = it
                },
                onBack = { screen = Screen.THREAD },
            )
        }
    }
}

/** The three destinations. A nav graph would be more machinery than this routes. */
private enum class Screen { THREAD, MEMORY, SETTINGS }
