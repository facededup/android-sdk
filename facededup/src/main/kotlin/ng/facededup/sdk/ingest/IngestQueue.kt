package ng.facededup.sdk.ingest

import android.content.Context
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Durable queue of SEALED envelopes that couldn't be delivered (device offline /
 * server 5xx). We persist ONLY the encrypted envelope — never the plaintext body —
 * so nothing sensitive lands on disk. [IngestFlushWorker] re-POSTs these when
 * connectivity returns. Idempotency is preserved because `request_id` lives inside
 * the (already-sealed) ciphertext, so a re-POST dedups server-side.
 */
internal object IngestQueue {
    private const val DIR = "facededup_ingest_queue"

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun enqueue(ctx: Context, env: SealedEnvelope): String {
        val name = "evt_" + System.currentTimeMillis() + "_" + (Math.random() * 1e6).toInt() + ".json"
        File(dir(ctx), name).writeText(IngestCrypto.json.encodeToString(env), Charsets.UTF_8)
        return name
    }

    fun pending(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sorted() ?: emptyList()

    fun read(file: File): SealedEnvelope? = runCatching {
        IngestCrypto.json.decodeFromString(SealedEnvelope.serializer(), file.readText(Charsets.UTF_8))
    }.getOrNull()

    fun remove(file: File) { runCatching { file.delete() } }

    fun count(ctx: Context): Int = pending(ctx).size
}
