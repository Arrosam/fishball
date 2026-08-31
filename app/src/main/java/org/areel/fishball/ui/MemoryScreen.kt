package org.areel.fishball.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.areel.fishball.R
import org.areel.fishball.ui.theme.Areel

/**
 * Spec §9 and §13 — what the app has stored, split in two.
 *
 * Two kinds, not three: world knowledge and things about the user. The conversation log is
 * deliberately absent — one is *what it believes*, the other is *what was said*, and the log
 * is answered conversationally ("我昨天问你什么了") rather than browsed.
 *
 * Read-and-forget, not read-only. `MemoryStore` already exposes `forget`, and a memory the
 * user cannot delete is not something to put on someone's phone.
 *
 * Still a dummy: rows come from [Demo], nothing calls `:core` yet.
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    var personal by remember { mutableStateOf(false) }
    val rows = remember(personal) { if (personal) Demo.personalMemories else Demo.worldMemories }

    Column(
        Modifier
            .fillMaxSize()
            .background(Areel.Concrete)
            .cadGrid()
            .statusBarsPadding(),
    ) {
        MemoryBand(onBack = onBack)

        Row(Modifier.fillMaxWidth()) {
            MemoryTab(stringResource(R.string.memory_tab_world), !personal, Modifier.weight(1f)) {
                personal = false
            }
            MemoryTab(stringResource(R.string.memory_tab_personal), personal, Modifier.weight(1f)) {
                personal = true
            }
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        if (personal) R.string.memory_empty_personal else R.string.memory_empty_world,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Areel.Ink40,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows) { row -> MemoryRow(row, personal) }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Same near-black band as the thread, with the mark replaced by a back control. */
@Composable
private fun MemoryBand(onBack: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Areel.Ink)
                .padding(start = 8.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.back),
                    tint = Areel.Paper,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.memory),
                style = MaterialTheme.typography.displaySmall,
                color = Areel.Paper,
            )
        }
        CheckerBand()
    }
}

/** Two plates. The selected one is paper with a heavy top rule; the other sits back. */
@Composable
private fun MemoryTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (selected) Areel.Paper else Areel.Concrete2, RectangleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Areel.Ink),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = if (selected) Areel.Ink else Areel.Ink40,
        )
    }
}

/**
 * A ledger line on glass — facts mounted under a pane rather than printed on the drawing.
 *
 * The bullet carries the kind without needing a word: an outline square for something learned
 * from the web, a solid magenta one for something about the user.
 */
@Composable
private fun MemoryRow(row: MemoryRowData, personal: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .glassSurface(small = true)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .then(
                    if (personal) Modifier.background(Areel.Magenta)
                    else Modifier.border(1.dp, Areel.Ink),
                ),
        )
        Text(
            row.text,
            style = MaterialTheme.typography.bodyMedium,
            color = Areel.Ink,
            modifier = Modifier.padding(start = 11.dp).weight(1f),
        )
        Text(
            row.ttl,
            style = MaterialTheme.typography.labelMedium,
            color = Areel.Ink40,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

data class MemoryRowData(val text: String, val ttl: String)
