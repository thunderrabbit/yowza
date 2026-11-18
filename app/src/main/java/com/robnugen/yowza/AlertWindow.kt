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
import java.time.temporal.ChronoUnit

/**
 * Pure logic (no Android) for "alert only on new matches":
 *
 * - [watermark] is the newest receivedAt seen, from the mail server's timestamps.
 * - Each query asks for mail received after `watermark − overlap`. The overlap catches
 *   mail that arrives out of order or during a reconnect.
 * - [alerted] remembers which email ids already alerted (id → receivedAt), so the overlap
 *   never alerts twice. Entries older than the window are pruned.
 *
 * Callers must alert on [Decision.toAlert] *before* saving [Decision.nextState]:
 * a crash in between causes a repeat, never a miss.
 */
data class AlertWindow(
    val watermark: Instant,
    val alerted: Map<String, Instant> = emptyMap(),
) {
    fun windowStart(overlap: Duration): Instant =
        watermark.minus(overlap).truncatedTo(ChronoUnit.SECONDS)

    data class Decision(val toAlert: List<EmailSummary>, val nextState: AlertWindow)

    fun process(results: List<EmailSummary>, overlap: Duration): Decision {
        val start = windowStart(overlap)
        val fresh = results
            .filter { it.id !in alerted }
            // The server already filters by date; this guards against servers that don't.
            .filter { it.receivedAt == null || !it.receivedAt.isBefore(start) }
            .distinctBy { it.id }

        val newestSeen = results.mapNotNull { it.receivedAt }.maxOrNull()
        val nextWatermark = if (newestSeen != null && newestSeen.isAfter(watermark)) newestSeen else watermark

        val now = nextWatermark // "now" in mail-server time, never the phone clock
        val remembered = alerted + fresh.associate { it.id to (it.receivedAt ?: now) }
        val pruneBefore = nextWatermark.minus(overlap)
        val pruned = remembered.filterValues { !it.isBefore(pruneBefore) }

        return Decision(fresh, AlertWindow(nextWatermark, pruned))
    }

    companion object {
        /** First run or empty store: start at [now] so existing mail never alerts. */
        fun fresh(now: Instant) = AlertWindow(now.truncatedTo(ChronoUnit.SECONDS))
    }
}

/**
 * One alert for one or many new emails, so several at once never become a storm.
 * Identical repeats (same sender and subject, e.g. a PHP error firing 40 times) collapse
 * into one line with a count.
 */
fun summarizeForAlert(emails: List<EmailSummary>): Pair<String, String> {
    val groups = collapseRepeats(emails).map { (e, count) -> Triple(e.displaySender, e.subject, count) }
    if (groups.size == 1) {
        val (sender, subject, count) = groups[0]
        return sender to if (count > 1) "×$count  $subject" else subject
    }
    val lines = groups.take(5).joinToString("\n") { (sender, subject, count) ->
        (if (count > 1) "×$count " else "") + "$sender: $subject"
    }
    val more = if (groups.size > 5) "\n…and ${groups.size - 5} more" else ""
    return "${emails.size} new emails" to lines + more
}

/**
 * Group identical repeats (same displayed sender and subject), keeping first-seen order.
 * Each group is represented by its first email (the newest, when the list is newest-first)
 * and how many emails it stands for.
 */
fun collapseRepeats(emails: List<EmailSummary>): List<Pair<EmailSummary, Int>> =
    emails.groupBy { it.displaySender to it.subject }.values.map { it.first() to it.size }
