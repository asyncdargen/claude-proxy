package org.claudeproxy.proxy

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.TargetAuthenticationStrategy
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.protocol.HttpContext

/** Shared Anthropic HTTP client. A configured proxy never falls back to direct transport. */
object Http {
    private var configuredProxy: AnthropicProxy? = null
    val client: HttpClient by lazy { createClient(configuredProxy) }
    // Public ChatGPT share fetching is not Anthropic traffic.
    internal val publicShareClient: HttpClient by lazy { createClient(null) }

    /** Called before starting any network tasks. Config.load validates the URL first. */
    fun configure(proxy: AnthropicProxy?) { configuredProxy = proxy }

    private val perProxy = java.util.concurrent.ConcurrentHashMap<org.claudeproxy.accounts.AccountProxy, HttpClient>()

    /**
     * The client for an account's upstream calls: its own proxy when it has one, otherwise the
     * shared [client] (direct or the deployment-wide `ANTHROPIC_PROXY_URL`). Clients are cached per
     * proxy config — an edit yields a new key, and the old client is simply no longer picked.
     */
    fun clientFor(proxy: org.claudeproxy.accounts.AccountProxy?): HttpClient =
        if (proxy == null) client else perProxy.computeIfAbsent(proxy) { createAccountProxyClient(it) }

    fun clientFor(account: org.claudeproxy.accounts.AccountRuntime): HttpClient = clientFor(account.proxy)

    /**
     * OkHttp because it is the one Ktor engine with both HTTP CONNECT and SOCKS5. HTTP proxy
     * credentials go only into the CONNECT (proxyAuthenticator answers the 407), never to the
     * origin. SOCKS5 credentials are handed to the JDK socket through [SocksCredentials]; the
     * target hostname travels unresolved, so DNS happens on the proxy side.
     */
    internal fun createAccountProxyClient(p: org.claudeproxy.accounts.AccountProxy): HttpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        upstreamSettings()
        engine {
            config {
                val addr = java.net.InetSocketAddress.createUnresolved(p.host, p.port)
                when (p.type) {
                    org.claudeproxy.model.ProxyType.SOCKS5 -> {
                        SocksCredentials.register(p)
                        proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, addr))
                    }
                    else -> {
                        proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, addr))
                        if (!p.username.isNullOrEmpty()) {
                            val credential = okhttp3.Credentials.basic(p.username, p.password.orEmpty())
                            proxyAuthenticator { _, response ->
                                // one attempt: a second 407 means the credentials are wrong
                                if (response.request.header("Proxy-Authorization") != null) null
                                else response.request.newBuilder().header("Proxy-Authorization", credential).build()
                            }
                        }
                    }
                }
                retryOnConnectionFailure(false)
                connectionPool(okhttp3.ConnectionPool(50, 5, java.util.concurrent.TimeUnit.MINUTES))
                dispatcher(okhttp3.Dispatcher().apply { maxRequests = 1000; maxRequestsPerHost = 1000 })
            }
        }
    }

    /**
     * The JDK's SOCKS client asks the process-wide [java.net.Authenticator] for credentials, keyed
     * by the proxy's host and port. This one answers only SOCKS requests for proxies an account
     * registered, so it cannot leak a password to any other prompt.
     */
    internal object SocksCredentials : java.net.Authenticator() {
        private val byEndpoint = java.util.concurrent.ConcurrentHashMap<String, java.net.PasswordAuthentication>()
        @Volatile private var installed = false

        fun register(p: org.claudeproxy.accounts.AccountProxy) {
            val user = p.username?.takeIf { it.isNotEmpty() } ?: return
            byEndpoint["${p.host.lowercase()}:${p.port}"] = java.net.PasswordAuthentication(user, p.password.orEmpty().toCharArray())
            if (!installed) synchronized(this) {
                if (!installed) { setDefault(this); installed = true }
            }
        }

        override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
            if (requestingProtocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
            return byEndpoint["${requestingHost?.lowercase()}:$requestingPort"]
        }
    }

    internal fun createClient(proxy: AnthropicProxy?, testSslContext: javax.net.ssl.SSLContext? = null): HttpClient = if (proxy == null) {
        HttpClient(CIO) {
            upstreamSettings()
            engine { maxConnectionsCount = 1000 }
        }
    } else {
        // CIO 3.2 forwards Proxy-Authorization inside the tunnel. Apache handles proxy
        // authentication separately, keeping those credentials away from the origin.
        HttpClient(Apache) {
            upstreamSettings()
            engine {
                if (testSslContext != null) sslContext = testSslContext
                customizeClient {
                    setProxy(HttpHost(proxy.host, proxy.port, "http"))
                    setMaxConnTotal(1000)
                    setMaxConnPerRoute(1000)
                    setTargetAuthenticationStrategy(object : TargetAuthenticationStrategy() {
                        override fun isAuthenticationRequested(host: HttpHost, response: HttpResponse, context: HttpContext): Boolean = false
                    })
                    if (proxy.username != null) {
                        setDefaultCredentialsProvider(BasicCredentialsProvider().apply {
                            setCredentials(AuthScope(proxy.host, proxy.port), UsernamePasswordCredentials(proxy.username, proxy.password))
                        })
                    }
                }
            }
        }
    }

    private fun HttpClientConfig<*>.upstreamSettings() {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            // Total time includes streamed responses; socket timeout instead measures silence.
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 30 * 1000
            socketTimeoutMillis = 10 * 60 * 1000
        }
    }
}
