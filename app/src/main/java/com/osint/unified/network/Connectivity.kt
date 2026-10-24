package com.osint.unified.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Whether this phone has a network, and whether it is a metered one.
 *
 * The app declared `ACCESS_NETWORK_STATE` and never read it. With no
 * network it was honest about each source and incoherent overall:
 * thirty independently-worded failures on thirty pills, a dossier
 * where every row is its own excuse, a black map with no explanation
 * and, until this pass, a confident `SAT 0`. The one sentence that
 * explains all of it — there is no network — was never said, and the
 * permission to say it was already held.
 *
 * It is deliberately not used to gate anything. A capabilities check
 * that says "connected" is routinely wrong — a captive portal, a VPN
 * that has dropped, a hotel wifi that resolves everything to its own
 * login page — so refusing to try would turn a recoverable situation
 * into a dead app. What it is for is the sentence.
 */
object Connectivity {

    /** What the system currently believes, as a snapshot. */
    data class State(
        val online: Boolean,
        /** True when the system says this link costs money by the byte. */
        val metered: Boolean,
        /**
         * True when the system has said this link wants a sign-in.
         *
         * `!VALIDATED` was the first test and it was wrong in a way
         * that showed: that capability is absent both when
         * validation has FAILED and while it is still PENDING, and
         * validation takes one to five seconds after a network
         * appears. So walking out of Wi-Fi range onto cellular put a
         * red "this network is not reaching the internet" on two
         * screens for those seconds, every time — a false alarm
         * every few minutes on a commute, on a connection that was
         * working.
         *
         * `NET_CAPABILITY_CAPTIVE_PORTAL` is the capability that
         * means what this field claims, and the system only sets it
         * once it has actually probed. False both when the link is
         * fine and when nothing has been checked yet, which is what
         * the name promises.
         */
        val walledGarden: Boolean
    )

    /**
     * Null when the system cannot be asked, which is different from
     * either answer and must not be rendered as "no network".
     */
    fun snapshot(context: Context): State? {
        val cm = runCatching {
            context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return null
        // `activeNetwork` is transiently null during a default-network
        // switch — handing off from Wi-Fi to mobile — so a null here
        // is also "ask again in a moment", not "offline". The caller
        // debounces it.
        val active = runCatching { cm.activeNetwork }.getOrNull() ?: return UNKNOWN_OFFLINE
        val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
            ?: return UNKNOWN_OFFLINE
        val hasTransport = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return State(
            // Having a link at all is what "online" means here. A link
            // the system has not validated yet is still worth trying.
            online = hasTransport,
            metered = metered,
            walledGarden = caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
            )
        )
    }

    /** No usable link right now. Debounced before it is shown. */
    private val UNKNOWN_OFFLINE = State(online = false, metered = false, walledGarden = false)

    /** Said nothing, because nothing could be asked. */
    private val ASSUME_FINE = State(online = true, metered = false, walledGarden = false)

    /**
     * How long a reported outage has to last before it is shown.
     *
     * Every handover between networks passes through "no active
     * network" and, on some devices, through an unvalidated link, and
     * a banner that appears for a second on every one of those is
     * worse than no banner. A real outage lasts longer than this.
     */
    private const val SETTLE_MS = 2_500L

    /** The same, as it changes. Emits the current state immediately. */
    fun observe(context: Context): Flow<State> = callbackFlow {
        val app = context.applicationContext
        val cm = runCatching {
            app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()
        if (cm == null) {
            // No way to ask, so no claim is made: the app behaves as
            // it did before this existed.
            trySend(ASSUME_FINE)
            awaitClose { }
            return@callbackFlow
        }
        fun report() { trySend(snapshot(app) ?: ASSUME_FINE) }
        report()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report()
            override fun onLost(network: Network) = report()
            override fun onUnavailable() = report()
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) = report()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // registerNetworkCallback throws if too many callbacks are
        // registered process-wide, and a crash here would be a crash
        // for a status line.
        val registered = runCatching { cm.registerNetworkCallback(request, callback) }.isSuccess
        if (!registered) {
            // Nothing will ever arrive on this flow, and a status
            // line stuck on whatever the first snapshot said is worse
            // than no status line. Say nothing instead.
            trySend(ASSUME_FINE)
        }
        awaitClose {
            if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
        .distinctUntilChanged()
        .settleBadNews()
        .conflate()

    /**
     * Holds bad news back for [SETTLE_MS], and lets good news
     * through at once.
     *
     * A handover produces a short burst — lost, no active network,
     * a new unvalidated link, validated — and rendering each step
     * put a red banner on screen for a second at a time. Recovery is
     * never delayed: the moment a working link is reported it is
     * shown, which is the asymmetry a warning wants.
     */
    private fun Flow<State>.settleBadNews(): Flow<State> = channelFlow {
        var pending: Job? = null
        var shown = ASSUME_FINE
        collect { next ->
            pending?.cancel()
            pending = null
            val bad = !next.online || next.walledGarden
            if (!bad) {
                shown = next
                send(next)
            } else if (shown != next) {
                pending = launch {
                    delay(SETTLE_MS)
                    shown = next
                    send(next)
                }
            }
        }
    }
}
