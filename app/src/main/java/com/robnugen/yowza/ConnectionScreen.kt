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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Paste / replace / forget the JMAP API token. */
@Composable
fun ConnectionScreen(
    state: ConnectionState,
    onSave: (token: String, sessionUrl: String) -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var sessionUrl by remember { mutableStateOf(TokenStore.sessionUrl(context)) }
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
            Text(
                "Mail connection",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            StatusText(state)

            Spacer(Modifier.height(24.dp))
            Text(
                "Create a token in Fastmail: Settings → Privacy & Security → " +
                    "Manage API tokens → New API token. Choose read-only access and the " +
                    "Email scope only, then paste it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text(if (TokenStore.hasToken(context)) "New API token" else "API token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = sessionUrl,
                onValueChange = { sessionUrl = it.trim() },
                label = { Text("JMAP session URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Leave as is for Fastmail. Other JMAP servers have their own session URL.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onSave(token, sessionUrl.ifBlank { TokenStore.DEFAULT_SESSION_URL })
                    token = ""
                },
                enabled = token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and connect") }

            if (TokenStore.hasToken(context)) {
                Spacer(Modifier.height(32.dp))
                OutlinedButton(
                    onClick = onForget,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Forget token") }
            }
        }
    }
}

/** One-line connection status, used on both screens. */
@Composable
fun StatusText(state: ConnectionState) {
    val (text, isError) = when (state) {
        ConnectionState.NoToken -> "Status: Not connected" to false
        ConnectionState.Connecting -> "Status: Connecting…" to false
        is ConnectionState.Connected -> "Status: Connected as ${state.session.accountName} ✅" to false
        is ConnectionState.Failed -> "Status: ${state.message}" to true
    }
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (state is ConnectionState.Connected && !state.session.mailIsReadOnly) {
            Text(
                "⚠️ This token can change your mail. Yowza! only needs read-only access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
