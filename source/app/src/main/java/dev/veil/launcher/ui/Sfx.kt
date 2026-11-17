package dev.veil.launcher.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Veil's interface sounds. Each one is synthesised once from soft sine tones (no downloaded files), saved to
 * Veil's cache as a tiny WAV and played through SoundPool for low latency. Quieter while music is playing.
 */
object Sfx {
    enum class S { MOVE, SELECT, OPEN, BACK, ON, OFF, SUCCESS, ERROR, INTRO }

    private const val VERSION = 1 // bump when the sound design changes, to re-synthesise
    private var pool: SoundPool? = null
    private val ids = HashMap<S, Int>()
    private var audio: AudioManager? = null
    private var lastMove = 0L
    @Volatile var enabled = true
        private set

    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        enabled = app.getSharedPreferences("veil", Context.MODE_PRIVATE).getBoolean("sounds", true)
        audio = app.getSystemService(AudioManager::class.java)
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        pool = p
        // The wake-up chime can be asked for before it has loaded (first boot): play it as soon as it's ready.
        p.setOnLoadCompleteListener { pl, id, status ->
            if (status == 0 && id == ids[S.INTRO] && SystemClock.elapsedRealtime() - introWanted < 1_500) {
                introWanted = 0L
                if (enabled) pl.play(id, 0.6f, 0.6f, 1, 0, 1f)
            }
        }
        thread(name = "veil-sfx") {
            val dir = File(app.cacheDir, "sfx").apply { mkdirs() }
            for (s in S.values()) {
                val f = File(dir, "${s.name.lowercase()}_v$VERSION.wav")
                if (!f.exists()) runCatching { writeWav(f, synth(s)) }
                if (f.exists()) ids[s] = p.load(f.path, 1)
            }
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences("veil", Context.MODE_PRIVATE).edit().putBoolean("sounds", on).apply()
    }

    /** Selection moved; [pitch] varies by area (cards lower, menus higher). Rate-limited for held keys. */
    fun move(pitch: Float = 1f) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMove < 45) return
        lastMove = now
        play(S.MOVE, 0.55f, pitch)
    }
    fun select() = play(S.SELECT, 0.7f)
    fun open() = play(S.OPEN, 0.6f)
    fun back() = play(S.BACK, 0.6f)
    fun on() = play(S.ON, 0.65f)
    fun off() = play(S.OFF, 0.65f)
    fun success() = play(S.SUCCESS, 0.65f)
    fun error() = play(S.ERROR, 0.65f)
    @Volatile private var introWanted = 0L
    fun intro() {
        if (ids[S.INTRO] == null) introWanted = SystemClock.elapsedRealtime() else play(S.INTRO, 0.6f)
    }

    private fun play(s: S, volume: Float, rate: Float = 1f) {
        if (!enabled) return
        val p = pool ?: return
        val id = ids[s] ?: return
        val duck = if (audio?.isMusicActive == true) 0.55f else 1f
        val v = (volume * duck).coerceIn(0f, 1f)
        p.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    // ---- Synthesis ----

    private const val RATE = 44_100

    /** A soft sine "glass" note: quick attack, exponential decay, a touch of 2nd harmonic for warmth. */
    private fun note(buf: FloatArray, startMs: Int, freq: Double, durMs: Int, tauMs: Double, amp: Float, glideTo: Double = freq) {
        val start = startMs * RATE / 1000
        val n = durMs * RATE / 1000
        var phase = 0.0
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / RATE
            val f = freq + (glideTo - freq) * (i.toDouble() / n)
            phase += 2 * PI * f / RATE
            val attack = (t / 0.003).coerceAtMost(1.0)
            val env = attack * exp(-t * 1000.0 / tauMs)
            buf[idx] += (amp * env * (sin(phase) + 0.15 * sin(2 * phase))).toFloat()
        }
    }

    private fun synth(s: S): FloatArray {
        val lenMs = when (s) { S.MOVE -> 60; S.SELECT -> 110; S.BACK -> 120; S.SUCCESS -> 380; S.INTRO -> 1600; else -> 260 }
        val b = FloatArray(lenMs * RATE / 1000)
        when (s) {
            S.MOVE -> note(b, 0, 1180.0, 55, 9.0, 0.32f)                                  // soft tick
            S.SELECT -> { note(b, 0, 520.0, 90, 26.0, 0.42f, glideTo = 780.0); note(b, 0, 2600.0, 8, 2.0, 0.08f) } // pop
            S.OPEN -> { note(b, 0, 880.0, 200, 55.0, 0.32f); note(b, 45, 1318.5, 200, 60.0, 0.28f) }               // chime
            S.BACK -> note(b, 0, 700.0, 110, 32.0, 0.38f, glideTo = 460.0)                                          // falling
            S.ON -> { note(b, 0, 784.0, 110, 32.0, 0.34f); note(b, 60, 1046.5, 160, 45.0, 0.34f) }                  // up
            S.OFF -> { note(b, 0, 1046.5, 110, 32.0, 0.34f); note(b, 60, 784.0, 160, 45.0, 0.34f) }                 // down
            S.SUCCESS -> { note(b, 0, 1046.5, 200, 55.0, 0.28f); note(b, 60, 1318.5, 200, 60.0, 0.28f); note(b, 120, 1568.0, 240, 80.0, 0.28f) }
            S.ERROR -> { note(b, 0, 330.0, 110, 40.0, 0.4f); note(b, 95, 262.0, 140, 45.0, 0.4f) }
            S.INTRO -> {
                // A warm, unhurried rising chord (G, C, E, G) with a soft low pad underneath.
                note(b, 0, 196.0, 1500, 520.0, 0.16f); note(b, 0, 196.6, 1500, 520.0, 0.12f)    // pad, slightly detuned
                note(b, 0, 784.0, 900, 260.0, 0.2f)
                note(b, 110, 1046.5, 1000, 300.0, 0.2f)
                note(b, 220, 1318.5, 1100, 340.0, 0.18f)
                note(b, 340, 1568.0, 1250, 420.0, 0.17f)
                note(b, 340, 3136.0, 600, 120.0, 0.03f)                                     // a little shimmer
            }
        }
        return b
    }

    private fun writeWav(f: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in samples) data.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.capacity()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.capacity())
        FileOutputStream(f).use { it.write(h.array()); it.write(data.array()) }
    }
}

/** Plays the "move" tick when this element gains focus. */
fun Modifier.focusSound(pitch: Float = 1f): Modifier = this.onFocusChanged { if (it.isFocused) Sfx.move(pitch) }
