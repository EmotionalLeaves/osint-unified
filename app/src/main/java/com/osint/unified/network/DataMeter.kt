package com.osint.unified.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How much of someone's data allowance this app has spent.
 *
 * Worth measuring rather than estimating. This is a map of live feeds
 * on a phone, and the difference between a layer that costs two
 * megabytes an hour and one that costs nine hundred is not visible
 * anywhere in the code — the aircraft layer was sweeping a dozen
 * half-megabyte requests every fifteen seconds for months and nothing
 * in the app said so. A number on the screen is the thing that makes
 * that kind of mistake findable by the person paying for it.
 *
 * Counted off the wire, after compression, by a network interceptor —
 * so a conditional request that comes back 304 costs the few hundred
 * bytes it really costs, and a response served from the disk cache
 * costs nothing and is not counted at all. Headers are not included;
 * they are small and the figure is for orientation, not billing.
 *
 * Per process, not persisted. "Since you opened the app" is the
 * question somebody actually has.
 */
object DataMeter {

    private val total = AtomicLong(0)
    private val calls = AtomicLong(0)
    private val byHost = ConcurrentHashMap<String, AtomicLong>()
    private val startedAt = System.currentTimeMillis()

    internal fun add(host: String, bytes: Long) {
        if (bytes <= 0) return
        total.addAndGet(bytes)
        // Bounded, because a dossier can touch hundreds of hosts and
        // this is a diagnostic rather than a ledger.
        if (byHost.size < MAX_HOSTS || byHost.containsKey(host)) {
            byHost.computeIfAbsent(host) { AtomicLong(0) }.addAndGet(bytes)
        }
    }

    internal fun countCall() {
        calls.incrementAndGet()
    }

    fun bytes(): Long = total.get()

    fun requests(): Long = calls.get()

    fun since(): Long = startedAt

    /** The [n] hosts that have cost the most, largest first. */
    fun worst(n: Int = 6): List<Pair<String, Long>> =
        byHost.entries
            .map { it.key to it.value.get() }
            .sortedByDescending { it.second }
            .take(n)

    fun reset() {
        total.set(0)
        calls.set(0)
        byHost.clear()
    }

    /** "4.2 MB", "860 kB", "512 B" — two significant figures is plenty. */
    fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format("%.0f kB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private const val MAX_HOSTS = 512
}
