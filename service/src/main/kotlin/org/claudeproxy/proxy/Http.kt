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
