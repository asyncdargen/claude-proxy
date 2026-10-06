package org.claudeproxy.proxy

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.claudeproxy.Config
import org.claudeproxy.accounts.*
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.model.AccountType
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class UpstreamForwarderPrivacyTest {
    @Test fun `legacy forwarding strips client origin and uses account credentials`() {
        val db = File.createTempFile("forwarder-privacy", ".db")
        val key = "test-secret-at-least-32-characters"
        val received = LinkedBlockingQueue<Map<String, List<String>>>()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            received.put(exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() })
            val response = """{"type":"message","model":"claude-opus-4-8","usage":{"input_tokens":1,"output_tokens":1}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        upstream.start()
        try {
            Secrets.init(Crypto(key))
            Db.init(Config(bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = db.absolutePath,
                masterKey = key, sessionSecret = key, adminUser = "admin", adminPassword = "admin",
                upstreamBaseUrl = "http://127.0.0.1:${upstream.address.port}", publicBaseUrl = "",
                databaseUrl = "", databaseUser = "", databasePassword = "", internalToken = null))
            MemoryCache.clear()
            val pool = AccountPool()
            val accounts = runBlocking {
                val ids = listOf(AccountType.OAUTH, AccountType.API_KEY).map { type ->
                    AccountRepo.create("privacy-$type", type, null, 10, 0.9, 1.0,
                        AccountSecret(accessToken = "fake-connected-oauth", apiKey = "fake-connected-api-key"), createdBy = null)
                }
                pool.reload()
                ids.map { pool.get(it)!! }
            }
            val privateHeaders = listOf(
                "Cookie", "Cookie2", "Proxy-Authorization", "Proxy-Authenticate", "Forwarded",
                "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port",
                "X-Forwarded-Client-Ip", "X-Real-Ip", "X-Client-Ip", "X-Cluster-Client-Ip",
                "X-Originating-Ip", "X-Original-Forwarded-For", "CF-Connecting-Ip",
                "CF-Connecting-IPv6", "True-Client-Ip", "Fastly-Client-Ip", "X-Client-Id", "X-Stainless-Lang",
            )
            val body = """{"model":"claude-opus-4-8","max_tokens":32,"messages":[{"role":"user","content":"hello"}]}"""
            val forwarder = UpstreamForwarder(pool, "http://127.0.0.1:${upstream.address.port}")
            testApplication {
                application {
                    routing {
                        accounts.forEach { account ->
                            post("/${account.type}") {
                                forwarder.forward(call, account, "/v1/messages", body.toByteArray(), null)
                            }
                        }
                    }
                }
                accounts.forEach { account ->
                    val response = client.post("/${account.type}") {
                        setBody(body)
                        header("Content-Type", "application/json")
                        header("Authorization", "Bearer fake-client-token")
                        header("X-Api-Key", "fake-client-api-key")
                        header("Anthropic-Version", "2023-06-01")
                        header("Anthropic-Beta", "context-management-2025-06-27")
                        privateHeaders.forEach { header(it, "client-private-198.51.100.23") }
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val headers = assertNotNull(received.poll(5, TimeUnit.SECONDS), "local upstream never received request")
                    privateHeaders.forEach { name -> assertTrue(name.lowercase() !in headers, "client $name leaked") }
                    assertEquals(if (account.type == AccountType.OAUTH) listOf("Bearer fake-connected-oauth") else null, headers["authorization"])
                    assertEquals(if (account.type == AccountType.API_KEY) listOf("fake-connected-api-key") else null, headers["x-api-key"])
                    assertEquals(listOf("2023-06-01"), headers["anthropic-version"])
                    assertEquals(listOf("application/json"), headers["content-type"])
                    assertEquals(listOf("context-management-2025-06-27" + if (account.type == AccountType.OAUTH) ",oauth-2025-04-20" else ""), headers["anthropic-beta"])
                }
            }
        } finally {
            upstream.stop(0)
            MemoryCache.clear()
            db.delete()
        }
    }
}
