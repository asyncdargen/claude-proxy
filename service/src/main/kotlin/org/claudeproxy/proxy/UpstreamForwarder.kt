package org.claudeproxy.proxy

import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.claudeproxy.repo.BilledUsage
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.RateLimitHeaders
import org.claudeproxy.accounts.AccountRuntime
import org.claudeproxy.model.AccountType
import org.claudeproxy.repo.UsageRepo
import org.slf4j.LoggerFactory
import java.time.Instant

enum class RetryKind { RATE_LIMITED, LOST_ACCESS, UPSTREAM_ERROR }

/** Outcome of a single upstream attempt. */
sealed interface ForwardResult {
    /** Response was streamed to the client; we are done. */
    object Served : ForwardResult
    /** This account couldn't serve; caller may retry another account. */
    data class Retry(val kind: RetryKind, val until: Instant?) : ForwardResult
}

/**
 * Forwards a buffered client request to Anthropic on behalf of a chosen account,
 * streaming the response back. Swaps client credentials for the account's, records
 * usage, and updates the account's live limit state from response headers.
 */
class UpstreamForwarder(
    private val pool: AccountPool,
    private val upstreamBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger("UpstreamForwarder")
    private val json = Json { ignoreUnknownKeys = true }

    // Hop-by-hop / auth / client-origin headers we never forward upstream verbatim.
    // `x-client-id` is a proxy tell (real Claude Code omits it); the session-id header is
    // re-emitted with a per-account rotated value below.
    private val stripRequestHeaders = setOf(
        "host", "content-length", "transfer-encoding", "connection",
        "authorization", "x-api-key", "accept-encoding",
        "proxy-authorization", "proxy-authenticate", "cookie", "cookie2",
        "forwarded", "x-real-ip", "x-client-ip", "x-cluster-client-ip",
        "x-originating-ip", "x-original-forwarded-for", "cf-connecting-ip",
        "cf-connecting-ipv6", "true-client-ip", "fastly-client-ip",
        "x-client-id", "x-claude-code-session-id",
    )
    private val stripResponseHeaders = setOf(
        "content-length", "transfer-encoding", "connection", "content-encoding",
    )

    suspend fun forward(
        call: ApplicationCall,
        account: AccountRuntime,
        pathAndQuery: String,
        bodyBytes: ByteArray,
        userId: Int?,
        tokenId: Int? = null,
        canRetry: Boolean = false,
        allowedGroups: Set<Int>? = null,
    ): ForwardResult {
        val method = call.request.httpMethod
        val url = "$upstreamBaseUrl$pathAndQuery"

        // Per-account identity: rotate the session-id and stamp this account's device-id into
        // the body's metadata. Cheap (cached) DB lookup; no-op for bodies without metadata.
        val rewritten = RequestRewriter.rewrite(
            bodyBytes,
            headerSessionId = call.request.headers["X-Claude-Code-Session-Id"],
            deviceId = account.deviceId,
            accountUuid = account.upstreamAccountUuid,
        ) { origin -> org.claudeproxy.repo.SessionMapRepo.resolve(origin, account.id) }
        val outBody = rewritten.body

        val statement = Http.client.prepareRequest(url) {
            this.method = method
            // copy through client headers except stripped + telemetry ones
            call.request.headers.forEach { name, values ->
                val ln = name.lowercase()
                if (ln !in stripRequestHeaders && !ln.startsWith("x-forwarded-") && !RequestRewriter.isTelemetryHeader(ln)) {
                    values.forEach { v -> header(name, v) }
                }
            }
            // Re-emit the session-id header with this account's rotated value.
            rewritten.sessionId?.let { header("X-Claude-Code-Session-Id", it) }
            applyAuth(this, account)
            // Anthropic requires this header; inject a default if the client omitted it.
            if (call.request.headers["anthropic-version"] == null) {
                header("anthropic-version", "2023-06-01")
            }
            if (outBody.isNotEmpty()) {
                setBody(object : OutgoingContent.ByteArrayContent() {
                    override val contentType: ContentType? =
                        call.request.headers["Content-Type"]?.let { ContentType.parse(it) }
                            ?: ContentType.Application.Json
                    override val contentLength: Long = outBody.size.toLong()
                    override fun bytes(): ByteArray = outBody
                })
            }
        }

        return statement.execute { response ->
            val headerMap = HashMap<String, String>()
            response.headers.forEach { k, v -> headerMap[k] = v.lastOrNull() ?: "" }

            // Update live limit state from headers.
            val prev = pool.get(account.id)?.limit ?: account.limit
            val newLimit = RateLimitHeaders.parse(headerMap, prev)
            pool.updateLimit(account.id, newLimit)

            val status = response.status
            log.info("upstream {} {} acct#{} -> {}", method.value, pathAndQuery.substringBefore('?'), account.id, status.value)

            // Update account health/limit state for retryable statuses, then decide whether to
            // retry another account or pass the real upstream response straight through.
            if (status == HttpStatusCode.TooManyRequests) {
                // Park until retry-after if given; else, only until the window reset when the
                // account is genuinely at its window limit (utilization ~full). A 429 at low
                // utilization is a short burst limit — park briefly so we retry soon.
                val retryAfter = resetInstantFrom(headerMap)
                val maxUtil = newLimit.windows.values.mapNotNull { it.utilization }.maxOrNull() ?: 0.0
                val until = when {
                    retryAfter != null -> retryAfter
                    maxUtil >= 0.95 -> newLimit.windows.values.mapNotNull { it.resetAt }.minOrNull()
                    else -> Instant.now().plusSeconds(60)
                }
                pool.markRateLimited(account.id, until)
                if (canRetry) {
                    runCatching { response.readRawBytes() }
                    UsageRepo.record(account.id, userId, BilledUsage(), status.value, null, tokenId = tokenId)
                    return@execute ForwardResult.Retry(RetryKind.RATE_LIMITED, until)
                }
            } else if (status.value == 401) {
                pool.setHealth(account.id, org.claudeproxy.model.AccountHealth.REFRESH_FAILED)
                runCatching { AccountRepo.updateHealth(account.id, org.claudeproxy.model.AccountHealth.REFRESH_FAILED) }
                if (canRetry) {
                    runCatching { response.readRawBytes() }
                    UsageRepo.record(account.id, userId, BilledUsage(), status.value, null, tokenId = tokenId)
                    return@execute ForwardResult.Retry(RetryKind.LOST_ACCESS, null)
                }
            } else if (status.value in intArrayOf(500, 502, 503, 529)) {
                if (canRetry) {
                    runCatching { response.readRawBytes() }
                    UsageRepo.record(account.id, userId, BilledUsage(), status.value, null, tokenId = tokenId)
                    return@execute ForwardResult.Retry(RetryKind.UPSTREAM_ERROR, null)
                }
            }
            // Otherwise (2xx, client 4xx, or last-attempt error) → pass the real response through.

            // Copy safe response headers to the client.
            response.headers.forEach { name, values ->
                if (name.lowercase() !in stripResponseHeaders) {
                    values.forEach { v -> call.response.headers.append(name, v, safeOnly = false) }
                }
            }

            val contentType = response.headers["Content-Type"]?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            val isEventStream = contentType?.match(ContentType.Text.EventStream) == true
            log.info("relay acct#{} status={} ctype='{}' sse={}", account.id, status.value, response.headers["Content-Type"], isEventStream)

            if (isEventStream) {
                // Stream SSE to the client in real time while teeing token usage out of the stream.
                // Anthropic can stay silent for 30s+ during adaptive "thinking" on a large context;
                // we flush the response head immediately and inject SSE keep-alive comments during
                // silence so the client (and Cloudflare/nginx) don't abort before the first real
                // event — which showed up as nginx "upstream prematurely closed connection while
                // reading response header" and an endless client retry loop.
                val model = modelFromRequest(bodyBytes)
                val scanner = SseUsageScanner()
                val errorScan = SseErrorScanner()
                val src = response.bodyAsChannel()
                var relayed = 0L
                var chunks = 0
                var keepalives = 0
                // Set when a retryable error surfaces mid-stream (after the head went out):
                // the status we record for this attempt instead of the 200 we already sent.
                var streamErrorStatus: Int? = null
                val buf = ByteArray(16 * 1024)
                // Everything written to the client goes through the framer, so a keep-alive comment
                // can never land inside a half-delivered event (see SseFramer).
                val framer = SseFramer()
                // A client that disconnects mid-stream closes the write channel; that's normal,
                // not an error. Swallow it and still record whatever usage we scanned.
                try {
                    call.respondBytesWriter(status = status, contentType = contentType) {
                        // Send the response head + an ignored SSE comment right away so the client
                        // enters streaming mode immediately, even if the first event is far off.
                        writeStringUtf8(": keep-alive\n\n")
                        flush()
                        while (!src.isClosedForRead) {
                            // Wait for upstream data, but bound the wait so we can emit keep-alives.
                            // awaitContent() never consumes bytes, so cancelling it on timeout is safe.
                            val ready = withTimeoutOrNull(15_000L) { src.awaitContent(1) }
                            when {
                                ready == null -> {
                                    // upstream silent (thinking) → keep the connection warm, but
                                    // never while an event is half delivered: the comment's blank
                                    // line would cut that event short.
                                    if (framer.pendingBytes == 0) {
                                        writeStringUtf8(": keep-alive\n\n")
                                        flush()
                                        keepalives++
                                    }
                                }
                                ready == false -> {} // channel closed; while-condition ends the loop
                                else -> {
                                    // data is ready → read whatever is available and forward it now
                                    val n = src.readAvailable(buf, 0, buf.size)
                                    if (n > 0) {
                                        scanner.feed(buf, 0, n)
                                        errorScan.feed(buf, 0, n)
                                        val errType = errorScan.retryableType
                                        if (errType != null) {
                                            // The fragment still buffered belongs to an event
                                            // upstream abandoned; drop it so the normalized error
                                            // frame opens a clean one.
                                            framer.discard()
                                            // A limit/overload surfaced *inside* the stream, so the
                                            // 200 head is already out and we can't retry another
                                            // account transparently. Don't relay the raw error frame;
                                            // hand the client a normalized retryable error so it
                                            // re-sends the whole request (which then picks a different
                                            // account). If the inject fails, we break out and let the
                                            // truncated stream force the retry instead.
                                            streamErrorStatus = injectStreamError(this, errType, account, userId, allowedGroups)
                                            break
                                        }
                                        val out = framer.feed(buf, 0, n)
                                        if (out.isNotEmpty()) {
                                            writeFully(out, 0, out.size)
                                            relayed += out.size
                                            chunks++
                                            flush()
                                        }
                                    }
                                }
                            }
                        }
                        log.info("relay acct#{} drained relayed={} chunks={} keepalives={}", account.id, relayed, chunks, keepalives)
                    }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log.warn("relay acct#{} write failed after {} bytes / {} chunks: {}", account.id, relayed, chunks, e.toString())
                }
                UsageRepo.record(
                    account.id, userId, scanner.billed(), streamErrorStatus ?: status.value, model,
                    tokenId = tokenId, webFetchRequests = scanner.webFetchRequests,
                )
            } else {
                // Buffer JSON (single message) so we can extract token usage.
                val bytes = response.readRawBytes()
                recordUsageFromJson(account.id, userId, tokenId, status.value, bytes)
                call.respondBytes(bytes = bytes, contentType = contentType, status = status)
            }
            ForwardResult.Served
        }
    }

    /**
     * A retryable error appeared mid-stream. Park the account if it was a real rate-limit,
     * then write a normalized Anthropic-shaped `error` event to the client so it retries.
     * When another account is available we send `overloaded_error` (retry now → lands on the
     * other account); when the pool is exhausted we send `rate_limit_error` with a "retry in Ns"
     * hint. Returns the status to record for this attempt. If the write fails (client already
     * gone), the broken stream itself triggers the client's retry.
     */
    private suspend fun injectStreamError(
        channel: io.ktor.utils.io.ByteWriteChannel,
        errType: String,
        account: AccountRuntime,
        userId: Int?,
        allowedGroups: Set<Int>?,
    ): Int {
        // A genuine rate-limit means this account should be skipped on the retry; park it.
        if (errType == "rate_limit_error") {
            val cur = pool.get(account.id)?.limit ?: account.limit
            val until = cur.windows.values.mapNotNull { it.resetAt }.minOrNull() ?: Instant.now().plusSeconds(60)
            pool.markRateLimited(account.id, until)
        }
        val hasAlt = pool.availability(userId, allowedGroups).healthy > 0
        val type: String
        val message: String
        val status: Int
        if (hasAlt) {
            type = "overloaded_error"
            message = "claude-proxy: account limit hit mid-stream, switching account — retry"
            status = 529
        } else {
            val secs = pool.earliestReset()?.let {
                maxOf(1L, java.time.Duration.between(Instant.now(), it).seconds)
            }
            type = "rate_limit_error"
            message = "claude-proxy: all accounts rate-limited" + (secs?.let { "; retry in ${it}s" } ?: "")
            status = 429
        }
        val frame = "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"$type\",\"message\":\"$message\"}}\n\n"
        runCatching {
            channel.writeStringUtf8(frame)
            channel.flush()
        }.onFailure {
            log.warn("mid-stream error inject failed acct#{}: {}", account.id, it.toString())
        }
        log.warn("mid-stream {} acct#{} -> injected {} (hasAlt={})", errType, account.id, type, hasAlt)
        return status
    }

    private fun applyAuth(builder: io.ktor.client.request.HttpRequestBuilder, account: AccountRuntime) {
        org.claudeproxy.accounts.UpstreamAuth.apply(builder, account.type, account.secret)
    }

    private fun resetInstantFrom(headers: Map<String, String>): Instant? {
        val retryAfter = headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value
        retryAfter?.trim()?.toLongOrNull()?.let { return Instant.now().plusSeconds(it) }
        return null
    }

    private fun modelFromRequest(bodyBytes: ByteArray): String? = try {
        if (bodyBytes.isEmpty()) null
        else (json.parseToJsonElement(bodyBytes.decodeToString()) as? JsonObject)
            ?.get("model")?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    private fun recordUsageFromJson(accountId: Int, userId: Int?, tokenId: Int?, status: Int, bytes: ByteArray) {
        var input = 0L; var output = 0L; var cacheRead = 0L; var cacheCreation = 0L
        var cacheCreation1h = 0L; var webSearch = 0L; var webFetch = 0L; var fast = false
        var model: String? = null
        try {
            val obj = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            model = obj?.get("model")?.jsonPrimitive?.contentOrNull
            val usage = obj?.get("usage")?.jsonObject
            input = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            output = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            cacheRead = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            cacheCreation = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            // Per-TTL split, server-side tool calls and fast mode: all priced differently from
            // the flat counters above, all reported only in these nested fields.
            val creation = usage?.get("cache_creation") as? JsonObject
            cacheCreation1h = creation?.get("ephemeral_1h_input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            if (cacheCreation == 0L && creation != null) {
                cacheCreation = cacheCreation1h + (creation["ephemeral_5m_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L)
            }
            val serverTools = usage?.get("server_tool_use") as? JsonObject
            webSearch = serverTools?.get("web_search_requests")?.jsonPrimitive?.longOrNull ?: 0L
            webFetch = serverTools?.get("web_fetch_requests")?.jsonPrimitive?.longOrNull ?: 0L
            fast = usage?.get("speed")?.jsonPrimitive?.contentOrNull == "fast"
        } catch (_: Exception) {
            // non-JSON error body; still record the event
        }
        val write1h = cacheCreation1h.coerceIn(0, cacheCreation)
        val billed = BilledUsage(
            input = input, output = output, cacheRead = cacheRead,
            cacheWrite5m = cacheCreation - write1h, cacheWrite1h = write1h,
            webSearchRequests = webSearch, fast = fast,
        )
        UsageRepo.record(accountId, userId, billed, status, model, tokenId = tokenId, webFetchRequests = webFetch)
    }
}
