package ng.facededup.sdk.ingest

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Drains [IngestQueue] (sealed envelopes that failed to deliver) once the device
 * is online again. Uses the same BuildConfig base URL + ingest key as the live path.
 *
 * Re-POSTing is safe: the server is idempotent on the `request_id` sealed inside
 * each envelope, so a duplicate just returns the original event with
 * `deduplicated: true`.
 */
internal class IngestFlushWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val cfg = IngestConfig.fromBuildConfig()
        if (!cfg.enabled) return Result.success()    // no key configured → nothing to do
        val api = HttpIngestApi(cfg.baseUrl, cfg.ingestKey)

        var anyTransient = false
        for (file in IngestQueue.pending(applicationContext)) {
            val env = IngestQueue.read(file)
            if (env == null) { IngestQueue.remove(file); continue }   // corrupt → drop
            when (val r = api.postSealed(env)) {
                is PostResult.Ok -> {
                    Log.i(TAG, "flushed event_id=${r.resp.eventId} dedup=${r.resp.deduplicated}")
                    IngestQueue.remove(file)
                }
                // Old kid still decryptable for 30 days; if truly unknown now we can't re-seal
                // (no plaintext kept by design) → drop rather than loop forever.
                is PostResult.UnknownKid -> {
                    Log.w(TAG, "flush dropped: kid no longer known"); IngestQueue.remove(file)
                }
                is PostResult.Rejected ->
                    if (r.retriable) anyTransient = true                // 5xx → keep, retry later
                    else { Log.w(TAG, "flush dropped: HTTP ${r.code}"); IngestQueue.remove(file) }
                is PostResult.NetworkError -> anyTransient = true       // still offline → retry later
            }
        }
        return if (anyTransient && IngestQueue.count(applicationContext) > 0) Result.retry()
               else Result.success()
    }

    companion object {
        private const val TAG = "FacededupIngest"
        private const val UNIQUE = "facededup_ingest_flush"

        /** Enqueue a network-constrained drain of the ingest queue. */
        fun schedule(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<IngestFlushWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }
    }
}
