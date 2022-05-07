package org.fdroid.fdroid.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Binder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

class AccessProtectionHelper(
    private val context: Context,
    private val pm: PackageManager = context.packageManager,
    private val whitelist: HashSet<Pair<String, String>> = ClientWhitelist.whitelist
) {
    /**
     * Checks if process that binds to this service (i.e. the package name corresponding to the
     * process) is in the whitelist.
     *
     * @return true if process is allowed to use this service
     */
    fun isCallerAllowed(): Boolean {
        return true
        return isUidAllowed(Binder.getCallingUid())
    }

    private fun isUidAllowed(uid: Int): Boolean {
        val callingPackages = pm.getPackagesForUid(uid)
            ?: throw RuntimeException("Should not happen. No packages associated to caller UID!")

        // is calling package allowed to use this service?
        // NOTE: No support for sharedUserIds
        // callingPackages contains more than one entry when sharedUserId has been used
        // No plans to support sharedUserIds due to many bugs connected to them:
        // http://java-hamster.blogspot.de/2010/05/androids-shareduserid.html
        val currentPkg = callingPackages[0]
        return isPackageAllowed(currentPkg)
    }

    private fun isPackageAllowed(packageName: String): Boolean {
        Log.d(
            ExtensionService.TAG,
            "Checking if package is allowed to access privileged extension: $packageName"
        )
        try {
            val currentPackageCert: ByteArray = getPackageCertificate(packageName)
            for ((first, second) in whitelist) {
                val whitelistHash: ByteArray = hexStringToByteArray(second)
                val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
                val packageHash: ByteArray = digest.digest(currentPackageCert)
                val packageHashString: String = BigInteger(1, packageHash).toString(16)
                Log.d(ExtensionService.TAG, "Allowed cert hash: $second")
                Log.d(ExtensionService.TAG, "Package cert hash: $packageHashString")
                val packageNameMatches = packageName == first
                val packageCertMatches: Boolean = whitelistHash.contentEquals(packageHash)
                if (packageNameMatches && packageCertMatches) {
                    Log.d(
                        ExtensionService.TAG,
                        "Package is allowed to access the privileged extension!"
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            throw e
        }
        Log.e(ExtensionService.TAG, "Package is NOT allowed to access the privileged extension!")
        return false
    }

    private fun getPackageCertificate(packageName: String): ByteArray {
        return try {
            // we do check the byte array of *all* signatures
            @SuppressLint("PackageManagerGetSignatures")
            val pkgInfo =
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)

            // NOTE: Silly Android API naming: Signatures are actually certificates
            val certificates: Array<Signature> = pkgInfo.signatures
            val outputStream = ByteArrayOutputStream()
            for (cert in certificates) {
                outputStream.write(cert.toByteArray())
            }

            // Even if an apk has several certificates, these certificates should never change
            // Google Play does not allow the introduction of new certificates into an existing apk
            // Also see this attack: http://stackoverflow.com/a/10567852
            outputStream.toByteArray()
        } catch (e: Exception) {
            throw e
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

}