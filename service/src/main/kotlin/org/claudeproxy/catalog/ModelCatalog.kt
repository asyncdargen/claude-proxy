package org.claudeproxy.catalog

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.UpstreamAuth
import org.claudeproxy.db.ModelOverrides
import org.claudeproxy.db.ModelsLive
import org.claudeproxy.envOrProp
import org.claudeproxy.model.AccountHealth
import org.claudeproxy.proxy.Http
import org.claudeproxy.repo.ModelPriceRepo
import org.claudeproxy.repo.SettingsRepo
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory

@Serializable
data class CatalogModelDto(
    val id: String,
    val displayName: String,
    val description: String? = null,
    // "live" (Anthropic's /v1/models listed it), "builtin" (no listing yet, curated fallback),
    // "manual" (added by an operator, upstream does not list it)
    val origin: String,
    val hidden: Boolean = false,
    // an operator override row exists for this id (rename / hide / manual add)
    val overridden: Boolean = false,
    val createdAt: String? = null,
    // the pricing pattern that bills this id, and where that price comes from; null = costs $0
    val pricePattern: String? = null,
    val priceOrigin: String? = null,
    // the override's own fields (null = inherited), so an editor can change one without
    // freezing the others at today's upstream value
    val customName: String? = null,
    val customDescription: String? = null,
)

@Serializable
data class CatalogStatusDto(
    val models: List<CatalogModelDto>,
    val refreshedAt: String? = null,
    val refreshResult: String? = null,
)

/**
 * The one list of models the proxy advertises — the routing gateways' `/v1/models` (via the
 * private `/internal/models`) and the chat picker both read it, so a model is offered the day
 * Anthropic ships it instead of after someone edits a hardcoded list.
 *
 * Built from Anthropic's own `/v1/models` (fetched through the pool — a free path, no quota),
 * persisted in [ModelsLive] so a restart without a healthy account still has it, and layered
 * under operator overrides ([ModelOverrides]): rename, hide, or add an id upstream does not list.
 * Until the first successful listing the curated [BUILTIN] list stands in.
 */
object ModelCatalog {
    private val log = LoggerFactory.getLogger("ModelCatalog")
    private val json = Json { ignoreUnknownKeys = true }

    private const val KEY_AT = "models_refresh:at"
    private const val KEY_RESULT = "models_refresh:result"

    private data class Builtin(val id: String, val name: String, val note: String? = null)

    /** Fallback list and the source of the picker's capability notes, newest/best first. */
    private val BUILTIN = listOf(
        Builtin("claude-opus-5-5", "Claude Opus 5.5", "Most capable — hard reasoning, long work"),
        Builtin("claude-opus-5", "Claude Opus 5", "Previous flagship"),
        Builtin("claude-sonnet-5-5", "Claude Sonnet 5.5", "Balanced default for everyday chat"),
        Builtin("claude-sonnet-5", "Claude Sonnet 5", "Balanced, previous generation"),
        Builtin("claude-fable-5-1", "Claude Fable 5.1", "Creative writing and long-form prose"),
        Builtin("claude-fable-5", "Claude Fable 5", "Creative writing, previous generation"),
        Builtin("claude-opus-4-8", "Claude Opus 4.8"),
        Builtin("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        Builtin("claude-haiku-4-5-20251001", "Claude Haiku 4.5", "Fastest and cheapest"),
    )
    private val NOTES = BUILTIN.associate { it.id to it.note }

    @Volatile private var cache: List<CatalogModelDto>? = null

    /** Every model, hidden ones included — the admin view. */
    fun all(): List<CatalogModelDto> {
        // Price matching is done per call, not cached with the list: prices change underneath it
        // (sync, edits) and ModelPriceRepo keeps its own cache anyway.
        return listed().map { m ->
            val price = ModelPriceRepo.matchFor(m.id)
            m.copy(pricePattern = price?.pattern, priceOrigin = price?.origin)
        }
    }

    private fun listed(): List<CatalogModelDto> {
        cache?.let { return it }
        val built = transaction {
            val live = ModelsLive.selectAll().orderBy(ModelsLive.position).map {
                Triple(it[ModelsLive.id], it[ModelsLive.displayName], it[ModelsLive.createdAt])
            }
            val overrides = ModelOverrides.selectAll().associateBy { it[ModelOverrides.id] }
            val baseOrigin = if (live.isEmpty()) "builtin" else "live"
            val base = live.ifEmpty { BUILTIN.map { Triple(it.id, it.name, null) } }
            val baseIds = base.map { it.first }.toSet()
            val listed = base.map { (id, name, created) ->
                val o = overrides[id]
                CatalogModelDto(
                    id = id,
                    displayName = o?.get(ModelOverrides.displayName) ?: name ?: prettify(id),
                    description = o?.get(ModelOverrides.description) ?: noteFor(id),
                    origin = baseOrigin,
                    hidden = o?.get(ModelOverrides.hidden) ?: false,
                    overridden = o != null,
                    createdAt = created,
                    customName = o?.get(ModelOverrides.displayName),
                    customDescription = o?.get(ModelOverrides.description),
                )
            }
            val manual = overrides.values.filter { it[ModelOverrides.id] !in baseIds }.map { o ->
                val id = o[ModelOverrides.id]
                CatalogModelDto(
                    id = id,
                    displayName = o[ModelOverrides.displayName] ?: prettify(id),
                    description = o[ModelOverrides.description] ?: noteFor(id),
                    origin = "manual",
                    hidden = o[ModelOverrides.hidden],
                    overridden = true,
                    customName = o[ModelOverrides.displayName],
                    customDescription = o[ModelOverrides.description],
                )
            }.sortedBy { it.id }
            // Hand-added models go first: someone added them because they want them offered.
            manual + listed
        }
        cache = built
        return built
    }

    /** What clients get offered. */
    fun visible(): List<CatalogModelDto> = all().filterNot { it.hidden }

    fun status() = CatalogStatusDto(all(), SettingsRepo.get(KEY_AT), SettingsRepo.get(KEY_RESULT))

    /** Same rule the chat applies to a client-sent model id. */
    fun isValidId(id: String): Boolean =
        id.isNotBlank() && id.length <= 128 && id.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }

