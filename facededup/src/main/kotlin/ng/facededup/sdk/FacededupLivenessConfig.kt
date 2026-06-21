package ng.facededup.sdk

import android.content.Context
import org.json.JSONObject

/**
 * Developer-tunable liveness settings. Everything the flow uses is here, so integrators
 * can adjust behaviour without touching the SDK:
 *
 *  • Drop a JSON file at `assets/facededup_liveness.json` in your app — any subset of
 *    these keys overrides the defaults (see [load]).
 *  • Or pass a [FacededupLivenessConfig] programmatically via `FacededupConfig.liveness`.
 *
 * Example assets/facededup_liveness.json:
 * ```json
 * { "actions": ["turn_left","turn_right","smile","blink"], "sequenceLength": 3,
 *   "turnYawDeg": 18, "tiltPitchDeg": 12, "smileThreshold": 0.5,
 *   "ringWidthDp": 9, "ringColor": "#1E9C69", "showDiagnostics": false }
 * ```
 */
data class FacededupLivenessConfig(
    /** Action pool / explicit sequence. Empty = random pick of [sequenceLength] from the full pool. */
    val actions: List<String> = emptyList(),
    val sequenceLength: Int = 3,
    /** Detection thresholds. */
    val turnYawDeg: Float = 18f,        // head turn left/right (deg)
    val tiltPitchDeg: Float = 12f,      // head tilt up/down, DELTA from neutral (deg)
    val smileThreshold: Float = 0.5f,   // ML Kit smiling probability
    val blinkThreshold: Float = 0.4f,   // both-eyes-open probability below this = blink
    val neutralYawDeg: Float = 12f,     // "back to frontal" between turns
    /** Smart scene quality (coaching + auto-brightness). */
    val darkLuma: Float = 60f,            // avg Y (0..255) below this = "too dark" → boost screen
    val minFaceCoverage: Float = 0.05f,   // face/frame area below this = "move closer"
    val maxFaceCoverage: Float = 0.55f,   // face/frame area above this = "move back"
    /** UI. */
    val instructionSizeSp: Float = 18f,   // instruction text size (sp) — configurable
    val fontAsset: String? = null,        // custom font path under app assets (e.g. "fonts/onset.ttf")
    val ringWidthDp: Float = 5f,          // progress-arc thickness (thin, calm)
    val ringColor: String? = null,      // hex; default = theme primary / green
    val successColor: String? = null,   // hex; default green
    val scrimColor: String? = null,     // hex (with alpha); default light grey
    val showDiagnostics: Boolean = false, // overlay live yaw/pitch/smile + directive (OFF by default)
    /** Timing. */
    val actionTimeoutMs: Long = 12000,  // per-action: after this, nudge "try the other way"
    val totalTimeoutMs: Long = 60000,   // whole flow safety cap
    val framesPerAction: Int = 2,
    /** Branding / white-label — fully customisable, no SDK change needed. */
    val pillColor: String? = null,        // instruction pill background (hex w/ alpha)
    val pillTextColor: String? = null,    // instruction text colour
    val cancelColor: String? = null,      // "Cancel" colour
    val showCancel: Boolean = true,
    val logoAsset: String? = null,        // optional brand logo (path under app assets) shown at top
    /** Override ANY on-screen string by key (also for localisation). Keys:
     *  center_face, turn_left, turn_right, look_up, look_down, smile, blink, hold_still,
     *  checking, only_one_face, great, cancel. */
    val strings: Map<String, String> = emptyMap(),
) {
    private val defaults = mapOf(
        "center_face" to "Center your face in the oval",
        "turn_left" to "Turn your head left", "turn_right" to "Turn your head right",
        "look_up" to "Tilt your head up", "look_down" to "Tilt your head down",
        "smile" to "Smile", "blink" to "Blink your eyes", "hold_still" to "Hold still",
        "checking" to "Checking…", "only_one_face" to "Only one face, please",
        "great" to "Great", "cancel" to "Cancel",
        // smart coaching + wait/offline
        "too_dark" to "A bit dark, find better light",
        "too_bright" to "Too bright, reduce glare",
        "move_closer" to "Move a little closer",
        "move_back" to "Move back a little",
        "hold_steady" to "Hold steady",
        "verifying" to "Hang tight, we are verifying your check",
        "offline_saved" to "No internet right now, your check is saved\nYou will get the result once you are back online",
    )
    /** Resolve a UI string: developer override → built-in default → the key. */
    fun str(key: String): String = strings[key] ?: defaults[key] ?: key
    companion object {
        /** Programmatic config (if set) wins; else read assets/facededup_liveness.json; else defaults. */
        fun resolve(ctx: Context, programmatic: FacededupLivenessConfig?): FacededupLivenessConfig =
            programmatic ?: load(ctx)

        fun load(ctx: Context): FacededupLivenessConfig {
            val def = FacededupLivenessConfig()
            val json = runCatching {
                ctx.assets.open("facededup_liveness.json").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return def
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return def
            fun str(k: String, d: String?) = if (o.has(k) && !o.isNull(k)) o.optString(k) else d
            fun f(k: String, d: Float) = if (o.has(k)) o.optDouble(k).toFloat() else d
            fun i(k: String, d: Int) = if (o.has(k)) o.optInt(k) else d
            fun l(k: String, d: Long) = if (o.has(k)) o.optLong(k) else d
            fun b(k: String, d: Boolean) = if (o.has(k)) o.optBoolean(k) else d
            val acts = if (o.has("actions")) {
                val a = o.optJSONArray("actions"); (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
            } else def.actions
            val strs = HashMap<String, String>()
            o.optJSONObject("strings")?.let { s -> s.keys().forEach { k -> strs[k] = s.optString(k) } }
            return FacededupLivenessConfig(
                actions = acts,
                sequenceLength = i("sequenceLength", def.sequenceLength),
                turnYawDeg = f("turnYawDeg", def.turnYawDeg),
                tiltPitchDeg = f("tiltPitchDeg", def.tiltPitchDeg),
                smileThreshold = f("smileThreshold", def.smileThreshold),
                blinkThreshold = f("blinkThreshold", def.blinkThreshold),
                neutralYawDeg = f("neutralYawDeg", def.neutralYawDeg),
                darkLuma = f("darkLuma", def.darkLuma),
                minFaceCoverage = f("minFaceCoverage", def.minFaceCoverage),
                maxFaceCoverage = f("maxFaceCoverage", def.maxFaceCoverage),
                instructionSizeSp = f("instructionSizeSp", def.instructionSizeSp),
                fontAsset = str("fontAsset", def.fontAsset),
                ringWidthDp = f("ringWidthDp", def.ringWidthDp),
                ringColor = str("ringColor", def.ringColor),
                successColor = str("successColor", def.successColor),
                scrimColor = str("scrimColor", def.scrimColor),
                showDiagnostics = b("showDiagnostics", def.showDiagnostics),
                actionTimeoutMs = l("actionTimeoutMs", def.actionTimeoutMs),
                totalTimeoutMs = l("totalTimeoutMs", def.totalTimeoutMs),
                framesPerAction = i("framesPerAction", def.framesPerAction),
                pillColor = str("pillColor", def.pillColor),
                pillTextColor = str("pillTextColor", def.pillTextColor),
                cancelColor = str("cancelColor", def.cancelColor),
                showCancel = b("showCancel", def.showCancel),
                logoAsset = str("logoAsset", def.logoAsset),
                strings = strs,
            )
        }
    }
}
