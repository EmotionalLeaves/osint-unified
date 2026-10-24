package com.osint.unified

import com.osint.unified.data.db.MigrationSql
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upgrade path, replayed against real SQLite.
 *
 * `exportSchema = false` means there is no generated JSON for Room's
 * MigrationTestHelper to validate against, and MigrationTestHelper
 * wants an instrumented test on a device, which this project's CI does
 * not have. But a migration is SQL, and SQL can be run anywhere: a
 * version-1 database is built here, filled with the sort of rows a real
 * one holds, and taken up to 7 one step at a time.
 *
 * The migration that needs this is 3 to 4 — the only one in the chain
 * that is not purely additive. It deletes user rows and then builds a
 * UNIQUE index over what is left, and if those two were ever reordered
 * the index creation would throw and the app would crash on launch for
 * whoever had watched the same target twice.
 */
class MigrationChainTest {

    private fun memory(): Connection {
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        return c
    }

    private fun Connection.run(sql: List<String>) =
        createStatement().use { s -> sql.forEach { s.execute(it) } }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.count(sql: String): Int =
        createStatement().use { s ->
            s.executeQuery(sql).use { if (it.next()) it.getInt(1) else -1 }
        }

    private fun Connection.columns(table: String): Set<String> =
        createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
        }

    private fun Connection.indices(): Set<String> =
        createStatement().use { s ->
            s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { rs ->
                buildSet { while (rs.next()) rs.getString(1)?.let { add(it) } }
            }
        }

    private fun Connection.tables(): Set<String> =
        createStatement().use { s ->
            s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                buildSet { while (rs.next()) rs.getString(1)?.let { add(it) } }
            }
        }

    private fun Connection.seedV1() {
        exec(
            "INSERT INTO `investigations` " +
                "(`name`, `seedObservable`, `observableType`, `createdAt`, `updatedAt`, `notes`) " +
                "VALUES ('Target one', 'example.com', 'domain', 1, 1, 'worth another look')"
        )
        exec(
            "INSERT INTO `results` " +
                "(`investigationId`, `toolId`, `toolName`, `input`, `status`, " +
                "`rawOutput`, `startedAt`) " +
                "VALUES (1, 'crtsh', 'crt.sh', 'example.com', 'success', 'a.example.com', 10)"
        )
    }

    @Test
    fun theWholeChainRunsAndKeepsTheRowsThatWereThere() {
        memory().use { db ->
            db.run(MigrationSql.v1)
            db.seedV1()
            MigrationSql.chain.forEach { (_, statements) -> db.run(statements) }

            assertEquals(1, db.count("SELECT count(*) FROM `investigations`"))
            assertEquals(1, db.count("SELECT count(*) FROM `results`"))
            assertTrue(
                db.tables().containsAll(
                    listOf("investigations", "results", "watches", "watch_events", "dismissals", "cases")
                )
            )
            // Every column the entities declare, arrived by ALTER.
            assertTrue(
                db.columns("investigations").containsAll(
                    listOf("parentId", "viaPivot", "caseId", "tags")
                )
            )
            assertTrue(
                db.columns("results").containsAll(
                    listOf("outputSha256", "outputBytes", "truncated")
                )
            )
            // And the index names Room generates, which repairVersion
            // cannot see and so cannot repair.
            assertTrue(
                db.indices().containsAll(
                    listOf(
                        "index_watches_observable",
                        "index_watch_events_watchId",
                        "index_watch_events_at",
                        "index_dismissals_investigationId_kind_value",
                        "index_investigations_caseId",
                    )
                )
            )
        }
    }

    /**
     * The failure this migration was written to avoid: two watches on
     * the same observable, and a UNIQUE index about to be built over
     * them.
     */
    @Test
    fun threeToFourCollapsesDuplicateWatchesAndKeepsTheOldest() {
        memory().use { db ->
            db.run(MigrationSql.v1)
            db.run(MigrationSql.v1to2)
            db.run(MigrationSql.v2to3)
            // Two rows for one target, the older with the longer record.
            db.exec(
                "INSERT INTO `watches` (`id`, `observable`, `observableType`, `createdAt`, " +
                    "`digest`, `lastChange`, `lastChangeAt`, `checks`) " +
                    "VALUES (1, 'example.com', 'domain', 100, 'd1', 'a subdomain appeared', 500, 9)"
            )
            db.exec(
                "INSERT INTO `watches` (`id`, `observable`, `observableType`, `createdAt`, " +
                    "`digest`, `lastChange`, `lastChangeAt`, `checks`) " +
                    "VALUES (2, 'example.com', 'domain', 200, 'd2', '', NULL, 1)"
            )
            db.exec(
                "INSERT INTO `watches` (`id`, `observable`, `observableType`, `createdAt`, " +
                    "`digest`, `lastChange`, `lastChangeAt`, `checks`) " +
                    "VALUES (3, 'other.example', 'domain', 300, 'd3', '', NULL, 0)"
            )

            db.run(MigrationSql.v3to4)

            assertEquals(2, db.count("SELECT count(*) FROM `watches`"))
            assertEquals(
                "the oldest of the duplicates is the one kept",
                9,
                db.count("SELECT `checks` FROM `watches` WHERE `observable` = 'example.com'")
            )
            // The change it already remembered became the first entry
            // of its log rather than being thrown away.
            assertEquals(1, db.count("SELECT count(*) FROM `watch_events`"))
            assertEquals(1, db.count("SELECT `watchId` FROM `watch_events`"))
        }
    }

    /**
     * And the index is real afterwards: a second row for the same
     * observable must be refused.
     */
    @Test
    fun afterThreeToFourTheSameTargetCannotBeWatchedTwice() {
        memory().use { db ->
            db.run(MigrationSql.v1)
            db.run(MigrationSql.v1to2)
            db.run(MigrationSql.v2to3)
            db.run(MigrationSql.v3to4)
            db.exec(
                "INSERT INTO `watches` (`observable`, `observableType`, `createdAt`, " +
                    "`digest`, `lastChange`, `checks`) " +
                    "VALUES ('example.com', 'domain', 1, '', '', 0)"
            )
            var refused = false
            try {
                db.exec(
                    "INSERT INTO `watches` (`observable`, `observableType`, `createdAt`, " +
                        "`digest`, `lastChange`, `checks`) " +
                        "VALUES ('example.com', 'domain', 2, '', '', 0)"
                )
            } catch (expected: java.sql.SQLException) {
                refused = true
            }
            assertTrue("the unique index is not doing anything", refused)
        }
    }

    /**
     * The order inside 3 to 4 is load-bearing, so it is asserted
     * directly rather than left to the comment above it.
     */
    @Test
    fun theDedupeComesBeforeTheUniqueIndex() {
        val dedupe = MigrationSql.v3to4.indexOfFirst { it.startsWith("DELETE FROM `watches`") }
        val index = MigrationSql.v3to4.indexOfFirst {
            it.contains("UNIQUE INDEX") && it.contains("index_watches_observable")
        }
        assertTrue("the dedupe is gone", dedupe >= 0)
        assertTrue("the unique index is gone", index >= 0)
        assertTrue(
            "building the index before collapsing duplicates crashes the " +
                "upgrade on launch",
            dedupe < index
        )
    }

    /**
     * Filing a target in a case and then deleting the case must not
     * take the target with it — the folder goes, the collection does
     * not. There is no foreign key on caseId, which is what makes that
     * true, so this pins it.
     */
    @Test
    fun sixToSevenLeavesTargetsUnownedRatherThanOwned() {
        memory().use { db ->
            db.run(MigrationSql.v1)
            db.seedV1()
            MigrationSql.chain.forEach { (_, statements) -> db.run(statements) }
            db.exec(
                "INSERT INTO `cases` (`id`, `name`, `createdAt`, `updatedAt`, `summary`) " +
                    "VALUES (1, 'Case one', 1, 1, '')"
            )
            db.exec("UPDATE `investigations` SET `caseId` = 1 WHERE `id` = 1")
            db.exec("DELETE FROM `cases` WHERE `id` = 1")
            assertEquals(
                "deleting a case must not delete its targets",
                1,
                db.count("SELECT count(*) FROM `investigations`")
            )
        }
    }

    /** Deleting an investigation does take its results and marks. */
    @Test
    fun deletingAnInvestigationCascadesToItsResultsAndMarks() {
        memory().use { db ->
            db.run(MigrationSql.v1)
            db.seedV1()
            MigrationSql.chain.forEach { (_, statements) -> db.run(statements) }
            db.exec(
                "INSERT INTO `dismissals` (`investigationId`, `kind`, `value`, `at`, `reason`) " +
                    "VALUES (1, 'email', 'a@example.com', 1, 'not them')"
            )
            db.exec("DELETE FROM `investigations` WHERE `id` = 1")
            assertEquals(0, db.count("SELECT count(*) FROM `results`"))
            assertEquals(0, db.count("SELECT count(*) FROM `dismissals`"))
            assertFalse(db.tables().isEmpty())
        }
    }
}
