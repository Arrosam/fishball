package org.areel.fishball.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.areel.fishball.data.Attachment
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.areel.fishball.R
import org.areel.fishball.ui.theme.Areel

/**
 * Spec §2 — one endless conversation. No chat list, no folders, no settings to understand.
 *
 * The thread is state the view model owns: it survives rotation and the memory screen, and
 * the composable stays a drawing of it. Everything below the state block is surface — the CAD
 * grid ground, the chamfered assistant plate, the user's glass pane, the meter, the source card,
 * and the pending bubble that occupies the slot its answer will land in.
 */
// LocalOverscrollConfiguration is still experimental; the alternative is shipping two
// competing overscroll effects, so the opt-in is the lesser problem.
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onMemoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    // Starts empty. An opening bubble would say what the empty state already says, and saying
    // it twice - once as ground, once as a message - would make the app look like it had been
    // talking before the user arrived.
    val messages = vm.messages
    val narration = vm.narration
    val busy = vm.busy
    // The placeholder has to stay on screen while the reply streams into it, so it is shown
    // for the whole turn rather than only while there is narration to list.
    val pending = busy
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Rows that have already played their entrance. A LazyColumn discards and rebuilds an
    // item when it scrolls out and back, so without this an old message re-animates every
    // time it returns to view.
    val entered = remember { mutableSetOf<Int>() }

    // The pending bubble is a list item, so it counts toward the scroll target.
    val itemCount = messages.size + if (pending) 1 else 0

    /*
     * Following the end of the conversation.
     *
     * Three things had to be true and only one of them was. The thread has to start at the end
     * rather than the top; it has to keep up while an answer is being written; and it has to
     * stay at the end when the viewport *shrinks* under it - which is the case the keyboard
     * broke, and the case an ime-visibility flag cannot express. What matters is not that a
     * keyboard appeared, it is that the space the conversation had to live in got smaller while
     * the user was reading the bottom of it.
     *
     * `following` is the intent, not the position. It is only revised while the user is
     * actually dragging, so a viewport change cannot silently answer the question "did they
     * want to be at the end" with "well, they aren't now".
     */
    val atTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        // Revised when a scroll *finishes*, not when it starts.
        //
        // Reading it at the start asked the question a moment too early: someone at the end of
        // the thread who flings upwards is still at the end when the fling begins, so the latch
        // was set true and never revised - the flow only emits on the transition. They would
        // land far up the conversation with the app still believing they wanted the bottom, and
        // the next thing to touch the viewport or the message count hauled them back down. That
        // is the "scroll hard, get thrown back" this is meant to prevent, caused by the very
        // thing meant to prevent it.
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (!scrolling) following = atTail }
    }

    /** The very bottom, not the top of the last message - a plate can be taller than the view. */
    suspend fun toEnd(smooth: Boolean) {
        if (itemCount == 0) return
        if (smooth) listState.animateScrollToItem(itemCount - 1) else listState.scrollToItem(itemCount - 1)
        listState.scrollBy(FAR_ENOUGH)
    }

    // Opening the app. The thread is restored from the log, and a restored conversation that
    // starts at its beginning is showing the user the least useful end of it.
    LaunchedEffect(Unit) { toEnd(smooth = false) }

    LaunchedEffect(itemCount) { if (following) toEnd(smooth = true) }

    // While it writes. Keyed on coarse buckets of length rather than on the text itself, so a
    // hundred tokens cost a handful of scrolls instead of a hundred animations.
    LaunchedEffect(vm.streamed.length / 48, vm.thinking.length / 240) {
        if (following && busy) toEnd(smooth = false)
    }

    // The viewport changing size, from any cause. The keyboard is the one that prompted this,
    // but it is not the condition: a shrinking view keeps its scroll offset, so whatever was at
    // the bottom slides out of sight.
    val viewport by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    LaunchedEffect(viewport) { if (following) toEnd(smooth = false) }

    val attach = rememberAttachState(vm.backend.attachments)

    fun send(text: String) {
        // Whatever was attached rides this message and only this one - typed or spoken, the
        // picture goes with the next thing said and is then let go of, so it cannot silently
        // follow the conversation into a question it had nothing to do with.
        val images = attach.pending.map { it.content }
        attach.clear()
        attach.close()
        vm.send(text, images)
    }

    // Spoken input joins the thread through the same door typing does: what comes back from
    // ASR is sent as the question, not offered as a draft to confirm.
    val voice = rememberVoiceState(backend = vm.backend, onText = { send(it) })

    Column(
        Modifier
            .fillMaxSize()
            .background(Areel.Concrete)
            .cadGrid()
            // No statusBarsPadding here — the masthead takes it, so the ink reaches the top
            // of the glass instead of stopping under the clock.
            .imePadding(),
    ) {
        // The ink row only. Its checker is not part of this Column - it is painted over the
        // top of the thread below, so the thread genuinely runs underneath it.
        TopBand(onMemoryClick = onMemoryClick, onSettingsClick = onSettingsClick)

        val bounce = rememberBounceState()
        // In dp, so the overrun is the same distance on every screen rather than the same
        // number of pixels.
        val arrivalPeak = with(LocalDensity.current) { 22.dp.toPx() }
        // clipToBounds is load-bearing, not tidiness: the bounce translates the whole list
        // past its own edges, and unclipped that content drew straight over the masthead. The
        // band and the composer own their strips of the screen; the thread stays inside its.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .bounce(bounce),
        ) {
            // The platform stretch is turned off: with the rubber band below it, an edge would
            // stretch and translate at once, which reads as two effects arguing.
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, bounce.translation) },
                // 32dp of top padding, not 16: the checker is an overlay now rather than a
                // row in the Column above, so the list has to leave its height clear or the
                // first message would start life half-hidden under it.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(messages) { index, message ->
                    val firstShowing = remember(index) { entered.add(index) }
                    EnterFromCorner(fromUser = message.fromUser, animate = firstShowing) {
                        if (message.fromUser) {
                            UserBubble(message.text)
                        } else {
                            AssistantBubble(
                                text = message.text,
                                confidence = message.confidence,
                                conflict = message.conflict,
                                sources = message.sources,
                                detail = message.detail,
                                steps = message.steps,
                                thinking = message.thinking,
                            )
                        }
                    }
                }
                // §21 inline: the placeholder sits where the answer will, and is replaced in place.
                if (pending) {
                    item {
                        EnterFromCorner(fromUser = false, animate = true) {
                            PendingBubble(
                                steps = narration.toList(),
                                thinking = vm.thinking,
                                streamed = vm.streamed,
                            )
                        }
                    }
                }
            }
            }

            // Nothing said yet. It lives in the thread's box rather than in the list, so it
            // neither scrolls nor rubber-bands with content that does not exist.
            if (messages.isEmpty() && narration.isEmpty()) {
                EmptyThread(Modifier.align(Alignment.Center))
            }

            // The masthead's checker, over the thread rather than above it. The squares that
            // are not ink are left unpainted, so a message scrolled up under the band shows
            // through the gaps instead of disappearing behind a grey chequerboard - which is
            // the whole reason the strip is a checker and not a rule.
            CheckerBand(Modifier.align(Alignment.TopCenter))

            /*
             * The way back down, and only when there is a way back down.
             *
             * [atTail] is the position rather than the intent, which is what this needs: the
             * question here is "is the end off screen", not "did they mean to leave it". It
             * appears while scrolled up whether they scrolled up on purpose or an arriving
             * answer grew the thread past them.
             *
             * Glass rather than the magenta of the send plate. It is a convenience sitting
             * directly above the one control in the app that does something irreversible, and
             * two magenta squares stacked in a corner would be one target read as two halves
             * of the same thing.
             */
            // The attach choices, floating over the thread rather than sitting in the bar.
            // They are a menu, not part of the composer, and a menu that reflows the layout it
            // is drawn over reads as the app rearranging itself around a question nobody asked.
            AttachRow(
                open = attach.open,
                full = attach.full,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 10.dp),
                onImage = attach::pickImage,
                onCamera = attach::takePhoto,
            )

            JumpToEnd(
                visible = !atTail,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp),
                onClick = {
                    // Following again, not just scrolled: landing at the end and then being
                    // left behind by the next answer would undo the trip.
                    following = true
                    scope.launch {
                        toEnd(smooth = true)
                        // After the list has actually stopped, not alongside it: run the two
                        // together and the overshoot is spent while the thread is still moving,
                        // so nothing arrives anywhere.
                        bounce.arrive(arrivalPeak)
                    }
                },
            )

            // The shade the composer casts on the thread running under it. Modifier.shadow on
            // the bar alone is not enough: Android throws elevation shadows downward, so a bar
            // pinned to the bottom gets almost nothing above it. Drawn over the list rather
            // than inside the bar, because the shade belongs to what passes beneath.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Areel.Ink10)),
                    ),
            )
        }

        Composer(
            value = draft,
            onValueChange = { draft = it },
            enabled = !busy,
            onSend = {
                send(draft)
                draft = ""
            },
            voice = voice,
            attach = attach,
        )
    }

    // Over everything, including the composer that raised it.
    AttachViewer(attach)
}

