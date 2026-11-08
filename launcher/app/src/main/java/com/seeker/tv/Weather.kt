package com.seeker.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Current conditions from Open-Meteo (no key) for the WEATHER LOCATION setting (default Denver).
 * The location and the last result are cached in prefs.
 */
class Weather(ctx: Context) {
    data class Now(
        val icon: String, val temp: String, val at: Long,
        val sunriseAt: Long = 0L, val sunsetAt: Long = 0L, val timezone: String? = null,
    )
    data class Place(val name: String, val lat: Double, val lon: Double)
    enum class Unit(val apiValue: String, val label: String) {
        CELSIUS("celsius", "CELSIUS"), FAHRENHEIT("fahrenheit", "FAHRENHEIT");

        companion object {
            fun from(value: String?): Unit = values().firstOrNull { it.apiValue == value } ?: CELSIUS
        }
    }

    private val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val fetchLock = Any()
    private var fetching = false
    private var pendingFetch: ((Now) -> kotlin.Unit)? = null

    /** Cached reading, or a placeholder on the first start or after a unit change. */
    fun cached(): Now {
        val at = prefs.getLong("at", 0L)
        val sun = sunData()
        if (prefs.getString("reading_unit", null) != unit().apiValue || at == 0L)
            return Now("cloud", "--\u00B0", 0L, sun.first, sun.second, sun.third)
        return Now(prefs.getString("icon", null) ?: "cloud", prefs.getString("temp", null) ?: "--\u00B0", at, sun.first, sun.second, sun.third)
    }

    private fun sunData() = Triple(prefs.getLong("sunrise_at", 0L), prefs.getLong("sunset_at", 0L), prefs.getString("timezone", null))

    fun isStale(): Boolean = cached().at == 0L || System.currentTimeMillis() - prefs.getLong("at", 0L) > REFRESH_MS

    fun unit(): Unit = Unit.from(prefs.getString("temperature_unit", null))

    fun setUnit(unit: Unit) = setUnit(prefs, unit)

    fun place(): Place = place(prefs)

    /** Stores a new location and forgets the old reading, so the next fetch is for the new place. */
    fun setPlace(p: Place) = setPlace(prefs, p)

    fun fetch(done: (Now) -> kotlin.Unit) {
        synchronized(fetchLock) {
            if (fetching) { pendingFetch = done; return }
            fetching = true
        }
        Thread({
            try {
                val requestedUnit = unit()
                val p = place()
                val url = String.format(Locale.US, FORECAST, p.lat, p.lon, requestedUnit.apiValue)
                val response = JSONObject(get(url))
                val cur = response.getJSONObject("current")
                val timezone = response.optString("timezone").takeIf { it.isNotBlank() }
                val daily = response.optJSONObject("daily")
                val sunriseAt = daily?.optJSONArray("sunrise")?.optString(0)?.let { parseLocalTime(it, timezone) } ?: 0L
                val sunsetAt = daily?.optJSONArray("sunset")?.optString(0)?.let { parseLocalTime(it, timezone) } ?: 0L
                val now = Now(
                    iconFor(cur.getInt("weather_code")),
                    "${cur.getDouble("temperature_2m").roundToInt()}\u00B0",
                    System.currentTimeMillis(),
                    sunriseAt,
                    sunsetAt,
                    timezone,
                )
                if (sunriseAt > 0 && sunsetAt > 0) prefs.edit().putLong("sunrise_at", sunriseAt)
                    .putLong("sunset_at", sunsetAt).putString("timezone", timezone).apply()
                if (unit() == requestedUnit) {
                    prefs.edit().putString("icon", now.icon).putString("temp", now.temp)
                        .putString("reading_unit", requestedUnit.apiValue).putLong("at", now.at).apply()
                    main.post { if (unit() == requestedUnit) done(now) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "weather fetch failed, keeping cached: $e")
            } finally {
                val next = synchronized(fetchLock) {
                    fetching = false
                    pendingFetch.also { pendingFetch = null }
                }
                if (next != null) fetch(next)
            }
        }, "seeker-weather").start()
    }

    /** Looks up a city name with Open-Meteo's geocoder. [done] gets null when nothing matched. */
    fun lookup(query: String, done: (Place?) -> kotlin.Unit) {
        Thread({
            val p = try {
                val r = JSONObject(get(GEOCODE + URLEncoder.encode(query.trim(), "UTF-8"))).optJSONArray("results")
                r?.optJSONObject(0)?.let {
                    Place(it.getString("name").uppercase(Locale.US), it.getDouble("latitude"), it.getDouble("longitude"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "location lookup failed: $e")
                null
            }
            main.post { done(p) }
        }, "seeker-geocode").start()
    }

    companion object {
        private const val TAG = "SeekerTV"
        const val REFRESH_MS = 20 * 60 * 1000L
        val DEFAULT = Place("DENVER", 39.7392, -104.9903)
        private const val FORECAST = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
            "&current=temperature_2m,weather_code&daily=sunrise,sunset&forecast_days=1&temperature_unit=%s&timezone=auto"
        private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="

        fun place(prefs: android.content.SharedPreferences): Place {
            val name = prefs.getString("place", null) ?: return DEFAULT
            return Place(name, prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble())
        }

        fun setPlace(prefs: android.content.SharedPreferences, p: Place) {
            prefs.edit().putString("place", p.name).putFloat("lat", p.lat.toFloat()).putFloat("lon", p.lon.toFloat())
                .remove("icon").remove("temp").remove("reading_unit").remove("sunrise_at").remove("sunset_at")
                .remove("timezone").putLong("at", 0L).apply()
        }

        fun unit(prefs: android.content.SharedPreferences): Unit = Unit.from(prefs.getString("temperature_unit", null))

        fun setUnit(prefs: android.content.SharedPreferences, unit: Unit) {
            prefs.edit().putString("temperature_unit", unit.apiValue).remove("icon").remove("temp")
                .remove("reading_unit").putLong("at", 0L).apply()
        }

        private fun get(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        private fun parseLocalTime(value: String, timezone: String?): Long = try {
            try { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            catch (_: Exception) {
                LocalDateTime.parse(value).atZone(timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        } catch (_: Exception) { 0L }

        /** WMO weather_code to the five mockup icons. */
        fun iconFor(code: Int): String = when (code) {
            0, 1 -> "sun"
            2, 3, 45, 48 -> "cloud"
            in 51..67, in 80..82 -> "rain"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "storm"
            else -> "cloud"
        }
    }
}
