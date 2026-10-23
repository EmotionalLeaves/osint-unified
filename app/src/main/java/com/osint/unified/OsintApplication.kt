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
import com.osint.unified.network.Http

/**
 * Minimal manual DI container. Hilt can replace this later; for now a
 * hand-rolled container keeps the build simple and the wiring explicit.
 */
class OsintApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before any request: the feed cache needs somewhere to live.
        Http.init(this)
        // Applied before anything can make a request, so the first poll of
        // the first feed is already going the way it is supposed to.
        if (settings.proxyEnabled) Http.useProxy(settings.proxyHost, settings.proxyPort)
        configureMapTileCache()
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
            direct = DirectToolExecutor(this, UsernameChecker(this), exifReader, settings)
        )
    }

    val repository: InvestigationRepository by lazy {
        InvestigationRepository(database, executorFactory)
    }

    /**
     * Where map tiles are kept.
     *
     * osmdroid was only given a user agent. Without a configured base path
     * its cache defaults to legacy external storage, which apps cannot
     * write to from Android 10 onwards — so every pan re-downloaded the
     * imagery it had just fetched. A private directory with a size cap
     * fixes the bandwidth and keeps the tiles out of shared storage, which
     * on this app is also a record of where someone has been looking.
     */
    private fun configureMapTileCache() {
        runCatching {
            val config = org.osmdroid.config.Configuration.getInstance()
            config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
            config.userAgentValue = "OSINT-Unified/${BuildConfig.VERSION_NAME}"
            val base = java.io.File(cacheDir, "osmdroid").apply { mkdirs() }
            config.osmdroidBasePath = base
            config.osmdroidTileCache = java.io.File(base, "tiles").apply { mkdirs() }
            config.tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            config.tileFileSystemCacheTrimBytes = 240L * 1024 * 1024
        }
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
