package ng.facededup.liveness

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for the liveness flow. STARTER CODE — swap the raw
 * HttpURLConnection for OkHttp/Retrofit and move calls off the main thread in
 * production. Mirrors the web SDK's consent -> request -> challenge -> verify.
 */
class LivenessClient(private val baseUrl: String) {

    private fun post(path: String, body: JSONObject): JSONObject {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("$path $code: $text")
        return JSONObject(text)
    }

    fun grantConsent(subjectId: String, signalScopes: List<String>): String =
        post("/v1/consent", JSONObject().apply {
            put("subject_id", subjectId)
            put("accepted", true)
            put("signal_scopes", JSONArray(signalScopes))
        }).getString("consent_id")

    fun openRequest(
        subjectId: String, consentId: String, deviceContext: JSONObject?,
        method: String = "face_liveness",
    ): JSONObject =
        post("/v1/request", JSONObject().apply {
            put("subject_id", subjectId)
            put("consent_id", consentId)
            put("reason", "issuance")
            put("method", method)
            deviceContext?.let { put("device_context", it) }
        })

    fun challenge(requestId: String): JSONObject =
        post("/v1/challenge", JSONObject().put("request_id", requestId))

    /** Verify a spoken (face_number / face_voice) challenge. frames: list of
     *  {image_b64, proves_action}. */
    fun verifySpoken(
        requestId: String, challenge: JSONObject, frames: JSONArray,
        transcript: String, audioPresent: Boolean, audioB64: String?,
    ): JSONObject =
        post("/v1/verify", JSONObject().apply {
            put("request_id", requestId)
            put("session_id", challenge.getString("session_id"))
            put("nonce", challenge.getString("nonce"))
            put("frames", frames)
            put("transcript", transcript)
            put("audio_present", audioPresent)
            put("audio_b64", audioB64)
        })

    /** frames: list of {image_b64, proves_action}. */
    fun verify(requestId: String, challenge: JSONObject, frames: JSONArray,
               attestationToken: String?): JSONObject =
        post("/v1/verify", JSONObject().apply {
            put("request_id", requestId)
            put("session_id", challenge.getString("session_id"))
            put("nonce", challenge.getString("nonce"))
            attestationToken?.let { put("attestation_token", it) }
            put("frames", frames)
        })
}
