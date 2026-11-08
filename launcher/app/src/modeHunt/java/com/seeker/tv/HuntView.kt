package com.seeker.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Seeker Hunt (M8): HOME MODE = HUNT. 8-bit, not dot matrix. The sight locks onto a duck, LEFT/RIGHT
 * jump it to the nearest duck on that side, and the shotgun at the bottom swings to aim at the sight.
 * Tap OK: one in four hits. Hold OK 600 ms: a guaranteed miss. The dog pops up out of the grass to grin
 * at a miss or hold up the duck on a hit; after he sinks on a hit the takeover runs and the service
 * opens ~900 ms in. START (dog peeking, SETTINGS under it on DOWN).
 * Sprites, palette, motion, nextTarget(), shoot(), drawPop(), drawGun(), screens and sounds are ported
 * 1:1 from design/seeker-hunt-mockup-v4.html (frame-based at 60 fps, 1920x1080 reference pixels, 6 px cells).
 * Same fixed-60 Hz Choreographer loop as FlapView and InvadersView.
 */
@SuppressLint("ViewConstructor")
class HuntView(ctx: Context, private val host: GameHost) : View(ctx), GameScreen {


    private companion object {
        const val W = 1920f
        const val H = 1080f
        const val GROUND = Pixel.GROUND
        const val PX = 6f
        const val DW = 16 * PX
        const val DH = 12 * PX
        const val HIT_RATE = 0.25  // tap OK: one in four hits
        const val HOLD_MS = 600L   // hold OK this long: a guaranteed miss, fired at the 600 ms mark
        const val LAUNCH_AT = 54   // frames into the takeover: the intent fires ~900 ms after it starts
        const val CRASH_END = 130  // frames: back to START if nothing launched (mockup)
        const val STEP_NS = 1_000_000_000L / 60

        // our own duck, 16x12, two wing frames (facing right): b brand body, d darker brand wing
        val DUCK = listOf(
            Pixel.Sprite(listOf("........kkk.....", "......kkbbbk....", "......kbbbbkoo..", "......kbkbbkooo.", "......kbbbbk....", "...kkkkbbbbk....", "..kbbbbbbbbbbk..", "kkbbbbbbbbbbbbk.", ".kdddbbbbbbbbbk.", "..kdddbbbbbbbk..", "...kkkkkkkkkk...", "....oo....oo....")),
            Pixel.Sprite(listOf("........kkk.....", "......kkbbbk....", "......kbbbbkoo..", "......kbkbbkooo.", "......kbbbbk....", "..kkkkkbbbbk....", ".kdddddbbbbbbk..", "kkddddbbbbbbbbk.", ".kkkkbbbbbbbbbk.", "....kbbbbbbbbk..", "....kkkkkkkkk...", "....oo....oo....")),
        )
        // our own hound, front-facing bust that pops up out of the grass, 24x18: green hunter's cap (G), tan coat (t),
        // dark-tan floppy ears (d), white muzzle and eyes (w), pupils (p), nose (n), red collar (r)
        val POP_MISS = Pixel.Sprite(listOf("........kkkkkkkk........", "......kkGGGGGGGGkk......", ".....kGGGGGGGGGGGGk.....", "....kkkkkkkkkkkkkkkk....", "..kddktttttttttttkddk...", ".kdddktwwtttttwwtkdddk..", ".kdddktwpttttttpwtdddk..", ".kdddkttttttttttttkdddk.", "..kdkttwwwwwwwwwwttkdk..", "...kttwwwwnnwwwwwwttk...", "...kttwwwkkkkkkkwwttk...", "...kttwwwwkwkwkwwwttk...", "....ktttwwwwwwwwtttk....", ".....kkkttttttttkkk.....", "......krrrrrrrrrrk......", ".....kttttttttttttk.....", "....kttttttttttttttk....", "....kttttttttttttttk...."))
        val POP_HIT = Pixel.Sprite(listOf("........kkkkkkkk........", "......kkGGGGGGGGkk......", ".....kGGGGGGGGGGGGk.....", "....kkkkkkkkkkkkkkkk....", "..kddktttttttttttkddk...", ".kdddktwwtttttwwtkdddk..", ".kdddktpwtttttwptkdddk..", ".kdddkttttttttttttkdddk.", "..kdkttwwwwwwwwwwttkdk..", "...kttwwwwnnwwwwwwttk...", "...kttwwwwwwwwwwwwttk...", "...kttwwwkkkkkkkwwttk...", "....ktttwwwwwwwwtttk....", ".....kkkttttttttkkk.....", "......krrrrrrrrrrk......", ".....kttttttttttttk.....", "....kttttttttttttttk....", "....kttttttttttttttk...."))
        // the player's shotgun, 20x6, pivot at the left end, barrel along +x
        val GUN = Pixel.Sprite(listOf("kkkkkkkkkkkkkkkkkkkk", "kggggggggggggggggggk", "kggggggggggggggggggk", "kkkkkkkkkkkkkkkkkkkk", "kdddkk..............", "kkkkk..............."))
        val SIGHT = Pixel.Sprite(listOf("....kkk....", "....kwk....", "..kkkwkkk..", ".kwwwkwwwk.", "kkwkkkkkwkk", "kwwk...kwwk", "kkwkkkkkwkk", ".kwwwkwwwk.", "..kkkwkkk..", "....kwk....", "....kkk...."))
        // ears darker tan, cap green, collar red
        val DOGPAL = Pixel.pal('g' to 0xFF4C4C4C.toInt(), 'd' to 0xFFA06020.toInt(), 'G' to 0xFF2C7C2C.toInt(), 'r' to 0xFFD82800.toInt(), 'p' to 0xFF000000.toInt())
        val GUNPAL = Pixel.pal('g' to 0xFF5C5C5C.toInt(), 'd' to 0xFF8C4800.toInt())
        const val TAN = 0xFFD8A060.toInt()  // PAL.t: the hit pose's arm

        // mockup APPS: body c, wing d (per duck), keyed by package; ink from GameApps
        val COLORS = mapOf(
            "com.netflix.ninja" to (0xFFE50914 to 0xFF9C0810),
            "com.google.android.youtube.tv" to (0xFFFF0000 to 0xFFA80000),
            "com.hulu.livingroomplus" to (0xFF1CE783 to 0xFF10944F),
            "com.amazon.amazonvideo.livingroom" to (0xFF00A8E1 to 0xFF006E95),
            "com.disney.disneyplus" to (0xFF2E6BFF to 0xFF1C45B0),
            "com.wbd.stream" to (0xFF3D34FF to 0xFF2820A8),
            "com.peacocktv.peacockandroid" to (0xFFFFC72C to 0xFFB58A18),
            "com.apple.atve.androidtv.appletv" to (0xFFFCFCFC to 0xFFA0A0A0),
        )
    }

