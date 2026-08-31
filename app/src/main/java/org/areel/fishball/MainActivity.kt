package org.areel.fishball

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.areel.fishball.core.llm.KeyCheck
import org.areel.fishball.data.Backend
import org.areel.fishball.ui.ChatScreen
import org.areel.fishball.ui.ChatViewModel
import org.areel.fishball.ui.KeyGate
import org.areel.fishball.ui.MemoryScreen
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
    var showMemory by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var gateError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val rejected = stringResource(R.string.gate_key_rejected)
    val unreachable = stringResource(R.string.gate_unreachable)

    if (!signedIn) {
        KeyGate(
            checking = checking,
            error = gateError,
            onSubmit = { key ->
                checking = true
                gateError = null
                scope.launch {
                    when (backend.signIn(key)) {
                        is KeyCheck.Valid -> signedIn = true
                        KeyCheck.Rejected -> gateError = rejected
                        is KeyCheck.Unreachable -> gateError = unreachable
                    }
                    checking = false
                }
            },
        )
        return
    }

    val vm: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(backend) as T
        },
    )

    if (showMemory) {
        // System back leaves the memory screen rather than the app — the user has no concept
        // of a screen stack, so "back" has to mean the obvious thing.
        BackHandler { showMemory = false }
        MemoryScreen(
            world = remember { vm.worldMemories() },
            personal = remember { vm.personalMemories() },
            onBack = { showMemory = false },
        )
    } else {
        ChatScreen(vm = vm, onMemoryClick = { showMemory = true })
    }
}
