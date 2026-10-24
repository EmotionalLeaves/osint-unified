package com.osint.unified.data.db

/**
 * Every migration's statements, as text, in order.
 *
 * Pulled out of the Migration objects so that something other than a
 * phone can run them. `exportSchema = false` means there is no
 * generated JSON for Room's MigrationTestHelper to check against, and
 * MigrationTestHelper needs an instrumented device in any case; this
 * project's only compiler is a CI runner with no emulator. So the
 * statements are data, the Migration objects execute them, and
 * MigrationChainTest replays the whole chain against real SQLite on
 * the JVM — building a version-1 database, putting rows in it, and
 * asserting what survives.
 *
 * That matters most for [v3to4], the only migration in the chain that
 * is not purely additive: it deletes user rows and then creates a
 * UNIQUE index over live data. If the dedupe and the index were ever
 * reordered, the index creation would fail and the app would crash on
 * launch for exactly the people who used watches most. The test holds
 * that order in place.
 *
 * These strings are the schema. Changing one changes what is on
 * somebody's phone, so they are edited with the same care as the
 * entity they mirror — and tools/migration-schema.py checks that the
 * two still agree.
 */
object MigrationSql {

    /**
     * Version 1, as Room first created it.
     *
     * Only the test uses this: the app has never created a version-1
     * database from here. It is the two tables that existed before
     * anything was migrated, written without any column a later
     * migration adds.
     */
    val v1: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `investigations` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `name` TEXT NOT NULL,
            `seedObservable` TEXT NOT NULL,
            `observableType` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `notes` TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `results` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `investigationId` INTEGER NOT NULL,
            `toolId` TEXT NOT NULL,
            `toolName` TEXT NOT NULL,
            `input` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `rawOutput` TEXT NOT NULL,
            `errorMessage` TEXT,
            `startedAt` INTEGER NOT NULL,
            `finishedAt` INTEGER,
            FOREIGN KEY(`investigationId`) REFERENCES `investigations`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_results_investigationId` " +
            "ON `results` (`investigationId`)",
    )

    /** Adds the watch table. */
    val v1to2: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `watches` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `observable` TEXT NOT NULL,
            `observableType` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `lastCheckedAt` INTEGER,
            `digest` TEXT NOT NULL,
            `lastChange` TEXT NOT NULL,
            `lastChangeAt` INTEGER,
            `checks` INTEGER NOT NULL
        )
        """.trimIndent(),
    )

    /** Records where an investigation came from. */
    val v2to3: List<String> = listOf(
        "ALTER TABLE `investigations` ADD COLUMN `parentId` INTEGER",
        "ALTER TABLE `investigations` ADD COLUMN `viaPivot` TEXT",
    )

    /**
     * Gives each watch a history, and stops the same target being
     * watched twice.
     *
     * The order is the point. Duplicate rows already in the database
     * are collapsed BEFORE the unique index is created, keeping the
     * oldest of each set — it has the longest record of checks behind
     * it. The other way round, the index creation fails and the
     * upgrade crashes on launch.
     */
    val v3to4: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `watch_events` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `watchId` INTEGER NOT NULL,
            `at` INTEGER NOT NULL,
            `summary` TEXT NOT NULL,
            `appeared` TEXT NOT NULL,
            `vanished` TEXT NOT NULL,
            `altered` TEXT NOT NULL,
            `acknowledged` INTEGER NOT NULL,
            FOREIGN KEY(`watchId`) REFERENCES `watches`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_watch_events_watchId` " +
            "ON `watch_events` (`watchId`)",
        "CREATE INDEX IF NOT EXISTS `index_watch_events_at` " +
            "ON `watch_events` (`at`)",
        "DELETE FROM `watches` WHERE `id` NOT IN " +
            "(SELECT MIN(`id`) FROM `watches` GROUP BY `observable`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_watches_observable` " +
            "ON `watches` (`observable`)",
        // The one change each watch already remembers becomes the
        // first entry of its log, rather than being thrown away.
        """
        INSERT INTO `watch_events`
            (`watchId`, `at`, `summary`, `appeared`, `vanished`, `altered`, `acknowledged`)
        SELECT `id`, `lastChangeAt`, `lastChange`, '', '', '', 1
        FROM `watches`
        WHERE `lastChangeAt` IS NOT NULL AND `lastChange` != ''
        """.trimIndent(),
    )

    /** Records what a source actually sent, not what was kept. */
    val v4to5: List<String> = listOf(
        "ALTER TABLE `results` ADD COLUMN `outputSha256` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `results` ADD COLUMN `outputBytes` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `results` ADD COLUMN `truncated` INTEGER NOT NULL DEFAULT 0",
    )

    /** Somewhere to record that a finding is wrong. */
    val v5to6: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `dismissals` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `investigationId` INTEGER NOT NULL,
            `kind` TEXT NOT NULL,
            `value` TEXT NOT NULL,
            `at` INTEGER NOT NULL,
            `reason` TEXT NOT NULL,
            FOREIGN KEY(`investigationId`) REFERENCES `investigations`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_dismissals_investigationId_kind_value` " +
            "ON `dismissals` (`investigationId`, `kind`, `value`)",
    )

    /** Several targets held together as one enquiry. */
    val v6to7: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `cases` (
            `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            `name` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `summary` TEXT NOT NULL
        )
        """.trimIndent(),
        "ALTER TABLE `investigations` ADD COLUMN `caseId` INTEGER",
        "ALTER TABLE `investigations` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''",
        "CREATE INDEX IF NOT EXISTS `index_investigations_caseId` " +
            "ON `investigations` (`caseId`)",
    )

    /** Every step from 1 to [AppDatabase.SCHEMA_VERSION], in order. */
    val chain: List<Pair<Int, List<String>>> = listOf(
        2 to v1to2,
        3 to v2to3,
        4 to v3to4,
        5 to v4to5,
        6 to v5to6,
        7 to v6to7,
    )
}
