# What is still wrong, in the order it will be fixed

Ranked by three things together: how much it costs the person using
the app, how much of the app it touches, and how long ago it was
asked for. An old unmet request outranks a new nice-to-have.

Sources: the user's own reports, the God's Eye UX audit, and six
code reviews filed beside this (r6–r12).

---

## OPEN — the queue, 24 Oct

Everything below this section is a record of work already done, kept
for the lessons in it. **This is the list.** Sections A to H are
closed; their headings say so.

### Blocked on the tester
- **T1.** Did their APK come from the CI artifact or a local build?
  `assets/` and `jniLibs/` are empty in the tree and filled by the
  workflow, so a locally built APK has no sanctions lists, no
  disposable-domain list and no SQLCipher library — which would
  present as several unrelated bugs.
- **T2.** Is the globe smooth now? Their report predates every
  main-thread fix since.
- **T3.** Which OpenStreetMap URL asked them to sign in? (A3.) Every
  URL the code can emit is public; without the actual link this is
  guesswork.

### Open work, in order
- **O1. Layers as data** (was C6). One registry, not thirty-four
  booleans spread across three files. Every layer added since has had
  to be added in three places, and the header, the pill and the brief
  have each disagreed with the others at least once because of it.
- **O2. Search as the spine** (was C8, I1). The globe is the entry
  point and search is a box on it; the tester's "none of the search
  features really worked" is partly that.
- **O3. The subscribe half of the briefing** (was G2). Scheduled
  collection into the watch worker, clustering across collections
  rather than within one query, RSS beside GDELT, and a dated
  briefing with the analyst's notes attached. Worth much more now
  that a watch diffs entities rather than hashes: a briefing built on
  entity diffs says what changed, one built on digest diffs says a
  hash moved.
- **O4. More camera networks** (was G3). Probed 24 Oct: the CARS/511
  platform answers keylessly to its own query shape —
  `/List/GetData/Cameras?query=<url-encoded DataTables JSON>&lang=en`
  — and Idaho (457), Alaska (128), Wisconsin and Ohio (491) all
  return real records. **But the list endpoint carries no latitude or
  longitude**, only a road name and an image path, so they cannot go
  on a layer that fetches by where the view is without a second
  lookup per camera. That is the data cost the layer's whole design
  avoids. Blocked until a coordinate-bearing endpoint is found.
  Separately: a tap that opens the stream rather than a link —
  Wisconsin's records carry an HLS `videoUrl`.
- **O5. The rest of B3.** GitHub commit search by author-email
  (rate-limit fragile), GBIF, Open-Meteo air quality.
- **O6. The dossier as a LazyColumn** (deliberately deferred). It is
  a `verticalScroll` Column; `CardList` caps each section at twelve
  rows behind a SHOW MORE, which is most of the win. Converting the
  whole screen is a structural rewrite with no device here to look at
  the result. Revisit if the tester reports scrolling trouble.
- **O7. One map engine** (was C10, deliberately deferred). A rewrite
  of the whole map surface with no device here, and the seam it
  removes is now a seam crossed once.

### The rules this project has earned
1. **A fix goes where the bug lives, not where it was noticed.** Six
   of thirty-five defects in one day were reintroductions, and every
   one was a change applied at the site the author happened to be
   reading.
2. **A signature that cannot express failure will keep producing the
   same bug.** `migrateIfNeeded` returning `String?` caused the same
   catastrophic failure twice in a day.
3. **An absence must be established, never assumed.** "No account
   found" and "could not ask" support opposite conclusions, and this
   app exists to keep them apart.
4. **A header that states a total must not then list fewer.**
5. **Write first, assert after.** Twice today a script asserted after
   its replace and threw before its write, so an edit believed to be
   on disk was not — a quieter failure than a compile error.

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
- [part done] **B3. More sources that fetch.** People landed seven; phone got
  two on 23 Oct. Audited for the rest, same day. Candidates below,
  all keyless and all **unverified until the probe run answers** —
  the research pass could not reach any of them from the sandbox.

  - **B3-1. Image is the weakest type and has the clearest wins.**
    Two of the three need no network at all, because ExifReader
    already downloads the bytes:
    - **C2PA / Content Credentials**: parse the embedded manifest
      for capture device, edit history, and the
      `digitalSourceType` flag that marks AI-generated media, with
      a signing chain behind it. Nothing in the app touches the
      provenance question and it is the one that matters most now.
    - **Embedded EXIF thumbnail**: many JPEGs keep a thumbnail that
      was never re-rendered after a crop, so it shows the
      uncropped original. One more read on an object already
      constructed.
    - **Wayback CDX on the exact image URL**: earliest capture is a
      floor on the image's age, and a repeated digest across
      captures proves the bytes are unchanged.
    - **HTTP HEAD**: true content type against the claimed
      extension, exact size, last-modified. Headers only, so it
      costs nothing on mobile data.
  - **B3-2. Username**: chess.com `pub/player`, lichess
    `api/user`, Codeforces `user.info`, Wikimedia `globaluserinfo`
    (every wiki an account touches, the OSM tool's equivalent for
    Wikimedia), Codeberg/Gitea `api/v1/users`, Hugging Face
    `api/users/overview`. All return real profile fields — name,
    city, employer, bio, declared links — not a yes/no.
  - **B3-3. Email is genuinely scarce keyless.** Everything that
    answers "what is this address in" is keyed now. Real adds:
    Libravatar with `d=404` (a 200 means a federated avatar
    exists, and it catches people who avoid Gravatar);
    keyserver.ubuntu.com HKP index parsed rather than linked, as a
    second PGP corpus — graded lower than keys.openpgp.org because
    it does not verify the address; GitHub commit search by
    author-email, which leaks a real name from git config even
    when the profile hides it, but is rate-limited hard; and a
    bundled disposable-domain list, offline, which reframes
    everything else in a dossier.
  - **B3-4. Coordinate is near-saturated** and should not be
    padded. Two worth adding, both on the Open-Meteo client
    already here: timezone resolution, which Nominatim does not
    give and chronolocation needs; and air quality. GBIF species
    occurrences is the only genuinely new source and it is niche.
  - **Rejected on safety**: an endpoint returning cleartext
    email:password pairs. Breach *metadata* — which breach, what
    fields — is the line this app already draws and it stays
    drawn.

