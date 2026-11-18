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

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

private const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"

/** What we need from a JMAP session (RFC 8620 §2). */
data class JmapSession(
    val username: String,
    val accountId: String,
    val accountName: String,
    val apiUrl: String,
    val eventSourceUrl: String,
    val mailIsReadOnly: Boolean,
)

sealed class ConnectionState {
    data object NoToken : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val session: JmapSession) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

object Jmap {
    /** Blocking: call off the main thread. Never puts the token in an error message. */
    fun fetchSession(sessionUrl: String, token: String): ConnectionState {
        val conn = try {
            URL(sessionUrl).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return ConnectionState.Failed("That server address isn't a valid URL.")
        }
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                200 -> parseSession(conn.inputStream.bufferedReader().use { it.readText() })
                401 -> ConnectionState.Failed("Token rejected (401): it's wrong, expired or revoked.")
                403 -> ConnectionState.Failed("Token not allowed (403): check its access in Fastmail.")
                404 -> ConnectionState.Failed("No JMAP session at that address (404).")
                else -> ConnectionState.Failed("Server answered HTTP $code.")
            }
        } catch (e: UnknownHostException) {
            ConnectionState.Failed("Can't reach the server. Are you online?")
        } catch (e: SocketTimeoutException) {
            ConnectionState.Failed("The server took too long to answer.")
        } catch (e: IOException) {
            ConnectionState.Failed("Network error: ${e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSession(body: String): ConnectionState = try {
        val json = JSONObject(body)
        val accountId = json.getJSONObject("primaryAccounts").optString(MAIL_CAPABILITY, "")
        if (accountId.isEmpty()) {
            ConnectionState.Failed("This token has no mail access. Give it the Email scope.")
        } else {
            val account = json.getJSONObject("accounts").getJSONObject(accountId)
            ConnectionState.Connected(
                JmapSession(
                    username = json.optString("username"),
                    accountId = accountId,
                    accountName = account.optString("name", json.optString("username")),
                    apiUrl = json.getString("apiUrl"),
                    eventSourceUrl = json.optString("eventSourceUrl"),
                    mailIsReadOnly = account.optBoolean("isReadOnly", false),
                ),
            )
        }
    } catch (e: JSONException) {
        ConnectionState.Failed("The server's answer wasn't a JMAP session.")
    }
}
