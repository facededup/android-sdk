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
version = "1.0.4"

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
    // Device attestation (Annex A3e): Play Integrity token bound to the challenge nonce.
    implementation("com.google.android.play:integrity:1.4.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ng.facededup"
            artifactId = "facededup"
            version = "1.0.4"
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
