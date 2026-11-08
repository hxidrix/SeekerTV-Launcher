package com.seeker.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps a receiver for com.seeker.tv.NOTIFY alive and shows each one as a toast (M4).
 * Foreground so it survives in the background; the receiver is registered here, not in the manifest,
 * because manifest receivers don't get implicit broadcasts (the plain `am broadcast -a ...` over adb).
 */
class NotifyService : Service() {

    companion object {
        private const val TAG = "SeekerTV"
        const val ACTION = "com.seeker.tv.NOTIFY"
        /** `am broadcast -a com.seeker.tv.MODE --es mode wheel|flap|invaders|hunt`: stores HOME MODE. */
        const val ACTION_MODE = "com.seeker.tv.MODE"
        /** WHEEL plus the game modes compiled into this flavor. */
        val MODES = listOf("WHEEL") + BuiltModes.GAMES.map { it.name }
        /**
         * `am broadcast -a com.seeker.tv.HOME_ALIAS --ez enabled true|false`: turns the `.Home` alias on/off.
         * Shell may not change component state on this TV (`pm enable` → SecurityException); the app may.
         */
        const val ACTION_HOME_ALIAS = "com.seeker.tv.HOME_ALIAS"
        /**
         * `am broadcast -a com.seeker.tv.SET --es name ALEX` sets NAME;
         * `--es place PARIS --es lat 48.8566 --es lon 2.3522` sets WEATHER LOCATION. Either or both.
         */
        const val ACTION_SET = "com.seeker.tv.SET"
        private const val CHANNEL = "seeker"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, NotifyService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "notify service start failed: $e")
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == ACTION_MODE) {
                val mode = i.getStringExtra("mode")?.uppercase()
                if (mode !in MODES) { Log.w(TAG, "MODE: ignored '${i.getStringExtra("mode")}'"); return }
                c.getSharedPreferences("seeker", MODE_PRIVATE).edit().putString("home_mode", mode).apply()
                Log.i(TAG, "MODE: home_mode = $mode")
                return
            }
            if (i.action == ACTION_HOME_ALIAS) {
                if (!i.hasExtra("enabled")) { Log.w(TAG, "HOME_ALIAS: no 'enabled' extra"); return }
                val on = i.getBooleanExtra("enabled", false)
                c.packageManager.setComponentEnabledSetting(
                    ComponentName(c, "com.seeker.tv.Home"),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
                Log.i(TAG, "HOME_ALIAS: .Home enabled = $on")
                return
            }
            if (i.action == ACTION_SET) {
                val prefs = c.getSharedPreferences("seeker", MODE_PRIVATE)
                i.getStringExtra("name")?.let {
                    prefs.edit().putString("name", MainActivity.cleanName(it)).apply()
                    Log.i(TAG, "SET: name")
                }
                val place = i.getStringExtra("place")
                val lat = i.getStringExtra("lat")?.toDoubleOrNull()
                val lon = i.getStringExtra("lon")?.toDoubleOrNull()
                if (place != null && lat != null && lon != null) {
                    Weather.setPlace(c.getSharedPreferences("weather", MODE_PRIVATE), Weather.Place(place.uppercase(), lat, lon))
                    prefs.edit().putLong("weather_rev", System.currentTimeMillis()).apply()  // MainActivity refetches
                    Log.i(TAG, "SET: weather location")
                } else if (place != null || lat != null || lon != null) {
                    Log.w(TAG, "SET: weather location needs place, lat and lon")
                }
                return
            }
            val title = i.getStringExtra("title") ?: return
            Log.i(TAG, "NOTIFY: $title")
            Toaster.show(c, title, i.getStringExtra("body") ?: "", i.getStringExtra("source") ?: "")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Seeker TV", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Seeker TV")
            .setContentText("Listening for notifications")
            .build()
        startForeground(1, n)
        val filter = IntentFilter(ACTION).apply { addAction(ACTION_MODE); addAction(ACTION_HOME_ALIAS); addAction(ACTION_SET) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Starts the service after a reboot (or an `adb install -r`), so toasts work before anyone opens the launcher. */
    class Boot : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) start(c)
        }
    }
}
