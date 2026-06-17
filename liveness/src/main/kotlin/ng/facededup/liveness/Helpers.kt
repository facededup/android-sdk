package ng.facededup.liveness

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.io.File

/** SDK version constant (kept in sync with the web SDK). */
object BuildConfigCompat {
    const val SDK_VERSION = "0.1.0"
}

/** Heuristic emulator detection (defense-in-depth; not authoritative — the
 *  server-side risk engine + Play Integrity make the real call). */
object Emulator {
    fun isProbablyEmulator(): Boolean {
        val f = Build.FINGERPRINT ?: ""
        return f.startsWith("generic") || f.contains("emulator") ||
            Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
            Build.PRODUCT == "sdk" || Build.PRODUCT.contains("sdk_gphone")
    }
}

/** Heuristic root detection. Real assurance comes from Play Integrity. */
object RootCheck {
    private val paths = arrayOf(
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
        "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/su/bin/su",
    )
    fun isRooted(): Boolean =
        Build.TAGS?.contains("test-keys") == true || paths.any { File(it).exists() }
}

object NetworkInfoCompat {
    // Requires ACCESS_NETWORK_STATE. Degrades to "unknown" if the permission is
    // missing (SecurityException) so signal collection never breaks the flow.
    fun connectionType(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
        } catch (e: Exception) { "unknown" }
    }

    fun carrier(ctx: Context): String? = try {
        (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.networkOperatorName?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }
}
