package ng.facededup.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import org.json.JSONObject

/**
 * Internal WebView host for the hosted Facededup flow. Grants the camera/mic to the
 * page's getUserMedia, supplies the demo HTTP Basic password, and finishes with the
 * result the flow reports through the `FacededupNative.onResult(json)` bridge.
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

    private lateinit var web: WebView
    private var password: String? = null
    private var cloudProjectNumber: Long = 0L
    private var done = false
    private var detector: FacededupMpDetector? = null
    private val detectExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Offline capture: when there's no network at launch we serve the flow from the
    // APK (localFlow) and the page queues frames to OfflineQueue; OfflineSubmitWorker
    // submits to /v1/offline/submit on reconnect and the verdict is delivered by webhook.
    private var offlineMode = false
    private var apiBase = ""
    private var licenseKey = ""

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
    } catch (e: Exception) { true }   // can't tell -> assume online (load the live page)

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadFlow() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        password = intent.getStringExtra(EXTRA_PASSWORD)
        cloudProjectNumber = intent.getLongExtra(EXTRA_CLOUD_PROJECT, 0L)

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false           // camera can auto-start
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Always fetch the hosted flow fresh. A cached page can otherwise pin a
            // device to an OLD build of the flow (the WebView HTTP cache survives
            // app force-close), so updates we ship server-side never reach it.
            cacheMode = WebSettings.LOAD_DEFAULT   // cache heavy static assets (WASM/model/fonts); the _cb param keeps the HTML fresh
        }
        web.addJavascriptInterface(Bridge(), "FacededupNative")
        // Native MediaPipe Tasks detection is OPTIONAL. Probe for the tasks-vision class
        // WITHOUT referencing it directly — if the integrator didn't add the dependency,
        // constructing FacededupMpDetector (which references FaceLandmarker) would throw
        // NoClassDefFoundError at class-load, BEFORE any try/catch inside it can run, and
        // crash the host app. So gate construction on Class.forName and silently fall back
        // to the bundled WASM engine when MediaPipe isn't present. loadFlow() passes
        // ?native=mp only when the detector actually started.
        detector = try {
            Class.forName("com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker")
            FacededupMpDetector(applicationContext)
        } catch (t: Throwable) {
            null   // tasks-vision not on the classpath → use the bundled WASM detection
        }
        web.addJavascriptInterface(DetectBridge(), "FacededupDetect")
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }   // camera + mic
            }
        }
        web.webViewClient = object : WebViewClient() {
            // Serve the MediaPipe engine + model from the APK (offline, instant, no data).
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                return localMediaPipe(this@FacededupActivity, url)
                    // OFFLINE: serve the bundled flow from the APK. ONLINE: null ->
                    // the live page loads so server-side flow updates still apply.
                    ?: (if (offlineMode) localFlow(this@FacededupActivity, url) else null)
                    ?: super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView, handler: HttpAuthHandler, host: String, realm: String,
            ) {
                val pw = password
                if (!pw.isNullOrEmpty()) handler.proceed("swiftend", pw) else handler.cancel()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) loadFlow()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun loadFlow() {
        val base = (intent.getStringExtra(EXTRA_BASE_URL) ?: "").trimEnd('/')
        if (base.isEmpty()) { finish(); return }
        val params = intent.getStringExtra(EXTRA_PARAMS).orEmpty()
        // Offline support: detect connectivity at launch + remember base/license so
        // the page can queue captures and the worker can submit them on reconnect.
        apiBase = base
        licenseKey = Regex("(?:^|&)license=([^&]+)").find(params)?.groupValues?.get(1) ?: ""
        offlineMode = !isOnline()
        // Cache-bust: a unique param per launch forces the WebView to fetch the
        // current hosted flow instead of serving a stale cached copy.
        val cb = "_cb=" + System.currentTimeMillis()
        // Tell the flow to use native MediaPipe detection (only if the engine started;
        // otherwise it falls back to the bundled WASM Worker automatically).
        val nativeFlag = if (detector?.isReady == true) "&native=mp" else ""
        val query = if (params.isEmpty()) cb else "$params&$cb"
        val url = "$base/demo/?$query$nativeFlag"
        web.loadUrl(url)
    }

    private fun finishWith(json: String) {
        if (done) return
        done = true
        // The payload can carry a selfie + 4-8 base64 frames (~1-2 MB), which can
        // exceed the Binder limit for Intent extras. Pass it via the in-process
        // holder; keep the Intent extra only as a small fallback (no images) so a
        // big transaction never throws TransactionTooLargeException.
        FacededupResultHolder.json = json
        val fallback = if (json.length <= 256 * 1024) json else "{\"type\":\"liveness\"}"
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, fallback))
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
        else { setResult(RESULT_CANCELED); super.onBackPressed() }
    }

    override fun onDestroy() {
        runCatching { detectExecutor.shutdownNow() }
        runCatching { detector?.close() }
        runCatching { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }

    // --- Device attestation (Annex A3e): Play Integrity bound to the challenge nonce ---

    /** Mint a Play Integrity token for [nonce] and hand it back to the web flow via
     *  `window.__onFacededupAttestation(token)`. On any failure (or no project number
     *  configured) it returns null so verify proceeds with attestation 'unverified'. */
    private fun requestPlayIntegrity(nonce: String) {
        if (cloudProjectNumber <= 0L) { deliverAttestation(null); return }
        // Play Integrity requires a URL-safe, unpadded nonce; the server accepts the
        // raw nonce or its base64url form (see play_integrity._nonce_matches).
        val encoded = Base64.encodeToString(
            nonce.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        runCatching {
            IntegrityManagerFactory.create(applicationContext)
                .requestIntegrityToken(
                    IntegrityTokenRequest.builder()
                        .setNonce(encoded)
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build(),
                )
                .addOnSuccessListener { resp -> deliverAttestation(resp.token()) }
                .addOnFailureListener { _ -> deliverAttestation(null) }
        }.onFailure { deliverAttestation(null) }
    }

    private fun deliverAttestation(token: String?) {
        val arg = if (token != null) JSONObject.quote(token) else "null"
        runOnUiThread {
            web.evaluateJavascript(
                "window.__onFacededupAttestation && window.__onFacededupAttestation($arg)", null)
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(json: String) { runOnUiThread { finishWith(json) } }

        /** Called by the web flow before /verify; replies via __onFacededupAttestation. */
        @JavascriptInterface
        fun requestAttestation(nonce: String) { runOnUiThread { requestPlayIntegrity(nonce) } }

        /** OFFLINE capture: the flow couldn't reach the server, so it hands us the
         *  captured frames (a /v1/offline/submit body). We persist it and schedule a
         *  network-constrained submit; the verdict is later delivered by webhook. */
        @JavascriptInterface
        fun queueOffline(payloadJson: String) {
            runCatching {
                val o = JSONObject(payloadJson)
                o.put("_base", apiBase)         // worker needs the API origin
                o.put("_license", licenseKey)   // and the tenant key to authenticate
                OfflineQueue.enqueue(applicationContext, o.toString())
                OfflineSubmitWorker.schedule(applicationContext)
            }
        }
    }

    /** Native-detection bridge: the flow ships a base64 JPEG per frame; we run MediaPipe
     *  off the UI thread and reply via window.__facededupPose(json). */
    private inner class DetectBridge {
        @JavascriptInterface
        fun detect(jpegBase64: String) {
            val d = detector ?: return
            runCatching {
                detectExecutor.execute {
                    val json = d.detect(jpegBase64)
                    web.post {
                        web.evaluateJavascript(
                            "window.__facededupPose && window.__facededupPose($json)", null)
                    }
                }
            }
        }
    }
}
