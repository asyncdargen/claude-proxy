package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.Accounts
import org.claudeproxy.db.UsageEvents
import org.claudeproxy.db.UsageHourly
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.compoundAnd
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Serializable
data class UsageEventDto(
    val id: Int, val accountId: Int, val accountName: String?, val userId: Int?, val ts: String,
    val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long, val cacheWriteTokens: Long,
    val cost: Double, val httpStatus: Int, val model: String?, val source: String = "proxy",
    // The parts of the bill that aren't visible in the token counts above: the 1h slice of the
    // cache writes (priced ~1.6× the 5m rate), server-side web searches (billed per call) and
    // fast mode (a premium tier on the same model).
    val cacheWrite1hTokens: Long = 0, val webSearchRequests: Long = 0, val fast: Boolean = false,
)

@Serializable
data class UsageSummaryDto(
    val accountId: Int, val accountName: String?, val requests: Long,
    val inputTokens: Long, val outputTokens: Long, val cost: Double,
)

@Serializable
data class ModelUsageDto(val model: String?, val requests: Long, val cleanTokens: Long, val cost: Double)

/** One (account, day) bucket of usage. */
@Serializable
data class DailyBucketDto(val accountId: Int, val date: String, val cost: Double, val requests: Long, val tokens: Long)

/** One (account, model, day) bucket with the four token kinds kept separate. */
@Serializable
data class TokenBucketDto(
    val accountId: Int, val model: String?, val date: String,
    val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long,
)

/** One (inbound token, day) bucket of one user's usage on one datapath. tokenId null = unattributed. */
@Serializable
data class TokenIdDailyBucketDto(val tokenId: Int?, val date: String, val cost: Double, val requests: Long, val tokens: Long)

/** Token counters + USD cost bundle. */
class Totals(
    val requests: Long = 0, val input: Long = 0, val output: Long = 0,
    val cacheRead: Long = 0, val cacheWrite: Long = 0, val cost: Double = 0.0,
) {
    val clean: Long get() = input + output + cacheRead + cacheWrite
}

object UsageRepo {

    /**
     * Record one attempt and its USD cost. [usage] carries the full priced breakdown (per-TTL
     * cache writes, web searches, fast mode) so the stored `cost` is the same number
     * [ModelPriceRepo.costOf] gives the daily-limit counter — the two must never diverge.
     *
     * Returns that cost, so the caller meters the limit against the persisted value rather than
     * a second, independently computed one.
     */
    fun record(
        accountId: Int, userId: Int?, usage: BilledUsage,
        status: Int, model: String?, source: String = "proxy", tokenId: Int? = null,
        webFetchRequests: Long = 0,
    ): Double {
        val cost = ModelPriceRepo.costOf(model, usage)
        runCatching {
            transaction {
                UsageEvents.insert {
                    it[UsageEvents.accountId] = accountId
                    it[UsageEvents.userId] = userId
                    it[UsageEvents.tokenId] = tokenId
                    it[ts] = Instant.now()
                    it[inputTokens] = usage.input
                    it[outputTokens] = usage.output
                    it[cacheReadTokens] = usage.cacheRead
                    it[cacheWriteTokens] = usage.cacheWrite5m + usage.cacheWrite1h
                    it[cacheWrite1hTokens] = usage.cacheWrite1h
                    it[UsageEvents.webSearchRequests] = usage.webSearchRequests
                    it[UsageEvents.webFetchRequests] = webFetchRequests
                    it[fast] = usage.fast
                    it[UsageEvents.cost] = cost
                    it[httpStatus] = status
                    it[UsageEvents.model] = model
                    it[UsageEvents.sourceCol] = source
                }
            }
        }
        return cost
    }

    /** Which events a read covers. [since] is inclusive, [until] exclusive; null leaves that side open. */
    private class Scope(
        val userId: Int? = null,
        val accounts: Set<Int>? = null,
        val excludedAccounts: Set<Int> = emptySet(),
        val source: String? = null,
        val since: Instant? = null,
        val until: Instant? = null,
    )

