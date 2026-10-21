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
    val notes: String = ""
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
