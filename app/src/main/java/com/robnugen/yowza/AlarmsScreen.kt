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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** List of time-zone alarms. */
@Composable
fun AlarmsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alarms = remember { mutableStateListOf<ZonedAlarm>().apply { addAll(AlarmStore.load(context)) } }
    var editing by remember { mutableStateOf<ZonedAlarm?>(null) }

    fun persist() {
        val snapshot = alarms.toList()
        scope.launch { withContext(Dispatchers.IO) { AlarmStore.save(context, snapshot) } }
    }

    editing?.let { alarm ->
        AlarmEditor(
            initial = alarm,
            isNew = alarms.none { it.id == alarm.id },
            knownZones = alarms.map { it.zone }.distinct(),
            onSave = { saved ->
                val i = alarms.indexOfFirst { it.id == saved.id }
                if (i >= 0) alarms[i] = saved else alarms.add(saved)
                persist()
                editing = null
            },
            onDelete = {
                AlarmScheduler.cancel(context, alarm)
                alarms.removeAll { it.id == alarm.id }
                persist()
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    BackHandler(onBack = onBack)
    val here = ZoneId.systemDefault()
    val now = Instant.now()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Time-zone alarms", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Pinned to a city's clock: \"09:00 Tokyo\" rings at 09:00 in Tokyo wherever you are. " +
                    "You're in ${here.id} now.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (alarms.isEmpty()) Text("No alarms yet.", style = MaterialTheme.typography.bodyMedium)
            alarms.forEachIndexed { i, a ->
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = a }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            a.describeTime() + "  ·  " + a.label.ifBlank { "Alarm" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val next = a.nextFire(now)
                        Text(
                            a.describeDays() + "  ·  " + a.zone.id +
                                (next?.let { "\nNext: " + ZonedAlarm.describeHere(it, here, now) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = a.enabled, onCheckedChange = {
                        alarms[i] = a.copy(enabled = it)
                        if (!it) AlarmScheduler.cancel(context, a)
                        persist()
                    })
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    editing = ZonedAlarm(
                        label = "",
                        time = LocalTime.of(9, 0),
                        zone = alarms.lastOrNull()?.zone ?: here,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add alarm") }
            if (!Alerts.canScheduleExact(context)) {
                Text(
                    "Android isn't letting Yowza! set exact alarms, so alarms may ring late.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditor(
    initial: ZonedAlarm,
    isNew: Boolean,
    knownZones: List<ZoneId>,
    onSave: (ZonedAlarm) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var zone by remember { mutableStateOf(initial.zone) }
    var search by remember { mutableStateOf("") }
    val days = remember { mutableStateListOf<DayOfWeek>().apply { addAll(initial.days) } }
    val timeState = rememberTimePickerState(initial.time.hour, initial.time.minute, is24Hour = true)
    val here = ZoneId.systemDefault()
    BackHandler(onBack = onCancel)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            TextButton(onClick = onCancel) { Text("← Cancel") }
            Text("Alarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (shown when it rings)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("Time in ${zone.id}", style = MaterialTheme.typography.titleMedium)
            TimeInput(state = timeState)

            Text("Time zone", style = MaterialTheme.typography.titleMedium)
            Text(zone.id, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(here) + knownZones + listOf(ZoneId.of("Asia/Tokyo"), ZoneId.of("Australia/Adelaide")))
                    .distinct().forEach { z ->
                        FilterChip(
                            selected = z == zone,
                            onClick = { zone = z },
                            label = { Text(if (z == here) "${z.id} (here)" else z.id) },
                        )
                    }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Find a city or zone, e.g. London") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (search.length >= 2) {
                val q = search.trim().replace(' ', '_')
                ZoneId.getAvailableZoneIds().asSequence()
                    .filter { it.contains(q, ignoreCase = true) && '/' in it && !it.startsWith("Etc/") }
                    .sorted().take(12).toList()
                    .forEach { id ->
                        Text(
                            id,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    zone = ZoneId.of(id)
                                    search = ""
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
            }

            Spacer(Modifier.height(16.dp))
            Text("Repeat (days in ${zone.id})", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(
                        selected = d in days,
                        onClick = { if (d in days) days.remove(d) else days.add(d) },
                        label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                    )
                }
            }
            Row {
                TextButton(onClick = { days.clear() }) { Text("Once") }
                TextButton(onClick = { days.clear(); days.addAll(ZonedAlarm.WEEKDAYS) }) { Text("Weekdays") }
                TextButton(onClick = { days.clear(); days.addAll(DayOfWeek.entries) }) { Text("Every day") }
            }

            val draft = initial.copy(
                label = label.trim(),
                time = LocalTime.of(timeState.hour, timeState.minute),
                zone = zone,
                days = days.toSet(),
                enabled = true,
            )
            val now = Instant.now()
            draft.nextFire(now)?.let {
                Text(
                    "Rings next at ${draft.describeTime()} = ${ZonedAlarm.describeHere(it, here, now)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Button(onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (!isNew) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete alarm", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
