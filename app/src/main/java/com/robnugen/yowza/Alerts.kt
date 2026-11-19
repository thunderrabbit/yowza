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
    const val EXTRA_RULES = "rules"

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
    fun show(
        context: Context,
        sender: String,
        subject: String,
        notificationId: Int,
        rules: List<String> = emptyList(),
    ) {
        ensureChannel(context)
        if (!canPostNotifications(context)) return

        val alertIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_SUBJECT, subject)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_RULES, rules.joinToString(" · "))
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
            .setSubText(rules.joinToString(" · ").ifEmpty { null })
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

    const val LISTEN_CHANNEL_ID = "yowza_listening_v1"
    private const val REPEATS_CHANNEL_ID = "yowza_repeats_v1"
    private const val REPEATS_NOTIFICATION_ID = 3_000_000
    const val LISTEN_NOTIFICATION_ID = 3_000_001
    private const val FULLSCREEN_REMINDER_ID = 3_000_002

    /** Quiet channels: the persistent "listening" notice and the muted-repeats summary. */
    fun ensureQuietChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(LISTEN_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(LISTEN_CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Yowza! watches your mail."
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(REPEATS_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(REPEATS_CHANNEL_ID, "Muted repeats", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Counts of identical alerts muted during their cooldown."
                    setShowBadge(false)
                },
            )
        }
    }

    private const val PROBLEMS_CHANNEL_ID = "yowza_problems_v1"
    private const val PROBLEM_NOTIFICATION_ID = 3_000_003
    private const val HELD_NOTIFICATION_ID = 3_000_004

    /** A normal (not full-screen) notification when Yowza! can't do its job. */
    fun showProblem(context: Context, title: String, text: String, id: Int = PROBLEM_NOTIFICATION_ID) {
        if (!canPostNotifications(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(PROBLEMS_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(PROBLEMS_CHANNEL_ID, "Problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When Yowza! can't reach your mail and might miss an alert."
                },
            )
        }
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, PROBLEMS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yowza)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        nm.notify(id, n)
    }

    private const val INBOX_PROBLEM_ID = 3_000_005

    /** Like [showProblem], but for the server inbox, so the two can clear independently. */
    fun showInboxProblem(context: Context, title: String, text: String) {
        showProblem(context, title, text, INBOX_PROBLEM_ID)
    }

    fun clearInboxProblem(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INBOX_PROBLEM_ID)
    }

    fun clearProblem(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PROBLEM_NOTIFICATION_ID)
    }

    /** Silent notice that alerts are being held until quiet hours end. */
    fun showHeldNotice(context: Context, count: Int, until: java.time.LocalTime) {
        ensureQuietChannels(context)
        if (!canPostNotifications(context)) return
        val n = Notification.Builder(context, REPEATS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yowza)
            .setContentTitle("$count alert${if (count == 1) "" else "s"} held for quiet hours")
            .setContentText("They'll go off at $until.")
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    HELD_NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        context.getSystemService(NotificationManager::class.java).notify(HELD_NOTIFICATION_ID, n)
    }

    fun clearHeldNotice(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(HELD_NOTIFICATION_ID)
    }

    /** No sound, no full screen: one summary of repeats muted during their cooldown. */
    fun showMutedRepeats(context: Context, muted: List<Cooldown.Entry>) {
        ensureQuietChannels(context)
        if (!canPostNotifications(context)) return
        val lines = muted.joinToString("\n") { "×${it.total}  ${it.label}" }
        val open = PendingIntent.getActivity(
            context,
            REPEATS_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, REPEATS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yowza)
            .setContentTitle("Repeats muted for ${Cooldown.DEFAULT_WINDOW.toMinutes()} min")
            .setContentText(lines.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(lines))
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(REPEATS_NOTIFICATION_ID, n)
    }

    /** A real alert for new mail. Always a fresh notification id, so it goes full screen. */
    fun showNew(context: Context, sender: String, subject: String, rules: List<String> = emptyList()) {
        val id = REAL_NOTIFICATION_ID_BASE + (System.currentTimeMillis() / 1000 % 100_000).toInt()
        show(context, sender, subject, id, rules)
    }

    /**
     * Android resets the full-screen permission when a sideloaded app is updated, and apps
     * can't grant it back themselves. After an update, nudge the user to switch it on again.
     */
    fun remindFullScreenIfNeeded(context: Context) {
        if (canUseFullScreen(context) || !canPostNotifications(context)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        ensureQuietChannels(context)
        val settings = PendingIntent.getActivity(
            context,
            FULLSCREEN_REMINDER_ID,
            Intent(
                android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, REPEATS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yowza)
            .setContentTitle("Yowza! updated: re-allow full-screen alerts")
            .setContentText("Android switched it off during the update. Tap to switch it back on.")
            .setContentIntent(settings)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(FULLSCREEN_REMINDER_ID, n)
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

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

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
