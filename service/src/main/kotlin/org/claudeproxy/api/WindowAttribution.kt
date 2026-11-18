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

/** Headroom over the period's average rise-per-spend: model mix and caching move the real rate. */
private const val CREDIT_SLACK = 1.25
/** How long a request's not-yet-visible share may still claim a later step of the reading. */
private const val CREDIT_CARRY_SECONDS = 1800L

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
    class Interval(val b: WindowSample, val cost: Boolean, val weights: Map<String,Double>)
    val intervals = sorted.zipWithNext().map { (a,b) ->
        while (events.isNotEmpty() && events.first.ts <= a.ts) events.removeFirst()
        val requests = mutableListOf<AttributionUsage>()
        while (events.isNotEmpty() && events.first.ts <= b.ts) requests += events.removeFirst()
        val cost = requests.all { it.costKnown && it.cost.isFinite() && it.cost > 0 }
        Interval(b,cost,requests.groupBy { it.userId?.takeIf(names::containsKey)?.let { "user:$it" } ?: "unattributed" }
            .mapValues { (_, es) -> es.sumOf { if (cost) it.cost else it.tokens.coerceAtLeast(0).toDouble() } })
    }
    // The window also moves for usage the proxy never saw (the subscription used elsewhere) and
    // jumps when Anthropic corrects its counter. Handing a whole step to whoever had a request in
    // that gap once billed a quarter of an Ultra week to two Haiku calls worth $0.0014. A request
    // can claim at most what the period's own rate (rise per unit of spend) says it is worth.
    val rise = (sorted.maxOf { it.util } - sorted.first().util).coerceAtLeast(0.0)
    val costUnits = intervals.filter { it.cost }.sumOf { it.weights.values.sum() }
    val tokenUnits = intervals.filter { !it.cost }.sumOf { it.weights.values.sum() }
    fun rate(cost: Boolean) = (if (cost) costUnits else tokenUnits).let { if (it > 0) rise/it else 0.0 }
    // Readings come in 0.01 steps and trail the requests that caused them, so a request's unused
    // share stays claimable for a while instead of the next step landing in "unattributed".
    class Credit(val earned: Instant, var amount: Double, val tokens: Boolean)
    val credit = mutableMapOf<String,ArrayDeque<Credit>>()
    fun award(key: String, amount: Double, tokens: Boolean) {
        if (amount <= 0) return
        values[key] = (values[key] ?: 0.0) + amount
        methods[key] = if (tokens || methods[key] == "tokens") "tokens" else "cost"
    }
    intervals.forEach { interval ->
        val b = interval.b
        credit.values.forEach { q -> q.removeAll { it.earned < b.ts.minusSeconds(CREDIT_CARRY_SECONDS) || it.amount <= 0 } }
        var remaining = (b.util-highWater).coerceAtLeast(0.0)
        highWater = maxOf(highWater,b.util)
        // This interval's own requests come first, then credit carried from earlier ones.
        val fresh = interval.weights.mapValues { it.value * rate(interval.cost) * CREDIT_SLACK }.filterValues { it > 0 }
        val freshSum = fresh.values.sum()
        val fromFresh = minOf(remaining,freshSum)
        fresh.forEach { (key,share) ->
            val taken = if (freshSum > 0) fromFresh * share/freshSum else 0.0
            award(key,taken,!interval.cost)
            if (share > taken) credit.getOrPut(key) { ArrayDeque() } += Credit(b.ts,share-taken,!interval.cost)
        }
        remaining -= fromFresh
        // Someone still sending requests is the likelier owner of a trailing step than someone idle.
        for (active in listOf(true,false)) {
            val carried = credit.filterKeys { (it in interval.weights) == active }
                .mapValues { (_,q) -> q.sumOf { it.amount } }.filterValues { it > 0 }
            val carriedSum = carried.values.sum()
            val fromCarried = minOf(remaining,carriedSum)
            if (fromCarried <= 0) continue
            carried.forEach { (key,held) ->
                var owed = fromCarried * held/carriedSum
                var tokens = false
                for (c in credit.getValue(key)) {
                    if (owed <= 0) break
                    val used = minOf(owed,c.amount)
                    c.amount -= used; owed -= used
                    tokens = tokens || c.tokens
                }
                award(key,fromCarried * held/carriedSum,tokens)
            }
            remaining -= fromCarried
        }
        values["unattributed"] = values.getValue("unattributed") + remaining
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
