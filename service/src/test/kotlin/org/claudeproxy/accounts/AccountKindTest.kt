package org.claudeproxy.accounts

import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountKind
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.claudeproxy.model.ProxyType
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountKindTest {
    private fun acct(type: AccountType = AccountType.OAUTH, kind: AccountKind = AccountKind.PRO, limit: LimitState = LimitState(), threshold: Double = 0.9) =
        AccountRuntime(
            id = 1, name = "a", type = type, groupId = null, ownerId = null, priority = 1, threshold = threshold,
            kind = kind, enabled = true, overThreshold = false, health = AccountHealth.OK, deviceId = null,
            secret = AccountSecret(), limit = limit,
        )

    @Test fun `legacy coefficients map onto the renamed plans`() {
        assertEquals(AccountKind.PRO, AccountKind.fromLegacyCoefficient(1.0))
        assertEquals(AccountKind.MAX, AccountKind.fromLegacyCoefficient(5.0))
        assertEquals(AccountKind.ULTRA, AccountKind.fromLegacyCoefficient(20.0))
    }

    @Test fun `ultra headers without a 5h family drop the phantom 5h window`() {
        // Shape of a verified Ultra response on prod: the 7d family plus the generic unified-* pair.
        val prev = LimitState(windows = mapOf(WindowKind.FIVE_HOUR to WindowLimit(utilization = 0.0, resetAt = java.time.Instant.ofEpochSecond(1793530800))))
        val parsed = RateLimitHeaders.parse(mapOf(
            "anthropic-ratelimit-unified-7d-utilization" to "0.36",
            "anthropic-ratelimit-unified-7d-status" to "allowed",
            "anthropic-ratelimit-unified-7d-reset" to "1794135600",
            "anthropic-ratelimit-unified-reset" to "1794135600",
            "anthropic-ratelimit-unified-status" to "allowed",
        ), prev)
        assertNull(parsed.window(WindowKind.FIVE_HOUR))
        assertEquals(0.36, parsed.window(WindowKind.WEEKLY)?.utilization)
        assertFalse(acct(kind = AccountKind.ULTRA, limit = parsed).fiveHourLimited)
    }

    @Test fun `a response with the 5h family still reads both windows`() {
        val parsed = RateLimitHeaders.parse(mapOf(
            "anthropic-ratelimit-unified-5h-utilization" to "0.5",
            "anthropic-ratelimit-unified-5h-reset" to "1793490600",
            "anthropic-ratelimit-unified-7d-utilization" to "0.4",
        ), LimitState())
        assertEquals(0.5, parsed.window(WindowKind.FIVE_HOUR)?.utilization)
        assertTrue(acct(limit = parsed).fiveHourLimited)
    }

    @Test fun `api keys have no windows in the dto and no threshold in selection`() {
        val limit = LimitState(windows = mapOf(WindowKind.FIVE_HOUR to WindowLimit(remaining = 1.0, limitTotal = 100.0, status = LimitStatus.ALLOWED)))
        val key = acct(type = AccountType.API_KEY, kind = AccountKind.NONE, limit = limit, threshold = 0.5)
        val dto = key.toDto("2026-11-01T00:00:00Z", org.claudeproxy.repo.Totals())
        assertNull(dto.fiveHour); assertNull(dto.weekly); assertNull(dto.effectiveRemaining)
        // 99% "used" per-minute budget would put a subscription over threshold; a key stays in front
        assertEquals(listOf(1), AccountPool().tierOrder(listOf(key)).map { it.id })
    }

    @Test fun `headroom is weighted by plan`() {
        val max = acct(kind = AccountKind.MAX, limit = LimitState(windows = mapOf(WindowKind.FIVE_HOUR to WindowLimit(utilization = 0.5))))
        assertEquals(2.5, max.toDto("2026-11-01T00:00:00Z", org.claudeproxy.repo.Totals()).effectiveRemaining!!, 1e-9)
    }

    @Test fun `proxy url encodes credentials and never prints them`() {
        val p = AccountProxy(ProxyType.SOCKS5, "proxy.example", 1080, "us er", "p@ss:w")
        assertEquals("socks5://us%20er:p%40ss%3Aw@proxy.example:1080", p.toUrl())
        assertEquals("http://[::1]:3128", AccountProxy(ProxyType.HTTP, "::1", 3128).toUrl())
        assertFalse(p.toString().contains("p@ss"))
        assertEquals("proxy port must be 1..65535", AccountProxy.validate(ProxyType.HTTP, "h", 0))
        assertNull(AccountProxy.validate(ProxyType.NONE, null, null))
    }
}
