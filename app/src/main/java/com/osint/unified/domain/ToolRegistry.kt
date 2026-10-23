package com.osint.unified.domain

import com.osint.unified.domain.ExecutionMode.CLOUD
import com.osint.unified.domain.ExecutionMode.DIRECT
import com.osint.unified.domain.ExecutionMode.LOCAL
import com.osint.unified.domain.ToolCategory.FININT
import com.osint.unified.domain.ToolCategory.FORENSICS
import com.osint.unified.domain.ToolCategory.GEOINT
import com.osint.unified.domain.ToolCategory.NETINT
import com.osint.unified.domain.ToolCategory.SOCMINT

/**
 * The 15 essential tools for the streamlined MVP. This is the single source of
 * truth the router and executors resolve against.
 */
object ToolRegistry {

    /**
     * Tools whose output is somebody else's words, not this app's.
     *
     * A page snapshot, a crawl, a web search, a news query: the row
     * holds text that was on a page, and anything read out of it is a
     * fact about that page rather than about the target. Three
     * different files kept three different versions of this list and
     * they disagreed — IntelSummary excluded all ten, EntityExtractor
     * excluded only "snapshot", and Timeline excluded none — so the
     * same page could be refused as a source of identifiers, accepted
     * as a source of dated events, and quoted in the dossier's own
     * summary, all in one report. One list, read by all three.
     */
    val QUOTES_OTHERS = setOf(
        "snapshot", "webcrawl", "websearch", "gdelt", "wayback",
        "commoncrawl", "dorks", "framework", "toolrun", "reverseimage"
    )

    val all: List<Tool> get() = graded

