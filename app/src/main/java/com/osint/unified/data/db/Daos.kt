package com.osint.unified.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestigationDao {
    @Query("SELECT * FROM investigations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Investigation>>

    @Query("SELECT * FROM investigations WHERE id = :id")
    suspend fun getById(id: Long): Investigation?

    /**
     * The same row, watched.
     *
     * The one-shot read above was wrapped in a flatMapLatest and
     * treated as a flow, so it re-emitted only when the selected id
     * changed. File the open target in a case, or save tags on it, and
     * the list updated while the screen above it did not: the case chip
     * never moved and the tags button said SAVE TAGS forever, because
     * the comparison it makes against the stored value could never
     * become equal.
     */
    @Query("SELECT * FROM investigations WHERE id = :id")
    fun observeById(id: Long): Flow<Investigation?>

    @Insert
    suspend fun insert(investigation: Investigation): Long

    @Update
    suspend fun update(investigation: Investigation)

    @Query("DELETE FROM investigations WHERE id = :id")
    suspend fun delete(id: Long)

    /** The chain back to the first thing that was searched. */
    @Query("SELECT * FROM investigations WHERE id = :id")
    suspend fun byId(id: Long): Investigation?

    /** Everything that came out of this investigation. */
    @Query("SELECT * FROM investigations WHERE parentId = :id ORDER BY createdAt")
    suspend fun children(id: Long): List<Investigation>

    @Query("UPDATE investigations SET notes = :notes, updatedAt = :at WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String, at: Long = System.currentTimeMillis())
}

@Dao
interface ResultDao {
    @Query(
        "SELECT * FROM results WHERE investigationId = :investigationId " +
            "AND toolId = :toolId AND input = :input LIMIT 1"
    )
    suspend fun existing(investigationId: Long, toolId: String, input: String): Result?

    @Query("SELECT * FROM results WHERE investigationId = :investigationId ORDER BY startedAt DESC")
    fun observeForInvestigation(investigationId: Long): Flow<List<Result>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: Result): Long

    @Update
    suspend fun update(result: Result)

    @Query("DELETE FROM results WHERE investigationId = :investigationId")
    suspend fun deleteForInvestigation(investigationId: Long)

    @Query("SELECT * FROM results WHERE investigationId = :investigationId ORDER BY startedAt DESC")
    suspend fun forInvestigation(investigationId: Long): List<Result>

    /** Nothing will ever finish a run the process died in the middle of. */
    @Query("UPDATE results SET status = 'interrupted' WHERE status = 'running'")
    suspend fun markRunningAsInterrupted()
}

@Dao
interface WatchDao {
    @Query("SELECT * FROM watches ORDER BY lastChangeAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<Watch>>

    @Query("SELECT * FROM watches")
    suspend fun all(): List<Watch>

    @Query("SELECT COUNT(*) FROM watches WHERE observable = :observable")
    suspend fun countFor(observable: String): Int

    @Query("SELECT * FROM watches WHERE observable = :observable LIMIT 1")
    suspend fun byObservable(observable: String): Watch?

    /** Returns -1 when the target is already watched, rather than throwing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(watch: Watch): Long

    @Update
    suspend fun update(watch: Watch)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM watches WHERE observable = :observable")
    suspend fun deleteByObservable(observable: String)
}

@Dao
interface WatchEventDao {
    @Query("SELECT * FROM watch_events WHERE watchId = :watchId ORDER BY at DESC LIMIT :limit")
    fun observeFor(watchId: Long, limit: Int): Flow<List<WatchEvent>>

    @Query("SELECT * FROM watch_events ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WatchEvent>>

    @Query(
        "SELECT watchId, COUNT(*) AS unseen FROM watch_events " +
            "WHERE acknowledged = 0 GROUP BY watchId"
    )
    fun observeUnseen(): Flow<List<UnseenCount>>

    @Insert
    suspend fun insert(event: WatchEvent): Long

    @Query("UPDATE watch_events SET acknowledged = 1 WHERE watchId = :watchId")
    suspend fun acknowledge(watchId: Long)

    @Query("UPDATE watch_events SET acknowledged = 1")
    suspend fun acknowledgeAll()

    /**
     * Keeps the most recent [keep] events for a target.
     *
     * A watch checked every six hours for a year is 1,460 checks; a noisy
     * target could leave a row behind for a good share of them. The log is
     * a history, not an archive.
     */
    @Query(
        "DELETE FROM watch_events WHERE watchId = :watchId AND id NOT IN (" +
            "SELECT id FROM watch_events WHERE watchId = :watchId ORDER BY at DESC LIMIT :keep)"
    )
    suspend fun trim(watchId: Long, keep: Int)
}

@Dao
interface DismissalDao {
    @Query("SELECT * FROM dismissals WHERE investigationId = :investigationId")
    fun observeFor(investigationId: Long): Flow<List<Dismissal>>

    @Query("SELECT * FROM dismissals WHERE investigationId = :investigationId")
    suspend fun forInvestigation(investigationId: Long): List<Dismissal>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(dismissal: Dismissal)

    @Query(
        "DELETE FROM dismissals WHERE investigationId = :investigationId " +
            "AND kind = :kind AND value = :value"
    )
    suspend fun remove(investigationId: Long, kind: String, value: String)

    /**
     * The reason, written after the fact.
     *
     * Marking a finding wrong has to be one tap or nobody does it, so
     * the reason is a second, optional step — and the insert above
     * ignores conflicts, which would silently drop it.
     */
    @Query(
        "UPDATE dismissals SET reason = :reason WHERE " +
            "investigationId = :investigationId AND kind = :kind AND value = :value"
    )
    suspend fun setReason(investigationId: Long, kind: String, value: String, reason: String)
}

@Dao
interface CaseDao {
    @Query("SELECT * FROM cases ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Case>>

    @Query("SELECT * FROM cases WHERE id = :id")
    suspend fun byId(id: Long): Case?

    // Not `case`. Room generates Java, Java reserves that word, and the
    // only sign of it is "illegal start of expression" pointing at a
    // generated file nobody has on disk.
    @Insert
    suspend fun insert(row: Case): Long

    @Update
    suspend fun update(row: Case)

    @Query("UPDATE cases SET summary = :summary, updatedAt = :at WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String, at: Long)

    @Query("DELETE FROM cases WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE investigations SET caseId = NULL, updatedAt = :at WHERE caseId = :caseId")
    suspend fun unfileAll(caseId: Long, at: Long)

    /**
     * Unfile every target, then drop the case, as one thing.
     *
     * The repository did this as a loop of single-row updates
     * followed by the delete, outside any transaction. There is no
     * foreign key on investigations.caseId, so nothing in the
     * database objected if the delete landed and some of the updates
     * had not: those rows kept pointing at a case that no longer
     * exists, the case list counted them against a row it could not
     * show, and they were reachable from nothing. One statement and
     * one transaction, so it either all happened or none of it did.
     */
    @Transaction
    suspend fun deleteWithTargets(id: Long, at: Long) {
        unfileAll(id, at)
        delete(id)
    }

    @Query("SELECT * FROM investigations WHERE caseId = :caseId ORDER BY createdAt")
    fun observeTargets(caseId: Long): Flow<List<Investigation>>

    @Query("SELECT * FROM investigations WHERE caseId = :caseId ORDER BY createdAt")
    suspend fun targets(caseId: Long): List<Investigation>

    @Query("UPDATE investigations SET caseId = :caseId, updatedAt = :at WHERE id = :id")
    suspend fun fileUnder(id: Long, caseId: Long?, at: Long)

    @Query("UPDATE investigations SET tags = :tags, updatedAt = :at WHERE id = :id")
    suspend fun setTags(id: Long, tags: String, at: Long)

    /** How many targets each case holds, for the list. */
    @Query(
        "SELECT caseId AS caseId, COUNT(*) AS targets FROM investigations " +
            "WHERE caseId IS NOT NULL GROUP BY caseId"
    )
    fun observeCounts(): Flow<List<CaseCount>>
}

data class CaseCount(val caseId: Long, val targets: Int)
