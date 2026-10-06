package org.claudeproxy.chat

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.claudeproxy.proxy.Http
import org.slf4j.LoggerFactory

/** One imported turn. */
data class ImportedTurn(val role: String, val content: String)

/** A conversation lifted out of a share page. */
data class ImportedChat(val title: String, val turns: List<ImportedTurn>)

/**
 * Imports a shared conversation from a public link — `chatgpt.com/share/…`, `claude.ai/share/…`
 * or a pasted export blob.
 *
 * Share pages are not an API: their markup changes without notice, so the fetch tries several
 * shapes and the *parsing* is kept pure (and unit-tested) rather than tied to whatever a live
 * page happens to serve today. When nothing matches, the caller gets a plain error instead of a
 * half-imported chat.
 */
object ShareImport {
    private val log = LoggerFactory.getLogger("ShareImport")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // A share page renders for a browser; a bare client gets a redirect or a challenge.
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    class ImportError(message: String) : RuntimeException(message)

    /** Fetch + parse. Blocking network work, so callers run it off the request's critical path. */
    suspend fun fetch(rawUrl: String): ImportedChat {
        val input = rawUrl.trim()
        // A pasted export blob skips the network entirely — the most reliable path there is.
        if (input.startsWith("{") || input.startsWith("[")) {
            return parseAny(runCatching { json.parseToJsonElement(input) }.getOrNull()
                ?: throw ImportError("That doesn't look like valid JSON."))
        }
        val url = runCatching { java.net.URI(input) }.getOrNull()
            ?: throw ImportError("Not a valid link.")
        val host = url.host?.lowercase()?.removePrefix("www.") ?: throw ImportError("Not a valid link.")
        if (url.scheme !in setOf("http", "https")) throw ImportError("Only http(s) links can be imported.")

        // Known share hosts only. The fetch runs from inside the server's network, so an arbitrary
        // host turned the import box into a GET against localhost and the compose network.
        val candidates = when {
            isHost(host, "chatgpt.com") || isHost(host, "openai.com") -> chatGptCandidates(input)
            isHost(host, "claude.ai") -> claudeCandidates(input)
            else -> throw ImportError("Only chatgpt.com and claude.ai share links can be imported.")
        }
        for (candidate in candidates) {
            val body = runCatching { get(candidate) }.getOrNull() ?: continue
            val element = if (candidate.contains("/api/") || candidate.contains("backend-api"))
                runCatching { json.parseToJsonElement(body) }.getOrNull()
            else embeddedJson(body)
            if (element != null) {
                val parsed = runCatching { parseAny(element) }.getOrNull()
                if (parsed != null && parsed.turns.isNotEmpty()) return parsed
            }
        }
        throw ImportError(
            "Couldn't read that conversation. Share links are only importable while they are public — " +
                "you can also paste the exported JSON directly.",
        )
    }

