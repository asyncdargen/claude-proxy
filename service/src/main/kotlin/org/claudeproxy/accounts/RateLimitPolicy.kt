package org.claudeproxy.accounts

import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import java.time.Instant

/**
 * Decides whether an upstream 429 takes the whole account out of rotation.
 *
 * Anthropic answers 429 for more than an exhausted subscription window: a per-model or burst
 * limit (seen on `claude-haiku-5-5` while the account's 5h window stood at 29%) rejects one model
 * while every other model on the same account keeps serving. Parking the account for such a 429
 * starved all models; with a single account in the pool, every request in the next minute got
 * the gateway's "no account" error. So the account is parked only when its own windows show it
 * is exhausted (a REJECTED window or utilization at [FULL]); any other 429 is passed back to the
 * client, which backs off for that request alone.
 */
object RateLimitPolicy {
    /** Utilization from which a window counts as spent. */
    const val FULL = 0.95

    /** Default park when the account is exhausted but neither retry-after nor a reset is known. */
    private const val FALLBACK_SECONDS = 60L

    /** Until when to park the account after a 429, or null when the 429 is not account-wide. */
    fun parkUntil(headers: Map<String, String>, limit: LimitState, now: Instant = Instant.now()): Instant? {
        val exhausted = limit.windows.values.filter {
            it.status == LimitStatus.REJECTED || (it.usageFraction() ?: 0.0) >= FULL
        }
        if (exhausted.isEmpty()) return null
        retryAfter(headers, now)?.let { return it }
        return exhausted.mapNotNull { it.resetAt }.filter { it.isAfter(now) }.minOrNull()
            ?: now.plusSeconds(FALLBACK_SECONDS)
    }

    /** `retry-after` in delta-seconds form, as an instant. */
    fun retryAfter(headers: Map<String, String>, now: Instant = Instant.now()): Instant? =
        headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value
            ?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { now.plusSeconds(it) }

    /** Non-secret headers worth logging for a 429: retry-after, request-id and rate-limit state. */
    fun describe(headers: Map<String, String>): String =
        headers.entries
            .filter { val k = it.key.lowercase(); k == "retry-after" || k == "request-id" || k.startsWith("anthropic-ratelimit") }
            .sortedBy { it.key.lowercase() }
            .joinToString(", ") { "${it.key.lowercase()}=${it.value}" }
            .ifEmpty { "none" }
}
