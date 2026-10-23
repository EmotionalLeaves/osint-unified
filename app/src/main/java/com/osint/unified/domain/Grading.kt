package com.osint.unified.domain

/**
 * The Admiralty code, which is how intelligence has been graded since the
 * 1940s and how every serious OSINT framework still says to do it.
 *
 * Two scales, rated independently. [Reliability] is a property of the
 * source and barely changes: certificate transparency logs are machine
 * records of something that provably happened, while a site that answers
 * 200 to any profile URL is not a witness at all. [Credibility] is a
 * property of one claim on one occasion, and depends mostly on whether
 * anything else agrees with it.
 *
 * Keeping them apart is the point of the system. A completely reliable
 * source can report something unverifiable, and an unreliable one can
 * report something three others confirm; collapsing the two into a single
 * "confidence" number loses exactly the distinction an investigator needs.
 * Written as A–F and 1–6 because that is what the rest of the field reads,
 * and because "B2" carries more than "fairly confident" ever will.
 */
object Grading {

    /** How much the source itself is worth, before it says anything. */
    enum class Reliability(val code: Char, val label: String, val why: String) {
        /** A record of the thing itself: a signed certificate, a registry. */
        COMPLETE('A', "completely reliable", "an authoritative record of the thing itself"),

        /** An operator publishing its own data: a registry API, a government feed. */
        USUALLY('B', "usually reliable", "the operator's own published data"),

        /** A third party reporting what it observed: scanners, archives. */
        FAIRLY('C', "fairly reliable", "a third party reporting what it saw"),

        /** Aggregated or derived data of unclear provenance. */
        NOT_USUALLY('D', "not usually reliable", "aggregated from sources it does not name"),

        /** A heuristic. A name is taken somewhere; that is all it means. */
        UNRELIABLE('E', "unreliable", "a pattern match, not an observation"),

        /** No basis to judge it. */
        UNJUDGED('F', "reliability cannot be judged", "nothing to judge it on")
    }

    /** How much this particular claim is worth, given everything else. */
    enum class Credibility(val code: Int, val label: String) {
        CONFIRMED(1, "confirmed by other sources"),
        PROBABLE(2, "probably true"),
        POSSIBLE(3, "possibly true"),
        DOUBTFUL(4, "doubtful"),
        IMPROBABLE(5, "improbable"),
        UNJUDGED(6, "truth cannot be judged")
    }

    /** "B2", the way it is written everywhere else. */
    fun code(reliability: Reliability, credibility: Credibility): String =
        "${reliability.code}${credibility.code}"

    /**
     * The grade one assertion earns.
     *
     * [agreeing] counts the *other* independent sources saying the same
     * thing, [contradicting] those saying the opposite. The rule the
     * frameworks give is that corroboration is what moves a claim up, and
     * nothing a single source says is ever "confirmed" — however good the
     * source is, one witness is one witness.
     */
    fun credibility(
        source: Reliability,
        agreeing: Int,
        contradicting: Int = 0
    ): Credibility = when {
        contradicting > agreeing -> Credibility.IMPROBABLE
        agreeing >= 1 && source <= Reliability.FAIRLY -> Credibility.CONFIRMED
        agreeing >= 2 -> Credibility.CONFIRMED
        agreeing == 1 -> Credibility.PROBABLE
        source <= Reliability.USUALLY -> Credibility.PROBABLE
        source == Reliability.FAIRLY -> Credibility.POSSIBLE
        source == Reliability.NOT_USUALLY -> Credibility.DOUBTFUL
        source == Reliability.UNRELIABLE -> Credibility.DOUBTFUL
        else -> Credibility.UNJUDGED
    }

    /**
     * What kind of statement this is, which the frameworks insist on
     * keeping visibly apart and which this app used to pool into
     * "results".
     */
    enum class Kind(val label: String) {
        /** The app went and looked, and this is what was there. */
        OBSERVED("observed"),

        /** Somebody else says so, and the app is passing it on. */
        REPORTED("reported"),

        /** The app worked this out from other things it holds. */
        INFERRED("inferred"),

        /** Something that needs checking and has not been checked. */
        OPEN("open question")
    }
}
