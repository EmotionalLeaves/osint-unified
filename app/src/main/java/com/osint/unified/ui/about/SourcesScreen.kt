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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osint.unified.network.DataMeter
import com.osint.unified.ui.theme.Intel
import androidx.lifecycle.repeatOnLifecycle

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
        "northern Europe",
        "No open network publishes live AIS worldwide without an " +
            "account — every aggregator that carries the whole sea " +
            "wants a key. What does exist is national authorities " +
            "publishing their own waters, and those add up: Fintraffic " +
            "for the Baltic and the Norwegian Coastal Administration " +
            "for the Norwegian Sea, the North Sea and the Barents. Any " +
            "other open feed can be pasted into settings and is merged " +
            "with these. Everywhere else is still empty, and the pill " +
            "says so rather than letting the absence of a ship look " +
            "like information."
    ),
    Coverage(
        "Earthquakes",
        "worldwide, from three agencies",
        "The USGS catalogue is global but it is a US agency reporting " +
            "the world, and small events far from its own networks " +
            "arrive late or never. The EMSC publishes a magnitude 2 in " +
            "Greece within minutes; GFZ Potsdam's GEOFON runs its own " +
            "global network and is often first for Asia, Africa and the " +
            "south Atlantic. All three are read and merged by where and " +
            "when, since each numbers the same earthquake differently."
    ),
    Coverage(
        "Sea level",
        "worldwide",
        "The IOC of UNESCO's tide-gauge network — the only live, " +
            "worldwide, keyless measurement of the ocean there is, and " +
            "the instruments that confirm a tsunami actually happened. " +
            "Gauges that stopped reporting are left off rather than " +
            "drawn, because one on a live map reads as a live reading."
    ),
    Coverage(
        "Internet outages",
        "worldwide",
        "IODA watches routing, pings and darknet noise for every " +
            "country and raises an alert when they fall together. A " +
            "shutdown, a cut cable, a power failure and a censorship " +
            "event all look like this; which one it is is the question " +
            "you are there to answer. Alerts carry no coordinates, so " +
            "each sits on its country's centroid."
    ),
    Coverage(
        "Wildfires",
        "worldwide",
        "NASA's satellite thermal detections — every one of them, not " +
            "the named events a newsroom has noticed. Two instruments: " +
            "MODIS at a kilometre and VIIRS at 375 metres, which finds " +
            "about three times as many and sees small fires MODIS " +
            "cannot resolve at all."
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
        "the Pacific rim, the Caribbean and Indonesia",
        "Four authorities, unmerged: the two US centres, Japan's JMA — " +
            "which issues for the whole Pacific, not only for Japan, and " +
            "writes its place names in Japanese — and Indonesia's BMKG, " +
            "read only for quakes it says can make a wave. Where two " +
            "agencies have issued for the same wave you will see both, " +
            "in each one's own words."
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
    Coverage(
        "Air quality",
        "a fixed list of cities, worldwide",
        "Open-Meteo, read for several hundred named cities rather than " +
            "for wherever you are looking. A town that is not on the " +
            "list has no reading here, which is not the same as clean air."
    ),
    Coverage(
        "Airfield weather",
        "worldwide",
        "METARs, swept in two dozen boxes covering the whole Earth " +
            "rather than following the view, so the coverage is wherever " +
            "an airfield reports rather than wherever you are looking."
    ),
    Coverage("Ocean buoys", "mostly US waters", "NOAA's own network, plus partners."),
    Coverage("Seismic stations", "worldwide", "The IRIS/EarthScope inventory."),
    Coverage(
        "Airports, ports, power plants, cable landings",
        "worldwide",
        "Bundled with the app, so they work offline. A vessel slowing " +
            "on approach to somewhere means nothing until the " +
            "somewhere has a name."
    ),
    Coverage(
        "Launches",
        "worldwide",
        "Rockets leave from about thirty places and the schedule is " +
            "public. A launch closes airspace and sea lanes and puts " +
            "ships in particular places, which is why an unusual " +
            "amount of activity around a pad is sometimes just a " +
            "launch. Only the next three weeks: further out is a plan " +
            "rather than an event."
    ),
    Coverage(
        "Controlled sites",
        "worldwide, around the view",
        "Border crossings, diplomatic missions, prisons and the " +
            "substations the lights depend on — the four kinds of " +
            "place a person looking at an area asks about that the " +
            "other layers do not hold. Off until you turn it on, " +
            "because each query costs a volunteer-run server " +
            "something."
    ),
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
        Source(
            "EMSC / seismicportal.eu",
            "earthquakes reported by European, Mediterranean and central Asian networks",
            "free reuse with attribution"
        ),
        Source(
            "IOC of UNESCO sea level monitoring",
            "the global tide-gauge network",
            "free use with attribution; data belongs to the operating agencies"
        ),
        Source(
            "IODA (Georgia Tech / CAIDA)",
            "country-level internet outage detection",
            "free for research and non-commercial use"
        ),
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
        Source("GFZ Potsdam / GEOFON", "a third global earthquake catalogue", "free for non-commercial use"),
    Source("NOAA NWS / tsunami.gov", "severe weather and tsunami bulletins", "US public domain"),
    Source("Japan Meteorological Agency", "tsunami bulletins for the Pacific", "free to use with attribution"),
    Source("BMKG (Indonesia)", "earthquakes judged able to produce a tsunami", "open government data"),
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
        Source(
            "The Space Devs (Launch Library 2)",
            "upcoming orbital launches and the pads they leave from",
            "free, no key, used within its rate limits"
        ),
        Source("WRI Global Power Plant Database", "power stations", "CC BY 4.0"),
        Source("Celestrak", "orbital elements", "free reuse"),
        Source(
            "OpenSky Network, adsb.fi, adsb.lol",
            "aircraft positions worldwide, including military",
            "community feeds, ODbL-like, non-commercial"
        ),
        Source(
            "Digitraffic (Fintraffic)",
            "AIS vessel positions, the Baltic",
            "CC BY 4.0"
        ),
        Source(
            "Kystdatahuset (Norwegian Coastal Administration)",
            "AIS vessel positions, the Norwegian Sea, North Sea and Barents",
            "Norwegian open data (NLOD), no account"
        ),
        Source("Caltrans", "traffic cameras with live stills, California", "US public domain"),
        Source(
            "Natural Earth",
            "the globe's coastlines, world ports, country positions",
            "public domain"
        ),
        Source(
            "NASA Visible Earth (Blue Marble, Earth at Night)",
            "the imagery the globe is drawn from",
            "public domain, attribution appreciated"
        ),
        Source(
            "Overpass API (OpenStreetMap)",
            "cameras, military ground, border crossings, diplomatic " +
                "missions, prisons and substations around the view, queried live",
            "ODbL 1.0 — volunteer-run, rate limited"
        ),
        Source(
            "KartaView and Panoramax",
            "street-level photographs near a point, with the date each " +
                "was taken and which way the camera was pointing",
            "CC BY-SA / etalab-2.0, no account"
        ),
        Source(
            "Wikimedia Commons",
            "geotagged photographs within 3 km of a point",
            "per file, mostly CC BY-SA"
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
            item { DataUsed() }
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

/**
 * What this session has cost in mobile data, and where it went.
 *
 * Not a nicety. Every layer here is a live feed on a phone, and the
 * difference between one that costs two megabytes an hour and one that
 * costs nine hundred is invisible in the code — the aircraft layer
 * swept a dozen half-megabyte requests every fifteen seconds for
 * months and nothing anywhere said so. Putting the number in front of
 * the person paying for it is what makes that kind of mistake
 * findable.
 *
 * Counted off the wire after compression, so a conditional request
 * that comes back unchanged costs what it really costs and a response
 * the disk cache answered costs nothing. Recomposed on a timer rather
 * than per frame: it is a figure to glance at, not a speedometer.
 */
@Composable
private fun DataUsed() {
    var tick by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Only while there is a screen to read it on.
    //
    // The third of these, and the one that wakes most often: the
    // other two were a one-second clock and a four-minute subsolar
    // point, and this is every two seconds, recomposing three
    // DataMeter reads behind a dark screen for as long as the
    // composition is retained.
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(2_000L)
                tick++
            }
        }
    }
    val used = remember(tick) { DataMeter.bytes() }
    val calls = remember(tick) { DataMeter.requests() }
    val worst = remember(tick) { DataMeter.worst(5) }
    val minutes = remember(tick) {
        ((System.currentTimeMillis() - DataMeter.since()) / 60_000L).coerceAtLeast(1L)
    }

    Column(Modifier.padding(bottom = 14.dp)) {
        Text(
            "NETWORK USED SINCE LAUNCH",
            style = MaterialTheme.typography.labelSmall,
            color = Intel.Cyan
        )
        Text(
            "${DataMeter.human(used)} over $calls requests in $minutes min " +
                "— about ${DataMeter.human(used * 60L / minutes)} an hour at this rate.",
            style = MaterialTheme.typography.bodySmall,
            color = Intel.TextMid,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (worst.isNotEmpty() && used > 0) {
            Text(
                "Mostly: " + worst.joinToString("  ·  ") {
                    "${it.first} ${DataMeter.human(it.second)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Intel.TextLow,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            "Layers that are switched off are not fetched at all, so the " +
                "quickest way to spend less is to turn off what you are not " +
                "looking at. Aircraft and vessels are the expensive ones.",
            style = MaterialTheme.typography.bodySmall,
            color = Intel.TextLow,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
