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
import androidx.compose.runtime.setValue
import org.areel.fishball.ui.ChatScreen
import org.areel.fishball.ui.KeyGate
import org.areel.fishball.ui.MemoryScreen
import org.areel.fishball.ui.theme.FishBallTheme

/**
 * Frontend dummy. Three screens, no backend: the activation gate (§1), the conversation (§2),
 * and the memory browser (§13).
 *
 * No navigation library — there are three destinations and one of them is a gate. A nav graph
 * would be more machinery than the thing it routes.
 *
 * The key is held in memory only; persisting a credential is a decision for the real build.
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
    var key by remember { mutableStateOf<String?>(null) }
    var showMemory by remember { mutableStateOf(false) }

    when {
        key == null -> KeyGate(onKeyEntered = { key = it })

        showMemory -> {
            // System back leaves the memory screen rather than the app — the user has no
            // concept of a screen stack, so "back" has to mean the obvious thing.
            BackHandler { showMemory = false }
            MemoryScreen(onBack = { showMemory = false })
        }

        else -> ChatScreen(onMemoryClick = { showMemory = true })
    }
}
