package ng.facededup.sdk

import android.content.Context
import java.io.File

/**
 * Durable on-device queue for OFFLINE captures. When the device has no network,
 * the flow hands the captured frames here; [OfflineSubmitWorker] drains the queue
 * to /v1/offline/submit once connectivity returns. Each entry is one JSON file
 * (the request body for /v1/offline/submit) under filesDir/facededup_offline_queue.
 */
internal object OfflineQueue {
    private const val DIR = "facededup_offline_queue"

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Persist one capture; returns the file name (the queue id). */
    fun enqueue(ctx: Context, payloadJson: String): String {
        val name = "cap_" + System.currentTimeMillis() + "_" +
            (Math.random() * 1e6).toInt() + ".json"
        File(dir(ctx), name).writeText(payloadJson, Charsets.UTF_8)
        return name
    }

    fun pending(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sorted() ?: emptyList()

    fun remove(file: File) { runCatching { file.delete() } }

    fun count(ctx: Context): Int = pending(ctx).size
}
