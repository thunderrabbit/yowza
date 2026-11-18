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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Rule list, rule editor, and backups. */
@Composable
fun RulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rules = remember { mutableStateListOf<StoredRule>().apply { addAll(RuleStore.load(context)) } }
    var editing by remember { mutableStateOf<StoredRule?>(null) }
    var mirrorTick by remember { mutableStateOf(0) }
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }

    fun persist() {
        val snapshot = rules.toList()
        scope.launch {
            withContext(Dispatchers.IO) { RuleStore.save(context, snapshot) }
            mirrorTick++
        }
    }

    val createMirror = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { RuleStore.linkMirror(context, uri) }
            mirrorTick++
        }
    }
    val openImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport = uri
    }

    editing?.let { rule ->
        RuleEditor(
            initial = rule,
            onSave = { saved ->
                val i = rules.indexOfFirst { it.id == saved.id }
                if (i >= 0) rules[i] = saved else rules.add(saved)
                persist()
                editing = null
            },
            onDelete = {
                rules.removeAll { it.id == rule.id }
                persist()
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Rules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Mail matching any enabled rule raises an alert. Tap a rule to edit it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            rules.forEachIndexed { i, rule ->
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = rule }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text(rule.describe(), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = {
                            rules[i] = rule.copy(enabled = it)
                            persist()
                        },
                    )
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { editing = StoredRule(name = "", conditions = listOf(Condition(Field.FROM, ""))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add rule") }

            Spacer(Modifier.height(32.dp))
            Text("Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Rules are included in Android's automatic backup to your Google account. " +
                    "You can also keep a copy as a file, e.g. in Google Drive, updated after every change. " +
                    "The mail token is never included.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            @Suppress("UNUSED_EXPRESSION") mirrorTick
            val mirrorName = RuleStore.mirrorName(context)
            if (mirrorName == null) {
                OutlinedButton(
                    onClick = { createMirror.launch("yowza-rules.json") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save rules to a file…") }
            } else {
                Text("Copy: $mirrorName", style = MaterialTheme.typography.bodyMedium)
                RuleStore.mirrorStatus(context)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { createMirror.launch("yowza-rules.json") }) { Text("Change file") }
                    TextButton(onClick = {
                        RuleStore.unlinkMirror(context)
                        mirrorTick++
                    }) { Text("Stop copying") }
                }
            }
            OutlinedButton(
                onClick = { openImport.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Load rules from a file…") }
        }
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Replace all rules?") },
            text = { Text("The rules in that file will replace your ${rules.size} current rules.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { RuleStore.importFrom(context, uri) }
                        }
                        result.onSuccess { n ->
                            rules.clear()
                            rules.addAll(RuleStore.load(context))
                            Toast.makeText(context, "Loaded $n rules", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(context, it.message ?: "Couldn't load that file", Toast.LENGTH_LONG).show()
                        }
                        mirrorTick++
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RuleEditor(
    initial: StoredRule,
    onSave: (StoredRule) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var matchAll by remember { mutableStateOf(initial.matchAll) }
    val conditions = remember { mutableStateListOf<Condition>().apply { addAll(initial.conditions) } }
    val isNew = initial.name.isBlank()
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
            Text(
                if (isNew) "New rule" else "Edit rule",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (shown on the alert)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Match", style = MaterialTheme.typography.bodyMedium)
                FilterChip(selected = matchAll, onClick = { matchAll = true }, label = { Text("all conditions") })
                FilterChip(selected = !matchAll, onClick = { matchAll = false }, label = { Text("any condition") })
            }
            Spacer(Modifier.height(8.dp))
            conditions.forEachIndexed { i, c ->
                ConditionRow(
                    condition = c,
                    onChange = { conditions[i] = it },
                    onRemove = if (conditions.size > 1) ({ conditions.removeAt(i) }) else null,
                )
            }
            TextButton(onClick = { conditions.add(Condition(Field.FROM, "")) }) { Text("+ Add condition") }
            Text(
                "Each condition matches text contained in that part of the email, " +
                    "e.g. From contains \"example.org\".",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            val draft = initial.copy(name = name.trim(), matchAll = matchAll, conditions = conditions.toList())
            Button(
                onClick = { onSave(draft) },
                enabled = draft.name.isNotEmpty() && draft.usable.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            if (!isNew) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete rule", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ConditionRow(condition: Condition, onChange: (Condition) -> Unit, onRemove: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            OutlinedButton(onClick = { menu = true }) { Text(condition.field.label) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Field.entries.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(f.label) },
                        onClick = {
                            onChange(condition.copy(field = f))
                            menu = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = condition.value,
            onValueChange = { onChange(condition.copy(value = it)) },
            label = { Text("contains") },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        if (onRemove != null) TextButton(onClick = onRemove) { Text("✕") }
    }
}
