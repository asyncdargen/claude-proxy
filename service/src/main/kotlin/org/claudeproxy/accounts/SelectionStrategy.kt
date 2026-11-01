package org.claudeproxy.accounts

import org.claudeproxy.model.WindowKind
import org.claudeproxy.repo.SettingsRepo
import java.time.Duration
import java.time.Instant
import kotlin.math.floor

/**
 * How [AccountPool] orders the candidates inside one tier.
 *
 * [PRIORITY] is the historical behaviour and stays the default: drain `(priority, id)` order
 * until each account crosses its threshold. It is the right shape for the 5-hour window, which
 * is a rate limit — long dwell on one account keeps prompt-cache locality, and the accounts
 * behind it hold their windows in reserve.
 *
 * It is the wrong shape for the **weekly** window, which is a fixed 7-day quota anchored per
 * account: unused quota is forfeited at the reset, the resets are staggered, and a static
 * `priority` cannot track a moving deadline. The account with the highest `priority` number
 * therefore writes off quota every seven days — measured at 52% of one Max window on
 * 2026-09-22.
 *
 * [DEADLINE] fixes the order without touching the sequentiality: earliest weekly reset first, so
 * perishable quota is spent before it expires. Ties inside a [BUCKET_HOURS]-hour horizon are
 * broken by 5-hour headroom, which picks the account that can run longest before rotating — the
 * same choice that minimises rotations, and therefore cache re-writes. Spreading load across
 * accounts would fix neither: there is no account affinity (`SessionMapRepo` rotates session ids
 * rather than pinning them), so a spread multiplies cache-prefix re-writes, and those are paid
 * in the same weekly window this is trying to stop wasting.
 */
enum class SelectionStrategy {
    PRIORITY,
    DEADLINE,
    ;

    companion object {
        const val SETTING_KEY = "selection_strategy"
        const val ENV_KEY = "SELECTION_STRATEGY"

        @Volatile
        private var resolved: SelectionStrategy? = null

        /** Unknown or unset values read as [PRIORITY] — the behaviour every deploy had before. */
        fun fromSetting(raw: String?): SelectionStrategy =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PRIORITY

        /**
         * `SELECTION_STRATEGY` first, then the `settings` row, then [PRIORITY]. Env first because
         * that is how every other knob in this service is configured ([LimitProbe]'s three, and
         * `ENABLE_LIMIT_PROBE`) and because it needs no admin route to exist before a deploy can
         * use it.
         *
         * Memoised: this is read on the request path, inside [AccountPool]'s mutex, and
         * [SettingsRepo.get] opens a transaction the first time it is called.
         */
        fun current(): SelectionStrategy =
            resolved ?: synchronized(this) {
                resolved ?: run {
                    val value = fromSetting(org.claudeproxy.envOrProp(ENV_KEY) ?: SettingsRepo.get(SETTING_KEY))
                    resolved = value
                    value
                }
            }

        /** Drops the memo so a settings write takes effect without a restart. */
        fun invalidate() { resolved = null }
    }
}

/**
 * Width of the weekly-deadline bucket, in hours. Deadlines land in the same bucket when they are
 * within the same horizon from now, and only then does the 5-hour headroom tiebreak get a say.
 *
 * Bucketing is relative to `now` rather than to an absolute epoch grid on purpose: an absolute
 * grid puts two deadlines a minute apart into different buckets whenever they straddle a
 * boundary, which would make the tiebreak fire or not fire for no reason a reader could predict.
 */
const val BUCKET_HOURS = 12.0

/**
 * Which [BUCKET_HOURS]-hour horizon this account's weekly window resets in. Lower sorts first.
 *
 * An account with no weekly reading yet sorts last rather than first: we will not preferentially
 * drain a window whose deadline we cannot see. That cannot strand it, because `LimitProbe` gives
 * every account a reading every 30 minutes whether or not anything routes to it.
 */
internal fun weeklyResetBucket(account: AccountRuntime, now: Instant): Long {
    val resetAt = account.limit.window(WindowKind.WEEKLY)?.resetAt ?: return Long.MAX_VALUE
    val hours = Duration.between(now, resetAt).toMinutes() / 60.0
    return floor(hours.coerceAtLeast(0.0) / BUCKET_HOURS).toLong()
}

/**
 * How much of the 5-hour window this account can still serve before it rotates out, weighted by
 * its plan — a Max account at 50% has five times the runway of a Pro at 50%. A weekly-only (Ultra)
 * account has no 5-hour reading and counts as fully open, weighted at its 5-hour multiplier.
 * Higher sorts first, so the leading account is the one that can absorb the longest burst.
 */
internal fun fiveHourHeadroom(account: AccountRuntime): Double {
    val used = account.limit.window(WindowKind.FIVE_HOUR)?.usageFraction() ?: 0.0
    return (account.threshold - used).coerceAtLeast(0.0) * account.kind.fiveHourWeight
}

/** The comparator [AccountPool.tierOrder] sorts each of its three partitions with. */
internal fun candidateOrder(strategy: SelectionStrategy, now: Instant): Comparator<AccountRuntime> =
    when (strategy) {
        SelectionStrategy.PRIORITY -> compareBy({ it.priority }, { it.id })
        SelectionStrategy.DEADLINE -> compareBy(
            { weeklyResetBucket(it, now) },
            { -fiveHourHeadroom(it) },
            { it.priority },
            { it.id },
        )
    }
