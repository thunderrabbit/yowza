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

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Everything about posting a big, loud Yowza! alert. */
object Alerts {
    // Channel settings are frozen once created, so bump the id to change them.
    const val CHANNEL_ID = "yowza_alerts_v1"
    const val EXTRA_SENDER = "sender"
    const val EXTRA_SUBJECT = "subject"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    private const val TEST_NOTIFICATION_ID_BASE = 1_000_000
    private const val REAL_NOTIFICATION_ID_BASE = 2_000_000
    private const val TEST_SENDER = "Test Sender"
    private const val TEST_SUBJECT = "Test subject"
    private val VIBRATION = longArrayOf(0, 600, 300, 600, 300, 600)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerts_description)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = VIBRATION
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    /** Post an alert. Shows full screen when the phone is locked or idle. */
    fun show(context: Context, sender: String, subject: String, notificationId: Int) {
        ensureChannel(context)
        if (!canPostNotifications(context)) return

        val alertIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_SUBJECT, subject)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val fullScreen = PendingIntent.getActivity(
            context,
            notificationId,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yowza)
            .setContentTitle(sender)
            .setContentText(subject)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setAutoCancel(true)
            .build()
        // Keep ringing until the alert is dismissed.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    fun dismiss(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
    }

    /**
     * Android only launches the full-screen activity for a *newly posted* notification;
     * re-posting an id that's still showing counts as an update. So every alert gets a
     * fresh id, and any older test alert still in the shade is cleared first.
     */
    fun showTest(
        context: Context,
        sender: String = TEST_SENDER,
        subject: String = TEST_SUBJECT,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications
            .filter { it.id in TEST_NOTIFICATION_ID_BASE until REAL_NOTIFICATION_ID_BASE }
            .forEach { nm.cancel(it.id) }
        val id = TEST_NOTIFICATION_ID_BASE + (System.currentTimeMillis() / 1000 % 100_000).toInt()
        show(context, sender, subject, id)
    }

    /** A real alert for new mail. Always a fresh notification id, so it goes full screen. */
    fun showNew(context: Context, sender: String, subject: String) {
        val id = REAL_NOTIFICATION_ID_BASE + (System.currentTimeMillis() / 1000 % 100_000).toInt()
        show(context, sender, subject, id)
    }

    /**
     * Fire a test alert after [delaySeconds], so there is time to lock the phone.
     * Uses an exact alarm so it still fires while the phone is idle.
     */
    fun scheduleTest(
        context: Context,
        delaySeconds: Int,
        sender: String = TEST_SENDER,
        subject: String = TEST_SUBJECT,
    ) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TestAlertReceiver::class.java)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_SUBJECT, subject),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = SystemClock.elapsedRealtime() + delaySeconds * 1000L
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }
    }

    // --- Diagnostics for the delayed test ---

    private const val PREFS = "diagnostics"

    /** Remember when the delayed test fired and whether the screen was on at that moment. */
    fun recordTestFired(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("test_fired_at", System.currentTimeMillis())
            .putBoolean("test_screen_on", power.isInteractive)
            .putBoolean("test_locked", keyguard.isKeyguardLocked)
            .apply()
    }

    /** Human-readable summary of the last delayed test, or null if none yet. */
    fun lastTestSummary(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("test_fired_at", 0L)
        if (at == 0L) return null
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
            .format(java.util.Date(at))
        val screenOn = prefs.getBoolean("test_screen_on", true)
        val locked = prefs.getBoolean("test_locked", false)
        val expect = if (screenOn) {
            "screen was on → banner expected"
        } else {
            "screen was off → full screen expected"
        }
        return "Last delayed test fired at $time (${if (locked) "locked" else "unlocked"}, $expect)"
    }

    // --- Permission / settings state, shown on the home screen ---

    fun canPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }

    fun canUseFullScreen(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }

    fun canBypassDnd(context: Context): Boolean {
        ensureChannel(context)
        return context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
            ?.canBypassDnd() == true
    }

    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else {
            true
        }
}

class TestAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alerts.recordTestFired(context)
        Alerts.showTest(
            context,
            sender = intent.getStringExtra(Alerts.EXTRA_SENDER) ?: "Test Sender",
            subject = intent.getStringExtra(Alerts.EXTRA_SUBJECT) ?: "Test subject",
        )
    }
}
