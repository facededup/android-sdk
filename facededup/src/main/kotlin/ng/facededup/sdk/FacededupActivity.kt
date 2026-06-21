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
    private var toast: TextView? = null          // bottom status pill (verifying / submitted / offline)

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
    private var consentId: String? = null
    private var agentMode = false
    private var primaryColor = Color.parseColor("#1E9C69")

    private var portrait: String? = null
    private val frames = ArrayList<LivenessClient.Frame>()
    private var capturing = true
    private var done = false
    private var captureStartMs = 0L
    private var lastBitmap: Bitmap? = null        // most recent rotated/mirrored frame (for freeze)
    private var bestPortraitBmp: Bitmap? = null   // sharpest frontal frame → the quality/PAD image
    private var bestPortraitSharp = 0f
    private val recentFrames = ArrayDeque<Pair<Bitmap, Float>>()  // ring of recent (frame, sharpness)
    private var brightnessBoosted = false         // whether we forced the screen bright for low light
    private var lumaEma = -1f                      // smoothed scene brightness (anti-flicker)
    private var darkRun = 0                        // consecutive dark frames before latching boost
    private var wasWrong = false                   // edge-detect wrong move → buzz once
    private var frameLog = 0                        // throttle diagnostic logcat
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
        consentId = params["consent"]?.ifEmpty { null }
        agentMode = params["agent"] == "1"
        params["color"]?.let { runCatching { primaryColor = Color.parseColor(it) } }
        cfg = FacededupLivenessConfig.load(this)           // developer config (assets/facededup_liveness.json)
        liveness = ActiveLiveness(cfg)
        android.util.Log.i("FacededupLive", "onCreate base=$base licenseSet=${license.isNotBlank()} " +
            "consent=$consentId method=$method seq=${cfg.sequenceLength}")

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
        // Light page behind everything (matches the hosted/WebView flow). Theme bg overrides.
        root.setBackgroundColor(runCatching { bgHex?.let { Color.parseColor(it) } }.getOrNull() ?: Color.parseColor("#F1F5F5"))
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        // Frozen-frame layer: ON TOP of the live preview, UNDER the overlay — when we stop
        // the camera to decide, the user still sees their last shot inside the circle.
        shotView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = android.view.View.GONE
        }
        overlay = LivenessOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH); ringColor = primaryColor
        }
        // TOP instruction — bold dark text on the white card (no pill).
        hint = TextView(this).apply {
            text = cfg.str("center_face"); textSize = cfg.instructionSizeSp
            setTextColor(parseColorOr(cfg.pillTextColor, Color.parseColor("#1F2024")))
            gravity = Gravity.CENTER; maxLines = 2
            applyFont(this)
            setPadding(dp(28), 0, dp(28), 0)
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
        title = hint
        // BOTTOM status toast — a white rounded pill (verifying / submitted / offline). Hidden until needed.
        toast = TextView(this).apply {
            textSize = cfg.instructionSizeSp; setTextColor(Color.parseColor("#1F2024"))
            gravity = Gravity.CENTER; maxLines = 3
            applyFont(this)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpf(20f) }
            setPadding(dp(24), dp(16), dp(24), dp(16))
            elevation = dpf(8f); visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(64)
            }
        }
        root.addView(previewView); shotView?.let { root.addView(it) }; root.addView(overlay)
        cfg.logoAsset?.let { path ->
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeStream(assets.open(path))
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                    layoutParams = FrameLayout.LayoutParams(WRAP, dp(34)).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(28)
                    }
                }
                root.addView(iv)
            }
        }
        root.addView(hint); toast?.let { root.addView(it) }
        if (cfg.showCancel) {
            val cancel = TextView(this).apply {
                text = cfg.str("cancel"); textSize = 15f
                setTextColor(parseColorOr(cfg.cancelColor, Color.parseColor("#3A3A3A")))
                gravity = Gravity.CENTER; applyFont(this)
                setPadding(dp(24), dp(10), dp(24), dp(10))
                setOnClickListener { cancelFlow() }
                layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20)
                }
            }
            root.addView(cancel)
        }
        setContentView(root)
        // Anchor the instruction near the TOP of the card, ABOVE the oval (web-flow style).
        overlay.onLaidOut = {
            (hint.layoutParams as FrameLayout.LayoutParams).let {
                it.topMargin = (overlay.cardTopPx() + dp(16)).toInt(); hint.layoutParams = it
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    /** Custom font (e.g. "Onset"): cfg.fontAsset, else auto-tries assets/fonts/onset.ttf. */
    private val customFont: android.graphics.Typeface? by lazy {
        val candidates = listOfNotNull(cfg.fontAsset, "fonts/onset.ttf", "fonts/Onset.ttf", "onset.ttf")
        for (p in candidates) {
            val tf = runCatching { android.graphics.Typeface.createFromAsset(assets, p) }.getOrNull()
            if (tf != null) return@lazy tf
        }
        null
    }
    /** Apply the custom font (falling back to the system default) at the given style. */
    private fun applyFont(tv: TextView, style: Int = android.graphics.Typeface.BOLD) {
        val base = customFont
        tv.typeface = if (base != null) android.graphics.Typeface.create(base, style) else android.graphics.Typeface.create(tv.typeface, style)
    }
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
                // Higher analysis resolution → sharper, more-detailed proving frames (the old
                // 480x640 was too low-detail; the server flagged it as synthetic). ML Kit copes.
                .setTargetResolution(android.util.Size(720, 1280))
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
        val sharp = sharpnessY(proxy)           // focus measure (Laplacian variance) — higher = sharper
        val input = InputImage.fromMediaImage(media, rot)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.firstOrNull()
                onFaces(face, faces.size, proxy, rot, luma, sharp)
            }
            .addOnCompleteListener { proxy.close() }
    }

    // Runs on the ML Kit callback thread; capture reads the proxy (still open here).
    private fun onFaces(face: Face?, count: Int, proxy: ImageProxy, rot: Int, luma: Int, sharp: Float) {
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
        // Is the face actually INSIDE the oval? (centered + right size). The bbox is in the
        // rotation-corrected image frame, so normalise by the upright dimensions.
        val centered = if (face != null && count == 1) {
            val b = face.boundingBox
            val rotated = rot == 90 || rot == 270
            val iw = if (rotated) proxy.height.toFloat() else proxy.width.toFloat()
            val ih = if (rotated) proxy.width.toFloat() else proxy.height.toFloat()
            (b.exactCenterX() / iw) in 0.25f..0.75f && (b.exactCenterY() / ih) in 0.18f..0.74f
        } else false
        // Head must be positioned in the oval to start (centered + sized) — but NOT gated on
        // light (a dark room must never permanently stall Positioning).
        val framedOk = count == 1 && centered && !tooFar && !tooClose

        // Keep a short ring of recent frames WITH their sharpness, so proving frames pick the
        // SHARPEST nearby shot (not the motion-blurred one captured the instant a turn lands —
        // blur made the server read the face as SYNTHETIC and miss live motion).
        if (face != null && count == 1) {
            val bmp = runCatching { toDisplayBitmap(proxy, rot, mirror) }.getOrNull()
            if (bmp != null) {
                lastBitmap = bmp
                recentFrames.addLast(bmp to sharp)
                while (recentFrames.size > 3) recentFrames.removeFirst()   // GC handles evicted refs
                // sharpest, well-lit FRONTAL frame becomes the portrait (the quality/PAD image)
                if (!dark && isFrontal(face) && sharp > bestPortraitSharp) {
                    bestPortraitSharp = sharp; bestPortraitBmp = bmp
                }
            }
        }

        val proves = liveness.current.proves
        // Positioning only completes when the head is framed in the oval (framedOk).
        val satisfied = liveness.onFace(if (count == 1) face else null, framedOk)
        if (satisfied) {
            // submit the SHARPEST of the recent frames (all near the satisfying pose)
            val best = recentFrames.maxByOrNull { it.second }?.first ?: lastBitmap
            best?.let { runCatching { jpegB64Bitmap(it) }.getOrNull()?.let { b64 -> frames.add(LivenessClient.Frame(b64, proves)) } }
            haptic("action")   // crisp buzz on each completed challenge
        }
        val present = face != null && count == 1
        val finishedNow = liveness.isFinished
        val wrong = present && !finishedNow && liveness.wrong
        // Buzz once when the user first moves the WRONG way (don't repeat every frame).
        if (wrong && !wasWrong) haptic("wrong")
        wasWrong = wrong
        val positioning = liveness.current == ActiveLiveness.Directive.Positioning
        // Coaching only matters while we're still getting the user framed, not mid-action.
        val coachMsg = if (positioning && present) when {
            tooFar -> cfg.str("move_closer")
            tooClose -> cfg.str("move_back")
            !centered -> cfg.str("center_face")   // head not in the oval yet
            dark -> cfg.str("too_dark")
            else -> null
        } else null
        val act = if (present && !finishedNow && !positioning) liveness.subProgress else 0f
        val dir = if (present && !finishedNow && !positioning) liveness.directionDeg else null
        // Smile / blink have no direction → glow the oval as feedback.
        val glowAct = present && !finishedNow && !positioning &&
            (liveness.current == ActiveLiveness.Directive.Smile || liveness.current == ActiveLiveness.Directive.Blink)
        // Rich diagnostic: RAW sensor angles (so the device's yaw/pitch SIGN is unambiguous),
        // whether a face is present + framed in the oval, current step, sub-progress.
        val dbg = if (cfg.showDiagnostics) {
            "f=${if (present) 1 else 0} oval=${if (framedOk) 1 else 0} ry=%.0f rx=%.0f [%s] %.0f%%".format(
                face?.headEulerAngleY ?: 0f, face?.headEulerAngleX ?: 0f,
                liveness.current.name, liveness.subProgress * 100)
        } else null
        if (dbg != null && (frameLog++ % 6 == 0))
            android.util.Log.i("FacededupLive", "$dbg cov=%.2f cen=$centered wrong=$wrong".format(coverage))
        runOnUiThread {
            overlay.present = present
            overlay.wrong = wrong
            overlay.actionProgress = act                  // current action drives the single arc
            overlay.glowAction = glowAct
            overlay.directionDeg = dir
            overlay.success = finishedNow
            overlay.diagnostic = dbg
            hint.text = when {
                finishedNow -> cfg.str("great")
                count > 1 -> cfg.str("only_one_face")
                coachMsg != null -> coachMsg
                wrong -> wrongHint()
                else -> liveness.hint(present)
            }
        }
        if (liveness.isFinished && capturing) {
            capturing = false; haptic("success")   // success double-buzz
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
        ActiveLiveness.Directive.TurnLeft  -> "The other way, turn left"
        ActiveLiveness.Directive.TurnRight -> "The other way, turn right"
        ActiveLiveness.Directive.LookUp    -> "Tilt up, not down"
        ActiveLiveness.Directive.LookDown  -> "Tilt down, not up"
        else -> liveness.hint(true)
    }

    private val vibrator by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 31)
            (getSystemService(android.os.VibratorManager::class.java))?.defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)
    }
    /** Haptic feedback. kind: "action" = crisp tick per completed challenge,
     *  "success" = double-buzz on full pass, "wrong" = distinct error buzz. */
    private fun haptic(kind: String) {
        val v = vibrator ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val amp = android.os.VibrationEffect.DEFAULT_AMPLITUDE
                val effect = when (kind) {
                    "success" -> android.os.VibrationEffect.createWaveform(longArrayOf(0, 45, 70, 55), -1)
                    "wrong"   -> android.os.VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1)
                    else      -> android.os.VibrationEffect.createOneShot(60, amp)   // action tick (clearly felt)
                }
                v.vibrate(effect)
            } else @Suppress("DEPRECATION") v.vibrate(
                when (kind) { "success" -> 120L; "wrong" -> longArrayOf(0, 90, 90, 90).sum(); else -> 60L }
            )
        }
    }

    private fun submit() {
        if (done) return
        movement.stop()
        // Freeze the last frame inside the oval so the camera can stop while we decide.
        lastBitmap?.let { bmp ->
            shotView?.apply { setImageBitmap(bmp); visibility = android.view.View.VISIBLE }
        }
        overlay.success = true; overlay.present = true; overlay.verifying = true
        overlay.directionDeg = null; overlay.wrong = false
        hint.text = cfg.str("great")
        // No "verifying" noise — the pulsing dots indicate processing. Only surface the
        // bottom toast when OFFLINE, where the user genuinely needs to know the result is delayed.
        if (!LivenessClient.isOnline(applicationContext))
            toast?.apply { text = cfg.str("offline_saved"); visibility = android.view.View.VISIBLE }
        val durationMs = if (captureStartMs > 0) System.currentTimeMillis() - captureStartMs else 0L
        // Use the SHARPEST frontal frame as the portrait (the image the server scores for
        // quality + PAD). Falls back to whatever we have if none was captured.
        bestPortraitBmp?.let { portrait = runCatching { jpegB64Bitmap(it) }.getOrNull() ?: portrait }
        val all = ArrayList<LivenessClient.Frame>()
        portrait?.let { all.add(LivenessClient.Frame(it, null)) }
        all.addAll(frames)
        val bytes = all.sumOf { it.imageB64.length }
        android.util.Log.i("FacededupLive", "SUBMIT base=$base frames=${all.size} bytes=$bytes " +
            "online=${LivenessClient.isOnline(applicationContext)} actions=${liveness.actionKeys()}")
        Thread {
            try {
                val metadata = runCatching {
                    DeviceMetadata.collect(applicationContext, durationMs, 0,
                        "$subject-${System.currentTimeMillis()}", movement.summary())
                }.getOrNull()
                val json = LivenessClient.submit(applicationContext, base, license, subject,
                    method, liveness.actionKeys(), all, metadata)
                android.util.Log.i("FacededupLive", "SUBMIT result: ${json.take(220)}")
                runCatching { ingestEvent(json, metadata, durationMs) }   // best-effort, never blocks
                runOnUiThread { finishWith(json) }
            } catch (e: Throwable) {
                android.util.Log.e("FacededupLive", "SUBMIT failed", e)
                runOnUiThread { finishWith("{\"type\":\"liveness\",\"outcome\":\"error\",\"error\":\"submit_failed\"}") }
            }
        }.start()
    }

    /** Seal + post an encrypted liveness event (no-op unless consentId + ingest key set). */
    private fun ingestEvent(verdictJson: String, metadata: Map<String, Any?>?, durationMs: Long) {
        val consent = consentId ?: return                       // host didn't supply consent → skip
        runCatching {
            val body = ng.facededup.sdk.ingest.IngestEventBuilder.fromNative(
                consentId = consent,
                requestId = "req_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                method = method,
                verdictJson = verdictJson,
                metadata = metadata,
                subjectRef = subject.takeIf { it != "user" },
                totalLatencyMs = durationMs.toInt().takeIf { it > 0 },
            )
            ng.facededup.sdk.ingest.IngestClient(applicationContext).fireAndForget(body)
        }
    }

    private fun isFrontal(f: Face) = abs(f.headEulerAngleY) < 10f && abs(f.headEulerAngleX) < 12f

    private fun jpegB64(proxy: ImageProxy, rot: Int, mirror: Boolean): String =
        jpegB64Bitmap(toDisplayBitmap(proxy, rot, mirror))

    /** Encode an (already upright/mirrored) bitmap to base64 JPEG. Quality 94 + a higher
     *  capture resolution keeps real skin texture so the server's PAD/quality checks pass. */
    private fun jpegB64Bitmap(bmp: Bitmap, quality: Int = 94): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /** Upright, mirror-corrected bitmap for display (the frozen frame) and JPEG encoding. */
    private fun toDisplayBitmap(proxy: ImageProxy, rot: Int, mirror: Boolean): Bitmap {
        val bmp = proxy.toBitmap()
        val m = Matrix(); m.postRotate(rot.toFloat()); if (mirror) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Focus measure: variance of the Laplacian sampled on the Y (luma) plane. Higher = sharper.
     *  Cheap (downsampled grid) — runs every frame to pick the sharpest proving shot. */
    private fun sharpnessY(proxy: ImageProxy): Float = runCatching {
        val plane = proxy.planes[0]; val buf = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        val w = proxy.width; val h = proxy.height
        val step = maxOf(2, minOf(w, h) / 180)
        fun px(x: Int, y: Int) = buf.get(y * rs + x * ps).toInt() and 0xFF
        var sum = 0.0; var sumSq = 0.0; var n = 0
        var y = step
        while (y < h - step) {
            var x = step
            while (x < w - step) {
                val lap = px(x - step, y) + px(x + step, y) + px(x, y - step) + px(x, y + step) - 4 * px(x, y)
                sum += lap; sumSq += lap.toDouble() * lap; n++
                x += step
            }
            y += step
        }
        if (n == 0) 0f else ((sumSq / n) - (sum / n) * (sum / n)).toFloat()
    }.getOrDefault(0f)

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
        // Debug: when diagnostics are on, show the full result JSON with a Copy button
        // BEFORE returning to the host, so it can be inspected/copied.
        if (cfg.showDiagnostics) runOnUiThread { showResultDebug(json) } else finish()
    }

    /** Full-screen JSON viewer with a Copy button (debug only). "Done" returns to the host. */
    private fun showResultDebug(json: String) {
        val content = findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return finish()
        val pretty = runCatching { org.json.JSONObject(json).toString(2) }.getOrDefault(json)
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpf(16f) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply {
                setMargins(dp(16), dp(48), dp(16), dp(32)); gravity = Gravity.CENTER
            }
        }
        val header = TextView(this).apply {
            text = "Result JSON (debug)"; textSize = 16f; setTextColor(Color.parseColor("#1F2024"))
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, dp(10))
        }
        val tv = TextView(this).apply {
            text = pretty; textSize = 12f; setTextColor(Color.parseColor("#222222"))
            typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv); layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0)
        }
        val copy = android.widget.Button(this).apply {
            text = "Copy JSON"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("facededup_result", json))
                android.widget.Toast.makeText(this@FacededupActivity, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        }
        val doneBtn = android.widget.Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        row.addView(copy); row.addView(doneBtn)
        panel.addView(header); panel.addView(scroll); panel.addView(row)
        scrim.addView(panel)
        content.addView(scrim)
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
