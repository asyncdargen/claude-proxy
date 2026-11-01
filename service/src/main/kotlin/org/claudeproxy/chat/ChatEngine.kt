package org.claudeproxy.chat

import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRuntime
import org.claudeproxy.accounts.UpstreamAuth
import org.claudeproxy.api.ChatStreamRequest
import org.claudeproxy.api.UsageReport
import org.claudeproxy.datapath.ActiveSessions
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.model.Permission
import org.claudeproxy.proxy.ClaudeCodeClient
import org.claudeproxy.proxy.Http
import org.claudeproxy.proxy.SseUsageScanner
import org.claudeproxy.repo.AttachmentBlob
import org.claudeproxy.repo.ChatMemoryRepo
import org.claudeproxy.repo.ChatRepo
import org.claudeproxy.repo.TurnUsage
import org.claudeproxy.repo.UserAuth
import org.claudeproxy.repo.UserRepo
import org.slf4j.LoggerFactory
import java.util.UUID

/** Statuses that make a non-last attempt swap accounts instead of surfacing the error. */
private val RETRYABLE = intArrayOf(429, 401, 403, 500, 502, 503, 529)

/** Bound on the upstream silence we tolerate before writing a keep-alive comment. */
private const val KEEPALIVE_MS = 15_000L

/**
 * The chat datapath. Turns a chat turn into an Anthropic Messages request, forwards it across the
 * user's ordered accounts with transparent retry, relays the stream to the browser as its own SSE
 * protocol, and records the spend under `source="chat"` so it meters the chat daily limit rather
 * than the one Claude Code runs on.
 *
 * It shares the pool, the crypto and the usage bookkeeping with the proxy datapath — only the
 * request body and the client-facing protocol are its own.
 */
