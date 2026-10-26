package org.claudeproxy.api

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.auth.requirePermission
import org.claudeproxy.chat.ChatEngine
import org.claudeproxy.chat.ChatModels
import org.claudeproxy.chat.ChatPrompt
import org.claudeproxy.chat.ShareImport
import org.claudeproxy.model.Permission
import org.claudeproxy.repo.ChatMemoryRepo
import org.claudeproxy.repo.ChatRepo
import org.claudeproxy.repo.UserRepo
import org.claudeproxy.datapath.DatapathService

/** Hard ceiling on one upload. Attachment bytes live in the DB, so this bounds a row. */
private const val MAX_ATTACHMENT_BYTES = 12 * 1024 * 1024

/**
 * The built-in chat UI's API. Everything here is scoped to the calling user and gated by
 * `CHAT_USE`: conversations are private, and there is no cross-user read path at all.
 */
fun Route.chatRoutes(pool: AccountPool, engine: ChatEngine, datapath: DatapathService, upstreamBaseUrl: String) {
    route("/chat") {

        // ---- conversations ----

        get("/chats") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val q = call.parameters["q"]
            val archived = call.parameters["archived"] == "true"
            call.respond(ChatRepo.list(user.id, q, includeArchived = archived))
        }

        post("/chats") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val req = call.receive<CreateChatRequest>()
            val model = req.model?.takeIf { ChatModels.isKnown(it) }
                ?: ChatMemoryRepo.settings(user.id).defaultModel
                ?: ChatModels.DEFAULT_MODEL
            call.respond(ChatRepo.create(user.id, req.title?.trim()?.ifBlank { null } ?: "New chat", model))
        }

        get("/chats/{id}") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@get call.badId()
            val chat = ChatRepo.get(user.id, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Chat not found"))
            call.respond(chat)
        }

        patch("/chats/{id}") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@patch call.badId()
            val req = call.receive<UpdateChatRequest>()
            val ok = ChatRepo.update(
                user.id, id, req.title?.trim()?.ifBlank { null }, req.model?.takeIf { ChatModels.isKnown(it) },
                req.pinned, req.archived, req.useMemory, req.systemPrompt, req.clearSystemPrompt,
            )
            if (!ok) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Chat not found"))
            call.respond(ChatRepo.meta(user.id, id) ?: MessageResponse("updated"))
        }

        delete("/chats/{id}") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@delete call.badId()
            call.respond(if (ChatRepo.delete(user.id, id)) OkResponse() else MessageResponse("Chat not found"))
        }

        delete("/chats") {
            val user = call.requirePermission(Permission.CHAT_USE)
            call.respond(MessageResponse("Deleted ${ChatRepo.deleteAll(user.id)} conversations"))
        }

        // Drop a message and everything after it — the same truncation a retry performs, exposed
        // on its own so the UI can prune a branch without immediately re-asking.
        delete("/chats/{id}/messages/{mid}") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@delete call.badId()
            val mid = call.parameters["mid"]?.toLongOrNull() ?: return@delete call.badId()
            if (!ChatRepo.truncateFrom(user.id, id, mid)) {
                return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("Chat not found"))
            }
            call.respond(ChatRepo.get(user.id, id) ?: MessageResponse("deleted"))
        }

        /**
         * Continue this conversation in a fresh chat: the transcript is compacted upstream and
         * carried over as the new chat's opening message, so nothing of substance is lost while
         * the context window starts empty again.
         */
        post("/chats/{id}/continue") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@post call.badId()
            if (ChatRepo.meta(user.id, id) == null) {
                return@post call.respond(HttpStatusCode.NotFound, MessageResponse("Chat not found"))
            }
            // Answered as a stream, not a JSON body: compacting a long conversation is a full
            // model call over the whole transcript and routinely outlives a proxy's read timeout.
            // Flushing the head at once and trickling keep-alives keeps nginx (and any CDN in
            // front of it) from tearing the request down and handing the client an HTML 504.
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                writeStringUtf8(": open\n\n")
                flush()
                coroutineScope {
                    val work = async { runCatching { engine.continueInNewChat(user, id) } }
                    while (!work.isCompleted) {
                        if (withTimeoutOrNull(10_000) { work.await() } == null) {
                            writeStringUtf8(": keep-alive\n\n")
                            flush()
                        }
                    }
                    val outcome = work.await()
                    val fresh = outcome.getOrNull()
                    val frame = when {
                        fresh != null -> "event: done\ndata: {\"chatId\":$fresh}\n\n"
                        else -> {
                            val reason = outcome.exceptionOrNull()?.message
                                ?: "no account answered, or the conversation is still empty"
                            "event: error\ndata: {\"message\":${JsonPrimitive("Couldn't compact this conversation — $reason")}}\n\n"
                        }
                    }
                    writeStringUtf8(frame)
                    flush()
                }
            }
        }

        // Duplicate a conversation, transcript and files included — a branch point you can take
        // apart without touching the original.
        post("/chats/{id}/copy") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@post call.badId()
            val fresh = ChatRepo.copy(user.id, id)
                ?: return@post call.respond(HttpStatusCode.NotFound, MessageResponse("Chat not found"))
            call.respond(ChatRepo.get(user.id, fresh) ?: MessageResponse("copied"))
        }

        // Promote a side question (asked against this chat's context, answered outside it) into
        // the conversation. No upstream call: the answer is already paid for.
        post("/chats/{id}/messages") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@post call.badId()
            if (ChatRepo.meta(user.id, id) == null) {
                return@post call.respond(HttpStatusCode.NotFound, MessageResponse("Chat not found"))
            }
            val req = call.receive<AppendExchangeRequest>()
            if (req.question.isBlank() || req.answer.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Nothing to append"))
            }
            ChatRepo.addMessage(id, "user", req.question.trim())
            ChatRepo.addMessage(id, "assistant", req.answer.trim(), model = req.model)
            call.respond(ChatRepo.get(user.id, id) ?: MessageResponse("appended"))
        }

        // ---- the datapath ----

        post("/stream") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val req = call.receive<ChatStreamRequest>()
            if (req.message.isBlank() && req.attachmentIds.isEmpty() && req.fromMessageId == null) {
                return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Message is empty"))
            }
            if (req.chatId != null && ChatRepo.meta(user.id, req.chatId) == null) {
                return@post call.respond(HttpStatusCode.NotFound, MessageResponse("Chat not found"))
            }
            engine.stream(call, user, req)
        }

        get("/models") {
            call.requirePermission(Permission.CHAT_USE)
            call.respond(ChatModels.list())
        }

        // ---- attachments ----

        // Raw upload: the file's bytes are the body, its name rides in the query. Multipart would
        // buy nothing here (one file per request) and costs a parser.
        post("/attachments") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val name = call.parameters["name"]?.trim()?.ifBlank { null } ?: "file"
            val declared = call.request.headers[HttpHeaders.ContentType] ?: "application/octet-stream"
            val bytes = call.receive<ByteArray>()
            if (bytes.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Empty file"))
            }
            if (bytes.size > MAX_ATTACHMENT_BYTES) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    MessageResponse("Files are limited to ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB"),
                )
            }
            val mime = normalizeMime(declared, name)
            val kind = attachmentKind(mime, name)
                ?: return@post call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    MessageResponse("Only images, PDFs and text files can be attached"),
                )
            call.respond(ChatMemoryRepo.addAttachment(user.id, name, mime, kind, bytes))
        }

        get("/attachments/{id}") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@get call.badId()
            val blob = ChatMemoryRepo.blob(user.id, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Attachment not found"))
            // inline so images preview in place; the filename still drives a manual download
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, blob.name).toString(),
            )
            call.respondBytes(blob.data, ContentType.parse(blob.mimeType))
        }

        delete("/attachments/{id}") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@delete call.badId()
            call.respond(if (ChatMemoryRepo.deleteAttachment(user.id, id)) OkResponse() else MessageResponse("not found"))
        }

        // ---- memory ----

        get("/memories") {
            val user = call.requirePermission(Permission.CHAT_USE)
            call.respond(ChatMemoryRepo.list(user.id))
        }

        post("/memories") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val req = call.receive<CreateMemoryRequest>()
            if (req.content.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Memory is empty"))
            }
            ChatMemoryRepo.add(user.id, req.content, "manual")
            call.respond(ChatMemoryRepo.list(user.id))
        }

        patch("/memories/{id}") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@patch call.badId()
            val req = call.receive<UpdateMemoryRequest>()
            if (!ChatMemoryRepo.update(user.id, id, req.content, req.enabled)) {
                return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
            }
            call.respond(ChatMemoryRepo.list(user.id))
        }

        delete("/memories/{id}") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val id = call.intParam("id") ?: return@delete call.badId()
            ChatMemoryRepo.delete(user.id, id)
            call.respond(ChatMemoryRepo.list(user.id))
        }

        delete("/memories") {
            val user = call.requirePermission(Permission.CHAT_USE)
            call.respond(MessageResponse("Forgot ${ChatMemoryRepo.clear(user.id)} memories"))
        }

        // ---- settings ----

        get("/settings") {
            val user = call.requirePermission(Permission.CHAT_USE)
            call.respond(settingsPayload(user.id, datapath))
        }

        patch("/settings") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val req = call.receive<UpdateChatSettingsRequest>()
            ChatMemoryRepo.saveSettings(
                user.id, req.memoryEnabled, req.defaultModel?.takeIf { ChatModels.isKnown(it) },
                req.aboutYou, req.responseStyle,
            )
            call.respond(settingsPayload(user.id, datapath))
        }

        // ---- import ----

        post("/import") {
            val user = call.requirePermission(Permission.CHAT_USE)
            val req = call.receive<ImportChatRequest>()
            val imported = try {
                ShareImport.fetch(req.url)
            } catch (e: ShareImport.ImportError) {
                return@post call.respond(HttpStatusCode.BadRequest, MessageResponse(e.message ?: "Import failed"))
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadGateway, MessageResponse("Import failed: ${e.message}"))
            }
            val model = ChatMemoryRepo.settings(user.id).defaultModel ?: ChatModels.DEFAULT_MODEL
            val chat = ChatRepo.create(user.id, ChatPrompt.cleanTitle(imported.title), model)
            // The title came with the conversation — an auto-title would only overwrite it.
            ChatRepo.update(user.id, chat.id, chat.title, null, null, null, null, null, false)
            imported.turns.forEach { ChatRepo.addMessage(chat.id, it.role, it.content) }
            call.respond(ChatRepo.get(user.id, chat.id) ?: MessageResponse("imported"))
        }
    }
}

