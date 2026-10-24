package com.osint.unified.domain

/**
 * A person's name, taken apart.
 *
 * Every other observable in this app has a shape the code understands.
 * A domain is labels and a suffix; an IP is four octets; a coordinate
 * is two numbers. A name was a string, handed to each source exactly
 * as typed — so "Smith, John" found nothing that "John Smith" found,
 * a search for "Dr. Jane A. Goodall PhD" was a search for that whole
 * phrase, and "Bob Dylan" and "Robert Dylan" were two unrelated
 * enquiries. People are the one thing this app was worst at, and a
 * large part of the reason is that it never looked at the name.
 *
 * Nothing here guesses at identity. It rearranges what was typed into
 * the forms a source is likely to hold it under, and says which is
 * which. The caller decides what to do with them.
 */
object PersonName {

    /**
     * Words that attach to the family name rather than standing alone.
     *
     * "van der Berg" is one surname in three words, and splitting on
     * spaces makes "der" a middle name and "Berg" the family name —
     * which is how a search for a Dutch or Portuguese name misses
     * everything.
     */
    private val PARTICLES = setOf(
        "van", "von", "der", "den", "de", "del", "della", "di", "da",
        "dos", "das", "du", "la", "le", "les", "el", "al", "bin", "ibn",
        "bint", "abu", "ter", "ten", "op", "af", "zu", "do", "mac", "mc",
        "san", "santa", "st", "saint", "ap", "ben", "bar"
    )

    /** Honorifics, which are not part of anybody's name. */
    private val TITLES = setOf(
        "mr", "mrs", "ms", "miss", "mx", "dr", "doctor", "prof",
        "professor", "sir", "dame", "lord", "lady", "rev", "reverend",
        "hon", "capt", "captain", "col", "colonel", "gen", "general",
        "lt", "sgt", "maj", "adm", "judge", "justice", "rabbi", "imam",
        "fr", "father", "sr.", "sister"
    )

    /** Generational and post-nominal suffixes. */
    private val SUFFIXES = setOf(
        "jr", "sr", "ii", "iii", "iv", "v", "phd", "md", "dds", "esq",
        "jd", "mba", "cpa", "rn", "do", "dvm", "msc", "bsc", "ma", "ba"
    )

    /**
     * Words that mean this is an organisation, not a person.
     *
     * The router sends every "two or more words" query down the person
     * route, which fires the company registries at people and the
     * people sources at companies. One is noise and the other is a
     * missed answer.
     */
    private val COMPANY_WORDS = setOf(
        "inc", "inc.", "llc", "ltd", "ltd.", "limited", "plc", "corp",
        "corp.", "corporation", "company", "co", "co.", "gmbh", "ag",
        "sa", "s.a.", "nv", "bv", "oy", "ab", "as", "aps", "spa", "srl",
        "pty", "pte", "kk", "kft", "sarl", "sas", "holdings", "holding",
        "group", "partners", "ventures", "capital", "foundation",
        "trust", "association", "institute", "university", "college",
        "hospital", "bank", "insurance", "systems", "solutions",
        "technologies", "industries", "enterprises", "international",
        "worldwide", "global", "services", "consulting", "labs",
        "laboratories", "media", "studios", "press", "publishing"
    )

