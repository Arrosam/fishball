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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin
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
    /** So 引用 can put the cursor where the next question goes. See the call on [AssistantBubble]. */
    val composerFocus = remember { FocusRequester() }
    // Hoisted out of the list: this is read in composition and used inside a click handler,
    // and stringResource cannot be called from the latter.
    val quotePrefix = stringResource(R.string.quote_prefix)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Rows that have already played their entrance. A LazyColumn discards and rebuilds an
    // item when it scrolls out and back, so without this an old message re-animates every
    // time it returns to view.
    /*
     * How many messages were already on screen when this screen was opened.
     *
     * Everything at or past it arrived while somebody was watching, and only those animate.
     * The rest - a conversation restored from the log, or the same conversation coming back
     * from the memory screen - is simply *there*, because it was there before.
     *
     * This used to be a set of indices that had been drawn once, which made every message its
     * own little animation to run and re-run: the set is emptied when the screen is rebuilt, so
     * coming back from Settings replayed the entire conversation, and dragging through a long
     * thread animated rows as they were recycled. That is the cost the drag was paying.
     */
    val alreadyThere = remember { messages.size }

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
    /*
     * "At the end" has to mean the end is *on screen*, which is not the same as the last
     * message being on screen.
     *
     * This used to ask whether the last visible item was the last item in the list, and for a
     * short thread those are the same question. They stop being the same the moment one item is
     * taller than the phone - which is exactly what an answer that quotes two or three sources
     * is. Scrolling up *inside* that answer never changes which item is last, so this read
     * "still at the end" the whole way up: the latch below re-armed `following` when the drag
     * settled, the next thing to touch the viewport threw the reader back down, and the way
     * back button stayed hidden because by this measure they had never left.
     *
     * `canScrollForward` is the list's own answer to "is there anything below this", which is
     * the question actually being asked, and it does not care how tall any one item is.
     */
    val atTail by remember { derivedStateOf { !listState.canScrollForward } }
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

    /*
     * How many items there are, in a box the effects below can still read from later.
     *
     * [itemCount] is a plain local, so a lambda that outlives the composition it was built in
     * goes on seeing the number that was true then. `LaunchedEffect(Unit)` is exactly that kind
     * of lambda, and the one it holds was built during first composition - when the count is
     * whatever the log restored, and zero for anybody opening the app for the first time. The
     * guard below would then return without scrolling, forever, no matter how long the thread
     * grew. Reading it out of `layoutInfo` instead would swap this for a worse bug: the first
     * call happens before the list has been measured, so the count there is still zero and the
     * app would no longer open at the end of the conversation.
     */
    val liveCount by rememberUpdatedState(itemCount)

    /** The very bottom, not the top of the last message - a plate can be taller than the view. */
    suspend fun toEnd(smooth: Boolean) {
        val count = liveCount
        if (count == 0) return
        if (smooth) listState.animateScrollToItem(count - 1) else listState.scrollToItem(count - 1)
        listState.scrollBy(FAR_ENOUGH)
    }

    // Opening the app. The thread is restored from the log, and a restored conversation that
    // starts at its beginning is showing the user the least useful end of it.
    LaunchedEffect(Unit) { toEnd(smooth = false) }

    /*
     * A message arriving. Keyed on both halves of the count rather than on the sum, because the
     * sum does not move at the one moment that matters most: when a turn lands, the placeholder
     * leaves in the same frame the answer arrives, so `messages.size + 1` becomes
     * `messages.size` and [itemCount] is the number it already was. This effect never fired for
     * the finished answer - the single message anybody is actually waiting for - which is the
     * other half of "the page is stuck": the reply lands off the bottom of the screen and, with
     * [atTail] mismeasured as it was, nothing offered to take you to it.
     */
    LaunchedEffect(messages.size, pending) { if (following) toEnd(smooth = true) }

    /*
     * While it works: the bottom of the last block stays on the bottom of the screen.
     *
     * Watched through the *size of the last item* rather than the length of the text, because
     * text is only one of the things that makes it grow. 思考中 gets taller when a narration
     * line is added, when the reasoning tail arrives, and over the several frames an expansion
     * animates - none of which is a character of streamed answer, and all of which used to
     * walk the block off the bottom of the screen while the app believed nothing had changed.
     *
     * Size and index, never offset: offset changes when the list scrolls, and keying on it
     * would make this chase its own scrolling.
     *
     * `collect` on a suspending body conflates for free - while one scroll is in flight the
     * sizes in between are dropped - so this costs one scroll per frame at worst.
     *
     * Every value this reads is read *live*, and that is the whole of the fix. It used to test
     * a `busy` and an `itemCount` lifted from the enclosing composition as plain locals, and
     * `LaunchedEffect(Unit)` keeps the block it was given the first time round: the coroutine
     * went on testing the values those two had at first composition for as long as the screen
     * lived. `busy` is false there on any ordinary launch, so the effect this comment describes
     * had never once run - the block it was written to keep on screen was walking off the
     * bottom exactly as before. The exception is the bug that was reported: leave for Settings
     * or Memory in the middle of a turn and come back, and the screen is composed afresh with
     * `busy` true, which it then believes *permanently*. From that point the list is slammed to
     * the end on every layout change for the rest of the session - on every frame of an upward
     * drag, and on the growth of any card opened anywhere in the thread.
     *
     * `following` and `atTail` were fine by luck: both are `by remember { ... }` over a State,
     * so the delegate the lambda captured is the live one.
     *
     * And not while the user's own hand is on the list. Their drag changes which item is last
     * and how big it is, so an emission arrives for every frame of it; without this guard a
     * turn still running would answer each of those by yanking the thread back to the bottom.
     * The latch above still gets the final say once the scroll settles.
     */
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, last?.index ?: -1, last?.size ?: 0)
        }.collect {
            if (following && vm.busy && !listState.isScrollInProgress) toEnd(smooth = false)
        }
    }

    // The viewport changing size, from any cause. The keyboard is the one that prompted this,
    // but it is not the condition: a shrinking view keeps its scroll offset, so whatever was at
    // the bottom slides out of sight.
    val viewport by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    LaunchedEffect(viewport) { if (following) toEnd(smooth = false) }

    val attach = rememberAttachState(vm.backend.attachments)

    /*
     * The masthead menu's state, held here rather than inside the button.
     *
     * Only so the catcher below can exist: closing on a tap outside is a box in this layout,
     * the same way the + row's is, and a box cannot reach into the masthead to read a flag.
     */
    var menuOpen by remember { mutableStateOf(false) }

    /**
     * A picture from the log, opened full-screen.
     *
     * Decoded on demand and held one at a time. A thread of thirty messages is thirty
     * photographs, and holding them all decoded to serve the one somebody tapped would cost
     * more memory than the whole conversation.
     */
    var viewing by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    fun send(text: String) {
        // Whatever was attached rides this message and only this one - typed or spoken, the
        // picture goes with the next thing said and is then let go of, so it cannot silently
        // follow the conversation into a question it had nothing to do with.
        // Kept on the way past, so the name travels with the picture it belongs to rather
        // than in a second list that could fall out of step with it.
        val images = attach.pending.map { item ->
            item.content.copy(handle = vm.backend.attachments.keep(item.content))
        }
        attach.clear()
        attach.close()
        /*
         * Asking something is choosing to watch for the answer.
         *
         * [following] is otherwise only revised when a scroll finishes, so somebody who had read
         * back up the thread and then typed a question stayed where they were reading while the
         * reply arrived somewhere below them. Every other way into the conversation - the jump
         * button, opening the app - lands at the end; this is the one that did not.
         */
        following = true
        scope.launch { toEnd(smooth = false) }
        vm.send(text, images)
    }

    /*
     * Spoken input joins the thread through the same door typing does: what comes back from ASR
     * is sent as the question, not offered as a draft to confirm.
     *
     * The same `send` the composer's plate calls, deliberately and by construction rather than
     * by two paths that happen to agree. Everything sending has learned since - the attachments
     * riding along, following the thread back to the end, and now offering the text to a running
     * turn as a correction before treating it as a new question - a spoken message gets for free.
     * Voice is send with a transcription in front of it, and nothing else.
     */
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
        TopBand(
            menuOpen = menuOpen,
            onMenuToggle = { menuOpen = !menuOpen },
            onMemoryClick = onMemoryClick,
            onSettingsClick = onSettingsClick,
        )


        // In dp, so the overrun is the same distance on every screen rather than the same
        // number of pixels.
        // clipToBounds is load-bearing, not tidiness: the bounce translates the whole list
        // past its own edges, and unclipped that content drew straight over the masthead. The
        // band and the composer own their strips of the screen; the thread stays inside its.
        /*
         * Coming back to the conversation, from Memory or Settings.
         *
         * The whole thread fades in once, together. Individually the bubbles do nothing - they
         * were there before and they are there now - but the screen arriving all at once, at
         * full contrast, reads as a jump cut. One shared fade is also one animation for the
         * whole list rather than one per row.
         */
        val settle = remember { Animatable(0f) }
        LaunchedEffect(Unit) { settle.animateTo(1f, tween(RETURN_FADE_MS)) }

        /*
         * An unclipped layer over the thread, for the one thing that has to leave it.
         *
         * The list's own box clips, and that is load-bearing - see the note above. But the stop
         * plate now lives over that box, and the fish it throws when it is pressed is documented
         * to fall past the bottom of the screen; inside the clip it was cut off at the composer's
         * top edge after about 56dp and then went on animating, invisible, for the rest of its
         * second. The plate is also laid out partly below this edge while it rises, and a clip
         * takes the taps on that part with it.
         *
         * So the clip stays exactly where it was needed and this layer sits outside it.
         */
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = settle.value }
                    .clipToBounds(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 32dp of top padding, not 16: the checker is an overlay now rather than a
                    // row in the Column above, so the list has to leave its height clear or the
                    // first message would start life half-hidden under it.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(messages) { index, message ->
                        Arriving(animate = index >= alreadyThere) {
                            if (message.fromUser) {
                                UserBubble(
                                    text = message.text,
                                    images = remember(message.images) {
                                        message.images.mapNotNull {
                                            vm.backend.attachments.recall(it)?.asImageBitmap()
                                        }
                                    },
                                    onImage = { i ->
                                        viewing = message.images.getOrNull(i)
                                            ?.let { vm.backend.attachments.recall(it) }
                                    },
                                )
                            } else {
                                AssistantBubble(
                                    text = message.text,
                                    confidence = message.confidence,
                                    conflict = message.conflict,
                                    sources = message.sources,
                                    detail = message.detail,
                                    steps = message.steps,
                                    thinking = message.thinking,
                                    /*
                                     * Not on an apology.
                                     *
                                     * `detail` is set exactly when the turn failed, so it is
                                     * the same honest test the view model uses. Quoting
                                     * 「这会儿连不上」 back at the model would be the app
                                     * inviting somebody to argue with an error message.
                                     */
                                    onQuote = if (message.detail != null) {
                                        null
                                    } else {
                                        { quoted ->
                                            draft = quotePrefix.format(quoted) + draft
                                            // The quote is only half of it - what comes next is
                                            // typed, so the field takes focus and the keyboard
                                            // comes up rather than leaving somebody looking at
                                            // a filled box wondering if it worked.
                                            composerFocus.requestFocus()
                                        }
                                    },
                                )
                            }
                        }
                    }
                    // §21 inline: the placeholder sits where the answer will, and is replaced in place.
                    if (pending) {
                        item {
                            Arriving(animate = true) {
                                PendingBubble(
                                    steps = narration.toList(),
                                    thinking = vm.thinking,
                                    streamed = vm.streamed,
                                    // Still, the moment they start typing. The movement is there to
                                    // fill a wait, and somebody writing the next question beside it
                                    // has stopped waiting - what is left is motion next to a field
                                    // they are trying to read what they typed in.
                                    animate = draft.isEmpty(),
                                )
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
                // Anywhere that is not the row or the button that opened it. The masthead's menu
                // gets this free by living in a Popup; this one is drawn in the layout, so the
                // catcher has to be explicit. It takes the tap rather than passing it on: a tap
                // that both dismissed a menu and pressed what was underneath it would be one
                // gesture doing two things.
                if (attach.open) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(Unit) { detectTapGestures { attach.close() } },
                    )
                }

                // The same catcher for the masthead menu, and the same reason: a tap on the
                // conversation closes it, and it takes that tap rather than passing it on.
                if (menuOpen) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(Unit) { detectTapGestures { menuOpen = false } },
                    )
                }

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
                            // Ticking while it travels, so the trip is felt as distance rather than
                            // as one event. Light, and often enough to read as texture rather than
                            // as a series of separate taps.
                            val ticking = launch {
                                while (isActive) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    delay(JUMP_TICK_MS)
                                }
                            }
                            toEnd(smooth = true)
                            ticking.cancel()
                            // And one firmer one on arrival. The texture stops, something solid
                            // happens: that is the end of the conversation, felt.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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

            /*
             * The stop control, over the send plate rather than in it.
             *
             * Placed here rather than inside the composer because a control has to be laid out
             * where it is tapped: a plate pushed above the bar from inside the bar would be
             * drawn in the right place and hit-tested in the wrong one. Aligned to the same edge
             * and the same inset as the send plate, so it comes up directly over it.
             *
             * Hidden while the microphone is held - see [WorkingPlate] for why that space has to
             * be clear.
             */
            WorkingPlate(
                visible = (busy || voice.phase == VoicePhase.TRANSCRIBING) && !voice.holding,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 8.dp)
                    .size(48.dp),
                onStop = {
                    /*
                     * Whatever is in flight, and both is safe here.
                     *
                     * The worry that made this an either/or was that cancelling a recording
                     * would kill the answer underneath it - but this plate is not up while the
                     * microphone is held, so that is not a state it can be pressed in. What it
                     * can be pressed in is a turn *and* a transcription, and stopping only the
                     * turn left the transcription to finish and then ask, as a brand new
                     * question, the words the user had just called off.
                     */
                    vm.stop()
                    voice.abandon()
                },
            )
        }


        Composer(
            value = draft,
            onValueChange = {
                // The first keystroke answers the microphone's last word. Without this the
                // line sat there through the whole conversation that followed: it shows in an
                // empty field, and the field empties again the moment anything is sent.
                //
                // On the keystroke rather than on the send, so it goes at the moment somebody
                // has plainly decided to type instead - not one message later.
                voice.dismissNotice()
                draft = it
            },
            onSend = {
                send(draft)
                draft = ""
            },
            voice = voice,
            canSpeak = vm.backend.canTranscribe,
            attach = attach,
            focus = composerFocus,
        )
    }

    // Over everything, including the composer that raised it.
    AttachViewer(attach, viewing) {
        viewing = null
        attach.viewing = null
    }
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
                .pressable(onClick = onClick),
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
    onSend: () -> Unit,
    voice: VoiceState,
    attach: AttachState,
    /**
     * Whether this provider turns speech into text at all.
     *
     * False on a custom profile that named no ASR model, and then the plate is never a
     * microphone - it stays the send plate, disabled with nothing typed, exactly as it is
     * mid-turn. Hidden rather than left to fail on release: a control that records, waits, and
     * then says it did not catch that is worse than no control, and it is worst for the person
     * this app was built for, who would reasonably conclude they had spoken wrongly.
     */
    canSpeak: Boolean,
    /** Taken when 引用 fills the field, so the keyboard comes up on the question being written. */
    focus: FocusRequester,
) {
    /*
     * With nothing typed there is nothing to send, so the plate is a microphone instead. One
     * control, two jobs, and never both at once - which is why it can be the same square.
     *
     * Not while the last recording is still being turned into words: a second one started there
     * would take the microphone from under the sentence already on its way into this field. It
     * used to be the running turn that closed the plate for both of those, and a running turn is
     * no longer a reason to close anything - see [WorkingPlate].
     */
    val speaking = value.isEmpty() && canSpeak && voice.phase != VoicePhase.TRANSCRIBING

    /*
     * There is something written, so the plate is a send button - whatever else is going on.
     *
     * This is the whole of the mid-flight change at this end. The plate has always been one
     * square with one job at a time, decided by what is in the field and what the app is doing;
     * all that is new is that "there is something to send" now outranks "something is running",
     * because sending it is what the person holding the phone has plainly decided to do. What
     * that send *means* while a turn is in flight - stop this one, keep what it found, ask the
     * new question of a model that can see it - is `ChatViewModel.send`'s half.
     */
    val typed = value.isNotEmpty()
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
     * content.
     *
     * And so is the ruled block inside it. That started as a way of finding room for the second
     * line - shrink the padding while recording and the block gets taller - which worked, and
     * lengthened the magenta rule along with it: hold the button and the mark that means *your
     * words* grew by a third, on a field nobody was typing in. The rule is one line of text
     * tall, always. The indicator is allowed to overflow it instead, which costs nothing on a
     * shell with 12dp of clear space above and below the block.
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
    /*
     * How many lines the field is showing, which is what its height is made of.
     *
     * Reported by the text's own layout rather than counted from the string: a line here is a
     * line as laid out, and Chinese wraps mid-sentence with no spaces to count. Clamped at
     * [COMPOSER_MAX_LINES] - past that the field stops growing and the text scrolls inside it,
     * because a composer that keeps growing eventually is the screen.
     */
    var typedLines by remember { mutableIntStateOf(1) }

    /*
     * Sending, with the field put back to one line on the way out.
     *
     * [typedLines] is set from `onTextLayout`, which does not run until the emptied field has
     * been measured again - a pass or two after the message is already in the list. So a
     * question long enough to have grown the composer left it standing at three or four lines
     * while the bubble was fading in above it, and the bar collapsed afterwards. Two things
     * moving in sequence when one gesture caused both.
     *
     * Resetting it here puts the shrink in the same frame as the send, so the composer is
     * already back to its resting height when the entrance starts.
     */
    fun submit() {
        typedLines = 1
        onSend()
    }
    val shownLines = if (recording) 1 else typedLines.coerceIn(1, COMPOSER_MAX_LINES)
    val fieldHeight = lineBox * shownLines + 28.dp

    // The ruled block: one line of text plus its own 2dp above and below, whatever is in it.
    val ruleHeight = lineBox * shownLines + 4.dp
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
            // A toggle, like the menu: the row it opens stays on screen. See [Feel].
            val dim = plusDown || attach.open
            // The edge arrives rather than appearing. Snapped on, it read as a second button
            // replacing the first; faded in over the same beat as the glyph's turn, it reads as
            // the one button changing state.
            val edge by animateFloatAsState(
                targetValue = if (dim) 1f else 0f,
                animationSpec = tween(durationMillis = 160, easing = EaseMech),
                label = "plus-edge",
            )
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
            // Built exactly like the masthead's more button: the plate is the target, the
            // press moves it a pixel, and the interaction source it reports through is the one
            // the tick listens to. An outer box larger than the plate was tried and taken back
            // out - the taps that went unfelt were landing on the button all along, and it was
            // the tick that was being dropped, not the touch.
            Box(
                Modifier.padding(end = 10.dp).size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .requiredSize(48.dp + TOUCH_SLOP * 2)
                        .pressable(
                            Feel.TOGGLE,
                            interaction = plusPress,
                            indication = null,
                            onClick = attach::toggle,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .offset(
                                x = if (plusDown) 1.dp else 0.dp,
                                y = if (plusDown) 1.dp else 0.dp,
                            )
                            .background(if (dim) Areel.Concrete2 else Areel.Paper, RectangleShape)
                            .then(
                                if (edge > 0.01f) {
                                    Modifier.border(1.dp, Areel.Ink.copy(alpha = edge))
                                } else {
                                    Modifier
                                },
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
                }
            }
            // The same two parts as a user bubble - shell outside, ruled text block inside -
            // so what is being typed and what has already been said are the same object.
            Box(
                Modifier
                    .weight(1f)
                    .height(fieldHeight)
                    .glassSurface()
                    .padding(start = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
              Box(
                Modifier
                    .fillMaxWidth()
                    // Fixed, so the rule is the same length in every state of the composer.
                    .height(ruleHeight)
                    // Under the rule, so the lines come out from behind it rather than over it.
                    .voiceWave(active = recording) { voice.level }
                    .userRule()
                    .padding(top = 2.dp, bottom = 2.dp, end = 16.dp),
                contentAlignment = Alignment.CenterStart,
              ) {
                /*
                 * While the button is held the field is where the state is reported, because
                 * that is the one place already in view and a finger is covering the plate.
                 *
                 * A running turn used to be reported here too - `if (waiting) BubbleField()`
                 * in front of this, which took the field away for the whole of it. That was
                 * coherent when a turn could not be interrupted: there was nothing to type
                 * into, so a box of bubbles said so better than a dead cursor. It is the
                 * opposite of true now, and it was the reason typing mid-turn still did
                 * nothing after the composer was supposedly made live - the plate had learned
                 * to send, the field had been un-disabled, and there was no field. Tapping it
                 * did nothing because there was nothing there to tap.
                 *
                 * So the bubbles belong to transcription alone, which is the one wait this
                 * composer still has no answer to: the words are coming and they are going
                 * into this box, and a cursor in it invites someone to race their own voice.
                 */
                when (voice.phase) {
                    // Both lines carry their own line height, so what fits is arithmetic
                    // rather than whatever the font happens to want.
                    // Taller than the block it sits in, and allowed to be: the rule keeps
                    // its length and the second line overflows into the shell's own margin.
                    VoicePhase.RECORDING -> Column(
                        Modifier.wrapContentHeight(unbounded = true),
                    ) {
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

                    VoicePhase.TRANSCRIBING -> BubbleField()

                    // The field is always here, and the notice sits behind it as a hint.
                    // Replacing the field with the notice was a trap: the only way to clear
                    // the line was to type, and there was nothing left to type into.
                    VoicePhase.IDLE -> {
                        if (speaking && voice.notice != null) {
                            // Two lines when there is a code, the same shape the recording
                            // state uses, and allowed to overflow the ruled block for the
                            // same reason: the rule stays one line of text long.
                            Column(Modifier.wrapContentHeight(unbounded = true)) {
                                Text(
                                    voice.notice.orEmpty(),
                                    style = fieldStyle,
                                    color = Areel.Ink40,
                                    maxLines = 1,
                                )
                                voice.noticeCode?.let { code ->
                                    Text(
                                        code,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp,
                                            platformStyle =
                                                PlatformTextStyle(includeFontPadding = false),
                                        ),
                                        color = Areel.Ink40,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    // Not single-line any more, but still capped: at the cap the field holds
                    // its height and BasicTextField scrolls the text within it, which is the
                    // behaviour wanted - the last line typed stays in view.
                    maxLines = COMPOSER_MAX_LINES,
                    onTextLayout = { typedLines = it.lineCount },
                    textStyle = fieldStyle,
                    cursorBrush = SolidColor(Areel.Magenta),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                }
              }
            }
            // The plate, which is a composable of its own rather than another hundred and
            // fifty lines of this one. See [SendPlate] - the reason is not tidiness.
            SendPlate(
                voice = voice,
                typed = typed,
                speaking = speaking,
                onSend = ::submit,
            )
        }
    }
}

/**
 * The one square at the end of the bar, doing whichever of its three jobs applies.
 *
 * A square plate rather than an IconButton: M3's clips its container to CircleShape, so the send
 * control came out round - the one shape this design does not contain anywhere.
 *
 * The three jobs, in the order they outrank each other. [typed] means there is something to send,
 * and that beats everything else - it is what the person has plainly decided to do, and while a
 * turn is running it is how that turn gets redirected. [stopping] is a turn in flight with
 * nothing written, and then the plate is the way to call it off. [speaking] is an empty field on
 * a profile that can transcribe, and then it is a microphone, held down rather than tapped.
 *
 * Lifted out of [Composer] rather than written here from the start, and that is worth recording
 * because nothing in the code says it: `Composer` had grown to the point where the method the
 * Compose compiler generates for it is rejected by ART's verifier outright, and the app died on
 * launch with `VerifyError ... copy1 v5<-v270` and no hint that sheer size was the problem. The
 * limit is per method, so the fix is to have two of them. This is the half that comes out
 * cleanly: everything it needs is in its six parameters.
 */
@Composable
private fun SendPlate(
    voice: VoiceState,
    typed: Boolean,
    speaking: Boolean,
    onSend: () -> Unit,
) {
    val held = voice.phase == VoicePhase.RECORDING

    Box(
        Modifier.padding(start = 10.dp).size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
    // Drawn from inside the composer so it can leave the button it belongs to. The bar
    // is the last thing the screen draws, and nothing here clips, so a child that
    // overflows upward lands over the conversation - which is where the light goes.
    CancelBeam(voice)
    Box(
        Modifier
            .requiredSize(48.dp + TOUCH_SLOP * 2)
            .then(
                if (speaking) {
                    // Held, not tapped - the second line of the indicator promises
                    // that releasing sends, and only a press gesture can keep that
                    // promise. tryAwaitRelease returns on a lifted finger and on a
                    // cancelled gesture alike, which is the behaviour wanted: sliding
                    // off the button still ends the recording rather than orphaning it.
                    Modifier.pointerInput(Unit) {
                        val reach = CANCEL_REACH.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            voice.onHold()
                            var up = false
                            // Followed by hand rather than with a drag detector: a drag
                            // detector waits for slop before it reports anything, and
                            // this gesture starts the moment the finger lands.
                            while (true) {
                                val touch = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id } ?: break
                                if (!touch.pressed) break
                                up = down.position.y - touch.position.y > reach
                                voice.aim(up)
                            }
                            if (up) voice.onCancel() else voice.onRelease()
                        }
                    }
                } else {
                    // The buzz is [Feel.CLICKY]'s own, so there is nothing to add here
                    // beyond doing the thing.
                    Modifier.pressable(
                        Feel.CLICKY,
                        enabled = typed,
                        indication = null,
                        onClick = onSend,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The plate is drawn at 48; the box around it listens wider. Colour stays here
        // so the enlarged press area is invisible.
        Box(
            Modifier
                .size(48.dp)
                .background(
                    when {
                        // Held: inverted, so the control that is doing something looks
                        // pressed rather than merely coloured.
                        held -> Areel.Ink
                        // Nothing written, nothing running and no microphone to offer -
                        // the one arrangement in which this square has no job at all.
                        !typed && !speaking -> Areel.Ink20
                        else -> Areel.Magenta
                    },
                    RectangleShape,
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
                tint = if (typed || speaking) Areel.Paper else Areel.Ink40,
                modifier = Modifier.size(24.dp),
            )
        }
    }
    }
}

/**
 * The way to call off whatever is running, riding above the send plate rather than replacing it.
 *
 * It used to *be* the send plate: for the length of a turn that square turned over into a fish
 * and the microphone went with it, so a turn in flight meant no voice input - the one form of
 * input somebody is most likely to want when their hands are busy enough to have put the phone
 * down. Two jobs on one square was the whole problem, so now there are two squares.
 *
 * It comes up out of the bar rather than appearing over it. Sliding is what makes it read as a
 * second control arriving, where a fade reads as the first one changing its mind - and the same
 * movement in reverse is what makes it plainly *gone* rather than merely disabled.
 *
 * And it goes away while the microphone is held. [CancelBeam] grows upward out of the plate into
 * exactly this space, so a fish parked there would be lit from below by the thing that means
 * *slide up to cancel* - two upward affordances, one of them a lie. Nothing is cancellable by
 * tapping mid-recording anyway: the gesture owns the finger.
 */
@Composable
private fun WorkingPlate(visible: Boolean, modifier: Modifier, onStop: () -> Unit) {
    /*
     * The fish somebody has already killed.
     *
     * A list rather than one, because the animation outlives the state that started it: the
     * plate stops turning the instant the turn is cancelled, and the fish that was turning still
     * has a second of falling to do. Keyed by a counter so a second kill during the first one's
     * fall is its own corpse rather than a restart of it, and each drops itself out of the list
     * once it is past the bottom of the screen - which is the whole of the recycling.
     */
    val dead = remember { mutableStateListOf<Int>() }
    var kills by remember { mutableIntStateOf(0) }
    // Read here: neither a Canvas nor a semantics block is a composable scope.
    val stopLabel = stringResource(R.string.stop_turn)

    /*
     * How far out it is, and the asymmetry is deliberate.
     *
     * Sprung on the way up, the same spring the attach plates come out on, because arriving is
     * the moment worth giving weight to. Eased on the way back, because an overshoot on the way
     * out would bounce the plate *away* from the bar it is supposed to be disappearing into.
     */
    val out = remember { Animatable(0f) }
    /*
     * Whether there is anything to compose, as a flag rather than as the animation's own value.
     *
     * Reading `out.value` here subscribed this composable to every frame of the spring, so the
     * whole subtree - the string lookup, the infinite transition, the mark - was rebuilt sixty
     * times a second while it rose. That is exactly what the deferred reads below it were
     * written to avoid. A Boolean flipped at the two ends of the animation says the same thing
     * and changes twice.
     */
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            present = true
            out.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow))
        } else {
            out.animateTo(0f, tween(durationMillis = ATTACH_EXIT_MS.toInt(), easing = EaseMech))
            present = false
        }
    }
    // Nothing at all when it is fully home, so it cannot take a tap meant for the bar under it -
    // unless a fish is still falling, which outlives the plate that dropped it by about a second.
    if (!present && dead.isEmpty()) return

    Box(modifier, contentAlignment = Alignment.Center) {
        // Allowed to leave the plate, and each removes itself once it is past the bottom of the
        // screen.
        dead.forEach { id ->
            key(id) { DyingFish(seed = id, onGone = { dead.remove(id) }) }
        }
        Box(
            Modifier
                // Laid out, not drawn, so the enlarged press area travels with it. A plate moved
                // by graphicsLayer would be tappable where it used to be.
                .offset { IntOffset(0, ((1f - out.value) * WORKING_RISE.toPx()).toInt()) }
                // The same slop every other plate in the app listens through. Without it the one
                // control people reach for in a hurry was the only 48dp target on a bar of 60dp
                // ones, and a near-miss fell through to the composer behind it.
                .requiredSize(48.dp + TOUCH_SLOP * 2)
                // Clicky, and deliberately the same weight as sending: stopping a turn is the
                // other irreversible thing this bar does. Off once it is on its way home, so a
                // plate nobody can see cannot be pressed.
                .pressable(Feel.CLICKY, enabled = visible, indication = null) {
                    dead += kills++
                    onStop()
                },
            contentAlignment = Alignment.Center,
        ) {
            // Drawn at 48 inside a box that listens wider, so the enlarged area stays invisible.
            Box(
                Modifier
                    .graphicsLayer {
                        alpha = out.value.coerceIn(0f, 1f)
                        scaleX = 0.86f + 0.14f * out.value
                        scaleY = 0.86f + 0.14f * out.value
                    }
                    .shadow(6.dp, clip = false, ambientColor = Areel.Ink, spotColor = Areel.Ink)
                    .size(48.dp)
                    .background(Areel.Ink, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Turning over, the way a fish does.
                val turning = rememberInfiniteTransition(label = "fish")
                val face by turning.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(FLIP_MS, easing = LinearEasing),
                    ),
                    label = "flip",
                )
                FishMark(
                    // The eye is dropped: past a quarter turn the mark is mirrored, and an eye that
                    // swaps ends reads as a fault rather than a fish.
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationY = face }
                        .semantics {
                            contentDescription = stopLabel
                            role = Role.Button
                        },
                    body = Areel.Paper,
                    eye = null,
                )
            }
        }
    }
}

