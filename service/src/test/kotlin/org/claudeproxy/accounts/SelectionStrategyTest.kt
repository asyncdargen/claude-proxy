package org.claudeproxy.accounts

import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SelectionStrategy.DEADLINE] ordering. Like [TierOrderTest] this only exercises pure functions
 * over a passed list — no DB, no mutex, no settings row (the strategy is passed in explicitly
 * rather than resolved from [SelectionStrategy.current]).
 *
 * The pool that motivated this: three x5 accounts whose weekly windows reset on three different
 * days, drained in `(priority, id)` order until the last one forfeited 52% of its window,
 * unused, at its own reset.
 */
class SelectionStrategyTest {

    private val now: Instant = Instant.parse("2026-09-22T06:00:00Z")

    private fun acct(
        id: Int,
        priority: Int,
        weeklyResetInHours: Double?,
        fiveHourUsage: Double = 0.0,
        weeklyUsage: Double = 0.0,
        kind: org.claudeproxy.model.AccountKind = org.claudeproxy.model.AccountKind.PRO,
        threshold: Double = 0.99,
        overThreshold: Boolean = true,
    ) = AccountRuntime(
        id = id, name = "a$id", type = AccountType.OAUTH, groupId = null, ownerId = null,
        priority = priority, threshold = threshold, kind = kind, enabled = true,
        overThreshold = overThreshold, health = AccountHealth.OK, deviceId = null,
        secret = AccountSecret(),
        limit = LimitState(
            windows = buildMap {
                put(WindowKind.FIVE_HOUR, WindowLimit(utilization = fiveHourUsage))
                put(
                    WindowKind.WEEKLY,
                    WindowLimit(
                        utilization = weeklyUsage,
                        resetAt = weeklyResetInHours?.let {
                            now.plus(Duration.ofMinutes((it * 60).toLong()))
                        },
                    ),
                )
            },
        ),
    )

    private val pool = AccountPool()

    private fun order(vararg accounts: AccountRuntime) =
        pool.tierOrder(accounts.toList(), SelectionStrategy.DEADLINE, now).map { it.id }

    @Test
    fun `earliest weekly reset leads regardless of priority`() {
        val soon = acct(id = 1, priority = 99, weeklyResetInHours = 26.0)
        val later = acct(id = 2, priority = 1, weeklyResetInHours = 167.0)
        // Exactly the live inversion: the account that resets in a day is drained before the one
        // that resets in a week, even though `priority` says the opposite.
        assertEquals(listOf(1, 2), order(soon, later))
    }

    @Test
    fun `a three-account pool orders by deadline, not by the configured priority`() {
        // Priority 1 resets in 38h, priority 2 in 26h, priority 99 in 167h: the configured
        // order is the exact inverse of the order the deadlines ask for.
        val first = acct(id = 5, priority = 1, weeklyResetInHours = 38.0, weeklyUsage = 0.90, kind = org.claudeproxy.model.AccountKind.MAX)
        val second = acct(id = 6, priority = 2, weeklyResetInHours = 26.0, weeklyUsage = 0.88, kind = org.claudeproxy.model.AccountKind.MAX)
        val third = acct(id = 2, priority = 99, weeklyResetInHours = 167.0, weeklyUsage = 0.0, kind = org.claudeproxy.model.AccountKind.MAX)
        assertEquals(listOf(6, 5, 2), order(first, second, third))
    }

    @Test
    fun `deadlines inside one 12-hour bucket tie-break on five-hour headroom`() {
        // 2h apart, same bucket => the 5-hour axis decides, and the account with more room to run
        // leads so the burst survives longer before a rotation.
        val busy = acct(id = 1, priority = 1, weeklyResetInHours = 4.0, fiveHourUsage = 0.80)
        val fresh = acct(id = 2, priority = 2, weeklyResetInHours = 6.0, fiveHourUsage = 0.05)
        assertEquals(listOf(2, 1), order(busy, fresh))
    }