    /**
     * Short forms that are the same person.
     *
     * One direction only in the table; [diminutives] reads it both
     * ways. Deliberately short and English-leaning, because a wrong
     * expansion is a wrong search and this list has to be one anybody
     * can check. It is not an identity claim — a variant is a query
     * to try, and anything it finds still has to agree with something
     * else before the app says it is the same person.
     */
    private val SHORT_FORMS: Map<String, List<String>> = mapOf(
        "abigail" to listOf("abby", "gail"),
        "alexander" to listOf("alex", "alec", "sasha", "xander"),
        "alexandra" to listOf("alex", "sasha", "sandra", "lexi"),
        "andrew" to listOf("andy", "drew"),
        "anthony" to listOf("tony", "ant"),
        "benjamin" to listOf("ben", "benny"),
        "catherine" to listOf("cathy", "kate", "katie", "cat"),
        "charles" to listOf("charlie", "chuck", "chas"),
        "christopher" to listOf("chris", "kit"),
        "daniel" to listOf("dan", "danny"),
        "deborah" to listOf("deb", "debbie"),
        "edward" to listOf("ed", "eddie", "ted", "ned"),
        "elizabeth" to listOf("liz", "beth", "betty", "eliza", "lizzie"),
        "frederick" to listOf("fred", "freddie"),
        "gregory" to listOf("greg"),
        "james" to listOf("jim", "jimmy", "jamie"),
        "jennifer" to listOf("jen", "jenny"),
        "joanna" to listOf("jo", "joan"),
        "john" to listOf("jack", "johnny", "jon"),
        "jonathan" to listOf("jon", "johnny"),
        "joseph" to listOf("joe", "joey"),
        "katherine" to listOf("kate", "katie", "kathy", "kay"),
        "kenneth" to listOf("ken", "kenny"),
        "lawrence" to listOf("larry", "laurie"),
        "margaret" to listOf("maggie", "meg", "peggy", "marge"),
        "matthew" to listOf("matt"),
        "michael" to listOf("mike", "mick", "micky"),
        "nicholas" to listOf("nick", "nico"),
        "patricia" to listOf("pat", "patty", "trish"),
        "patrick" to listOf("pat", "paddy"),
        "peter" to listOf("pete"),
        "philip" to listOf("phil"),
        "rebecca" to listOf("becky", "becca"),
        "richard" to listOf("rick", "dick", "rich", "richie"),
        "robert" to listOf("bob", "rob", "bobby", "robbie"),
        "ronald" to listOf("ron", "ronnie"),
        "samuel" to listOf("sam", "sammy"),
        "stephen" to listOf("steve", "steph"),
        "steven" to listOf("steve"),
        "susan" to listOf("sue", "susie"),
        "theodore" to listOf("ted", "teddy", "theo"),
        "thomas" to listOf("tom", "tommy"),
        "timothy" to listOf("tim", "timmy"),
        "victoria" to listOf("vicky", "tori"),
        "vincent" to listOf("vince", "vinny"),
        "walter" to listOf("walt"),
        "william" to listOf("will", "bill", "billy", "liam")
    )

