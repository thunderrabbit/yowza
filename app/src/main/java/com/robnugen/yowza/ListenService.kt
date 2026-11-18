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

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps one JMAP push connection (EventSource, RFC 8620 §7.3) open while listening is on.
 * A push only says "something changed"; each one (debounced) runs the windowed check.
 * Reconnects with exponential backoff, immediately when the network comes back, and runs
 * a catch-up check on every (re)connect so nothing is missed while disconnected.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var debounce: Job? = null
    @Volatile private var connection: HttpURLConnection? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Alerts.ensureQuietChannels(this)
        val notification = listeningNotification("Starting…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Alerts.LISTEN_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Alerts.LISTEN_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android refused a background start (e.g. battery optimisation is on).
            ListenStatus.markDisconnected(this, "Android blocked listening: allow unrestricted battery")
            stopSelf()
            return
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ListenStatus.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
        }
        connection?.disconnect()
        ListenStatus.markDisconnected(this, null)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runLoop() {
        var backoffSeconds = 2L
        while (scope.isActive && ListenStatus.enabled(this)) {
            val startedAt = System.currentTimeMillis()
            val error = try {
                connectOnce()
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            ListenStatus.markDisconnected(this, error)
            updateNotification("Reconnecting… ${error ?: ""}".trim())
            // A connection that stayed up a while resets the backoff.
            if (System.currentTimeMillis() - startedAt > 60_000) backoffSeconds = 2
            delay(backoffSeconds * 1000)
            backoffSeconds = (backoffSeconds * 2).coerceAtMost(300)
        }
        stopSelf()
    }

    /** One connection: fetch session, catch up, then read events until it drops. */
    private suspend fun connectOnce() {
        val token = TokenStore.load(this) ?: throw IOException("No token saved")
        val session = when (val s = Jmap.fetchSession(TokenStore.sessionUrl(this), token)) {
            is ConnectionState.Connected -> s.session
            is ConnectionState.Failed -> throw IOException(s.message)
            else -> throw IOException("Not connected")
        }
        if (session.eventSourceUrl.isBlank()) throw IOException("Server has no push (eventSourceUrl)")

        val url = session.eventSourceUrl
            .replace("{types}", "Email")
            .replace("{closeafter}", "no")
            .replace("{ping}", PING_SECONDS.toString())
        val conn = URL(url).openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.connectTimeout = 15_000
            // No ping for 2.5 intervals means the connection is dead.
            conn.readTimeout = (PING_SECONDS * 2500).toInt()
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (conn.responseCode != 200) throw IOException("Push answered HTTP ${conn.responseCode}")

            ListenStatus.markConnected(this)
            updateNotification("Listening for new mail")
            runCheck(session, token) // catch up on anything missed while disconnected

            var event = ""
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.isEmpty() -> {
                            if (event == "state") {
                                ListenStatus.markPush(this)
                                scheduleCheck(session, token)
                            }
                            event = ""
                        }
                    }
                }
            }
            throw IOException("Push connection closed")
        } finally {
            connection = null
            conn.disconnect()
        }
    }

    /** Bursts of pushes (several emails arriving together) become one check. */
    private fun scheduleCheck(session: JmapSession, token: String) {
        debounce?.cancel()
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            runCheck(session, token)
        }
    }

    private fun runCheck(session: JmapSession, token: String) {
        val pm = getSystemService(PowerManager::class.java)
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yowza:check")
        wake.acquire(30_000)
        try {
            Checker.checkNow(this, session, token)
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                // The first call just reports the current network; later ones mean it changed.
                if (first) { first = false; return }
                // Drop the stale connection; the loop reconnects (backoff restarts small).
                connection?.disconnect()
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun listeningNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, Alerts.LISTEN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yowza)
            .setContentTitle("Yowza! is listening")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!Alerts.canPostNotifications(this)) return
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Alerts.LISTEN_NOTIFICATION_ID, listeningNotification(text))
    }

    companion object {
        private const val PING_SECONDS = 60L
        private const val DEBOUNCE_MS = 3_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }
}

/** Restart listening after a reboot or an app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) Alerts.remindFullScreenIfNeeded(context)
        if ((action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
            ListenStatus.enabled(context) && TokenStore.hasToken(context)
        ) {
            ListenService.start(context)
        }
    }
}
