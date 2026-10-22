package org.claudeproxy.repo

import org.claudeproxy.db.WindowSnapshots
import org.claudeproxy.model.WindowKind
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// `coef` is the coefficient frozen at record time (defaults to 1.0 for any legacy row the
// startup backfill somehow missed), used by the ×coef-weighted chart aggregation.
data class WindowSample(val accountId: Int, val kind: String, val ts: Instant, val util: Double, val coef: Double, val resetAt: Instant? = null)

/**
 * Time series of observed window utilization. Writes are throttled to one point per
 * (account, window) per minute so bursts of proxied requests don't flood the table.
 */
object WindowSnapshotRepo {
    private val throttleMs = 60_000L
    private val lastTs = ConcurrentHashMap<String, Long>()

    fun record(accountId: Int, kind: WindowKind, utilization: Double, coefficient: Double, now: Instant = Instant.now(), resetAt: Instant? = null) {
        val key = "${System.identityHashCode(org.claudeproxy.db.Db.database)}:$accountId:${kind.code}"
        val last = lastTs[key]
        val nowMs = now.toEpochMilli()
        if (last != null && nowMs - last < throttleMs) return
        lastTs[key] = nowMs
        runCatching {
            transaction {
                WindowSnapshots.insert {
                    it[WindowSnapshots.accountId] = accountId
                    it[windowKind] = kind.code
                    it[WindowSnapshots.utilization] = utilization
                    it[WindowSnapshots.coefficient] = coefficient
                    it[WindowSnapshots.resetAt] = resetAt
                    it[ts] = now
                }
            }
        }
    }

    fun fetch(start: Instant, end: Instant): List<WindowSample> = transaction {
        WindowSnapshots.selectAll()
            .where { (WindowSnapshots.ts greaterEq start) and (WindowSnapshots.ts less end) }
            .map { WindowSample(it[WindowSnapshots.accountId], it[WindowSnapshots.windowKind], it[WindowSnapshots.ts], it[WindowSnapshots.utilization], it[WindowSnapshots.coefficient] ?: 1.0, it[WindowSnapshots.resetAt]) }
    }

    fun pruneOlderThan(cutoff: Instant): Int = transaction {
        WindowSnapshots.deleteWhere { ts less cutoff }
    }
}
