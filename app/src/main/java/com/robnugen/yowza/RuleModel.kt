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
import org.json.JSONObject
import java.util.UUID

/** Which part of the email a condition looks at (JMAP FilterCondition properties). */
enum class Field(val jmap: String, val label: String) {
    FROM("from", "From"),
    TO("to", "To"),
    SUBJECT("subject", "Subject"),
    TEXT("text", "Anywhere"),
    ;

    companion object {
        fun fromJmap(s: String) = entries.firstOrNull { it.jmap == s } ?: TEXT
    }
}

data class Condition(val field: Field, val value: String)

/** A user-editable rule: conditions combined with AND ([matchAll]) or OR. */
data class StoredRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val matchAll: Boolean = true,
    val conditions: List<Condition>,
    /** If false, this rule's alerts wait until quiet hours end. */
    val wakeDuringQuiet: Boolean = true,
) {
    /** Conditions with something in them. A rule with none never matches. */
    val usable: List<Condition> get() = conditions.filter { it.value.isNotBlank() }

    fun describe(): String {
        val parts = usable.map { "${it.field.label.lowercase()} contains \"${it.value.trim()}\"" }
        return if (parts.isEmpty()) "(no conditions)" else parts.joinToString(if (matchAll) " and " else " or ")
    }
}

object RuleJson {
    const val FORMAT = "yowza-rules"
    const val VERSION = 1

    /** The JMAP Email/query filter for one rule, or null if it has no usable conditions. */
    fun toFilter(rule: StoredRule): JSONObject? {
        val conds = rule.usable.map { JSONObject().put(it.field.jmap, it.value.trim()) }
        return when (conds.size) {
            0 -> null
            1 -> conds[0]
            else -> JSONObject()
                .put("operator", if (rule.matchAll) "AND" else "OR")
                .put("conditions", JSONArray(conds))
        }
    }

    /** Enabled, usable rules as query-ready [Rule]s. */
    fun toQueryRules(rules: List<StoredRule>): List<Rule> =
        rules.filter { it.enabled }.mapNotNull { r -> toFilter(r)?.let { Rule(r.name, r.describe(), it) } }

    /** File format for local storage and the Google Drive copy. Never contains the token. */
    fun encode(rules: List<StoredRule>): String {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("enabled", r.enabled)
                    .put("match", if (r.matchAll) "all" else "any")
                    .put("wakeDuringQuiet", r.wakeDuringQuiet)
                    .put(
                        "conditions",
                        JSONArray(r.conditions.map { JSONObject().put("field", it.field.jmap).put("value", it.value) }),
                    ),
            )
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("rules", arr).toString(2)
    }

    /** Parse [encode]'s output. Throws IllegalArgumentException with a readable message if it isn't one. */
    fun decode(text: String): List<StoredRule> {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("That file isn't JSON.")
        }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("That isn't a Yowza! rules file.")
        val arr = root.optJSONArray("rules") ?: throw IllegalArgumentException("The file has no rules.")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val conds = o.optJSONArray("conditions") ?: JSONArray()
            StoredRule(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { "Rule ${i + 1}" },
                enabled = o.optBoolean("enabled", true),
                matchAll = o.optString("match", "all") != "any",
                wakeDuringQuiet = o.optBoolean("wakeDuringQuiet", true),
                conditions = (0 until conds.length()).map { j ->
                    val c = conds.getJSONObject(j)
                    Condition(Field.fromJmap(c.optString("field")), c.optString("value"))
                },
            )
        }
    }
}
