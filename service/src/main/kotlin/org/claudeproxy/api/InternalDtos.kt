package org.claudeproxy.api

import kotlinx.serialization.Serializable
import org.claudeproxy.repo.BilledUsage

/**
 * Wire DTOs for the private `/internal/` control API consumed by the Go gateway.
 * The gateway calls `/internal/resolve` once per inbound request and `/internal/usage`
 * after each upstream attempt. All selection/crypto/limit logic stays in the service.
 */

@Serializable
data class ResolveRequest(
    val token: String,
    val method: String,
    val path: String,
    // datapath: "proxy" (Claude Code, default) or "routing" (OpenAI/Anthropic gateways).
    val source: String = "proxy",
    // Gateway-generated id for this inbound request. When present, the resolve opens an
    // active-session entry that /internal/session-end closes. Absent = no liveness tracking
    // (older gateways), which costs nothing but the "active now" gauge.
    val requestId: String? = null,
)

/** Closes the active session opened by a resolve carrying the same [requestId]. */
@Serializable
data class SessionEndRequest(val requestId: String)

/**
 * One try-list entry: an account already ordered by the pool, carrying the *decrypted*
 * upstream auth headers so the gateway performs no crypto. Reset epochs feed the
 * gateway's mid-stream "retry in Ns" message.
 */
@Serializable
data class CandidateDto(
    val accountId: Int,
    val type: String,
    val deviceId: String? = null,
    // OAuth accounts only: the Anthropic account uuid for metadata.user_id.account_uuid
    val accountUuid: String? = null,
    val authHeaders: Map<String, String> = emptyMap(),
    val fiveHourResetEpoch: Long? = null,
    val weeklyResetEpoch: Long? = null,
)

@Serializable
data class ResolveResponse(
    val userId: Int?,
    // id of the inbound token (namespace by datapath: proxy_tokens / routing_tokens);
    // the gateway echoes it back in each UsageReport so usage rows attribute per token.
    val tokenId: Int? = null,
    val overLimit: Boolean,
    val dailyLimitUsd: Double? = null,
    val usedUsd: Double? = null,
    val candidates: List<CandidateDto> = emptyList(),
    // routing only: static per-token system prompt the gateway injects ahead of client system.
    val systemPrompt: String? = null,
    // proxy only: model forced by the token. The gateway writes it over the body's `model`; a
    // `[1m]` suffix (Claude Code's notation) is stripped there and becomes the 1M-context beta.
    val defaultModel: String? = null,
    // free path (token counting, model listing): the gateway echoes this back on the usage
    // report so a successful zero-token attempt stays out of the statistics.
    val free: Boolean = false,
    // empty candidates because the reachable accounts are parked: seconds until one frees up.
    // The gateway answers 429 + Retry-After for it; null keeps the 503 "no account" answer.
    val retryAfterSeconds: Long? = null,
)

/**
 * One upstream attempt's outcome, reported by the gateway. Doubles as the domain input
 * for [org.claudeproxy.datapath.DatapathService.applyOutcome] — no separate mapping type.
 */
@Serializable
data class UsageReport(
    val accountId: Int,
    val userId: Int? = null,
    // inbound token that authenticated the request, from the resolve response.
    val tokenId: Int? = null,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    // total cache-write tokens (both TTLs)
    val cacheWrite: Long = 0,
    // the 1-hour-TTL slice of [cacheWrite]. Anthropic bills it at 2× input against 1.25× for the
    // default 5-minute TTL, and Claude Code ≥2.1 writes its main-loop prefix with ttl:"1h".
    val cacheWrite1h: Long = 0,
    // server-side tool calls billed per invocation rather than per token (web search).
    val webSearchRequests: Long = 0,
    val webFetchRequests: Long = 0,
    // response served in fast mode (`speed: "fast"`) — a premium price tier on the same model.
    val fast: Boolean = false,
    val status: Int,
    val model: String? = null,
    // Nullable on purpose: an attempt that never got a response (transport error, or a 502 the
    // gateway synthesized itself) has no headers to report, and Go marshals that nil map as
    // `null`. Refusing it cost the whole report — the failed attempt vanished from the stats and
    // its account bookkeeping never ran — and buried a stack trace in the log for every one.
    val ratelimitHeaders: Map<String, String>? = null,
    // datapath that produced this attempt: "proxy" (default) or "routing".
    val source: String = "proxy",
    // the request ran on a free path (token counting, model listing) — echoed back from the
    // resolve so a successful zero-token attempt can be left out of the stats.
    val free: Boolean = false,
    // MCP tool invocations seen in the response (client-side "mcp__…" tool_use blocks and
    // server-side mcp_tool_use blocks, both keyed by "mcp__server__tool"), by tool name.
    val mcpCalls: Map<String, Long> = emptyMap(),
) {
    /** 1h slice clamped to the total it belongs to, so pricing can never exceed the writes. */
    private val cacheWrite1hClamped: Long get() = cacheWrite1h.coerceIn(0, cacheWrite.coerceAtLeast(0))

    /** The 5-minute-TTL slice: whatever of the total the 1h slice didn't claim. */
    private val cacheWrite5m: Long get() = (cacheWrite.coerceAtLeast(0) - cacheWrite1hClamped)

    /** The priced view of this attempt. */
    fun billed(): BilledUsage = BilledUsage(
        input = input.coerceAtLeast(0),
        output = output.coerceAtLeast(0),
        cacheRead = cacheRead.coerceAtLeast(0),
        cacheWrite5m = cacheWrite5m,
        cacheWrite1h = cacheWrite1hClamped,
        webSearchRequests = webSearchRequests.coerceAtLeast(0),
        fast = fast,
    )
}
