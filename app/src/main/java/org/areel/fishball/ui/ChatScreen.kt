package org.areel.fishball.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.areel.fishball.R
import org.areel.fishball.ui.theme.Areel

/**
 * Spec §2 — one endless conversation. No chat list, no folders, no settings to understand.
 *
 * Still the dummy: replies are scripted in [Demo] and the narration runs on a timer, nothing
 * talks to `:core`. What is real is the surface — the CAD grid ground, the chamfered assistant
 * plate, the user's glass pane, the meter, the source card, and the pending bubble that occupies
 * the slot its answer will land in.
 */
@Composable
fun ChatScreen(onMemoryClick: () -> Unit) {
    val messages = remember { mutableStateListOf<DemoMessage>().apply { addAll(Demo.opening) } }
    val narration = remember { mutableStateListOf<String>() }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var nextExchange by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The pending bubble is a list item, so it counts toward the scroll target.
    val itemCount = messages.size + if (narration.isEmpty()) 0 else 1
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    fun send(text: String) {
        if (busy || text.isBlank()) return
        val exchange = Demo.exchanges.getOrNull(nextExchange % Demo.exchanges.size) ?: return
        messages += DemoMessage(fromUser = true, text = text)
        busy = true
        narration.clear()

        scope.launch {
            exchange.narration.forEach { step ->
                narration += step
                delay(900)
            }
            narration.clear()
            messages += exchange.reply
            nextExchange += 1
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Areel.Concrete)
            .cadGrid()
            .statusBarsPadding()
            .imePadding(),
    ) {
        TopBand(onMemoryClick = onMemoryClick)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(messages) { _, message ->
                if (message.fromUser) {
                    UserBubble(message.text)
                } else {
                    AssistantBubble(
                        text = message.text,
                        confidence = message.confidence,
                        conflict = message.conflict,
                        sources = message.sources,
                    )
                }
            }
            // §21 inline: the placeholder sits where the answer will, and is replaced in place.
            if (narration.isNotEmpty()) {
                item { PendingBubble(narration.toList()) }
            }
        }

        Hairline()
        Composer(
            value = draft,
            onValueChange = { draft = it },
            enabled = !busy,
            onSend = {
                send(draft.ifBlank { Demo.exchanges[nextExchange % Demo.exchanges.size].question })
                draft = ""
            },
        )
    }
}

/**
 * No placeholder by design — the field and the send plate carry it. A control that presses is
 * never glass, so the composer stays opaque.
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Areel.Concrete2)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .background(if (enabled) Areel.Paper else Areel.Ink06, RectangleShape)
                .padding(horizontal = 14.dp, vertical = 15.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Areel.Ink),
                cursorBrush = SolidColor(Areel.Magenta),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // A square plate rather than an IconButton. M3's IconButton clips its container to
        // CircleShape, so the send control came out round — the one shape this design does
        // not contain anywhere.
        Box(
            Modifier
                .padding(start = 10.dp)
                .size(48.dp)
                .background(if (enabled) Areel.Magenta else Areel.Ink20, RectangleShape)
                .clickable(enabled = enabled, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_send),
                contentDescription = stringResource(R.string.send),
                tint = if (enabled) Areel.Paper else Areel.Ink40,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