/**
 * How far below its resting place the working plate starts.
 *
 * Its own height plus the gap, so it begins exactly behind the bar and is not seen until it
 * clears it - the plate rises out of the composer rather than fading in above it.
 */
private val WORKING_RISE = 56.dp

/**
 * More than any single message can be tall. Handed to scrollBy after landing on the last item,
 * because landing on an item puts its *top* at the top of the view - and an assistant plate with
 * sources under it is routinely taller than the view, so its top is not the end of anything.
 * The list clamps this to whatever scroll is actually left.
 */
private const val FAR_ENOUGH = 100_000f


/**
 * The way out of a voice message, while the finger is still holding the button down.
 *
 * A narrow lens of light standing on the microphone plate, chevrons climbing inside it, and one
 * line above it saying what to do with them. Every other affordance in the app can be looked at
 * before it is used. This one cannot - the finger that would point at it is the finger holding
 * the button - so it has to be read from the corner of an eye, which is why it is a moving arrow
 * and not a word.
 *
 * It grows out of the button rather than hovering over it: the foot of the shape is the middle
 * of the plate, so what is lit is plainly lit *by* the thing under the finger. That is also why
 * it is drawn here, inside the composer, instead of over the thread - the bar is the last thing
 * the screen paints, so a child of it that overflows upward passes over the conversation, while
 * one placed in the thread could never reach down over the bar.
 *
 * The light is the one soft edge in the app, deliberately. Everything else here is cut, and a
 * hard-edged beam would read as a shape rather than as a glow.
 *
 * Armed, it brightens and the line changes. Somebody has to be able to tell without looking
 * away from a conversation whether letting go now sends or discards, and the tick that comes
 * with [VoiceState.aim] is felt at the moment this changes rather than somewhere along the way.
 */
