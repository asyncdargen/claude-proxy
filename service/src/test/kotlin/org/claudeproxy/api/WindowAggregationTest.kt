package org.claudeproxy.api

import org.claudeproxy.repo.WindowSample
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [aggregateWindows] is a pure function over the sample list, so we exercise the sum +
 * carry-forward math without any DB or route wiring.
 */
class WindowAggregationTest {
    // 4 one-second buckets starting at epoch 0.
    private val startMs = 0L
    private val widthMs = 1000.0
    private val buckets = 4
    private val labels = (0 until buckets).map { "b$it" }

    private fun sample(acct: Int, bucket: Int, util: Double, kind: String = "5h") =
        WindowSample(acct, kind, Instant.ofEpochMilli(bucket * 1000L + 10), util)

    private fun assertSeries(expected: List<Double?>, actual: List<Double?>, msg: String) {
        assertEquals(expected.size, actual.size, "$msg: size")
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            if (e == null) assertEquals(null, a, "$msg[$i] expected null")
            else {
                assertTrue(a != null && kotlin.math.abs(e - a) < 1e-9, "$msg[$i] expected $e got $a")
            }
        }
    }

    @Test
    fun `pool total sums utilization across accounts and carries forward across gaps`() {
        // acct1: b0=0.5, b2=0.2 ; acct2: b0=0.8, b3=0.1 ; b1 is a gap for both.
        val samples = listOf(
            sample(1, 0, 0.5), sample(1, 2, 0.2),
            sample(2, 0, 0.8), sample(2, 3, 0.1),
        )
        val p = aggregateWindows(samples, startMs, widthMs, buckets, labels, includeAccounts = true, names = emptyMap())

        // raw = Σ util (carry-forward fills b1 with the last value)
        assertSeries(listOf(1.3, 1.3, 1.0, 0.3), p.totalFiveHour, "totalFiveHour")
        // weekly untouched (no 7d samples)
        assertSeries(listOf(null, null, null, null), p.totalWeekly, "totalWeekly")
    }

    @Test
    fun `per-account series carries forward`() {
        val samples = listOf(sample(2, 0, 0.8), sample(2, 3, 0.1))
        val p = aggregateWindows(samples, startMs, widthMs, buckets, labels, includeAccounts = true, names = mapOf(2 to "acc2"))
        val acc2 = p.perAccount.single { it.accountId == 2 }
        assertEquals("acc2", acc2.accountName)
        assertSeries(listOf(0.8, 0.8, 0.8, 0.1), acc2.fiveHour, "acc2.fiveHour")
    }

    @Test
    fun `buckets before an account's first sample are null, not zero`() {
        // acct first reports in b2 -> b0/b1 have no contributor at all.
        val samples = listOf(sample(1, 2, 0.5))
        val p = aggregateWindows(samples, startMs, widthMs, buckets, labels, includeAccounts = false, names = emptyMap())
        assertSeries(listOf(null, null, 0.5, 0.5), p.totalFiveHour, "leading nulls")
        assertTrue(p.perAccount.isEmpty(), "perAccount hidden when includeAccounts=false")
    }
}
