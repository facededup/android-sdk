package ng.facededup.sdk

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drains [OfflineQueue] to POST /v1/offline/submit once the device is online.
 * The server runs the liveness decision and delivers the verdict to the tenant's
 * configured webhook, so the worker only needs to submit (and retry on failure).
 *
 * Each queued JSON file carries `_base` (API origin) and `_license` (the tenant
 * key) alongside the /v1/offline/submit body; we strip those before POSTing.
 */
internal class OfflineSubmitWorker(ctx: Context, params: WorkerParameters) :
    Worker(ctx, params) {

    override fun doWork(): Result {
        var anyFailed = false
        for (file in OfflineQueue.pending(applicationContext)) {
            val ok = runCatching {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                val base = obj.optString("_base").trimEnd('/')
                val license = obj.optString("_license")
                obj.remove("_base"); obj.remove("_license")
                if (base.isEmpty() || license.isEmpty()) return@runCatching true  // unusable -> drop
                submit("$base/v1/offline/submit", license, obj.toString())
            }.getOrDefault(false)
            if (ok) OfflineQueue.remove(file) else anyFailed = true
        }
        // RETRY (WorkManager backoff) if anything failed AND items remain — else done.
        return if (anyFailed && OfflineQueue.count(applicationContext) > 0) Result.retry()
               else Result.success()
    }

    private fun submit(url: String, license: String, body: String): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000; readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-License-Key", license)
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // 2xx = accepted (verdict goes to the webhook). 4xx = unrecoverable -> drop
            // so we don't loop forever on a bad capture. 5xx -> retry later.
            code in 200..299 || code in 400..499
        } catch (e: Exception) {
            false   // network/transient -> retry
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val UNIQUE = "facededup_offline_submit"

        /** Enqueue a network-constrained drain of the offline queue. */
        fun schedule(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<OfflineSubmitWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
