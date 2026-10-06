package org.claudeproxy.proxy

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Proxy credentials are deliberately excluded from diagnostics and configuration toString. */
class AnthropicProxy private constructor(
    val host: String,
    val port: Int,
    internal val username: String?,
    internal val password: String?,
) {
    override fun toString(): String = "AnthropicProxy(configured)"

    companion object {
        fun parse(value: String?): AnthropicProxy? {
            if (value.isNullOrEmpty()) return null
            // Never retain URI parser exceptions: their messages contain the complete input.
            val uri = try { URI(value) } catch (_: Exception) {
                throw IllegalArgumentException("ANTHROPIC_PROXY_URL must be a valid HTTP proxy URL")
            }
            require(uri.scheme == "http") { "ANTHROPIC_PROXY_URL supports only http CONNECT proxies" }
            require(!uri.host.isNullOrBlank() && uri.rawAuthority != null) {
                "ANTHROPIC_PROXY_URL requires a host"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "ANTHROPIC_PROXY_URL must not contain a path"
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "ANTHROPIC_PROXY_URL must not contain a query or fragment"
            }
            // URI.port reports -1 both for an absent port and malformed authorities.
            val authority = uri.rawAuthority.substringAfterLast('@')
            val portText = if (authority.startsWith('[')) authority.substringAfter(']').removePrefix(":")
                else authority.substringAfter(':', "")
            require(portText.isEmpty() && !authority.endsWith(':') ||
                portText.isNotEmpty() && portText.all { it in '0'..'9' } && uri.port in 1..65535) {
                "ANTHROPIC_PROXY_URL requires a port between 1 and 65535"
            }
            val userInfo = uri.rawUserInfo
            fun decode(part: String): String = URLDecoder.decode(part.replace("+", "%2B"), StandardCharsets.UTF_8)
            val username = userInfo?.substringBefore(':')?.let(::decode)
            val password = userInfo?.substringAfter(':', "")?.let(::decode)
            require(username == null || username.isNotEmpty() && !username.contains(':') &&
                (username + password.orEmpty()).none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "ANTHROPIC_PROXY_URL contains invalid credentials"
            }
            return AnthropicProxy(uri.host.removeSurrounding("[", "]"), if (uri.port == -1) 80 else uri.port, username, password)
        }
    }
}
