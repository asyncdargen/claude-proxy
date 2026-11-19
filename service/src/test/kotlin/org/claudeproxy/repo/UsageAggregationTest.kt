package org.claudeproxy.repo

import org.claudeproxy.Config
import org.claudeproxy.accounts.*
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.db.*
import org.claudeproxy.model.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.*

/**
 * Usage reads are answered by the database, from the hourly rollup for the hours it holds and from
 * the events for the rest. Every read is checked here against the row-by-row semantics it replaced,
 * over the same random events, with no rollup, a rollup a day behind and a current one, so neither
 * the move to SQL nor the rollup changed a figure.
 */
class UsageAggregationTest {
    private class Event(
        val account: Int, val user: Int?, val ts: Instant, val input: Long, val output: Long,
        val cacheRead: Long, val cacheWrite: Long, val cost: Double, val model: String?, val source: String, val token: Int?,
    )

    private lateinit var db: File
    private val events = mutableListOf<Event>()
    private val start: Instant get() = now.minus(Duration.ofDays(3)).minus(Duration.ofMinutes(17))
    private val end: Instant get() = now.minus(Duration.ofMinutes(7))
    private var alice = 0
    private var bob = 0
    private val owner = HashMap<Int, Int?>()
    private val now: Instant = Instant.ofEpochMilli(System.currentTimeMillis())
    private val today: Instant = now.minus(Duration.ofHours(6))

    @BeforeTest fun setup() {
        db = File.createTempFile("usage-aggregation", ".db")
        val secret = "test-secret-at-least-32-characters"
        Secrets.init(Crypto(secret))
        Db.init(Config(bindHost="127.0.0.1", port=8787, publicDomain=null, dbPath=db.absolutePath,
            masterKey=secret, sessionSecret=secret, adminUser="admin", adminPassword="admin",
            upstreamBaseUrl="https://example.invalid", publicBaseUrl="", databaseUrl="",
            databaseUser="", databasePassword="", internalToken=null))
        MemoryCache.clear()
        alice = UserRepo.create("alice", "pw", listOf("user"), emptyList(), null)
        bob = UserRepo.create("bob", "pw", listOf("user"), emptyList(), null)
        for ((name, ownerId) in listOf("shared-a" to null, "shared-b" to null, "alice-own" to alice, "bob-own" to bob)) {
            val id = AccountRepo.create(
                name = name, type = AccountType.OAUTH, groupId = null, priority = 10, threshold = 0.9,
                kind = AccountKind.PRO, secret = AccountSecret(accessToken = "a", refreshToken = "r"), createdBy = null,
                ownerId = ownerId)
            owner[id] = ownerId
        }
        val random = Random(42)
        val accounts = owner.keys.toList()
        repeat(400) {
            events += Event(
                account = accounts.random(random), user = listOf(alice, bob, null).random(random),
                ts = now.minus(Duration.ofMinutes(random.nextLong(0, 4 * 24 * 60))),
                input = random.nextLong(0, 5000), output = random.nextLong(0, 5000),
                cacheRead = random.nextLong(0, 50000), cacheWrite = random.nextLong(0, 9000),
                cost = random.nextDouble(0.0, 3.0), model = listOf("claude-opus-5-5", "claude-sonnet-5-5", null).random(random),
                source = listOf("proxy", "routing", "chat").random(random), token = listOf(1, 2, null).random(random),
            )
        }
        transaction {
            for (e in events) UsageEvents.insert {
                it[accountId] = e.account; it[userId] = e.user; it[ts] = e.ts
                it[inputTokens] = e.input; it[outputTokens] = e.output
                it[cacheReadTokens] = e.cacheRead; it[cacheWriteTokens] = e.cacheWrite
                it[cost] = e.cost; it[model] = e.model; it[sourceCol] = e.source; it[tokenId] = e.token
            }
        }
    }

    @AfterTest fun cleanup() { MemoryCache.clear(); db.delete() }

