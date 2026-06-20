package ng.facededup.sdk.ingest

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Hybrid envelope encryption — RSA-OAEP-256 wraps a one-shot AES-256-GCM key.
 * Only the backend (holding the matching RSA private key) can decrypt; no proxy,
 * load balancer, or operator sees the plaintext.
 *
 * Pure `javax.crypto` — no third-party crypto dependency. Every step here must
 * match `app/core/liveness_crypto.py` / the integration guide §5 exactly, or the
 * server's authenticated decryption fails.
 *
 * NEVER log the AES key, the plaintext, or the assembled envelope.
 */
internal object IngestCrypto {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    const val ALG = "RSA-OAEP-256+AES-256-GCM"

    /**
     * Seal [body] for [pub]. Steps:
     *   1. fresh 32-byte AES key (CSPRNG)
     *   2. fresh 12-byte GCM nonce (CSPRNG)
     *   3. AES-256-GCM(body-JSON-UTF8), 128-bit tag, no AAD
     *   4. RSA-OAEP-SHA256/MGF1-SHA256 wrap of the AES key
     *   5. base64 (NO_WRAP) all three → envelope
     */
    fun sealEnvelope(body: LivenessEventIngest, pub: PubKey): SealedEnvelope {
        require(pub.alg == ALG) { "unsupported alg: ${pub.alg}" }

        val pubKey = run {
            val pemBody = pub.publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val der = Base64.decode(pemBody, Base64.NO_WRAP)
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        }

        val rng = SecureRandom()
        val aesKey = ByteArray(32).also(rng::nextBytes)
        val nonce  = ByteArray(12).also(rng::nextBytes)

        val plaintext = json.encodeToString(body).toByteArray(Charsets.UTF_8)

        // AES-256-GCM over the body (tag appended to ciphertext).
        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        }
        val ct = gcm.doFinal(plaintext)

        // RSA-OAEP-SHA256 wrap of the AES key.
        val oaep = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.ENCRYPT_MODE, pubKey, OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }
        val encKey = oaep.doFinal(aesKey)

        return SealedEnvelope(
            kid        = pub.kid,
            alg        = pub.alg,
            encKey     = Base64.encodeToString(encKey, Base64.NO_WRAP),
            nonce      = Base64.encodeToString(nonce,  Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ct,     Base64.NO_WRAP),
        )
    }
}
