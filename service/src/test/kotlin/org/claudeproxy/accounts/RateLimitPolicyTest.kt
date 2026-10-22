package org.claudeproxy.accounts

import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitPolicyTest {
    private val now = Instant.parse("2026-10-22T12:46:40Z")
    private val reset5h = Instant.parse("2026-10-22T17:10:00Z")

    private fun state(util5h: Double, status5h: LimitStatus = LimitStatus.ALLOWED, util7d: Double = 0.05) = LimitState(
        windows = mapOf(
            WindowKind.FIVE_HOUR to WindowLimit(utilization = util5h, resetAt = reset5h, status = status5h),
            WindowKind.WEEKLY to WindowLimit(utilization = util7d, resetAt = Instant.parse("2026-10-28T13:00:00Z"), status = LimitStatus.ALLOWED),
        ),
    )

    /** The incident: haiku 429 at 29% 5h parked the only account and starved Opus for a minute. */
    @Test
    fun `a 429 with headroom left does not park the account`() {
        assertNull(RateLimitPolicy.parkUntil(emptyMap(), state(0.29), now))
        assertNull(RateLimitPolicy.parkUntil(mapOf("retry-after" to "30"), state(0.29), now),
            "retry-after on a model/burst limit applies to that request, not the account")
    }

    @Test
    fun `an exhausted window parks until its reset`() {
        assertEquals(reset5h, RateLimitPolicy.parkUntil(emptyMap(), state(1.01, LimitStatus.REJECTED), now))
        assertEquals(reset5h, RateLimitPolicy.parkUntil(emptyMap(), state(0.96), now))
    }

    @Test
    fun `a rejected window parks even below the utilization mark`() {
        assertEquals(reset5h, RateLimitPolicy.parkUntil(emptyMap(), state(0.5, LimitStatus.REJECTED), now))
    }

    @Test
    fun `retry-after wins for an exhausted account`() {
        assertEquals(now.plusSeconds(20), RateLimitPolicy.parkUntil(mapOf("Retry-After" to "20"), state(1.0, LimitStatus.REJECTED), now))
    }

    @Test
    fun `exhausted without a known reset falls back to a short park`() {
        val s = LimitState(windows = mapOf(WindowKind.FIVE_HOUR to WindowLimit(utilization = 1.0, status = LimitStatus.REJECTED)))
        assertEquals(now.plusSeconds(60), RateLimitPolicy.parkUntil(emptyMap(), s, now))
    }

    @Test
    fun `describe keeps only non-secret rate-limit headers`() {
        val d = RateLimitPolicy.describe(mapOf(
            "Authorization" to "Bearer secret", "retry-after" to "5", "request-id" to "req_1",
            "anthropic-ratelimit-unified-5h-utilization" to "0.29", "set-cookie" to "x",
        ))
        assertTrue("secret" !in d && "set-cookie" !in d, d)
        assertEquals("anthropic-ratelimit-unified-5h-utilization=0.29, request-id=req_1, retry-after=5", d)
        assertEquals("none", RateLimitPolicy.describe(emptyMap()))
    }
}
