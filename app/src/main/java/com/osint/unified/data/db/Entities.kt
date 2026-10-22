package com.osint.unified.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved investigation / case. Groups observables and their tool results.
 */
@Entity(tableName = "investigations")
data class Investigation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val seedObservable: String,
    val observableType: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    /**
     * The investigation this one came out of.
     *
     * Tapping a pivot used to create a new, unrelated investigation, so a
     * real enquiry — a handle leading to an address leading to a host —
     * came apart into a flat list of disconnected seeds with nothing
     * recording that any of them had anything to do with each other.
     */
    val parentId: Long? = null,
    /** The identifier in the parent's results that led here. */
    val viaPivot: String? = null
)

/**
 * The output of one tool run within an investigation. Raw tool output is
 * stored verbatim as JSON text so nothing is lost before parsing/export.
 */
@Entity(
    tableName = "results",
    foreignKeys = [
        ForeignKey(
            entity = Investigation::class,
            parentColumns = ["id"],
            childColumns = ["investigationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("investigationId")]
)
data class Result(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investigationId: Long,
    val toolId: String,
    val toolName: String,
    val input: String,
    val status: String,        // "success" | "error" | "timeout" | "running"
    val rawOutput: String,     // verbatim JSON/text from the tool
    val errorMessage: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null
)

/**
 * A target the app keeps checking on its own.
 *
 * An investigation is a snapshot: it tells you what was true when you
 * asked. Most of what an analyst wants to know is whether something has
 * *changed* — a new subdomain, a profile that appeared, a sanctions entry
 * added — and that requires asking again later and remembering the
 * previous answer. [digest] is that memory: one line per source, naming
 * the source and fingerprinting what it said, which is enough to detect a
 * change without storing every full result forever.
 */
@Entity(
    tableName = "watches",
    // Watching the same target twice produced two rows that checked the
    // same sources, raised the same alerts, and each had to be removed
    // separately. The database refuses the second one now.
    indices = [Index(value = ["observable"], unique = true)]
)
data class Watch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val observable: String,
    val observableType: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long? = null,
    /** "toolId<TAB>fingerprint" per line, from the last successful check. */
    val digest: String = "",
    /** What changed at [lastChangeAt], in words, or empty if never. */
    val lastChange: String = "",
    val lastChangeAt: Long? = null,
    val checks: Int = 0
)

/**
 * One change found on a watched target.
 *
 * The watch row used to hold only the most recent change, so the second
 * one overwrote the first and the history of a target — the thing a watch
 * exists to accumulate — was a single line that kept being replaced. Each
 * change is now its own row: what appeared, what went away, what altered,
 * and whether it has been seen.
 *
 * Tool ids rather than names are stored, because a name can be reworded
 * between versions and a stored sentence would then disagree with the
 * current one. The words are built when the row is read.
 */
@Entity(
    tableName = "watch_events",
    foreignKeys = [
        ForeignKey(
            entity = Watch::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("watchId"), Index("at")]
)
data class WatchEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    val at: Long,
    /** The sentence shown in the list and the notification. */
    val summary: String,
    /** Comma-separated tool ids; empty when nothing fell in that bucket. */
    val appeared: String = "",
    val vanished: String = "",
    val altered: String = "",
    val acknowledged: Boolean = false
)

/** How many unseen changes a watch has. Room fills both columns by name. */
data class UnseenCount(val watchId: Long, val unseen: Int)
