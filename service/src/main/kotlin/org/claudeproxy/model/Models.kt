package org.claudeproxy.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** Permissions a user can hold (directly, via roles). */
enum class Permission {
    PROXY_USE,          // may route requests through the proxy
    STATS_VIEW_OWN,     // may view only their own usage statistics
    STATS_RESET_OWN,    // may reset their own stats (resets daily spend — can bypass a limit)
    ACCOUNTS_OWN_MANAGE,// may manage their own personal accounts (tried before the global pool)
    ACCOUNTS_ORDER_TOGGLE,// may switch whether their personal accounts or the global pool are tried first
    POOL_GLOBAL_USE,    // may route requests through the shared (global) account pool
    ROUTING_USE,        // may use the OpenAI/Anthropic API routing gateways + manage routing tokens
    CHAT_USE,           // may use the built-in chat UI (conversations, attachments, memory)
    STATS_VIEW_RECENT,  // may view the list of recent requests (pool-wide)
    STATS_VIEW_ACCOUNTS,// may see which account each request/stat came from
    ACCOUNTS_VIEW,      // may view upstream accounts + their limits
    STATS_VIEW,         // full statistics (implies recent + accounts + graphs)
    STATS_VIEW_WINDOW_ATTRIBUTION, // named users' shared-account quota estimates; independent of STATS_VIEW
    ACCOUNTS_MANAGE,    // may create/edit/delete upstream accounts
    USERS_MANAGE,       // may create/edit/delete users and roles
    ADMIN;              // superuser: implies everything

    companion object {
        fun fromString(s: String): Permission? = entries.firstOrNull { it.name == s }
    }
}

/** How an upstream account authenticates to Anthropic. */
enum class AccountType {
    OAUTH,         // access_token + refresh_token, auto-refreshed
    OAUTH_STATIC,  // access_token only (no refresh), works until expiry
    API_KEY;       // static x-api-key

