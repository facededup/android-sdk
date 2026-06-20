package ng.facededup.sdk

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.core.content.ContextCompat
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.TimeZone

/**
 * Anti-fraud device/session signals collected at capture and submitted alongside the
 * frames (server stores + webhooks them as evidence). Best-effort: every probe is
 * guarded so a missing API/permission never breaks capture. Permission-gated signals
 * (geolocation) are included ONLY if the host already holds the permission — the SDK
 * never prompts for them.
 */
internal object DeviceMetadata {

    fun collect(ctx: Context, captureDurationMs: Long, retries: Int,
                attestChallenge: String, movement: Map<String, Any?>): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        // capture provenance (anti-injection: native capture is ALWAYS the live camera)
        m["SelfieImageOrigin"] = "camera"
        m["SelfieImageColorDepth"] = 24
        m["SelfieCaptureDuration"] = captureDurationMs
        m["SelfieCaptureRetries"] = retries
        m["SdkLaunchCount"] = bumpLaunchCount(ctx)
        m["Fingerprint"] = safe { Build.FINGERPRINT }
        m["DeviceModel"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        m["DeviceOS"] = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        m["SdkVersion"] = "2.0.0-alpha03"
        m["Locale"] = safe { ctx.resources.configuration.locales[0].toLanguageTag() }
        m["Timezone"] = TimeZone.getDefault().id
        m["ScreenResolution"] = safe {
            val dm = ctx.resources.displayMetrics; "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}"
        }
        m["NumberOfCameras"] = safe { (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.size }

        // network integrity
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = safe { cm?.getNetworkCapabilities(cm.activeNetwork) }
        m["VPNDetected"] = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
        m["ProxyDetected"] = (cm?.defaultProxy != null) ||
            (System.getProperty("http.proxyHost")?.isNotEmpty() == true)
        m["NetworkConnection"] = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        m["CarrierInfo"] = safe {
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkOperatorName
        }

        // battery
        m["BatteryStatus"] = safe {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            mapOf("level" to if (scale > 0) (level * 100 / scale) else level, "charging" to charging)
        }

        // movement / orientation / proximity (sampled during capture)
        m.putAll(movement)
        m["DeviceOrientation"] = if (ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

        // geolocation — ONLY if the host already granted it (never prompt)
        m["Geolocation"] = geoIfPermitted(ctx)

        // hardware key attestation certificate chain (API 24+, hardware-backed keystore)
        m["AttestationCertificateChain"] = attestationChain(attestChallenge)
        m["SupportsHardwareAttestation"] = (m["AttestationCertificateChain"] != null)

        return m
    }

    private fun bumpLaunchCount(ctx: Context): Int {
        val p = ctx.getSharedPreferences("facededup_sdk", Context.MODE_PRIVATE)
        val n = p.getInt("launch_count", 0) + 1
        p.edit().putInt("launch_count", n).apply()
        return n
    }

    private fun geoIfPermitted(ctx: Context): Map<String, Any?>? {
        val fine = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return safe {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time } ?: return@safe null
            mapOf("lat" to loc.latitude, "lon" to loc.longitude, "accuracy_m" to loc.accuracy.toDouble())
        }
    }

    private fun attestationChain(challenge: String): List<String>? {
        if (Build.VERSION.SDK_INT < 24) return null
        return safe {
            val alias = "facededup_attest_" + System.currentTimeMillis()
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge.toByteArray())
                    .build())
            kpg.generateKeyPair()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val chain = ks.getCertificateChain(alias) ?: return@safe null
            runCatching { (ks as KeyStore).deleteEntry(alias) }
            chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (e: Throwable) { null }
}
