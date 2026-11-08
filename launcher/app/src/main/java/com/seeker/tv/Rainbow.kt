package com.seeker.tv

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import kotlin.math.abs

/**
 * Flap mode's rainbow strip (FLAP-SPEC "Rainbow strip"): it drives bias lighting behind the TV from the top
 * edge of the screen. Ported exactly from rainbow() in design/seeker-flap-mockup-v2.html:
 * three rows of 16 px dots (radius cell*0.34), hue = (gx/W)*360 + t*speed + row*12 with gx the cell's
 * left edge, plus a glow in 24 px columns (hue (gx2/W)*360 + t*speed, always 100% / 55%) fading to 0
 * over 220 px below the strip.
 * FULL (play, crash): 1.6 deg/frame, 100% sat, 55% light, glow 0.28.
 * CALM (START, settings): 0.5 deg/frame, 70% sat, 42% light, glow 0.12.
 * [t] is the caller's frame counter, like the mockup's (FlapView's game t resets each game).
 * M6: Invaders' mockup runs the same recipe with its own numbers ([INVADERS]): FULL 1.2 deg/frame,
 * glow 0.22 (CALM 0.10) over 200 px.
 */
object Rainbow {
    private const val W = 1920f
    private const val CELL = 16
    private const val ROWS = 3
    private const val GLOW_COL = 24
    private const val GLOW_COLS = 1920 / GLOW_COL  // 80

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint()  // no bitmap filtering: each 24 px column is one flat hue, like the mockup
    // One pixel per glow column, stretched 24x across: the 80 per-column gradients in one draw.
    private val glowCols = Bitmap.createBitmap(GLOW_COLS, 1, Bitmap.Config.ARGB_8888)
    private val glowPx = IntArray(GLOW_COLS)
    private val colShader = BitmapShader(glowCols, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply { setScale(GLOW_COL.toFloat(), 1f) })
    }

    /** One mockup's numbers: FULL hue speed, glow height and the two glow opacities. */
    class Style(val fullSpeed: Float, val glowH: Float, full: Float, calm: Float) {
        val glowFull = ComposeShader(colShader, fade(full, glowH), PorterDuff.Mode.DST_IN)
        val glowCalm = ComposeShader(colShader, fade(calm, glowH), PorterDuff.Mode.DST_IN)
    }
    val FLAP = Style(1.6f, 220f, 0.28f, 0.12f)
    val INVADERS = Style(1.2f, 200f, 0.22f, 0.10f)

    private fun fade(a: Float, h: Float) = LinearGradient(
        0f, ROWS * CELL.toFloat(), 0f, ROWS * CELL + h,
        Color.argb(Math.round(a * 255), 0, 0, 0), 0, Shader.TileMode.CLAMP,
    )

    /** Draws the strip in 1920x1080 reference space. */
    fun draw(c: Canvas, full: Boolean, t: Int, style: Style = FLAP) {
        val speed = if (full) style.fullSpeed else 0.5f
        val sat = if (full) 1f else 0.7f
        val lum = if (full) 0.55f else 0.42f
        val r = CELL * 0.34f
        for (row in 0 until ROWS) {
            var gx = 0
            while (gx < W) {
                dot.color = hsl((gx / W) * 360 + t * speed + row * 12, sat, lum)
                c.drawCircle(gx + CELL / 2f, row * CELL + CELL / 2f, r, dot)
                gx += CELL
            }
        }
        // soft glow bleeding down from the strip
        for (i in 0 until GLOW_COLS) glowPx[i] = hsl((i * GLOW_COL / W) * 360 + t * speed, 1f, 0.55f)
        glowCols.setPixels(glowPx, 0, GLOW_COLS, 0, 0, GLOW_COLS, 1)
        glow.shader = if (full) style.glowFull else style.glowCalm
        c.drawRect(0f, ROWS * CELL.toFloat(), W, ROWS * CELL + style.glowH, glow)
    }

    /** CSS hsl(): h in degrees (any range), s and l 0..1. */
    private fun hsl(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val ch = (1 - abs(2 * l - 1)) * s
        val x = ch * (1 - abs(hh % 2 - 1))
        val m = l - ch / 2
        val sector = hh.toInt()  // no Triple here: this runs ~440 times a frame
        val r = when (sector) { 0, 5 -> ch; 1, 4 -> x; else -> 0f }
        val g = when (sector) { 1, 2 -> ch; 0, 3 -> x; else -> 0f }
        val b = when (sector) { 3, 4 -> ch; 2, 5 -> x; else -> 0f }
        return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255))
    }
}
