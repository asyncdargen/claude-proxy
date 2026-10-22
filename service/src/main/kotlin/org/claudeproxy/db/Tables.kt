package org.claudeproxy.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : Table("users") {
    val sessionVersion = long("session_version").default(0L)
    val id = integer("id").autoIncrement()
    val username = varchar("username", 128).uniqueIndex()
    val passwordHash = varchar("password_hash", 256)
    val enabled = bool("enabled").default(true)
    // optional per-day spend limit in USD. null = unlimited.
    val dailyCostLimit = double("daily_cost_limit").nullable()
    // optional per-day spend limit in USD for the OpenAI/Anthropic routing gateways. null = unlimited.
    val dailyRoutingCostLimit = double("daily_routing_cost_limit").nullable()
    // optional per-day spend limit in USD for the built-in chat UI. null = unlimited.
    val dailyChatCostLimit = double("daily_chat_cost_limit").nullable()
    // routing preference: true = try the global pool before this user's personal accounts.
    val preferGlobalPool = bool("prefer_global_pool").default(false)
    // optional, admin-set, stored lower-cased. Only used to link a first OIDC login to this
    // account (OIDC_LINK_BY_EMAIL); unique so a verified email can never match two users.
    val email = varchar("email", 320).nullable().uniqueIndex("users_email_unique")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * External OIDC identities linked to a local user. The (issuer, subject) pair is the only stable
 * identifier OIDC guarantees, so it — never email or username — is what a returning login is
 * matched on. One identity per issuer per user.
 */
object UserIdentities : Table("user_identities") {
    val id = integer("id").autoIncrement()
    // CASCADE, like the other per-user tables: a release that does not know this table can still
    // delete a user (rollback safety). UserRepo.delete also removes the rows explicitly.
    val userId = integer("user_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val issuer = varchar("issuer", 512)
    val subject = varchar("subject", 255)
    val createdAt = timestamp("created_at")
    val lastLoginAt = timestamp("last_login_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("user_identities_iss_sub", issuer, subject)
        uniqueIndex("user_identities_user_iss", userId, issuer)
    }
}

/** Simple key/value settings store (admin-tunable). */
object Settings : Table("settings") {
    val key = varchar("key", 64)
    val value = varchar("value", 256)
    override val primaryKey = PrimaryKey(key)
}

/** Per-model pricing ($ per million tokens), matched by substring of the request model id. */
object ModelPrices : Table("model_prices") {
    val pattern = varchar("pattern", 64)   // e.g. "haiku", "sonnet", "opus"
    val inputPrice = double("input_price").default(0.0)             // USD / 1M input tokens
    val outputPrice = double("output_price").default(0.0)          // USD / 1M output tokens
    val cacheReadPrice = double("cache_read_price").default(0.0)    // USD / 1M cache-read tokens
    // USD / 1M cache-write tokens at the default 5-minute TTL (Anthropic's "5m Cache Writes").
    val cacheWritePrice = double("cache_write_price").default(0.0)
    // USD / 1M cache-write tokens at the 1-hour TTL — a separate, higher tier (2× input vs the
    // 5m tier's 1.25×). Claude Code ≥2.1 writes its main-loop prefix with ttl:"1h", so without
    // its own price most cache-write spend on the proxy datapath is under-counted.
    val cacheWrite1hPrice = double("cache_write_1h_price").default(0.0)
    // Multiplier applied to every token price when the response was served in fast mode
    // (`speed: "fast"`): same model, premium tier — Opus 5 fast is $10/$50 against $5/$25.
    val fastMultiplier = double("fast_multiplier").default(2.0)
    // USD per server-side web-search invocation ($10 / 1000 searches). Billed per request, not
    // per token, so it lives outside the /1M columns.
    val webSearchPrice = double("web_search_price").default(0.01)
    override val primaryKey = PrimaryKey(pattern)
}

object Roles : Table("roles") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 64).uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object RolePermissions : Table("role_permissions") {
    val roleId = integer("role_id").references(Roles.id)
    val permission = varchar("permission", 64)
    override val primaryKey = PrimaryKey(roleId, permission)
}

object UserRoles : Table("user_roles") {
    val userId = integer("user_id").references(Users.id)
    val roleId = integer("role_id").references(Roles.id)
    override val primaryKey = PrimaryKey(userId, roleId)
}

object ProxyTokens : Table("proxy_tokens") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val tokenHash = varchar("token_hash", 128).uniqueIndex()
    val name = varchar("name", 128)
    // off = the token stops authenticating (resolves like an unknown token) without being revoked.
    val enabled = bool("enabled").default(true)
    // optional model forced onto every request made with this token, whatever the client asked
    // for. Claude Code's own notation: a `[1m]` suffix also turns on the 1M-context beta.
    val defaultModel = varchar("default_model", 128).nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Tokens for the OpenAI/Anthropic API routing gateways (`cxr_...`). Same shape as
 * [ProxyTokens] but a distinct namespace: routing spend is metered against a separate
 * per-user daily limit and gated by the `ROUTING_USE` permission.
 */
object RoutingTokens : Table("routing_tokens") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val tokenHash = varchar("token_hash", 128).uniqueIndex()
    val name = varchar("name", 128)
    // optional static system prompt injected by the routing gateways right after the mandatory
    // Claude Code block — i.e. ahead of (higher priority than) any client-supplied system.
    val systemPrompt = text("system_prompt").nullable()
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Named groups of upstream accounts, used to scope which users may use which accounts. */
object AccountGroups : Table("account_groups") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 128).uniqueIndex()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Grants a user access to all accounts in a group. */
object UserGroupAccess : Table("user_group_access") {
    val userId = integer("user_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val groupId = integer("group_id").references(AccountGroups.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(userId, groupId)
}

object Accounts : Table("accounts") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 128)
    val type = varchar("type", 32)
    val groupId = integer("group_id").references(AccountGroups.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.SET_NULL).nullable()
    val priority = integer("priority").default(100)
    val threshold = double("threshold").default(0.9)
    val coefficient = double("coefficient").default(1.0)
    val enabled = bool("enabled").default(true)
    // opt-in fallback: when every account is over its threshold, only accounts with this flag
    // stay usable past their threshold; others drop out of selection until their window resets.
    val overThreshold = bool("over_threshold").default(false)
    val health = varchar("health", 32).default("OK")
    val rateLimitedUntil = timestamp("rate_limited_until").nullable()
    // distinct per-account device fingerprint (64-hex) substituted into the request body's
    // metadata.user_id.device_id. DB column keeps its historical name `client_id`.
    val deviceId = varchar("client_id", 64).nullable()
    // Anthropic account uuid of an OAuth subscription, learned at login (token response) or from
    // /api/oauth/profile. Stamped into metadata.user_id.account_uuid: a genuine OAuth Claude Code
    // sends its own there, so an empty one — or the client's — next to our bearer is a tell.
    val accountUuid = varchar("account_uuid", 64).nullable()
    // null = global (shared pool); otherwise a personal account owned by this user, tried
    // before the global pool and excluded from global statistics.
    val ownerId = integer("owner_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE).nullable()
    val createdBy = integer("created_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Encrypted credential blob (JSON) per account. */
object AccountSecrets : Table("account_secrets") {
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val cipherBlob = text("cipher_blob")
    override val primaryKey = PrimaryKey(accountId)
}

/** Live limit state per account per window, persisted so it survives restarts. */
object AccountLimits : Table("account_limits") {
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val windowKind = varchar("window_kind", 8)   // "5h" | "7d"
    val utilization = double("utilization").nullable()
    val remaining = double("remaining").nullable()
    val limitTotal = double("limit_total").nullable()
    val resetAt = timestamp("reset_at").nullable()
    val status = varchar("status", 32).default("UNKNOWN")
    val updatedAt = timestamp("updated_at").nullable()
    override val primaryKey = PrimaryKey(accountId, windowKind)
}

object UsageEvents : Table("usage_events") {
    val id = integer("id").autoIncrement()
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val userId = integer("user_id").references(Users.id).nullable()
    val ts = timestamp("ts")
    val inputTokens = long("input_tokens").default(0)          // base (non-cache) input
    val outputTokens = long("output_tokens").default(0)
    val cacheReadTokens = long("cache_read_tokens").default(0)
    val cacheWriteTokens = long("cache_write_tokens").default(0)  // both TTLs
    // The 1h-TTL slice of cacheWriteTokens, stored so the cost above stays auditable/re-derivable
    // (it is priced at a different rate than the 5m remainder).
    val cacheWrite1hTokens = long("cache_write_1h_tokens").default(0)
    // Server-side tool calls billed per invocation rather than per token.
    val webSearchRequests = long("web_search_requests").default(0)
    val webFetchRequests = long("web_fetch_requests").default(0)
    // Response served in fast mode (premium price tier on the same model).
    val fast = bool("fast").default(false)
    // computed USD cost of this request from model pricing at record time
    val cost = double("cost").default(0.0)
    val httpStatus = integer("http_status").default(0)
    val model = varchar("model", 128).nullable()
    // datapath that produced this event: "proxy" (Claude Code) or "routing" (OpenAI/Anthropic
    // API gateways). Metered against separate per-user daily limits; stats show both together.
    val sourceCol = varchar("source", 16).default("proxy")
    // inbound token that authenticated the request. Deliberately NOT an FK: deleting a token
    // must keep its usage history. `source` picks the namespace ("proxy" → proxy_tokens.id,
    // "routing" → routing_tokens.id); null = pre-migration rows or token-less attempts.
    val tokenId = integer("token_id").nullable()
    override val primaryKey = PrimaryKey(id)
    // Range reads per account (window attribution).
    init { index(false, accountId, ts) }
}

/**
 * MCP tool invocations observed on the Claude Code datapath: one row per (request, tool),
 * counted by the gateway from `tool_use` content blocks named `mcp__server__tool` in the
 * response. Counts only — token/cost attribution stays in [UsageEvents].
 */
object McpToolCalls : Table("mcp_tool_calls") {
    val id = long("id").autoIncrement()
    val ts = timestamp("ts")
    val userId = integer("user_id").references(Users.id).nullable()
    // inbound proxy token. Deliberately NOT an FK: deleting a token keeps its call history.
    val tokenId = integer("token_id").nullable()
    val toolName = varchar("tool_name", 256)
    val calls = integer("calls").default(1)
    override val primaryKey = PrimaryKey(id)
    init { index(false, userId, ts) }
}

/** Time series of observed window utilization (0..1) per account per window. */
object WindowSnapshots : Table("window_snapshots") {
    val id = long("id").autoIncrement()
    val accountId = integer("account_id").references(Accounts.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val windowKind = varchar("window_kind", 8)   // "5h" | "7d"
    val utilization = double("utilization")
    // coefficient in effect when this point was recorded, FROZEN at write time so the
    // ×coef-weighted charts keep their historical weighting even after an account's coefficient
    // later changes. Nullable only for pre-migration rows; backfilled once from the account's
    // current coefficient at startup (see Db.migrateWindowSnapshotCoefficient), and always
    // written for new rows.
    val coefficient = double("coefficient").nullable()
    // Actual observed reset only; legacy rows intentionally stay NULL.
    val resetAt = timestamp("reset_at").nullable()
    val ts = timestamp("ts")
    override val primaryKey = PrimaryKey(id)
    init { index(false, accountId, windowKind, ts) }
}

/**
 * Session-id rotation state. To stop Anthropic correlating one client session-id across
 * several accounts, the first account to use a given client (origin) session-id keeps it
 * unchanged; every other account gets a stable, distinct replacement.
 */
object SessionOwners : Table("session_owner") {
    // the client's original X-Claude-Code-Session-Id
    val origin = varchar("origin", 64)
    // the account that first used this origin — it presents the origin id unchanged
    val accountId = integer("account_id")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(origin)
}

object SessionMap : Table("session_map") {
    val origin = varchar("origin", 64)
    val accountId = integer("account_id")
    // session-id this account presents upstream: == origin for the owner, a fresh uuid otherwise
    val replaced = varchar("replaced", 64)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(origin, accountId)
}

/**
 * A conversation in the built-in chat UI. Owned by exactly one user — chats are private, there
 * is no sharing model. "Temporary" chats never reach this table at all: the datapath keeps their
 * history in the request body, so nothing about them is persisted anywhere.
 */
object Chats : Table("chats") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val title = varchar("title", 300)
    // model used for the next turn; each message also records the model that actually answered
    val model = varchar("model", 128)
    // per-chat custom instructions, prepended after the user's global ones
    val systemPrompt = text("system_prompt").nullable()
    val pinned = bool("pinned").default(false)
    val archived = bool("archived").default(false)
    // whether this chat reads and writes the user's memory
    val useMemory = bool("use_memory").default(true)
    // set once an auto-generated title has replaced the first-message stub, so a renamed chat
    // is never re-titled behind the user's back
    val titleLocked = bool("title_locked").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
    init { index(false, userId, updatedAt) }
}

/** One turn in a chat. Assistant rows carry the token/cost accounting of the turn that produced them. */
object ChatMessages : Table("chat_messages") {
    val id = long("id").autoIncrement()
    val chatId = integer("chat_id").references(Chats.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val role = varchar("role", 16)              // "user" | "assistant"
    val content = text("content")
    // extended-thinking text, kept apart from the answer so the UI can collapse it and the
    // upstream request can leave it out of the replayed history
    val thinking = text("thinking").nullable()
    val model = varchar("model", 128).nullable()
    val inputTokens = long("input_tokens").default(0)
    val outputTokens = long("output_tokens").default(0)
    val cacheReadTokens = long("cache_read_tokens").default(0)
    val cacheWriteTokens = long("cache_write_tokens").default(0)
    val cost = double("cost").default(0.0)
    // set when the turn failed; the row is kept so the thread shows what happened
    val error = text("error").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
    init { index(false, chatId, createdAt) }
}

/**
 * A file attached to a chat message. Bytes live in the DB rather than on disk: the deploy has
 * exactly one persistent volume (pgdata) and attachments must survive a redeploy like the rest
 * of the chat. Uploads are bounded (see ChatRoutes), so rows stay small.
 */
object ChatAttachments : Table("chat_attachments") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    // null until the attachment is sent with a message (uploads are staged first)
    val messageId = long("message_id").nullable()
    val name = varchar("name", 255)
    val mimeType = varchar("mime_type", 128)
    val size = long("size")
    // "image" | "document" | "text" — decides which Anthropic content block it becomes
    val kind = varchar("kind", 16)
    val data = binary("data")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
    init { index(false, userId, messageId) }
}

/**
 * A durable fact about a user, injected into every chat that opts into memory. Written either by
 * hand or by the extractor that runs after a turn (see ChatMemory). Disabled entries stay
 * visible in the UI but are not injected.
 */
object ChatMemories : Table("chat_memories") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val content = text("content")
    // named sourceCol because `source` collides with a ColumnSet member
    val sourceCol = varchar("source", 16).default("auto")   // "auto" | "manual"
    // chat it was learned in; kept for provenance, cleared when that chat is deleted
    val chatId = integer("chat_id").references(Chats.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.SET_NULL).nullable()
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
    init { index(false, userId) }
}

/** Per-user chat preferences (one row per user, created on first save). */
object ChatSettings : Table("chat_settings") {
    val userId = integer("user_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val memoryEnabled = bool("memory_enabled").default(true)
    val defaultModel = varchar("default_model", 128).nullable()
    // "what should Claude know about you" / "how should Claude respond" — the two halves of the
    // familiar custom-instructions pair, injected as one system block
    val aboutYou = text("about_you").nullable()
    val responseStyle = text("response_style").nullable()
    override val primaryKey = PrimaryKey(userId)
}

object OAuthAddSessions : Table("oauth_add_sessions") {
    val id = varchar("id", 64)          // state
    val pkceVerifier = varchar("pkce_verifier", 256)
    val createdBy = integer("created_by").references(Users.id).nullable()
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(id)
}

val ALL_TABLES = arrayOf(
    Users, UserIdentities, Settings, ModelPrices, Roles, RolePermissions, UserRoles, ProxyTokens, RoutingTokens,
    AccountGroups, UserGroupAccess,
    Accounts, AccountSecrets, AccountLimits, UsageEvents, McpToolCalls, WindowSnapshots,
    OAuthAddSessions, SessionOwners, SessionMap,
    Chats, ChatMessages, ChatAttachments, ChatMemories, ChatSettings,
)
