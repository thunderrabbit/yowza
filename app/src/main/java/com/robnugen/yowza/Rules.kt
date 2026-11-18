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

import org.json.JSONObject

/** A ready-to-query rule: a name and its JMAP Email/query filter (RFC 8621 §4.4.1). */
data class Rule(val name: String, val description: String, val filter: JSONObject)

object Rules {
    /**
     * The rules that were hardcoded up to v0.1.14. Written into rule storage once, the first
     * time this version runs, so they become ordinary editable rules. The old ExampleCo
     * rule (A or (B and C)) is split into two rules, since a rule is one AND or one OR.
     */
    val seeds: List<StoredRule> = listOf(
        StoredRule(name = "Client", conditions = listOf(Condition(Field.FROM, "client@example.com"))),
        StoredRule(name = "Colleague", conditions = listOf(Condition(Field.FROM, "colleague@example.com"))),
        StoredRule(name = "Teammate", conditions = listOf(Condition(Field.FROM, "teammate@example.com"))),
        StoredRule(name = "ExampleCo", conditions = listOf(Condition(Field.FROM, "example.org"))),
        StoredRule(
            name = "ExampleCo (JobBoard)",
            matchAll = true,
            conditions = listOf(
                Condition(Field.FROM, "jobs.example.net"),
                Condition(Field.TEXT, "ExampleCo"),
            ),
        ),
        StoredRule(name = "PHP error", conditions = listOf(Condition(Field.SUBJECT, "PHP ERROR in"))),
    )
}
