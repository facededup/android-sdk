package ng.facededup.sdk.ingest

import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire-format data classes for the end-to-end-encrypted liveness-event ingest.
 *
 * These mirror the backend contract in `SDK_INTEGRATION_GUIDE.md` byte-for-byte
 * (`@SerialName` is authoritative — do NOT rename the JSON keys). `@Keep` +
 * the consumer ProGuard rules keep them and their kotlinx-serialization
 * serializers alive through R8 in host release builds.
 */

/** Backend-issued ID for the event we just ingested. */
typealias EventId = String

/** Response of `GET /api/v1/liveness/pubkey`. */
@Keep
@Serializable
data class PubKey(
    @SerialName("kid") val kid: String,
    @SerialName("alg") val alg: String,                   // "RSA-OAEP-256+AES-256-GCM"
    @SerialName("public_key_pem") val publicKeyPem: String,
)

/** The sealed envelope POSTed to `/api/v1/liveness/events/sealed` (NOT the plaintext). */
@Keep
@Serializable
data class SealedEnvelope(
    @SerialName("kid") val kid: String,
    @SerialName("alg") val alg: String,
    @SerialName("enc_key")    val encKey: String,         // base64( RSA-OAEP-SHA256( 32-byte AES key ) )
    @SerialName("nonce")      val nonce: String,          // base64( 12 random bytes )
    @SerialName("ciphertext") val ciphertext: String,     // base64( AES-256-GCM(plaintext)+tag )
)

/** Success body of `POST /api/v1/liveness/events/sealed`. */
@Keep
@Serializable
data class IngestResponse(
    @SerialName("event_id")       val eventId: String,
    @SerialName("deduplicated")   val deduplicated: Boolean = false,
    @SerialName("chain_hash_hex") val chainHashHex: String? = null,
)

/**
 * The plaintext body we encrypt — matches the backend Pydantic `LivenessEventIngest`.
 * Required: consentId, requestId, method, decision, riskLevel, riskScore. Everything
 * else is optional context. `extra` is free-form JSON (e.g. `extra.smile_metadata`).
 *
 * Privacy: send `geoCountry` (ISO-2) + `geoAdmin1` only — NEVER precise GPS. Don't put
 * raw biometric data / photos in `extra`.
 */
@Keep
@Serializable
data class LivenessEventIngest(
    @SerialName("consent_id")  val consentId: String,
    @SerialName("request_id")  val requestId: String,
    @SerialName("method")      val method: String,
    @SerialName("decision")    val decision: String,       // live | not_live | referred
    @SerialName("risk_level")  val riskLevel: String,      // low | medium | high | critical
    @SerialName("risk_score")  val riskScore: Int,         // 0..100 (lower = safer)

    @SerialName("session_id")        val sessionId: String? = null,
    @SerialName("subject_ref")       val subjectRef: String? = null,
    @SerialName("re_verify_reason")  val reVerifyReason: String? = null,
    @SerialName("reasons")           val reasons: List<String> = emptyList(),
    @SerialName("attempt_no")        val attemptNo: Int = 1,

    @SerialName("quality_score")     val qualityScore: Double? = null,
    @SerialName("passive_pad_score") val passivePadScore: Double? = null,
    @SerialName("ml_pad_score")      val mlPadScore: Double? = null,
    @SerialName("active_passed")     val activePassed: Boolean? = null,

    @SerialName("ip_addr")        val ipAddr: String? = null,
    @SerialName("ip_country")     val ipCountry: String? = null,
    @SerialName("ip_asn")         val ipAsn: Int? = null,
    @SerialName("ip_asn_org")     val ipAsnOrg: String? = null,
    @SerialName("ip_is_vpn")      val ipIsVpn: Boolean? = null,
    @SerialName("ip_is_tor")      val ipIsTor: Boolean? = null,
    @SerialName("ip_is_hosting")  val ipIsHosting: Boolean? = null,

    @SerialName("platform")      val platform: String? = "android",
    @SerialName("os_name")       val osName: String? = null,
    @SerialName("os_version")    val osVersion: String? = null,
    @SerialName("device_model")  val deviceModel: String? = null,
    @SerialName("is_emulator")   val isEmulator: Boolean? = null,
    @SerialName("is_rooted")     val isRooted: Boolean? = null,
    @SerialName("has_debugger")  val hasDebugger: Boolean? = null,
    @SerialName("app_version")   val appVersion: String? = null,
    @SerialName("sdk_version")   val sdkVersion: String? = null,
    @SerialName("timezone")      val timezone: String? = null,
    @SerialName("locale")        val locale: String? = null,

    @SerialName("attest_present")  val attestPresent: Boolean = false,
    @SerialName("attest_verified") val attestVerified: Boolean? = null,
    @SerialName("attest_reason")   val attestReason: String? = null,

    @SerialName("geo_country") val geoCountry: String? = null,
    @SerialName("geo_admin1")  val geoAdmin1: String? = null,

    @SerialName("capture_latency_ms")   val captureLatencyMs: Int? = null,
    @SerialName("challenge_latency_ms") val challengeLatencyMs: Int? = null,
    @SerialName("total_latency_ms")     val totalLatencyMs: Int? = null,

    @SerialName("result_token_jti") val resultTokenJti: String? = null,

    @SerialName("extra") val extra: JsonObject = JsonObject(emptyMap()),
)
