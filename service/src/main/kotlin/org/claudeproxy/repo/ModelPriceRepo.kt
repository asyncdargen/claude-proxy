package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.ModelPriceBase
import org.claudeproxy.db.ModelPrices
import org.claudeproxy.db.Settings
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

@Serializable
data class ModelPriceDto(
    val pattern: String,
    val inputPrice: Double,
    val outputPrice: Double,
    val cacheReadPrice: Double,
    // 5-minute-TTL cache writes (Anthropic's default tier)
    val cacheWritePrice: Double,
    // 1-hour-TTL cache writes — a higher tier (2× input vs the 5m tier's 1.25×)
    val cacheWrite1hPrice: Double = 0.0,
    // × applied to every token price when the response was served in fast mode
    val fastMultiplier: Double = 2.0,
    // USD per server-side web-search invocation (billed per request, not per token)
    val webSearchPrice: Double = 0.01,
    // where the effective row comes from: "builtin" (curated seed), "synced" (public price
    // list), "override" (an operator edit, which wins over both and survives every sync)
    val origin: String = ModelPriceRepo.ORIGIN_OVERRIDE,
    // an override that shadows a base row — deleting it resets to that row instead of to $0
    val hasBase: Boolean = false,
)

/**
 * Everything about one response that costs money. A single value object rather than a positional
 * argument list, because it is priced from two places (usage recording and the daily-limit
 * counter) and those must never drift apart.
 */
data class BilledUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    /** cache writes at the default 5-minute TTL */
    val cacheWrite5m: Long = 0,
    /** cache writes at the 1-hour TTL, priced separately */
    val cacheWrite1h: Long = 0,
    /** server-side web searches, billed per invocation */
    val webSearchRequests: Long = 0,
    /** response was served in fast mode (premium tier on the same model) */
    val fast: Boolean = false,
) {
    /** Nothing was consumed — the attempt cost nothing and carries no data point. */
    fun isEmpty(): Boolean =
        input == 0L && output == 0L && cacheRead == 0L &&
            cacheWrite5m == 0L && cacheWrite1h == 0L && webSearchRequests == 0L
}

/**
 * Per-model pricing in USD per 1M tokens, split by token kind (input, output, cache read,
 * cache write at each TTL), plus the two charges that are not per-token: the fast-mode
 * multiplier and the per-invocation web-search price. A request's model id is matched against
 * each pattern by case-insensitive substring; the longest matching pattern wins.
 *
 * Two layers, merged per pattern: the base ([ModelPriceBase] — the curated seed below, then
 * whatever PriceSync pulls from the public list) and operator overrides ([ModelPrices]). An
 * override always wins and is never written by a sync, so a hand-set price sticks until it is
 * reset; a model that ships tomorrow gets priced by the next sync without anyone touching it.
 */
object ModelPriceRepo {
    const val ORIGIN_BUILTIN = "builtin"
    const val ORIGIN_SYNCED = "synced"
    const val ORIGIN_OVERRIDE = "override"

    @Volatile
    private var cache: List<ModelPriceDto>? = null

