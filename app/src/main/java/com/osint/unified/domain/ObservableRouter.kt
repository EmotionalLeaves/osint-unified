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
            is Observable.Email -> listOf("gravatar", "emailaccounts", "hudsonrock", "mailsec", "websearch", "dorks", "framework", "holehe", "ghunt", "h8mail")
            is Observable.Username -> listOf("github", "sherlock", "maigret", "blackbird", "websearch", "dorks", "framework")
            is Observable.Domain -> listOf("subfinder", "crtsh", "dns", "mailsec", "headers", "rdap", "httpx", "shodan", "urlscan", "waybackurls", "gau", "webcrawl", "wayback", "archive", "dorks", "framework", "amass", "nuclei")
            is Observable.IpAddress -> listOf("ipinfo", "ptr", "shodan", "bgpview", "rdap", "dorks", "framework", "httpx", "nuclei")
            is Observable.PhoneNumber -> listOf("phoneinfoga", "websearch", "dorks", "framework")
            is Observable.Coordinate -> listOf("revgeo", "wikinearby", "overpass_turbo", "framework")
            is Observable.Url -> listOf("webcrawl", "wayback", "archive", "dorks", "framework")
            is Observable.ImageUrl -> listOf("exif", "reverseimage", "archive", "framework")
            is Observable.Name -> listOf("websearch", "geocode", "gdelt", "dorks", "framework")
            is Observable.Unknown -> emptyList()
        }
        return ids.mapNotNull { ToolRegistry.byId(it) }
    }
}
