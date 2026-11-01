package org.claudeproxy.accounts

import io.ktor.client.request.get
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * negligible amount of quota. API keys get a free key check instead ([probeApiKey]). A dedicated
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
        if (account.isApiKey) return probeApiKey(account)
        return try {
            val resp: HttpResponse = Http.clientFor(account).post("$upstreamBaseUrl$probePath") {
                contentType(ContentType.Application.Json)
                header("anthropic-version", "2023-06-01")
                org.claudeproxy.proxy.ClaudeCodeClient.applyHeaders(this, probeSessionId(account.id))
                UpstreamAuth.apply(this, account)
                setBody(probeBody(account))
            }
            val headerMap = HashMap<String, String>()
            resp.headers.forEach { k, v -> headerMap[k] = v.lastOrNull() ?: "" }
            val responseBody = runCatching { resp.readRawBytes() }.getOrDefault(byteArrayOf())
            if (resp.status.value !in 200..299) {
                log.warn("Probe for account {} returned {}: {}", accountId, resp.status.value, upstreamError(responseBody, account))
            }

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

    /**
     * An API key has no subscription window to read, so its probe only answers "does this key
     * still work": `GET /v1/models` on its upstream, which is free — the 1-token message the
     * subscriptions need would bill the key every half hour for nothing. 401/403 marks the key
     * as lost; a 200 heals it and clears a stale 429 cooldown.
     */
    private suspend fun probeApiKey(account: AccountRuntime): Outcome = try {
        val base = account.upstreamBaseUrl ?: upstreamBaseUrl
        val resp: HttpResponse = Http.clientFor(account).get("$base/v1/models?limit=1") {
            header("anthropic-version", "2023-06-01")
            UpstreamAuth.apply(this, account)
        }
        val body = runCatching { resp.readRawBytes() }.getOrDefault(byteArrayOf())
        val code = resp.status.value
        when {
            code in 200..299 -> {
                if (account.health != AccountHealth.OK) {
                    AccountRepo.updateHealth(account.id, AccountHealth.OK)
                    pool.setHealth(account.id, AccountHealth.OK)
                }
                if (account.limit.rateLimitedUntil != null) pool.updateLimit(account.id, account.limit.copy(rateLimitedUntil = null))
            }
            code == 401 || code == 403 -> {
                log.warn("API key of account {} rejected ({}): {}", account.id, code, upstreamError(body, account))
                AccountRepo.updateHealth(account.id, AccountHealth.REFRESH_FAILED)
                pool.setHealth(account.id, AccountHealth.REFRESH_FAILED)
            }
            else -> log.warn("Key check for account {} returned {}: {}", account.id, code, upstreamError(body, account))
        }
        log.info("Checked API key of account {} -> status {}", account.id, code)
        if (code in 200..499) Outcome.REACHED else Outcome.FAILED
    } catch (e: Exception) {
        log.warn("Key check failed for account {}: {}", account.id, e.message)
        Outcome.FAILED
    }

    /** `type: message` of an upstream error body, with the account's own key scrubbed out. */
    private fun upstreamError(body: ByteArray, account: AccountRuntime): String = runCatching {
        val error = Json.parseToJsonElement(body.decodeToString()).jsonObject["error"]?.jsonObject
        listOfNotNull(error?.get("type")?.jsonPrimitive?.contentOrNull, error?.get("message")?.jsonPrimitive?.contentOrNull).joinToString(": ")
    }.getOrNull()?.ifEmpty { null }?.take(320)?.let { m -> account.secret.apiKey?.takeIf { it.isNotEmpty() }?.let { m.replace(it, "[redacted]") } ?: m }
        ?: "upstream error"

    /** Probe every account. Returns the number that reported rate-limit headers. */
    suspend fun probeAll(): Int {
        var n = 0
        for (acc in pool.snapshot()) {
            if (probe(acc.id) == Outcome.OBSERVED) n++
        }
        return n
    }
}
