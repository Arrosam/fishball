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
import org.areel.fishball.core.llm.HydrogenClient
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.data.Backend
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

    if (!signedIn) {
        KeyGate(
            checking = checking,
            error = gateError,
            detail = gateDetail,
            onSubmit = { key ->
                checking = true
                gateError = null
                gateDetail = null
                scope.launch {
                    when (val check = backend.signIn(key)) {
                        is KeyCheck.Valid -> signedIn = true

                        KeyCheck.Rejected -> {
                            gateError = rejected
                            gateDetail = "HTTP 401/403 from ${Backend.LLM_URL}/v1/models"
                        }

                        is KeyCheck.NoModel -> {
                            gateError = noModel
                            // The catalogue it did return, so the gap is visible at a glance.
                            gateDetail = "wanted " +
                                HydrogenClient.PREFERRED.joinToString(" | ") +
                                "   offered " +
                                check.offered.joinToString(", ").ifBlank { "(nothing)" }
                        }

                        is KeyCheck.Unreachable -> {
                            gateError = unreachable
                            gateDetail = check.reason
                        }
                    }
                    checking = false
                }
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
    val vm: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(backend, compacted) as T
        },
    )

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

        Screen.MEMORY -> MemoryScreen(
            world = remember { vm.worldMemories() },
            personal = remember { vm.personalMemories() },
            onBack = { screen = Screen.THREAD },
        )

        Screen.SETTINGS -> {
            var mode by remember { mutableStateOf(if (backend.modelId == Backend.PRO) Mode.PRO else Mode.FAST) }
            var settingsBusy by remember { mutableStateOf(false) }
            var settingsError by remember { mutableStateOf<String?>(null) }
            var keyHint by remember { mutableStateOf(backend.keyHint()) }
            var history by remember { mutableStateOf(vm.historySize()) }
            var compacting by remember { mutableStateOf(false) }
            val modeUnavailable = stringResource(R.string.mode_unavailable)

            SettingsScreen(
                mode = mode,
                keyHint = keyHint,
                busy = settingsBusy,
                error = settingsError,
                history = history,
                compacting = compacting,
                onClearHistory = {
                    vm.clearHistory()
                    history = vm.historySize()
                },
                onModeChange = { wanted ->
                    settingsBusy = true
                    compacting = true
                    settingsError = null
                    scope.launch {
                        val id = if (wanted == Mode.PRO) Backend.PRO else Backend.FAST
                        val switch = backend.setModel(id)
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
                onKeyChange = { key ->
                    settingsBusy = true
                    settingsError = null
                    scope.launch {
                        when (val check = backend.signIn(key)) {
                            is KeyCheck.Valid -> {
                                keyHint = backend.keyHint()
                                mode = if (backend.modelId == Backend.PRO) Mode.PRO else Mode.FAST
                            }
                            KeyCheck.Rejected -> settingsError = rejected
                            is KeyCheck.NoModel -> settingsError = noModel
                            is KeyCheck.Unreachable -> settingsError = unreachable
                        }
                        settingsBusy = false
                    }
                },
                onBack = { screen = Screen.THREAD },
            )
        }
    }
}

/** The three destinations. A nav graph would be more machinery than this routes. */
private enum class Screen { THREAD, MEMORY, SETTINGS }
