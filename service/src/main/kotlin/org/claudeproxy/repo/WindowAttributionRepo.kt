package org.claudeproxy.repo

import org.claudeproxy.api.*
import org.claudeproxy.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Fixed query count: shared accounts, limits, snapshots, events and display names once each.
 * The extra week recovers the beginning of a period intersecting the requested range. No
 * credential tables or runtime account objects are read/serialized by this endpoint.
 */
object WindowAttributionRepo {
    fun fetch(days: Int, now: Instant = Instant.now()): WindowAttributionResponse = transaction {
        require(days in 1..90)
        val start = now.minus(days.toLong(),ChronoUnit.DAYS)
        val lookback = start.minus(7,ChronoUnit.DAYS)
        val accounts = Accounts.select(Accounts.id,Accounts.name)
            .where { Accounts.ownerId.isNull() }.orderBy(Accounts.id).toList()
        val ids = accounts.map { it[Accounts.id] }
        if (ids.isEmpty()) return@transaction WindowAttributionResponse(now.toString(),emptyList())
        val limits = AccountLimits.selectAll().where { AccountLimits.accountId inList ids }.toList()
        val snapshots = WindowSnapshots.selectAll().where {
            (WindowSnapshots.accountId inList ids) and (WindowSnapshots.ts greaterEq lookback) and (WindowSnapshots.ts lessEq now)
        }.map { WindowSample(it[WindowSnapshots.accountId],it[WindowSnapshots.windowKind],it[WindowSnapshots.ts],
            it[WindowSnapshots.utilization],it[WindowSnapshots.coefficient] ?: 1.0,it[WindowSnapshots.resetAt]) }.toMutableList()
        limits.forEach { row ->
            val ts = row[AccountLimits.updatedAt]
            // Only reported utilization, like snapshots: remaining/limit on API keys is a per-minute rate limit.
            val util = row[AccountLimits.utilization]
            // Unknown observation time is not "now"; an unavailable quota is not zero usage.
            if (ts != null && ts >= lookback && ts <= now && util != null) {
                snapshots += WindowSample(row[AccountLimits.accountId],row[AccountLimits.windowKind],ts,util,1.0,row[AccountLimits.resetAt])
            }
        }
        val byWindow = snapshots.groupBy { it.accountId to it.kind }.mapValues { (_,points) ->
            // The live row is appended last and supplies a reset timestamp if the snapshot was legacy.
            points.groupBy { it.ts }.values.map { same -> same.firstOrNull { it.resetAt != null } ?: same.last() }
        }
        // Cost counts as known only when positive (checked in attributeWindow); otherwise tokens are used.
        val events = UsageEvents.select(UsageEvents.accountId,UsageEvents.userId,UsageEvents.ts,UsageEvents.cost,
            UsageEvents.inputTokens,UsageEvents.outputTokens,UsageEvents.cacheReadTokens,UsageEvents.cacheWriteTokens)
            .where { (UsageEvents.accountId inList ids) and (UsageEvents.ts greaterEq lookback) and (UsageEvents.ts lessEq now) }
            .map { AttributionUsage(it[UsageEvents.accountId],it[UsageEvents.userId],it[UsageEvents.ts],it[UsageEvents.cost],true,
                it[UsageEvents.inputTokens].coerceAtLeast(0)+it[UsageEvents.outputTokens].coerceAtLeast(0)+
                    it[UsageEvents.cacheReadTokens].coerceAtLeast(0)+it[UsageEvents.cacheWriteTokens].coerceAtLeast(0)) }
            .groupBy { it.accountId }
        val names = Users.select(Users.id,Users.username).associate { it[Users.id] to it[Users.username] }
        val resets = limits.associate { (it[AccountLimits.accountId] to it[AccountLimits.windowKind]) to it[AccountLimits.resetAt] }
        WindowAttributionResponse(now.toString(),accounts.map { account ->
            val id = account[Accounts.id]
            AttributionAccount(id,account[Accounts.name],"ANTHROPIC",listOf("5h","7d").map { kind ->
                val periods = attributeWindow(id,kind,byWindow[id to kind].orEmpty(),events[id].orEmpty(),names,resets[id to kind],now)
                    .filter { Instant.parse(it.observedAt) >= start }
                AttributionWindow(kind,periods)
            })
        })
    }
}
