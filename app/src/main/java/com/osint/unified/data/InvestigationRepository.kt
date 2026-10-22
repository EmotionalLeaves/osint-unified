package com.osint.unified.data

import com.osint.unified.data.db.AppDatabase
import com.osint.unified.data.db.Investigation
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Observable
import com.osint.unified.domain.Tool
import com.osint.unified.domain.executor.ExecutorFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

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

    /** Runs one tool, persists its lifecycle (running -> success/error), and
     *  returns the final stored row (so a workflow can chain on its output). */
    suspend fun runTool(investigationId: Long, tool: Tool, input: String): ResultRow {
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

        // Hard cap per tool so one slow source can't make the dossier feel
        // hung; most finish in 1–10s, this is just a safety net.
        val cap = tool.timeoutMs.coerceAtMost(40_000L)
        val outcome: kotlin.Result<String> =
            withTimeoutOrNull(cap) { executors.forTool(tool).execute(tool, input) }
                ?: kotlin.Result.failure(java.io.IOException("No response within ${cap / 1000}s"))
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
        return updated
    }
}