    /** One duck: the service, its two body colors and their sprite palettes (built once). */
    private class Duck(val app: GameApp) {
        val c: Int
        val d: Int
        val ink = if (app.ink == GameApps.INK_WHITE) Pixel.PAPER else Pixel.INK
        init {
            val cd = COLORS[app.pkg]
            c = cd?.first?.toInt() ?: app.c
            d = cd?.second?.toInt() ?: app.c
        }
        val pal = Pixel.pal('b' to c, 'd' to d)
        val sightPal = Pixel.pal('w' to c)
        val inkPal = Pixel.pal('b' to ink, 'd' to ink)
    }

    private class Bird(val duck: Duck, var x: Float, var y: Float, var vx: Float, val ph: Float) {
        var flee = 0
        var fall = false
    }

    private class Hit(val duck: Duck, val i: Int, val x: Float, val y: Float)

    /** The dog rising out of the grass to react (mockup `pop`): 20 frames up, 60 hold, 20 down. */
    private class Pop(val hit: Boolean, var t: Int, val duck: Duck)

    private enum class Mode { START, PLAY, CRASH }

    // ---------- game state (mockup names) ----------
    private var mode = Mode.START
    private var t = 0
    private var menuSel = 0
    private var ducks = GameApps.ALL.map { Duck(it) }
    private var birds: List<Bird> = emptyList()
    private var target = 0
    private var sightX = 960f
    private var sightY = 400f
    private var shots = 0
    private var flash = 0
    private var hit: Hit? = null
    private var hitT = 0
    private var missT = 0
    private var missAtX = 0f
    private var missAtY = 0f
    private var pop: Pop? = null
    private var launched = false