    fun seedDefaults() {
        transaction {
            val now = System.currentTimeMillis()
            // pattern to (input, output, cacheRead, cacheWrite5m, cacheWrite1h) in USD per 1M
            // tokens, from Anthropic's list prices (platform.claude.com/docs/en/about-claude/pricing).
            // cacheWrite5m is the "5m Cache Writes" column (= 1.25× input), cacheWrite1h the
            // "1h Cache Writes" one (= 2× input); cacheRead the "Cache Hits & Refreshes" column
            // (= 0.1× input; the 5.1 Fable/Mythos and Opus 5.5 generation reads cheaper, see their
            // rows). Version-specific full-id patterns come first so the longest-match
            // rule (see list()) prices each version correctly — e.g. Opus 4.1 ($15) is
            // distinguished from Opus 4.8 ($5), and Fable 5 no longer falls through to $0. The
            // bare haiku/sonnet/opus rows stay as catch-all fallbacks for any unrecognised
            // future id. fastMultiplier and webSearchPrice take their column defaults (×2 and
            // $0.01/search), which hold across every current model.
            //
            // These are base rows: rewritten on every start so a corrected seed reaches existing
            // installs, but never over a row a sync has since taken over (that one is fresher).
            val seed = listOf(
                // Fable / Mythos. 5.1 reads its cache at 0.025× input ($0.25) against 5's 0.1×;
                // without its own row it would match `claude-fable-5` and pay 4× for every hit.
                Row("claude-fable-5-1", 10.0, 50.0, 0.25, 12.5, 20.0),
                Row("claude-mythos-5-1", 10.0, 50.0, 0.25, 12.5, 20.0),
                Row("claude-fable-5", 10.0, 50.0, 1.0, 12.5, 20.0),
                Row("claude-mythos-5", 10.0, 50.0, 1.0, 12.5, 20.0),
                // Opus 5.5 — cheaper than Opus 5, and reads its cache at 0.05× input ($0.20).
                // Needs its own row: `claude-opus-5` is a substring of its id.
                Row("claude-opus-5-5", 4.0, 20.0, 0.2, 5.0, 8.0),
                // Opus 5. Priced identically to Opus 4.8, but spelled out
                // rather than left to the bare `opus` fallback: the fallback is only correct by
                // coincidence, and a future edit to it would silently misprice the flagship.
                Row("claude-opus-5", 5.0, 25.0, 0.5, 6.25, 10.0),
                // Opus 4.x current ($5 tier)
                Row("claude-opus-4-8", 5.0, 25.0, 0.5, 6.25, 10.0),
                Row("claude-opus-4-7", 5.0, 25.0, 0.5, 6.25, 10.0),
                Row("claude-opus-4-6", 5.0, 25.0, 0.5, 6.25, 10.0),
                Row("claude-opus-4-5", 5.0, 25.0, 0.5, 6.25, 10.0),
                // Opus 4.1 / 4.0 (deprecated, $15 tier). 4.0 is listed explicitly because the
                // bare `opus` fallback prices it at the $5 tier — a silent 3× under-charge on
                // any traffic that still names it.
                Row("claude-opus-4-1", 15.0, 75.0, 1.5, 18.75, 30.0),
                Row("claude-opus-4-0", 15.0, 75.0, 1.5, 18.75, 30.0),
                // Sonnet 5.5 reads its cache at 0.05× input ($0.10), half Sonnet 5's rate; it
                // needs its own row for the same substring reason as Opus 5.5.
                Row("claude-sonnet-5-5", 2.0, 10.0, 0.1, 2.5, 4.0),
                // Sonnet 5: launched as introductory pricing, made the standard price instead of
                // the increase to $3/$15 once scheduled for 2026-09-01.
                Row("claude-sonnet-5", 2.0, 10.0, 0.2, 2.5, 4.0),
                // Sonnet 4.x ($3 tier)
                Row("claude-sonnet-4-6", 3.0, 15.0, 0.3, 3.75, 6.0),
                Row("claude-sonnet-4-5", 3.0, 15.0, 0.3, 3.75, 6.0),
                // Haiku
                Row("claude-haiku-4-5", 1.0, 5.0, 0.1, 1.25, 2.0),
                Row("claude-3-5-haiku", 0.8, 4.0, 0.08, 1.0, 1.6),
                // Generic fallbacks (shortest patterns → matched only when no version row does).
                Row("haiku", 1.0, 5.0, 0.1, 1.25, 2.0),
                Row("sonnet", 3.0, 15.0, 0.3, 3.75, 6.0),
                Row("opus", 5.0, 25.0, 0.5, 6.25, 10.0),
                Row("fable", 10.0, 50.0, 1.0, 12.5, 20.0),
            )
            val synced = ModelPriceBase.selectAll().where { ModelPriceBase.origin eq ORIGIN_SYNCED }
                .map { it[ModelPriceBase.pattern] }.toSet()
            seed.filter { it.pattern !in synced }.forEach { p ->
                ModelPriceBase.upsert {
                    it[pattern] = p.pattern
                    it[inputPrice] = p.input
                    it[outputPrice] = p.output
                    it[cacheReadPrice] = p.cacheRead
                    it[cacheWritePrice] = p.cacheWrite5m
                    it[cacheWrite1hPrice] = p.cacheWrite1h
                    it[fastMultiplier] = 2.0
                    it[webSearchPrice] = 0.01
                    it[origin] = ORIGIN_BUILTIN
                    it[updatedAt] = now
                }
            }
            // A builtin row the seed no longer carries was retired on purpose.
            val seeded = seed.map { it.pattern }.toSet()
            ModelPriceBase.selectAll().where { ModelPriceBase.origin eq ORIGIN_BUILTIN }
                .map { it[ModelPriceBase.pattern] }.filter { it !in seeded }
                .forEach { p -> ModelPriceBase.deleteWhere { pattern eq p } }
            migrateToLayers()
            backfill1hCacheWritePrice()
        }
        invalidate()
    }

