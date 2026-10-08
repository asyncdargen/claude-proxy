package org.claudeproxy.api

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class CreateAccountRequest(
    val name: String,
    val type: String,               // API_KEY | OAUTH | OAUTH_STATIC
    val groupId: Int? = null,
    val priority: Int = 100,
    val threshold: Double = 0.9,
    val coefficient: Double = 1.0,
    val apiKey: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,    // epoch millis
)

@Serializable
data class UpdateAccountRequest(
    val name: String? = null,
    val groupId: Int? = null,
    val clearGroup: Boolean = false,
    val priority: Int? = null,
    val threshold: Double? = null,
    val coefficient: Double? = null,
    val enabled: Boolean? = null,
    val overThreshold: Boolean? = null,
    val deviceId: String? = null,
    // "" clears; null leaves it alone
    val accountUuid: String? = null,
)

@Serializable
data class CreateGroupRequest(val name: String)

@Serializable
data class UpdateGroupRequest(val name: String)

@Serializable
data class OAuthStartResponse(val authorizeUrl: String, val state: String)

@Serializable
data class OAuthCompleteRequest(
    val state: String,
    val code: String,
    val name: String,
    val groupId: Int? = null,
    val priority: Int = 100,
    val threshold: Double = 0.9,
    val coefficient: Double = 1.0,
)

@Serializable
data class OAuthReauthRequest(val state: String, val code: String)

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val roles: List<String> = emptyList(),
    val allowedGroups: List<Int> = emptyList(),
    val dailyCostLimit: Double? = null,
    val dailyRoutingCostLimit: Double? = null,
    val dailyChatCostLimit: Double? = null,
)

@Serializable
data class UpdateUserRequest(
    val password: String? = null,
    val enabled: Boolean? = null,
    val roles: List<String>? = null,
    val allowedGroups: List<Int>? = null,
    val dailyCostLimit: Double? = null,
    val clearDailyLimit: Boolean = false,
    val dailyRoutingCostLimit: Double? = null,
    val clearRoutingLimit: Boolean = false,
    val dailyChatCostLimit: Double? = null,
    val clearChatLimit: Boolean = false,
)

