package ng.facededup.sdk.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64 as JavaB64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.security.spec.MGF1ParameterSpec

/**
 * Acceptance 5a + 4 (local): sealEnvelope() round-trips against a known test pubkey,
 * decryptable by the matching private key — and a tampered ciphertext fails AEAD.
 *
 * Robolectric so `android.util.Base64` runs for real on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SealEnvelopeTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }.generateKeyPair()

    private fun testPubKey(): PubKey {
        val der = keyPair.public.encoded   // X.509 SubjectPublicKeyInfo
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            JavaB64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END PUBLIC KEY-----\n"
        return PubKey(kid = "test-k1", alg = IngestCrypto.ALG, publicKeyPem = pem)
    }

    private fun sampleBody() = LivenessEventIngest(
        consentId = "ct_test_01", requestId = "req_test_01", method = "face_liveness",
        decision = "live", riskLevel = "low", riskScore = 8,
        sessionId = "sess_xyz", qualityScore = 0.92, activePassed = true,
        deviceModel = "Pixel 7", osVersion = "14",
    )

    /** RSA-OAEP unwrap + AES-GCM decrypt — the backend's half of the contract. */
    private fun decrypt(env: SealedEnvelope): String {
        val encKey = JavaB64.getDecoder().decode(env.encKey)
        val nonce  = JavaB64.getDecoder().decode(env.nonce)
        val ct     = JavaB64.getDecoder().decode(env.ciphertext)

        val aesKey = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            init(Cipher.DECRYPT_MODE, keyPair.private, OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            doFinal(encKey)
        }
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
            doFinal(ct)
        }
        return String(plain, Charsets.UTF_8)
    }

    @Test
    fun seal_roundTrips_andDecryptsToOriginalBody() {
        val pub = testPubKey()
        val body = sampleBody()
        val env = IngestCrypto.sealEnvelope(body, pub)

        assertEquals("test-k1", env.kid)
        assertEquals(IngestCrypto.ALG, env.alg)
        // 32-byte AES key wrapped by RSA-2048-OAEP → 256-byte ciphertext.
        assertEquals(256, JavaB64.getDecoder().decode(env.encKey).size)
        assertEquals(12, JavaB64.getDecoder().decode(env.nonce).size)

        val decryptedJson = decrypt(env)
        val expected = Json { encodeDefaults = false }
            .parseToJsonElement(IngestCrypto.json.encodeToString(LivenessEventIngest.serializer(), body))
        val actual = Json.parseToJsonElement(decryptedJson)
        assertEquals(expected.jsonObject["request_id"], actual.jsonObject["request_id"])
        assertEquals(expected.jsonObject["consent_id"], actual.jsonObject["consent_id"])
        assertEquals(expected.jsonObject["decision"], actual.jsonObject["decision"])
        assertEquals(expected.jsonObject["risk_score"], actual.jsonObject["risk_score"])
        // full structural equality of the inner JSON
        assertEquals(expected, actual)
    }

    @Test
    fun freshKeyAndNonce_perSeal() {
        val pub = testPubKey()
        val a = IngestCrypto.sealEnvelope(sampleBody(), pub)
        val b = IngestCrypto.sealEnvelope(sampleBody(), pub)
        assertNotEquals("nonce must be random per seal", a.nonce, b.nonce)
        assertNotEquals("AES key (wrapped) must be random per seal", a.encKey, b.encKey)
    }

    @Test
    fun tamperedCiphertext_failsAuthenticatedDecryption() {
        val env = IngestCrypto.sealEnvelope(sampleBody(), testPubKey())
        // Flip one byte of the raw ciphertext → GCM tag check must fail.
        val raw = JavaB64.getDecoder().decode(env.ciphertext)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x01).toByte()
        val tampered = env.copy(ciphertext = JavaB64.getEncoder().encodeToString(raw))
        val threw = try { decrypt(tampered); false }
                    catch (e: javax.crypto.AEADBadTagException) { true }
                    catch (e: Exception) { true }
        assertTrue("authenticated decryption must fail on tamper", threw)
    }
}