    // hold-to-miss (mockup holdTimer / held / armed)
    private var held = false
    private var armed = false
    private val holdTimer = Runnable { if (armed && mode == Mode.PLAY) { held = true; shoot(true) } }

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

    private val rect = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
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
        menuSel = 0
        reset()
        invalidate()
    }

    /** Ducks are the spec's eight services that are installed (all eight if none resolve). */
    override fun refreshApps() {
        val apps = GameApps.installed(host::flapEntry)
        if (apps.map { it.pkg } != ducks.map { it.app.pkg }) { ducks = apps.map { Duck(it) }; makeBirds() }
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

    // ---------- game (mockup) ----------
    private fun rnd() = Math.random().toFloat()

    private fun makeBirds() {
        birds = ducks.mapIndexed { i, d ->
            Bird(d, 200 + i * 200 + rnd() * 80, 180 + (i % 4) * 95 + rnd() * 40, (if (rnd() < 0.5f) -1 else 1) * (1.2f + rnd() * 1.2f), rnd() * 6.28f)
        }
    }

    private fun reset() {
        makeBirds(); target = 0; shots = 0; flash = 0; hit = null; hitT = 0; missT = 0; pop = null; launched = false
        removeCallbacks(holdTimer); held = false; armed = false
    }

    private fun nextTarget(dir: Int) {
        val cur = birds[target]
        var best = -1
        var bestD = 1e9f
        for ((i, b) in birds.withIndex()) {
            if (i == target || b.fall) continue
            val d = (b.x - cur.x) * dir
            if (d > 10 && d < bestD) { bestD = d; best = i }
        }
        if (best >= 0) { target = best; Sfx.play(Sfx.Id.HUNT_LOCK) }
    }

    /** Tap OK: 25% hit. Hold OK (600 ms): guaranteed miss. The dog pops up either way; on a hit the takeover runs after he sinks. */
    private fun shoot(forceMiss: Boolean) {
        if (pop != null) return
        shots++; flash = 6
        val b = birds[target]
        if (forceMiss || Math.random() >= HIT_RATE) {
            Sfx.play(Sfx.Id.HUNT_SHOT_MISS)  // shot, miss at 120 ms, yip when the dog is up (one buffer)
            missT = 70; missAtX = b.x; missAtY = b.y; b.flee = 60
            pop = Pop(false, 0, b.duck)
            return
        }
        Sfx.play(Sfx.Id.HUNT_SHOT_HIT)   // shot, hit jingle when the dog is up (one buffer)
        b.fall = true; hit = Hit(b.duck, target, b.x, b.y); pop = Pop(true, 0, b.duck)
    }

    private fun duckFrame(ph: Float) = floor(t / 8f + ph * 3).toInt() % 2

    private fun step() {
        t++
        if (mode == Mode.START) {
            // mockup drawStart(): the ducks fly about
            for (b in birds) { b.x += b.vx; if (b.x < 120 || b.x > W - 120) b.vx = -b.vx; b.y += sin(t / 20f + b.ph) * 0.6f }
        }
        if (mode == Mode.PLAY || mode == Mode.CRASH) {
            for (b in birds) {
                if (b.fall) { b.y += 9; continue }
                val sp = if (b.flee > 0) 3.5f else 1f
                b.x += b.vx * sp; if (b.x < 120 || b.x > W - 120) b.vx = -b.vx
                b.y += sin(t / 20f + b.ph) * 0.8f + (if (b.flee > 0) -1.5f else 0f)
                if (b.y < 130) b.y = 130f
                if (b.y > GROUND - 150) b.y = GROUND - 150
                if (b.flee > 0) b.flee--
            }
            val tb = birds[target]
            sightX += (tb.x - sightX) * 0.18f; sightY += (tb.y - sightY) * 0.18f
            if (flash > 0) flash--
            if (missT > 0) missT--
            pop?.let { p ->
                p.t++
                if (p.t > 100) { if (p.hit) { mode = Mode.CRASH; hitT = 0 }; pop = null }
            }
        }
        if (mode == Mode.CRASH) {
            hitT++
            if (hitT == LAUNCH_AT) {
                val entry = hit?.let { host.flapEntry(it.duck.app.pkg) }
                if (entry != null) { launched = true; host.launch(entry) }
            }
            // A launched app covers us and onStop resets to START; MainActivity's 4 s fallback covers a no-show.
            if (hitT > CRASH_END && !launched) toStart()
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
        if (e.action == KeyEvent.ACTION_UP) {
            // mockup keyup: a release before the hold timer is a tap
            if (isCenter && mode == Mode.PLAY && armed) {
                removeCallbacks(holdTimer)
                if (!held) shoot(false)
                held = false; armed = false
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true  // mockup ignores key repeat
        when (mode) {
            Mode.START -> when {
                code == KeyEvent.KEYCODE_DPAD_DOWN -> menuSel = 1
                code == KeyEvent.KEYCODE_DPAD_UP -> menuSel = 0
                isCenter -> if (menuSel == 0) { reset(); mode = Mode.PLAY; Sfx.play(Sfx.Id.HUNT_LOCK) } else { toStart(); host.openSettings() }
            }
            Mode.PLAY -> when {
                code == KeyEvent.KEYCODE_DPAD_LEFT -> nextTarget(-1)
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> nextTarget(1)
                isCenter -> if (flash == 0 && pop == null) {
                    held = false; armed = true
                    removeCallbacks(holdTimer); postDelayed(holdTimer, HOLD_MS)
                }
                code == KeyEvent.KEYCODE_BACK -> toStart()
            }
            Mode.CRASH -> Unit
        }
        return true
    }

    // ---------- drawing (mockup draw*()) ----------
    override fun isOpaque() = true

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        Pixel.backdrop(canvas, t)
        if (mode == Mode.START) drawStart(canvas)
        else {
            drawBirds(canvas); pop?.let { drawPop(canvas, it.hit, it.t, it.duck) }; drawGun(canvas); drawSight(canvas); drawHud(canvas)
            if (mode == Mode.CRASH) drawTakeover(canvas)
        }
        canvas.restore()
        if (!firstFrameSent) { firstFrameSent = true; post { host.firstFrame() } }
    }

    private fun drawBirds(c: Canvas) {
        val h = hit
        for ((i, b) in birds.withIndex()) {
            if (h != null && h.i == i) continue
            Pixel.sprite(c, DUCK[duckFrame(b.ph)], b.x - DW / 2, b.y - DH / 2, PX, b.duck.pal, b.vx < 0)
            val n = b.duck.app.n
            val w = Pixel.textW(n, 4f)
            val on = i == target
            Pixel.text(c, n, b.x - w / 2, b.y + DH / 2 + 14, 4f, if (on) Pixel.PAPER else Pixel.INK, if (on) Pixel.INK else 0)
        }
    }

    /** The dog rises out of the grass (20 frames), holds with a bob (60), sinks (20). On a hit he holds the duck up by his side. */
    private fun drawPop(c: Canvas, isHit: Boolean, pt: Int, duck: Duck) {
        val cell = 8f
        val w = 24 * cell
        val h = 18 * cell
        val k = if (pt < 20) pt / 20f else if (pt > 80) (100 - pt) / 20f else 1f
        val yTop = GROUND - h * k + 24 + (if (pt in 20..80) sin(pt / 4f) * 5 else 0f)
        val xL = 960 - w / 2
        c.save()
        c.clipRect(0f, 0f, W, GROUND + 4)
        if (isHit) {
            // arm up to the right, duck dangling from the paw in its brand color
            box(c, Pixel.INK, xL + w - 4, yTop + 9 * cell, 4 * cell + 8, 5 * cell)
            box(c, TAN, xL + w, yTop + 9 * cell + 4, 4 * cell, 5 * cell - 8)
            box(c, Pixel.INK, xL + w + 4 * cell - 4, yTop - 2 * cell, 3 * cell + 8, 11 * cell + 8)
            box(c, TAN, xL + w + 4 * cell, yTop - 2 * cell + 4, 3 * cell, 11 * cell)
            Pixel.sprite(c, DUCK[0], xL + w + 4 * cell - 16 * PX / 2 + 1.5f * cell, yTop - 2 * cell - 12 * PX + 6, PX, duck.pal)
        }
        Pixel.sprite(c, if (isHit) POP_HIT else POP_MISS, xL, yTop, cell, DOGPAL)
        c.restore()
        // grass in front of him so he reads as rising out of it
        Pixel.fringe(c)
    }

    private fun box(c: Canvas, color: Int, x: Float, y: Float, w: Float, h: Float) {
        rect.color = color
        c.drawRect(x, y, x + w, y + h, rect)
    }

    /** The player's shotgun at the bottom center, rotated about its left end to point at the sight. */
    private fun drawGun(c: Canvas) {
        val cell = 6f
        val ang = atan2(sightY - (GROUND + 150), sightX - 960)
        c.save()
        c.translate(960f, GROUND + 150)
        c.rotate(Math.toDegrees(ang.toDouble()).toFloat())
        Pixel.sprite(c, GUN, 0f, -2 * cell, cell, GUNPAL)
        c.restore()
    }

    private fun drawSight(c: Canvas) {
        val cell = 6f
        val w = 11 * cell
        Pixel.sprite(c, SIGHT, sightX - w / 2, sightY - w / 2, cell, birds[target].duck.sightPal)
        if (flash > 0) {
            rect.color = Dots.fade(0xFFFCFCFC.toInt(), flash / 6f)
            val g = (6 - flash) * 14f
            c.drawRect(sightX - 70 - g, sightY - 70 - g, sightX + 70 + g, sightY + 70 + g, rect)
        }
        if (missT > 0) {
            val s = "MISSED"
            val sw = Pixel.textW(s, 7f)
            Pixel.text(c, s, missAtX - sw / 2, missAtY - 100, 7f, Pixel.PAPER, Pixel.INK)
        }
    }

    private fun drawHud(c: Canvas) {
        val s = "SHOTS $shots"
        Pixel.text(c, s, W - 150 - Pixel.textW(s, 6f), 70f, 6f, Pixel.PAPER, Pixel.INK)
    }

    private fun drawStart(c: Canvas) {
        val title = "SEEKER HUNT"
        Pixel.text(c, title, (W - Pixel.textW(title, 16f)) / 2, 250f, 16f, Pixel.PAPER, Pixel.INK)
        if (floor(t / 30f).toInt() % 2 == 0 || menuSel == 1) {
            val s = "START"
            Pixel.text(c, s, (W - Pixel.textW(s, 10f)) / 2, 500f, 10f, if (menuSel == 0) Pixel.PAPER else Pixel.GRAY, Pixel.INK)
        }
        val g = "SETTINGS"
        Pixel.text(c, g, (W - Pixel.textW(g, 7f)) / 2, 640f, 7f, if (menuSel == 1) Pixel.PAPER else Pixel.GRAY, Pixel.INK)
        for (b in birds) Pixel.sprite(c, DUCK[duckFrame(b.ph)], b.x - DW / 2, b.y - DH / 2 - 60, PX, b.duck.pal, b.vx < 0)
        // on START the dog peeks out of the grass, grinning
        drawPop(c, false, 40, ducks[0])
    }

    private fun drawTakeover(c: Canvas) {
        val h = hit ?: return
        val k = min(1f, hitT / 22f)
        fill.color = h.duck.c
        c.drawCircle(h.x, h.y, k * k * 2300, fill)
        if (hitT > 20) {
            val n = h.duck.app.n
            val cell = if (n.length > 7) 18f else 24f
            val w = Pixel.textW(n, cell)
            val ink = h.duck.ink
            Pixel.sprite(c, DUCK[floor(t / 8f).toInt() % 2], (W - 16 * 9) / 2, 540 - 7 * cell / 2 - 140, 9f, h.duck.inkPal)
            Pixel.text(c, n, (W - w) / 2, 540 - 7 * cell / 2, cell, ink)
            monoPaint.color = ink
            c.drawText("OPENING", 960f, 540 + 7 * cell / 2 + 70, monoPaint)
            val s = "SHOTS $shots"
            Pixel.text(c, s, (W - Pixel.textW(s, 6f)) / 2, 540 + 7 * cell / 2 + 120, 6f, ink)
        }
    }
}