@Serializable
data class UpdateProfileRequest(
    // current password is required to authorize any self-service change
    val currentPassword: String,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class AccountOrderRequest(val preferGlobalPool: Boolean)

@Serializable
data class ModelPriceRequest(
    val pattern: String,
    val inputPrice: Double,
    val outputPrice: Double,
    val cacheReadPrice: Double = 0.0,
    // cache writes at the default 5-minute TTL
    val cacheWritePrice: Double = 0.0,
    // cache writes at the 1-hour TTL; 0 = derive from input (Anthropic's 2× relation)
    val cacheWrite1hPrice: Double = 0.0,
    // × applied to every token price in fast mode; 0 = no premium
    val fastMultiplier: Double = 2.0,
    // USD per server-side web search (billed per invocation)
    val webSearchPrice: Double = 0.01,
)

@Serializable
data class ConfigDto(val publicBaseUrl: String)

@Serializable
data class CreateRoleRequest(val name: String, val permissions: List<String> = emptyList())

@Serializable
data class UpdateRoleRequest(val permissions: List<String>)

@Serializable
data class CreateProxyTokenRequest(val name: String, val systemPrompt: String? = null)

/** PATCH body for a routing token: set (non-blank) or clear (null/blank) its static system prompt. */
@Serializable
data class UpdateRoutingTokenRequest(val systemPrompt: String? = null)

@Serializable
data class UpdateProxyTokenRequest(val defaultModel: String? = null)

/**
 * PATCH body for the `…/{id}/enabled` sub-resource of both token kinds. A dedicated sub-resource
 * rather than a field on [UpdateRoutingTokenRequest], which can't tell "prompt omitted" from
 * "clear the prompt".
 */
@Serializable
data class UpdateTokenEnabledRequest(val enabled: Boolean)

@Serializable
data class RolesPayload(val roles: List<org.claudeproxy.repo.RoleDto>, val allPermissions: List<String>)

@Serializable
data class StatsPayload(
    val summary: List<org.claudeproxy.repo.UsageSummaryDto>,
    val recent: List<org.claudeproxy.repo.UsageEventDto>,
)

@Serializable
data class MyStatsPayload(
    // today/total reflect the selected `source` filter (null = both datapaths), across ALL accounts
    val todayCost: Double,
    val todayClean: Long,
    val todayRequests: Long,
    val totalCost: Double,
    val totalClean: Long,
    val totalRequests: Long,
    // limit gauges are always on their own basis (shared-pool spend of one datapath), independent of the filter
    val dailyCostLimit: Double?,
    val proxyTodayCost: Double = 0.0,           // spend counted against dailyCostLimit today
    val dailyRoutingCostLimit: Double? = null,
    val routingTodayCost: Double = 0.0,         // spend counted against dailyRoutingCostLimit today
    val dailyChatCostLimit: Double? = null,
    val chatTodayCost: Double = 0.0,            // spend counted against dailyChatCostLimit today
    // this user's requests streaming from Anthropic right now, by datapath
    val activeProxySessions: Int = 0,
    val activeRoutingSessions: Int = 0,
    val perModel: List<org.claudeproxy.repo.ModelUsageDto>,        // all-time, per model (source-filtered)
    val perModelToday: List<org.claudeproxy.repo.ModelUsageDto>,   // since start of the UTC day (source-filtered)
    val recent: List<org.claudeproxy.repo.UsageEventDto>,
)

/** One user's line in the admin per-user usage overview. */
@Serializable
data class UserStatsOverviewDto(
    val userId: Int?,                   // null = events left by since-deleted users
    val username: String?,
    val enabled: Boolean = true,
    val dailyCostLimit: Double? = null,
    val dailyRoutingCostLimit: Double? = null,
    val todayProxyCost: Double, val todayRoutingCost: Double, val todayCost: Double,
    val todayRequests: Long, val todayTokens: Long,
    val totalProxyCost: Double, val totalRoutingCost: Double, val totalCost: Double,
    val totalRequests: Long, val totalTokens: Long,
    val lastActivity: String?,
)

/** Pool-wide per-model breakdown for the global Statistics page (today vs all-time toggle). */
@Serializable
data class ModelBreakdownPayload(
    val today: List<org.claudeproxy.repo.ModelUsageDto>,
    val allTime: List<org.claudeproxy.repo.ModelUsageDto>,
)

@Serializable
data class AccountSeriesDto(
    val accountId: Int,
    val accountName: String?,
    val cost: List<Double>,
    val requests: List<Long>,
)

@Serializable
data class DailyStatsPayload(
    val days: List<String>,               // date labels (UTC), oldest→newest
    val totalCost: List<Double>,          // combined cost per day
    val totalRequests: List<Long>,
    val perAccount: List<AccountSeriesDto>, // empty if the viewer can't see accounts
    val canViewAccounts: Boolean,
)

@Serializable
data class TokenTotalsDto(                  // per-day token counts, four kinds kept apart
    val input: List<Long>,
    val output: List<Long>,
    val cacheRead: List<Long>,
    val cacheWrite: List<Long>,
)

@Serializable
data class TokenModelSeriesDto(
    val model: String,                     // "unknown" when the DB model is null
    val input: List<Long>,
    val output: List<Long>,
    val cacheRead: List<Long>,
    val cacheWrite: List<Long>,
)

@Serializable
data class TokenAccountSeriesDto(
    val accountId: Int,
    val accountName: String?,
    val input: List<Long>,
    val output: List<Long>,
    val cacheRead: List<Long>,
    val cacheWrite: List<Long>,
)

@Serializable
data class TokenStatsPayload(
    val days: List<String>,                // date labels (UTC), oldest→newest
    val total: TokenTotalsDto,             // summed across all accounts and models
    val perModel: List<TokenModelSeriesDto>,   // one entry per distinct model, sorted alphabetically
    val perAccount: List<TokenAccountSeriesDto>, // empty if the viewer can't see accounts
    val models: List<String>,              // sorted distinct model labels (incl. "unknown")
    val canViewAccounts: Boolean,
)

@Serializable
data class TokenUsageSeriesDto(
    val tokenId: Int?,                     // null = unattributed (pre-migration rows)
    val name: String?,                     // null for deleted tokens (usage history kept)
    val cost: List<Double>,                // per-day USD, range-aligned with `days`
    val tokens: List<Long>,                // per-day total tokens (all four kinds)
    val requests: List<Long>,
    val totalCost: Double,                 // ALL-TIME totals (not range-scoped)
    val totalTokens: Long,
    val totalRequests: Long,
)

/** Per-inbound-token usage for the caller, one datapath at a time (Tokens / API Routing pages). */
@Serializable
data class TokenUsagePayload(
    val days: List<String>,                // date labels (UTC), oldest→newest
    val perToken: List<TokenUsageSeriesDto>, // sorted by all-time cost desc
)

@Serializable
data class McpToolSeriesDto(
    val name: String,                      // full tool name, e.g. "mcp__github__get_issue"
    val calls: List<Long>,                 // per-day call counts, range-aligned with `days`
    val totalCalls: Long,                  // sum over the range
)

/** Per-MCP-tool call counts for one user on the Claude Code datapath. */
@Serializable
data class McpUsagePayload(
    val days: List<String>,                // date labels (UTC), oldest→newest
    val tools: List<McpToolSeriesDto>,     // sorted by totalCalls desc
)

@Serializable
data class WindowSeriesDto(
    val accountId: Int,
    val accountName: String?,
    val fiveHour: List<Double?>,          // 0..1 utilization per bucket (null = no data)
    val weekly: List<Double?>,
    val fiveHourWeighted: List<Double?>,  // coefficient × utilization (may exceed 1)
    val weeklyWeighted: List<Double?>,
)

@Serializable
data class WindowStatsPayload(
    val buckets: List<String>,                 // time labels, oldest→newest
    val totalFiveHour: List<Double?>,          // Σ raw 5h utilization across accounts (may exceed 1)
    val totalWeekly: List<Double?>,
    val totalFiveHourWeighted: List<Double?>,  // Σ coefficient × 5h utilization across accounts
    val totalWeeklyWeighted: List<Double?>,
    val perAccount: List<WindowSeriesDto>,     // empty if the viewer can't see accounts
    val canViewAccounts: Boolean,
)

@Serializable
data class WindowDailySeriesDto(
    val accountId: Int,
    val accountName: String?,
    // Window budget burned per day, in window-fractions: 1.0 = one whole window consumed.
    // A day with three fully-spent 5h windows reads 3.0, which is exactly the point — the
    // utilization gauge itself can never exceed 1.0 because it resets.
    val fiveHour: List<Double>,
    val weekly: List<Double>,
    // The same 5-hour burn expressed in *base-subscription* windows: each step is scaled by the
    // account's capacity coefficient, so a ×5 account spending its whole window reads 5.0. Lets a
    // mixed pool be compared on one scale. No weekly counterpart: the weekly window is the same
    // size on every plan, so the multiplier does not apply to it.
    val fiveHourWeighted: List<Double>,
)

/**
 * Per-day window burn (5h + weekly), summed from the positive steps of the utilization series so
 * that mid-day limit resets are counted rather than swallowed. Days are labelled in the viewer's
 * timezone, oldest→newest.
 */
@Serializable
data class WindowDailyPayload(
    val days: List<String>,
    val totalFiveHour: List<Double>,           // Σ across accounts
    val totalWeekly: List<Double>,
    val totalFiveHourWeighted: List<Double>,   // Σ across accounts, in base-subscription windows
    val perAccount: List<WindowDailySeriesDto>, // empty if the viewer can't see accounts
    val canViewAccounts: Boolean,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class MessageResponse(val message: String)
