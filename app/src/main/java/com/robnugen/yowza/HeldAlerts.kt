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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime

/** Quiet-hours settings (phone-local). */
object QuietStore {
    private const val PREFS = "quiet_hours"

    fun load(context: Context): QuietHours {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return QuietHours(
            enabled = p.getBoolean("enabled", false),
            start = LocalTime.ofSecondOfDay(p.getInt("start_min", 22 * 60) * 60L),
            end = LocalTime.ofSecondOfDay(p.getInt("end_min", 7 * 60) * 60L),
        )
    }

    fun save(context: Context, q: QuietHours) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", q.enabled)
            .putInt("start_min", q.start.toSecondOfDay() / 60)
            .putInt("end_min", q.end.toSecondOfDay() / 60)
            .apply()
    }
}

/**
 * Alerts held during quiet hours. Released together, as one alert, when quiet hours end.
 * Stores sender/subject only until released (needed to show them), on this phone only.
 */
object HeldAlerts {
    private const val PREFS = "held_alerts"
    private const val KEY = "items"
    private val lock = Any()

    data class Item(val sender: String, val subject: String, val rules: List<String>)

    fun count(context: Context): Int = load(context).size

    fun hold(context: Context, emails: List<EmailSummary>) = synchronized(lock) {
        val items = load(context) + emails.map { Item(it.displaySender, it.subject, it.matchedRules) }
        save(context, items)
        scheduleRelease(context)
        Alerts.showHeldNotice(context, items.size, QuietStore.load(context).end)
    }

    /** Called when quiet hours end: one loud alert for everything held. */
    fun release(context: Context) = synchronized(lock) {
        val items = load(context)
        save(context, emptyList())
        Alerts.clearHeldNotice(context)
        if (items.isEmpty()) return@synchronized
        val emails = items.mapIndexed { i, it ->
            EmailSummary("held$i", it.sender, "", it.subject, null, it.rules)
        }
        val (sender, subject) = summarizeForAlert(emails)
        Alerts.showNew(context, sender, subject, items.flatMap { it.rules }.distinct())
        AppLog.info(context, "Released ${items.size} held alert(s) after quiet hours")
    }

    private fun scheduleRelease(context: Context) {
        val at = QuietStore.load(context).nextEnd(ZonedDateTime.now()).toInstant().toEpochMilli()
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, HeldReleaseReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Alerts.canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun load(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val r = o.optJSONArray("rules") ?: JSONArray()
                Item(o.getString("sender"), o.getString("subject"), (0 until r.length()).map { r.getString(it) })
            }
        }.getOrElse { emptyList() }
    }

    private fun save(context: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("sender", it.sender).put("subject", it.subject).put("rules", JSONArray(it.rules))) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).commit()
    }
}

class HeldReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HeldAlerts.release(context)
    }
}
