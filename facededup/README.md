# Facededup Android SDK (`:facededup`)

A **drop-in**, fully **native** face-liveness SDK. One `ActivityResult` call runs an
on-device active-liveness challenge (CameraX preview + ML Kit face detection — **no
WebView, no WASM, no model download**) and returns a typed `FacededupResult`.

> 2.0 replaced the old WebView host with a native capture pipeline. The lower-level
> REST client lives in `:liveness`.

## Add the module

In-tree:
```kotlin
dependencies { implementation(project(":facededup")) }
```

Or via the tokenless S3 Maven repo:
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://swiftend-assets-348761024048.s3.eu-west-2.amazonaws.com/m2") }
        google(); mavenCentral()
    }
}
// app/build.gradle.kts
dependencies { implementation("ng.facededup:facededup:2.0.0-alpha11") }
```

> **Channels:** the native pipeline ships as `2.0.0-alphaNN` and is **opt-in by exact
> version**. The `latest`/`release` Maven coordinate stays on the stable `1.2.x`
> (WebView) line until native is promoted. Pin the alpha explicitly to use it.

## Use it
```kotlin
private val verify = registerForActivityResult(FacededupContract()) { result ->
    when {
        result == null -> { /* user cancelled */ }
        result.passed  -> { /* verified — result.outcome / result.score */ }
        else           -> { /* not verified — show result.outcome */ }
    }
}

verify.launch(FacededupConfig(
    baseUrl    = "https://facededup.ai",
    licenseKey = "fdk_…",        // per-tenant key (or `password` for the demo gate)
    subjectId  = "user-123",
))
```

`FacededupResult` → `type` (liveness | identity | document | enroll), `outcome`,
`isLive`, `score`, `decision`, `enrollmentId`, `selfieImage`, `livelinessImages`,
`raw` (full JSON). `passed` is a convenience for "clearly verified".

## How it works (native 2.0)

1. `FacededupActivity` binds **CameraX** (front camera) + `ImageAnalysis`.
2. **ML Kit** face detection drives an **active-liveness challenge** (`ActiveLiveness`):
   a short, randomised sequence of head turns / tilts / smile / blink, each gated on a
   tracked frontal + resting-pitch baseline (so "look up/down" works with the natural
   phone-below-face tilt).
3. Proving frames (a portrait + one per action) POST to `/v1/offline/submit`
   (online → immediate verdict; offline → queued + flushed on reconnect).
4. The typed result returns via `ActivityResult`.

### UX (banking-grade)
- **Clean capture frame** — opaque white surround; only the oval (capture area) shows
  the camera.
- **Segmented progress ring** — a single neutral ring split into one segment per
  challenge; each segment fills green smoothly as that challenge passes (full ring +
  tick on success). No glow, no pulsing. A wrong move tints the active segment red.
- **Smart scene coaching** — low-light detection auto-boosts screen brightness (steady,
  latched — no flicker) and nudges *"find better light"*; framing nudges
  (*move closer / back*) gate the start under good conditions.
- **Freeze-frame + honest waiting** — on completion the last frame freezes in the oval
  with *"Hang tight — we're verifying…"*; **offline** shows *"saved ✓ — result once
  you're back online"*.
- **Haptics** — a tick per completed action + a success buzz.

## Configure everything (white-label)

Drop `assets/facededup_liveness.json` in your app (any subset overrides defaults), or
pass a `FacededupLivenessConfig` programmatically. Keys:

| Group | Keys |
|---|---|
| Challenges | `actions` (empty = random), `sequenceLength`, `turnYawDeg`, `tiltPitchDeg`, `smileThreshold`, `blinkThreshold`, `neutralYawDeg` |
| Scene quality | `darkLuma` (low-light threshold), `minFaceCoverage`, `maxFaceCoverage` |
| Ring / colours | `ringWidthDp`, `ringColor`, `successColor`, `scrimColor` |
| Branding | `pillColor`, `pillTextColor`, `cancelColor`, `showCancel`, `logoAsset` |
| Timing | `actionTimeoutMs`, `totalTimeoutMs`, `framesPerAction` |
| Diagnostics | `showDiagnostics` (live yaw/pitch/smile/eye readout — **off** by default) |
| Strings | `strings{}` — override/localise any on-screen copy (see below) |

Overridable string keys: `center_face`, `turn_left`, `turn_right`, `look_up`,
`look_down`, `smile`, `blink`, `hold_still`, `checking`, `only_one_face`, `great`,
`cancel`, `too_dark`, `too_bright`, `move_closer`, `move_back`, `hold_steady`,
`verifying`, `offline_saved`.

```json
{
  "sequenceLength": 3,
  "turnYawDeg": 18, "tiltPitchDeg": 12, "smileThreshold": 0.5,
  "darkLuma": 60, "minFaceCoverage": 0.05, "maxFaceCoverage": 0.55,
  "ringWidthDp": 10, "ringColor": "#1E9C69", "successColor": "#2CC05C",
  "showDiagnostics": false,
  "strings": { "center_face": "Center your face in the oval" }
}
```

## Device attestation (Play Integrity — Annex A3e)

Pass `cloudProjectNumber` to bind a hardware attestation token to the challenge nonce
(defeats emulators, hooked apps, injected streams):
```kotlin
verify.launch(FacededupConfig(
    baseUrl = "https://…", licenseKey = "fdk_…", subjectId = "user-123",
    cloudProjectNumber = 123456789012L,   // your GCP project number
))
```
Server must run `ATTESTATION_PROVIDER=play_integrity` with `ANDROID_PACKAGE_NAME` +
a Play-Integrity service account (`GOOGLE_SERVICE_ACCOUNT_JSON`).

## Anti-fraud metadata
Each submission carries ~20 device/integrity signals (`DeviceMetadata` + `MovementMonitor`):
VPN/proxy, orientation, battery, carrier, key attestation, capture duration, device
movement/proximity, etc. — all best-effort and guarded.

## Notes
- `minSdk 21`, `compileSdk 35`, JDK 17.
- Deps: AndroidX, **CameraX** 1.3.4, **ML Kit** face-detection 16.1.7, WorkManager,
  Play Integrity. (APK carries the bundled ML Kit model, ~bigger — can slim with the
  unbundled Play-Services model.)
- Production: use per-tenant `licenseKey` (not the demo `password`).
