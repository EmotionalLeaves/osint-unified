# Review 9 — DeepApis.kt / UsernameChecker.kt  (open items)

1. DeepApis 1886-1919 + 461-498 — get() throws on every non-2xx, so socialSweep files
   Bluesky 400 / Mastodon 404 / Docker 404 / Bitbucket 404 as UNREACHABLE: four
   platforms can never report an absence and the denominator is always wrong.
   Same mechanism kills breaches() at 546-552.
2. DeepApis 147-191 — githubActivity prints a 90-day absence from a null events array
   (403 rate limit, 404, network). Gists count is the per_page cap.
3. Bare runCatching leaving a false absence: 901 (OTX), 849 (network data), 717
   (host intel), 645 (subdomain sweep), 1743 (CVE), 1073-1077 (PGP), 1230 (photos).
4. DeepApis 753-776 — ipReputation ignores the DoH `Status` field (SERVFAIL/REFUSED
   read as clean) and the all-refused case still prints "Not listed on any blocklist
   that answered." Same Status-blindness in typosquat 1530-1534.
5. UsernameChecker 195/211/508 — accepts() is tested against the URL-ENCODED name, so
   every non-ASCII handle is NotApplicable everywhere; that shrinks the denominator
   and raises coverage. Also Java \w is ASCII-only vs Python's Unicode \w.
6. UsernameChecker 330-333 vs 410/428 — the `~` legend claims a control probe that
   anything past CONTROL_PROBE_CAP or the 12 s budget never got.
7. DeepApis 1628-1651 / 1586-1609 — historicalWeather and sunAndTerrain are documented
   as testing a *claimed date* and cannot take one. Also 1605 `1/tan(elev)` with a
   geometric elevation prints "572.96x" near sunrise.
8. DeepApis 712 — hostIntel's external-link test is a bare substring match.
9. DeepApis 523-535 — osmUser averages bbox centres (unweighted, antimeridian-broken,
   no area filter) and calls it where the person lives. 518 reads changesets_count
   as a changeset tag.
10. DeepApis 1911-1913 — get() reads every body unbounded, and retries 5xx once.
11. Headers stating a total over a truncated body: 700-701, 879-880, 311-312, 936-937,
    1116-1117; and page caps reported as totals: 895, 1172. 433-434 is the right shape.
12. DeepApis 1018-1032 — secEdgar zips names to CIKs by index after .distinct() on one
    of them; 1040's "No registrant matched." sits under a header so it reads as a HIT.
13. DeepApis 1418-1427 — emailPermutations' substringBeforeLast is case-sensitive
    against a lowercased domain.
14. UsernameChecker 190 — the ruleset-load failure omits VERDICT_FIELD. loadRules can
    hit the network from the non-suspend siteTemplates()/rulesetSummary().
15. Smaller: 222 (Mastodon P4033 URL shape), 1195-1208 (Panoramax bbox corner is 566 m,
    limit=30 reported as a count), 574-577 (overstates what in:email matched), 1723
    (unencoded path), 1324-1326 (own change addresses as counterparties), 1093-1095
    (hardcoded CC-MAIN fallback will 404).
