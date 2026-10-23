# Review 8 — ui/godeye  (open items)

1. EarthTexture.kt:112 — a Bitmap allocated per rendered frame, never recycled.
   (Superseded for API 33+ by the GPU path; still live on the CPU fallback.)
2. AreaReport.kt:90-166 — build() sweeps every layer on Main from three call sites.
3. GlobeHandoff.kt:42-51 — spanDegrees mixes a width chord with a min-dimension
   radius: in landscape the globe never hands over to the map.
4. GlobeView.kt:446 — long-press invents `2.0 + zoom` instead of GlobeHandoff.tileZoom.
5. GodEyeViewModel.kt:732-740 — ensureRadiation swallows failures; the RAD pill sticks
   on "…" for ever.
6. GodEyeViewModel.kt:524-534 — onViewportChanged only calls ensureRadiation, so
   cameras/sites/airspace are never re-queried; the *Key machinery is dead.
7. GodEyeViewScreen.kt:388 — nothing clears visibleLayers when the screen leaves the
   composition; polling continues behind the dossier.
8. GodEyeViewScreen.kt:1142-1150 — ALL LAYERS off leaves radiationOn true.
9. WorldBrief.kt:118-124 — build runs in composition and fully sorts each layer to
   take six.
10. GodEyeViewModel.kt:222/376-379 — searchJobs is an unsynchronised MutableList
    mutated from invokeOnCompletion; CME on SEARCH/STOP.
11. GlobeView.kt:387 — pointerInput(points) re-keys on 2,500 elements and cancels an
    in-flight long press on every feed poll.
12. MarkerShapes.kt:75/91 — markerIcon allocates a Path inside draw().
13. GlobeView.kt:745-760, 947-964, 509, 517 — per-frame allocation in the draw lambda.
14. MarkerPlan.kt:140 — thinBy's comparator recomputes a regex-backed identity per
    comparison.
15. Header vs pill disagree about aircraft (GodEyeViewScreen.kt:468 vs :698;
    WorldBrief.kt:216; AreaReport.kt:145). WorldBrief.kt:297 headline drops the
    "FEED DOWN" mark. GodEyeUiState.lastSuccess is written and never read.