    /**
     * One-time split of the old single table. Before the base layer existed, [ModelPrices] held
     * the seed and the operator's edits mixed together, indistinguishably. A row still equal to
     * its base row is the seed and leaves the override table — otherwise it would pin the old
     * price forever and no sync could ever correct it. Any row that differs is, by definition,
     * someone's edit and stays an override, so the effective price of every pattern is exactly
     * what it was before the upgrade.
     */
    private fun migrateToLayers() {
        val markerKey = "migrated:price_layers"
        if (Settings.selectAll().where { Settings.key eq markerKey }.any()) return
        val base = ModelPriceBase.selectAll().associateBy { it[ModelPriceBase.pattern] }
        ModelPrices.selectAll().toList().forEach { row ->
            val p = row[ModelPrices.pattern]
            val b = base[p] ?: return@forEach
            if (overrideDto(row, false).samePrices(baseDto(b))) ModelPrices.deleteWhere { pattern eq p }
        }
        Settings.insert {
            it[key] = markerKey
            it[value] = java.time.Instant.now().toString()
        }
    }

    /**
     * Give every override a 1h cache-write price. The column arrives on existing databases with a 0.0
     * default, and 0.0 means *free* — a silent under-charge worse than the 5m-rate approximation
     * it replaces. Anything still unpriced (rows seeded before this column existed, plus any
     * operator-added pattern) is set to Anthropic's 2× input relation; a row already carrying a
     * price is never touched, so UI edits stick.
     */
    private fun backfill1hCacheWritePrice() {
        ModelPrices.selectAll().filter { it[ModelPrices.cacheWrite1hPrice] <= 0.0 && it[ModelPrices.inputPrice] > 0.0 }
            .forEach { row ->
                val p = row[ModelPrices.pattern]
                val derived = row[ModelPrices.inputPrice] * 2.0
                ModelPrices.update({ ModelPrices.pattern eq p }) { it[cacheWrite1hPrice] = derived }
            }
    }

    fun list(): List<ModelPriceDto> {
        cache?.let { return it }
        val loaded = transaction {
            val base = ModelPriceBase.selectAll().associate { it[ModelPriceBase.pattern] to baseDto(it) }
            val overrides = ModelPrices.selectAll().associate {
                it[ModelPrices.pattern] to overrideDto(it, it[ModelPrices.pattern] in base)
            }
            (base + overrides).values.toList()
        }.sortedWith(compareByDescending<ModelPriceDto> { it.pattern.length }.thenBy { it.pattern })
        cache = loaded
        return loaded
    }

    /**
     * Write rows pulled from the public price list into the base layer. Builtin rows for the same
     * pattern are replaced (the list is the fresher source); overrides are untouched by
     * construction, they live in the other table. Returns how many rows actually changed.
     */
    fun applySynced(rows: List<ModelPriceDto>): Int {
        if (rows.isEmpty()) return 0
        var changed = 0
        transaction {
            val now = System.currentTimeMillis()
            val current = ModelPriceBase.selectAll().associateBy { it[ModelPriceBase.pattern] }
            rows.forEach { r ->
                val p = r.pattern.trim().lowercase()
                val old = current[p]
                if (old != null && old[ModelPriceBase.origin] == ORIGIN_SYNCED && baseDto(old).samePrices(r)) return@forEach
                ModelPriceBase.upsert {
                    it[pattern] = p
                    it[inputPrice] = r.inputPrice
                    it[outputPrice] = r.outputPrice
                    it[cacheReadPrice] = r.cacheReadPrice
                    it[cacheWritePrice] = r.cacheWritePrice
                    it[cacheWrite1hPrice] = if (r.cacheWrite1hPrice > 0.0) r.cacheWrite1hPrice else r.inputPrice * 2.0
                    it[fastMultiplier] = if (r.fastMultiplier > 0.0) r.fastMultiplier else 1.0
                    it[webSearchPrice] = r.webSearchPrice.coerceAtLeast(0.0)
                    it[origin] = ORIGIN_SYNCED
                    it[updatedAt] = now
                }
                changed++
            }
        }
        invalidate()
        return changed
    }