    fun setOverride(id: String, displayName: String?, description: String?, hidden: Boolean) {
        transaction {
            ModelOverrides.upsert {
                it[ModelOverrides.id] = id
                it[ModelOverrides.displayName] = displayName?.trim()?.ifBlank { null }?.take(128)
                it[ModelOverrides.description] = description?.trim()?.ifBlank { null }?.take(256)
                it[ModelOverrides.hidden] = hidden
            }
        }
        invalidate()
    }

    fun deleteOverride(id: String) {
        transaction { ModelOverrides.deleteWhere { ModelOverrides.id eq id } }
        invalidate()
    }

    private fun invalidate() { cache = null }

    fun start(scope: CoroutineScope, pool: AccountPool, upstreamBaseUrl: String) {
        val every = (envOrProp("MODELS_REFRESH_HOURS")?.toDoubleOrNull() ?: 6.0).coerceAtLeast(0.25)
        scope.launch {
            delay(15_000)
            while (isActive) {
                refresh(pool, upstreamBaseUrl)
                delay((every * 3_600_000).toLong())
            }
        }
    }

    /**
     * Re-read Anthropic's listing. An empty or failed fetch keeps the stored list — a transient
     * upstream error must never empty every model dropdown at once.
     */
    suspend fun refresh(pool: AccountPool, upstreamBaseUrl: String): CatalogStatusDto {
        val result = runCatching {
            val models = fetch(pool, upstreamBaseUrl)
            check(models.isNotEmpty()) { "no models listed (no healthy account?)" }
            replaceLive(models)
            "ok: ${models.size} models"
        }.getOrElse { "error: ${it.message ?: it.toString()}" }
        if (result.startsWith("ok")) log.info("model listing {}", result) else log.warn("model listing {}", result)
        SettingsRepo.set(KEY_AT, java.time.Instant.now().toString())
        SettingsRepo.set(KEY_RESULT, result.take(250))
        return status()
    }

    internal data class Listed(val id: String, val displayName: String?, val createdAt: String?)

    internal fun replaceLive(models: List<Listed>) {
        transaction {
            ModelsLive.deleteAll()
            ModelsLive.batchInsert(models.withIndex()) { (i, m) ->
                this[ModelsLive.id] = m.id
                this[ModelsLive.displayName] = m.displayName?.take(128)
                this[ModelsLive.createdAt] = m.createdAt?.take(40)
                this[ModelsLive.position] = i
            }
        }
        invalidate()
    }

    private suspend fun fetch(pool: AccountPool, upstreamBaseUrl: String): List<Listed> {
        // Any healthy account will do — listing consumes no subscription quota. Global accounts
        // first: a personal account should not be what the whole proxy's catalogue depends on.
        val account = (pool.snapshotGlobal() + pool.snapshot()).firstOrNull {
            it.enabled && it.health == AccountHealth.OK
        } ?: return emptyList()
        val out = mutableListOf<Listed>()
        var after: String? = null
        // Paged by after_id; a handful of pages is far more than Anthropic lists.
        repeat(5) {
            val page = Http.client.prepareGet(
                "$upstreamBaseUrl/v1/models?limit=1000" + (after?.let { "&after_id=$it" } ?: ""),
            ) {
                header("anthropic-version", "2023-06-01")
                UpstreamAuth.apply(this, account.type, account.secret)
            }.execute { response ->
                check(response.status.value in 200..299) { "HTTP ${response.status.value} from /v1/models" }
                json.parseToJsonElement(response.readRawBytes().decodeToString()) as? JsonObject
            } ?: return out
            val items = parseListing(page)
            out += items.filter { m -> out.none { it.id == m.id } }
            val more = (page["has_more"] as? JsonPrimitive)?.booleanOrNull == true
            after = (page["last_id"] as? JsonPrimitive)?.contentOrNull
            if (!more || after == null || items.isEmpty()) return out
        }
        return out
    }

    internal fun parseListing(page: JsonObject): List<Listed> =
        (page["data"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { isValidId(it) } ?: return@mapNotNull null
            Listed(
                id,
                (o["display_name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null },
                (o["created_at"] as? JsonPrimitive)?.contentOrNull,
            )
        }

    /** A note for a dated id too: "claude-haiku-4-5-20251001" and "claude-opus-5-5" both have one. */
    private fun noteFor(id: String): String? =
        NOTES[id] ?: NOTES.entries.firstOrNull { (k, _) -> id.startsWith("$k-") && id.removePrefix("$k-").all(Char::isDigit) }?.value

    /** "claude-sonnet-4-5-20250929" → "Sonnet 4 5 20250929" — a readable stand-in for a new id. */
    internal fun prettify(id: String): String =
        id.removePrefix("claude-").split('-').joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
}
