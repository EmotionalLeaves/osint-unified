package com.osint.unified

import android.app.Application
import com.osint.unified.data.AircraftDataSource
import com.osint.unified.data.AppSettings
import com.osint.unified.data.InvestigationExporter
import com.osint.unified.data.InvestigationRepository
import com.osint.unified.data.db.AppDatabase
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.executor.CloudToolExecutor
import com.osint.unified.domain.executor.DirectToolExecutor
import com.osint.unified.domain.executor.ExecutorFactory
import com.osint.unified.domain.executor.LocalToolExecutor

/**
 * Minimal manual DI container. Hilt can replace this later; for now a
 * hand-rolled container keeps the build simple and the wiring explicit.
 */
class OsintApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val router: ObservableRouter by lazy { ObservableRouter() }
    val settings: AppSettings by lazy { AppSettings(this) }
    val aircraftDataSource: AircraftDataSource by lazy { AircraftDataSource(settings) }

    private val executorFactory: ExecutorFactory by lazy {
        ExecutorFactory(
            cloud = CloudToolExecutor(settings),
            local = LocalToolExecutor(applicationInfo.nativeLibraryDir),
            direct = DirectToolExecutor()
        )
    }

    val repository: InvestigationRepository by lazy {
        InvestigationRepository(database, executorFactory)
    }

    val exporter: InvestigationExporter by lazy { InvestigationExporter(this) }
}
