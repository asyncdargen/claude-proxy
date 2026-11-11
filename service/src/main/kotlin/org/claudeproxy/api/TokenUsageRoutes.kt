package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.model.Permission
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoutingTokenRepo
import org.claudeproxy.repo.UserRepo
import org.claudeproxy.repo.WindowAttributionRepo
import java.time.Instant
import java.time.ZoneOffset

@Serializable
data class TokenUsageWindow(val used_percentage: Double?, val resets_at: Long?, val updated_at: Long?, val stale: Boolean)
@Serializable
data class TokenRateLimits(val five_hour: TokenUsageWindow?, val seven_day: TokenUsageWindow?)
@Serializable
data class TokenAttributionContributor(val username: String, val percentage: Double, val me: Boolean)
@Serializable
data class TokenAttributionPoint(val ts: Long, val total: Double, val values: Map<String, Double>)
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TokenAttributionWindow(
    val started_at: Long, val resets_at: Long?, val observed_at: Long, val partial: Boolean,
    val total_percentage: Double, val mine_percentage: Double,
    val contributors: List<TokenAttributionContributor>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val points: List<TokenAttributionPoint>? = null,
)
@Serializable
data class TokenAttribution(val five_hour: TokenAttributionWindow?, val seven_day: TokenAttributionWindow?)
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TokenAccountLimits(
    val scope: String, val available: Boolean, val rate_limits: TokenRateLimits,
    // Only with ?attribution=summary|full. Absent otherwise, not null: the default response
    // keeps its exact shape and names no account or user.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val attribution: TokenAttribution? = null,
)
@Serializable
data class TokenDailyLimit(val used_usd: Double, val limit_usd: Double?, val exhausted: Boolean, val resets_at: Long)
@Serializable
data class TokenUsageSnapshot(
    val snapshot_at: Long,
    val source: String,
    val basis: String = "next_request_candidate",
    val next_account_index: Int?,
    val available_accounts: Int,
    val rate_limits: TokenRateLimits?,
    val daily: TokenDailyLimit,
    val accounts: List<TokenAccountLimits>,
)

/** A reset passing never proves a zero reading: retain the last value and mark it stale. */
internal fun tokenUsageWindow(window: WindowLimit?, now: Instant): TokenUsageWindow? = window?.let {
    TokenUsageWindow(it.usageFraction()?.times(100), it.resetAt?.epochSecond, it.updatedAt?.epochSecond,
        it.updatedAt == null || it.updatedAt.isBefore(now.minusSeconds(35 * 60)) ||
            (it.resetAt != null && !it.resetAt.isAfter(now)))
}

/**
 * One shared computation serves every poller. A caller never waits for another caller's
 * refresh: while one request recomputes, the rest get the previous value (null before the
 * first). A failed refresh keeps the previous value and is retried after the same interval,
 * so attribution trouble never fails the quota snapshot itself.
 */
internal object TokenAttributionCache {
    private const val TTL_NANOS = 60_000_000_000L
    private class Entry(val value: WindowAttributionResponse?, val loadedAtNanos: Long)
    private val entry = java.util.concurrent.atomic.AtomicReference<Entry?>(null)
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    internal var load: () -> WindowAttributionResponse = { WindowAttributionRepo.fetch(1) }

    fun get(): WindowAttributionResponse? {
        val current = entry.get()
        if (current != null && System.nanoTime() - current.loadedAtNanos < TTL_NANOS) return current.value
        if (!refreshing.compareAndSet(false, true)) return current?.value
        try {
            val loaded = runCatching { load() }.getOrElse { current?.value }
            entry.set(Entry(loaded, System.nanoTime()))
            return loaded
        } finally { refreshing.set(false) }
    }
    fun clear() { entry.set(null); load = { WindowAttributionRepo.fetch(1) } }
}

/** The current period of one window in percent of the quota; null when no current period is known. */
internal fun tokenAttributionWindow(window: AttributionWindow?, userId: Int, withPoints: Boolean): TokenAttributionWindow? {
    val period = window?.periods?.lastOrNull()?.takeIf { it.current } ?: return null
    // Usernames are unique; only the bucket of unmatched usage could share its label with one.
    val names = period.contributors.associate {
        it.key to if (it.userId != null && it.username == "Unattributed") "Unattributed (user)" else it.username
    }
    return TokenAttributionWindow(
        started_at = Instant.parse(period.startAt).epochSecond, resets_at = period.endAt?.let { Instant.parse(it).epochSecond },
        observed_at = Instant.parse(period.observedAt).epochSecond, partial = period.partial,
        total_percentage = period.total * 100,
        mine_percentage = period.contributors.filter { it.userId == userId }.sumOf { it.amount } * 100,
        contributors = period.contributors.sortedByDescending { it.amount }
            .map { TokenAttributionContributor(names.getValue(it.key), it.amount * 100, it.userId == userId) },
        points = if (!withPoints) null else period.points.map { point ->
            TokenAttributionPoint(Instant.parse(point.ts).epochSecond, point.total * 100,
                point.values.mapNotNull { (key, amount) -> names[key]?.let { it to amount * 100 } }.toMap())
        },
    )
}

