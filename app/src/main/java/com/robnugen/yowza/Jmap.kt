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

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.Instant

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

/** Just enough of an email to alert on. Never the body. */
data class EmailSummary(
    val id: String,
    val fromName: String,
    val fromEmail: String,
    val subject: String,
    val receivedAt: Instant?,
    val matchedRules: List<String> = emptyList(),
) {
    val sender: String get() = fromName.ifBlank { fromEmail }.ifBlank { "(unknown sender)" }
}

sealed class QueryResult {
    /** [ruleErrors]: rules the server rejected while others still returned results. */
    data class Found(
        val emails: List<EmailSummary>,
        val ruleErrors: List<String> = emptyList(),
    ) : QueryResult()
    data class Failed(val message: String) : QueryResult()
}

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

    /**
     * One JMAP request covering every rule: for rule i, Email/query "q<i>" (newest first,
     * up to [limitPerRule]) then Email/get "g<i>" on those ids via a back-reference,
     * fetching only from/subject/receivedAt. Results are merged, each email tagged with
     * the rules it matched. Blocking: call off the main thread.
     */
    fun queryRules(
        session: JmapSession,
        token: String,
        rules: List<Rule>,
        limitPerRule: Int = 10,
    ): QueryResult {
        val calls = JSONArray()
        rules.forEachIndexed { i, rule ->
            calls.put(
                JSONArray()
                    .put("Email/query")
                    .put(
                        JSONObject()
                            .put("accountId", session.accountId)
                            .put("filter", rule.filter)
                            .put(
                                "sort",
                                JSONArray().put(
                                    JSONObject().put("property", "receivedAt").put("isAscending", false),
                                ),
                            )
                            .put("limit", limitPerRule),
                    )
                    .put("q$i"),
            )
            calls.put(
                JSONArray()
                    .put("Email/get")
                    .put(
                        JSONObject()
                            .put("accountId", session.accountId)
                            .put(
                                "#ids",
                                JSONObject()
                                    .put("resultOf", "q$i")
                                    .put("name", "Email/query")
                                    .put("path", "/ids"),
                            )
                            .put("properties", JSONArray().put("from").put("subject").put("receivedAt")),
                    )
                    .put("g$i"),
            )
        }
        val request = JSONObject()
            .put("using", JSONArray().put("urn:ietf:params:jmap:core").put(MAIL_CAPABILITY))
            .put("methodCalls", calls)

        val conn = try {
            URL(session.apiUrl).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return QueryResult.Failed("Bad API URL from the server.")
        }
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            when (val code = conn.responseCode) {
                200 -> parseRuleResults(conn.inputStream.bufferedReader().use { it.readText() }, rules)
                401 -> QueryResult.Failed("Token rejected (401): it's wrong, expired or revoked.")
                else -> QueryResult.Failed("Server answered HTTP $code.")
            }
        } catch (e: UnknownHostException) {
            QueryResult.Failed("Can't reach the server. Are you online?")
        } catch (e: SocketTimeoutException) {
            QueryResult.Failed("The server took too long to answer.")
        } catch (e: IOException) {
            QueryResult.Failed("Network error: ${e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRuleResults(body: String, rules: List<Rule>): QueryResult {
        return try {
            val responses = JSONObject(body).getJSONArray("methodResponses")
            val byId = LinkedHashMap<String, EmailSummary>()
            val errors = mutableListOf<String>()
            for (i in 0 until responses.length()) {
                val r = responses.getJSONArray(i)
                val callId = r.getString(2)
                val ruleName = callId.drop(1).toIntOrNull()?.let { rules.getOrNull(it)?.name } ?: "?"
                when (r.getString(0)) {
                    "error" -> {
                        val err = r.getJSONObject(1)
                        // A failed query also makes its back-referenced get fail; report once.
                        if (callId.startsWith("q")) {
                            errors += "$ruleName: ${err.optString("type")} ${err.optString("description")}".trim()
                        }
                    }
                    "Email/get" -> {
                        val list = r.getJSONObject(1).getJSONArray("list")
                        for (j in 0 until list.length()) {
                            val email = parseEmail(list.getJSONObject(j))
                            val existing = byId[email.id]
                            byId[email.id] = (existing ?: email).copy(
                                matchedRules = (existing?.matchedRules ?: emptyList()) + ruleName,
                            )
                        }
                    }
                }
            }
            if (byId.isEmpty() && errors.isNotEmpty()) {
                QueryResult.Failed("Server error — " + errors.joinToString("; "))
            } else {
                QueryResult.Found(
                    byId.values.sortedByDescending { it.receivedAt ?: Instant.EPOCH },
                    errors,
                )
            }
        } catch (e: JSONException) {
            QueryResult.Failed("Couldn't read the server's answer.")
        }
    }

    private fun parseEmail(e: JSONObject): EmailSummary {
        val from = e.optJSONArray("from")?.optJSONObject(0)
        return EmailSummary(
            id = e.getString("id"),
            fromName = from?.optString("name").orEmpty().let { n -> if (n == "null") "" else n },
            fromEmail = from?.optString("email").orEmpty(),
            subject = e.optString("subject").let { s -> if (s == "null" || s.isEmpty()) "(no subject)" else s },
            receivedAt = e.optString("receivedAt").takeIf { s -> s.isNotEmpty() }
                ?.let { s -> runCatching { Instant.parse(s) }.getOrNull() },
        )
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