    /** [host] is [domain] or a subdomain of it — a bare suffix match would also take `evilclaude.ai`. */
    internal fun isHost(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    /** Share id → the JSON endpoints and the page itself, most reliable first. */
    internal fun chatGptCandidates(url: String): List<String> {
        val id = url.trimEnd('/').substringAfterLast('/')
        return listOfNotNull(
            id.takeIf { it.isNotBlank() }?.let { "https://chatgpt.com/backend-api/share/$it" },
            url,
        )
    }

    internal fun claudeCandidates(url: String): List<String> {
        val id = url.trimEnd('/').substringAfterLast('/')
        return listOfNotNull(
            id.takeIf { it.isNotBlank() }?.let { "https://claude.ai/api/chat_snapshots/$it" },
            url,
        )
    }

    private suspend fun get(url: String): String? {
        val host = java.net.URI(url).host.lowercase()
        val client = if (isHost(host, "claude.ai")) Http.client else Http.publicShareClient
        return client.prepareGet(url) {
            header("User-Agent", UA)
            header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
        }.execute { response ->
            if (response.status.value !in 200..299) {
                log.debug("share fetch {} -> {}", url, response.status.value)
                null
            } else {
                response.readRawBytes().decodeToString()
            }
        }
    }

    // ---- pure parsing (unit-tested) ----

    /**
     * Pull the page's embedded state out of HTML. Share pages ship the conversation as JSON in a
     * `<script>` tag; which tag it is has changed more than once, so any script whose body parses
     * as JSON and contains a conversation is accepted.
     */
    internal fun embeddedJson(html: String): JsonElement? {
        val scriptRe = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
        val hints = listOf("linear_conversation", "\"mapping\"", "chat_messages")
        return scriptRe.findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { body -> hints.any { body.contains(it) } }
            .mapNotNull { body ->
                val start = body.indexOf('{')
                val end = body.lastIndexOf('}')
                if (start < 0 || end <= start) null
                else runCatching { json.parseToJsonElement(body.substring(start, end + 1)) }.getOrNull()
            }
            .firstOrNull()
    }

    /** Dispatch on whatever conversation shape is buried in [root]. */
    internal fun parseAny(root: JsonElement): ImportedChat {
        val node = findConversation(root) ?: throw ImportError("No conversation found at that link.")
        val title = node.stringOf("title") ?: node.stringOf("name") ?: "Imported chat"
        val turns = when {
            node["chat_messages"] is JsonArray -> parseClaude(node)
            else -> parseChatGpt(node)
        }
        if (turns.isEmpty()) throw ImportError("That conversation appears to be empty.")
        return ImportedChat(title, turns)
    }

    /** Depth-first search for the object that actually holds the transcript. */
    internal fun findConversation(root: JsonElement, depth: Int = 0): JsonObject? {
        if (depth > 12) return null
        when (root) {
            is JsonObject -> {
                if (root["linear_conversation"] is JsonArray || root["mapping"] is JsonObject ||
                    root["chat_messages"] is JsonArray
                ) return root
                root.values.forEach { child -> findConversation(child, depth + 1)?.let { return it } }
            }
            is JsonArray -> root.forEach { child -> findConversation(child, depth + 1)?.let { return it } }
            else -> {}
        }
        return null
    }

    /**
     * ChatGPT: `linear_conversation` is already ordered; a bare `mapping` is a tree, walked from
     * the root down its first child each time — which is the branch a share link renders.
     */
    internal fun parseChatGpt(node: JsonObject): List<ImportedTurn> {
        val ordered = (node["linear_conversation"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: walkMapping(node["mapping"] as? JsonObject ?: return emptyList())
        return ordered.mapNotNull { entry ->
            val message = entry["message"] as? JsonObject ?: return@mapNotNull null
            val role = (message["author"] as? JsonObject)?.stringOf("role") ?: return@mapNotNull null
            if (role !in setOf("user", "assistant")) return@mapNotNull null
            val text = chatGptText(message["content"]).trim()
            if (text.isEmpty()) null else ImportedTurn(role, text)
        }
    }

    private fun walkMapping(mapping: JsonObject): List<JsonObject> {
        val nodes = mapping.values.filterIsInstance<JsonObject>()
        val root = nodes.firstOrNull { it["parent"] == null || it["parent"] is kotlinx.serialization.json.JsonNull }
            ?: return nodes
        val out = ArrayList<JsonObject>()
        var current: JsonObject? = root
        val seen = HashSet<String>()
        while (current != null) {
            current.stringOf("id")?.let { if (!seen.add(it)) return out }
            out.add(current)
            val childId = (current["children"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
            current = childId?.let { mapping[it] as? JsonObject }
        }
        return out
    }

    /** `content` is `{content_type, parts:[…]}` for text and something else for images/tools. */
    private fun chatGptText(content: JsonElement?): String {
        val obj = content as? JsonObject ?: return ""
        if (obj.stringOf("content_type") !in setOf(null, "text", "multimodal_text")) return ""
        val parts = obj["parts"] as? JsonArray ?: return obj.stringOf("text") ?: ""
        return parts.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> part.stringOf("text")
                else -> null
            }
        }.joinToString("\n").trim()
    }

    /** Claude: `chat_messages[]` with `sender` and either `text` or a `content` block array. */
    internal fun parseClaude(node: JsonObject): List<ImportedTurn> {
        val messages = node["chat_messages"] as? JsonArray ?: return emptyList()
        return messages.filterIsInstance<JsonObject>().mapNotNull { m ->
            val sender = m.stringOf("sender") ?: m.stringOf("role") ?: return@mapNotNull null
            val role = if (sender == "human" || sender == "user") "user" else "assistant"
            val text = (m["content"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.filter { it.stringOf("type") == "text" }
                ?.mapNotNull { it.stringOf("text") }
                ?.joinToString("\n")
                ?.ifBlank { null }
                ?: m.stringOf("text") ?: return@mapNotNull null
            if (text.isBlank()) null else ImportedTurn(role, text.trim())
        }
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}
