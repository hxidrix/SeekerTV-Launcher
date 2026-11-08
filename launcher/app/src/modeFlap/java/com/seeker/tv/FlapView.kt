package com.seeker.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Seeker Flap (M5): HOME MODE = FLAP. START screen, OK flaps, crash into a tube and that service opens.
 * Bird, tubes, physics, screens and timings are ported 1:1 from design/seeker-flap-mockup-v2.html
 * (frame-based at 60 fps, 1920x1080 reference pixels). The game steps on Choreographer frames with a
 * fixed 60 Hz timestep, so physics stay true even if a frame is dropped.
 */
@SuppressLint("ViewConstructor")
class FlapView(ctx: Context, private val host: GameHost) : View(ctx), GameScreen {


    private companion object {
        const val W = 1920f
        const val H = 1080f
        const val GROUND = 960f
        const val BX = 420f
        const val BW = 11 * 7f
        const val BH = 8 * 7f
        // tuned 2026-10-21 for TV latency (FLAP-SPEC)
        const val GRAV = 0.40f
        const val FLAP = -10.2f
        const val SPEED = 4.8f
        const val GAP = 370f
        const val PW = 150f
        const val SPAWN = 125      // frames between tubes
        const val LAUNCH_AT = 54   // frames: the intent fires ~900 ms into the crash, like the wheel
        const val CRASH_END = 130  // frames: back to START if nothing launched (mockup)
        const val STEP_NS = 1_000_000_000L / 60
        const val BG = 0xFF0B0C0E.toInt()
        const val GRID_DOT = 0xFF16181C.toInt()
        const val FLOOR_BG = 0xFF0E0F12.toInt()
        const val EDGE = 0x47000000         // rgba(0,0,0,.28)
        const val ICON = 72                 // v2: the app's own icon on each cap, 72 px, rounded
        const val ICON_R = 16f

        // our own bird, 11x8, two frames (wing up / down)
        val BIRD = listOf(
            listOf("00001111000", "00011111100", "00111011110", "01111111111", "11111111100", "01111111000", "00011110000", "00001100000"),
            listOf("00001111000", "00011111100", "00111011110", "11111111111", "01111111100", "00111111000", "00011110000", "00001100000"),
        ).map { f -> f.map { row -> BooleanArray(row.length) { row[it] == '1' } } }

        // Shuffle bag (mockup nextApp()): lives with the process, not the view, so it carries across games.
        val bag = ArrayList<GameApp>()
        var lastApp: GameApp? = null
        val icons = HashMap<String, Bitmap?>()  // rounded 72 px icons by package
    }

    private class Pipe(var x: Float, val gy: Float, val app: GameApp, var passed: Boolean = false)

    private enum class Mode { START, PLAY, CRASH }

    // ---------- game state (mockup names) ----------
    private var mode = Mode.START
    private var menuSel = 0
    private var by = 480f
    private var vy = 0f
    private val pipes = ArrayList<Pipe>()
    private var score = 0
    private var best = host.flapBest()
    private var t = 0
    private var lastSpawn = -SPAWN
    private var crash: GameApp? = null
    private var crashT = 0
    private var wing = 0
    private var launched = false
    private var tubes = GameApps.ALL

    // ---------- loop ----------
    private var running = false
    private var lastNs = 0L
    private var acc = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            if (lastNs != 0L) acc += min(now - lastNs, 100_000_000L)
            lastNs = now
            // 2 ms slack so vsync jitter doesn't turn one step into zero-then-two
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
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // Opaque tile (background baked in): the grid is the only full-screen fill, no view background under it.
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
    /** Coming back HOME, or the launch never happened: always the START screen, never mid-game. */
    override fun toStart() {
        mode = Mode.START
        menuSel = 0
        reset()
        invalidate()
    }

    /** Tubes are the spec's eight services that are installed (all eight if none resolve). */
    override fun refreshApps() = refreshTubes()

    fun refreshTubes() {
        val now = GameApps.installed(host::flapEntry)
        if (now != tubes) { tubes = now; bag.clear() }  // keep the bag unless the set of services changed
        for (tb in tubes) if (tb.pkg !in icons) icons[tb.pkg] = host.flapEntry(tb.pkg)?.let { host.flapIcon(it) }?.let(::rounded)
    }

