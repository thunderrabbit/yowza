package com.robnugen.yowza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleModelTest {
    private fun rule(matchAll: Boolean, vararg c: Pair<Field, String>) =
        StoredRule(id = "r", name = "R", matchAll = matchAll, conditions = c.map { Condition(it.first, it.second) })

    @Test fun singleConditionIsAPlainFilterCondition() {
        val f = RuleJson.toFilter(rule(true, Field.FROM to " client@example.com "))!!
        assertEquals("{\"from\":\"client@example.com\"}", f.toString())
    }

    @Test fun matchAllBecomesAnd() {
        val f = RuleJson.toFilter(rule(true, Field.FROM to "jobs.example.net", Field.TEXT to "ExampleCo"))!!
        assertEquals("AND", f.getString("operator"))
        assertEquals(2, f.getJSONArray("conditions").length())
        assertEquals("ExampleCo", f.getJSONArray("conditions").getJSONObject(1).getString("text"))
    }

    @Test fun matchAnyBecomesOr() {
        val f = RuleJson.toFilter(rule(false, Field.SUBJECT to "a", Field.TO to "b"))!!
        assertEquals("OR", f.getString("operator"))
        assertEquals("b", f.getJSONArray("conditions").getJSONObject(1).getString("to"))
    }

    @Test fun blankConditionsAreIgnoredAndEmptyRuleHasNoFilter() {
        assertNull(RuleJson.toFilter(rule(true, Field.FROM to "  ")))
        val f = RuleJson.toFilter(rule(true, Field.FROM to "x", Field.SUBJECT to ""))!!
        assertEquals("{\"from\":\"x\"}", f.toString())
    }

    @Test fun disabledAndEmptyRulesAreNotQueried() {
        val rules = listOf(
            rule(true, Field.FROM to "a").copy(id = "1", name = "On"),
            rule(true, Field.FROM to "b").copy(id = "2", name = "Off", enabled = false),
            rule(true, Field.FROM to "").copy(id = "3", name = "Empty"),
        )
        assertEquals(listOf("On"), RuleJson.toQueryRules(rules).map { it.name })
    }

    @Test fun encodeDecodeRoundTrip() {
        val back = RuleJson.decode(RuleJson.encode(Rules.seeds))
        assertEquals(Rules.seeds, back)
    }

    @Test fun seedsCoverThePreviouslyHardcodedRules() {
        val names = RuleJson.toQueryRules(Rules.seeds).map { it.name }
        assertEquals(listOf("Client", "Colleague", "Teammate", "ExampleCo", "ExampleCo (JobBoard)", "PHP error"), names)
    }

    @Test fun decodeRejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { RuleJson.decode("{\"hello\":1}") }
        assertThrows(IllegalArgumentException::class.java) { RuleJson.decode("not json") }
    }

    @Test fun describeReadsNaturally() {
        val d = rule(true, Field.FROM to "jobs.example.net", Field.TEXT to "ExampleCo").describe()
        assertTrue(d, d == "from contains \"jobs.example.net\" and anywhere contains \"ExampleCo\"")
    }
}