    private val declared: List<Tool> = listOf(
        // ---- SOCMINT ----
        Tool("sherlock", "Sherlock", SOCMINT, "username", "json", DIRECT,
            "Verifies public account existence across hundreds of sites (native on-device check).",
            "https://github.com/sherlock-project/sherlock", timeoutMs = 70_000L, touchesTarget = true),
        Tool("maigret", "Maigret", SOCMINT, "username", "json", DIRECT,
            "Checks a large site ruleset for public accounts (native on-device check).",
            "https://github.com/soxoj/maigret", timeoutMs = 70_000L, touchesTarget = true),
        Tool("holehe", "Holehe", SOCMINT, "email", "json", CLOUD,
            "Confirms where an email is registered (needs the Python backend).",
            "https://github.com/megadose/holehe"),
        Tool("phoneinfoga", "PhoneInfoga", SOCMINT, "phone", "json", DIRECT,
            "Phone number intelligence: country, line type, validity and search dorks (native on-device).",
            "https://github.com/sundowndev/phoneinfoga"),
        Tool("ghunt", "GHunt", SOCMINT, "email", "json", CLOUD,
            "Extracts Google account metadata, Maps reviews and public calendar.",
            "https://github.com/mxrch/ghunt"),
        Tool("h8mail", "h8mail", SOCMINT, "email", "json", CLOUD,
            "Queries breach/paste services to trace compromised credentials.",
            "https://github.com/khast3x/h8mail"),

        // ---- NETINT ----
        Tool("subfinder", "Subfinder", NETINT, "domain", "json", LOCAL,
            "Passive subdomain discovery via certificate transparency and DNS.",
            "https://github.com/projectdiscovery/subfinder", touchesTarget = true),
        Tool("httpx", "httpx", NETINT, "domain", "json", LOCAL,
            "Probes HTTP services for status, title, banners, TLS and tech stack.",
            "https://github.com/projectdiscovery/httpx", touchesTarget = true),
        Tool("waybackurls", "Wayback URLs", NETINT, "domain", "text", LOCAL,
            "Every URL the Wayback Machine knows for a domain (tomnomnom).",
            "https://github.com/tomnomnom/waybackurls"),
        Tool("gau", "GetAllURLs", NETINT, "domain", "text", LOCAL,
            "Known URLs from Wayback, Common Crawl and others (lc/gau).",
            "https://github.com/lc/gau"),
        Tool("nuclei", "Nuclei", NETINT, "domain", "json", CLOUD,
            "Template-based scanning for exposed panels, misconfigs and CVEs.",
            "https://github.com/projectdiscovery/nuclei", timeoutMs = 120_000L, touchesTarget = true),

        // ---- GEOINT ----
        Tool("tar1090", "tar1090", GEOINT, "coordinate", "json", LOCAL,
            "Live aviation ADS-B radar feed rendered on the God's Eye View.",
            "https://github.com/wiedehopf/tar1090"),
        Tool("overpass_turbo", "Overpass Turbo", GEOINT, "coordinate", "geojson", DIRECT,
            "Mines OpenStreetMap for nearby infrastructure. Calls the public API directly — no backend.",
            "https://github.com/tyrasd/overpass-turbo"),

        // ---- FININT ----
        Tool("trufflehog", "TruffleHog", FININT, "repo", "json", CLOUD,
            "Scans Git history and buckets for verified live credentials.",
            "https://github.com/trufflesecurity/trufflehog"),
        Tool("opensanctions", "OpenSanctions", FININT, "entity", "json", CLOUD,
            "Sanctions lists, watchlists and politically exposed persons.",
            "https://github.com/opensanctions/opensanctions"),

        // ---- FORENSICS ----
        Tool("exiftool", "ExifTool", FORENSICS, "media", "json", LOCAL,
            "Reads EXIF/GPS/IPTC/XMP metadata across hundreds of file formats.",
            "https://github.com/exiftool/exiftool"),

        // ---- Keyless public OSINT data sources (DIRECT, no backend) ----
        Tool("crtsh", "crt.sh (CT logs)", NETINT, "domain", "json", DIRECT,
            "Certificate-transparency subdomain discovery via crt.sh.",
            "https://crt.sh"),
        Tool("dns", "DNS records", NETINT, "domain", "text", DIRECT,
            "A/AAAA/MX/NS/TXT records via DNS-over-HTTPS.",
            "https://dns.google"),
        Tool("ipinfo", "IP geo / ASN", NETINT, "ip", "json", DIRECT,
            "IP geolocation, ISP, organization and ASN.",
            "https://ipwho.is"),
        Tool("emailaccounts", "Same-name accounts (unverified)", SOCMINT, "email", "text", DIRECT,
            "Takes the part of the address before the @ and looks for accounts " +
                "using it as a username. A match means the name is taken on that " +
                "site, not that this person holds it — \"john\" is thousands of " +
                "people. Corroborate before relying on any of it.",
            "https://github.com/WebBreacher/WhatsMyName", timeoutMs = 70_000L, touchesTarget = true),
        Tool("gravatar", "Gravatar", SOCMINT, "email", "json", DIRECT,
            "Finds a public Gravatar profile and avatar for an email.",
            "https://gravatar.com"),
        Tool("wayback", "Wayback Machine", FORENSICS, "domain", "json", DIRECT,
            "Historical archived snapshots of a site.",
            "https://web.archive.org"),
        Tool("revgeo", "Reverse geocode", GEOINT, "coordinate", "json", DIRECT,
            "Resolves coordinates to a street address (OSM Nominatim).",
            "https://nominatim.openstreetmap.org"),
        Tool("shodan", "Shodan (InternetDB)", NETINT, "ip", "json", DIRECT,
            "Open ports, hostnames, CPEs and CVEs for an IP or domain (keyless Shodan InternetDB).",
            "https://internetdb.shodan.io"),
        // Named for both of the places it sends the query to. It
        // posts the raw input — including a complete email address,
        // since this tool is in the email list — to DuckDuckGo's
        // instant-answer endpoint before it asks Wikipedia, and the
        // entry used to name Wikipedia alone. A person deciding
        // whether to run a tool is entitled to know who sees the
        // query.
        Tool("websearch", "Web & Wikipedia", NETINT, "any", "text", DIRECT,
            "Wikipedia full-text search, plus DuckDuckGo's instant-answer " +
                "endpoint — so the query goes to both.",
            "https://en.wikipedia.org"),
        Tool("framework", "OSINT Framework", NETINT, "any", "text", DIRECT,
            "Every matching OSINT Framework resource for this target, as links.",
            "https://osintframework.com"),
        Tool("milsites", "Military sites", GEOINT, "coordinate", "json", DIRECT,
            "Military-tagged sites within 25 km from OpenStreetMap.",
            "https://www.openstreetmap.org"),
        Tool("osmrecon", "Infrastructure scan", GEOINT, "coordinate", "text", DIRECT,
            "Surveillance cameras, masts, helipads, border control, substations and bunkers within 2 km, worldwide, from OpenStreetMap.",
            "https://www.openstreetmap.org", timeoutMs = 70_000L),
        Tool("tlscert", "TLS certificate", NETINT, "domain", "text", DIRECT,
            "Issuer, expiry, days remaining and every hostname the cert covers.",
            "https://en.wikipedia.org/wiki/Public_key_certificate", touchesTarget = true),
        Tool("sitefiles", "Site files", NETINT, "domain", "text", DIRECT,
            "robots.txt, security.txt and sitemap — paths the site publishes.",
            "https://securitytxt.org", touchesTarget = true),
        Tool("torexit", "Tor exit check", NETINT, "ip", "text", DIRECT,
            "Whether an IP is a known Tor exit node (official list).",
            "https://check.torproject.org"),
        Tool("mailsec", "Mail security", NETINT, "email", "text", DIRECT,
            "MX, SPF and DMARC posture + spoofing risk for an email's domain.",
            "https://dns.google"),
        Tool("headers", "Security headers", NETINT, "domain", "text", DIRECT,
            "HTTP security headers, server banner and status for a domain.",
            "https://owasp.org/www-project-secure-headers/", touchesTarget = true),
        // Four tools came off the active-recon gate, because none of
        // them contacts the target at all. `touchesTarget` exists to
        // keep the investigator out of the target's logs, and marking
        // a passive lookup with it does not make anyone safer — it
        // just withholds a tool that was never going to show up
        // anywhere. Reverse DNS asks Google's resolver; the subdomain
        // sweep asks hackertarget, RapidDNS, Cert Spotter and the
        // Wayback index; the typosquat sweep resolves lookalike names
        // that by definition are not the target; the camera lookup
        // reads a Caltrans inventory and Overpass.
        Tool("ptr", "Reverse DNS", NETINT, "ip", "text", DIRECT,
            "PTR hostname for an IP via DNS-over-HTTPS.",
            "https://dns.google"),
        Tool("rdap", "RDAP registration", NETINT, "domain", "json", DIRECT,
            "Registrar, key dates, nameservers, org/country for a domain or IP (keyless).",
            "https://rdap.org"),
        Tool("bgpview", "BGP / ASN", NETINT, "ip", "json", DIRECT,
            "ASN, prefix, RIR and PTR for an IP (keyless, BGPView).",
            "https://bgpview.io"),
        Tool("hudsonrock", "Infostealer check", SOCMINT, "email", "json", DIRECT,
            "Whether an email appears in infostealer malware logs (keyless, Hudson Rock).",
            "https://www.hudsonrock.com"),
        Tool("urlscan", "urlscan.io", NETINT, "domain", "json", DIRECT,
            "Recent public URL scans and screenshots for a domain (keyless).",
            "https://urlscan.io"),
        Tool("github", "GitHub profile", SOCMINT, "username", "json", DIRECT,
            "Live GitHub profile: name, bio, company, location, repos, followers.",
            "https://github.com"),
        Tool("dorks", "Search & pivots", NETINT, "any", "text", DIRECT,
            "Platform profiles, people-search, breach checks and dork links for the target.",
            "https://www.exploit-db.com/google-hacking-database"),
        Tool("blackbird", "Blackbird", SOCMINT, "username", "json", DIRECT,
            "Username enumeration across many sites (native on-device check).",
            "https://github.com/p1ngul1n0/blackbird", timeoutMs = 70_000L, touchesTarget = true),
        Tool("webcrawl", "Web crawl (Photon)", NETINT, "domain", "text", DIRECT,
            "Crawls a page for emails, links and JS files.",
            "https://github.com/s0md3v/photon", touchesTarget = true),
        Tool("archive", "Archive (Wayback)", FORENSICS, "domain", "text", DIRECT,
            "Finds the latest archived copy of a URL (Wayback availability API).",
            "https://web.archive.org"),
        Tool("geocode", "Geocode (place)", GEOINT, "place", "json", DIRECT,
            "Resolves a place name to coordinates and a map (OSM Nominatim).",
            "https://nominatim.openstreetmap.org"),
        Tool("reverseimage", "Reverse image", FORENSICS, "image", "text", DIRECT,
            "Reverse image search links (Google Lens, Yandex, Bing, TinEye).",
            "https://lens.google.com"),
        Tool("exif", "EXIF / GPS", FORENSICS, "image", "text", DIRECT,
            "Extracts EXIF GPS and camera metadata from an image URL (native).",
            "https://github.com/exiftool/exiftool", touchesTarget = true),
        Tool("wikinearby", "Wikipedia nearby", GEOINT, "coordinate", "text", DIRECT,
            "Notable places within 10 km of a coordinate (Wikipedia GeoSearch).",
            "https://en.wikipedia.org"),
        // ---- Deep keyless sources (probed live before shipping) ----
        Tool("keybase", "Keybase proofs", SOCMINT, "username", "text", DIRECT,
            "Cryptographically proven links from a username to Twitter, GitHub, Reddit, HN and websites.",
            "https://keybase.io"),
        Tool("ghactivity", "GitHub activity", SOCMINT, "username", "text", DIRECT,
            "Mines public events for commit emails, author names, repos touched and working hours.",
            "https://github.com"),
        Tool("reddit", "Reddit profile", SOCMINT, "username", "text", DIRECT,
            "Karma, account age, bio, active subreddits and a timezone hint from posting hours.",
            "https://www.reddit.com"),
        Tool("hackernews", "Hacker News", SOCMINT, "username", "text", DIRECT,
            "Karma, account age and the about text (often holds contact details).",
            "https://news.ycombinator.com"),
        Tool("telegram", "Telegram channel", SOCMINT, "username", "text", DIRECT,
            "Public channel: subscribers, description and recent posts.",
            "https://telegram.org"),
        Tool("wikidata", "Wikidata entity", SOCMINT, "any", "text", DIRECT,
            "Structured facts and every linked social handle for a person, company or place.",
            "https://www.wikidata.org"),
        Tool("breachcat", "Breach catalogue", SOCMINT, "any", "text", DIRECT,
            "Every known public breach at a domain or company — date, record count, fields leaked. Offline.",
            "https://haveibeenpwned.com"),
        Tool("breaches", "Breach exposure", SOCMINT, "email", "text", DIRECT,
            "Which public breaches an address appears in, what leaked and when (XposedOrNot, keyless).",
            "https://xposedornot.com"),
        Tool("emailgithub", "Email to GitHub", SOCMINT, "email", "text", DIRECT,
            "Finds GitHub accounts that publicly list this email.",
            "https://github.com"),
        Tool("subsweep", "Subdomain sweep", NETINT, "domain", "text", DIRECT,
            "Merges hackertarget, RapidDNS, Cert Spotter and Wayback into one " +
                "deduplicated host list.",
            "https://rapiddns.io"),
        Tool("hostintel", "Host intel", NETINT, "domain", "text", DIRECT,
            "Reverse IP neighbours, AS lookup and outbound page links.",
            "https://hackertarget.com", touchesTarget = true),
        Tool("cve", "CVE / exploit odds", NETINT, "any", "text", DIRECT,
            "CVE detail plus the EPSS probability it gets exploited in the next 30 days.",
            "https://www.first.org/epss/"),
        Tool("gleif", "Legal entity (LEI)", FININT, "entity", "text", DIRECT,
            "The global register of legal entities: address, status, former names.",
            "https://www.gleif.org"),
        Tool("edgar", "SEC EDGAR", FININT, "entity", "text", DIRECT,
            "US registrants: CIK, business address, industry and recent filings.",
            "https://www.sec.gov/edgar"),
        Tool("sanctions", "Sanctions (OFAC)", FININT, "entity", "text", DIRECT,
            "Searches the bundled OFAC SDN list offline — no network, no key.",
            "https://sanctionslist.ofac.treas.gov"),
        Tool("unsanctions", "UN sanctions", FININT, "entity", "text", DIRECT,
            "Searches the UN Security Council consolidated list bundled in the app — offline, no network.",
            "https://www.un.org/securitycouncil/sanctions"),
        Tool("pgpkey", "PGP key", SOCMINT, "email", "text", DIRECT,
            "Public key for an address from keys.openpgp.org, which verifies the address before listing it.",
            "https://keys.openpgp.org"),
        Tool("commoncrawl", "Common Crawl", NETINT, "domain", "text", DIRECT,
            "Indexed URLs and hosts for a domain from the Common Crawl archive.",
            "https://commoncrawl.org"),
        Tool("photosnear", "Imagery nearby", GEOINT, "coordinate", "text", DIRECT,
            "Geotagged Wikimedia Commons media plus KartaView and Panoramax street-level photos near a point.",
            "https://commons.wikimedia.org"),
        Tool("fbi", "FBI wanted", FININT, "name", "text", DIRECT,
            "The FBI's public wanted and seeking-information list.",
            "https://www.fbi.gov/wanted"),
        Tool("wallet", "Crypto wallet", FININT, "wallet", "text", DIRECT,
            "BTC or ETH address: balance, transaction count, tokens and counterparties.",
            "https://www.blockchain.com"),
        Tool("aircraftreg", "Aircraft lookup", GEOINT, "aircraft", "text", DIRECT,
            "Mode-S hex or tail number: type, registered owner, and live position if airborne.",
            "https://hexdb.io"),
        Tool("sunterrain", "Sun & terrain", GEOINT, "coordinate", "text", DIRECT,
            "Elevation, solar angle, shadow direction and length — chronolocation maths.",
            "https://open-meteo.com"),
        Tool("histweather", "Historical weather", GEOINT, "coordinate", "text", DIRECT,
            "Daily conditions for the past month — tests whether a photo matches its claimed date.",
            "https://open-meteo.com"),
        Tool("ukcrime", "UK street crime", GEOINT, "coordinate", "text", DIRECT,
            "Reported offences within a mile, by category and hotspot street (UK only).",
            "https://data.police.uk"),
        Tool("cctvnear", "Cameras nearby", GEOINT, "coordinate", "text", DIRECT,
            "Public traffic cameras near a coordinate, with live still-image links.",
            "https://cwwp2.dot.ca.gov"),

        Tool("toolrun", "Run tool directory", NETINT, "any", "text", DIRECT,
            "Executes every runnable endpoint in the directory against this target and points the rest at it.",
            "https://osintframework.com", timeoutMs = 70_000L, touchesTarget = true),
        Tool("emailperm", "Address patterns", SOCMINT, "any", "text", DIRECT,
            "Generates the likely addresses for a name at a domain, with that domain's real mail setup. Input: 'Jane Doe @example.com'.",
            "https://en.wikipedia.org/wiki/Email_address"),
        Tool("typosquat", "Typosquat sweep", NETINT, "domain", "text", DIRECT,
            "Generates lookalike domains and resolves every one; reports only those actually registered.",
            "https://en.wikipedia.org/wiki/Typosquatting", timeoutMs = 40_000L),
        Tool("socialsweep", "Platform sweep", SOCMINT, "username", "text", DIRECT,
            "One username checked in parallel against Bluesky, Mastodon, GitLab, Docker Hub, Stack Overflow, Bitbucket, npm and Wikipedia.",
            "https://bsky.app", touchesTarget = true),
        Tool("osmuser", "OSM edit history", SOCMINT, "username", "text", DIRECT,
            "Where an OpenStreetMap contributor edits — people map where they live and travel.",
            "https://www.openstreetmap.org"),
        Tool("asnintel", "Network operator", NETINT, "ip", "text", DIRECT,
            "RIR abuse contact, announced prefix count and the PeeringDB record behind an IP or ASN.",
            "https://stat.ripe.net"),
        Tool("iprep", "IP reputation", NETINT, "ip", "text", DIRECT,
            "Checks six open DNS blocklists over DNS-over-HTTPS; reports refusals honestly rather than as hits.",
            "https://www.dnsbl.info"),
        Tool("otx", "Threat exchange", NETINT, "domain", "text", DIRECT,
            "Passive DNS history, hosted URLs and threat reports from AlienVault OTX (keyless).",
            "https://otx.alienvault.com"),
        Tool("torrelay", "Tor relay detail", NETINT, "ip", "text", DIRECT,
            "Full relay record — flags, bandwidth, operator contact, first seen — from the Tor Project's Onionoo.",
            "https://metrics.torproject.org"),
        Tool("outages", "Internet outages", NETINT, "any", "text", DIRECT,
            "Networks and regions losing connectivity in the last 24 hours (Georgia Tech IODA).",
            "https://ioda.inetintel.cc.gatech.edu"),

        Tool("gdelt", "Global news", NETINT, "any", "text", DIRECT,
            "Worldwide coverage of a query with sources, reporting countries and dates (GDELT article API).",
            "https://www.gdeltproject.org"),
        // Not suggested by the router — nothing routes to it. It exists
        // so that a page kept from the isolated browser has a name, a
        // category and a grade like any other source, rather than
        // appearing in the dossier as an unidentified row.
        Tool("snapshot", "Page snapshot", FORENSICS, "url", "text", DIRECT,
            "A copy of a page as it was when you looked at it, with the hash " +
                "of what the server served.",
            "https://www.bellingcat.com"),
    )


