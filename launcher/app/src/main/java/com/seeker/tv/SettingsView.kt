package com.seeker.tv

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.min

/**
 * Seeker's own settings screen (spec v4), same canvas language as the wheel: dot-matrix headings,
 * mono values, cursor dot on the selected row. Three pages: the main list, HIDDEN APPS, ABOUT.
 * BACK goes up one level; BACK on the main list returns home (the wheel, or Flap's START).
 * M5: HOME MODE row (WHEEL / FLAP). In FLAP mode the list starts with APP LIST (the wheel for one
 * visit) and the calm rainbow strip runs across the top.
 * M6: HOME MODE cycles WHEEL / FLAP / INVADERS (both games get APP LIST and the strip), and SOUND: ON / OFF
 * mutes every mode.
 * M8: HOME MODE adds HUNT. In HUNT the same pages are drawn in the Hunt mockup's style instead: the
 * daytime scene, a black panel with a white border, square-pixel text and a `>` cursor.
 * v0.9.0: GREETING and WEATHER LOCATION take text from the TV keyboard. HOME MODE lists only the
 * modes built into the flavor and is left out of the wheel-only build.
 */
@SuppressLint("ViewConstructor")
class SettingsView(ctx: Context, private val host: Host) : View(ctx) {

    interface Host {
        fun openSystemSettings()
        fun openInputs()
        fun greeting(): String
        fun editGreeting()
        fun timeGreetingsEnabled(): Boolean
        fun toggleTimeGreetings()
        fun timeGreeting(period: GreetingPeriod): String
        fun editTimeGreeting(period: GreetingPeriod)
        fun weatherPlace(): String        // WEATHER LOCATION
        fun editWeatherPlace()
        fun allApps(): List<AppEntry>
        fun orderedApps(): List<AppEntry>
        fun saveAppOrder(keys: List<String>)
        fun temperatureUnit(): Weather.Unit
        fun toggleTemperatureUnit()
        fun hiddenKeys(): Set<String>
        fun toggleHidden(entry: AppEntry)
        fun about(): List<Pair<String, String>>
        fun closeSettings()
        fun homeModes(): List<String>     // WHEEL plus the games built into this flavor
        fun homeMode(): String            // one of homeModes()
        fun cycleHomeMode()
        fun openAppList()
        fun soundOn(): Boolean
        fun toggleSound()
        fun infiniteScrollEnabled(): Boolean
        fun toggleInfiniteScroll()
        fun customizeApps()
        fun chooseBackground()
        fun hasCustomBackground(): Boolean
        fun customImageEnabled(): Boolean
        fun toggleCustomImage()
        fun backgroundOpacity(): Int
        fun adjustBackgroundOpacity(delta: Int)
        fun backgroundGradientEnabled(): Boolean
        fun toggleBackgroundGradient()
        fun gradientOpacity(): Int
        fun adjustGradientOpacity(delta: Int)
        fun gradientLength(): Int
        fun adjustGradientLength(delta: Int)
        fun gradientStretch(): Int
        fun adjustGradientStretch(delta: Int)
    }

    private companion object {
        const val W = 1920f
        const val H = 1080f
        const val BG = 0xFF0B0C0E.toInt()
        const val GRID_DOT = 0xFF16181C.toInt()
        const val VALUE = 0xFF8B9098.toInt()
        const val LEFT = 150f
        const val CURSOR_X = 101f
        const val TITLE_Y = 120f
        const val TITLE_CELL = 12f
        const val LIST_TOP = 330f
        const val VALUE_X = 1100f
        val MAIN = listOf("SYSTEM SETTINGS", "INPUTS", "HOME MODE", "INFINITE SCROLL", "SOUND", "CUSTOM GREETING", "GREETING MODE", "TIME GREETINGS", "WEATHER LOCATION", "TEMPERATURE UNIT", "APP TITLES & COLORS", "BACKGROUND IMAGE", "USE CUSTOM IMAGE", "IMAGE OPACITY", "GRADIENT SETTINGS", "APP ORDER", "HIDDEN APPS", "ABOUT")
        const val ROW_SP = 104f
        const val LAST_ROW = 1000f  // lowest row center
        const val HUNT_VALUE = 0xFFBCBCBC.toInt()  // HUNT style: values and visible apps
        const val HUNT_DIM = 0xFF4C4C4C.toInt()    // HUNT style: hidden apps
    }

