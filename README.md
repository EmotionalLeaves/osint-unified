# OSINT Unified

A single-entry Android app for open-source intelligence. You type one
observable — an email, username, domain, IP, phone number, or coordinate —
and the app classifies it and routes you to the right tools. The default
screen is the **God's Eye View**: a 3D globe (CesiumJS) showing live
aircraft, with maritime and satellites to follow.

> **Status:** Feature-complete MVP. Globe (aircraft + vessels + tap-to-
> investigate), observable router, on-device + direct + cloud tool execution,
> readable results, chained workflow, investigation history, and export
> (JSON / CSV / dossier / PDF + link graph) are all built. Remaining:
> deploy the backend for the cloud/credentialed tools.

## Building the APK with no PC (phone-only)

You don't need Android Studio or a desktop. GitHub builds the APK for you:

1. Create a new GitHub repo and push this project to it (GitHub mobile app,
   or any git client on your phone, works).
2. GitHub Actions runs automatically on push (see `.github/workflows/build.yml`).
3. Open the repo on github.com → **Actions** tab → the latest **Build APK**
   run → scroll to **Artifacts** → download **osint-unified-debug**.
4. Unzip, tap the `.apk`, and allow "install from unknown sources."

Every push rebuilds and produces a fresh APK. No local toolchain required.

## What's in Phase 1

| Area | File | What it does |
|------|------|--------------|
| Observable router | `domain/ObservableRouter.kt` | Regex classification + tool suggestions |
| Tool registry | `domain/ToolRegistry.kt` | The 15 MVP tools, single source of truth |
| God's Eye View | `assets/cesium.html` + `ui/godeye/` | 3D globe, live (mock) aircraft, tap-to-search |
| Storage | `data/db/` | Room: investigations + results, ready for Phase 2 |
| Tests | `test/.../ObservableRouterTest.kt` | Router classification unit tests |

## Architecture

```
Search bar  ─►  ObservableRouter  ─►  suggested Tools (chips)
   │                                        │
God's Eye View (CesiumJS WebView)           ▼  (Phase 2)
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
- **Remaining:** deploy the backend (Nuclei/GHunt/h8mail/TruffleHog/
  OpenSanctions); bundle Amass; a Python path for Holehe/others; signed
  release build.

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

| Search type | Works with no backend | Needs backend |
|-------------|-----------------------|---------------|
| Username | Sherlock, Maigret | — |
| Phone | PhoneInfoga | — |
| Coordinate | Overpass | — |
| Domain | Subfinder, httpx | Nuclei; Amass (pending) |
| IP | httpx | Nuclei |
| Email | — | GHunt, h8mail (Holehe pending) |

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
- **CesiumJS** loads from its CDN and uses OpenStreetMap imagery (no Cesium
  Ion account/token needed).

## Legal & ethical

This app orchestrates publicly available OSINT tools against public data.
Use it only for lawful research with appropriate authorization. Active
network tools (port/vuln scanning) can be intrusive — rate limiting and
consent prompts are part of the Phase 2/3 scope.
