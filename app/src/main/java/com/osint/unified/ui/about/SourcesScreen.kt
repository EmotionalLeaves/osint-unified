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
