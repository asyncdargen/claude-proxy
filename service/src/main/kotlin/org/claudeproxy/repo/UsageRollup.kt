package org.claudeproxy.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.claudeproxy.db.Settings
import org.claudeproxy.db.UsageEvents
import org.claudeproxy.db.UsageHourly
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Running sums of a group of usage events: a rollup row, a read's group, or both merged. */
internal class UsageSums {
    var requests = 0L; var input = 0L; var output = 0L; var cacheRead = 0L; var cacheWrite = 0L; var cost = 0.0
    var last: Instant? = null

    fun add(requests: Long, input: Long, output: Long, cacheRead: Long, cacheWrite: Long, cost: Double, last: Instant?) {
        this.requests += requests; this.input += input; this.output += output
        this.cacheRead += cacheRead; this.cacheWrite += cacheWrite; this.cost += cost
        if (last != null && this.last.let { it == null || last > it }) this.last = last
    }

    val totals: Totals get() = Totals(requests, input, output, cacheRead, cacheWrite, cost)
}

/**
 * Keeps [UsageHourly] up to date from [UsageEvents]. Each pass rebuilds the hours from a little before
 * its watermark up to the last closed hour, in one transaction per day of events, then moves the
 * watermark. Idempotent, so a crash, a restart or a rollback to code that never writes the rollup
 * only delays it: the next pass picks up from the watermark. Reads never depend on it being current
 * ([UsageRepo] adds the events past the watermark), only their cost does.
 */
object UsageRollup {
    private val log = LoggerFactory.getLogger("UsageRollup")
    private const val THROUGH_KEY = "usage_rollup_through"
    /** Hours re-read behind the watermark on every pass: an event stamped just before an hour closed can commit after the pass that closed it. */
    private val OVERLAP: Duration = Duration.ofHours(2)
    /** One transaction per day while catching up, so a first pass over a long history holds neither a long transaction nor that history in memory. */
    private val CHUNK: Duration = Duration.ofDays(1)
    private val INTERVAL: Duration = Duration.ofMinutes(5)
    private val lock = Any()

    /**
     * Run [block] with no pass in progress. Every change to existing events (a clear, a user detached)
     * goes through here and mirrors itself on the rollup in the same transaction: a pass reading the
     * events while the change commits would otherwise write the old rows back.
     */
    fun <T> exclusive(block: () -> T): T = synchronized(lock, block)

    /** The first instant the rollup does not cover, on an hour boundary; null before the first pass. Read inside the caller's transaction. */
    fun through(): Instant? =
        Settings.selectAll().where { Settings.key eq THROUGH_KEY }.firstOrNull()?.let { Instant.parse(it[Settings.value]) }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { rollUp() }.onFailure { log.warn("usage rollup failed: {}", it.message) }
            delay(INTERVAL.toMillis())
        }
    }

    /** Brings the rollup up to the last hour closed before [now]. */
    fun rollUp(now: Instant = Instant.now()) {
        val target = now.truncatedTo(ChronoUnit.HOURS)
        while (exclusive { transaction { pass(target) } }) Unit
    }

    /** One chunk. True while there is more to catch up. */
    private fun pass(target: Instant): Boolean {
        val through = through()
        if (through != null && through >= target) return false
        val start = through ?: firstEventHour() ?: run { setThrough(target); return false }
        val from = if (through == null) start else through.minus(OVERLAP)
        val to = minOf(start.plus(CHUNK), target)
        UsageHourly.deleteWhere { (UsageHourly.hour greaterEq from) and (UsageHourly.hour less to) }
        val groups = HashMap<List<Any?>, UsageSums>()
        UsageEvents.select(
            UsageEvents.ts, UsageEvents.accountId, UsageEvents.userId, UsageEvents.model, UsageEvents.sourceCol,
            UsageEvents.tokenId, UsageEvents.inputTokens, UsageEvents.outputTokens, UsageEvents.cacheReadTokens,
            UsageEvents.cacheWriteTokens, UsageEvents.cost,
        ).where { (UsageEvents.ts greaterEq from) and (UsageEvents.ts less to) }.forEach { row ->
            val ts = row[UsageEvents.ts]
            val key = listOf(
                ts.truncatedTo(ChronoUnit.HOURS), row[UsageEvents.accountId], row[UsageEvents.userId], row[UsageEvents.model],
                row[UsageEvents.sourceCol], row[UsageEvents.tokenId],
            )
            groups.getOrPut(key) { UsageSums() }.add(
                1, row[UsageEvents.inputTokens], row[UsageEvents.outputTokens], row[UsageEvents.cacheReadTokens],
                row[UsageEvents.cacheWriteTokens], row[UsageEvents.cost], ts,
            )
        }
        UsageHourly.batchInsert(groups.entries, shouldReturnGeneratedValues = false) { (key, sums) ->
            this[UsageHourly.hour] = key[0] as Instant
            this[UsageHourly.accountId] = key[1] as Int
            this[UsageHourly.userId] = key[2] as Int?
            this[UsageHourly.model] = key[3] as String?
            this[UsageHourly.sourceCol] = key[4] as String
            this[UsageHourly.tokenId] = key[5] as Int?
            this[UsageHourly.requests] = sums.requests
            this[UsageHourly.inputTokens] = sums.input
            this[UsageHourly.outputTokens] = sums.output
            this[UsageHourly.cacheReadTokens] = sums.cacheRead
            this[UsageHourly.cacheWriteTokens] = sums.cacheWrite
            this[UsageHourly.cost] = sums.cost
            this[UsageHourly.lastTs] = sums.last!!
        }
        setThrough(to)
        return to < target
    }

    private fun firstEventHour(): Instant? {
        val first = UsageEvents.ts.min()
        return UsageEvents.select(first).firstOrNull()?.get(first)?.truncatedTo(ChronoUnit.HOURS)
    }

    private fun setThrough(instant: Instant) {
        Settings.upsert {
            it[key] = THROUGH_KEY
            it[value] = instant.toString()
        }
    }

    /** Whether every offset [zone] takes in [from, to) is a whole number of hours, so an hour of the rollup falls on a single local day. */
    fun wholeHours(zone: ZoneId, from: Instant, to: Instant): Boolean {
        val rules = zone.rules
        if (rules.getOffset(from).totalSeconds % 3600 != 0) return false
        var t = from
        while (true) {
            val next = rules.nextTransition(t) ?: return true
            if (next.instant >= to) return true
            if (next.offsetAfter.totalSeconds % 3600 != 0) return false
            t = next.instant
        }
    }
}
