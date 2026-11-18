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

import java.time.Duration
import java.time.Instant

/**
 * Pure logic (no Android) that stops identical repeats from re-alerting.
 *
 * After a loud alert for a given sender + subject, further copies within [window] are
 * muted: no sound, no full screen, just a running count. After the window passes, the
 * next copy alerts loudly again.
 */
data class Cooldown(val entries: Map<String, Entry> = emptyMap()) {
    data class Entry(val label: String, val total: Int, val lastLoudAt: Instant)

    /** A group of identical new emails: [key] identifies sender + subject. */
    data class Group(val key: String, val label: String, val count: Int)

    data class Result(
        val loudKeys: Set<String>,
        /** Entries that just received muted repeats, with updated totals. */
        val muted: List<Entry>,
        val next: Cooldown,
    )

    fun apply(groups: List<Group>, now: Instant, window: Duration): Result {
        val live = entries.filterValues { Duration.between(it.lastLoudAt, now) < window }.toMutableMap()
        val loud = mutableSetOf<String>()
        val mutedKeys = linkedSetOf<String>()
        for (g in groups) {
            val existing = live[g.key]
            if (existing != null) {
                live[g.key] = existing.copy(total = existing.total + g.count)
                mutedKeys += g.key
            } else {
                live[g.key] = Entry(g.label, g.count, now)
                loud += g.key
            }
        }
        return Result(loud, mutedKeys.mapNotNull { live[it] }, Cooldown(live))
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofMinutes(30)

        fun keyFor(e: EmailSummary) = e.displaySender + "\u0000" + e.subject
        fun labelFor(e: EmailSummary) = "${e.displaySender}: ${e.subject}"
    }
}
