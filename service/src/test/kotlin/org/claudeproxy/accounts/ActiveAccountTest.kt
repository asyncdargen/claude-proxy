package org.claudeproxy.accounts

import kotlinx.coroutines.runBlocking
import org.claudeproxy.Config
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.*
import org.claudeproxy.model.*
import org.claudeproxy.repo.*
import java.io.File
import java.time.Instant
import kotlin.test.*

/**
 * The dashboard's "active" marker is the head of a real routing order. Quota-free calls (token
 * counts, model lists) pick by plain priority, so letting them write it pinned the marker on the
 * priority-1 account whatever the routing order said.
 */
class ActiveAccountTest {
    private lateinit var db: File
    private lateinit var pool: AccountPool
    private var first = 0
    private var drained = 0

    @BeforeTest fun setup() {
        db = File.createTempFile("active-account", ".db")
        val secret = "test-secret-at-least-32-characters"
        Secrets.init(Crypto(secret))
        Db.init(Config(bindHost="127.0.0.1", port=8787, publicDomain=null, dbPath=db.absolutePath,
            masterKey=secret, sessionSecret=secret, adminUser="admin", adminPassword="admin",
            upstreamBaseUrl="https://example.invalid", publicBaseUrl="", databaseUrl="",
            databaseUser="", databasePassword="", internalToken=null))
        MemoryCache.clear()
        first = account("priority-one", priority = 1)
        drained = account("priority-two", priority = 2)
        pool = AccountPool()
        runBlocking {
            pool.reload()
            // Priority 1 is past its threshold, so the real order puts priority 2 first.
            pool.updateLimit(first, LimitState(windows = mapOf(WindowKind.FIVE_HOUR to WindowLimit(
                utilization = 0.95, resetAt = Instant.now().plusSeconds(3600), updatedAt = Instant.now()))))
        }
    }

    private fun account(name: String, priority: Int) = AccountRepo.create(
        name = name, type = AccountType.OAUTH, groupId = null, priority = priority, threshold = 0.9,
        kind = AccountKind.PRO, secret = AccountSecret(accessToken = "a", refreshToken = "r"),
        createdBy = null, ownerId = null)

    @AfterTest fun cleanup() { MemoryCache.clear(); db.delete() }

    @Test fun `a quota-free call does not move the active marker`() = runBlocking {
        pool.selectionOrder(null, null)
        assertEquals(drained, pool.activeAccountId)

        pool.selectAnyOrder(null, null)
        pool.selectAny(null, null)

        assertEquals(drained, pool.activeAccountId)
    }

    @Test fun `a real routing order marks its head as active`() = runBlocking {
        assertNull(pool.activeAccountId)

        val order = pool.selectionOrder(null, null)

        assertEquals(listOf(drained, first), order.map { it.id })
        assertEquals(drained, pool.activeAccountId)
    }
}
