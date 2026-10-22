# OSINT Unified

A single-entry Android app for open-source intelligence. You type one
observable — an email, username, domain, IP, phone number, coordinate,
crypto wallet, aircraft tail number or just a name — and the app classifies
it, fans out across every source that can say something about it, and shows
the findings in one dossier.

The default screen is the **God's Eye View**: a native satellite-imagery
globe carrying 29 live layers, from aircraft and vessels to tropical
cyclones, tsunami bulletins, FAA ground stops, traffic cameras,
restricted airspace and submarine cable landings. A long press anywhere
reports everything every layer holds around that point; the HUD carries a
one-line **world brief** of what currently matters most.

> **Status:** Working. 85 registered tools, 72 of them keyless and run
> directly on the device with no backend, no API keys and no accounts.
> Beyond those, CI measures what the build actually carries and prints it
> as a build annotation rather than letting this file claim it:
>
> | Measured in CI | Count |
> |---|---|
> | Username site rules bundled | 9,389 |
> | Distinct web tools runnable against a target | 3,240 |
> | Catalogue entries browsable offline | 7,698 |
> | Known public breaches searchable offline | 1,047 |
>
> The optional `backend/` server only adds the handful that genuinely
> need credentials.

## Building the APK with no PC (phone-only)

You don't need Android Studio or a desktop. GitHub builds the APK for you:

1. Push this project to a GitHub repo (the GitHub mobile app, or any git
   client on your phone, works).
2. GitHub Actions runs automatically on push (`.github/workflows/build.yml`).
3. Open the repo → **Actions** → the latest **Build APK** run → **Artifacts**
   → download **osint-unified-debug**.
4. Unzip, tap the `.apk`, and allow "install from unknown sources."

Every push rebuilds and produces a fresh APK. No local toolchain required.

## Two checks that stand in for a compiler

The APK is built only in CI, so a one-line Kotlin mistake costs a full
round trip, and the Android SDK cannot be installed here. Two things close
most of that gap:

`tools/syntaxcheck.sh` runs stock `kotlinc` over the sources. Most of its
several thousand diagnostics are the missing SDK and are noise — but parser
errors, conflicting declarations and `"$name_"` string templates are
classpath-independent and real. So is a **bare identifier that is declared
somewhere in this project but not imported into the file using it**: the
name exists, it is being used where it cannot be seen, and that compiles
nowhere. Imports are tracked per file, because pooling them is what let four
missing imports through to CI. Each rule was verified by reintroducing the
break that originally escaped.

`.github/workflows/build.yml` counts what the APK actually carries — site
rules, catalogue entries, runnable templates, breach records — and prints it
as an annotation. That is where the numbers in this file come from. It also
asserts the fields the breach parser depends on, so a schema change surfaces
in CI rather than on a phone. The count is why this README no longer claims
thousands of runnable directory tools derived from catalogue links: the
measurement said twenty-six, and the real three thousand were the bundled
username rulesets, which are now in the directory where they belong.

## Verifying sources actually work

Third-party endpoints rot: they start demanding keys, block datacenter IPs,
or quietly disappear. `.github/workflows/probe.yml` exists so no source is
ever wired in on faith.

Put candidate URLs in `.github/probe-urls.txt` (the globe feeds) or
`.github/probe-tools.txt` (every keyless API a tool calls, with a real
sample input), one per line, optionally `URL||custom user agent` or
`URL@@byte-offset`. Run the **Probe endpoints** workflow — the `list` input
picks which file — and each URL's status code, response size and first bytes
come back as build annotations.

Every source in this app was confirmed that way before a line of Kotlin was
written for it, and the ones that failed — FAA's registry, Blockchair,
OpenSanctions, OpenAQ, ReliefWeb, phishstats, CBP border wait times (no
coordinates) — were dropped rather than shipped broken.

It also catches rot after the fact, which a green build cannot. A sweep of
the tool endpoints found two sources reporting "nothing found" when they
meant "no answer": crt.sh answers from a Postgres replica and routinely
exceeds a 25-second call timeout, and BGPView was not responding at all. The
first now gets a patient client with Cert Spotter filling in behind it; the
second falls back to RIPEstat. Neither failure was visible from here any
other way.