    private enum class Dim(val event: Column<*>, val hourly: Column<*>) {
        ACCOUNT(UsageEvents.accountId, UsageHourly.accountId),
        USER(UsageEvents.userId, UsageHourly.userId),
        MODEL(UsageEvents.model, UsageHourly.model),
        SOURCE(UsageEvents.sourceCol, UsageHourly.sourceCol),
        TOKEN(UsageEvents.tokenId, UsageHourly.tokenId),
    }

    private fun filters(scope: Scope, account: Column<Int>, user: Column<Int?>, source: Column<String>): List<Op<Boolean>> =
        listOfNotNull(
            scope.userId?.let { user eq it },
            scope.accounts?.let { account inList it },
            scope.excludedAccounts.takeIf { it.isNotEmpty() }?.let { account notInList it },
            scope.source?.let { source eq it },
        )

    private fun hourlyFilters(scope: Scope) = filters(scope, UsageHourly.accountId, UsageHourly.userId, UsageHourly.sourceCol)
    private fun eventFilters(scope: Scope) = filters(scope, UsageEvents.accountId, UsageEvents.userId, UsageEvents.sourceCol)

    /**
     * The whole hours of [scope] the rollup holds: from the first hour boundary at or after `since`
     * up to the watermark (or the last boundary before `until`). Null when there are none, and
     * then the events answer alone.
     */
    private fun covered(scope: Scope, through: Instant?): Pair<Instant?, Instant>? {
        through ?: return null
        val from = scope.since?.let { since -> since.truncatedTo(ChronoUnit.HOURS).let { if (it.isBefore(since)) it.plus(1, ChronoUnit.HOURS) else it } }
        val to = scope.until?.let { minOf(it.truncatedTo(ChronoUnit.HOURS), through) } ?: through
        return if (from != null && from >= to) null else from to to
    }

    private fun hourRange(covered: Pair<Instant?, Instant>) =
        listOfNotNull(covered.first?.let { UsageHourly.hour greaterEq it }, UsageHourly.hour less covered.second)

    /** The events [scope] needs on top of the rollup: the part-hour before it, and everything past it. */
    private fun eventRange(scope: Scope, covered: Pair<Instant?, Instant>?): List<Op<Boolean>> = listOfNotNull(
        scope.since?.let { UsageEvents.ts greaterEq it },
        scope.until?.let { UsageEvents.ts less it },
        covered?.let { (from, to) -> if (from == null) UsageEvents.ts greaterEq to else (UsageEvents.ts less from) or (UsageEvents.ts greaterEq to) },
    )

    /**
     * Count, sums and latest instant of the events in [scope], grouped by [dims] (one group, keyed by
     * the empty list, when there are none). The database adds them up: the rollup for the hours it
     * holds, the events for the rest. Loading the events to add them up in the JVM made every
     * all-time read cost the whole table, which grows with every request.
     */
    private fun aggregate(scope: Scope, vararg dims: Dim): Map<List<Any?>, UsageSums> {
        val out = HashMap<List<Any?>, UsageSums>()
        val covered = covered(scope, UsageRollup.through())
        if (covered != null) {
            val keys = dims.map { it.hourly }
            val requests = UsageHourly.requests.sum(); val input = UsageHourly.inputTokens.sum()
            val output = UsageHourly.outputTokens.sum(); val cacheRead = UsageHourly.cacheReadTokens.sum()
            val cacheWrite = UsageHourly.cacheWriteTokens.sum(); val cost = UsageHourly.cost.sum(); val last = UsageHourly.lastTs.max()
            var query = UsageHourly.select(keys + listOf(requests, input, output, cacheRead, cacheWrite, cost, last))
                .where((hourlyFilters(scope) + hourRange(covered)).compoundAnd())
            if (keys.isNotEmpty()) query = query.groupBy(*keys.toTypedArray())
            query.forEach { row ->
                out.getOrPut(keys.map { row[it] }) { UsageSums() }.add(
                    row[requests] ?: 0, row[input] ?: 0, row[output] ?: 0, row[cacheRead] ?: 0, row[cacheWrite] ?: 0,
                    row[cost] ?: 0.0, row[last],
                )
            }
        }
        val keys = dims.map { it.event }
        val requests = UsageEvents.id.count(); val input = UsageEvents.inputTokens.sum()
        val output = UsageEvents.outputTokens.sum(); val cacheRead = UsageEvents.cacheReadTokens.sum()
        val cacheWrite = UsageEvents.cacheWriteTokens.sum(); val cost = UsageEvents.cost.sum(); val last = UsageEvents.ts.max()
        var query = UsageEvents.select(keys + listOf(requests, input, output, cacheRead, cacheWrite, cost, last))
        val where = eventFilters(scope) + eventRange(scope, covered)
        if (where.isNotEmpty()) query = query.where(where.compoundAnd())
        if (keys.isNotEmpty()) query = query.groupBy(*keys.toTypedArray())
        query.forEach { row ->
            out.getOrPut(keys.map { row[it] }) { UsageSums() }.add(
                row[requests], row[input] ?: 0, row[output] ?: 0, row[cacheRead] ?: 0, row[cacheWrite] ?: 0,
                row[cost] ?: 0.0, row[last],
            )
        }
        return out
    }

