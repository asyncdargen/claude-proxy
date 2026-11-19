package org.claudeproxy.repo

import org.claudeproxy.auth.Passwords
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.RolePermissions
import org.claudeproxy.db.Roles
import org.claudeproxy.db.UserGroupAccess
import org.claudeproxy.db.UserRoles
import org.claudeproxy.db.Users
import org.claudeproxy.model.Permission
import org.claudeproxy.model.UserDto
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteReturning
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class UserAuth(val id: Int, val username: String, val enabled: Boolean, val permissions: Set<Permission>, val sessionVersion: Long = 0L)

/** What an offboarding removed. Tokens are deleted (not just disabled), so a re-enabled user starts with none. */
data class OffboardResult(val proxyTokensRevoked: Int, val routingTokensRevoked: Int)

class EmailTakenException : RuntimeException("Email already used by another user")

object UserRepo {

    fun authenticate(username: String, password: String): UserAuth? = transaction {
        val row = Users.selectAll().where { Users.username eq username }.firstOrNull() ?: return@transaction null
        if (!row[Users.enabled]) return@transaction null
        if (!Passwords.verify(password, row[Users.passwordHash])) return@transaction null
        toAuth(row)
    }

    /** Logout revokes all sessions from this generation; a stale cookie cannot revoke newer ones. */
    fun revokeSessions(userId: Int, version: Long) = transaction {
        Users.update({ (Users.id eq userId) and (Users.sessionVersion eq version) }) {
            it[sessionVersion] = Users.sessionVersion + 1L
        }
    }

