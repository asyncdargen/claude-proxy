package org.claudeproxy.datapath

import kotlinx.coroutines.runBlocking
import org.claudeproxy.Config
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountSecret
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.model.AccountType
import org.claudeproxy.repo.ProxyTokenRepo
import org.jetbrains.exposed.sql.selectAll
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Behavioral, DB-backed tests for [DatapathService] against throwaway SQLite. */
class DatapathServiceTest {
    private lateinit var dbFile: File
    private lateinit var pool: AccountPool
    private var adminId: Int = 0
    private lateinit var seededToken: String

    private fun config(dbPath: String) = Config(
        bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbPath,
        masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
        adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
        publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
        internalToken = null,
    )

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("datapath-test", ".db")
        val cfg = config(dbFile.absolutePath)
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
        // Fresh DB per test but the cache is process-global: user/spend keys would leak between tests.
        org.claudeproxy.cache.MemoryCache.clear()
        // The seeded bootstrap admin has PROXY_USE (all perms). Use it as the token owner.
        adminId = org.jetbrains.exposed.sql.transactions.transaction {
            org.claudeproxy.db.Users.selectAll().first()[org.claudeproxy.db.Users.id]
        }
        seededToken = ProxyTokenRepo.create(adminId, "test").token!!
        // One OAUTH account in the global pool.
        AccountRepo.create(
            name = "acc1", type = AccountType.OAUTH, groupId = null, priority = 10,
            threshold = 0.8, coefficient = 1.0,
            secret = AccountSecret(accessToken = "sk-ant-oat-TESTTOKEN", refreshToken = "r"),
            createdBy = adminId,
        )
        pool = AccountPool()
        runBlocking { pool.reload() }
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
    }

    @Test
    fun `resolve carries the token's forced model, and an edit applies at once`() = runBlocking {
        val svc = DatapathService(pool)
        val id = ProxyTokenRepo.listForUser(adminId).single().id
        assertEquals(null, svc.resolve(seededToken, "POST", "/v1/messages").defaultModel)

        assertTrue(ProxyTokenRepo.updateDefaultModel(id, adminId, "  claude-opus-5-5[1m] "))
        assertEquals("claude-opus-5-5[1m]", svc.resolve(seededToken, "POST", "/v1/messages").defaultModel)
        // count_tokens must be counted against the model that will actually answer
        assertEquals("claude-opus-5-5[1m]", svc.resolve(seededToken, "POST", "/v1/messages/count_tokens").defaultModel)
        assertEquals("claude-opus-5-5[1m]", ProxyTokenRepo.listForUser(adminId).single().defaultModel)

        assertFalse(ProxyTokenRepo.updateDefaultModel(id, adminId + 1, "x"), "someone else's token")
        assertTrue(ProxyTokenRepo.updateDefaultModel(id, adminId, " "))
        assertEquals(null, svc.resolve(seededToken, "POST", "/v1/messages").defaultModel)
    }

    @Test
    fun `a forced model id must be a single word`() {
        assertEquals(null, ProxyTokenRepo.normalizeModel(""))
        assertEquals("claude-opus-5-5", ProxyTokenRepo.normalizeModel(" claude-opus-5-5 "))
        assertTrue(runCatching { ProxyTokenRepo.normalizeModel("opus 5.5") }.isFailure)
        assertTrue(runCatching { ProxyTokenRepo.normalizeModel("a".repeat(129)) }.isFailure)
    }

    private fun accId() = org.jetbrains.exposed.sql.transactions.transaction {
        org.claudeproxy.db.Accounts.selectAll().first()[org.claudeproxy.db.Accounts.id]
    }

    private fun limitHeaders(util5h: String, status5h: String) = mapOf(
        "anthropic-ratelimit-unified-5h-utilization" to util5h,
        "anthropic-ratelimit-unified-5h-status" to status5h,
        "anthropic-ratelimit-unified-5h-reset" to (java.time.Instant.now().epochSecond + 3600).toString(),
    )

    /** A per-model 429 (haiku at 29% of the 5h window) must leave the account serving other models. */
    @Test
    fun `a model-scoped 429 keeps the account in rotation`() = runBlocking {
        val svc = DatapathService(pool)
        svc.applyOutcome(org.claudeproxy.api.UsageReport(accountId = accId(), userId = adminId, status = 429,
            model = "claude-haiku-5-5", ratelimitHeaders = limitHeaders("0.29", "allowed")))
        val r = svc.resolve(seededToken, "POST", "/v1/messages")
        assertTrue(r.candidates.isNotEmpty(), "account must stay a candidate")
        assertEquals(null, r.retryAfterSeconds)
    }

    @Test
    fun `an exhausted-window 429 parks the account and resolve reports retry-after`() = runBlocking {
        val svc = DatapathService(pool)
        svc.applyOutcome(org.claudeproxy.api.UsageReport(accountId = accId(), userId = adminId, status = 429,
            model = "claude-opus-5-5", ratelimitHeaders = limitHeaders("1.0", "rejected")))
        val r = svc.resolve(seededToken, "POST", "/v1/messages")
        assertTrue(r.candidates.isEmpty())
        val secs = r.retryAfterSeconds
        assertTrue(secs != null && secs in 3000..3600, "retry-after = $secs")
    }

    @Test
    fun `resolve returns BAD_TOKEN for unknown token`() = runBlocking {
        val r = DatapathService(pool).resolve("cxp_nope", "POST", "/v1/messages")
        assertEquals(ResolveError.BAD_TOKEN, r.error)
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun `resolve orders candidates and includes decrypted auth headers`() = runBlocking {
        val r = DatapathService(pool).resolve(seededToken, "POST", "/v1/messages")
        assertEquals(adminId, r.userId)
        assertTrue(r.candidates.isNotEmpty())
        val c = r.candidates.first()
        assertEquals("Bearer sk-ant-oat-TESTTOKEN", c.authHeaders["Authorization"])
        assertEquals("oauth-2025-04-20", c.authHeaders["anthropic-beta"])
    }

    @Test
    fun `free path count_tokens resolves an any-account candidate ignoring limits`() = runBlocking {
        val r = DatapathService(pool).resolve(seededToken, "POST", "/v1/messages/count_tokens")
        assertTrue(r.candidates.isNotEmpty())
        assertTrue(r.free, "count_tokens must be flagged free so its zero-token result stays out of stats")
    }

    /**
     * Claude Code counts tokens on every turn. Those attempts consume nothing, so recording them
     * would pad the request counters and the recent-requests list with zero-token noise — while a
     * *failed* one is exactly the thing an operator needs to see.
     */
    @Test
    fun `successful free-path attempts are not recorded, failures are`() = runBlocking {
        val accId = org.jetbrains.exposed.sql.transactions.transaction {
            org.claudeproxy.db.Accounts.selectAll().first()[org.claudeproxy.db.Accounts.id]
        }
        val svc = DatapathService(pool)
        fun rows() = org.jetbrains.exposed.sql.transactions.transaction {
            org.claudeproxy.db.UsageEvents.selectAll().count()
        }

        svc.applyOutcome(org.claudeproxy.api.UsageReport(accountId = accId, userId = adminId, status = 200, free = true))
        assertEquals(0L, rows(), "a successful token count is not a usage event")

        svc.applyOutcome(org.claudeproxy.api.UsageReport(accountId = accId, userId = adminId, status = 401, free = true))
        assertEquals(1L, rows(), "a failed token count must stay visible")

        // A metered request is always recorded, even with zero tokens (an empty error response).
        svc.applyOutcome(org.claudeproxy.api.UsageReport(accountId = accId, userId = adminId, status = 200))
        assertEquals(2L, rows())
    }

    /**
     * The daily limit and the statistics must spend the same dollars: [DatapathService] meters
     * the value [org.claudeproxy.repo.UsageRepo.record] persisted, and both come from one
     * pricing call over the full breakdown (per-TTL cache writes, fast mode, web searches).
     */
    @Test
    fun `recorded cost covers 1h cache writes, fast mode and web searches`() = runBlocking {
        val accId = org.jetbrains.exposed.sql.transactions.transaction {
            org.claudeproxy.db.Accounts.selectAll().first()[org.claudeproxy.db.Accounts.id]
        }
        val report = org.claudeproxy.api.UsageReport(
            accountId = accId, userId = adminId, status = 200, model = "claude-opus-5",
            input = 1_000_000, output = 0, cacheWrite = 1_000_000, cacheWrite1h = 1_000_000,
            webSearchRequests = 4, fast = true,
        )
        DatapathService(pool).applyOutcome(report)

        val row = org.jetbrains.exposed.sql.transactions.transaction {
            org.claudeproxy.db.UsageEvents.selectAll().last()
        }
        // (input 5 + 1h write 10) × fast 2 + 4 searches × $0.01
        assertEquals(30.04, row[org.claudeproxy.db.UsageEvents.cost], 1e-9)
        assertEquals(1_000_000L, row[org.claudeproxy.db.UsageEvents.cacheWrite1hTokens])
        assertEquals(4L, row[org.claudeproxy.db.UsageEvents.webSearchRequests])
        assertTrue(row[org.claudeproxy.db.UsageEvents.fast])
        // …and the exact same number is what the pricing call gives the daily-limit counter.
        assertEquals(
            org.claudeproxy.repo.ModelPriceRepo.costOf(report.model, report.billed()),
            row[org.claudeproxy.db.UsageEvents.cost],
            1e-9,
        )
    }

    /** A malformed report must never price a 1h slice larger than the writes it belongs to. */
    @Test
    fun `an oversized 1h slice is clamped to the total`() {
        val billed = org.claudeproxy.api.UsageReport(
            accountId = 1, status = 200, cacheWrite = 100, cacheWrite1h = 900,
        ).billed()
        assertEquals(100L, billed.cacheWrite1h)
        assertEquals(0L, billed.cacheWrite5m)
    }

    /**
     * Create a fresh user + token, with a role holding exactly [perms], and one personal
     * OAUTH account they own. The global "acc1" from setup stays in the pool. Returns the
     * user's token and personal account id.
     */
    private fun seedUser(name: String, perms: List<org.claudeproxy.model.Permission>): Pair<String, Int> {
        val roleId = org.claudeproxy.repo.RoleRepo.create(name, perms.map { it.name })
        // create() takes role NAMES; the role we just made is addressable by its name.
        val uid = org.claudeproxy.repo.UserRepo.create(name, "pw", listOf(name), emptyList(), null)
        val token = ProxyTokenRepo.create(uid, "$name-tok").token!!
        val accId = AccountRepo.create(
            name = "$name-personal", type = AccountType.OAUTH, groupId = null, priority = 5,
            threshold = 0.8, coefficient = 1.0,
            secret = AccountSecret(accessToken = "sk-ant-oat-PERSONAL", refreshToken = "r"),
            createdBy = uid, ownerId = uid,
        )
        runBlocking { pool.reload() }
        return token to accId
    }

    @Test
    fun `routing resolve carries the token's static system prompt`() = runBlocking {
        val created = org.claudeproxy.repo.RoutingTokenRepo.create(adminId, "rt", "Always answer in French.")
        val r = DatapathService(pool).resolve(created.token!!, "POST", "/v1/messages", source = "routing")
        assertEquals("Always answer in French.", r.systemPrompt)

        // clearing the prompt drops it from resolve (cache evicted on update)
        org.claudeproxy.repo.RoutingTokenRepo.updatePrompt(created.id, adminId, "  ")
        val r2 = DatapathService(pool).resolve(created.token!!, "POST", "/v1/messages", source = "routing")
        assertEquals(null, r2.systemPrompt)

        // proxy-datapath resolves never carry a prompt
        val rp = DatapathService(pool).resolve(seededToken, "POST", "/v1/messages")
        assertEquals(null, rp.systemPrompt)
    }

    @Test
    fun `applyOutcome records MCP tool calls and aggregates daily buckets`() = runBlocking {
        val accId = org.jetbrains.exposed.sql.transactions.transaction {
            org.claudeproxy.db.Accounts.selectAll().first()[org.claudeproxy.db.Accounts.id]
        }
        val svc = DatapathService(pool)
        svc.applyOutcome(
            org.claudeproxy.api.UsageReport(
                accountId = accId, userId = adminId, tokenId = 1, input = 10, output = 5, status = 200,
                model = "claude-sonnet-5",
                mcpCalls = mapOf("mcp__github__get_issue" to 2L, "mcp__memory__search" to 1L),
            ),
        )
        // A report without MCP calls must not add rows.
        svc.applyOutcome(org.claudeproxy.api.UsageReport(accountId = accId, userId = adminId, status = 200))

        val now = java.time.Instant.now()
        val buckets = org.claudeproxy.repo.McpUsageRepo.dailyBucketsForUser(adminId, now.minusSeconds(3600), now.plusSeconds(60))
        assertEquals(2, buckets.size)
        assertEquals(2L, buckets.first { it.toolName == "mcp__github__get_issue" }.calls)
        assertEquals(1L, buckets.first { it.toolName == "mcp__memory__search" }.calls)
    }

    @Test
    fun `without POOL_GLOBAL_USE only personal accounts are candidates`() = runBlocking {
        val (token, personalId) = seedUser(
            "nogp",
            listOf(org.claudeproxy.model.Permission.PROXY_USE, org.claudeproxy.model.Permission.ACCOUNTS_OWN_MANAGE),
        )
        val r = DatapathService(pool).resolve(token, "POST", "/v1/messages")
        val ids = r.candidates.map { it.accountId }
        assertEquals(listOf(personalId), ids) // global "acc1" is excluded
    }

    @Test
    fun `with POOL_GLOBAL_USE the global pool is included`() = runBlocking {
        val (token, personalId) = seedUser(
            "withgp",
            listOf(
                org.claudeproxy.model.Permission.PROXY_USE,
                org.claudeproxy.model.Permission.ACCOUNTS_OWN_MANAGE,
                org.claudeproxy.model.Permission.POOL_GLOBAL_USE,
            ),
        )
        val r = DatapathService(pool).resolve(token, "POST", "/v1/messages")
        val ids = r.candidates.map { it.accountId }
        assertTrue(personalId in ids)
        assertTrue(ids.size > 1) // personal + the global "acc1"
    }

    @Test
    fun `only the real counting and listing endpoints are free`() {
        listOf("/v1/messages/count_tokens?beta=true", "/v1/models", "/v1/models/claude-opus-5-5").forEach {
            assertTrue(DatapathService.isFreePath(it), it)
        }
        listOf(
            "/v1/messages", "/v1/chat/completions/count_tokens", "/v1/messages?count_tokens",
            "/v1/messages/count_tokens/x", "/v1/models/a/b",
        ).forEach { assertFalse(DatapathService.isFreePath(it), it) }
    }
}
