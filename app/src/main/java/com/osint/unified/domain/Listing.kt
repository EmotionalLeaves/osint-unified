package com.osint.unified.domain

/**
 * Saying how many were left out.
 *
 * A tool that prints "37 historical resolutions" and then lists
 * the first thirty has told the reader two things, one of which
 * is wrong: that there are thirty-seven, and — by showing a list
 * that ends — that they are looking at them. The second is the
 * damaging one, because an investigator who scans a list and
 * finds nothing concludes something from it.
 *
 * Caps are right. Thirty resolutions is as much as anybody reads
 * and the rest is noise on a phone screen. What is not right is a
 * cap that does not announce itself.
 */

/** Lists at most [first] of [items], then says what was cut. */
internal fun <T> StringBuilder.listed(
    items: List<T>,
    first: Int,
    render: (T) -> String
) {
    items.take(first).forEach { append(render(it)) }
    andMore(items.size, first)
}

/** The line on its own, for callers that render their own list. */
internal fun StringBuilder.andMore(total: Int, shown: Int) {
    if (total > shown) {
        append("…and ${total - shown} more, not listed here.\n")
    }
}