    private fun totalsOf(rows: List<Event>) = Totals(
        rows.size.toLong(), rows.sumOf { it.input }, rows.sumOf { it.output },
        rows.sumOf { it.cacheRead }, rows.sumOf { it.cacheWrite }, rows.sumOf { it.cost },
    )

    private fun assertSame(expected: Totals, actual: Totals?, label: String) {
        assertNotNull(actual, label)
        assertEquals(
            listOf(expected.requests, expected.input, expected.output, expected.cacheRead, expected.cacheWrite),
            listOf(actual.requests, actual.input, actual.output, actual.cacheRead, actual.cacheWrite), label)
        assertEquals(expected.cost, actual.cost, 1e-6, label)
    }

    private val pool get() = events.filter { owner[it.account] == null }

    private fun checkUserTotals() {
        for (user in listOf(alice, bob)) for (since in listOf(null, today)) for (globalOnly in listOf(false, true))
            for (source in listOf(null, "proxy", "routing", "chat")) {
                val expected = events.filter {
                    it.user == user && (since == null || it.ts >= since) &&
                        (!globalOnly || owner[it.account] == null) && (source == null || it.source == source)
                }
                assertSame(totalsOf(expected), UsageRepo.userTotals(user, since, globalOnly, source), "$user $since $globalOnly $source")
            }
    }

    private fun checkPerAccountAndPool() {
        val perAccount = UsageRepo.totalsPerAccount()
        assertEquals(pool.map { it.account }.toSet(), perAccount.keys)
        pool.groupBy { it.account }.forEach { (id, rows) -> assertSame(totalsOf(rows), perAccount[id], "account $id") }
        assertSame(totalsOf(pool), UsageRepo.poolTotals(), "pool")
    }

    private fun checkAccountSets() {
        val ids = owner.filterValues { it != null }.keys
        val actual = UsageRepo.totalsForAccounts(ids)
        assertEquals(events.filter { it.account in ids }.map { it.account }.toSet(), actual.keys)
        events.filter { it.account in ids }.groupBy { it.account }.forEach { (id, rows) -> assertSame(totalsOf(rows), actual[id], "account $id") }
        assertEquals(emptyMap(), UsageRepo.totalsForAccounts(emptySet()))
    }

    private fun checkPerModel() {
        fun check(expected: List<Event>, actual: List<ModelUsageDto>, label: String) {
            val byModel = expected.groupBy { it.model }
            assertEquals(byModel.keys, actual.map { it.model }.toSet(), label)
            for (row in actual) {
                val t = totalsOf(byModel.getValue(row.model))
                assertEquals(t.requests, row.requests, label); assertEquals(t.clean, row.cleanTokens, label)
                assertEquals(t.cost, row.cost, 1e-6, label)
            }
            assertEquals(actual.sortedByDescending { it.cost }, actual, "$label sorted by cost")
        }
        check(events.filter { it.user == alice && it.source == "routing" }, UsageRepo.userPerModel(alice, null, "routing"), "user")
        check(events.filter { it.user == bob && it.ts >= today }, UsageRepo.userPerModel(bob, today), "user today")
        check(pool, UsageRepo.perModel(), "pool")
        check(pool.filter { it.ts >= today }, UsageRepo.perModel(today), "pool today")
    }

    private fun checkSummaryAndTokens() {
        val summary = UsageRepo.summarySince(today)
        val expected = pool.filter { it.ts >= today }.groupBy { it.account }
        assertEquals(expected.keys, summary.map { it.accountId }.toSet())
        for (row in summary) {
            val t = totalsOf(expected.getValue(row.accountId))
            assertEquals(listOf(t.requests, t.input, t.output), listOf(row.requests, row.inputTokens, row.outputTokens))
            assertEquals(t.cost, row.cost, 1e-6)
        }
        val perToken = UsageRepo.totalsPerTokenForUser(alice, "proxy")
        val byToken = events.filter { it.user == alice && it.source == "proxy" }.groupBy { it.token }
        assertEquals(byToken.keys, perToken.keys)
        byToken.forEach { (token, rows) -> assertSame(totalsOf(rows), perToken[token], "token $token") }
    }

