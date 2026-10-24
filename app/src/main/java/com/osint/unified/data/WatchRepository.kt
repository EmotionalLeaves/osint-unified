package com.osint.unified.data

import com.osint.unified.data.db.AppDatabase
import com.osint.unified.data.db.UnseenCount
import com.osint.unified.data.db.Watch
import com.osint.unified.data.db.WatchEvent
import com.osint.unified.domain.orStop
import com.osint.unified.domain.ExecutionMode
import com.osint.unified.domain.Findings
import com.osint.unified.domain.Observable
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.Tool
import com.osint.unified.domain.executor.ExecutorFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val events = db.watchEventDao()

    /**
     * One round of checking at a time.
     *
     * The six-hourly worker and the CHECK NOW button could run together.
     * Both read a watch row, run the same sources, and write the row back;
     * the slower one then overwrote the faster one's digest with a baseline
     * taken before its change was recorded, and the change was reported
     * again on the next check. Interleaved, they also ran every source
     * twice at once against the same target.
     */
    private val checking = Mutex()

    fun observeAll(): Flow<List<Watch>> = dao.observeAll()

    /** The change log for one target, newest first. */
    fun observeEvents(watchId: Long): Flow<List<WatchEvent>> = events.observeFor(watchId, 50)

    /** Everything that has changed lately, across all targets. */
    fun observeRecentEvents(): Flow<List<WatchEvent>> = events.observeRecent(200)

    /** How many unseen changes each target has. */
    fun observeUnseen(): Flow<List<UnseenCount>> = events.observeUnseen()

    suspend fun acknowledge(watchId: Long) = events.acknowledge(watchId)

    suspend fun acknowledgeAll() = events.acknowledgeAll()

    suspend fun isWatched(observable: String): Boolean = dao.countFor(observable) > 0

    /** True when this added a new watch, false when it was already there. */
    suspend fun add(observable: Observable): Boolean = dao.insert(
        Watch(observable = observable.raw, observableType = observable.typeLabel)
    ) != -1L

    suspend fun remove(id: Long) = dao.delete(id)

    /** The dossier knows the target, not the row id. */
    suspend fun removeByObservable(observable: String) = dao.deleteByObservable(observable)

    /** What one check found, for the caller that has to announce it. */
    data class Change(val watchId: Long, val target: String, val summary: String)

    /** A check of every watched target. Returns what changed. */
    suspend fun checkAll(): List<Change> = withContext(Dispatchers.Default) {
        checkAllHere()
    }

    /**
     * CHECK NOW, run somewhere a tab switch cannot reach.
     *
     * The button launched into the screen's own
     * rememberCoroutineScope, and the tab host swaps screens by taking
     * them out of the composition — so starting a sweep of forty
     * targets and then looking at the globe for a moment cancelled the
     * whole thing silently, and coming back showed the button at rest
     * as though nothing had been asked. This repository outlives every
     * screen, which is where work the user started belongs.
     */
    fun checkAllDetached() {
        if (_running.value) return
        _running.value = true
        _trouble.value = null
        own.launch {
            val outcome = runCatching { checkAllHere() }
            _running.value = false
            _trouble.value = outcome.exceptionOrNull()
                ?.takeIf { it !is CancellationException }
                ?.message?.take(120)
        }
    }

    fun clearTrouble() { _trouble.value = null }

    private val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _running = MutableStateFlow(false)

    /** Whether a sweep started from the button is still going. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _trouble = MutableStateFlow<String?>(null)

    /** Why the last sweep did not finish, if it did not. */
    val trouble: StateFlow<String?> = _trouble.asStateFlow()

    /**
     * Its own dispatcher, rather than the caller's.
     *
     * The executors move the network to IO themselves, so the
     * requests were fine — everything after them was not.
     * WatchDigest.normalise is four regex passes over a tool's whole
     * output and every answer is then SHA-1'd, ten tools deep, for
     * every watched target. CHECK NOW launches from
     * rememberCoroutineScope, which is Main.immediate, so twenty
     * watched targets meant two hundred regex-and-digest passes on
     * the thread drawing the button. The background worker got this
     * right by accident, because CoroutineWorker defaults to
     * Default; the interactive path is the one a person is waiting
     * on.
     */
    private suspend fun checkAllHere(): List<Change> = checking.withLock {
        val changes = ArrayList<Change>()
        dao.all().forEach { watch ->
            val outcome = runCatching { check(watch) }
            // A cancellation is the work being stopped, not a target
            // failing. Swallowing it here would carry on through the
            // remaining watches after the system asked us to stop.
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            outcome.getOrNull()?.let { changes.add(it) }
        }
        changes
    }

    /** One target. Returns a description if something changed, else null. */
    private suspend fun check(watch: Watch): Change? {
        val observable = router.classify(watch.observable)
        // Passive only, always. A watch runs by itself, four times a day,
        // with nobody there to decide anything — so it is the last place
        // that should be knocking on a target's own door. It was running
        // tlscert, headers, sitefiles, webcrawl and the account sweeps
        // from the investigator's address every six hours, which
        // contradicted the promise on the first-run screen in the most
        // direct way available.
        val tools = router.suggestTools(observable)
            .filter { it.executionMode == ExecutionMode.DIRECT && !it.touchesTarget }
            .take(TOOLS_PER_CHECK)
        if (tools.isEmpty()) return null

        val outcome = fingerprint(observable, tools)
        val fresh = outcome.found
        val now = System.currentTimeMillis()

        // Nothing answered at all — the network was down, or every source
        // timed out. Reporting that as "everything disappeared" would be a
        // false alarm of the worst kind, so the check is abandoned. The
        // attempt is still recorded, or the watch would read as never
        // checked while the app had been trying all along.
        if (outcome.answered.isEmpty()) {
            dao.update(
                watch.copy(
                    lastCheckedAt = now,
                    checks = watch.checks + 1,
                    lastAnswered = 0,
                    lastAsked = tools.size
                )
            )
            return null
        }

        val previous = parse(watch.digest)

        // The first check has nothing to compare against. It establishes the
        // baseline and reports no change, which is the honest answer.
        if (previous.isEmpty()) {
            dao.update(
                watch.copy(
                    digest = serialise(fresh),
                    lastCheckedAt = now,
                    checks = watch.checks + 1,
                    lastAnswered = outcome.answered.size,
                    lastAsked = tools.size
                )
            )
            return null
        }

        val appeared = fresh.keys.filter { it !in previous }
        // Only a source that answered can be said to have stopped finding
        // something. One flaky source out of ten used to produce "no longer
        // found by X", and then — because its entry was dropped from the
        // stored digest — "now found by X" on the next successful check: a
        // watch flapping between two false alarms.
        val vanished = previous.keys.filter { it in outcome.answered && it !in fresh }
        val altered = fresh.keys.filter {
            it in previous && fresh[it]?.fingerprint != previous[it]?.fingerprint
        }
        // And, for the ones that changed, WHAT changed.
        //
        // This is the whole difference between a notification worth
        // acting on and one that means "go and look yourself" — which
        // is what every watch event said until now, against a database
        // that no longer holds the previous answer to look at.
        val gained = LinkedHashSet<String>()
        val lost = LinkedHashSet<String>()
        altered.forEach { id ->
            val before = previous[id]?.entities.orEmpty()
            // Nothing is "new" against a baseline that was never
            // taken.
            //
            // A row stored before this app remembered identifiers has
            // an empty set, and the first check after the upgrade
            // both fills it in and diffs against it — so a domain
            // where crt.sh had gained exactly one name reported
            // twenty-four of them as new. An empty baseline means the
            // detail is not known yet, which is a different thing
            // from everything being new.
            if (before.isEmpty()) return@forEach
            val after = fresh[id]?.entities.orEmpty()
            gained += after - before
            lost += before - after
        }
        // A source that was not finding this target and now is: every
        // identifier under it really is new.
        appeared.forEach { id -> gained += fresh[id]?.entities.orEmpty() }

        // A source that could not be reached keeps its previous
        // fingerprint, so the baseline survives the outage.
        val merged = previous.filterKeys { it !in outcome.answered } + fresh

        val description = WatchDigest.describe(
            watch.observable, appeared, vanished, altered,
            gained.toList(), lost.toList()
        )
        dao.update(
            watch.copy(
                digest = serialise(merged),
                lastCheckedAt = now,
                checks = watch.checks + 1,
                lastChange = description ?: watch.lastChange,
                lastChangeAt = if (description != null) now else watch.lastChangeAt,
                lastAnswered = outcome.answered.size,
                lastAsked = tools.size
            )
        )
        if (description == null) return null

        // The row keeps the latest change so the list can show it without a
        // join; the log keeps every one of them, which is the record a watch
        // exists to build.
        events.insert(
            WatchEvent(
                watchId = watch.id,
                at = now,
                summary = description,
                appeared = appeared.joinToString(","),
                vanished = vanished.joinToString(","),
                altered = altered.joinToString(",")
            )
        )
        events.trim(watch.id, 100)
        return Change(watch.id, watch.observable, description)
    }

    /**
     * What one round of checking produced.
     *
     * [answered] is every source that replied at all; [found] is the subset
     * that replied with something. The difference between them is what keeps
     * an unreachable source from being reported as a disappearance.
     */
    private data class Outcome(
        val answered: Set<String>,
        val found: Map<String, Snapshot>
    )

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
    ): Outcome = coroutineScope {
        val results = tools.map { tool ->
            async {
                val output = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                    runCatching { executors.forTool(tool).execute(tool, observable.raw) }
                        // Same reason as checkAllHere twelve lines up:
                        // a cancellation is this job being stopped, not
                        // a source failing. withTimeoutOrNull and the
                        // enclosing coroutineScope happen to recover the
                        // right behaviour today, which is luck, not a
                        // design — the first statement added after this
                        // async would run inside a cancelled coroutine.
                        .orStop()
                        .getOrNull()?.getOrNull()
                }
                // null  -> the source could not be reached at all
                // ""    -> it answered, and found nothing
                // other -> it answered, and found something
                val fingerprint: String? = when {
                    output == null -> null
                    output.isBlank() -> ""
                    Findings.saysUnreachable(output) -> null
                    Findings.saysNothing(output) -> ""
                    else -> sha1(WatchDigest.normalise(output))
                }
                val snapshot = if (fingerprint.isNullOrEmpty()) null else {
                    Snapshot(fingerprint, entitiesIn(tool.id, output.orEmpty(), observable.raw))
                }
                Triple(tool.id, fingerprint, snapshot)
            }
        }.map { it.await() }

        Outcome(
            answered = results.filter { it.second != null }.map { it.first }.toSet(),
            found = results.mapNotNull { (id, _, snap) ->
                if (snap == null) null else id to snap
            }.toMap()
        )
    }

    /**
     * What one source said last time: a fingerprint, and the
     * identifiers it named.
     *
     * The fingerprint alone is why a watch could say "changed at
     * Shodan" and never what changed. The content is not stored — the
     * class comment above is right that a growing archive of every
     * answer is the wrong thing — but the extracted identifiers are a
     * few hundred bytes a target and are exactly what a reader wants:
     * "new host mail2.example.com", not "a hash moved". And nothing
     * else can supply it later, because runTool replaces the previous
     * row, so the old answer is gone from the database too.
     */
    private data class Snapshot(val fingerprint: String, val entities: Set<String>)

    private fun serialise(digest: Map<String, Snapshot>): String =
        digest.entries.sortedBy { it.key }.joinToString("\n") { (id, snap) ->
            "$id\t${snap.fingerprint}\t${snap.entities.joinToString("|")}"
        }

    private fun parse(stored: String): Map<String, Snapshot> = stored.lineSequence()
        .mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
            // Two fields is a row written before this stored entities.
            // It parses as a snapshot with none, and the first check
            // after the upgrade fills them in.
            val entities = parts.getOrNull(2).orEmpty()
                .split('|').filter { it.isNotBlank() }.toSet()
            parts[0] to Snapshot(parts[1], entities)
        }.toMap()

    /**
     * The identifiers in one answer, as the set a later check diffs
     * against.
     *
     * Capped, because this is stored per source per target and a
     * subdomain sweep can name hundreds. The cap means a very large
     * answer's diff is partial, which is why [WatchDigest.describe]
     * never claims the list it prints is complete.
     */
    private fun entitiesIn(toolId: String, output: String, seed: String): Set<String> =
        runCatching {
            com.osint.unified.domain.EntityExtractor.extract(
                listOf(
                    com.osint.unified.data.db.Result(
                        investigationId = 0,
                        toolId = toolId,
                        toolName = toolId,
                        input = seed,
                        status = "success",
                        rawOutput = output,
                        startedAt = 0
                    )
                ),
                seed,
                limitPerKind = 10
            )
                .map { "${it.kind}:${it.value}" }
                // Sorted before the cap, not taken in the order the
                // text happened to be written.
                //
                // Every host in a certificate log is mentioned once,
                // so the extractor's count sort is a tie across all
                // of them and the order is whatever order the source
                // printed. crt.sh does not promise one — so a
                // reordered answer changed which two dozen were
                // remembered and the next check named hosts as new
                // and gone that had been there throughout. A vague
                // alert became a false specific one, which is worse.
                .sorted()
                .take(ENTITY_CAP)
                .toSet()
        }.getOrDefault(emptySet())

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private companion object {
        /** Enough for a meaningful picture; few enough to run unattended. */
        const val TOOLS_PER_CHECK = 10
        const val TOOL_TIMEOUT_MS = 30_000L

        /** Identifiers remembered per source per target. */
        const val ENTITY_CAP = 24
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
        // Sorted BEFORE any whitespace collapse, which would
        // otherwise eat the newlines this splits on.
        //
        // The order a source prints its answer in is not a fact
        // about the target: crt.sh and the subdomain sweeps promise
        // none, so a reordered answer changed the fingerprint and
        // raised an alert about nothing. The first thing a watch
        // must get right is not crying wolf.
        .split('\n')
        .map { it.replace(Regex("""[ \t]+"""), " ").trim() }
        .filter { it.isNotEmpty() }
        .sorted()
        .joinToString("\n")

    /**
     * What changed, in words — or null, which means nothing did and the
     * person should not be told anything at all.
     */
    fun describe(
        target: String,
        appeared: List<String>,
        vanished: List<String>,
        altered: List<String>,
        /** Identifiers in the new answer that were not in the old one. */
        gained: List<String> = emptyList(),
        /** And the ones that were and are not. */
        lost: List<String> = emptyList()
    ): String? {
        if (appeared.isEmpty() && vanished.isEmpty() && altered.isEmpty()) return null
        fun names(ids: List<String>) = ids.map {
            com.osint.unified.domain.ToolRegistry.byId(it)?.name ?: it
        }.joinToString(", ")
        val parts = ArrayList<String>()
        if (appeared.isNotEmpty()) parts.add("now found by ${names(appeared)}")
        if (vanished.isNotEmpty()) parts.add("no longer found by ${names(vanished)}")
        if (altered.isNotEmpty()) parts.add("changed at ${names(altered)}")
        val head = "$target — ${parts.joinToString("; ")}"
        // What changed, by name, where it is known.
        //
        // A watch existed to answer "what changed" and could only
        // answer "something did, at Shodan" — the check reduced every
        // answer to a hash and threw the rest away, and runTool
        // replaces the previous row, so there was nothing left to
        // compare against afterwards either. Three months of watching
        // produced a hundred events and not one byte of what any
        // source had said.
        val detail = ArrayList<String>()
        if (gained.isNotEmpty()) detail.add("new: " + pretty(gained))
        if (lost.isNotEmpty()) detail.add("gone: " + pretty(lost))
        return if (detail.isEmpty()) head else "$head\n" + detail.joinToString("; ")
    }

    /**
     * A handful of identifiers, named, with the rest counted.
     *
     * Never claims the list is complete: the check remembers a capped
     * set per source, so a very large answer's difference is partial
     * and saying otherwise would be the same class of lie as a header
     * that states a total and lists fewer.
     */
    private fun pretty(values: List<String>, show: Int = 4): String {
        val named = values.take(show).joinToString(", ") { it.substringAfter(':') }
        val rest = values.size - show
        return if (rest > 0) "$named and $rest more" else named
    }
}
