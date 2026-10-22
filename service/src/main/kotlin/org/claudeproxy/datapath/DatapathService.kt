package org.claudeproxy.datapath

import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountRuntime
import org.claudeproxy.accounts.RateLimitHeaders
import org.claudeproxy.accounts.RateLimitPolicy
import org.claudeproxy.api.CandidateDto
import org.claudeproxy.api.UsageReport
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.Permission
import org.claudeproxy.model.WindowKind
import org.claudeproxy.repo.McpUsageRepo
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoutingTokenRepo
import org.claudeproxy.repo.UsageRepo
import org.claudeproxy.repo.ModelPriceRepo
import org.claudeproxy.repo.UserRepo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Why a resolve failed, mapped by the route to 401/403. */
enum class ResolveError { BAD_TOKEN, NO_PERMISSION }

/**
 * Result of resolving an inbound datapath request into an ordered try-list. On error,
 * [error] is set and [candidates] is empty. Empty candidates with [overLimit]=true means
 * the daily spend limit was hit with no personal fallback; empty with overLimit=false
 * means no usable account.
 */
data class ResolveResult(
    val userId: Int?,
    val tokenId: Int?,
    val error: ResolveError?,
    val overLimit: Boolean,
    val dailyLimitUsd: Double?,
    val usedUsd: Double?,
    val candidates: List<CandidateDto>,
    // routing only: the token's static system prompt, injected by the gateway ahead of the
    // client's own system content (but after the mandatory Claude Code block).
    val systemPrompt: String? = null,
    // proxy only: the model the token forces onto the request, replacing the client's choice.
    val defaultModel: String? = null,
    // the request is on a free path (token counting, model listing): no quota, no limit, and a
    // successful zero-token outcome is not recorded as usage.
    val free: Boolean = false,
    // empty plan because every account in reach is parked: seconds until the earliest one frees up.
    val retryAfterSeconds: Long? = null,
)

/**
 * The reusable datapath decision logic, extracted from `ProxyRoutes`/`UpstreamForwarder`
 * so both the in-process Kotlin datapath and the `/internal/` control API (Go gateway)
 * share one implementation. `resolve` turns a request into an ordered plan; `applyOutcome`
 * applies a single attempt's result to account bookkeeping. The service stays the single
 * source of truth — selection, decryption, and limit state never leave it.
 */
class DatapathService(private val pool: AccountPool) {
    private val log = org.slf4j.LoggerFactory.getLogger("Datapath")

