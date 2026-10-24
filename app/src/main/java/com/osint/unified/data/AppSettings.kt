package com.osint.unified.data

import android.content.Context

/**
 * Simple SharedPreferences-backed settings. Holds the deployed cloud backend
 * URL (for CLOUD tools) and the tar1090 feed URL (for live aircraft).
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("osint_settings", Context.MODE_PRIVATE)

    var backendBaseUrl: String
        get() = prefs.getString(KEY_BACKEND, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BACKEND, value.trim()).apply()

    var tar1090Url: String
        get() = prefs.getString(KEY_TAR1090, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TAR1090, value.trim()).apply()

    // Digitraffic's open AIS: keyless and real, but Finnish waters and the
    /**
     * Every keyless live AIS feed found, merged.
     *
     * There is no open network publishing AIS worldwide without an
     * account — every aggregator that carries the whole sea wants a
     * key. What does exist is national authorities publishing their own
     * waters, and those can be added up. Fintraffic covers the Baltic
     * and the Norwegian Coastal Administration covers the Norwegian
     * Sea, the North Sea and the Barents, which between them is most of
     * northern Europe's water rather than one corner of it.
     *
     * The pill names the real extent, because a layer labelled only
     * "SEA" reads as global coverage and the absence of a ship anywhere
     * else then looks like information. Anyone who finds another open
     * feed can paste it in and it is merged with these.
     */
    var aisUrl: String
        get() = prefs.getString(KEY_AIS, DEFAULT_AIS).orEmpty()
        set(value) = prefs.edit().putString(KEY_AIS, value.trim()).apply()

    /**
     * Whether a search may connect to the target's own infrastructure.
     *
     * Off by default, and that default is the point. Passive sources ask
     * third parties what they already know; active ones put the
     * investigator's address in the target's logs. Which of those happens
     * when you press search should be a decision, not a surprise.
     */
    var activeReconEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVE, value).apply()

    /**
     * Whether the app may chase the identifiers it finds.
     *
     * The second pass reaches infrastructure the person never named, on
     * the strength of a string scraped out of someone else's output. It is
     * useful, and it should be chosen.
     */
    var followUpEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOLLOWUP, false)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOWUP, value).apply()

    /** Whether the terms have been shown. Shown once, not on every launch. */
    var termsAccepted: Boolean
        get() = prefs.getBoolean(KEY_TERMS, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMS, value).apply()

    /** Bearer token for your own backend. Without it the backend refuses
     *  every request, which is the point: an open scanning service on the
     *  public internet is someone else's abuse report with your name on it. */
    var backendToken: String
        get() = prefs.getString(KEY_BACKEND_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BACKEND_TOKEN, value.trim()).apply()

    /** Contact address sent to SEC EDGAR, which requires one. Empty means
     *  the EDGAR tool stays off rather than borrowing someone else's. */
    var secContactEmail: String
        get() = prefs.getString(KEY_SEC_EMAIL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SEC_EMAIL, value.trim()).apply()

    /**
     * Whether every request goes through a local HTTP proxy, and where.
     *
     * The default is Orbot's HTTP port, so turning this on sends the app's
     * traffic through Tor. An HTTP proxy rather than Orbot's SOCKS port on
     * purpose: against an HTTP proxy the client sends CONNECT with the
     * hostname and never resolves it, while Java's SOCKS support resolves
     * the name on the device first and announces every target to whatever
     * DNS the network handed out — which is most of what the proxy was
     * for.
     *
     * It does not cover the bundled Go binaries: those are separate
     * processes with their own networking, and saying otherwise would be
     * the most dangerous kind of wrong. The setting says so.
     */
    var proxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_ON, value).apply()

    var proxyHost: String
        get() = prefs.getString(KEY_PROXY_HOST, DEFAULT_PROXY_HOST).orEmpty()
        set(value) = prefs.edit().putString(KEY_PROXY_HOST, value.trim()).apply()

    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, DEFAULT_PROXY_PORT)
        set(value) = prefs.edit().putInt(KEY_PROXY_PORT, value).apply()

    /**
     * Whether the app asks for a fingerprint or the device PIN on opening.
     *
     * Off unless chosen, and only offered where the device can actually
     * do it: an app that locks itself on a phone with no way to unlock it
     * is a bricked app.
     */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    /** Any GeoJSON points URL the user wants plotted as a custom globe layer. */
    var overlayUrl: String
        get() = prefs.getString(KEY_OVERLAY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OVERLAY, value.trim()).apply()

    /**
     * What a search of each observable type has cost, on this phone.
     *
     * Mobile data is the budget that matters here, and the app knew
     * what it had spent only in a diagnostics panel, after the fact,
     * for the whole process. A dossier that fires forty tools and then
     * eleven pivots at six tools each never said what it would cost
     * before, or what it had cost after — the header printed a tool
     * count and nothing about bytes.
     *
     * A running mean per type, not per tool: tools run concurrently,
     * so a delta measured around one of them includes the other
     * thirty-nine. A whole search is the unit that can honestly be
     * measured, and the type is what decides how big it is.
     */
    fun searchCost(type: String): Pair<Long, Int>? = costs()[type.lowercase()]

    fun recordSearchCost(type: String, bytes: Long) {
        if (bytes <= 0) return
        val key = type.lowercase()
        val all = HashMap(costs())
        val previous = all[key]
        val samples = minOf((previous?.second ?: 0) + 1, COST_SAMPLE_CAP)
        val mean = previous?.first ?: 0L
        all[key] = ((mean * (samples - 1) + bytes) / samples) to samples
        val encoded = all.entries.take(32).joinToString(",") {
            "${it.key}=${it.value.first}:${it.value.second}"
        }
        prefs.edit().putString(KEY_SEARCH_COSTS, encoded).apply()
    }

    private fun costs(): Map<String, Pair<Long, Int>> =
        prefs.getString(KEY_SEARCH_COSTS, "").orEmpty()
            .split(',')
            .mapNotNull { entry ->
                val name = entry.substringBefore('=', "").trim()
                val mean = entry.substringAfter('=', "").substringBefore(':').toLongOrNull()
                val n = entry.substringAfterLast(':', "").toIntOrNull()
                if (name.isBlank() || mean == null || n == null || n <= 0) null
                else name to (mean to n)
            }
            .toMap()

    private companion object {
        const val KEY_BACKEND = "backend_base_url"
        const val KEY_TAR1090 = "tar1090_url"
        const val KEY_AIS = "ais_url"
        const val KEY_OVERLAY = "overlay_url"
        const val KEY_ACTIVE = "active_recon_enabled"
        const val KEY_FOLLOWUP = "follow_up_enabled"
        const val KEY_SEC_EMAIL = "sec_contact_email"
        const val KEY_BACKEND_TOKEN = "backend_token"
        const val KEY_TERMS = "terms_accepted"
        const val KEY_PROXY_ON = "proxy_enabled"
        const val KEY_PROXY_HOST = "proxy_host"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val DEFAULT_AIS =
            "https://meri.digitraffic.fi/api/ais/v1/locations\n" +
                "https://kystdatahuset.no/ws/api/ais/realtime/geojson"

        /** Orbot's HTTP port, which is what almost anyone turning this on
         *  is running. Its SOCKS port is 9050 and is the wrong one here. */
        const val DEFAULT_PROXY_HOST = "127.0.0.1"
        const val DEFAULT_PROXY_PORT = 8118
    }
}
