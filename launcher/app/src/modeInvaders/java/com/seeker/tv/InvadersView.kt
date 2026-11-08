package com.seeker.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Seeker Invaders (M6): HOME MODE = INVADERS. START screen, OK starts and fires, LEFT/RIGHT step the
 * ship one tile. Shoot a critter and that service opens; shoot the meteor and Seeker settings open.
 * Sprites, layout, formation drift, bullet, burst, takeover, screens and sounds are ported 1:1 from
 * design/seeker-invaders-mockup-v1.html (frame-based at 60 fps, 1920x1080 reference pixels). Same
 * fixed-60 Hz Choreographer loop as FlapView.
 */
@SuppressLint("ViewConstructor")
class InvadersView(ctx: Context, private val host: GameHost) : View(ctx), GameScreen {


    private companion object {
        const val W = 1920f
        const val H = 1080f
        const val TILE = 64f
        const val COL0 = 160f
        const val COLS = 26
        const val SHIP_COL0 = 12
        const val SHIPY = 900f
        const val SC = 6f
        const val SW = 13 * SC
        const val SH = 7 * SC
        const val INV_Y = 250f
        const val SPACING = 170f
        const val INV0 = 200f
        const val INV_CELL = 7f
        const val FORM_RANGE = 110f
        const val FORM_STEP = 22f
        const val FORM_EVERY = 48
        const val METEOR_X = 1700f
        const val METEOR_Y = 150f
        const val METEOR_CELL = 7f
        const val ROCK_C = 0xFF8F969F.toInt()
        const val LAUNCH_AT = 54   // frames: the intent fires ~900 ms into the hit, like the wheel and Flap
        const val CRASH_END = 130  // frames: back to START if nothing launched (mockup)
        const val TO_SETTINGS = 16 // frames: meteor burst, then settings (mockup)
        const val STEP_NS = 1_000_000_000L / 60
        const val BG = 0xFF0B0C0E.toInt()
        const val GRID_DOT = 0xFF16181C.toInt()
        const val GRID_TOP = 48f   // mockup grid rows start at y=60

        fun bm(rows: List<String>) = rows.map { row -> BooleanArray(row.length) { row[it] == '1' } }

        // our own critters: three species, two frames each
        val CRIT = listOf(
            listOf(
                bm(listOf("00100000100", "00010001000", "00111111100", "01101110110", "11111111111", "10111111101", "10100000101", "00011011000")),
                bm(listOf("00100000100", "00010001000", "00111111100", "01101110110", "11111111111", "00111111100", "01000000010", "10000000001")),
            ),
            listOf(
                bm(listOf("00011000", "00111100", "01111110", "11011011", "11111111", "00100100", "01011010", "10100101")),
                bm(listOf("00011000", "00111100", "01111110", "11011011", "11111111", "01011010", "10000001", "01000010")),
            ),
            listOf(
                bm(listOf("000011110000", "011111111110", "111111111111", "111001100111", "111111111111", "000110011000", "001100001100", "110000000011")),
                bm(listOf("000011110000", "011111111110", "111111111111", "111001100111", "111111111111", "001101101100", "010000000010", "001000000100")),
            ),
        )
        val SHIP = bm(listOf("0000001000000", "0000011100000", "0000011100000", "0111111111110", "1111111111111", "1111111111111", "1111111111111"))
        val ROCK = bm(listOf("000111100000", "001111111000", "011111111100", "111101111110", "111111111111", "111111011111", "011111111110", "001111111100", "000111111000", "000001100000"))
    }

    private class Box(val x: Float, val y: Float, val w: Float, val h: Float, val cx: Float, val cy: Float)
    private class Bullet(val x: Float, var y: Float)
    private class Boom(val x: Float, val y: Float, val c: Int, var t: Int = 0)
    private class Hit(val app: GameApp, val i: Int, val x: Float, val y: Float)

    private enum class Mode { START, PLAY, CRASH, TO_SETTINGS }

    // ---------- game state (mockup names) ----------
    private var mode = Mode.START
    private var t = 0
    private var shipCol = SHIP_COL0
    private var formX = 0f
    private var formDir = 1
    private var frameA = 0
    private var bullet: Bullet? = null
    private var boom: Boom? = null
    private var hit: Hit? = null
    private var hitT = 0
    private var shots = 0
    private var launched = false
    private var apps = GameApps.ALL

