# OSINT Unified

A single-entry Android app for open-source intelligence. You type one
observable — an email, username, domain, IP, phone number, coordinate,
crypto wallet, aircraft tail number or just a name — and the app classifies
it, fans out across every source that can say something about it, and shows
the findings in one dossier.

The default screen is the **God's Eye View**: a native satellite-imagery
globe carrying ~25 live layers, from aircraft and vessels to traffic
cameras, restricted airspace and submarine cable landings.

> **Status:** Working. 84 registered tools, 71 of them keyless and run
> directly on the device with no backend, no API keys and no accounts —
> plus thousands of username sites executed natively and thousands of
> directory tools runnable against a target. The optional `backend/`
> server only adds the handful that genuinely need credentials.

## Building the APK with no PC (phone-only)

You don't need Android Studio or a desktop. GitHub builds the APK for you:

1. Push this project to a GitHub repo (the GitHub mobile app, or any git
   client on your phone, works).
2. GitHub Actions runs automatically on push (`.github/workflows/build.yml`).
3. Open the repo → **Actions** → the latest **Build APK** run → **Artifacts**
   → download **osint-unified-debug**.
4. Unzip, tap the `.apk`, and allow "install from unknown sources."

Every push rebuilds and produces a fresh APK. No local toolchain required.

## Verifying sources actually work

Third-party endpoints rot: they start demanding keys, block datacenter IPs,
or quietly disappear. `.github/workflows/probe.yml` exists so no source is
ever wired in on faith.

Put candidate URLs in `.github/probe-urls.txt` (one per line, optionally
`URL||custom user agent` or `URL@@byte-offset`), run the **Probe endpoints**
workflow, and each URL's status code, response size and first bytes come
back as build annotations. Every source in this app was confirmed that way
before a line of Kotlin was written for it, and the ones that failed —
FAA's registry, Blockchair, OpenSanctions, OpenAQ, ReliefWeb, phishstats —
were dropped rather than shipped broken.

## The globe

A native osmdroid `MapView` over keyless Esri World Imagery — earlier
WebView attempts (CesiumJS, MapLibre, Leaflet) all rendered blank on device.

Layers, each toggleable, counts live in the pill: aircraft (with
carrier/cargo/military filtering), military ADS-B, LADD (aircraft whose
owners paid to be hidden from tracking), vessels, satellites, earthquakes,
seismic stations, natural events, wildfires, volcanoes, GDACS disasters,
weather alerts, METAR, air quality, aurora, ocean buoys, news hotspots,
conflicts, airports, power plants, traffic cameras, restricted airspace,
submarine cable landings, and your own overlay. A space-weather line in the
HUD carries the Kp index, X-ray flare class and active SWPC alerts.

Tap any marker for an object panel with its label, coordinates and MGRS,
then investigate it or re-centre on it.

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

## Pivots

Every successful result is scanned for emails, handles, hosts, IPs, phone
numbers, wallets, tail numbers, coordinates and hashes, ranked by how many
sources corroborate each. They appear as **PIVOTS** in the dossier — one tap
starts a fresh investigation. A domain leads to an admin email, which leads
to a username, which leads to a Keybase proof.

## Self-test

The network-check icon runs every DIRECT tool and every live feed against a
sample input and reports ok / empty / timeout / fail with timings. If
something breaks in the field, that screen says which source and why.

## What's in Phase 1

| Area | File | What it does |
|------|------|--------------|
| Observable router | `domain/ObservableRouter.kt` | Regex classification + tool suggestions |
| Tool registry | `domain/ToolRegistry.kt` | All 69 tools, single source of truth |
| God's Eye View | `ui/godeye/` | Native osmdroid globe, ~25 live layers, tap-to-inspect |
| Keyless sources | `domain/executor/OsintApis.kt`, `DeepApis.kt` | Every direct API call the app makes |
| Pivot extraction | `domain/EntityExtractor.kt` | Identifiers pulled out of all results |
| Storage | `data/db/` | Room: investigations + results, ready for Phase 2 |
| Tests | `test/.../ObservableRouterTest.kt` | Router classification unit tests |

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
- **Phase 6 — Keyless depth:** 56 direct sources, live-probed in CI before
  shipping; object inspector; cross-result pivots. ✅
- **Remaining:** signed release build; optional backend for the few
  credentialed tools (Nuclei/GHunt/h8mail/TruffleHog).

## How each tool runs

Four execution modes (see `domain/ExecutionMode`):

- **On-device (bundled Go binary):** Subfinder, httpx — cross-compiled for
  Android/arm64 in CI with the NDK (so system DNS works), shipped in the APK
  as `jniLibs`. No backend.
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

- **Live aircraft** fall back to demo points until you set a tar1090 feed URL
  in settings; with a URL, it parses the real `aircraft.json`.
- **arm64 only:** bundled binaries target arm64-v8a (essentially all modern
  phones). A 32-bit-only device wouldn't get the on-device tools.
- **Esri World Imagery** supplies the satellite basemap, keyless — no token
  or account.
- **Reddit** blocks datacenter IP ranges, so that source may return nothing
  on an emulator in the cloud while working normally from a phone.
- **Traffic cameras** currently cover Caltrans (California); the coordinate
  tool points elsewhere at other public camera directories.

## Full tool coverage (the 48)

Every tool below is either implemented, has a functional native/keyless
equivalent, or is documented as infeasible on a stock phone with the reason.

**Functional now — on-device or keyless direct APIs (no backend):**

- GEOINT: God's Eye View (3D globe), tar1090 (aircraft), AIS-catcher
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

**Attempted on-device (may bundle, CI-dependent):** OWASP Amass.

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
- Kepler.gl — heavy geospatial viz (the 3D globe is the in-app equivalent)
- Bellingcat Toolkit, OSINT Framework, Monitoring-The-Situation — reference
  directories, represented by the tool suite itself

## Legal & ethical

This app orchestrates publicly available OSINT tools against public data.
Use it only for lawful research with appropriate authorization. Active
network tools (port/vuln scanning) can be intrusive — rate limiting and
consent prompts are part of the Phase 2/3 scope.