## The globe

A native osmdroid `MapView` over keyless Esri World Imagery — earlier
WebView attempts (CesiumJS, MapLibre, Leaflet) all rendered blank on device.

Layers, each toggleable, with the true count in the pill: aircraft (with
carrier/cargo/military filtering), military ADS-B, vessels, satellites,
earthquakes,
seismic stations, natural events, wildfires, volcanoes, GDACS disasters,
tropical cyclones, tsunami bulletins, FAA ground stops and delay
programmes, weather alerts, METAR, air quality, radiation, aurora, ocean
buoys, news volume, conflicts, airports, power plants, traffic cameras,
restricted airspace, submarine cable landings, and your own overlay. A
space-weather line in the HUD carries the Kp index, X-ray flare class and
active SWPC alerts.

Some of those are there because of what they explain about the others.
When a hundred aircraft are holding over one state the reason is a ground
stop at the airport they are all aiming for, and that fact lives in one
small FAA feed and nowhere else on the map. GDACS carries cyclones, but
only once one is about to hit someone; the NHC publishes the storm itself.
A tsunami is not an earthquake — most large undersea quakes produce no
wave — so the bulletin carries its own category word, because the
difference between "information" and "warning" is the entire message.

**Long-press anywhere** and every loaded layer is swept for a radius
around that point — scaled to the zoom you pressed at — and reported
nearest first. Tapping a marker answers "what is this"; the question
actually asked of a map is "what is going on here", and no single layer
answers it. It reads state the app already holds, so it needs no network
and works over open ocean.

**The world brief** is the same idea at global scale. A marker count is
not a situation — eleven thousand aircraft is an ordinary sky — so every
loaded layer is ranked by how much it would matter: severity first, then
recency, then volume, with a red GDACS alert, a tsunami warning or a
magnitude 6 lifting its whole section above routine traffic. The top line
sits in the HUD unasked. A layer that has reported and found nothing gets
a line of its own, because "no disaster alerts anywhere" is information
and is not the same as a layer that has not run yet.

Tap any marker and the inspector leads with **what it is** — which layer
it came from — then its label, coordinates, MGRS, a link to the source
where the layer has one, and what INVESTIGATE will actually search for.
Layers whose caption is not an identifier offer a location search instead
of one that cannot work.

### Showing everything, drawing only what is visible

Layers hold tens of thousands of points. Markers are culled to the visible
viewport and thinned to a budget with an even stride, so what is off
screen costs nothing and what remains stays spread out rather than
clumping. The pill shows the real count; the map draws what it can.

Feeds that cost bandwidth per region follow the viewport too. The ADS-B
sweep takes a spread sample of busy regions when zoomed out and the
nearest hubs when zoomed in — querying every hub against every provider
each refresh would pull gigabytes an hour of mobile data.

### Live means live

Several feeds keep items "open" long after they stop mattering — EONET
wildfires stay open for months. Events are filtered by observation date,
METAR drops anything over two hours old, and every label carries its age.
A four-month-old fire shown as current is the map lying.

There is no demo data anywhere. Earlier versions invented plausible
aircraft and ships when a feed failed; on an intelligence map a fabricated
contact is indistinguishable from a real one. An empty layer is the
honest answer.

### Satellite positions

Celestrak publishes mean orbital elements for the whole active catalogue,
keyless, and positions are computed on device: Kepler's equation plus the
J2 secular drift of the node and perigee. Not full SGP4 — `OrbitPropagator`
is tested against an observed ISS position and holds to 19 km of ground
track at 38 hours of element age, which is well under a pixel at the zoom
a globe is viewed at.

## Thousands of tools, actually executed

Two different things get called "a tool", and they are made executable in
two different ways.

**Username checkers are data, not programs.** Sherlock, Maigret, Blackbird
and WhatsMyName are each a list of sites plus a rule for telling a live
profile from a 404. The app bundles all three rulesets and runs the rules
itself — no Python, no backend. They are merged and deduplicated by target
URL, then fetched with bounded concurrency under a time cap, reporting a
partial result honestly if it runs out of time.

