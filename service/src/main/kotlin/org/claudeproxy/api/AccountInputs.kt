package org.claudeproxy.api

import org.claudeproxy.accounts.AccountProxy
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountSecret
import org.claudeproxy.model.AccountKind
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.ApiAuthStyle
import org.claudeproxy.model.ApiKeyMode
import org.claudeproxy.model.ProxyType
import java.net.InetAddress
import java.net.URI

/**
 * Validation shared by the three account surfaces (global, "My Accounts", admin oversight of a
 * user's accounts), so they cannot drift on what an account may be configured to.
 *
 * [personal] tightens the network rules. A personal account is configured by an ordinary user,
 * and its base URL / proxy decide where *this server* opens connections: left open, that is a way
 * to make the gateway talk to the compose network or the host's loopback. Personal upstreams must
 * therefore be `https` on a public address; the operator (global accounts) may point anywhere.
 */
internal class AccountCreate(
    val type: AccountType,
    val apiKeyMode: ApiKeyMode,
    val kind: AccountKind,
    val baseUrl: String?,
    val apiAuthStyle: ApiAuthStyle,
    val secret: AccountSecret,
)

internal class InputError(message: String) : Exception(message)

internal fun CreateAccountRequest.validated(personal: Boolean): AccountCreate {
    val type = AccountType.fromString(type) ?: throw InputError("Unknown type $type")
    val mode = ApiKeyMode.fromString(apiKeyMode) ?: throw InputError("Unknown API key mode $apiKeyMode")
    val kind = AccountKind.fromString(kind) ?: throw InputError("Unknown account kind $kind")
    val style = ApiAuthStyle.fromString(apiAuthStyle) ?: throw InputError("Unknown auth style $apiAuthStyle")
    val secret = when (type) {
        AccountType.API_KEY -> AccountSecret(apiKey = apiKey?.trim()?.ifEmpty { null } ?: throw InputError("apiKey required"))
        AccountType.OAUTH, AccountType.OAUTH_STATIC -> AccountSecret(
            accessToken = accessToken ?: throw InputError("accessToken required"),
            refreshToken = refreshToken, expiresAt = expiresAt,
        )
    }
    val base = if (type == AccountType.API_KEY) normalizeBaseUrl(baseUrl, personal) else null
    proxy?.let { validateProxy(it, personal) }
    return AccountCreate(type, mode, kind, base, style, secret)
}

/** Parsed optional enum fields of an update; each null = leave alone. */
internal class AccountUpdate(
    val apiKeyMode: ApiKeyMode?,
    val kind: AccountKind?,
    val baseUrl: String?,
    val apiAuthStyle: ApiAuthStyle?,
)

internal fun UpdateAccountRequest.validated(personal: Boolean): AccountUpdate {
    val mode = apiKeyMode?.let { ApiKeyMode.fromString(it) ?: throw InputError("Unknown API key mode $it") }
    val kind = kind?.let { AccountKind.fromString(it) ?: throw InputError("Unknown account kind $it") }
    val style = apiAuthStyle?.let { ApiAuthStyle.fromString(it) ?: throw InputError("Unknown auth style $it") }
    // "" clears the custom upstream (back to api.anthropic.com)
    val base = baseUrl?.let { if (it.isBlank()) "" else normalizeBaseUrl(it, personal) }
    proxy?.let { validateProxy(it, personal) }
    return AccountUpdate(mode, kind, base, style)
}

/** Persist [input] for account [id]; null leaves the proxy untouched. */
internal fun applyProxy(id: Int, input: ProxyInput?) {
    input ?: return
    val type = ProxyType.fromString(input.type) ?: ProxyType.NONE
    AccountRepo.setProxy(id, type, input.host, input.port, input.username, input.password)
}

/** The proxy described by [p], validated — for calls made before the account row exists (OAuth login). */
internal fun proxyFromInput(p: ProxyInput?, personal: Boolean): AccountProxy? {
    p ?: return null
    validateProxy(p, personal)
    val type = ProxyType.fromString(p.type) ?: return null
    if (type == ProxyType.NONE) return null
    return AccountProxy(type, p.host!!.trim(), p.port!!, p.username?.trim()?.ifEmpty { null }, p.password?.ifEmpty { null })
}

internal fun normalizeBaseUrl(raw: String?, personal: Boolean): String? {
    val value = raw?.trim()?.ifEmpty { null } ?: return null
    val uri = try { URI(value) } catch (_: Exception) { throw InputError("base URL is not a valid URL") }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") throw InputError("base URL must be http(s)://")
    if (uri.host.isNullOrBlank()) throw InputError("base URL needs a host")
    if (uri.rawUserInfo != null) throw InputError("base URL must not carry credentials — put the token in the API key field")
    if (uri.rawQuery != null || uri.rawFragment != null) throw InputError("base URL must not have a query or fragment")
    if (personal) {
        if (scheme != "https") throw InputError("a personal account's base URL must use https")
        requirePublicHost(uri.host, "base URL")
    }
    return value.trimEnd('/')
}

private fun validateProxy(p: ProxyInput, personal: Boolean) {
    val type = ProxyType.fromString(p.type) ?: throw InputError("Unknown proxy type ${p.type}")
    AccountProxy.validate(type, p.host, p.port)?.let { throw InputError(it) }
    if (type != ProxyType.NONE && personal) requirePublicHost(p.host!!.trim(), "proxy host")
}

/** Rejects loopback, private, link-local and wildcard addresses (and names resolving to them). */
private fun requirePublicHost(host: String, what: String) {
    val addrs = try { InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]")) } catch (_: Exception) {
        throw InputError("$what does not resolve")
    }
    if (addrs.any(::isInternalAddress)) throw InputError("$what must be a public address")
}

/** Loopback, private, link-local, wildcard, multicast, ULA, and CGNAT 100.64.0.0/10 (Tailscale/carrier). */
internal fun isInternalAddress(addr: InetAddress): Boolean {
    val b = addr.address
    val cgnat = addr is java.net.Inet4Address && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
    return addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress ||
        addr.isMulticastAddress || cgnat || (addr is java.net.Inet6Address && (b[0].toInt() and 0xfe) == 0xfc)
}
