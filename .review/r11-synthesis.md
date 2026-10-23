# Review 11 — Findings/Corroboration/Grading/IntelSummary/EntityExtractor/ProfileSignals

Method note: the reviewer reconstructed 54 real first lines and ran them through a
replica of the classifier. 44 of 54 classify correctly.

1. socialsweep writes no VERDICT_FIELD, so a zero-hit sweep reads as a finding and
   can then vouch for other tools in the username-sweep family.
   (DeepApis 481-499; Executors 317-331 claims otherwise.)
2. Six tools put a header on line 1 and the verdict on line 2 -> HIT:
   wallet (A, DeepApis 1310/1366), aircraftreg (1255/1266), edgar (A, 1017/1040),
   ghactivity (149/189), photosnear (1127/1231), webcrawl (OsintApis 695).
   The fix pattern already in the file is `headerOnly = sb.length` (DeepApis 683, 717).
   Findings.NOTHING's "no registrant matched" is dead config for this reason.
3. SecAgent.NOT_SET is returned as the whole output and classifies HIT: a source that
   was never consulted, graded A, appears as one that answered.
4. Corroboration 73: mailsec, typosquat and emailperm all resolve through the SAME
   dns.google endpoint as dns/ptr and are not in that family -> 2 "independent"
   witnesses from one query.
5. bgpview and asnintel are both RIPEstat and are in no family -> C1 from one upstream.
   ToolRegistry:363 also says asnintel uses hackertarget; it does not.
6. Grading 88: credibility counts agreeing sources without looking at their grade, so
   an E-grade sweep lifts an A-grade certificate finding from A2 to A1.
7. IntelSummary 24-34: LOCATION/COORDINATE read `ok`, not `structured` (same as r7 #6).
8. IntelSummary 20/138: SOURCES n/m counts absences; the same page prints
   "4 of 60 sources returned something" from Corroboration.assessment.
9. emailperm's GENERATED addresses enter the corroboration pool as assertions.
10. ptr: an IPv6 or CIDR seed builds a malformed in-addr.arpa query and the miss is
    reported as an established absence. torexit has a milder version.
11. Corroboration.INFRASTRUCTURE (26 families) and EntityExtractor.noiseDomains (5)
    disagree, and the exporter calls extract() directly -> cdn.jsdelivr.net etc. become
    pivots. Three noiseDomains entries can never match what registrable() produces.
12. ProfileSignals 262: FURNITURE is substring-matched against the whole URL — "t.co"
    eats every *t.com host; "linktr.ee/s" eats every Linktree handle starting with s;
    "about." eats /about.html.
13. ProfileSignals 261: !host.endsWith(pageHost) has no label boundary — netflix.com
    ends with x.com.
14. ProfileSignals 326-337: displayName/bio/avatar get no boilerplate filter, so two
    Cloudflare challenge pages cluster as "the same person".
15. Corroboration 105-116: independentCount is order-dependent — the same three
    sources grade A1 or A2 depending on DB row order.

Below the cut: Findings.saysNothing files blank output ABSENT; coordinate tools echo
the seed at a different precision so the user's own input becomes a B1 corroborated
discovery; a classify() HIT is stored as plain "success" so prose can still override;
Grading.credibility's `contradicting` parameter has no caller.
