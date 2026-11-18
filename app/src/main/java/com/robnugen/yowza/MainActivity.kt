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
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TEST_DELAY_SECONDS = 10

class MainActivity : ComponentActivity() {

    // Bumped on every resume so the permission rows re-check after a trip to Settings.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Alerts.ensureChannel(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        setContent {
            YowzaTheme {
                HomeScreen(version = version, refreshKey = resumeCount)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}

internal val YowzaOrange = Color(0xFFFF6D00)

@Composable
fun YowzaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = YowzaOrange, onPrimary = Color.Black)
    } else {
        lightColorScheme(primary = YowzaOrange, onPrimary = Color.Black)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private data class Readiness(
    val notifications: Boolean,
    val fullScreen: Boolean,
    val bypassDnd: Boolean,
) {
    val allGood get() = notifications && fullScreen && bypassDnd
}

private fun readiness(context: Context) = Readiness(
    notifications = Alerts.canPostNotifications(context),
    fullScreen = Alerts.canUseFullScreen(context),
    bypassDnd = Alerts.canBypassDnd(context),
)

@Composable
fun HomeScreen(version: String, refreshKey: Int) {
    val context = LocalContext.current
    var permissionTick by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    // Re-read on every resume and after the permission dialog closes.
    val ready = androidx.compose.runtime.remember(refreshKey, permissionTick) { readiness(context) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionTick++ }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Yowza!",
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text = "👋 ✉️ 🥳🎉", fontSize = 32.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Big alerts for rare messages.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Text(text = "Status: Not connected", style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(24.dp))
            Text(
                text = if (ready.allGood) "Alerts are ready ✅" else "Alerts need setup",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            SetupRow(
                label = "Notifications",
                ok = ready.notifications,
                fixLabel = "Allow",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppNotificationSettings(context)
                }
            }
            SetupRow(
                label = "Full-screen alerts",
                ok = ready.fullScreen,
                fixLabel = "Open settings",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }
            SetupRow(
                label = "Override Do Not Disturb",
                ok = ready.bypassDnd,
                fixLabel = "Open settings",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, Alerts.CHANNEL_ID)
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    Alerts.scheduleTest(context, TEST_DELAY_SECONDS)
                    Toast.makeText(
                        context,
                        "Test alert in $TEST_DELAY_SECONDS seconds. Lock your phone now! 😱",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                enabled = ready.notifications,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text("Test alert in $TEST_DELAY_SECONDS s", fontSize = 20.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { Alerts.showTest(context) },
                enabled = ready.notifications,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test alert now")
            }
            Text(
                text = "While you're using the phone, Android shows a banner instead of the full screen.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            val lastTest = androidx.compose.runtime.remember(refreshKey) { Alerts.lastTestSummary(context) }
            if (lastTest != null) {
                Text(
                    text = lastTest,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(32.dp))
            Text(text = "v$version", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SetupRow(label: String, ok: Boolean, fixLabel: String, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = (if (ok) "✅ " else "❌ ") + label,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!ok) {
            OutlinedButton(onClick = onFix) { Text(fixLabel) }
        }
    }
}

private fun openAppNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        },
    )
}
