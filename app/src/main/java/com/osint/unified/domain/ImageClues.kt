package com.osint.unified.domain

import com.osint.unified.data.ImageText

/**
 * Turning the text in a photograph into things this app can look up.
 *
 * Reading the words out of a picture is half the job; the other half
 * is knowing which of them is worth anything. A street sign is a
 * place name, a van carries a phone number, a shopfront carries a
 * business name and sometimes a domain, and a notice carries the
 * language and the alphabet — which on their own narrow the planet
 * to a region before anything has been looked up at all.
 *
 * Nothing here guesses a location. It sorts, says what each kind of
 * string is good for, and leaves the conclusion to the person.
 */
object ImageClues {

    enum class Kind {
        DOMAIN, EMAIL, PHONE, PLATE, ROAD, COORDINATE, PLACE, OTHER
    }

    data class Clue(val kind: Kind, val value: String, val why: String)

    // Through Hostnames, like the other scanners: OCR text is short
    // today, but the pattern that was here is the one that crashes
    // on four kilobytes of dotted labels and there is no reason for
    // a fifth copy of it.
    // Loose on purpose: the point is to notice that a number is on
    // the van, not to validate it. The phone tool parses it properly.
    private val PHONE = Regex("""(?:\+|00)\s?\d[\d\s().-]{6,18}\d|\b0\d[\d\s().-]{7,16}\d\b""")
    private val COORD = Regex("""(-?\d{1,2}\.\d{3,})\s*[,/]\s*(-?\d{1,3}\.\d{3,})""")
    // European-style plates and the common British shape. Enough to
    // flag "that looks like a plate", which is all a cue list needs.
    private val PLATE = Regex("""\b[A-Z]{2}\d{2}\s?[A-Z]{3}\b|\b[A-Z]{1,3}[- ]?\d{1,4}[- ]?[A-Z]{1,3}\b""")
    private val ROAD_WORDS = setOf(
        "street", "st", "road", "rd", "avenue", "ave", "lane", "ln", "drive",
        "boulevard", "blvd", "way", "close", "court", "place", "square",
        "strasse", "straße", "rue", "calle", "avenida", "via", "viale",
        "plaza", "platz", "gata", "gade", "weg", "laan", "straat", "utca",
        "ulica", "ulitsa", "jalan", "dori", "lu", "jie"
    )

    /** A cue in the picture is one of these, or it is prose. */
    fun clues(read: ImageText.Read): List<Clue> {
        val out = LinkedHashMap<String, Clue>()
        fun add(kind: Kind, value: String, why: String) {
            val v = value.trim()
            if (v.length < 3) return
            out.putIfAbsent(kind.name + "|" + v.lowercase(), Clue(kind, v, why))
        }
        read.lines.forEach { line ->
            val t = line.text
            COORD.findAll(t).forEach {
                add(
                    Kind.COORDINATE, it.value,
                    "A coordinate printed in the frame. Run it as a coordinate: " +
                        "reverse geocode it and see whether what is mapped there " +
                        "matches what is in the picture."
                )
            }
            val addresses = Hostnames.emailsIn(t)
            addresses.forEach {
                add(
                    Kind.EMAIL, it,
                    "An address on a sign belongs to whoever runs the premises. " +
                        "Run it as an email."
                )
            }
            Hostnames.hostsIn(t).forEach { host ->
                val d = host.lowercase()
                // An email's domain is already covered by the email.
                if (addresses.isNotEmpty() && t.contains("@$d", true)) return@forEach
                // The guard Identify was given and this, edited in the
                // same commit, was not. OCR of a screenshot or a
                // whiteboard reads `logo.png` and `report.pdf`, and
                // both became a domain to investigate under the words
                // "a business whose site is painted on its own wall".
                //
                // Dropping is right here, unlike in the decoder: this
                // is a list of leads to offer, and a lead that is
                // almost always a filename is not worth one of the
                // few slots.
                if (d.substringAfterLast('.') in ObservableExtractor.NOT_HOSTS) return@forEach
                add(
                    Kind.DOMAIN, d,
                    "A business whose site is painted on its own wall is a " +
                        "business with an address on that site. Run it as a domain."
                )
            }
            PHONE.findAll(t).forEach {
                add(
                    Kind.PHONE, it.value,
                    "A number on a shopfront or a van carries a country and " +
                        "usually an area. Run it as a phone number — the " +
                        "numbering plan alone narrows the map."
                )
            }
            if (looksLikeRoad(t)) {
                add(
                    Kind.ROAD, t,
                    "A street name. On its own it is in a thousand towns; with " +
                        "one more cue from this list it is in one. Search it as " +
                        "a place together with anything else here."
                )
            } else if (PLATE.matches(t.trim())) {
                add(
                    Kind.PLATE, t.trim(),
                    "The shape of a registration plate is national before its " +
                        "characters mean anything — the proportions, the colour " +
                        "of the strip and whether there is a country band on the " +
                        "left narrow it without reading a single character."
                )
            } else if (t.length in 3..40 && t.any { it.isLetter() }) {
                add(
                    Kind.PLACE, t,
                    "A name on something. Worth searching as a place or a " +
                        "business, especially with another line from this list."
                )
            }
        }
        return out.values.toList()
    }