**Directory tools take their query in the URL.** `ToolTemplates` detects
the search parameter in any catalogue entry and turns it into a template,
so the entry can be launched already pointed at your target instead of at
a blank search box:

- the directory screen has a target field; every fillable entry grows a
  **RUN** button
- the `toolrun` tool executes, in parallel, every endpoint that returns
  plain text or JSON — those run with no browser at all — and points the
  rest at the target

The self-test screen shows the exact per-dataset counts this build carries,
read from a manifest written at build time, so the numbers are measured on
your device rather than claimed here.

## Pivots, and the second pass

Every successful result is scanned for emails, handles, hosts, IPs, phone
numbers, wallets, tail numbers, coordinates and hashes, ranked by how many
sources corroborate each. They appear as **PIVOTS** in the dossier.

A lookup can only answer what you typed, and its output names things you
could not have known to ask about — the IPs a domain resolves to, the hosts
on its certificate, the handle a profile links to. Chasing those is what an
analyst does next, and it is the difference between a page of lookups and a
dossier, so **the app does it without being asked**: one further round,
quotas per identifier kind, six tools each, never repeating a lookup the
first pass already made. Those results are kept visibly separate under
**FOLLOWED UP** and labelled with the identifier they came from, so no claim
in the dossier is of unclear provenance. Pivots already chased are ticked;
tapping one still opens a dossier of its own.

## Hits, absences and unreachable sources

Nearly every source answers "no entity named X" with HTTP 200, so a dossier
that trusts the status column is mostly full-height cards reporting absence
with the two or three real findings buried among them. `domain/Findings`
makes that judgement in one place — the screen and the exported report
cannot disagree about it — and the dossier leads with what was found, then
collapses the rest to one line naming the sources that looked.

Sources that *could not be reached* are kept apart from sources that said
no. "This source says nothing is there" and "this source was unreachable"
support opposite conclusions, and pooling them is how an absence of evidence
turns into evidence of absence.

## Identification

The decoder bench had fifteen buttons and presumed you already knew which
one you needed. `domain/Identify` recognises the shape first — length,
alphabet, octet range, segment count, hash length — and names what the
string is before offering the decoder that fits: IPv4, IPv6, MAC, UUID,
JWT, PEM, MGRS, coordinates, Bitcoin and Ethereum addresses, ICAO 24-bit
aircraft addresses, six hash families, Unix timestamps in either unit,
base64, base64url and percent-encoding. Where a string honestly is two
things, both readings are shown with the stronger first rather than one
being chosen and presented as fact — six hex digits really are both an
aircraft address and three bytes.

## Self-test

The network-check icon runs every DIRECT tool and every live feed against a
sample input and reports ok / empty / timeout / fail with timings. If
something breaks in the field, that screen says which source and why.

## What's in Phase 1

| Area | File | What it does |
|------|------|--------------|
| Observable router | `domain/ObservableRouter.kt` | Regex classification + tool suggestions |
| Tool registry | `domain/ToolRegistry.kt` | All 85 tools, single source of truth |
| God's Eye View | `ui/godeye/` | Native osmdroid globe, 29 live layers, tap-to-inspect, area report, world brief |
| Keyless sources | `domain/executor/OsintApis.kt`, `DeepApis.kt` | Every direct API call the app makes |
| Pivot extraction | `domain/EntityExtractor.kt` | Identifiers pulled out of all results |
| Storage | `data/db/` | Room: investigations + results, ready for Phase 2 |
| Tests | `app/src/test/` | Router, orbital propagation, freshness, area sweep, world-brief ranking, finding/absence, identification, link building |

## Architecture

```
Search bar  ─►  ObservableRouter  ─►  suggested Tools (chips)
   │                                        │
God's Eye View (osmdroid, native)           ▼
   ▲                                   Tool executors
   └── live aircraft feed              (LOCAL binaries / CLOUD API)
                                            │
                                            ▼
                                   Room DB (investigations + results)
```

