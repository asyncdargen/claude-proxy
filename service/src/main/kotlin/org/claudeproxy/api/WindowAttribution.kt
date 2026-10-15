package org.claudeproxy.api

import kotlinx.serialization.Serializable
import org.claudeproxy.repo.WindowSample
import java.time.Instant

@Serializable data class WindowAttributionResponse(val generatedAt: String, val accounts: List<AttributionAccount>)
@Serializable data class AttributionAccount(val accountId: Int, val accountName: String, val provider: String, val windows: List<AttributionWindow>)
@Serializable data class AttributionWindow(val kind: String, val periods: List<AttributionPeriod>)
@Serializable data class AttributionPeriod(
    val id: String, val startAt: String, val endAt: String?, val current: Boolean,
    val estimatedBounds: Boolean, val partial: Boolean, val observedAt: String, val total: Double,
    val points: List<AttributionPoint>, val contributors: List<AttributionContributor>,
)
@Serializable data class AttributionPoint(val ts: String, val total: Double, val values: Map<String, Double>)
@Serializable data class AttributionContributor(val key: String, val userId: Int?, val username: String, val amount: Double, val method: String)
data class AttributionUsage(val accountId: Int, val userId: Int?, val ts: Instant, val cost: Double, val costKnown: Boolean, val tokens: Long)

/** Estimates only: request completion timestamps do not exactly align with quota header observations. */
internal fun attributeWindow(
    accountId: Int, kind: String, samples: List<WindowSample>, events: List<AttributionUsage>,
    names: Map<Int, String>, liveReset: Instant?, now: Instant,
): List<AttributionPeriod> {
    val duration = if (kind == "5h") 18000L else 604800L
    val sorted = samples.filter { it.accountId == accountId && it.kind == kind && it.util.isFinite() && it.util >= 0 && it.ts <= now }
        .map { sample ->
            val reset = sample.resetAt?.takeIf { sample.ts < it && sample.ts >= it.minusSeconds(duration) }
            sample.copy(resetAt=reset)
        }.sortedBy { it.ts }
    if (sorted.isEmpty()) return emptyList()
    val requests = java.util.ArrayDeque(events.filter { it.accountId == accountId }.sortedBy { it.ts })
    val groups = mutableListOf(mutableListOf(sorted.first()))
    var groupReset = sorted.first().resetAt
    sorted.drop(1).forEach { sample ->
        val previous = groups.last().last()
        val crossedLiveStart = liveReset?.minusSeconds(duration)?.let { previous.ts < it && sample.ts >= it } ?: false
        val previousReset = groupReset
        val nextReset = sample.resetAt
        // Readings jitter: resets derived from "seconds until reset" move a little between samples.
        val slack = maxOf(300L, duration / 20)
        val resetChanged = previousReset != null && nextReset != null &&
            kotlin.math.abs(nextReset.epochSecond - previousReset.epochSecond) > slack
        val crossedRecorded = (previousReset?.let { sample.ts >= it.plusSeconds(slack) } ?: false) ||
            (nextReset?.let { previous.ts < it.minusSeconds(duration + slack) } ?: false)
        // A single window can never span longer than its duration: if it would, a reset was missed.
        val spanExceeded = sample.ts.epochSecond - groups.last().first().ts.epochSecond >= duration
        val boundary = resetChanged || crossedRecorded || crossedLiveStart || spanExceeded
        val legacyDrop = sample.resetAt == null && (
            (previous.util - sample.util >= 0.15 && sample.util <= previous.util * 0.5) ||
            (previous.util >= 0.05 && sample.util <= 0.02 && sample.util < previous.util * 0.5))
        if (boundary || legacyDrop) {
            groups += mutableListOf(sample)
            groupReset = sample.resetAt
        } else {
            val group = groups.last()
            // Idle zero readings carry no usage; keep only the latest as the baseline so a quiet
            // stretch does not pull the period start back by hours.
            if (sample.util == 0.0 && group.all { it.util == 0.0 }) group.clear()
            group += sample
            groupReset = groupReset ?: sample.resetAt
        }
    }
    // A past period that only ever read 0% is idle time, not a quota period worth showing.
    val kept = groups.filterIndexed { i, g -> i == groups.lastIndex || g.any { it.util > 0.0 } }
    val periods = kept.mapIndexed { index, group ->
        val reset = group.firstNotNullOfOrNull { it.resetAt } ?: liveReset?.takeIf {
            index == kept.lastIndex && group.first().ts >= it.minusSeconds(duration) && group.last().ts < it
        }
        val p = attributePeriod(accountId,kind,group,requests,names,reset,now)
        if (index < kept.lastIndex) p.copy(current=false,endAt=p.endAt ?: kept[index+1].first().ts.toString()) else p
    }
    // Past ends are bounded by the window length (an idle gap after a window is not part of it)
    // and by the next period's start (no overlapping periods).
    return periods.mapIndexed { i, p ->
        val next = periods.getOrNull(i + 1) ?: return@mapIndexed p
        val end = p.endAt?.let(Instant::parse) ?: return@mapIndexed p
        val bound = minOf(Instant.parse(p.startAt).plusSeconds(duration), Instant.parse(next.startAt))
        if (end > bound) p.copy(endAt = maxOf(bound, Instant.parse(p.observedAt)).toString(), estimatedBounds = true) else p
    }
}

