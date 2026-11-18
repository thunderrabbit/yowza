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

/**
 * A named JMAP Email/query filter (RFC 8621 §4.4.1). The mail server does the matching.
 * Hardcoded for now (step 4.5); step 7 makes rules editable data.
 */
data class Rule(val name: String, val description: String, val filter: JSONObject)

private fun cond(key: String, value: String) = JSONObject().put(key, value)

private fun anyOf(vararg c: JSONObject) =
    JSONObject().put("operator", "OR").put("conditions", JSONArray(c.toList()))

private fun allOf(vararg c: JSONObject) =
    JSONObject().put("operator", "AND").put("conditions", JSONArray(c.toList()))

object Rules {
    val hardcoded: List<Rule> = listOf(
        Rule(
            name = "Client",
            description = "from client@example.com",
            filter = cond("from", "client@example.com"),
        ),
        Rule(
            name = "Colleague",
            description = "from colleague@example.com",
            filter = cond("from", "colleague@example.com"),
        ),
        Rule(
            name = "Teammate",
            description = "from teammate@example.com",
            filter = cond("from", "teammate@example.com"),
        ),
        Rule(
            name = "ExampleCo",
            description = "from example.org, or JobBoard mail mentioning ExampleCo",
            filter = anyOf(
                cond("from", "example.org"),
                allOf(cond("from", "jobs.example.net"), cond("text", "ExampleCo")),
            ),
        ),
        Rule(
            name = "PHP error",
            description = "subject contains \"PHP ERROR in\"",
            filter = cond("subject", "PHP ERROR in"),
        ),
    )
}
