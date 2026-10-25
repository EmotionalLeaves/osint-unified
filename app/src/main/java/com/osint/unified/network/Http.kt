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
/**
 * A host is in cooldown after refusing, and this request was not sent.
 *
 * Its own type so a source that knows why its host refuses can still
 * say so. The first refusal reached the source as a 429 and got its
 * named explanation; every poll after that was stopped here, in the
 * interceptor, and got this class's generic sentence instead — so the
 * explanation was on screen for one poll and then replaced.
 */
class RateLimited(val host: String, val waitSeconds: Long) : IOException(
    "$host asked to be left alone for another ${waitSeconds}s " +
        "(it answered 429). Not asking again yet."
)

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

    /**
     * Map-tile servers, which are built to be asked for dozens of tiles at
     * once — a browser map does exactly that. Four at a time made the
     * globe fill in a row at a time.
     */
    private val TILE_HOSTS = mapOf(
        "server.arcgisonline.com" to 8,
        "services.arcgisonline.com" to 8,
        "gibs.earthdata.nasa.gov" to 8,
        "tiles.openfreemap.org" to 6
    )

    /** How long a request will wait for its turn before giving up. The
     *  call timeout would cut it off anyway; this fails with a reason. */
    private const val QUEUE_WAIT_SECONDS = 20L

    /** A Retry-After this short is simply waited out, once. */
    private const val RETRY_INLINE_MS = 4_000L

    /** What a host that says 429 without a Retry-After gets. */
    private const val DEFAULT_COOLDOWN_MS = 30_000L

    /** Thirty seconds doubled five times is sixteen minutes. */
    private const val MAX_BACKOFF_DOUBLINGS = 5

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
    private val PACED = mapOf(
        "api.gdeltproject.org" to 6_000L,
        // Both ADS-B hosts, added after being measured rather than
        // guessed: six requests from one address, a second or two
        // apart, went 200, 200, 429, 429, 429, 429. The user agent
        // made no difference.
        //
        // adsb.fi is asked for two different things — the military
        // layer every twenty seconds and the general aircraft layer's
        // second provider every twenty-five — so the two drift in and
        // out of phase and collide on their own, with no help from
        // anybody else sharing the address. This is what the two
        // layers reported as "military does not work".
        "opendata.adsb.fi" to 8_000L,
        "api.adsb.lol" to 8_000L
    )

    private val pacing = ConcurrentHashMap<String, Any>()
    private val lastCall = ConcurrentHashMap<String, Long>()

    /**
     * How many times in a row a host has refused.
     *
     * The back-off was a flat thirty seconds, which is the right
     * answer to one unlucky collision and the wrong answer to a host
     * that is refusing everything. A layer polled every twenty
     * seconds against a host with nothing left to give spent the
     * session asking, being refused, waiting half a minute and
     * asking again — which is both why it never recovered and part
     * of why it was refused in the first place.
     *
     * Doubling instead, to a quarter of an hour. Cleared the moment
     * the host answers, so a single bad minute costs a minute and a
     * genuinely exhausted budget is left alone.
     */
    private val refusals = ConcurrentHashMap<String, Int>()

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
        // Set on the JVM's default selector as well as on the
        // clients. The tiles go through OkHttp now that the globe
        // fetches them itself, but anything in a library that
        // reaches for HttpURLConnection consults this instead — and
        // a proxy that covered everything except one thing would be
        // worse than none, because it would look like it was
        // working.
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

            awaitCooldown(host)

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
            // And the same for the cooldowns, which had no bound at all.
            //
            // An entry is only ever removed by asking that host again,
            // so a sweep across two thousand sites that refuses a few
            // hundred of them left those few hundred in the map for
            // the life of the process, whether or not anything ever
            // went back. Expired ones go first, because they are the
            // ones that no longer mean anything; only if that is not
            // enough does the rest go, and losing a live cooldown
            // costs one 429, not a wrong answer.
            if (cooldowns.size > MAX_TRACKED_HOSTS) {
                val now = System.currentTimeMillis()
                cooldowns.entries.removeAll { it.value <= now }
                if (cooldowns.size > MAX_TRACKED_HOSTS) cooldowns.clear()
            }
            // computeIfAbsent rather than getOrPut: two threads arriving
            // together would otherwise each make a semaphore, and the limit
            // this exists to impose would not be imposed.
            val gate = gates.computeIfAbsent(host) { Semaphore(TILE_HOSTS[host] ?: MAX_PER_HOST, true) }
            if (!gate.tryAcquire(QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("Too many requests already queued for $host.")
            }
            try {
                // And again, now that this caller has waited its turn.
                //
                // The check above ran before the pacing wait and the
                // queue, which can be several seconds. If the caller
                // ahead was refused in that time, this one went straight
                // at a host that had just said stop — collected a second
                // refusal for the same collision, and doubled the
                // back-off twice for it.
                awaitCooldown(host)
                var response = chain.proceed(request)
                if (response.isSuccessful) refusals.remove(host)
                if (response.code == 429 || response.code == 503) {
                    val after = retryAfterMillis(response.header("Retry-After"))
                    cooldowns[host] = System.currentTimeMillis() + (after ?: backoffFor(host))
                    // Worth one wait when the host named a short one; a
                    // longer one belongs to the next poll, not to this
                    // request's deadline.
                    if (after != null && after in 1..RETRY_INLINE_MS) {
                        response.close()
                        sleep(after)
                        cooldowns.remove(host)
                        response = chain.proceed(request)
                        // And the retry's answer is an answer too.
                        //
                        // The cooldown was cleared above and nothing
                        // put it back, so a host that had now refused
                        // twice in a row was left with no cooldown at
                        // all and the next request went straight at
                        // it — the exact opposite of what this
                        // interceptor is for, against precisely the
                        // services this file's header says to be
                        // polite to.
                        if (response.isSuccessful) refusals.remove(host)
                        if (response.code == 429 || response.code == 503) {
                            val again = retryAfterMillis(response.header("Retry-After"))
                            cooldowns[host] = System.currentTimeMillis() +
                                (again ?: backoffFor(host))
                        }
                    }
                }
                return response
            } finally {
                // Released before the body is read, so a slow download does
                // not hold a slot the whole time.
                gate.release()
            }
        }

        /**
         * Waits out a short cooldown on [host], or refuses for a long one.
         */
        private fun awaitCooldown(host: String) {
            val until = cooldowns[host] ?: return
            val wait = until - System.currentTimeMillis()
            when {
                wait <= 0 -> cooldowns.remove(host, until)
                wait <= MAX_WAIT_MS -> {
                    sleep(wait)
                    cooldowns.remove(host, until)
                }
                else -> throw RateLimited(host, wait / 1000)
            }
        }

        /**
         * The next wait for a host that just refused, doubling.
         *
         * Counted here rather than at the call site so that every
         * client shares one count per host: the aircraft layer and
         * the military layer are two pollers against one service,
         * and backing each of them off separately would leave the
         * service being asked twice as often as either of them
         * thinks.
         */
        private fun backoffFor(host: String): Long {
            if (refusals.size > MAX_TRACKED_HOSTS) refusals.clear()
            val n = refusals.merge(host, 1, Int::plus) ?: 1
            return DEFAULT_COOLDOWN_MS shl (n - 1).coerceIn(0, MAX_BACKOFF_DOUBLINGS)
        }

        private fun sleep(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (interrupted: InterruptedException) {
                // Re-asserted, not swallowed.
                //
                // runCatching caught this, cleared the thread's
                // interrupt flag as InterruptedException always does,
                // and carried on as though the wait had completed. The
                // thread had been asked to stop; everything after this
                // point ran anyway, and the next blocking call on it
                // had no idea it had been asked.
                Thread.currentThread().interrupt()
            }
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
    private class Meter(private val forTool: Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            val response = chain.proceed(chain.request())
            DataMeter.countCall()
            val body = response.body ?: return response
            val counted = object : ResponseBody() {
                private val source: BufferedSource = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val read = super.read(sink, byteCount)
                        if (read > 0) DataMeter.add(host, read, forTool)
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

    /**
     * Where network work that comes in bulk is run.
     *
     * Every request in this app is a blocking OkHttp call inside
     * `withContext(Dispatchers.IO)`, and the interceptor above may hold
     * that thread asleep for seconds at a time: up to five waiting out
     * a cooldown, up to six behind a paced host's monitor, up to four
     * on an inline retry. Dispatchers.IO's default parallelism on a
     * phone is 64 — and the username sweep alone runs a Semaphore(64)
     * of exactly these calls. A run of hosts in cooldown could
     * therefore put every thread in the shared pool to sleep at once,
     * and everything else that uses it queued behind them: the feed
     * pollers, and the export, whose button then appeared to do
     * nothing for several seconds.
     *
     * A bounded slice of the same pool keeps the rest of the app
     * moving — but it is a real bound, not a free one. These calls
     * block, so a waiting call holds a thread AND a slot, and the
     * sweep's own Semaphore(64) can never exceed this number. The
     * first version of this comment claimed otherwise and was wrong.
     *
     * So it is set above the sweep's own limit rather than below it.
     * The sweep is the one caller that can occupy everything, its
     * limit is 64, and leaving the shared pool a third of its threads
     * is what this is actually for: the export and the feed pollers
     * must not queue behind a username sweep. The per-host gate still
     * caps what any one site sees at four.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bulk: kotlinx.coroutines.CoroutineDispatcher by lazy {
        kotlinx.coroutines.Dispatchers.IO.limitedParallelism(BULK_PARALLELISM)
    }

    /**
     * Enough for the sweep's own limit, and not the whole pool.
     *
     * Dispatchers.IO is 64 on a phone. At 32 the sweep's Semaphore(64)
     * was dead and a two-thousand-site sweep took twice as long for a
     * bound that was never the point; at 64 it would be free to take
     * every thread there is. 48 leaves a quarter of the pool for the
     * export, the feed pollers and the watch worker.
     */
    private const val BULK_PARALLELISM = 48

    private fun base(): OkHttpClient.Builder = OkHttpClient.Builder()
        .addInterceptor(Politeness)
        .proxySelector(Relay)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    /**
     * For the tools, and counted as a search's own cost.
     *
     * The dossier's "this cost N" line was the difference of the
     * process-wide meter across the search, so a ten-second username
     * lookup run with the globe on screen was charged everything the
     * twenty-six feed pollers fetched in the same ten seconds. They
     * were already on different clients; only the counting pooled
     * them.
     */
    val client: OkHttpClient by lazy {
        base().addNetworkInterceptor(Meter(forTool = true)).build()
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
        val builder = base().addNetworkInterceptor(Meter(forTool = false))
        if (store == null) builder.build() else builder.cache(store).build()
    }

    /**
     * For the globe's imagery tiles.
     *
     * Through the same politeness as everything else, so a tile server
     * that answers 429 is backed off from — but that cooldown is per
     * host, and only the tile host's. Counted with the feeds, not with
     * any search.
     */
    val tileClient: OkHttpClient by lazy {
        // No HTTP cache: the globe keeps what it downloads itself, in app
        // storage, without an expiry (GlobeDisk). A second copy here would
        // only double the space.
        base().addNetworkInterceptor(Meter(forTool = false)).build()
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

    /**
     * How much of a response body an investigation tool will read.
     *
     * Every one of them called `body.string()`, which reads the
     * whole thing into memory however large it is. A certificate
     * transparency query for a large domain is tens of megabytes; a
     * URL that turns out to be a video is however long the video
     * is; and a tool pointed at the wrong thing downloads all of it
     * before discovering that. On a phone that is somebody's data
     * allowance, and on a big enough body it is the process.
     *
     * Four megabytes is far more than any of these endpoints
     * legitimately returns, and the connection is closed as soon as
     * it is reached, so the rest is never transferred.
     */
    const val MAX_BODY_BYTES = 4L * 1024 * 1024

    /**
     * Thrown when the cap, not the source, ended the response.
     *
     * The difference matters more than it looks. A caller that
     * parses JSON gets an incomplete document either way, but one
     * of those is the source failing and the other is this app
     * deciding not to read any more — and the certificate
     * transparency tool turned the second into "no certificates
     * found for this domain", which is a stated clean sheet
     * produced by its own byte limit. Nothing in this app is
     * allowed to do that.
     */
    class BodyTooLarge(limit: Long) : IOException(
        "the response was larger than ${describe(limit)} and this app stopped " +
            "reading it, so nothing can be concluded from what arrived"
    )

    private fun describe(limit: Long): String =
        if (limit >= 1024 * 1024) "${limit / (1024 * 1024)} MB" else "${limit / 1024} KB"

    /**
     * The body, up to a limit, and an error if there was more.
     *
     * For everything that parses what comes back. A truncated
     * document is not a short document: it is a question with no
     * answer, and it must not look like one.
     */
    fun ResponseBody.bodyText(limit: Long = MAX_BODY_BYTES): String {
        val (text, truncated) = read(limit)
        if (truncated) throw BodyTooLarge(limit)
        return text
    }

    /**
     * The body, up to a limit, saying so in its own last line.
     *
     * For the callers that show a prefix to a person rather than
     * parsing it — a preview of a page is still a preview when it
     * stops early.
     */
    fun ResponseBody.bodyPreview(limit: Long = MAX_BODY_BYTES): String {
        val (text, truncated) = read(limit)
        return if (truncated) {
            text + "\n…truncated at ${describe(limit)} by this app, not by the source."
        } else {
            text
        }
    }

    private fun ResponseBody.read(limit: Long): Pair<String, Boolean> {
        val src = source()
        // Fills the buffer to the limit, or to the end of the body
        // if that comes first. Either way nothing past this is
        // downloaded, because the connection closes with the
        // response. A multi-byte character split exactly at the
        // boundary comes back as a replacement character, which is
        // the last character of a body already being cut short.
        src.request(limit + 1)
        val held = src.buffer
        val take = minOf(held.size, limit)
        val text = held.readUtf8(take)
        // What is left in the buffer after consuming `take` is the
        // overflow, so this is exactly "there was more than limit".
        return text to (held.size > 0)
    }
}