    fun findAuth(userId: Int): UserAuth? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
        toAuth(row)
    }

    private fun toAuth(row: ResultRow): UserAuth {
        val uid = row[Users.id]
        return UserAuth(uid, row[Users.username], row[Users.enabled], permissionsOf(uid), row[Users.sessionVersion])
    }

    fun permissionsOf(userId: Int): Set<Permission> = transaction {
        // Cache eviction can race with an in-flight token lookup. Authorization must use live state.
        if (Users.selectAll().where { (Users.id eq userId) and (Users.enabled eq true) }.empty()) {
            return@transaction emptySet()
        }
        val roleIds = UserRoles.selectAll().where { UserRoles.userId eq userId }.map { it[UserRoles.roleId] }
        if (roleIds.isEmpty()) return@transaction emptySet()
        val perms = RolePermissions.selectAll().where { RolePermissions.roleId inList roleIds }
            .mapNotNull { Permission.fromString(it[RolePermissions.permission]) }
            .toMutableSet()
        if (Permission.ADMIN in perms) return@transaction Permission.entries.toSet()
        perms
    }

    fun rolesOf(userId: Int): List<String> = transaction {
        val roleIds = UserRoles.selectAll().where { UserRoles.userId eq userId }.map { it[UserRoles.roleId] }
        if (roleIds.isEmpty()) return@transaction emptyList()
        Roles.selectAll().where { Roles.id inList roleIds }.map { it[Roles.name] }
    }

    fun list(): List<UserDto> = transaction {
        Users.selectAll().map { row -> toDto(row) }
    }

    fun get(userId: Int): UserDto? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
        toDto(row)
    }

    /** Per-day spend limit in USD for a user; null = unlimited. */
    fun dailyLimitOf(userId: Int): Double? = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.dailyCostLimit)
    }

    /** Per-day routing (OpenAI/Anthropic gateway) spend limit in USD for a user; null = unlimited. */
    fun dailyRoutingLimitOf(userId: Int): Double? = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.dailyRoutingCostLimit)
    }

    /** Per-day chat-UI spend limit in USD for a user; null = unlimited. */
    fun dailyChatLimitOf(userId: Int): Double? = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.dailyChatCostLimit)
    }

    /** Routing preference: true = try the global pool before the user's own personal accounts. */
    fun preferGlobalPoolOf(userId: Int): Boolean = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.preferGlobalPool) ?: false
    }

    fun setPreferGlobalPool(userId: Int, prefer: Boolean) = transaction {
        Users.update({ Users.id eq userId }) { it[preferGlobalPool] = prefer }
    }

    /** Verify a plaintext password against the stored hash (self-service profile edits). */
    fun verifyPassword(userId: Int, plain: String): Boolean = transaction {
        val hash = Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.passwordHash)
            ?: return@transaction false
        Passwords.verify(plain, hash)
    }

    /** True if [username] is already used by a different user (case-sensitive, matches the unique index). */
    fun usernameTaken(username: String, exceptUserId: Int): Boolean = transaction {
        Users.selectAll().where { (Users.username eq username) and (Users.id neq exceptUserId) }.any()
    }

    /** Self-service update of one's own username/password. Nulls leave the field unchanged. */
    fun updateSelf(userId: Int, username: String?, password: String?) = transaction {
        Users.update({ Users.id eq userId }) {
            if (username != null) it[Users.username] = username
            if (password != null) {
                it[passwordHash] = Passwords.hash(password)
                it[sessionVersion] = Users.sessionVersion + 1L
            }
        }
    }

    private fun toDto(row: ResultRow): UserDto {
        val uid = row[Users.id]
        val perms = permissionsOf(uid)
        // "today" here reflects shared-pool spend (what the daily limit governs); personal-account usage is excluded.
        // Split by datapath so proxy, routing and chat spend are metered against their own daily limits.
        val today = UsageRepo.userTotals(uid, startOfUtcDay(), globalOnly = true, source = "proxy")
        val todayRouting = UsageRepo.userTotals(uid, startOfUtcDay(), globalOnly = true, source = "routing")
        val todayChat = UsageRepo.userTotals(uid, startOfUtcDay(), globalOnly = true, source = "chat")
        return UserDto(
            id = uid,
            username = row[Users.username],
            enabled = row[Users.enabled],
            roles = rolesOf(uid),
            permissions = perms.map { it.name },
            allowedGroups = allowedGroupsOf(uid).toList(),
            allGroups = Permission.ADMIN in perms,
            dailyCostLimit = row[Users.dailyCostLimit],
            dailyRoutingCostLimit = row[Users.dailyRoutingCostLimit],
            dailyChatCostLimit = row[Users.dailyChatCostLimit],
            preferGlobalPool = row[Users.preferGlobalPool],
            email = row[Users.email],
            ssoLinked = IdentityRepo.isLinked(uid),
            todayCost = today.cost,
            todayRoutingCost = todayRouting.cost,
            todayChatCost = todayChat.cost,
            todayInputTokens = today.input,
            todayOutputTokens = today.output,
        )
    }

    /**
     * Start of today on the *limit* clock. The per-user daily USD limits are enforced on UTC days,
     * so anything read against a limit must use this — never the viewer's zone.
     */
    fun startOfUtcDay(): Instant = startOfDayIn(java.time.ZoneOffset.UTC)

    /**
     * Start of today on [zone]'s clock — the basis for *displayed* "today" counters, so they line
     * up with the charts (which are already sliced on the viewer's days).
     */
    fun startOfDayIn(zone: java.time.ZoneId): Instant =
        java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()

    fun allowedGroupsOf(userId: Int): Set<Int> = transaction {
        UserGroupAccess.selectAll().where { UserGroupAccess.userId eq userId }
            .map { it[UserGroupAccess.groupId] }.toSet()
    }

    fun setAllowedGroups(userId: Int, groupIds: List<Int>) = transaction {
        UserGroupAccess.deleteWhere { UserGroupAccess.userId eq userId }
        groupIds.distinct().forEach { gid ->
            UserGroupAccess.insert {
                it[UserGroupAccess.userId] = userId
                it[groupId] = gid
            }
        }
    }

    /**
     * Normalizes an admin-entered email for [Users.email]: trimmed, lower-cased, null when blank.
     * Shape check only — the address is never mailed, it just has to compare equal to an IdP claim.
     */
    fun normalizeEmail(raw: String?): String? {
        val e = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        require(e.length <= 320 && e.count { it == '@' } == 1 && !e.startsWith('@') && !e.endsWith('@') &&
            e.none { it.isWhitespace() || it.isISOControl() }) { "invalid email" }
        return e
    }

    /** Caller's transaction. Lookup by normalized email. */
    internal fun userIdByEmail(email: String): Int? =
        Users.selectAll().where { Users.email eq email }.firstOrNull()?.get(Users.id)

    /**
     * Test seam: false skips the pre-check, reproducing the case where a concurrent writer takes the
     * email between [checkEmailFree] and the insert/update (only the unique index catches it then).
     */
    @Volatile internal var emailPrecheck: Boolean = true

    private fun checkEmailFree(email: String?, exceptUserId: Int?) {
        if (email == null || !emailPrecheck) return
        val owner = userIdByEmail(email)
        if (owner != null && owner != exceptUserId) throw EmailTakenException()
    }

    fun create(
        username: String, password: String, roleNames: List<String>, groupIds: List<Int>,
        dailyCostLimit: Double?, dailyRoutingCostLimit: Double? = null, dailyChatCostLimit: Double? = null,
        email: String? = null,
    ): Int = emailRace(email) { transaction {
        checkEmailFree(email, null)
        val uid = Users.insert {
            it[Users.email] = email
            it[Users.username] = username
            it[passwordHash] = Passwords.hash(password)
            it[enabled] = true
            it[Users.dailyCostLimit] = dailyCostLimit
            it[Users.dailyRoutingCostLimit] = dailyRoutingCostLimit
            it[Users.dailyChatCostLimit] = dailyChatCostLimit
            it[createdAt] = Instant.now()
        }[Users.id]
        setRoles(uid, roleNames)
        setAllowedGroups(uid, groupIds)
        uid
    } }

    /**
     * The pre-check in [checkEmailFree] can race a concurrent write; the unique index is the real
     * guard. Turn that violation into [EmailTakenException] (→ 409, no SQL text to the client).
     */
    private fun <T> emailRace(email: String?, block: () -> T): T = try {
        block()
    } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
        if (email != null && transaction { userIdByEmail(email) } != null) throw EmailTakenException()
        throw e
    }

    fun update(
        userId: Int, password: String?, enabled: Boolean?, roleNames: List<String>?, groupIds: List<Int>?,
        dailyCostLimit: Double?, clearDailyLimit: Boolean,
        dailyRoutingCostLimit: Double? = null, clearRoutingLimit: Boolean = false,
        dailyChatCostLimit: Double? = null, clearChatLimit: Boolean = false,
        email: String? = null, clearEmail: Boolean = false,
    ) {
        emailRace(email.takeUnless { clearEmail }) { transaction {
            if (!clearEmail) checkEmailFree(email, userId)
            Users.update({ Users.id eq userId }) {
                if (clearEmail) it[Users.email] = null else if (email != null) it[Users.email] = email
                if (password != null) it[passwordHash] = Passwords.hash(password)
                if (password != null || enabled == false) it[sessionVersion] = Users.sessionVersion + 1L
                if (enabled != null) it[Users.enabled] = enabled
                if (clearDailyLimit) it[Users.dailyCostLimit] = null else if (dailyCostLimit != null) it[Users.dailyCostLimit] = dailyCostLimit
                if (clearRoutingLimit) it[Users.dailyRoutingCostLimit] = null else if (dailyRoutingCostLimit != null) it[Users.dailyRoutingCostLimit] = dailyRoutingCostLimit
                if (clearChatLimit) it[Users.dailyChatCostLimit] = null else if (dailyChatCostLimit != null) it[Users.dailyChatCostLimit] = dailyChatCostLimit
            }
            if (roleNames != null) setRoles(userId, roleNames)
            if (groupIds != null) setAllowedGroups(userId, groupIds)
        } }
        // Token resolves are cached for 60s; a disable has to bite on the very next request.
        // Evicted after the commit, or a concurrent resolve could re-cache the old state.
        if (enabled == false) tokenCacheKeys(userId).forEach(MemoryCache::evict)
    }

    /** Resolve-cache keys of every token this user owns, both namespaces. */
    private fun tokenCacheKeys(userId: Int): List<String> = transaction {
        org.claudeproxy.db.ProxyTokens.selectAll().where { org.claudeproxy.db.ProxyTokens.userId eq userId }
            .map { "cp:tok:${it[org.claudeproxy.db.ProxyTokens.tokenHash]}" } +
            org.claudeproxy.db.RoutingTokens.selectAll().where { org.claudeproxy.db.RoutingTokens.userId eq userId }
                .map { "cp:rtok:${it[org.claudeproxy.db.RoutingTokens.tokenHash]}" }
    }

    fun delete(userId: Int) {
        val cacheKeys = tokenCacheKeys(userId)
        UsageRollup.exclusive { transaction {
            // clear rows that reference the user (Postgres enforces these FKs)
            org.claudeproxy.db.ProxyTokens.deleteWhere { org.claudeproxy.db.ProxyTokens.userId eq userId }
            org.claudeproxy.db.RoutingTokens.deleteWhere { org.claudeproxy.db.RoutingTokens.userId eq userId }
            UsageRepo.detachUser(userId)
            McpUsageRepo.detachUser(userId)
            // delete this user's personal accounts (their secrets/limits/usage cascade off Accounts)
            org.claudeproxy.db.Accounts.deleteWhere { org.claudeproxy.db.Accounts.ownerId eq userId }
            // detach global accounts this user created so the users row can be removed (Postgres FK)
            org.claudeproxy.db.Accounts.update({ org.claudeproxy.db.Accounts.createdBy eq userId }) {
                it[org.claudeproxy.db.Accounts.createdBy] = null
            }
            UserGroupAccess.deleteWhere { UserGroupAccess.userId eq userId }
            UserRoles.deleteWhere { UserRoles.userId eq userId }
            IdentityRepo.unlinkAll(userId)
            Users.deleteWhere { Users.id eq userId }
        } }
        // a deleted user's tokens are gone from the DB; their cached resolves must go too
        cacheKeys.forEach(MemoryCache::evict)
    }

    /**
     * Offboarding in one step: disable the user, end every panel session (session generation bump)
     * and delete all of their `cxp_`/`cxr_` tokens, so nothing they hold keeps working on any
     * datapath. Linked SSO identities are kept — the disabled flag already refuses them, and the
     * link is the audit trail of who the account belonged to. Null when the user does not exist.
     */
    fun offboard(userId: Int): OffboardResult? {
        val (result, cacheKeys) = transaction {
            val n = Users.update({ Users.id eq userId }) {
                it[enabled] = false
                it[sessionVersion] = Users.sessionVersion + 1L
            }
            if (n == 0) return@transaction null to emptyList()
            // DELETE … RETURNING: the cache keys are taken from exactly the rows this statement
            // deleted, including a token committed by a concurrent request after the transaction
            // began (Postgres READ COMMITTED would delete it, but a separate SELECT would miss it).
            val proxy = org.claudeproxy.db.ProxyTokens.deleteReturning(listOf(org.claudeproxy.db.ProxyTokens.tokenHash)) {
                org.claudeproxy.db.ProxyTokens.userId eq userId
            }.map { "cp:tok:${it[org.claudeproxy.db.ProxyTokens.tokenHash]}" }
            val routing = org.claudeproxy.db.RoutingTokens.deleteReturning(listOf(org.claudeproxy.db.RoutingTokens.tokenHash)) {
                org.claudeproxy.db.RoutingTokens.userId eq userId
            }.map { "cp:rtok:${it[org.claudeproxy.db.RoutingTokens.tokenHash]}" }
            OffboardResult(proxyTokensRevoked = proxy.size, routingTokensRevoked = routing.size) to (proxy + routing)
        }
        // After the commit, or a concurrent resolve could re-cache a token that is about to vanish.
        // A token minted after this commit by a request that was already past its auth check is not
        // deleted here — but it, like any stale cache entry, is refused: every datapath re-checks the
        // owner's live enabled state/permissions (DatapathService, ProxyRoutes, TokenUsageRoutes).
        cacheKeys.forEach(MemoryCache::evict)
        return result
    }

    /**
     * Just-in-time account for a first SSO login. [role] null = a pending request: created
     * **disabled with no roles**, so it can do nothing (no panel, no tokens, no pool) until an admin
     * enables it and picks a role. Never any account-group access, and an unusable random password
     * — the account can only sign in through SSO unless an admin sets one. Caller's transaction.
     */
    internal fun createProvisioned(username: String, email: String?, role: String?): Int {
        val uid = Users.insert {
            it[Users.username] = username
            it[passwordHash] = Passwords.hash(org.claudeproxy.db.Crypto.randomToken(32))
            it[enabled] = role != null
            it[Users.email] = email
            it[createdAt] = Instant.now()
        }[Users.id]
        if (role != null) setRoles(uid, listOf(role))
        return uid
    }

    /** Caller's transaction. */
    internal fun roleExists(name: String): Boolean =
        Roles.selectAll().where { Roles.name eq name }.any()

    /** Caller's transaction. */
    internal fun usernameExists(username: String): Boolean =
        Users.selectAll().where { Users.username eq username }.any()

    private fun setRoles(userId: Int, roleNames: List<String>) {
        UserRoles.deleteWhere { UserRoles.userId eq userId }
        roleNames.forEach { rn ->
            val rid = Roles.selectAll().where { Roles.name eq rn }.firstOrNull()?.get(Roles.id) ?: return@forEach
            UserRoles.insert {
                it[UserRoles.userId] = userId
                it[roleId] = rid
            }
        }
    }
}
