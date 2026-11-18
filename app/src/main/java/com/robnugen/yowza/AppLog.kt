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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A small ring buffer of what the app did, for the Health screen.
 * Never logs email content (senders, subjects) or the token: only counts, rule names
 * and connection events.
 */
object AppLog {
    private const val PREFS = "app_log"
    private const val KEY = "entries"
    private const val MAX = 100
    private val lock = Any()

    data class Entry(val at: Instant, val message: String, val isError: Boolean)

    fun info(context: Context, message: String) = add(context, message, false)
    fun error(context: Context, message: String) = add(context, message, true)

    fun entries(context: Context): List<Entry> = synchronized(lock) {
        val arr = runCatching { JSONArray(prefs(context).getString(KEY, "[]")) }.getOrElse { JSONArray() }
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Entry(Instant.ofEpochMilli(o.getLong("t")), o.getString("m"), o.optBoolean("e"))
            }.getOrNull()
        }.reversed() // newest first
    }

    fun clear(context: Context) = synchronized(lock) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun add(context: Context, message: String, isError: Boolean) = synchronized(lock) {
        val p = prefs(context)
        val arr = runCatching { JSONArray(p.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("m", message.take(200)).put("e", isError))
        val trimmed = if (arr.length() > MAX) {
            JSONArray().also { out -> for (i in arr.length() - MAX until arr.length()) out.put(arr.get(i)) }
        } else {
            arr
        }
        p.edit().putString(KEY, trimmed.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
