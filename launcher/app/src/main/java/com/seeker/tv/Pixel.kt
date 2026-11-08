package com.seeker.tv

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.sin

/**
 * M8 Hunt's 8-bit renderer, ported from design/seeker-hunt-mockup-v4.html: indexed-color sprites
 * (PAL + per-sprite overrides), the 5x7 font drawn as square pixels with the half-cell drop shadow,
 * and backdrop(). Square cells, no anti-aliasing. Each sprite row is pre-split into runs of one color,
 * so a run is one drawRect instead of one per pixel (same pixels as the mockup's per-pixel fillRect).
 * Shared by HuntView and SettingsView's HUNT style.
 */
object Pixel {
    const val INK = 0xFF000000.toInt()
    const val PAPER = 0xFFFCFCFC.toInt()
    const val GRAY = 0xFF7C7C7C.toInt()
    const val SKY = 0xFF5C94FC.toInt()
    const val SKY_BAND = 0xFF6CA4FC.toInt()
    const val GRASS = 0xFF00A800.toInt()
    const val GRASSD = 0xFF007800.toInt()
    const val GROUND = 840f
    private const val W = 1920f
    private const val H = 1080f

    // mockup PAL: k black, w white, o orange (beak/feet), g/G greens, d brown, t tan, n nose gray
    private val PAL = mapOf(
        'k' to 0xFF000000.toInt(), 'w' to 0xFFFCFCFC.toInt(), 'o' to 0xFFF8A000.toInt(), 'g' to 0xFF00A800.toInt(),
        'G' to 0xFF00D800.toInt(), 'd' to 0xFF8C4800.toInt(), 't' to 0xFFD8A060.toInt(), 'n' to 0xFF3C3C3C.toInt(),
    )

    /** PAL with [over] on top (the mockup's `pal[ch] || PAL[ch]`), as a char-indexed table; 0 = no color. */
    fun pal(vararg over: Pair<Char, Int>): IntArray {
        val p = IntArray(128)
        for ((k, v) in PAL) p[k.code] = v
        for ((k, v) in over) p[k.code] = v
        return p
    }

    val BASE = pal()

    /** An indexed sprite: '.' transparent, any other char a palette index. Runs = (row, col, len, char). */
    class Sprite(rows: List<String>) {
        val w = rows[0].length
        val h = rows.size
        val runs: IntArray = run {
            val out = ArrayList<Int>()
            for ((r, row) in rows.withIndex()) {
                var c = 0
                while (c < row.length) {
                    val ch = row[c]
                    if (ch == '.') { c++; continue }
                    var e = c
                    while (e < row.length && row[e] == ch) e++
                    out += r; out += c; out += e - c; out += ch.code
                    c = e
                }
            }
            out.toIntArray()
        }
    }

    private val rect = Paint()  // no AA: square pixels

    fun sprite(c: Canvas, s: Sprite, px: Float, py: Float, cell: Float, pal: IntArray, flip: Boolean = false) {
        val r = s.runs
        var i = 0
        while (i < r.size) {
            val color = pal[r[i + 3]]
            if (color != 0) {
                val col = if (flip) s.w - r[i + 1] - r[i + 2] else r[i + 1]
                val x = px + col * cell
                val y = py + r[i] * cell
                rect.color = color
                c.drawRect(x, y, x + r[i + 2] * cell, y + cell, rect)
            }
            i += 4
        }
    }

    // ---------- the mockup's 5x7 font (F), plus its '>' ----------
    private val F_SRC = mapOf(
        'A' to "01110 10001 10001 11111 10001 10001 10001", 'B' to "11110 10001 10001 11110 10001 10001 11110",
        'C' to "01111 10000 10000 10000 10000 10000 01111", 'D' to "11110 10001 10001 10001 10001 10001 11110",
        'E' to "11111 10000 10000 11110 10000 10000 11111", 'F' to "11111 10000 10000 11110 10000 10000 10000",
        'G' to "01111 10000 10000 10111 10001 10001 01111", 'H' to "10001 10001 10001 11111 10001 10001 10001",
        'I' to "11111 00100 00100 00100 00100 00100 11111", 'J' to "00001 00001 00001 00001 10001 10001 01110",
        'K' to "10001 10010 10100 11000 10100 10010 10001", 'L' to "10000 10000 10000 10000 10000 10000 11111",
        'M' to "10001 11011 10101 10101 10001 10001 10001", 'N' to "10001 11001 10101 10011 10001 10001 10001",
        'O' to "01110 10001 10001 10001 10001 10001 01110", 'P' to "11110 10001 10001 11110 10000 10000 10000",
        'Q' to "01110 10001 10001 10001 10101 10010 01101", 'R' to "11110 10001 10001 11110 10100 10010 10001",
        'S' to "01111 10000 10000 01110 00001 00001 11110", 'T' to "11111 00100 00100 00100 00100 00100 00100",
        'U' to "10001 10001 10001 10001 10001 10001 01110", 'V' to "10001 10001 10001 10001 01010 01010 00100",
        'W' to "10001 10001 10001 10101 10101 11011 10001", 'X' to "10001 10001 01010 00100 01010 10001 10001",
        'Y' to "10001 10001 01010 00100 00100 00100 00100", 'Z' to "11111 00001 00010 00100 01000 10000 11111",
        '0' to "01110 10001 10011 10101 11001 10001 01110", '1' to "00100 01100 00100 00100 00100 00100 01110",
        '2' to "01110 10001 00001 00010 00100 01000 11111", '3' to "11111 00010 00100 00010 00001 10001 01110",
        '4' to "00010 00110 01010 10010 11111 00010 00010", '5' to "11111 10000 11110 00001 00001 10001 01110",
        '6' to "00111 01000 10000 11110 10001 10001 01110", '7' to "11111 00001 00010 00100 01000 01000 01000",
        '8' to "01110 10001 10001 01110 10001 10001 01110", '9' to "01110 10001 10001 01111 00001 00010 11100",
        ' ' to "00000 00000 00000 00000 00000 00000 00000", '+' to "00000 00100 00100 11111 00100 00100 00000",
        '-' to "00000 00000 00000 11111 00000 00000 00000", '!' to "00100 00100 00100 00100 00100 00000 00100",
        '.' to "00000 00000 00000 00000 00000 00000 00100", '>' to "10000 01000 00100 00010 00100 01000 10000",
    )
    private val GLYPH: Map<Char, Sprite> = F_SRC.mapValues { Sprite(it.value.split(' ').map { r -> r.replace('0', '.') }) }
    private val BLANK = GLYPH.getValue(' ')

