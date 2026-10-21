package com.osint.unified.domain

/**
 * Classifies raw user input into an [Observable] and recommends tools.
 *
 * Ordering matters: coordinate and IP are checked before the more permissive
 * domain/username patterns so that "40.7,-74.0" or "8.8.8.8" are never
 * misread as usernames or domains.
 */
class ObservableRouter {

    private val emailRegex = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    private val coordinateRegex = Regex("""^-?\d{1,3}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?$""")
    private val ipv4Regex = Regex("""^(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:/\d{1,2})?$""")
    private val ipv6Regex = Regex("""^(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?:/\d{1,3})?$""")
    private val phoneRegex = Regex("""^\+\d{8,15}$""")
    private val domainRegex = Regex("""^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$""")
    private val usernameRegex = Regex("""^[a-zA-Z0-9_.-]{3,30}$""")

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
                    Observable.Unknown(input)
                }
            }

            phoneRegex.matches(input) -> Observable.PhoneNumber(input)

            ipv4Regex.matches(input) || ipv6Regex.matches(input) -> Observable.IpAddress(input)

            domainRegex.matches(input) -> Observable.Domain(input)

            usernameRegex.matches(input) -> Observable.Username(input)

            else -> Observable.Unknown(input)
        }
    }

    /**
     * Returns suggested tools for an observable, ordered primary-first.
     * Resolved against [ToolRegistry]; unknown ids are silently dropped.
     */
    fun suggestTools(observable: Observable): List<Tool> {
        val ids = when (observable) {
            is Observable.Email -> listOf("holehe", "ghunt", "h8mail")
            is Observable.Username -> listOf("sherlock", "maigret")
            is Observable.Domain -> listOf("subfinder", "amass", "httpx")
            is Observable.IpAddress -> listOf("nuclei", "httpx")
            is Observable.PhoneNumber -> listOf("phoneinfoga")
            is Observable.Coordinate -> listOf("overpass_turbo")
            is Observable.Unknown -> emptyList()
        }
        return ids.mapNotNull { ToolRegistry.byId(it) }
    }
}
