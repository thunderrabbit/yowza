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

/** Persists the repeat [Cooldown]. */
object CooldownStore {
    private const val PREFS = "cooldown"
    private const val ENTRIES = "entries"

    fun load(context: Context): Cooldown {
        val raw = prefs(context).getString(ENTRIES, null) ?: return Cooldown()
        return runCatching {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Cooldown.Entry>()
            json.keys().forEach { k ->
                val e = json.getJSONObject(k)
                map[k] = Cooldown.Entry(e.getString("label"), e.getInt("total"), Instant.parse(e.getString("at")))
            }
            Cooldown(map)
        }.getOrElse { Cooldown() }
    }

    fun save(context: Context, cooldown: Cooldown) {
        val json = JSONObject()
        cooldown.entries.forEach { (k, e) ->
            json.put(k, JSONObject().put("label", e.label).put("total", e.total).put("at", e.lastLoudAt.toString()))
        }
        prefs(context).edit().putString(ENTRIES, json.toString()).commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Timestamps and state the home screen shows about listening. */
object ListenStatus {
    private const val PREFS = "listen_status"

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
    }

    fun markPush(context: Context) = putNow(context, "last_push")
    fun markQuery(context: Context) = putNow(context, "last_query")
    fun markConnected(context: Context) {
        prefs(context).edit().putLong("connected_since", System.currentTimeMillis()).remove("last_error").apply()
    }
    fun markDisconnected(context: Context, error: String?) {
        prefs(context).edit().remove("connected_since").putString("last_error", error).apply()
    }

    fun lastPush(context: Context) = time(context, "last_push")
    fun lastQuery(context: Context) = time(context, "last_query")
    fun connectedSince(context: Context) = time(context, "connected_since")
    fun lastError(context: Context): String? = prefs(context).getString("last_error", null)

    private fun putNow(context: Context, key: String) {
        prefs(context).edit().putLong(key, System.currentTimeMillis()).apply()
    }
    private fun time(context: Context, key: String): Instant? =
        prefs(context).getLong(key, 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Result of one windowed check, for the home screen. */
data class CheckOutcome(
    val alerted: List<EmailSummary>,
    val windowStart: Instant,
    val error: String?,
    val mutedRepeats: Int = 0,
    val held: Int = 0,
)

/** What happened to a batch of new items. */
data class Delivery(val loud: List<EmailSummary>, val muted: Int, val held: List<EmailSummary>)

object Checker {
    private val lock = Any()

    /**
     * The shared alert path for email and server-inbox items: hold what may not wake you
     * (quiet hours), mute identical repeats in cooldown, and raise one alert for the rest.
     */
    fun deliver(context: Context, items: List<EmailSummary>, alertNow: (EmailSummary) -> Boolean): Delivery {
        val (now, later) = items.partition(alertNow)
        if (later.isNotEmpty()) HeldAlerts.hold(context, later)
        if (now.isEmpty()) return Delivery(emptyList(), 0, later)
        val groups = collapseRepeats(now).map { (e, n) ->
            Cooldown.Group(Cooldown.keyFor(e), Cooldown.labelFor(e), n)
        }
        val cd = CooldownStore.load(context).apply(groups, Instant.now(), Cooldown.DEFAULT_WINDOW)
        val loud = now.filter { Cooldown.keyFor(it) in cd.loudKeys }
        if (loud.isNotEmpty()) {
            val (sender, subject) = summarizeForAlert(loud)
            Alerts.showNew(context, sender, subject, loud.flatMap { it.matchedRules }.distinct())
        }
        if (cd.muted.isNotEmpty()) Alerts.showMutedRepeats(context, cd.muted)
        CooldownStore.save(context, cd.next)
        return Delivery(loud, now.size - loud.size, later)
    }

    /**
     * Run all rules over the window, alert on anything new, then record it.
     * Identical repeats within the cooldown are muted instead of re-alerting.
     * Blocking: call off the main thread. Used by Check now and by the listening service.
     */
    fun checkNow(context: Context, session: JmapSession, token: String): CheckOutcome = synchronized(lock) {
        val window = WindowStore.load(context)
        val overlap = WindowStore.overlap(context)
        val start = window.windowStart(overlap)
        ListenStatus.markQuery(context)
        val stored = RuleStore.load(context)
        when (val r = Jmap.queryRules(session, token, RuleJson.toQueryRules(stored), limitPerRule = 25, after = start)) {
            is QueryResult.Failed -> {
                AppLog.error(context, "Check failed: ${r.message}")
                CheckOutcome(emptyList(), start, r.message)
            }
            is QueryResult.Found -> {
                r.ruleErrors.forEach { AppLog.error(context, "Rule error: $it") }
                val decision = window.process(r.emails, overlap)
                if (decision.toAlert.isEmpty()) {
                    WindowStore.save(context, decision.nextState)
                    AppLog.info(context, "Check: ${r.emails.size} in window, nothing new")
                    return@synchronized CheckOutcome(emptyList(), start, r.ruleErrors.firstOrNull())
                }

                // Quiet hours: hold mail whose rules may not wake you.
                val quiet = QuietStore.load(context)
                val wakers = stored.filter { it.wakeDuringQuiet }.map { it.name }.toSet()
                val nowLocal = java.time.LocalTime.now()
                val d = deliver(context, decision.toAlert) { shouldAlertNow(it.matchedRules, wakers, quiet, nowLocal) }
                val loud = d.loud
                val muted = d.muted
                val later = d.held
                WindowStore.save(context, decision.nextState) // …then record
                AppLog.info(
                    context,
                    "Check: ${decision.toAlert.size} new → ${loud.size} alerted" +
                        (if (muted > 0) ", $muted muted repeats" else "") +
                        (if (later.isNotEmpty()) ", ${later.size} held for quiet hours" else "") +
                        " (rules: ${decision.toAlert.flatMap { it.matchedRules }.distinct().joinToString()})",
                )
                CheckOutcome(loud, start, r.ruleErrors.firstOrNull(), muted, later.size)
            }
        }
    }
}
