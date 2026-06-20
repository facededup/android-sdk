package ng.facededup.sdk.ingest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ng.facededup.sdk.BuildConfig

/** Resolved ingest endpoint + secret. [enabled] is false when no key is configured. */
internal data class IngestConfig(val baseUrl: String, val ingestKey: String) {
    val enabled: Boolean get() = ingestKey.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        fun fromBuildConfig() = IngestConfig(
            baseUrl   = BuildConfig.FACEDEDUP_BASE_URL.trimEnd('/'),
            ingestKey = BuildConfig.FACEDEDUP_INGEST_KEY,
        )
    }
}

/**
 * Orchestrates encrypted liveness-event ingest:
 *  1. fetch the active backend pubkey (24 h disk cache; refresh on kid mismatch)
 *  2. seal the body (RSA-OAEP-256 + AES-256-GCM)
 *  3. POST the envelope with `X-Liveness-Ingest-Key`
 *  4. on `400 unknown kid`: re-fetch pubkey, re-seal, retry ONCE (same `request_id`)
 *  5. on network failure / 5xx: persist the sealed envelope, flush on reconnect
 *
 * [submit] is a suspend fun returning `Result<EventId>`; it runs on `Dispatchers.IO`
 * and never throws. [fireAndForget] launches it detached from the UI so the host
 * app's liveness result is never blocked by ingest.
 *
 * Logging: only non-secret signals (kid, HTTP code, event_id). NEVER the key,
 * the plaintext body, or the envelope.
 */
class IngestClient internal constructor(
    private val appContext: Context,
    private val cfg: IngestConfig,
    private val api: IngestApi,
    private val cache: PubKeyCache,
    private val now: () -> Long,
) {
    constructor(context: Context) : this(
        appContext = context.applicationContext,
        cfg = IngestConfig.fromBuildConfig(),
        api = HttpIngestApi(IngestConfig.fromBuildConfig().baseUrl, IngestConfig.fromBuildConfig().ingestKey),
        cache = PubKeyCache(context.applicationContext),
        now = { System.currentTimeMillis() },
    )

    /** Encrypt + deliver [body]. Returns the backend event id, or a failure (best-effort). */
    suspend fun submit(body: LivenessEventIngest): Result<EventId> = withContext(Dispatchers.IO) {
        if (!cfg.enabled) {
            Log.w(TAG, "ingest disabled (no FACEDEDUP_INGEST_KEY) — skipping")
            return@withContext Result.failure(IllegalStateException("ingest_disabled"))
        }
        runCatching { deliver(body) }
            .getOrElse { e -> Result.failure(e) }
    }

    /** Launch [submit] detached so the caller (and the UI) never waits on it. */
    fun fireAndForget(body: LivenessEventIngest) {
        scope.launch { submit(body) }
    }

    private fun deliver(body: LivenessEventIngest): Result<EventId> {
        // 1. pubkey (cache → network), 2. seal, 3. POST.
        var pub = pubKey(forceRefresh = false)
            ?: return Result.failure(IllegalStateException("no_pubkey"))
        Log.i(TAG, "pubkey kid=${pub.kid}")
        var env = IngestCrypto.sealEnvelope(body, pub)
        Log.i(TAG, "sealed envelope kid=${env.kid} (request_id idempotency-keyed)")

        when (val r = api.postSealed(env)) {
            is PostResult.Ok -> return ok(r)
            is PostResult.UnknownKid -> {
                // 4. backend rotated keys → refresh, re-seal with SAME body (same request_id), retry once.
                Log.w(TAG, "unknown kid=${pub.kid} → refreshing pubkey + retrying once")
                cache.clear()
                pub = pubKey(forceRefresh = true)
                    ?: return Result.failure(IllegalStateException("no_pubkey_on_refresh"))
                env = IngestCrypto.sealEnvelope(body, pub)
                return when (val r2 = api.postSealed(env)) {
                    is PostResult.Ok -> ok(r2)
                    is PostResult.NetworkError -> persist(env, r2.cause)
                    is PostResult.Rejected -> rejected(r2, env)
                    is PostResult.UnknownKid ->
                        Result.failure(IllegalStateException("kid_still_unknown_after_refresh"))
                }
            }
            is PostResult.NetworkError -> return persist(env, r.cause)
            is PostResult.Rejected -> return rejected(r, env)
        }
    }

    private fun ok(r: PostResult.Ok): Result<EventId> {
        Log.i(TAG, "POST 200 event_id=${r.resp.eventId} deduplicated=${r.resp.deduplicated}")
        return Result.success(r.resp.eventId)
    }

    private fun rejected(r: PostResult.Rejected, env: SealedEnvelope): Result<EventId> {
        if (r.retriable) {   // 5xx — server transient; queue + flush later.
            Log.w(TAG, "POST ${r.code} (transient) → queued for retry")
            return persist(env, RuntimeException("http_${r.code}"))
        }
        // 401 (bad key) / 422 (schema) / other 400 — do NOT retry blindly; alert.
        Log.e(TAG, "POST ${r.code} rejected (no retry): ${r.message}")
        return Result.failure(IllegalStateException("ingest_rejected_${r.code}"))
    }

    private fun persist(env: SealedEnvelope, cause: Throwable): Result<EventId> {
        IngestQueue.enqueue(appContext, env)
        IngestFlushWorker.schedule(appContext)
        Log.w(TAG, "delivery deferred (${cause.javaClass.simpleName}) → persisted + scheduled flush")
        return Result.failure(cause)
    }

    /** Cached-or-fetched pubkey; refreshes from network when stale or forced. */
    private fun pubKey(forceRefresh: Boolean): PubKey? {
        if (!forceRefresh) cache.fresh(now())?.let { return it }
        return runCatching { api.getPubKey() }
            .onSuccess { cache.put(it, now()) }
            .getOrElse { cache.any() }   // fall back to any cached key if the refresh fetch fails
    }

    private val scope: CoroutineScope get() = appScope

    companion object {
        private const val TAG = "FacededupIngest"
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
