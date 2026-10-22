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
        Source("USGS", "earthquakes, seismic stations", "US public domain"),
        Source("NASA EONET", "wildfires, volcanoes, natural events", "US public domain"),
        Source("NOAA NHC", "tropical cyclones", "US public domain"),
        Source("NOAA NWS / tsunami.gov", "severe weather and tsunami bulletins", "US public domain"),
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
        Source("adsb.lol / OpenSky", "aircraft positions", "community, ODbL-like"),
        Source("Digitraffic (Fintraffic)", "AIS vessel positions, Baltic", "CC BY 4.0"),
        Source("Caltrans", "traffic cameras, California", "US public domain"),
        Source("US Treasury OFAC", "sanctions lists", "US public domain"),
        Source("UN Security Council", "consolidated sanctions list", "UN terms"),
        Source("Have I Been Pwned", "breach catalogue", "CC BY 4.0"),
        Source("WhatsMyName, Sherlock, Maigret", "username site rules", "MIT / GPL per project"),
        Source("OSINT Framework and awesome-osint lists", "tool directory", "per project, mostly MIT/CC"),
        Source("Wikidata / Wikipedia", "entities, conflicts, nearby places", "CC0 / CC BY-SA"),
        Source("RIPE NCC, RDAP registries, Google DNS", "network registration and DNS", "open APIs"),
        Source("crt.sh, Cert Spotter", "certificate transparency", "open APIs")
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
                        "are deciding how much to trust a number.",
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
