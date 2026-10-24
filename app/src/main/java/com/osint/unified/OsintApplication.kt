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
import com.osint.unified.data.LaunchDataSource
import com.osint.unified.data.OutageDataSource
import com.osint.unified.data.SeaLevelDataSource
import com.osint.unified.data.SitesDataSource
import com.osint.unified.data.WorldWarningsDataSource
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
        if (settings.proxyEnabled) {
            Http.useProxy(settings.proxyHost, settings.proxyPort)
            com.osint.unified.ui.common.PrivateBrowserHost
                .useProxy(settings.proxyHost, settings.proxyPort)
        }
        // Idempotent and cheap: with no watched targets the worker returns
        // immediately, so there is nothing to turn on or off.
        com.osint.unified.work.WatchWorker.schedule(this)
        // Opening the database can mean unsealing a key, running a
        // migration and, after an upgrade, copying every case into an
        // encrypted file. None of that belongs on the thread that draws
        // the first frame, so it is started here and is usually finished
        // before anything asks for it.
        Thread({
            runCatching { database }
            // Rows left "running" by a process death will never be
            // finished by anything. Without this they spin in the case
            // list for the life of the database — the function to clear
            // them existed and was called from nowhere.
            runCatching {
                kotlinx.coroutines.runBlocking { repository.markInterruptedRuns() }
            }
            // A dossier left in the cache by an export that was never
            // followed by another one. The purge used to run only on
            // the way into a new export.
            runCatching {
                kotlinx.coroutines.runBlocking { exporter.purgeStaleExports() }
            }
            _ready.value = true
        }, "db-warmup").start()
    }

    private val _ready = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Whether the database is open and the screen may ask for it.
     *
     * `database` is `by lazy`, which is SYNCHRONIZED: whichever thread
     * reaches it first does the keystore unseal, the version repair —
     * which opens the file up to seven times probing for its number —
     * and, on the first launch after the encryption upgrade, a whole
     * sqlcipher_export of every case, while holding the monitor. The
     * warm-up thread above was started to be that thread, and it is a
     * race, not a guarantee: composition resolves the view-model
     * factories, and onStart touched the view model earlier still, so
     * on a cold start the main thread usually got there first or
     * blocked on the monitor for the whole of it. An ANR on the first
     * launch after the upgrade, worst for whoever had the most cases.
     *
     * So the screen waits on this instead of racing it.
     */
    val ready: kotlinx.coroutines.flow.StateFlow<Boolean> = _ready

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
    val firesDataSource: FiresDataSource by lazy { FiresDataSource(diskCache) }
    val satellitesDataSource: SatellitesDataSource by lazy { SatellitesDataSource(diskCache) }
    val newsDataSource: NewsDataSource by lazy { NewsDataSource() }
    val gdacsDataSource: GdacsDataSource by lazy { GdacsDataSource() }
    val volcanoDataSource: VolcanoDataSource by lazy { VolcanoDataSource() }

    /** The global tide-gauge network, which is an observation rather
     *  than a warning. */
    val seaLevelDataSource: SeaLevelDataSource by lazy { SeaLevelDataSource(diskCache) }

    /** Countries whose connectivity has fallen over. */
    val outageDataSource: OutageDataSource by lazy { OutageDataSource(this) }

    /** Every port in the world, bundled, so it works with the network off. */
    val portDataSource: BundledPointsDataSource by lazy {
        BundledPointsDataSource.ports(this)
    }

    /** Border crossings, missions, prisons and substations around the view. */
    val sitesDataSource: SitesDataSource by lazy { SitesDataSource() }

    /** What is going to space in the next three weeks, and from where. */
    val launchDataSource: LaunchDataSource by lazy { LaunchDataSource(diskCache) }

    /** Every official severe-weather warning on Earth, not only the US ones. */
    val worldWarningsDataSource: WorldWarningsDataSource by lazy {
        WorldWarningsDataSource(diskCache)
    }
    val auroraDataSource: AuroraDataSource by lazy { AuroraDataSource() }
    val buoyDataSource: BuoyDataSource by lazy { BuoyDataSource() }
    val metarDataSource: MetarDataSource by lazy { MetarDataSource() }
    val milAircraftDataSource: MilAircraftDataSource by lazy { MilAircraftDataSource() }
    val airQualityDataSource: AirQualityDataSource by lazy { AirQualityDataSource() }
    val seismicStationDataSource: SeismicStationDataSource by lazy { SeismicStationDataSource(diskCache) }
    val conflictDataSource: ConflictDataSource by lazy { ConflictDataSource(this) }
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
            // The same camera inventory the map layer uses.
            //
            // DirectToolExecutor defaults this parameter to a fresh
            // CctvDataSource, and nobody overrode it, so the app held
            // two: one for the CAMERAS layer and one for the camera
            // tool, each with its own six-hour cache and each pulling
            // nine Caltrans district inventories in full. The
            // constructor already says "held, not rebuilt"; it was
            // being rebuilt here.
            direct = DirectToolExecutor(
                this, UsernameChecker(this), exifReader, settings,
                cctv = cctvDataSource
            )
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
