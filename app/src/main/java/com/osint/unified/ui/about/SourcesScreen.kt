package com.osint.unified.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osint.unified.ui.theme.Intel

/**
 * Who the data belongs to.
 *
 * Several of these require attribution as a condition of use — Esri's
 * imagery, the WRI power-plant database under CC BY, OpenStreetMap under
 * ODbL — and the app was using all of them with no credit anywhere. Beyond
 * the licence, an analyst is entitled to know whose numbers they are
 * repeating.
 */
private data class Source(val name: String, val usedFor: String, val licence: String)

/**
 * What each layer actually covers.
 *
 * A pill reading zero means one of two things and the map cannot tell
 * you which: nothing is happening, or nothing is being watched there.
 * The pills carry the worst cases in their names — SEA·BALTIC, ATC·US —
 * but a name has room for three words, and "why" does not fit in three
 * words. This is the long version, and where a layer is regional it says
 * what was looked for and what was found instead of implying nobody
 * tried.
 */
private data class Coverage(val layer: String, val extent: String, val why: String = "")

private val coverage = listOf(
    Coverage(
        "Aircraft, military aircraft",
        "worldwide",
        "Community ADS-B receivers, so coverage follows the people who " +
            "run them: thin over oceans and over parts of Africa and " +
            "central Asia where few are listening."
    ),
    Coverage(
        "Vessels",
        "the Baltic only",
        "No open network publishes live AIS worldwide without an " +
            "account. Every aggregator that carries the world wants a " +
            "key; the national feeds that need no key cover their own " +
            "waters. Fintraffic's covers the Baltic, so that is what " +
            "this covers, and the pill says so rather than implying the " +
            "rest of the sea is empty."
    ),
    Coverage("Earthquakes", "worldwide", "USGS, every event above the reporting threshold."),
    Coverage(
        "Wildfires",
        "worldwide",
        "NASA's satellite thermal detections — every one of them, not " +
            "the named events a newsroom has noticed."
    ),
    Coverage("Volcanoes", "worldwide", "Smithsonian Global Volcanism Program."),
    Coverage(
        "Severe weather — world",
        "worldwide",
        "Every WMO member state's own official warnings. They carry no " +
            "geometry, so each marker sits on the service that issued " +
            "the warning and is flagged approximate; the area is named " +
            "in the label."
    ),
    Coverage(
        "Severe weather — US",
        "the United States",
        "Kept alongside the world layer because NWS alerts carry real " +
            "polygons, which the worldwide feed does not."
    ),
    Coverage(
        "Tropical cyclones",
        "worldwide",
        "The hurricane centre for the Atlantic and east Pacific, the " +
            "Joint Typhoon Warning Center for the west Pacific, south " +
            "Pacific and Indian Ocean."
    ),
    Coverage(
        "Tsunami bulletins",
        "the Pacific and the Caribbean",
        "The two US centres publish openly. Other basins' warnings " +
            "reach the map through disaster alerts instead."
    ),
    Coverage("Disaster alerts", "worldwide", "GDACS, after an event is judged to affect people."),
    Coverage(
        "Conflicts",
        "worldwide, as recorded rather than as live",
        "Wikidata, which is written after the fact and is not a feed."
    ),
    Coverage(
        "News hotspots",
        "worldwide, at country level",
        "GDELT's article index carries the country each outlet " +
            "publishes from, not where the event happened. Plotting it " +
            "as the event would be a lie the map tells convincingly."
    ),
    Coverage(
        "Satellites",
        "worldwide",
        "Orbital elements from Celestrak, propagated on the phone, so " +
            "the positions are computed rather than reported."
    ),
    Coverage("Aurora", "worldwide forecast", "NOAA Space Weather Prediction Center."),
    Coverage("Air quality", "worldwide", "Open-Meteo, around the view."),
    Coverage("Airfield weather", "worldwide", "METARs around the view, wherever an airfield reports."),
    Coverage("Ocean buoys", "mostly US waters", "NOAA's own network, plus partners."),
    Coverage("Seismic stations", "worldwide", "The IRIS/EarthScope inventory."),
    Coverage("Airports, power plants, cable landings", "worldwide", "Bundled with the app, so they work offline."),
    Coverage(
        "Cameras",
        "worldwide, with live stills in California",
        "OpenStreetMap's mapped cameras around the view, anywhere. " +
            "Caltrans adds pictures, which nobody else publishes openly."
    ),
    Coverage(
        "Restricted airspace",
        "the United States, plus military ground worldwide",
        "The FAA publishes its airspace; nobody else does without an " +
            "aviation data contract. OpenStreetMap's military areas fill " +
            "in the rest of the world as ground rather than as airspace."
    ),
    Coverage(
        "Airport delays",
        "the United States",
        "No open worldwide equivalent exists. Every other source is a " +
            "commercial aviation feed."
    ),
    Coverage(
        "Radiation",
        "worldwide, wherever anyone has measured",
        "Safecast is an archive, not a live feed, and most of it was " +
            "recorded years ago. Every marker says when its reading was " +
            "taken."
    ),
    Coverage("Findings", "your case", "Whatever the tools located, plotted where they said.")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val sources = listOf(
        Source("Esri World Imagery (Maxar, Earthstar Geographics)", "satellite basemap", "Esri terms — attribution required"),
        Source("OpenStreetMap contributors", "place names, features, cameras, infrastructure", "ODbL 1.0"),
        Source("USGS", "earthquakes", "US public domain"),
        // IRIS is a university consortium, not USGS, and crediting the
        // wrong institution is both a licence failure and an accuracy
        // one: it tells a reader the station list comes from somewhere
        // it does not.
        Source("IRIS / EarthScope (SAGE)", "seismic station inventory", "free reuse with attribution"),
        Source("NASA EONET", "natural events", "US public domain"),
        Source("NASA FIRMS (MODIS)", "active fire detections worldwide", "US public domain"),
        Source(
            "Smithsonian Global Volcanism Program",
            "volcanic eruptions since 1960",
            "free reuse with attribution"
        ),
        Source("NOAA NHC", "tropical cyclones", "US public domain"),
        Source("NOAA NWS / tsunami.gov", "severe weather and tsunami bulletins", "US public domain"),
        Source(
            "US Navy Joint Typhoon Warning Center",
            "tropical cyclones in the west Pacific, south Pacific and Indian Ocean",
            "US public domain"
        ),
        Source(
            "WMO Severe Weather Information Centre",
            "official severe-weather warnings issued by WMO member states worldwide",
            "WMO — free use with attribution; warnings belong to the issuing service"
        ),
        Source("NOAA SWPC", "space weather, aurora", "US public domain"),
        Source("NOAA NDBC", "ocean buoys", "US public domain"),
        Source("FAA", "airport status, restricted airspace", "US public domain"),
        Source("GDACS (EU JRC / UN)", "disaster alerts", "free reuse with attribution"),
        Source("GDELT Project", "news volume and geography", "free for research"),
        Source("Open-Meteo", "weather, air quality, elevation", "CC BY 4.0"),
        Source("Safecast", "radiation readings", "CC0"),
        Source("OurAirports", "airport positions", "public domain"),
        Source("WRI Global Power Plant Database", "power stations", "CC BY 4.0"),
        Source("Celestrak", "orbital elements", "free reuse"),
        Source(
            "OpenSky Network, adsb.fi, adsb.lol",
            "aircraft positions worldwide, including military",
            "community feeds, ODbL-like, non-commercial"
        ),
        Source("Digitraffic (Fintraffic)", "AIS vessel positions, Baltic", "CC BY 4.0"),
        Source("Caltrans", "traffic cameras with live stills, California", "US public domain"),
        Source(
            "Natural Earth",
            "the globe's coastlines",
            "public domain"
        ),
        Source(
            "Overpass API (OpenStreetMap)",
            "cameras and military ground around the view, queried live",
            "ODbL 1.0 — volunteer-run, rate limited"
        ),
        Source("US Treasury OFAC", "sanctions lists", "US public domain"),
        Source("UN Security Council", "consolidated sanctions list", "UN terms"),
        Source("Have I Been Pwned", "breach catalogue", "CC BY 4.0"),
        Source("WhatsMyName, Sherlock, Maigret", "username site rules", "MIT / GPL per project"),
        Source("OSINT Framework and awesome-osint lists", "tool directory", "per project, mostly MIT/CC"),
        Source("Wikidata / Wikipedia", "entities, conflicts, nearby places", "CC0 / CC BY-SA"),
        Source("RIPE NCC, RDAP registries, Google DNS", "network registration and DNS", "open APIs"),
        Source("crt.sh, Cert Spotter", "certificate transparency", "open APIs"),
        // The lookups a dossier makes, which the list left out entirely
        // — about twenty services that see a target's name every time a
        // case is run, and were not mentioned anywhere in the app.
        Source(
            "GLEIF, SEC EDGAR, OpenSanctions",
            "company registries and sanctions screening",
            "open data, EDGAR requires a contact address"
        ),
        Source(
            "Shodan InternetDB, urlscan.io, AlienVault OTX, Hudson Rock",
            "host exposure, scans and breach intelligence",
            "free tiers, no key"
        ),
        Source(
            "BGPView, Team Cymru, Tor Project",
            "network ownership, reputation and exit nodes",
            "open APIs"
        ),
        Source(
            "Wayback Machine, Common Crawl",
            "archived pages and historical URLs",
            "Internet Archive terms / CC"
        ),
        Source(
            "Gravatar, Keybase, GitHub, Reddit, Hacker News, Telegram",
            "public profile and activity lookups",
            "each service's own terms"
        ),
        Source(
            "XposedOrNot, Mozilla Observatory-style mail checks",
            "breach and mail-security checks",
            "free tiers"
        ),
        Source(
            "UK Police, FBI Wanted, Open-Meteo archive, SunCalc-equivalent maths",
            "jurisdictional and environmental context",
            "open data"
        )
    )

    Scaffold(
        containerColor = Intel.Base,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "DATA SOURCES & LICENCES",
                        style = MaterialTheme.typography.titleSmall,
                        color = Intel.TextHi
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Intel.Base, navigationIconContentColor = Intel.Cyan
                )
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            item {
                Text(
                    "Everything on the globe and in a dossier comes from one of " +
                        "these. Several require attribution as a condition of " +
                        "use, and all of them are worth knowing about when you " +
                        "are deciding how much to trust a number — and when you " +
                        "are deciding who learns what you asked, because every " +
                        "one of them sees the query and the address it came " +
                        "from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.TextMid,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            item {
                Text(
                    "WHAT EACH LAYER COVERS",
                    style = MaterialTheme.typography.labelMedium,
                    color = Intel.TextHi,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
                Text(
                    "A layer showing zero means one of two things, and the " +
                        "map cannot tell you which: nothing is happening, or " +
                        "nothing is being watched there. Where a layer is " +
                        "regional this says what was looked for and what was " +
                        "found instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.TextMid,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            items(coverage) { c ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        c.layer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Intel.TextHi
                    )
                    Text(
                        c.extent,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.extent.startsWith("worldwide")) Intel.CyanBright
                        else Intel.TextMid
                    )
                    if (c.why.isNotBlank()) {
                        Text(
                            c.why,
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.TextLow
                        )
                    }
                }
            }
            item {
                Text(
                    "WHOSE DATA IT IS",
                    style = MaterialTheme.typography.labelMedium,
                    color = Intel.TextHi,
                    modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)
                )
            }
            items(sources) { s ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        s.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Intel.TextHi
                    )
                    Text(
                        s.usedFor,
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextMid
                    )
                    Text(
                        s.licence,
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow
                    )
                }
            }
        }
    }
}
