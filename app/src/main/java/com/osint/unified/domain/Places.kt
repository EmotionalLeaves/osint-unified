package com.osint.unified.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Two sources saying the same place in two languages.
 *
 * Five platforms independently saying Vienna is the most valuable
 * thing a handle search can produce, and the engine that exists to say
 * so never saw it — the extractor collected machine identifiers only.
 * Now that it reads declared places, they have to agree with each
 * other or the feature is noise: `Wien`, `Vienna`, `Vienna, Austria`,
 * `Vienna, AT` and `AT` are one assertion written five ways, and
 * counting them as five would grade a single source as corroborated.
 *
 * Normalising is therefore not cosmetic. It is the whole of it.
 *
 * Deliberately a bundled table rather than a lookup: the app is
 * keyless, a place name is not worth a request, and the APK's size is
 * not the constraint here. Deliberately incomplete, too — it holds the
 * countries, the endonyms of the cities most likely to appear in a
 * profile, and the US and Canadian subdivisions. An unknown place
 * falls through as itself, which merges with another spelling of
 * itself and not with anything else. That is the safe failure: two
 * entries where there should be one is a reader noticing, where one
 * entry that merged two different places is a reader misled.
 */
object Places {

    /** Values that are a shrug rather than a place. */
    private val NOT_A_PLACE = setOf(
        "n/a", "na", "none", "null", "unknown", "nowhere", "somewhere",
        "earth", "world", "worldwide", "global", "the internet", "internet",
        "remote", "everywhere", "anywhere", "here", "there", "home",
        "localhost", "127.0.0.1", "/dev/null", "the moon", "mars",
        "not specified", "not stated", "undisclosed", "private", "redacted",
        "your heart", "cyberspace", "the cloud", "tbd", "-", "--", "?",
    )

