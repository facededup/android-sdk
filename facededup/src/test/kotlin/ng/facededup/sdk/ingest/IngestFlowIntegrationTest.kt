package ng.facededup.sdk.ingest

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64 as JavaB64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * End-to-end demonstration of acceptance 1–4 against an in-process STUB backend
 * (the real endpoints don't exist yet) — driving the SDK's REAL networking + crypto
 * path (HttpIngestApi → IngestCrypto → IngestClient):
 *
 *   1. pubkey fetched, envelope sealed, POST 200, event_id returned
 *   3. retry with the same request_id → deduplicated:true, identical event_id
 *   4. tampered ciphertext byte → 400 "authenticated decryption failed"
 *   +  401 on a bad ingest key; unknown-kid → re-fetch + retry once
 *
 * The stub is a tiny raw-socket HTTP/1.1 server (no com.sun dependency, which
 * isn't on the Android unit-test classpath). Robolectric gives us a Context for
 * PubKeyCache + a real android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IngestFlowIntegrationTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val ingestKey = "test-ingest-secret"

    private lateinit var kp: KeyPair
    @Volatile private var kid = "k1"
    private lateinit var stub: HttpStub
    private lateinit var baseUrl: String
    private val seen = mutableMapOf<String, String>()   // request_id → event_id
    private var eventSeq = 0

    @Before fun setUp() {
        kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        stub = HttpStub { method, path, headers, reqBody ->
            when {
                path == "/api/v1/liveness/pubkey" -> 200 to
                    """{"kid":"$kid","alg":"${IngestCrypto.ALG}","public_key_pem":"${pem().replace("\n", "\\n")}"}"""

                path == "/api/v1/liveness/events/sealed" -> {
                    if (headers["x-liveness-ingest-key"] != ingestKey) 401 to """{"error":"bad key"}"""
                    else {
                        val env = IngestCrypto.json.decodeFromString(SealedEnvelope.serializer(), reqBody)
                        if (env.kid != kid) 400 to """{"error":"unknown kid: ${env.kid}"}"""
                        else {
                            val plain = runCatching { decrypt(env) }.getOrNull()
                            if (plain == null) 400 to """{"error":"authenticated decryption failed"}"""
                            else {
                                val reqId = Regex("\"request_id\"\\s*:\\s*\"([^\"]+)\"").find(plain)!!.groupValues[1]
                                val dedup = seen.containsKey(reqId)
                                val eventId = seen.getOrPut(reqId) { "evt_${++eventSeq}" }
                                200 to """{"event_id":"$eventId","deduplicated":$dedup,"chain_hash_hex":"deadbeef"}"""
                            }
                        }
                    }
                }
                else -> 404 to """{"error":"not found"}"""
            }
        }
        baseUrl = "http://127.0.0.1:${stub.port}"
    }

    @After fun tearDown() { stub.close() }

    private fun client() = IngestClient(
        appContext = ctx,
        cfg = IngestConfig(baseUrl, ingestKey),
        api = HttpIngestApi(baseUrl, ingestKey),
        cache = PubKeyCache(ctx).also { it.clear() },
        now = { 1_000L },
    )

    private fun body(reqId: String) = LivenessEventIngest(
        consentId = "ct_1", requestId = reqId, method = "face_liveness",
        decision = "live", riskLevel = "low", riskScore = 5, qualityScore = 0.95, activePassed = true,
    )

    @Test fun fullFlow_post200_returnsEventId() = runBlocking {
        val r = client().submit(body("req_alpha"))
        assertTrue("submit should succeed: $r", r.isSuccess)
        val eventId = r.getOrNull()
        assertNotNull(eventId)
        println("[ingest-demo] POST 200 → event_id=$eventId")
    }

    @Test fun retrySameRequestId_isDeduplicated_sameEventId() = runBlocking {
        val first = client().submit(body("req_dedupe")).getOrThrow()
        val api = HttpIngestApi(baseUrl, ingestKey)
        val env = IngestCrypto.sealEnvelope(body("req_dedupe"), api.getPubKey())   // SAME request_id
        val res = api.postSealed(env)
        assertTrue(res is PostResult.Ok)
        val ok = res as PostResult.Ok
        assertEquals("event_id must be identical on dedupe", first, ok.resp.eventId)
        assertTrue("deduplicated must be true", ok.resp.deduplicated)
        println("[ingest-demo] dedup retry → event_id=${ok.resp.eventId} deduplicated=${ok.resp.deduplicated}")
    }

    @Test fun tamperedCiphertext_serverReturns400() {
        val api = HttpIngestApi(baseUrl, ingestKey)
        val env = IngestCrypto.sealEnvelope(body("req_tamper"), api.getPubKey())
        val raw = JavaB64.getDecoder().decode(env.ciphertext)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x01).toByte()
        val res = api.postSealed(env.copy(ciphertext = JavaB64.getEncoder().encodeToString(raw)))
        assertTrue("tamper must be rejected: $res", res is PostResult.Rejected)
        assertEquals(400, (res as PostResult.Rejected).code)
        assertTrue(res.message.contains("authenticated decryption failed"))
        println("[ingest-demo] tamper → HTTP 400 ${res.message}")
    }

    @Test fun badIngestKey_returns401() {
        val pub = HttpIngestApi(baseUrl, ingestKey).getPubKey()
        val res = HttpIngestApi(baseUrl, "wrong-key").postSealed(IngestCrypto.sealEnvelope(body("req_401"), pub))
        assertTrue(res is PostResult.Rejected)
        assertEquals(401, (res as PostResult.Rejected).code)
    }

    @Test fun unknownKid_refetchesAndRetriesOnce() = runBlocking {
        val c = client()
        assertTrue(c.submit(body("req_warm")).isSuccess)    // warms the cache with k1
        kid = "k2"                                          // backend rotates → cached k1 now unknown
        val r = c.submit(body("req_rotated"))
        assertTrue("should recover via pubkey refresh + single retry: $r", r.isSuccess)
        println("[ingest-demo] kid rotation k1→k2 recovered, event_id=${r.getOrNull()}")
    }

    // ── crypto + PEM helpers ────────────────────────────────────────────
    private fun pem(): String =
        "-----BEGIN PUBLIC KEY-----\n" +
        JavaB64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.public.encoded) +
        "\n-----END PUBLIC KEY-----\n"

    private fun decrypt(env: SealedEnvelope): String {
        val aesKey = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            init(Cipher.DECRYPT_MODE, kp.private, OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            doFinal(JavaB64.getDecoder().decode(env.encKey))
        }
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"),
                 GCMParameterSpec(128, JavaB64.getDecoder().decode(env.nonce)))
            doFinal(JavaB64.getDecoder().decode(env.ciphertext))
        }
        return String(plain, Charsets.UTF_8)
    }
}

/**
 * Minimal raw-socket HTTP/1.1 stub. [handle] receives (method, path, lower-cased
 * headers, body) and returns (status, json). One connection at a time is plenty
 * for the test, and `Connection: close` keeps each exchange self-contained.
 */
private class HttpStub(
    private val handle: (method: String, path: String, headers: Map<String, String>, body: String) -> Pair<Int, String>,
) : AutoCloseable {
    private val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var running = true
    private val loop = thread(isDaemon = true) {
        while (running) {
            val sock = try { server.accept() } catch (e: Exception) { break }
            runCatching { serve(sock) }
            runCatching { sock.close() }
        }
    }

    private fun serve(sock: Socket) {
        val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "GET" }
        val path = parts.getOrElse(1) { "/" }
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) CharArray(len).let { buf -> readFully(reader, buf); String(buf) } else ""

        val (code, json) = handle(method, path, headers, body)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        out.write(("HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.write(bytes); out.flush()
    }

    private fun readFully(reader: BufferedReader, buf: CharArray) {
        var off = 0
        while (off < buf.size) {
            val n = reader.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Status"
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        runCatching { loop.join(1000) }
    }
}
