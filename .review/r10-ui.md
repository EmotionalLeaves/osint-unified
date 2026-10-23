# Review 10 — ui/ outside godeye, network, manifest  (open items)

1. MainActivity.kt:174-179 — the app-lock `return@Surface` sits above
   rememberSaveable/SaveableStateHolder, so every background cycle resets the tab and
   every layer toggle. Make the lock an overlay.
2. MainActivity.kt:108-138/229-242 — consume() stamps currentTimeMillis(), and
   getIntent() is re-delivered after a rotation: a shared target re-runs the whole
   investigation, wiping notes and dismissals. Same for the notification path.
3. InvestigationScreen.kt:166-190 — six remember(state.results) blocks run
   Corroboration.merge / EntityExtractor / IntelSummary / Timeline during composition
   on Main, once per arriving result.
4. InvestigationScreen.kt:192 — verticalScroll Column, not LazyColumn; every
   ResultCard composes at once and each runs ResultFormatter + ObservableExtractor.
5. PrivateBrowser.kt:112-115 — BackHandler is outside the Dialog, so in-page Back
   closes the browser.
6. PrivateBrowser.kt:84 — newSingleThreadExecutor() per useProxy call, never shut
   down; MoreScreen calls it per keystroke.
7. MoreScreen.kt:213-225 + Http.kt:133-144 — proxy applied per keystroke (port 9, 90,
   905); InetSocketAddress resolves a hostname on Main -> NetworkOnMainThreadException
   swallowed -> switch reads ON while traffic goes direct.
8. HistoryViewModel.kt:121-139 — caseLinks on Main (also in r7).
9. InvestigationScreen.kt:663-668/975-983 — export has no busy flag, null is silent,
   shareFile is the one unguarded share. GraphBuilder.build on Main at :660;
   showGraph/graphData are plain remember.
10. AndroidManifest.xml:44-58 + MainActivity.kt:229-242 — any app can start a full
    investigation at a target of its choosing with no confirmation. Land on the
    dossier pre-filled instead.
11. FrameworkScreen.kt:184-197 — filters thousands of entries per keystroke in the
    composable body; countLeaves recurses per row per recomposition; no list keys;
    `stack` is not saveable.
12. WatchScreen.kt:148-154, DiagnosticsScreen.kt:188-206 — work tied to
    rememberCoroutineScope is cancelled by a tab switch; diagnostics fans out
    unbounded across Dispatchers.IO where Politeness blocks with Thread.sleep.
13. ForensicsScreen.kt:53-56/73 — state is not saveable; sharedImage is never cleared
    so re-entering the screen re-reads and overwrites.
14. GraphDialog.kt:512-514/546-567 — writes Compose state during draw; measures every
    label every frame against an 8-entry cache.

Smaller: FirstRunScreen insets; HistoryScreen `confirming` never resets; ResultCard
`copied` never resets; ToolTemplates.kt:336-343 unencoded {LAT}/{LON}; Http.kt:85
cooldowns never pruned.

Disagreement to settle: this reviewer defends Http.kt's Chrome UA (correctly). The
r6 finding stands anyway — OsintApis.getBody OVERRIDES it with
"OSINT-Unified/1.0 (research)" on every request, which is the opposite of the policy.
