package com.seeker.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * M6 sounds, synthesized (no asset files). The recipes are the SFX table in
 * design/seeker-invaders-mockup-v1.html, rendered the way its WebAudio graph plays them: an oscillator
 * whose frequency ramps exponentially f0 → f1 over the duration, through a gain that ramps exponentially
 * from the start gain to 0.0001; the hit noise is white noise fading linearly to 0 through a 900 Hz
 * low-pass. Rendered once at startup to 16-bit mono WAVs in cacheDir, then played with SoundPool.
 * [enabled] is Seeker settings > SOUND, for every mode.
 */
object Sfx {
    private const val TAG = "SeekerTV"
    private const val RATE = 44100

    enum class Id {
        MOVE, FIRE, HIT, MARCH_A, MARCH_B, METEOR,
        // M8 Hunt (design/seeker-hunt-mockup-v4.html SFX). The mockup plays miss 120 ms after the shot and the
        // yip / hit jingle when the dog is up (pop frame 20 = 333 ms). Here each shot is one buffer with those parts
        // at their offsets: on this TV a second AudioTrack opened shortly after the shot is refused (AudioFlinger
        // -32; v3 at +90 ms, v4 hit at +312 ms). HUNT_STEP and HUNT_FLAP are in the mockup's table but nothing in
        // v4 plays them.
        HUNT_LOCK, HUNT_SHOT_HIT, HUNT_SHOT_MISS, HUNT_STEP, HUNT_FLAP,
    }

    @Volatile var enabled = true
    private var pool: SoundPool? = null
    private val ids = IntArray(Id.values().size)
    private val ready = BooleanArray(Id.values().size)
    private var march = 0

    /** Renders and loads the buffers on a background thread; plays before they're loaded are dropped. */
    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        pool = p
        p.setOnLoadCompleteListener { _, sample, status ->
            val i = ids.indexOf(sample)
            if (i >= 0 && status == 0) ready[i] = true
            if (ready.all { it }) Log.i(TAG, "sfx ready (${ready.size} sounds)")
        }
        val dir = ctx.cacheDir
        Thread {
            try {
                for (id in Id.values()) {
                    val f = File(dir, "sfx_${id.name.lowercase()}.wav")
                    writeWav(f, render(id))
                    ids[id.ordinal] = p.load(f.path, 1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "sfx render failed", e)
            }
        }.start()
    }

    fun play(id: Id) {
        if (!enabled || !ready[id.ordinal]) return
        val s = pool?.play(ids[id.ordinal], 1f, 1f, 1, 0, 1f) ?: 0
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "sfx $id stream=$s")
    }

    /** Formation step: the two notes alternate (mockup SFX.march). */
    fun march() { play(if (march == 0) Id.MARCH_A else Id.MARCH_B); march = 1 - march }

    // ---------- the recipes (mockup SFX) ----------
    private fun render(id: Id): FloatArray = when (id) {
        Id.MOVE -> tone(Wave.SQUARE, 180f, 180f, 0.045f, 0.08f)
        Id.FIRE -> tone(Wave.SAW, 900f, 180f, 0.14f, 0.12f)
        Id.HIT -> mix(noise(0.35f, 0.5f, 900f), tone(Wave.SQUARE, 120f, 40f, 0.3f, 0.2f))
        Id.MARCH_A -> tone(Wave.SQUARE, 98f, 98f, 0.08f, 0.07f)
        Id.MARCH_B -> tone(Wave.SQUARE, 87f, 87f, 0.08f, 0.07f)
        Id.METEOR -> tone(Wave.TRIANGLE, 300f, 700f, 0.25f, 0.12f)
        // Hunt: the mockup's delayed parts are rendered into the same buffer at their offsets
        Id.HUNT_LOCK -> tone(Wave.SQUARE, 1200f, 1200f, 0.03f, 0.06f)
        Id.HUNT_SHOT_HIT -> mix(huntShot(), at(POP_UP, huntHit()))
        Id.HUNT_SHOT_MISS -> mix(mix(huntShot(), at(0.12f, huntMiss())), at(POP_UP, huntYip()))
        Id.HUNT_STEP -> tone(Wave.SQUARE, 90f, 70f, 0.03f, 0.04f)
        Id.HUNT_FLAP -> tone(Wave.SQUARE, 2200f, 1800f, 0.02f, 0.02f)
    }

    private const val POP_UP = 20f / 60  // the dog's frame 20, when the mockup plays the yip / hit jingle

    private fun huntYip() = mix(tone(Wave.SQUARE, 700f, 900f, 0.06f, 0.1f), at(0.08f, tone(Wave.SQUARE, 900f, 650f, 0.08f, 0.1f)))
    private fun huntShot() = mix(noise(0.22f, 0.6f, 2000f), tone(Wave.SQUARE, 160f, 50f, 0.18f, 0.25f))
    private fun huntMiss() = mix(tone(Wave.TRIANGLE, 420f, 300f, 0.12f, 0.12f), at(0.12f, tone(Wave.TRIANGLE, 300f, 200f, 0.18f, 0.12f)))
    private fun huntHit() = mix(
        tone(Wave.SQUARE, 523f, 523f, 0.07f, 0.12f),
        mix(at(0.07f, tone(Wave.SQUARE, 659f, 659f, 0.07f, 0.12f)), at(0.14f, tone(Wave.SQUARE, 784f, 784f, 0.12f, 0.12f))),
    )

    /** [buf] starting [delay] seconds in (the mockup's tone(..., delay)). */
    private fun at(delay: Float, buf: FloatArray): FloatArray {
        val pad = (RATE * delay).toInt()
        return FloatArray(pad + buf.size) { if (it < pad) 0f else buf[it - pad] }
    }

    private enum class Wave { SQUARE, SAW, TRIANGLE }

    private fun tone(w: Wave, f0: Float, f1: Float, dur: Float, gain: Float): FloatArray {
        val n = (RATE * dur).toInt()
        val out = FloatArray(n)
        val end = max(20f, f1)
        var ph = 0.0
        for (i in 0 until n) {
            val k = i.toDouble() / n
            val f = f0 * (end / f0).toDouble().pow(k)
            val g = gain * (0.0001 / gain).pow(k)
            val fr = ph - floor(ph)
            val v = when (w) {
                Wave.SQUARE -> if (fr < 0.5) 1.0 else -1.0
                Wave.SAW -> 2 * fr - 1
                Wave.TRIANGLE -> 1 - 4 * abs(fr - 0.5)
            }
            out[i] = (v * g).toFloat()
            ph += f / RATE
        }
        return out
    }

    /** White noise fading to 0, through a WebAudio-style low-pass ([cutoff] Hz, Q 1 dB), times [gain]. */
    private fun noise(dur: Float, gain: Float, cutoff: Float): FloatArray {
        val n = (RATE * dur).toInt()
        val w0 = 2 * PI * cutoff / RATE
        val q = 10.0.pow(1.0 / 20)
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        val b0 = (1 - cos(w0)) / 2 / a0
        val b1 = (1 - cos(w0)) / a0
        val a1 = -2 * cos(w0) / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        val out = FloatArray(n)
        for (i in 0 until n) {
            val x = (Math.random() * 2 - 1) * (1 - i.toDouble() / n)
            val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            out[i] = (y * gain).toFloat()
        }
        return out
    }

    private fun mix(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(max(a.size, b.size)) { (a.getOrElse(it) { 0f } + b.getOrElse(it) { 0f }) }

    private fun writeWav(f: File, pcm: FloatArray) {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (v in pcm) b.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        FileOutputStream(f).use { it.write(b.array()) }
    }
}