    fun textW(t: String, cell: Float) = t.length * cell * 6 - cell

    /**
     * Mockup text(): square cells, optional drop shadow offset by half a cell. The mockup paints each
     * pixel's shadow then the pixel; a shadow only ever lands on pixels painted after it, so painting
     * every shadow first gives the same image.
     */
    fun text(c: Canvas, s: String, px: Float, py: Float, cell: Float, color: Int, shadow: Int = 0) {
        if (shadow != 0) glyphs(c, s, px + cell * 0.5f, py + cell * 0.5f, cell, shadow)
        glyphs(c, s, px, py, cell, color)
    }

    private fun glyphs(c: Canvas, s: String, px: Float, py: Float, cell: Float, color: Int) {
        rect.color = color
        var cx = px
        for (ch in s) {
            val r = (GLYPH[ch] ?: BLANK).runs
            var i = 0
            while (i < r.size) {
                val x = cx + r[i + 1] * cell
                val y = py + r[i] * cell
                c.drawRect(x, y, x + r[i + 2] * cell, y + cell, rect)
                i += 4
            }
            cx += cell * 6
        }
    }

    // ---------- scene (mockup TREE, BUSH, CLOUD, backdrop()) ----------
    private val TREE = Sprite(listOf(
        "......gggggg......", "....gggggggggg....", "...gggGGGGgggg....", "..ggGGGGGGGGggg...", "..gGGGGGGGGGGgg...",
        "..gGGGGGGGGGGgg...", "...gGGGGGGGGgg....", "....ggGGGGggg.....", "......gggg........", "........dd........",
        "........dd........", "........dd........", "........dd........", "......dddddd......",
    ))
    private val BUSH = Sprite(listOf("....ggggg....", "..gggGGGggg..", ".ggGGGGGGGgg.", "gggGGGGGGGggg", "gggggggggggg."))
    private val CLOUD = Sprite(listOf("....wwww.....", ".wwwwwwwwww..", "wwwwwwwwwwwww", "wwwwwwwwwwwww"))

    fun backdrop(c: Canvas, t: Int) {
        rect.color = SKY; c.drawRect(0f, 0f, W, GROUND, rect)
        // flat sky bands like an old CRT scene, then clouds, tree, bushes, grass
        rect.color = SKY_BAND; c.drawRect(0f, 0f, W, 180f, rect)
        sprite(c, CLOUD, 300f, 120 + sin(t / 90f) * 4, 8f, BASE)
        sprite(c, CLOUD, 1250f, 90 + sin(t / 70f + 2) * 4, 10f, BASE)
        sprite(c, TREE, 60f, GROUND - 14 * 18 + 6, 18f, BASE)
        sprite(c, BUSH, 1500f, GROUND - 5 * 14 + 4, 14f, BASE)
        sprite(c, BUSH, 1720f, GROUND - 5 * 12 + 4, 12f, BASE)
        rect.color = GRASS; c.drawRect(0f, GROUND, W, H, rect)
        rect.color = GRASSD
        var g = 0
        while (g < W) {
            val hgt = 12 + ((g * 7) % 4) * 6f
            c.drawRect(g.toFloat(), GROUND - hgt, g + 6f, GROUND, rect)
            val ty = GROUND + 60 + ((g * 3) % 5) * 20
            c.drawRect(g + 10f, ty, g + 16f, ty + 12, rect)
            g += 24
        }
        rect.color = 0xFF007800.toInt(); c.drawRect(0f, H - 90, W, H, rect)
    }

    /** The grass tufts along the ground line alone (mockup drawPop() redraws them in front of the dog). */
    fun fringe(c: Canvas) {
        rect.color = GRASSD
        var g = 0
        while (g < W) {
            val hgt = 12 + ((g * 7) % 4) * 6f
            c.drawRect(g.toFloat(), GROUND - hgt, g + 6f, GROUND, rect)
            g += 24
        }
    }
}
