package org.claudeproxy.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.claudeproxy.Config
import org.claudeproxy.accounts.*
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.db.*
import org.claudeproxy.model.*
import org.claudeproxy.repo.*
import org.jetbrains.exposed.sql.insert
import java.io.File
import java.time.Instant
import kotlin.test.*

class TokenUsageRoutesTest {
    private lateinit var db: File
    private lateinit var pool: AccountPool
    private var user = 0
    private var own = 0
    private var bob = 0
    private var shared = 0
    private var bobOwn = 0
    private var forbidden = 0
    private lateinit var token: String
    private lateinit var routingToken: String

    @BeforeTest fun setup() {
        db = File.createTempFile("token-usage", ".db")
        val secret = "test-secret-at-least-32-characters"
        Secrets.init(Crypto(secret))
        Db.init(Config(bindHost="127.0.0.1", port=8787, publicDomain=null, dbPath=db.absolutePath,
            masterKey=secret, sessionSecret=secret, adminUser="admin", adminPassword="admin",
            upstreamBaseUrl="https://example.invalid", publicBaseUrl="", databaseUrl="",
            databaseUser="", databasePassword="", internalToken=null))
        MemoryCache.clear()
        TokenAttributionCache.clear()
        user = UserRepo.create("alice", "pw", listOf("user"), emptyList(), null)
        bob = UserRepo.create("bob", "pw", listOf("user"), emptyList(), null)
        own = account("alice-private-name", user)
        bobOwn = account("bob-private-name", bob)
        shared = account("shared-private-name", null)
        forbidden = account("forbidden-group", null, GroupRepo.create("secret-group"))
        pool = AccountPool()
        runBlocking {
            pool.reload()
            pool.updateLimit(own, LimitState(windows=mapOf(WindowKind.FIVE_HOUR to WindowLimit(
                utilization=0.42, resetAt=Instant.now().plusSeconds(3600), updatedAt=Instant.now()))))
        }
        token = ProxyTokenRepo.create(user, "proxy").token!!
        routingToken = RoutingTokenRepo.create(user, "routing").token!!
    }
    private fun account(name: String, owner: Int?, group: Int? = null) = AccountRepo.create(
        name=name, type=AccountType.OAUTH, groupId=group, priority=10, threshold=0.9,
        kind=org.claudeproxy.model.AccountKind.PRO, secret=AccountSecret(accessToken="upstream-secret",refreshToken="refresh-secret"),
        createdBy=null, ownerId=owner)
    @AfterTest fun cleanup() { MemoryCache.clear(); db.delete() }
    private fun io.ktor.server.application.Application.mount() {
        install(ContentNegotiation) { json(Json { encodeDefaults=true }) }
        routing { tokenUsageRoutes(pool, DatapathService(pool)) }
    }
    @Test fun `missing and invalid tokens are rejected`() = testApplication {
        application { mount() }
        for (value in listOf("", "Bearer bad")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/gateway/v1/usage") { header("Authorization",value) }.status)
        }
    }
    @Test fun `scoped snapshots contain next candidate but no identities or secrets`() = testApplication {
        application { mount() }
        val response = client.get("/gateway/v1/usage") { header("Authorization","Bearer $token") }
        assertEquals(HttpStatusCode.OK,response.status)
        assertEquals("no-store",response.headers["Cache-Control"])
        val text=response.bodyAsText(); val body=Json.parseToJsonElement(text).jsonObject
        assertEquals(2,body["accounts"]!!.jsonArray.size)
        assertEquals(0,body["next_account_index"]!!.jsonPrimitive.int)
        assertEquals(42.0,body["rate_limits"]!!.jsonObject["five_hour"]!!.jsonObject["used_percentage"]!!.jsonPrimitive.double)
        for (secret in listOf("alice-private-name","bob-private-name","shared-private-name","forbidden-group","upstream-secret","refresh-secret",token,"authHeaders","deviceId")) assertFalse(text.contains(secret),secret)
        assertEquals(0L,UsageRepo.userTotals(user,UserRepo.startOfUtcDay()).requests)
    }
    @Test fun `attribution detail names scoped accounts and splits the current window by user`() = testApplication {
        application { mount() }
        val t = Instant.now().minusSeconds(600)
        val carol = UserRepo.create("carol", "pw", listOf("user"), emptyList(), null)
        org.jetbrains.exposed.sql.transactions.transaction {
            // out of alice's scope: another user's personal account and a group she is not in
            for (hidden in listOf(bobOwn, forbidden)) {
                for ((seconds, util) in listOf(0L to 0.1, 60L to 0.5)) WindowSnapshots.insert {
                    it[accountId] = hidden; it[windowKind] = "5h"; it[ts] = t.plusSeconds(seconds); it[utilization] = util
                }
                UsageEvents.insert {
                    it[accountId] = hidden; it[userId] = carol; it[ts] = t.plusSeconds(30); it[cost] = 2.0; it[inputTokens] = 20
                }
            }
            for ((seconds, util) in listOf(0L to 0.2, 60L to 0.6, 120L to 0.8)) WindowSnapshots.insert {
                it[accountId] = shared; it[windowKind] = "5h"; it[ts] = t.plusSeconds(seconds); it[utilization] = util
            }
            for ((seconds, who, spent) in listOf(Triple(20L, user, 1.0), Triple(40L, bob, 3.0), Triple(90L, user, 1.0))) UsageEvents.insert {
                it[accountId] = shared; it[userId] = who; it[ts] = t.plusSeconds(seconds); it[cost] = spent
                it[inputTokens] = 20
            }
        }
        suspend fun fetch(detail: String) = Json.parseToJsonElement(
            client.get("/gateway/v1/usage?attribution=$detail") { header("Authorization", "Bearer $token") }.bodyAsText()).jsonObject
        val text = client.get("/gateway/v1/usage?attribution=full") { header("Authorization", "Bearer $token") }.bodyAsText()
        for (hidden in listOf("bob-private-name", "forbidden-group", "carol", "upstream-secret", "refresh-secret", token, "accountId", "userId")) assertFalse(text.contains(hidden), hidden)
        val accounts = fetch("full")["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("alice-private-name", "shared-private-name"), accounts.map { it["name"]!!.jsonPrimitive.content })
        // a personal account has one spender, so nothing is attributed
        assertNull(accounts[0]["attribution"])
        val window = accounts[1]["attribution"]!!.jsonObject["five_hour"]!!.jsonObject
        assertEquals(JsonNull, accounts[1]["attribution"]!!.jsonObject["seven_day"])
        assertEquals(80.0, window["total_percentage"]!!.jsonPrimitive.double, 1e-9)
        // 20% was read before any request; alice 0.4 * 1/4 + 0.2, bob 0.4 * 3/4
        assertEquals(30.0, window["mine_percentage"]!!.jsonPrimitive.double, 1e-9)
        val people = window["contributors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("alice" to true, "bob" to false, "Unattributed" to false).toSet(),
            people.map { it["username"]!!.jsonPrimitive.content to it["me"]!!.jsonPrimitive.boolean }.toSet())
        val points = window["points"]!!.jsonArray
        assertEquals(3, points.size)
        assertEquals(30.0, points.last().jsonObject["values"]!!.jsonObject["bob"]!!.jsonPrimitive.double, 1e-9)
        val summary = fetch("summary")["accounts"]!!.jsonArray[1].jsonObject["attribution"]!!.jsonObject["five_hour"]!!.jsonObject
        assertNull(summary["points"])
        assertEquals(30.0, summary["mine_percentage"]!!.jsonPrimitive.double, 1e-9)
        // without the parameter the entries keep their exact keys
        for (plain in listOf(fetch("bogus"), Json.parseToJsonElement(
            client.get("/gateway/v1/usage") { header("Authorization", "Bearer $token") }.bodyAsText()).jsonObject)) {
            assertEquals(setOf("scope", "available", "rate_limits"), plain["accounts"]!!.jsonArray[1].jsonObject.keys)
        }
    }
    @Test fun `attribution is computed once per interval and its failure keeps the quota snapshot`() = testApplication {
        application { mount() }
        var loads = 0
        TokenAttributionCache.load = { loads++; throw IllegalStateException("db busy") }
        repeat(2) {
            val response = client.get("/gateway/v1/usage?attribution=summary") { header("Authorization", "Bearer $token") }
            assertEquals(HttpStatusCode.OK, response.status)
            val entry = Json.parseToJsonElement(response.bodyAsText()).jsonObject["accounts"]!!.jsonArray[1].jsonObject
            assertEquals("shared-private-name", entry["name"]!!.jsonPrimitive.content)
            assertNull(entry["attribution"])
        }
        assertEquals(1, loads)
    }
    @Test fun `routing keys work and disabled users and revoked tokens fail immediately`() = testApplication {
        application { mount() }
        suspend fun query() = client.get("/gateway/v1/usage") { header("x-api-key",routingToken) }
        assertEquals(HttpStatusCode.OK,query().status)
        UserRepo.update(user,null,false,null,null,null,false)
        assertTrue(query().status in listOf(HttpStatusCode.Unauthorized,HttpStatusCode.Forbidden))
        UserRepo.update(user,null,true,null,null,null,false)
        val id=RoutingTokenRepo.listForUser(user).single().id
        RoutingTokenRepo.setEnabled(id,user,false)
        assertEquals(HttpStatusCode.Unauthorized,query().status)
    }
    @Test fun `daily cap prevents shared selection but preserves personal access`() = testApplication {
        application { mount() }
        UserRepo.update(user,null,null,null,null,0.0,false)
        val response=client.get("/gateway/v1/usage") { header("Authorization","Bearer $token") }
        val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["daily"]!!.jsonObject["exhausted"]!!.jsonPrimitive.boolean)
        assertEquals(1,body["available_accounts"]!!.jsonPrimitive.int)
        assertEquals("personal",body["accounts"]!!.jsonArray[0].jsonObject["scope"]!!.jsonPrimitive.content)
    }
    @Test fun `unknown and expired readings are explicitly stale`() {
        val now=Instant.now()
        assertNull(tokenUsageWindow(null,now))
        val expired=tokenUsageWindow(WindowLimit(utilization=1.0,resetAt=now.minusSeconds(1),updatedAt=now),now)!!
        assertTrue(expired.stale)
        assertEquals(100.0,expired.used_percentage)
        assertTrue(tokenUsageWindow(WindowLimit(),now)!!.stale)
    }
}
