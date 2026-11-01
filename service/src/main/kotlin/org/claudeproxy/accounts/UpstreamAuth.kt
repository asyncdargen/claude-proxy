package org.claudeproxy.accounts

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.ApiKeyMode

/** Applies an account's upstream credentials to a client request. */
object UpstreamAuth {

    /** Billing is selected by the API key/workspace; this only removes incompatible identity betas. */
    fun applyApiKeyMode(builder: HttpRequestBuilder, type: AccountType, mode: ApiKeyMode) {
        if (type != AccountType.API_KEY || mode == ApiKeyMode.CLAUDE_CODE) return
        val blocked = setOfNotNull("oauth-2025-04-20", "claude-code-20250219".takeIf { mode == ApiKeyMode.ANTHROPIC_API })
        val kept = builder.headers.getAll("anthropic-beta").orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in blocked }
        builder.headers.remove("anthropic-beta")
        if (kept.isNotEmpty()) builder.header("anthropic-beta", kept.joinToString(","))
    }

    /** Credentials of [account], presented the way its upstream expects them. */
    fun apply(builder: HttpRequestBuilder, account: AccountRuntime) = apply(builder, account.type, account.secret, account.apiAuthStyle)

    fun apply(builder: HttpRequestBuilder, type: AccountType, secret: AccountSecret, apiAuthStyle: org.claudeproxy.model.ApiAuthStyle = org.claudeproxy.model.ApiAuthStyle.X_API_KEY) {
        // Note: no `x-client-id` header — genuine Claude Code never sends one, so adding it
        // marked our traffic as a proxy. Per-account device identity now lives in the request
        // body (metadata.user_id.device_id), applied by RequestRewriter.
        when (type) {
            AccountType.API_KEY -> secret.apiKey?.let {
                // a non-Anthropic upstream (another proxy, a gateway) may want the key as a bearer
                if (apiAuthStyle == org.claudeproxy.model.ApiAuthStyle.BEARER) builder.header("Authorization", "Bearer $it")
                else builder.header("x-api-key", it)
            }
            AccountType.OAUTH, AccountType.OAUTH_STATIC -> {
                secret.accessToken?.let { builder.header("Authorization", "Bearer $it") }
                val existingBeta = builder.headers["anthropic-beta"]
                if (existingBeta == null) {
                    builder.header("anthropic-beta", "oauth-2025-04-20")
                } else if (!existingBeta.contains("oauth-2025-04-20")) {
                    builder.headers.remove("anthropic-beta")
                    builder.header("anthropic-beta", "$existingBeta,oauth-2025-04-20")
                }
            }
        }
    }
}