    /**
     * What each source is worth, before it says anything.
     *
     * One table rather than a field scattered across eighty declarations,
     * so the grading can be read and argued with in one place — which is
     * the only way a grade means anything.
     *
     * A: a record of the thing itself. Certificate transparency is a
     * signed log of certificates that were actually issued; RDAP and
     * GLEIF are the registries; OFAC and the UN publish their own lists.
     * There is no interpretation between the event and the record.
     *
     * B: an operator publishing its own data — DNS as served, an archive
     * saying what it holds, a government feed.
     *
     * C: a third party reporting what it observed. Scanners, aggregators
     * of other people's crawls, breach catalogues: honest, secondhand.
     *
     * D: derived or aggregated without naming where it came from.
     *
     * E: a heuristic. The username sweeps belong here and nowhere else: a
     * name being taken on a site is a fact about the name, not about the
     * person, and presenting it at the same weight as a certificate was
     * most of what made the dossier unusable.
     */
    private val RELIABILITY: Map<String, Grading.Reliability> = buildMap {
        // A — the record of the thing itself.
        //
        // Five tools came out of this group, because none of them is
        // the record of anything:
        //
        //   wikidata — a wiki anyone may edit, reached through a
        //     fuzzy search whose top hit is taken unchecked;
        //   cve — the number it leads with is EPSS, a *prediction* of
        //     future exploitation, and the prose comes from an
        //     aggregator. A forecast is not a record;
        //   aircraftreg — hexdb.io is community-maintained and
        //     adsb.lol is a volunteer receiver network. Neither is a
        //     civil aviation registry, and the tool prints
        //     "Registered owner:" from them;
        //   bgpview — a third-party aggregator, which silently falls
        //     back to RIPEstat, so the grade did not even describe
        //     what answered;
        //   asnintel — the same, through RIPEstat and PeeringDB.
        //
        // The grade is the whole point of grading: an A says this is
        // the authoritative record and a C says somebody is reporting
        // what they saw. Putting an editable wiki next to a
        // certificate transparency log makes both numbers mean less.
        listOf(
            "crtsh", "rdap", "gleif", "edgar", "sanctions", "unsanctions", "wallet"
        ).forEach { put(it, Grading.Reliability.COMPLETE) }

        // B — an operator publishing its own data. A page is the site's
        // own publication, and a snapshot is a copy of it with the time
        // and the digest attached.
        listOf(
            "snapshot", "dns", "ptr", "tlscert", "wayback", "archive", "commoncrawl",
            "waybackurls", "gau", "subfinder", "github", "ghactivity",
            "keybase", "pgpkey", "gravatar", "geocode", "revgeo",
            "wikinearby", "osmrecon", "osmuser", "overpass_turbo",
            "milsites", "photosnear", "ukcrime", "fbi", "histweather",
            "sunterrain", "outages", "cctvnear", "reddit", "hackernews",
            "telegram", "gdelt", "breaches", "tar1090", "exif", "exiftool"
        ).forEach { put(it, Grading.Reliability.USUALLY) }

        // C — a third party reporting what it saw.
        listOf(
            "httpx", "ipinfo", "shodan", "urlscan", "otx", "iprep",
            "hostintel", "nuclei", "trufflehog", "opensanctions",
            "hudsonrock", "breachcat", "webcrawl", "sitefiles", "headers",
            "mailsec", "torexit", "torrelay", "subsweep", "phoneinfoga",
            "holehe", "ghunt", "h8mail", "emailgithub",
            "wikidata", "cve", "aircraftreg", "bgpview", "asnintel"
        ).forEach { put(it, Grading.Reliability.FAIRLY) }

        // D — derived, of unclear provenance.
        listOf("typosquat", "reverseimage").forEach {
            put(it, Grading.Reliability.NOT_USUALLY)
        }

        // "emailperm" reports nothing it saw. It generates candidate
        // strings and its own output says so — "These are generated
        // patterns, not confirmed accounts" — which is the definition
        // of a heuristic and belongs beside the sweeps.
        put("emailperm", Grading.Reliability.UNRELIABLE)

        // E — a heuristic, and it should look like one.
        listOf(
            "sherlock", "maigret", "blackbird", "socialsweep", "emailaccounts"
        ).forEach { put(it, Grading.Reliability.UNRELIABLE) }

        // F — directories and search pages. These are places to look, not
        // sources that said anything, and they are graded as what they
        // are rather than left to look like findings.
        listOf("framework", "toolrun", "dorks", "websearch").forEach {
            put(it, Grading.Reliability.UNJUDGED)
        }
    }

    /** Every tool, carrying the grade the table above gives it. */
    private val graded: List<Tool> =
        declared.map { it.copy(reliability = RELIABILITY[it.id] ?: it.reliability) }

    private val byId: Map<String, Tool> = all.associateBy { it.id }

    fun byId(id: String): Tool? = byId[id]

    fun byCategory(category: ToolCategory): List<Tool> = all.filter { it.category == category }
}
