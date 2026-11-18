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

import android.content.Context
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** Persists the [AlertWindow] and the overlap setting. App-local only. */
object WindowStore {
    private const val PREFS = "alert_window"
    private const val WATERMARK = "watermark"
    private const val ALERTED = "alerted"
    private const val OVERLAP_MIN = "overlap_minutes"
    const val DEFAULT_OVERLAP_MINUTES = 10

    fun overlapMinutes(context: Context): Int =
        prefs(context).getInt(OVERLAP_MIN, DEFAULT_OVERLAP_MINUTES)

    fun setOverlapMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(OVERLAP_MIN, minutes.coerceIn(1, 24 * 60)).apply()
    }

    fun overlap(context: Context): Duration = Duration.ofMinutes(overlapMinutes(context).toLong())

    /** Saved window, or a fresh one starting now (and saved) if there is none. */
    fun load(context: Context): AlertWindow {
        val p = prefs(context)
        val wm = p.getString(WATERMARK, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (wm == null) {
            val fresh = AlertWindow.fresh(Instant.now())
            save(context, fresh)
            return fresh
        }
        val alerted = mutableMapOf<String, Instant>()
        runCatching {
            val json = JSONObject(p.getString(ALERTED, "{}") ?: "{}")
            json.keys().forEach { id ->
                runCatching { Instant.parse(json.getString(id)) }.getOrNull()?.let { alerted[id] = it }
            }
        }
        return AlertWindow(wm, alerted)
    }

    fun save(context: Context, window: AlertWindow) {
        val json = JSONObject()
        window.alerted.forEach { (id, at) -> json.put(id, at.toString()) }
        // commit(), not apply(): the record must be on disk before we report success.
        prefs(context).edit()
            .putString(WATERMARK, window.watermark.toString())
            .putString(ALERTED, json.toString())
            .commit()
    }

    /** Start over from now. Existing mail won't alert. */
    fun reset(context: Context) {
        prefs(context).edit().remove(WATERMARK).remove(ALERTED).commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Result of one windowed check, for the home screen. */
data class CheckOutcome(val alerted: List<EmailSummary>, val windowStart: Instant, val error: String?)

object Checker {
    private val lock = Any()

    /**
     * Run all rules over the window, alert on anything new, then record it.
     * Blocking: call off the main thread.
     */
    fun checkNow(context: Context, session: JmapSession, token: String): CheckOutcome = synchronized(lock) {
        val window = WindowStore.load(context)
        val overlap = WindowStore.overlap(context)
        val start = window.windowStart(overlap)
        when (val r = Jmap.queryRules(session, token, Rules.hardcoded, limitPerRule = 25, after = start)) {
            is QueryResult.Failed -> CheckOutcome(emptyList(), start, r.message)
            is QueryResult.Found -> {
                val decision = window.process(r.emails, overlap)
                if (decision.toAlert.isNotEmpty()) {
                    val (sender, subject) = summarizeForAlert(decision.toAlert)
                    Alerts.showNew(context, sender, subject) // alert first…
                }
                WindowStore.save(context, decision.nextState) // …then record
                CheckOutcome(decision.toAlert, start, r.ruleErrors.firstOrNull())
            }
        }
    }
}
