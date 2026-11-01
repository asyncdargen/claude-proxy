package org.claudeproxy.accounts

import org.claudeproxy.db.AccountLimits
import org.claudeproxy.db.AccountSecrets
import org.claudeproxy.db.Accounts
import org.claudeproxy.model.AccountDto
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountKind
import org.claudeproxy.model.ApiAuthStyle
import org.claudeproxy.model.ProxyType
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.ApiKeyMode
import org.claudeproxy.model.LimitState
import org.claudeproxy.model.LimitStatus
import org.claudeproxy.model.WindowKind
import org.claudeproxy.model.WindowLimit
import org.claudeproxy.model.GraceDto
import org.claudeproxy.model.GraceState
import org.claudeproxy.model.OverageDto
import org.claudeproxy.model.OverageState
import org.claudeproxy.model.WindowLimitDto
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.claudeproxy.repo.Totals
import java.time.Instant

/** Full config + secret + live limit state for one account, as held in the pool. */
data class AccountRuntime(
    val id: Int,
    val name: String,
    val type: AccountType,
    val apiKeyMode: ApiKeyMode = ApiKeyMode.CLAUDE_CODE,
    val groupId: Int?,
    val ownerId: Int?,
    val priority: Int,
    val threshold: Double,
    val kind: AccountKind = AccountKind.NONE,
    val enabled: Boolean,
    val overThreshold: Boolean,
    val health: AccountHealth,
    // per-account device fingerprint (64-hex) substituted into the upstream request body
    val deviceId: String?,
    // Anthropic account uuid (OAuth only), stamped into metadata.user_id.account_uuid
    val accountUuid: String? = null,
    val secret: AccountSecret,
    val limit: LimitState,
    // API keys only: custom upstream + how the key is presented to it
    val baseUrl: String? = null,
    val apiAuthStyle: ApiAuthStyle = ApiAuthStyle.X_API_KEY,
    // outbound proxy for every upstream call made for this account (null = direct/global egress)
    val proxy: AccountProxy? = null,
) {
    val isApiKey: Boolean get() = type == AccountType.API_KEY

    /** Upstream this account talks to, when it is not api.anthropic.com (API keys only). */
    val upstreamBaseUrl: String? get() = baseUrl?.takeIf { isApiKey && it.isNotBlank() }?.trimEnd('/')

    /**
     * A subscription whose responses carry the weekly window but no 5-hour one: a verified Ultra
     * plan, which Anthropic no longer gives a session window. Until anything is observed the
     * account is assumed to have both.
     */
    val fiveHourLimited: Boolean get() =
        isApiKey || limit.window(WindowKind.FIVE_HOUR) != null || limit.window(WindowKind.WEEKLY) == null

    /**
     * What goes into metadata.user_id.account_uuid for this account: its own uuid for a
     * subscription, "" for an API key (that is what Claude Code itself sends in API-key mode)
     * and "" while a subscription's uuid is still unknown — never the client's.
     */
    val upstreamAccountUuid: String get() = if (type == AccountType.API_KEY) "" else accountUuid ?: ""

    fun toDto(createdAt: String, counts: Totals): AccountDto {
        val usage = limit.usageFraction()
        // "Capacity left" / effective-remaining reflects the SHORT-TERM (5-hour) budget — the
        // constraint that governs immediate use. Basing it on the max across windows let the
        // weekly window dominate, so a fresh-5h account looked saturated. Fall back to the max
        // when no 5-hour reading exists yet.
        // A weekly-only (Ultra) account falls back to the weekly reading, which is its real bound.
        // API keys have no windows and stay out of headroom altogether.
        val shortTermUsage = limit.window(WindowKind.FIVE_HOUR)?.usageFraction() ?: usage
        val effRemaining = if (isApiKey) null else shortTermUsage?.let { kind.fiveHourWeight * (1.0 - it) }
        return AccountDto(
            id = id, name = name, type = type.name, apiKeyMode = apiKeyMode.name,
            baseUrl = upstreamBaseUrl, apiAuthStyle = apiAuthStyle.name,
            groupId = groupId, ownerId = ownerId, priority = priority,
            threshold = threshold, kind = kind.name, enabled = enabled,
            overThreshold = overThreshold,
            health = health.name,
            fiveHour = if (isApiKey) null else limit.window(WindowKind.FIVE_HOUR)?.toDto(),
            weekly = if (isApiKey) null else limit.window(WindowKind.WEEKLY)?.toDto(),
            overage = if (isApiKey) null else limit.overage?.toDto(),
            grace = if (isApiKey) null else limit.grace?.takeIf { !it.isEmpty() }?.toDto(),
            usageFraction = if (isApiKey) null else usage,
            rateLimitedUntil = limit.rateLimitedUntil?.toString(),
            effectiveRemaining = effRemaining,
            fiveHourLimited = fiveHourLimited,
            proxy = proxy?.toDto(),
            totalInputTokens = counts.input,
            totalOutputTokens = counts.output,
            totalCacheReadTokens = counts.cacheRead,
            totalCacheWriteTokens = counts.cacheWrite,
            totalCost = counts.cost,
            totalRequests = counts.requests,
            deviceId = deviceId,
            accountUuid = accountUuid,
            createdAt = createdAt,
        )
    }
}

