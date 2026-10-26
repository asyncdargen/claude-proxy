package org.claudeproxy.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.claudeproxy.repo.AttachmentBlob
import java.util.Base64

/**
 * Builds the Anthropic Messages request for a chat turn. Pure JSON assembly — no I/O — so the
 * shape of the prompt (which is the load-bearing part for OAuth accounts) is unit-testable.
 *
 * The FIRST system block must be exactly the Claude Code prompt or subscription tokens reject the
 * request (see CLAUDE.md); everything the chat UI wants to say goes into the blocks after it,
 * starting with one that reframes the assistant from "CLI" to "web chat".
 */
object ChatPrompt {

    /** The exact first system block Anthropic requires for OAuth subscription auth. */
    const val CLAUDE_CODE_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude."

    /**
     * Reframes the turn for the chat UI. The Claude Code block above it describes a terminal
     * agent; without this the model opens with CLI habits (tool plans, file paths, terse
     * acknowledgements) in a window that has none of that.
     */
    const val CHAT_PROMPT = """You are now answering inside a web chat interface, not a terminal.
Respond conversationally and helpfully to whatever the user asks.

Formatting:
- Reply in GitHub-flavored Markdown: headings, **bold**, lists, tables, links, and blockquotes all render.
- Put code in fenced blocks with a language tag (```python), including short snippets.
- Use LaTeX between ${'$'}…${'$'} (inline) or ${'$'}${'$'}…${'$'}${'$'} (display) for mathematics.
- Keep answers as long as they need to be and no longer; lead with the answer, then the detail.
- Answer in the language the user writes in.

Each user message is preceded by `[sent <date> <time>, <timezone>]`. That is metadata, not part of
what the user wrote: never repeat it or comment on it. Read the newest one as the current time, and
use the gaps between them when they matter ("yesterday", "a moment ago", a stale answer)."""

    /** Wraps the remembered facts so the model treats them as background, not as instructions. */
    fun memoryBlock(facts: List<String>): String? {
        if (facts.isEmpty()) return null
        return "Here is what you remember about this user from earlier conversations. " +
            "Use it when relevant; do not bring it up unprompted, and do not mention that you have stored memories.\n" +
            facts.joinToString("\n") { "- $it" }
    }

