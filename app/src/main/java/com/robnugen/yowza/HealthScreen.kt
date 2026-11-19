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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss")
private fun show(i: Instant?) = i?.let { stamp.format(it.atZone(ZoneId.systemDefault())) } ?: "—"

/** Everything needed to tell whether Yowza! is working, without any email content. */
@Composable
fun HealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            tick++
        }
    }
    BackHandler(onBack = onBack)

    @Suppress("UNUSED_EXPRESSION") tick
    val window = WindowStore.load(context)
    val quiet = QuietStore.load(context)
    val rows = listOf(
        "Listening" to if (ListenStatus.enabled(context)) "on" else "off",
        "Connected since" to show(ListenStatus.connectedSince(context)),
        "Last error" to (ListenStatus.lastError(context) ?: "—"),
        "Last push" to show(ListenStatus.lastPush(context)),
        "Last check" to show(ListenStatus.lastQuery(context)),
        "Watermark (newest mail seen)" to show(window.watermark),
        "Window starts" to show(window.windowStart(WindowStore.overlap(context))),
        "Remembered alerted emails" to window.alerted.size.toString(),
        "Repeats in cooldown" to CooldownStore.load(context).entries.size.toString(),
        "Quiet hours" to if (quiet.enabled) "${quiet.start}–${quiet.end}" else "off",
        "Held for quiet hours" to HeldAlerts.count(context).toString(),
        "Server inbox" to if (InboxStore.isConfigured(context)) "on" else "not set up",
        "Inbox last fetch" to show(InboxStore.lastFetch(context)),
        "Inbox last error" to (InboxStore.lastError(context) ?: "—"),
        "Inbox cursor" to (InboxStore.cursor(context)?.toString() ?: "—"),
        "Time-zone alarms on" to AlarmStore.load(context).count { it.enabled }.toString(),
        "Next time-zone alarm" to (AlarmScheduler.nextOf(AlarmStore.load(context))?.let { (a, at) ->
            "${a.describeTime()} (${show(at)} here)"
        } ?: "—"),
        "Rules on" to RuleStore.load(context).count { it.enabled }.toString(),
        "Full-screen allowed" to if (Alerts.canUseFullScreen(context)) "yes" else "NO",
        "Unrestricted battery" to if (Alerts.ignoresBatteryOptimizations(context)) "yes" else "NO",
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Health", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            rows.forEach { (label, value) ->
                Text(label, style = MaterialTheme.typography.bodySmall)
                Text(value, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text("Recent events", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Newest first. No email contents or token are ever logged.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                AppLog.clear(context)
                tick++
            }) { Text("Clear log") }
            val entries = AppLog.entries(context)
            if (entries.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
            entries.forEach { e ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(show(e.at), style = MaterialTheme.typography.bodySmall)
                Text(
                    e.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (e.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
