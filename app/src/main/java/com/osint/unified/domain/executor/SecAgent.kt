package com.osint.unified.domain.executor

/**
 * The user agent the SEC requires.
 *
 * EDGAR rejects generic agents with 403, and it specifically wants a
 * contact *address* — a descriptive string carrying only a URL still gets
 * refused, which is why the EDGAR card was showing HTTP 403 in the field.
 * Tested against the live endpoint: this form returns 200.
 */
internal const val SEC_USER_AGENT = "OSINT Unified research woofdog05@gmail.com"
