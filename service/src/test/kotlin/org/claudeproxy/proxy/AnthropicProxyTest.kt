package org.claudeproxy.proxy

import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.claudeproxy.Config
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.*

class AnthropicProxyTest {
    @Test fun `validation rejects unsupported or malformed settings without secrets`() {
        assertNull(AnthropicProxy.parse(null))
        assertNull(AnthropicProxy.parse(""))
        assertEquals(80, AnthropicProxy.parse("http://localhost")!!.port)
        assertEquals("secret:pass", AnthropicProxy.parse("http://user:secret%3Apass@localhost:8080/")!!.password)
        for (value in listOf(" ", "http://user%3Aname:secret@host", "http://user:secret%0A@host", "https://user:secret@host:123", "socks5://user:secret@host", "http://user:secret@host:0", "http://user:secret@host:65536", "http://user:secret@host:", "http://user:secret@host/a", "http://user:secret@host?x", "http://user:secret@host#x", "http://user:secret@", "http://user:secret@host:bad")) {
            val error = assertFailsWith<IllegalArgumentException> { AnthropicProxy.parse(value) }
            assertFalse(error.toString().contains("secret"))
            assertNull(error.cause)
        }
        assertFalse(AnthropicProxy.parse("http://user:secret@localhost")!!.toString().contains("secret"))
    }

    @Test fun `invalid proxy fails runtime configuration loading`() {
        val oldKey = System.getProperty("MASTER_KEY")
        val oldProxy = System.getProperty("ANTHROPIC_PROXY_URL")
        try {
            System.setProperty("MASTER_KEY", "test-master-key-32-chars-minimum-xx")
            System.setProperty("ANTHROPIC_PROXY_URL", "https://user:secret@localhost:123")
            assertFailsWith<IllegalArgumentException> { Config.load() }
        } finally {
            if (oldKey == null) System.clearProperty("MASTER_KEY") else System.setProperty("MASTER_KEY", oldKey)
            if (oldProxy == null) System.clearProperty("ANTHROPIC_PROXY_URL") else System.setProperty("ANTHROPIC_PROXY_URL", oldProxy)
        }
    }

    @Test fun `unset proxy reaches origin directly and unreachable configured proxy never falls back`() = runBlocking {
        val hits = AtomicInteger()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        origin.start()
        try {
            val url = "http://127.0.0.1:${origin.address.port}/"
            Http.createClient(null).use { assertEquals("ok", it.get(url).bodyAsText()) }
            val closedPort = ServerSocket(0).use { it.localPort }
            Http.createClient(AnthropicProxy.parse("http://127.0.0.1:$closedPort")).use { client ->
                assertFails { client.get(url) }
            }
            assertEquals(1, hits.get())
        } finally { origin.stop(0) }
    }

