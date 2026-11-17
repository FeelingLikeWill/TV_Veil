package dev.veil.launcher.ui

import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.veil.launcher.data.AudioHub
import dev.veil.launcher.viz.VizSettings
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import dev.veil.launcher.data.NowPlaying
import dev.veil.launcher.viz.VisualizerRenderer
import kotlinx.coroutines.delay

/** Music mode "Visualizer": full-screen MilkDrop-style visuals with a compact track card on top. */
@Composable
fun VisualizerFace(track: NowPlaying.Track, chromeAlpha: Float = 1f) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var presetName by remember { mutableStateOf<String?>(null) }
    var showName by remember { mutableStateOf(false) }

    val accent = Veil.Accent
    val bg = Veil.Bg
    val main = remember { Handler(Looper.getMainLooper()) }
    val audio = remember { AudioHub.acquire(context) }
    val renderer = remember { VisualizerRenderer(audio) { name -> main.post { presetName = name } } }
    renderer.accent = floatArrayOf(accent.red, accent.green, accent.blue)
    renderer.background = floatArrayOf(bg.red, bg.green, bg.blue)

    // Show the preset's name for a few seconds whenever it changes.
    LaunchedEffect(presetName) {
        if (presetName != null) { showName = true; delay(4_000); showName = false }
    }

    val view = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            // Render at 960x540; the display hardware scales it to the screen for free. The visuals are soft
            // (the detail lives in the 640x360 feedback buffer), so this looks the same and leaves the GPU
            // room for the lyrics and other animations drawn on top.
            if (dev.veil.launcher.BuildConfig.LITE_MODE) holder.setFixedSize(640, 360) else holder.setFixedSize(960, 540)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            view.onPause()
            AudioHub.release()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        // Lyrics over the visuals (own switch in Visualizer settings). They stay when Clean screen hides
        // everything else, and then sit a little lower, where the track card would be.
        if (VizSettings.lyrics) {
            val lyr = rememberLyrics(track)
            var shown by remember { mutableStateOf<dev.veil.launcher.data.Lyrics.Song?>(null) }
            if (lyr is dev.veil.launcher.data.Lyrics.State.Ready && lyr.hasLines()) shown = lyr.song
            else if (lyr !is dev.veil.launcher.data.Lyrics.State.Loading) shown = null
            val clean = chromeAlpha < 0.5f
            val bottom by animateDpAsState(if (clean) 96.dp else 150.dp, tween(900), label = "lyrics-y")
            // Faded by hand without an offscreen layer: AnimatedVisibility's fade clipped the soft halo and
            // the text shadows into a dark rectangle until it finished.
            val visible = shown != null && lyr !is dev.veil.launcher.data.Lyrics.State.Loading
            val fade by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 900 else 600), label = "lyrics-fade")
            val song = shown
            if (song != null && fade > 0f) {
                val pos = rememberPlaybackPosition(track)
                LyricsScroller(
                    song, pos, LyricsLook.OVERLAY,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 80.dp, end = 80.dp)
                        .graphicsLayer {
                            alpha = fade
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        }
                        // A soft dark halo behind the words keeps them readable on bright presets
                        // (an ellipse that fades out before the edges of the lyrics area).
                        .drawBehind {
                            val c = Offset(size.width / 2, size.height / 2)
                            val ry = size.height / 2
                            scale(scaleX = (size.width / 2) / ry, scaleY = 1f, pivot = c) {
                                drawCircle(
                                    Brush.radialGradient(
                                        0f to Color.Black.copy(alpha = 0.3f * fade), 0.55f to Color.Black.copy(alpha = 0.13f * fade),
                                        1f to Color.Transparent, center = c, radius = ry,
                                    ),
                                    radius = ry, center = c,
                                )
                            }
                        }.widthIn(max = 1100.dp).fillMaxWidth().height(180.dp),
                )
            }
        }

        // Preset name, top centre
        AnimatedVisibility(
            visible = showName && presetName != null,
            enter = fadeIn(tween(600)), exit = fadeOut(tween(900)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp).alpha(chromeAlpha),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(VeilIcons.Sparkle, contentDescription = null, modifier = Modifier.size(11.dp), tint = Veil.Accent)
                Spacer(Modifier.width(7.dp))
                Text(presetName ?: "", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            }
        }

        // Track card, bottom left
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 26.dp).alpha(chromeAlpha)
                .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.38f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Veil.GlassStrong), contentAlignment = Alignment.Center) {
                val art = track.artLarge ?: track.art
                if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(VeilIcons.Note, contentDescription = null, modifier = Modifier.size(22.dp), tint = Veil.Text)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(track.title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                track.artist?.let { Text(it, fontSize = 11.sp, color = Veil.Text.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (track.durationMs > 0) {
                    val pos by produceState(track.positionNow(), track) { while (true) { value = track.positionNow(); delay(500) } }
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.width(200.dp).height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))) {
                        Box(
                            Modifier.fillMaxWidth((pos.toFloat() / track.durationMs).coerceIn(0f, 1f)).height(3.dp)
                                .clip(RoundedCornerShape(50)).background(Veil.Accent),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}
