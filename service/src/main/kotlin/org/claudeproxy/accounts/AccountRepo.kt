package org.claudeproxy.accounts

import org.claudeproxy.db.AccountLimits
import org.claudeproxy.db.AccountSecrets
import org.claudeproxy.db.Accounts
import org.claudeproxy.model.AccountDto
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.model.AccountType
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
    val groupId: Int?,
    val ownerId: Int?,
    val priority: Int,
    val threshold: Double,
    val coefficient: Double,
    val enabled: Boolean,
    val overThreshold: Boolean,
    val health: AccountHealth,
    // per-account device fingerprint (64-hex) substituted into the upstream request body
    val deviceId: String?,
    // Anthropic account uuid (OAuth only), stamped into metadata.user_id.account_uuid
    val accountUuid: String? = null,
    val secret: AccountSecret,
    val limit: LimitState,
) {
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
        val shortTermUsage = limit.window(WindowKind.FIVE_HOUR)?.usageFraction() ?: usage
        val effRemaining = shortTermUsage?.let { coefficient * (1.0 - it) }
            ?: if (type == AccountType.API_KEY) coefficient else null
        return AccountDto(
            id = id, name = name, type = type.name, groupId = groupId, ownerId = ownerId, priority = priority,
            threshold = threshold, coefficient = coefficient, enabled = enabled,
            overThreshold = overThreshold,
            health = health.name,
            fiveHour = limit.window(WindowKind.FIVE_HOUR)?.toDto(),
            weekly = limit.window(WindowKind.WEEKLY)?.toDto(),
            overage = limit.overage?.toDto(),
            grace = limit.grace?.takeIf { !it.isEmpty() }?.toDto(),
            usageFraction = usage,
            rateLimitedUntil = limit.rateLimitedUntil?.toString(),
            effectiveRemaining = effRemaining,
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
                groupId = row[Accounts.groupId],
                ownerId = row[Accounts.ownerId],
                priority = row[Accounts.priority],
                threshold = row[Accounts.threshold],
                coefficient = row[Accounts.coefficient],
                enabled = row[Accounts.enabled],
                overThreshold = row[Accounts.overThreshold],
                health = runCatching { AccountHealth.valueOf(row[Accounts.health]) }.getOrDefault(AccountHealth.OK),
                deviceId = deviceId,
                accountUuid = row[Accounts.accountUuid],
                secret = secret,
                limit = limit,
            )
            rt to row[Accounts.createdAt]
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
        name: String, type: AccountType, groupId: Int?, priority: Int, threshold: Double, coefficient: Double,
        secret: AccountSecret, createdBy: Int?, ownerId: Int? = null,
        deviceId: String = generateDeviceId(),
        accountUuid: String? = null,
    ): Int = transaction {
        val id = Accounts.insert {
            it[Accounts.name] = name
            it[Accounts.type] = type.name
            // personal accounts are owner-scoped, never grouped
            it[Accounts.groupId] = if (ownerId != null) null else groupId
            it[Accounts.ownerId] = ownerId
            it[Accounts.priority] = priority
            it[Accounts.threshold] = threshold
            it[Accounts.coefficient] = coefficient
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
        id: Int, name: String?, groupId: Int?, priority: Int?, threshold: Double?, coefficient: Double?,
        enabled: Boolean?, deviceId: String?, clearGroup: Boolean = false, overThreshold: Boolean? = null,
        accountUuid: String? = null,
    ) = transaction {
        Accounts.update({ Accounts.id eq id }) {
            if (name != null) it[Accounts.name] = name
            if (clearGroup) it[Accounts.groupId] = null else if (groupId != null) it[Accounts.groupId] = groupId
            if (priority != null) it[Accounts.priority] = priority
            if (threshold != null) it[Accounts.threshold] = threshold
            if (coefficient != null) it[Accounts.coefficient] = coefficient
            if (enabled != null) it[Accounts.enabled] = enabled
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

    /** Swap in the credentials from a fresh login on an existing account, keeping its id, config and usage history. */
    fun reauthorize(id: Int, type: AccountType, secret: AccountSecret, accountUuid: String?) = transaction {
        Accounts.update({ Accounts.id eq id }) {
            it[Accounts.type] = type.name
            it[health] = AccountHealth.OK.name
            if (accountUuid != null) it[Accounts.accountUuid] = accountUuid
        }
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
