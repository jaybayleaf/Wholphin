package com.github.damontecres.wholphin.ui.playback

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.updatePlaybackPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackButton
import com.github.damontecres.wholphin.ui.playback.overlay.buttonSpacing
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

/** Seconds to jump when skipping backward/forward. */
private const val TRAILER_SEEK_SECONDS = 10f

/** How long the control bar stays visible after the last interaction. */
private const val CONTROLS_TIMEOUT_MS = 4500L

/**
 * Backs [YouTubeTrailerPage]: pops the back stack, keeps the screen awake, and reads/writes the
 * "show captions on trailers" app preference so the choice carries across trailers.
 */
@HiltViewModel
class YouTubeTrailerViewModel
    @Inject
    constructor(
        private val navigationManager: NavigationManager,
        private val screensaverService: ScreensaverService,
        private val appPreferences: DataStore<AppPreferences>,
    ) : ViewModel() {
        val captionsEnabled: Flow<Boolean> =
            appPreferences.data.map { it.playbackPreferences.trailerCaptions }

        init {
            screensaverService.keepScreenOn(true)
        }

        fun setCaptionsEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appPreferences.updateData { it.updatePlaybackPreferences { trailerCaptions = enabled } }
            }
        }

        fun goBack() {
            navigationManager.goBack()
        }

        override fun onCleared() {
            super.onCleared()
            screensaverService.keepScreenOn(false)
        }
    }

/**
 * Plays a YouTube [Destination.YouTubeTrailer] inside the app using the YouTube IFrame player
 * (embedded in a [YouTubePlayerView]) rather than launching the external YouTube app.
 *
 * The YouTube web controls are hidden and replaced with a control bar styled like the main
 * player's:
 *  - while it is hidden, left/right (or the media rewind/ffwd keys) skip [TRAILER_SEEK_SECONDS]s
 *    and any other d-pad press brings up the bar
 *  - the bar has rewind / play-pause / fast-forward / captions buttons, a seek bar and time
 *  - back hides the bar, or (when already hidden) returns to the previous page; the video ending
 *    also returns automatically
 *
 * Captions default to off and are toggled from the bar (or the captions media key); the choice is
 * persisted as the "show captions on trailers" app preference.
 */
