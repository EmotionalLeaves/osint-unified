package com.osint.unified.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp clients. */
object Http {

    /** Tight timeouts so a slow endpoint fails fast instead of hanging the
     *  dossier — results should arrive quickly or not at all. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
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
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