@Composable
private fun CancelBeam(voice: VoiceState) {
    val on = voice.phase == VoicePhase.RECORDING

    // How far open the shaft is. It grows out of the plate rather than appearing over it.
    val open by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = tween(BEAM_OPEN_MS, easing = FastOutSlowInEasing),
        label = "beam",
    )
    if (open < 0.01f) return
    val armed = voice.cancelling

    // The bin swells when letting go would use it. One thing moves, and it is the thing that
    // would happen - which is the whole reason this is a bin and not a beam of light.
    val bin by animateFloatAsState(
        targetValue = if (armed) 1f else 0f,
        animationSpec = tween(BIN_ARM_MS, easing = FastOutSlowInEasing),
        label = "bin",
    )

    /*
     * The chevrons work the way the voice lines do: emitted at the plate on a fixed interval,
     * travelling away at a constant speed, fading evenly the whole trip. They point at the bin
     * now, which is what stops them reading as "send".
     */
    val arrows = remember { mutableListOf<Float>() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(on) {
        if (on) arrows.clear()
        tick++
        var last = withFrameNanos { it }
        var since = ARROW_EMIT_MS
        while (on || arrows.isNotEmpty()) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000L).toFloat()
            last = now
            if (on) {
                since += dt
                if (since >= ARROW_EMIT_MS) {
                    since = 0f
                    arrows.add(0f)
                }
            }
            for (i in arrows.indices) arrows[i] = arrows[i] + dt / ARROW_LIFE_MS
            while (arrows.isNotEmpty() && arrows.first() >= 1f) arrows.removeAt(0)
            tick++
        }
    }

    val tall = BEAM_HEIGHT + BIN_PLATE + BEAM_HINT
    Column(
        Modifier
            // requiredSize, so none of this is measured into the bar: the composer keeps its
            // one height and all of this is overflow above it. Lifted by half, which puts the
            // foot of the column on the middle of the plate.
            .requiredSize(width = BEAM_SPAN, height = tall)
            .offset(y = -tall / 2),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            stringResource(if (armed) R.string.voice_cancel_armed else R.string.voice_cancel),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = if (armed) Areel.Magenta else Areel.Ink40,
            modifier = Modifier
                // Ends 12dp from the edge of the screen, like everything else in the bar.
                .padding(end = BEAM_SPAN / 2 - PLATE_CENTRE + 12.dp)
                .height(BEAM_HINT)
                .graphicsLayer { alpha = open },
        )

        // ---- the bin, at the far end of the shaft ------------------------------------
        // [BinMark] now, shared with the deletes on the memory and settings screens. The
        // slide-to-cancel arm and a two-tap delete are the same promise made twice, so they
        // are the same drawing rather than two that have to be kept in step by hand.
        BinMark(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(BIN_PLATE)
                .graphicsLayer {
                    val grow = 1f + BIN_SWELL * bin
                    scaleX = grow
                    scaleY = grow
                    alpha = open
                },
            armed = armed,
        )

        // ---- the shaft ---------------------------------------------------------------
        Canvas(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(BEAM_BODY)
                .height(BEAM_HEIGHT),
        ) {
            @Suppress("UNUSED_EXPRESSION") tick      // subscribe: this repaints every frame
            val foot = size.height                   // the plate, where the shaft stands
            val reach = size.height * open           // how much of it is open right now
            val head = foot - reach

            /*
             * A rectangle, not a cone.
             *
             * It was a lens of light widening upward, and the shape said nothing: an upward
             * glow with arrows in it reads as *send*, and somebody who did not stop to read
             * 上滑取消语音 would learn what it did by losing a message. A shaft with a bin on
             * the end of it can only mean one thing.
             */
            val step = (if (armed) 0.20f else 0.10f) * open
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Areel.Magenta.copy(alpha = step)),
                    startY = head,
                    endY = foot,
                ),
                topLeft = Offset(0f, head),
                size = Size(size.width, reach),
            )

            val arm = size.width * 0.26f * open
            val drop = size.height * 0.05f
            val edge = Stroke(width = 2.dp.toPx())
            val mid = size.width / 2f
            arrows.forEach { travelled ->
                // Inside the shaft however far open it is, so nothing climbs out ahead of it.
                val y = foot - reach * (0.10f + 0.78f * travelled)
                drawPath(
                    Path().apply {
                        moveTo(mid - arm, y + drop)
                        lineTo(mid, y)
                        lineTo(mid + arm, y + drop)
                    },
                    Areel.Magenta.copy(
                        alpha = ((if (armed) 0.95f else 0.62f) * (1f - travelled) * open)
                            .coerceIn(0f, 1f),
                    ),
                    style = edge,
                )
            }
        }
    }
}

