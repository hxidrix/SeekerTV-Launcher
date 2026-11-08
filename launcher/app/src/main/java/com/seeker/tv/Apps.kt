package com.seeker.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

/** One row on the wheel. [parts] = two-tone name pieces (text, color), used when selected. */
class AppEntry(
    val key: String,
    val name: String,
    val color: Int,
    val parts: List<Pair<String, Int>>? = null,
    val intent: Intent,
    val component: ComponentName? = null,
) {
    val dots: String = Dots.clean(name)
}

private class Brand(val name: String, val color: Int, val parts: List<Pair<String, Int>>? = null)

object Apps {
    const val SETTINGS_COLOR = 0xFF9AA3AD.toInt()
    private const val FALLBACK_COLOR = 0xFF9AA3AD.toInt()

    // Brand colors from DESIGN-SPEC.md, keyed by package. Order here is the default wheel order.
    private val BRANDS = linkedMapOf(
        "com.google.android.youtube.tv" to Brand(
            "YouTube", 0xFFFF0000.toInt(),
            listOf("YOU" to 0xFFFF0000.toInt(), "TUBE" to 0xFFFFFFFF.toInt()),
        ),
        "com.netflix.ninja" to Brand("Netflix", 0xFFE50914.toInt()),
        "com.hulu.livingroomplus" to Brand("Hulu", 0xFF1CE783.toInt()),
        "com.amazon.amazonvideo.livingroom" to Brand("Prime Video", 0xFF00A8E1.toInt()),
        "com.disney.disneyplus" to Brand("Disney+", 0xFF2E6BFF.toInt()),
        "com.wbd.stream" to Brand("Max", 0xFF3D34FF.toInt()),
        "com.peacocktv.peacockandroid" to Brand("Peacock", 0xFFFFC72C.toInt()),
        "com.apple.atve.androidtv.appletv" to Brand("Apple TV", 0xFFF5F5F7.toInt()),
    )

    // Never listed: ourselves, and Settings (SYSTEM SETTINGS lives in Seeker's settings screen).
    private val EXCLUDED = setOf("com.seeker.tv", "com.android.tv.settings")

    // Hidden by default; any of them can come back from HIDDEN APPS (spec v4).
    private val DEFAULT_HIDDEN = setOf(
        "com.hisense.tv.protector",
        "com.spocky.projengmenu",
        "com.mediatek.wwtv.tvcenter",
        "com.vt.tv.mediacenter",
        "com.google.android.katniss",       // Assistant launcher entry, if it ever shows
        "com.google.android.apps.tv.launcherx",
    )

    // The gray group at the bottom of the wheel.
    const val PLAY_STORE = "com.android.vending"

