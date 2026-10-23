# Review 6 — OsintApis.kt / Executors.kt  (open items)

1. siteFiles 214-229: all four fetches fail -> "No robots.txt… found" = ABSENT.
2. Executors 1117-1121 / 1047-1052: Overpass 200-with-non-JSON -> "No military-tagged
   sites within 25 km." / "No mapped infrastructure" = ABSENT.
3. Executors 962-981 + 888-902: cctvNear OSM fallback turns 429 into "none mapped".
4. OsintApis 177-181 (reverseDns, dohTxt) and 241-254 (dns): DoH failure -> ABSENT.
   Also dohOrNull treats SERVFAIL (Status 2, no Answer) as empty.
5. OsintApis 769-777: GDELT 200 with non-JSON error -> "No news coverage" = ABSENT.
6. OsintApis 494-512: Hudson Rock vendor message is the first line; clean result = HIT.
   No "hudsonrock" case in classify. isBlank fallback asserts an absence.
7. Executors 1167-1175: "No mapped features within 500 m" matches no absence pattern
   -> HIT. Also appends raw Overpass JSON verbatim.
8. OsintApis 685-700: webcrawl first line always HIT; prints "Emails (0):" sections;
   unbounded body read.
9. Executors 544-579: directoryRun conflates failure with empty; SecAgent.NOT_SET
   unused; the 20-byte "returned data" threshold.
10. OsintApis 25, 834 + Executors 539: UA "OSINT-Unified/1.0 (research)" sent to
    target-controlled hosts, against Http.kt's stated policy.
11. Executors 956: new CctvDataSource per call + explicit client defeats both the
    6-hour cache and the disk cache. Nine inventories per run.
12. OsintApis 66-73: Cert Spotter pagination ignored but counted as complete;
    crt.sh wildcard body unbounded; getBody reads failed bodies.
13. OsintApis 138: `p=(\w+)` matches `sp=reject` -> wrong DMARC verdict.
14. OsintApis 723-730 (+106, 245, 823): unvalidated/unencoded input; wikiNearby turns
    a rejected request into an absence.
15. OsintApis 149, 341, 686: startsWith("http") accepts httpbin.org -> throws.

Below the cut:
- Executors 346, 386, 389, 425: runCatching swallows CancellationException.
- Executors 709-712: 40-hit cap checked before reading, so sdn-alt.csv never searched.
- Headers promising more than follows: OsintApis 207, 792, 327; Executors 1086-1087.
- Executors 1126/1134: "0 military-tagged sites" classified HIT.
- OsintApis 675-676: Twitch/Telegram pivots interpolate raw handle.
- OsintApis 622/624: isDomain still matches an IPv4 in dorks.
- OsintApis 217: siteFiles serial + deprecated root security.txt.
- OsintApis 473-482: ripeRouting serial per ASN.
- Executors 297-302: no HIT case for "unsanctions"; new OFAC string unmatched.
- OsintApis 44-53: stale comment describing a bug that is fixed.
