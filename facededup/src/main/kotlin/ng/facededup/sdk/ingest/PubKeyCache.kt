package ng.facededup.sdk.ingest

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * On-disk cache of the backend public key (`kid` + PEM + fetch time). The pubkey
 * is non-secret, so a plain JSON file under filesDir is enough — no DataStore
 * dependency. Valid for [TTL_MS] (24 h); callers force a refresh on `kid` mismatch.
 */
internal class PubKeyCache(ctx: Context) {

    @Serializable
    private data class Entry(
        @SerialName("pub")        val pub: PubKey,
        @SerialName("fetched_at") val fetchedAt: Long,
    )

    private val file = File(ctx.filesDir, FILE)

    /** Cached key if present AND fresh (< 24 h); else null. */
    fun fresh(nowMs: Long): PubKey? {
        val e = read() ?: return null
        return if (nowMs - e.fetchedAt < TTL_MS) e.pub else null
    }

    /** Cached key regardless of age (used as a fallback when a refresh fetch fails). */
    fun any(): PubKey? = read()?.pub

    fun put(pub: PubKey, nowMs: Long) {
        runCatching { file.writeText(IngestCrypto.json.encodeToString(Entry(pub, nowMs)), Charsets.UTF_8) }
    }

    fun clear() { runCatching { file.delete() } }

    private fun read(): Entry? = runCatching {
        if (!file.exists()) return null
        IngestCrypto.json.decodeFromString<Entry>(file.readText(Charsets.UTF_8))
    }.getOrNull()

    companion object {
        private const val FILE = "facededup_ingest_pubkey.json"
        const val TTL_MS = 24L * 60 * 60 * 1000   // 24 h
    }
}
