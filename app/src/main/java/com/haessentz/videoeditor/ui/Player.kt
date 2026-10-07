package com.haessentz.videoeditor.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

class PlayerState(val player: ExoPlayer) {
    var positionMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    fun seek(ms: Long, play: Boolean = true) {
        player.seekTo(ms.coerceAtLeast(0))
        if (play) player.play()
    }
    fun toggle() = if (player.isPlaying) player.pause() else player.play()
}

@Composable
fun rememberPlayer(uri: String): PlayerState {
    val ctx = LocalContext.current
    val state = remember(uri) { PlayerState(buildPlayer(ctx, uri)) }
    DisposableEffect(state) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.playing = isPlaying }
        }
        state.player.addListener(l)
        onDispose {
            state.player.removeListener(l)
            state.player.release()
        }
    }
    LaunchedEffect(state) {
        while (true) {
            state.positionMs = state.player.currentPosition
            delay(100)
        }
    }
    return state
}

private fun buildPlayer(ctx: Context, uri: String): ExoPlayer =
    ExoPlayer.Builder(ctx).build().apply {
        setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        prepare()
    }

@UnstableApi
@Composable
fun VideoView(state: PlayerState, modifier: Modifier = Modifier, controls: Boolean = true, zoom: Boolean = false, texture: Boolean = false) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            // A TextureView-backed player can be clipped and moved (needed for the 9:16 preview).
            val view = if (texture) android.view.LayoutInflater.from(c).inflate(com.haessentz.videoeditor.R.layout.player_texture, null) as PlayerView
            else PlayerView(c)
            view.apply {
                player = state.player
                useController = controls
                if (zoom) resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            }
        },
        update = { it.player = state.player }
    )
}