    @Test
    fun `deadlines in different buckets ignore five-hour headroom`() {
        // 13h apart straddles the bucket, so the weekly deadline wins outright even though the
        // leading account has almost no 5-hour room left.
        val soonAndBusy = acct(id = 1, priority = 9, weeklyResetInHours = 2.0, fiveHourUsage = 0.95)
        val laterAndFresh = acct(id = 2, priority = 1, weeklyResetInHours = 15.0, fiveHourUsage = 0.0)
        assertEquals(listOf(1, 2), order(soonAndBusy, laterAndFresh))
    }

    @Test
    fun `five-hour headroom is weighted by the account plan`() {
        // Same fraction used, same bucket: the x5 account can absorb five times the burst.
        val small = acct(id = 1, priority = 1, weeklyResetInHours = 3.0, fiveHourUsage = 0.5, kind = org.claudeproxy.model.AccountKind.PRO)
        val big = acct(id = 2, priority = 2, weeklyResetInHours = 3.0, fiveHourUsage = 0.5, kind = org.claudeproxy.model.AccountKind.MAX)
        assertEquals(listOf(2, 1), order(small, big))
    }

    @Test
    fun `an account with no weekly reading sorts last, not first`() {
        val known = acct(id = 1, priority = 50, weeklyResetInHours = 100.0)
        val unknown = acct(id = 2, priority = 1, weeklyResetInHours = null)
        // We will not preferentially drain a deadline we cannot see. LimitProbe gives it a
        // reading within 30 minutes either way, so this cannot strand it.
        assertEquals(listOf(1, 2), order(known, unknown))
    }

    @Test
    fun `priority still breaks a full tie`() {
        val a = acct(id = 1, priority = 20, weeklyResetInHours = 5.0)
        val b = acct(id = 2, priority = 10, weeklyResetInHours = 5.0)
        assertEquals(listOf(2, 1), order(a, b))
    }

    @Test
    fun `the threshold partition still outranks the deadline`() {
        // Over threshold on weekly, and resetting soonest — still behind the usable account,
        // because there is nothing left in it to spend before that deadline.
        val exhaustedButSoon = acct(id = 1, priority = 1, weeklyResetInHours = 1.0, weeklyUsage = 0.995)
        val usable = acct(id = 2, priority = 99, weeklyResetInHours = 150.0, weeklyUsage = 0.1)
        assertEquals(listOf(2, 1), order(exhaustedButSoon, usable))
    }

    @Test
    fun `PRIORITY is the default and is unchanged by any of this`() {
        val a = acct(id = 1, priority = 99, weeklyResetInHours = 1.0)
        val b = acct(id = 2, priority = 1, weeklyResetInHours = 999.0)
        assertEquals(listOf(2, 1), pool.tierOrder(listOf(a, b)).map { it.id })
    }

    @Test
    fun `an unset or unknown setting reads as PRIORITY`() {
        assertEquals(SelectionStrategy.PRIORITY, SelectionStrategy.fromSetting(null))
        assertEquals(SelectionStrategy.PRIORITY, SelectionStrategy.fromSetting(""))
        assertEquals(SelectionStrategy.PRIORITY, SelectionStrategy.fromSetting("nonsense"))
        assertEquals(SelectionStrategy.DEADLINE, SelectionStrategy.fromSetting("deadline"))
        assertEquals(SelectionStrategy.DEADLINE, SelectionStrategy.fromSetting("  DEADLINE  "))
    }

    @Test
    fun `a past weekly reset clamps to the nearest bucket rather than going negative`() {
        val stale = acct(id = 1, priority = 50, weeklyResetInHours = -8.0)
        val soon = acct(id = 2, priority = 60, weeklyResetInHours = 1.0)
        // Both land in bucket 0; a negative horizon must not sort ahead of a real imminent one by
        // running off the bottom of the scale, so `priority` decides.
        assertEquals(listOf(1, 2), order(stale, soon))
    }
}
