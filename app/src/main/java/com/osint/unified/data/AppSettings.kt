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

    // Defaults to Finland's Digitraffic open AIS (keyless, real live vessels in
    // the Baltic) so the vessel layer shows real ships out of the box. There is
    // no free global AIS feed; replace with your own for other regions.
    var aisUrl: String
        get() = prefs.getString(KEY_AIS, DEFAULT_AIS).orEmpty()
        set(value) = prefs.edit().putString(KEY_AIS, value.trim()).apply()

    /** Any GeoJSON points URL the user wants plotted as a custom globe layer. */
    var overlayUrl: String
        get() = prefs.getString(KEY_OVERLAY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OVERLAY, value.trim()).apply()

    private companion object {
        const val KEY_BACKEND = "backend_base_url"
        const val KEY_TAR1090 = "tar1090_url"
        const val KEY_AIS = "ais_url"
        const val KEY_OVERLAY = "overlay_url"
        const val DEFAULT_AIS = "https://meri.digitraffic.fi/api/ais/v1/locations"
    }
}
