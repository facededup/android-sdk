// Facededup drop-in SDK: launches the hosted verification flow in a WebView and
// returns a typed result via ActivityResult. Android framework + org.json only.
//
// Publishing — gives integrators a real dependency coordinate instead of the raw
// source module (fixes "unresolved reference: FacededupConfig"):
//   • Quick local test:   ./gradlew :facededup:publishToMavenLocal
//       then in the app:  repositories { mavenLocal() }
//                         implementation("ng.facededup:facededup:0.6.0")
//   • Build a drop-in AAR: ./gradlew :facededup:assembleRelease
//       -> build/outputs/aar/facededup-release.aar
//   • GitHub Packages:    ./gradlew :facededup:publish   (needs gpr.user/gpr.key)
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group = "ng.facededup"
version = "2.0.0-alpha06"

android {
    namespace = "ng.facededup.sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Offline capture: durable queue + network-constrained submit on reconnect.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Device attestation (Annex A3e): Play Integrity token bound to the challenge nonce.
    implementation("com.google.android.play:integrity:1.4.0")

    // --- 2.0 NATIVE capture (replaces the WebView) ---
    // CameraX: front-camera preview + frame analysis + still capture.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // ML Kit on-device face detection — head Euler angles + smile probability drive
    // the active-liveness challenge (no WebView, no WASM, no model download).
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ng.facededup"
            artifactId = "facededup"
            version = "2.0.0-alpha06"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Facededup Android SDK")
                description.set("Drop-in WebView SDK for the Facededup liveness + identity flow.")
            }
        }
    }
    repositories {
        // GitHub Packages — only used by `./gradlew :facededup:publish`.
        // Provide gpr.user / gpr.key in ~/.gradle/gradle.properties or env
        // (GITHUB_ACTOR / GITHUB_TOKEN). Harmless if absent for local builds.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/surdykbaba/facededup-liveliness")
            credentials {
                username = (project.findProperty("gpr.user") as String?)
                    ?: System.getenv("GITHUB_ACTOR")
                password = (project.findProperty("gpr.key") as String?)
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
