package com.robnugen.yowza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class QuietHoursTest {
    private val night = QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0))
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test fun overnightWindow() {
        assertTrue(night.isQuiet(t(23)))
        assertTrue(night.isQuiet(t(2)))
        assertTrue(night.isQuiet(t(22)))
        assertFalse(night.isQuiet(t(7)))
        assertFalse(night.isQuiet(t(12)))
    }

    @Test fun sameDayWindow() {
        val nap = QuietHours(true, t(13), t(15))
        assertTrue(nap.isQuiet(t(14)))
        assertFalse(nap.isQuiet(t(15)))
        assertFalse(nap.isQuiet(t(9)))
    }

    @Test fun disabledIsNeverQuiet() {
        assertFalse(night.copy(enabled = false).isQuiet(t(2)))
    }

    @Test fun nextEndIsLaterTodayOrTomorrow() {
        val zone = ZoneId.of("Australia/Adelaide")
        val at2am = ZonedDateTime.of(2026, 11, 19, 2, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 19, 7, 0, 0, 0, zone), night.nextEnd(at2am))
        val at11pm = ZonedDateTime.of(2026, 11, 19, 23, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 20, 7, 0, 0, 0, zone), night.nextEnd(at11pm))
    }

    @Test fun wakersAlertDuringQuietOthersWait() {
        val wakers = setOf("ExampleCo")
        assertTrue(shouldAlertNow(listOf("ExampleCo"), wakers, night, t(3)))
        assertFalse(shouldAlertNow(listOf("PHP error"), wakers, night, t(3)))
        assertTrue(shouldAlertNow(listOf("PHP error", "ExampleCo"), wakers, night, t(3)))
        assertTrue(shouldAlertNow(listOf("PHP error"), wakers, night, t(9)))
    }

    @Test fun ruleQuietFlagSurvivesRoundTrip() {
        val r = StoredRule(id = "x", name = "PHP", conditions = listOf(Condition(Field.SUBJECT, "PHP")), wakeDuringQuiet = false)
        assertEquals(listOf(r), RuleJson.decode(RuleJson.encode(listOf(r))))
    }
}
