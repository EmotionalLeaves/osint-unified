package com.osint.unified

import android.app.Application
import java.io.File
import com.osint.unified.data.AircraftDataSource
import com.osint.unified.data.AirQualityDataSource
import com.osint.unified.data.AisDataSource
import com.osint.unified.data.AppSettings
import com.osint.unified.data.AuroraDataSource
import com.osint.unified.data.BundledPointsDataSource
import com.osint.unified.data.AirspaceDataSource
import com.osint.unified.data.BuoyDataSource
import com.osint.unified.data.AirportStatusDataSource
import com.osint.unified.data.CableLandingDataSource
import com.osint.unified.data.CycloneDataSource
import com.osint.unified.data.RadiationDataSource
import com.osint.unified.data.TsunamiDataSource
import com.osint.unified.data.CctvDataSource
import com.osint.unified.data.LaddAircraftDataSource
import com.osint.unified.data.SpaceWeatherDataSource
import com.osint.unified.data.ConflictDataSource
import com.osint.unified.data.DiskCache
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
import com.osint.unified.data.WatchRepository
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

    override fun onCreate() {
        super.onCreate()
        // Idempotent and cheap: with no watched targets the worker returns
        // immediately, so there is nothing to turn on or off.
        com.osint.unified.work.WatchWorker.schedule(this)
    }

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val diskCache: DiskCache by lazy { DiskCache(this) }
    val router: ObservableRouter by lazy { ObservableRouter() }
    val settings: AppSettings by lazy { AppSettings(this) }
    val aircraftDataSource: AircraftDataSource by lazy { AircraftDataSource(settings) }
    val aisDataSource: AisDataSource by lazy { AisDataSource(settings) }
    val earthquakeDataSource: EarthquakeDataSource by lazy { EarthquakeDataSource() }
    val naturalEventsDataSource: NaturalEventsDataSource by lazy { NaturalEventsDataSource() }
    val overlayDataSource: OverlayDataSource by lazy { OverlayDataSource(settings) }
    val weatherDataSource: WeatherDataSource by lazy { WeatherDataSource() }
    val firesDataSource: FiresDataSource by lazy { FiresDataSource() }
    val satellitesDataSource: SatellitesDataSource by lazy { SatellitesDataSource(diskCache) }
    val newsDataSource: NewsDataSource by lazy { NewsDataSource() }
    val gdacsDataSource: GdacsDataSource by lazy { GdacsDataSource() }
    val volcanoDataSource: VolcanoDataSource by lazy { VolcanoDataSource() }
    val auroraDataSource: AuroraDataSource by lazy { AuroraDataSource() }
    val buoyDataSource: BuoyDataSource by lazy { BuoyDataSource() }
    val metarDataSource: MetarDataSource by lazy { MetarDataSource() }
    val milAircraftDataSource: MilAircraftDataSource by lazy { MilAircraftDataSource() }
    val airQualityDataSource: AirQualityDataSource by lazy { AirQualityDataSource() }
    val seismicStationDataSource: SeismicStationDataSource by lazy { SeismicStationDataSource(diskCache) }
    val conflictDataSource: ConflictDataSource by lazy { ConflictDataSource() }
    val airportDataSource: BundledPointsDataSource by lazy { BundledPointsDataSource.airports(this) }
    val powerPlantDataSource: BundledPointsDataSource by lazy { BundledPointsDataSource.powerPlants(this) }
    val cctvDataSource: CctvDataSource by lazy { CctvDataSource() }
    val airspaceDataSource: AirspaceDataSource by lazy { AirspaceDataSource() }
    val spaceWeatherDataSource: SpaceWeatherDataSource by lazy { SpaceWeatherDataSource() }
    val laddAircraftDataSource: LaddAircraftDataSource by lazy { LaddAircraftDataSource() }
    val cableLandingDataSource: CableLandingDataSource by lazy { CableLandingDataSource() }
    val cycloneDataSource: CycloneDataSource by lazy { CycloneDataSource() }
    val tsunamiDataSource: TsunamiDataSource by lazy { TsunamiDataSource() }
    val radiationDataSource: RadiationDataSource by lazy { RadiationDataSource() }
    val airportStatusDataSource: AirportStatusDataSource by lazy {
        AirportStatusDataSource(this, airportDataSource)
    }

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

    val watchRepository: WatchRepository by lazy {
        WatchRepository(database, router, executorFactory)
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
