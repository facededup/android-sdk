package ng.facededup.liveness

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects the canonical DeviceContext (same shape as app/signals/schema.py and
 * the web SDK). Maximum-signal posture, but every field is gated by the consent
 * the host app obtained (device_signals / precise_location scopes).
 *
 * STARTER CODE: builds the JSON contract and the integrity/device signals.
 * Wire Play Integrity (attestation), FusedLocationProvider (precise location)
 * and ConnectivityManager (network) where marked, then build/test on-device.
 */
object SignalCollector {

    private fun iso(date: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date)

    /**
     * @param attestationToken Play Integrity token (obtain via
     *   IntegrityManager.requestIntegrityToken; verify server-side, A3e).
     * @param location pass a fused-location result when precise_location is consented.
     */
    fun collect(
        ctx: Context,
        attestationToken: String? = null,
        location: LocationFix? = null,
        appVersion: String? = null,
    ): JSONObject {
        val device = JSONObject().apply {
            put("platform", "android")
            put("os", "android")
            put("os_version", Build.VERSION.RELEASE)
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("sdk_version", BuildConfigCompat.SDK_VERSION)
            put("app_version", appVersion)
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", TimeZone.getDefault().id)
            put("is_emulator", Emulator.isProbablyEmulator())
            put("is_rooted_jailbroken", RootCheck.isRooted())
            put("is_debugger_attached", android.os.Debug.isDebuggerConnected())
            // App-scoped id (NOT IMEI). Hash before sending if policy requires.
            put("device_id", Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ANDROID_ID))
        }
        val network = JSONObject().apply {
            put("connection_type", NetworkInfoCompat.connectionType(ctx))
            put("carrier", NetworkInfoCompat.carrier(ctx))
        }
        val root = JSONObject().apply {
            put("device", device)
            put("network", network)
            location?.let {
                put("location", JSONObject().apply {
                    put("lat", it.lat); put("lng", it.lng)
                    put("accuracy_m", it.accuracyM); put("source", it.source)
                    put("captured_at", iso(Date(it.timeMs)))
                })
            }
            put("timing", JSONObject().put("client_timestamp", iso()))
            attestationToken?.let { put("attestation_token", it) }
        }
        return root
    }

    data class LocationFix(
        val lat: Double, val lng: Double, val accuracyM: Float,
        val source: String, val timeMs: Long,
    )
}
