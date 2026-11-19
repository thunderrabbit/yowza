package com.robnugen.yowza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {
    // A real reply captured from server/alerts.php against MariaDB.
    private val reply = """{"cursor":3,"alerts":[
        {"id":1,"seq":2,"source":"my-site","title":"Backup failed","body":"still full","priority":"high","count":2,
         "first_seen_at":"2026-11-19T03:10:58Z","last_seen_at":"2026-11-19T03:10:58Z"},
        {"id":2,"seq":3,"source":"my-site","title":"PHP ERROR in x.php","body":"","priority":"normal","count":1,
         "first_seen_at":"2026-11-19T03:10:58Z","last_seen_at":"2026-11-19T03:10:58Z"}],
        "more":false,"source_code":"https://github.com/thunderrabbit/yowza/tree/main/server"}"""

    @Test fun parsesAlertsAndCursor() {
        val r = InboxClient.parse(reply) as InboxResult.Ok
        assertEquals(3L, r.cursor)
        assertEquals(listOf("Backup failed", "PHP ERROR in x.php"), r.alerts.map { it.title })
        assertTrue(r.alerts[0].high)
        assertEquals(2, r.alerts[0].count)
    }

    @Test fun firstPollHasNoBacklog() {
        val r = InboxClient.parse("""{"cursor":42,"alerts":[],"more":false}""") as InboxResult.Ok
        assertEquals(42L, r.cursor)
        assertTrue(r.alerts.isEmpty())
    }

    @Test fun rejectsNonInboxReplies() {
        assertTrue(InboxClient.parse("<html>Not found</html>") is InboxResult.Failed)
    }

    @Test fun summaryUsesSourceAsSenderAndCarriesCount() {
        val s = (InboxClient.parse(reply) as InboxResult.Ok).alerts[0].toSummary()
        assertEquals("my-site", s.displaySender)
        assertEquals("Backup failed", s.subject)
        assertEquals(2, s.repeat)
        assertEquals(listOf(InboxAlert.INBOX_RULE), s.matchedRules)
    }

    @Test fun grownCountIsANewEventWithTheSameCooldownKey() {
        val a = (InboxClient.parse(reply) as InboxResult.Ok).alerts[0]
        val later = a.copy(count = 7).toSummary()
        assertTrue(a.toSummary().id != later.id)
        assertEquals(Cooldown.keyFor(a.toSummary()), Cooldown.keyFor(later))
    }

    @Test fun serverCountsShowInCollapsedAlert() {
        val s = (InboxClient.parse(reply) as InboxResult.Ok).alerts[0].toSummary()
        val (_, subject) = summarizeForAlert(listOf(s))
        assertEquals("×2  Backup failed", subject)
    }
}