/**
 * The way back down, and only when there is a way back down.
 *
 * Driven by position rather than intent, which is what this needs: the question is "is the end
 * off screen", not "did they mean to leave it". So it appears while scrolled up whether they
 * scrolled up on purpose or an arriving answer grew the thread out from under them.
 *
 * Clear glass with a solid magenta triangle, centred over the thread. It sits directly above
 * the send plate, so it stays unfilled and lets the conversation show through: a second solid
 * square in that corner read as two halves of one control, and centred it is plainly its own
 * thing. The mark is filled rather than stroked because a hairline arrow on clear glass had to
 * compete with whatever text happened to be behind it, and lost.
 *
 * Lifted out of the thread\'s Box on purpose: in there, `AnimatedVisibility` had the enclosing
 * Column\'s overload in scope as well as the plain one and resolved to the wrong receiver.
 */
@Composable
private fun JumpToEnd(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically { it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(38.dp)
                // No fill under the glass. Everywhere else in the app a pane shows what is
                // behind it - the CAD grid, a message scrolled under the band - and this one
                // has the thread behind it, which is the thing it is offering to move.
                .glassSurface(small = true)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_triangle_down),
                contentDescription = stringResource(R.string.jump_to_end),
                // Magenta, which through clear glass is the only thing marking the target.
                tint = Areel.Magenta,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * No placeholder by design — the field and the send plate carry it. A control that presses is
 * never glass, so the composer stays opaque.
 *
 * It sits above the thread on a shadow. Flat against the ground it read as the point the
 * conversation stopped at; lifted, it reads as a bar the thread scrolls underneath — which is
 * what actually happens. The hairline lives inside the bar rather than above it so the crisp
 * lip draws on top of the shadow instead of being dimmed by it.
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    voice: VoiceState,
    attach: AttachState,
) {
    // With nothing typed there is nothing to send, so the plate is a microphone instead. One
    // control, two jobs, and never both at once - which is why it can be the same square.
    val speaking = value.isEmpty()
    val recording = voice.phase == VoicePhase.RECORDING

    /*
     * The bar is one height, always, and two separate things were moving it.
     *
     * Typing Chinese made it grow: `includeFontPadding` is the legacy behaviour and pads a line
     * by the *font's* own ascent and descent, so the moment a CJK glyph forced a fallback font
     * the line box grew with it and the whole composer stepped down. Turning it off and trimming
     * the line-height means the box is exactly the line height whatever is being typed in it.
     *
     * Holding the microphone made it grow too, for a plainer reason: two lines of indicator are
     * taller than one line of text. So the outer height is fixed here rather than left to the
     * content, and the recording state buys the room for its second line by giving back its own
     * padding instead of by pushing the bar up.
     */
    val fieldStyle = MaterialTheme.typography.bodyLarge.copy(
        color = Areel.Ink,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    val lineBox = with(LocalDensity.current) { fieldStyle.lineHeight.toDp() }
    // 12 above and below on the shell, 2 above and below on the ruled block inside it.
    val fieldHeight = lineBox + 28.dp
    val inset = if (recording) 4.dp else 12.dp
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                clip = false,
                ambientColor = Areel.Ink,
                spotColor = Areel.Ink,
            )
            // Plain light grey, deliberately. Carrying the CAD grid through here made the
            // field's glass behave, but it also put a mesh behind the one strip of the app
            // that exists to be read and typed into. Legibility wins over material purity.
            .background(Areel.Concrete2),
    ) {
        // What is going with the next message, and what could. Both live above the bar's
        // hairline so the field itself never moves for either of them.
        AttachedRow(attach)
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The masthead's more button, at the other end of the app. Same paper plate, same
            // press offset, same darkening while its menu is out - it opens a menu of the same
            // plates, so it should be the same object.
            val plusPress = remember { MutableInteractionSource() }
            val plusDown by plusPress.collectIsPressedAsState()
            // Grey while pressed or open, exactly as the masthead's more button behaves. The
            // grey it darkens to is the bar's own colour though, so on its own the plate
            // vanishes into the bar the moment it is doing something - hence the ink edge,
            // which is the only thing keeping it a button while its menu is out.
            val dim = plusDown || attach.open
            // One glyph, turned. Swapping a plus for a cross is two icons agreeing to look
            // like one; turning it is the same mark doing the thing the word describes.
            //
            // 225, not 235. A plus has four-fold symmetry, so where it *rests* is the angle
            // modulo 90: 235 rests at 55 and the cross leans ten degrees off square. 225 rests
            // at 45, which is a cross exactly. The travel is unchanged - still more than half a
            // turn - so the spin reads the same and only the landing is square.
            val turn by animateFloatAsState(
                targetValue = if (attach.open) 225f else 0f,
                animationSpec = spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessLow),
                label = "plus-turn",
            )
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .size(48.dp)
                    .offset(x = if (plusDown) 1.dp else 0.dp, y = if (plusDown) 1.dp else 0.dp)
                    .background(if (dim) Areel.Concrete2 else Areel.Paper, RectangleShape)
                    .then(if (dim) Modifier.border(1.dp, Areel.Ink) else Modifier)
                    .clickable(
                        interactionSource = plusPress,
                        indication = null,
                        enabled = enabled,
                        onClick = attach::toggle,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = stringResource(R.string.attach),
                    tint = Areel.Ink,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(turn),
                )
            }
            // The same two parts as a user bubble - shell outside, ruled text block inside -
            // so what is being typed and what has already been said are the same object.
            Box(
                Modifier
                    .weight(1f)
                    .height(fieldHeight)
                    .glassSurface()
                    .padding(start = 14.dp, top = inset, bottom = inset),
                contentAlignment = Alignment.CenterStart,
            ) {
              Box(
                Modifier
                    .fillMaxWidth()
                    .userRule()
                    .padding(top = 2.dp, bottom = 2.dp, end = 16.dp),
              ) {
                // While the button is held the field is where the state is reported, because
                // that is the one place already in view and a finger is covering the plate.
                when (voice.phase) {
                    // Both lines carry their own line height, so what fits is arithmetic
                    // rather than whatever the font happens to want.
                    VoicePhase.RECORDING -> Column {
                        Text(
                            stringResource(R.string.voice_recording),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                lineHeight = 18.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            ),
                            color = Areel.Magenta,
                        )
                        Text(
                            stringResource(R.string.voice_release),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                            ),
                            color = Areel.Ink40,
                        )
                    }

                    VoicePhase.TRANSCRIBING -> Text(
                        stringResource(R.string.voice_transcribing),
                        style = fieldStyle,
                        color = Areel.Ink40,
                    )

                    // The field is always here, and the notice sits behind it as a hint.
                    // Replacing the field with the notice was a trap: the only way to clear
                    // the line was to type, and there was nothing left to type into.
                    VoicePhase.IDLE -> {
                        if (speaking && voice.notice != null) {
                            Text(
                                voice.notice.orEmpty(),
                                style = fieldStyle,
                                color = Areel.Ink40,
                            )
                        }
                        BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = fieldStyle,
                    cursorBrush = SolidColor(Areel.Magenta),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
              }
            }
            // A square plate rather than an IconButton. M3's IconButton clips its container to
            // CircleShape, so the send control came out round - the one shape this design does
            // not contain anywhere.
            val held = voice.phase == VoicePhase.RECORDING
            Box(
                Modifier
                    .padding(start = 10.dp)
                    .size(48.dp)
                    .background(
                        when {
                            !enabled -> Areel.Ink20
                            // Held: inverted, so the control that is doing something looks
                            // pressed rather than merely coloured.
                            held -> Areel.Ink
                            else -> Areel.Magenta
                        },
                        RectangleShape,
                    )
                    .then(
                        if (speaking) {
                            // Held, not tapped - the second line of the indicator promises
                            // that releasing sends, and only a press gesture can keep that
                            // promise. tryAwaitRelease returns on a lifted finger and on a
                            // cancelled gesture alike, which is the behaviour wanted: sliding
                            // off the button still ends the recording rather than orphaning it.
                            Modifier.pointerInput(enabled) {
                                if (!enabled) return@pointerInput
                                detectTapGestures(
                                    onPress = {
                                        voice.onHold()
                                        tryAwaitRelease()
                                        voice.onRelease()
                                    },
                                )
                            }
                        } else {
                            Modifier.clickable(enabled = enabled, onClick = onSend)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (speaking) R.drawable.ic_mic else R.drawable.ic_send,
                    ),
                    contentDescription = stringResource(
                        if (speaking) R.string.voice_hold else R.string.send,
                    ),
                    tint = if (enabled) Areel.Paper else Areel.Ink40,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * More than any single message can be tall. Handed to scrollBy after landing on the last item,
 * because landing on an item puts its *top* at the top of the view - and an assistant plate with
 * sources under it is routinely taller than the view, so its top is not the end of anything.
 * The list clamps this to whatever scroll is actually left.
 */
private const val FAR_ENOUGH = 100_000f

/**
 * The choices, floating over the thread.
 *
 * Same plate as 记忆 and 设置 in the masthead menu, because that is what this is: the app's
 * secondary actions, one shape for all of them, so the pattern is learned once. Drawn over the
 * conversation rather than inside the bar - a menu that grows the layout it is drawn over reads
 * as the app rearranging itself around a question nobody asked yet.
 *
 * The container never animates. Each plate carries its own entrance and its own retreat, so
 * what slides back into the plus is the buttons and not the furniture around them.
 */
@Composable
private fun AttachRow(
    open: Boolean,
    full: Boolean,
    modifier: Modifier,
    onImage: () -> Unit,
    onCamera: () -> Unit,
) {
    // Kept in composition after closing so the plates can be seen leaving. Once the last one is
    // home the row stops taking hit tests, which a plate at alpha 0 would otherwise keep doing.
    var settled by remember { mutableStateOf(true) }
    LaunchedEffect(open) {
        if (open) settled = false else { delay(ATTACH_EXIT_MS + ATTACH_STAGGER_MS); settled = true }
    }
    if (!open && settled) return

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AttachPlate(R.drawable.ic_picture, stringResource(R.string.attach_image), 0, open, !full, onImage)
        AttachPlate(R.drawable.ic_camera, stringResource(R.string.attach_camera), 1, open, !full, onCamera)
    }
}

/**
 * One choice, built to the masthead menu's plate.
 *
 * The transform origin is the bottom-left rather than the menu's top-right, because this stack
 * comes out of a button below and to the left of it. Going back it is eased rather than sprung,
 * and in reverse order: an overshoot on the way out would bounce a plate *away* from the button
 * it is supposed to be disappearing into, and collapsing near-first would read as the far plate
 * falling through the near one.
 */
@Composable
private fun AttachPlate(
    icon: Int,
    label: String,
    order: Int,
    open: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            delay(order * ATTACH_STAGGER_MS)
            progress.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow))
        } else {
            delay((1 - order).coerceAtLeast(0) * ATTACH_STAGGER_MS)
            progress.animateTo(0f, tween(durationMillis = ATTACH_EXIT_MS.toInt(), easing = EaseMech))
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Column(
        Modifier
            .graphicsLayer {
                alpha = (if (enabled) 1f else 0.4f) * progress.value.coerceIn(0f, 1f)
                // Down and left, into the plus.
                translationX = (progress.value - 1f) * -14.dp.toPx()
                translationY = (1f - progress.value) * 34.dp.toPx()
                scaleX = 0.86f + 0.14f * progress.value
                scaleY = 0.86f + 0.14f * progress.value
                transformOrigin = TransformOrigin(0f, 1f)
            }
            .shadow(6.dp, clip = false, ambientColor = Areel.Ink, spotColor = Areel.Ink)
            .size(48.dp)
            .offset(x = if (pressed) 1.dp else 0.dp, y = if (pressed) 1.dp else 0.dp)
            .background(if (pressed) Areel.Concrete2 else Areel.Paper, RectangleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && open,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Areel.Magenta,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = Areel.Ink,
        )
    }
}

