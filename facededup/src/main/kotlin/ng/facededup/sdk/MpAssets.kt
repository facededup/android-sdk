package ng.facededup.sdk

import android.content.Context
import android.webkit.WebResourceResponse

/**
 * Serve the MediaPipe face engine (lib + WASM) and landmark model from the SDK's
 * bundled assets instead of the network. The hosted flow requests these from
 * jsdelivr/S3; we short-circuit those so the engine loads instantly, offline, and
 * uses zero data — only the verification API calls need internet.
 *
 * SIMD WASM is bundled; non-SIMD devices fall through to the network (return null).
 */
internal fun localMediaPipe(ctx: Context, rawUrl: String): WebResourceResponse? {
    val u = rawUrl.substringBefore('?')
    val (asset, mime) = when {
        u.endsWith("/vision_wasm_internal.wasm") -> "mp/wasm/vision_wasm_internal.wasm" to "application/wasm"
        u.endsWith("/vision_wasm_internal.js")   -> "mp/wasm/vision_wasm_internal.js" to "text/javascript"
        u.endsWith("/face_landmarker.task")      -> "mp/face_landmarker.task" to "application/octet-stream"
        u.endsWith("/vision_bundle.mjs")         -> "mp/vision_bundle.mjs" to "text/javascript"
        u.endsWith("tasks-vision") || Regex("tasks-vision@[0-9.]+$").containsMatchIn(u) ->
            "mp/vision_bundle.mjs" to "text/javascript"
        else -> return null
    }
    return try {
        WebResourceResponse(
            mime, null, 200, "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "max-age=31536000",
            ),
            ctx.assets.open(asset),
        )
    } catch (e: Exception) {
        null
    }
}