/** Stored preferences plus the read-only limit gauge the settings dialog shows. */
private fun settingsPayload(userId: Int, datapath: DatapathService): ChatSettingsDto =
    ChatMemoryRepo.settings(userId).copy(
        dailyChatCostLimit = UserRepo.dailyChatLimitOf(userId),
        todayChatCost = datapath.cachedDailySpend(userId, "chat"),
    )

/**
 * Which Anthropic content block a file becomes: images and PDFs travel natively, anything that
 * reads as text is inlined, everything else is refused rather than silently dropped upstream.
 */
internal fun attachmentKind(mime: String, name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> if (mime in IMAGE_MIMES) "image" else null
        mime == "application/pdf" -> "document"
        mime.startsWith("text/") -> "text"
        mime in TEXT_MIMES || ext in TEXT_EXTENSIONS -> "text"
        else -> null
    }
}

/** Browsers send an empty/octet-stream type for many code files; fall back to the extension. */
internal fun normalizeMime(declared: String, name: String): String {
    val base = declared.substringBefore(';').trim().lowercase().ifBlank { "application/octet-stream" }
    if (base != "application/octet-stream") return base
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        in TEXT_EXTENSIONS -> "text/plain"
        else -> base
    }
}

/** The four formats Anthropic accepts as image blocks. */
private val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

private val TEXT_MIMES = setOf(
    "application/json", "application/xml", "application/javascript", "application/x-yaml",
    "application/x-sh", "application/sql", "application/toml",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "env",
    "csv", "tsv", "log", "xml", "html", "htm", "css", "scss", "sql", "sh", "bash", "zsh",
    "ps1", "bat", "py", "rb", "go", "rs", "java", "kt", "kts", "swift", "c", "h", "cpp", "hpp",
    "cs", "php", "js", "jsx", "ts", "tsx", "vue", "svelte", "gradle", "properties", "dockerfile",
)

private fun io.ktor.server.application.ApplicationCall.intParam(name: String): Int? =
    parameters[name]?.toIntOrNull()

private suspend fun io.ktor.server.application.ApplicationCall.badId() =
    respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
