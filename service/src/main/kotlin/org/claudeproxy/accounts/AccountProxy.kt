package org.claudeproxy.accounts

import org.claudeproxy.model.AccountProxyDto
import org.claudeproxy.model.ProxyType
import java.net.URLEncoder

/**
 * Per-account outbound proxy. Every upstream call made *for* this account — the Go datapath,
 * chat, the limit probe, token refresh — goes through it, so the account presents one network
 * origin to Anthropic instead of the server's address for some calls and the proxy's for others.
 * A configured proxy never falls back to direct: a dead proxy fails the attempt and the pool
 * moves on to the next account.
 */
data class AccountProxy(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    /**
     * URL form handed to the Go gateway (`CandidateDto.proxyUrl`), credentials included —
     * it only ever travels over the authenticated internal control API. SOCKS5 is `socks5h`
     * semantics on both sides: the target hostname is resolved by the proxy.
     */
    fun toUrl(): String {
        val scheme = if (type == ProxyType.SOCKS5) "socks5" else "http"
        val auth = username?.takeIf { it.isNotEmpty() }?.let { u ->
            enc(u) + (password?.takeIf { it.isNotEmpty() }?.let { ":" + enc(it) } ?: "") + "@"
        } ?: ""
        val h = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        return "$scheme://$auth$h:$port"
    }

    fun toDto() = AccountProxyDto(
        type = type.name, host = host, port = port,
        username = username, hasPassword = !password.isNullOrEmpty(),
    )

    /** Never print credentials. */
    override fun toString(): String = "AccountProxy(${type.name} $host:$port)"

    companion object {
        private val HOST = Regex("^[A-Za-z0-9.\\-]{1,253}$|^\\[?[0-9A-Fa-f:.]+]?$")

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

        /** Validation error for operator input, or null when it is usable. */
        fun validate(type: ProxyType, host: String?, port: Int?): String? {
            if (type == ProxyType.NONE) return null
            if (host.isNullOrBlank() || !HOST.matches(host.trim())) return "proxy host must be a hostname or IP address"
            if (port == null || port !in 1..65535) return "proxy port must be 1..65535"
            return null
        }
    }
}
