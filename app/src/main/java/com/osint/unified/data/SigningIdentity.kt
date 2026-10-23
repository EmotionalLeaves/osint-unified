package com.osint.unified.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.osint.unified.BuildConfig
import java.security.MessageDigest

/**
 * Whether this build is signed with a key anybody can get hold of.
 *
 * The database is encrypted with a key sealed by the hardware keystore,
 * and that key is bound to the app's signing identity. The protection
 * therefore assumes nobody else can produce an app with the same
 * identity — which stops being true the moment the signing key is
 * published, as this project's was: committed to a public repository with
 * its password in the build file. Anyone could then sign a replacement,
 * install it as an update, and unseal the key.
 *
 * Rather than quietly depending on it being fixed, the app checks its own
 * certificate against the published one and says so when they match. When
 * the key is rotated this reports nothing and needs no further change.
 */
object SigningIdentity {

    /** True when this copy is signed with the key that is public. */
    fun signedWithAPublishedKey(context: Context): Boolean =
        fingerprint(context)?.equals(BuildConfig.PUBLIC_KEY_SHA256, ignoreCase = true) == true

    /** The SHA-256 of this app's signing certificate, lower-case hex. */
    fun fingerprint(context: Context): String? = runCatching {
        val packages = context.packageManager
        val certificate: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packages.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signing = info.signingInfo ?: return@runCatching null
            val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
            signers?.firstOrNull()?.toByteArray() ?: return@runCatching null
        } else {
            @Suppress("DEPRECATION")
            val info = packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: return@runCatching null
        }
        MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
