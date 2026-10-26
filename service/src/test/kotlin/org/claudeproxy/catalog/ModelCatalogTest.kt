package org.claudeproxy.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.claudeproxy.Config
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    private lateinit var dbFile: File

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("catalog-test", ".db")
        val cfg = Config(
            bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbFile.absolutePath,
            masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
            adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
            publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
            internalToken = null,
        )
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
        // The catalogue is a process-wide object; start every test from an empty listing.
        ModelCatalog.replaceLive(emptyList())
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
    }

    @Test
    fun `before any listing the builtin list is offered, newest first`() {
        val ids = ModelCatalog.visible().map { it.id }
        assertEquals("claude-opus-5-5", ids.first())
        assertTrue("claude-sonnet-5-5" in ids)
        assertTrue(ModelCatalog.visible().all { it.origin == "builtin" })
    }

    /** Opus 5.5 shares a prefix with Opus 5 — the old chat merge hid it behind that one. */
    @Test
    fun `a live listing replaces the builtin one, prefix siblings included`() {
        ModelCatalog.replaceLive(
            listOf(
                ModelCatalog.Listed("claude-opus-5-5", "Claude Opus 5.5", "2026-09-01T00:00:00Z"),
                ModelCatalog.Listed("claude-opus-5", "Claude Opus 5", null),
                ModelCatalog.Listed("claude-haiku-4-5-20251001", null, null),
            ),
        )
        val all = ModelCatalog.visible()
        assertEquals(listOf("claude-opus-5-5", "claude-opus-5", "claude-haiku-4-5-20251001"), all.map { it.id })
        assertTrue(all.all { it.origin == "live" })
        assertEquals("Haiku 4 5 20251001", all.last().displayName, "no display name → prettified id")
        assertEquals("Fastest and cheapest", all.last().description)
        assertEquals("claude-opus-5-5", all.first().pricePattern, "priced by its own row, not Opus 5's")
    }

    @Test
    fun `overrides rename, hide and add models`() {
        ModelCatalog.replaceLive(listOf(ModelCatalog.Listed("claude-opus-5", "Claude Opus 5", null)))
        ModelCatalog.setOverride("claude-opus-5", "Opus (old)", null, hidden = true)
        ModelCatalog.setOverride("claude-secret-9", "Secret 9", "early access", hidden = false)

        val all = ModelCatalog.all()
        val opus = all.first { it.id == "claude-opus-5" }
        assertEquals("Opus (old)", opus.displayName)
        assertTrue(opus.hidden && opus.overridden)
        val manual = all.first { it.id == "claude-secret-9" }
        assertEquals("manual", manual.origin)
        assertNull(manual.pricePattern, "nothing prices an unknown family")
        assertEquals(listOf("claude-secret-9"), ModelCatalog.visible().map { it.id })

        ModelCatalog.deleteOverride("claude-opus-5")
        assertFalse(ModelCatalog.all().first { it.id == "claude-opus-5" }.hidden)
    }

    @Test
    fun `listing parse keeps ids only`() {
        val page = Json.parseToJsonElement(
            """{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-09-01T00:00:00Z"},
               {"type":"model","id":"bad id/../x"},{"type":"model"}],"has_more":false}""",
        ) as JsonObject
        val listed = ModelCatalog.parseListing(page)
        assertEquals(listOf("claude-opus-5-5"), listed.map { it.id })
        assertEquals("Claude Opus 5.5", listed.single().displayName)
    }

    @Test
    fun `price list parse takes anthropic rows and folds dated aliases`() {
        val text = """
        {
          "sample_spec": {"input_cost_per_token": 0},
          "claude-opus-5-5": {"litellm_provider": "anthropic", "input_cost_per_token": 4e-06,
            "output_cost_per_token": 2e-05, "cache_read_input_token_cost": 2e-07,
            "cache_creation_input_token_cost": 5e-06, "cache_creation_input_token_cost_above_1hr": 8e-06,
            "provider_specific_entry": {"us": 1.1, "fast": 2.5},
            "search_context_cost_per_query": {"search_context_size_medium": 0.02}},
          "claude-opus-4-5": {"litellm_provider": "anthropic", "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05},
          "claude-opus-4-5-20251101": {"litellm_provider": "anthropic", "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05},
          "claude-3-haiku-20240307": {"litellm_provider": "anthropic", "input_cost_per_token": 2.5e-07, "output_cost_per_token": 1.25e-06},
          "anthropic/claude-opus-5-5": {"litellm_provider": "anthropic", "input_cost_per_token": 4e-06, "output_cost_per_token": 2e-05},
          "bedrock/claude-x": {"litellm_provider": "bedrock", "input_cost_per_token": 4e-06, "output_cost_per_token": 2e-05},
          "claude-free": {"litellm_provider": "anthropic", "input_cost_per_token": 0, "output_cost_per_token": 0}
        }
        """
        val rows = PriceSync.parse(text).associateBy { it.pattern }
        assertEquals(setOf("claude-opus-5-5", "claude-opus-4-5", "claude-3-haiku-20240307"), rows.keys)
        val o = rows.getValue("claude-opus-5-5")
        assertEquals(4.0, o.inputPrice)
        assertEquals(20.0, o.outputPrice)
        assertEquals(0.2, o.cacheReadPrice)
        assertEquals(5.0, o.cacheWritePrice)
        assertEquals(8.0, o.cacheWrite1hPrice)
        assertEquals(2.5, o.fastMultiplier)
        assertEquals(0.02, o.webSearchPrice)
        // Missing cache prices are derived from Anthropic's fixed relations to input.
        val old = rows.getValue("claude-opus-4-5")
        assertEquals(0.5, old.cacheReadPrice)
        assertEquals(6.25, old.cacheWritePrice)
        assertEquals(10.0, old.cacheWrite1hPrice)
    }
}