    private fun checkOverview() {
        val limitDay = today.minus(Duration.ofHours(30))
        val rows = UsageRepo.overviewByUser(today, limitDay).associateBy { it.userId }
        assertEquals(events.map { it.user }.toSet(), rows.keys)
        for ((user, mine) in events.groupBy { it.user }) {
            val r = rows.getValue(user)
            val routing = mine.filter { it.source == "routing" }
            val other = mine.filter { it.source != "routing" }
            val todays = mine.filter { it.ts >= today }
            val limits = mine.filter { it.ts >= limitDay }
            assertEquals(mine.size.toLong(), r.totalRequests)
            assertEquals(totalsOf(mine).clean, r.totalTokens)
            assertEquals(routing.sumOf { it.cost }, r.totalRoutingCost, 1e-6)
            assertEquals(other.sumOf { it.cost }, r.totalProxyCost, 1e-6)
            assertEquals(todays.size.toLong(), r.todayRequests)
            assertEquals(totalsOf(todays).clean, r.todayTokens)
            assertEquals(todays.sumOf { it.cost }, r.todayCost, 1e-6)
            assertEquals(limits.filter { it.source == "routing" }.sumOf { it.cost }, r.todayRoutingCost, 1e-6)
            assertEquals(limits.filter { it.source != "routing" }.sumOf { it.cost }, r.todayProxyCost, 1e-6)
            assertEquals(mine.maxOf { it.ts }, r.lastActivity)
        }
    }

    private fun checkDayBuckets() {
        val window = events.filter { it.ts >= start && it.ts < end }
        for (zone in listOf("UTC", "Europe/Paris", "Asia/Kolkata").map(ZoneId::of)) {
            fun date(e: Event) = e.ts.atZone(zone).toLocalDate().toString()
            fun <K> byKey(rows: List<Event>, key: (Event) -> K) = rows.groupBy(key).mapValues { totalsOf(it.value) }
            val daily = UsageRepo.dailyBuckets(start, end, zone)
            val expectedDaily = byKey(window.filter { owner[it.account] == null }) { it.account to date(it) }
            assertEquals(expectedDaily.keys, daily.map { it.accountId to it.date }.toSet(), "daily $zone")
            for (b in daily) {
                val t = expectedDaily.getValue(b.accountId to b.date)
                assertEquals(listOf(t.requests, t.clean), listOf(b.requests, b.tokens), "daily $zone")
                assertEquals(t.cost, b.cost, 1e-6, "daily $zone")
            }
            for (source in listOf(null, "routing")) {
                val mine = window.filter { it.user == alice && (source == null || it.source == source) }
                val userDaily = UsageRepo.dailyBucketsForUser(alice, start, end, source, zone)
                val expectedUser = byKey(mine) { it.account to date(it) }
                assertEquals(expectedUser.keys, userDaily.map { it.accountId to it.date }.toSet(), "user daily $zone $source")
                for (b in userDaily) {
                    val t = expectedUser.getValue(b.accountId to b.date)
                    assertEquals(listOf(t.requests, t.clean), listOf(b.requests, b.tokens)); assertEquals(t.cost, b.cost, 1e-6)
                }
                val userTokens = UsageRepo.tokenBucketsForUser(alice, start, end, source, zone)
                val expectedTokens = byKey(mine) { Triple(it.account, it.model, date(it)) }
                assertEquals(expectedTokens.keys, userTokens.map { Triple(it.accountId, it.model, it.date) }.toSet(), "user tokens $zone")
                for (b in userTokens) {
                    val t = expectedTokens.getValue(Triple(b.accountId, b.model, b.date))
                    assertEquals(listOf(t.input, t.output, t.cacheRead, t.cacheWrite), listOf(b.input, b.output, b.cacheRead, b.cacheWrite))
                }
            }
            val perToken = UsageRepo.tokenDailyBucketsForUser(bob, "proxy", start, end, zone)
            val expectedPerToken = byKey(window.filter { it.user == bob && it.source == "proxy" }) { it.token to date(it) }
            assertEquals(expectedPerToken.keys, perToken.map { it.tokenId to it.date }.toSet(), "token daily $zone")
            for (b in perToken) {
                val t = expectedPerToken.getValue(b.tokenId to b.date)
                assertEquals(listOf(t.requests, t.clean), listOf(b.requests, b.tokens)); assertEquals(t.cost, b.cost, 1e-6)
            }
            val pooled = UsageRepo.tokenBuckets(start, end, zone)
            val expectedPooled = byKey(window.filter { owner[it.account] == null }) { Triple(it.account, it.model, date(it)) }
            assertEquals(expectedPooled.keys, pooled.map { Triple(it.accountId, it.model, it.date) }.toSet(), "pool tokens $zone")
            for (b in pooled) {
                val t = expectedPooled.getValue(Triple(b.accountId, b.model, b.date))
                assertEquals(listOf(t.input, t.output, t.cacheRead, t.cacheWrite), listOf(b.input, b.output, b.cacheRead, b.cacheWrite))
            }
        }
    }

