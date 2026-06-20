# Encrypted liveness-event ingest (`ng.facededup.sdk.ingest`)

End-to-end-encrypted telemetry for liveness events: the SDK seals a
`LivenessEventIngest` body with **RSA-OAEP-256 + AES-256-GCM** (stock
`javax.crypto`, no third-party crypto dep) and POSTs the envelope to the
Facededup backend. Only the backend's RSA private key can decrypt — no proxy,
load balancer, or operator sees the plaintext.

This is **separate** from the existing liveness *verification* call
(`/v1/offline/submit`): verification returns the pass/fail verdict to the host;
ingest is an encrypted audit/analytics event derived from that verdict.

## Flow

```
finish liveness ─▶ build LivenessEventIngest ─▶ IngestClient.fireAndForget(body)
                                                      │  (Dispatchers.IO, detached)
                                                      ▼
        GET /api/v1/liveness/pubkey  (24h disk cache, refresh on kid mismatch)
                                                      ▼
        sealEnvelope(body, pub)   RSA-OAEP-256 wrap of a one-shot AES-256-GCM key
                                                      ▼
        POST /api/v1/liveness/events/sealed   header: X-Liveness-Ingest-Key
                                                      ▼
        200 → { event_id, deduplicated, chain_hash_hex }
        400 unknown kid → re-fetch pubkey, re-seal, retry ONCE (same request_id)
        network / 5xx   → persist sealed envelope, flush on reconnect (WorkManager)
```

The host app's result is **never blocked** by ingest — `fireAndForget` runs it
detached, and any ingest failure is swallowed (logged, queued) so the liveness
result still reaches the host.

## Files

| File | Role |
|---|---|
| `IngestModels.kt` | `PubKey`, `SealedEnvelope`, `LivenessEventIngest`, `IngestResponse` (wire schema; `@SerialName` is authoritative). |
| `IngestCrypto.kt` | `sealEnvelope()` — the exact RSA+AES-GCM recipe. |
| `IngestApi.kt` | The two endpoints over `HttpURLConnection` (+ typed `PostResult`). |
| `PubKeyCache.kt` | 24h on-disk pubkey cache. |
| `IngestQueue.kt` / `IngestFlushWorker.kt` | Persist undelivered **sealed** envelopes; flush on reconnect. |
| `IngestEventBuilder.kt` | Build the body from the native SDK's verdict + `DeviceMetadata`. |
| `SmileMetadataMapper.kt` | Build the body from a Smile ID SDK metadata blob (→ `extra.smile_metadata`). |
| `IngestClient.kt` | Orchestrator: fetch pubkey → seal → POST → retry → persist. |

## Configuration

Set at **build time** (never committed). Precedence: Gradle property →
`local.properties` (gitignored) → environment variable.

```properties
# local.properties  (NOT checked in)
facededup.ingestKey=<the X-Liveness-Ingest-Key secret>
# optional: point at staging instead of prod (https://api.facededup.ai)
facededup.baseUrl=https://staging.api.facededup.ai
```

CI: export `FACEDEDUP_INGEST_KEY` (and optionally `FACEDEDUP_BASE_URL`) from your
secret manager. These become `BuildConfig.FACEDEDUP_INGEST_KEY` /
`BuildConfig.FACEDEDUP_BASE_URL`. If no key is set, ingest is silently disabled
(the verification flow is unaffected).

The host must also pass `consentId` in `FacededupConfig` (the consent-capture
record id) — it's a required field of the ingest body.

## Rotating the ingest key

The `X-Liveness-Ingest-Key` is a shared secret between this SDK and the backend
(treat it like a private key). To rotate:

1. Backend issues a new key and keeps the old one valid during the overlap.
2. Update the secret in your CI secret manager / each developer's
   `local.properties` (`facededup.ingestKey=…`).
3. Ship a new SDK/app build — the new `BuildConfig.FACEDEDUP_INGEST_KEY` is
   baked in at build time, so a rebuild + release is required (the key is not
   hot-swappable at runtime unless you wire a remote-config override into
   `IngestConfig`).
4. Backend retires the old key after the overlap window.

> Key **rotation** above is the *ingest secret* (auth header). Separately, the
> backend's **RSA pubkey** (`kid`) rotates transparently — the SDK refreshes it
> on a `400 unknown kid` and keeps working (old `kid`s stay decryptable ≥30 days).

## Security invariants

- Never log the ingest key, the AES key, the plaintext body, or the envelope.
- Persist only the **sealed** envelope offline — never the plaintext.
- Coarse geo only (`geo_country` ISO-2 + `geo_admin1`); never precise GPS.
- No binary biometric data in `extra` — that field is JSON for the dashboard.