private fun OverageState.toDto() = OverageDto(
    status = status,
    inUse = inUse,
    utilization = utilization,
    monthlyUtilization = monthlyUtilization,
    channelUtilization = channelUtilization,
    resetAt = resetAt?.toString(),
    disabledReason = disabledReason,
    weeklyWithOverage = weeklyWithOverage,
)

private fun GraceState.toDto() = GraceDto(
    status = status,
    fiveHourUtilization = fiveHourUtilization,
    weeklyUtilization = weeklyUtilization,
)

private fun WindowLimit.toDto() = WindowLimitDto(
    usageFraction = usageFraction(),
    remaining = remaining,
    limitTotal = limitTotal,
    resetAt = resetAt?.toString(),
    status = status.name.takeIf { status != LimitStatus.UNKNOWN },
    updatedAt = updatedAt?.toString(),
)

/** A genuine Claude Code device id is a 64-char lowercase hex string. */
private val HEX64 = Regex("^[0-9a-f]{64}$")

/** Random 64-hex device fingerprint, shaped like the one Claude Code sends. */
fun generateDeviceId(): String {
    val bytes = ByteArray(32)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Keep an existing 64-hex device id; otherwise (null or legacy UUID) mint a fresh one. */
private fun normalizeDeviceId(current: String?): String =
    current?.takeIf { HEX64.matches(it) } ?: generateDeviceId()

object AccountRepo {

    fun loadAll(): List<Pair<AccountRuntime, Instant>> = transaction {
        Accounts.selectAll().map { row ->
            val id = row[Accounts.id]
            val secretBlob = AccountSecrets.selectAll().where { AccountSecrets.accountId eq id }
                .firstOrNull()?.get(AccountSecrets.cipherBlob)
            val secret = secretBlob?.let { Secrets.decode(it) } ?: AccountSecret()
            val windows = AccountLimits.selectAll().where { AccountLimits.accountId eq id }.mapNotNull { lr ->
                val kind = WindowKind.fromCode(lr[AccountLimits.windowKind]) ?: return@mapNotNull null
                kind to WindowLimit(
                    utilization = lr[AccountLimits.utilization],
                    remaining = lr[AccountLimits.remaining],
                    limitTotal = lr[AccountLimits.limitTotal],
                    resetAt = lr[AccountLimits.resetAt],
                    status = runCatching { LimitStatus.valueOf(lr[AccountLimits.status]) }.getOrDefault(LimitStatus.UNKNOWN),
                    updatedAt = lr[AccountLimits.updatedAt],
                )
            }.toMap()
            val limit = LimitState(windows = windows, rateLimitedUntil = row[Accounts.rateLimitedUntil])
            // Ensure every account has a genuine 64-hex device id; backfill legacy/empty values
            // once and persist so the id stays stable across reloads.
            val deviceId = normalizeDeviceId(row[Accounts.deviceId])
            if (deviceId != row[Accounts.deviceId]) {
                Accounts.update({ Accounts.id eq id }) { it[Accounts.deviceId] = deviceId }
            }
            val rt = AccountRuntime(
                id = id,
                name = row[Accounts.name],
                type = AccountType.fromString(row[Accounts.type]) ?: AccountType.API_KEY,
                apiKeyMode = ApiKeyMode.fromString(row[Accounts.apiKeyMode]) ?: ApiKeyMode.CLAUDE_CODE,
                groupId = row[Accounts.groupId],
                ownerId = row[Accounts.ownerId],
                priority = row[Accounts.priority],
                threshold = row[Accounts.threshold],
                kind = AccountKind.fromString(row[Accounts.kind]) ?: AccountKind.NONE,
                enabled = row[Accounts.enabled],
                overThreshold = row[Accounts.overThreshold],
                health = runCatching { AccountHealth.valueOf(row[Accounts.health]) }.getOrDefault(AccountHealth.OK),
                deviceId = deviceId,
                accountUuid = row[Accounts.accountUuid],
                secret = secret,
                limit = limit,
                baseUrl = row[Accounts.baseUrl],
                apiAuthStyle = ApiAuthStyle.fromString(row[Accounts.apiAuthStyle]) ?: ApiAuthStyle.X_API_KEY,
                proxy = proxyOf(row),
            )
            rt to row[Accounts.createdAt]
        }
    }

    private fun proxyOf(row: org.jetbrains.exposed.sql.ResultRow): AccountProxy? {
        val type = ProxyType.fromString(row[Accounts.proxyType]) ?: ProxyType.NONE
        if (type == ProxyType.NONE) return null
        val host = row[Accounts.proxyHost] ?: return null
        val port = row[Accounts.proxyPort] ?: return null
        val password = row[Accounts.proxyPasswordCipher]?.let { runCatching { Secrets.crypto.decrypt(it) }.getOrNull() }
        return AccountProxy(type, host, port, row[Accounts.proxyUsername], password)
    }

    /**
     * Replace an account's proxy. [password] null keeps the stored one (the UI never sees it, so an
     * edit that does not touch the field must not wipe it); "" clears it.
     */
    fun setProxy(id: Int, type: ProxyType, host: String?, port: Int?, username: String?, password: String?) = transaction {
        Accounts.update({ Accounts.id eq id }) {
            it[proxyType] = type.name
            if (type == ProxyType.NONE) {
                it[proxyHost] = null; it[proxyPort] = null; it[proxyUsername] = null; it[proxyPasswordCipher] = null
            } else {
                it[proxyHost] = host?.trim()
                it[proxyPort] = port
                it[proxyUsername] = username?.trim()?.ifEmpty { null }
                if (password != null) it[proxyPasswordCipher] = password.ifEmpty { null }?.let { p -> Secrets.crypto.encrypt(p) }
            }
        }
    }

    fun createdAtMap(): Map<Int, Instant> = transaction {
        Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.createdAt] }
    }

    fun namesMap(): Map<Int, String> = transaction {
        Accounts.selectAll().associate { it[Accounts.id] to it[Accounts.name] }
    }

    /** Ids of every personal (owner-scoped) account — used to keep them out of global stats. */
    fun personalIds(): Set<Int> = transaction {
        Accounts.selectAll().mapNotNull { row -> row[Accounts.id].takeIf { row[Accounts.ownerId] != null } }.toSet()
    }

    /** Ids of the personal accounts owned by [userId] — for the user's own per-account stats. */
    fun personalIdsOf(userId: Int): Set<Int> = transaction {
        Accounts.selectAll().where { Accounts.ownerId eq userId }.map { it[Accounts.id] }.toSet()
    }

    /** True if account [id] exists and is owned by [userId] (a personal account of that user). */
    fun isOwnedBy(id: Int, userId: Int): Boolean = transaction {
        Accounts.selectAll().where { (Accounts.id eq id) and (Accounts.ownerId eq userId) }.any()
    }

    /** Detach global accounts created by [userId] so the user can be deleted (Postgres FK). */
    fun clearCreatedBy(userId: Int) = transaction {
        Accounts.update({ Accounts.createdBy eq userId }) { it[createdBy] = null }
    }

    fun create(
        name: String, type: AccountType, groupId: Int?, priority: Int, threshold: Double, kind: AccountKind,
        secret: AccountSecret, createdBy: Int?, ownerId: Int? = null,
        deviceId: String = generateDeviceId(),
        accountUuid: String? = null,
        apiKeyMode: ApiKeyMode = ApiKeyMode.CLAUDE_CODE,
        baseUrl: String? = null,
        apiAuthStyle: ApiAuthStyle = ApiAuthStyle.X_API_KEY,
    ): Int = transaction {
        val api = type == AccountType.API_KEY
        val id = Accounts.insert {
            it[Accounts.name] = name
            it[Accounts.type] = type.name
            it[Accounts.apiKeyMode] = if (api) apiKeyMode.name else ApiKeyMode.CLAUDE_CODE.name
            it[Accounts.baseUrl] = baseUrl?.trim()?.ifEmpty { null }?.takeIf { api }
            it[Accounts.apiAuthStyle] = if (api) apiAuthStyle.name else ApiAuthStyle.X_API_KEY.name
            // an API key is not a subscription plan
            it[Accounts.kind] = if (api) AccountKind.NONE.name else kind.name
            // personal accounts are owner-scoped, never grouped
            it[Accounts.groupId] = if (ownerId != null) null else groupId
            it[Accounts.ownerId] = ownerId
            it[Accounts.priority] = priority
            it[Accounts.threshold] = threshold
            it[enabled] = true
            it[health] = AccountHealth.OK.name
            it[Accounts.deviceId] = deviceId
            it[Accounts.accountUuid] = accountUuid
            it[Accounts.createdBy] = createdBy
            it[createdAt] = Instant.now()
        }[Accounts.id]
        AccountSecrets.insert {
            it[accountId] = id
            it[cipherBlob] = Secrets.encode(secret)
        }
        id
    }

    fun updateConfig(
        id: Int, name: String?, groupId: Int?, priority: Int?, threshold: Double?, kind: AccountKind?,
        enabled: Boolean?, deviceId: String?, clearGroup: Boolean = false, overThreshold: Boolean? = null,
        accountUuid: String? = null,
        apiKeyMode: ApiKeyMode? = null,
        baseUrl: String? = null,
        apiAuthStyle: ApiAuthStyle? = null,
    ) = transaction {
        // an edit that only touches the proxy (saved separately) has nothing for this UPDATE
        val touches = clearGroup || listOf(name, groupId, priority, threshold, kind, enabled, apiKeyMode, baseUrl,
            apiAuthStyle, overThreshold, deviceId, accountUuid).any { it != null }
        if (!touches) return@transaction
        Accounts.update({ Accounts.id eq id }) {
            if (name != null) it[Accounts.name] = name
            if (clearGroup) it[Accounts.groupId] = null else if (groupId != null) it[Accounts.groupId] = groupId
            if (priority != null) it[Accounts.priority] = priority
            if (threshold != null) it[Accounts.threshold] = threshold
            if (kind != null) it[Accounts.kind] = kind.name
            // "" clears it; null leaves it alone
            if (baseUrl != null) it[Accounts.baseUrl] = baseUrl.trim().ifEmpty { null }
            if (apiAuthStyle != null) it[Accounts.apiAuthStyle] = apiAuthStyle.name
            if (enabled != null) it[Accounts.enabled] = enabled
            if (apiKeyMode != null) it[Accounts.apiKeyMode] = apiKeyMode.name
            if (overThreshold != null) it[Accounts.overThreshold] = overThreshold
            if (deviceId != null) it[Accounts.deviceId] = deviceId
            // "" clears it (an operator undoing a wrong value); null leaves it alone
            if (accountUuid != null) it[Accounts.accountUuid] = accountUuid.trim().lowercase().ifBlank { null }
        }
    }

    fun updateAccountUuid(id: Int, accountUuid: String) = transaction {
        Accounts.update({ Accounts.id eq id }) { it[Accounts.accountUuid] = accountUuid }
    }

    fun updateSecret(id: Int, secret: AccountSecret) = transaction {
        AccountSecrets.upsert {
            it[accountId] = id
            it[cipherBlob] = Secrets.encode(secret)
        }
    }

    fun updateHealth(id: Int, health: AccountHealth) = transaction {
        Accounts.update({ Accounts.id eq id }) { it[Accounts.health] = health.name }
    }

    fun delete(id: Int): Boolean = transaction {
        AccountLimits.deleteWhere { accountId eq id }
        AccountSecrets.deleteWhere { accountId eq id }
        Accounts.deleteWhere { Accounts.id eq id } > 0
    }

    fun persistLimit(id: Int, limit: LimitState) = transaction {
        // A window upstream stopped reporting (Ultra losing its 5-hour window) must not come back
        // from the table on the next start.
        WindowKind.entries.filter { it !in limit.windows }.forEach { k ->
            AccountLimits.deleteWhere { (accountId eq id) and (windowKind eq k.code) }
        }
        limit.windows.forEach { (kind, w) ->
            AccountLimits.upsert {
                it[accountId] = id
                it[windowKind] = kind.code
                it[utilization] = w.utilization
                it[remaining] = w.remaining
                it[limitTotal] = w.limitTotal
                it[resetAt] = w.resetAt
                it[status] = w.status.name
                it[updatedAt] = w.updatedAt ?: Instant.now()
            }
        }
        Accounts.update({ Accounts.id eq id }) {
            it[rateLimitedUntil] = limit.rateLimitedUntil
        }
    }
}
