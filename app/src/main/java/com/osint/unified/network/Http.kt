package com.osint.unified.network

import android.content.Context
import com.osint.unified.data.Freshness
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp clients.
 *
 * Two things sit in front of every request. The first is politeness: these
 * are free public services, and twenty-eight feeds polling on their own
 * timers plus a dossier firing fifteen tools at once can put a dozen
 * simultaneous requests on one host without meaning to. The second is
 * taking "no" for an answer — a 429 used to be read as "the source found
 * nothing", so the app would report an absence it had been told nothing
 * about, and then ask again immediately.
 *
 * Caching is deliberately split. Public feeds get a disk cache, because
 * they are polled on a timer and most polls change nothing. Investigation
 * traffic does not: a cached response about a person is that person's data
 * written to disk by the network layer, which is not somewhere anyone would
 * think to go and clear.
 */
object Http {

    private const val UA = "OSINT-Unified/1.0 (research)"

    /** At once, to any one host. Enough to be quick, few enough that no
     *  free service sees this app as a burst of traffic. */
    private const val MAX_PER_HOST = 4

    /** How long a request will wait for its turn before giving up. The
     *  call timeout would cut it off anyway; this fails with a reason. */
    private const val QUEUE_WAIT_SECONDS = 20L

    /** A Retry-After this short is simply waited out, once. */
    private const val RETRY_INLINE_MS = 4_000L

    /** What a host that says 429 without a Retry-After gets. */
    private const val DEFAULT_COOLDOWN_MS = 30_000L

    /** How long a cooldown will be waited out rather than failed. */
    private const val MAX_WAIT_MS = 5_000L

    private const val FEED_CACHE_BYTES = 48L * 1024 * 1024

    /** Hosts tracked for concurrency at once. */
    private const val MAX_TRACKED_HOSTS = 4_096

    private val gates = ConcurrentHashMap<String, Semaphore>()
    private val cooldowns = ConcurrentHashMap<String, Long>()

    @Volatile private var feedCacheDir: File? = null

    /** Called once from the application, before anything makes a request. */
    fun init(context: Context) {
        if (feedCacheDir == null) {
            feedCacheDir = File(context.cacheDir, "feed-http")
        }
    }

    /**
     * One at a time per host, and a host that asks to be left alone is.
     *
     * An application interceptor rather than a network one, so it sees the
     * call once rather than once per redirect, and so a retry it performs
     * goes through the whole chain.
     */
    private object Politeness : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .apply {
                    if (chain.request().header("User-Agent") == null) header("User-Agent", UA)
                }
                .build()
            val host = request.url.host

            cooldowns[host]?.let { until ->
                val wait = until - System.currentTimeMillis()
                when {
                    wait <= 0 -> cooldowns.remove(host)
                    wait <= MAX_WAIT_MS -> {
                        sleep(wait)
                        cooldowns.remove(host)
                    }
                    else -> throw IOException(
                        "$host asked to be left alone for another ${wait / 1000}s " +
                            "(it answered 429). Not asking again yet."
                    )
                }
            }

            // The username sweep touches a couple of thousand hosts, so
            // this map is bounded: better to lose the bookkeeping for a
            // host than to keep a semaphore for every site on the internet.
            if (gates.size > MAX_TRACKED_HOSTS) gates.clear()
            // computeIfAbsent rather than getOrPut: two threads arriving
            // together would otherwise each make a semaphore, and the limit
            // this exists to impose would not be imposed.
            val gate = gates.computeIfAbsent(host) { Semaphore(MAX_PER_HOST, true) }
            if (!gate.tryAcquire(QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Too many requests already queued for $host.")
            }
            try {
                var response = chain.proceed(request)
                if (response.code == 429 || response.code == 503) {
                    val after = retryAfterMillis(response.header("Retry-After"))
                    cooldowns[host] = System.currentTimeMillis() + (after ?: DEFAULT_COOLDOWN_MS)
                    // Worth one wait when the host named a short one; a
                    // longer one belongs to the next poll, not to this
                    // request's deadline.
                    if (after != null && after in 1..RETRY_INLINE_MS) {
                        response.close()
                        sleep(after)
                        cooldowns.remove(host)
                        response = chain.proceed(request)
                    }
                }
                return response
            } finally {
                // Released before the body is read, so a slow download does
                // not hold a slot the whole time.
                gate.release()
            }
        }

        private fun sleep(millis: Long) {
            runCatching { Thread.sleep(millis) }
        }
    }

    /**
     * Retry-After in milliseconds, from either spelling.
     *
     * The header is either a number of seconds or an HTTP date, and
     * services use both. The date goes through the same lenient parser the
     * feeds use, because java.time's own RFC 1123 rejects a timestamp whose
     * weekday disagrees with its date and third-party servers get that
     * wrong. Anything over an hour is treated as an hour: a header saying
     * "come back next Tuesday" is not a reason to remember a cooldown past
     * the life of the process.
     */
    internal fun retryAfterMillis(header: String?): Long? {
        val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return (it * 1000L).coerceIn(0L, 3600_000L) }
        val at = Freshness.parse(raw) ?: return null
        return (at - System.currentTimeMillis()).coerceIn(0L, 3600_000L)
    }

    /** Tight timeouts so a slow endpoint fails fast instead of hanging the
     *  dossier — results should arrive quickly or not at all. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Politeness)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * For the public map feeds, which are polled on a timer.
     *
     * Most polls of an alert feed return the same bytes as the last one, and
     * the servers say so with an ETag. With a cache the conditional request
     * costs a few hundred bytes instead of the whole document; without one
     * every feed re-downloaded everything every time. Only feeds use this:
     * nothing here is about a person.
     */
    val feedClient: OkHttpClient by lazy {
        val store = feedCacheDir?.let {
            runCatching { Cache(it, FEED_CACHE_BYTES) }.getOrNull()
        }
        if (store == null) client else client.newBuilder().cache(store).build()
    }

    /**
     * For the few query APIs that are simply slow rather than large.
     *
     * crt.sh runs its search against a Postgres replica and routinely takes
     * half a minute; on the shared client that read as "no certificates
     * found" when the truth was that the request never finished. Long
     * enough to let it answer, short enough that a dossier does not sit
     * waiting on one source.
     */
    val slowApiClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(55, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * For the handful of sources that are whole catalogues rather than
     * queries: Celestrak's orbital elements are seven megabytes and the IRIS
     * station list is fourteen.
     *
     * On the shared client those were quietly exceeding the 25-second call
     * timeout on mobile and failing — which is why the satellite layer fell
     * back to the small 'visual' group and showed 155 objects instead of the
     * full catalogue, and why seismic stations came up empty. Both are
     * cached on disk, so this runs rarely.
     */
    val bulkClient: OkHttpClient by lazy {
        feedClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