    private fun looksLikeRoad(line: String): Boolean {
        val words = line.lowercase().split(Regex("""[\s,.]+""")).filter { it.isNotBlank() }
        if (words.size !in 1..6) return false
        return words.any { it.trim('.') in ROAD_WORDS } ||
            // German and Scandinavian compounds put it on the end.
            words.any { w ->
                w.length > 6 && ROAD_WORDS.any { w.endsWith(it) && it.length > 3 }
            }
    }

    /**
     * Which alphabets are in the frame.
     *
     * Before any word is understood, the script it is written in has
     * already excluded most of the planet. This is the single
     * cheapest geolocation cue there is and it needs no lookup at
     * all.
     */
    fun scripts(read: ImageText.Read): List<String> {
        val found = LinkedHashSet<String>()
        read.lines.forEach { line ->
            line.text.forEach { c ->
                val name = when (Character.UnicodeBlock.of(c)) {
                    Character.UnicodeBlock.BASIC_LATIN,
                    Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                    Character.UnicodeBlock.LATIN_EXTENDED_A,
                    Character.UnicodeBlock.LATIN_EXTENDED_B -> "Latin"
                    Character.UnicodeBlock.CYRILLIC,
                    Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> "Cyrillic"
                    Character.UnicodeBlock.GREEK -> "Greek"
                    Character.UnicodeBlock.ARABIC,
                    Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
                    Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B -> "Arabic"
                    Character.UnicodeBlock.HEBREW -> "Hebrew"
                    Character.UnicodeBlock.DEVANAGARI -> "Devanagari"
                    Character.UnicodeBlock.THAI -> "Thai"
                    Character.UnicodeBlock.HANGUL_SYLLABLES,
                    Character.UnicodeBlock.HANGUL_JAMO -> "Hangul"
                    Character.UnicodeBlock.HIRAGANA -> "Hiragana"
                    Character.UnicodeBlock.KATAKANA -> "Katakana"
                    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> "Han"
                    else -> null
                }
                if (name != null) found.add(name)
            }
        }
        return found.toList()
    }

    /**
     * The things a person looks at in a photograph that no model and
     * no lookup will do for them.
     *
     * Written down because a checklist is the difference between
     * someone who geolocates pictures and someone who stares at one.
     * Every item here is visible in the frame and costs nothing.
     */
    val CHECKLIST = listOf(
        "Which side of the road is traffic on, and which side is the " +
            "driver sitting on. That one answer splits the world roughly " +
            "two to one.",
        "Plate shape and colour, and whether there is a blue band on the " +
            "left. National before any character is read.",
        "Road markings: colour of the centre line, dashed or solid, and " +
            "whether the edge line is white or yellow.",
        "Signage: the shape of the stop and give-way signs, the typeface, " +
            "and whether distances are in kilometres or miles.",
        "Utility poles — wood, concrete or steel — and whether the cables " +
            "are overhead or buried.",
        "Mains sockets and plug shapes in any interior shot.",
        "Bollards, kerb profile and paving pattern. Municipal and very " +
            "local once you know the country.",
        "Vegetation and the state of it: species, whether it is in leaf, " +
            "and how the light falls. Hemisphere and season.",
        "Architecture: roof pitch, window proportions, balcony style, " +
            "chimney and aerial types.",
        "Shadows. Their direction gives a bearing and their length gives " +
            "a sun elevation, and the two together give a latitude band " +
            "and a time of day — the coordinate tools here will do the " +
            "arithmetic once you have a candidate place.",
        "Anything with a date on it: a poster, a newspaper, a parking " +
            "ticket, a price in a window."
    )
}
