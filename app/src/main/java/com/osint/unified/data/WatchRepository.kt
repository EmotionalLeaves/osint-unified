package com.osint.unified.data

import com.osint.unified.data.db.AppDatabase
import com.osint.unified.data.db.Watch
import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Findings
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.Tool
import com.osint.unified.domain.executor.ExecutorFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * Targets the app keeps checking by itself.
 *
 * An investigation answers "what is true now". The question that usually
 * matters is "what changed", and no single run can answer it — you have to
 * ask again later and remember the previous answer. A new subdomain, a
 * profile that appeared, a sanctions entry added: each is invisible to a
 * snapshot and obvious to a comparison.
 *
 * What is stored between checks is a digest, not the results: one line per
 * source naming it and fingerprinting what it said. That is enough to see a
 * change, costs a few hundred bytes per target, and avoids keeping a
 * growing archive of everything every source has ever returned.
 */
class WatchRepository(
    db: AppDatabase,
    private val router: ObservableRouter,
    private val executors: ExecutorFactory
) {
    private val dao = db.watchDao()

    fun observeAll(): Flow<List<Watch>> = dao.observeAll()

    suspend fun isWatched(observable: String): Boolean = dao.countFor(observable) > 0

    suspend fun add(observable: Observable): Long = dao.insert(
        Watch(observable = observable.raw, observableType = observable.typeLabel)
    )

    suspend fun remove(id: Long) = dao.delete(id)

    /** The dossier knows the target, not the row id. */
    suspend fun removeByObservable(observable: String) {
        dao.all().filter { it.observable == observable }.forEach { dao.delete(it.id) }
    }

    /** A check of every watched target. Returns what changed, in words. */
    suspend fun checkAll(): List<String> {
        val changes = ArrayList<String>()
        dao.all().forEach { watch ->
            runCatching { check(watch) }.getOrNull()?.let { changes.add(it) }
        }
        return changes
    }

    /** One target. Returns a description if something changed, else null. */
    suspend fun check(watch: Watch): String? {
        val observable = router.classify(watch.observable)
        val tools = router.suggestTools(observable)
            .filter { it.executionMode == ExecutionMode.DIRECT }
            .take(TOOLS_PER_CHECK)
        if (tools.isEmpty()) return null

        val fresh = fingerprint(observable, tools)
        // Nothing came back at all — the network was down, or every source
        // timed out. Reporting that as "everything disappeared" would be a
        // false alarm of the worst kind, so the check is simply abandoned.
        if (fresh.isEmpty()) return null

        val previous = parse(watch.digest)
        val now = System.currentTimeMillis()

        // The first check has nothing to compare against. It establishes the
        // baseline and reports no change, which is the honest answer.
        if (previous.isEmpty()) {
            dao.update(
                watch.copy(
                    digest = serialise(fresh),
                    lastCheckedAt = now,
                    checks = watch.checks + 1
                )
            )
            return null
        }

        val appeared = fresh.keys.filter { it !in previous }
        val vanished = previous.keys.filter { it !in fresh }
        val altered = fresh.keys.filter { it in previous && fresh[it] != previous[it] }

        val description = WatchDigest.describe(watch.observable, appeared, vanished, altered)
        dao.update(
            watch.copy(
                digest = serialise(fresh),
                lastCheckedAt = now,
                checks = watch.checks + 1,
                lastChange = description ?: watch.lastChange,
                lastChangeAt = if (description != null) now else watch.lastChangeAt
            )
        )
        return description
    }

    /**
     * Runs the tools and reduces each answer to a short fingerprint.
     *
     * Only sources that actually found something are fingerprinted. A source
     * that says "no entity named X" says the same thing every time until the
     * day it does not, and that day is a change worth reporting — but an
     * unreachable source is not, and pooling the two would raise an alarm
     * every time a server hiccuped.
     */
    private suspend fun fingerprint(
        observable: Observable,
        tools: List<Tool>
    ): Map<String, String> = coroutineScope {
        tools.map { tool ->
            async {
                val output = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                    runCatching { executors.forTool(tool).execute(tool, observable.raw) }
                        .getOrNull()?.getOrNull()
                } ?: return@async null
                if (output.isBlank() || Findings.saysNothing(output)) return@async null
                tool.id to sha1(WatchDigest.normalise(output))
            }
        }.mapNotNull { it.await() }.toMap()
    }

    private fun serialise(digest: Map<String, String>): String =
        digest.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }

    private fun parse(stored: String): Map<String, String> = stored.lineSequence()
        .mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
        }.toMap()

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private companion object {
        /** Enough for a meaningful picture; few enough to run unattended. */
        const val TOOLS_PER_CHECK = 10
        const val TOOL_TIMEOUT_MS = 30_000L
    }
}

/**
 * The two judgements a watch rests on, kept apart from the database so
 * they can be tested without one.
 */
internal object WatchDigest {

    /**
     * Strips what changes on its own.
     *
     * Half these sources stamp the response with the time it was
     * generated, a request id, or a "3 hours ago" age. Fingerprinting the
     * raw text would then report a change on every single check, which
     * tells you exactly as much as reporting none — and trains you to
     * ignore the alerts that matter.
     */
    fun normalise(output: String): String = output
        .replace(Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?(\.\d+)?Z?"""), "")
        .replace(Regex("""\b\d+\s*(seconds?|minutes?|hours?|days?)\s*ago\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\b1[0-9]{9,12}\b"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /**
     * What changed, in words — or null, which means nothing did and the
     * person should not be told anything at all.
     */
    fun describe(
        target: String,
        appeared: List<String>,
        vanished: List<String>,
        altered: List<String>
    ): String? {
        if (appeared.isEmpty() && vanished.isEmpty() && altered.isEmpty()) return null
        fun names(ids: List<String>) = ids.map {
            com.osint.unified.domain.ToolRegistry.byId(it)?.name ?: it
        }.joinToString(", ")
        val parts = ArrayList<String>()
        if (appeared.isNotEmpty()) parts.add("now found by ${names(appeared)}")
        if (vanished.isNotEmpty()) parts.add("no longer found by ${names(vanished)}")
        if (altered.isNotEmpty()) parts.add("changed at ${names(altered)}")
        return "$target — ${parts.joinToString("; ")}"
    }
}
