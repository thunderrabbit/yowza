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
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Alarms live in files/alarms.json (included in Android's backup, like rules.json). */
object AlarmStore {
    private const val FILE = "alarms.json"
    private val lock = Any()

    fun load(context: Context): List<ZonedAlarm> = synchronized(lock) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) emptyList() else AlarmJson.decode(f.readText())
    }

    /** Save and reschedule everything. */
    fun save(context: Context, alarms: List<ZonedAlarm>) {
        synchronized(lock) {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(AlarmJson.encode(alarms))
            if (!tmp.renameTo(File(context.filesDir, FILE))) {
                File(context.filesDir, FILE).writeText(tmp.readText())
                tmp.delete()
            }
        }
        android.app.backup.BackupManager(context).dataChanged()
        AlarmScheduler.rescheduleAll(context)
    }
}

/**
 * Uses AlarmManager.setAlarmClock: exact, allowed in Doze, and shown as the phone's next
 * alarm. Absolute instants, so a time-zone change needs no work, but we still recompute on
 * time/zone changes and boot (the clock may have been set by hand; boot clears alarms).
 */
object AlarmScheduler {
    const val EXTRA_ALARM_ID = "alarm_id"

    fun rescheduleAll(context: Context) {
        val now = Instant.now()
        val alarms = AlarmStore.load(context)
        alarms.forEach { a ->
            val fire = a.nextFire(now)
            if (fire == null) cancel(context, a) else schedule(context, a, fire)
        }
    }

    private fun schedule(context: Context, alarm: ZonedAlarm, fire: Instant) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (!Alerts.canScheduleExact(context)) {
            AppLog.error(context, "Can't schedule exact alarms; time-zone alarms may be late")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire.toEpochMilli(), firePending(context, alarm))
            return
        }
        val show = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(fire.toEpochMilli(), show), firePending(context, alarm))
    }

    fun cancel(context: Context, alarm: ZonedAlarm) {
        context.getSystemService(AlarmManager::class.java).cancel(firePending(context, alarm))
    }

    private fun firePending(context: Context, alarm: ZonedAlarm): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(alarm.id),
            Intent(context, ZonedAlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Stable per alarm, away from other request codes the app uses. */
    private fun requestCode(id: String) = 0x5A000000 or (id.hashCode() and 0x00FFFFFF)

    /** The soonest enabled alarm, for the home and health screens. */
    fun nextOf(alarms: List<ZonedAlarm>, now: Instant = Instant.now()): Pair<ZonedAlarm, Instant>? =
        alarms.mapNotNull { a -> a.nextFire(now)?.let { a to it } }.minByOrNull { it.second }
}

/** An alarm went off: full-screen alert, then schedule its next ring (or switch off a one-time alarm). */
class ZonedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val alarms = AlarmStore.load(context)
        val alarm = alarms.firstOrNull { it.id == id } ?: return
        if (!alarm.enabled) return

        val here = ZoneId.systemDefault()
        val subject = if (here.rules.getOffset(Instant.now()) == alarm.zone.rules.getOffset(Instant.now())) {
            alarm.describeTime()
        } else {
            alarm.describeTime() + " · " + java.time.LocalTime.now(here).format(ZonedAlarm.HHMM) + " here"
        }
        // Alarms always ring: no quiet hours, no repeat cooldown.
        Alerts.showNew(context, alarm.label.ifBlank { "Alarm" }, subject, listOf("Alarm"))
        AppLog.info(context, "Time-zone alarm rang (${alarm.describeTime()})")

        if (!alarm.repeats) {
            AlarmStore.save(context, alarms.map { if (it.id == id) it.copy(enabled = false) else it })
        } else {
            AlarmScheduler.rescheduleAll(context)
        }
    }
}

/** The clock or time zone changed (travel, manual change, network time): recompute. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            AppLog.info(context, "Time zone changed to ${ZoneId.systemDefault().id}; alarms rechecked")
        }
    }
}
