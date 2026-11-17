package dev.veil.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picks the most characterful colours out of artwork (a tiny, dependency-free "palette"). */
object ArtColor {
    /**
     * Up to two dominant, reasonably saturated colours, strongest first. Pixels are bucketed by hue and
     * weighted by saturation x brightness, so a red logo beats a big grey sky.
     */
    fun extract(bmp: Bitmap): List<Color> {
        val small = if (bmp.width > 64) Bitmap.createScaledBitmap(bmp, 64, (64f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true) else bmp
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        val src = if (small.config == Bitmap.Config.HARDWARE) small.copy(Bitmap.Config.ARGB_8888, false) else small
        src.getPixels(px, 0, w, 0, 0, w, h)
        val bins = 24
        val weight = FloatArray(bins); val r = FloatArray(bins); val g = FloatArray(bins); val b = FloatArray(bins)
        val hsv = FloatArray(3)
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            val wgt = hsv[1] * hsv[1] * hsv[2]
            if (hsv[1] < 0.18f || hsv[2] < 0.15f) continue
            val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            weight[bin] += wgt
            r[bin] += wgt * ((c shr 16) and 0xFF); g[bin] += wgt * ((c shr 8) and 0xFF); b[bin] += wgt * (c and 0xFF)
        }
        val order = (0 until bins).filter { weight[it] > 0.4f }.sortedByDescending { weight[it] }
        if (order.isEmpty()) return emptyList()
        val first = order.first()
        // Second colour: the strongest hue that's clearly different (at least ~45° away).
        val second = order.drop(1).firstOrNull { val d = kotlin.math.abs(it - first); minOf(d, bins - d) >= 3 }
        return listOfNotNull(first, second).map { i ->
            Color(r[i] / weight[i] / 255f, g[i] / weight[i] / 255f, b[i] / weight[i] / 255f)
        }
    }

    /** A version that reads well as Veil's accent: light enough for dark text on it, never neon. */
    fun asAccent(c: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.7f)
        hsv[2] = hsv[2].coerceAtLeast(0.92f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** For edge glows: full brightness, a bit more saturation. */
    fun asGlow(c: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.9f)
        hsv[2] = 1f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    fun extract(img: ImageBitmap): List<Color> = runCatching { extract(img.asAndroidBitmap()) }.getOrDefault(emptyList())

    private val cache = HashMap<String, Color?>()

    /** Accent colour for an image URL (downloads a tiny copy through Coil's cache). Null if it has none. */
    suspend fun accentFor(context: Context, url: String): Color? {
        synchronized(cache) { if (cache.containsKey(url)) return cache[url] }
        val req = ImageRequest.Builder(context).data(url).size(Size(96, 54)).allowHardware(false).build()
        val result = context.imageLoader.execute(req)
        val bmp = (result as? SuccessResult)?.drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap } ?: return null
        val c = withContext(Dispatchers.Default) { extract(bmp).firstOrNull()?.let(::asAccent) }
        synchronized(cache) { cache[url] = c }
        return c
    }
}

/** "Colours from artwork": Veil's accent follows the focused show's artwork while on Home. */
object ArtTint {
    /** Current (animated) accent from artwork, or null to use the theme's. */
    var accent by mutableStateOf<Color?>(null)
        internal set
    var enabled by mutableStateOf(true)
        private set
    /** The artwork colour being moved towards (not animated), or null on the theme's colour. */
    var target by mutableStateOf<Color?>(null)
        internal set

    fun load(context: Context) {
        enabled = context.getSharedPreferences("veil", Context.MODE_PRIVATE).getBoolean("art_colors", true)
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        if (!on) accent = null
        context.getSharedPreferences("veil", Context.MODE_PRIVATE).edit().putBoolean("art_colors", on).apply()
    }
}