    private fun checkAll() {
        checkUserTotals(); checkPerAccountAndPool(); checkAccountSets(); checkPerModel()
        checkSummaryAndTokens(); checkOverview(); checkDayBuckets()
    }

    private fun hourlyRows() = transaction {
        UsageHourly.selectAll().map { row -> UsageHourly.columns.map { row[it] } }.sortedBy { it.toString() }
    }

    @Test fun `the events alone answer every read`() = checkAll()

    @Test fun `a rollup through the last closed hour answers the same`() {
        UsageRollup.rollUp(now)
        assertTrue(hourlyRows().isNotEmpty())
        checkAll()
    }

    @Test fun `a rollup that stopped a day behind answers the same`() {
        UsageRollup.rollUp(now.minus(Duration.ofHours(30)))
        checkAll()
    }

    @Test fun `a second pass changes nothing`() {
        UsageRollup.rollUp(now)
        val first = hourlyRows()
        val through = transaction { UsageRollup.through() }
        UsageRollup.rollUp(now)
        assertEquals(first, hourlyRows())
        assertEquals(through, transaction { UsageRollup.through() })
        assertEquals(now.truncatedTo(java.time.temporal.ChronoUnit.HOURS), through)
    }

    @Test fun `clearing a user and deleting one carry over to the rollup`() {
        UsageRollup.rollUp(now)
        UsageRepo.clearUser(bob)
        events.removeAll { it.user == bob }
        checkAll()
        val aliceOwn = owner.filterValues { it == alice }.keys
        UserRepo.delete(alice)
        events.removeAll { it.account in aliceOwn }
        aliceOwn.forEach(owner::remove)
        val detached = events.map { if (it.user == alice) Event(it.account, null, it.ts, it.input, it.output, it.cacheRead, it.cacheWrite, it.cost, it.model, it.source, it.token) else it }
        events.clear(); events.addAll(detached)
        checkAll()
        UsageRepo.clearAll()
        events.clear()
        assertEquals(emptyList(), hourlyRows())
        checkAll()
    }

    @Test fun `usage events are indexed by user for the daily limit check`() {
        val indexes = transaction {
            val names = mutableListOf<String>()
            TransactionManager.current().exec("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'usage_events'") { rs ->
                while (rs.next()) names += rs.getString(1)
            }
            names
        }
        assertTrue("usage_events_user_id_ts" in indexes && "usage_events_ts" in indexes, "indexes: $indexes")
    }
}
