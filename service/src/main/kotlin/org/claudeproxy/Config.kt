package org.claudeproxy

import java.io.File
import org.claudeproxy.proxy.AnthropicProxy

/**
 * Runtime configuration, read from environment variables with sane defaults.
 * A local `.env` file (KEY=VALUE lines) is loaded first if present, so both local
 * and server deployments configure the same way.
 */
data class Config(
    val bindHost: String,
    val port: Int,
    val publicDomain: String?,
    val dbPath: String,
    val masterKey: String,
    val sessionSecret: String,
    val adminUser: String,
    val adminPassword: String,
    val upstreamBaseUrl: String,
    // Canonical URL clients use to reach the proxy (shown on the Tokens page).
    // Empty => the UI falls back to the browser's current origin.
    val publicBaseUrl: String,
    // Postgres JDBC URL (jdbc:postgresql://host:port/db). Empty => use SQLite at dbPath.
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    // Shared secret gating the private /internal/* control API (used by the Go gateway).
    // Null during rollout — the datapath still works without it.
    val internalToken: String?,
    val anthropicProxy: AnthropicProxy? = null,
) {
    companion object {
        fun load(): Config {
            loadDotEnv()
            fun env(key: String, default: String? = null): String? =
                envOrProp(key) ?: default

            val masterKey = env("MASTER_KEY")
                ?: error("MASTER_KEY is required (32+ char secret used to encrypt account credentials at rest)")
            val sessionSecret = env("SESSION_SECRET") ?: masterKey
            val publicDomain = env("PUBLIC_DOMAIN")
            val publicBaseUrl = env("PUBLIC_BASE_URL")
                ?: publicDomain?.let { "https://$it" }
                ?: ""

            return Config(
                bindHost = env("BIND_HOST", "127.0.0.1")!!,
                port = env("PORT", "8787")!!.toInt(),
                publicDomain = publicDomain,
                dbPath = env("DB_PATH", "data/claude-proxy.db")!!,
                masterKey = masterKey,
                sessionSecret = sessionSecret,
                adminUser = env("ADMIN_USER", "admin")!!,
                adminPassword = env("ADMIN_PASSWORD", "admin")!!,
                upstreamBaseUrl = env("UPSTREAM_BASE_URL", "https://api.anthropic.com")!!.trimEnd('/'),
                publicBaseUrl = publicBaseUrl.trimEnd('/'),
                databaseUrl = env("DATABASE_URL", "")!!,
                databaseUser = env("DATABASE_USER", "claudeproxy")!!,
                databasePassword = env("DATABASE_PASSWORD", "")!!,
                internalToken = env("INTERNAL_TOKEN"),
                anthropicProxy = AnthropicProxy.parse(System.getenv("ANTHROPIC_PROXY_URL") ?: System.getProperty("ANTHROPIC_PROXY_URL")),
            )
        }

        private fun loadDotEnv() {
            val file = File(".env")
            if (!file.exists()) return
            file.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                var value = line.substring(idx + 1).trim()
                if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
                    value = value.substring(1, value.length - 1)
                }
                // Do not override real environment variables.
                if (System.getenv(key) == null) {
                    System.setProperty(key, value)
                }
            }
        }
    }
}

/** Env lookup that also honors values loaded from .env into system properties. */
fun envOrProp(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: System.getProperty(key)?.takeIf { it.isNotBlank() }
