package org.claudeproxy.api

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.claudeproxy.auth.*
import org.claudeproxy.repo.*
import kotlin.test.*

class WindowAttributionRoutesTest {
    private fun Application.mount() {
        installSecurity(TEST_SECRET)
        install(ContentNegotiation) { json(Json { encodeDefaults=true }) }
        install(StatusPages) {
            exception<ForbiddenException> { call,_ -> call.respond(HttpStatusCode.Forbidden) }
            exception<UnauthorizedException> { call,_ -> call.respond(HttpStatusCode.Unauthorized) }
        }
        routing {
            get("/test-login/{id}") {
                call.setUserSession(UserRepo.findAuth(call.parameters["id"]!!.toInt())!!)
                call.respond("ok")
            }
            route("/api") { windowAttributionRoutes() }
        }
    }
    @Test fun `response keeps accounts separate excludes personal and preserves no-data and stale readings`() = testApplication {
        val file=freshDb()
        try {
            val now=java.time.Instant.now()
            fun account(name: String, owner: Int? = null) =
                org.claudeproxy.accounts.AccountRepo.create(name,org.claudeproxy.model.AccountType.OAUTH,null,1,0.9,1.0,
                    org.claudeproxy.accounts.AccountSecret(accessToken="upstream-secret"),null,ownerId=owner)
            val shared=account("shared")
            val openai=account("weekly-only")
            val empty=account("no-readings")
            account("private-secret-name",owner=1)
            org.claudeproxy.accounts.AccountRepo.persistLimit(openai,org.claudeproxy.model.LimitState(windows=mapOf(
                org.claudeproxy.model.WindowKind.WEEKLY to org.claudeproxy.model.WindowLimit(utilization=0.35,resetAt=now.plusSeconds(1000),updatedAt=now.minusSeconds(60)))))
            org.claudeproxy.accounts.AccountRepo.persistLimit(shared,org.claudeproxy.model.LimitState(windows=mapOf(
                org.claudeproxy.model.WindowKind.FIVE_HOUR to org.claudeproxy.model.WindowLimit(utilization=1.1,resetAt=now.minusSeconds(1),updatedAt=now.minusSeconds(100)))))
            application { mount() }
            val cookie=client.get("/test-login/1").headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val response=client.get("/api/stats/window-attribution") { header(HttpHeaders.Cookie,cookie) }
            assertEquals(HttpStatusCode.OK,response.status)
            val text=response.bodyAsText()
            assertFalse(text.contains("private-secret-name"))
            assertFalse(text.contains("upstream-secret"))
            val body=Json.decodeFromString<WindowAttributionResponse>(text)
            assertEquals(setOf(shared,openai,empty),body.accounts.map { it.accountId }.toSet())
            val oa=body.accounts.single { it.accountId == openai }
            assertEquals("ANTHROPIC",oa.provider)
            assertTrue(oa.windows.single { it.kind == "5h" }.periods.isEmpty())
            assertEquals(0.35,oa.windows.single { it.kind == "7d" }.periods.single().total)
            assertTrue(body.accounts.single { it.accountId == empty }.windows.all { it.periods.isEmpty() })
            val stale=body.accounts.single { it.accountId == shared }.windows.single { it.kind == "5h" }.periods.single()
            assertFalse(stale.current)
            assertEquals(now.minusSeconds(100).toEpochMilli(),java.time.Instant.parse(stale.observedAt).toEpochMilli())
            assertEquals(1.1,stale.total)
            assertEquals(1.1,stale.contributors.single().amount)
        } finally { file.delete() }
    }

    @Test fun `route requires independent permission and validates days`() = testApplication {
        val file=freshDb()
        try {
            RoleRepo.create("stats-only",listOf("STATS_VIEW"))
            RoleRepo.create("attribution-only",listOf("STATS_VIEW_WINDOW_ATTRIBUTION"))
            val denied=UserRepo.create("denied","pw",listOf("stats-only"),emptyList(),null)
            val allowed=UserRepo.create("allowed","pw",listOf("attribution-only"),emptyList(),null)
            application { mount() }
            assertEquals(HttpStatusCode.Unauthorized,client.get("/api/stats/window-attribution").status)
            val deniedCookie=client.get("/test-login/$denied").headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            assertEquals(HttpStatusCode.Forbidden,client.get("/api/stats/window-attribution") { header(HttpHeaders.Cookie,deniedCookie) }.status)
            for (id in listOf(allowed,1)) {
                val cookie=client.get("/test-login/$id").headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                assertEquals(HttpStatusCode.OK,client.get("/api/stats/window-attribution?days=30") { header(HttpHeaders.Cookie,cookie) }.status)
                for (days in listOf("0","91","abc")) assertEquals(HttpStatusCode.BadRequest,client.get("/api/stats/window-attribution?days=$days") { header(HttpHeaders.Cookie,cookie) }.status)
            }
            val permissions=UserRepo.findAuth(allowed)!!.permissions.map { it.name }
            assertEquals(listOf("STATS_VIEW_WINDOW_ATTRIBUTION"),permissions)
        } finally { file.delete() }
    }
}
