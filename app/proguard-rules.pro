# =========================================================================
# NutriMadhvi — ProGuard / R8 rules
# =========================================================================
# Active when buildTypes.release.isMinifyEnabled = true (and mirrored on
# debug while we shake the rules down). Most third-party libraries ship
# their own consumer rules via `consumer-rules.pro` and are picked up
# automatically; everything below is project-specific reinforcement or
# rules for libraries whose own consumers are incomplete.
#
# Rule hygiene: prefer the MINIMAL keep that works. A bare `-keep class **`
# defeats the point of R8. Each section explains WHY the keep is needed.
# =========================================================================

# -------------------------------------------------------------------------
# Keep line numbers on stack traces — without this, R8 strips them and a
# crash report becomes worthless. The mapping.txt produced alongside the
# APK can deobfuscate `a.b.c` back to real names; the line number is the
# part that doesn't survive without explicit attribute preservation.
# -------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -------------------------------------------------------------------------
# Generic annotations + signatures used by Kotlin reflection, kotlinx-
# serialization-style scanners, and Retrofit's parameter parsing. Cheap to
# keep; expensive to debug when missing.
# -------------------------------------------------------------------------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# -------------------------------------------------------------------------
# Moshi (with codegen)
# Codegen generates `*JsonAdapter` classes at compile time, so Moshi can
# in principle work without reflection. We still keep:
#   - All @JsonClass-annotated data classes and their generated adapters,
#     because Moshi's Builder fallback looks them up by reflection if no
#     adapter is provided explicitly.
#   - Anything Moshi might call via `Types.newParameterizedType(...)`.
# -------------------------------------------------------------------------
-keep,allowobfuscation,allowshrinking @com.squareup.moshi.JsonClass class *
-keep class **JsonAdapter { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}

# Moshi's internal reflection on enum values (used for @Json names on enums).
-keepclassmembers,allowobfuscation @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
    **[] values();
}

# -------------------------------------------------------------------------
# Retrofit + OkHttp
# Retrofit ships solid consumer rules; the extras below cover the corner
# cases where a release build was observed silently dropping a return type.
# -------------------------------------------------------------------------
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Retrofit's service interfaces are looked up by reflection. Keep them
# all (we only have GeminiApiService in this project; the wildcard is
# defensive against future additions).
-keep,allowobfuscation interface retrofit2.Call
-keep,allowobfuscation,allowshrinking interface * { @retrofit2.http.* <methods>; }

# OkHttp uses Conscrypt opportunistically; absent on most devices.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# -------------------------------------------------------------------------
# Room
# AndroidX Room ships its own consumer-rules.pro that already covers
# generated DAO impls and entity field access. We add one defensive keep
# for the database class to make sure the migration list is reachable
# even after aggressive shrinking.
# -------------------------------------------------------------------------
-keep class com.example.data.local.ChatDatabase { *; }
-keep class com.example.data.local.ChatDatabase$* { *; }

# -------------------------------------------------------------------------
# Apache POI (XLSX export)
# POI is famously reflection-heavy. Without these, the Drive backup
# silently fails to load OOXML schemas at runtime. The `-dontwarn`s
# silence the optional XML APIs POI references but we exclude in
# build.gradle.
# -------------------------------------------------------------------------
-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class org.etsi.uri.x01903.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class schemasMicrosoftComOfficeExcel.** { *; }
-keep class schemasMicrosoftComOfficeOffice.** { *; }
-keep class schemasMicrosoftComVml.** { *; }
-keep class com.graphbuilder.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn org.apache.xmlbeans.**
-dontwarn javax.xml.**
-dontwarn org.etsi.uri.**
-dontwarn com.microsoft.schemas.**
-dontwarn com.graphbuilder.**

# -------------------------------------------------------------------------
# Apache log4j2 (transitive dep of POI ooxml)
# POI's `POIXMLDocumentPart.<clinit>` triggers `LogManager.<clinit>` which
# instantiates `DefaultFlowMessageFactory` via `Class.newInstance()`. R8
# saw nothing else calling the no-args ctor and stripped it, so the static
# initialiser blew up with `InstantiationException` the moment the Drive
# export button was tapped. Keep the whole log4j2 surface + all <init>s
# reachable by reflection. Loud silence about its optional async/JMX/OSGi
# plumbing we don't carry on Android.
# Crash trace (2026-06-26, version 1.135):
#   java.lang.InstantiationException:
#     org.apache.logging.log4j.message.DefaultFlowMessageFactory
#     cannot be instantiated
# -------------------------------------------------------------------------
-keep class org.apache.logging.log4j.** { *; }
-keep class org.apache.logging.slf4j.** { *; }
-keepclassmembers class org.apache.logging.** {
    <init>(...);
}
-dontwarn org.apache.logging.**
-dontwarn org.apache.logging.log4j.core.**
-dontwarn org.apache.logging.log4j.util.**