    @Test fun `CONNECT authenticates only to proxy and origin 401 cannot obtain proxy credentials`() = runBlocking {
        val ssl = tlsContext()
        val originHeaders = CopyOnWriteArrayList<Map<String, List<String>>>()
        val origin = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.httpsConfigurator = HttpsConfigurator(ssl)
        origin.createContext("/") { exchange ->
            originHeaders.add(exchange.requestHeaders.toMap())
            val status = when (exchange.requestURI.path) { "/challenge" -> 401; "/redirect" -> 302; else -> 200 }
            if (status == 302) exchange.responseHeaders.add("Location", "https://localhost:${origin.address.port}/")
            if (status == 401) exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=origin")
            exchange.sendResponseHeaders(status, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        origin.start()
        val auth = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())
        TunnelProxy(auth, origin.address.port).use { proxy ->
            try {
                Http.createClient(AnthropicProxy.parse("http://user:secret@127.0.0.1:${proxy.port}")).use { client ->
                    val error = assertFails { withTimeout(5_000) { client.get("https://localhost:${origin.address.port}/") } }
                    assertTrue(generateSequence(error) { it.cause }.any { it is javax.net.ssl.SSLException })
                }
                assertEquals(0, originHeaders.size)
                Http.createClient(AnthropicProxy.parse("http://user:secret@127.0.0.1:${proxy.port}"), ssl).use { client ->
                    assertEquals("ok", client.get("https://localhost:${origin.address.port}/") {
                        header("Authorization", "Bearer account-test")
                    }.bodyAsText())
                    assertEquals(401, client.get("https://localhost:${origin.address.port}/challenge").status.value)
                    assertEquals(302, client.get("https://localhost:${origin.address.port}/redirect").status.value)
                }
                assertTrue(proxy.requests.any { it.first().startsWith("CONNECT localhost:${origin.address.port} ") })
                assertTrue(proxy.requests.any { lines -> lines.any { it.equals("Proxy-Authorization: $auth", true) } })
                assertEquals(3, originHeaders.size)
                assertEquals(listOf("Bearer account-test"), originHeaders.first().entries.single { it.key.equals("Authorization", true) }.value)
                originHeaders.forEachIndexed { index, headers ->
                    assertFalse(headers.keys.any { it.equals("Proxy-Authorization", true) })
                    if (index > 0) assertFalse(headers.keys.any { it.equals("Authorization", true) })
                }
            } finally { origin.stop(0) }
        }
    }

    @Test fun `proxy authentication rejection never reaches origin`() = runBlocking {
        val hits = AtomicInteger()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { hits.incrementAndGet(); it.close() }
        origin.start()
        try {
            TunnelProxy("Basic rejected", origin.address.port).use { proxy ->
                Http.createClient(AnthropicProxy.parse("http://user:secret@127.0.0.1:${proxy.port}")).use { client ->
                    assertEquals(407, client.get("https://localhost:${origin.address.port}/").status.value)
                }
                assertTrue(proxy.requests.isNotEmpty())
                assertEquals(0, hits.get())
            }
        } finally { origin.stop(0) }
    }

    @Test fun `authenticated CONNECT delivers SSE before the origin finishes the response`() = runBlocking {
        val ssl = tlsContext()
        val firstFrameRead = CountDownLatch(1)
        val finalFrameSent = AtomicBoolean(false)
        val origin = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.httpsConfigurator = HttpsConfigurator(ssl)
        origin.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write("data: first\n\n".toByteArray())
                output.flush()
                // A buffering client cannot release this gate; both sides have bounded waits.
                if (firstFrameRead.await(5, TimeUnit.SECONDS)) {
                    output.write("data: final\n\n".toByteArray())
                    output.flush()
                    finalFrameSent.set(true)
                }
            }
        }
        origin.start()
        val auth = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())
        try {
            TunnelProxy(auth, origin.address.port).use { proxy ->
                Http.createClient(AnthropicProxy.parse("http://user:secret@127.0.0.1:${proxy.port}"), ssl).use { client ->
                    withTimeout(5_000) {
                        client.prepareGet("https://localhost:${origin.address.port}/").execute { response ->
                            assertEquals(200, response.status.value)
                            val channel = response.bodyAsChannel()
                            assertEquals("data: first", channel.readUTF8Line())
                            assertEquals("", channel.readUTF8Line())
                            assertFalse(finalFrameSent.get())
                            firstFrameRead.countDown()
                            assertEquals("data: final", channel.readUTF8Line())
                            assertEquals("", channel.readUTF8Line())
                            assertNull(channel.readUTF8Line())
                        }
                    }
                }
                assertTrue(finalFrameSent.get())
                assertTrue(proxy.requests.any { lines -> lines.any { it.equals("Proxy-Authorization: $auth", true) } })
            }
        } finally {
            firstFrameRead.countDown()
            origin.stop(0)
        }
    }

    private fun tlsContext(): SSLContext {
        val dir = Files.createTempDirectory("proxy-tls-test")
        val file = dir.resolve("test.p12")
        try {
            val keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", "testpass", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            check(process.waitFor() == 0)
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, "testpass".toCharArray()) } }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "testpass".toCharArray()) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir) }
    }

    private class TunnelProxy(private val expectedAuth: String, private val originPort: Int) : AutoCloseable {
        private val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<List<String>>()
        val port get() = server.localPort
        init {
            executor.submit {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    sockets.add(socket)
                    executor.submit { handle(socket) }
                }
            }
        }
        private fun handle(socket: Socket) {
            socket.use {
                val input = socket.getInputStream()
                val lines = mutableListOf<String>()
                while (true) {
                    val line = StringBuilder()
                    while (true) { val b = input.read(); if (b < 0) return; if (b == 10) break; if (b != 13) line.append(b.toChar()) }
                    if (line.isEmpty()) break
                    lines.add(line.toString())
                }
                requests.add(lines)
                if (lines.none { it.equals("Proxy-Authorization: $expectedAuth", true) }) {
                    socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=proxy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                Socket("127.0.0.1", originPort).use { target ->
                    sockets.add(target)
                    socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    val upstream = executor.submit {
                        try { input.copyTo(target.getOutputStream()) } catch (_: Exception) {}
                        finally { runCatching { target.shutdownOutput() } }
                    }
                    try { target.getInputStream().copyTo(socket.getOutputStream()) } catch (_: Exception) {}
                    upstream.cancel(true)
                }
            }
        }
        override fun close() { server.close(); sockets.forEach { it.close() }; executor.shutdownNow() }
    }
}
