import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  id("kotlin-parcelize")
}

// Build timestamp surfaced in BuildConfig.BUILD_DATE so the About card on the
// Reports tab can show "Build 2026-06-15 22:35". Computed at configuration
// time with minute granularity per Hugo's request — the timestamp now also
// includes the hour, so two builds within the same day are distinguishable
// in the About panel. Trade-off: config-cache now invalidates on every build
// (was once-per-day), but this card is the only consumer and the cost is
// negligible compared to the diagnostic value of seeing the build minute.
val buildDateString: String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())

// Auto-bumped versioning. The current `versionCode` and major number live in
// `app/version.properties`; `versionName` is derived as "${major}.${code}" so
// a single source of truth drives both. The `bumpVersionCode` task below
// rewrites the file before any APK is packaged, so every shipped build has a
// unique, monotonic versionCode without manual edits.
val versionPropsFile = file("version.properties")
val versionProps = Properties().apply {
  if (versionPropsFile.exists()) {
    val s = FileInputStream(versionPropsFile)
    try { load(s) } finally { s.close() }
  }
}
val currentVersionCode: Int = (versionProps.getProperty("versionCode") ?: "1").toInt()
val versionMajor: String = versionProps.getProperty("versionMajor") ?: "1"
val derivedVersionName: String = "$versionMajor.$currentVersionCode"

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.geminichat.rvqzmx"
    minSdk = 29
    targetSdk = 36
    versionCode = currentVersionCode
    versionName = derivedVersionName

    buildConfigField("String", "BUILD_DATE", "\"$buildDateString\"")

    // Chat provider router (plan Phase 6). When false — the default,
    // everywhere — the chat send path is byte-for-byte today's Gemini-only
    // fleet cascade (`streamReply` + `blockingReply`). When true, a normal
    // chat turn routes through `ChatProviderRouter` (Groq → RunInfra →
    // Gemini-cascade). Flipping this flag off is the instant rollback: no
    // data touched, no schema change. Keys are seeded on-device via adb, so
    // this flag — not key presence — is the gate.
    buildConfigField("boolean", "CHAT_ROUTER_ENABLED", "true")
    // Vision ensemble mode. "GEMINI_ONLY" = comportamento anterior (Gemini directo).
    // "BITDEER_THEN_GEMINI" = Qwen3-VL-32B/Bitdeer primário, Gemini challenger.
    buildConfigField("String", "VISION_ENSEMBLE_MODE", "\"BITDEER_THEN_GEMINI\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      // R8 enabled — shrinking + obfuscation + optimization. Explicit
      // keep rules live in `proguard-rules.pro` for the reflection-heavy
      // libraries (Moshi, Room, Retrofit/OkHttp, Samsung Health AAR).
      // Resource shrinking piggy-backs on minify and trims unused
      // drawables / strings / colors.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Mirror release minify so the debug APK Hugo installs is
      // representative of what release will ship — keeps the ProGuard
      // rules honest. Without this, an obfuscation crash would only
      // surface in a release pipeline Hugo can't easily build locally
      // (no `my-upload-key.jks` keystore in the worktree).
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // Not used directly (this is a pure ComponentActivity + Compose app), but
  // pins the transitive androidx.fragment up from 1.1.0 to >= 1.3.0 so the
  // fatal lint check `InvalidFragmentVersionForActivityResult` passes on the
  // release build. registerForActivityResult on ComponentActivity is not
  // affected by the old-Fragment bug, but lintVitalRelease can't prove that.
  implementation(libs.androidx.fragment)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // Build 1.5 (2026-06-27): location-only — coarse city-level lookup
  // for the IPMA weather context injected into the chat prompt. See
  // [WeatherFetcher] / AndroidManifest.xml ACCESS_COARSE_LOCATION block.
  implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation("com.google.code.gson:gson:2.9.0")
  // Runtime for kotlin-parcelize: required by samsung-health-data-api-1.1.0.aar which uses
  // `kotlinx.parcelize.Parceler` for cross-process IPC of its request/response types.
  // Without this, the app crashes at first SDK call with NoClassDefFoundError on
  // kotlinx/parcelize/Parceler. Version must match the Kotlin version (2.2.10) used by
  // the project's kotlin-parcelize plugin.
  implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.2.10")
  // Markdown rendering for chat bubbles. Compose-richtext is published on Maven
  // Central as KMP artifacts; the Android variant resolves automatically. The
  // -ui-material3 module provides a Compose `RichText { ... }` themed against
  // M3, and -commonmark gives the `Markdown(text)` extension that parses bold,
  // italic, headings, lists, tables, code blocks, links, and horizontal rules.
  implementation("com.halilibo.compose-richtext:richtext-ui-material3:1.0.0-alpha02")
  implementation("com.halilibo.compose-richtext:richtext-commonmark:1.0.0-alpha02")
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
  // SAF helper — DocumentFile.fromTreeUri / findFile / createFile usados pelo
  // exporter Drive para escrever diario_nutricao.xlsx na pasta que o utilizador
  // selecciona uma vez via OpenDocumentTree.
  implementation("androidx.documentfile:documentfile:1.0.1")
  // EXIF-aware bitmap rotation for chat-attached photos. JPEGs from the camera
  // carry an `Orientation` tag that `BitmapFactory.decodeFile` ignores; the
  // chat thumbnail loaders consume this to apply the matching Matrix rotation
  // (see [ChatImageStore.decodeBitmapWithOrientation]).
  implementation("androidx.exifinterface:exifinterface:1.3.7")
  // Apache POI para gerar .xlsx. Excluímos xml-apis (conflita com Android) e
  // xmlgraphics (não usado, puxa Batik). log4j-api FICA: POI 5.x faz
  // LogManager.getLogger() durante a inicialização das classes XSSF — sem ele
  // o app crasha com ClassNotFoundException ao instanciar XSSFWorkbook.
  implementation("org.apache.poi:poi:5.2.5") {
    exclude(group = "xml-apis")
  }
  implementation("org.apache.poi:poi-ooxml:5.2.5") {
    exclude(group = "xml-apis")
    exclude(group = "org.apache.xmlgraphics")
  }
  // WorkManager — used by DriveBackupWorker to run a daily background backup of
  // diario_nutricao.xlsx into the user's chosen Drive folder. PeriodicWorkRequest
  // (1 day) plus a catch-up OneTimeWorkRequest enqueued from MainActivity when
  // the app opens on a new day.
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

ksp {
  arg("room.schemaLocation", "${projectDir}/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

// Auto-bump versionCode antes de cada `package*` (debug e release).
//
// O `versionCode` que o gradle leu na fase de configuração já foi gravado no
// AGP DSL — incrementar agora só afecta o *próximo* build. Por isso a task
// roda DEPOIS do package, escrevendo `current+1` em version.properties; o
// build seguinte carrega o novo valor e pode entregar o APK incrementado.
//
// Config-cache compat: o caminho passa via Provider<RegularFile> (serializável
// pelo cache), não como String capturada do script. A task só toca em ficheiros
// e logger, ambos suportados.
abstract class BumpVersionCodeTask : DefaultTask() {
  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.ABSOLUTE)
  abstract val versionPropsFile: org.gradle.api.file.RegularFileProperty

  @org.gradle.api.tasks.TaskAction
  fun bump() {
    val f = versionPropsFile.get().asFile
    if (!f.exists()) {
      logger.warn("version.properties not found at ${f.absolutePath} — skipping bump.")
      return
    }
    val props = Properties()
    val input = FileInputStream(f)
    try { props.load(input) } finally { input.close() }
    val current = (props.getProperty("versionCode") ?: "0").toInt()
    val major = props.getProperty("versionMajor") ?: "1"
    val next = current + 1
    props.setProperty("versionCode", next.toString())
    val output = FileOutputStream(f)
    try { props.store(output, "Auto-bumped by :app:bumpVersionCode after build $current.") } finally { output.close() }
    logger.lifecycle("→ versionCode bumped: $current → $next (versionName next build: $major.$next)")
  }
}

val bumpVersionCode = tasks.register<BumpVersionCodeTask>("bumpVersionCode") {
  description = "Increments versionCode in app/version.properties for the next build."
  versionPropsFile.set(layout.projectDirectory.file("version.properties"))
}

afterEvaluate {
  // Para cada flavour de buildType, pendurar o bump no fim do package task.
  // assembleDebug → packageDebug → bumpVersionCode (finalizedBy).
  listOf("packageDebug", "packageRelease").forEach { taskName ->
    tasks.findByName(taskName)?.finalizedBy(bumpVersionCode)
  }
}