    /** English name to the codes that mean it. */
    private val COUNTRIES: List<Pair<String, List<String>>> = listOf(
        "afghanistan" to listOf("af", "afg"), "albania" to listOf("al", "alb"),
        "algeria" to listOf("dz", "dza"), "argentina" to listOf("ar", "arg"),
        "armenia" to listOf("am", "arm"), "australia" to listOf("au", "aus"),
        "austria" to listOf("at", "aut"), "azerbaijan" to listOf("az", "aze"),
        "bangladesh" to listOf("bd", "bgd"), "belarus" to listOf("by", "blr"),
        "belgium" to listOf("be", "bel"), "bolivia" to listOf("bo", "bol"),
        "bosnia and herzegovina" to listOf("ba", "bih"),
        "brazil" to listOf("br", "bra"), "bulgaria" to listOf("bg", "bgr"),
        "cambodia" to listOf("kh", "khm"), "cameroon" to listOf("cm", "cmr"),
        "canada" to listOf("ca", "can"), "chile" to listOf("cl", "chl"),
        "china" to listOf("cn", "chn"), "colombia" to listOf("co", "col"),
        "costa rica" to listOf("cr", "cri"), "croatia" to listOf("hr", "hrv"),
        "cuba" to listOf("cu", "cub"), "cyprus" to listOf("cy", "cyp"),
        "czechia" to listOf("cz", "cze"), "denmark" to listOf("dk", "dnk"),
        "ecuador" to listOf("ec", "ecu"), "egypt" to listOf("eg", "egy"),
        "estonia" to listOf("ee", "est"), "ethiopia" to listOf("et", "eth"),
        "finland" to listOf("fi", "fin"), "france" to listOf("fr", "fra"),
        "georgia" to listOf("ge", "geo"), "germany" to listOf("de", "deu"),
        "ghana" to listOf("gh", "gha"), "greece" to listOf("gr", "grc"),
        "hungary" to listOf("hu", "hun"), "iceland" to listOf("is", "isl"),
        "india" to listOf("in", "ind"), "indonesia" to listOf("id", "idn"),
        "iran" to listOf("ir", "irn"), "iraq" to listOf("iq", "irq"),
        "ireland" to listOf("ie", "irl"), "israel" to listOf("il", "isr"),
        "italy" to listOf("it", "ita"), "japan" to listOf("jp", "jpn"),
        "jordan" to listOf("jo", "jor"), "kazakhstan" to listOf("kz", "kaz"),
        "kenya" to listOf("ke", "ken"), "kuwait" to listOf("kw", "kwt"),
        "latvia" to listOf("lv", "lva"), "lebanon" to listOf("lb", "lbn"),
        "lithuania" to listOf("lt", "ltu"), "luxembourg" to listOf("lu", "lux"),
        "malaysia" to listOf("my", "mys"), "mexico" to listOf("mx", "mex"),
        "moldova" to listOf("md", "mda"), "mongolia" to listOf("mn", "mng"),
        "morocco" to listOf("ma", "mar"), "myanmar" to listOf("mm", "mmr"),
        "nepal" to listOf("np", "npl"), "netherlands" to listOf("nl", "nld"),
        "new zealand" to listOf("nz", "nzl"), "nigeria" to listOf("ng", "nga"),
        "north macedonia" to listOf("mk", "mkd"), "norway" to listOf("no", "nor"),
        "pakistan" to listOf("pk", "pak"), "panama" to listOf("pa", "pan"),
        "peru" to listOf("pe", "per"), "philippines" to listOf("ph", "phl"),
        "poland" to listOf("pl", "pol"), "portugal" to listOf("pt", "prt"),
        "qatar" to listOf("qa", "qat"), "romania" to listOf("ro", "rou"),
        "russia" to listOf("ru", "rus"), "saudi arabia" to listOf("sa", "sau"),
        "serbia" to listOf("rs", "srb"), "singapore" to listOf("sg", "sgp"),
        "slovakia" to listOf("sk", "svk"), "slovenia" to listOf("si", "svn"),
        "south africa" to listOf("za", "zaf"), "south korea" to listOf("kr", "kor"),
        "spain" to listOf("es", "esp"), "sri lanka" to listOf("lk", "lka"),
        "sweden" to listOf("se", "swe"), "switzerland" to listOf("ch", "che"),
        "taiwan" to listOf("tw", "twn"), "tanzania" to listOf("tz", "tza"),
        "thailand" to listOf("th", "tha"), "tunisia" to listOf("tn", "tun"),
        "turkey" to listOf("tr", "tur"), "uganda" to listOf("ug", "uga"),
        "ukraine" to listOf("ua", "ukr"),
        "united arab emirates" to listOf("ae", "are"),
        "united kingdom" to listOf("gb", "gbr", "uk"),
        "united states" to listOf("us", "usa"),
        "uruguay" to listOf("uy", "ury"), "uzbekistan" to listOf("uz", "uzb"),
        "venezuela" to listOf("ve", "ven"), "vietnam" to listOf("vn", "vnm"),
        "zimbabwe" to listOf("zw", "zwe"),
    )

    private val COUNTRY_NAMES: Set<String> = COUNTRIES.map { it.first }.toSet()

    /** The other names people write for the same countries. */
    private val OTHER_COUNTRY_NAMES: List<Pair<String, String>> = listOf(
        "deutschland" to "germany", "brd" to "germany",
        "osterreich" to "austria", "oesterreich" to "austria",
        "suomi" to "finland", "sverige" to "sweden", "norge" to "norway",
        "danmark" to "denmark",
        // "island", the Danish and German name for Iceland, is not
        // here: it is also an ordinary English word, and "Long
        // Island" and "Rhode Island" both contain it.
        "nederland" to "netherlands", "holland" to "netherlands",
        "the netherlands" to "netherlands",
        "belgie" to "belgium", "belgique" to "belgium",
        "schweiz" to "switzerland", "suisse" to "switzerland",
        "svizzera" to "switzerland", "helvetia" to "switzerland",
        "espana" to "spain", "italia" to "italy", "portugal" to "portugal",
        "polska" to "poland", "ceska republika" to "czechia",
        "czech republic" to "czechia", "cesko" to "czechia",
        "magyarorszag" to "hungary", "hrvatska" to "croatia",
        "srbija" to "serbia", "romania" to "romania",
        "ellada" to "greece", "hellas" to "greece",
        "turkiye" to "turkey", "rossiya" to "russia",
        "russian federation" to "russia", "ukraina" to "ukraine",
        "nippon" to "japan", "nihon" to "japan",
        "zhongguo" to "china", "prc" to "china",
        "peoples republic of china" to "china",
        "republic of korea" to "south korea", "korea" to "south korea",
        "bharat" to "india", "uae" to "united arab emirates",
        "great britain" to "united kingdom", "britain" to "united kingdom",
        "england" to "united kingdom", "scotland" to "united kingdom",
        "wales" to "united kingdom", "northern ireland" to "united kingdom",
        "united states of america" to "united states",
        "u.s." to "united states", "u.s.a." to "united states",
        "the states" to "united states", "america" to "united states",
        "eire" to "ireland", "republic of ireland" to "ireland",
        "rsa" to "south africa", "nz" to "new zealand", "aotearoa" to "new zealand",
        "oz" to "australia", "viet nam" to "vietnam", "burma" to "myanmar",
        "persia" to "iran", "siam" to "thailand",
    )

