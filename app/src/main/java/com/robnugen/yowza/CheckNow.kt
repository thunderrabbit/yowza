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

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Step 5: Check now alerts only on new matches. "Recent matches" is the old browse view. */
private const val ALERT_DELAY_SECONDS = 10

private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

@Composable
fun CheckNowSection(session: JmapSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<CheckOutcome?>(null) }
    var recent by remember { mutableStateOf<QueryResult?>(null) }
    var overlapMin by remember { mutableIntStateOf(WindowStore.overlapMinutes(context)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                busy = true
                scope.launch {
                    outcome = withContext(Dispatchers.IO) {
                        val token = TokenStore.load(context)
                        if (token == null) {
                            CheckOutcome(emptyList(), java.time.Instant.now(), "No token saved.")
                        } else {
                            Checker.checkNow(context, session, token)
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Checking…" else "Check now")
        }

        outcome?.let { o ->
            val since = timeFormat.format(o.windowStart.atZone(ZoneId.systemDefault()))
            Text(
                when {
                    o.error != null && o.alerted.isEmpty() -> o.error
                    o.alerted.isEmpty() -> "Nothing new since $since."
                    else -> "Alerted on ${o.alerted.size} new: " +
                        o.alerted.joinToString { it.displaySender }
                },
                color = if (o.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Overlap: $overlapMin min", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                overlapMin = (overlapMin - 5).coerceAtLeast(5)
                WindowStore.setOverlapMinutes(context, overlapMin)
            }) { Text("−") }
            TextButton(onClick = {
                overlapMin = (overlapMin + 5).coerceAtMost(120)
                WindowStore.setOverlapMinutes(context, overlapMin)
            }) { Text("+") }
        }

        Text(
            "Rules (fixed for now; editable in a later version):",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Rules.hardcoded.forEach { rule ->
            Text(
                "• ${rule.name}: ${rule.description}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    recent = withContext(Dispatchers.IO) {
                        val token = TokenStore.load(context)
                        if (token == null) {
                            QueryResult.Failed("No token saved.")
                        } else {
                            Jmap.queryRules(session, token, Rules.hardcoded)
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Show recent matches (no alerts)")
        }

        when (val r = recent) {
            null -> Unit
            is QueryResult.Failed -> Text(
                r.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            is QueryResult.Found -> {
                Spacer(Modifier.height(8.dp))
                r.ruleErrors.forEach {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (r.emails.isEmpty()) "No matching mail." else
                        "${r.emails.size} matching emails. Tap one to test its alert in $ALERT_DELAY_SECONDS s, then lock your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                r.emails.forEach { email ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    EmailRow(email) {
                        Alerts.scheduleTest(context, ALERT_DELAY_SECONDS, email.displaySender, email.subject)
                        Toast.makeText(
                            context,
                            "Alert in $ALERT_DELAY_SECONDS seconds. Lock your phone! 😱",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailRow(email: EmailSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Text(
            email.sender,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            email.subject,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val time = email.receivedAt?.let { timeFormat.format(it.atZone(ZoneId.systemDefault())) }
        Text(
            listOfNotNull(time, email.matchedRules.joinToString(" · ").ifEmpty { null })
                .joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