    companion object {
        fun fromString(s: String): AccountType? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

enum class AccountHealth { OK, REFRESH_FAILED, DEAD }

enum class LimitStatus { ALLOWED, ALLOWED_WARNING, REJECTED, UNKNOWN }

/** Rolling limit windows Anthropic exposes for subscription accounts. */
enum class WindowKind(val code: String, val label: String) {
    FIVE_HOUR("5h", "5-hour"),
    WEEKLY("7d", "weekly");

    companion object {
        fun fromCode(s: String): WindowKind? = entries.firstOrNull { it.code == s }
    }
}

// ---- API DTOs ----

@Serializable
data class UserDto(
    val id: Int,
    val username: String,
    val enabled: Boolean,
    val roles: List<String>,
    val permissions: List<String>,
    // ids of account-groups this user may route through (empty = only ungrouped accounts,
    // unless the user is an admin, who may use everything)
    val allowedGroups: List<Int> = emptyList(),
    val allGroups: Boolean = false,
    // per-day spend limit in USD; null = unlimited
    val dailyCostLimit: Double? = null,
    // per-day spend limit in USD for the OpenAI/Anthropic routing gateways; null = unlimited.
    // Tracked separately from dailyCostLimit — routing spend is metered against this one.
    val dailyRoutingCostLimit: Double? = null,
    // per-day spend limit in USD for the built-in chat UI; null = unlimited. Metered separately
    // again, so a chat session can't eat the quota Claude Code runs on (and vice versa).
    val dailyChatCostLimit: Double? = null,
    // routing preference: true = try the global pool before personal accounts (default false = personal first)
    val preferGlobalPool: Boolean = false,
    // admin-set, lower-cased; only used to link a first SSO login (OIDC_LINK_BY_EMAIL)
    val email: String? = null,
    // true once an external (OIDC) identity is linked to this user
    val ssoLinked: Boolean = false,
    // usage since the start of the current UTC day
    val todayCost: Double = 0.0,
    // routing spend (source=routing) since the start of the current UTC day
    val todayRoutingCost: Double = 0.0,
    // chat spend (source=chat) since the start of the current UTC day
    val todayChatCost: Double = 0.0,
    val todayInputTokens: Long = 0,
    val todayOutputTokens: Long = 0,
)

@Serializable
data class AccountGroupDto(
    val id: Int,
    val name: String,
    val accountCount: Int,
    val createdAt: String,
)

@Serializable
data class WindowLimitDto(
    val usageFraction: Double?,
    val remaining: Double?,
    val limitTotal: Double?,
    val resetAt: String?,
    val status: String?,
    val updatedAt: String?,
)

/** Paid usage credits ("extra usage") on an account — see [OverageState]. */
@Serializable
data class OverageDto(
    val status: String?,
    val inUse: Boolean,
    val utilization: Double?,
    val monthlyUtilization: Double?,
    val channelUtilization: Double?,
    val resetAt: String?,
    val disabledReason: String?,
    val weeklyWithOverage: Double?,
)

/** Grace allowance on top of a saturated window — see [GraceState]. */
@Serializable
data class GraceDto(
    val status: String?,
    val fiveHourUtilization: Double?,
    val weeklyUtilization: Double?,
)

@Serializable
data class AccountDto(
    val id: Int,
    val name: String,
    val type: String,
    val groupId: Int?,
    // null = global (shared pool); otherwise the owning user's id (personal account)
    val ownerId: Int? = null,
    val priority: Int,
    val threshold: Double,
    val coefficient: Double,
    val enabled: Boolean,
    // opt-in fallback: keep using this account past its threshold when all accounts are saturated
    val overThreshold: Boolean = false,
    val health: String,
    // live limit state per window (nullable when never observed)
    val fiveHour: WindowLimitDto?,
    val weekly: WindowLimitDto?,
    // paid usage credits + grace allowance, when the upstream reports them (null = never seen)
    val overage: OverageDto? = null,
    val grace: GraceDto? = null,
    // usage fraction driving selection (max across windows), 0..1
    val usageFraction: Double?,
    val rateLimitedUntil: String?,
    val effectiveRemaining: Double?, // coefficient-weighted remaining capacity
    // cumulative counters for this account (all-time)
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val totalCacheWriteTokens: Long,
    val totalCost: Double,
    val totalRequests: Long,
    val deviceId: String?,
    val accountUuid: String? = null,
    val createdAt: String,
)

@Serializable
data class ProxyTokenDto(
    val id: Int,
    val name: String,
    val userId: Int,
    val createdAt: String,
    val lastUsedAt: String?,
    // full token value only returned once, at creation time
    val token: String? = null,
    // static system prompt (routing tokens only; null for proxy tokens / unset)
    val systemPrompt: String? = null,
    // off = the token no longer authenticates, without being revoked
    val enabled: Boolean = true,
    // model that replaces whatever the client asks for (proxy tokens only; null = client's choice)
    val defaultModel: String? = null,
)

@Serializable
data class PoolStatsDto(
    val totalAccounts: Int,
    val healthyAccounts: Int,
    val activeAccountId: Int?,
    val totalEffectiveRemaining: Double,   // Σ coefficient-weighted remaining (5-hour)
    val totalEffectiveCapacity: Double,    // Σ coefficient
    // same, for the weekly window — drives the "Pool headroom" card subtitle
    val totalWeeklyRemaining: Double,
    val totalWeeklyCapacity: Double,
    // pool-wide counters (all-time)
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val totalCacheWriteTokens: Long,
    val totalCost: Double,
    val totalRequests: Long,
    // nearest reset times across the pool, per window
    val nextFiveHourReset: String?,
    val nextWeeklyReset: String?,
    // requests being streamed from Anthropic right now, by datapath. Scoped like the rest of the
    // payload: pool-wide view = everyone, personal view = that user's own.
    val activeProxySessions: Int = 0,
    val activeRoutingSessions: Int = 0,
    // Which order the pool drains its accounts in — `PRIORITY` or `DEADLINE`. Reported rather
    // than inferred: a dashboard that draws a drain order has no other way to know which rule
    // produced it, and one drawn under the wrong rule is worse than none at all.
    val selectionStrategy: String = "PRIORITY",
    val accounts: List<AccountDto>,
)

/** Live limit state for a single rolling window. */
data class WindowLimit(
    // Anthropic reports usage directly as a 0..1 `utilization` fraction (subscription).
    val utilization: Double? = null,
    // API-key style limits report remaining/limit instead.
    val remaining: Double? = null,
    val limitTotal: Double? = null,
    val resetAt: Instant? = null,
    val status: LimitStatus = LimitStatus.UNKNOWN,
    val updatedAt: Instant? = null,
) {
    /** Usage fraction 0..1: prefer the reported utilization, else derive from remaining/limit. */
    fun usageFraction(): Double? {
        utilization?.let { return it.coerceIn(0.0, 1.0) }
        val r = remaining ?: return null
        val t = limitTotal ?: return null
        if (t <= 0.0) return null
        return (1.0 - (r / t)).coerceIn(0.0, 1.0)
    }

    fun isEmpty(): Boolean =
        utilization == null && remaining == null && limitTotal == null && resetAt == null && status == LimitStatus.UNKNOWN
}

/**
 * Paid usage credits — Anthropic's "extra usage". With credits enabled, a subscription that has
 * exhausted its 5h/7d window keeps serving and the overflow is charged as money against a
 * monthly credit budget. Read-only here: the proxy reports it so an operator can see an account
 * spending real money, and so a weekly window pinned at 100% is recognisable as "still working
 * on credits" rather than "dead until the reset".
 */
data class OverageState(
    /** raw `overage-status`: "allowed" when credits are usable, "rejected" when they are not */
    val status: String? = null,
    /** this response was served from credits rather than the subscription */
    val inUse: Boolean = false,
    /**
     * 0..1 of the credit allowance consumed (`overage-utilization`). This is the figure live
     * traffic actually carries; the monthly/channel pair below is reported only on some accounts.
     */
    val utilization: Double? = null,
    /** 0..1 of the monthly credit budget consumed */
    val monthlyUtilization: Double? = null,
    /** 0..1 of the per-channel credit budget consumed */
    val channelUtilization: Double? = null,
    /** when the credit period rolls over */
    val resetAt: Instant? = null,
    /** why credits are off: "free" | "preference" | "extra_usage_disabled" | "network_error" */
    val disabledReason: String? = null,
    /** weekly utilization *including* credits (`7d_oi`), which can exceed the plain 7d reading */
    val weeklyWithOverage: Double? = null,
    val updatedAt: Instant? = null,
) {
    fun isEmpty(): Boolean = status == null && !inUse && utilization == null && monthlyUtilization == null &&
        channelUtilization == null && resetAt == null && disabledReason == null && weeklyWithOverage == null

    /** How much of the credit allowance is gone, from whichever figure the upstream reported. */
    fun spentFraction(): Double? = monthlyUtilization ?: utilization
}

/**
 * A grace allowance Anthropic grants on top of a saturated window — a soft landing rather than
 * a hard stop. Surfaced for visibility only; unlike credits it costs nothing.
 */
data class GraceState(
    val status: String? = null,
    val fiveHourUtilization: Double? = null,
    val weeklyUtilization: Double? = null,
) {
    fun isEmpty(): Boolean = status == null && fiveHourUtilization == null && weeklyUtilization == null

    /** Grace is only interesting once something is actually being drawn from it. */
    fun active(): Boolean = (fiveHourUtilization ?: 0.0) > 0.0 || (weeklyUtilization ?: 0.0) > 0.0
}

/** Immutable snapshot of an account's live limit state across all windows. */
data class LimitState(
    val windows: Map<WindowKind, WindowLimit> = emptyMap(),
    val rateLimitedUntil: Instant? = null,
    val updatedAt: Instant? = null,
    val overage: OverageState? = null,
    val grace: GraceState? = null,
) {
    fun window(kind: WindowKind): WindowLimit? = windows[kind]

    /** Usage fraction that drives selection: the max across known windows. */
    fun usageFraction(): Double? =
        windows.values.mapNotNull { it.usageFraction() }.maxOrNull()
}
