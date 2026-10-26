package org.claudeproxy.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudeproxy.repo.AttachmentBlob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat request body is what an OAuth subscription account accepts or rejects, so its shape
 * is worth pinning down: the Claude Code block must lead the system array, and the memory /
 * instruction blocks must follow it rather than replace it.
 */
class ChatPromptTest {

    private fun texts(arr: JsonArray) = arr.map { it.jsonObject["text"]!!.jsonPrimitive.content }

    @Test
    fun `claude code prompt is always the first system block`() {
        val blocks = ChatPrompt.systemBlocks()
        assertEquals(ChatPrompt.CLAUDE_CODE_PROMPT, texts(blocks).first())
        assertEquals(2, blocks.size, "the chat reframe follows the mandatory block")
    }

    @Test
    fun `memory and instructions are appended, never substituted`() {
        val blocks = ChatPrompt.systemBlocks(
            memory = listOf("Writes Kotlin", "Lives in Berlin"),
            aboutYou = "Backend engineer",
            responseStyle = "Be brief",
            chatPrompt = "Only talk about databases",
        )
        val all = texts(blocks)
        assertEquals(ChatPrompt.CLAUDE_CODE_PROMPT, all.first())
        assertEquals(5, all.size)
        assertTrue(all[2].contains("Writes Kotlin") && all[2].contains("Lives in Berlin"))
        assertTrue(all[3].contains("Backend engineer") && all[3].contains("Be brief"))
        assertTrue(all[4].contains("Only talk about databases"))
    }

    @Test
    fun `blank instruction halves are dropped rather than injected empty`() {
        assertNull(ChatPrompt.instructionsBlock(null, "   "))
        assertNull(ChatPrompt.memoryBlock(emptyList()))
        val blocks = ChatPrompt.systemBlocks(aboutYou = "  ", chatPrompt = "")
        assertEquals(2, blocks.size)
    }

