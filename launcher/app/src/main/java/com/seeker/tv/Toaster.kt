package com.seeker.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Toast notifications (M4). Drawn in a TYPE_APPLICATION_OVERLAY window so they show over other apps;
 * without the SYSTEM_ALERT_WINDOW appop they only show while the launcher is in front.
 */
object Toaster {
    private const val TAG = "SeekerTV"
    const val HOLD_MS = 4200L

    /** Brand color of the app selected on the wheel; the toast's left bar uses it. */
    @Volatile var brand: Int = Dots.FG

    /** The launcher activity while it's resumed, for the no-overlay fallback. */
    var front: WeakReference<MainActivity>? = null

    private var window: ToastView? = null
    private var windowManager: WindowManager? = null

    fun show(ctx: Context, title: String, body: String, source: String) {
        val app = ctx.applicationContext
        val overlay = Settings.canDrawOverlays(app)
        val host: Context = if (overlay) app else front?.get() ?: run {
            Log.w(TAG, "no overlay permission and launcher not in front, dropping toast: $title")
            return
        }
        val v = window ?: ToastView(app).also { tv ->
            val wm = host.getSystemService(WindowManager::class.java)
            // Only the top-left corner the toast slides through, not a full-screen surface (RAM).
            val k = app.resources.displayMetrics.widthPixels / 1920f
            val lp = WindowManager.LayoutParams(
                (ToastView.WIN_W * k).toInt(), (ToastView.WIN_H * k).toInt(),
                if (overlay) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; setTitle("SeekerToast") }
            try {
                wm.addView(tv, lp)
            } catch (e: Exception) {
                Log.e(TAG, "toast window failed (overlay=$overlay)", e)
                return
            }
            window = tv
            windowManager = wm
            tv.onDone = { remove() }
        }
        v.show(title, body, source, brand)
    }

    private fun remove() {
        val v = window ?: return
        window = null
        try { windowManager?.removeView(v) } catch (e: Exception) { Log.w(TAG, "toast remove: $e") }
        windowManager = null
    }
}

/** The toast box from the mockup, in 1920x1080 reference pixels scaled to the window. */
@SuppressLint("ViewConstructor")
class ToastView(ctx: Context) : View(ctx) {
    companion object {
        const val WIN_W = 900f   // window size in 1920x1080 reference px: box + shadow
        const val WIN_H = 420f
        private const val W = 1920f
        private const val X = 150f
        private const val Y = 110f
        private const val BOX_W = 620f
        private const val BG = 0xFF0E0F12.toInt()
        private const val LINE = 0xFF1F2126.toInt()
        private const val ICON_CELL = 6f
        private const val PAD_T = 22f; private const val PAD_R = 30f; private const val PAD_B = 22f; private const val PAD_L = 26f
        private const val BAR = 4f
        private const val GAP_X = 26f; private const val GAP_Y = 10f
    }

    var onDone: (() -> Unit)? = null

    private var title = ""
    private var body = ""
    private var src = ""
    private var bar = Dots.FG
    private var bodyLayout: StaticLayout? = null

    // css: transform .45s cubic-bezier(.2,.8,.2,1), opacity .3s (ease)
    private val slideEase = PathInterpolator(.2f, .8f, .2f, 1f)
    private val fadeEase = PathInterpolator(.25f, .1f, .25f, 1f)
    private var t = 0f           // 0 = hidden (-130%), 1 = in place
    private var opacity = 0f
    private var anim: ValueAnimator? = null
    private val hide = Runnable { slide(false) }

    private val mono = Typeface.create(Typeface.MONOSPACE, 400, false)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono; textSize = 22f; letterSpacing = 0.08f; color = Dots.FG
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono; textSize = 20f; color = Dots.MUTE
    }
    private val srcPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono; textSize = 14f; letterSpacing = 0.14f; color = Dots.DIM; textAlign = Paint.Align.RIGHT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BG
        setShadowLayer(60f, 0f, 20f, 0x99000000.toInt())  // box-shadow 0 20px 60px rgba(0,0,0,.6)
    }
    private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = LINE }
    private val tz = TimeZone.getDefault()

    fun show(title: String, body: String, source: String, brand: Int) {
        this.title = title.uppercase(Locale.US)
        this.body = body
        val time = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(Date())
        src = (if (source.isBlank()) time else "$source · $time").uppercase(Locale.US)
        bar = brand
        val textW = BOX_W - BAR - PAD_L - PAD_R - 9 * ICON_CELL - GAP_X
        bodyLayout = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, textW.toInt())
            .setLineSpacing(0f, 1.4f * 20f / bodyPaint.fontSpacing)
            .setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        removeCallbacks(hide)
        if (t < 1f) slide(true) else invalidate()
        postDelayed(hide, Toaster.HOLD_MS)
    }

    private fun ms(d: Long): Long {
        val s = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return if (s > 0f) (d / s).toLong() else d
    }

    private fun slide(inward: Boolean) {
        anim?.cancel()
        val fromT = t; val fromO = opacity
        val toT = if (inward) 1f else 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms(450)
            interpolator = null
            addUpdateListener {
                val ms = it.animatedFraction * 450f
                t = fromT + (toT - fromT) * slideEase.getInterpolation(it.animatedFraction)
                opacity = fromO + (toT - fromO) * fadeEase.getInterpolation(minOf(1f, ms / 300f))
                invalidate()
            }
            if (!inward) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { if (t == 0f) onDone?.invoke() }
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (opacity <= 0f) return
        val k = resources.displayMetrics.widthPixels / W
        c.save()
        c.scale(k, k)
        val layout = bodyLayout
        val titleH = titlePaint.fontSpacing
        val textH = titleH + GAP_Y + (layout?.height ?: 0)
        val iconH = 9 * ICON_CELL
        val innerH = maxOf(iconH, textH)
        val boxH = PAD_T + innerH + PAD_B
        c.translate(X - 1.3f * BOX_W * (1 - t), Y)
        val a = (255 * opacity).toInt()
        c.saveLayerAlpha(-80f, -60f, BOX_W + 80f, boxH + 120f, a)
        c.drawRect(0f, 0f, BOX_W, boxH, shadow)
        c.drawRect(.5f, .5f, BOX_W - .5f, boxH - .5f, line)
        fill.color = bar
        c.drawRect(0f, 0f, BAR, boxH, fill)
        val iconX = BAR + PAD_L
        Dots.icon(c, "bell", iconX, PAD_T + (innerH - iconH) / 2, ICON_CELL, Dots.FG, true)
        val tx = iconX + iconH + GAP_X
        var ty = PAD_T + (innerH - textH) / 2
        c.drawText(TextUtils.ellipsize(title, titlePaint, BOX_W - tx - PAD_R - 140f, TextUtils.TruncateAt.END).toString(),
            tx, ty - titlePaint.ascent(), titlePaint)
        ty += titleH + GAP_Y
        if (layout != null) { c.save(); c.translate(tx, ty); layout.draw(c); c.restore() }
        c.drawText(src, BOX_W - 26f, 14f - srcPaint.ascent(), srcPaint)
        c.restore()
        c.restore()
    }
}
