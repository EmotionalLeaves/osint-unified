# What is still wrong, in the order it will be fixed

Ranked by three things together: how much it costs the person using
the app, how much of the app it touches, and how long ago it was
asked for. An old unmet request outranks a new nice-to-have.

Sources: the user's own reports, the God's Eye UX audit, and six
code reviews filed beside this (r6–r12).

---

## 0. Standing frame

"I want this to be a tool, not a bookmark bar." Everything below is
weighed against that. A tool that returns a list of places to look is
not a source and must not be counted, graded or presented as one.

---

## 1. BLOCKING: the build is red

Nothing below can be tested until there is an APK, so this is first
whatever else is on the list.

- **1a.** r321 — `Overpass.kt:100` uses `continue` inside an inline
  lambda, which Kotlin 2.0 calls experimental. Restructure.
- **1b.** r320 — `PersonNameTest.variantsCoverTheFormsASourceWouldHold`
  expects "Bob Smith" and gets "bob Smith". The diminutive table is
  lowercase and the variant goes into a search box, so capitalise it.

## 2. What the second tester actually hit (23 Oct)

Reported by the user's boyfriend, on a device that is not the one
these builds have been judged on. User-encountered breakage, so it
outranks everything I found by reading.

- **2a. The UI does not fit his screen.** The most-reported and the
  least diagnosed. Needs a real responsive pass: window-size classes
  rather than fixed dp, text that scales with the system font size,
  insets on every screen and not just the ones with a Scaffold,
  overflow behaviour for long labels, and a check at small-width,
  large-font and landscape. FirstRunScreen is already known to draw
  under the status bar.
- **2b. Zooming into the map stops every layer working.** Concrete
  and reproducible. Suspects, in order: the on-demand layers were
  never re-queried when the view moved (fixed since, untested by
  him); `wideView = zoom < 5.0` gating in the poller; markers culled
  to a window that is correct but holds data fetched for a far wider
  one; the handoff arming at high zoom. Reproduce from the code
  before changing anything.
- **2c. "None of the search features really worked."** The single
  most serious item in this document. Search is the spine of the app
  and it is the thing he says did not work. Needs: what the search
  box does with each of the nine type chips, what happens on an
  empty or odd input, what the dossier shows when every tool is
  filtered out, and whether the fan-out is silently dropping
  everything.
- **2d. Not smooth on his device.** Partly addressed by the GPU
  sphere and the main-thread work moved off it; he has not tested
  any of that yet. Re-ask after he has.

## 3. Keys, and the disposable-mailbox idea

Answered here so it is not asked twice: no. Registering throwaway
accounts to mint free API keys and rotating them to get round a rate
limit is circumventing the terms of a service that is giving its
data away, and the predictable end of it is the whole user base
blocked by key pattern or by app name. It is also the exact opposite
of the posture the rest of this app takes — the first-run screen is
about not announcing yourself and not touching what you are not
entitled to touch.

What is legitimate and does the same job: **bring your own key**, per
service, blank by default, with the setting saying in plain words
what gets sent and where. SEC EDGAR already works exactly like that
with the contact address. That is the pattern for Picarta or anything
else keyed, if the person wants it.

## A. Oldest open reports

