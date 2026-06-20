package ng.facededup.sdk.ingest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acceptance 5b: the Smile metadata → ingest-body mapping preserves every key of
 * the Smile blob under `extra.smile_metadata` (the guide states the FULL blob
 * lives there), and lifts the documented scalar fields.
 *
 * Robolectric so the native builder's `android.os.Build` reads resolve on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SmileMappingTest {

    private val smile: Map<String, Any?> = mapOf(
        "sdk_version" to "smileidentity_android@10.5.0",
        "device_model" to "SM-G991B",
        "device_os" to "Android",
        "device_os_version" to "13",
        "timezone" to "Africa/Lagos",
        "locale" to "en-NG",
        "vpn" to false,
        "proxy" to false,
        "client_ip" to "203.0.113.10",
        "supports_hardware_attestation" to true,
        "attestation_certificate_chain" to listOf("cert-a", "cert-b"),
        "selfie_capture_duration_ms" to 1240,
        "carrier_info" to mapOf("carrier_name" to "MTN Nigeria"),
        "geolocation" to mapOf("country_code" to "NG", "admin1" to "Lagos"),
        "host_application" to mapOf("version" to "2.0.0"),
        "active_liveness_type" to "smile",
        "network_robustness" to mapOf("retries" to 0, "ok" to true),
    )

    @Test
    fun extraSmileMetadata_preservesAllKeys() {
        val body = SmileMetadataMapper.build(
            smileMetadata = smile,
            consentId = "ct_1", requestId = "req_1", method = "face_liveness",
            decision = "live", riskLevel = "low", riskScore = 8,
        )
        val sm = body.extra["smile_metadata"]
        assertNotNull("extra.smile_metadata must be present", sm)
        val smObj = (sm as JsonObject)
        // every top-level key of the Smile blob survives
        assertEquals(smile.keys, smObj.keys)
        // nested objects survive too
        assertEquals("NG", smObj["geolocation"]!!.jsonObject["country_code"]!!.jsonPrimitive.contentOrNull)
        assertEquals("MTN Nigeria", smObj["carrier_info"]!!.jsonObject["carrier_name"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun documentedScalarFields_areLifted() {
        val body = SmileMetadataMapper.build(
            smileMetadata = smile,
            consentId = "ct_1", requestId = "req_1", method = "face_liveness",
            decision = "live", riskLevel = "low", riskScore = 8,
        )
        assertEquals("face_liveness", body.method)
        assertEquals("SM-G991B", body.deviceModel)
        assertEquals("13", body.osVersion)
        assertEquals("Africa/Lagos", body.timezone)
        assertEquals("en-NG", body.locale)
        assertEquals(false, body.ipIsVpn)
        assertEquals("203.0.113.10", body.ipAddr)
        assertEquals("NG", body.geoCountry)
        assertEquals("Lagos", body.geoAdmin1)
        assertEquals("MTN Nigeria", body.ipAsnOrg)
        assertEquals(true, body.attestPresent)
        assertEquals(true, body.attestVerified)
        assertEquals(1240, body.captureLatencyMs)
        assertEquals("2.0.0", body.appVersion)
    }

    @Test
    fun nativeBuilder_mapsVerdictToDecisionAndRisk() {
        val live = IngestEventBuilder.fromNative(
            consentId = "ct_1", requestId = "req_1", method = "face_liveness",
            verdictJson = """{"outcome":"live","is_live":true,"score":0.97}""",
            metadata = mapOf("VPNDetected" to false, "DeviceModel" to "Pixel 7"),
        )
        assertEquals("live", live.decision)
        assertEquals("low", live.riskLevel)
        assertEquals(3, live.riskScore)          // (1-0.97)*100
        assertEquals(false, live.ipIsVpn)
        assertTrue(live.extra.containsKey("facededup_metadata"))

        val spoof = IngestEventBuilder.fromNative(
            consentId = "ct_1", requestId = "req_2", method = "face_liveness",
            verdictJson = """{"outcome":"not_live","is_live":false,"score":0.10}""",
            metadata = null,
        )
        assertEquals("not_live", spoof.decision)
        assertEquals("critical", spoof.riskLevel)
        assertEquals(90, spoof.riskScore)
    }
}