    /** The user's global custom instructions ("about you" + "response style"), if any. */
    fun instructionsBlock(aboutYou: String?, responseStyle: String?): String? {
        val parts = buildList {
            aboutYou?.trim()?.takeIf { it.isNotEmpty() }?.let { add("About the user:\n$it") }
            responseStyle?.trim()?.takeIf { it.isNotEmpty() }?.let { add("How the user wants you to respond:\n$it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * The `system` array: Claude Code first (mandatory), then the chat reframe, then the user's
     * memory, global instructions and this chat's own prompt — each only when it has content.
     *
     * The last block carries a cache breakpoint, so tools + the whole system prefix are cached
     * (Anthropic caches in tools → system → messages order). This prefix is identical on every
     * turn of a conversation, which makes it the cheapest thing in the request to stop re-reading.
     */
    fun systemBlocks(
        memory: List<String> = emptyList(),
        aboutYou: String? = null,
        responseStyle: String? = null,
        chatPrompt: String? = null,
        cache: Boolean = true,
    ): JsonArray {
        val texts = buildList {
            add(CLAUDE_CODE_PROMPT)
            add(CHAT_PROMPT)
            memoryBlock(memory)?.let { add(it) }
            instructionsBlock(aboutYou, responseStyle)?.let { add(it) }
            chatPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Instructions for this conversation:\n$it") }
        }
        return buildJsonArray {
            texts.forEachIndexed { i, t ->
                addJsonObject {
                    put("type", "text")
                    put("text", t)
                    if (cache && i == texts.lastIndex) putCacheControl()
                }
            }
        }
    }

    /** Marks a content block as the end of a cacheable prefix (Anthropic's default 5-minute TTL). */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putCacheControl() {
        putJsonObject("cache_control") { put("type", "ephemeral") }
    }

    /**
     * A turn to send upstream: role plus its text, its attachments (user turns only) and when it
     * was sent. [sentAt] is stamped into the turn rather than the system prompt on purpose — the
     * system prefix has to stay byte-identical across a conversation for the cache to hit, and a
     * clock in it would miss on every single request.
     */
    data class Turn(
        val role: String,
        val text: String,
        val attachments: List<AttachmentBlob> = emptyList(),
        val sentAt: java.time.Instant? = null,
    )

    private val STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** `[sent 2026-08-01 09:14, Europe/Berlin]` — the marker the system prompt tells it to read. */
    internal fun timeMarker(at: java.time.Instant, zone: java.time.ZoneId): String =
        "[sent ${STAMP.format(at.atZone(zone))}, $zone]"

    /**
     * One `messages` entry. Attachments lead the block list (Anthropic reads images/documents
     * better before the question); text files are inlined as tagged text rather than uploaded,
     * since the document block only accepts PDFs.
     */
    fun messageObject(
        turn: Turn, cache: Boolean = false, zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
    ): JsonObject = buildJsonObject {
        put("role", if (turn.role == "assistant") "assistant" else "user")
        putJsonArray("content") {
            // A user turn leads with when it was sent, so the model can tell "an hour ago" from
            // "last week" and knows what time it is now (the newest turn's stamp).
            if (turn.role != "assistant" && turn.sentAt != null) {
                addJsonObject { put("type", "text"); put("text", timeMarker(turn.sentAt, zone)) }
            }
            turn.attachments.forEach { att ->
                when (att.kind) {
                    "image" -> addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", att.mimeType)
                            put("data", Base64.getEncoder().encodeToString(att.data))
                        }
                    }
                    "document" -> addJsonObject {
                        put("type", "document")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", att.mimeType)
                            put("data", Base64.getEncoder().encodeToString(att.data))
                        }
                    }
                    else -> addJsonObject {
                        put("type", "text")
                        put("text", "<file name=\"${att.name}\">\n${att.data.decodeToString()}\n</file>")
                    }
                }
            }
            // An empty text block is rejected upstream; a turn that is only attachments still
            // needs something to act on, so give it a neutral instruction.
            val text = turn.text.ifBlank { if (turn.attachments.isEmpty()) " " else "(see the attached files)" }
            addJsonObject {
                put("type", "text")
                put("text", text)
                if (cache) putCacheControl()
            }
        }
    }

    /**
     * Which turns end a cacheable prefix. The transcript only ever grows at the end, so a rolling
     * pair keeps the cache useful: the newest turn writes the prefix this request just built, and
     * an older breakpoint (two turns back) is still a *read* on the next request even if the newest
     * write expired. Fewer than two turns needs no rolling pair — the single mark covers it.
     *
     * Anthropic allows four breakpoints in total; one goes to the system prefix, leaving room.
     */
    internal fun cacheMarks(turnCount: Int): Set<Int> = when {
        turnCount <= 0 -> emptySet()
        turnCount <= 2 -> setOf(turnCount - 1)
        else -> setOf(turnCount - 3, turnCount - 1)
    }

    /** Extended-thinking budget and the ceiling it needs; `max_tokens` must exceed the budget. */
    private const val THINKING_BUDGET = 10_000
    private const val MAX_TOKENS = 16_000
    private const val MAX_TOKENS_THINKING = 32_000

    /**
     * Whether [model] takes `thinking: {type: "adaptive"}`. From Opus 4.7 and the whole 5 generation
     * on, a fixed `budget_tokens` is a 400 (Opus 5.5 rejected every chat turn with thinking on);
     * 4.6 still accepts it but adaptive is its native mode. Haiku 4.5 and older only know budgets.
     */
    internal fun adaptiveThinking(model: String): Boolean {
        val m = Regex("""^claude-([a-z]+)-(\d{1,2})(?:-(\d{1,2}))?(?:-|$)""").find(model.lowercase()) ?: return false
        val family = m.groupValues[1]
        val major = m.groupValues[2].toInt()
        val minor = m.groupValues[3].toIntOrNull() ?: 0
        return major >= 5 || (major == 4 && minor >= 6 && family != "haiku")
    }

    /**
     * The full Messages request body. [webSearch] adds Anthropic's server-side search tool (billed
     * per invocation and priced by `web_search_price`); [thinking] turns on extended thinking,
     * whose deltas the UI shows in a collapsed block.
     */
    fun body(
        model: String,
        system: JsonArray,
        turns: List<Turn>,
        webSearch: Boolean = false,
        thinking: Boolean = false,
        stream: Boolean = true,
        cache: Boolean = true,
        zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
    ): JsonObject = buildJsonObject {
        val marks = if (cache) cacheMarks(turns.size) else emptySet()
        put("model", model)
        put("max_tokens", if (thinking) MAX_TOKENS_THINKING else MAX_TOKENS)
        put("stream", stream)
        put("system", system)
        putJsonArray("messages") { turns.forEachIndexed { i, t -> add(messageObject(t, i in marks, zone)) } }
        if (thinking) putJsonObject("thinking") {
            if (adaptiveThinking(model)) {
                put("type", "adaptive")
                // These models omit the reasoning text by default; the UI shows it, so ask for it.
                put("display", "summarized")
            } else {
                put("type", "enabled")
                put("budget_tokens", THINKING_BUDGET)
            }
        }
        if (webSearch) putJsonArray("tools") {
            addJsonObject {
                put("type", "web_search_20250305")
                put("name", "web_search")
                put("max_uses", 8)
            }
        }
    }

    /** A short, non-streaming request used for the auxiliary calls (auto-title, memory extraction). */
    fun utilityBody(model: String, prompt: String, maxTokens: Int): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("stream", false)
        putJsonArray("system") {
            addJsonObject { put("type", "text"); put("text", CLAUDE_CODE_PROMPT) }
        }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject { put("type", "text"); put("text", prompt) }
                }
            }
        }
    }

    /**
     * Asks for the carry-over context when a conversation is continued in a fresh chat. The point
     * is a working handover, not a summary: the new chat starts with an empty window, so anything
     * dropped here is genuinely lost. Written to be pasted into a system block verbatim.
     */
    fun compactionPrompt(transcript: String): String = """
        Below is a conversation between a user and an assistant. It is being continued in a new,
        empty chat, and you are writing the only thing that carries over.

        Write a handover in Markdown, in the language of the conversation, covering:
        - who the user is and what they are working on, as far as this conversation shows it;
        - the task: what was asked, what was decided, and why;
        - concrete artefacts that matter — names, paths, versions, identifiers, commands, and any
          code or configuration that would have to be retyped otherwise (quote it, don't describe it);
        - constraints, preferences and corrections the user gave;
        - where things stand right now and what the open questions are.

        Be complete over concise: a detail you leave out cannot be recovered. Do not add advice,
        do not address the user, and do not mention that this is a summary.

        --- conversation ---
        $transcript
    """.trimIndent()

    /** The first, visible message of a continued chat — what the user sees carried over. */
    fun carryOverMessage(context: String): String =
        "**Context carried over from the previous chat**\n\n$context"

    /** Trims a generated title down to something that fits the sidebar. */
    fun cleanTitle(raw: String): String =
        raw.trim().removeSurrounding("\"").lineSequence().firstOrNull()?.trim()?.trim('.', '"', '«', '»')
            ?.take(70)?.ifBlank { null } ?: "New chat"

    /** A stub title from the first user message, shown until the generated one lands. */
    fun stubTitle(message: String): String {
        val line = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "New chat"
        return if (line.length <= 48) line else line.take(48).substringBeforeLast(' ', line.take(48)) + "…"
    }

    /** JSON string helper for callers assembling raw fragments (kept next to the builders). */
    fun jsonString(s: String): String = JsonPrimitive(s).toString()
}
