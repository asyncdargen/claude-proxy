package org.claudeproxy.accounts

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.claudeproxy.envOrProp
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.proxy.Http
import org.slf4j.LoggerFactory

/**
 * Actively refreshes an account's live limit state by making a minimal upstream request
 * and reading its rate-limit headers.
 *
 * For subscription (OAuth) accounts the request must look like Claude Code — same auth,
 * beta flag and a Claude Code system prompt — otherwise Anthropic rejects it (401/403).
 * We therefore send a 1-token `/v1/messages` call framed like Claude Code. It costs a
 * negligible amount of quota but works for both OAuth and API-key accounts. A dedicated
 * limits endpoint can be substituted via `LIMIT_PROBE_PATH` if one becomes known.
 */
class LimitProbe(
    private val pool: AccountPool,
    private val upstreamBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger("LimitProbe")

    private val probePath: String get() = envOrProp("LIMIT_PROBE_PATH") ?: "/v1/messages"
    private val probeModel: String get() = envOrProp("LIMIT_PROBE_MODEL") ?: "claude-haiku-4-5-20251001"
    private val systemPrompt = "You are Claude Code, Anthropic's official CLI for Claude."

    /** Stable per-account probe session id (probes have no client origin session). */
    private fun probeSessionId(accountId: Int): String =
        java.util.UUID.nameUUIDFromBytes("claude-proxy-probe-$accountId".toByteArray()).toString()

    /** Probe body framed like Claude Code, carrying this account's device-id + probe session. */
    private fun probeBody(account: AccountRuntime): String {
        val deviceId = account.deviceId ?: generateDeviceId()
        val inner = org.claudeproxy.proxy.RequestRewriter.userId(deviceId, account.upstreamAccountUuid, probeSessionId(account.id))
        val userId = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(inner))
        return """{"model":"$probeModel","max_tokens":1,"system":"$systemPrompt","messages":[{"role":"user","content":"."}],"metadata":{"user_id":$userId}}"""
    }

    /**
     * Outcome of one probe, kept apart from "did we see rate-limit headers" because the scheduler
     * has to tell a reading it could not refresh from one it refreshed and found nothing in. A
     * [FAILED] probe leaves the account's `updatedAt` frozen while the account still looks
     * healthy, which is the shape of the seven-hour-stale reading seen on 2026-09-14.
     */
    enum class Outcome { OBSERVED, REACHED, FAILED }

    /** Probe a single account. */
    suspend fun probe(accountId: Int): Outcome {
        val account = pool.get(accountId) ?: return Outcome.FAILED
        return try {
            val resp: HttpResponse = Http.client.post("$upstreamBaseUrl$probePath") {
                contentType(ContentType.Application.Json)
                header("anthropic-version", "2023-06-01")
                org.claudeproxy.proxy.ClaudeCodeClient.applyHeaders(this, probeSessionId(account.id))
                UpstreamAuth.apply(this, account.type, account.secret)
                setBody(probeBody(account))
            }
            val headerMap = HashMap<String, String>()
            resp.headers.forEach { k, v -> headerMap[k] = v.lastOrNull() ?: "" }
            runCatching { resp.readRawBytes() } // drain

            val prev = pool.get(accountId)?.limit ?: account.limit
            val parsedLimit = RateLimitHeaders.parse(headerMap, prev)
            // A successful inference probe invalidates the old 429 cooldown; error headers do not.
            val newLimit = if (resp.status.value in 200..299 && parsedLimit.rateLimitedUntil != null)
                parsedLimit.copy(rateLimitedUntil = null) else parsedLimit
            val observed = headerMap.keys.any { it.lowercase().startsWith("anthropic-ratelimit") }
            if (newLimit !== prev) pool.updateLimit(accountId, newLimit)
            when (resp.status.value) {
                // Same rule as live traffic: a per-model/burst 429 on the probe model must not
                // take the whole account out of rotation until the window reset.
                429 -> {
                    val until = RateLimitPolicy.parkUntil(headerMap, newLimit)
                    log.warn("Probe for account {} returned 429 -> {}; headers: {}", accountId,
                        until?.let { "park until $it" } ?: "not parked (model/burst limit)", RateLimitPolicy.describe(headerMap))
                    if (until != null) pool.markRateLimited(accountId, until)
                }
                401, 403 -> {
                    // Token no longer accepted. Try to recover OAuth accounts; otherwise flag lost access.
                    log.warn("Probe for account {} returned {} (lost access)", accountId, resp.status.value)
                    AccountRepo.updateHealth(accountId, AccountHealth.REFRESH_FAILED)
                    pool.setHealth(accountId, AccountHealth.REFRESH_FAILED)
                }
                in 200..299 -> {
                    if (account.health != AccountHealth.OK) {
                        AccountRepo.updateHealth(accountId, AccountHealth.OK)
                        pool.setHealth(accountId, AccountHealth.OK)
                    }
                }
            }
            log.info("Probed account {} -> status {} (rate-limit headers: {})", accountId, resp.status.value, observed)
            if (observed) Outcome.OBSERVED else Outcome.REACHED
        } catch (e: Exception) {
            log.warn("Probe failed for account {}: {}", accountId, e.message)
            Outcome.FAILED
        }
    }

    /** Probe every account. Returns the number that reported rate-limit headers. */
    suspend fun probeAll(): Int {
        var n = 0
        for (acc in pool.snapshot()) {
            if (probe(acc.id) == Outcome.OBSERVED) n++
        }
        return n
    }
}
