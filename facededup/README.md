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
dependencies { implementation("ng.facededup:facededup:2.0.0-alpha12") }
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
   a short, randomised sequence of head turns / tilts / smile / blink, each judged against
   a tracked frontal + resting-pitch baseline (so "look up/down" works with the natural
   phone-below-face tilt).
3. Proving frames (a portrait + one per action) POST to `/v1/offline/submit`
   (online → immediate verdict; offline → queued + flushed on reconnect).
4. The typed result returns via `ActivityResult`.

### UX (banking-grade)
- **Card layout** — a white surround with a centred translucent-grey card; the camera
  shows only inside a tall **oval** window (position the face there). Instruction sits in
  small, configurable text below the oval; "Cancel" at the bottom.
- **Intelligent oval border** — a full outline tracing the oval: faint/neutral until a
  face is detected, then a bright green border. A single **progress arc** (starts long,
  grows to full) rides on top, with a **directional arrow** for turns; **smile/blink glow**
  the oval (no direction). Full green ring + tick on success; a wrong move shows red.
- **Adaptive calibration** — each action starts strict, then gently relaxes (to ~70% over
  ~6s) so precise users pass instantly and strugglers still succeed.
- **Smart scene coaching (advisory)** — low-light detection auto-boosts screen brightness
  (steady, latched, no flicker) and nudges *"find better light"* / *"move closer/back"*.
  These never block the flow — a dark room or large face can't stall it.
- **Freeze-frame + honest waiting** — on completion the last frame freezes in the oval
  with a "verifying" message; **offline** shows a "saved, result once you are back online"
  message. **Haptics**: a tick per completed action + a success buzz.

## Configure everything (white-label)

Drop `assets/facededup_liveness.json` in your app (any subset overrides defaults), or
pass a `FacededupLivenessConfig` programmatically. Keys:

| Group | Keys |
|---|---|
| Challenges | `actions` (empty = random), `sequenceLength`, `turnYawDeg`, `tiltPitchDeg`, `smileThreshold`, `blinkThreshold`, `neutralYawDeg` (thresholds are starting points — adaptive relaxation eases them over time) |
| Scene quality (advisory) | `darkLuma` (low-light threshold), `minFaceCoverage`, `maxFaceCoverage` |
| Border / arc colours | `ringWidthDp` (arc/border thickness), `ringColor`, `successColor` (the green border), `scrimColor` (card colour) |
| Text / font | `instructionSizeSp` (default 14), `fontAsset` (custom font path, e.g. `fonts/onset.ttf`) |
| Branding | `pillTextColor` (instruction colour), `cancelColor`, `showCancel`, `logoAsset` |
| Timing | `actionTimeoutMs`, `totalTimeoutMs`, `framesPerAction` |
| Diagnostics | `showDiagnostics` — live yaw/pitch/smile/eye readout **and** a copyable result-JSON panel on completion (**off** by default) |
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
  "instructionSizeSp": 14, "fontAsset": "fonts/onset.ttf",
  "ringWidthDp": 5, "successColor": "#3DDC84", "scrimColor": "#33000000",
  "showDiagnostics": false,
  "strings": { "center_face": "Center your face in the oval" }
}
```

> **Custom font:** drop the `.ttf` in your app's `assets` (e.g. `assets/fonts/onset.ttf`)
> and set `fontAsset` to its path; the SDK also auto-tries `fonts/onset.ttf` if unset.
> Falls back to the system font when no file is found.

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