    /**
     * Resolve a request into an ordered, ready-to-forward candidate list. [source] selects the
     * datapath: `"proxy"` (Claude Code, the default) or `"routing"` (the OpenAI/Anthropic API
     * gateways). Routing resolves the token in the routing-token namespace, requires
     * `ROUTING_USE`, and meters spend against the separate per-user routing daily limit. Account
     * selection (personal-first, group scope, global-pool gating) is identical for both.
     *
     * [requestId], when the caller supplies one, opens an [ActiveSessions] entry for the "active
     * now" gauges; the caller closes it when the request finishes.
     */
    suspend fun resolve(
        token: String, method: String, path: String, source: String = "proxy", requestId: String? = null,
    ): ResolveResult {
        val routing = source == "routing"
        val auth = (if (routing) RoutingTokenRepo.resolveAuth(token) else ProxyTokenRepo.resolveAuth(token))
            ?: return ResolveResult(null, null, ResolveError.BAD_TOKEN, false, null, null, emptyList())
        val userId = auth.userId
        val tokenId = auth.tokenId
        val perms = UserRepo.permissionsOf(userId)
        val required = if (routing) Permission.ROUTING_USE else Permission.PROXY_USE
        if (required !in perms) {
            return ResolveResult(userId, tokenId, ResolveError.NO_PERMISSION, false, null, null, emptyList())
        }
        // Only a resolve the caller can act on opens a session — a rejected one never gets a
        // matching close, and would sit in the map until it aged out.
        if (requestId != null) ActiveSessions.begin(requestId, userId, routing)

        // Admins may use any account; others are scoped to their granted groups.
        val allowedGroups: Set<Int>? = if (Permission.ADMIN in perms) null else UserRepo.allowedGroupsOf(userId)
        val personalFirst = !UserRepo.preferGlobalPoolOf(userId)
        // Routing through the shared pool requires POOL_GLOBAL_USE (admins always allowed).
        // Without it a user reaches only their own personal accounts.
        val allowGlobal = Permission.ADMIN in perms || Permission.POOL_GLOBAL_USE in perms

        val sysPrompt = if (routing) tokenId?.let { RoutingTokenRepo.promptOf(it) } else null
        val forcedModel = if (routing) null else tokenId?.let { ProxyTokenRepo.defaultModelOf(it) }

        // Free paths (token counting, model listing) never consume quota: any account, no limits.
        // They still get the *whole* try-list rather than a single pick — Claude Code counts
        // tokens on every turn, and one unhealthy account shouldn't break the context indicator
        // when the pool has others that would answer.
        if (isFreePath(path)) {
            val order = pool.selectAnyOrder(userId, allowedGroups, personalFirst, allowGlobal)
            return ResolveResult(userId, tokenId, null, false, null, null, order.map { it.toCandidate() }, sysPrompt, forcedModel, free = true)
        }

        // Per-user daily USD limit is a shared-pool constraint; personal accounts are exempt.
        val costLimit = if (routing) UserRepo.dailyRoutingLimitOf(userId) else UserRepo.dailyLimitOf(userId)
        val usedCost = if (costLimit != null) cachedDailySpend(userId, if (routing) "routing" else "proxy") else 0.0
        val overLimit = costLimit != null && usedCost >= costLimit

        val order = if (overLimit) pool.selectionOrderOwned(userId) else pool.selectionOrder(userId, allowedGroups, personalFirst, allowGlobal)
        // An empty plan becomes a 503 at the gateway and records no usage at all — without this
        // line the failure leaves no trace anywhere, neither in the log nor in the stats.
        if (order.isEmpty() && !overLimit) logNoCandidate(userId, source)
        // Pool empty because accounts are parked: tell the gateway when to come back, so the
        // client gets a 429 + Retry-After instead of a 503 that reads like an outage.
        val retryAfter = if (order.isEmpty() && !overLimit) {
            // Only a parked account comes back at a known instant; anything else (no access to
            // the pool, lost credentials) stays a 503.
            if (pool.availability(userId, allowedGroups).rateLimited > 0) pool.earliestReset()?.let {
                maxOf(1L, java.time.Duration.between(Instant.now(), it).seconds)
            } else null
        } else null
        return ResolveResult(userId, tokenId, null, overLimit, costLimit, usedCost, order.map { it.toCandidate() }, sysPrompt, forcedModel, retryAfterSeconds = retryAfter)
    }

    /** Why the pool had nothing to offer, per account in the user's reach. */
    private suspend fun logNoCandidate(userId: Int, source: String) {
        val now = Instant.now()
        val reasons = pool.snapshot().filter { it.ownerId == null || it.ownerId == userId }.joinToString("; ") { a ->
            val util = a.limit.usageFraction()
            val why = when {
                !a.enabled -> "disabled"
                a.health != AccountHealth.OK -> a.health.name.lowercase()
                a.limit.rateLimitedUntil?.isAfter(now) == true -> "rate-limited until ${a.limit.rateLimitedUntil}"
                util != null && util >= a.threshold -> "used %.2f of threshold %.2f".format(util, a.threshold)
                else -> "usable"
            }
            "#${a.id} ${a.name}: $why"
        }
        log.warn("No account to serve user {} ({}): {}", userId, source, reasons)
    }