    /**
     * Like [aggregate], but per local date in [zone] as well, for the day-bucketed charts. The rollup
     * serves the hours it holds only when every hour falls on one local day, which is the case for
     * any zone whose offsets are whole hours; otherwise the events answer alone, as before.
     */
    private fun daily(scope: Scope, zone: ZoneId, vararg dims: Dim): Map<Pair<String, List<Any?>>, UsageSums> {
        val out = HashMap<Pair<String, List<Any?>>, UsageSums>()
        val since = scope.since; val until = scope.until
        val covered = if (since != null && until != null && UsageRollup.wholeHours(zone, since, until)) {
            covered(scope, UsageRollup.through())
        } else null
        if (covered != null) {
            val keys = dims.map { it.hourly }
            val requests = UsageHourly.requests.sum(); val input = UsageHourly.inputTokens.sum()
            val output = UsageHourly.outputTokens.sum(); val cacheRead = UsageHourly.cacheReadTokens.sum()
            val cacheWrite = UsageHourly.cacheWriteTokens.sum(); val cost = UsageHourly.cost.sum()
            UsageHourly.select(listOf(UsageHourly.hour) + keys + listOf(requests, input, output, cacheRead, cacheWrite, cost))
                .where((hourlyFilters(scope) + hourRange(covered)).compoundAnd())
                .groupBy(UsageHourly.hour, *keys.toTypedArray())
                .forEach { row ->
                    val date = row[UsageHourly.hour].atZone(zone).toLocalDate().toString()
                    out.getOrPut(date to keys.map { row[it] }) { UsageSums() }.add(
                        row[requests] ?: 0, row[input] ?: 0, row[output] ?: 0, row[cacheRead] ?: 0, row[cacheWrite] ?: 0,
                        row[cost] ?: 0.0, null,
                    )
                }
        }
        val keys = dims.map { it.event }
        val columns = listOf(
            UsageEvents.ts, UsageEvents.inputTokens, UsageEvents.outputTokens, UsageEvents.cacheReadTokens,
            UsageEvents.cacheWriteTokens, UsageEvents.cost,
        ) + keys
        UsageEvents.select(columns.distinct())
            .where((eventFilters(scope) + eventRange(scope, covered)).compoundAnd())
            .forEach { row ->
                val date = row[UsageEvents.ts].atZone(zone).toLocalDate().toString()
                out.getOrPut(date to keys.map { row[it] }) { UsageSums() }.add(
                    1, row[UsageEvents.inputTokens], row[UsageEvents.outputTokens], row[UsageEvents.cacheReadTokens],
                    row[UsageEvents.cacheWriteTokens], row[UsageEvents.cost], null,
                )
            }
        return out
    }

