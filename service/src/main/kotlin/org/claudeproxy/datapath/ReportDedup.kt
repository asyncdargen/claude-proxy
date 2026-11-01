package org.claudeproxy.datapath

import java.util.concurrent.ConcurrentHashMap

/**
 * Usage reports the service has already taken, by the gateway's per-report id.
 *
 * The gateway retries a report whose answer is slow, not only one that was lost — and the first
 * attempt had usually been recorded already, so each retry billed the request again (three rows
 * three seconds apart, ~3% of all requests before this existed). The id is claimed *before* the
 * report is processed, so a retry arriving while the first attempt is still writing is dropped too.
 *
 * In-process, like the rest of the service's caches: single instance by design, and a restart only
 * forgets ids whose retries are long over.
 */
object ReportDedup {
    private const val TTL_MS = 15 * 60_000L
    private val seen = ConcurrentHashMap<String, Long>()
    @Volatile private var lastPrune = 0L

    /** True the first time [id] is offered; reports without an id (old gateways) always pass. */
    fun firstSighting(id: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (id.isNullOrBlank()) return true
        prune(now)
        return seen.putIfAbsent(id, now) == null
    }

    private fun prune(now: Long) {
        if (now - lastPrune < 60_000L) return
        lastPrune = now
        seen.entries.removeIf { now - it.value > TTL_MS }
    }
}