private fun attributePeriod(
    accountId: Int, kind: String, sorted: List<WindowSample>, events: java.util.ArrayDeque<AttributionUsage>,
    names: Map<Int,String>, liveReset: Instant?, now: Instant,
): AttributionPeriod {
    val values = linkedMapOf("unattributed" to sorted.first().util)
    val methods = mutableMapOf<String,String>()
    var highWater = sorted.first().util
    val points = mutableListOf(AttributionPoint(sorted.first().ts.toString(),sorted.first().util,values.toMap()))
    sorted.zipWithNext().forEach { (a,b) ->
        while (events.isNotEmpty() && events.first.ts <= a.ts) events.removeFirst()
        val requests = mutableListOf<AttributionUsage>()
        while (events.isNotEmpty() && events.first.ts <= b.ts) requests += events.removeFirst()
        val cost = requests.all { it.costKnown && it.cost.isFinite() && it.cost > 0 }
        val weights = requests.groupBy { it.userId?.takeIf(names::containsKey)?.let { "user:$it" } ?: "unattributed" }
            .mapValues { (_, es) -> es.sumOf { if (cost) it.cost else it.tokens.coerceAtLeast(0).toDouble() } }
        val sum = weights.values.sum()
        val delta = (b.util-highWater).coerceAtLeast(0.0)
        highWater = maxOf(highWater,b.util)
        if (sum > 0) weights.forEach { (key,weight) ->
            values[key] = (values[key] ?: 0.0) + delta * weight/sum
            if (delta > 0 && weight > 0) methods[key] = if (!cost || methods[key] == "tokens") "tokens" else "cost"
        } else values["unattributed"] = values.getValue("unattributed") + delta
        // Keep the high-water ledger, but reconcile each displayed point to the actual reading.
        val scale = if (highWater > 0) b.util / highWater else 0.0
        points += AttributionPoint(b.ts.toString(),b.util,values.mapValues { it.value * scale })
    }
    val start = liveReset?.minusSeconds(if (kind == "5h") 18000 else 604800) ?: sorted.first().ts
    val current = liveReset?.let { now >= start && now < it }
        ?: (now.epochSecond - sorted.last().ts.epochSecond < if (kind == "5h") 18000 else 604800)
    return AttributionPeriod("$accountId:$kind:${sorted.first().ts}",start.toString(),liveReset?.toString(),current,
        liveReset == null,liveReset == null || sorted.first().ts > start || sorted.first().util > 0,sorted.last().ts.toString(),sorted.last().util,
        if (points.size <= 200) points else (0..199).map { points[it * (points.size-1) / 199] },
        points.last().values.filterValues { it > 0 }.map { (key,amount) ->
            val uid = key.removePrefix("user:").toIntOrNull()
            AttributionContributor(key,uid,uid?.let(names::get) ?: "Unattributed",amount,if (uid == null) "unattributed" else methods.getValue(key))
        })
}
