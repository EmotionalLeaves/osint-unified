# OSINT Unified

A single-entry Android app for open-source intelligence. You type one
observable — an email, username, domain, IP, phone number, or coordinate —
and the app classifies it and routes you to the right tools. The default
screen is the **God's Eye View**: a 3D globe (CesiumJS) showing live
aircraft, with maritime and satellites to follow.

> **Status:** Phase 1 (foundation). The globe, search bar, and observable
> router are functional. Tool execution is wired as UI (chips) and lands in
> Phase 2.

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

- **Phase 1 (now):** Globe, router, registry, DB schema, cloud build. ✅
- **Phase 2:** Tool executors — local ARM64 binaries + cloud REST API
  (Nuclei, GHunt, TruffleHog). Real tar1090 aircraft feed. Link graph.
- **Phase 3:** PDF dossier export, case management, dark-mode polish, release.

## How each tool runs

- **On-device (bundled):** Subfinder and httpx are cross-compiled for
  Android/arm64 in CI (with the NDK, so system DNS works) and shipped inside
  the APK as `jniLibs` — the one place Android lets an app execute binaries.
  No backend needed.
- **Direct:** Overpass calls the public OpenStreetMap API straight from the
  app. No backend needed.
- **Cloud:** Nuclei, TruffleHog, GHunt, etc. run on the `backend/` server you
  deploy, because they're heavy or need credentials/templates.
- **Not yet bundled:** Amass and the Python tools (Maigret, Holehe, …) still
  show a "binary not bundled" message until added to the build.

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
