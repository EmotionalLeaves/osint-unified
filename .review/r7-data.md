# Review 7 — data layer / export  (open items)

1. DatabaseKey.kt:336-339 — `commit()` result discarded; a failed key write plus the
   plaintext-backup delete in AppDatabase.kt:322 loses every case unrecoverably.
2. DatabaseEncryption.kt:71-73 — plaintext -wal/-shm survive the rename for ever;
   `cleanUp(plain)` is never called on the success path.
3. InvestigationExporter.kt:340/450/492 — JSON/CSV fabricate a sha256 for rows that
   have none (and hash the stored, truncated copy; empty-string digest beside an
   error message).
4. GodEyeViewModel.kt:1340-1347 — buildExport reads _state.dismissals, which is
   emptyMap() in the window after openStored. HistoryViewModel.export re-reads.
5. Timeline.kt:92-93 — mines snapshot bodies; EntityExtractor and IntelSummary
   exclude snapshots and say why. Timeline.build also ignores dismissals.
6. IntelSummary.kt:24-34 — LOCATION/COORDINATE read from `ok`, not `structured`,
   so a snapshot's page speaks in the report's voice. Also :20 vs exporter :555
   give two different source counts in one document.
7. InvestigationExporter.kt:542-546 — IDENTIFIERS DISCOVERED reprints dismissed
   entities uncaveated.
8. InvestigationRepository.kt:338-370 — recordSnapshot does regex + SHA-256 over the
   whole page on Main; GodEyeViewModel.kt:1329 swallows the failure.
9. HistoryViewModel.kt:121-139 — caseLinks re-runs the whole case analysis on Main on
   every write to `investigations`.
10. WatchScreen.kt:148-154 + WatchRepository.kt:87-237 — CHECK NOW runs normalise/sha1
    on Main; runCatching discards the result and CancellationException.
11. InvestigationExporter.kt:46 — shared SimpleDateFormat across IO coroutines; one
    second of filename resolution, silent overwrite.
12. HistoryViewModel.kt:79-87/185-203 + HistoryScreen.kt:142/329 — export failure is
    indistinguishable from "no results"; writePdf close() not in finally.
13. InvestigationExporter.kt:440-470/480-495 — JSON/CSV label running rows
    "could not be reached"; :555 prints a total that excludes them.
14. AppDatabase.kt:292-310 — a migration failure is reported as an encryption failure
    and silently drops to plaintext. exportSchema=false, no migration tests.
15. Entities.kt:30-33 / InvestigationRepository.kt:169-173 — no FK despite the
    comments; deleteCase is not @Transaction.