    fun set(
        pattern: String, input: Double, output: Double, cacheRead: Double, cacheWrite: Double,
        cacheWrite1h: Double, fastMultiplier: Double, webSearchPrice: Double,
    ) {
        transaction {
            ModelPrices.upsert {
                it[ModelPrices.pattern] = pattern.trim().lowercase()
                it[inputPrice] = input
                it[outputPrice] = output
                it[cacheReadPrice] = cacheRead
                it[cacheWritePrice] = cacheWrite
                // A 0 here would silently make 1h cache writes free; fall back to the 2× relation.
                it[cacheWrite1hPrice] = if (cacheWrite1h > 0.0) cacheWrite1h else input * 2.0
                // A 0 multiplier would make fast-mode responses free; 1.0 is the "no premium" value.
                it[ModelPrices.fastMultiplier] = if (fastMultiplier > 0.0) fastMultiplier else 1.0
                it[ModelPrices.webSearchPrice] = webSearchPrice.coerceAtLeast(0.0)
            }
        }
        invalidate()
    }

    /** Drops an override; a pattern that also has a base row falls back to it. */
    fun delete(pattern: String) {
        transaction { ModelPrices.deleteWhere { ModelPrices.pattern eq pattern } }
        invalidate()
    }

    /** The pattern that prices [model] (longest substring match), or null when nothing does. */
    fun matchFor(model: String?): ModelPriceDto? = priceFor(model)

    private fun priceFor(model: String?): ModelPriceDto? {
        if (model.isNullOrBlank()) return null
        val m = model.lowercase()
        return list().firstOrNull { m.contains(it.pattern) }
    }

    /**
     * USD cost of one response. Each token kind is priced separately (per 1M), cache writes at
     * the rate of their own TTL; fast mode scales the whole token bill by the model's premium
     * multiplier (Anthropic prices fast mode as the same model on a higher tier, so it applies
     * uniformly rather than to output alone); server-side web searches are added per invocation.
     *
     * This is the single place a response turns into money — [UsageRepo.record] stores what it
     * returns and the per-user daily limit counts the same number.
     */
    fun costOf(model: String?, u: BilledUsage): Double {
        val p = priceFor(model) ?: return 0.0
        val tokens = (
            u.input * p.inputPrice +
                u.output * p.outputPrice +
                u.cacheRead * p.cacheReadPrice +
                u.cacheWrite5m * p.cacheWritePrice +
                u.cacheWrite1h * p.cacheWrite1hPrice
            ) / 1_000_000.0
        val multiplier = if (u.fast) p.fastMultiplier else 1.0
        return tokens * multiplier + u.webSearchRequests * p.webSearchPrice
    }

    private fun invalidate() { cache = null }

    private fun baseDto(it: ResultRow) = ModelPriceDto(
        it[ModelPriceBase.pattern], it[ModelPriceBase.inputPrice], it[ModelPriceBase.outputPrice],
        it[ModelPriceBase.cacheReadPrice], it[ModelPriceBase.cacheWritePrice],
        it[ModelPriceBase.cacheWrite1hPrice], it[ModelPriceBase.fastMultiplier],
        it[ModelPriceBase.webSearchPrice], it[ModelPriceBase.origin], false,
    )

    private fun overrideDto(it: ResultRow, hasBase: Boolean) = ModelPriceDto(
        it[ModelPrices.pattern], it[ModelPrices.inputPrice], it[ModelPrices.outputPrice],
        it[ModelPrices.cacheReadPrice], it[ModelPrices.cacheWritePrice],
        it[ModelPrices.cacheWrite1hPrice], it[ModelPrices.fastMultiplier],
        it[ModelPrices.webSearchPrice], ORIGIN_OVERRIDE, hasBase,
    )

    private data class Row(
        val pattern: String,
        val input: Double,
        val output: Double,
        val cacheRead: Double,
        val cacheWrite5m: Double,
        val cacheWrite1h: Double,
    )
}

/** Same money, whatever the origin — float noise below a micro-dollar per 1M is not a price. */
internal fun ModelPriceDto.samePrices(o: ModelPriceDto): Boolean {
    fun eq(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6
    return eq(inputPrice, o.inputPrice) && eq(outputPrice, o.outputPrice) &&
        eq(cacheReadPrice, o.cacheReadPrice) && eq(cacheWritePrice, o.cacheWritePrice) &&
        eq(cacheWrite1hPrice, o.cacheWrite1hPrice) && eq(fastMultiplier, o.fastMultiplier) &&
        eq(webSearchPrice, o.webSearchPrice)
}
