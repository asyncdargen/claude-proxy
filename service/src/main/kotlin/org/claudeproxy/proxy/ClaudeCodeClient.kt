package org.claudeproxy.proxy

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import org.claudeproxy.accounts.AccountRuntime
import org.claudeproxy.accounts.generateDeviceId
import org.claudeproxy.envOrProp
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Makes a request the service builds itself (limit probe, chat) look like it came from the CLI
 * logged into that account: Claude Code's user agent and `x-app`, a session id in the header,
 * and the same session in the body's `metadata.user_id` next to the account's device id and
 * uuid. The Claude Code datapath doesn't need this — the real CLI already sent all of it, and
 * [RequestRewriter] only swaps the identity values.
 */
object ClaudeCodeClient {
    val userAgent: String get() = envOrProp("CLAUDE_CODE_USER_AGENT") ?: "claude-cli/2.1.292 (external, cli)"

    /**
     * A CLI session lives for hours, not one request: one id per (purpose, owner, account) per
     * UTC day. Fresh uuids per call would read as thousands of one-shot sessions.
     */
    fun dailySession(scope: String, accountId: Int, day: LocalDate = LocalDate.now(ZoneOffset.UTC)): String =
        UUID.nameUUIDFromBytes("claude-proxy:$scope:$accountId:$day".toByteArray()).toString()

    fun applyHeaders(builder: HttpRequestBuilder, sessionId: String) {
        builder.headers.remove("User-Agent")
        builder.header("User-Agent", userAgent)
        builder.header("x-app", "cli")
        builder.header("X-Claude-Code-Session-Id", sessionId)
    }

    /** [body] with this account's identity in `metadata.user_id`. */
    fun stamp(body: ByteArray, account: AccountRuntime, sessionId: String): ByteArray =
        RequestRewriter.stampIdentity(body, account.deviceId ?: generateDeviceId(), account.upstreamAccountUuid, sessionId)
}