/**
 * The line of what is going with this message.
 *
 * A line of its own rather than a badge on the field: the field is where somebody is typing,
 * and pictures pushed into it would take room from the sentence they belong to. The bar grows
 * by exactly one row and gives it back when the last one is removed.
 */
@Composable
private fun AttachedRow(attach: AttachState) {
    val showing = attach.pending.isNotEmpty() || attach.loading > 0
    AnimatedVisibility(
        visible = showing,
        enter = expandVertically(tween(190, easing = EaseMech)) + fadeIn(tween(150)),
        exit = shrinkVertically(tween(170, easing = EaseMech)) + fadeOut(tween(120)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            attach.pending.forEach { item ->
                AttachedThumb(item, onOpen = { attach.viewing = item }, onRemove = { attach.remove(item) })
            }
            repeat(attach.loading) {
                Box(
                    Modifier
                        .size(THUMB)
                        .background(Areel.Concrete2, RectangleShape)
                        .glassSurface(small = true),
                )
            }
        }
    }
}

/**
 * One picture, square, with the way to take it off in its corner.
 *
 * The cross sits *on* the image rather than beside it, which is the only place it can go and
 * still be obviously about that one picture rather than about the row.
 */
@Composable
private fun AttachedThumb(item: Attachment, onOpen: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.size(THUMB + 6.dp)) {
        Box(
            Modifier
                .size(THUMB)
                .align(Alignment.BottomStart)
                .background(Areel.Concrete2, RectangleShape)
                .clickable(onClick = onOpen),
        ) {
            item.thumb?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier
                .size(20.dp)
                .align(Alignment.TopEnd)
                .background(Areel.Ink, RectangleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = stringResource(R.string.attach_remove),
                tint = Areel.Paper,
                // The plus, turned into a cross. The same glyph the composer's own button uses
                // when it is open, which is what closing looks like in this app.
                modifier = Modifier
                    .size(14.dp)
                    .rotate(45f),
            )
        }
    }
}

/**
 * One picture, filling the screen, because a 46dp square is not a look at anything.
 *
 * Dismissed by touching it anywhere. There is nothing to do in here but see the photograph, so
 * a control bar would be three affordances for one action.
 */
@Composable
private fun AttachViewer(attach: AttachState) {
    val item = attach.viewing ?: return
    Dialog(
        onDismissRequest = { attach.viewing = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Areel.Ink.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { attach.viewing = null },
            contentAlignment = Alignment.Center,
        ) {
            item.thumb?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
    }
}

/** Left to right, out of the plus. Same beat as the masthead menu. */
private const val ATTACH_STAGGER_MS = 55L

/** And back down into it, eased. Long enough to read as travel, short enough not to be waited on. */
private const val ATTACH_EXIT_MS = 150L

/** Square, and small enough that five of them fit across a phone. */
private val THUMB = 56.dp