## C. The globe audit, remaining items

- [done] **C1. G2 — vectors re-projected every frame.** With the sphere on
  the GPU this is the last per-frame trigonometry on the main thread.
  Level-of-detail by zoom, decimated once off-thread, not a cache
  keyed on a camera that changes every frame.
- **C2. G4 — one Canvas repainted wholesale.** Bake the static layers
  into a cached layer keyed on the camera; redraw only markers.
  Reconsidered 24 Oct and **not worth building as described**: with
  the sphere on the GPU the static part left is the graticule and
  the borders, and a cache keyed on the camera is re-baked on every
  frame of a drag, which is the cost it was meant to remove. C1
  took the allocation and the redundant trig out of that path
  instead, which is where the cost actually was. Revisit only if a
  profile on a real device says otherwise.
- [done] **C3. G5 — pollers rebuild the whole point set.** Diff per layer.
- [done] **C4. L1 — 34 flat pills with a search box to find a pill.** One
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

## D. Correctness by review, r6-r10 — closed 24 Oct

Audited item by item against the current source. Of the items still
listed as open, **thirty-five had already been fixed in earlier
rounds and never ticked off** — the list had become a record of what
had once been wrong rather than of what was. Ten were genuinely
open, and all ten are now done:

- photosNear's three bare runCatchings, which printed "No geotagged
  imagery found near 51.50735, -0.12776" — a London street corner
  with thousands of photographs of it — whenever the connection
  failed. A stated clean sheet manufactured by the user's own
  network, and counted as a source that answered.
- The `~` mark, appended from whether a site is tested by status
  rather than from whether anything had tested it, while the control
  pass only probes the first 400 hits inside 12 seconds. The legend
  then told the investigator every mark meant "confirmed by a control
  username". Unprobed hits are `?` now, with the reason and the
  numbers.
- The chronolocation pair, which could not be told the date they were
  documented as checking. See G-6; this was the larger half of it.
- Panoramax's 566 m corner reported as "within 400 m", and its page
  size reported as a count.
- Eight more headers stating a total and then listing fewer.
- The ruleset-load failure, which omitted the verdict field and
  relied on a prose fallback to be classified correctly.
- AreaReport rebuilt on the main thread in the dossier on every feed
  poll — the globe's copy had been moved and this one missed.
- The tool directory filtering twelve hundred leaves and several
  thousand templates on the thread drawing the keystroke.
- The watch sweep and the diagnostics self-test, both launched into
  the screen's own scope, so leaving the tab mid-run cancelled them
  silently — up to a hundred requests' worth of mobile data spent and
  discarded. Both now live outside any screen, and the self-test is
  bounded to twelve at once rather than fanning out unbounded.
- A lifetime changeset count read out of a changeset's free-text
  tags, and every graph label re-measured on every pan frame.

**Deliberately not done.** The dossier is still a `verticalScroll`
Column rather than a LazyColumn. `CardList` caps each section at
twelve rows behind a SHOW MORE, which is most of the win; converting
the whole screen is a structural rewrite with no device here to look
at the result. Revisit if the tester reports scrolling trouble.

## E. People, continued (task #50)

Seven sources landed. Still to do: use PersonName.variants in the
sources that search by name; a person-shaped dossier rather than the
infrastructure-shaped one; and the same audit for the other
observable types (B3).

## G. Asked for on 23 Oct

- [part done] **G3. Live public webcams, worldwide — YES. Unsecured private
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

- [done] **G1. Image geolocation — GeoSpy, GeoInfer, Picarta.**
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

- [part done] **G2. Taranis AI.** Open-source, self-hosted, and its useful idea
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

## G-progress, 24 Oct

- **G1 done.** Keyless image geolocation: on-device OCR via a
  bundled ML Kit model, the words sorted into coordinates,
  domains, addresses, phone numbers, street names, plate-shaped
  strings and names, each with the tool here that takes it; the
  scripts present, which exclude most of the planet before a word
  is read; and the visual checklist no model does for you. Nothing
  uploaded, nothing claimed. **Still open:** C2PA / Content
  Credentials, which needs a JUMBF reader or the c2pa-rs JNI path
  and is the strongest remaining image question — was this
  AI-generated, what made it, and is that claim signed.
- **G2 part done.** News is clustered by shared headline terms and
  ordered by independent publishers, with a date range and a
  country count per story. **Still open:** the subscribe-to-a-topic
  half — scheduled collection into the watch worker, clustering
  across collections rather than within one query, RSS alongside
  GDELT, and a dated briefing with the analyst's notes attached.
- **G3 part done.** Four keyless public camera networks, fetched by
  where the view is. **Still open:** more of them — the US state
  511 systems, the European national feeds, tourism and weather
  cams — and a tap that opens the stream rather than a link.