/**
 * How tall the composer is allowed to get.
 *
 * Four lines, then the text scrolls inside it. One line was the whole field: anything longer
 * than a short question scrolled sideways a word at a time, and what somebody had written was
 * unreadable while they were writing it. Four is where the bar still leaves most of the
 * conversation on screen on the phone this is for.
 */
private const val COMPOSER_MAX_LINES = 4

/**
 * The thread fading up when the screen is opened.
 *
 * Long enough to read as arriving, short enough that somebody coming back from Settings is not
 * kept waiting for their own conversation.
 */
private const val RETURN_FADE_MS = 220

/** How far up the finger has to travel before letting go discards instead of sends. */
private val CANCEL_REACH = 56.dp

/**
 * Where the microphone plate's middle is, measured in from the right edge of the screen.
 *
 * The bar is inset 12 and the plate is 48 wide, so 36. The light is centred on this and the
 * line above it is placed off it.
 */
private val PLATE_CENTRE = 36.dp

/** The shaft: how wide it stands and how far up it reaches. */
private val BEAM_BODY = 56.dp
private val BEAM_HEIGHT = 132.dp

/** The bin at the top of it, and how much bigger it gets when letting go would use it. */
private val BIN_PLATE = 40.dp
private const val BIN_SWELL = 0.34f
private const val BIN_ARM_MS = 160

