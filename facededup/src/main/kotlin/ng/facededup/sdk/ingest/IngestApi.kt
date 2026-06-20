package ng.facededup.sdk.ingest

import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of POSTing a sealed envelope, classified for the orchestrator's retry logic. */
internal sealed interface PostResult {
    /** 200 — accepted. */
    data class Ok(val resp: IngestResponse) : PostResult
    /** 400 + "unknown kid" — backend rotated keys; re-fetch pubkey, re-seal, retry once. */
    object UnknownKid : PostResult
    /** Any other non-2xx the server returned (401/422/400-other/503/…). [retriable] = transient (5xx). */
    data class Rejected(val code: Int, val message: String, val retriable: Boolean) : PostResult
    /** Transport failure (timeout, no route, DNS). Persist + flush on reconnect. */
    data class NetworkError(val cause: Throwable) : PostResult
}

/** The two ingest endpoints. */
internal interface IngestApi {
    /** GET /api/v1/liveness/pubkey — no auth. Throws on transport/parse failure. */
    fun getPubKey(): PubKey
    /** POST /api/v1/liveness/events/sealed — with the X-Liveness-Ingest-Key header. */
    fun postSealed(env: SealedEnvelope): PostResult
}

/**
 * `HttpURLConnection`-backed [IngestApi] — keeps the SDK dependency-light (no
 * Retrofit/OkHttp). [baseUrl] is the Facededup ingest host (BuildConfig.FACEDEDUP_BASE_URL);
 * [ingestKey] is the secret `X-Liveness-Ingest-Key` (BuildConfig / remote config).
 *
 * Never logs the key, the envelope, or any plaintext.
 */
internal class HttpIngestApi(
    private val baseUrl: String,
    private val ingestKey: String,
) : IngestApi {

    override fun getPubKey(): PubKey {
        val conn = open("$baseUrl/api/v1/liveness/pubkey", "GET")
        try {
            val code = conn.responseCode
            val body = conn.readBody()
            if (code !in 200..299) error("pubkey HTTP $code")
            return IngestCrypto.json.decodeFromString(PubKey.serializer(), body)
        } finally { conn.disconnect() }
    }

    override fun postSealed(env: SealedEnvelope): PostResult {
        val conn = try {
            open("$baseUrl/api/v1/liveness/events/sealed", "POST").apply {
                doOutput = true
                setRequestProperty("X-Liveness-Ingest-Key", ingestKey)
            }
        } catch (e: Exception) {
            return PostResult.NetworkError(e)
        }
        return try {
            conn.outputStream.use { it.write(IngestCrypto.json.encodeToString(env).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = conn.readBody()
            when {
                code in 200..299 ->
                    PostResult.Ok(IngestCrypto.json.decodeFromString(IngestResponse.serializer(), body))
                code == 400 && body.contains("kid", ignoreCase = true) &&
                    body.contains("unknown", ignoreCase = true) -> PostResult.UnknownKid
                else -> PostResult.Rejected(code, body.take(300), retriable = code >= 500)
            }
        } catch (e: Exception) {
            PostResult.NetworkError(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (method == "POST") setRequestProperty("Content-Type", "application/json")
        }

    private fun HttpURLConnection.readBody(): String = runCatching {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }.getOrDefault("")
}
