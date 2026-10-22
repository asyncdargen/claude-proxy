package org.claudeproxy.db

import org.claudeproxy.auth.freshDb
import org.claudeproxy.auth.freshDbAt
import org.claudeproxy.repo.RoleRepo
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class WindowAttributionMigrationTest {
    @Test fun `all existing roles receive independent permission once and revocation survives restart`() {
        val file = freshDb()
        try {
            val permission = "STATS_VIEW_WINDOW_ATTRIBUTION"
            assertTrue(RoleRepo.list().all { permission in it.permissions })
            val custom = RoleRepo.create("custom-restricted",emptyList())
            transaction { Settings.deleteWhere { key eq "migrated:window_attribution" } }
            freshDbAt(file)
            val role = RoleRepo.list().single { it.id == custom }
            assertEquals(listOf(permission),role.permissions)
            RoleRepo.setPermissions(custom,emptyList())
            freshDbAt(file)
            assertTrue(RoleRepo.list().single { it.id == custom }.permissions.isEmpty())
        } finally { file.delete() }
    }
}
