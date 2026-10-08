package org.claudeproxy.accounts

import kotlinx.coroutines.runBlocking
import org.claudeproxy.Config
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.*
import org.claudeproxy.model.*
import java.io.File
import kotlin.test.*

class ReauthorizeTest {
    private lateinit var db: File
    @BeforeTest fun setup() {
        db = File.createTempFile("reauthorize", ".db")
        val secret = "test-secret-at-least-32-characters"
        Secrets.init(Crypto(secret))
        Db.init(Config(bindHost="127.0.0.1", port=8787, publicDomain=null, dbPath=db.absolutePath,
            masterKey=secret, sessionSecret=secret, adminUser="admin", adminPassword="admin",
            upstreamBaseUrl="https://example.invalid", publicBaseUrl="", databaseUrl="",
            databaseUser="", databasePassword="", internalToken=null))
        MemoryCache.clear()
    }
    @AfterTest fun cleanup() { MemoryCache.clear(); db.delete() }

    @Test fun `reauthorize swaps credentials and clears the failed health but keeps config`() = runBlocking {
        val id = AccountRepo.create(
            name="claude_01", type=AccountType.OAUTH, groupId=null, priority=1, threshold=0.99,
            coefficient=5.0, secret=AccountSecret(accessToken="old-access", refreshToken="old-refresh"), createdBy=null)
        AccountRepo.updateHealth(id, AccountHealth.REFRESH_FAILED)

        AccountRepo.reauthorize(id, AccountType.OAUTH, AccountSecret(accessToken="new-access", refreshToken="new-refresh", expiresAt=123L), "11111111-2222-3333-4444-555555555555")

        val pool = AccountPool().also { it.reload() }
        val a = pool.get(id)!!
        assertEquals(AccountHealth.OK, a.health)
        assertEquals("new-access", a.secret.accessToken)
        assertEquals("new-refresh", a.secret.refreshToken)
        assertEquals(123L, a.secret.expiresAt)
        assertEquals("11111111-2222-3333-4444-555555555555", a.accountUuid)
        assertEquals(1, a.priority)
        assertEquals(0.99, a.threshold)
        assertEquals(5.0, a.coefficient)
        assertEquals("claude_01", a.name)
    }

    @Test fun `reauthorize without a profile uuid keeps the known one and can drop to a static token`() = runBlocking {
        val id = AccountRepo.create(
            name="a", type=AccountType.OAUTH, groupId=null, priority=1, threshold=0.9,
            coefficient=1.0, secret=AccountSecret(accessToken="old", refreshToken="old-refresh"), createdBy=null,
            accountUuid="11111111-2222-3333-4444-555555555555")

        AccountRepo.reauthorize(id, AccountType.OAUTH_STATIC, AccountSecret(accessToken="static"), null)

        val a = AccountPool().also { it.reload() }.get(id)!!
        assertEquals(AccountType.OAUTH_STATIC, a.type)
        assertNull(a.secret.refreshToken)
        assertEquals("11111111-2222-3333-4444-555555555555", a.accountUuid)
    }
}
