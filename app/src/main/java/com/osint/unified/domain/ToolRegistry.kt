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

    val all: List<Tool> = listOf(
        // ---- SOCMINT ----
        Tool("sherlock", "Sherlock", SOCMINT, "username", "json", DIRECT,
            "Verifies public account existence across hundreds of sites (native on-device check).",
            "https://github.com/sherlock-project/sherlock"),
        Tool("maigret", "Maigret", SOCMINT, "username", "json", DIRECT,
            "Checks a large site ruleset for public accounts (native on-device check).",
            "https://github.com/soxoj/maigret"),
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
            "https://github.com/projectdiscovery/subfinder"),
        Tool("amass", "OWASP Amass", NETINT, "domain", "json", LOCAL,
            "Attack-surface mapping: passive DNS plus active subdomain discovery.",
            "https://github.com/owasp-amass/amass"),
        Tool("httpx", "httpx", NETINT, "domain", "json", LOCAL,
            "Probes HTTP services for status, title, banners, TLS and tech stack.",
            "https://github.com/projectdiscovery/httpx"),
        Tool("nuclei", "Nuclei", NETINT, "domain", "json", CLOUD,
            "Template-based scanning for exposed panels, misconfigs and CVEs.",
            "https://github.com/projectdiscovery/nuclei", timeoutMs = 120_000L),

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
        Tool("emailaccounts", "Account finder", SOCMINT, "email", "text", DIRECT,
            "Finds accounts across sites registered to the email's username (WhatsMyName, keyless).",
            "https://github.com/WebBreacher/WhatsMyName"),
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
        Tool("websearch", "Web & Wikipedia", NETINT, "any", "text", DIRECT,
            "Live Wikipedia results + instant-answer facts shown inline.",
            "https://en.wikipedia.org"),
        Tool("framework", "OSINT Framework", NETINT, "any", "text", DIRECT,
            "Every matching OSINT Framework resource for this target, as links.",
            "https://osintframework.com"),
        Tool("github", "GitHub profile", SOCMINT, "username", "json", DIRECT,
            "Live GitHub profile: name, bio, company, location, repos, followers.",
            "https://github.com"),
        Tool("dorks", "Search & pivots", NETINT, "any", "text", DIRECT,
            "Platform profiles, people-search, breach checks and dork links for the target.",
            "https://www.exploit-db.com/google-hacking-database"),
        Tool("blackbird", "Blackbird", SOCMINT, "username", "json", DIRECT,
            "Username enumeration across many sites (native on-device check).",
            "https://github.com/p1ngul1n0/blackbird"),
        Tool("webcrawl", "Web crawl (Photon)", NETINT, "domain", "text", DIRECT,
            "Crawls a page for emails, links and JS files.",
            "https://github.com/s0md3v/photon"),
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
            "https://github.com/exiftool/exiftool"),
        Tool("wikinearby", "Wikipedia nearby", GEOINT, "coordinate", "text", DIRECT,
            "Notable places within 10 km of a coordinate (Wikipedia GeoSearch).",
            "https://en.wikipedia.org"),
        Tool("gdelt", "GDELT news geo", GEOINT, "any", "geojson", DIRECT,
            "Geolocated global-news hotspots mentioning the query (GDELT 2.0).",
            "https://www.gdeltproject.org"),
    )

    private val byId: Map<String, Tool> = all.associateBy { it.id }

    fun byId(id: String): Tool? = byId[id]

    fun byCategory(category: ToolCategory): List<Tool> = all.filter { it.category == category }
}