    /** Every launchable app: brands first, the rest A-Z, Play Store last. Ignores hidden/saved order. */
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        var infos = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
        var category = Intent.CATEGORY_LEANBACK_LAUNCHER
        if (infos.isEmpty()) {
            infos = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            category = Intent.CATEGORY_LAUNCHER
        }
        val colors = ColorCache(ctx)
        val byPkg = LinkedHashMap<String, AppEntry>()
        for (ri in infos) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName
            if (pkg in EXCLUDED || byPkg.containsKey(pkg) || !ai.enabled || !ai.applicationInfo.enabled) continue
            val cn = ComponentName(pkg, ai.name)
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val brand = BRANDS[pkg]
            val entry = if (brand != null) {
                AppEntry(pkg, brand.name, brand.color, brand.parts, intent, cn)
            } else {
                val label = Dots.clean(ri.loadLabel(pm).toString()).ifEmpty { Dots.clean(pkg.substringAfterLast('.')) }
                val color = if (pkg == PLAY_STORE) SETTINGS_COLOR else colors.colorFor(pkg) { iconOf(ctx, cn) }
                AppEntry(pkg, label, color, null, intent, cn)
            }
            byPkg[pkg] = entry
        }
        colors.save()
        val out = ArrayList<AppEntry>()
        for (pkg in BRANDS.keys) byPkg.remove(pkg)?.let { out.add(it) }
        val store = byPkg.remove(PLAY_STORE)
        out.addAll(byPkg.values.sortedBy { it.name })
        store?.let { out.add(it) }
        return out
    }

    /**
     * What goes on the wheel: [all] minus hidden, in the saved order. Apps not in the saved order yet
     * (new installs, just unhidden) go at the end, above Play Store.
     */
    fun wheel(all: List<AppEntry>, prefs: SharedPreferences): List<AppEntry> {
        val hidden = hidden(prefs)
        val visible = all.filter { it.key !in hidden }
        return ordered(visible, prefs).map { app ->
            val name = prefs.getString("title_${app.key}", null)?.takeIf { it.isNotBlank() } ?: app.name
            val color = prefs.getInt("app_color_${app.key}", app.color)
            if (name == app.name && color == app.color) app
            else AppEntry(app.key, name, color, null, app.intent, app.component)
        }
    }

    /** All apps in saved order, including hidden apps for the Settings ordering screen. */
    fun ordered(all: List<AppEntry>, prefs: SharedPreferences): List<AppEntry> {
        val order = prefs.getString("order", null)?.split(',')?.filter { it.isNotEmpty() } ?: return all
        val byKey = all.associateBy { it.key }
        val out = order.mapNotNull { byKey[it] }.toMutableList()
        val fresh = all.filter { it.key !in order }
        val at = out.indexOfFirst { it.key == PLAY_STORE }.takeIf { it >= 0 } ?: out.size
        out.addAll(at, fresh)
        return out
    }

    /** One-time, minimal migration for saved pre-YouTube-first orders. */
    fun migrateYoutubeFirst(prefs: SharedPreferences) {
        if (prefs.getBoolean("youtube_first_migrated", false)) return
        val old = prefs.getString("order", null)?.split(',')?.filter { it.isNotEmpty() }
        if (old != null) {
            val youtube = old.indexOf("com.google.android.youtube.tv")
            val netflix = old.indexOf("com.netflix.ninja")
            if (youtube >= 0 && netflix >= 0 && youtube > netflix) {
                val updated = old.toMutableList().apply {
                    val key = removeAt(youtube)
                    add(indexOf("com.netflix.ninja"), key)
                }
                prefs.edit().putString("order", updated.joinToString(",")).apply()
            }
        }
        prefs.edit().putBoolean("youtube_first_migrated", true).apply()
    }

    fun hidden(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet("hidden", null) ?: DEFAULT_HIDDEN

    fun saveOrder(prefs: SharedPreferences, keys: List<String>) {
        prefs.edit().putString("order", keys.joinToString(",")).apply()
    }

    /** Hide or unhide. Unhiding drops the app from the saved order so [wheel] puts it above Play Store. */
    fun setHidden(prefs: SharedPreferences, key: String, hide: Boolean) {
        val h = hidden(prefs).toMutableSet()
        if (hide) h.add(key) else h.remove(key)
        val e = prefs.edit().putStringSet("hidden", h)
        if (!hide) prefs.getString("order", null)?.let { o ->
            e.putString("order", o.split(',').filter { it.isNotEmpty() && it != key }.joinToString(","))
        }
        e.apply()
    }

    /** Hisense inputs picker (com.vt.tv.input answers VIEW_INPUTS), else the stock TV input picker. */
    fun inputsIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        val candidates = listOf(
            Intent("com.android.tv.action.VIEW_INPUTS").setPackage("com.vt.tv.input"),
            Intent("com.android.tv.action.VIEW_INPUTS"),
        )
        for (i in candidates) {
            val ri = pm.resolveActivity(i, 0) ?: continue
            val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
            return Intent(i).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return null
    }

    /** The app's TV banner if it has one, else its icon. */
    fun iconOf(ctx: Context, cn: ComponentName): Drawable? {
        val pm = ctx.packageManager
        return try {
            pm.getActivityBanner(cn) ?: pm.getApplicationBanner(cn.packageName) ?: pm.getActivityIcon(cn)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Dominant color of an icon: hue histogram of saturated, not-too-dark pixels on a 24x24 copy.
     * Falls back to the Settings gray for monochrome icons.
     */
    fun dominantColor(d: Drawable?): Int {
        d ?: return FALLBACK_COLOR
        val n = 24
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, n, n)
        d.draw(c)
        val bins = 24
        val weight = FloatArray(bins)
        val sumR = FloatArray(bins); val sumG = FloatArray(bins); val sumB = FloatArray(bins)
        val hsv = FloatArray(3)
        for (y in 0 until n) for (x in 0 until n) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue
            val b = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            val w = hsv[1] * hsv[2]
            weight[b] += w
            sumR[b] += Color.red(p) * w; sumG[b] += Color.green(p) * w; sumB[b] += Color.blue(p) * w
        }
        bmp.recycle()
        val best = weight.indices.maxByOrNull { weight[it] } ?: return FALLBACK_COLOR
        if (weight[best] < 4f) return FALLBACK_COLOR
        val w = weight[best]
        return Color.rgb((sumR[best] / w).toInt(), (sumG[best] / w).toInt(), (sumB[best] / w).toInt())
    }

    /** Icon colors cached by package + version, so we don't rasterize icons on every start. */
    private class ColorCache(ctx: Context) {
        private val prefs = ctx.getSharedPreferences("colors", Context.MODE_PRIVATE)
        private val pm = ctx.packageManager
        private val edits = HashMap<String, String>()

        fun colorFor(pkg: String, icon: () -> Drawable?): Int {
            val ver = try { pm.getPackageInfo(pkg, 0).longVersionCode } catch (e: Exception) { 0L }
            val saved = prefs.getString(pkg, null)
            if (saved != null) {
                val (v, col) = saved.split(':').let { it[0].toLongOrNull() to it.getOrNull(1)?.toIntOrNull() }
                if (v == ver && col != null) return col
            }
            val col = dominantColor(icon())
            edits[pkg] = "$ver:$col"
            return col
        }

        fun save() {
            if (edits.isEmpty()) return
            val e = prefs.edit()
            for ((k, v) in edits) e.putString(k, v)
            e.apply()
        }
    }
}
