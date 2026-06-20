package ng.facededup.sdk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * NATIVE liveness capture (2.0) — CameraX preview + ML Kit face detection drive an
 * active-liveness challenge ([ActiveLiveness]); proving frames submit to the Facededup
 * backend ([LivenessClient]) and the typed result is returned via ActivityResult.
 * Replaces the 1.x WebView host — no WebView, no WASM, no getUserMedia.
 */
class FacededupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BASE_URL = "facededup.baseUrl"
        const val EXTRA_PASSWORD = "facededup.password"
        const val EXTRA_SUBJECT = "facededup.subjectId"
        const val EXTRA_CLOUD_PROJECT = "facededup.cloudProjectNumber"
        const val EXTRA_PARAMS = "facededup.params"
        const val EXTRA_RESULT = "facededup.result"
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: LivenessOverlay
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private var shotView: ImageView? = null      // frozen last frame shown while deciding

    private val analysisExec = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)   // smile probability
            .build())

    private lateinit var liveness: ActiveLiveness
    private lateinit var cfg: FacededupLivenessConfig
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private var base = ""
    private var license = ""
    private var subject = "user"
    private var method = "face_liveness"
    private var agentMode = false
    private var primaryColor = Color.parseColor("#1E9C69")

    private var portrait: String? = null
    private val frames = ArrayList<LivenessClient.Frame>()
    private var capturing = true
    private var done = false
    private var captureStartMs = 0L
    private var lastBitmap: Bitmap? = null        // most recent rotated/mirrored frame (for freeze)
    private var brightnessBoosted = false         // whether we forced the screen bright for low light
    private var lumaEma = -1f                      // smoothed scene brightness (anti-flicker)
    private var darkRun = 0                        // consecutive dark frames before latching boost
    private val movement by lazy { MovementMonitor(this) }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else finishWith("{\"type\":\"liveness\",\"outcome\":\"error\",\"error\":\"camera_denied\"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        base = (intent.getStringExtra(EXTRA_BASE_URL) ?: "").trimEnd('/')
        val params = parseParams(intent.getStringExtra(EXTRA_PARAMS).orEmpty())
        license = params["license"] ?: ""
        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: params["subject"] ?: "user"
        method = params["method"] ?: "face_liveness"
        agentMode = params["agent"] == "1"
        params["color"]?.let { runCatching { primaryColor = Color.parseColor(it) } }
        cfg = FacededupLivenessConfig.load(this)           // developer config (assets/facededup_liveness.json)
        liveness = ActiveLiveness(cfg)

        buildUi(params["bg"])
        overlay.applyConfig(cfg.ringWidthDp, primaryColor,
            runCatching { cfg.successColor?.let { Color.parseColor(it) } }.getOrNull() ?: LivenessOverlay.GREEN,
            cfg.scrimColor)
        runCatching { cfg.ringColor?.let { primaryColor = Color.parseColor(it) } }
        // Safety net: never hang — submit whatever we have if the whole flow runs long.
        watchdog.postDelayed({ if (!done && capturing) { capturing = false; submit() } }, cfg.totalTimeoutMs)
        if (base.isEmpty()) { finishWith("{\"type\":\"liveness\",\"outcome\":\"error\",\"error\":\"no_base_url\"}"); return }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi(bgHex: String?) {
        val root = FrameLayout(this)
        // Light background (host reads as a clean white screen). Theme bg overrides.
        root.setBackgroundColor(runCatching { bgHex?.let { Color.parseColor(it) } }.getOrNull() ?: Color.WHITE)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        // Frozen-frame layer: sits ON TOP of the live preview but UNDER the overlay, so when
        // we stop the camera to decide, the user still sees their last shot inside the oval.
        shotView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = android.view.View.GONE
        }
        overlay = LivenessOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); ringColor = primaryColor
        }
        // Instruction on a rounded pill, placed just BELOW the oval (colours configurable).
        val pillBg = parseColorOr(cfg.pillColor, Color.parseColor("#D92D2B2A"))
        val pillText = parseColorOr(cfg.pillTextColor, Color.WHITE)
        hint = TextView(this).apply {
            text = cfg.str("center_face"); textSize = 17f; setTextColor(pillText)
            gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { setColor(pillBg); cornerRadius = dpf(24f) }
            setPadding(dp(22), dp(12), dp(22), dp(12))
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        title = hint   // single instruction line drives the pill
        root.addView(previewView); shotView?.let { root.addView(it) }; root.addView(overlay)
        // Optional brand logo at the top (from app assets).
        cfg.logoAsset?.let { path ->
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeStream(assets.open(path))
                val iv = android.widget.ImageView(this).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                    layoutParams = FrameLayout.LayoutParams(WRAP, dp(40)).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(40)
                    }
                }
                root.addView(iv)
            }
        }
        root.addView(hint)
        if (cfg.showCancel) {
            val cancel = TextView(this).apply {
                text = cfg.str("cancel"); textSize = 16f
                setTextColor(parseColorOr(cfg.cancelColor, Color.parseColor("#5F5E5A")))
                gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(24), dp(14), dp(24), dp(14))
                setOnClickListener { cancelFlow() }
                layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(36)
                }
            }
            root.addView(cancel)
        }
        setContentView(root)
        // Once laid out, drop the instruction pill just under the oval.
        overlay.post {
            (hint.layoutParams as FrameLayout.LayoutParams).let {
                it.topMargin = (overlay.ovalBottomPx() + dp(28)).toInt(); hint.layoutParams = it
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density
    private fun parseColorOr(hex: String?, fallback: Int): Int =
        runCatching { hex?.let { Color.parseColor(it) } }.getOrNull() ?: fallback

    private fun cancelFlow() {
        if (done) return
        done = true; capturing = false
        watchdog.removeCallbacksAndMessages(null)
        setResult(RESULT_CANCELED); finish()   // contract returns null -> host sees cancelled
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(analysisExec, ::analyze)
            val selector = if (agentMode) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                captureStartMs = System.currentTimeMillis()
                movement.start()   // sample motion/orientation/proximity during capture
            }.onFailure { finishWith("{\"type\":\"liveness\",\"outcome\":\"error\",\"error\":\"camera_init\"}") }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || !capturing) { proxy.close(); return }
        val rot = proxy.imageInfo.rotationDegrees
        val luma = avgLuma(proxy)               // scene brightness 0..255 from the Y plane
        val input = InputImage.fromMediaImage(media, rot)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.firstOrNull()
                onFaces(face, faces.size, proxy, rot, luma)
            }
            .addOnCompleteListener { proxy.close() }
    }

    // Runs on the ML Kit callback thread; capture reads the proxy (still open here).
    private fun onFaces(face: Face?, count: Int, proxy: ImageProxy, rot: Int, luma: Int) {
        if (done) return
        val mirror = !agentMode

        // --- Smart scene quality: brightness + framing coaching ---------------------
        // Smooth the luma (EMA) so the dark/bright decision can't flicker frame-to-frame.
        lumaEma = if (lumaEma < 0f) luma.toFloat() else lumaEma + (luma - lumaEma) * 0.1f
        val dark = lumaEma < cfg.darkLuma
        // Latch the screen-brightness boost ON after several steady dark frames, then HOLD
        // it for the rest of the flow. Toggling per-frame caused a feedback flicker (the
        // brighter screen lights the face → luma rises → boost off → face darkens → on…).
        if (!brightnessBoosted) {
            darkRun = if (dark) darkRun + 1 else 0
            if (darkRun >= 8) applyBrightness(true)
        }
        // Face coverage = how much of the frame the face fills (rotation-invariant area ratio).
        val coverage = if (face != null && count == 1) {
            val b = face.boundingBox
            (b.width().toFloat() * b.height()) / (proxy.width.toFloat() * proxy.height)
        } else 0f
        val tooFar = count == 1 && coverage in 0.0001f..cfg.minFaceCoverage
        val tooClose = count == 1 && coverage > cfg.maxFaceCoverage
        // Good light + sensible distance → safe to lock the resting baseline.
        val qualityOk = !dark && !tooFar && !tooClose

        // Capture a frontal portrait once (only when well lit + framed).
        if (portrait == null && face != null && count == 1 && qualityOk && isFrontal(face))
            portrait = runCatching { jpegB64(proxy, rot, mirror) }.getOrNull()

        // Keep the latest frame around so we can freeze it while deciding.
        if (face != null && count == 1)
            runCatching { toDisplayBitmap(proxy, rot, mirror) }.getOrNull()?.let { lastBitmap = it }

        val proves = liveness.current.proves
        val satisfied = liveness.onFace(if (count == 1) face else null, qualityOk)   // require exactly one good face
        if (satisfied) {
            runCatching { jpegB64(proxy, rot, mirror) }.getOrNull()?.let { frames.add(LivenessClient.Frame(it, proves)) }
            vibrate(40)   // SHAP — haptic confirm on each completed action
        }
        val present = face != null && count == 1
        val finishedNow = liveness.isFinished
        val wrong = present && !finishedNow && liveness.wrong
        val positioning = liveness.current == ActiveLiveness.Directive.Positioning
        // Coaching only matters while we're still getting the user framed, not mid-action.
        val coachMsg = if (positioning && present) when {
            dark -> cfg.str("too_dark")
            tooFar -> cfg.str("move_closer")
            tooClose -> cfg.str("move_back")
            else -> null
        } else null
        val prog = liveness.overallProgress
        val dir = if (present && !finishedNow) liveness.directionDeg else null
        runOnUiThread {
            overlay.wrong = wrong
            overlay.progress = prog
            overlay.segments = liveness.total            // one ring segment per challenge
            overlay.directionDeg = dir
            overlay.success = finishedNow
            overlay.diagnostic = if (cfg.showDiagnostics) liveness.debugLine() else null
            hint.text = when {
                finishedNow -> cfg.str("great")
                count > 1 -> cfg.str("only_one_face")
                coachMsg != null -> coachMsg
                wrong -> wrongHint()
                else -> liveness.hint(present)
            }
        }
        if (liveness.isFinished && capturing) {
            capturing = false; vibrate(0)   // success buzz (double pattern)
            runOnUiThread { submit() }
        }
    }

    /** Average luminance (0..255) sampled from the YUV Y plane — cheap scene-brightness probe. */
    private fun avgLuma(proxy: ImageProxy): Int = runCatching {
        val buf = proxy.planes[0].buffer; buf.rewind()
        val n = buf.remaining(); if (n == 0) return 200
        var sum = 0L; var cnt = 0; val step = maxOf(1, n / 2000)
        var i = 0; while (i < n) { sum += (buf.get(i).toInt() and 0xFF); cnt++; i += step }
        (sum / maxOf(1, cnt)).toInt()
    }.getOrDefault(200)

    /** Boost the screen to full brightness ONCE (steady; helps the front camera in low light).
     *  Held for the rest of the flow and restored in onDestroy — never toggled per frame. */
    private fun applyBrightness(on: Boolean) {
        if (brightnessBoosted == on) return
        brightnessBoosted = on
        runOnUiThread {
            runCatching {
                val lp = window.attributes
                lp.screenBrightness = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    // Corrective nudge when the user moves the opposite way from what's asked.
    private fun wrongHint(): String = when (liveness.current) {
        ActiveLiveness.Directive.TurnLeft  -> "↩ The other way — turn LEFT"
        ActiveLiveness.Directive.TurnRight -> "↪ The other way — turn RIGHT"
        ActiveLiveness.Directive.LookUp    -> "↑ Tilt UP, not down"
        ActiveLiveness.Directive.LookDown  -> "↓ Tilt DOWN, not up"
        else -> liveness.hint(true)
    }

    private val vibrator by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 31)
            (getSystemService(android.os.VibratorManager::class.java))?.defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)
    }
    /** SHAP: ms>0 = single tick (action done); ms==0 = success double-buzz. */
    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val effect = if (ms > 0) android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                             else android.os.VibrationEffect.createWaveform(longArrayOf(0, 35, 60, 35), -1)
                v.vibrate(effect)
            } else @Suppress("DEPRECATION") v.vibrate(if (ms > 0) ms else 90L)
        }
    }

    private fun submit() {
        if (done) return
        movement.stop()
        // Freeze the last frame inside the oval so the camera can stop while we decide.
        lastBitmap?.let { bmp ->
            shotView?.apply { setImageBitmap(bmp); visibility = android.view.View.VISIBLE }
        }
        overlay.success = true; overlay.directionDeg = null; overlay.wrong = false
        // Friendly waiting copy — and if we're offline, set expectations honestly.
        val online = LivenessClient.isOnline(applicationContext)
        hint.text = if (online) cfg.str("verifying") else cfg.str("offline_saved")
        if (!online) hint.maxLines = 4
        val durationMs = if (captureStartMs > 0) System.currentTimeMillis() - captureStartMs else 0L
        val all = ArrayList<LivenessClient.Frame>()
        portrait?.let { all.add(LivenessClient.Frame(it, null)) }
        all.addAll(frames)
        Thread {
            // Anti-fraud metadata: VPN/proxy, device movement/orientation/proximity, battery,
            // carrier, geolocation (if permitted), attestation chain, capture duration, etc.
            val metadata = runCatching {
                DeviceMetadata.collect(applicationContext, durationMs, 0,
                    "$subject-${System.currentTimeMillis()}", movement.summary())
            }.getOrNull()
            val json = LivenessClient.submit(applicationContext, base, license, subject,
                method, liveness.actionKeys(), all, metadata)
            runOnUiThread { finishWith(json) }
        }.start()
    }

    private fun isFrontal(f: Face) = abs(f.headEulerAngleY) < 10f && abs(f.headEulerAngleX) < 12f

    private fun jpegB64(proxy: ImageProxy, rot: Int, mirror: Boolean): String {
        val out = toDisplayBitmap(proxy, rot, mirror)
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 88, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /** Upright, mirror-corrected bitmap for display (the frozen frame) and JPEG encoding. */
    private fun toDisplayBitmap(proxy: ImageProxy, rot: Int, mirror: Boolean): Bitmap {
        val bmp = proxy.toBitmap()
        val m = Matrix(); m.postRotate(rot.toFloat()); if (mirror) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun parseParams(query: String): Map<String, String> =
        query.split("&").mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else
                runCatching { java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8") }.getOrNull()
        }.toMap()

    private fun finishWith(json: String) {
        if (done) return
        done = true; capturing = false
        watchdog.removeCallbacksAndMessages(null)
        FacededupResultHolder.json = json
        val fallback = if (json.length <= 256 * 1024) json else "{\"type\":\"liveness\"}"
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, fallback))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { watchdog.removeCallbacksAndMessages(null) }
        runCatching { movement.stop() }
        runCatching { analysisExec.shutdown() }
        runCatching { detector.close() }
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
