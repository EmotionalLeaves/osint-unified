package com.osint.unified.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp client. Tight timeouts so a slow endpoint fails fast instead
 *  of hanging the dossier — results should arrive quickly or not at all. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
