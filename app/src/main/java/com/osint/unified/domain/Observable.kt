package com.osint.unified.domain

/**
 * A seed observable the user enters. The [ObservableRouter] classifies raw
 * input into one of these types, which in turn drives tool selection.
 */
sealed interface Observable {
    val raw: String

    data class Email(override val raw: String) : Observable
    data class Username(override val raw: String) : Observable
    data class Domain(override val raw: String) : Observable
    data class IpAddress(override val raw: String) : Observable
    data class PhoneNumber(override val raw: String) : Observable
    data class Coordinate(override val raw: String, val lat: Double, val lon: Double) : Observable
    data class Unknown(override val raw: String) : Observable

    /** Short human-readable label for the detected type. */
    val typeLabel: String
        get() = when (this) {
            is Email -> "Email"
            is Username -> "Username"
            is Domain -> "Domain"
            is IpAddress -> "IP / CIDR"
            is PhoneNumber -> "Phone"
            is Coordinate -> "Coordinate"
            is Unknown -> "Unknown"
        }
}
