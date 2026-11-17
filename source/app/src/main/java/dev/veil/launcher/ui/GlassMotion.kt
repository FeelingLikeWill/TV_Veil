package dev.veil.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Apple TV-style "glass" focus: the newly focused card tips slightly in the direction you moved and settles
 * with a soft wobble, while a band of light sweeps across it. Animations only touch the draw/layer phase,
 * so they cost no recomposition.
 */
class GlassMotion internal constructor(private val scope: CoroutineScope) {
    internal val tiltX = Animatable(0f)
    internal val tiltY = Animatable(0f)
    /** 0 → 1 while the light sweeps across; 1 = finished (nothing drawn). */
    internal val glint = Animatable(1f)
    internal var dir = 1f
    internal var center: Offset? = null

    internal fun onFocused() {
        val c = center
        val last = lastCenter
        lastCenter = c
        var dx = 0f; var dy = 0f
        if (c != null && last != null) { dx = c.x - last.x; dy = c.y - last.y }
        dir = if (abs(dx) >= abs(dy) && dx != 0f) sign(dx) else 1f
        val settle = spring<Float>(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow)
        scope.launch {
            if (abs(dx) > abs(dy)) { tiltY.snapTo(TILT * sign(dx)); tiltX.snapTo(0f) }
            else if (dy != 0f) { tiltX.snapTo(-TILT * sign(dy)); tiltY.snapTo(0f) }
            launch { tiltX.animateTo(0f, settle) }
            tiltY.animateTo(0f, settle)
        }
        scope.launch {
            glint.snapTo(0f)
            glint.animateTo(1f, tween(1400, easing = LinearOutSlowInEasing))
        }
    }

    companion object {
        private const val TILT = 5f
        /** Centre of whatever was focused last, to know which way the selection moved. */
        private var lastCenter: Offset? = null
    }
}

@Composable
fun rememberGlassMotion(): GlassMotion {
    val scope = rememberCoroutineScope()
    return remember { GlassMotion(scope) }
}

/** Put on the outside of a focusable card: tracks position/focus and tilts it. */
fun Modifier.glassTilt(m: GlassMotion): Modifier = this
    .onGloballyPositioned { m.center = it.boundsInRoot().center }
    .onFocusChanged { if (it.isFocused) m.onFocused() }
    .graphicsLayer {
        rotationX = m.tiltX.value
        rotationY = m.tiltY.value
        cameraDistance = 18f * density
    }

/**
 * Put inside the card's clipped content: a broad, soft sheen of light that drifts slowly across the card
 * in the direction you moved, as if it caught a lamp while tipping, plus a whisper of shade on the far
 * side so it also reads on the white focused pills. Colourless and low-key.
 */
fun Modifier.glassGlint(m: GlassMotion): Modifier = this.drawWithCache {
    // The gradients are created once per card size (they used to be rebuilt on every frame of the sweep)
    // and only moved and faded while drawing; the result is the same.
    val w = size.width; val h = size.height
    val ry = h * 1.3f
    val rx = maxOf(w * 0.45f, ry)
    val spot = Brush.radialGradient(
        0f to Color.White.copy(alpha = 0.2f), 0.45f to Color.White.copy(alpha = 0.07f), 1f to Color.Transparent,
        center = Offset.Zero, radius = ry,
    )
    // Shade on the side facing away from the light (light from the left → shade on the right, and back).
    val shadeRight = Brush.horizontalGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.06f))
    val shadeLeft = Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.06f), 0.4f to Color.Transparent, 1f to Color.Transparent)
    onDrawWithContent {
        drawContent()
        val p = m.glint.value
        if (p >= 1f) return@onDrawWithContent
        val strength = kotlin.math.sin(p * kotlin.math.PI.toFloat())          // eases in and out
        val lead = if (m.dir >= 0) 0f else 1f                                  // side the light comes from
        val x = w * (lead + (1f - 2f * lead) * (-0.15f + 1.3f * p))
        translate(x, h * 0.2f) {                                               // light from slightly above
            scale(scaleX = rx / ry, scaleY = 1f, pivot = Offset.Zero) {
                drawCircle(spot, radius = ry, center = Offset.Zero, alpha = strength)
            }
        }
        drawRect(if (lead == 0f) shadeRight else shadeLeft, alpha = strength)
    }
}
