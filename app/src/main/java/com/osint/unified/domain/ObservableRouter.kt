package com.osint.unified.domain

/**
 * Classifies raw user input into an [Observable] and recommends tools.
 *
 * Almost any non-empty input resolves to something useful: technical types
 * (email, IP, domain, phone, coordinate, URL, image URL) route to their
 * specialist tools, and everything else is treated as a [Observable.Name]
 * (person / place / free query) that fans out to dorks and geocoding.
 */
class ObservableRouter {

    private val emailRegex = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    private val coordinateRegex = Regex("""^-?\d{1,3}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?$""")
    private val ipv4Regex = Regex("""^(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:/\d{1,2})?$""")
    private val ipv6Regex = Regex("""^(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?:/\d{1,3})?$""")
    private val phoneRegex = Regex("""^\+\d{8,15}$""")
    private val domainRegex = Regex("""^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$""")
    private val usernameRegex = Regex("""^[a-zA-Z0-9_.-]{3,30}$""")
    private val btcRegex = Regex("""^(?:bc1[a-z0-9]{25,62}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$""")
    private val ethRegex = Regex("""^0x[a-fA-F0-9]{40}$""")
    private val modeSRegex = Regex("""^[0-9A-Fa-f]{6}$""")
    private val tailRegex = Regex("""^(?:N[0-9]{1,5}[A-Z]{0,2}|[A-Z]{1,2}-[A-Z0-9]{3,5})$""")
    private val imageExtRegex = Regex(""".*\.(?:jpe?g|png|gif|webp|bmp|tiff?)(?:\?.*)?$""", RegexOption.IGNORE_CASE)

    fun classify(rawInput: String): Observable {
        val input = rawInput.trim()
        if (input.isEmpty()) return Observable.Unknown(input)

        return when {
            emailRegex.matches(input) -> Observable.Email(input)

            coordinateRegex.matches(input) -> {
                val parts = input.split(",").map { it.trim() }
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    Observable.Coordinate(input, lat, lon)
                } else {
                    Observable.Name(input)
                }
            }

            phoneRegex.matches(input) -> Observable.PhoneNumber(input)

            btcRegex.matches(input) || ethRegex.matches(input) -> Observable.Wallet(input)

            tailRegex.matches(input.uppercase()) -> Observable.Aircraft(input.uppercase())

            input.startsWith("http://", true) || input.startsWith("https://", true) ->
                if (imageExtRegex.matches(input)) Observable.ImageUrl(input) else Observable.Url(input)

            ipv4Regex.matches(input) || ipv6Regex.matches(input) -> Observable.IpAddress(input)

            domainRegex.matches(input) -> Observable.Domain(input)

            usernameRegex.matches(input) -> Observable.Username(input)

            else -> Observable.Name(input)
        }
    }

    /** Classifies with the type forced by the user (the search type selector),
     *  falling back to auto-detection for AUTO/unknown keys. */
    fun classifyAs(rawInput: String, type: String): Observable {
        val input = rawInput.trim()
        if (input.isEmpty()) return Observable.Unknown(input)
        return when (type) {
            "email" -> Observable.Email(input)
            "username" -> Observable.Username(input)
            "domain" -> Observable.Domain(input)
            "ip" -> Observable.IpAddress(input)
            "phone" -> Observable.PhoneNumber(input)
            "url" -> Observable.Url(input)
            "image" -> Observable.ImageUrl(input)
            "name" -> Observable.Name(input)
            "wallet" -> Observable.Wallet(input)
            "aircraft" -> Observable.Aircraft(input.uppercase())
            "coordinate" -> {
                val parts = input.split(",").map { it.trim() }
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) Observable.Coordinate(input, lat, lon) else Observable.Name(input)
            }
            else -> classify(input)
        }
    }

    /**
     * Returns suggested tools for an observable, ordered primary-first.
     * Resolved against [ToolRegistry]; unknown ids are silently dropped.
     */
    fun suggestTools(observable: Observable): List<Tool> {
        val ids = when (observable) {
            is Observable.Email -> listOf("breaches", "gravatar", "pgpkey", "emailaccounts", "hudsonrock", "emailgithub", "mailsec", "websearch", "dorks", "toolrun", "framework", "holehe", "ghunt", "h8mail")
            is Observable.Username -> listOf("keybase", "socialsweep", "github", "ghactivity", "sherlock", "maigret", "blackbird", "reddit", "hackernews", "telegram", "osmuser", "wikidata", "websearch", "dorks", "toolrun", "framework")
            is Observable.Domain -> listOf("subfinder", "crtsh", "subsweep", "commoncrawl", "dns", "tlscert", "mailsec", "headers", "sitefiles", "rdap", "hostintel", "otx", "typosquat", "httpx", "shodan", "urlscan", "waybackurls", "gau", "webcrawl", "wayback", "archive", "dorks", "toolrun", "framework", "amass", "nuclei")
            is Observable.IpAddress -> listOf("ipinfo", "ptr", "iprep", "torexit", "torrelay", "shodan", "bgpview", "asnintel", "otx", "rdap", "hostintel", "dorks", "toolrun", "framework", "httpx", "nuclei")
            is Observable.PhoneNumber -> listOf("phoneinfoga", "websearch", "dorks", "toolrun", "framework")
            is Observable.Coordinate -> listOf("revgeo", "sunterrain", "photosnear", "wikinearby", "overpass_turbo", "milsites", "cctvnear", "ukcrime", "histweather", "framework")
            is Observable.Url -> listOf("webcrawl", "wayback", "archive", "dorks", "toolrun", "framework")
            is Observable.ImageUrl -> listOf("exif", "reverseimage", "archive", "framework")
            is Observable.Wallet -> listOf("wallet", "websearch", "dorks", "toolrun", "framework")
            is Observable.Aircraft -> listOf("aircraftreg", "websearch", "dorks", "toolrun", "framework")
            is Observable.Name -> listOf("websearch", "emailperm", "wikidata", "gleif", "edgar", "sanctions", "unsanctions", "fbi", "geocode", "gdelt", "dorks", "toolrun", "framework")
            is Observable.Unknown -> emptyList()
        }
        return ids.mapNotNull { ToolRegistry.byId(it) }
    }
}
