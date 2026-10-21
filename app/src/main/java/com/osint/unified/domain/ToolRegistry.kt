package com.osint.unified.domain

import com.osint.unified.domain.ExecutionMode.CLOUD
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
        Tool("sherlock", "Sherlock", SOCMINT, "username", "json", LOCAL,
            "Verifies public account existence across 400+ social networks.",
            "https://github.com/sherlock-project/sherlock"),
        Tool("maigret", "Maigret", SOCMINT, "username", "json", LOCAL,
            "Checks 6,000+ sites and parses profiles for secondary identifiers.",
            "https://github.com/soxoj/maigret"),
        Tool("holehe", "Holehe", SOCMINT, "email", "json", LOCAL,
            "Confirms where an email is registered without notifying the user.",
            "https://github.com/megadose/holehe"),
        Tool("phoneinfoga", "PhoneInfoga", SOCMINT, "phone", "json", LOCAL,
            "Identifies country, carrier, line type and generates search dorks.",
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
        Tool("overpass_turbo", "Overpass Turbo", GEOINT, "coordinate", "geojson", CLOUD,
            "Mines OpenStreetMap for infrastructure (towers, cameras, bases).",
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
    )

    private val byId: Map<String, Tool> = all.associateBy { it.id }

    fun byId(id: String): Tool? = byId[id]

    fun byCategory(category: ToolCategory): List<Tool> = all.filter { it.category == category }
}