    @Test
    fun `images and PDFs become native blocks, text files are inlined`() {
        val turn = ChatPrompt.Turn(
            "user", "What is this?",
            listOf(
                AttachmentBlob("shot.png", "image/png", "image", byteArrayOf(1, 2, 3)),
                AttachmentBlob("spec.pdf", "application/pdf", "document", byteArrayOf(4, 5)),
                AttachmentBlob("main.kt", "text/plain", "text", "fun main() {}".toByteArray()),
            ),
        )
        val content = ChatPrompt.messageObject(turn)["content"]!!.jsonArray
        val types = content.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("image", "document", "text", "text"), types)
        // the inlined file keeps its name so the model can refer to it
        val inlined = content[2].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(inlined.contains("main.kt") && inlined.contains("fun main() {}"))
        // the question itself is last
        assertEquals("What is this?", content[3].jsonObject["text"]!!.jsonPrimitive.content)
    }

    /** Anthropic rejects an empty text block, so an attachments-only turn needs a stand-in. */
    @Test
    fun `an attachment-only turn still carries text`() {
        val turn = ChatPrompt.Turn("user", "", listOf(AttachmentBlob("a.png", "image/png", "image", byteArrayOf(1))))
        val content = ChatPrompt.messageObject(turn)["content"]!!.jsonArray
        val text = content.last().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `thinking raises max_tokens above the budget and adds the tool only when asked`() {
        val plain = ChatPrompt.body("claude-sonnet-5", ChatPrompt.systemBlocks(), listOf(ChatPrompt.Turn("user", "hi")))
        assertNull(plain["thinking"])
        assertNull(plain["tools"])

        val rich = ChatPrompt.body(
            "claude-haiku-4-5-20251001", ChatPrompt.systemBlocks(), listOf(ChatPrompt.Turn("user", "hi")),
            webSearch = true, thinking = true,
        )
        val budget = (rich["thinking"] as JsonObject)["budget_tokens"]!!.jsonPrimitive.int
        val maxTokens = rich["max_tokens"]!!.jsonPrimitive.int
        assertTrue(maxTokens > budget, "max_tokens must leave room for the answer after the thinking budget")
        assertEquals("web_search_20250305", rich["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `current models get adaptive thinking, never a budget they would 400`() {
        for (model in listOf("claude-opus-5-5", "claude-opus-5", "claude-opus-4-7", "claude-sonnet-5-5", "claude-fable-5-1", "claude-sonnet-4-6")) {
            val t = ChatPrompt.body(model, ChatPrompt.systemBlocks(), listOf(ChatPrompt.Turn("user", "hi")), thinking = true)["thinking"] as JsonObject
            assertEquals("adaptive", t["type"]!!.jsonPrimitive.content, model)
            assertNull(t["budget_tokens"], model)
        }
        for (model in listOf("claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250929", "claude-opus-4-20250514", "claude-3-7-sonnet-20250219")) {
            assertFalse(ChatPrompt.adaptiveThinking(model), model)
        }
    }

    /**
     * Prompt caching is what makes a long chat affordable: the system prefix never changes and the
     * transcript only grows at the end, so both are re-read on every turn unless marked.
     */
    @Test
    fun `the system prefix ends with a cache breakpoint`() {
        val blocks = ChatPrompt.systemBlocks(memory = listOf("Writes Kotlin"))
        val marked = blocks.count { it.jsonObject.containsKey("cache_control") }
        assertEquals(1, marked, "exactly one breakpoint, on the last block")
        assertEquals("ephemeral", blocks.last().jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // …and it is opt-out, for the one-shot utility calls that can never hit a cache
        assertTrue(ChatPrompt.systemBlocks(cache = false).none { it.jsonObject.containsKey("cache_control") })
    }

    @Test
    fun `a growing conversation keeps a rolling pair of breakpoints`() {
        assertEquals(emptySet(), ChatPrompt.cacheMarks(0))
        assertEquals(setOf(0), ChatPrompt.cacheMarks(1))
        assertEquals(setOf(1), ChatPrompt.cacheMarks(2))
        // newest turn + two back, so an expired write still leaves an older prefix to read
        assertEquals(setOf(0, 2), ChatPrompt.cacheMarks(3))
        assertEquals(setOf(4, 6), ChatPrompt.cacheMarks(7))
    }

    @Test
    fun `breakpoints land on the last content block of the marked turns and nowhere else`() {
        val turns = (1..5).map { ChatPrompt.Turn(if (it % 2 == 1) "user" else "assistant", "turn $it") }
        val messages = ChatPrompt.body("claude-sonnet-5", ChatPrompt.systemBlocks(), turns)["messages"]!!.jsonArray
        val marked = messages.mapIndexedNotNull { i, m ->
            i.takeIf { m.jsonObject["content"]!!.jsonArray.any { b -> b.jsonObject.containsKey("cache_control") } }
        }
        assertEquals(listOf(2, 4), marked)
        // total breakpoints stay within Anthropic's limit of four (one of them is the system's)
        assertTrue(marked.size + 1 <= 4)

        val attachTurn = ChatPrompt.Turn("user", "look", listOf(AttachmentBlob("a.png", "image/png", "image", byteArrayOf(1))))
        val content = ChatPrompt.messageObject(attachTurn, cache = true)["content"]!!.jsonArray
        assertFalse(content.first().jsonObject.containsKey("cache_control"), "not on the image")
        assertTrue(content.last().jsonObject.containsKey("cache_control"), "on the block that ends the turn")
    }

    /**
     * The clock rides on the turns, not the system prefix: a timestamp up there would change the
     * cached prefix on every request and turn every cache read into a miss.
     */
    @Test
    fun `user turns are stamped with their time and the system prefix stays clock-free`() {
        val at = java.time.Instant.parse("2026-08-01T06:14:00Z")
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val content = ChatPrompt.messageObject(ChatPrompt.Turn("user", "hi", sentAt = at), zone = zone)["content"]!!.jsonArray
        assertEquals("[sent 2026-08-01 08:14, Europe/Berlin]", content.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("hi", content.last().jsonObject["text"]!!.jsonPrimitive.content)

        // assistant turns carry no stamp — they answer the turn right above them
        val reply = ChatPrompt.messageObject(ChatPrompt.Turn("assistant", "hello", sentAt = at))["content"]!!.jsonArray
        assertEquals(1, reply.size)

        // an unstamped turn (a temporary transcript replayed from the browser) stays as-is
        val bare = ChatPrompt.messageObject(ChatPrompt.Turn("user", "hi"))["content"]!!.jsonArray
        assertEquals(1, bare.size)

        assertTrue(
            ChatPrompt.systemBlocks().none { it.jsonObject["text"]!!.jsonPrimitive.content.contains("2026-") },
            "no wall-clock value in the cached system prefix",
        )
    }

    @Test
    fun `titles are trimmed of quotes, trailing punctuation and extra lines`() {
        assertEquals("Deploying the proxy", ChatPrompt.cleanTitle("\"Deploying the proxy.\"\nsomething else"))
        assertEquals("New chat", ChatPrompt.cleanTitle("   "))
    }

    @Test
    fun `stub titles cut on a word boundary`() {
        val long = "Explain how the rolling window rate limits work in this proxy implementation"
        val stub = ChatPrompt.stubTitle(long)
        assertTrue(stub.length <= 50)
        assertTrue(stub.endsWith("…"))
        assertFalse(stub.contains("  "))
        assertEquals("Short one", ChatPrompt.stubTitle("\n\nShort one\nmore"))
    }
}

private val kotlinx.serialization.json.JsonPrimitive.int: Int get() = content.toInt()
