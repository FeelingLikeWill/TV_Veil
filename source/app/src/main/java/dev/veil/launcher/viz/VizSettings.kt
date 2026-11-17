package dev.veil.launcher.viz

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Visualizer sliders. Compose state (for the UI) that the render thread also reads every frame. */
object VizSettings {
    var sensitivity by mutableFloatStateOf(1.4f)   // 0.25..3
    var motion by mutableFloatStateOf(1f)          // 0.5..2
    var trails by mutableFloatStateOf(0.5f)        // 0..1
    var glow by mutableFloatStateOf(0.5f)          // 0..1
    var tint by mutableFloatStateOf(0.5f)          // 0..1
    /** How bright the brightest parts may get (soft cap; loud sections used to blow out to white). */
    var brightness by mutableFloatStateOf(0.5f)    // 0..1
    var presetSeconds by mutableFloatStateOf(30f)  // 10..120
    /** Hide all text/icons over the visualizer (revealed for 5 s when a song starts). */
    var clean by mutableStateOf(false)
    /** Synced lyrics over the visuals; works on top of Clean screen (then the lyrics are all you see). */
    var lyrics by mutableStateOf(true)

    /** The visualizer needs OpenGL ES 3.0; some older/cheaper TV chips only have 2.0. */
    fun supported(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .deviceConfigurationInfo.reqGlEsVersion >= 0x30000
    }.getOrDefault(false)

    /** Bumped by "Next preset"; renderers switch when it changes. */
    var skipRequests by mutableIntStateOf(0)

    fun load(context: Context) {
        val p = context.getSharedPreferences("viz", Context.MODE_PRIVATE)
        sensitivity = p.getFloat("sensitivity", 1.4f)
        motion = p.getFloat("motion", 1f)
        trails = p.getFloat("trails", 0.5f)
        glow = p.getFloat("glow", 0.5f)
        tint = p.getFloat("tint", 0.5f)
        brightness = p.getFloat("brightness", 0.5f)
        presetSeconds = p.getFloat("presetSeconds", 30f)
        clean = p.getBoolean("clean", false)
        lyrics = p.getBoolean("lyrics", true)
    }

    fun save(context: Context) {
        context.getSharedPreferences("viz", Context.MODE_PRIVATE).edit()
            .putFloat("sensitivity", sensitivity).putFloat("motion", motion).putFloat("trails", trails)
            .putFloat("glow", glow).putFloat("tint", tint).putFloat("brightness", brightness).putFloat("presetSeconds", presetSeconds)
            .putBoolean("clean", clean).putBoolean("lyrics", lyrics).apply()
    }

    fun reset(context: Context) {
        sensitivity = 1.4f; motion = 1f; trails = 0.5f; glow = 0.5f; tint = 0.5f; brightness = 0.5f; presetSeconds = 30f; clean = false; lyrics = true
        save(context)
    }
}
