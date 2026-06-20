package ng.facededup.sdk.ingest

import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Builds a [LivenessEventIngest] from the NATIVE Facededup SDK's own state — the
 * server verdict plus the [ng.facededup.sdk.DeviceMetadata] anti-fraud map. (The
 * Smile-SDK path uses [SmileMetadataMapper] instead.)
 *
 * `consent_id` must be supplied by the host (its consent-capture record). The
 * verdict drives `decision` / `risk_level` / `risk_score`; the full native metadata
 * is forwarded verbatim under `extra.facededup_metadata`.
 */
internal object IngestEventBuilder {

    fun fromNative(
        consentId: String,
        requestId: String,
        method: String,
        verdictJson: String?,
        metadata: Map<String, Any?>?,
        subjectRef: String? = null,
        sessionId: String? = null,
        totalLatencyMs: Int? = null,
        sdkVersion: String? = null,
    ): LivenessEventIngest {
        val v = runCatching { JSONObject(verdictJson ?: "{}") }.getOrDefault(JSONObject())
        val outcome = v.optStringOrNull("outcome")
        val isLive = if (v.has("is_live") && !v.isNull("is_live")) v.optBoolean("is_live") else null
        val score = if (v.has("score") && !v.isNull("score")) v.optDouble("score") else null
        val decision = normalizeDecision(outcome, isLive, v.optStringOrNull("decision"))
        val riskScore = riskScore(score, decision)

        val m = (metadata ?: emptyMap()).withDefault { null }
        val geo = m["Geolocation"] as? Map<*, *>

        return LivenessEventIngest(
            consentId  = consentId,
            requestId  = requestId,
            method     = method,
            decision   = decision,
            riskLevel  = riskLevel(riskScore),
            riskScore  = riskScore,
            sessionId  = sessionId,
            subjectRef = subjectRef,
            qualityScore = score,
            activePassed = isLive,
            ipIsVpn      = m["VPNDetected"] as? Boolean,
            ipIsHosting  = m["ProxyDetected"] as? Boolean,
            platform     = "android",
            osName       = "Android",
            osVersion    = android.os.Build.VERSION.RELEASE,
            deviceModel  = (m["DeviceModel"] as? String)
                ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            isEmulator   = isProbablyEmulator(),
            appVersion   = null,
            sdkVersion   = (m["SdkVersion"] as? String) ?: sdkVersion,
            timezone     = (m["Timezone"] as? String) ?: java.util.TimeZone.getDefault().id,
            locale       = (m["Locale"] as? String) ?: java.util.Locale.getDefault().toLanguageTag(),
            attestPresent  = (m["SupportsHardwareAttestation"] as? Boolean) ?: false,
            attestVerified = m["AttestationCertificateChain"] != null,
            geoCountry   = geo?.get("country_code") as? String,
            geoAdmin1    = geo?.get("admin1") as? String,
            captureLatencyMs = (m["SelfieCaptureDuration"] as? Number)?.toInt(),
            totalLatencyMs   = totalLatencyMs,
            extra = if (metadata.isNullOrEmpty()) JsonObject(emptyMap())
                    else JsonObject(mapOf("facededup_metadata" to metadata.toJsonElement())),
        )
    }

    /** Map the verify verdict → the ingest `decision` enum (live | not_live | referred). */
    private fun normalizeDecision(outcome: String?, isLive: Boolean?, decision: String?): String {
        val tokens = listOfNotNull(outcome, decision).map { it.lowercase() }
        return when {
            tokens.any { it in setOf("live", "pass", "match", "genuine") } || isLive == true -> "live"
            tokens.any { it in setOf("not_live", "spoof", "fail", "fake", "no_match") } || isLive == false -> "not_live"
            tokens.any { it in setOf("referred", "review", "manual", "pending", "queued") } -> "referred"
            else -> "referred"
        }
    }

    /** Liveness confidence (0..1, higher = safer) → risk score (0..100, lower = safer). */
    private fun riskScore(score: Double?, decision: String): Int = when {
        score != null -> ((1.0 - score.coerceIn(0.0, 1.0)) * 100).toInt()
        decision == "live"     -> 10
        decision == "not_live" -> 90
        else                   -> 50
    }

    private fun riskLevel(riskScore: Int): String = when {
        riskScore < 20 -> "low"
        riskScore < 50 -> "medium"
        riskScore < 80 -> "high"
        else           -> "critical"
    }

    private fun isProbablyEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        return fp.contains("generic") || fp.contains("emulator") ||
            model.contains("emulator") || model.contains("sdk_gphone") ||
            android.os.Build.PRODUCT.lowercase().let { it.contains("sdk") || it.contains("emulator") }
    }

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null
}