    /** US states and Canadian provinces, by the codes people write. */
    private val SUBDIVISIONS: List<Pair<String, String>> = listOf(
        "al" to "alabama", "ak" to "alaska", "az" to "arizona",
        "ar" to "arkansas", "ca" to "california", "co" to "colorado",
        "ct" to "connecticut", "de" to "delaware", "fl" to "florida",
        "ga" to "georgia", "hi" to "hawaii", "id" to "idaho",
        "il" to "illinois", "in" to "indiana", "ia" to "iowa", "ks" to "kansas",
        "ky" to "kentucky", "la" to "louisiana", "me" to "maine",
        "md" to "maryland", "ma" to "massachusetts", "mi" to "michigan",
        "mn" to "minnesota", "ms" to "mississippi", "mo" to "missouri",
        "mt" to "montana", "ne" to "nebraska", "nv" to "nevada",
        "nh" to "new hampshire", "nj" to "new jersey", "nm" to "new mexico",
        "ny" to "new york state",
        "nc" to "north carolina", "nd" to "north dakota", "oh" to "ohio",
        "ok" to "oklahoma", "or" to "oregon", "pa" to "pennsylvania",
        "ri" to "rhode island", "sc" to "south carolina", "sd" to "south dakota",
        "tn" to "tennessee", "tx" to "texas", "ut" to "utah",
        "vt" to "vermont", "va" to "virginia", "wa" to "washington state",
        "wv" to "west virginia", "wi" to "wisconsin", "wy" to "wyoming",
        "ab" to "alberta", "bc" to "british columbia", "mb" to "manitoba",
        "nb" to "new brunswick", "ns" to "nova scotia", "on" to "ontario",
        "qc" to "quebec", "sk" to "saskatchewan",
    )

    /** Every code any country in the table goes by. */
    private val COUNTRY_CODES: Set<String> =
        COUNTRIES.flatMap { it.second }.toSet()

    private val SUBDIVISION_NAMES: Set<String> =
        SUBDIVISIONS.map { it.second }.toSet()

