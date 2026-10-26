package org.claudeproxy.catalog

import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.claudeproxy.envOrProp
import org.claudeproxy.proxy.Http
import org.claudeproxy.repo.ModelPriceDto
import org.claudeproxy.repo.ModelPriceRepo
import org.claudeproxy.repo.samePrices
import org.claudeproxy.repo.SettingsRepo
import org.slf4j.LoggerFactory
import kotlin.math.roundToLong

/**
 * Keeps the base price layer current from a public price list — LiteLLM's
 * `model_prices_and_context_window.json` by default, which tracks Anthropic's pricing page
 * (per-token USD, both cache-write tiers, the fast-mode multiplier, the web-search fee).
 * Anthropic itself publishes no machine-readable prices.
 *
 * Only ever writes the base layer: operator overrides win regardless, so a bad upstream row is
 * fixed by overriding it, and a broken or unreachable list just leaves yesterday's prices in
 * place. `PRICING_SYNC_URL=off` disables it; `PRICING_SYNC_HOURS` sets the interval (default 24).
 */
object PriceSync {
    private val log = LoggerFactory.getLogger("PriceSync")
    private val json = Json { ignoreUnknownKeys = true }

    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"

    private const val KEY_AT = "pricing_sync:at"
    private const val KEY_RESULT = "pricing_sync:result"

    // The ids Anthropic's API accepts; everything else in the file is another provider's alias.
    private val ID = Regex("^claude-[a-z0-9][a-z0-9.-]*$")
    private val DATED = Regex("^(.+)-\\d{8}$")

    /** Fewer rows than this means the file changed shape, not that Anthropic retired its models. */
    private const val MIN_ROWS = 3

    val url: String? get() = (envOrProp("PRICING_SYNC_URL") ?: DEFAULT_URL)
        .takeUnless { it.equals("off", true) || it.equals("false", true) || it.equals("none", true) }

    @Serializable
    data class Status(val enabled: Boolean, val url: String?, val at: String?, val result: String?)

    fun status() = Status(url != null, url, SettingsRepo.get(KEY_AT), SettingsRepo.get(KEY_RESULT))

    fun start(scope: CoroutineScope) {
        if (url == null) {
            log.info("price sync disabled (PRICING_SYNC_URL=off)")
            return
        }
        val every = (envOrProp("PRICING_SYNC_HOURS")?.toDoubleOrNull() ?: 24.0).coerceAtLeast(0.25)
        scope.launch {
            delay(10_000)
            while (isActive) {
                syncNow()
                delay((every * 3_600_000).toLong())
            }
        }
    }

    /** One sync; never throws — the outcome lands in [status]. */
    suspend fun syncNow(): Status {
        val src = url ?: return status()
        val result = runCatching {
            val text = withTimeout(60_000) {
                val response = Http.publicShareClient.get(src)
                check(response.status.value in 200..299) { "HTTP ${response.status.value}" }
                response.readRawBytes().decodeToString()
            }
            val rows = parse(text)
            check(rows.size >= MIN_ROWS) { "only ${rows.size} Claude rows in the list" }
            val changed = ModelPriceRepo.applySynced(rows)
            "ok: ${rows.size} models, $changed updated"
        }.getOrElse { "error: ${it.message ?: it.toString()}" }
        if (result.startsWith("ok")) log.info("price sync {}", result) else log.warn("price sync {}", result)
        SettingsRepo.set(KEY_AT, java.time.Instant.now().toString())
        SettingsRepo.set(KEY_RESULT, result.take(250))
        return status()
    }

    /**
     * The Anthropic rows of a LiteLLM price file, as per-1M prices. A dated id whose undated
     * sibling costs the same is dropped: substring matching already prices it through that row,
     * and keeping both would only double the table.
     */
    internal fun parse(text: String): List<ModelPriceDto> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val rows = root.mapNotNull { (key, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            if (o.str("litellm_provider") != "anthropic") return@mapNotNull null
            val id = key.lowercase()
            if (id.length > 64 || !ID.matches(id)) return@mapNotNull null
            val input = o.perM("input_cost_per_token") ?: return@mapNotNull null
            val output = o.perM("output_cost_per_token") ?: return@mapNotNull null
            // A zero or absurd price is a broken row, not a free model.
            if (input <= 0.0 || output <= 0.0 || input > 1000.0 || output > 1000.0) return@mapNotNull null
            ModelPriceDto(
                pattern = id,
                inputPrice = input,
                outputPrice = output,
                cacheReadPrice = o.perM("cache_read_input_token_cost") ?: round(input * 0.1),
                cacheWritePrice = o.perM("cache_creation_input_token_cost") ?: round(input * 1.25),
                cacheWrite1hPrice = o.perM("cache_creation_input_token_cost_above_1hr") ?: round(input * 2.0),
                fastMultiplier = (o["provider_specific_entry"] as? JsonObject)?.num("fast")?.takeIf { it > 0 } ?: 2.0,
                webSearchPrice = (o["search_context_cost_per_query"] as? JsonObject)?.num("search_context_size_medium") ?: 0.01,
                origin = ModelPriceRepo.ORIGIN_SYNCED,
            )
        }
        val byId = rows.associateBy { it.pattern }
        return rows.filter { r ->
            val undated = DATED.matchEntire(r.pattern)?.groupValues?.get(1) ?: return@filter true
            byId[undated]?.samePrices(r) != true
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.perM(k: String) = num(k)?.let { round(it * 1_000_000.0) }

    // 4e-06 × 1e6 is 4.000000000000001 in binary; prices are quoted to well under a micro-dollar.
    private fun round(x: Double) = (x * 1_000_000.0).roundToLong() / 1_000_000.0
}
