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

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Pure logic (no Android): a nightly quiet window in the phone's local time, e.g.
 * 22:00–07:00. Rules that aren't allowed to wake you have their alerts held until the
 * window ends. Uses local time on purpose: sleep follows you when you travel.
 */
data class QuietHours(
    val enabled: Boolean = false,
    val start: LocalTime = LocalTime.of(22, 0),
    val end: LocalTime = LocalTime.of(7, 0),
) {
    fun isQuiet(t: LocalTime): Boolean {
        if (!enabled || start == end) return false
        return if (start < end) {
            !t.isBefore(start) && t.isBefore(end) // same-day window, e.g. 13:00–15:00
        } else {
            !t.isBefore(start) || t.isBefore(end) // crosses midnight, e.g. 22:00–07:00
        }
    }

    /** When the current quiet window ends (the next [end] after [now]). */
    fun nextEnd(now: ZonedDateTime): ZonedDateTime {
        var candidate = now.with(end).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate
    }
}

/** Should this email alert now? Yes if quiet hours are off, or any rule it matched may wake you. */
fun shouldAlertNow(
    matchedRules: List<String>,
    wakers: Set<String>,
    quiet: QuietHours,
    now: LocalTime,
): Boolean = !quiet.isQuiet(now) || matchedRules.any { it in wakers } || matchedRules.isEmpty()