/** The line above it, and the room the pair of them are allowed to take. */
private val BEAM_HINT = 20.dp
private val BEAM_SPAN = 220.dp

/**
 * How often a chevron leaves the plate, and how long it takes to climb the light and fade.
 *
 * The same shape of numbers as the voice lines in Surfaces.kt, spread further apart: these are
 * read one at a time as a direction, where the lines are read together as a waveform.
 */
private const val ARROW_EMIT_MS = 300f
private const val ARROW_LIFE_MS = 1150f

/** How long the light takes to open out of the button, and to close back into it. */
private const val BEAM_OPEN_MS = 260

/** One turn of the fish, while it is fetching the words. */
private const val FLIP_MS = 1400

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
            .pressable(
                interaction = interaction,
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
                .pressable(onClick = onOpen),
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
                .pressable(onClick = onRemove),
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
private fun AttachViewer(attach: AttachState, kept: android.graphics.Bitmap?, onClose: () -> Unit) {
    // Either the picture being attached right now, or one recalled out of the log. One viewer
    // for both, because it is the same thing to look at and the same gesture to dismiss.
    val shown = kept?.asImageBitmap() ?: attach.viewing?.thumb?.asImageBitmap() ?: return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Areel.Ink.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
    }
}

/** How often the jump ticks on its way down. Texture, not a countdown. */
private const val JUMP_TICK_MS = 55L

/** Left to right, out of the plus. Same beat as the masthead menu. */
private const val ATTACH_STAGGER_MS = 55L

/** And back down into it, eased. Long enough to read as travel, short enough not to be waited on. */
private const val ATTACH_EXIT_MS = 150L

/** Square, and small enough that five of them fit across a phone. */
private val THUMB = 56.dp
