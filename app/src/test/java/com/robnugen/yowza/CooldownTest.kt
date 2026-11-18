package com.robnugen.yowza

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CooldownTest {
    private val t0 = Instant.parse("2026-11-19T00:00:00Z")
    private val window = Duration.ofMinutes(30)
    private val err = Cooldown.Group("php", "anddev: PHP ERROR", 1)
    private val colleague = Cooldown.Group("colleague", "Colleague: Lunch?", 1)

    @Test fun firstTimeIsLoud() {
        val r = Cooldown().apply(listOf(err), t0, window)
        assertEquals(setOf("php"), r.loudKeys)
        assertTrue(r.muted.isEmpty())
    }

    @Test fun repeatWithinWindowIsMutedAndCounted() {
        val s1 = Cooldown().apply(listOf(err), t0, window).next
        val r = s1.apply(listOf(err.copy(count = 3)), t0.plusSeconds(600), window)
        assertTrue(r.loudKeys.isEmpty())
        assertEquals(4, r.muted.single().total)
    }

    @Test fun repeatAfterWindowIsLoudAgain() {
        val s1 = Cooldown().apply(listOf(err), t0, window).next
        val r = s1.apply(listOf(err), t0.plus(window).plusSeconds(1), window)
        assertEquals(setOf("php"), r.loudKeys)
        assertEquals(1, r.next.entries.getValue("php").total)
    }

    @Test fun mutedRepeatDoesNotExtendTheWindow() {
        val s1 = Cooldown().apply(listOf(err), t0, window).next
        val s2 = s1.apply(listOf(err), t0.plusSeconds(25 * 60), window).next
        val r = s2.apply(listOf(err), t0.plusSeconds(31 * 60), window)
        assertEquals(setOf("php"), r.loudKeys)
    }

    @Test fun differentEmailStillAlertsDuringAnotherCooldown() {
        val s1 = Cooldown().apply(listOf(err), t0, window).next
        val r = s1.apply(listOf(err, colleague), t0.plusSeconds(60), window)
        assertEquals(setOf("colleague"), r.loudKeys)
        assertEquals(listOf("anddev: PHP ERROR"), r.muted.map { it.label })
    }
}
