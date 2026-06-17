// FaceDedup liveness SDK library module. Android framework + org.json only; the
// integration bits (Play Integrity, FusedLocation, CameraX) live in the host
// app / sample so the library stays light.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ng.facededup.liveness"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // No third-party deps: SignalCollector/LivenessClient use the Android
    // framework and org.json only. Swap HttpURLConnection for OkHttp in prod.
}
