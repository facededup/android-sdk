package ng.facededup.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Facededup drop-in verification SDK.
 *
 * Launches the hosted Facededup flow (Enroll / Validate with Government IDs /
 * Authenticate — liveness, document scan, MRZ, face match) inside a managed
 * WebView and hands you back a typed [FacededupResult].
 *
 * Usage (AndroidX ActivityResult):
 * ```
 * private val verify = registerForActivityResult(FacededupContract()) { r ->
 *     if (r != null && r.isLive == true) { /* verified */ }
 * }
 * // ...
 * verify.launch(FacededupConfig(
 *     baseUrl = "https://<your-host>",
 *     password = BuildConfig.SWIFTEND_PASSWORD,   // demo gate; omit in prod
 *     subjectId = "user-123",
 * ))
 * ```
 */
data class FacededupConfig(
    val baseUrl: String,
    val password: String? = null,
    /** Per-tenant license key (fdk_…) — production replacement for the demo password. */
    val licenseKey: String? = null,
    val subjectId: String? = null,
    /** GCP project number for Play Integrity device attestation (Annex A3e).
     *  When set, the flow mints a hardware attestation token bound to the
     *  challenge nonce. Null -> no attestation token is sent. */
    val cloudProjectNumber: Long? = null,
    /** Start screen. Defaults to **"liveness"** — the SDK drops straight into
     *  face capture (no menu), since integrators drive product choice from
     *  config, not an in-app menu. Set "select" to show the method menu, or
     *  "enroll" / "authenticate" / "address" for those flows. */
    val flow: String? = "liveness",
    /** Liveness method: "face_liveness" (motion) or "face_number" (read digits). */
    val method: String? = null,
    /** "lenient" | "standard" | "strict". */
    val strictness: String? = null,
    /** true = rear camera (agent points it at the customer). */
    val agentMode: Boolean? = null,
    /** Show the in-flow Settings panel (default hidden in production embeds). */
    val showSettings: Boolean? = null,
    /** Show the in-flow back button. Defaults to **false** — the SDK has no menu
     *  to return to. Set true if you embed it inside your own navigation. */
    val showBack: Boolean? = false,
    /** Branding / theme: product name and brand/accent colour (hex, e.g. "#0a3d62"). */
    val productName: String? = null,
    val primaryColor: String? = null,
    /** Theme: UI / font scale (e.g. 1.15 = 15% larger). 1.0 = default. */
    val fontScale: Double? = null,
    /** Theme: body text colour (hex). */
    val textColor: String? = null,
    /** Theme: background colour (hex). */
    val backgroundColor: String? = null,
) {
    /** Launch options → URL query the web flow reads (`_urlConfig` in the demo). */
    internal fun toQuery(): String {
        val p = ArrayList<Pair<String, String>>()
        fun add(k: String, v: String?) { if (!v.isNullOrEmpty()) p.add(k to v) }
        add("flow", flow); add("method", method); add("strictness", strictness)
        agentMode?.let { p.add("agent" to if (it) "1" else "0") }
        showSettings?.let { p.add("settings" to if (it) "1" else "0") }
        showBack?.let { p.add("back" to if (it) "1" else "0") }
        add("product", productName); add("color", primaryColor)
        fontScale?.let { add("fontScale", it.toString()) }
        add("textColor", textColor); add("bg", backgroundColor)
        add("license", licenseKey)
        return p.joinToString("&") { (k, v) -> "$k=" + android.net.Uri.encode(v) }
    }
}

/** One image in the downstream face-compare / liveness contract. */
data class FacededupImage(
    val imageType: String,     // raw-base64 JPEG frames are "image_type_2"
    val image: String,         // raw Base64 (no data: URI prefix)
)

/** Result reported by the flow (see the web bridge `postToHost`). */
data class FacededupResult(
    val type: String,          // liveness | identity | document | enroll
    val outcome: String?,      // live | not_live | referred | match | no_match | found | enrolled …
    val isLive: Boolean?,
    val score: Double?,
    val decision: String?,
    val enrollmentId: String?,
    /** Best selfie captured this run (null if none). */
    val selfieImage: FacededupImage?,
    /** 4-8 liveness frames captured this run (empty if none). */
    val livelinessImages: List<FacededupImage>,
    val raw: String,           // the full JSON payload
) {
    /** True for a clearly successful verification/enrolment. */
    val passed: Boolean
        get() = isLive == true ||
            outcome in setOf("live", "match", "found", "enrolled") ||
            decision == "match" || type == "enroll"

    companion object {
        fun fromJson(json: String): FacededupResult {
            val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k, null as String?)
            fun dbl(k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k) else null
            fun bool(k: String) = if (o.has(k) && !o.isNull(k)) o.optBoolean(k) else null
            fun img(obj: JSONObject?): FacededupImage? {
                if (obj == null) return null
                val b64 = obj.optString("image", "")
                if (b64.isEmpty()) return null
                return FacededupImage(obj.optString("image_type", "image_type_2"), b64)
            }
            val selfie = img(o.optJSONObject("selfie_image"))
            val live = ArrayList<FacededupImage>()
            (o.optJSONArray("liveliness_images") ?: JSONArray()).let { arr ->
                for (i in 0 until arr.length()) img(arr.optJSONObject(i))?.let { live.add(it) }
            }
            return FacededupResult(
                type = o.optString("type", "liveness"),
                outcome = str("outcome"),
                isLive = bool("is_live"),
                score = dbl("score"),
                decision = str("decision"),
                enrollmentId = str("enrollment_id"),
                selfieImage = selfie,
                livelinessImages = live,
                raw = json,
            )
        }
    }
}

/**
 * In-memory handoff for the result JSON. The payload now carries the selfie +
 * 4-8 liveness frames (base64), which can exceed Android's ~1 MB Binder limit
 * for Intent extras (TransactionTooLargeException → silently empty result).
 * FacededupActivity and FacededupContract run in the SAME process, so we pass
 * the full JSON through this holder and keep the Intent extra tiny.
 */
internal object FacededupResultHolder {
    @Volatile var json: String? = null
}

/** AndroidX ActivityResult contract: `launch(config)` -> `FacededupResult?` (null = cancelled). */
class FacededupContract : ActivityResultContract<FacededupConfig, FacededupResult?>() {
    override fun createIntent(context: Context, input: FacededupConfig): Intent =
        Intent(context, FacededupActivity::class.java).apply {
            putExtra(FacededupActivity.EXTRA_BASE_URL, input.baseUrl)
            putExtra(FacededupActivity.EXTRA_PASSWORD, input.password)
            putExtra(FacededupActivity.EXTRA_SUBJECT, input.subjectId)
            input.cloudProjectNumber?.let { putExtra(FacededupActivity.EXTRA_CLOUD_PROJECT, it) }
            putExtra(FacededupActivity.EXTRA_PARAMS, input.toQuery())
        }

    override fun parseResult(resultCode: Int, intent: Intent?): FacededupResult? {
        if (resultCode != Activity.RESULT_OK) { FacededupResultHolder.json = null; return null }
        // Prefer the in-memory holder (full payload incl. images); fall back to the
        // Intent extra for safety. Clear the holder so it never leaks to a later run.
        val json = FacededupResultHolder.json
            ?: intent?.getStringExtra(FacededupActivity.EXTRA_RESULT)
            ?: return null
        FacededupResultHolder.json = null
        return FacededupResult.fromJson(json)
    }
}
