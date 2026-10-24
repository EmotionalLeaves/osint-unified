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

  Audited 23 Oct against every screen, at 320 dp width, in landscape
  and at 2.0x font scale. The pass is now a numbered list, worst
  first; fonts are all `sp` off `theme/Type.kt` and insets are
  covered by Scaffold everywhere except the two screens that already
  call `safeDrawingPadding`, so neither of those is the problem.

  - [done] **2a-1. In landscape the globe gets zero height.** GodEyeViewScreen's
    root Column does not scroll, and the header (~60 dp), the search
    field (~72 dp) and the controls panel (`panelMax` =
    `screenHeightDp * 0.38`) are all outside the panel's own scroll
    and are measured before the map, which takes `weight(1f)`. On a
    640x360 dp landscape phone that is 269 dp of chrome against ~270 dp
    of usable height, after the 80 dp nav bar — the map resolves to
    about nothing. At 2.0x font in portrait it is ~60 dp. This is the
    most likely thing he actually saw. `panelMax` has to come from
    measured remaining space, with a floor under the map.
  - [done] **2a-2. The private browser's action row runs off the right edge.**
    Four labels and three spacers in a plain Row: ~296 dp against
    296 dp usable at 320 dp width. MY BROWSER and INVESTIGATE are
    measured at zero and vanish at the stock "large" font setting;
    SNAPSHOT, which is the evidence-preservation feature, goes next.
  - [done] **2a-3. The marker-inspect dialog cannot scroll.** Its two
    siblings do; this one was missed, so in landscape the OPEN SOURCE
    link below the fold is clipped away rather than scrolled to.
  - [done] **2a-4. Watch's "N watched" line disappears at large font.** It is
    the weighted child of a Row whose two buttons are measured first
    and take everything.
  - [done] **2a-5. Two lists get measured to zero height.** Diagnostics'
    self-test results behind a long bundle manifest, and the watch
    list behind its intro paragraph: in both, unbounded content above
    a `fillMaxSize` LazyColumn. Both fix by moving the content above
    into an `item {}`.
  - [done] **2a-6. Touch targets under 48 dp with no other route.**
    `minimumInteractiveComponentSize()` is applied in GodEyeViewScreen
    and InvestigationScreen and nowhere else. Worst: PrivateBrowser's
    actions and CLOSE (no padding at all, ~15 dp, and CLOSE is the
    only way out once BackHandler is walking page history), then
    CasesScreen's case chips, HistoryScreen, WatchScreen,
    FrameworkScreen's RUN, ResultCard's COPY/RAW.
  - [done] **2a-7. `maxLines` without `overflow = Ellipsis`** in four places,
    so long values are clipped mid-glyph with no sign they continue:
    ResultCard's PivotChip, InvestigationScreen's FactChip and
    breadcrumb, FrameworkScreen's app-bar title.
  - [done] **2a-8. A long pivot value eats its own count badge.**
  - [done] **2a-9. Fixed-width containers holding text**: the AREA report's
    58 dp distance column (the identical control elsewhere already
    uses `widthIn(min=)` and carries a comment saying why), and
    MoreScreen's 110 dp proxy-port field.
  - [done] **2a-10. Nav-bar labels wrap and clip** at large font inside M3's
    fixed 80 dp bar; the unseen count belongs on a badge, not in the
    label string.
  - [done] **2a-11. The keyboard covers every field but Forensics'.** No
    `imePadding()` and no `windowSoftInputMode`: notes, tags, new
    case, proxy host and port, and all six settings fields.
