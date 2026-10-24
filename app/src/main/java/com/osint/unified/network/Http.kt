package com.osint.unified.network

import android.content.Context
import com.osint.unified.data.Freshness
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
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

    /**
     * What this app calls itself to a server it has never met.
     *
     * Not "OSINT-Unified". Several of these requests go to hosts the
     * target controls — fetching an image for its metadata, reading a
     * profile page — and a user agent announcing an OSINT tool writes
     * "somebody is investigating you" into their access log. A plain
     * mobile browser string says nothing that the request did not
     * already say.
     *
     * Where a service asks to be identified — the ones whose terms
     * require a contact or a project name — the data source sets its own
     * header and this is not applied.
     */
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * The same string, for a caller that sets the header itself.
     *
     * The interceptor below only fills the header in when nobody else
     * has, so a data source that builds its own request has to be
     * able to ask for the default rather than invent one — which is
     * how two executor classes came to announce themselves as an
     * OSINT tool to every host they touched.
     */
    val defaultUserAgent: String get() = UA

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

    /**
     * Hosts that publish a minimum interval between requests, and it.
     *
     * A 429 after the fact is the expensive way to learn this. GDELT
     * writes it on the refusal itself — "please limit requests to one
     * every 5 seconds" — so there is no reason to find out by being
     * refused, and a layer that comes back empty because two polls
     * happened to coincide is a layer that lies about the world rather
     * than about the feed.
     */
    private val PACED = mapOf("api.gdeltproject.org" to 6_000L)

    private val pacing = ConcurrentHashMap<String, Any>()
    private val lastCall = ConcurrentHashMap<String, Long>()

    @Volatile private var feedCacheDir: File? = null

    /** Where to send everything, or null for straight out of the phone. */
    @Volatile private var relay: InetSocketAddress? = null

    /** Whatever the platform had before this app interfered. */
    private val systemSelector: ProxySelector? = runCatching { ProxySelector.getDefault() }.getOrNull()

    /** Called once from the application, before anything makes a request. */
    fun init(context: Context) {
        if (feedCacheDir == null) {
            feedCacheDir = File(context.cacheDir, "feed-http")
        }
    }

    /**
     * Route everything through a local HTTP proxy — Orbot, usually — or
     * stop doing so.
     *
     * Applied through a selector consulted on each connection rather than
     * baked into the clients, so turning it on takes effect on the next
     * request instead of the next launch. That matters: a privacy setting
     * that silently waits for a restart is a setting that is off when its
     * owner believes it is on.
     *
     * An HTTP proxy and not a SOCKS one, deliberately. Against an HTTP
     * proxy the client sends CONNECT carrying the hostname and never
     * resolves it; Java's SOCKS support resolves the name on the device
     * first, which hands every target to whatever DNS the network supplied
     * and gives away exactly what the proxy was meant to hide.
     */
    fun useProxy(host: String?, port: Int) {
        // Unresolved, because resolving is a network call.
        //
        // InetSocketAddress(host, port) performs a DNS lookup on the
        // calling thread, and the settings screen calls this from a
        // text field's onValueChange — the main thread. On Android
        // that throws NetworkOnMainThreadException, which the
        // runCatching swallowed, leaving `relay` null: the switch read
        // ON, the setting was persisted as enabled, the system
        // selector was restored, and every request went direct. A
        // proxy that silently is not a proxy is the worst possible
        // outcome for the person who turned it on.
        //
        // createUnresolved never touches the network. OkHttp and the
        // JVM's own selector both resolve a proxy address when they
        // connect, which is off the main thread by then and is also
        // the right time to do it — a proxy whose name resolves
        // differently later is a proxy that still works.
        relay = if (host.isNullOrBlank() || port !in 1..65535) null
        else runCatching { InetSocketAddress.createUnresolved(host.trim(), port) }.getOrNull()
        // The map's tile loader is not OkHttp — osmdroid uses
        // HttpURLConnection, which consults the JVM's default selector. A
        // proxy that covered everything except the thing drawing the
        // screen would be worse than none, because it would look like it
        // was working.
        runCatching {
            ProxySelector.setDefault(if (relay == null) systemSelector else Relay)
        }
    }

    /** Whether traffic is currently going through a proxy, for the UI. */
    fun proxyDescription(): String? = relay?.let { "${it.hostString}:${it.port}" }

    private object Relay : ProxySelector() {
        override fun select(uri: URI?): MutableList<Proxy> {
            val address = relay ?: return mutableListOf(Proxy.NO_PROXY)
            return mutableListOf(Proxy(Proxy.Type.HTTP, address))
        }

        // A proxy that is not there should fail the request, not quietly
        // fall back to going direct — which would be the one outcome the
        // person turning this on was trying to avoid.
        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
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

            // Hosts that stated an interval are held to it, one caller at
            // a time, so two polls arriving together queue instead of
            // racing into a refusal.
            PACED[host]?.let { gap ->
                synchronized(pacing.computeIfAbsent(host) { Any() }) {
                    val wait = (lastCall[host] ?: 0L) + gap - System.currentTimeMillis()
                    if (wait > 0) sleep(wait.coerceAtMost(gap))
                    lastCall[host] = System.currentTimeMillis()
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
    /**
     * Counts what actually goes over the wire.
     *
     * A *network* interceptor on purpose. Application interceptors do
     * not run for a response the disk cache answered, which would be
     * right, but they also sit above the bridge that un-gzips the body
     * — so they would count the inflated size and report two or three
     * times the data that was really spent. Down here the bytes are
     * the bytes.
     */
    private object Meter : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            val response = chain.proceed(chain.request())
            DataMeter.countCall()
            val body = response.body ?: return response
            val counted = object : ResponseBody() {
                private val source: BufferedSource = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val read = super.read(sink, byteCount)
                        if (read > 0) DataMeter.add(host, read)
                        return read
                    }
                }.buffer()

                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                override fun source(): BufferedSource = source
            }
            return response.newBuilder().body(counted).build()
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Politeness)
            .addNetworkInterceptor(Meter)
            .proxySelector(Relay)
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
