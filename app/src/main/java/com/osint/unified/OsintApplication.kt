package com.osint.unified

import android.app.Application
import java.io.File
import com.osint.unified.data.AircraftDataSource
import com.osint.unified.data.AirQualityDataSource
import com.osint.unified.data.AisDataSource
import com.osint.unified.data.AppSettings
import com.osint.unified.data.AuroraDataSource
import com.osint.unified.data.BuoyDataSource
import com.osint.unified.data.ConflictDataSource
import com.osint.unified.data.SeismicStationDataSource
import com.osint.unified.data.EarthquakeDataSource
import com.osint.unified.data.FiresDataSource
import com.osint.unified.data.GdacsDataSource
import com.osint.unified.data.MetarDataSource
import com.osint.unified.data.MilAircraftDataSource
import com.osint.unified.data.NaturalEventsDataSource
import com.osint.unified.data.NewsDataSource
import com.osint.unified.data.OverlayDataSource
import com.osint.unified.data.SatellitesDataSource
import com.osint.unified.data.VolcanoDataSource
import com.osint.unified.data.WeatherDataSource
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
    val weatherDataSource: WeatherDataSource by lazy { WeatherDataSource() }
    val firesDataSource: FiresDataSource by lazy { FiresDataSource() }
    val satellitesDataSource: SatellitesDataSource by lazy { SatellitesDataSource() }
    val newsDataSource: NewsDataSource by lazy { NewsDataSource() }
    val gdacsDataSource: GdacsDataSource by lazy { GdacsDataSource() }
    val volcanoDataSource: VolcanoDataSource by lazy { VolcanoDataSource() }
    val auroraDataSource: AuroraDataSource by lazy { AuroraDataSource() }
    val buoyDataSource: BuoyDataSource by lazy { BuoyDataSource() }
    val metarDataSource: MetarDataSource by lazy { MetarDataSource() }
    val milAircraftDataSource: MilAircraftDataSource by lazy { MilAircraftDataSource() }
    val airQualityDataSource: AirQualityDataSource by lazy { AirQualityDataSource() }
    val seismicStationDataSource: SeismicStationDataSource by lazy { SeismicStationDataSource() }
    val conflictDataSource: ConflictDataSource by lazy { ConflictDataSource() }

    val executorFactory: ExecutorFactory by lazy {
        ExecutorFactory(
            cloud = CloudToolExecutor(settings),
            local = LocalToolExecutor(applicationInfo.nativeLibraryDir, filesDir.absolutePath),
            direct = DirectToolExecutor(this, UsernameChecker(this), exifReader)
        )
    }

    val repository: InvestigationRepository by lazy {
        InvestigationRepository(database, executorFactory)
    }

    val exporter: InvestigationExporter by lazy { InvestigationExporter(this) }
    val exifReader: ExifReader by lazy { ExifReader(this) }

    /** Which LOCAL tools actually shipped a binary (jniLibs/lib<id>.so) in this
     *  build. Used to hide local tools that would just error. */
    val availableLocalTools: Set<String> by lazy {
        File(applicationInfo.nativeLibraryDir).listFiles()
            ?.mapNotNull { f ->
                f.name.takeIf { it.startsWith("lib") && it.endsWith(".so") }
                    ?.removePrefix("lib")?.removeSuffix(".so")
            }?.toSet() ?: emptySet()
    }
}