- **2b. Zooming into the map stops every layer working.** Concrete
  and reproducible. Suspects, in order: the on-demand layers were
  never re-queried when the view moved (fixed since, untested by
  him); `wideView = zoom < 5.0` gating in the poller; markers culled
  to a window that is correct but holds data fetched for a far wider
  one; the handoff arming at high zoom. Reproduce from the code
  before changing anything.

  Traced 23 Oct. It is the globe, not the flat map, and the
  cap-before-cull ordering I suspected is real — just not where I
  expected it.

  - [done] **2b-1. The globe's 2500-marker budget is spread over the whole
    Earth and never recomputed for the camera.** `produceState` for
    the globe points is keyed on the layers only, and `thinGlobe`
    takes no window at all: it samples every layer down to
    GLOBE_BUDGET across the entire planet, and the only view test is
    per-frame, at draw time, dropping the far hemisphere. So the
    survivors are scattered world-wide and zooming in shrinks the
    share of them that lands on screen. The visible fraction of a
    sphere is (1 - cos(span/2))/2: at 180 deg of span that is half
    the budget on screen, at the 22 deg where the globe hands over to
    tiles it is 0.92% — a 54x drop with no change in the data. The
    budget is split evenly across ~30 armed layers, so each gets
    about 83 points world-wide and under one on screen at 22 deg.
    Every layer dies at once, which is the report word for word. Cull
    to the camera before thinning, the way the flat map already does.
  - [done] **2b-2. Marker labels on the globe never render at any zoom.**
    The 30-label cap is tested against all 2500 thinned points
    instead of the ones actually on screen, so with any real layer
    set the count is in the hundreds and the branch never runs.
  - [done] **2b-3. `ensureSites` records its cache key before the fetch is
    launched.** `onDemand` returns early if a job is still running,
    so the new region's key gets recorded against the old region's
    data and the next call sees a hit and returns too. The layer
    freezes on the previous region with no outstanding request and no
    error. Cameras and airspace both check the job first; sites is
    the only one with the order wrong.
  - [done] **2b-4. Three layers are gated by a fourth layer's freshness
    test.** `movedBeyond` compares against `radiationCentre` and
    radiation's radius-for-zoom, and cameras (25 km), sites (120 km)
    and military ground (150 km) all fetch fixed radii that have
    nothing to do with it. At zoom 5 the gate's limit is 200 km, so a
    100 km pan leaves the 25 km camera circle entirely and does not
    re-ask. Only bites with RADIATION armed — with it off,
    `radiationCentre` is null and the gate passes everything, which
    is the opposite waste.
  - [done] **2b-5. The map window is only re-read on a listener event or
    when it is null.** 250 ms debounce, and no fallback comparing the
    live zoom against the zoom the window was captured at. If any
    zoom path emits no event the window stays stale indefinitely.
  - **2b-6. Latent: a window under 0.001 deg turns the cull off**
    rather than emptying it, and then reports "showing 2500 of N"
    over an empty map. Needs a map under ~373 px wide, so not
    reachable on a phone.

  Confirmed correct, not to be re-checked: the flat map's
  cull-then-cap order, `MapWindow.contains` including the
  antimeridian, the map window being live rather than cached,
  `onViewportChanged` wiring for a pure zoom, marker add/remove
  bookkeeping, GlobeHandoff holding no layer state, and
  `wideView` (it reaches exactly one fetch, aircraft, whose narrow
  box is far larger than any viewport at zoom >= 5).
