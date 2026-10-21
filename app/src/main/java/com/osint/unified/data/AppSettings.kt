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

    var aisUrl: String
        get() = prefs.getString(KEY_AIS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AIS, value.trim()).apply()

    private companion object {
        const val KEY_BACKEND = "backend_base_url"
        const val KEY_TAR1090 = "tar1090_url"
        const val KEY_AIS = "ais_url"
    }
}
