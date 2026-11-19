package com.robnugen.yowza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class ZonedAlarmTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val adelaide = ZoneId.of("Australia/Adelaide")
    private val newYork = ZoneId.of("America/New_York")
    private fun at(s: String) = Instant.parse(s)

    @Test fun tokyoNineAmIsTheSameMomentWhereverThePhoneIs() {
        val a = ZonedAlarm(label = "Call", time = LocalTime.of(9, 0), zone = tokyo)
        // 2026-11-19 08:00 JST = 2026-11-18T23:00Z
        val fire = a.nextFire(at("2026-11-18T23:00:00Z"))
        assertEquals(at("2026-11-19T00:00:00Z"), fire)
        // Seen from Adelaide (UTC+10:30 in November) that is 10:30.
        assertEquals("10:30 here", ZonedAlarm.describeHere(fire!!, adelaide, at("2026-11-18T23:00:00Z")))
    }

    @Test fun passedTimeMovesToTomorrow() {
        val a = ZonedAlarm(label = "x", time = LocalTime.of(9, 0), zone = tokyo)
        assertEquals(at("2026-11-20T00:00:00Z"), a.nextFire(at("2026-11-19T00:00:00Z")))
    }

    @Test fun weekdaysSkipTheWeekendInTheAlarmsZone() {
        val a = ZonedAlarm(label = "x", time = LocalTime.of(9, 0), zone = tokyo, days = ZonedAlarm.WEEKDAYS)
        // Fri 2026-11-20 10:00 JST → next is Mon 2026-11-23 09:00 JST
        assertEquals(at("2026-11-23T00:00:00Z"), a.nextFire(at("2026-11-20T01:00:00Z")))
    }

    @Test fun springForwardGapRingsAtTheShiftedTime() {
        // New York 2026-03-08: 02:00 → 03:00. 02:30 doesn't exist; rings at 03:30 EDT (07:30Z).
        val a = ZonedAlarm(label = "x", time = LocalTime.of(2, 30), zone = newYork)
        assertEquals(at("2026-03-08T07:30:00Z"), a.nextFire(at("2026-03-08T05:00:00Z")))
    }

    @Test fun fallBackOverlapRingsOnceAtTheFirst() {
        // New York 2026-11-01: 01:30 happens twice; first is EDT (05:30Z).
        val a = ZonedAlarm(label = "x", time = LocalTime.of(1, 30), zone = newYork)
        val first = a.nextFire(at("2026-11-01T04:00:00Z"))
        assertEquals(at("2026-11-01T05:30:00Z"), first)
        // Asking again just after it rang skips the repeat hour and goes to the next day.
        assertEquals(at("2026-11-02T06:30:00Z"), a.nextFire(first!!))
    }

    @Test fun disabledNeverFires() {
        assertNull(ZonedAlarm(label = "x", time = LocalTime.NOON, zone = tokyo, enabled = false).nextFire(Instant.EPOCH))
    }

    @Test fun jsonRoundTripAndUnknownZoneSkipped() {
        val a = ZonedAlarm(id = "1", label = "Call", time = LocalTime.of(9, 5), zone = tokyo, days = setOf(DayOfWeek.MONDAY))
        assertEquals(listOf(a), AlarmJson.decode(AlarmJson.encode(listOf(a))))
        val bad = AlarmJson.encode(listOf(a)).replace("Asia/Tokyo", "Mars/Olympus_Mons")
        assertEquals(emptyList<ZonedAlarm>(), AlarmJson.decode(bad))
    }

    @Test fun describesDays() {
        val a = ZonedAlarm(label = "x", time = LocalTime.NOON, zone = tokyo)
        assertEquals("Once", a.describeDays())
        assertEquals("Weekdays", a.copy(days = ZonedAlarm.WEEKDAYS).describeDays())
        assertEquals("Every day", a.copy(days = DayOfWeek.entries.toSet()).describeDays())
    }
}
