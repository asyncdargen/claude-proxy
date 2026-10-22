package org.claudeproxy.api

import org.claudeproxy.repo.WindowSample
import java.time.Instant
import kotlin.test.*

class WindowAttributionTest {
    private val t = Instant.parse("2026-10-15T00:00:00Z")
    private fun sample(seconds: Long, util: Double, reset: Instant? = null) = WindowSample(1,"5h",t.plusSeconds(seconds),util,1.0,reset)
    private fun event(seconds: Long, user: Int?, cost: Double, known: Boolean = true, tokens: Long = 10) = AttributionUsage(1,user,t.plusSeconds(seconds),cost,known,tokens)
    private fun periods(samples: List<WindowSample>, events: List<AttributionUsage> = emptyList(), liveReset: Instant? = t.plusSeconds(18000), now: Instant = t.plusSeconds(600)) =
        attributeWindow(1,"5h",samples,events,mapOf(1 to "alice",2 to "bob"),liveReset,now)

    @Test fun `adding reset metadata to legacy readings does not invent a new quota period`() {
        val end=t.plusSeconds(18000)
        val ps=periods(listOf(sample(0,0.2),sample(60,0.4,end),sample(120,0.6,end)),listOf(event(20,1,1.0),event(90,2,1.0)),liveReset=end)
        assertEquals(1,ps.size)
        assertEquals(0.2,ps.single().contributors.single { it.userId == 1 }.amount,1e-9)
        assertEquals(t.toString(),ps.single().startAt)
        assertFalse(ps.single().estimatedBounds)
    }

    @Test fun `zero opening baseline at known start is complete but missing baseline is partial`() {
        val complete=periods(listOf(sample(0,0.0),sample(60,0.2))).single()
        assertFalse(complete.partial)
        assertTrue(periods(listOf(sample(30,0.2),sample(60,0.4))).single().partial)
        assertTrue(periods(listOf(sample(0,0.2)),liveReset=null).single().partial)
    }

    @Test fun `no readings means no periods and unknown requests and zero weights stay unattributed`() {
        assertTrue(periods(emptyList()).isEmpty())
        for (es in listOf(emptyList(),listOf(event(20,null,1.0)),listOf(event(20,99,1.0)),listOf(event(20,1,0.0,tokens=0)))) {
            val p=periods(listOf(sample(0,0.2),sample(60,0.6)),es).single()
            assertEquals("unattributed",p.contributors.single().key)
            assertEquals("unattributed",p.contributors.single().method)
            assertEquals(0.6,p.contributors.single().amount,1e-9)
        }
    }

    @Test fun `unknown or zero cost switches whole interval to tokens and contributor never hides token estimates`() {
        val p=periods(listOf(sample(0,0.0),sample(60,0.4),sample(120,0.6)),listOf(
            event(10,1,100.0,tokens=10),event(20,2,0.0,known=false,tokens=30),event(100,1,1.0))).single()
        assertEquals(0.3,p.contributors.single { it.userId == 1 }.amount,1e-9)
        assertEquals(0.3,p.contributors.single { it.userId == 2 }.amount,1e-9)
        assertEquals("tokens",p.contributors.single { it.userId == 1 }.method)
        for (known in listOf(true,false)) {
            val zero=periods(listOf(sample(0,0.0),sample(60,0.4)),listOf(event(20,1,0.0,known,tokens=10))).single()
            assertEquals("tokens",zero.contributors.single().method)
            assertEquals(0.4,zero.contributors.single().amount)
        }
    }

    @Test fun `idle zero readings do not stretch a legacy period beyond the window duration`() {
        // Reset to 0 at 0s, idle zeros polled every 30 min for 20h, then usage. Real data shape.
        val idle = (0..40).map { sample(it * 1800L, 0.0) }
        val busy = listOf(sample(72_060, 0.1), sample(75_000, 0.3), sample(90_000, 0.5), sample(95_000, 0.6))
        val ps = periods(idle + busy, liveReset = null, now = t.plusSeconds(95_100))
        ps.forEach { p ->
            val end = p.endAt?.let(Instant::parse) ?: Instant.parse(p.observedAt)
            assertTrue(end.epochSecond - Instant.parse(p.startAt).epochSecond <= 18000, "period ${p.startAt}..${p.endAt} longer than 5h")
        }
        assertEquals(0.6, ps.last().total, 1e-9)
    }

    @Test fun `past legacy period ending in a long idle gap is capped at the window length`() {
        // 0.2 → 0.8 within an hour, silence for 10h, then a drop (reset) and new usage.
        val ps = periods(listOf(sample(0, 0.2), sample(3600, 0.8), sample(40_000, 0.1), sample(41_000, 0.3)),
            liveReset = null, now = t.plusSeconds(41_100))
        assertEquals(2, ps.size)
        val first = ps.first()
        assertTrue(Instant.parse(first.endAt!!) <= Instant.parse(first.startAt).plusSeconds(18000))
        assertTrue(first.estimatedBounds)
    }

    @Test fun `zero-only legacy run before the live window merges instead of overlapping it`() {
        val liveEnd = t.plusSeconds(18000 + 3600) // window started at t+3600
        val ps = periods(listOf(sample(0, 0.0), sample(1800, 0.0), sample(4200, 0.0), sample(5000, 0.2)),
            liveReset = liveEnd, now = t.plusSeconds(5100))
        ps.zipWithNext().forEach { (a, b) ->
            assertTrue(Instant.parse(a.endAt!!) <= Instant.parse(b.startAt), "overlap ${a.endAt} > ${b.startAt}")
        }
        assertTrue(ps.none { p -> p.total == 0.0 && p.points.all { it.total == 0.0 } && !p.current }, "empty zero-only period emitted")
        assertEquals(0.2, ps.last().total, 1e-9)
        assertTrue(ps.last().current)
    }