    private fun rounded(d: Drawable): Bitmap? = try {
        val sq = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, ICON, ICON)
        d.draw(Canvas(sq))
        val out = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawRoundRect(RectF(0f, 0f, ICON.toFloat(), ICON.toFloat()), ICON_R, ICON_R, p)
        sq.recycle()
        out
    } catch (e: Exception) {
        null
    }

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
        by = 480f; vy = 0f; pipes.clear(); score = 0; t = 0; lastSpawn = -SPAWN; crash = null; launched = false
    }

    // shuffle bag: every service appears once before any repeats, in a fresh random order each cycle
    private fun nextApp(): GameApp {
        if (bag.isEmpty()) {
            bag.addAll(tubes)
            for (i in bag.size - 1 downTo 1) {
                val j = floor(Math.random() * (i + 1)).toInt()
                val tmp = bag[i]; bag[i] = bag[j]; bag[j] = tmp
            }
            if (bag[bag.size - 1] === lastApp) { val t0 = bag[0]; bag[0] = bag[bag.size - 1]; bag[bag.size - 1] = t0 }
        }
        val app = bag.removeAt(bag.size - 1)
        lastApp = app
        return app
    }

    private fun spawn() {
        val app = nextApp()
        if (Log.isLoggable("SeekerTV", Log.DEBUG)) Log.d("SeekerTV", "flap tube ${app.n} (bag ${bag.size} left)")
        val gy = 220f + (Math.random() * (GROUND - 220f - GAP - 120f)).toFloat()
        pipes.add(Pipe(W + 40, gy, app))
    }

    private fun flap() { vy = FLAP; wing = 6 }

    private fun step() {
        t++
        if (wing > 0) wing--
        if (mode == Mode.START) { by = 480f + sin(t / 14f) * 14f; vy = 0f }
        if (mode == Mode.PLAY) {
            vy += GRAV; by += vy
            if (t - lastSpawn >= SPAWN) { spawn(); lastSpawn = t }
            for (p in pipes) { p.x -= SPEED; if (!p.passed && p.x + PW < BX) { p.passed = true; score++ } }
            pipes.removeAll { it.x <= -PW - 40 }
            var hit: GameApp? = null
            for (p in pipes) {
                val ox = BX + BW > p.x - 12 && BX < p.x + PW + 12
                if (ox && (by < p.gy || by + BH > p.gy + GAP)) { hit = p.app; break }
            }
            if (hit == null && (by + BH > GROUND || by < 0)) {
                // floor or ceiling: the next tube ahead
                hit = pipes.firstOrNull { it.x + PW > BX }?.app ?: tubes[(Math.random() * tubes.size).toInt()]
            }
            if (hit != null) {
                if (score > best) { best = score; host.saveFlapBest(best) }
                crash = hit; crashT = 0; mode = Mode.CRASH
            }
        }
        if (mode == Mode.CRASH) {
            crashT++
            if (crashT == LAUNCH_AT) {
                val entry = crash?.let { host.flapEntry(it.pkg) }
                if (entry != null) { launched = true; host.launch(entry) }
            }
            // A launched app covers us and onStop resets to START; MainActivity's 4 s fallback covers a no-show.
            if (crashT > CRASH_END && !launched) toStart()
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
            Mode.START -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> menuSel = 1
                KeyEvent.KEYCODE_DPAD_UP -> menuSel = 0
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                else -> if (menuSel == 0) { reset(); mode = Mode.PLAY; flap() } else host.openSettings()
            }
            Mode.PLAY -> if (isCenter) flap() else if (code == KeyEvent.KEYCODE_BACK) toStart()
            Mode.CRASH -> Unit
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
                for (p in pipes) drawPipe(canvas, p)
                drawBird(canvas)
                drawScore(canvas)
                if (mode == Mode.CRASH) drawCrash(canvas)
            }
        }
        canvas.restore()
        if (!firstFrameSent) { firstFrameSent = true; post { host.firstFrame() } }
    }

    private fun drawBackdrop(c: Canvas) {
        c.drawRect(0f, 0f, W, GROUND, gridPaint)
        rect.color = BG
        c.drawRect(0f, GROUND, W, GROUND + 20, rect)
        // ground: a dotted rail that scrolls
        val off = (t * SPEED) % 40
        fill.color = Dots.DIM
        var g = -off
        while (g < W) { c.drawCircle(g, GROUND + 8, 4f, fill); g += 40 }
        rect.color = FLOOR_BG
        c.drawRect(0f, GROUND + 20, W, H, rect)
        Rainbow.draw(c, full = mode != Mode.START, t = t)  // in the backdrop, like the mockup: tubes and takeover cover it
    }

    private fun drawPipe(c: Canvas, p: Pipe) {
        val top = p.gy
        val bot = p.gy + GAP
        rect.color = p.app.c
        c.drawRect(p.x, 0f, p.x + PW, top, rect)
        c.drawRect(p.x, bot, p.x + PW, GROUND, rect)
        // caps
        c.drawRect(p.x - 12, top - 44, p.x + PW + 12, top, rect)
        c.drawRect(p.x - 12, bot, p.x + PW + 12, bot + 44, rect)
        // dark edge for depth
        rect.color = EDGE
        c.drawRect(p.x + PW - 26, 0f, p.x + PW, top - 44, rect)
        c.drawRect(p.x + PW - 26, bot + 44, p.x + PW, GROUND, rect)
        // the app's own icon, centered on each cap at the mouth of the gap (v2, TV only)
        icons[p.app.pkg]?.let { ic ->
            val ix = p.x + (PW - ICON) / 2
            c.drawBitmap(ic, ix, top - 22 - ICON / 2, iconPaint)
            c.drawBitmap(ic, ix, bot + 22 - ICON / 2, iconPaint)
        }
        // name runs along the tube like a spine, on whichever half is taller, shrunk to fit; never past the screen edge
        val name = p.app.n
        val topH = top - 44
        val botH = GROUND - bot - 44
        val onTop = topH >= botH
        val avail = (if (onTop) topH else botH) - 44
        var cell = 6f
        while (cell > 3.5f && Dots.textW(name, cell) > avail) cell -= 0.5f
        if (Dots.textW(name, cell) > avail) return
        val w = Dots.textW(name, cell)
        val h = 7 * cell
        c.save()
        c.translate(p.x + PW / 2, if (onTop) top - 44 - 22 else bot + 44 + 22)
        c.rotate(-90f)
        Dots.text(c, name, if (onTop) 0f else -w, -h / 2, cell, p.app.ink, false)
        c.restore()
    }

    private fun drawBird(c: Canvas) {
        val bm = BIRD[if (wing > 0) 1 else 0]
        c.save()
        c.translate(BX + BW / 2, by + BH / 2)
        c.rotate(Math.toDegrees(max(-0.5f, min(0.9f, vy / 14f)).toDouble()).toFloat())
        val cell = 7f
        val r = cell * 0.42f
        fill.color = Dots.FG
        for (row in bm.indices) for (col in bm[row].indices) if (bm[row][col]) {
            c.drawCircle(-BW / 2 + col * cell + cell / 2, -BH / 2 + row * cell + cell / 2, r, fill)
        }
        c.restore()
    }

    /** Score sits at y=110 to clear the rainbow strip (spec). */
    private fun drawScore(c: Canvas) {
        val s = score.toString()
        val cell = 12f
        Dots.text(c, s, (W - Dots.textW(s, cell)) / 2, 110f, cell, Dots.FG, true)
    }

    private fun drawStart(c: Canvas) {
        val title = "SEEKER FLAP"
        val cell = 16f
        Dots.text(c, title, (W - Dots.textW(title, cell)) / 2, 300f, cell, Dots.FG, true)
        // blinking START, old-school
        if (floor(t / 30f).toInt() % 2 == 0 || menuSel == 1) {
            val s = "START"
            val sc = 10f
            Dots.text(c, s, (W - Dots.textW(s, sc)) / 2, 560f, sc, if (menuSel == 0) Dots.FG else Dots.MUTE, menuSel == 0)
        }
        val g = "SETTINGS"
        val gc = 7f
        Dots.text(c, g, (W - Dots.textW(g, gc)) / 2, 700f, gc, if (menuSel == 1) Dots.FG else Dots.DIM, menuSel == 1)
        if (best > 0) {
            val b = "BEST $best"
            val bc = 5f
            Dots.text(c, b, (W - Dots.textW(b, bc)) / 2, 820f, bc, Dots.MUTE, false)
        }
        drawBird(c)
    }

    private fun drawCrash(c: Canvas) {
        val app = crash ?: return
        val k = min(1f, crashT / 22f)
        val r = k * k * 2200
        fill.color = app.c
        c.drawCircle(BX + BW / 2, by + BH / 2, r, fill)
        if (crashT > 20) {
            val n = app.n
            val cell = if (n.length > 7) 18f else 24f
            val w = Dots.textW(n, cell)
            val faint = if (app.ink == GameApps.INK_WHITE) 0x38FFFFFF else 0x2E000000  // rgba(255,255,255,.22) / rgba(0,0,0,.18)
            Dots.text(c, n, (W - w) / 2, 540 - 7 * cell / 2, cell, app.ink, true, faint)
            monoPaint.color = app.ink
            c.drawText("OPENING", 960f, 540 + 7 * cell / 2 + 70, monoPaint)
            val s = "SCORE $score"
            val sc = 6f
            Dots.text(c, s, (W - Dots.textW(s, sc)) / 2, 540 + 7 * cell / 2 + 120, sc, app.ink, false)
        }
    }
}
