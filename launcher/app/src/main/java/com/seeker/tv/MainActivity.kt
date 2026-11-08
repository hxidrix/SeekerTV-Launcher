package com.seeker.tv

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.ListView
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity(), SeekerView.Host, SettingsView.Host, GameHost {

    companion object {
        private const val TAG = "SeekerTV"
        private const val MAX_CRASHES = 2
        private const val REQUEST_BACKGROUND = 7321
        private val ANIM_KEYS = arrayOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        const val DEFAULT_NAME = "SEEKER"
        const val MAX_NAME = 12
        const val DEFAULT_GREETING = "HELLO SEEKER"
        const val MAX_GREETING = 24
        /** The characters the dot-matrix and pixel fonts can draw. */
        private val NAME_CHARS = Regex("[^A-Z0-9 +\\-.!&]")

        /** NAME setting: upper case, drawable characters only, at most [MAX_NAME]. Blank means the default. */
        fun cleanName(raw: String?): String =
            raw.orEmpty().uppercase(Locale.US).replace(NAME_CHARS, "").trim().take(MAX_NAME).trim().ifEmpty { DEFAULT_NAME }

        /** Full home-screen greeting, limited to the dot font's drawable characters. */
        fun cleanGreeting(raw: String?): String =
            Dots.clean(raw.orEmpty().uppercase(Locale.US)).take(MAX_GREETING).trim().ifEmpty { DEFAULT_GREETING }
    }

    private lateinit var prefs: SharedPreferences
    private var view: SeekerView? = null
    private var settings: SettingsView? = null
    /** Game screens built so far, by HOME MODE name. Only the flavor's BuiltModes can appear here. */
    private val games = HashMap<String, GameScreen>()
    /** Settings > APP LIST in a game mode: the wheel until the next launch, BACK or HOME. */
    private var wheelVisit = false
    private var shown: android.view.View? = null
    private var allApps: List<AppEntry> = emptyList()
    private var weather: Weather? = null
    private var customBackground: android.graphics.Bitmap? = null
    private var decodedBackgroundUri: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false

    private val weatherTick = object : Runnable {
        override fun run() {
            weather?.fetch { view?.setWeather(it); view?.setGreeting(greetingText()) }
            main.postDelayed(this, Weather.REFRESH_MS)
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { view?.setGreeting(greetingText()); view?.tick() }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { reloadApps() }
    }

    /** HOME MODE can change from settings or from the com.seeker.tv.MODE broadcast (same process). */
    private val modeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "greeting" || key == "name" || key == "greeting_mode" || key?.startsWith("greeting_") == true) {
            main.post { view?.setGreeting(greetingText()); settings?.invalidate() }
            return@OnSharedPreferenceChangeListener
        }
        if (key == "weather_rev") { main.post { refetchWeather() }; return@OnSharedPreferenceChangeListener }
        if (key != "home_mode") return@OnSharedPreferenceChangeListener
        main.post {
            val s = settings
            if (s != null && shown === s) s.modeChanged()
            else if (view?.isLaunching != true) { wheelVisit = false; showHome() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("seeker", MODE_PRIVATE)
        Apps.migrateYoutubeFirst(prefs)
        installCrashCounter()
        Log.i(TAG, "onCreate")
        Sfx.enabled = soundOn()
        Sfx.init(applicationContext)
        applyAnimationScales()

        val crashes = prefs.getInt("crashes", 0)
        if (crashes >= MAX_CRASHES) {
            Log.e(TAG, "$crashes crashes in a row, showing the fallback list")
            showFallback()
            return
        }
        try {
            val v = SeekerView(this, this)
            view = v
            weather = Weather(this)
            v.setGreeting(greetingText())
            v.setWeather(weather!!.cached())
            allApps = Apps.load(this)
            v.setApps(Apps.wheel(allApps, prefs))
            applyAppearance()
            showHome()
        } catch (e: Exception) {
            Log.e(TAG, "startup failed, showing the fallback list", e)
            view = null
            showFallback()
            return
        }
        NotifyService.start(this)
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        prefs.registerOnSharedPreferenceChangeListener(modeListener)
    }

    override fun onStart() {
        super.onStart()
        if (view == null) return
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        view?.tick()
        main.removeCallbacks(weatherTick)
        if (weather?.isStale() != false) main.post(weatherTick) else main.postDelayed(weatherTick, Weather.REFRESH_MS)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Toaster.front = WeakReference(this)
        applyAnimationScales()
    }

    override fun onPause() {
        resumed = false
        if (Toaster.front?.get() === this) Toaster.front = null
        super.onPause()
    }

    override fun onStop() {
        if (view != null) {
            unregisterReceiver(timeReceiver)
            main.removeCallbacks(weatherTick)
            view?.resetLaunch()  // come back to a clean wheel
            view?.resetModes()
            gamesToStart()       // the games: HOME always shows START, never mid-game
            if (wheelVisit) { wheelVisit = false; if (shown === view) showHome() }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (view != null) {
            unregisterReceiver(packageReceiver)
            prefs.unregisterOnSharedPreferenceChangeListener(modeListener)
        }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while we're already up (once Seeker is HOME): back to the wheel
        if (!intent.hasCategory(Intent.CATEGORY_HOME) || view == null) return
        wheelVisit = false
        gamesToStart()
        if (shown !== view || homeMode() != "WHEEL") showHome()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "key $event")
        val handled = when (val cur = shown) {
            is SettingsView -> cur.onKey(event)
            is GameScreen -> cur.onKey(event)
            else -> view?.onKey(event) == true
        }
        return handled || super.dispatchKeyEvent(event)
    }

    // ---------- SeekerView.Host ----------
    override fun launch(entry: AppEntry) {
        val intent = entry.intent
        try {
            val opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0)
            startActivity(intent, opts.toBundle())
            // If nothing came up in front of us, don't leave the color screen hanging.
            main.postDelayed({ if (resumed) { view?.resetLaunch(); gamesToStart() } }, 4000)
        } catch (e: Exception) {
            Log.e(TAG, "launch failed: ${entry.key}", e)
            view?.resetLaunch()
            gamesToStart()
        }
    }

    override fun openSettings() {
        val s = settings ?: SettingsView(this, this).also { settings = it }
        s.open()
        show(s)
    }

    override fun hide(entry: AppEntry) = Apps.setHidden(prefs, entry.key, true)

    override fun saveOrder(keys: List<String>) = Apps.saveOrder(prefs, keys)

    // ---------- SettingsView.Host ----------
    override fun openSystemSettings() = start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun openInputs() {
        val i = Apps.inputsIntent(this)
        if (i == null) Log.w(TAG, "no inputs activity on this TV") else start(i)
    }

    override fun greeting(): String = cleanGreeting(
        prefs.getString("greeting", null) ?: "HELLO ${cleanName(prefs.getString("name", null))}",
    )

    override fun editGreeting() = askText(
        "GREETING", "Shown at the top of the home screen. Up to $MAX_GREETING characters.", greeting(),
    ) {
        prefs.edit().putString("greeting", cleanGreeting(it)).apply()  // the listener redraws the greeting
    }

    override fun timeGreetingsEnabled(): Boolean = prefs.getBoolean("time_greetings", false)

    override fun toggleTimeGreetings() {
        prefs.edit().putBoolean("time_greetings", !timeGreetingsEnabled()).apply()
    }

    override fun timeGreeting(period: GreetingPeriod): String = cleanGreeting(
        prefs.getString(period.preferenceKey, null) ?: period.defaultText,
    )

    override fun editTimeGreeting(period: GreetingPeriod) = askText(
        "${period.label} GREETING", "Shown during ${period.label.lowercase(Locale.US)}. Up to $MAX_GREETING characters.",
        timeGreeting(period),
    ) { prefs.edit().putString(period.preferenceKey, cleanGreeting(it)).apply() }

    override fun weatherPlace(): String = (weather?.place() ?: Weather.DEFAULT).name

    override fun temperatureUnit(): Weather.Unit = weather?.unit() ?: Weather.Unit.CELSIUS

    override fun toggleTemperatureUnit() {
        val w = weather ?: return
        val next = if (w.unit() == Weather.Unit.CELSIUS) Weather.Unit.FAHRENHEIT else Weather.Unit.CELSIUS
        w.setUnit(next)
        settings?.invalidate()
        refetchWeather()
    }

    override fun customizeApps() {
        if (allApps.isEmpty()) return
        val entries = Apps.ordered(allApps, prefs)
        val colorPresets = listOf(
            "WHITE" to android.graphics.Color.WHITE,
            "RED" to android.graphics.Color.rgb(244, 67, 54),
            "ORANGE" to android.graphics.Color.rgb(255, 152, 0),
            "YELLOW" to android.graphics.Color.rgb(255, 235, 59),
            "GREEN" to android.graphics.Color.rgb(76, 175, 80),
            "TEAL" to android.graphics.Color.rgb(0, 150, 136),
            "CYAN" to android.graphics.Color.rgb(0, 188, 212),
            "BLUE" to android.graphics.Color.rgb(33, 150, 243),
            "PURPLE" to android.graphics.Color.rgb(156, 39, 176),
            "PINK" to android.graphics.Color.rgb(233, 30, 99),
            "GRAY" to android.graphics.Color.rgb(158, 158, 158),
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("APP TITLES & COLORS")
            .setItems(entries.map { app -> prefs.getString("title_${app.key}", null)?.takeIf { it.isNotBlank() } ?: app.name }.toTypedArray()) { _, index ->
                val app = entries[index]
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(app.name)
                    .setItems(arrayOf("EDIT TITLE", "CHOOSE PRESET COLOR", "ENTER HEX COLOR", "RESET CUSTOMIZATION")) { _, action ->
                        when (action) {
                            0 -> askText("APP TITLE", "Dot matrix characters only; up to 24 characters.", prefs.getString("title_${app.key}", app.name) ?: app.name) { raw ->
                                val cleaned = Dots.clean(raw).take(24)
                                prefs.edit().putString("title_${app.key}", cleaned).apply()
                                refreshCustomizedApps()
                            }
                            1 -> android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("APP COLOR")
                                .setItems(colorPresets.map { it.first }.toTypedArray()) { _, colorIndex ->
                                    prefs.edit().putInt("app_color_${app.key}", colorPresets[colorIndex].second).apply()
                                    refreshCustomizedApps()
                                }.show()
                            2 -> askText("APP COLOR", "Enter a color such as #33AAFF.", String.format(Locale.US, "#%06X", 0xFFFFFF and prefs.getInt("app_color_${app.key}", app.color))) { raw ->
                                val parsed = try { android.graphics.Color.parseColor(if (raw.startsWith("#") && raw.length == 7) raw else "#$raw") } catch (_: IllegalArgumentException) { null }
                                if (parsed != null) { prefs.edit().putInt("app_color_${app.key}", parsed).apply(); refreshCustomizedApps() }
                            }
                            else -> { prefs.edit().remove("title_${app.key}").remove("app_color_${app.key}").apply(); refreshCustomizedApps() }
                        }
                    }.show()
            }.show()
    }

    private fun refreshCustomizedApps() {
        view?.setApps(Apps.wheel(allApps, prefs))
        settings?.invalidate()
    }

    override fun chooseBackground() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_BACKGROUND) } catch (e: Exception) { Log.w(TAG, "No image picker available", e) }
    }

    @Deprecated("Deprecated by Android, retained for broad TV API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BACKGROUND || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("background_uri", uri.toString()).putBoolean("background_enabled", true).apply()
            applyAppearance()
            settings?.invalidate()
        } catch (e: Exception) { Log.w(TAG, "Could not save background image", e) }
    }

    override fun hasCustomBackground(): Boolean = prefs.getString("background_uri", null) != null
    override fun customImageEnabled(): Boolean = prefs.getBoolean("background_enabled", true)
    override fun toggleCustomImage() {
        prefs.edit().putBoolean("background_enabled", !customImageEnabled()).apply()
        applyAppearance()
        settings?.invalidate()
    }
    override fun backgroundOpacity(): Int = prefs.getInt("background_opacity", 100).coerceIn(0, 100)
    override fun adjustBackgroundOpacity(delta: Int) {
        prefs.edit().putInt("background_opacity", (backgroundOpacity() + delta).coerceIn(0, 100) / 5 * 5).apply()
        applyAppearance()
        settings?.invalidate()
    }

    override fun backgroundGradientEnabled(): Boolean = prefs.getBoolean("background_gradient", true)
    override fun toggleBackgroundGradient() {
        prefs.edit().putBoolean("background_gradient", !backgroundGradientEnabled()).apply()
        applyAppearance()
        settings?.invalidate()
    }
    override fun gradientOpacity(): Int = prefs.getInt("gradient_opacity", 100).coerceIn(0, 100)
    override fun adjustGradientOpacity(delta: Int) {
        prefs.edit().putInt("gradient_opacity", (gradientOpacity() + delta).coerceIn(0, 100) / 5 * 5).apply()
        applyAppearance()
        settings?.invalidate()
    }
    override fun gradientLength(): Int = prefs.getInt("gradient_length", 100).coerceIn(20, 100)
    override fun adjustGradientLength(delta: Int) {
        prefs.edit().putInt("gradient_length", ((gradientLength() + delta).coerceIn(20, 100) / 5) * 5).apply()
        applyAppearance()
        settings?.invalidate()
    }
    override fun gradientStretch(): Int = prefs.getInt("gradient_stretch", 100).coerceIn(50, 200)
    override fun adjustGradientStretch(delta: Int) {
        prefs.edit().putInt("gradient_stretch", ((gradientStretch() + delta).coerceIn(50, 200) / 5) * 5).apply()
        applyAppearance()
        settings?.invalidate()
    }
    private fun applyAppearance() {
        val raw = prefs.getString("background_uri", null)
        if (raw != decodedBackgroundUri) {
            customBackground = raw?.let { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val sample = maxOf(1, minOf(bounds.outWidth / 1920, bounds.outHeight / 1080))
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                } catch (e: Exception) { Log.w(TAG, "Could not decode background image", e); null }
            }
            decodedBackgroundUri = raw
        }
        view?.setAppearance(customBackground, backgroundOpacity(), customImageEnabled(), backgroundGradientEnabled(), gradientOpacity(), gradientLength(), gradientStretch())
    }

    override fun editWeatherPlace() = askText("WEATHER LOCATION", "A city name, like Denver or Paris.", "") { q ->
        val w = weather ?: return@askText
        if (q.isBlank()) return@askText
        w.lookup(q) { p ->
            if (p == null) { Toaster.show(this, "WEATHER LOCATION", "No place called \"$q\".", "SEEKER"); return@lookup }
            w.setPlace(p)
            settings?.invalidate()
            refetchWeather()
        }
    }

    override fun allApps(): List<AppEntry> = allApps

    override fun orderedApps(): List<AppEntry> = Apps.ordered(allApps, prefs)

    override fun saveAppOrder(keys: List<String>) {
        Apps.saveOrder(prefs, keys)
        view?.setApps(Apps.wheel(allApps, prefs))
        games.values.forEach { it.refreshApps() }
    }

    override fun hiddenKeys(): Set<String> = Apps.hidden(prefs)

    override fun toggleHidden(entry: AppEntry) {
        Apps.setHidden(prefs, entry.key, entry.key !in Apps.hidden(prefs))
        view?.setApps(Apps.wheel(allApps, prefs))
    }

    override fun about(): List<Pair<String, String>> {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val pss = android.os.Debug.getPss() / 1024
        val w = weather?.cached()
        val wx = if (w == null || w.at == 0L) "NEVER" else
            "${w.temp} ${w.icon.uppercase()} AT ${SimpleDateFormat("h:mm a", Locale.US).apply {
                timeZone = TimeZone.getDefault() }.format(Date(w.at))}"
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName ?: "?"
        return listOf(
            "VERSION" to "$ver",
            "RAM" to "$pss MB PSS",
            "WEATHER" to wx,
            "HOME" to if (home == packageName) "YES, SEEKER IS HOME" else "NO ($home)",
        )
    }

    override fun closeSettings() = showHome()

    override fun homeModes(): List<String> = NotifyService.MODES

    override fun homeMode(): String = prefs.getString("home_mode", null)?.takeIf { it in NotifyService.MODES } ?: "WHEEL"

    override fun cycleHomeMode() {
        val next = NotifyService.MODES[(NotifyService.MODES.indexOf(homeMode()) + 1) % NotifyService.MODES.size]
        prefs.edit().putString("home_mode", next).apply()
        Log.i(TAG, "HOME MODE = $next (settings)")
    }

    override fun soundOn(): Boolean = prefs.getBoolean("sound", true)

    override fun infiniteScrollEnabled(): Boolean = prefs.getBoolean("infinite_scroll", true)

    override fun toggleInfiniteScroll() {
        prefs.edit().putBoolean("infinite_scroll", !infiniteScrollEnabled()).apply()
    }

    override fun toggleSound() {
        val on = !soundOn()
        prefs.edit().putBoolean("sound", on).apply()
        Sfx.enabled = on
        Log.i(TAG, "SOUND = ${if (on) "ON" else "OFF"}")
    }

    override fun openAppList() {
        val v = view ?: return
        wheelVisit = true
        show(v)
    }

    override fun back() {
        if (wheelVisit) { wheelVisit = false; showHome() }
    }

    // ---------- GameHost ----------
    override fun flapEntry(pkg: String): AppEntry? = allApps.firstOrNull { it.key == pkg }

    /** The app's launcher icon (not the TV banner) for the tube caps. */
    override fun flapIcon(entry: AppEntry): android.graphics.drawable.Drawable? = try {
        entry.component?.let { packageManager.getActivityIcon(it) } ?: packageManager.getApplicationIcon(entry.key)
    } catch (e: Exception) {
        null
    }

    override fun flapBest(): Int = prefs.getInt("flap_best", 0)

    override fun saveFlapBest(best: Int) = prefs.edit().putInt("flap_best", best).apply()

    override fun firstFrame() {
        // Survived startup and drew: clear the crash counter after a few quiet seconds.
        main.postDelayed({ prefs.edit().putInt("crashes", 0).apply() }, 5000)
    }

    // ---------- helpers ----------
    /** The home screen for the current HOME MODE: a game's START, or the wheel (also during an APP LIST visit). */
    private fun showHome() {
        val v = view ?: return
        when (if (wheelVisit) "WHEEL" else homeMode()) {
            "WHEEL" -> show(v)
            else -> {
                val mode = homeMode()
                val g = games[mode] ?: BuiltModes.GAMES.firstOrNull { it.name == mode }?.create?.invoke(this, this)
                    ?.also { games[mode] = it }
                if (g == null) { show(v); return }
                g.refreshApps()
                if (shown !== g) g.toStart()
                show(g as android.view.View)
            }
        }
    }

    private fun gamesToStart() = games.values.forEach { it.toStart() }

    private fun show(target: android.view.View) {
        if (shown !== target) {
            setContentView(target)
            shown = target
            // Every screen paints its own opaque background; skipping the window's saves a full-screen fill a frame.
            window.setBackgroundDrawable(null)
        }
        target.requestFocus()
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "start failed: $intent", e)
        }
    }

    private fun reloadApps() {
        val v = view ?: return
        try {
            allApps = Apps.load(this)
            v.setApps(Apps.wheel(allApps, prefs))
            games.values.forEach { it.refreshApps() }
        } catch (e: Exception) {
            Log.e(TAG, "app reload failed", e)
        }
    }

    private fun greetingText(): String {
        if (!timeGreetingsEnabled()) return greeting()
        val now = weather?.cached()
        val period = GreetingPeriod.current(System.currentTimeMillis(), now?.sunriseAt ?: 0L, now?.sunsetAt ?: 0L, now?.timezone)
        return timeGreeting(period)
    }

    /** New WEATHER LOCATION: show the placeholder until the new reading arrives. */
    private fun refetchWeather() {
        val w = weather ?: return
        view?.setWeather(w.cached())
        view?.setGreeting(greetingText())
        w.fetch { view?.setWeather(it); view?.setGreeting(greetingText()) }
    }

    /** One line of text from the TV's on-screen keyboard. [done] runs only on OK. */
    private fun askText(title: String, hint: String, current: String, done: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSelection(current.length)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> done(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> done(input.text.toString()); dialog.dismiss(); true }
        dialog.show()
        input.requestFocus()
    }

    /** These keep reverting to 1.0 on this TV; needs WRITE_SECURE_SETTINGS granted over adb. */
    private fun applyAnimationScales() {
        try {
            for (k in ANIM_KEYS) {
                if (Settings.Global.getString(contentResolver, k) != "0.5") {
                    val ok = Settings.Global.putString(contentResolver, k, "0.5")
                    Log.i(TAG, "re-applied $k = 0.5 (ok=$ok, now ${Settings.Global.getString(contentResolver, k)})")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS, can't re-apply animation scales")
        }
    }

    private fun installCrashCounter() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashCounter) return
        Thread.setDefaultUncaughtExceptionHandler(CrashCounter(prefs, prev))
    }

    private class CrashCounter(
        val prefs: SharedPreferences,
        val prev: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try { prefs.edit().putInt("crashes", prefs.getInt("crashes", 0) + 1).commit() } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** Plain list of apps, no dot matrix. Used when the real screen keeps crashing. */
    private fun showFallback() {
        val apps = try { Apps.wheel(Apps.load(this), prefs) } catch (e: Exception) { emptyList() }
        val labels = listOf("Retry Seeker TV", "Settings") + apps.map { it.name }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, pos, _ ->
            when (pos) {
                0 -> { prefs.edit().putInt("crashes", 0).commit(); recreate() }
                1 -> startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> try { startActivity(apps[pos - 2].intent) } catch (e: Exception) { Log.e(TAG, "launch failed", e) }
            }
        }
        setContentView(list)
        list.requestFocus()
    }
}