    private val FROM_SHORT: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        SHORT_FORMS.forEach { (full, shorts) ->
            shorts.forEach { getOrPut(it) { mutableListOf() }.add(full) }
        }
    }

    /** A name as the app understands it. */
    data class Parsed(
        val given: String,
        val middles: List<String>,
        val family: String,
        val suffix: String,
        /** True when the words look like an organisation. */
        val isOrganisation: Boolean,
        /** What was typed, trimmed. */
        val raw: String
    ) {
        val full: String
            get() = listOf(given, middles.joinToString(" "), family)
                .filter { it.isNotBlank() }.joinToString(" ")

        val initials: String
            get() = (listOf(given) + middles).mapNotNull { it.firstOrNull()?.uppercase() }
                .joinToString("")

        val hasBothParts: Boolean get() = given.isNotBlank() && family.isNotBlank()
    }

    /** True when this looks like a company rather than a person. */
    fun looksLikeOrganisation(raw: String): Boolean {
        val words = raw.lowercase().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        if (words.any { it.trim('.', ',') in COMPANY_WORDS }) return true
        // Four or more words with no particle between them is far more
        // often an organisation than a person.
        return words.size >= 5
    }

    /**
     * A written name reduced to the form two sources are counted under.
     *
     * Five platforms declaring the same person write it five ways:
     * "José García", "Jose Garcia", "García, José", "J. García". The
     * corroboration engine counts assertions by their value, so
     * without this they are five findings of one source each rather
     * than one finding with five witnesses — which is the whole point
     * of reading declared names at all.
     *
     * Given name and family name only: middles and titles are dropped,
     * because a source that prints them and a source that does not are
     * still naming the same person. Diacritics go too, so the merge
     * does not depend on which keyboard somebody had. Returns null for
     * anything that reads as an organisation — those are counted whole
     * under ORG, where "Ltd" is part of the name rather than noise.
     */
    fun normalise(raw: String): String? {
        val parsed = parse(raw)
        if (parsed.isOrganisation) return null
        val words = listOf(parsed.given, parsed.family)
            .filter { it.isNotBlank() }
            .map { word ->
                java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{Mn}+"), "")
                    .lowercase(java.util.Locale.US)
                    .trim('.', ',', '\'', '-')
            }
            .filter { it.length > 1 }
        if (words.isEmpty()) return null
        return words.joinToString(" ")
    }

    fun parse(raw: String): Parsed {
        val trimmed = raw.trim()
        // "Smith, John" — the form every official list uses, and the
        // one the app never recognised.
        val commaFirst = trimmed.contains(',') &&
            trimmed.substringAfter(',').isNotBlank() &&
            !looksLikeOrganisation(trimmed)
        val reordered = if (commaFirst) {
            trimmed.substringAfter(',').trim() + " " + trimmed.substringBefore(',').trim()
        } else {
            trimmed
        }

        var words = reordered.split(Regex("\\s+"))
            .map { it.trim().trim(',') }
            .filter { it.isNotBlank() }

        // Titles off the front, suffixes off the back.
        while (words.size > 1 && words.first().lowercase().trim('.') in TITLES) {
            words = words.drop(1)
        }
        val suffixes = mutableListOf<String>()
        while (words.size > 2 && words.last().lowercase().trim('.', ',') in SUFFIXES) {
            suffixes.add(0, words.last())
            words = words.dropLast(1)
        }

        if (words.isEmpty()) {
            return Parsed("", emptyList(), "", "", looksLikeOrganisation(trimmed), trimmed)
        }
        if (words.size == 1) {
            return Parsed(
                given = words[0], middles = emptyList(), family = "",
                suffix = suffixes.joinToString(" "),
                isOrganisation = looksLikeOrganisation(trimmed), raw = trimmed
            )
        }

        // The family name runs back from the end through any particles.
        var start = words.size - 1
        while (start > 1 && words[start - 1].lowercase().trim('.') in PARTICLES) start--
        val family = words.subList(start, words.size).joinToString(" ")
        val front = words.subList(0, start)
        return Parsed(
            given = front.firstOrNull().orEmpty(),
            middles = front.drop(1),
            family = family,
            suffix = suffixes.joinToString(" "),
            isOrganisation = looksLikeOrganisation(trimmed),
            raw = trimmed
        )
    }

    /** Short forms of a given name, and the long forms of a short one. */
    fun diminutives(given: String): List<String> {
        val g = given.lowercase().trim('.')
        return ((SHORT_FORMS[g] ?: emptyList()) + (FROM_SHORT[g] ?: emptyList()))
            .distinct()
    }

    /**
     * The forms a source might hold this name under.
     *
     * Ordered by how likely each is to be the record rather than a
     * coincidence, because a caller will usually take the first few.
     * The original is always first and always present, even when
     * nothing could be parsed out of it.
     */
    fun variants(raw: String, limit: Int = 8): List<String> {
        val p = parse(raw)
        val out = LinkedHashSet<String>()
        out.add(p.raw)
        if (!p.hasBothParts || p.isOrganisation) return out.toList()

        val middle = p.middles.joinToString(" ")
        out.add(p.full)
        out.add("${p.family}, ${p.given}")
        if (middle.isNotBlank()) {
            out.add("${p.given} ${p.family}")
            out.add("${p.given} ${middle.first()}. ${p.family}")
        }
        out.add("${p.given.first()}. ${p.family}")
        // Capitalised, because these are going into a search box and
        // several of the sources behind it match on the literal
        // string: "bob Smith" is not how anybody writes it down.
        diminutives(p.given).forEach { short ->
            out.add(short.replaceFirstChar { c -> c.uppercase() } + " ${p.family}")
        }
        return out.filter { it.isNotBlank() }.take(limit)
    }
}