    /** Ids of personal (owner-scoped) accounts; their usage is excluded from global stats. */
    private fun personalAccountIds(): Set<Int> =
        Accounts.selectAll().mapNotNull { row -> row[Accounts.id].takeIf { row[Accounts.ownerId] != null } }.toSet()

    /**
     * Totals for one user. [globalOnly] excludes usage that went through the user's own
     * personal accounts — used for the daily-limit check, which governs only shared-pool spend.
     * [source], when set ("proxy" | "routing"), restricts to events from that datapath — the
     * per-datapath daily limits meter their own spend independently.
     */
    fun userTotals(userId: Int, since: Instant? = null, globalOnly: Boolean = false, source: String? = null): Totals = transaction {
        val personal = if (globalOnly) personalAccountIds() else emptySet()
        aggregate(Scope(userId = userId, excludedAccounts = personal, source = source, since = since))[emptyList()]?.totals ?: Totals()
    }

    /** Per-account totals for a specific set of accounts (used by the personal "My Accounts" view). */
    fun totalsForAccounts(ids: Set<Int>): Map<Int, Totals> = transaction {
        if (ids.isEmpty()) return@transaction emptyMap()
        aggregate(Scope(accounts = ids), Dim.ACCOUNT).entries.associate { (key, sums) -> key[0] as Int to sums.totals }
    }

    private fun perModelOf(scope: Scope): List<ModelUsageDto> =
        aggregate(scope, Dim.MODEL)
            .map { (key, sums) -> ModelUsageDto(key[0] as String?, sums.requests, sums.totals.clean, sums.cost) }
            .sortedByDescending { it.cost }

    /**
     * Per-model breakdown for a single user; [since] limits to events at/after that instant,
     * [source] ("proxy" | "routing") restricts to one datapath.
     */
    fun userPerModel(userId: Int, since: Instant? = null, source: String? = null): List<ModelUsageDto> = transaction {
        perModelOf(Scope(userId = userId, source = source, since = since))
    }

    /** Pool-wide per-model breakdown (excludes personal accounts); [since] limits the window. */
    fun perModel(since: Instant? = null): List<ModelUsageDto> = transaction {
        perModelOf(Scope(excludedAccounts = personalAccountIds(), since = since))
    }

    fun recentForUser(userId: Int, limit: Int = 100, source: String? = null): List<UsageEventDto> = transaction {
        val names = accountNames()
        val q = if (source == null) UsageEvents.selectAll().where { UsageEvents.userId eq userId }
        else UsageEvents.selectAll().where { (UsageEvents.userId eq userId) and (UsageEvents.sourceCol eq source) }
        q.orderBy(UsageEvents.ts, SortOrder.DESC).limit(limit).map { it.toEventDto(names) }
    }

    fun totalsPerAccount(): Map<Int, Totals> = transaction {
        aggregate(Scope(excludedAccounts = personalAccountIds()), Dim.ACCOUNT).entries.associate { (key, sums) -> key[0] as Int to sums.totals }
    }

    fun poolTotals(): Totals = transaction {
        aggregate(Scope(excludedAccounts = personalAccountIds()))[emptyList()]?.totals ?: Totals()
    }

    /**
     * Daily (account, day) buckets in [start, end), bucketed by date in [zone]. Excludes personal
     * accounts. [zone] is the viewer's local timezone, so a "day" matches what they see on a clock.
     */
    fun dailyBuckets(start: Instant, end: Instant, zone: ZoneId = ZoneOffset.UTC): List<DailyBucketDto> = transaction {
        daily(Scope(excludedAccounts = personalAccountIds(), since = start, until = end), zone, Dim.ACCOUNT)
            .map { (key, sums) -> DailyBucketDto(key.second[0] as Int, key.first, sums.cost, sums.requests, sums.totals.clean) }
    }

