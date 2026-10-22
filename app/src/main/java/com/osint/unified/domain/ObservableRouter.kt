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

    /**
     * Returns suggested tools for an observable, ordered primary-first.
     * Resolved against [ToolRegistry]; unknown ids are silently dropped.
     */
    fun suggestTools(observable: Observable): List<Tool> {
        val ids = when (observable) {
            is Observable.Email -> listOf("gravatar", "emailaccounts", "dorks", "holehe", "ghunt", "h8mail")
            is Observable.Username -> listOf("sherlock", "maigret", "blackbird", "dorks")
            is Observable.Domain -> listOf("subfinder", "crtsh", "dns", "httpx", "shodan", "webcrawl", "wayback", "archive", "dorks", "amass", "nuclei")
            is Observable.IpAddress -> listOf("ipinfo", "shodan", "httpx", "nuclei")
            is Observable.PhoneNumber -> listOf("phoneinfoga", "dorks")
            is Observable.Coordinate -> listOf("revgeo", "wikinearby", "overpass_turbo")
            is Observable.Url -> listOf("webcrawl", "wayback", "archive", "dorks")
            is Observable.ImageUrl -> listOf("exif", "reverseimage", "archive")
            is Observable.Name -> listOf("geocode", "gdelt", "dorks")
            is Observable.Unknown -> emptyList()
        }
        return ids.mapNotNull { ToolRegistry.byId(it) }
    }
}
