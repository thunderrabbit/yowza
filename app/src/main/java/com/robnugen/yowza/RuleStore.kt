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
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Rules live in files/rules.json (the only file Android's own backup copies, see
 * res/xml/backup_rules.xml). Optionally mirrored to a file the user picked, e.g. in
 * Google Drive, rewritten after every change.
 */
object RuleStore {
    private const val FILE = "rules.json"
    private const val PREFS = "rules_meta"
    private const val SEEDED = "seeded_v1"
    private const val MIRROR_URI = "mirror_uri"
    private const val MIRROR_STATUS = "mirror_status"

    private val lock = Any()

    fun load(context: Context): List<StoredRule> = synchronized(lock) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) {
            // First run of the rules version: keep the previously hardcoded rules, once only.
            val p = prefs(context)
            if (!p.getBoolean(SEEDED, false)) {
                writeLocal(context, Rules.seeds)
                p.edit().putBoolean(SEEDED, true).commit()
                return@synchronized Rules.seeds
            }
            return@synchronized emptyList()
        }
        runCatching { RuleJson.decode(f.readText()) }.getOrElse { emptyList() }
    }

    /** Save locally, then mirror to the linked file if there is one. Call off the main thread. */
    fun save(context: Context, rules: List<StoredRule>) {
        synchronized(lock) {
            writeLocal(context, rules)
            prefs(context).edit().putBoolean(SEEDED, true).commit()
        }
        android.app.backup.BackupManager(context).dataChanged()
        mirror(context, rules)
    }

    /** Enabled, usable rules ready for JMAP. */
    fun queryRules(context: Context): List<Rule> = RuleJson.toQueryRules(load(context))

    // --- Mirror file (e.g. Google Drive), chosen with the system file picker ---

    fun mirrorUri(context: Context): Uri? = prefs(context).getString(MIRROR_URI, null)?.let(Uri::parse)
    fun mirrorStatus(context: Context): String? = prefs(context).getString(MIRROR_STATUS, null)

    fun mirrorName(context: Context): String? {
        val uri = mirrorUri(context) ?: return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
    }

    /** Remember a file the user created/picked and write the current rules to it. */
    fun linkMirror(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs(context).edit().putString(MIRROR_URI, uri.toString()).apply()
        mirror(context, load(context))
    }

    fun unlinkMirror(context: Context) {
        mirrorUri(context)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs(context).edit().remove(MIRROR_URI).remove(MIRROR_STATUS).apply()
    }

    /** Replace all rules with the contents of [uri]. Returns the count, or throws with a readable message. */
    fun importFrom(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: throw IllegalArgumentException("Couldn't open that file.")
        val rules = RuleJson.decode(text)
        save(context, rules)
        return rules.size
    }

    private fun mirror(context: Context, rules: List<StoredRule>) {
        val uri = mirrorUri(context) ?: return
        val status = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(RuleJson.encode(rules).toByteArray(Charsets.UTF_8))
            } ?: error("couldn't open the file")
            "Saved ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}"
        }.getOrElse { "Couldn't save: ${it.message ?: it.javaClass.simpleName}" }
        prefs(context).edit().putString(MIRROR_STATUS, status).apply()
    }

    private fun writeLocal(context: Context, rules: List<StoredRule>) {
        val f = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(RuleJson.encode(rules))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