- **B3 part done.** Six handle sources, two for addresses, three
  for images, two for phone. **Still open:** the bundled
  disposable-domain list, GitHub commit search by author-email
  (rate-limit fragile), GBIF, and Open-Meteo air quality.

## E2. Verified, 24 Oct

A reviewer went over the riskiest changes of 23 Oct and found
eight defects in them, three severe. All fixed the same day; the
list is here because the pattern matters more than the items.

- **Three of the eight were reintroductions of the exact bug the
  change had just fixed.** The panel measurement went back inside
  the scroller it was added to escape; the globe cull moved the
  disappearing markers from the centre of the sphere to its
  edges; the recompute key put the per-frame cost back under a
  different name. A fix that restates the problem in new terms
  reads as finished and is not.
- **A guard applied to one call site out of four.** The search run
  token went on the main fan-out and not on enrichment, a tool run
  by hand, or the workflow runner — all three of which sit in jobs
  the next search cancels. When a guard exists because an
  interleaving is possible, every site with that interleaving
  needs it.
- **A shared counter with three owners.** Set, incremented, and
  ignored, by three callers that all dispatch tools. It is a set
  of ids now.
- **Two classifiers read the first line of an output whose verdict
  is on the second.** Worth re-checking the rest of `classify`
  against what each tool can actually print, not against what the
  happy path prints.
- **A cap this app chose was reported as a fact about the world.**
  Truncating a body made "the source said nothing" and "we stopped
  reading" the same string. Anything that limits what is read has
  to say so in a way the caller can act on.

## F. The exhaustive sweep, 24 Oct

Two reviewers went over the two areas that had never had a
dedicated pass: everything below the lock gate (the screens, the
Activity, AppLock, LinkOpener) and everything that persists or
runs in the background (Room, the migrations, the exporter, Http,
the worker). Twenty-six defects between them. Worked in this
order, hardest-first within each severity band.

### F-a. State that lives below the AppLock gate

The whole of the first report's top finding and its #7 are one
root cause, which makes this the highest-value fix in the batch.

- [x] **F1. Unlocking replays every pending navigation signal.**
  `MainActivity` hoists `screen` and `tabState` above the
  `return@Surface` lock gate precisely because that gate disposes
  the subtree and a `rememberSaveable` only survives
  `onSaveInstanceState`, not plain disposal. The five
  de-duplication guards — `handledNav`, `handledShare`,
  `handledWatch`, `handledBrowserNav`, `handledSnapshot` — were
  left below it. Lock on, background, unlock: all five are zero,
  every `LaunchedEffect` re-runs, and the signals are still
  non-zero because they live in the ViewModel or in process-wide
  singletons. `navSignal` ≥ 1 after any search, so every unlock
  dumps you into the dossier. `investigateTarget` and
  `snapshotTaken` are never nulled, so the last browser target is
  re-searched with a full fan-out and the last snapshot re-filed.
  `sharedAt` is an Activity field, so the last share re-runs. Fix:
  the guards go above the gate with `screen`, and the browser
  singletons get nulled by their consumers.
- [x] **F2. With the lock on, the Forensics photo picker never
  works.** The picker is registered inside the composition;
  launching it stops the Activity, which sets `locked = true`,
  which disposes the subtree, which unregisters the launcher. The
  result arrives with nobody listening. Every time. Fix: register
  above the gate, or don't re-lock for an `onStop` this app
  caused by starting an activity for a result.
- [x] **F3. `sharedImage` is never cleared.** Nothing nulls it, and
  `ForensicsScreen` keys its effect on the Uri, so every
  re-entry — by hand, or after a lock cycle — re-reads the old
  share and overwrites whatever decode the user just ran. By then
  the URI grant has usually lapsed, so real work is replaced with
  "Could not read that image".

### F-b. Data the user loses or is lied to about

- [x] **F4. A persistent failure to open the encrypted database
  tells the user "nothing was lost" over an empty one.** First
  launch after the failure: no backup, no plaintext file, so the
  note is honest and `plain()` hands back an unopened builder
  whose first query creates a fresh empty database. Every launch
  after that: the plaintext file now exists, so the note becomes
  "the unencrypted copy of your cases is being used — nothing was
  lost". The real database is intact on disk, the user is looking
  at an empty case list being told it is their cases, and the
  reasonable response — reinstall — deletes the one file that
  still has them. Fix: only claim the fallback holds their cases
  when it predates the failure.
- [x] **F5. `migrateIfNeeded` deletes the plaintext database even
  when the backup rename failed.** `plain.renameTo(backup)`'s
  return value is discarded and `cleanUp(plain)` runs
  unconditionally. The unconditional part is right for the
  sidecars and wrong for the main file: on a failed rename there
  is now neither a backup nor an original.
- [x] **F6. The pre-encryption backup is saved without its WAL.**
  The rename moves the main file; the cleanup deletes `-wal` and
  `-shm`. The encrypted copy gets the WAL's committed pages
  because `copyInto` ATTACHes a live database; the backup does
  not. So the insurance is strictly older than the thing it
  insures, and the note still says nothing was lost. Fix:
  checkpoint before the rename, or rename the sidecars too.
- [x] **F7. `selected` never refreshes, so filing and tags go
  stale.** `flatMapLatest { flowOf(it).map { repository.byId(it) } }`
  wraps a one-shot suspend read, so it re-emits only when the id
  changes. File a target: the TARGETS list updates, the case chip
  does not move, and the tags button says SAVE TAGS forever
  because `tags.trim() == investigation.tags` can never become
  true. Two views of the same row disagree. Fix: a real
  `Flow<Investigation?>` query.
