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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun fmt(i: Instant?): String = i?.let { clock.format(it.atZone(ZoneId.systemDefault())) } ?: "—"

/** On/off for push listening, with live status. */
@Composable
fun ListenSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(ListenStatus.enabled(context)) }
    var tick by remember { mutableIntStateOf(0) }

    // Refresh the status lines every few seconds while visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            tick++
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Listen for new mail",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    ListenStatus.setEnabled(context, it)
                    if (it) ListenService.start(context) else ListenService.stop(context)
                },
            )
        }
        @Suppress("UNUSED_EXPRESSION") tick // re-read the values below on every tick
        val since = ListenStatus.connectedSince(context)
        val error = ListenStatus.lastError(context)
        Text(
            when {
                !on -> "Off. Alerts only come when you tap Check now."
                since != null -> "Connected since ${fmt(since)}"
                error != null -> "Not connected: $error"
                else -> "Connecting…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (on && since == null && error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        if (on) {
            Text(
                "Last push ${fmt(ListenStatus.lastPush(context))} · last check ${fmt(ListenStatus.lastQuery(context))}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
