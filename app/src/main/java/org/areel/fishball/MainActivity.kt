package org.areel.fishball

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.areel.fishball.ui.ChatScreen
import org.areel.fishball.ui.KeyGate
import org.areel.fishball.ui.theme.FishBallTheme

/**
 * Frontend dummy. Two screens, no backend: the key gate (§1, §22) and the conversation (§2).
 *
 * The key is held in memory only — this is a design prototype, and storing a credential is a
 * decision for the real build, not for a mock.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FishBallTheme {
                var key by remember { mutableStateOf<String?>(null) }
                if (key == null) {
                    KeyGate(onKeyEntered = { key = it })
                } else {
                    ChatScreen()
                }
            }
        }
    }
}