class ChatEngine(
    private val pool: AccountPool,
    private val upstreamBaseUrl: String,
    private val datapath: DatapathService,
) {
    private val log = LoggerFactory.getLogger("ChatEngine")

    /** Everything one turn needs, resolved before a single byte is written to the client. */
    private data class Plan(
        val chatId: Int?,
        val title: String?,
        val newChat: Boolean,
        val model: String,
        val turns: List<ChatPrompt.Turn>,
        val system: kotlinx.serialization.json.JsonArray,
        val userMessageId: Long?,
        val useMemory: Boolean,
        val zone: java.time.ZoneId,
    )

    /**
     * Serve one turn. Writes an SSE stream: `start` (ids), then `delta`/`thinking`/`tool` frames,
     * then exactly one `done` or `error`.
     */
    suspend fun stream(call: ApplicationCall, user: UserAuth, req: ChatStreamRequest) {
        val plan = buildPlan(user, req)
        val sessionId = UUID.randomUUID().toString()
        ActiveSessions.begin(sessionId, user.id, routing = false)
        try {
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                // Flush the head at once: the first token can be 30s away on a thinking model,
                // and a withheld head is what turns into a client abort / nginx 502.
                writeStringUtf8(": open\n\n")
                flush()
                event(this, "start", buildJsonObject {
                    plan.chatId?.let { put("chatId", it) }
                    plan.title?.let { put("title", it) }
                    plan.userMessageId?.let { put("userMessageId", it) }
                    put("model", plan.model)
                    put("temporary", plan.chatId == null)
                })
                runTurn(this, user, req, plan)
            }
        } finally {
            ActiveSessions.end(sessionId)
        }
    }

    // ---- planning ----

    private fun buildPlan(user: UserAuth, req: ChatStreamRequest): Plan {
        // The turn stamps are rendered on the user's clock; an unparseable zone falls back to UTC,
        // exactly as the stats endpoints do.
        val zone = req.tz?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneOffset.UTC
        val settings = ChatMemoryRepo.settings(user.id)
        val existing = req.chatId?.let { ChatRepo.meta(user.id, it) }
        val model = listOfNotNull(req.model, existing?.model, settings.defaultModel, ChatModels.DEFAULT_MODEL)
            .first { ChatModels.isKnown(it) }

        // A retry/edit drops the target message and everything after it, so the turn re-runs in
        // place instead of branching the conversation.
        if (existing != null && req.fromMessageId != null) {
            ChatRepo.truncateFrom(user.id, existing.id, req.fromMessageId)
        }

        val newAttachments = ChatMemoryRepo.blobsOf(user.id, req.attachmentIds)
        val useMemory = settings.memoryEnabled && (existing?.useMemory ?: true)
        val system = ChatPrompt.systemBlocks(
            memory = if (useMemory) ChatMemoryRepo.activeFacts(user.id) else emptyList(),
            aboutYou = settings.aboutYou,
            responseStyle = settings.responseStyle,
            chatPrompt = existing?.systemPrompt,
        )

        // A retry re-answers the existing history: there is no new user turn to add, only the
        // truncated one to replace. An edit does carry text, and lands as a fresh user turn.
        val now = java.time.Instant.now()
        val newTurn = if (req.message.isNotBlank() || newAttachments.isNotEmpty())
            ChatPrompt.Turn("user", req.message, newAttachments, now) else null

        if (req.temporary) {
            // Nothing about a temporary chat is stored — its transcript rides along in the request.
            // A temporary transcript lives in the browser and carries no timestamps of its own;
            // only the turn being sent right now can be stamped honestly.
            val turns = req.history.map {
                ChatPrompt.Turn(it.role, it.content, ChatMemoryRepo.blobsOf(user.id, it.attachmentIds))
            } + listOfNotNull(newTurn)
            return Plan(null, null, false, model, turns, system, null, useMemory = false, zone = zone)
        }

        val chatId: Int
        var title: String? = null
        var newChat = false
        if (existing != null) {
            chatId = existing.id
            if (req.model != null && req.model != existing.model) {
                ChatRepo.update(user.id, chatId, null, req.model, null, null, null, null, false)
            }
        } else {
            newChat = true
            title = ChatPrompt.stubTitle(req.message)
            chatId = ChatRepo.create(user.id, title, model).id
        }

        val history = ChatRepo.messagesOf(chatId)
            // A failed turn left an empty assistant row behind; replaying it upstream would send
            // an empty assistant message, which Anthropic rejects.
            .filter { it.content.isNotBlank() }
            .map { m ->
                ChatPrompt.Turn(
                    m.role, m.content, ChatMemoryRepo.blobsOf(user.id, m.attachments.map { it.id }),
                    runCatching { java.time.Instant.parse(m.createdAt) }.getOrNull(),
                )
            }
        val userMessageId = newTurn?.let {
            ChatRepo.addMessage(chatId, "user", req.message, attachmentIds = req.attachmentIds)
        }
        return Plan(chatId, title, newChat, model, history + listOfNotNull(newTurn), system, userMessageId, useMemory, zone)
    }

    // ---- the turn itself ----

    private suspend fun runTurn(out: ByteWriteChannel, user: UserAuth, req: ChatStreamRequest, plan: Plan) {
        // A retry against an empty transcript (everything was truncated away) has nothing to answer.
        if (plan.turns.isEmpty()) {
            finish(out, plan, "", null, plan.model, TurnUsage(), "There is nothing left to answer in this conversation.")
            return
        }
        val body = ChatPrompt.body(plan.model, plan.system, plan.turns, req.webSearch, req.thinking, zone = plan.zone)
            .toString().toByteArray()

        val perms = user.permissions
        val allowedGroups: Set<Int>? = if (Permission.ADMIN in perms) null else UserRepo.allowedGroupsOf(user.id)
        val allowGlobal = Permission.ADMIN in perms || Permission.POOL_GLOBAL_USE in perms
        val personalFirst = !UserRepo.preferGlobalPoolOf(user.id)

        // The chat has its own daily USD limit; like the proxy datapath, hitting it leaves the
        // user's personal accounts (their own quota) usable.
        val limit = UserRepo.dailyChatLimitOf(user.id)
        val used = if (limit != null) datapath.cachedDailySpend(user.id, "chat") else 0.0
        val overLimit = limit != null && used >= limit
        val order = if (overLimit) pool.selectionOrderOwned(user.id)
        else pool.selectionOrder(user.id, allowedGroups, personalFirst, allowGlobal)

        if (order.isEmpty()) {
            val message = if (overLimit)
                "Daily chat limit reached (%.4f of %.4f USD). It resets at 00:00 UTC.".format(used, limit ?: 0.0)
            else "No upstream account is available right now — every account is rate-limited or disabled."
            finish(out, plan, "", null, plan.model, TurnUsage(), message)
            return
        }

        val text = StringBuilder()
        val thinking = StringBuilder()
        var served: Attempt? = null
        var lastError: String? = null

        for (account in order) {
            pool.markActive(account.id)
            val attempt = attempt(out, account, body, user.id, plan.model, text, thinking)
            if (attempt.served) { served = attempt; break }
            lastError = attempt.error
            if (!attempt.retryable) break
            log.info("chat: account {} could not serve ({}), trying next", account.id, attempt.error)
            // An account can fail after emitting thinking (never after emitting an answer — that
            // counts as served). Wipe what it produced, on the wire and in the buffer, so the next
            // account's turn doesn't get glued onto a dead one's.
            if (text.isNotEmpty() || thinking.isNotEmpty()) {
                text.setLength(0)
                thinking.setLength(0)
                event(out, "reset", buildJsonObject { put("reason", "switching account") })
            }
        }

        if (served == null) {
            finish(out, plan, text.toString(), null, plan.model, TurnUsage(), lastError ?: "The request failed on every account.")
            return
        }
        finish(
            out, plan, text.toString(), thinking.toString().ifBlank { null },
            served.model ?: plan.model, served.usage, served.error,
        )

        // Housekeeping that shouldn't hold the response open: naming a fresh chat and learning
        // from the exchange both cost an extra (cheap) upstream call.
        plan.chatId?.let { chatId ->
            if (plan.newChat) launchTitleJob(user, chatId)
            if (plan.useMemory) launchMemoryJob(user, chatId)
        }
    }

    /**
     * One attempt's outcome. [error] is set both when the account couldn't serve (served=false,
     * move on) and when the answer arrived but the stream ended badly (served=true + a warning).
     */
    private data class Attempt(
        val served: Boolean, val error: String?,
        val usage: TurnUsage = TurnUsage(), val model: String? = null,
        // false = the failure is the request's fault (a 400, say), so trying another account
        // would only repeat it — stop and show the error.
        val retryable: Boolean = true,
    )

    private suspend fun attempt(
        out: ByteWriteChannel, account: AccountRuntime, body: ByteArray, userId: Int, requestedModel: String,
        text: StringBuilder, thinking: StringBuilder,
    ): Attempt {
        val sessionId = ClaudeCodeClient.dailySession("chat:$userId", account.id)
        val stamped = ClaudeCodeClient.stamp(body, account, sessionId)
        val statement = Http.clientFor(account).prepareRequest("${account.upstreamBaseUrl ?: upstreamBaseUrl}/v1/messages") {
            method = HttpMethod.Post
            header("anthropic-version", "2023-06-01")
            ClaudeCodeClient.applyHeaders(this, sessionId)
            UpstreamAuth.apply(this, account)
            setBody(object : OutgoingContent.ByteArrayContent() {
                override val contentType = ContentType.Application.Json
                override val contentLength = stamped.size.toLong()
                override fun bytes() = stamped
            })
        }

        return statement.execute { response ->
            val headers = HashMap<String, String>()
            response.headers.forEach { k, v -> headers[k] = v.lastOrNull() ?: "" }
            val status = response.status.value

            if (status !in 200..299) {
                val raw = runCatching { response.readRawBytes() }.getOrDefault(ByteArray(0))
                report(account.id, userId, SseUsageScanner(), status, requestedModel, headers)
                val message = errorFromJson(raw) ?: "Upstream returned HTTP $status"
                return@execute Attempt(false, message, retryable = status in RETRYABLE)
            }

            val scanner = SseUsageScanner()
            val parser = ChatSseParser()
            var model: String? = null
            var streamError: String? = null
            var wrote = false
            val buf = ByteArray(16 * 1024)
            val src = response.bodyAsChannel()
            try {
                while (!src.isClosedForRead) {
                    val ready = withTimeoutOrNull(KEEPALIVE_MS) { src.awaitContent(1) }
                    when {
                        // Upstream silent (thinking) — keep the connection warm end to end.
                        ready == null -> { out.writeStringUtf8(": ping\n\n"); out.flush() }
                        ready == false -> {}
                        else -> {
                            val n = src.readAvailable(buf, 0, buf.size)
                            if (n <= 0) continue
                            scanner.feed(buf, 0, n)
                            for (ev in parser.feed(buf, 0, n)) {
                                when (ev) {
                                    is ChatEvent.Model -> model = ev.model
                                    is ChatEvent.Text -> {
                                        text.append(ev.text); wrote = true
                                        event(out, "delta", buildJsonObject { put("t", ev.text) })
                                    }
                                    is ChatEvent.Thinking -> {
                                        thinking.append(ev.text)
                                        event(out, "thinking", buildJsonObject { put("t", ev.text) })
                                    }
                                    is ChatEvent.Tool -> event(out, "tool", buildJsonObject {
                                        put("name", ev.name); ev.query?.let { put("query", it) }
                                    })
                                    is ChatEvent.ToolResult -> event(out, "tool", buildJsonObject {
                                        put("name", ev.name); put("results", ev.count)
                                    })
                                    is ChatEvent.Error -> streamError = "${ev.type}: ${ev.message}"
                                    is ChatEvent.Stop -> {}
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.warn("chat relay failed on account {}: {}", account.id, e.toString())
                streamError = streamError ?: e.message
            }

            // Anthropic can fail a request *after* a 200 with an in-stream error frame. Nothing
            // client-visible has been written if no text arrived, so the turn can still move to
            // another account — exactly what the proxy datapath does.
            val effectiveStatus = if (streamError != null && !wrote) 529 else 200
            report(account.id, userId, scanner, effectiveStatus, model ?: requestedModel, headers)
            val answeredBy = model ?: requestedModel
            val usage = TurnUsage(
                input = scanner.input, output = scanner.output,
                cacheRead = scanner.cacheRead, cacheWrite = scanner.cacheCreation,
                cost = org.claudeproxy.repo.ModelPriceRepo.costOf(answeredBy, scanner.billed()),
            )
            if (streamError != null && !wrote) Attempt(false, streamError, usage, answeredBy)
            else Attempt(true, streamError, usage, answeredBy)
        }
    }

    /** Hand one attempt's outcome to the shared bookkeeping (usage row, limits, account health). */
    private suspend fun report(
        accountId: Int, userId: Int, scanner: SseUsageScanner, status: Int, model: String?,
        headers: Map<String, String>,
    ) {
        val billed = scanner.billed()
        datapath.applyOutcome(
            UsageReport(
                accountId = accountId, userId = userId, tokenId = null,
                input = billed.input, output = billed.output, cacheRead = billed.cacheRead,
                cacheWrite = billed.cacheWrite5m + billed.cacheWrite1h, cacheWrite1h = billed.cacheWrite1h,
                webSearchRequests = billed.webSearchRequests, webFetchRequests = scanner.webFetchRequests,
                fast = billed.fast, status = status, model = model,
                ratelimitHeaders = headers, source = "chat",
            ),
        )
    }

    /** Persist the assistant turn (stored chats only) and close the stream with `done`/`error`. */
    private suspend fun finish(
        out: ByteWriteChannel, plan: Plan, text: String, thinking: String?, model: String,
        usage: TurnUsage, error: String?,
    ) {
        var messageId: Long? = null
        plan.chatId?.let { chatId ->
            messageId = ChatRepo.addMessage(
                chatId, "assistant", text, thinking = thinking, model = model, usage = usage, error = error,
            )
        }
        // The turn is already persisted; a client that hung up mid-write must not take the
        // background chores (titling, memory) down with it.
        runCatching {
            if (error != null && text.isEmpty()) {
                event(out, "error", buildJsonObject {
                    put("message", error)
                    messageId?.let { put("messageId", it) }
                })
            } else {
                event(out, "done", buildJsonObject {
                    messageId?.let { put("messageId", it) }
                    put("model", model)
                    put("inputTokens", usage.input)
                    put("outputTokens", usage.output)
                    put("cost", usage.cost)
                    error?.let { put("warning", it) }
                })
            }
        }
    }

    /**
     * Continue a conversation in a fresh chat. The transcript is compacted upstream into a
     * handover block, which lands as the new chat's first (assistant) message: visible to the
     * user, editable, and replayed to the model on every turn like any other turn. Deliberately
     * not *also* copied into the chat's system prompt — the model would then read the same
     * handover twice, on every request, for nothing.
     *
     * Synchronous on purpose, unlike the background chores: the user is waiting on the new chat,
     * and a silent failure here would open an empty one that has quietly lost the thread.
     */
    suspend fun continueInNewChat(user: UserAuth, chatId: Int): Int? {
        val source = ChatRepo.meta(user.id, chatId) ?: return null
        val messages = ChatRepo.messagesOf(chatId).filter { it.content.isNotBlank() }
        if (messages.isEmpty()) return null

        // Bound what goes upstream: a very long chat would otherwise blow past the context window
        // (and cost accordingly). The tail is the part a handover actually needs.
        val transcript = buildString {
            var budget = 120_000
            messages.reversed().forEach { m ->
                val line = "${m.role}: ${m.content}\n\n"
                if (budget - line.length < 0) return@forEach
                budget -= line.length
                insert(0, line)
            }
        }

        val model = if (ChatModels.isKnown(source.model)) source.model else ChatModels.DEFAULT_MODEL
        val context = utilityCall(user, ChatPrompt.utilityBody(model, ChatPrompt.compactionPrompt(transcript), 4_000))
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val title = ChatPrompt.cleanTitle(source.title).let { if (it.length > 56) it.take(56) else it }
        val fresh = ChatRepo.create(user.id, "$title ›", model)
        // Lock the title: this chat is named after its parent, and the auto-titler would rename it
        // after the first real exchange.
        ChatRepo.update(user.id, fresh.id, "$title ›", null, null, null, null, null, false)
        ChatRepo.addMessage(fresh.id, "assistant", ChatPrompt.carryOverMessage(context), model = model)
        return fresh.id
    }

    // ---- background chores ----

    /**
     * Name a freshly created chat from its first exchange. Fire-and-forget on purpose: a failed
     * or slow title must never delay (or fail) the answer the user is reading.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun launchTitleJob(user: UserAuth, chatId: Int) = GlobalScope.launch {
        runCatching {
            val transcript = ChatRepo.tailText(chatId, 2).joinToString("\n\n") { (role, text) ->
                "$role: ${text.take(1200)}"
            }
            if (transcript.isBlank()) return@launch
            val prompt = "Give this conversation a title of at most six words, in the language the user writes in. " +
                "Reply with the title only — no quotes, no punctuation at the end.\n\n$transcript"
            val answer = utilityCall(user, ChatPrompt.utilityBody(ChatModels.UTILITY_MODEL, prompt, 64)) ?: return@launch
            ChatRepo.setGeneratedTitle(chatId, ChatPrompt.cleanTitle(answer))
        }.onFailure { log.debug("auto-title failed for chat {}: {}", chatId, it.toString()) }
    }

    /**
     * Learn durable facts about the user from the exchange that just happened. Only stable,
     * reusable things are kept — the extractor is told to return nothing rather than guess, and
     * duplicates are dropped in [ChatMemoryRepo.add].
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun launchMemoryJob(user: UserAuth, chatId: Int) = GlobalScope.launch {
        runCatching {
            val transcript = ChatRepo.tailText(chatId, 2).joinToString("\n\n") { (role, text) ->
                "$role: ${text.take(2000)}"
            }
            if (transcript.isBlank()) return@launch
            val known = ChatMemoryRepo.activeFacts(user.id)
            val prompt = buildString {
                append("Read the exchange below and extract durable facts about the USER that would help in future, ")
                append("unrelated conversations: their name, job, stack, location, ongoing projects, stated preferences. ")
                append("Ignore anything specific to this one task, anything temporary, and anything about you.\n")
                append("Reply with a JSON array of short strings, at most three, in the user's language. ")
                append("If there is nothing worth remembering, reply with exactly [].\n")
                if (known.isNotEmpty()) {
                    append("\nAlready remembered (do not repeat these):\n")
                    known.take(60).forEach { append("- ").append(it).append('\n') }
                }
                append("\nExchange:\n").append(transcript)
            }
            val answer = utilityCall(user, ChatPrompt.utilityBody(ChatModels.UTILITY_MODEL, prompt, 300)) ?: return@launch
            ChatMemory.parseFacts(answer).forEach { ChatMemoryRepo.add(user.id, it, "auto", chatId) }
        }.onFailure { log.debug("memory extraction failed for chat {}: {}", chatId, it.toString()) }
    }

    /**
     * One buffered upstream call for the background chores, on the first account that answers.
     * Its spend is recorded like any other chat request, so nothing is billed invisibly.
     */
    private suspend fun utilityCall(user: UserAuth, body: JsonObject): String? {
        val perms = user.permissions
        val allowedGroups: Set<Int>? = if (Permission.ADMIN in perms) null else UserRepo.allowedGroupsOf(user.id)
        val allowGlobal = Permission.ADMIN in perms || Permission.POOL_GLOBAL_USE in perms
        // Same daily chat limit as a turn: compaction (`/continue`) is a full model call over the
        // transcript, and the chores are billed as chat too. Over the limit only the user's own
        // accounts remain — none means no call at all.
        val limit = UserRepo.dailyChatLimitOf(user.id)
        val overLimit = limit != null && datapath.cachedDailySpend(user.id, "chat") >= limit
        val order = if (overLimit) pool.selectionOrderOwned(user.id)
        else pool.selectionOrder(user.id, allowedGroups, !UserRepo.preferGlobalPoolOf(user.id), allowGlobal)
        val bytes = body.toString().toByteArray()
        for (account in order.take(2)) {
            val sessionId = ClaudeCodeClient.dailySession("chat:${user.id}", account.id)
            val stamped = ClaudeCodeClient.stamp(bytes, account, sessionId)
            val result = Http.clientFor(account).prepareRequest("${account.upstreamBaseUrl ?: upstreamBaseUrl}/v1/messages") {
                method = HttpMethod.Post
                header("anthropic-version", "2023-06-01")
                ClaudeCodeClient.applyHeaders(this, sessionId)
                UpstreamAuth.apply(this, account)
                setBody(object : OutgoingContent.ByteArrayContent() {
                    override val contentType = ContentType.Application.Json
                    override val contentLength = stamped.size.toLong()
                    override fun bytes() = stamped
                })
            }.execute { response ->
                val headers = HashMap<String, String>()
                response.headers.forEach { k, v -> headers[k] = v.lastOrNull() ?: "" }
                val raw = runCatching { response.readRawBytes() }.getOrDefault(ByteArray(0))
                val scanner = SseUsageScanner().also { it.feed(raw, 0, raw.size) }
                report(account.id, user.id, scanner, response.status.value, ChatModels.UTILITY_MODEL, headers)
                if (response.status.value in 200..299) textFromMessageJson(raw) else null
            }
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    // ---- SSE plumbing ----

    private suspend fun event(out: ByteWriteChannel, name: String, data: JsonObject) {
        out.writeStringUtf8("event: $name\ndata: ${data.toString().replace("\n", "")}\n\n")
        out.flush()
    }
}