- [x] **F8. Pressing STOP leaves rows stuck at "running" for eight
  tools.** Bare `runCatching` around a suspend call catches
  `CancellationException`, so `runTool`'s cancelled branch — the
  one written under `NonCancellable` specifically to stamp the
  row — never runs, and the `resultDao.update` it falls through
  to throws in an already-cancelled coroutine. The row is never
  written at all. The earlier note said four places; there are at
  least eight, including the username sweep, which is the
  slowest and most-used tool in the app.

### F-c. Correctness the user can see

- [x] **F9. AppLock opens the app when it cannot determine an
  authenticator.** `usable()` returns null on everything but
  `BIOMETRIC_SUCCESS`, and `prompt()` then calls `onUnlocked()`
  with no authentication. Five failed fingerprints is lockout, and
  the next background cycle lets anyone in. A transient
  `HW_UNAVAILABLE` does the same. The intent — don't brick the app
  when the screen lock is removed — only justifies
  `NONE_ENROLLED` and `NO_HARDWARE`.
- [x] **F10. No `launchMode`, so a share stacks a second
  Activity.** `standard` means `onNewIntent` is never delivered,
  so the listener `MainActivity` registers is unreachable for a
  share arriving while the app is running. A second
  `MainActivity` means a second `ViewModelStore`, a second
  `GodEyeViewModel`, and a second set of 26 feed pollers — the
  exact duplicate data bill `onStart`/`onStop` exists to stop.
- [x] **F11. A share silently destroys an in-flight search.**
  `onSearch` cancels every job and nulls the current
  investigation before it decides whether to fan out, so the
  "prepared, not fired" comment is true of the new target and
  irrelevant to the old one. RUN ALL in progress, share a link:
  40 requests cancelled, dossier replaced, pending notes write
  dropped. Also: the `consumedKey` guard is an extra on the
  Intent, and after process death the Intent is rebuilt from the
  parcel captured at launch, so recents re-fires the old share.
- [x] **F12. One case's shared identifiers under another case's
  name.** `openCase` is a synchronous combine; `caseLinks` goes
  through `flatMapLatest` → `mapLatest` → `flowOn(Default)` and
  re-reads every result row of every target first. `stateIn`
  holds the old value meanwhile, so case A's rows render under
  case B's heading with no loading state to tell them apart.
- [x] **F13. The database is opened on the main thread.** `by
  lazy` is SYNCHRONIZED and `MainActivity` resolves it during
  `setContent` and from a `LaunchedEffect`. Whoever gets there
  first runs the keystore unseal, `repairVersion`'s up-to-seven
  opens, and on first launch after the upgrade a whole
  `sqlcipher_export`, holding the monitor. ANR on the first
  launch after the encryption upgrade, worst for the people with
  the most cases.
- [x] **F14. System back in CASES skips the drill-down.** The
  in-screen arrow has three levels, the system handler has none —
  `"history"` falls to the else branch and jumps to the globe. And
  because the selection lives in the ViewModel, tapping CASES in
  the nav bar lands back inside the old target. There is no path
  from the nav bar to the CASES root.
- [x] **F15. Every piece of FrameworkScreen state is plain
  `remember`.** The one tab `SaveableStateProvider` cannot help.
  Four folders deep, tap GLOBE, tap TOOLS: back at the root with
  an empty box. And `remember(initialTarget)` keys the typed
  target on the last search, so a search finishing elsewhere
  replaces what the user typed.
- [x] **F16. The live case-links view collapses two targets with
  the same seed.** `associate { it.seedObservable to … }` where
  the exporter — whose own comments say filing the same observable
  twice is ordinary — keys by id. The report is right and the
  screen is wrong.

### F-d. Smaller, one fix each

- [x] **F17.** A double-tap deletes an investigation: DELETE and
  CONFIRM are the same 48dp target in the same place with no
  debounce, and the delete cascades results and dismissals.
- [x] **F18.** No scheme allow-list before `WebView.loadUrl`.
  `shouldOverrideUrlLoading` guards every navigation except the
  initial one, which is the one carrying the externally-supplied
  string — and `GeoBlip.url` comes from remote feeds. Blast
  radius is small (opaque origin, file and content access off)
  and the fix is one line in `sanitize`.
- [x] **F19.** An inline 429 retry clears the cooldown and never
  re-records a second refusal, so a host that has now refused
  twice has no cooldown at all.
- [x] **F20.** `Thread.sleep` inside the interceptor, up to ~15 s
  per call and once while holding the pacing monitor, against a
  64-wide sweep on a 64-thread `Dispatchers.IO`. Everything else
  on IO — including the export — queues behind it.
  `limitedParallelism` for the sweep. Same helper swallows
  `InterruptedException` and clears the flag.
- [x] **F21.** CSV exports carry no analyst notes and no ruled-out
  list; the case CSV does not even name the case. The one format
  where the investigator's own judgement is entirely absent.
- [x] **F22.** The export cache is only purged when another export
  is made, so a user who exports once leaves that dossier in
  `cacheDir/exports` for the life of the install.
- [x] **F23.** The plaintext database path is never pre-flighted,
  so a migration failure there surfaces as a crash from whatever
  screen queries first — on the path most likely to be running an
  old schema.
- [x] **F24.** A failed encrypted open leaks the Room instance and
  its SQLCipher connection for the life of the process.
- [x] **F25.** `writePdf`'s `document.close()` is not in a
  `finally`.