    /** Daily (account, day) buckets for one user's own usage in [start, end), across ALL accounts (incl. personal). */
    fun dailyBucketsForUser(
        userId: Int, start: Instant, end: Instant, source: String? = null, zone: ZoneId = ZoneOffset.UTC,
    ): List<DailyBucketDto> = transaction {
        daily(Scope(userId = userId, source = source, since = start, until = end), zone, Dim.ACCOUNT)
            .map { (key, sums) -> DailyBucketDto(key.second[0] as Int, key.first, sums.cost, sums.requests, sums.totals.clean) }
    }

    /** Daily (account, model, day) token buckets for one user's own usage in [start, end), across ALL accounts (incl. personal). */
    fun tokenBucketsForUser(
        userId: Int, start: Instant, end: Instant, source: String? = null, zone: ZoneId = ZoneOffset.UTC,
    ): List<TokenBucketDto> = transaction {
        daily(Scope(userId = userId, source = source, since = start, until = end), zone, Dim.ACCOUNT, Dim.MODEL)
            .map { (key, s) -> TokenBucketDto(key.second[0] as Int, key.second[1] as String?, key.first, s.input, s.output, s.cacheRead, s.cacheWrite) }
    }

    /**
     * Daily (inbound token, day) buckets of one user's usage on one datapath ("proxy" | "routing")
     * in [start, end), bucketed by date in [zone]. All accounts count (incl. personal) — this feeds
     * the user's own per-token view. tokenId null groups pre-migration/unattributed rows.
     */
    fun tokenDailyBucketsForUser(
        userId: Int, source: String, start: Instant, end: Instant, zone: ZoneId = ZoneOffset.UTC,
    ): List<TokenIdDailyBucketDto> = transaction {
        daily(Scope(userId = userId, source = source, since = start, until = end), zone, Dim.TOKEN)
            .map { (key, sums) -> TokenIdDailyBucketDto(key.second[0] as Int?, key.first, sums.cost, sums.requests, sums.totals.clean) }
    }

    /** All-time totals per inbound token for one user's usage on one datapath ("proxy" | "routing"). */
    fun totalsPerTokenForUser(userId: Int, source: String): Map<Int?, Totals> = transaction {
        aggregate(Scope(userId = userId, source = source), Dim.TOKEN).entries.associate { (key, sums) -> key[0] as Int? to sums.totals }
    }

    /** Daily (account, model, day) token buckets in [start, end), bucketed by date in [zone], kinds kept apart. Excludes personal accounts. */
    fun tokenBuckets(start: Instant, end: Instant, zone: ZoneId = ZoneOffset.UTC): List<TokenBucketDto> = transaction {
        daily(Scope(excludedAccounts = personalAccountIds(), since = start, until = end), zone, Dim.ACCOUNT, Dim.MODEL)
            .map { (key, s) -> TokenBucketDto(key.second[0] as Int, key.second[1] as String?, key.first, s.input, s.output, s.cacheRead, s.cacheWrite) }
    }

    /**
     * One user's aggregate line for the admin per-user overview: today + all-time totals, split by
     * datapath. userId null groups unattributed (deleted-user) events.
     *
     * "Today" has two bases here, deliberately: the display counters (`todayCost`, `todayRequests`,
     * `todayTokens`) follow the *viewer's* day like every chart, while the per-datapath costs
     * (`todayProxyCost`/`todayRoutingCost`) — which are read against the daily USD limits — stay on
     * the UTC day the limits are actually enforced on. The two agree only at UTC±0.
     */
    class UserOverviewRow(
        val userId: Int?,
        var todayProxyCost: Double = 0.0, var todayRoutingCost: Double = 0.0,
        var todayCost: Double = 0.0,
        var todayRequests: Long = 0, var todayTokens: Long = 0,
        var totalProxyCost: Double = 0.0, var totalRoutingCost: Double = 0.0,
        var totalRequests: Long = 0, var totalTokens: Long = 0,
        var lastActivity: Instant? = null,
    ) {
        val totalCost: Double get() = totalProxyCost + totalRoutingCost
    }

