package org.claudeproxy.repo

import org.claudeproxy.Config
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.db.ModelPriceBase
import org.claudeproxy.db.ModelPrices
import org.claudeproxy.db.Settings
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.claudeproxy.accounts.Secrets
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Money. Every charge Anthropic applies must be reproduced here, because the number
 * [ModelPriceRepo.costOf] returns is both what the stats show and what the per-user daily limit
 * spends against.
 */
class ModelPricingTest {
    private lateinit var dbFile: File

    private fun config(dbPath: String) = Config(
        bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbPath,
        masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
        adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
        publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
        internalToken = null,
    )

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("pricing-test", ".db")
        val cfg = config(dbFile.absolutePath)
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
    }

    private fun assertMoney(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected \$$expected, got \$$actual")

    /** Opus 5: input 5, output 25, cache read 0.5, 5m write 6.25, 1h write 10 — per 1M. */
    @Test
    fun `cache writes are priced by their TTL`() {
        val fiveMinute = ModelPriceRepo.costOf(
            "claude-opus-5", BilledUsage(cacheWrite5m = 1_000_000),
        )
        val oneHour = ModelPriceRepo.costOf(
            "claude-opus-5", BilledUsage(cacheWrite1h = 1_000_000),
        )
        assertMoney(6.25, fiveMinute)
        assertMoney(10.0, oneHour)
        // Claude Code writes its main-loop prefix with ttl:"1h": pricing that at the 5m rate is
        // the ~60% under-count this split exists to fix.
        assertTrue(oneHour > fiveMinute)
    }

    @Test
    fun `a full response sums every token kind`() {
        val cost = ModelPriceRepo.costOf(
            "claude-opus-5",
            BilledUsage(
                input = 1_000_000, output = 1_000_000, cacheRead = 1_000_000,
                cacheWrite5m = 1_000_000, cacheWrite1h = 1_000_000,
            ),
        )
        assertMoney(5.0 + 25.0 + 0.5 + 6.25 + 10.0, cost)
    }

    @Test
    fun `fast mode applies the premium multiplier`() {
        val standard = ModelPriceRepo.costOf("claude-opus-5", BilledUsage(input = 1_000_000, output = 1_000_000))
        val fast = ModelPriceRepo.costOf("claude-opus-5", BilledUsage(input = 1_000_000, output = 1_000_000, fast = true))
        assertMoney(30.0, standard)
        // Opus 5 fast is $10/$50 against the standard $5/$25 — exactly 2×.
        assertMoney(60.0, fast)
    }

    @Test
    fun `web searches are billed per invocation on top of tokens`() {
        val cost = ModelPriceRepo.costOf("claude-opus-5", BilledUsage(input = 1_000_000, webSearchRequests = 7))
        assertMoney(5.0 + 7 * 0.01, cost)
    }

    /** Fast mode is a tier on the same model: the per-invocation charge is not multiplied. */
    @Test
    fun `fast mode does not multiply the web search charge`() {
        val cost = ModelPriceRepo.costOf("claude-opus-5", BilledUsage(webSearchRequests = 10, fast = true))
        assertMoney(0.10, cost)
    }

    @Test
    fun `unknown models still cost nothing`() {
        assertMoney(0.0, ModelPriceRepo.costOf("some-other-vendor-model", BilledUsage(input = 1_000_000)))
        assertMoney(0.0, ModelPriceRepo.costOf(null, BilledUsage(input = 1_000_000)))
    }

    /**
     * The 1h price column lands on existing databases defaulted to 0.0, and 0.0 means *free* —
     * a worse under-charge than the 5m approximation it replaces. Seeding must repair that.
     */
    @Test
    fun `seeding backfills a missing 1h cache-write price`() {
        ModelPriceRepo.set("test-model", 5.0, 25.0, 0.5, 6.25, cacheWrite1h = 7.0, fastMultiplier = 2.0, webSearchPrice = 0.01)
        transaction {
            ModelPrices.update({ ModelPrices.pattern eq "test-model" }) { it[cacheWrite1hPrice] = 0.0 }
        }
        ModelPriceRepo.seedDefaults()
        val row = transaction { ModelPrices.selectAll().first { it[ModelPrices.pattern] == "test-model" } }
        assertEquals(10.0, row[ModelPrices.cacheWrite1hPrice], "2× the \$5 input price")
    }

    /** An operator-set price must survive re-seeding, unlike an unset one. */
    @Test
    fun `seeding does not overwrite an operator-set 1h price`() {
        ModelPriceRepo.set("claude-opus-5", 5.0, 25.0, 0.5, 6.25, cacheWrite1h = 9.0, fastMultiplier = 2.0, webSearchPrice = 0.01)
        ModelPriceRepo.seedDefaults()
        val row = transaction { ModelPrices.selectAll().first { it[ModelPrices.pattern] == "claude-opus-5" } }
        assertEquals(9.0, row[ModelPrices.cacheWrite1hPrice])
    }

    /** A 0 written through the UI must not silently make 1h writes or fast mode free. */
    @Test
    fun `zero prices fall back instead of becoming free`() {
        ModelPriceRepo.set("test-model", 4.0, 20.0, 0.4, 5.0, cacheWrite1h = 0.0, fastMultiplier = 0.0, webSearchPrice = 0.01)
        val row = transaction { ModelPrices.selectAll().first { it[ModelPrices.pattern] == "test-model" } }
        assertEquals(8.0, row[ModelPrices.cacheWrite1hPrice], "derived from 2× input")
        assertEquals(1.0, row[ModelPrices.fastMultiplier], "no premium, not free")
    }

    /** The longest matching pattern wins, so a version row beats the bare family fallback. */
    @Test
    fun `version rows outrank the family fallback`() {
        // Opus 4.1 is the $15 tier; the bare `opus` fallback would price it at $5.
        assertMoney(15.0, ModelPriceRepo.costOf("claude-opus-4-1-20250805", BilledUsage(input = 1_000_000)))
        assertMoney(5.0, ModelPriceRepo.costOf("claude-opus-9-future", BilledUsage(input = 1_000_000)))
    }

    /** Point releases whose id extends their predecessor's must not inherit its price. */
    @Test
    fun `point releases are priced on their own row`() {
        val u = BilledUsage(input = 1_000_000, output = 1_000_000, cacheRead = 1_000_000, cacheWrite5m = 1_000_000, cacheWrite1h = 1_000_000)
        // Opus 5.5: 4 + 20 + 0.20 + 5 + 8, not Opus 5's 5 + 25 + 0.5 + 6.25 + 10
        assertMoney(37.2, ModelPriceRepo.costOf("claude-opus-5-5", u))
        assertMoney(46.75, ModelPriceRepo.costOf("claude-opus-5", u))
        // Fable 5.1 reads its cache at $0.25, Fable 5 at $1
        assertMoney(0.25, ModelPriceRepo.costOf("claude-fable-5-1", BilledUsage(cacheRead = 1_000_000)))
        assertMoney(0.25, ModelPriceRepo.costOf("claude-mythos-5-1", BilledUsage(cacheRead = 1_000_000)))
        assertMoney(1.0, ModelPriceRepo.costOf("claude-fable-5", BilledUsage(cacheRead = 1_000_000)))
    }

    private fun price(pattern: String) = ModelPriceRepo.list().first { it.pattern == pattern }

    /** A synced row replaces the builtin one, but never an operator override. */
    @Test
    fun `sync updates the base layer and leaves overrides alone`() {
        ModelPriceRepo.set("claude-opus-5", 9.0, 45.0, 0.9, 11.25, 18.0, 2.0, 0.01)
        val changed = ModelPriceRepo.applySynced(
            listOf(
                ModelPriceDto("claude-opus-5", 6.0, 30.0, 0.6, 7.5, 12.0),
                ModelPriceDto("claude-sonnet-5", 1.0, 5.0, 0.1, 1.25, 2.0),
                ModelPriceDto("claude-new-7", 3.0, 15.0, 0.3, 3.75, 6.0),
            ),
        )
        assertEquals(3, changed)
        assertEquals("override", price("claude-opus-5").origin)
        assertEquals(9.0, price("claude-opus-5").inputPrice, "the override still wins")
        assertTrue(price("claude-opus-5").hasBase)
        assertEquals("synced", price("claude-sonnet-5").origin)
        assertEquals(1.0, price("claude-sonnet-5").inputPrice)
        assertMoney(3.0, ModelPriceRepo.costOf("claude-new-7-20270101", BilledUsage(input = 1_000_000)))
        // Re-applying identical rows is a no-op.
        assertEquals(0, ModelPriceRepo.applySynced(listOf(ModelPriceDto("claude-sonnet-5", 1.0, 5.0, 0.1, 1.25, 2.0))))
    }

    /** Deleting an override falls back to the base row; a restart's re-seed keeps the synced one. */
    @Test
    fun `resetting an override returns to the base price`() {
        ModelPriceRepo.applySynced(listOf(ModelPriceDto("claude-opus-5", 6.0, 30.0, 0.6, 7.5, 12.0)))
        ModelPriceRepo.set("claude-opus-5", 9.0, 45.0, 0.9, 11.25, 18.0, 2.0, 0.01)
        ModelPriceRepo.delete("claude-opus-5")
        ModelPriceRepo.seedDefaults()
        assertEquals("synced", price("claude-opus-5").origin)
        assertEquals(6.0, price("claude-opus-5").inputPrice, "the seed must not clobber a fresher sync")
    }

    /**
     * Upgrading from the single-table layout: rows equal to the seed are the seed and move out of
     * the way, an edited row stays an override — no pattern's effective price changes.
     */
    @Test
    fun `the layer migration keeps edits and releases seed rows`() {
        transaction {
            Settings.deleteWhere { key eq "migrated:price_layers" }
            ModelPrices.insert {
                it[pattern] = "claude-opus-5"; it[inputPrice] = 5.0; it[outputPrice] = 25.0; it[cacheReadPrice] = 0.5
                it[cacheWritePrice] = 6.25; it[cacheWrite1hPrice] = 10.0
            }
            ModelPrices.insert {
                it[pattern] = "claude-sonnet-5"; it[inputPrice] = 3.0; it[outputPrice] = 15.0; it[cacheReadPrice] = 0.3
                it[cacheWritePrice] = 3.75; it[cacheWrite1hPrice] = 6.0
            }
        }
        ModelPriceRepo.seedDefaults()
        assertEquals("builtin", price("claude-opus-5").origin)
        assertEquals("override", price("claude-sonnet-5").origin)
        assertEquals(3.0, price("claude-sonnet-5").inputPrice)
        assertTrue(transaction { ModelPriceBase.selectAll().any { it[ModelPriceBase.pattern] == "claude-sonnet-5" } })
    }
}