    /**
     * Written form to the form everything is counted under.
     *
     * Endonyms, the common abbreviations, and the spellings that differ
     * only by a diacritic once the diacritics are gone anyway.
     */
    /**
     * Cities whose English name differs from the local one, or whose
     * shorthand people write. Hoisted out of the alias map so the
     * ambiguity check can see the names as well as the codes.
     */
    private val CITY_ALIASES: List<Pair<String, String>> = listOf(
        "wien" to "vienna", "muenchen" to "munich", "munchen" to "munich",
        "koeln" to "cologne", "koln" to "cologne", "nurnberg" to "nuremberg",
        "nuernberg" to "nuremberg", "frankfurt am main" to "frankfurt",
        "praha" to "prague", "moskva" to "moscow", "moskwa" to "moscow",
        "sankt peterburg" to "saint petersburg",
        "st petersburg" to "saint petersburg",
        "st. petersburg" to "saint petersburg",
        "roma" to "rome", "firenze" to "florence", "milano" to "milan",
        "napoli" to "naples", "torino" to "turin", "venezia" to "venice",
        "genova" to "genoa", "lisboa" to "lisbon", "porto" to "oporto",
        "sevilla" to "seville", "zaragoza" to "saragossa",
        "warszawa" to "warsaw", "krakow" to "cracow", "gdansk" to "danzig",
        "beograd" to "belgrade", "zagreb" to "zagreb",
        "bucuresti" to "bucharest", "kobenhavn" to "copenhagen",
        "goteborg" to "gothenburg", "malmo" to "malmo",
        "zurich" to "zurich", "geneve" to "geneva", "genf" to "geneva",
        "basel" to "basel", "bern" to "berne",
        "den haag" to "the hague", "s-gravenhage" to "the hague",
        "'s-gravenhage" to "the hague", "antwerpen" to "antwerp",
        "anvers" to "antwerp", "gent" to "ghent", "gand" to "ghent",
        "brugge" to "bruges", "bruxelles" to "brussels",
        "brussel" to "brussels", "liege" to "liege", "luik" to "liege",
        "athina" to "athens", "athinai" to "athens",
        "thessaloniki" to "salonica", "istanbul" to "istanbul",
        "izmir" to "izmir", "kyiv" to "kyiv", "kiev" to "kyiv",
        "lviv" to "lviv", "lvov" to "lviv", "odesa" to "odesa",
        "odessa" to "odesa", "minsk" to "minsk", "tallinn" to "tallinn",
        "riga" to "riga", "vilnius" to "vilnius", "helsinki" to "helsinki",
        "helsingfors" to "helsinki", "oslo" to "oslo",
        "reykjavik" to "reykjavik", "dublin" to "dublin",
        "baile atha cliath" to "dublin", "edinburgh" to "edinburgh",
        "beijing" to "beijing", "peking" to "beijing",
        "guangzhou" to "guangzhou",
        // "canton" is also a town in Ohio, Michigan, Georgia and
        // Massachusetts, and the word for a Swiss subdivision.

        "shanghai" to "shanghai", "hong kong" to "hong kong",
        "hongkong" to "hong kong", "hk" to "hong kong",
        "tokyo" to "tokyo", "tokio" to "tokyo", "kyoto" to "kyoto",
        "osaka" to "osaka", "seoul" to "seoul",
        "mumbai" to "mumbai", "bombay" to "mumbai",
        "kolkata" to "kolkata", "calcutta" to "kolkata",
        "chennai" to "chennai", "madras" to "chennai",
        "bengaluru" to "bengaluru", "bangalore" to "bengaluru",
        "delhi" to "delhi", "new delhi" to "delhi",
        "al qahirah" to "cairo", "cairo" to "cairo",
        "mexico city" to "mexico city", "ciudad de mexico" to "mexico city",
        "cdmx" to "mexico city", "sao paulo" to "sao paulo",
        "rio de janeiro" to "rio de janeiro", "rio" to "rio de janeiro",
        "buenos aires" to "buenos aires", "nyc" to "new york",
        "new york city" to "new york", "ny" to "new york",
        "sf" to "san francisco", "san fran" to "san francisco",
        "bay area" to "san francisco bay area",
        "la" to "los angeles", "l.a." to "los angeles",
        "dc" to "washington", "washington dc" to "washington",
        "washington d.c." to "washington",
        )

    /** The two-letter city shorthands, which collide most. */
    private val SHORT_CITY_CODES = setOf("la", "ny", "sf", "dc", "hk")

    /** Where the city and the country are one and the same. */
    private val CITY_STATES = setOf("singapore", "monaco", "luxembourg", "vatican city")

    /**
     * Anything in this table with more than one plausible meaning.
     *
     * Codes AND names. The first version intersected codes only, so a
     * collision between a US state's NAME and a country's name was
     * invisible to it: `SUBDIVISIONS` maps "ga" to "georgia", which
     * is also a country, so "Atlanta, GA" was filed as a claim about
     * the Caucasus — graded as corroborated when a second source
     * mentioned the country, and then contradicted by its own posting
     * hours, which `ActivityHours` measured against Tbilisi.
     *
     * A collision resolves to itself, which merges with another copy
     * of itself and with nothing else. That is exactly as much as is
     * actually known, and the alternative — guessing by position —
     * fails on "Toronto, CA" and on "Atlanta, GA" in opposite
     * directions.
     */
    private val AMBIGUOUS: Set<String> = buildSet {
        val subdivisionCodes = SUBDIVISIONS.map { it.first }.toSet()
        val subdivisionNames = SUBDIVISIONS.map { it.second }.toSet()
        val cityNames = CITY_ALIASES.map { it.second }.toSet()
        addAll(COUNTRY_CODES.filter { it.length <= 3 && it in subdivisionCodes })
        // Georgia the state and Georgia the country.
        addAll(COUNTRY_NAMES.filter { it in subdivisionNames })
        // Washington the city and Washington the state; Georgia again.
        addAll(cityNames.filter { it in subdivisionNames || it in COUNTRY_NAMES })
        // And the city abbreviations that collide with a subdivision:
        // LA is Los Angeles and Louisiana.
        addAll(SHORT_CITY_CODES.filter { it in subdivisionCodes || it in COUNTRY_CODES })
        // Except where the city and the country are the same place,
        // which is what a city-state is.
        removeAll(CITY_STATES)
    }


