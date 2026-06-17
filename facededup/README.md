# Facededup Android SDK (`:facededup`)

A **drop-in** SDK that runs the full hosted Facededup flow (Enroll / Validate with
Government IDs / Authenticate — liveness, document scan, MRZ, face match) inside a
managed `WebView` and returns a typed result. One call, no WebView/permission/auth
plumbing for the integrator.

> This is the high-level *flow* SDK. The lower-level REST client lives in `:liveness`.

## Add the module
`settings.gradle.kts` already includes `:facededup`. In your app module:
```kotlin
dependencies { implementation(project(":facededup")) }
```

## Use it
```kotlin
private val verify = registerForActivityResult(FacededupContract()) { result ->
    when {
        result == null            -> { /* user cancelled */ }
        result.passed             -> { /* verified — result.outcome / result.score / result.enrollmentId */ }
        else                      -> { /* not verified — show result.outcome */ }
    }
}

// launch it
verify.launch(FacededupConfig(
    baseUrl  = "https://facededup.ai",
    password = "…",            // the demo gate password (omit once you use API keys)
    subjectId = "user-123",
))
```

`FacededupResult` → `type` (liveness | identity | document | enroll), `outcome`,
`isLive`, `score`, `decision`, `enrollmentId`, and `raw` (full JSON). `passed` is a
convenience for "clearly verified".

## How it works
- Hosts `<baseUrl>/demo/` in a `WebView`; grants camera/mic to the page's getUserMedia
  (`onPermissionRequest`) and requests the runtime `CAMERA` permission.
- Supplies the demo HTTP Basic password (`onReceivedHttpAuthRequest`).
- The flow reports the final result via the JS bridge `FacededupNative.onResult(json)`
  (added in the web flow's `postToHost`); the SDK parses it and returns via ActivityResult.

## Device attestation (Play Integrity — Annex A3e)
Pass `cloudProjectNumber` to bind a **hardware attestation token** to the challenge
nonce, so the server can prove the frames came from a genuine, untampered app+device
(defeats emulators, hooked apps, injected streams):
```kotlin
verify.launch(FacededupConfig(
    baseUrl = "https://…",
    password = "…",
    subjectId = "user-123",
    cloudProjectNumber = 123456789012L,   // your GCP project number
))
```
When set, the SDK requests a Play Integrity token (via the `requestAttestation` bridge)
and the web flow sends it as `attestation_token` in `/v1/verify`. Omit it → no token is
sent and the server records attestation as `unverified` (only blocks when the server has
`require_attestation` / step-up on).

**Server must be configured to verify it:** `ATTESTATION_PROVIDER=play_integrity`,
`ANDROID_PACKAGE_NAME=<your applicationId>`, and a Play-Integrity-enabled service account
via `GOOGLE_SERVICE_ACCOUNT_JSON`. Enable the Play Integrity API in that GCP project and
link it to your Play Console app.

## Notes
- `minSdk 24`, framework + `org.json` + `com.google.android.play:integrity`.
- Production: replace the demo password gate with per-tenant API keys / a session token,
  and pass a server-issued token instead of `subjectId`.