/** Read-only cached quotas. API tokens never gain management access or upstream credentials. */
fun Route.tokenUsageRoutes(pool: AccountPool, datapath: DatapathService) {
    get("/gateway/v1/usage") {
        call.response.headers.append("Cache-Control", "no-store")
        val authHeader = call.request.headers["Authorization"]
        val token = if (authHeader != null) {
            if (!authHeader.startsWith("Bearer ", ignoreCase = true)) "" else authHeader.substring(7).trim()
        } else call.request.headers["x-api-key"]?.trim().orEmpty()
        val routing = token.startsWith("cxr_")
        val auth = if (token.isBlank()) null else if (routing) RoutingTokenRepo.resolveAuth(token) else ProxyTokenRepo.resolveAuth(token)
        val user = auth?.let { UserRepo.findAuth(it.userId) }?.takeIf { it.enabled }
            ?: return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("Invalid API token"))
        val required = if (routing) Permission.ROUTING_USE else Permission.PROXY_USE
        if (required !in user.permissions) return@get call.respond(HttpStatusCode.Forbidden, MessageResponse("Missing permission"))
        val now = Instant.now()
        val source = if (routing) "routing" else "proxy"
        val cap = if (routing) UserRepo.dailyRoutingLimitOf(user.id) else UserRepo.dailyLimitOf(user.id)
        val spent = datapath.cachedDailySpend(user.id, source)
        val exhausted = cap != null && spent >= cap
        val groups = if (Permission.ADMIN in user.permissions) null else UserRepo.allowedGroupsOf(user.id)
        val allowGlobal = Permission.ADMIN in user.permissions || Permission.POOL_GLOBAL_USE in user.permissions
        val eligible = if (exhausted) pool.selectionOrderOwned(user.id, now)
            else pool.selectionOrder(user.id, groups, !UserRepo.preferGlobalPoolOf(user.id), allowGlobal, now)
        val availableIds = eligible.map { it.id }.toSet()
        // Include exhausted/unhealthy accounts for visibility, but never other users' personal
        // accounts or groups. No account IDs, names, email, device IDs or credentials are emitted.
        val scoped = pool.snapshot().filter {
            it.enabled && if (it.ownerId != null) it.ownerId == user.id
            else allowGlobal && (groups == null || it.groupId == null || it.groupId in groups)
        }.sortedWith(compareBy({ if (it.id == eligible.firstOrNull()?.id) 0 else 1 }, { it.priority }, { it.id }))
        // Opt-in detail for clients that draw per-account rows: account names and who spent the
        // current windows. Usernames are shown to every token holder of the same account scope.
        val detail = call.request.queryParameters["attribution"]?.takeIf { it == "summary" || it == "full" }
        val attributed = if (detail == null) emptyMap() else TokenAttributionCache.get()?.accounts.orEmpty().associateBy { it.accountId }
        val accounts = scoped.map { a -> TokenAccountLimits(
            scope = if (a.ownerId == null) "shared" else "personal", available = a.id in availableIds,
            rate_limits = TokenRateLimits(tokenUsageWindow(a.limit.window(WindowKind.FIVE_HOUR), now),
                tokenUsageWindow(a.limit.window(WindowKind.WEEKLY), now)),
            name = if (detail == null) null else a.name,
            attribution = attributed[a.id]?.let { found ->
                TokenAttribution(
                    tokenAttributionWindow(found.windows.firstOrNull { it.kind == "5h" }, user.id, detail == "full"),
                    tokenAttributionWindow(found.windows.firstOrNull { it.kind == "7d" }, user.id, detail == "full"))
            }) }
        val next = scoped.indexOfFirst { it.id == eligible.firstOrNull()?.id }.takeIf { it >= 0 }
        call.respond(TokenUsageSnapshot(snapshot_at=now.epochSecond, source=source, next_account_index=next,
            available_accounts=accounts.count { it.available }, rate_limits=next?.let { accounts[it].rate_limits },
            daily=TokenDailyLimit(spent, cap, exhausted, now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toEpochSecond()), accounts=accounts))
    }
}
