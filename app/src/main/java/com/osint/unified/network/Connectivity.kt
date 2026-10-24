package com.osint.unified.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

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
         * True when the system has checked and the link does not reach
         * the internet — the shape of a captive portal. False both
         * when it is fine and when it has not been checked, so it is
         * a reason to warn and never a reason to stop.
         */
        val walledGarden: Boolean
    )

    fun snapshot(context: Context): State {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return State(online = true, metered = false, walledGarden = false)
        val active = cm.activeNetwork
        val caps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        if (caps == null) return State(online = false, metered = false, walledGarden = false)
        val hasTransport = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return State(
            // Having a link at all is what "online" means here. A link
            // the system has not validated yet is still worth trying.
            online = hasTransport,
            metered = metered,
            walledGarden = hasTransport && !validated
        )
    }

    /** The same, as it changes. Emits the current state immediately. */
    fun observe(context: Context): Flow<State> = callbackFlow {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            // No way to ask, so no claim is made: the app behaves as
            // it did before this existed.
            trySend(State(online = true, metered = false, walledGarden = false))
            awaitClose { }
            return@callbackFlow
        }
        trySend(snapshot(app))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(snapshot(app)) }
            override fun onLost(network: Network) { trySend(snapshot(app)) }
            override fun onUnavailable() { trySend(snapshot(app)) }
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) {
                trySend(snapshot(app))
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // registerNetworkCallback throws if too many callbacks are
        // registered process-wide, and a crash here would be a crash
        // for a status line.
        val registered = runCatching { cm.registerNetworkCallback(request, callback) }.isSuccess
        awaitClose {
            if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged().conflate()
}
