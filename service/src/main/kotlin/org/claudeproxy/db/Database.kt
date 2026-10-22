package org.claudeproxy.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.claudeproxy.Config
import org.claudeproxy.model.Permission
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

private val log = LoggerFactory.getLogger("Database")

object Db {
    lateinit var database: Database
        private set

    fun init(config: Config) {
        val hikari = if (config.databaseUrl.isNotBlank()) {
            // PostgreSQL — proper concurrent writes, no lock contention.
            HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                driverClassName = "org.postgresql.Driver"
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = 10
            }
        } else {
            // SQLite fallback. Pragmas in the URL apply to every pooled connection so
            // writers wait for the lock (busy_timeout) instead of failing with SQLITE_BUSY.
            File(config.dbPath).absoluteFile.parentFile?.mkdirs()
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${config.dbPath}?journal_mode=WAL&synchronous=NORMAL&busy_timeout=15000&foreign_keys=on"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 4
                connectionInitSql = "PRAGMA busy_timeout=15000;"
            }
        }
        val ds = HikariDataSource(hikari)
        database = Database.connect(ds)

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES)
            seedRoles()
            migrateGlobalPoolPermission()
            migrateChatPermission()
            migrateWindowAttributionPermission()
            migrateWindowSnapshotCoefficient()
            seedAdmin(config)
        }
        org.claudeproxy.repo.ModelPriceRepo.seedDefaults()
        log.info("Database ready ({})", if (config.databaseUrl.isNotBlank()) "PostgreSQL" else "SQLite ${config.dbPath}")
    }

    /**
     * Default roles bundling permissions. Permissions are written ONLY when the role itself is
     * being created (first boot / a new built-in role appearing) — an existing role's permission
     * set belongs to the admin, and re-seeding it on every start silently resurrected
     * permissions the admin had removed (every redeploy restarts the service).
     */
    private fun seedRoles() {
        val defaults = mapOf(
            "admin" to Permission.entries.toList(),
            "manager" to listOf(Permission.ACCOUNTS_MANAGE, Permission.ACCOUNTS_VIEW, Permission.ACCOUNTS_OWN_MANAGE, Permission.ACCOUNTS_ORDER_TOGGLE, Permission.POOL_GLOBAL_USE, Permission.ROUTING_USE, Permission.CHAT_USE, Permission.STATS_VIEW, Permission.STATS_VIEW_OWN, Permission.PROXY_USE),
            "viewer" to listOf(Permission.ACCOUNTS_VIEW, Permission.STATS_VIEW, Permission.STATS_VIEW_OWN),
            "user" to listOf(Permission.PROXY_USE, Permission.STATS_VIEW_OWN, Permission.ACCOUNTS_OWN_MANAGE, Permission.ACCOUNTS_ORDER_TOGGLE, Permission.POOL_GLOBAL_USE, Permission.ROUTING_USE, Permission.CHAT_USE),
        )
        defaults.forEach { (roleName, defaultsForRole) ->
            val perms = (defaultsForRole + Permission.STATS_VIEW_WINDOW_ATTRIBUTION).distinct()
            val exists = Roles.select(Roles.id).where { Roles.name eq roleName }.any()
            if (exists) return@forEach
            val roleId = Roles.insert { it[name] = roleName }[Roles.id]
            perms.forEach { p ->
                RolePermissions.insertIgnore {
                    it[RolePermissions.roleId] = roleId
                    it[permission] = p.name
                }
            }
        }
    }

    /** One-time opt-out grant of named-user window estimates to every existing role. */
    private fun migrateWindowAttributionPermission() {
        // Every existing role, including custom roles, gets the permission once. Revocations persist.
        val markerKey = "migrated:window_attribution"
        if (Settings.selectAll().where { Settings.key eq markerKey }.any()) return
        Roles.selectAll().map { it[Roles.id] }.forEach { rid ->
            RolePermissions.insertIgnore {
                it[roleId] = rid
                it[permission] = Permission.STATS_VIEW_WINDOW_ATTRIBUTION.name
            }
        }
        Settings.insert {
            it[key] = markerKey
            it[value] = Instant.now().toString()
        }
    }

    /** Preserve shared pool access once for roles already holding PROXY_USE. */
    private fun migrateGlobalPoolPermission() {
        val markerKey = "migrated:pool_global_use"
        val done = Settings.selectAll().where { Settings.key eq markerKey }.any()
        if (done) return
        val roleIds = RolePermissions
            .select(RolePermissions.roleId)
            .where { RolePermissions.permission eq Permission.PROXY_USE.name }
            .map { it[RolePermissions.roleId] }
        roleIds.forEach { rid ->
            RolePermissions.insertIgnore {
                it[roleId] = rid
                it[permission] = Permission.POOL_GLOBAL_USE.name
            }
        }
        Settings.insert {
            it[key] = markerKey
            it[value] = Instant.now().toString()
        }
    }

    /**
     * One-time backfill for CHAT_USE, mirroring [migrateGlobalPoolPermission]: a role that could
     * already spend on the pool (PROXY_USE) gets the chat UI too, so the feature is reachable
     * right after an upgrade without hand-editing every role. Marked in the settings table so it
     * runs exactly once — otherwise every restart would resurrect a permission an admin removed.
     */
    private fun migrateChatPermission() {
        val markerKey = "migrated:chat_use"
        if (Settings.selectAll().where { Settings.key eq markerKey }.any()) return
        RolePermissions
            .select(RolePermissions.roleId)
            .where { RolePermissions.permission eq Permission.PROXY_USE.name }
            .map { it[RolePermissions.roleId] }
            .forEach { rid ->
                RolePermissions.insertIgnore {
                    it[roleId] = rid
                    it[permission] = Permission.CHAT_USE.name
                }
            }
        Settings.insert {
            it[key] = markerKey
            it[value] = Instant.now().toString()
        }
    }

    /**
     * One-time backfill of window_snapshots.coefficient (added for the coefficient-weighted
     * charts). Pre-existing rows carry no recorded coefficient; set each to its account's
     * *current* coefficient so the historical ×coef view is populated. New rows always write
     * the live coefficient, and this touches only NULL rows — so changing an account's
     * coefficient never rewrites old points. Idempotent (no NULLs remain after the first run).
     * The correlated subquery form runs on both PostgreSQL and SQLite.
     */
    private fun migrateWindowSnapshotCoefficient() {
        org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
            "UPDATE window_snapshots SET coefficient = " +
                "(SELECT coefficient FROM accounts WHERE accounts.id = window_snapshots.account_id) " +
                "WHERE coefficient IS NULL",
        )
    }

    /** Create the bootstrap admin user from config if no users exist yet. */
    private fun seedAdmin(config: Config) {
        val hasUsers = Users.selectAll().limit(1).any()
        if (hasUsers) return
        val uid = Users.insert {
            it[username] = config.adminUser
            it[passwordHash] = org.claudeproxy.auth.Passwords.hash(config.adminPassword)
            it[enabled] = true
            it[createdAt] = Instant.now()
        }[Users.id]
        val adminRoleId = Roles.select(Roles.id).where { Roles.name eq "admin" }.first()[Roles.id]
        UserRoles.insert {
            it[userId] = uid
            it[roleId] = adminRoleId
        }
        log.warn("Bootstrapped admin user '{}' (change the password!)", config.adminUser)
    }
}