@Composable
fun YouTubeTrailerPage(
    destination: Destination.YouTubeTrailer,
    modifier: Modifier = Modifier,
    viewModel: YouTubeTrailerViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootFocus = remember { FocusRequester() }
    val seekBarFocus = remember { FocusRequester() }

    var player by remember { mutableStateOf<YouTubePlayer?>(null) }
    var playerView by remember { mutableStateOf<YouTubePlayerView?>(null) }
    // Bumped whenever the player's module API (re)loads, so the caption state is re-asserted
    var apiChangeNonce by remember { mutableIntStateOf(0) }
    var positionSeconds by remember { mutableFloatStateOf(0f) }
    var durationSeconds by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(false) }
    // Bumped on every interaction to restart the control bar's auto-hide timer
    var interactionNonce by remember { mutableIntStateOf(0) }

    val captionsEnabled by viewModel.captionsEnabled.collectAsState(initial = false)

    fun showControls() {
        controlsVisible = true
        interactionNonce++
    }

    fun seekBy(deltaSeconds: Float) {
        val yt = player ?: return
        var target = (positionSeconds + deltaSeconds).coerceAtLeast(0f)
        if (durationSeconds > 0f) target = target.coerceAtMost(durationSeconds)
        yt.seekTo(target)
    }

    LaunchedEffect(apiChangeNonce, captionsEnabled) {
        if (apiChangeNonce == 0) return@LaunchedEffect
        val webView = playerView?.findWebView() ?: return@LaunchedEffect
        // The IFrame caption module can be slow to populate its track list, so re-apply a few times.
        repeat(3) {
            webView.evaluateJavascript(captionsScript(captionsEnabled), null)
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, interactionNonce) {
        if (controlsVisible) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    // Own the d-pad focus while the bar is hidden; hand it to the seek bar while it is shown, so
    // left/right keeps seeking (now via the focused seek bar) instead of moving between buttons.
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) seekBarFocus.tryRequestFocus() else rootFocus.tryRequestFocus()
    }
    LaunchedEffect(player) { if (player != null && !controlsVisible) rootFocus.tryRequestFocus() }

    BackHandler(enabled = !controlsVisible) { viewModel.goBack() }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                // Catch Back before the focus system can consume it to move focus out of a
                // control, so a single press always hides the bar.
                .onPreviewKeyEvent { event ->
                    if (controlsVisible && isBackKey(event)) {
                        if (event.type == KeyEventType.KeyUp) controlsVisible = false
                        true
                    } else {
                        false
                    }
                }.focusRequester(rootFocus)
                .focusable()
                .onKeyEvent { event ->
                    if (controlsVisible || event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    when {
                        event.key == Key.Captions -> {
                            viewModel.setCaptionsEnabled(!captionsEnabled)
                            showControls()
                            true
                        }

                        isSkipBack(event) || isBackwardButton(event) -> {
                            seekBy(-TRAILER_SEEK_SECONDS)
                            showControls()
                            true
                        }

                        isSkipForward(event) || isForwardButton(event) -> {
                            seekBy(TRAILER_SEEK_SECONDS)
                            showControls()
                            true
                        }

                        isDpad(event) || isEnterKey(event) -> {
                            showControls()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                YouTubePlayerView(context).apply {
                    playerView = this
                    enableAutomaticInitialization = false
                    // The remote drives playback; don't let the WebView grab D-pad focus.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    lifecycleOwner.lifecycle.addObserver(this)

                    val listener =
                        object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                player = youTubePlayer
                                youTubePlayer.loadVideo(destination.videoId, 0f)
                            }

                            override fun onStateChange(
                                youTubePlayer: YouTubePlayer,
                                state: PlayerConstants.PlayerState,
                            ) {
                                when (state) {
                                    PlayerConstants.PlayerState.PLAYING -> {
                                        playing = true
                                        // Fallback in case onApiChange has not fired yet
                                        apiChangeNonce++
                                    }

                                    PlayerConstants.PlayerState.PAUSED -> {
                                        playing = false
                                    }

                                    PlayerConstants.PlayerState.ENDED -> {
                                        viewModel.goBack()
                                    }

                                    else -> {
                                        Unit
                                    }
                                }
                            }

                            override fun onCurrentSecond(
                                youTubePlayer: YouTubePlayer,
                                second: Float,
                            ) {
                                positionSeconds = second
                            }

                            override fun onVideoDuration(
                                youTubePlayer: YouTubePlayer,
                                duration: Float,
                            ) {
                                durationSeconds = duration
                            }

                            override fun onApiChange(youTubePlayer: YouTubePlayer) {
                                // The captions module is available once the API is ready
                                apiChangeNonce++
                            }

                            override fun onError(
                                youTubePlayer: YouTubePlayer,
                                error: PlayerConstants.PlayerError,
                            ) {
                                Timber.w("YouTube trailer error for %s: %s", destination.videoId, error)
                            }
                        }

                    val options =
                        IFramePlayerOptions
                            .Builder(context)
                            .controls(0)
                            .rel(0)
                            .ccLoadPolicy(0)
                            .build()
                    initialize(listener, options)
                }
            },
            onRelease = { view ->
                lifecycleOwner.lifecycle.removeObserver(view)
                playerView = null
                view.release()
            },
        )

        // Full-screen darkening scrim behind the controls, matching the main player's overlay.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            val scrimBrush =
                remember {
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.80f),
                            ),
                    )
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(scrimBrush),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
        ) {
            TrailerControlBar(
                title = destination.title,
                positionSeconds = positionSeconds,
                durationSeconds = durationSeconds,
                playing = playing,
                captionsEnabled = captionsEnabled,
                seekBarFocus = seekBarFocus,
                onInteraction = { interactionNonce++ },
                onRewind = { seekBy(-TRAILER_SEEK_SECONDS) },
                onForward = { seekBy(TRAILER_SEEK_SECONDS) },
                onPlayPause = { player?.let { if (playing) it.pause() else it.play() } },
                onToggleCaptions = { viewModel.setCaptionsEnabled(!captionsEnabled) },
            )
        }
    }
}