- [x] **F26.** The minor list: `getStringExtra` where the sender
  sent a CharSequence; the URL share branch with no `.take`;
  `trimEnd(')')` breaking `/wiki/Foo_(bar)` and the 8-character
  floor dropping short URLs silently; `NewCaseField`'s unkeyed
  `rememberSaveable` carrying a half-typed name between targets;
  REMOVE with no confirmation; the unchecked `EXTRA_STREAM`
  scheme; `applyProxy` persisting a host but not an invalid port;
  exports on `rememberCoroutineScope`; `FrameworkScreen`'s back
  popping folders the user cannot see; `openWatchId` never
  cleared; `WatchRepository.fingerprint` and
  `markRunningAsInterrupted` swallowing cancellation.
- [x] **F27.** `exportSchema = true`, commit the JSON, and
  `MigrationTestHelper` tests for 1→7, for 3→4 with duplicate
  observables (the only non-additive migration: it deletes rows
  and builds a UNIQUE index over live data), and for 6→7.
  Separately: `repairVersion` derives the version from table and
  column existence only, so a database that reached a version and
  lost an index is stamped "already there".

All twenty-six fixed, 24 Oct, in nine commits. One red build on
the way (r412): `Result.failure(...)` inside the package that
declares the Room entity `Result`, which the local pre-flight
could not see because without the Android SDK everything around it
was already unresolved. `tools/shadowed-stdlib.py` catches that
class now.

Two things worth keeping from the batch:

- **The cluster was one root cause wearing six faces.** Findings
  1, 2 and 7 of the first report, and 1, 2 and 10 of the second,
  were all "state declared below something that disposes it" or
  "a one-shot read dressed as a flow". A reviewer who had never
  seen the code found them in an afternoon; the author, who had
  written the comment explaining exactly why `screen` was hoisted
  above the lock gate, had not applied the same reasoning to the
  five guards eight lines below it.
- **Three of the twenty-six were the app lying about its own
  state**, and all three were the same shape: an absence reported
  as a fact. An empty fallback database described as the user's
  cases; a backup described as complete when its write-ahead log
  had been deleted; a stale case's links drawn under a new case's
  heading. Each one would make a reasonable person do the wrong
  thing.

## G. Improvements, 24 Oct

A pass over the whole instrument, asked not for defects but for what
would make it a better tool. Worked in this order. Everything here
is keyless, and the request cost of each is stated because mobile
data is the one budget that matters.

- [x] **G-1. The corroboration engine cannot see a name, an employer
  or a place.** `EntityExtractor.extract` collects ten kinds and all
  ten are machine identifiers — email, handle, host, ip, phone,
  wallet, tail number, coordinate, sha256, md5. But the sources
  added in the last two rounds return labelled soft attributes:
  chess, lichess, codeforces, codeberg, huggingface, github, ghname,
  orcid and nonprofits all emit `Declared name:`, `Declared
  location:`, `Company:`, `Institutions:`, city and state. So the
  single most valuable thing a username search can produce — five
  platforms independently saying Vienna — never reaches the engine
  built to say exactly that, and for a person's name, which routes
  to fourteen fetching tools, the corroborated section is near-empty
  by construction. The analyst reads six cards and does the merge in
  their head. Needs: a reader for `Label: value` lines (these are
  already structured, so a parse rather than any guessing at prose),
  three new kinds — PERSON, ORG, PLACE — and a bundled place-alias
  table so Wien, Vienna and AT merge rather than becoming three
  findings. **Zero new requests.** The existing machinery — the
  independence families, the grade, CaseLinks — does the rest.
  `ProfileSignals.cluster` already does this shape of work and is
  called from exactly one place inside the username sweep; it never
  sees any of the nine sources above.
- [x] **G-2. The grading scheme can agree and cannot disagree.**
  `Grading.credibility` takes a `contradicting` parameter and has an
  IMPROBABLE verdict for it. No caller passes it: both call sites
  pass `agreeing` only, so IMPROBABLE is unreachable in the shipped
  app. ipinfo says Germany and rdap says Netherlands, and the two
  sit side by side each graded "probably true" — the disagreement,
  which is the finding, is left for the reader to notice.
  `Grading.Kind` (OBSERVED / REPORTED / INFERRED / OPEN) is
  referenced nowhere at all: the model has an axis for "the app
  worked this out" and nothing has ever been put on it. Needs: a
  list of which attributes are single-valued per subject, the
  opposition count passed into the call that already accepts it, a
  section of its own for disagreement, and INFERRED stamped on
  anything derived so a conclusion never reads as an observation. A
  useful subset — coordinates, PTR, registration country — works
  without G-1.
- [x] **G-3. A URL runs two sources and never looks at the host.**
  `Observable.Url` routes to six tools, of which two are link lists
  and two are `touchesTarget` and so do not fire on the passive
  default. Searching a URL asks whether it is in the Wayback index
  and what the latest capture is. The host is not investigated at
  all: none of the eighteen a bare domain gets. This is the most
  common entry point on a phone — a link shared in from another app
  lands exactly here. And `snapshot`, the one tool that preserves a
  page as evidence, is registered under type url with nothing
  routing to it. Needs: decompose the URL — host to the domain list,
  exact URL to wayback, commoncrawl, urlscan and archive on the
  path — and route snapshot from the url type. **This multiplies
  requests: roughly 2 passive fetches to roughly 20.** That is the
  bill a domain search already pays, and the dossier prints the
  planned count before it fires.
