/*
 * Yowza! — big alerts for rare messages.
 * Copyright (C) 2026 Rob Nugen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.robnugen.yowza

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * An alarm pinned to a time zone: "09:00 in Asia/Tokyo" rings at that real moment wherever
 * the phone is. [days] are weekdays *in the alarm's zone*; empty means ring once.
 */
data class ZonedAlarm(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val time: LocalTime,
    val zone: ZoneId,
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
) {
    val repeats: Boolean get() = days.isNotEmpty()

    /**
     * The next moment this alarm rings strictly after [now], or null if it's off.
     * Daylight saving in the alarm's zone: a time that doesn't exist that day (spring
     * forward) rings at the shifted time; a time that happens twice (fall back) rings once,
     * at the first.
     */
    fun nextFire(now: Instant): Instant? {
        if (!enabled) return null
        val local = now.atZone(zone)
        var date = local.toLocalDate()
        repeat(8) {
            if (!repeats || date.dayOfWeek in days) {
                val candidate = ZonedDateTime.of(date, time, zone).toInstant()
                if (candidate.isAfter(now)) return candidate
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** "09:00 JST" style, in the alarm's own zone. */
    fun describeTime(): String =
        time.format(HHMM) + " " + zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    fun describeDays(): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == WEEKDAYS -> "Weekdays"
        days == WEEKEND -> "Weekends"
        else -> DayOfWeek.entries.filter { it in days }
            .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val WEEKDAYS = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        /** "10:30 here" or "10:30 here (tomorrow)", for a fire instant seen from [here]. */
        fun describeHere(fire: Instant, here: ZoneId, now: Instant): String {
            val f = fire.atZone(here)
            val dayDiff = f.toLocalDate().toEpochDay() - now.atZone(here).toLocalDate().toEpochDay()
            val day = when (dayDiff) {
                0L -> ""
                1L -> " (tomorrow)"
                else -> " (" + f.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + ")"
            }
            return f.toLocalTime().format(HHMM) + " here" + day
        }
    }
}

object AlarmJson {
    fun encode(alarms: List<ZonedAlarm>): String {
        val arr = JSONArray()
        alarms.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("time", a.time.format(ZonedAlarm.HHMM))
                    .put("zone", a.zone.id)
                    .put("days", JSONArray(DayOfWeek.entries.filter { it in a.days }.map { it.name }))
                    .put("enabled", a.enabled),
            )
        }
        return JSONObject().put("format", "yowza-alarms").put("version", 1).put("alarms", arr).toString(2)
    }

    /** Skips entries it can't read (e.g. a zone this phone doesn't know) instead of failing. */
    fun decode(text: String): List<ZonedAlarm> {
        val arr = runCatching { JSONObject(text).getJSONArray("alarms") }.getOrElse { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val d = o.optJSONArray("days") ?: JSONArray()
                ZonedAlarm(
                    id = o.getString("id"),
                    label = o.optString("label"),
                    time = LocalTime.parse(o.getString("time")),
                    zone = ZoneId.of(o.getString("zone")),
                    days = (0 until d.length()).map { DayOfWeek.valueOf(d.getString(it)) }.toSet(),
                    enabled = o.optBoolean("enabled", true),
                )
            }.getOrNull()
        }
    }
}
