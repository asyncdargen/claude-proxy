package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.Accounts
import org.claudeproxy.db.UsageEvents
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.compoundAnd
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

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

    private val requestCount = UsageEvents.id.count()
    private val inputSum = UsageEvents.inputTokens.sum()
    private val outputSum = UsageEvents.outputTokens.sum()
    private val cacheReadSum = UsageEvents.cacheReadTokens.sum()
    private val cacheWriteSum = UsageEvents.cacheWriteTokens.sum()
    private val costSum = UsageEvents.cost.sum()
    private val lastTs = UsageEvents.ts.max()

    /**
     * Count and sums computed by the database, one row per group of [keys] (one row in all when
     * there are none). Every all-time figure goes through here: loading the events to add them up
     * in the JVM made each stats read cost the whole table, and the table grows by every request.
     */
    private fun aggregate(where: List<Op<Boolean>>, vararg keys: Expression<*>): List<Pair<ResultRow, Totals>> {
        var query = UsageEvents.select(listOf(*keys, requestCount, inputSum, outputSum, cacheReadSum, cacheWriteSum, costSum, lastTs))
        if (where.isNotEmpty()) query = query.where(where.compoundAnd())
        if (keys.isNotEmpty()) query = query.groupBy(*keys)
        return query.map { row ->
            row to Totals(
                row[requestCount], row[inputSum] ?: 0, row[outputSum] ?: 0,
                row[cacheReadSum] ?: 0, row[cacheWriteSum] ?: 0, row[costSum] ?: 0.0,
            )
        }
    }

    private fun excludingPersonal(): Op<Boolean>? =
        personalAccountIds().takeIf { it.isNotEmpty() }?.let { UsageEvents.accountId notInList it }

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
        aggregate(
            listOfNotNull(
                UsageEvents.userId eq userId,
                since?.let { UsageEvents.ts greaterEq it },
                personal.takeIf { it.isNotEmpty() }?.let { UsageEvents.accountId notInList it },
                source?.let { UsageEvents.sourceCol eq it },
            ),
        ).single().second
    }

    /** Per-account totals for a specific set of accounts (used by the personal "My Accounts" view). */
    fun totalsForAccounts(ids: Set<Int>): Map<Int, Totals> = transaction {
        if (ids.isEmpty()) return@transaction emptyMap()
        aggregate(listOf(UsageEvents.accountId inList ids), UsageEvents.accountId)
            .associate { (row, totals) -> row[UsageEvents.accountId] to totals }
    }

    private fun perModelOf(where: List<Op<Boolean>>): List<ModelUsageDto> =
        aggregate(where, UsageEvents.model)
            .map { (row, totals) -> ModelUsageDto(row[UsageEvents.model], totals.requests, totals.clean, totals.cost) }
            .sortedByDescending { it.cost }

    /**
     * Per-model breakdown for a single user; [since] limits to events at/after that instant,
     * [source] ("proxy" | "routing") restricts to one datapath.
     */
    fun userPerModel(userId: Int, since: Instant? = null, source: String? = null): List<ModelUsageDto> = transaction {
        perModelOf(
            listOfNotNull(
                UsageEvents.userId eq userId,
                since?.let { UsageEvents.ts greaterEq it },
                source?.let { UsageEvents.sourceCol eq it },
            ),
        )
    }

    /** Pool-wide per-model breakdown (excludes personal accounts); [since] limits the window. */
    fun perModel(since: Instant? = null): List<ModelUsageDto> = transaction {
        perModelOf(listOfNotNull(since?.let { UsageEvents.ts greaterEq it }, excludingPersonal()))
    }

    fun recentForUser(userId: Int, limit: Int = 100, source: String? = null): List<UsageEventDto> = transaction {
        val names = accountNames()
        val q = if (source == null) UsageEvents.selectAll().where { UsageEvents.userId eq userId }
        else UsageEvents.selectAll().where { (UsageEvents.userId eq userId) and (UsageEvents.sourceCol eq source) }
        q.orderBy(UsageEvents.ts, SortOrder.DESC).limit(limit).map { it.toEventDto(names) }
    }

    fun totalsPerAccount(): Map<Int, Totals> = transaction {
        aggregate(listOfNotNull(excludingPersonal()), UsageEvents.accountId)
            .associate { (row, totals) -> row[UsageEvents.accountId] to totals }
    }

    fun poolTotals(): Totals = transaction {
        aggregate(listOfNotNull(excludingPersonal())).single().second
    }

    /**
     * Daily (account, day) buckets in [start, end), bucketed by date in [zone]. Excludes personal
     * accounts. [zone] is the viewer's local timezone, so a "day" matches what they see on a clock.
     */
    fun dailyBuckets(start: Instant, end: Instant, zone: ZoneId = ZoneOffset.UTC): List<DailyBucketDto> = transaction {
        val personal = personalAccountIds()
        val acc = HashMap<Pair<Int, String>, DoubleArray>() // (accountId, date) -> [cost, requests, tokens]
        UsageEvents.selectAll()
            .where { (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                if (row[UsageEvents.accountId] in personal) return@forEach
                val date = row[UsageEvents.ts].atZone(zone).toLocalDate().toString()
                val a = acc.getOrPut(row[UsageEvents.accountId] to date) { DoubleArray(3) }
                a[0] += row[UsageEvents.cost]
                a[1] += 1
                a[2] += row[UsageEvents.inputTokens] + row[UsageEvents.outputTokens] + row[UsageEvents.cacheReadTokens] + row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> DailyBucketDto(k.first, k.second, a[0], a[1].toLong(), a[2].toLong()) }
    }

    /** Daily (account, day) buckets for one user's own usage in [start, end), across ALL accounts (incl. personal). */
    fun dailyBucketsForUser(
        userId: Int, start: Instant, end: Instant, source: String? = null, zone: ZoneId = ZoneOffset.UTC,
    ): List<DailyBucketDto> = transaction {
        val acc = HashMap<Pair<Int, String>, DoubleArray>() // (accountId, date) -> [cost, requests, tokens]
        UsageEvents.selectAll()
            .where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                if (source != null && row[UsageEvents.sourceCol] != source) return@forEach
                val date = row[UsageEvents.ts].atZone(zone).toLocalDate().toString()
                val a = acc.getOrPut(row[UsageEvents.accountId] to date) { DoubleArray(3) }
                a[0] += row[UsageEvents.cost]
                a[1] += 1
                a[2] += row[UsageEvents.inputTokens] + row[UsageEvents.outputTokens] + row[UsageEvents.cacheReadTokens] + row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> DailyBucketDto(k.first, k.second, a[0], a[1].toLong(), a[2].toLong()) }
    }

    /** Daily (account, model, day) token buckets for one user's own usage in [start, end), across ALL accounts (incl. personal). */
    fun tokenBucketsForUser(
        userId: Int, start: Instant, end: Instant, source: String? = null, zone: ZoneId = ZoneOffset.UTC,
    ): List<TokenBucketDto> = transaction {
        val acc = HashMap<Triple<Int, String?, String>, LongArray>() // (accountId, model, date) -> [in, out, cacheRead, cacheWrite]
        UsageEvents.selectAll()
            .where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                if (source != null && row[UsageEvents.sourceCol] != source) return@forEach
                val date = row[UsageEvents.ts].atZone(zone).toLocalDate().toString()
                val a = acc.getOrPut(Triple(row[UsageEvents.accountId], row[UsageEvents.model], date)) { LongArray(4) }
                a[0] += row[UsageEvents.inputTokens]
                a[1] += row[UsageEvents.outputTokens]
                a[2] += row[UsageEvents.cacheReadTokens]
                a[3] += row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> TokenBucketDto(k.first, k.second, k.third, a[0], a[1], a[2], a[3]) }
    }

    /**
     * Daily (inbound token, day) buckets of one user's usage on one datapath ("proxy" | "routing")
     * in [start, end), bucketed by date in [zone]. All accounts count (incl. personal) — this feeds
     * the user's own per-token view. tokenId null groups pre-migration/unattributed rows.
     */
    fun tokenDailyBucketsForUser(
        userId: Int, source: String, start: Instant, end: Instant, zone: ZoneId = ZoneOffset.UTC,
    ): List<TokenIdDailyBucketDto> = transaction {
        val acc = HashMap<Pair<Int?, String>, DoubleArray>() // (tokenId, date) -> [cost, requests, tokens]
        UsageEvents.selectAll()
            .where { (UsageEvents.userId eq userId) and (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                if (row[UsageEvents.sourceCol] != source) return@forEach
                val date = row[UsageEvents.ts].atZone(zone).toLocalDate().toString()
                val a = acc.getOrPut(row[UsageEvents.tokenId] to date) { DoubleArray(3) }
                a[0] += row[UsageEvents.cost]
                a[1] += 1
                a[2] += row[UsageEvents.inputTokens] + row[UsageEvents.outputTokens] + row[UsageEvents.cacheReadTokens] + row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> TokenIdDailyBucketDto(k.first, k.second, a[0], a[1].toLong(), a[2].toLong()) }
    }

    /** All-time totals per inbound token for one user's usage on one datapath ("proxy" | "routing"). */
    fun totalsPerTokenForUser(userId: Int, source: String): Map<Int?, Totals> = transaction {
        aggregate(listOf(UsageEvents.userId eq userId, UsageEvents.sourceCol eq source), UsageEvents.tokenId)
            .associate { (row, totals) -> row[UsageEvents.tokenId] to totals }
    }

    /** Daily (account, model, day) token buckets in [start, end), bucketed by date in [zone], kinds kept apart. Excludes personal accounts. */
    fun tokenBuckets(start: Instant, end: Instant, zone: ZoneId = ZoneOffset.UTC): List<TokenBucketDto> = transaction {
        val personal = personalAccountIds()
        val acc = HashMap<Triple<Int, String?, String>, LongArray>() // (accountId, model, date) -> [in, out, cacheRead, cacheWrite]
        UsageEvents.selectAll()
            .where { (UsageEvents.ts greaterEq start) and (UsageEvents.ts less end) }
            .forEach { row ->
                if (row[UsageEvents.accountId] in personal) return@forEach
                val date = row[UsageEvents.ts].atZone(zone).toLocalDate().toString()
                val a = acc.getOrPut(Triple(row[UsageEvents.accountId], row[UsageEvents.model], date)) { LongArray(4) }
                a[0] += row[UsageEvents.inputTokens]
                a[1] += row[UsageEvents.outputTokens]
                a[2] += row[UsageEvents.cacheReadTokens]
                a[3] += row[UsageEvents.cacheWriteTokens]
            }
        acc.map { (k, a) -> TokenBucketDto(k.first, k.second, k.third, a[0], a[1], a[2], a[3]) }
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
        fun of(row: ResultRow) = acc.getOrPut(row[UsageEvents.userId]) { UserOverviewRow(row[UsageEvents.userId]) }
        aggregate(emptyList(), UsageEvents.userId, UsageEvents.sourceCol).forEach { (row, totals) ->
            val r = of(row)
            if (row[UsageEvents.sourceCol] == "routing") r.totalRoutingCost += totals.cost else r.totalProxyCost += totals.cost
            r.totalRequests += totals.requests; r.totalTokens += totals.clean
            val last = row[lastTs]
            if (last != null && r.lastActivity.let { it == null || last > it }) r.lastActivity = last
        }
        aggregate(listOf(UsageEvents.ts greaterEq startOfDay), UsageEvents.userId).forEach { (row, totals) ->
            val r = of(row)
            r.todayCost += totals.cost; r.todayRequests += totals.requests; r.todayTokens += totals.clean
        }
        aggregate(listOf(UsageEvents.ts greaterEq startOfLimitDay), UsageEvents.userId, UsageEvents.sourceCol).forEach { (row, totals) ->
            val r = of(row)
            if (row[UsageEvents.sourceCol] == "routing") r.todayRoutingCost += totals.cost else r.todayProxyCost += totals.cost
        }
        acc.values.toList()
    }

    fun clearAll(): Int = transaction { UsageEvents.deleteAll() }
    fun clearUser(userId: Int): Int = transaction { UsageEvents.deleteWhere { UsageEvents.userId eq userId } }

    fun recent(limit: Int = 200): List<UsageEventDto> = transaction {
        val names = accountNames()
        val personal = personalAccountIds()
        val base = UsageEvents.selectAll()
        val q = if (personal.isEmpty()) base else base.where { UsageEvents.accountId notInList personal }
        q.orderBy(UsageEvents.ts, SortOrder.DESC).limit(limit).map { it.toEventDto(names) }
    }

    fun summarySince(since: Instant): List<UsageSummaryDto> = transaction {
        val names = accountNames()
        aggregate(listOfNotNull(UsageEvents.ts greaterEq since, excludingPersonal()), UsageEvents.accountId)
            .map { (row, t) ->
                val aid = row[UsageEvents.accountId]
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