    private enum class Page { MAIN, HIDDEN, ORDER, ABOUT, TIME_GREETINGS, GRADIENT }

    private var page = Page.MAIN
    private var mainSel = 0
    private var rows: List<String> = MAIN
    private val game get() = host.homeMode() != "WHEEL"  // FLAP, INVADERS or HUNT
    private val hunt get() = host.homeMode() == "HUNT"
    private var rainbowT = 0  // the mockup's frame counter t, for the strip's hue

    /** The main list; the game modes add APP LIST on top. HOME MODE only when a game is built in. */
    private fun mainRows(): List<String> {
        val main = if (host.homeModes().size > 1) MAIN else MAIN - "HOME MODE"
        return if (game) listOf("APP LIST") + main else main
    }
    private var appSel = 0
    private var apps: List<AppEntry> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var about: List<Pair<String, String>> = emptyList()
    private var periodSel = 0
    private var gradientSel = 0

    // HIDDEN APPS scrolls; animate the scroll like the wheel
    private val ease = PathInterpolator(.2f, .7f, .2f, 1f)
    private var scroll = 0f
    private var scrollAnim: ValueAnimator? = null

    private val gridPaint = Paint().apply {
        val tile = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawCircle(12f, 12f, 1.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID_DOT })
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setTranslate(12f, 12f) })
        }
    }
    private val glowPaint = Paint().apply {
        shader = RadialGradient(
            0f, 540f, 900f,
            intArrayOf(Dots.fade(Apps.SETTINGS_COLOR, 0.14f), Dots.fade(Apps.SETTINGS_COLOR, 0f)),
            floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP,
        ).apply { setLocalMatrix(Matrix().apply { setScale(1f, 700f / 900f, 0f, 540f) }) }
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, 500, false)
        textSize = 24f
        letterSpacing = 0.14f
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(BG)
    }

    /** Called each time the screen opens: start on the main list, top row. */
    fun open() {
        page = Page.MAIN
        rows = mainRows()
        mainSel = 0
        invalidate()
    }

    /** HOME MODE changed (here or by the MODE broadcast): rebuild the list, keep the cursor on its row. */
    fun modeChanged() {
        val at = rows.getOrNull(mainSel)
        rows = mainRows()
        mainSel = rows.indexOf(at).takeIf { it >= 0 } ?: 0
        invalidate()
    }

    private fun ms(d: Long): Long {
        val s = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return if (s > 0f) (d / s).toLong() else d
    }

    // ---------- remote ----------
    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        val handled = isCenter || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!handled) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        // lists scroll on key repeat; OK and BACK act once
        if (e.repeatCount > 0 && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return true

        when (page) {
            Page.MAIN -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (mainSel < rows.size - 1) mainSel++
                KeyEvent.KEYCODE_DPAD_UP -> if (mainSel > 0) mainSel--
                KeyEvent.KEYCODE_BACK -> { host.closeSettings(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    { when (rows.getOrNull(mainSel)) {
                        "TEMPERATURE UNIT" -> host.toggleTemperatureUnit()
                        "GREETING MODE" -> host.toggleTimeGreetings()
                        "INFINITE SCROLL" -> host.toggleInfiniteScroll()
                        "IMAGE OPACITY" -> host.adjustBackgroundOpacity(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 5 else -5)
                        "USE CUSTOM IMAGE" -> host.toggleCustomImage()
                        "GRADIENT SETTINGS" -> host.toggleBackgroundGradient()
                    } }
                else -> pickMain()
            }
            Page.HIDDEN -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (appSel < apps.size - 1) { appSel++; scrollTo(true) }
                KeyEvent.KEYCODE_DPAD_UP -> if (appSel > 0) { appSel--; scrollTo(true) }
                KeyEvent.KEYCODE_BACK -> page = Page.MAIN
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                else -> apps.getOrNull(appSel)?.let { host.toggleHidden(it); hidden = host.hiddenKeys() }
            }
            Page.ORDER -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (appSel < apps.size - 1) { appSel++; scrollTo(true) }
                KeyEvent.KEYCODE_DPAD_UP -> if (appSel > 0) { appSel--; scrollTo(true) }
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSelected(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSelected(1)
                KeyEvent.KEYCODE_BACK -> page = Page.MAIN
            }
            Page.ABOUT -> if (code == KeyEvent.KEYCODE_BACK || isCenter) page = Page.MAIN
            Page.TIME_GREETINGS -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> periodSel = (periodSel + 1) % GreetingPeriod.values().size
                KeyEvent.KEYCODE_DPAD_UP -> periodSel = (periodSel - 1 + GreetingPeriod.values().size) % GreetingPeriod.values().size
                KeyEvent.KEYCODE_BACK -> page = Page.MAIN
                else -> host.editTimeGreeting(GreetingPeriod.values()[periodSel])
            }
            Page.GRADIENT -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> gradientSel = (gradientSel + 1) % 4
                KeyEvent.KEYCODE_DPAD_UP -> gradientSel = (gradientSel - 1 + 4) % 4
                KeyEvent.KEYCODE_BACK -> page = Page.MAIN
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> adjustGradient(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 5 else -5)
                else -> adjustGradient(5)
            }
        }
        invalidate()
        return true
    }

    private fun pickMain() {
        when (rows[mainSel]) {
            "APP LIST" -> host.openAppList()
            "SYSTEM SETTINGS" -> host.openSystemSettings()
            "INPUTS" -> host.openInputs()
            "HOME MODE" -> { host.cycleHomeMode(); modeChanged() }
            "SOUND" -> host.toggleSound()
            "INFINITE SCROLL" -> host.toggleInfiniteScroll()
            "CUSTOM GREETING" -> host.editGreeting()
            "GREETING MODE" -> host.toggleTimeGreetings()
            "TIME GREETINGS" -> { periodSel = 0; page = Page.TIME_GREETINGS }
            "WEATHER LOCATION" -> host.editWeatherPlace()
            "TEMPERATURE UNIT" -> host.toggleTemperatureUnit()
            "APP TITLES & COLORS" -> host.customizeApps()
            "BACKGROUND IMAGE" -> host.chooseBackground()
            "USE CUSTOM IMAGE" -> host.toggleCustomImage()
            "IMAGE OPACITY" -> host.adjustBackgroundOpacity(5)
            "GRADIENT SETTINGS" -> { gradientSel = 0; page = Page.GRADIENT }
            "APP ORDER" -> {
                apps = host.orderedApps()
                appSel = 0
                scroll = 0f
                page = Page.ORDER
            }
            "HIDDEN APPS" -> {
                apps = host.allApps()
                hidden = host.hiddenKeys()
                appSel = 0
                page = Page.HIDDEN
                scrollTo(false)
            }
            "ABOUT" -> { about = host.about(); page = Page.ABOUT }
        }
    }

    private fun moveSelected(delta: Int) {
        val next = appSel + delta
        if (next !in apps.indices) return
        apps = apps.toMutableList().apply { add(next, removeAt(appSel)) }
        appSel = next
        host.saveAppOrder(apps.map { it.key })
        scrollTo(true)
    }

    // HIDDEN APPS rows
    private val rowCell = 7f
    private val rowSp = 84f
    private val visibleRows get() = if (hunt) 7 else 8

    /** Keep the selected row inside the window, scrolling a row at a time. */
    private fun scrollTo(animate: Boolean) {
        val first = (scroll / rowSp).toInt()
        val target = when {
            appSel < first -> appSel
            appSel >= first + visibleRows -> appSel - visibleRows + 1
            else -> first
        } * rowSp
        scrollAnim?.cancel()
        if (!animate || target == scroll) { scroll = target; return }
        val from = scroll
        scrollAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms(300)
            interpolator = ease
            addUpdateListener { scroll = from + (target - from) * ease.getInterpolation(it.animatedFraction); invalidate() }
            start()
        }
    }

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        if (hunt) {
            drawHunt(canvas)
            canvas.restore()
            return
        }
        canvas.drawRect(0f, 0f, W, H, gridPaint)
        canvas.drawRect(0f, 0f, W, H, glowPaint)
        if (game) {
            val style = if (host.homeMode() == "INVADERS") Rainbow.INVADERS else Rainbow.FLAP
            Rainbow.draw(canvas, full = false, t = rainbowT++, style = style)
            postInvalidateOnAnimation()
        }  // backdrop, as in the mockup
        when (page) {
            Page.MAIN -> drawMain(canvas)
            Page.HIDDEN -> drawHidden(canvas)
            Page.ORDER -> drawOrder(canvas)
            Page.ABOUT -> drawAbout(canvas)
            Page.TIME_GREETINGS -> drawTimeGreetings(canvas)
            Page.GRADIENT -> drawGradientSettings(canvas)
        }
        canvas.restore()
    }

    private fun title(c: Canvas, t: String) = Dots.text(c, t, LEFT, TITLE_Y, TITLE_CELL, Dots.FG, true)

    private fun cursor(c: Canvas, cy: Float, color: Int) {
        fill.shader = RadialGradient(
            CURSOR_X, cy, 24f,
            intArrayOf(Dots.fade(color, 0.75f), Dots.fade(color, 0.3f), Dots.fade(color, 0f)),
            floatArrayOf(0.15f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(CURSOR_X, cy, 24f, fill)
        fill.shader = null
        fill.color = color
        c.drawCircle(CURSOR_X, cy, 5f, fill)
    }

    private fun value(c: Canvas, text: String, cy: Float, color: Int) {
        mono.color = color
        c.drawText(text, VALUE_X, cy + mono.textSize * 0.36f, mono)
    }

    private fun drawMain(c: Canvas) {
        title(c, "SETTINGS")
        val sp = min(ROW_SP, (LAST_ROW - LIST_TOP) / (rows.size - 1))
        val cell = min(8f, sp / 9f)
        for ((i, label) in rows.withIndex()) {
            val cy = LIST_TOP + i * sp
            val on = i == mainSel
            if (on) cursor(c, cy, Dots.FG)
            Dots.text(c, label, LEFT, cy - 3.5f * cell, cell, if (on) Dots.FG else Dots.MUTE, on, Dots.SEL_GRID)
            val v = when (label) {
                "HOME MODE" -> host.homeMode()
                "SOUND" -> if (host.soundOn()) "ON" else "OFF"
                "INFINITE SCROLL" -> if (host.infiniteScrollEnabled()) "ON" else "OFF"
                "CUSTOM GREETING" -> host.greeting()
                "GREETING MODE" -> if (host.timeGreetingsEnabled()) "TIME BASED" else "CUSTOM"
                "TIME GREETINGS" -> "EDIT PHRASES"
                "WEATHER LOCATION" -> host.weatherPlace()
                "TEMPERATURE UNIT" -> host.temperatureUnit().label
                "HIDDEN APPS" -> "${host.hiddenKeys().count { k -> host.allApps().any { it.key == k } }} HIDDEN"
                "BACKGROUND IMAGE" -> "CHOOSE IMAGE"
                "USE CUSTOM IMAGE" -> if (!host.hasCustomBackground()) "NO IMAGE" else if (host.customImageEnabled()) "ON" else "OFF"
                "IMAGE OPACITY" -> "${host.backgroundOpacity()}%"
                "GRADIENT SETTINGS" -> if (host.backgroundGradientEnabled()) "ON" else "OFF"
                "APP TITLES & COLORS" -> "EDIT APPS"
                else -> null
            }
            if (v != null) value(c, v, cy, if (on) Dots.FG else VALUE)
        }
    }

    private fun drawHidden(c: Canvas) {
        title(c, "HIDDEN APPS")
        val top = LIST_TOP - 30f
        c.save()
        c.clipRect(0f, top - rowSp / 2, W, top + (visibleRows - 0.5f) * rowSp)
        for ((i, app) in apps.withIndex()) {
            val cy = top + i * rowSp - scroll
            if (cy < top - rowSp || cy > top + visibleRows * rowSp) continue
            val on = i == appSel
            val isHidden = app.key in hidden
            val color = when {
                isHidden && on -> Dots.MUTE
                isHidden -> Dots.DIM
                on -> app.color
                else -> Dots.MUTE
            }
            if (on) cursor(c, cy, if (isHidden) Dots.MUTE else app.color)
            val parts = app.parts
            if (on && !isHidden && parts != null) {
                var x = LEFT
                for ((txt, col) in parts) {
                    Dots.text(c, txt, x, cy - 3.5f * rowCell, rowCell, col, true, Dots.SEL_GRID)
                    x += txt.length * rowCell * 6
                }
            } else {
                Dots.text(c, app.dots, LEFT, cy - 3.5f * rowCell, rowCell, color, on, Dots.SEL_GRID)
            }
            value(c, if (isHidden) "HIDDEN" else "ON WHEEL", cy, if (on) Dots.FG else if (isHidden) Dots.DIM else VALUE)
        }
        c.restore()
    }

    private fun drawOrder(c: Canvas) {
        title(c, "APP ORDER")
        val top = LIST_TOP - 30f
        c.save()
        c.clipRect(0f, top - rowSp / 2, W, top + (visibleRows - 0.5f) * rowSp)
        for ((i, app) in apps.withIndex()) {
            val cy = top + i * rowSp - scroll
            if (cy < top - rowSp || cy > top + visibleRows * rowSp) continue
            val on = i == appSel
            if (on) cursor(c, cy, app.color)
            Dots.text(c, app.dots, LEFT, cy - 3.5f * rowCell, rowCell, if (on) app.color else Dots.MUTE, on, Dots.SEL_GRID)
            value(c, "${i + 1}", cy, if (on) Dots.FG else VALUE)
        }
        c.restore()
        Dots.text(c, "UP DOWN SELECT   LEFT RIGHT MOVE", LEFT, 1010f, 5f, Dots.MUTE, false)
    }

    private fun drawAbout(c: Canvas) {
        title(c, "ABOUT")
        val cell = 6f
        for ((i, kv) in about.withIndex()) {
            val cy = LIST_TOP + i * 90f
            Dots.text(c, kv.first, LEFT, cy - 3.5f * cell, cell, Dots.MUTE, false)
            value(c, kv.second, cy, Dots.FG)
        }
    }

    private fun drawTimeGreetings(c: Canvas) {
        title(c, "TIME GREETINGS")
        for ((i, period) in GreetingPeriod.values().withIndex()) {
            val cy = 330f + i * 88f
            val on = i == periodSel
            if (on) cursor(c, cy, Dots.FG)
            Dots.text(c, period.label, LEFT, cy - 24.5f, 7f, if (on) Dots.FG else Dots.MUTE, on, Dots.SEL_GRID)
            value(c, host.timeGreeting(period), cy, if (on) Dots.FG else VALUE)
        }
        Dots.text(c, "UP DOWN SELECT   OK EDIT   BACK", LEFT, 1010f, 5f, Dots.MUTE, false)
    }

    private fun adjustGradient(delta: Int) {
        when (gradientSel) {
            0 -> host.toggleBackgroundGradient()
            1 -> host.adjustGradientOpacity(delta)
            2 -> host.adjustGradientLength(delta)
            3 -> host.adjustGradientStretch(delta)
        }
    }

    private fun drawGradientSettings(c: Canvas) {
        title(c, "BACKGROUND GRADIENT")
        val values = listOf(
            "GRADIENT" to if (host.backgroundGradientEnabled()) "ON" else "OFF",
            "OPACITY" to "${host.gradientOpacity()}%",
            "LENGTH" to "${host.gradientLength()}%",
            "STRETCH" to "${host.gradientStretch()}%",
        )
        for ((i, row) in values.withIndex()) {
            val cy = 350f + i * 150f
            val on = i == gradientSel
            if (on) cursor(c, cy, Dots.FG)
            Dots.text(c, row.first, LEFT, cy - 28f, 8f, if (on) Dots.FG else Dots.MUTE, on, Dots.SEL_GRID)
            value(c, row.second, cy, if (on) Dots.FG else VALUE)
        }
        Dots.text(c, "LENGTH = WIDTH   STRETCH = FADE", LEFT, 960f, 4.5f, Dots.MUTE, false)
        Dots.text(c, "UP DOWN SELECT   LEFT RIGHT ADJUST   OK +5   BACK", LEFT, 1010f, 5f, Dots.MUTE, false)
    }

    // ---------- HUNT style (mockup drawSettings(): black panel, white border, `>` cursor) ----------
    private val huntRect = Paint()

    private fun drawHunt(c: Canvas) {
        Pixel.backdrop(c, rainbowT++)
        postInvalidateOnAnimation()  // the clouds drift
        huntRect.color = Pixel.INK; c.drawRect(380f, 150f, 1540f, 930f, huntRect)
        huntRect.color = Pixel.PAPER; c.drawRect(392f, 162f, 1528f, 918f, huntRect)
        huntRect.color = Pixel.INK; c.drawRect(404f, 174f, 1516f, 906f, huntRect)
        when (page) {
            Page.MAIN -> drawHuntMain(c)
            Page.HIDDEN -> drawHuntHidden(c)
            Page.ORDER -> drawHuntOrder(c)
            Page.ABOUT -> drawHuntAbout(c)
            Page.TIME_GREETINGS -> drawHuntTimeGreetings(c)
            Page.GRADIENT -> drawGradientSettings(c)
        }
    }

    private fun huntTitle(c: Canvas, t: String) = Pixel.text(c, t, (W - Pixel.textW(t, 11f)) / 2, 200f, 11f, Pixel.PAPER)

    private fun drawHuntMain(c: Canvas) {
        huntTitle(c, "SETTINGS")
        // the mockup's 6 rows sit 95 apart from y=300 at cell 8; the 8 real rows need cell 7 to fit the panel
        val sp = min(95f, 550f / (rows.size - 1))
        for ((i, label) in rows.withIndex()) {
            val y = 300 + i * sp
            val on = i == mainSel
            val v = when (label) {
                "HOME MODE" -> host.homeMode()
                "SOUND" -> if (host.soundOn()) "ON" else "OFF"
                "INFINITE SCROLL" -> if (host.infiniteScrollEnabled()) "ON" else "OFF"
                "CUSTOM GREETING" -> host.greeting()
                "GREETING MODE" -> if (host.timeGreetingsEnabled()) "TIME BASED" else "CUSTOM"
                "TIME GREETINGS" -> "EDIT PHRASES"
                "WEATHER LOCATION" -> host.weatherPlace()
                "TEMPERATURE UNIT" -> host.temperatureUnit().label
                "HIDDEN APPS" -> "${host.hiddenKeys().count { k -> host.allApps().any { it.key == k } }}"
                else -> null
            }
            val full = if (v != null) "$label $v" else label
            val cell = min(min(7f, sp * 0.11f), 1000f / (full.length * 6))  // long values shrink to stay in the panel
            val x = (W - Pixel.textW(full, cell)) / 2
            Pixel.text(c, label, x, y, cell, if (on) Pixel.PAPER else Pixel.GRAY)
            if (v != null) Pixel.text(c, v, x + (label.length + 1) * cell * 6, y, cell, if (on) Pixel.SKY_BAND else HUNT_VALUE)
            if (on) Pixel.text(c, ">", x - 7.5f * cell, y, cell, Pixel.PAPER)
        }
    }

    private fun drawHuntHidden(c: Canvas) {
        huntTitle(c, "HIDDEN APPS")
        val top = 300f
        val cell = 6f
        c.save()
        c.clipRect(404f, top - 20, 1516f, top + (visibleRows - 1) * rowSp + 7 * cell + 20)
        for ((i, app) in apps.withIndex()) {
            val y = top + i * rowSp - scroll
            if (y < top - rowSp || y > top + visibleRows * rowSp) continue
            val on = i == appSel
            val isHidden = app.key in hidden
            val color = when {
                on -> if (isHidden) Pixel.GRAY else Pixel.PAPER
                isHidden -> HUNT_DIM
                else -> HUNT_VALUE
            }
            if (on) Pixel.text(c, ">", 440f, y, cell, Pixel.PAPER)
            Pixel.text(c, app.dots.take(20), 490f, y, cell, color)
            val st = if (isHidden) "HIDDEN" else "ON WHEEL"
            Pixel.text(c, st, 1470f - Pixel.textW(st, 5f), y + cell, 5f, if (on) Pixel.SKY_BAND else color)
        }
        c.restore()
    }

    private fun drawHuntOrder(c: Canvas) {
        huntTitle(c, "APP ORDER")
        val top = 285f
        val cell = 5f
        c.save()
        c.clipRect(404f, top - 20, 1516f, top + (visibleRows - 1) * rowSp + 7 * cell + 20)
        for ((i, app) in apps.withIndex()) {
            val y = top + i * rowSp - scroll
            if (y < top - rowSp || y > top + visibleRows * rowSp) continue
            val on = i == appSel
            if (on) Pixel.text(c, ">", 440f, y, cell, Pixel.PAPER)
            Pixel.text(c, app.dots.take(20), 490f, y, cell, if (on) Pixel.PAPER else HUNT_VALUE)
            val rank = "${i + 1}"
            Pixel.text(c, rank, 1470f - Pixel.textW(rank, cell), y, cell, if (on) Pixel.SKY_BAND else HUNT_VALUE)
        }
        c.restore()
        Pixel.text(c, "UP/DOWN SELECT   LEFT/RIGHT MOVE", 490f, 850f, 5f, Pixel.GRAY)
    }

    private fun drawHuntAbout(c: Canvas) {
        huntTitle(c, "ABOUT")
        for ((i, kv) in about.withIndex()) {
            val y = 320 + i * 100f
            Pixel.text(c, kv.first, 490f, y, 5f, Pixel.GRAY)
            mono.color = Pixel.PAPER
            c.drawText(kv.second, 860f, y + 17.5f + mono.textSize * 0.36f, mono)
        }
    }

    private fun drawHuntTimeGreetings(c: Canvas) {
        huntTitle(c, "TIME GREETINGS")
        for ((i, period) in GreetingPeriod.values().withIndex()) {
            val y = 300 + i * 78f
            val on = i == periodSel
            val phrase = host.timeGreeting(period)
            val cell = min(6f, 580f / (phrase.length * 6))
            Pixel.text(c, period.label, 490f, y, 6f, if (on) Pixel.PAPER else Pixel.GRAY)
            Pixel.text(c, phrase, 850f, y, cell, if (on) Pixel.SKY_BAND else HUNT_VALUE)
            if (on) Pixel.text(c, ">", 440f, y, 6f, Pixel.PAPER)
        }
        Pixel.text(c, "UP/DOWN SELECT   OK EDIT   BACK", 490f, 850f, 5f, Pixel.GRAY)
    }
}
