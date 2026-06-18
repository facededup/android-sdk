package ng.facededup.sdk

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Native on-device detection for the Android hybrid. Runs the SAME MediaPipe
 * FaceLandmarker model as the web flow (`mp/face_landmarker.task`, bundled in assets),
 * so the signals returned match the page's `processLandmarks()` EXACTLY — coverage, cx,
 * cy, noseOff, blendshapes (blink / smile / jawOpen) and pose from the transformation
 * matrix.
 *
 * The web flow sends a MIRRORED (selfie) JPEG and the page computes its own geometry on
 * a mirrored frame too, so everything here is in the same mirror space — no sign
 * calibration needed (unlike iOS Vision's separate Euler pitch). Output is a JSON string
 * for `window.__facededupPose(...)`; `{"face":false}` when no face / on any failure.
 */
class FacededupMpDetector(context: Context) {

    private val landmarker: FaceLandmarker? = runCatching {
        val base = BaseOptions.builder()
            .setModelAssetPath("mp/face_landmarker.task")
            .build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build()
        FaceLandmarker.createFromOptions(context, opts)
    }.getOrNull()

    val isReady: Boolean get() = landmarker != null

    fun detect(base64Jpeg: String): String {
        val lm = landmarker ?: return NO_FACE
        return runCatching {
            val bytes = Base64.decode(base64Jpeg, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return NO_FACE
            val image = BitmapImageBuilder(bmp).build()
            val res = lm.detect(image)
            bmp.recycle()
            shape(res)
        }.getOrDefault(NO_FACE)
    }

    private fun shape(res: FaceLandmarkerResult): String {
        val faces = res.faceLandmarks()
        if (faces.isEmpty()) return NO_FACE
        val lmk = faces[0]

        // Bounding box from the landmark spread (normalised 0..1, top-left origin).
        var ys = 1.0; var ye = 0.0; var xs = 1.0; var xe = 0.0
        for (p in lmk) {
            val x = p.x().toDouble(); val y = p.y().toDouble()
            if (y < ys) ys = y; if (y > ye) ye = y
            if (x < xs) xs = x; if (x > xe) xe = x
        }

        // Blendshapes (blink / smile / jawOpen) — same category names as the web model.
        val bs = HashMap<String, Double>()
        val blend = res.faceBlendshapes()
        if (blend.isPresent && blend.get().isNotEmpty()) {
            for (c in blend.get()[0]) bs[c.categoryName()] = c.score().toDouble()
        }

        // Pose from the facial transformation matrix (column-major, like MediaPipe JS).
        var yaw = 0.0; var pitch = 0.0; var roll = 0.0
        val mxs = res.facialTransformationMatrixes()
        if (mxs.isPresent && mxs.get().isNotEmpty()) {
            val e = euler(mxs.get()[0])
            yaw = e[0]; pitch = e[1]; roll = e[2]
        }

        // noseOff (mirror-consistent turn signal): nose=lm[1], eyes=lm[33]/lm[263].
        val nose = lmk.getOrNull(1); val eL = lmk.getOrNull(33); val eR = lmk.getOrNull(263)
        var noseOff = 0.0
        if (nose != null && eL != null && eR != null) {
            val fw = (xe - xs).let { if (it == 0.0) 1.0 else it }
            noseOff = (nose.x().toDouble() - (eL.x().toDouble() + eR.x().toDouble()) / 2.0) / fw
        }

        return JSONObject().apply {
            put("face", true)
            put("coverage", ye - ys)
            put("cx", (xs + xe) / 2.0)
            put("cy", (ys + ye) / 2.0)
            put("yaw", yaw); put("pitch", pitch); put("roll", roll)
            put("noseOff", noseOff)
            put("eyeBlinkLeft", bs["eyeBlinkLeft"] ?: 0.0)
            put("eyeBlinkRight", bs["eyeBlinkRight"] ?: 0.0)
            put("mouthSmileLeft", bs["mouthSmileLeft"] ?: 0.0)
            put("mouthSmileRight", bs["mouthSmileRight"] ?: 0.0)
            put("jawOpen", bs["jawOpen"] ?: 0.0)
        }.toString()
    }

    // Replicates the web euler() EXACTLY: column-major matrix, logical R[row][col] =
    // m[col*4 + row]; yaw/pitch folded to [-90,90] like the page's fold().
    private fun euler(m: FloatArray): DoubleArray {
        fun r(row: Int, col: Int) = m[col * 4 + row].toDouble()
        val r00 = r(0, 0); val r10 = r(1, 0); val r20 = r(2, 0); val r21 = r(2, 1); val r22 = r(2, 2)
        val sy = hypot(r00, r10); val d = 180.0 / PI
        val yaw: Double; val pitch: Double
        if (sy > 1e-6) { pitch = atan2(r21, r22) * d; yaw = atan2(-r20, sy) * d }
        else { pitch = atan2(-r(1, 2), r(1, 1)) * d; yaw = atan2(-r20, sy) * d }
        val roll = atan2(r10, r00) * d
        return doubleArrayOf(fold(yaw), fold(pitch), roll)
    }

    private fun fold(a0: Double): Double {
        var a = ((a0 + 180) % 360) - 180
        if (a > 90) a -= 180 else if (a < -90) a += 180
        return a
    }

    fun close() { runCatching { landmarker?.close() } }

    companion object { private const val NO_FACE = "{\"face\":false}" }
}
