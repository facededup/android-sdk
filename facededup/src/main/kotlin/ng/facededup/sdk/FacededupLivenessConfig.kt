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
    /** UI. */
    val ringWidthDp: Float = 9f,
    val ringColor: String? = null,      // hex; default = theme primary / green
    val successColor: String? = null,   // hex; default green
    val scrimColor: String? = null,     // hex (with alpha); default light grey
    val showDiagnostics: Boolean = false, // overlay live yaw/pitch/smile + directive
    /** Timing. */
    val actionTimeoutMs: Long = 12000,  // per-action: after this, nudge "try the other way"
    val totalTimeoutMs: Long = 60000,   // whole flow safety cap
    val framesPerAction: Int = 2,
) {
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
            return FacededupLivenessConfig(
                actions = acts,
                sequenceLength = i("sequenceLength", def.sequenceLength),
                turnYawDeg = f("turnYawDeg", def.turnYawDeg),
                tiltPitchDeg = f("tiltPitchDeg", def.tiltPitchDeg),
                smileThreshold = f("smileThreshold", def.smileThreshold),
                blinkThreshold = f("blinkThreshold", def.blinkThreshold),
                neutralYawDeg = f("neutralYawDeg", def.neutralYawDeg),
                ringWidthDp = f("ringWidthDp", def.ringWidthDp),
                ringColor = str("ringColor", def.ringColor),
                successColor = str("successColor", def.successColor),
                scrimColor = str("scrimColor", def.scrimColor),
                showDiagnostics = b("showDiagnostics", def.showDiagnostics),
                actionTimeoutMs = l("actionTimeoutMs", def.actionTimeoutMs),
                totalTimeoutMs = l("totalTimeoutMs", def.totalTimeoutMs),
                framesPerAction = i("framesPerAction", def.framesPerAction),
            )
        }
    }
}