    // ---------- loop ----------
    private var running = false
    private var lastNs = 0L
    private var acc = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            if (lastNs != 0L) acc += min(now - lastNs, 100_000_000L)
            lastNs = now
            var n = 0
            while (acc >= STEP_NS - 2_000_000L && n < 4) { step(); acc -= STEP_NS; n++ }
            if (n == 4) acc = 0
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---------- paints ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // Opaque tile with the background baked in, like Flap: the only full-screen fill.
    private val gridPaint = Paint().apply {
        val tile = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        tile.eraseColor(BG)
        Canvas(tile).drawCircle(12f, 12f, 1.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID_DOT })
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setTranslate(12f, 12f) })
        }
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, 500, false)
        textSize = 22f
        letterSpacing = 0.3f
        textAlign = Paint.Align.CENTER
    }
    private var firstFrameSent = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        reset()
    }

    // ---------- public API ----------
    /** Coming back HOME, from an app or from settings: always the START screen, never mid-game. */
    override fun toStart() {
        mode = Mode.START
        reset()
        invalidate()
    }

    /** Critters are the spec's eight services that are installed (all eight if none resolve). */
    override fun refreshApps() { apps = GameApps.installed(host::flapEntry) }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !running) {
            running = true
            lastNs = 0L
            acc = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        } else if (!isVisible && running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
        }
    }

    // ---------- game (mockup step()) ----------
    private fun reset() {
        bullet = null; boom = null; hit = null; hitT = 0; shots = 0; shipCol = SHIP_COL0; formX = 0f; formDir = 1; launched = false
    }

    private fun invBox(i: Int): Box {
        val sp = CRIT[i % 3][0]
        val w = sp[0].size * INV_CELL
        val h = sp.size * INV_CELL
        val cx = INV0 + i * SPACING + formX
        return Box(cx - w / 2, INV_Y - h / 2, w, h, cx, INV_Y)
    }

    private fun meteorBox(): Box {
        val w = ROCK[0].size * METEOR_CELL
        val h = ROCK.size * METEOR_CELL
        return Box(METEOR_X - w / 2, METEOR_Y - h / 2 + sin(t / 40f) * 6, w, h, METEOR_X, METEOR_Y)
    }

    private fun shipX() = COL0 + shipCol * TILE

    private fun Bullet.inside(b: Box) = x > b.x - 6 && x < b.x + b.w + 6 && y < b.y + b.h && y + 26 > b.y

    private fun step() {
        t++
        if (mode == Mode.PLAY || mode == Mode.START) {
            if (t % FORM_EVERY == 0) {
                formX += formDir * FORM_STEP
                if (formX > FORM_RANGE || formX < -FORM_RANGE) { formDir = -formDir; formX += formDir * FORM_STEP }
                frameA = 1 - frameA
                if (mode == Mode.PLAY) Sfx.march()
            }
        }
        if (mode == Mode.PLAY) {
            bullet?.let { it.y -= 16; if (it.y < -30) bullet = null }
            bullet?.let { b ->
                var hb: Box? = null
                var hi = -1
                for (i in apps.indices) { val box = invBox(i); if (b.inside(box)) { hb = box; hi = i; break } }
                val m = meteorBox()
                if (hb == null && b.inside(m)) {
                    bullet = null; boom = Boom(m.cx, m.cy, ROCK_C); Sfx.play(Sfx.Id.METEOR); mode = Mode.TO_SETTINGS; hitT = 0
                } else if (hb != null) {
                    bullet = null; boom = Boom(hb.cx, hb.cy, apps[hi].c); hit = Hit(apps[hi], hi, hb.cx, hb.cy); hitT = 0
                    Sfx.play(Sfx.Id.HIT); mode = Mode.CRASH
                }
            }
        }
        boom?.let { it.t++; if (it.t > 14) boom = null }
        if (mode == Mode.CRASH) {
            hitT++
            if (hitT == LAUNCH_AT) {
                val entry = hit?.let { host.flapEntry(it.app.pkg) }
                if (entry != null) { launched = true; host.launch(entry) }
            }
            // A launched app covers us and onStop resets to START; MainActivity's 4 s fallback covers a no-show.
            if (hitT > CRASH_END && !launched) toStart()
        }
        if (mode == Mode.TO_SETTINGS) {
            hitT++
            if (hitT > TO_SETTINGS) { toStart(); host.openSettings() }
        }
    }

    // ---------- remote ----------
    override fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        val handled = isCenter || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!handled) return false
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true  // mockup ignores key repeat
        when (mode) {
            Mode.START -> if (isCenter) { reset(); mode = Mode.PLAY; Sfx.play(Sfx.Id.MOVE) }
            Mode.PLAY -> when {
                code == KeyEvent.KEYCODE_DPAD_LEFT -> if (shipCol > 0) { shipCol--; Sfx.play(Sfx.Id.MOVE) }
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> if (shipCol < COLS - 1) { shipCol++; Sfx.play(Sfx.Id.MOVE) }
                isCenter -> if (bullet == null) { bullet = Bullet(shipX(), SHIPY - 20); shots++; Sfx.play(Sfx.Id.FIRE) }
                code == KeyEvent.KEYCODE_BACK -> toStart()
            }
            Mode.CRASH, Mode.TO_SETTINGS -> Unit
        }
        return true
    }

    // ---------- drawing (mockup draw*()) ----------
    override fun isOpaque() = true

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        drawBackdrop(canvas)
        when (mode) {
            Mode.START -> drawStart(canvas)
            else -> {
                drawInvaders(canvas)
                drawShip(canvas)
                drawBullet(canvas)
                drawBoom(canvas)
                if (mode == Mode.CRASH) drawTakeover(canvas)
            }
        }
        canvas.restore()
        if (!firstFrameSent) { firstFrameSent = true; post { host.firstFrame() } }
    }

    private fun bitmap(c: Canvas, b: List<BooleanArray>, px: Float, py: Float, cell: Float, color: Int) {
        val r = cell * 0.42f
        fill.color = color
        for (row in b.indices) for (col in b[row].indices) if (b[row][col]) {
            c.drawCircle(px + col * cell + cell / 2, py + row * cell + cell / 2, r, fill)
        }
    }

    private fun text(c: Canvas, t: String, x: Float, y: Float, cell: Float, color: Int, grid: Boolean) =
        Dots.text(c, t, x, y, cell, color, grid)

    private fun drawBackdrop(c: Canvas) {
        rect.color = BG
        c.drawRect(0f, 0f, W, GRID_TOP, rect)
        c.drawRect(0f, GRID_TOP, W, H, gridPaint)
        // tile rail the ship rides on
        fill.color = Dots.DIM
        for (col in 0 until COLS) c.drawCircle(COL0 + col * TILE, SHIPY + SH + 26, 3f, fill)
        Rainbow.draw(c, full = mode == Mode.PLAY || mode == Mode.CRASH, t = t, style = Rainbow.INVADERS)
    }

    private fun drawInvaders(c: Canvas) {
        val h = hit
        for ((i, a) in apps.withIndex()) {
            if (h != null && h.app === a) continue
            val b = invBox(i)
            bitmap(c, CRIT[i % 3][frameA], b.x, b.y, INV_CELL, a.c)
            val cell = 3.5f
            text(c, a.n, b.cx - Dots.textW(a.n, cell) / 2, b.y + b.h + 22, cell, Dots.MUTE, false)
        }
        val m = meteorBox()
        bitmap(c, ROCK, m.x, m.y, METEOR_CELL, ROCK_C)
        val cell = 3.5f
        text(c, "SETTINGS", m.cx - Dots.textW("SETTINGS", cell) / 2, m.y + m.h + 22, cell, Dots.MUTE, false)
    }

    private fun drawShip(c: Canvas) = bitmap(c, SHIP, shipX() - SW / 2, SHIPY, SC, Dots.FG)

    private fun drawBullet(c: Canvas) {
        val b = bullet ?: return
        rect.color = Dots.FG
        c.drawRect(b.x - 3, b.y, b.x + 3, b.y + 26, rect)
    }

    private fun drawBoom(c: Canvas) {
        val b = boom ?: return
        val k = b.t / 14f
        val r = 20 + k * 70
        line.color = Dots.fade(b.c, 1 - k)
        line.strokeWidth = 6 * (1 - k)
        for (i in 0 until 8) {
            val ang = i * PI / 4
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            c.drawLine(b.x + ca * r * 0.4f, b.y + sa * r * 0.4f, b.x + ca * r, b.y + sa * r, line)
        }
    }

    private fun drawStart(c: Canvas) {
        val title = "SEEKER INVADERS"
        val cell = 14f
        text(c, title, (W - Dots.textW(title, cell)) / 2, 300f, cell, Dots.FG, true)
        if (floor(t / 30f).toInt() % 2 == 0) {
            val s = "START"
            val sc = 10f
            text(c, s, (W - Dots.textW(s, sc)) / 2, 560f, sc, Dots.FG, true)
        }
        val hint = "SHOOT A CRITTER TO OPEN IT"
        val hc = 4f
        text(c, hint, (W - Dots.textW(hint, hc)) / 2, 700f, hc, Dots.DIM, false)
        for (i in apps.indices) { val b = invBox(i); bitmap(c, CRIT[i % 3][frameA], b.x, b.y - 100, 5f, Dots.DIM) }
        drawShip(c)
    }

    private fun drawTakeover(c: Canvas) {
        val h = hit ?: return
        val app = h.app
        val k = min(1f, hitT / 22f)
        val r = k * k * 2300
        fill.color = app.c
        c.drawCircle(h.x, h.y, r, fill)
        if (hitT > 20) {
            val n = app.n
            val cell = if (n.length > 7) 18f else 24f
            val w = Dots.textW(n, cell)
            val faint = if (app.ink == GameApps.INK_WHITE) 0x38FFFFFF else 0x2E000000  // rgba(255,255,255,.22) / rgba(0,0,0,.18)
            bitmap(c, CRIT[h.i % 3][floor(t / 12f).toInt() % 2], (W - 11 * 9) / 2, 540 - 7 * cell / 2 - 120, 9f, app.ink)
            Dots.text(c, n, (W - w) / 2, 540 - 7 * cell / 2, cell, app.ink, true, faint)
            monoPaint.color = app.ink
            c.drawText("OPENING", 960f, 540 + 7 * cell / 2 + 70, monoPaint)
            val s = "SHOTS $shots"
            val sc = 6f
            Dots.text(c, s, (W - Dots.textW(s, sc)) / 2, 540 + 7 * cell / 2 + 120, sc, app.ink, false)
        }
    }
}
