package com.osint.unified

import android.app.Application
import com.osint.unified.data.AircraftDataSource
import com.osint.unified.data.AisDataSource
import com.osint.unified.data.AppSettings
import com.osint.unified.data.EarthquakeDataSource
import com.osint.unified.data.NaturalEventsDataSource
import com.osint.unified.data.OverlayDataSource
import com.osint.unified.data.ExifReader
import com.osint.unified.data.InvestigationExporter
import com.osint.unified.data.InvestigationRepository
import com.osint.unified.data.db.AppDatabase
import com.osint.unified.domain.ObservableRouter
import com.osint.unified.domain.executor.CloudToolExecutor
import com.osint.unified.domain.executor.DirectToolExecutor
import com.osint.unified.domain.executor.ExecutorFactory
import com.osint.unified.domain.executor.LocalToolExecutor
import com.osint.unified.domain.executor.UsernameChecker

/**
 * Minimal manual DI container. Hilt can replace this later; for now a
 * hand-rolled container keeps the build simple and the wiring explicit.
 */
class OsintApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val router: ObservableRouter by lazy { ObservableRouter() }
    val settings: AppSettings by lazy { AppSettings(this) }
    val aircraftDataSource: AircraftDataSource by lazy { AircraftDataSource(settings) }
    val aisDataSource: AisDataSource by lazy { AisDataSource(settings) }
    val earthquakeDataSource: EarthquakeDataSource by lazy { EarthquakeDataSource() }
    val naturalEventsDataSource: NaturalEventsDataSource by lazy { NaturalEventsDataSource() }
    val overlayDataSource: OverlayDataSource by lazy { OverlayDataSource(settings) }

    private val executorFactory: ExecutorFactory by lazy {
        ExecutorFactory(
            cloud = CloudToolExecutor(settings),
            local = LocalToolExecutor(applicationInfo.nativeLibraryDir, filesDir.absolutePath),
            direct = DirectToolExecutor(UsernameChecker(this), exifReader)
        )
    }

    val repository: InvestigationRepository by lazy {
        InvestigationRepository(database, executorFactory)
    }

    val exporter: InvestigationExporter by lazy { InvestigationExporter(this) }
    val exifReader: ExifReader by lazy { ExifReader(this) }
}
