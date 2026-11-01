package org.claudeproxy.api

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.claudeproxy.Config
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountSecret
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.db.Users
import org.claudeproxy.model.AccountType
import org.claudeproxy.repo.ProxyTokenRepo
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalRoutesTest {
    private val token = "devtok"
    private lateinit var dbFile: File
    private lateinit var pool: AccountPool
    private lateinit var seededToken: String
    private var adminId: Int = 0

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("internal-routes-test", ".db")
        val cfg = Config(
            bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbFile.absolutePath,
            masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
            adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
            publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
            internalToken = token,
        )
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
        // Fresh DB per test but the cache is process-global: user/spend keys would leak between tests.
        org.claudeproxy.cache.MemoryCache.clear()
        adminId = transaction { Users.selectAll().first()[Users.id] }
        seededToken = ProxyTokenRepo.create(adminId, "test").token!!
        AccountRepo.create(
            name = "acc1", type = AccountType.OAUTH, groupId = null, priority = 10,
            threshold = 0.8, kind = org.claudeproxy.model.AccountKind.PRO,
            secret = AccountSecret(accessToken = "sk-ant-oat-TESTTOKEN", refreshToken = "r"),
            createdBy = adminId,
        )
        pool = AccountPool()
        runBlocking { pool.reload() }
    }

    @AfterTest
    fun teardown() = dbFile.delete().let {}

    private fun io.ktor.server.application.Application.mount() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        routing { internalRoutes(DatapathService(pool), token) }
    }

    @Test
    fun `resolve without internal token is 401`() = testApplication {
        application { mount() }
        val res = client.post("/internal/resolve") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"x","method":"POST","path":"/v1/messages"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `resolve with token returns candidates JSON`() = testApplication {
        application { mount() }
        val res = client.post("/internal/resolve") {
            header("X-Internal-Token", token)
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$seededToken","method":"POST","path":"/v1/messages"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"userId\":$adminId"), body)
        assertTrue(body.contains("anthropic-beta"), body)
    }

    @Test
    fun `resolve with bad proxy token is 401`() = testApplication {
        application { mount() }
        val res = client.post("/internal/resolve") {
            header("X-Internal-Token", token)
            contentType(ContentType.Application.Json)
            setBody("""{"token":"cxp_nope","method":"POST","path":"/v1/messages"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `usage endpoint returns 204`() = testApplication {
        application { mount() }
        val res = client.post("/internal/usage") {
            header("X-Internal-Token", token)
            contentType(ContentType.Application.Json)
            setBody("""{"accountId":1,"userId":$adminId,"status":200,"model":"claude-opus-4-8","ratelimitHeaders":{}}""")
        }
        assertEquals(HttpStatusCode.NoContent, res.status)
    }

    /**
     * The wire contract with the Go gateway. This is the exact JSON `control.UsageReport`
     * marshals once a response carries the priced extras — if a field name drifts, the money
     * silently stops being counted, so it is pinned here as a literal rather than a builder.
     */
    @Test
    fun `usage endpoint accepts the priced extras the gateway sends`() = testApplication {
        application { mount() }
        val res = client.post("/internal/usage") {
            header("X-Internal-Token", token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"accountId":1,"userId":$adminId,"input":100,"output":20,"cacheRead":5,""" +
                    """"cacheWrite":9000,"cacheWrite1h":8000,"webSearchRequests":2,"webFetchRequests":1,""" +
                    """"fast":true,"free":false,"status":200,"model":"claude-opus-5","ratelimitHeaders":{},""" +
                    """"mcpCalls":{"mcp__github__get_issue":1}}""",
            )
        }
        assertEquals(HttpStatusCode.NoContent, res.status)
    }

    /**
     * An attempt that never reached upstream (transport error, or a 502 the gateway wrote itself)
     * has no rate-limit headers, and Go marshals that nil map as `null`. Rejecting it dropped the
     * whole report: the failed attempt left no usage row and no account bookkeeping, and every
     * one of them logged a deserialization stack trace.
     */
    @Test
    fun `usage endpoint accepts a report with no rate-limit headers`() = testApplication {
        application { mount() }
        val res = client.post("/internal/usage") {
            header("X-Internal-Token", token)
            contentType(ContentType.Application.Json)
            setBody("""{"accountId":1,"userId":$adminId,"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"status":0,"model":null,"ratelimitHeaders":null}""")
        }
        assertEquals(HttpStatusCode.NoContent, res.status)
    }

    /** A gateway that predates the extras must keep working — every new field defaults. */
    @Test
    fun `usage endpoint still accepts a legacy report`() = testApplication {
        application { mount() }
        val res = client.post("/internal/usage") {
            header("X-Internal-Token", token)
            contentType(ContentType.Application.Json)
            setBody("""{"accountId":1,"input":10,"output":2,"cacheRead":0,"cacheWrite":0,"status":200,"model":"opus","ratelimitHeaders":{}}""")
        }
        assertEquals(HttpStatusCode.NoContent, res.status)
    }
}
