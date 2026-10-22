package com.osint.unified.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestigationDao {
    @Query("SELECT * FROM investigations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Investigation>>

    @Query("SELECT * FROM investigations WHERE id = :id")
    suspend fun getById(id: Long): Investigation?

    @Insert
    suspend fun insert(investigation: Investigation): Long

    @Update
    suspend fun update(investigation: Investigation)

    @Query("DELETE FROM investigations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ResultDao {
    @Query("SELECT * FROM results WHERE investigationId = :investigationId ORDER BY startedAt DESC")
    fun observeForInvestigation(investigationId: Long): Flow<List<Result>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: Result): Long

    @Update
    suspend fun update(result: Result)

    @Query("DELETE FROM results WHERE investigationId = :investigationId")
    suspend fun deleteForInvestigation(investigationId: Long)

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

    @Insert
    suspend fun insert(watch: Watch): Long

    @Update
    suspend fun update(watch: Watch)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun delete(id: Long)
}
