# Review 12 — Mgrs/Orbit/Decoder/Identify/Sanctions/Timeline/Graph/workflow

1. SanctionsNames 35-36: the 3-character token floor discards CJK/Korean names —
   "Ri Yong Ho" matches every OFAC entry containing "Yong"; "Li Na" is refused as
   too short. The UN path (Executors 808) has no floor, so the two lists disagree.
2. SanctionsNames 16-24: normalise() deletes every non-Latin script ("Владимир" -> "")
   and Ø/Æ/Ł/Đ/Þ/ß. The NAME_ORIGINAL_SCRIPT candidate at Executors 845 is dead.
3. OrbitPropagator 72-76: `return@repeat` is continue, not break, so the convergence
   test does nothing; at e≈0.99 the residual is 6.1e9 rad and a wrong sub-satellite
   point is plotted inside the altitude sieve.
4. GodEyeViewModel 1012-1021: a failed workflow step is swallowed and the pipeline
   reports completion; a 3-step chain can run step 3 on step 1's hosts.
5. ResultFormatter 12-17/37/91-99: fallback() only runs on a thrown exception, so
   non-NDJSON output parses to "0 subdomains" with fifty in the raw text.
6. Mgrs 29/47: at exactly lat 84.0 the Svalbard exception is skipped and 32X/34X/36X
   are emitted — grid zones that do not exist.
7. DecoderEngine 67-74: hexDecode strips non-hex, so the D of "MD5:" becomes a nibble
   and every later byte shifts; an odd trailing nibble is dropped silently.
8. Identify 154-155 -> DecoderEngine 34: the Base64url guess hands off to b64d, which
   uses Base64.DEFAULT and rejects - and _.
9. GraphBuilder 24: filters on status=="success", so "nothing found" and "unreachable"
   are drawn as findings. Timeline gets this right.
10. Timeline 64-77/99-100/118: IGNORE's "expires" kills INTERESTING's "expires on";
    "Last Modified" passes; and take(limit) after sorting keeps the 120 OLDEST events.
11. Identify 14-17: claims checksum verification it never performs (no Base58Check,
    no EIP-55); bech32 class admits 1/b/i/o.
12. Identify 44-50: the IPv6 pattern accepts up to 14 hextets.
13. ObservableExtractor 11/14: `[A-Za-z]{2,}` with no TLD list turns config.yaml,
    Mr.Smith and Done.Next into pivot targets; O(n^2) with no length cap.
14. ForceLayout 57-58/108-109: coerceIn throws on width<48 or height 0;
    GraphDialog 126-127 passes an unchecked height.
15. GraphBuilder 32: node id is `label.hashCode()` over third-party text — "Aa"/"BB"
    collide, so two findings silently merge into one node.

Below the cut: GeoExtractor.allCoordinates is not in order of appearance;
parseTimestamp overflows and uses the device timezone; the MGRS recogniser regex is
loose; geodetic(0.0, z) at the pole returns altitude -6400 km.