    /**
     * Apply one upstream attempt's outcome: record usage, refresh limit state from headers,
     * and run the per-status account bookkeeping that `UpstreamForwarder` did inline.
     */
    suspend fun applyOutcome(o: UsageReport) {
        // Datapath tag as reported: "proxy" (Claude Code), "routing" (API gateways) or "chat"
        // (the built-in UI). Each meters its own daily limit, so the tag has to survive verbatim.
        val source = o.source.ifBlank { "proxy" }
        val billed = o.billed()
        // A free-path request (token counting, model listing) that succeeded without consuming
        // anything is not a data point — recording it would pad the request counters and the
        // "recent requests" list with zero-token noise for every keystroke's context estimate.
        // Failures still land: a broken count_tokens is exactly what you want to see.
        val silent = o.free && o.status in 200..299 && billed.isEmpty()
        val cost = if (silent) 0.0 else {
            UsageRepo.record(o.accountId, o.userId, billed, o.status, o.model, source, o.tokenId, o.webFetchRequests)
        }
        if (o.mcpCalls.isNotEmpty()) McpUsageRepo.record(o.userId, o.tokenId, o.mcpCalls)

        // Keep the cached daily spend fresh. Only *global* (shared-pool) usage counts toward the
        // per-user daily limit; personal accounts are the user's own quota (exempt). Proxy and
        // routing spend accumulate under separate keys so each limit meters only its own datapath.
        // The increment is a no-op when the key isn't cached — the next resolve recomputes from DB.
        // `cost` is the very number persisted on the usage row, so the limit can never drift from
        // what the stats show.
        val ownerId = pool.get(o.accountId)?.ownerId
        if (o.userId != null && ownerId == null && cost > 0.0) {
            MemoryCache.incrExistingByFloat(spendKey(o.userId, source), cost)
        }

        val headers = o.ratelimitHeaders.orEmpty()
        val prev = pool.get(o.accountId)?.limit ?: LimitState()
        val newLimit = RateLimitHeaders.parse(headers, prev)
        pool.updateLimit(o.accountId, newLimit)

        when {
            o.status == 429 -> {
                // Park the account only when its own windows say it is spent; a per-model or
                // burst 429 goes back to the client alone (see RateLimitPolicy).
                val until = RateLimitPolicy.parkUntil(headers, newLimit)
                log.warn("upstream 429 acct#{} model={} user={} -> {}; headers: {}", o.accountId, o.model, o.userId,
                    until?.let { "park until $it" } ?: "not parked (model/burst limit)", RateLimitPolicy.describe(headers))
                if (until != null) pool.markRateLimited(o.accountId, until)
            }
            o.status == 401 -> {
                pool.setHealth(o.accountId, AccountHealth.REFRESH_FAILED)
                runCatching { AccountRepo.updateHealth(o.accountId, AccountHealth.REFRESH_FAILED) }
            }
        }
    }

    /** Format an ordered account into a gateway-ready candidate (decrypted auth headers). */
    private fun AccountRuntime.toCandidate(): CandidateDto = CandidateDto(
        accountId = id,
        type = type.name,
        deviceId = deviceId,
        accountUuid = accountUuid.takeIf { type != AccountType.API_KEY },
        authHeaders = authHeadersFor(this),
        fiveHourResetEpoch = limit.window(WindowKind.FIVE_HOUR)?.resetAt?.epochSecond,
        weeklyResetEpoch = limit.window(WindowKind.WEEKLY)?.resetAt?.epochSecond,
    )

    /**
     * Build the upstream auth headers exactly like `UpstreamAuth.apply`: OAUTH →
     * `Authorization: Bearer <access>` + `anthropic-beta: oauth-2025-04-20`; API_KEY → `x-api-key`.
     */
    private fun authHeadersFor(a: AccountRuntime): Map<String, String> = buildMap {
        when (a.type) {
            AccountType.API_KEY -> a.secret.apiKey?.let { put("x-api-key", it) }
            AccountType.OAUTH, AccountType.OAUTH_STATIC -> {
                a.secret.accessToken?.let { put("Authorization", "Bearer $it") }
                put("anthropic-beta", "oauth-2025-04-20")
            }
        }
    }

    /**
     * Cached shared-pool daily spend (USD) for a user on one datapath. Cached in-process under
     * `cp:spend:<source>:<user>:<utcDate>` until the next UTC midnight, with a DB recompute on
     * miss. Public because the chat datapath meters its own limit through the same counter.
     */
    fun cachedDailySpend(userId: Int, source: String): Double {
        val ttl = secondsToUtcMidnight()
        val cached = MemoryCache.getOrLoad(spendKey(userId, source), ttl) {
            UsageRepo.userTotals(userId, UserRepo.startOfUtcDay(), globalOnly = true, source = source).cost.toString()
        }
        return cached?.toDoubleOrNull() ?: 0.0
    }

    private fun spendKey(userId: Int, source: String): String =
        "cp:spend:$source:$userId:${LocalDate.now(ZoneOffset.UTC)}"

    private fun secondsToUtcMidnight(): Long {
        val nextMidnight = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        return maxOf(1L, java.time.Duration.between(Instant.now(), nextMidnight).seconds)
    }


    companion object {
        private val freePathRe = Regex("""/v1/(messages/count_tokens|models(/[^/]+)?)/?""")

        /**
         * Paths that don't consume subscription usage: token counting and model listing, matched as
         * the *whole* path (every datapath hands the resolve a bare `/v1/…`). A substring match let any path that merely mentioned
         * `count_tokens` — `/v1/chat/completions/count_tokens` on the OpenAI gateway, say — skip
         * the daily limit while the translator still ran a full generation.
         */
        internal fun isFreePath(pathAndQuery: String): Boolean {
            val p = pathAndQuery.substringBefore('?')
            return freePathRe.matches(p)
        }
    }
}