/** Drives [ArtTint] from the artwork currently behind Home. Glides between colours over ~1 s. */
@Composable
fun ArtTintEffect(url: String?, active: Boolean = true) {
    val context = LocalContext.current
    val theme = ThemeState.current.accent
    var target by remember { mutableStateOf<Color?>(null) }
    // Paused while the Veil menu is open, so picking a theme shows the theme's own accent.
    LaunchedEffect(url, ArtTint.enabled, active) {
        target = if (url != null && ArtTint.enabled && active) runCatching { ArtColor.accentFor(context, url) }.getOrNull() else null
    }
    val anim = remember { Animatable(ArtTint.accent ?: theme, Color.VectorConverter(ArtTint.accent?.colorSpace ?: theme.colorSpace)) }
    LaunchedEffect(target, theme, ArtTint.enabled) {
        val goal = (if (ArtTint.enabled) target else null) ?: theme
        ArtTint.target = if (ArtTint.enabled) target else null
        anim.animateTo(goal, tween(1100)) { ArtTint.accent = value }
        if (target == null || !ArtTint.enabled) ArtTint.accent = null // back on the theme's own colour
    }
}

/**
 * Title logos only look good when they're light: many TMDB logos are black or dark-coloured, which
 * disappears on Veil's dark hero. Checks each logo once (tiny copy from Coil's cache); dark ones fall back
 * to the plain title text.
 */
object LogoCheck {
    val readable = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    suspend fun check(context: Context, url: String) {
        if (readable.containsKey(url)) return
        val req = ImageRequest.Builder(context).data(url).size(Size(160, 160)).allowHardware(false).build()
        val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.drawable
            ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap } ?: return
        val ok = withContext(Dispatchers.Default) {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            var lum = 0.0; var n = 0
            for (c in px) {
                if ((c ushr 24) < 128) continue
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                lum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0; n++
            }
            n > px.size * 0.02 && lum / n > 0.42
        }
        readable[url] = ok
    }
}

/**
 * Pre-blurred, tiny copies of backdrops for the frosted-glass menus. Blurring the live screen is far too
 * slow on this TV's GPU, so a 192x108 copy is blurred once on the CPU and simply faded in (scaled up, it
 * looks like a heavy blur).
 */
object FrostCache {
    private val cache = HashMap<String, ImageBitmap>()

    suspend fun get(context: Context, url: String): ImageBitmap? {
        synchronized(cache) { cache[url]?.let { return it } }
        val req = ImageRequest.Builder(context).data(url).size(Size(192, 108)).allowHardware(false).build()
        val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.drawable
            ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap } ?: return null
        val img = withContext(Dispatchers.Default) {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxBlur(px, w, h, 5) } // three box passes ≈ a smooth gaussian
            android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        synchronized(cache) { if (cache.size > 30) cache.clear(); cache[url] = img }
        return img
    }

    internal fun blur(px: IntArray, w: Int, h: Int, r: Int) = boxBlur(px, w, h, r)

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, idx: (Int, Int) -> Int) {
            val n = 2 * r + 1
            for (l in 0 until lines) {
                var sr = 0; var sg = 0; var sb = 0
                for (k in -r..r) {
                    val c = src[idx(l, k.coerceIn(0, len - 1))]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
                for (i in 0 until len) {
                    dst[idx(l, i)] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    val out = src[idx(l, (i - r).coerceIn(0, len - 1))]
                    val inn = src[idx(l, (i + r + 1).coerceIn(0, len - 1))]
                    sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    sb += (inn and 0xFF) - (out and 0xFF)
                }
            }
        }
        pass(px, tmp, w, h) { line, i -> line * w + i }   // horizontal
        pass(tmp, px, h, w) { line, i -> i * w + line }   // vertical
    }
}

/** A tiny, heavily blurred copy of artwork (64 px), made off the main thread; scaled up it reads as a soft wash. */
@Composable
fun rememberSoftArt(art: ImageBitmap?): ImageBitmap? {
    var out by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(art) {
        out = if (art == null) null else withContext(Dispatchers.Default) {
            runCatching {
                val src = art.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                val small = Bitmap.createScaledBitmap(src, 64, 64, true)
                val px = IntArray(64 * 64)
                small.getPixels(px, 0, 64, 0, 0, 64, 64)
                repeat(3) { FrostCache.blur(px, 64, 64, 4) }
                Bitmap.createBitmap(px, 64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
    }
    return out
}
