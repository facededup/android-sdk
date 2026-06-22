package ng.facededup.sdk

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Native device/fraud signals the WebView (JS) cannot see: Build.*, fingerprint,
 * root/emulator/debugger, package + app hash, memory, cameras, battery, carrier, VPN,
 * hardware attestation. Collected once and injected as `window.__FACEDEDUP_NATIVE_SIGNALS`
 * so the web SDK's collectDeviceContext() merges it. Every field is best-effort —
 * a failure on one never blocks the others (or the flow).
 */
internal object DeviceSignals {

    /** Returns a JSON string `{ "device": {…}, "network": {…} }` for injection. */
    fun collect(ctx: Context): String {
        val device = JSONObject()
        val network = JSONObject()
        fun dev(k: String, v: Any?) { runCatching { if (v != null) device.put(k, v) } }
        fun net(k: String, v: Any?) { runCatching { if (v != null) network.put(k, v) } }

        // --- Build / identity ---
        dev("os", "android"); dev("os_version", Build.VERSION.RELEASE)
        dev("model", Build.MODEL); dev("manufacturer", Build.MANUFACTURER)
        dev("build_brand", Build.BRAND); dev("build_device", Build.DEVICE)
        dev("build_hardware", Build.HARDWARE); dev("build_product", Build.PRODUCT)
        dev("build_fingerprint", Build.FINGERPRINT)
        dev("system_architecture", Build.SUPPORTED_ABIS.firstOrNull())

        // --- Stable, app-scoped device id (for device-farm velocity). ANDROID_ID is
        // unique per app-signing-key per device on Android 8+ (not cross-app trackable). ---
        runCatching {
            @android.annotation.SuppressLint("HardwareIds")
            val aid = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            if (!aid.isNullOrBlank()) dev("device_id", aid)
        }

        // --- App identity + integrity ---
        runCatching {
            dev("package_name", ctx.packageName)
            val pm = ctx.packageManager
            dev("host_application", pm.getApplicationLabel(ctx.applicationInfo).toString())
            @Suppress("DEPRECATION")
            val pi = pm.getPackageInfo(ctx.packageName, 0)
            dev("app_version", pi.versionName)
            dev("device_app_hash", signingDigest(ctx))
        }

        // --- Privileges / environment ---
        dev("is_emulator", isEmulator())
        dev("is_rooted_jailbroken", isRooted())
        dev("is_debugger_attached", Debug.isDebuggerConnected() || Debug.waitingForDebugger())
        dev("supports_hardware_attestation", Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)

        // --- Screen ---
        runCatching {
            @Suppress("DEPRECATION")
            val d = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            dev("screen_refresh_rate", Math.round(d.refreshRate))
            val dm = ctx.resources.displayMetrics
            dev("screen_width_px", dm.widthPixels); dev("screen_height_px", dm.heightPixels)
            dev("screen_density_dpi", dm.densityDpi)
        }

        // --- Memory ---
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            dev("total_memory_bytes", mi.totalMem); dev("free_memory_bytes", mi.availMem)
            dev("low_memory", mi.lowMemory)
            val rt = Runtime.getRuntime()
            dev("app_memory_used_bytes", rt.totalMemory() - rt.freeMemory())
            dev("app_memory_limit_bytes", rt.maxMemory())
        }

        // --- Cameras ---
        runCatching {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            dev("num_cameras", cm.cameraIdList.size)
        }

        // --- Battery (sticky broadcast, no permission) ---
        runCatching {
            val bs = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bs != null) {
                val lvl = bs.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bs.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (lvl >= 0 && scale > 0) dev("battery_level", lvl.toDouble() / scale)
                val st = bs.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                dev("battery_charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
            }
        }

        // --- Locale / timezone ---
        runCatching {
            val tz = java.util.TimeZone.getDefault()
            dev("timezone", tz.id)
            dev("timezone_offset_minutes", tz.getOffset(System.currentTimeMillis()) / 60000)
            dev("locale", java.util.Locale.getDefault().toLanguageTag())
        }

        // --- SDK launch count ---
        runCatching {
            val sp = ctx.getSharedPreferences("facededup_sdk", Context.MODE_PRIVATE)
            val n = sp.getInt("launch_count", 0) + 1
            sp.edit().putInt("launch_count", n).apply()
            dev("sdk_launch_count", n)
        }

        // --- Network: carrier + VPN/proxy ---
        runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val carrier = tm?.networkOperatorName
            if (!carrier.isNullOrBlank()) net("carrier", carrier)
        }
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null) {
                net("vpn_suspected", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                net("connection_type", when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> null
                })
            }
            // HTTP(S) proxy configured?
            val proxy = System.getProperty("http.proxyHost")
            if (!proxy.isNullOrBlank()) net("proxy_suspected", true)
        }

        return JSONObject().put("device", device).put("network", network).toString()
    }

    /** SHA-256 of the app's signing certificate (hex). */
    private fun signingDigest(ctx: Context): String? = runCatching {
        val pm = ctx.packageManager
        val cert: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pi = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val pi = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            pi.signatures?.firstOrNull()?.toByteArray()
        }
        if (cert == null) null
        else MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase(); val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase(); val hw = Build.HARDWARE.lowercase()
        return fp.startsWith("generic") || fp.contains("emulator") || fp.contains("unknown") ||
            model.contains("emulator") || model.contains("sdk") || model.contains("google_sdk") ||
            product.contains("sdk") || product.contains("emulator") || product.contains("simulator") ||
            hw.contains("goldfish") || hw.contains("ranchu") || Build.BRAND.startsWith("generic") ||
            "google_sdk" == Build.PRODUCT
    }

    private fun isRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su",
            "/system/bin/.ext/.su", "/system/usr/we-need-root/su", "/cache/su",
            "/data/adb/magisk", "/sbin/.magisk",
        )
        return paths.any { runCatching { File(it).exists() }.getOrDefault(false) } ||
            runCatching { Runtime.getRuntime().exec(arrayOf("which", "su")).inputStream.bufferedReader().readLine() != null }.getOrDefault(false)
    }
}
