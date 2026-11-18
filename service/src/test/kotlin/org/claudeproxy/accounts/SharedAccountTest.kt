package org.claudeproxy.accounts

import kotlinx.coroutines.runBlocking
import org.claudeproxy.Config
import org.claudeproxy.api.ShareAccountRequest
import org.claudeproxy.api.sharedUntil
import org.claudeproxy.api.sharingRefusal
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.*
import org.claudeproxy.model.*
import org.claudeproxy.repo.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import kotlin.test.*

/**
 * An owner can lend a personal account to the shared pool until a datetime.
 */
class SharedAccountTest {
    private lateinit var db: File
    private lateinit var pool: AccountPool
    private var alice = 0
    private var bob = 0
    private var aliceAccount = 0
    private val now = Instant.parse("2026-11-18T10:00:00Z")

    @BeforeTest fun setup() {
        db = File.createTempFile("shared-account", ".db")
        val secret = "test-secret-at-least-32-characters"
        Secrets.init(Crypto(secret))
        Db.init(Config(bindHost="127.0.0.1", port=8787, publicDomain=null, dbPath=db.absolutePath,
            masterKey=secret, sessionSecret=secret, adminUser="admin", adminPassword="admin",
            upstreamBaseUrl="https://example.invalid", publicBaseUrl="", databaseUrl="",
            databaseUser="", databasePassword="", internalToken=null))
        MemoryCache.clear()
        alice = UserRepo.create("alice", "pw", emptyList(), emptyList(), null)
        bob = UserRepo.create("bob", "pw", emptyList(), emptyList(), null)
        aliceAccount = AccountRepo.create(
            name = "alice-personal", type = AccountType.OAUTH, groupId = null, priority = 1, threshold = 0.9,
            kind = AccountKind.PRO, secret = AccountSecret(accessToken = "a", refreshToken = "r"),
            createdBy = alice, ownerId = alice)
        pool = AccountPool()
    }

    @AfterTest fun cleanup() { MemoryCache.clear(); db.delete() }

    private fun share(until: Instant?) = runBlocking {
        AccountRepo.setSharedUntil(aliceAccount, until)
        pool.reload()
    }

    private fun ids(user: Int, allowGlobal: Boolean = true) = runBlocking {
        pool.selectionOrder(user, null, allowGlobal = allowGlobal, now = now).map { it.id }
    }

    @Test fun `an unshared personal account serves only its owner`() {
        share(null)

        assertEquals(listOf(aliceAccount), ids(alice))
        assertEquals(emptyList(), ids(bob))
    }

    @Test fun `a shared personal account serves the pool until the datetime`() {
        share(now.plusSeconds(3600))

        assertEquals(listOf(aliceAccount), ids(bob))
        assertEquals(emptyList(), runBlocking { pool.selectionOrder(bob, null, now = now.plusSeconds(3601)).map { it.id } })
    }

    @Test fun `quota-free calls follow the same share`() = runBlocking {
        share(now.plusSeconds(3600))

        assertEquals(listOf(aliceAccount), pool.selectAnyOrder(bob, null, now = now).map { it.id })
        assertEquals(emptyList(), pool.selectAnyOrder(bob, null, now = now.plusSeconds(3601)).map { it.id })
    }

    @Test fun `the owner meets a shared account once`() {
        share(now.plusSeconds(3600))

        assertEquals(listOf(aliceAccount), ids(alice))
    }

    @Test fun `a user barred from the pool cannot borrow a shared account`() {
        share(now.plusSeconds(3600))

        assertEquals(emptyList(), ids(bob, allowGlobal = false))
    }

    @Test fun `spend on a borrowed account counts as pool spend and the owner's own does not`() {
        share(now.plusSeconds(3600))
        transaction {
            for (user in listOf(alice, bob)) UsageEvents.insert {
                it[accountId] = aliceAccount; it[userId] = user; it[ts] = Instant.now(); it[cost] = 2.0
            }
        }

        assertEquals(2.0, UsageRepo.userTotals(bob, globalOnly = true).cost)
        assertEquals(0.0, UsageRepo.userTotals(alice, globalOnly = true).cost)
    }

    @Test fun `usage on a borrowed account is flagged with its owner and the owner's own use is not`() {
        UsageRepo.record(aliceAccount, bob, BilledUsage(input = 1), 200, null)
        UsageRepo.record(aliceAccount, alice, BilledUsage(input = 1), 200, null)

        val lentBy = transaction { UsageEvents.selectAll().associate { it[UsageEvents.userId] to it[UsageEvents.lentByUserId] } }
        assertEquals(alice, lentBy[bob])
        assertNull(lentBy[alice])
    }

    @Test fun `an account with its own upstream cannot be shared`() = runBlocking {
        val custom = AccountRepo.create(
            name = "alice-gateway", type = AccountType.API_KEY, groupId = null, priority = 2, threshold = 0.9,
            kind = AccountKind.NONE, secret = AccountSecret(apiKey = "k"), createdBy = alice, ownerId = alice,
            baseUrl = "https://gateway.example.test")
        pool.reload()

        assertNotNull(pool.get(custom)!!.sharingRefusal())
        assertNull(pool.get(aliceAccount)!!.sharingRefusal())
    }

    @Test fun `the share datetime must be in the future and within fourteen days`() {
        assertNull(ShareAccountRequest(null).sharedUntil(now))
        assertEquals(now.plusSeconds(60), ShareAccountRequest(now.plusSeconds(60).toString()).sharedUntil(now))
        assertFailsWith<Exception> { ShareAccountRequest(now.minusSeconds(1).toString()).sharedUntil(now) }
        assertEquals(now.plusSeconds(14 * 86400), ShareAccountRequest(now.plusSeconds(14 * 86400 + 60).toString()).sharedUntil(now))
        assertFailsWith<Exception> { ShareAccountRequest(now.plusSeconds(15 * 86400).toString()).sharedUntil(now) }
        assertFailsWith<Exception> { ShareAccountRequest("tomorrow").sharedUntil(now) }
    }
}
