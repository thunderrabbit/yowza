package com.robnugen.yowza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlertWindowTest {
    private val t0 = Instant.parse("2026-11-18T12:00:00Z")
    private val overlap = Duration.ofMinutes(10)

    private fun email(id: String, minutesAfterT0: Long, from: String = "Colleague", subject: String = "Hi") =
        EmailSummary(id, from, "x@example.com", subject, t0.plusSeconds(minutesAfterT0 * 60))

    @Test fun windowStartIsWatermarkMinusOverlap() {
        assertEquals(Instant.parse("2026-11-18T11:50:00Z"), AlertWindow(t0).windowStart(overlap))
    }

    @Test fun newEmailAlertsAndAdvancesWatermark() {
        val d = AlertWindow(t0).process(listOf(email("a", 5)), overlap)
        assertEquals(listOf("a"), d.toAlert.map { it.id })
        assertEquals(t0.plusSeconds(300), d.nextState.watermark)
        assertTrue("a" in d.nextState.alerted)
    }

    @Test fun secondRunWithSameResultsAlertsNothing() {
        val results = listOf(email("a", 5), email("b", 6))
        val first = AlertWindow(t0).process(results, overlap)
        val second = first.nextState.process(results, overlap)
        assertEquals(2, first.toAlert.size)
        assertTrue(second.toAlert.isEmpty())
    }

    @Test fun lateArrivalInsideOverlapStillAlertsOnce() {
        // Watermark moved to t0+5; an email stamped t0-3 shows up late, inside the overlap.
        val s1 = AlertWindow(t0).process(listOf(email("a", 5)), overlap).nextState
        val d = s1.process(listOf(email("a", 5), email("late", -3)), overlap)
        assertEquals(listOf("late"), d.toAlert.map { it.id })
    }

    @Test fun emailsBeforeWindowAreIgnored() {
        val d = AlertWindow(t0).process(listOf(email("old", -60)), overlap)
        assertTrue(d.toAlert.isEmpty())
    }

    @Test fun watermarkNeverMovesBackwards() {
        val d = AlertWindow(t0).process(listOf(email("a", -5)), overlap)
        assertEquals(t0, d.nextState.watermark)
    }

    @Test fun alertedIdsOlderThanWindowArePruned() {
        val s1 = AlertWindow(t0).process(listOf(email("a", 0)), overlap).nextState
        val s2 = s1.process(listOf(email("b", 30)), overlap).nextState
        assertTrue("a" !in s2.alerted)
        assertTrue("b" in s2.alerted)
    }

    @Test fun duplicateIdsInOneResultAlertOnce() {
        val d = AlertWindow(t0).process(listOf(email("a", 1), email("a", 1)), overlap)
        assertEquals(1, d.toAlert.size)
    }

    @Test fun identicalRepeatsCollapseIntoOneLine() {
        val errs = (1..40).map { email("e$it", it.toLong(), "examplesite", "PHP ERROR in Database.php - Line 214") }
        val (sender, subject) = summarizeForAlert(errs)
        assertEquals("examplesite", sender)
        assertEquals("×40  PHP ERROR in Database.php - Line 214", subject)
    }

    @Test fun mixedEmailsListEachGroup() {
        val list = listOf(
            email("1", 1, "Colleague", "Lunch?"),
            email("2", 2, "examplesite", "PHP ERROR"),
            email("3", 3, "examplesite", "PHP ERROR"),
        )
        val (sender, subject) = summarizeForAlert(list)
        assertEquals("3 new emails", sender)
        assertEquals("Colleague: Lunch?\n×2 examplesite: PHP ERROR", subject)
    }
}
