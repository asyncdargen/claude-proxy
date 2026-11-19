package org.claudeproxy.repo

import org.claudeproxy.db.UsageEvents
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Today" has two bases, and mixing them up is the bug this guards: displayed counters follow the
 * viewer's day (so the cards agree with the charts under them), while the per-datapath costs read
 * against the daily USD limits stay on the UTC day the limits are enforced on.
 */
class ViewerDayStatsTest {
    private val dbFile = File.createTempFile("viewer-day-stats", ".db")

    @AfterTest
    fun teardown() = dbFile.delete().let {}

    private fun connect(): Database {
        val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        // The usage reads also consult the rollup and its watermark (a settings row).
        transaction(db) { SchemaUtils.create(UsageEvents, org.claudeproxy.db.Settings, org.claudeproxy.db.UsageHourly) }
        return db
    }

    private fun event(db: Database, at: Instant, cost: Double, source: String = "proxy") = transaction(db) {
        UsageEvents.insert {
            it[accountId] = 1; it[userId] = 7; it[ts] = at
            it[inputTokens] = 10; it[outputTokens] = 0
            it[UsageEvents.cost] = cost; it[sourceCol] = source
        }
        Unit
    }

    @Test
    fun `startOfDayIn tracks the zone, startOfUtcDay stays on UTC`() {
        // A zone far enough east that its day starts on the previous UTC date for most of the day.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(
            LocalDate.now(tokyo).atStartOfDay(tokyo).toInstant(),
            UserRepo.startOfDayIn(tokyo),
        )
        assertEquals(
            LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant(),
            UserRepo.startOfUtcDay(),
        )
    }

    @Test
    fun `overview counts display totals on the viewer day and limit costs on the UTC day`() {
        val db = connect()
        val utcDayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()
        // One event inside the UTC day, one two hours before it — the latter still belongs to
        // "today" for a viewer whose day started earlier (anything east of UTC).
        val insideUtcDay = utcDayStart.plusSeconds(3600)
        val beforeUtcDay = utcDayStart.minusSeconds(2 * 3600)
        event(db, insideUtcDay, cost = 1.0)
        event(db, beforeUtcDay, cost = 0.25)

        val row = transaction(db) {
            UsageRepo.overviewByUser(startOfDay = beforeUtcDay, startOfLimitDay = utcDayStart)
        }.single { it.userId == 7 }

        // Display counters take both events (the viewer's day began before the UTC day did)…
        assertEquals(1.25, row.todayCost, 1e-9)
        assertEquals(2, row.todayRequests)
        // …while the limit-facing proxy cost only counts what landed inside the UTC day.
        assertEquals(1.0, row.todayProxyCost, 1e-9)
        assertEquals(0.0, row.todayRoutingCost, 1e-9)
        // All-time is unaffected by either boundary.
        assertEquals(1.25, row.totalCost, 1e-9)
        assertEquals(2, row.totalRequests)
    }

    @Test
    fun `a single basis reproduces the pre-change behaviour`() {
        val db = connect()
        val utcDayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()
        event(db, utcDayStart.plusSeconds(60), cost = 2.0, source = "routing")
        event(db, utcDayStart.minusSeconds(60), cost = 5.0, source = "routing")

        // Default startOfLimitDay == startOfDay: display and limit bases coincide, as on UTC±0.
        val row = transaction(db) { UsageRepo.overviewByUser(utcDayStart) }.single { it.userId == 7 }
        assertEquals(2.0, row.todayCost, 1e-9)
        assertEquals(2.0, row.todayRoutingCost, 1e-9)
        assertEquals(1, row.todayRequests)
    }
}