## Roadmap

- **Phase 1 — Foundation:** Globe, router, registry, DB schema, cloud build. ✅
- **Phase 2 — Executors + feeds:** local/cloud/direct tool execution, real
  tar1090 feed, results persisted to Room. ✅
- **Phase 3 — Results & release prep:** parsed summaries, export
  (JSON/CSV/dossier/PDF), investigation history, link graph. ✅
- **Phase 4 — GEOINT:** maritime (AIS) vessels, tap-to-investigate. ✅
- **Phase 5 — Automation:** chained workflow (Subfinder → httpx). ✅
- **Phase 6 — Keyless depth:** 72 direct sources, live-probed in CI before
  shipping; object inspector; cross-result pivots. ✅
- **Phase 7 — Judgement:** automatic second pass on discovered identifiers;
  hits separated from absences and from unreachable sources, in the screen
  and the report alike; long-press area report; world brief; structural
  identification; offline breach catalogue; four more live layers. ✅
- **Remaining:** signed release build; optional backend for the few
  credentialed tools (Nuclei/GHunt/h8mail/TruffleHog).

## How each tool runs

Four execution modes (see `domain/ExecutionMode`):

- **On-device (bundled Go binary):** Subfinder, httpx, gau and waybackurls —
  cross-compiled for Android/arm64 in CI with the NDK (so system DNS works),
  shipped in the APK as `jniLibs`. No backend. Every one is pinned to an
  exact upstream version, built with `GOTOOLCHAIN=local`, stripped and
  `-trimpath`'d, and the build fails if one is missing rather than quietly
  dropping it. `@latest` and `@master` meant whatever upstream pushed that
  morning became native code running beside the case database.
- **Direct (native, in-app):** Sherlock/Maigret (username) via a native
  WhatsMyName checker over OkHttp; PhoneInfoga (phone) via Google
  libphonenumber; Overpass (coordinate) via the public OSM API. No backend.
- **Cloud:** Nuclei, TruffleHog, GHunt, h8mail, OpenSanctions — run on the
  `backend/` server you deploy (heavy, or need credentials/templates).
- **Local, not yet bundled:** Amass, Holehe, ExifTool — show a "binary not
  bundled" message until added (Amass is Go and bundleable; the Python tools
  need a Python runtime path).

| Search type | Runs with no backend, no key, no account | Needs backend |
|-------------|------------------------------------------|---------------|
| Email | Breach exposure, Gravatar, account finder, infostealer check, email→GitHub, mail security, web search, dorks, framework | GHunt, h8mail, Holehe |
| Username | Keybase proofs, GitHub profile + activity, Sherlock/Maigret/Blackbird, Reddit, Hacker News, Telegram, Wikidata, web search, dorks | — |
| Domain | Subfinder, crt.sh, subdomain sweep, DNS, TLS cert, mail security, headers, site files, RDAP, host intel, httpx, Shodan InternetDB, urlscan, Wayback, crawl, archive | Nuclei |
| IP | IP geo/ASN, reverse DNS, Tor exit, Shodan InternetDB, BGP/ASN, RDAP, host intel | Nuclei |
| Phone | PhoneInfoga (native libphonenumber) | — |
| Coordinate | Reverse geocode, sun & terrain, Wikipedia nearby, Overpass, military sites, cameras nearby, UK crime, historical weather | — |
| Name / entity | Web search, Wikidata, GLEIF, SEC EDGAR, OFAC sanctions (offline), FBI wanted, geocode, GDELT | OpenSanctions |
| Wallet | BTC/ETH balance, transactions, tokens, counterparties | — |
| Aircraft | Mode-S/tail → type, registered owner, live position | — |
| Image | EXIF/GPS, reverse image search, archive | — |

## Results, export & analysis

- Results are parsed into readable summaries (`domain/ResultFormatter`).
- Export an investigation as **JSON, CSV, a text dossier, or PDF**, shared via
  the system share sheet.
