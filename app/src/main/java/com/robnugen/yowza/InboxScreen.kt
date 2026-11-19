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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val when_ = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss")

/** Settings for the server inbox (server/ in the repo, installed on your own host). */
@Composable
fun InboxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(InboxStore.url(context)) }
    var key by remember { mutableStateOf("") }
    var canWake by remember { mutableStateOf(InboxStore.canWake(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)

    fun test() {
        busy = true
        scope.launch {
            val o = withContext(Dispatchers.IO) { InboxPoller.pollOnce(context) }
            message = when {
                o.error != null -> o.error
                o.started -> "Connected ✅ New server alerts will arrive from now on."
                o.delivered == null -> "Connected ✅ Nothing new."
                else -> "Connected ✅ ${o.delivered.loud.size} alerted."
            }
            busy = false
            tick++
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Server inbox", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Your own scripts (PHP error handlers, cron jobs…) post alerts to an inbox on your " +
                    "web host, and Yowza! checks it every few minutes while listening is on. " +
                    "Set up the inbox from the server folder in the Yowza! source code.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("Inbox URL (…/alerts.php)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val hasKey = remember(tick) { InboxStore.key(context) != null }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text(if (hasKey) "Read key (leave blank to keep)" else "Read key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    InboxStore.save(context, url, key.ifBlank { null })
                    key = ""
                    test()
                },
                enabled = !busy && url.startsWith("https://") && (key.isNotBlank() || hasKey),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Connecting…" else "Save and connect") }
            if (!url.startsWith("https://") && url.isNotEmpty()) {
                Text("The address must start with https://", color = MaterialTheme.colorScheme.error)
            }
            message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Server alerts can wake me during quiet hours (high-priority ones always can)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = canWake, onCheckedChange = {
                    canWake = it
                    InboxStore.setCanWake(context, it)
                })
            }

            @Suppress("UNUSED_EXPRESSION") tick
            if (InboxStore.isConfigured(context)) {
                Spacer(Modifier.height(16.dp))
                val last = InboxStore.lastFetch(context)?.let { when_.format(it.atZone(ZoneId.systemDefault())) } ?: "—"
                Text("Last check: $last", style = MaterialTheme.typography.bodySmall)
                InboxStore.lastError(context)?.let {
                    Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row {
                    TextButton(onClick = { test() }, enabled = !busy) { Text("Check now") }
                    TextButton(onClick = {
                        InboxStore.forget(context)
                        url = ""
                        message = "Inbox removed."
                        tick++
                    }) { Text("Forget inbox") }
                }
            }
        }
    }
}
