package ng.facededup.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Submits captured liveness frames to the Facededup backend. ONLINE: POST
 * /v1/offline/submit and return the server's signed verdict immediately (the endpoint
 * runs the same decision as /v1/verify). OFFLINE / on failure: persist to [OfflineQueue]
 * and let [OfflineSubmitWorker] deliver it on reconnect (verdict then goes to the
 * tenant's webhook). The server stays authoritative — the device never decides liveness.
 */
internal object LivenessClient {

    data class Frame(val imageB64: String, val provesAction: String?)

    /** Build the /v1/offline/submit request body. */
    private fun body(subjectId: String, method: String, actions: List<String>,
                     txn: String, frames: List<Frame>, metadata: Map<String, Any?>?): JSONObject {
        val arr = JSONArray()
        for (f in frames) arr.put(JSONObject().apply {
            put("image_b64", f.imageB64); put("proves_action", f.provesAction)
        })
        return JSONObject().apply {
            put("subject_id", subjectId)
            put("method", method)
            put("client_actions", JSONArray(actions))
            put("client_txn_id", txn)
            put("captured_at", isoNow())
            put("frames", arr)
            metadata?.let { put("metadata", toJson(it) as JSONObject) }
        }
    }

    // Recursively convert Map/List/primitives to org.json types (nested-safe).
    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, value) -> put(k.toString(), toJson(value)) } }
        is List<*> -> JSONArray().apply { v.forEach { put(toJson(it)) } }
        else -> v
    }

    /**
     * Submit and return the result JSON the SDK reports to the host.
     * - online success → the server verdict (outcome/score/decision_id/…).
     * - offline / network failure → a `{"outcome":"queued",…}` placeholder; the real
     *   verdict is delivered to the webhook once the worker drains the queue.
     */
    fun submit(ctx: Context, base: String, license: String, subjectId: String,
               method: String, actions: List<String>, frames: List<Frame>,
               metadata: Map<String, Any?>? = null): String {
        val txn = "cap_" + System.currentTimeMillis() + "_" + (Math.random() * 1e6).toInt()
        val payload = body(subjectId, method, actions, txn, frames, metadata)
        val online = isOnline(ctx)
        if (online) {
            val resp = runCatching { post("${base.trimEnd('/')}/v1/offline/submit", license, payload.toString()) }
                .getOrNull()
            if (resp != null) return resp
        }
        // offline or POST failed → queue for deferred submit (verdict via webhook)
        payload.put("_base", base); payload.put("_license", license)
        OfflineQueue.enqueue(ctx, payload.toString())
        OfflineSubmitWorker.schedule(ctx)
        return JSONObject().apply {
            put("type", "liveness"); put("outcome", "queued")
            put("client_txn_id", txn); put("queued_offline", true)
        }.toString()
    }

    private fun post(url: String, license: String, body: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-License-Key", license)
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            if (code in 200..299) text else null
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun isOnline(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
    } catch (e: Exception) { true }

    private fun isoNow(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date())
    }
}