- **2c. "None of the search features really worked."** The single
  most serious item in this document. Search is the spine of the app
  and it is the thing he says did not work. Needs: what the search
  box does with each of the nine type chips, what happens on an
  empty or odd input, what the dossier shows when every tool is
  filtered out, and whether the fan-out is silently dropping
  everything.

  Audited end to end, 23 Oct. Seventeen findings; the first two
  together are most of the reported symptom.

  - [done] **2c-1. The nine type chips and the example pills are still
    behind `panelOpen`.** Only the text field was lifted out. The
    comment right beside them says "what folds away is the search
    box, the type chips and the examples" — the box was moved and
    they were not. So on a fresh install there is a search box with
    no way to choose a type, and every search is AUTO for ever
    unless the user finds the unlabelled chevron. This makes 2c-2
    unavoidable.
  - [done] **2c-2. AUTO reads any bare word of 3-30 characters as a
    username.** The username regex is tested before the name
    fallback, so "london", "tesla", "gazprom" go to the account
    sweep — keybase, reddit, hacker news, telegram — which all
    answer "no account named london", and the dossier reads NOTHING
    FOUND. The sources that would have answered (orcid, court
    records, GLEIF, EDGAR, sanctions, GDELT, geocode, FBI) are never
    dispatched. With 2c-1 there is no override.
  - [done] **2c-3. PHONE dispatches four tools and none of them asks
    anything about the number.** phoneinfoga is offline
    libphonenumber plus two hard-coded Google links; the other three
    are force-classified ABSENT. One card, a format parse and two
    links. This is the bookmark-bar complaint literally.
  - [done] **2c-4. IMAGE never runs EXIF.** `exif` is marked
    touchesTarget, and autoRun drops every touchesTarget tool while
    active recon is off — but for an image URL the target IS the
    image the user pasted. What is left is two link builders and one
    Wayback call.
  - [done] **2c-5. `toolrun` is dead twice over.** It is touchesTarget, so
    it is in no auto set, and it is force-classified ABSENT
    unconditionally — so even run by hand, "5 of 7 executable
    endpoints returned data" is filed under NOTHING FOUND. It is
    the only tool dropped from every single chip.
  - [done] **2c-6. An empty or whitespace query is a silent no-op.** No
    toast, no error, no focus, nothing. From the tester's seat the
    search button is broken.
  - [done] **2c-7. A second search's progress counter is decremented by
    the first search's cancelled jobs.** The cancelled job's
    `finally` runs after a NonCancellable database write, so it
    commonly lands after the new run has added its own counts, and
    tool ids overlap almost completely. Result: the progress bar
    vanishes mid-scan and the dossier prints "No source returned
    anything on this target" while the tools are still running.
  - [done] **2c-8. The progress denominator counts tools that will never
    be dispatched.** Domain shows 15 run of 21, username 11 of 16,
    image 3 of 5. The counter stops short and the bar disappears,
    which reads as giving up.
  - [done] **2c-9. An infrastructure exception out of runTool is
    swallowed** and the row stays "running" for ever: "N still
    running", permanently, and history spins on it.
  - [done] **2c-10. `classifyAs` validates only coordinates.** Pick
    DOMAIN, type "john smith", and fifteen domain tools fire at a
    two-word phrase; mailsec's `substringAfter('@')` returns the
    whole string and it queries DNS for it. Every other chip trusts
    the input verbatim.
  - [done] **2c-11. No length cap, and five words makes an
    "organisation".** A natural-language question — which is what
    people type into a box labelled "query any target" — goes to
    GLEIF, EDGAR, OFAC and the UN list as a company name. A very
    long string becomes a dozen 414s reported as "could not be
    reached", with no hint that the input was the problem.
  - [done] **2c-12. "SHOW n MORE" collapses again on every incoming
    result**, because it is keyed on the row count. Expand a
    section mid-scan and it snaps shut, which reads as the app
    discarding results.
  - [done] **2c-13. The IPv4-only refusals are graded as findings.** Three
    tools answer an IPv6 search with a careful "this needs an IPv4
    address", and none of those sentences matches the absence or
    unreachable shapes, so they are filed as HITs and counted in
    "returned intel".
  - [done] **2c-14. Three router types have no chip**: url, wallet,
    aircraft.
  - [done] **2c-15. "sources queried" and "returned intel" are counted
    over different scopes** — one seed-only, the other including
    the second pass. With follow-up on it reads "15 queried · 22
    returned intel".
  - [done] **2c-16. `snapshot` is DIRECT with no executor branch**, so the
    self-test screen reports a permanent unfixable failure. It is
    written by the browser, not executed.
  - **2c-17. Confirm the tester's APK came from CI.** assets/ is
    empty in the tree and jniLibs/ does not exist; both are filled
    by the workflow. A build made any other way ships no datasets
    at all and every bundled source says "not bundled".

  Checked and correct, not to be re-audited: the dossier is never
  blank when everything is absent or unreachable — it names every
  tool and offers the full card; no `take(n)` hides a row without
  an affordance; `Findings.sort` and `kindOf` are sound; no
  hit-shaped first line in the codebase matches NOTHING_SHAPE
  (all ~75 matches are genuine absence sentences); absent and
  unreachable rows render in full; there is no row-clobbering race
  between two searches, only the counter one; cancellation writes
  a cancelled row under NonCancellable and rethrows; a failed
  refresh does not destroy a previous finding; nothing writes a
  terminal status before the tool runs; every DIRECT tool but
  `snapshot` has an executor branch and all 92 registry ids
  cross-check against all 11 router lists; LOCAL and CLOUD gating
  is honest; held-back active tools are named with a RUN THEM ONCE
  offer.

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