- [x] **G-4. A watch can tell you something changed and can never
  tell you what.** Each check reduces a tool's whole answer to a
  sha1 of its normalised text, so an event can only say "changed at
  Shodan". And `runTool` replaces the previous row, so the previous
  answer is gone from the database too: a target watched for three
  months has a hundred events saying a source changed and not one
  byte of what it said. Needs: store the extracted entity set per
  check rather than the text — a few hundred bytes a target, not the
  growing archive the current design was avoiding — and diff sets
  instead of hashes. The event then reads "new host
  mail2.example.com; declared location changed Vienna to Berlin".
  **Zero new requests:** the check already has the answer in hand
  before it throws it away. Also gives a case re-run a diff.
- [x] **G-5. The analysis does not know the target from something one
  hop away.** The screen and the exporter both separate first-pass
  rows from rows looked up because the first pass named them.
  `Corroboration.merge` has `row.input` and drops it, and `Entity`
  has no field for it — so a host found only in a pivoted IP's
  results is listed, graded and exported exactly like a host found
  in the seed's own crt.sh, and IntelSummary can take a pivot's
  hosting city as the dossier's headline LOCATION. Needs: a `hops`
  field, shown, and hop-1 rows barred from setting a headline fact.
  Small, and it gets worse rather than better once G-1 enlarges the
  entity pool — so do it with G-1.
- [x] **G-6. The chronolocation tools cannot be given the date.**
  `sunAndTerrain` computes the solar position for now;
  `historicalWeather` hardcodes the last 31 days. Both exist to test
  whether a photograph matches a claimed time and place, and neither
  can be told the time — while the same dossier routinely holds the
  EXIF capture timestamp and often the GPS. The analyst reads a date
  off one card and compares it with a sun position for the moment
  they happen to be reading. Needs: an optional date on both, a
  parser over the EXIF output, and one Open-Meteo archive call for
  that specific day — then state the result: "shadow azimuth 112
  degrees, length ratio 1.6, consistent with 14:30 on 2 March", or
  "the sun was below the horizon". Pure arithmetic over data already
  fetched, and no keyless competitor offers it.
- [x] **G-7. The timeline is a list of dates and every interval is
  computed by hand.** `Timeline.build` returns a flat chronological
  list and the only aggregation is by year. The finding in a
  timeline is almost never a date; it is a gap — the domain
  registered three days before the account, six accounts created in
  one fortnight, a certificate issued the week of the breach. Every
  one is a subtraction the app has both operands for. Same shape:
  the commit-hour peak is printed as a string and never turned into
  a candidate UTC offset, nor checked against the declared location
  the same dossier holds. All derived, no requests, and everything
  it concludes must be stamped INFERRED (see G-2).
- [x] **G-8. The dossier never says what it cost.** `DataMeter` is
  process-wide, unpersisted, and surfaced only in the diagnostics
  panel. A search that fires forty tools and then eleven pivots at
  six tools each never reports its own bill, before or after — the
  header prints the planned tool count and nothing about bytes. On a
  metered phone that is the number the person actually needs. Needs:
  attribute bytes to the in-flight search (the interceptor already
  has the host and the count), print what the dossier cost, and keep
  a per-tool running average so the pre-flight can say "about 8 MB"
  before RUN ALL.
- [x] **G-9. `websearch` is classified as a bookmark and is not
  one.** B1 swept it into LINK_LISTS with `dorks` and `framework`,
  force-classified ABSENT and graded F. It fetches the DuckDuckGo
  instant-answer abstract and up to eight Wikipedia articles with
  snippets, and emits no search-engine links at all. It appears in
  the email, username, phone, wallet, aircraft and name lists, so in
  six of twelve types a real source is excluded from the findings.
  Grade it C and let it into the pool. `dorks`, `framework` and
  `reverseimage` are correctly classified and stay.
- [x] **G-10. Wallet and aircraft are 1:3.** Both have a second
  source already written as a curated template and not wired: a
  second block explorer would give wallet a real independence pair,
  and the second registry would do the same for a tail number.

### The recount, as actually fired

Fetch-and-parse to link-only, with tools that cannot run (cloud with
no backend, local with no binary) and `toolrun` (never in an
automatic set) excluded: domain 18:2 · username 16:3 · name 14:2 ·
ip 11:2 · email 11:3 · coordinate 10:1 · organisation 8:2 · image
5:2 · phone 3:3 · **url 2:2** · **wallet 1:3** · **aircraft 1:3**.
Phone and image were fixed on 23 Oct and are no longer the worst;
the three types that have got worse relative to the rest are the
three that gained a chip after the last count.

### Keyless camera networks, probed 24 Oct

The CARS/511 platform answers without a key, but only to its own
query shape: `/List/GetData/Cameras?query=<url-encoded DataTables
JSON>&lang=en`. Without the query parameter it returns
`recordsTotal` and an empty `data` array, which is what made it look
dead. Confirmed with real records: **Idaho (457), Alaska (128),
Wisconsin (491), Ohio (491)**. Nebraska, Iowa and Minnesota serve
HTML on that path and need their own shape; New Jersey 403s;
api.511.org, Trafikverket, DriveBC and Ontario all want a key.

All ten done, 24 Oct. Three red builds on the way, each now
impossible locally: a table built from lists declared below it in
the same file (`init-order.py`), a constant used and never declared
(`missing-constants.py`), and `Result.failure` in the package that
declares the Room entity `Result` (`shadowed-stdlib.py`). The three
checkers that blank comments and strings share one implementation
now, because the first two were wrong about the same thing — a
string template is a string containing code containing a string,
and scanning quote to quote ends the outer string at the inner
one's opening quote.

