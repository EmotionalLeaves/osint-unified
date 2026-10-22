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
    // Baltic only. The SEA pill says so, because a layer labelled only "SEA"
    // reads as global coverage and the absence of a ship anywhere else then
    // looks like information.
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

    /** Any GeoJSON points URL the user wants plotted as a custom globe layer. */
    var overlayUrl: String
        get() = prefs.getString(KEY_OVERLAY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OVERLAY, value.trim()).apply()

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
        const val DEFAULT_AIS = "https://meri.digitraffic.fi/api/ais/v1/locations"
    }
}