- [done]**A1. Overpass-backed layers still file failures as absences.**
  Asked for on 22 Oct ("infrastructure scan, overpass and military
  sites have never worked"). The layers were fixed; the TOOLS were
  not. `militarySites` and `osmRecon` treat a 200-with-non-JSON — the
  normal shape of an Overpass timeout — as an empty result and state
  "No military-tagged sites within 25 km." `cctvNear`'s OSM fallback
  does the same. r6 #2, #3.
- [done]**A2. "0 military-tagged sites" is classified as a finding** and
  the Overpass head line "No mapped features within 500 m" matches no
  absence pattern. r6 #7, r6 below-the-cut.
- **A3. OSM Notes sign-in wall** (task #44). Swept 23 Oct: every
  OpenStreetMap URL the code can emit is public — the Nominatim
  endpoints, `api.openstreetmap.org/api/0.6/changesets.json`
  (read API, no auth), `openstreetmap.org/user/<name>`,
  `/node|way|relation/<id>`, and `?mlat=&mlon=#map=`. None of them
  is a notes URL and none needs an account. Either the marker came
  from a bundled asset (assets/ is filled by CI and is empty in
  the tree, so it cannot be checked from here) or it was a
  different site. **Needs the exact URL from the tester.** While
  sweeping: the Overpass Turbo link was `?Q=&C=lat;lon;16` — an
  empty query, so it opened the editor over the right place with
  nothing to run. Fixed.
- [done]**A4. directoryRun's 20-byte "returned data" test**, and
  `SecAgent.NOT_SET` silently joining the "No data from" list. r6 #9.

## B. The bookmark-bar problem

- [done] **B1. Link-list tools must stop counting as sources.** framework,
  toolrun, dorks, websearch, reverseimage are graded F and classified
  ABSENT, which keeps them out of the findings — but they are still
  dispatched by `autoRun`, still fill the runningTools progress bar,
  and still occupy slots in "N of M sources". Move them out of the
  source list into a "places to look" section of their own.
- [done] **B2. Count what actually runs, per observable type**, and publish
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
- [done] **C5. L2 — presets.** Aviation, Maritime, Disaster, Conflict,
  Infrastructure, Space weather.
- **C6. L3 — layers as data**, one registry, not 34 booleans in three
  files.
- [done] **C7. C1 — zero-config first run.** Raw backendUrl / tar1090Url /
  aisUrl / overlayUrl / token fields on a fresh install. Public
  defaults, everything else behind Advanced, and a dead layer should
  say what it needs inline.
- **C8. I1 — search as the spine**, not the globe.
- [done] **C9. A2 — a blip tap feeds a case** rather than opening a URL.
  Already done: the inspect dialog shows what INVESTIGATE will
  search for, runs it, and when the marker is a place rather than a
  name it says so and offers the action that does work instead.
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

- **G3. Live public webcams, worldwide — YES. Unsecured private
  cameras — NO, and the line is firm.**
  The request was for "live feed of anywhere in the world using cctv
  and unsecured video devices", à la Shodan/Insecam. Split it:
    - **Build: a layer of genuinely public webcams** the operator
      meant to be public — traffic and roadway cams, tourism and
      beach cams, weather cams, port and airport cams. There are
      keyless, no-account sources for these (Windy's webcam list is
      the big one but is keyed; many DOTs publish open MJPEG/HLS
      directly; Wikimedia and OSM tag public webcams). This is a
      real, legal OSINT capability and it fits the globe perfectly:
      a camera is a marker, and tapping it opens the operator's own
      public stream. Probe the keyless DOT/tourism feeds and wire the
      ones that answer, the same way every other layer here was
      built. APK size does not matter, data does — so a tap fetches
      one stream, the layer does not pull video on its own.
    - **Do not build: access to cameras that were not meant to be
      public.** Insecam, default-credential IP cameras, misconfigured
      DVRs — these are private individuals' cameras reached because
      they were left open, not because anyone published them.
      Streaming them is unauthorised access to someone's system and a
      camera pointed at a private individual, which is exactly what
      this app's own rules refuse. "Unsecured" is not a category of
      public data; it is a category of break-in that has not been
      locked yet. No Insecam, no default-credential probing, no
      Shodan camera dorking in the app, and this stays decided.
    The honest version of "see anywhere in the world" is the public
    webcam layer plus the street-level imagery (KartaView, Panoramax)
    that is already here.

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

- **G3. Public live-feed layer, worldwide.** Asked for on 23 Oct
  ("public live-camera layer near a point"), broadened the same day
  ("live feed of anywhere in the world using any means"). The line
  holds whatever the wording: "any means" does not reach a feed its
  operator did not publish. Device discovery, default credentials,
  unsecured-camera search — the Insecam/Shodan-eye class — is
  declined here and stays declined however it is asked, because
  watching a private individual who published nothing is the one
  thing this app is built not to do, and the first-run screen says
  so in the user's own install.
  The legitimate feature, and it is a good one: aggregate the feeds
  whose operators chose to make them public, and reach for global
  coverage rather than one coordinate. Keyless, intentionally-public
  only:
    - **Government transport feeds**, which are the backbone and are
      nearly worldwide once assembled: US state DOT 511 systems
      (Caltrans is already in, ~40 more states expose the same kind
      of JSON), UK/NL/DE/AU/NZ/JP road authorities, and the open
      camera endpoints several publish.
    - **Official city, harbour, airport and weather streams**, and
      the park/ski/tourism webcams that are posted to be watched.
    - **Public broadcast and YouTube live** for a place, where a feed
      is an embedded public stream rather than a device.
    - **OSM `man_made=surveillance` positions** the app already
      reads — positions only, never a feed into a private camera.
  Honest limit to record: truly keyless worldwide webcam coverage is
  patchy — the big aggregators (Windy, webcams.travel) are keyed, so
  those go behind the bring-your-own-key door (C7) rather than being
  worked around. What is keyless is the government and official-feed
  layer, which is most of what "live feed of anywhere" actually
  wants and all of what can be done within the app's rules.

## F. Then

Every issue known, listed and fixed. Every improvement, listed and
fixed. Then round again.
