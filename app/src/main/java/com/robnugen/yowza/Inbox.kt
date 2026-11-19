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

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.Instant

/** One alert from the server inbox (server/alerts.php). */
data class InboxAlert(
    val id: Long,
    val seq: Long,
    val source: String,
    val title: String,
    val body: String,
    val high: Boolean,
    val count: Int,
    val lastSeenAt: Instant?,
) {
    /**
     * Same path as email alerts. The source plays the sender; the id includes the count so a
     * collapsed alert whose count grew is a new event (the cooldown then mutes it).
     */
    fun toSummary() = EmailSummary(
        id = "inbox:$id:$count",
        fromName = source,
        fromEmail = "",
        subject = title,
        receivedAt = lastSeenAt,
        matchedRules = listOf(INBOX_RULE),
        repeat = count,
    )

    companion object {
        /** Rule name shown on alerts from the server inbox. */
        const val INBOX_RULE = "Server inbox"
    }
}

sealed class InboxResult {
    data class Ok(val cursor: Long, val alerts: List<InboxAlert>, val more: Boolean) : InboxResult()
    data class Failed(val message: String, val unauthorized: Boolean = false) : InboxResult()
}

object InboxClient {
    /** Pure: parse alerts.php's GET reply. */
    fun parse(body: String): InboxResult = try {
        val o = JSONObject(body)
        val arr = o.optJSONArray("alerts")
        val alerts = (0 until (arr?.length() ?: 0)).map { i ->
            val a = arr!!.getJSONObject(i)
            InboxAlert(
                id = a.getLong("id"),
                seq = a.getLong("seq"),
                source = a.optString("source"),
                title = a.optString("title"),
                body = a.optString("body"),
                high = a.optString("priority") == "high",
                count = a.optInt("count", 1).coerceAtLeast(1),
                lastSeenAt = a.optString("last_seen_at").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
        InboxResult.Ok(o.getLong("cursor"), alerts, o.optBoolean("more", false))
    } catch (e: JSONException) {
        InboxResult.Failed("The server's answer wasn't a Yowza! inbox reply.")
    }

    /** Blocking GET. [after] null = just fetch the current cursor. Never puts the key in a message. */
    fun fetch(url: String, key: String, after: Long?): InboxResult {
        val full = if (after == null) url else {
            url + (if ('?' in url) "&" else "?") + "after=" + URLEncoder.encode(after.toString(), "UTF-8")
        }
        val conn = try {
            URL(full).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return InboxResult.Failed("That inbox address isn't a valid URL.")
        }
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("X-Yowza-Key", key) // for hosts that drop Authorization
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                200 -> parse(conn.inputStream.bufferedReader().use { it.readText() })
                401 -> InboxResult.Failed("Inbox key rejected (401).", unauthorized = true)
                404 -> InboxResult.Failed("No inbox at that address (404).")
                else -> InboxResult.Failed("Inbox answered HTTP $code.")
            }
        } catch (e: UnknownHostException) {
            InboxResult.Failed("Can't reach the inbox server. Are you online?")
        } catch (e: SocketTimeoutException) {
            InboxResult.Failed("The inbox server took too long to answer.")
        } catch (e: IOException) {
            InboxResult.Failed("Network error: ${e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }
}

/** Inbox settings and state. The key is encrypted with the Keystore, like the mail token. */
object InboxStore {
    private const val PREFS = "inbox"

    fun url(context: Context): String = prefs(context).getString("url", "").orEmpty()
    fun key(context: Context): String? = prefs(context).getString("key", null)?.let(TokenStore::unseal)
    fun isConfigured(context: Context) = url(context).isNotBlank() && key(context) != null

    fun save(context: Context, url: String, key: String?) {
        val e = prefs(context).edit().putString("url", url.trim())
        if (key != null) e.putString("key", TokenStore.seal(key.trim()))
        // New address or key: start fresh, so no backlog arrives.
        e.remove("cursor").apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun cursor(context: Context): Long? =
        prefs(context).getLong("cursor", -1L).takeIf { it >= 0 }

    fun setCursor(context: Context, cursor: Long) {
        prefs(context).edit().putLong("cursor", cursor).commit()
    }

    /** Server alerts may sound during quiet hours (high-priority ones always may). */
    fun canWake(context: Context) = prefs(context).getBoolean("can_wake", true)
    fun setCanWake(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("can_wake", on).apply()
    }

    fun markFetch(context: Context, error: String?) {
        prefs(context).edit()
            .putLong("last_fetch", System.currentTimeMillis())
            .putString("last_error", error)
            .apply()
    }

    fun lastFetch(context: Context): Instant? =
        prefs(context).getLong("last_fetch", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }

    fun lastError(context: Context): String? = prefs(context).getString("last_error", null)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Result of one inbox poll, for the home screen and log. */
data class InboxOutcome(val delivered: Delivery?, val error: String?, val started: Boolean = false)

object InboxPoller {
    private val lock = Any()
    const val INTERVAL_MS = 3 * 60_000L

    /** Due if the last poll was at least [INTERVAL_MS] ago. */
    fun isDue(context: Context): Boolean {
        val last = InboxStore.lastFetch(context) ?: return true
        return System.currentTimeMillis() - last.toEpochMilli() >= INTERVAL_MS
    }

    /**
     * Fetch new server alerts and deliver them like email alerts: alert first, then save the
     * cursor (a crash repeats an alert, never misses one). Blocking: call off the main thread.
     */
    fun pollOnce(context: Context): InboxOutcome = synchronized(lock) {
        if (!InboxStore.isConfigured(context)) return@synchronized InboxOutcome(null, null)
        val url = InboxStore.url(context)
        val key = InboxStore.key(context) ?: return@synchronized InboxOutcome(null, "Inbox key unreadable; enter it again.")
        var cursor = InboxStore.cursor(context)

        if (cursor == null) {
            // First poll for this address/key: take the current cursor, deliver nothing old.
            return@synchronized when (val r = InboxClient.fetch(url, key, null)) {
                is InboxResult.Ok -> {
                    InboxStore.setCursor(context, r.cursor)
                    InboxStore.markFetch(context, null)
                    AppLog.info(context, "Inbox connected, starting at ${r.cursor}")
                    InboxOutcome(null, null, started = true)
                }
                is InboxResult.Failed -> fail(context, r)
            }
        }

        val all = mutableListOf<InboxAlert>()
        var pages = 0
        while (true) {
            when (val r = InboxClient.fetch(url, key, cursor)) {
                is InboxResult.Failed -> return@synchronized fail(context, r)
                is InboxResult.Ok -> {
                    all += r.alerts
                    cursor = r.cursor
                    if (!r.more || ++pages >= 5) break
                }
            }
        }

        val canWake = InboxStore.canWake(context)
        val quiet = QuietStore.load(context)
        val nowLocal = java.time.LocalTime.now()
        // Keep only each alert's newest state (a count may have grown twice since last poll).
        val newest = all.groupBy { it.id }.values.map { v -> v.maxBy { it.seq } }
        val d = if (newest.isEmpty()) null else {
            val byId = newest.associateBy { "inbox:${it.id}:${it.count}" }
            Checker.deliver(context, newest.map { it.toSummary() }) { s ->
                canWake || byId[s.id]?.high == true || !quiet.isQuiet(nowLocal)
            }
        }
        InboxStore.setCursor(context, cursor!!) // …then record
        InboxStore.markFetch(context, null)
        Alerts.clearInboxProblem(context)
        if (d != null) {
            AppLog.info(
                context,
                "Inbox: ${newest.size} new → ${d.loud.size} alerted" +
                    (if (d.muted > 0) ", ${d.muted} muted repeats" else "") +
                    (if (d.held.isNotEmpty()) ", ${d.held.size} held for quiet hours" else "") +
                    " (sources: ${newest.map { it.source }.distinct().joinToString()})",
            )
        }
        InboxOutcome(d, null)
    }

    private fun fail(context: Context, r: InboxResult.Failed): InboxOutcome {
        InboxStore.markFetch(context, r.message)
        AppLog.error(context, "Inbox: ${r.message}")
        if (r.unauthorized) {
            Alerts.showInboxProblem(context, "Yowza! can't log in to your server inbox", "${r.message} Enter the read key again in Server inbox.")
        }
        return InboxOutcome(null, r.message)
    }
}