    @Test fun `reset timestamps drifting by seconds do not split the period`() {
        // reset_after_seconds-derived resets jitter a few seconds between readings.
        val end = t.plusSeconds(18000)
        val ps = periods(listOf(sample(0, 0.0, end), sample(60, 0.2, end.plusSeconds(2)),
            sample(120, 0.4, end.minusSeconds(3)), sample(180, 0.6, end.plusSeconds(40))),
            listOf(event(30, 1, 1.0), event(90, 2, 1.0), event(150, 1, 1.0)), liveReset = end, now = t.plusSeconds(200))
        assertEquals(1, ps.size, ps.joinToString { "${it.startAt}..${it.endAt} ${it.total} ${it.contributors}" })
        assertEquals(0.4, ps.single().contributors.single { it.userId == 1 }.amount, 1e-9)
    }

    @Test fun `small legacy reset to near zero is detected`() {
        val ps = periods(listOf(sample(0, 0.05), sample(600, 0.12), sample(20_000, 0.01), sample(20_600, 0.05)),
            liveReset = null, now = t.plusSeconds(20_700))
        assertEquals(2, ps.size)
        assertEquals(0.05, ps.last().total, 1e-9)
    }

    @Test fun `dense readings are bounded to 200 points without losing observed final totals`() {
        val p = periods((0..600).map { sample(it.toLong(),it/500.0) }).single()
        assertTrue(p.points.size <= 200)
        assertEquals(t.toString(),p.points.first().ts)
        assertEquals(t.plusSeconds(600).toString(),p.points.last().ts)
        assertEquals(1.2,p.total,1e-9)
        assertEquals(1.2,p.contributors.sumOf { it.amount },1e-9)
    }

    @Test fun `legacy reset still detected when live reset is supplied`() {
        val ps = periods(listOf(sample(0,0.9),sample(60,0.0),sample(120,0.1)))
        assertEquals(2,ps.size)
        assertTrue(ps[0].estimatedBounds)
        assertFalse(ps[0].current)
        assertEquals(t.plusSeconds(18000).toString(),ps[1].endAt)
    }

    @Test fun `recorded reset boundaries split even without a utilization drop and stale data is not current`() {
        val end1 = t.plusSeconds(18000)
        val end2 = t.plusSeconds(36000)
        val ps = periods(listOf(sample(0,0.2,end1),sample(17900,0.3,end1),sample(18010,0.4,end2)),liveReset=end2,now=t.plusSeconds(20000))
        assertEquals(2,ps.size)
        assertEquals(t.toString(),ps[0].startAt)
        assertEquals(end1.toString(),ps[0].endAt)
        assertFalse(ps[0].estimatedBounds)
        assertFalse(ps[0].current)
        assertTrue(ps[1].current)
        assertEquals(end1.toString(),ps[1].startAt)
        val stale = periods(listOf(sample(0,0.4,end1)),liveReset=end1,now=t.plusSeconds(200000)).single()
        assertFalse(stale.current)
        assertEquals(t.toString(),stale.observedAt)
    }

    @Test fun `substantial legacy drop starts separate estimated historical and current periods`() {
        val ps = periods(listOf(sample(0,0.9),sample(60,0.0),sample(120,0.2)),listOf(event(90,1,1.0)),liveReset=null)
        assertEquals(2,ps.size)
        assertFalse(ps[0].current)
        assertTrue(ps.all { it.estimatedBounds })
        assertEquals(t.plusSeconds(60).toString(),ps[0].endAt)
        assertEquals(0.2,ps[1].contributors.single { it.userId == 1 }.amount,1e-9)
    }

    @Test fun `rounding corrections reconcile to observation without counting recovery as new usage`() {
        val p = periods(listOf(sample(0,0.55),sample(60,0.54),sample(120,0.55),sample(180,0.71),sample(240,0.69),sample(300,0.71)),
            listOf(event(90,1,1.0),event(150,2,1.0),event(270,1,1.0))).single()
        assertEquals(0.71,p.contributors.sumOf { it.amount },1e-9)
        assertFalse(p.contributors.any { it.userId == 1 && it.amount > 0 })
        p.points.forEach { assertEquals(it.total,it.values.values.sum(),1e-9) }
    }

    @Test fun `observed delta is weighted by known request cost and opening reading stays unattributed`() {
        val p = periods(listOf(sample(0,0.2),sample(60,0.6)),listOf(event(20,1,1.0),event(40,2,3.0))).single()
        assertEquals(0.6,p.total,1e-9)
        val values = p.contributors.associate { it.key to it.amount }
        assertEquals(0.2,values.getValue("unattributed"),1e-9)
        assertEquals(0.1,values.getValue("user:1"),1e-9)
        assertEquals(0.3,values.getValue("user:2"),1e-9)
        assertEquals("cost",p.contributors.first { it.userId == 1 }.method)
        p.points.forEach { assertEquals(it.total,it.values.values.sum(),1e-9) }
        assertEquals(t.plusSeconds(60).toString(),p.observedAt)
    }
}
