package com.osint.unified.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A case: several targets investigated as one enquiry.
 *
 * Until this existed, every target was its own island. A handle leading
 * to an address leading to a host was held together by [Investigation.parentId],
 * but two people you believe are working together, reached separately,
 * had nothing recording that belief — so the app could never say the one
 * thing that actually makes a case: these two targets share an address.
 */
@Entity(tableName = "cases")
data class Case(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** What the investigator concluded about the case as a whole. */
    val summary: String = ""
)

/**
 * One target within a case, and its tool results.
 */
@Entity(
    tableName = "investigations",
    indices = [Index(value = ["caseId"])]
)
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
    val viaPivot: String? = null,
    /**
     * The case this target belongs to, if it has been filed in one.
     *
     * Null is the ordinary state: a target searched once and never
     * filed. Nulled rather than deleted when a case is deleted, because
     * throwing away an evening's collection along with the folder it was
     * in is not what anybody means by deleting a case.
     */
    val caseId: Long? = null,
    /**
     * The investigator's own labels, comma-separated and lowercased.
     *
     * Free text rather than a fixed vocabulary: the words that matter in
     * an enquiry are the enquiry's own, and a list someone else wrote
     * would be wrong for every case but theirs.
     */
    val tags: String = ""
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
    val finishedAt: Long? = null,
    /**
     * The digest of what the source actually sent, before this row
     * truncated it.
     *
     * The export computed its hash from [rawOutput], which is capped at
     * 256 KB — so a report claiming to fingerprint "exactly what came
     * back" was fingerprinting a copy with the end cut off, and the hash
     * would not match the source's own output for any large answer.
     * Taken at capture, when the whole thing is still in hand.
     */
    val outputSha256: String = "",
    /** How many bytes the source sent, whole. */
    val outputBytes: Long = 0,
    /** Whether [rawOutput] is the whole of it. */
    val truncated: Boolean = false
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

/**
 * A finding the investigator says is wrong.
 *
 * The app grades what the sources return; it has no idea whether the
 * "J. Doe" a sweep found is the right J. Doe. The person looking does,
 * and until now had nowhere to put that — so the same false lead came
 * back at the same confidence every time the case was reopened, and
 * three of them made a dossier untrustworthy in a way no amount of
 * grading could fix.
 *
 * Kept per case rather than globally: an address that is noise in one
 * investigation is the subject of another.
 */
@Entity(
    tableName = "dismissals",
    foreignKeys = [
        ForeignKey(
            entity = Investigation::class,
            parentColumns = ["id"],
            childColumns = ["investigationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["investigationId", "kind", "value"], unique = true)]
)
data class Dismissal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investigationId: Long,
    val kind: String,
    val value: String,
    val at: Long = System.currentTimeMillis(),
    /** Why, when the person said. Worth more in a report than the fact. */
    val reason: String = ""
)
