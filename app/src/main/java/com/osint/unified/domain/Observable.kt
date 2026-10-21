package com.osint.unified.domain

/**
 * A seed the user enters. The [ObservableRouter] classifies raw input into one
 * of these types, which drives tool selection. Beyond the technical types the
 * app also accepts a general [Name] (person, place, free query), a [Url], and
 * an [ImageUrl] so almost any input produces useful intelligence.
 */
sealed interface Observable {
    val raw: String

    data class Email(override val raw: String) : Observable
    data class Username(override val raw: String) : Observable
    data class Domain(override val raw: String) : Observable
    data class IpAddress(override val raw: String) : Observable
    data class PhoneNumber(override val raw: String) : Observable
    data class Coordinate(override val raw: String, val lat: Double, val lon: Double) : Observable
    data class Url(override val raw: String) : Observable
    data class ImageUrl(override val raw: String) : Observable
    data class Name(override val raw: String) : Observable
    data class Unknown(override val raw: String) : Observable

    val typeLabel: String
        get() = when (this) {
            is Email -> "Email"
            is Username -> "Username"
            is Domain -> "Domain"
            is IpAddress -> "IP / CIDR"
            is PhoneNumber -> "Phone"
            is Coordinate -> "Coordinate"
            is Url -> "URL"
            is ImageUrl -> "Image"
            is Name -> "Name / place / query"
            is Unknown -> "Unknown"
        }
}
