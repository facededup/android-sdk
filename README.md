# Facededup Android SDK — `ng.facededup:facededup`

Drop-in face-liveness + identity verification for Android. **2.0 is fully native** —
CameraX + ML Kit on-device active liveness (no WebView, no WASM, no model download),
typed `FacededupResult`. `minSdk 21`.

- Maven (tokenless): `https://swiftend-assets-348761024048.s3.eu-west-2.amazonaws.com/m2`
  - Native preview (opt-in by exact version): `implementation("ng.facededup:facededup:2.0.0-alpha12")`
  - Stable (WebView, `latest`): `implementation("ng.facededup:facededup:1.2.1")`
- Modules: `:facededup` (public SDK), `:liveness` (REST/engine), `:sample` (demo app).
- Integration guide: [`facededup/README.md`](facededup/README.md)
- Docs: https://facededup.ai/docs#android

> The native pipeline lives on branch `native-2.0` and ships as `2.0.0-alphaNN`. The
> Maven `latest`/`release` coordinate stays on `1.2.x` until native is promoted, so
> existing integrators are unaffected until they pin the alpha.
