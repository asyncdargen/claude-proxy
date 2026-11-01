package org.claudeproxy.accounts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
import org.claudeproxy.oauth.ClaudeOAuth
import org.claudeproxy.proxy.Http
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Background loop that refreshes OAuth access tokens ~5 minutes before expiry.
 * Accounts whose refresh fails are marked REFRESH_FAILED and excluded from selection
 * until an operator fixes them — and are not retried until their [RefreshBackoff] window
 * elapses, so a permanently dead refresh token can't hammer Anthropic's token endpoint
 * once a minute forever.
 */
class TokenRefresher(private val pool: AccountPool) {
    private val log = LoggerFactory.getLogger("TokenRefresher")
    private val refreshMarginMs = 5 * 60 * 1000L
    private val pollIntervalMs = 60 * 1000L
    private val backoff = RefreshBackoff()
    // (account, access-token tag) pairs whose profile lookup already ran — a token without the
    // user:profile scope answers 403 forever, so each token is asked once per process.
    private val uuidLookups = ConcurrentHashMap.newKeySet<String>()

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { tick() }.onFailure { log.warn("refresh tick failed: {}", it.message) }
            delay(pollIntervalMs)
        }
    }

    suspend fun tick() {
        val now = System.currentTimeMillis()
        for (acc in pool.snapshot()) backfillAccountUuid(acc)
        for (acc in pool.snapshot()) {
            if (acc.type != AccountType.OAUTH) continue
            if (acc.health == AccountHealth.DEAD) continue
            val refreshToken = acc.secret.refreshToken ?: continue
            val expiresAt = acc.secret.expiresAt
            val dueSoon = expiresAt == null || expiresAt - now <= refreshMarginMs
            if (!dueSoon) continue
            // A failing account waits out its backoff. Re-authorizing it (a new refresh token)
            // clears the wait immediately — that's the operator fixing it.
            if (!backoff.allowed(acc.id, refreshToken, now)) continue
            refreshOne(acc.id, refreshToken)
        }
    }

    suspend fun refreshOne(accountId: Int, refreshToken: String) {
        try {
            // through the account's own proxy: the refresh is part of how the account presents
            val result = ClaudeOAuth.refresh(Http.clientFor(pool.get(accountId)?.proxy), refreshToken)
            val cur = pool.get(accountId)?.secret ?: AccountSecret()
            val updated = cur.copy(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken ?: cur.refreshToken,
                expiresAt = result.expiresAtMillis ?: cur.expiresAt,
            )
            AccountRepo.updateSecret(accountId, updated)
            result.accountUuid?.let { storeAccountUuid(accountId, it) }
            AccountRepo.updateHealth(accountId, AccountHealth.OK)
            pool.updateSecretInMemory(accountId, updated, AccountHealth.OK)
            backoff.onSuccess(accountId)
            log.info("Refreshed token for account {}", accountId)
        } catch (e: Exception) {
            val message = e.message ?: ""
            // `invalid_grant` means the refresh token itself is gone (expired or revoked): no
            // number of retries brings it back, only a fresh "Login with Claude".
            val permanent = message.contains("invalid_grant")
            val nextAt = backoff.onFailure(accountId, refreshToken, System.currentTimeMillis(), permanent)
            if (permanent) {
                log.warn(
                    "Refresh token for account {} is no longer valid ({}) — re-add the account; next attempt at {}",
                    accountId, message, Instant.ofEpochMilli(nextAt),
                )
            } else {
                log.warn("Refresh failed for account {}: {} — next attempt at {}", accountId, message, Instant.ofEpochMilli(nextAt))
            }
            AccountRepo.updateHealth(accountId, AccountHealth.REFRESH_FAILED)
            pool.setHealth(accountId, AccountHealth.REFRESH_FAILED)
        }
    }

    /**
     * OAuth accounts added before the uuid was kept (or pasted by hand) learn it from the profile
     * endpoint. Without it the datapath can only send an empty account_uuid next to a
     * subscription bearer, which genuine Claude Code never does.
     */
    private suspend fun backfillAccountUuid(acc: AccountRuntime) {
        if (acc.type == AccountType.API_KEY || acc.accountUuid != null) return
        if (acc.health == AccountHealth.DEAD) return
        val token = acc.secret.accessToken ?: return
        if (!uuidLookups.add("${acc.id}:${token.hashCode()}")) return
        val uuid = runCatching { ClaudeOAuth.fetchAccountUuid(Http.clientFor(acc), token) }
            .onFailure { log.warn("Profile lookup failed for account {}: {}", acc.id, it.message) }
            .getOrNull()
        if (uuid == null) {
            log.info("Account {} has no account uuid and its token cannot read the profile; set it by hand", acc.id)
            return
        }
        storeAccountUuid(acc.id, uuid)
    }

    private suspend fun storeAccountUuid(accountId: Int, uuid: String) {
        if (pool.get(accountId)?.accountUuid == uuid) return
        AccountRepo.updateAccountUuid(accountId, uuid)
        pool.setAccountUuid(accountId, uuid)
        log.info("Account {} identified as Anthropic account {}", accountId, uuid)
    }
}

/**
 * Per-account retry gate for OAuth refresh failures: the first failure parks the account for
 * [baseMs], each further one doubles the wait up to [maxMs], and a permanent failure goes
 * straight to the cap. Keyed by account, but tied to the refresh token that failed — a new
 * token (the operator re-authorized the account) is tried at once. Pure time-in/decision-out
 * so the policy is testable without a clock or the network.
 */
internal class RefreshBackoff(
    private val baseMs: Long = 30 * 60 * 1000L,
    private val maxMs: Long = 6 * 60 * 60 * 1000L,
) {
    private data class Entry(val tokenTag: String, val notBefore: Long, val failures: Int)

    private val entries = ConcurrentHashMap<Int, Entry>()

    fun allowed(accountId: Int, refreshToken: String, now: Long): Boolean {
        val e = entries[accountId] ?: return true
        if (e.tokenTag != tag(refreshToken)) {
            entries.remove(accountId)
            return true
        }
        return now >= e.notBefore
    }

    /** Record a failure; returns the epoch-millis instant of the next allowed attempt. */
    fun onFailure(accountId: Int, refreshToken: String, now: Long, permanent: Boolean): Long {
        val prev = entries[accountId]?.takeIf { it.tokenTag == tag(refreshToken) }
        val failures = (prev?.failures ?: 0) + 1
        val wait = if (permanent) maxMs else minOf(maxMs, baseMs shl minOf(failures - 1, 16))
        val notBefore = now + wait
        entries[accountId] = Entry(tag(refreshToken), notBefore, failures)
        return notBefore
    }

    fun onSuccess(accountId: Int) {
        entries.remove(accountId)
    }

    /** Identifies the token without keeping (or ever logging) the secret itself. */
    private fun tag(refreshToken: String): String = refreshToken.hashCode().toString()
}