- **Link graph:** a D3 force-directed view of seed → tools → findings.
- **Workflow:** one-tap "Domain recon" chains Subfinder → httpx.
- **History:** every investigation and its results persist in Room and are
  browsable from the clock icon.

## Notes & honest limitations

- **No demo data, anywhere.** Earlier versions invented plausible aircraft
  and ships when a feed failed. On an intelligence map a fabricated contact
  is indistinguishable from a real one, so the generators were deleted; an
  empty layer is the honest answer. A tar1090 URL in settings adds your own
  feed alongside the public ones.
- **Radiation** is queried around the viewport, not globally — Safecast holds
  tens of millions of points. Its API ignores sort order and will return a
  2013 reading first, so the layer filters by capture date in the query and
  again on the device.
- **Airport status** is the FAA's, so it covers the US national airspace
  system and the Canadian airports the FAA lists, not the world.
- **arm64 only:** bundled binaries target arm64-v8a (essentially all modern
  phones). A 32-bit-only device wouldn't get the on-device tools.
- **Esri World Imagery** supplies the satellite basemap, keyless — no token
  or account.
- **Reddit** blocks datacenter IP ranges, so that source may return nothing
  on an emulator in the cloud while working normally from a phone.
- **Traffic cameras** currently cover Caltrans (California); the coordinate
  tool points elsewhere at other public camera directories.

## Full tool coverage

Every tool below is either implemented, has a functional native/keyless
equivalent, or is documented as infeasible on a stock phone with the reason.

**Functional now — on-device or keyless direct APIs (no backend):**

- GEOINT: God's Eye View (native map), tar1090 (aircraft), AIS-catcher
  (vessels — data feed), satellite tracking (satellite.js/Celestrak, the
  Gpredict capability), Overpass Turbo, reverse geocoding
- SOCMINT: Sherlock, Maigret, Blackbird, WhatsMyName (all native username
  checks), PhoneInfoga (native, libphonenumber), Gravatar
- NETINT: Subfinder + httpx (bundled Go binaries), crt.sh (CT logs),
  DNS-over-HTTPS, IP geo/ASN, Shodan InternetDB (ports/CVEs — the
  Masscan/ZMap/Shodan capability, keyless), Photon-style web crawl,
  theHarvester (subdomains/hosts), Google Dorks, Wayback Machine
- FORENSICS: CyberChef (native decoder/hash/JWT bench), ExifTool (native EXIF),
  Auto Archiver / ArchiveBox (Wayback Save)
- Analysis capabilities: Maltego → link graph + pivot transforms;
  SpiderFoot / Recon-ng → Run-all + workflows + history workspace

**Deliberately not bundled:** OWASP Amass (51 MB, and in passive mode
largely repeats subfinder) and dnsx (39 MB, which was being built and
shipped without a single call site).

**Functional once the backend is deployed (code ready):** Nuclei (templates),
GHunt (needs Google cookie), h8mail (breach keys), TruffleHog, OpenSanctions.

**Not feasible on a stock, unrooted phone — capability covered where noted:**

- readsb, SatDump — need SDR radio hardware (aircraft/satellite shown via
  feeds/tracking instead)
- Masscan, ZMap — raw-socket mass scanning needs root (covered by Shodan
  InternetDB + httpx)
- OnionSearch, TorBot — need Tor/Orbot
- Instaloader, Toutatis, Ignorant — Instagram/phone sessions + anti-bot
- Holehe — Python per-site logic (email partly covered by Gravatar + crawl)
- OpenCTI, MISP, Yeti, IntelOwl, OCCRP Aleph — server platforms/databases,
  not an app feature
- Gitleaks, FinalRecon — need a local repo / covered by httpx + DNS
- Kepler.gl — heavy geospatial viz (the native map is the in-app equivalent)
- Bellingcat Toolkit, OSINT Framework, Monitoring-The-Situation — reference
  directories, represented by the tool suite itself

## Legal & ethical

This app orchestrates publicly available OSINT tools against public data.
Use it only for lawful research with appropriate authorization. Active
network tools (port/vuln scanning) can be intrusive — rate limiting and
consent prompts are part of the Phase 2/3 scope.
