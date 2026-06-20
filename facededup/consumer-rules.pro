# ── Facededup SDK consumer ProGuard/R8 rules (applied in the host app's build) ──

# kotlinx-serialization: keep generated serializers + the @Serializable models so
# the encrypted-ingest wire schema survives R8 in release builds.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the serializer companion + the synthetic $serializer for every model.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-braces: keep the ingest model classes + their members by name.
-keep @kotlinx.serialization.Serializable class ng.facededup.sdk.ingest.** { *; }
-keepclassmembers class ng.facededup.sdk.ingest.** { *; }

# WorkManager worker is instantiated reflectively.
-keep class ng.facededup.sdk.ingest.IngestFlushWorker { *; }
