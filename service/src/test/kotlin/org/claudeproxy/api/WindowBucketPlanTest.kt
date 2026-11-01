package org.claudeproxy.api

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window-utilization charts must not draw into the future. Sizing the axis to the end of the
 * last calendar day leaves the rest of today empty, and because the aggregator carries the last
 * reading forward across gaps, those buckets render as a flat line running hours ahead of now —
 * which reads as current data. The grid is therefore truncated at `now`, without changing the
 * bucket width.
 */
class WindowBucketPlanTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val halfHourMs = 30 * 60 * 1000.0

    @Test
    fun `a range that already ended keeps the full day grid`() {
        val plan = planWindowBuckets(
            days = 1, endDate = LocalDate.parse("2026-01-10"), zone = utc,
            now = Instant.parse("2026-01-15T12:00:00Z"),
        )
        assertEquals(Instant.parse("2026-01-10T00:00:00Z"), plan.start)
        assertEquals(Instant.parse("2026-01-11T00:00:00Z"), plan.end)
        assertEquals(48, plan.buckets)
        assertEquals(halfHourMs, plan.widthMs)
        // A historical range does not reach the present, so there is no live column to add.
        assertFalse(plan.truncated, "a past range must not be flagged as reaching now")
    }

    @Test
    fun `a range ending today stops at the current bucket, not at midnight`() {
        val now = Instant.parse("2026-01-15T06:10:00Z")
        val plan = planWindowBuckets(days = 1, endDate = LocalDate.parse("2026-01-15"), zone = utc, now = now)

        // Bucket width is unchanged — only the tail is dropped.
        assertEquals(halfHourMs, plan.widthMs)
        // 06:10 falls in the 13th half-hour bucket (06:00–06:30), which is kept: it is partially
        // elapsed but already holds readings.
        assertEquals(13, plan.buckets)
        assertEquals(Instant.parse("2026-01-15T06:30:00Z"), plan.end)
        assertTrue(plan.end.isAfter(now), "the bucket containing now must be included")
        assertTrue(plan.buckets < 48, "the rest of the day must not be charted")
        assertTrue(plan.truncated, "a range reaching now must be flagged for the live column")
    }

    /**
     * The live "now" column is delivered by stamping synthetic samples into an extra bucket past
     * the grid. That bucket's start is a truncated long, so a sample sitting exactly on it can
     * floor back into the previous bucket whenever the width is not integral (coarsened ranges) —
     * hence the mid-bucket stamp. This pins that down against the real aggregator.
     */
    @Test
    fun `live samples land in the extra bucket, not the last real one`() {
        // A deliberately non-integral width, as a >7-day range produces.
        val plan = planWindowBuckets(
            days = 30, endDate = LocalDate.parse("2026-02-14"), zone = utc,
            now = Instant.parse("2026-02-14T00:00:00Z"),
        )
        assertTrue(plan.widthMs % 1.0 != 0.0, "this test needs a fractional bucket width")

        val liveTs = Instant.ofEpochMilli(plan.end.toEpochMilli() + (plan.widthMs / 2).toLong())
        val total = plan.buckets + 1
        val payload = aggregateWindows(
            samples = listOf(org.claudeproxy.repo.WindowSample(1, "5h", liveTs, 0.42)),
            startMs = plan.start.toEpochMilli(), widthMs = plan.widthMs, buckets = total,
            labels = (0 until total).map { "b$it" }, includeAccounts = false, names = emptyMap(),
        )

        // The reading shows up in the appended column…
        assertEquals(0.42, payload.totalFiveHour[total - 1]!!, 1e-9)
        // …and nowhere before it: the grid holds no other samples, so every earlier bucket is null.
        assertEquals(null, payload.totalFiveHour[total - 2], "live value must not bleed into the grid")
    }

    /**
     * What the mid-bucket offset actually buys. Carry-forward means a boundary-stamped live sample
     * still *reaches* the appended column, so the visible symptom is not a missing point — it is
     * that the sample also lands in the last real bucket and is averaged into whatever genuine
     * reading was there, silently rewriting history at the right edge.
     */
    @Test
    fun `stamping on the extra bucket boundary corrupts the last real bucket`() {
        val widthMs = 7714285.714285714 // 30 days over the 336-bucket cap => fractional width
        val grid = 3
        val total = grid + 1
        val labels = (0 until total).map { "b$it" }
        // A genuine reading inside the last real bucket.
        val real = org.claudeproxy.repo.WindowSample(
            1, "5h", Instant.ofEpochMilli((2 * widthMs).toLong() + 100), 0.2,
        )
        fun runWith(liveTs: Instant) = aggregateWindows(
            samples = listOf(real, org.claudeproxy.repo.WindowSample(1, "5h", liveTs, 0.9)),
            startMs = 0L, widthMs = widthMs, buckets = total,
            labels = labels, includeAccounts = false, names = emptyMap(),
        ).totalFiveHour

        val onBoundary = runWith(Instant.ofEpochMilli((grid * widthMs).toLong()))
        assertEquals(0.55, onBoundary[grid - 1]!!, 1e-9) // (0.2 + 0.9) / 2 — the real reading is gone

        val midBucket = runWith(
            Instant.ofEpochMilli((grid * widthMs).toLong() + (widthMs / 2).toLong()),
        )
        assertEquals(0.2, midBucket[grid - 1]!!, 1e-9)   // last real bucket untouched
        assertEquals(0.9, midBucket[total - 1]!!, 1e-9)  // live value in its own column
    }

    @Test
    fun `truncation works across a multi-day range`() {
        val now = Instant.parse("2026-01-21T02:00:00Z")
        val plan = planWindowBuckets(days = 7, endDate = LocalDate.parse("2026-01-21"), zone = utc, now = now)

        assertEquals(Instant.parse("2026-01-15T00:00:00Z"), plan.start)
        assertEquals(halfHourMs, plan.widthMs)
        // 6 whole days (288 buckets) + 4 buckets of the 7th day, plus the in-progress one.
        assertEquals(293, plan.buckets)
        assertTrue(plan.end.isAfter(now))
        assertTrue(plan.end.isBefore(Instant.parse("2026-01-22T00:00:00Z")))
    }

    @Test
    fun `a coarsened long range keeps its wider buckets when truncated`() {
        val now = Instant.parse("2026-02-14T00:00:00Z")
        val full = planWindowBuckets(days = 30, endDate = LocalDate.parse("2026-01-30"), zone = utc, now = now)
        val truncated = planWindowBuckets(days = 30, endDate = LocalDate.parse("2026-02-14"), zone = utc, now = now)

        // 30 days over the 336-bucket cap => buckets wider than half an hour…
        assertTrue(full.widthMs > halfHourMs)
        // …and truncating the range must not silently change that width.
        assertEquals(full.widthMs, truncated.widthMs)
        assertEquals(336, full.buckets)
        assertTrue(truncated.buckets < 336)
    }

    @Test
    fun `a range starting in the future degrades to a single bucket`() {
        val plan = planWindowBuckets(
            days = 1, endDate = LocalDate.parse("2026-03-20"), zone = utc,
            now = Instant.parse("2026-01-15T00:00:00Z"),
        )
        assertEquals(1, plan.buckets)
        assertTrue(plan.end.isAfter(plan.start))
    }

    @Test
    fun `the grid follows the viewer's zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val plan = planWindowBuckets(
            days = 1, endDate = LocalDate.parse("2026-01-15"), zone = tokyo,
            now = Instant.parse("2026-01-14T16:10:00Z"), // 01:10 on the 15th in Tokyo
        )
        assertEquals(Instant.parse("2026-01-14T15:00:00Z"), plan.start) // 00:00 JST
        assertEquals(3, plan.buckets)                                    // 00:00–01:30 JST
    }
}