    private val ALIASES: Map<String, String> = buildMap {
        // Cities whose English name differs from the local one. These
        // are the ones that actually turn up in a profile's location
        // field; a complete gazetteer would be a different project.
        CITY_ALIASES.forEach { (from, to) -> put(from, to) }

        // Countries: ISO alpha-2, alpha-3 and the names people write,
        // all counted under the English name.
        COUNTRIES.forEach { (name, codes) ->
            put(name, name)
            codes.forEach { put(it, name) }
        }
        OTHER_COUNTRY_NAMES.forEach { (from, to) -> put(from, to) }

        // US states and Canadian provinces, because "Austin, TX" and
        // "Austin, Texas" are one place.
        SUBDIVISIONS.forEach { (code, name) -> put(code, name) }
        SUBDIVISION_NAMES.forEach { put(it, it) }

        // And then every short code that could mean two things is put
        // back to meaning itself.
        //
        // Half the subdivision codes are also country codes: CA is
        // California and Canada, DE is Delaware and Germany, AL, AR,
        // ID, IL, MA, MD, MN, PA, SK, TN and a dozen more. Guessing
        // between them by position works until it does not — "Toronto,
        // CA" would become California — and a wrong merge is the one
        // failure this table must not produce, because two entries
        // where there should be one is a reader noticing, while one
        // entry that merged two different places is a reader misled.
        // An ambiguous code therefore merges only with another copy of
        // itself, which is exactly as much as is actually known.
        AMBIGUOUS.forEach { put(it, it) }
    }



    /**
     * One written place, reduced to the form everything counts under,
     * or null when it is not a place at all.
     */
    fun canonical(raw: String): String? {
        val cleaned = clean(raw) ?: return null
        return ALIASES[cleaned] ?: cleaned
    }

    /**
     * A written place and the pieces of it worth counting separately.
     *
     * "Vienna, Austria" is both a claim about a city and a claim about
     * a country, and the second is what lets a source that only says
     * "AT" agree with it. Returned whole-first, deduplicated, and only
     * for two or three components — a longer list is an address, and
     * splitting an address produces street names as places.
     */
    fun parts(raw: String): List<String> {
        val whole = canonical(raw) ?: return emptyList()
        val pieces = raw.split(',', '·', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (pieces.size !in 2..3) return listOf(whole)
        val out = LinkedHashSet<String>()
        out += whole
        pieces.forEach { piece -> canonical(piece)?.let { out += it } }
        return out.toList()
    }

    /**
     * Whether a canonical form names a country rather than somewhere
     * in one — and is not also the name of something else.
     *
     * Georgia is a country and a US state. Putting it in the
     * country-level exclusive group meant "Atlanta, GA" was graded as
     * a claim about the Caucasus, corroborated by anything that
     * mentioned the country, and then contradicted by its own posting
     * hours. A name with two meanings is not a country for the
     * purpose of deciding what contradicts what.
     */
    fun isCountry(canonical: String): Boolean =
        canonical in COUNTRY_NAMES && canonical !in AMBIGUOUS

    private fun clean(raw: String): String? {
        var v = raw.trim()
        if (v.isEmpty() || v.length > 60) return null
        if (v.contains('@') || v.contains("http", true) || v.contains('/')) return null
        // Diacritics removed, so Zürich and Zurich are one word before
        // the alias table is consulted at all.
        v = Normalizer.normalize(v, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        v = v.lowercase(Locale.US)
            .replace(Regex("[\\p{Punct}&&[^.,'\\-]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', ',', '-', '\'')
        if (v.isEmpty() || v.length < 2) return null
        if (v in NOT_A_PLACE) return null
        // A place name made only of digits is a postcode or a mistake.
        if (v.none { it.isLetter() }) return null
        return v
    }

}
