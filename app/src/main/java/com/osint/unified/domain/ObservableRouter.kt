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

    private companion object {
        /** Common generic TLDs; any two-letter label is treated as a country
         *  code, which covers the rest without shipping the IANA list. */
        val KNOWN_TLDS = setOf(
            "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz",
            "name", "pro", "aero", "coop", "museum", "jobs", "mobi", "travel",
            "xxx", "asia", "cat", "tel", "post", "app", "dev", "io", "ai",
            "co", "me", "tv", "cc", "ws", "xyz", "site", "online", "store",
            "tech", "cloud", "space", "website", "press", "blog", "wiki",
            "live", "life", "world", "today", "news", "media", "studio",
            "agency", "systems", "solutions", "network", "digital", "email",
            "group", "team", "zone", "works", "company", "center", "services",
            "shop", "page", "link", "click", "fyi", "gg", "sh", "to", "ly",
            "onion", "local", "lan", "test", "example", "invalid", "localhost"
        )
    }

    private val emailRegex = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    private val coordinateRegex = Regex("""^-?\d{1,3}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?$""")
    private val ipv4Regex = Regex("""^(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:/\d{1,2})?$""")
    // Groups of up to four hex digits, and at least one group longer
    // than two — otherwise this is a MAC address or a clock.
    // "ab:cd:ef:12:34:56" and "12:30:45" both matched, and a MAC was
    // then sent to ipwho.is, Shodan, RDAP and BGPView as an address.
    private val ipv6Regex = Regex("""^(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?:/\d{1,3})?$""")
    private val macLikeRegex = Regex("""^(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$""")
    private val clockLikeRegex = Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""")

    private fun looksLikeModeS(input: String): Boolean =
        modeSRegex.matches(input) &&
            input == input.uppercase() &&
            input.any { it.isDigit() } &&
            input.any { it.isLetter() }

    private fun looksLikeIpv6(input: String): Boolean {
        if (!ipv6Regex.matches(input)) return false
        if (macLikeRegex.matches(input)) return false
        if (clockLikeRegex.matches(input)) return false
        // A real IPv6 address either abbreviates with "::" or has a
        // group of more than two hex digits somewhere; six groups of
        // exactly two is the shape of hardware, not of an address.
        if (input.contains("::")) return true
        return input.substringBefore('/').split(":").any { it.length > 2 }
    }
    // A phone number typed the way people write them. The old pattern
    // demanded a leading + and no separators, so "(415) 555-0123" and
    // "+44 20 7946 0958" were both investigated as a person's name.
    private val phoneShapeRegex = Regex("""^\+?[\d(][\d\s().\-]{6,22}$""")
    // "2026-10-22" has the same alphabet and would otherwise be a phone
    // number, and so would a plain year range.
    private val dateLikeRegex = Regex("""^\d{4}-\d{1,2}-\d{1,2}$""")
    // Four groups of up to three digits is an address, whatever the
    // digits add up to. Allowing dots as a separator — French numbers use
    // them — meant 192.168.1.1 had eight digits and the right shape, and
    // every private address anyone typed was investigated as a telephone.
    private val dottedQuadRegex = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
    // And a decimal number is a number. 3.14159265 was a phone call.
    private val decimalRegex = Regex("""^-?\d+\.\d+$""")
    private val domainRegex = Regex("""^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$""")
    private val usernameRegex = Regex("""^[a-zA-Z0-9_.-]{3,30}$""")
    private val btcRegex = Regex("""^(?:bc1[a-z0-9]{25,62}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$""")
    private val ethRegex = Regex("""^0x[a-fA-F0-9]{40}$""")
    private val modeSRegex = Regex("""^[0-9A-Fa-f]{6}$""")
    // Tail numbers, with the hyphenated form restricted to real
    // registration prefixes. "jo-anna" matched [A-Z]{1,2}-[A-Z0-9]{3,5}
    // once uppercased, so a name was investigated as an aircraft.
    // At least two digits and four characters: "n42" is far more often a
    // handle than an aircraft, and it was being investigated as one.
    private val tailRegex = Regex("""^N[0-9]{2,5}[A-Z]{0,2}$""")
    // "d-link", "g-unit", "i-robot", "m-audio", "b-side", "f-zero"
    // and "c-span" all match this, and they are left matching it.
    //
    // The single-letter prefixes are real — G, D, F, I, C, B, M and 2
    // are the United Kingdom, Germany, France, Italy, Canada, China,
    // the Isle of Man and Guernsey — and a British registration is
    // four letters after the hyphen, which is exactly the shape of
    // "LINK" and "UNIT". No pattern separates them, so the question
    // is only which mistake to make.
    //
    // Sending a brand to the aircraft lookup costs one request that
    // finds nothing. Sending a real registration to the username
    // route costs three sweeps across thousands of third-party sites.
    // So the cheap mistake is the one left in, and anyone who meant
    // the other thing has the type selector.
    private val hyphenTailRegex = Regex(
        "^(?:" + listOf(
            "G", "D", "F", "I", "EC", "PH", "OO", "OE", "HB", "SE", "LN", "OY",
            "SP", "OK", "HA", "YR", "LZ", "SX", "TC", "CS", "EI", "C", "VH",
            "ZK", "ZS", "JA", "HL", "B", "VT", "PP", "PR", "LV", "CC", "XA",
            "A6", "A7", "9V", "9M", "HS", "PK", "RA", "UR", "4X", "TF", "LX",
            "LY", "YL", "ES", "S5", "9A", "Z3", "T7", "M", "2"
        ).joinToString("|") + ")-[A-Z0-9]{3,5}$"
    )
    private val imageExtRegex = Regex(""".*\.(?:jpe?g|png|gif|webp|bmp|tiff?)(?:\?.*)?$""", RegexOption.IGNORE_CASE)

    /**
     * Whether the last label is a top-level domain anyone actually uses.
     *
     * Not the full IANA list — that is 1,400 entries and changes — but
     * enough that a two-part personal name stops being read as a hostname.
     * Anything ending in an unknown label falls through to the username and
     * name checks, which is where "john.doe" belonged all along.
     */
    /** A phone number, as opposed to a date or a bare identifier. */
    private fun looksLikePhone(input: String): Boolean {
        if (!phoneShapeRegex.matches(input)) return false
        if (dateLikeRegex.matches(input)) return false
        if (dottedQuadRegex.matches(input)) return false
        if (decimalRegex.matches(input)) return false
        if (ipv4Regex.matches(input)) return false
        val digits = input.count { it.isDigit() }
        return digits in 7..15
    }

    private fun hasKnownTld(input: String): Boolean {
        val tld = input.substringAfterLast('.').lowercase()
        return tld in KNOWN_TLDS || (tld.length == 2 && tld.all { it.isLetter() })
    }

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

            // Addresses are decided before telephones, not after. An IP
            // and a phone number are both digits and separators, and
            // whichever test runs first wins — so the one with an exact
            // definition goes first.
            ipv4Regex.matches(input) || looksLikeIpv6(input) -> Observable.IpAddress(input)

            looksLikePhone(input) -> Observable.PhoneNumber(input)

            btcRegex.matches(input) || ethRegex.matches(input) -> Observable.Wallet(input)

            (tailRegex.matches(input.uppercase()) && input.length >= 4) ||
                hyphenTailRegex.matches(input.uppercase()) ->
                Observable.Aircraft(input.uppercase())

            // Six hex digits is a Mode-S transponder address.
            //
            // This pattern was written and then never consulted, so
            // "4CA853" fell through to the username rule and ran the
            // whole username fan-out — three sweeps, thousands of
            // sites, every one touching a third party — while the
            // aircraft lookup, which has a dedicated Mode-S branch,
            // was unreachable by auto-detection.
            //
            // Only when it is written the way a Mode-S address is
            // written: upper case, and mixing letters with digits.
            // Six hex characters is also "abc123" and "cafe01", which
            // are handles, and routing those to an aircraft registry
            // would be trading one wrong answer for another.
            looksLikeModeS(input) -> Observable.Aircraft(input.uppercase())

            input.startsWith("http://", true) || input.startsWith("https://", true) ->
                if (imageExtRegex.matches(input)) Observable.ImageUrl(input) else Observable.Url(input)

            // A domain has to end in a real TLD. "john.doe" matched the
            // shape and was investigated as a domain, ahead of the username
            // check, because "doe" looks like a TLD if you never ask.
            domainRegex.matches(input) && hasKnownTld(input) -> Observable.Domain(input)

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
                // Bounds checked, as auto-detection does. Forcing the
                // type skipped them, so "500, 900" became a coordinate
                // and was sent to Nominatim, Overpass and Open-Meteo
                // as a place on Earth.
                if (lat != null && lon != null &&
                    lat in -90.0..90.0 && lon in -180.0..180.0
                ) {
                    Observable.Coordinate(input, lat, lon)
                } else {
                    Observable.Name(input)
                }
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
            is Observable.Email -> listOf("breaches", "breachcat", "gravatar", "pgpkey", "emailaccounts", "hudsonrock", "emailgithub", "mailsec", "websearch", "dorks", "toolrun", "framework", "holehe", "ghunt", "h8mail")
            is Observable.Username -> listOf("keybase", "socialsweep", "github", "ghactivity", "sherlock", "maigret", "blackbird", "reddit", "hackernews", "telegram", "osmuser", "wikidata", "websearch", "dorks", "toolrun", "framework")
            is Observable.Domain -> listOf("subfinder", "breachcat", "crtsh", "subsweep", "commoncrawl", "dns", "tlscert", "mailsec", "headers", "sitefiles", "rdap", "hostintel", "otx", "typosquat", "httpx", "shodan", "urlscan", "waybackurls", "gau", "webcrawl", "wayback", "archive", "dorks", "toolrun", "framework", "nuclei")
            is Observable.IpAddress -> listOf("ipinfo", "ptr", "iprep", "torexit", "torrelay", "shodan", "bgpview", "asnintel", "otx", "rdap", "hostintel", "dorks", "toolrun", "framework", "httpx", "nuclei")
            is Observable.PhoneNumber -> listOf("phoneinfoga", "websearch", "dorks", "toolrun", "framework")
            is Observable.Coordinate -> listOf("revgeo", "sunterrain", "photosnear", "wikinearby", "overpass_turbo", "osmrecon", "milsites", "cctvnear", "ukcrime", "histweather", "toolrun", "framework")
            is Observable.Url -> listOf("webcrawl", "wayback", "archive", "dorks", "toolrun", "framework")
            is Observable.ImageUrl -> listOf("exif", "reverseimage", "toolrun", "archive", "framework")
            is Observable.Wallet -> listOf("wallet", "websearch", "dorks", "toolrun", "framework")
            is Observable.Aircraft -> listOf("aircraftreg", "websearch", "dorks", "toolrun", "framework")
            is Observable.Name -> listOf("websearch", "emailperm", "wikidata", "gleif", "edgar", "breachcat", "sanctions", "unsanctions", "fbi", "geocode", "gdelt", "dorks", "toolrun", "framework")
            is Observable.Unknown -> emptyList()
        }
        return ids.mapNotNull { ToolRegistry.byId(it) }
    }
}
