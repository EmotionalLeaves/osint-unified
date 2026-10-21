package com.osint.unified.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp client. Generous timeouts to accommodate slow cloud scans. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .callTimeout(160, TimeUnit.SECONDS)
            .build()
    }
}
