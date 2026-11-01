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

class ProbeRecoveryTest {
    private lateinit var db: File
    private lateinit var pool: AccountPool
    private var user = 0
    private var own = 0
    @BeforeTest fun setup() {
        db = File.createTempFile("token-usage", ".db")
        val secret = "test-secret-at-least-32-characters"
        Secrets.init(Crypto(secret))
        Db.init(Config(bindHost="127.0.0.1", port=8787, publicDomain=null, dbPath=db.absolutePath,
            masterKey=secret, sessionSecret=secret, adminUser="admin", adminPassword="admin",
            upstreamBaseUrl="https://example.invalid", publicBaseUrl="", databaseUrl="",
            databaseUser="", databasePassword="", internalToken=null))
        MemoryCache.clear()
        user = UserRepo.create("alice", "pw", listOf("user"), emptyList(), null)
        val bob = UserRepo.create("bob", "pw", listOf("user"), emptyList(), null)
        own = account("alice-private-name", user)
        account("bob-private-name", bob)
        account("shared-private-name", null)
        account("forbidden-group", null, GroupRepo.create("secret-group"))
        pool = AccountPool()
        runBlocking {
            pool.reload()
            pool.updateLimit(own, LimitState(windows=mapOf(WindowKind.FIVE_HOUR to WindowLimit(
                utilization=0.42, resetAt=Instant.now().plusSeconds(3600), updatedAt=Instant.now()))))
        }
    }
    private fun account(name: String, owner: Int?, group: Int? = null) = AccountRepo.create(
        name=name, type=AccountType.OAUTH, groupId=group, priority=10, threshold=0.9,
        kind=org.claudeproxy.model.AccountKind.PRO, secret=AccountSecret(accessToken="upstream-secret",refreshToken="refresh-secret"),
        createdBy=null, ownerId=owner)
    @AfterTest fun cleanup() { MemoryCache.clear(); db.delete() }
    @Test fun `successful probe clears persisted cooldown but failed probe preserves it`() = runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var status = 500
        server.createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("anthropic-ratelimit-unified-5h-utilization", "0")
            exchange.responseHeaders.add("anthropic-ratelimit-unified-5h-status", "allowed")
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val until = Instant.now().plusSeconds(3600)
            pool.markRateLimited(own, until)
            assertTrue(pool.selectionOrderOwned(user).isEmpty())
            val probe = LimitProbe(pool, "http://127.0.0.1:${server.address.port}")
            probe.probe(own)
            assertEquals(until, pool.get(own)!!.limit.rateLimitedUntil)
            assertTrue(pool.selectionOrderOwned(user).isEmpty())
            status = 200
            probe.probe(own)
            assertNull(pool.get(own)!!.limit.rateLimitedUntil)
            assertEquals(listOf(own), pool.selectionOrderOwned(user).map { it.id })
            pool.reload()
            assertNull(pool.get(own)!!.limit.rateLimitedUntil)
            assertEquals(listOf(own), pool.selectionOrderOwned(user).map { it.id })
        } finally { server.stop(0) }
    }

}
