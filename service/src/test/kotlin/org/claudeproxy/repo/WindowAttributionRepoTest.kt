package org.claudeproxy.repo

import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountSecret
import org.claudeproxy.auth.freshDb
import org.claudeproxy.db.*
import org.claudeproxy.model.AccountType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import kotlin.test.*

class WindowAttributionRepoTest {
    @Test fun `persisted requests produce named attribution without limits in five range queries regardless of account count`() {
        val file=freshDb()
        try {
            val t=Instant.parse("2026-10-15T00:00:00Z")
            val alice=UserRepo.create("alice","pw",listOf("user"),emptyList(),null)
            val bob=UserRepo.create("bob","pw",listOf("user"),emptyList(),null)
            val ids=(1..10).map { AccountRepo.create("shared-$it",AccountType.OAUTH,null,1,0.9,1.0,AccountSecret(accessToken="test"),null) }
            val id=ids.first()
            transaction {
                for ((seconds,util) in listOf(0L to 0.2,60L to 0.6,120L to 0.8)) WindowSnapshots.insert {
                    it[accountId]=id; it[windowKind]="5h"; it[ts]=t.plusSeconds(seconds); it[utilization]=util; it[coefficient]=1.0
                }
                for ((seconds,user,cost) in listOf(Triple(20L,alice,1.0),Triple(40L,bob,3.0),Triple(90L,alice,0.0))) UsageEvents.insert {
                    it[accountId]=id; it[userId]=user; it[ts]=t.plusSeconds(seconds); it[UsageEvents.cost]=cost
                    it[inputTokens]=20
                }
            }
            val statements=mutableListOf<String>()
            val response=transaction {
                addLogger(object: SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) { statements += context.statement.type.name }
                })
                WindowAttributionRepo.fetch(1,t.plusSeconds(180))
            }
            assertEquals(5,statements.count { it.startsWith("SELECT",ignoreCase=true) },statements.joinToString("\n"))
            assertEquals(10,response.accounts.size)
            val p=response.accounts.first().windows.single { it.kind == "5h" }.periods.single()
            assertTrue(p.estimatedBounds)
            assertEquals(0.8,p.total)
            val users=p.contributors.associateBy { it.username }
            assertEquals(0.3,users.getValue("alice").amount,1e-9)
            assertEquals(0.3,users.getValue("bob").amount,1e-9)
            assertEquals("tokens",users.getValue("alice").method)
            assertEquals(0.2,users.getValue("Unattributed").amount,1e-9)
            assertTrue(response.accounts.drop(1).all { it.windows.all { it.periods.isEmpty() } })
        } finally { file.delete() }
    }

    @Test fun `api-key per-minute remaining and limit is not presented as a quota window`() {
        val file=freshDb()
        try {
            val now=Instant.parse("2026-10-15T00:00:00Z")
            val id=AccountRepo.create("api-key",AccountType.API_KEY,null,1,0.9,1.0,AccountSecret(apiKey="k"),null)
            AccountRepo.persistLimit(id,org.claudeproxy.model.LimitState(windows=mapOf(
                org.claudeproxy.model.WindowKind.FIVE_HOUR to org.claudeproxy.model.WindowLimit(remaining=10.0,limitTotal=100.0,
                    resetAt=now.plusSeconds(30),updatedAt=now.minusSeconds(5)))))
            val r=WindowAttributionRepo.fetch(1,now)
            assertTrue(r.accounts.single().windows.all { it.periods.isEmpty() })
        } finally { file.delete() }
    }
}