@Composable
private fun TrailerControlBar(
    title: String?,
    positionSeconds: Float,
    durationSeconds: Float,
    playing: Boolean,
    captionsEnabled: Boolean,
    seekBarFocus: FocusRequester,
    onInteraction: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPlayPause: () -> Unit,
    onToggleCaptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationSeconds > 0f) (positionSeconds / durationSeconds).coerceIn(0f, 1f) else 0f
    val screenHeightPx = LocalWindowInfo.current.containerSize.height
    val bottomPadding = with(LocalDensity.current) { (screenHeightPx * 0.075f).toDp() }
    val seekBarToButtonsGap = with(LocalDensity.current) { (screenHeightPx * 0.04f).toDp() }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = bottomPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            Text(
                text = "${formatClock(positionSeconds)} / ${formatClock(durationSeconds)}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.height(12.dp))
        TrailerSeekBar(
            progress = progress,
            focusRequester = seekBarFocus,
            onSeekBack = onRewind,
            onSeekForward = onForward,
            onPlayPause = onPlayPause,
            onFocused = onInteraction,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(seekBarToButtonsGap))
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaybackButton(
                    iconRes = R.drawable.baseline_fast_rewind_24,
                    onClick = onRewind,
                    onControllerInteraction = onInteraction,
                )
                PlaybackButton(
                    iconRes = if (playing) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24,
                    onClick = onPlayPause,
                    onControllerInteraction = onInteraction,
                )
                PlaybackButton(
                    iconRes = R.drawable.baseline_fast_forward_24,
                    onClick = onForward,
                    onControllerInteraction = onInteraction,
                )
            }
            TrailerToggleButton(
                iconRes = R.drawable.captions_svgrepo_com,
                active = captionsEnabled,
                onClick = onToggleCaptions,
                onControllerInteraction = onInteraction,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * A focusable seek bar styled like the main player's. Left/right seek by a fixed amount (consumed
 * on both key phases so focus does not move away), center toggles play/pause, and up/down are left
 * for normal focus traversal to the button row.
 */
@Composable
private fun TrailerSeekBar(
    progress: Float,
    focusRequester: FocusRequester,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPlayPause: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val barHeight by animateDpAsState(targetValue = if (focused) 12.dp else 6.dp, label = "TrailerSeekBarHeight")
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val fillColor = MaterialTheme.colorScheme.border
    val clamped = progress.coerceIn(0f, 1f)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(barHeight)
                .padding(horizontal = 4.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .focusable(interactionSource = interactionSource)
                .onKeyEvent { event ->
                    val handled = isDpadLeft(event) || isDpadRight(event) || isEnterKey(event)
                    if (!handled) return@onKeyEvent false
                    if (event.type == KeyEventType.KeyUp) {
                        when {
                            isDpadLeft(event) -> onSeekBack()
                            isDpadRight(event) -> onSeekForward()
                            else -> onPlayPause()
                        }
                        onFocused()
                    }
                    true
                },
    ) {
        val y = size.height / 2f
        drawLine(
            color = trackColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = fillColor,
            start = Offset(0f, y),
            end = Offset(size.width * clamped, y),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Color.White,
            radius = size.height + 2f,
            center = Offset(size.width * clamped, y),
        )
    }
}

/**
 * An icon toggle button styled like [PlaybackButton] but with an on/off state: the icon is tinted
 * with the primary color and the background is darker while [active].
 */
@Composable
private fun TrailerToggleButton(
    @DrawableRes iconRes: Int,
    active: Boolean,
    onClick: () -> Unit,
    onControllerInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (active) AppColors.TransparentBlack75 else AppColors.TransparentBlack25,
                focusedContainerColor = MaterialTheme.colorScheme.border,
            ),
        contentPadding = PaddingValues(4.dp),
        modifier =
            modifier
                .size(36.dp, 36.dp)
                .onFocusChanged { onControllerInteraction() },
    ) {
        Icon(
            modifier = Modifier.fillMaxSize(),
            painter = painterResource(iconRes),
            contentDescription = null,
            tint =
                if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

/**
 * JavaScript for the YouTube IFrame player (global `player`) to enable or disable captions.
 * The library exposes no caption API, so this is injected into its WebView directly.
 */
private fun captionsScript(enabled: Boolean): String =
    if (enabled) {
        """
        try {
          player.loadModule('captions');
          player.loadModule('cc');
          var tl = player.getOption('captions','tracklist') || player.getOption('cc','tracklist') || [];
          if (tl.length) {
            player.setOption('captions','track', tl[0]);
            player.setOption('cc','track', tl[0]);
          }
        } catch (e) {}
        """.trimIndent()
    } else {
        "try { player.unloadModule('captions'); player.unloadModule('cc'); } catch (e) {}"
    }

/** Depth-first search for the [WebView] the YouTube player renders into. */
private fun View.findWebView(): WebView? =
    when (this) {
        is WebView -> {
            this
        }

        is ViewGroup -> {
            (0 until childCount)
                .asSequence()
                .mapNotNull { getChildAt(it)?.findWebView() }
                .firstOrNull()
        }

        else -> {
            null
        }
    }

/** Formats a number of seconds as `m:ss`, or `h:mm:ss` when an hour or longer. */
private fun formatClock(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", m, s)
    }
}