- **A1. Overpass-backed layers still file failures as absences.**
  Asked for on 22 Oct ("infrastructure scan, overpass and military
  sites have never worked"). The layers were fixed; the TOOLS were
  not. `militarySites` and `osmRecon` treat a 200-with-non-JSON — the
  normal shape of an Overpass timeout — as an empty result and state
  "No military-tagged sites within 25 km." `cctvNear`'s OSM fallback
  does the same. r6 #2, #3.
- **A2. "0 military-tagged sites" is classified as a finding** and
  the Overpass head line "No mapped features within 500 m" matches no
  absence pattern. r6 #7, r6 below-the-cut.
- **A3. OSM Notes sign-in wall** (task #44). No notes URL exists in
  the code; the links are to `openstreetmap.org/node/<id>` and plain
  map views, both public. Needs either the exact marker from the user
  or a sweep of every OSM URL the app can emit. Do the sweep.
- **A4. directoryRun's 20-byte "returned data" test**, and
  `SecAgent.NOT_SET` silently joining the "No data from" list. r6 #9.

## B. The bookmark-bar problem

- **B1. Link-list tools must stop counting as sources.** framework,
  toolrun, dorks, websearch, reverseimage are graded F and classified
  ABSENT, which keeps them out of the findings — but they are still
  dispatched by `autoRun`, still fill the runningTools progress bar,
  and still occupy slots in "N of M sources". Move them out of the
  source list into a "places to look" section of their own.
- **B2. Count what actually runs, per observable type**, and publish
  the number in the app. If a username search runs eleven tools and
  four of them are link lists, the person should be able to see that.
- **B3. More sources that fetch.** People landed seven. Do the same
  audit for username, email, phone, image and coordinate.

## C. The globe audit, remaining items

- **C1. G2 — vectors re-projected every frame.** With the sphere on
  the GPU this is the last per-frame trigonometry on the main thread.
  Level-of-detail by zoom, decimated once off-thread, not a cache
  keyed on a camera that changes every frame.
- **C2. G4 — one Canvas repainted wholesale.** Bake the static layers
  into a cached layer keyed on the camera; redraw only markers.
- **C3. G5 — pollers rebuild the whole point set.** Diff per layer.
- **C4. L1 — 34 flat pills with a search box to find a pill.** One
  Layers button, a grouped sheet, 3–5 pinned quick chips.
- **C5. L2 — presets.** Aviation, Maritime, Disaster, Conflict,
  Infrastructure, Space weather.
- **C6. L3 — layers as data**, one registry, not 34 booleans in three
  files.
- **C7. C1 — zero-config first run.** Raw backendUrl / tar1090Url /
  aisUrl / overlayUrl / token fields on a fresh install. Public
  defaults, everything else behind Advanced, and a dead layer should
  say what it needs inline.
- **C8. I1 — search as the spine**, not the globe.
- **C9. A2 — a blip tap feeds a case** rather than opening a URL.
- **C10. A1 — one map engine.** Deferred deliberately: it is a
  rewrite of the whole map surface with no device here, and the seam
  it removes is now a seam you cross once. Revisit after C1–C3.

## D. Correctness still open, by review

### r6 — OsintApis / Executors
siteFiles DONE · Overpass absences (A1) · cctvNear (A1) · reverseDns
still uses dohTxt · GDELT DONE · Hudson Rock vendor prose is the
first line and a clean result reads as a HIT · webcrawl DONE ·
directoryRun (A4) · UA DONE · **cctvNear rebuilds CctvDataSource per
call and defeats both caches — the largest avoidable data cost found**
· Cert Spotter pagination counted as complete · DMARC DONE ·
wikiNearby unvalidated input · startsWith("http") DONE ·
CancellationException swallowed in four places · the 40-hit cap means
sdn-alt.csv is never searched · six headers promising more than
follows · Twitch/Telegram raw handle interpolation · isDomain matches
an IPv4 in dorks · siteFiles serial · ripeRouting serial · classify
has no HIT case for unsanctions.

### r7 — data layer
Key commit DONE · WAL/SHM DONE · sha256 DONE · dismissals DONE ·
Timeline snapshots DONE · IntelSummary LOCATION DONE · IDENTIFIERS
DONE · **recordSnapshot runs regex + SHA-256 over a whole page on the
main thread and swallows the failure** · **caseLinks re-runs the whole
case analysis on Main on every write to the investigations table** ·
**CHECK NOW runs the watch sweep on Main** · shared SimpleDateFormat
and one-second filenames · export failure is silent · running rows
DONE · a migration failure is reported as an encryption failure ·
cases has no FK and deleteCase is not transactional.

### r8 — godeye
Bitmap per frame DONE · AreaReport DONE · landscape handoff DONE ·
long-press zoom DONE · radiation DONE · on-demand re-query DONE ·
visibleLayers on dispose DONE · ALL LAYERS off DONE · **WorldBrief
sorts every layer in composition to take six** · searchJobs DONE ·
pointerInput DONE · markerIcon allocates a Path per draw ·
per-frame allocation in drawStars/drawMarkers · thinBy recomputes a
regex identity per comparison · header vs pill disagree about
aircraft · the HUD ticker drops the FEED DOWN mark · lastSuccess is
written and never read.

### r9 — DeepApis / UsernameChecker
socialSweep DONE · githubActivity DONE · **seven bare runCatching
blocks still leaving a false absence: OTX, network data, host intel,
subdomain sweep, CVE, PGP, photos** · **ipReputation reads a
non-answering resolver as a clean sheet** · **UsernameChecker tests
the ruleset against the URL-ENCODED name, so every non-ASCII handle
is NotApplicable everywhere and coverage is inflated** · the `~`
legend claims a control probe most hits never got · historicalWeather
and sunAndTerrain cannot take the date they are documented as
checking · hostIntel's external-link substring test · osmUser averages
bbox centres and calls it where somebody lives · get() reads every
body unbounded · headers vs caps · secEdgar DONE · emailPermutations
case-sensitive domain strip · the ruleset-load failure omits the
verdict field · Mastodon P4033 URL shape · Panoramax 566 m corner ·
unencoded CVE path · BTC counterparties DONE · hardcoded CC-MAIN
fallback.

### r10 — ui outside godeye
App lock DONE · rotation re-run DONE · **the dossier runs six
full-corpus regex passes during composition on every arriving result**
· **the dossier is a verticalScroll Column, not a LazyColumn** · the
isolated browser's in-page Back closes it · a thread leaked per
proxy keystroke · proxy applied per keystroke and resolved on Main ·
caseLinks (r7) · export double-tap and silent failure · **any app can
start an investigation** DONE · FrameworkScreen filters thousands per
keystroke · work cancelled by a tab switch · ForensicsScreen loses
everything on rotation · GraphCanvas writes state during draw.

### r11 — synthesis
socialsweep verdict DONE · six header-first tools DONE · SecAgent
DONE · dns family DONE · RIPEstat family DONE · credibility DONE ·
IntelSummary LOCATION DONE · SOURCES count DONE · **emailperm's
generated addresses enter the corroboration pool as assertions** ·
**ptr builds a malformed query from an IPv6 or CIDR seed and reports
a clean absence** · INFRASTRUCTURE and noiseDomains disagree, and the
exporter calls extract() directly · ProfileSignals DONE ·
independentCount DONE · blank output filed ABSENT · coordinate tools
echo the seed back as a corroborated discovery.

### r12 — domain
MGRS DONE · Kepler DONE · workflow step failure swallowed ·
ResultFormatter does not fall back on an empty parse · hexDecode DONE
· Base64url DONE · graph DONE · Timeline DONE · checksums DONE ·
IPv6 DONE · **ObservableExtractor turns config.yaml and Mr.Smith into
pivot targets, and is O(n^2) with no length cap** · ForceLayout DONE
· GraphBuilder hash DONE.

## E. People, continued (task #50)

Seven sources landed. Still to do: use PersonName.variants in the
sources that search by name; a person-shaped dossier rather than the
infrastructure-shaped one; and the same audit for the other
observable types (B3).

## G. Asked for on 23 Oct

- **G1. Image geolocation — GeoSpy, GeoInfer, Picarta.**
  All three are keyed: GeoSpy is commercial, Picarta's free tier
  still issues a key, GeoInfer likewise. The standing rule for this
  app is no keys and no accounts, so they cannot be wired in as they
  are. Two honest ways to give the person the capability anyway:
    - **Build the keyless equivalent.** On-device OCR (ML Kit ships
      with no key and runs offline) reads the text in a photograph —
      shop names, street signs, plates, phone numbers — and those
      strings go straight into the sources this app already has.
      Combine with EXIF GPS, the sun-and-shadow maths that is already
      here, the reverse-image links, and a structured cue list
      (which side of the road, plate shape, mains sockets, kerb and
      bollard style, vegetation, architecture). That is most of how
      a human geolocates a picture and none of it needs a key.
      APK size does not matter here, so a bundled scene model is on
      the table too.
    - **Let the person bring their own key**, the way SEC EDGAR
      already takes their contact address: a blank optional setting,
      off by default, that says exactly what it sends and where.
      Ask the user which they want before building either.

- **G2. Taranis AI.** Open-source, self-hosted, and its useful idea
  is portable without running their server: collect from open feeds
  on a schedule, pull entities out of what arrives, cluster the
  stories that are about the same thing, and produce a briefing with
  the analyst's own notes attached. This app already has three
  quarters of the parts — GDELT, the watch worker, EntityExtractor,
  IntelSummary, WorldBrief — and they do not talk to each other.
  Build: a topic or target you can subscribe to; keyless collection
  (GDELT plus RSS, which needs no key and no account); clustering by
  shared entities rather than by keyword; and a dated briefing that
  says which source said what and when. That is a feature this app
  is unusually well placed to have, and it is the difference between
  a lookup tool and something you keep running.

- **G3. Public live-camera layer near a point.** Asked for on 23
  Oct. The request as phrased — find and view "unsecured video
  devices" via Shodan-style discovery — is declined: that is
  watching private individuals who never published anything, which
  is the line the whole app is built not to cross, and it is not
  built regardless of how it is asked for. The legitimate feature
  that answers the real need: aggregate cameras whose operators
  chose to publish them — traffic and highway, transit and airport,
  official city and tourism, weather and harbour, park and ski
  streams. Keyless, intentionally-public feeds only, drawn near a
  coordinate. Extends the Caltrans source the app already has and
  the OSM mapped-camera positions. No device discovery, no default
  credentials, nothing that was not meant to be seen.

## F. Then

Every issue known, listed and fixed. Every improvement, listed and
fixed. Then round again.
