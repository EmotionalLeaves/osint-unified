package com.osint.unified.data

import com.osint.unified.data.db.AppDatabase
import com.osint.unified.data.db.Investigation
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Observable
import com.osint.unified.domain.Tool
import com.osint.unified.domain.executor.ExecutorFactory
import kotlinx.coroutines.flow.Flow

/**
 * Creates investigations and runs tools, persisting every run to Room as a
 * [ResultRow]: inserted as "running", then updated to "success"/"error".
 */
class InvestigationRepository(
    db: AppDatabase,
    private val executors: ExecutorFactory
) {
    private val investigationDao = db.investigationDao()
    private val resultDao = db.resultDao()

    suspend fun createInvestigation(observable: Observable): Long {
        val now = System.currentTimeMillis()
        return investigationDao.insert(
            Investigation(
                name = "${observable.typeLabel}: ${observable.raw}",
                seedObservable = observable.raw,
                observableType = observable.typeLabel,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    fun observeResults(investigationId: Long): Flow<List<ResultRow>> =
        resultDao.observeForInvestigation(investigationId)

    fun observeInvestigations(): Flow<List<Investigation>> =
        investigationDao.observeAll()

    suspend fun deleteInvestigation(id: Long) = investigationDao.delete(id)

    /** Runs one tool and persists its lifecycle (running -> success/error). */
    suspend fun runTool(investigationId: Long, tool: Tool, input: String) {
        val started = System.currentTimeMillis()
        val rowId = resultDao.insert(
            ResultRow(
                investigationId = investigationId,
                toolId = tool.id,
                toolName = tool.name,
                input = input,
                status = "running",
                rawOutput = "",
                startedAt = started
            )
        )

        val outcome: kotlin.Result<String> = executors.forTool(tool).execute(tool, input)
        val finished = System.currentTimeMillis()

        val updated = outcome.fold(
            onSuccess = { raw ->
                ResultRow(
                    id = rowId,
                    investigationId = investigationId,
                    toolId = tool.id,
                    toolName = tool.name,
                    input = input,
                    status = "success",
                    rawOutput = raw,
                    startedAt = started,
                    finishedAt = finished
                )
            },
            onFailure = { err ->
                ResultRow(
                    id = rowId,
                    investigationId = investigationId,
                    toolId = tool.id,
                    toolName = tool.name,
                    input = input,
                    status = "error",
                    rawOutput = "",
                    errorMessage = err.message ?: err.javaClass.simpleName,
                    startedAt = started,
                    finishedAt = finished
                )
            }
        )
        resultDao.update(updated)
    }
}
