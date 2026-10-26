package org.claudeproxy.chat

import org.claudeproxy.api.ChatModelDto
import org.claudeproxy.catalog.ModelCatalog

/**
 * The model picker's catalogue — the proxy-wide [ModelCatalog] (Anthropic's live listing under
 * operator overrides), so the chat offers exactly what the routing gateways advertise.
 */
object ChatModels {
    val DEFAULT_MODEL: String = "claude-sonnet-5"

    /** Cheap model used for the background chores (auto-title, memory extraction). */
    val UTILITY_MODEL: String = "claude-haiku-4-5-20251001"

    /** True when [id] is something we are willing to send upstream (guards a hand-crafted body). */
    fun isKnown(id: String): Boolean = ModelCatalog.isValidId(id)

    fun list(): List<ChatModelDto> =
        ModelCatalog.visible().map { ChatModelDto(it.id, it.displayName.removePrefix("Claude "), it.description) }
}
