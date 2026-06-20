package ng.facededup.sdk.ingest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps a Smile ID Android SDK `Metadata` blob → [LivenessEventIngest], forwarding
 * the FULL Smile metadata under `extra.smile_metadata` (rendered as labelled
 * sections in the dashboard drawer).
 *
 * Only relevant when the host app ALSO integrates the Smile ID SDK. The native
 * Facededup flow uses [IngestEventBuilder] instead (our own SDK state). Kept here
 * for those integrators + the mapping unit test.
 *
 * (Renamed from the reference `SmileIDToFacededupLiveness` to this project's
 * `ng.facededup.sdk.ingest` conventions.)
 */
object SmileMetadataMapper {

    /** Map Smile SDK metadata → [LivenessEventIngest]. */
    fun build(
        smileMetadata: Map<String, Any?>,
        consentId: String,
        requestId: String,
        method: String,
        decision: String,
        riskLevel: String,
        riskScore: Int,
        subjectRef: String? = null,
        sessionId: String? = null,
        attemptNo: Int = 1,
        reVerifyReason: String? = null,
        reasons: List<String> = emptyList(),
        qualityScore: Double? = null,
        passivePadScore: Double? = null,
        mlPadScore: Double? = null,
        activePassed: Boolean? = null,
        captureLatencyMs: Int? = null,
        challengeLatencyMs: Int? = null,
        totalLatencyMs: Int? = null,
        resultTokenJti: String? = null,
    ): LivenessEventIngest {
        val m = smileMetadata.withDefault { null }
        val vpn     = (m["vpn"] as? Boolean) ?: (m["proxy"] as? Boolean)
        val carrier = m["carrier_info"] as? Map<*, *>
        val asnOrg  = carrier?.get("carrier_name") as? String
        val country = (m["geolocation"] as? Map<*, *>)?.get("country_code") as? String

        return LivenessEventIngest(
            consentId       = consentId,
            requestId       = requestId,
            method          = method,
            decision        = decision,
            riskLevel       = riskLevel,
            riskScore       = riskScore,
            sessionId       = sessionId,
            subjectRef      = subjectRef,
            reVerifyReason  = reVerifyReason,
            reasons         = reasons,
            attemptNo       = attemptNo,
            qualityScore    = qualityScore,
            passivePadScore = passivePadScore,
            mlPadScore      = mlPadScore,
            activePassed    = activePassed,
            ipAddr          = m["client_ip"] as? String,
            ipCountry       = country,
            ipAsnOrg        = asnOrg,
            ipIsVpn         = vpn,
            ipIsHosting     = m["proxy"] as? Boolean,
            osName          = (m["device_os"] as? String) ?: "Android",
            osVersion       = m["device_os_version"] as? String,
            deviceModel     = (m["device_model"] as? String) ?: (m["build_device"] as? String),
            appVersion      = (m["host_application"] as? Map<*, *>)?.get("version") as? String
                                ?: m["host_application"] as? String,
            sdkVersion      = (m["sdk_version"] as? String) ?: (m["smileidentity_android"] as? String),
            timezone        = m["timezone"] as? String,
            locale          = m["locale"] as? String,
            attestPresent   = (m["supports_hardware_attestation"] as? Boolean) ?: false,
            attestVerified  = (m["attestation_certificate_chain"] != null),
            geoCountry      = country,
            geoAdmin1       = (m["geolocation"] as? Map<*, *>)?.get("admin1") as? String,
            captureLatencyMs   = (m["selfie_capture_duration_ms"] as? Number)?.toInt() ?: captureLatencyMs,
            challengeLatencyMs = challengeLatencyMs,
            totalLatencyMs     = totalLatencyMs,
            resultTokenJti     = resultTokenJti,
            extra              = JsonObject(mapOf("smile_metadata" to smileMetadata.toJsonElement())),
        )
    }
}

/** Recursively convert an arbitrary metadata value into a kotlinx JsonElement. */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null           -> JsonPrimitive(null as String?)
    is Boolean     -> JsonPrimitive(this)
    is Number      -> JsonPrimitive(this)
    is String      -> JsonPrimitive(this)
    is Map<*, *>   -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else           -> JsonPrimitive(toString())
}