    /**
     * Per-user usage overview (all accounts, both datapaths) in a single pass over the events.
     * [startOfDay] bounds the viewer-day display counters; [startOfLimitDay] (UTC) bounds the
     * per-datapath costs the limits are read against — see [UserOverviewRow].
     */
    fun overviewByUser(startOfDay: Instant, startOfLimitDay: Instant = startOfDay): List<UserOverviewRow> = transaction {
        val acc = HashMap<Int?, UserOverviewRow>()
        fun of(key: List<Any?>) = (key[0] as Int?).let { id -> acc.getOrPut(id) { UserOverviewRow(id) } }
        aggregate(Scope(), Dim.USER, Dim.SOURCE).forEach { (key, sums) ->
            val r = of(key)
            if (key[1] == "routing") r.totalRoutingCost += sums.cost else r.totalProxyCost += sums.cost
            r.totalRequests += sums.requests; r.totalTokens += sums.totals.clean
            val last = sums.last
            if (last != null && r.lastActivity.let { it == null || last > it }) r.lastActivity = last
        }
        aggregate(Scope(since = startOfDay), Dim.USER).forEach { (key, sums) ->
            val r = of(key)
            r.todayCost += sums.cost; r.todayRequests += sums.requests; r.todayTokens += sums.totals.clean
        }
        aggregate(Scope(since = startOfLimitDay), Dim.USER, Dim.SOURCE).forEach { (key, sums) ->
            val r = of(key)
            if (key[1] == "routing") r.todayRoutingCost += sums.cost else r.todayProxyCost += sums.cost
        }
        acc.values.toList()
    }

    fun clearAll(): Int = UsageRollup.exclusive {
        transaction {
            UsageHourly.deleteAll()
            UsageEvents.deleteAll()
        }
    }

    fun clearUser(userId: Int): Int = UsageRollup.exclusive {
        transaction {
            UsageHourly.deleteWhere { UsageHourly.userId eq userId }
            UsageEvents.deleteWhere { UsageEvents.userId eq userId }
        }
    }

    /** A deleted user's history is kept, unattributed: the events and their hourly sums alike. */
    fun detachUser(userId: Int) {
        UsageEvents.update({ UsageEvents.userId eq userId }) { it[UsageEvents.userId] = null }
        UsageHourly.update({ UsageHourly.userId eq userId }) { it[UsageHourly.userId] = null }
    }

    fun recent(limit: Int = 200): List<UsageEventDto> = transaction {
        val names = accountNames()
        val personal = personalAccountIds()
        val base = UsageEvents.selectAll()
        val q = if (personal.isEmpty()) base else base.where { UsageEvents.accountId notInList personal }
        q.orderBy(UsageEvents.ts, SortOrder.DESC).limit(limit).map { it.toEventDto(names) }
    }

    fun summarySince(since: Instant): List<UsageSummaryDto> = transaction {
        val names = accountNames()
        aggregate(Scope(excludedAccounts = personalAccountIds(), since = since), Dim.ACCOUNT)
            .map { (key, t) ->
                val aid = key[0] as Int
                UsageSummaryDto(aid, names[aid], t.requests, t.input, t.output, t.cost)
            }
            .sortedByDescending { it.requests }
    }

    private fun accountNames() = Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.name] }

    private fun ResultRow.toEventDto(names: Map<Int, String>): UsageEventDto {
        val aid = this[UsageEvents.accountId]
        return UsageEventDto(
            id = this[UsageEvents.id], accountId = aid, accountName = names[aid],
            userId = this[UsageEvents.userId], ts = this[UsageEvents.ts].toString(),
            inputTokens = this[UsageEvents.inputTokens], outputTokens = this[UsageEvents.outputTokens],
            cacheReadTokens = this[UsageEvents.cacheReadTokens], cacheWriteTokens = this[UsageEvents.cacheWriteTokens],
            cacheWrite1hTokens = this[UsageEvents.cacheWrite1hTokens],
            webSearchRequests = this[UsageEvents.webSearchRequests], fast = this[UsageEvents.fast],
            cost = this[UsageEvents.cost], httpStatus = this[UsageEvents.httpStatus], model = this[UsageEvents.model],
            source = this[UsageEvents.sourceCol],
        )
    }
}
