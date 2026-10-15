package org.claudeproxy.repo

import org.claudeproxy.auth.freshDb
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountSecret
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.WindowKind
import java.time.Instant
import kotlin.test.*

class WindowSnapshotResetTest {
    @Test fun `snapshot throttling does not discard observations after switching database`() {
        val files=mutableListOf<java.io.File>()
        try {
            val t=Instant.parse("2040-01-01T00:00:00Z")
            repeat(2) {
                files += freshDb()
                val id=AccountRepo.create("shared",AccountType.OAUTH,null,1,0.9,1.0,AccountSecret(accessToken="test"),null)
                WindowSnapshotRepo.record(id,WindowKind.FIVE_HOUR,0.2,1.0,t)
                assertEquals(1,WindowSnapshotRepo.fetch(t,t.plusSeconds(1)).size)
            }
        } finally { files.forEach { it.delete() } }
    }

    @Test fun `pool snapshot preserves observation time instead of refreshing stale window on write`() = kotlinx.coroutines.runBlocking {
        val file = freshDb()
        try {
            AccountRepo.create("other",AccountType.OAUTH,null,1,0.9,1.0,AccountSecret(accessToken="test"),null)
            val id=AccountRepo.create("shared",AccountType.OAUTH,null,1,0.9,1.0,AccountSecret(accessToken="test"),null)
            val observed=Instant.now().minusSeconds(600)
            val pool=org.claudeproxy.accounts.AccountPool()
            pool.reload()
            pool.updateLimit(id,org.claudeproxy.model.LimitState(windows=mapOf(
                WindowKind.FIVE_HOUR to org.claudeproxy.model.WindowLimit(utilization=0.42,updatedAt=observed,resetAt=observed.plusSeconds(18000)))))
            val actual=WindowSnapshotRepo.fetch(observed.minusSeconds(1),Instant.now().plusSeconds(1)).single()
            assertEquals(observed.toEpochMilli(),actual.ts.toEpochMilli())
            assertEquals(observed.plusSeconds(18000).toEpochMilli(),actual.resetAt!!.toEpochMilli())
        } finally { file.delete() }
    }

    @Test fun `new snapshots retain actual reset timestamps while legacy samples default to null`() {
        val file = freshDb()
        try {
            val id = AccountRepo.create("shared",AccountType.OAUTH,null,1,0.9,1.0,AccountSecret(accessToken="test"),null)
            val t = Instant.parse("2030-01-01T00:00:00Z")
            WindowSnapshotRepo.record(id,WindowKind.FIVE_HOUR,0.2,1.0,t,resetAt=t.plusSeconds(18000))
            val sample = WindowSnapshotRepo.fetch(t,t.plusSeconds(1)).single()
            assertEquals(t.plusSeconds(18000),sample.resetAt)
            assertNull(WindowSample(id,"5h",t,0.2,1.0).resetAt)
        } finally { file.delete() }
    }
}