What the batch came to, in one line: **the app can now state things
it previously only held.** Five platforms saying Vienna is one
finding with four witnesses rather than four one-witness findings;
two sources answering a one-answer question differently is a
section rather than two rows both reading "probably true"; a
finding one step from the seed says so; a watch says what changed
rather than that something did; the gap between two dates is
computed; a quiet window is compared with a declared city; and the
dossier says what it cost. None of it needed a new source or a new
request.

## H. Verified, 24 Oct — thirty-five defects in one day's work

Two reviewers went over the day's forty commits, one on the
analysis and source layers, one on the UI, lifecycle and
persistence. Seventeen and eighteen defects. **Six were
reintroductions of the exact bug the change had just fixed**, which
is the same proportion as the last verification pass and the thing
worth learning from.

Worked hardest-first. Severe:

- [x] **H1. The photo decode cancels itself.** `ForensicsScreen`'s
  effect calls `onImageHandled()` — which nulls its own
  `LaunchedEffect` key — and then suspends in `fromUri`. The next
  recomposition cancels it before `output` is ever assigned, and
  `exifBusy` has no `finally`, so the screen sits busy forever.
  Every pick, every share. The picker was hoisted above the lock
  gate this morning and the result is thrown away one line later.
- [x] **H2. The coordinate-with-date input breaks six of the ten
  coordinate tools.** `parseCoord` learned to tolerate a third
  field; `cctvNear`, `osmRecon`, `militarySites`, `overpass`,
  `revgeo` and `wikiNearby` all still demand exactly two and throw.
  The dossier reports six sources unreachable because the app
  malformed the question — and reverse geocoding, the tool that
  says where you are, is one of them. Literally "a guard applied
  to one call site out of four".
- [x] **H3. The same input reopens the seed-echo bug.**
  `sameCoordinate` splits on commas and requires two fields, so
  with a date on the seed the echo guard is dead and the user's own
  coordinate comes back graded "confirmed by other sources". The
  comment above that guard describes this exact failure as fixed.
- [x] **H4. `orStop()` went on the call that cannot throw and not on
  the one that does.** In `siteFiles` and `ripeRouting` the inner
  `runCatching` wraps a blocking `getBody`, so the guard is a
  no-op; the cancellation arrives at `it.await()` inside the outer
  bare `runCatching`. Press STOP during an IP dossier and the row
  is written as a *successful* result asserting RIPEstat was
  consulted and failed. `PeopleApis.codeAccounts` has it right
  three lines away.
- [x] **H5. Every coordinate in a coordinate dossier contradicts
  every other.** `exclusiveGroup` puts all COORD values in one
  competing set with no notion of what each is a coordinate *of* —
  so nine camera positions from `cctvNear` make each other
  IMPROBABLE. And two renderings of one point at four and five
  decimals contradict each other, because the comparison is on raw
  strings while `sameCoordinate` exists and is not used.
- [x] **H6. A failed encryption migration reports success over an
  empty database.** `migrateIfNeeded` returns a non-null string for
  both success and failure, and the caller treats non-null as the
  note. On a failed verification Room then creates a fresh
  encrypted file, the probe passes, `encrypted = true`, and the
  note reads "the copy did not match the original, so nothing was
  changed" over an empty case list — while the real database is
  never looked at again, because `encrypted.exists()` short-circuits
  every later launch. Exactly what this morning's fix set out to
  prevent.
- [x] **H7. Duplicate `items` keys crash the TOOLS tab on search.**
  Two `items()` in one LazyColumn share a key space, and the
  directory's four markdown catalogues dedupe URLs within a
  catalogue and not across them. Type "shodan" and the list throws.
- [x] **H8. `liveNow` moved off the main thread with the
  under-keying intact.** The same three layer lists as keys, for a
  function that sweeps thirty-five — so arming TSUNAMI or CONFLICT
  never refreshes the dossier's live-area block. The globe's copy
  keys on the whole state. Reintroduction, in the commit whose own
  comment names the bug.
- [x] **H9. `GA` is the country Georgia, and `IN` is India.** The
  ambiguous-code rule intersects *codes* only, so a name collision
  between a US state and a country is invisible to it. "Atlanta,
  GA" is filed as a claim about the Caucasus and then contradicted
  by its own posting hours. Indiana and New York are missing from
  the subdivision table entirely, so `IN` falls through to India
  and `NY` resolves to New York City, merging Albany with
  Manhattan.
- [x] **H10. Candidate lists are read as assertions about the
  subject.** `ghname` reads five different GitHub accounts that
  merely share the searched name and prints `Name:`/`Company:`/
  `Location:` for each — its own output says they are not the same
  person until something ties them together, and the parser does
  not read that line. Two strangers both in London become a
  corroborated finding; the dossier's headline LOCATION can be the
  third unrelated account's city; and the candidates' names all
  contradict each other as PERSON. `orcid` has the same shape with
  twenty records.

High and medium, in the order they will be worked:

- [x] **H11.** An IP dossier marks both its geolocation and its
  registry country IMPROBABLE — a prefix registered in the US and
  geolocated in Germany is the ordinary state of the internet.
  `exclusiveGroup` groups on "is this a country" and not on what
  each source is describing.
- [x] **H12.** One Tor relay lookup asserts five contradicting
  countries: the label is per-relay and the parser reads it as
  per-subject.
- [x] **H13.** `ActivityHours` is confidently wrong at UTC+12 and
  prints "UTC−14", which is not a real offset — the band is plain
  integer containment on a circular quantity. Plus table errors:
  Kenya, Uganda and Ethiopia at +2 (they are +3), Lebanon at +3
  (+2), Costa Rica at −5 (−6), and Mexico and Indonesia present at
  all when the doc says multi-zone countries are excluded.