# -------------------------------------------------------------------------
# Apache Commons Compress (transitive dep of POI ooxml — produces the ZIP
# wrapping every XLSX). After fixing log4j2, the next crash came from
# ZipArchiveEntry walking its extra-field registry and hitting a stripped
# ExtraFieldUtils. The pattern is the same — POI calls into commons-compress
# by reflection in spots R8 can't see — so we keep the whole namespace.
# Crash trace (2026-06-26, version 1.136):
#   java.lang.NoClassDefFoundError:
#     org.apache.commons.compress.archivers.zip.ExtraFieldUtils
# -------------------------------------------------------------------------
-keep class org.apache.commons.compress.** { *; }
-keep interface org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# -------------------------------------------------------------------------
# Apache Commons IO / Codec / Collections — siblings of commons-compress
# that POI pulls in. Pre-emptive keeps to avoid the same whack-a-mole.
# -------------------------------------------------------------------------
-keep class org.apache.commons.io.** { *; }
-keep class org.apache.commons.codec.** { *; }
-keep class org.apache.commons.collections4.** { *; }
-keep class org.apache.commons.math3.** { *; }
-dontwarn org.apache.commons.io.**
-dontwarn org.apache.commons.codec.**
-dontwarn org.apache.commons.collections4.**
-dontwarn org.apache.commons.math3.**

# -------------------------------------------------------------------------
# Samsung Health Data SDK (AAR, app/libs/samsung-health-data-1.1.0.aar)
# The SDK does Parcelable<->builder reflection internally. Without these
# keeps, AggregateRequest construction throws ClassNotFoundException at
# runtime — a failure mode that ONLY manifests on a minified build, so
# regression-testing the Activity tab on release is mandatory after any
# proguard change.
# -------------------------------------------------------------------------
-keep class com.samsung.android.sdk.health.data.** { *; }
-keep interface com.samsung.android.sdk.health.data.** { *; }
-dontwarn com.samsung.android.sdk.health.data.**

# -------------------------------------------------------------------------
# Jetpack Compose
# Compose's runtime doesn't generally need keep rules — the compiler
# plugin generates code that R8 understands. The one exception:
# `@Composable` functions referenced by string via tooling APIs. We
# don't use those, but keep the attribute table just in case.
# -------------------------------------------------------------------------
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers,allowobfuscation class * {
    @androidx.compose.runtime.Composable <methods>;
}

# -------------------------------------------------------------------------
# Project-specific: BuildConfig (read at runtime by the About card).
# -------------------------------------------------------------------------
-keep class com.example.BuildConfig { *; }

# -------------------------------------------------------------------------
# WorkManager workers
# Workers are instantiated by class name via reflection. Each worker
# class must be keepable.
# -------------------------------------------------------------------------
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger
-keep class androidx.work.OverwritingInputMerger { *; }
-keep class androidx.work.ArrayCreatingInputMerger { *; }

# -------------------------------------------------------------------------
# Compose preview / tooling — only present in debug. Silence the
# unresolved references when shrinking a release build.
# -------------------------------------------------------------------------
-dontwarn androidx.compose.ui.tooling.**

# -------------------------------------------------------------------------
# Apache POI transitive deps that ship with log4j2 — referenced for
# OSGi-runtime paths POI never takes on Android. Surfaced verbatim by
# R8's `missing_rules.txt` after the first build; they're load-bearing
# silence rules, not real keeps.
# -------------------------------------------------------------------------
-dontwarn aQute.bnd.annotation.spi.ServiceConsumer
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn org.osgi.framework.Bundle
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.framework.FrameworkUtil
-dontwarn org.osgi.framework.ServiceReference

# -------------------------------------------------------------------------
# Keep android.util.Log.d/v/i/w/e calls intact.
#
# `proguard-android-optimize.txt` (the AGP-provided base) declares the
# Log class as `-assumenosideeffects` — R8 then strips every Log.d/v
# call as dead code. That makes diagnostics from minified builds
# impossible. We need the breadcrumbs from `MealAnalysis`, `CHAT_ORCH`,
# `CHAT_IMG`, `OrphanImgCleanup`, `SHD#*` tags during smoke testing
# (and arguably also in production crash investigations).
#
# This rule re-introduces the Log methods to the keep set, overriding
# the `assumenosideeffects` directive. Future toggle: split debug vs
# release proguard files if log noise in production becomes an issue.
# -------------------------------------------------------------------------
-keep class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# -------------------------------------------------------------------------
# ML Kit Pose Detection
# Firebase component discovery instantiates registrars by reflection via
# no-arg constructors. R8 strips them because nothing calls them directly.
# Without this, PoseDetection.getClient() throws LazyInstanceMap NPE.
# Crash trace (2026-09-30, v394/395):
#   NoSuchMethodException: PoseRegistrar.<init> []
#   → NullPointerException: LazyInstanceMap.get() on null
# -------------------------------------------------------------------------
-keep class com.google.mlkit.vision.pose.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.mlkit.vision.segmentation.** { *; }
-keep class com.google.mlkit.common.** { *; }
-keepclassmembers class com.google.mlkit.** {
    <init>();
    <init>(...);
}
-dontwarn com.google.mlkit.**
