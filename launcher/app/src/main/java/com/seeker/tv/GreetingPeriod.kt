package com.seeker.tv

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Day-part labels and time bands used by the optional home-screen greeting. */
enum class GreetingPeriod(val label: String, val preferenceKey: String, val defaultText: String) {
    DAWN("DAWN", "greeting_dawn", "GOOD DAWN"),
    MORNING("MORNING", "greeting_morning", "GOOD MORNING"),
    NOON("NOON", "greeting_noon", "GOOD DAY"),
    AFTERNOON("AFTERNOON", "greeting_afternoon", "GOOD AFTERNOON"),
    EVENING("EVENING", "greeting_evening", "GOOD EVENING"),
    NIGHT("NIGHT", "greeting_night", "GOOD NIGHT"),
    MIDNIGHT("MIDNIGHT", "greeting_midnight", "GOOD NIGHT");

    companion object {
        fun current(nowMillis: Long, sunriseMillis: Long, sunsetMillis: Long, timezone: String?): GreetingPeriod {
            val zone = try { timezone?.let(ZoneId::of) ?: ZoneId.systemDefault() }
            catch (_: Exception) { ZoneId.systemDefault() }
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
            val time = now.toLocalTime()
            val sunrise = if (validForDate(sunriseMillis, now, zone)) Instant.ofEpochMilli(sunriseMillis).atZone(zone).toLocalTime() else null
            val sunset = if (validForDate(sunsetMillis, now, zone)) Instant.ofEpochMilli(sunsetMillis).atZone(zone).toLocalTime() else null

            if (sunrise == null || sunset == null) return when {
                time >= LocalTime.of(5, 0) && time < LocalTime.of(6, 0) -> DAWN
                time >= LocalTime.of(6, 0) && time < LocalTime.of(11, 0) -> MORNING
                time >= LocalTime.of(11, 0) && time < LocalTime.of(13, 0) -> NOON
                time >= LocalTime.of(13, 0) && time < LocalTime.of(17, 0) -> AFTERNOON
                time >= LocalTime.of(17, 0) && time < LocalTime.of(20, 0) -> EVENING
                time >= LocalTime.of(20, 0) && time < LocalTime.of(23, 0) -> NIGHT
                else -> MIDNIGHT
            }

            val dawnStart = sunrise.minusHours(1)
            val eveningStart = sunset.minusHours(1)
            val eveningEnd = sunset.plusHours(1)
            return when {
                time >= dawnStart && time < sunrise -> DAWN
                time >= sunrise && time < LocalTime.of(11, 0) -> MORNING
                time >= LocalTime.of(11, 0) && time < LocalTime.of(13, 0) -> NOON
                time >= LocalTime.of(13, 0) && time < eveningStart -> AFTERNOON
                time >= eveningStart && time < eveningEnd -> EVENING
                time >= eveningEnd && time < LocalTime.of(23, 0) -> NIGHT
                else -> MIDNIGHT
            }
        }

        private fun validForDate(millis: Long, now: java.time.ZonedDateTime, zone: ZoneId): Boolean =
            millis > 0 && Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == now.toLocalDate()
    }
}
