package com.osint.unified.data

import com.osint.unified.data.db.AppDatabase
import com.osint.unified.data.db.Investigation
import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Findings
import com.osint.unified.domain.Observable
import com.osint.unified.domain.Tool
import com.osint.unified.domain.executor.ExecutorFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

    suspend fun createInvestigation(
        observable: Observable,
        parentId: Long? = null,
        viaPivot: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        return investigationDao.insert(
            Investigation(
                name = "${observable.typeLabel}: ${observable.raw}",
                seedObservable = observable.raw,
                observableType = observable.typeLabel,
                createdAt = now,
                updatedAt = now,
                parentId = parentId,
                viaPivot = viaPivot
            )
        )
    }

    /**
     * The chain back to the first thing searched, oldest first.
     *
     * A real enquiry is a tree — a handle leads to an address leads to a
     * host — and the app used to throw the edges away, leaving a flat list
     * of seeds with nothing recording that any of them were related.
     */
    suspend fun lineage(id: Long): List<Investigation> {
        val chain = ArrayList<Investigation>()
        var current = investigationDao.byId(id)
        val seen = HashSet<Long>()
        while (current != null && seen.add(current.id)) {
            chain.add(current)
            current = current.parentId?.let { investigationDao.byId(it) }
        }
        return chain.reversed()
    }

    suspend fun setNotes(id: Long, notes: String) = investigationDao.setNotes(id, notes)

    /** What was written about this case, for re-opening it. */
    suspend fun notesFor(id: Long): String = investigationDao.getById(id)?.notes.orEmpty()

    /** One case, for a screen that has only its id. */
    suspend fun byId(id: Long): Investigation? = investigationDao.getById(id)

    fun observeResults(investigationId: Long): Flow<List<ResultRow>> =
        resultDao.observeForInvestigation(investigationId)

    fun observeInvestigations(): Flow<List<Investigation>> =
        investigationDao.observeAll()

    suspend fun deleteInvestigation(id: Long) = investigationDao.delete(id)

    /** Runs one tool, persists its lifecycle (running -> success/error), and
     *  returns the final stored row (so a workflow can chain on its output). */
    suspend fun runTool(investigationId: Long, tool: Tool, input: String): ResultRow {
        val started = System.currentTimeMillis()
        // Re-running a tool replaces its row rather than adding a second
        // one. RUN ALL used to duplicate every result, which doubled the
        // pivot counts and printed everything twice in the export.
        //
        // But the replacement is written at the end, not the start. Before,
        // the "running" row went over the previous one immediately: a
        // re-run that then failed — a source gone down, the network gone —
        // destroyed the finding it was supposed to refresh. Evidence is not
        // deleted to make room for an attempt to re-collect it.
        val previous = resultDao.existing(investigationId, tool.id, input)
        val rowId = if (previous != null && previous.status == "success") {
            previous.id
        } else {
            resultDao.insert(
                ResultRow(
                    id = previous?.id ?: 0,
                    investigationId = investigationId,
                    toolId = tool.id,
                    toolName = tool.name,
                    input = input,
                    status = "running",
                    rawOutput = "",
                    startedAt = started
                )
            )
        }

        // Hard cap per tool so one slow source can't make the dossier feel
        // hung; most finish in 1–10s, this is just a safety net.
        val cap = tool.timeoutMs.coerceAtMost(TOOL_CAP_MS)
        val outcome: kotlin.Result<String> = try {
            withTimeoutOrNull(cap) { executors.forTool(tool).execute(tool, input) }
                ?: kotlin.Result.failure(java.io.IOException("No response within ${cap / 1000}s"))
        } catch (cancelled: CancellationException) {
            // A new search, or STOP. The row was inserted as "running" and
            // without this it stays that way for ever: History spins on it
            // and the export calls it unreachable. NonCancellable because the
            // scope is already dying.
            withContext(NonCancellable) {
                // A stopped re-run leaves the previous finding exactly as
                // it was. Only a row that had nothing in it becomes
                // "cancelled".
                if (previous == null || previous.status != "success") {
                    resultDao.update(
                        ResultRow(
                            id = rowId,
                            investigationId = investigationId,
                            toolId = tool.id,
                            toolName = tool.name,
                            input = input,
                            status = "cancelled",
                            rawOutput = "",
                            errorMessage = "stopped before it finished",
                            startedAt = started,
                            finishedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            throw cancelled
        }
        val finished = System.currentTimeMillis()

        val updated = outcome.fold(
            onSuccess = { raw ->
                // The tool's own verdict where it has one, so the dossier is
                // not left reading English to decide whether a sanctions
                // search came back clean.
                val stated = runCatching { executors.forTool(tool).classify(tool, raw) }
                    .getOrNull()
                // Fingerprinted here, where the whole answer is still in
                // hand. Taking the digest later, from the stored row, is
                // taking it from a copy this method truncated.
                val bytes = raw.toByteArray()
                ResultRow(
                    id = rowId,
                    investigationId = investigationId,
                    toolId = tool.id,
                    toolName = tool.name,
                    input = input,
                    status = when (stated) {
                        Findings.Kind.ABSENT -> Findings.STATUS_ABSENT
                        Findings.Kind.UNREACHABLE -> Findings.STATUS_UNREACHABLE
                        else -> "success"
                    },
                    rawOutput = cap(raw),
                    startedAt = started,
                    finishedAt = finished,
                    outputSha256 = sha256(bytes),
                    outputBytes = bytes.size.toLong(),
                    truncated = raw.length > MAX_RESULT_CHARS
                )
            },
            onFailure = { err ->
                // A failed re-run keeps what the successful run found, and
                // says that the refresh failed. Overwriting a finding with
                // an error message is losing evidence to a dropped
                // connection.
                if (previous != null && previous.status == "success") {
                    previous.copy(
                        errorMessage = "could not be refreshed " +
                            "(${err.message ?: err.javaClass.simpleName}); " +
                            "showing what was found before"
                    )
                } else {
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
            }
        )
        resultDao.update(updated)
        return updated
    }

    /**
     * Keeps one result from making a whole case unopenable.
     *
     * Room reads a row through a CursorWindow of about two megabytes, and a
     * row past that throws when the results flow reads it — which kills the
     * collector, so the investigation can never be opened again. One
     * talkative source should not be able to do that.
     */
    /** The digest of exactly what the source sent. */
    private fun sha256(bytes: ByteArray): String = runCatching {
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun cap(raw: String): String =
        if (raw.length <= MAX_RESULT_CHARS) raw
        else raw.take(MAX_RESULT_CHARS) +
            "\n\n[truncated: this source returned ${raw.length / 1024} KB, " +
            "kept the first ${MAX_RESULT_CHARS / 1024} KB]"

    /**
     * Rows left mid-flight when the app was killed.
     *
     * A process death during a search leaves "running" rows that nothing
     * will ever complete, and they would otherwise spin in History for the
     * life of the database.
     */
    suspend fun markInterruptedRuns() {
        runCatching { resultDao.markRunningAsInterrupted() }
    }

    private companion object {
        /**
         * The outer cap. It has to exceed everything that can happen inside
         * a single tool — the username sweep is 32 s of checking plus up to
         * 18 s of control probes — or the whole result is thrown away and
         * replaced by a generic timeout just as it finishes.
         */
        const val TOOL_CAP_MS = 75_000L

        /** Well inside Room's ~2 MB CursorWindow, with room for the rest of
         *  the row and for several rows in one page. */
        const val MAX_RESULT_CHARS = 256 * 1024
    }
}