- [x] **H14.** The watch notification's intent gets none of the
  `consumedShare` protection, and with `singleTask` it becomes the
  task's root intent — so every cold restore from recents re-fires
  it.
- [x] **H15.** The notification's scroll-to-row never happens:
  `onFocusHandled()` fires on the first composition, while the list
  is still empty.
- [x] **H16.** "Nothing was lost" and the locked-out warning both
  disappear after the user creates one case, because both decide
  from "does the fallback have any rows".
- [x] **H17.** A swallowed checkpoint brings the WAL loss straight
  back: `checkpoint` is a bare `runCatching` and the sidecars are
  deleted unconditionally before the `if (kept)` branch.
- [x] **H18.** `whatFailed` blames the key for a missing native
  library, because `dlopen failed: library "libsqlcipher.so"`
  contains "sqlcipher" and that branch is tested first — the exact
  mis-blame that sends somebody to reinstall over an intact
  database.
- [x] **H19.** `SelfTest` loses results to a read-modify-write race
  on `_results.value = _results.value + (...)`.
- [x] **H20.** `rememberTwoTap` passes the id as an *input*, not as
  the saved-state key, so inside a plain `forEach` the armed state
  is restored positionally and one tap can unfile the wrong target.
- [x] **H21.** Every real export failure is reported as "there is
  nothing to export": `export()` swallows the exception before
  `runExport` can see it.
- [x] **H22.** An export requested while one is running is dropped
  silently, and an export that finishes after the screen is left
  pops the share sheet on the next visit.
- [x] **H23.** The first watch check after the upgrade names every
  identifier as new, because the diff runs in the same pass that
  fills the baseline in. And the entity cap plus unsorted
  extraction makes a reordered upstream look like movement — a
  false *specific* claim where the old alert was merely vague.
- [x] **H24.** `ActivityHours` is handed places regardless of hop,
  so the "declared location" can be a pivoted IP's hosting country.
- [x] **H25.** The URL host wave never fires for a shared link,
  which is the entry point it was written for: `alsoTheHost` is
  called only from `autoRun`, and a share is `fanOut = false`
  followed by RUN ALL.
- [x] **H26.** `sunAndTerrain` queries the archive for dates it does
  not have and prints "sunrise null, sunset null" as a finding; a
  rejected date is reported as no date given; and `Calendar` is
  left lenient so 31 February rolls to 3 March and is printed as
  "the moment you gave".
- [x] **H27.** phoneinfoga's `Country:` line produces three junk
  places and no country, because `Places.parts` splits a line that
  was never a place list.
- [x] **H28.** The timezone finding is hidden behind
  `if (timeline.isNotEmpty())`, so a dossier with the disagreement
  and no dated events shows nothing.
- [x] **H29.** `AppLock.noWayIn` opens the app on API 26–29 when
  biometrics are un-enrolled but a PIN remains.
- [x] **H30.** The dossier's cost line measures the whole process —
  every feed poller and map tile — not the search.
- [x] **H31.** `Http.bulk = 32` makes the sweep's own `Semaphore(64)`
  dead and halves a two-thousand-site sweep, and the rationale
  contradicts itself; the real risk is head-of-line blocking from
  the 20-second per-host gate.
- [x] **H32.** `singleTask` was heavier than needed: `singleTop` is
  the minimal fix, and `singleTask` breaks Back from a share and
  the `PROCESS_TEXT` result contract.
- [x] **H33.** Smaller: ORG values get no punctuation stripping and
  `|` is the watch digest's own separator; `Control.SAYS_YES_TO_
  ANYTHING`'s mark is unreachable; `aircraftSecondOpinion`'s
  `Country:` enters the country group; `c2pa` is declared without
  `touchesTarget` while `exif`, which fetches the same file the
  same way, has it; three platforms saying Germany produce two
  entities because `valuesOf` throws the label away before calling
  `Places.parts`.

### What this says about the day

Six reintroductions out of thirty-five. Every one has the same
shape: **the fix was applied where the bug was noticed and not
where the bug lives.** `parseCoord` learned about dates and six
sibling parsers did not. `orStop` went on the call the author was
looking at rather than the one that can throw. `liveNow` was moved
to a background thread with its keys copied across unexamined. The
coordinate precision guard was written for the seed and not for
the new contradiction path.

Two signatures keep producing this: `migrateIfNeeded` returning
`String?` for "nothing to do", "succeeded" and "failed" alike, and
`rememberSaveable(key)` taking its first argument as an input
rather than as the registry key.

All thirty-five fixed, 24 Oct. Six red builds along the way, five
of them in this section — three Kotlin mistakes the local checks
now catch, and twice a script that asserted after its replace and
threw before its write, so an edit I believed was on disk was not.
That is its own lesson: **write first, assert after**, or the
failure is silent in exactly the way a compile error is not.

Three of the fixes changed a signature rather than a line, which
is the only kind of fix that stops the bug recurring:

- `migrateIfNeeded` returns a sealed `Move` instead of `String?`.
  That one signature produced the same catastrophic bug twice in
  one day — a failed migration reported as a success over an empty
  database — because "nothing to do", "succeeded" and "failed"
  were indistinguishable to the caller.
- There is one `Coordinates` parser, where there were seven. Six
  of them did not know about the date the seventh had learned.
- `rememberTwoTap` passes its id as the registry key rather than
  as an input, so armed state can no longer be restored onto a
  different row.

And one rule earned by repetition: **a fix goes where the bug
lives, not where it was noticed.** Six of the thirty-five were
reintroductions, and every one was a change applied at the site
the author happened to be reading.

## I. Then

Round again.
