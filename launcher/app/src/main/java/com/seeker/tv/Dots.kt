package com.seeker.tv

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

// Port of the mockup's dot-matrix renderer (design/seeker-tv-mockup-v3.html): same 5x7 font,
// same 9x9 icons, lit dot radius 0.36 * cell, unlit dot radius 0.7 of that.
object Dots {
    const val DOT = 0xFF2A2D33.toInt()   // unlit matrix dot
    const val FG = 0xFFE8E9EB.toInt()    // lit matrix, primary text
    const val MUTE = 0xFF70757E.toInt()  // idle app names
    const val DIM = 0xFF3B3F46.toInt()   // far app names
    const val SEL_GRID = 0x12FFFFFF      // rgba(255,255,255,.07)

    private val FONT_SRC = mapOf(
        'A' to "01110 10001 10001 11111 10001 10001 10001",
        'B' to "11110 10001 10001 11110 10001 10001 11110",
        'C' to "01111 10000 10000 10000 10000 10000 01111",
        'D' to "11110 10001 10001 10001 10001 10001 11110",
        'E' to "11111 10000 10000 11110 10000 10000 11111",
        'F' to "11111 10000 10000 11110 10000 10000 10000",
        'G' to "01111 10000 10000 10111 10001 10001 01111",
        'H' to "10001 10001 10001 11111 10001 10001 10001",
        'I' to "11111 00100 00100 00100 00100 00100 11111",
        'J' to "00001 00001 00001 00001 10001 10001 01110",
        'K' to "10001 10010 10100 11000 10100 10010 10001",
        'L' to "10000 10000 10000 10000 10000 10000 11111",
        'M' to "10001 11011 10101 10101 10001 10001 10001",
        'N' to "10001 11001 10101 10011 10001 10001 10001",
        'O' to "01110 10001 10001 10001 10001 10001 01110",
        'P' to "11110 10001 10001 11110 10000 10000 10000",
        'Q' to "01110 10001 10001 10001 10101 10010 01101",
        'R' to "11110 10001 10001 11110 10100 10010 10001",
        'S' to "01111 10000 10000 01110 00001 00001 11110",
        'T' to "11111 00100 00100 00100 00100 00100 00100",
        'U' to "10001 10001 10001 10001 10001 10001 01110",
        'V' to "10001 10001 10001 10001 01010 01010 00100",
        'W' to "10001 10001 10001 10101 10101 11011 10001",
        'X' to "10001 10001 01010 00100 01010 10001 10001",
        'Y' to "10001 10001 01010 00100 00100 00100 00100",
        'Z' to "11111 00001 00010 00100 01000 10000 11111",
        '0' to "01110 10001 10011 10101 11001 10001 01110",
        '1' to "00100 01100 00100 00100 00100 00100 01110",
        '2' to "01110 10001 00001 00010 00100 01000 11111",
        '3' to "11111 00010 00100 00010 00001 10001 01110",
        '4' to "00010 00110 01010 10010 11111 00010 00010",
        '5' to "11111 10000 11110 00001 00001 10001 01110",
        '6' to "00111 01000 10000 11110 10001 10001 01110",
        '7' to "11111 00001 00010 00100 01000 01000 01000",
        '8' to "01110 10001 10001 01110 10001 10001 01110",
        '9' to "01110 10001 10001 01111 00001 00010 11100",
        ' ' to "00000 00000 00000 00000 00000 00000 00000",
        ':' to "00000 00100 00100 00000 00100 00100 00000",
        '°' to "01100 10010 01100 00000 00000 00000 00000",
        '+' to "00000 00100 00100 11111 00100 00100 00000",
        '-' to "00000 00000 00000 11111 00000 00000 00000",
        '.' to "00000 00000 00000 00000 00000 00000 00100",
        '!' to "00100 00100 00100 00100 00100 00000 00100",
        '/' to "00001 00001 00010 00100 01000 10000 10000",
        '&' to "01100 10010 10100 01000 10101 10010 01101",
    )

    private val ICON_SRC = mapOf(
        "sun" to "000010000 010000010 000111000 001111100 101111101 001111100 000111000 010000010 000010000",
        "moon" to "000100000 001110000 011100000 011000000 111000000 011100000 001110000 000111000 000011000",
        "cloud" to "000000000 000011100 000111110 011111111 111111111 111111111 011111110 000000000 000000000",
        "rain" to "000011100 000111110 011111111 111111111 011111110 000000000 010010010 001001001 010010010",
        "snow" to "000011100 000111110 011111111 111111111 011111110 000000000 010010010 000000000 001001001",
        "storm" to "000011100 000111110 011111111 111111111 011111110 000011000 000110000 001111000 000010000",
        "gear" to "000010000 010111010 001111100 011101110 111000111 011101110 001111100 010111010 000010000",
        "bell" to "000010000 000111000 001111100 001111100 001111100 011111110 111111111 000000000 000111000",
    )

    private fun parse(src: String): Array<BooleanArray> =
        src.split(' ').map { row -> BooleanArray(row.length) { row[it] == '1' } }.toTypedArray()

    private val FONT: Map<Char, Array<BooleanArray>> = FONT_SRC.mapValues { parse(it.value) }
    private val ICONS: Map<String, Array<BooleanArray>> = ICON_SRC.mapValues { parse(it.value) }
    private val BLANK = FONT.getValue(' ')

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Uppercases and maps characters the font lacks to spaces, so names never show junk. */
    fun clean(s: String): String = buildString {
        for (ch in s.uppercase()) append(if (FONT.containsKey(ch)) ch else ' ')
    }.replace(Regex(" +"), " ").trim()

    fun textW(text: String, cell: Float): Float = text.length * cell * 6 - cell

    /** Multiplies a color's alpha by [opacity]. Dots never overlap, so this equals group opacity. */
    fun fade(color: Int, opacity: Float): Int =
        if (opacity >= 1f) color
        else Color.argb((Color.alpha(color) * opacity).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun text(
        c: Canvas, text: String, x: Float, y: Float, cell: Float, color: Int,
        grid: Boolean, gridColor: Int = DOT, opacity: Float = 1f, dotWeight: Float = 0.36f,
    ) {
        val r = cell * dotWeight
        val lit = fade(color, opacity)
        val unlit = fade(gridColor, opacity)
        var cx = x
        for (ch in text) {
            val g = FONT[ch] ?: BLANK
            for (row in 0 until 7) for (col in 0 until 5) {
                val on = g[row][col]
                if (!on && !grid) continue
                paint.color = if (on) lit else unlit
                c.drawCircle(cx + col * cell + cell / 2, y + row * cell + cell / 2, if (on) r else r * 0.7f, paint)
            }
            cx += cell * 6
        }
    }

    fun icon(c: Canvas, name: String, x: Float, y: Float, cell: Float, color: Int, grid: Boolean) {
        val g = ICONS[name] ?: return
        val r = cell * 0.36f
        for (row in 0 until 9) for (col in 0 until 9) {
            val on = g[row][col]
            if (!on && !grid) continue
            paint.color = if (on) color else DOT
            c.drawCircle(x + col * cell + cell / 2, y + row * cell + cell / 2, if (on) r else r * 0.7f, paint)
        }
    }
}
