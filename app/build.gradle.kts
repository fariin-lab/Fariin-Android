import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    // Collects every library in the app with its licence at build time, for Settings > Help &
    // About > Open-source licences (Apache-2.0 and similar licences ask the app to carry them).
    id("com.mikepenz.aboutlibraries.plugin") version "12.2.4"
}

android {
    namespace = "com.fariin.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fariin.app"
        resValue("bool", "fariin_debug_appcheck", "false")
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The map's key: the app's own key from google-services.json (already in the repo), so no
        // second key is committed. Empty when the file is missing; the map then shows no tiles.
        manifestPlaceholders["mapsApiKey"] = run {
            val f = file("google-services.json")
            if (!f.exists()) return@run ""
            @Suppress("UNCHECKED_CAST")
            val clients = (groovy.json.JsonSlurper().parse(f) as Map<String, Any?>)["client"] as? List<Map<String, Any?>> ?: emptyList()
            val mine = clients.firstOrNull {
                ((it["client_info"] as? Map<*, *>)?.get("android_client_info") as? Map<*, *>)?.get("package_name") == "com.fariin.app"
            } ?: clients.firstOrNull()
            ((mine?.get("api_key") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("current_key") as? String ?: ""
        }
    }
    // On-device tests run against the debug build. `-PtestRelease` runs them against the minified
    // release build instead, to prove R8 kept what libsodium/JNA and WebRTC need:
    // ./gradlew connectedReleaseAndroidTest -PtestRelease
    testBuildType = if (project.hasProperty("testRelease")) "release" else "debug"

    // Release signing: read from keystore.properties (git-ignored, never committed) when it exists.
    // Today it points at a TEST upload key in %USERPROFILE%\.fariin; drop the real upload key's
    // four lines in later. With no file, the release build comes out unsigned.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (project.hasProperty("testRelease")) proguardFile("proguard-release-test.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            // The R8 mapping goes to Crashlytics only when asked: ./gradlew bundleRelease -PuploadMapping=true
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = project.hasProperty("uploadMapping")
            }
        }
        // Release code signed with the debug key, allowed to use the LOCAL test backend: for
        // measuring start-up, memory and data on the low-end emulator. Never uploaded anywhere.
        // ./gradlew assembleBenchmark
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            configure<CrashlyticsExtension> { mappingFileUploadEnabled = false }
        }
        // The owner's Pixel at the REAL speed (owner 2026-11-09: "the app is extremely laggy"): release
        // code (R8, AOT, baseline profile) signed with the debug key, so it installs over the debug
        // build with the same data and keeps that install's App Check debug token. Real backend.
        // Never uploaded anywhere. ./gradlew assemblePixelfast
        create("pixelfast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFile("proguard-pixelfast.pro")
            resValue("bool", "fariin_debug_appcheck", "true")
            configure<CrashlyticsExtension> { mappingFileUploadEnabled = false }
        }
    }
    sourceSets.getByName("benchmark").res.srcDir("src/debug/res")

    // One APK per CPU type for release side-loading (Play uses the AAB and splits by itself).
    // Debug builds keep the single app-debug.apk the scripts install. AGP cannot shrink resources
    // for split APKs and an AAB in the same run, so `assembleRelease bundleRelease` together gives
    // one universal APK + the AAB; `assembleRelease` alone gives the per-CPU APKs.
    val startTasks = gradle.startParameter.taskNames.map { it.lowercase() }
    splits {
        abi {
            isEnable = startTasks.any { "assemble" in it && "release" in it } && startTasks.none { "bundle" in it }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)
    // Crash reports, as iOS (Telemetry.swift): stack traces and phone facts only, never message text.
    implementation(libs.firebase.crashlytics)
    // App Check, as iOS sends it (AppCheckSetup.swift): Play Integrity in builds for users, the
    // debug provider in test builds. Nothing is enforced on the server yet.
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    // Create Sticker: the subject lifted on the phone (iOS uses Vision's foreground mask).
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    "pixelfastImplementation"("com.google.firebase:firebase-appcheck-debug")
    // The launch screen (Android 12 API, back-ported), and the app-wide foreground/background signal.
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.process)
    // Installs the Baseline Profile (src/main/baseline-prof.txt + the Compose libraries' own) on
    // side-loaded installs too, so the first start on a cheap phone runs compiled code, not the interpreter.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Notification Reply / Mark as read run as short expedited work, also with the app closed (system/).
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // A link tapped in a message opens in a Custom Tab, the in-app browser (ui/thread/BubbleLinks.kt).
    implementation("androidx.browser:browser:1.4.0")
    implementation(libs.kotlinx.coroutines.play.services)
    // libsodium, the library the iOS app's Crypto.swift uses (swift-sodium), so both sides speak
    // the same box/secretbox formats.
    // lazysodium-android drags in the plain JNA jar; Android needs the JNA aar (with its .so files)
    // instead, so the jar is excluded here and the aar is added below.
    implementation(libs.lazysodium.android) {
        artifact { type = "aar" }
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.coil.compose)
    // Group photos can be plain https links; profile photos go through FariinPhotoFetcher.
    implementation(libs.coil.network.okhttp)
    // Received GIFs and animated sticker WebP (iOS AnimatedGifView / RowStickerView): Coil's own
    // animated decoders, Apache 2.0.
    implementation("io.coil-kt.coil3:coil-gif:3.2.0")
    // Animated .tgs stickers (iOS TGSEngine on rlottie): Lottie for Android, Apache 2.0.
    implementation("com.airbnb.android:lottie-compose:6.6.7")
    // Voice notes, videos and the full-screen video player (Media3, the Android media stack).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // Chat video send: 540p H.264 like iOS VideoTranscoder (media worker).
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
    // Story camera (ui/stories/StoryCamera.kt): CameraX preview, photo and video capture.
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // My QR code and the scanner (ui/settings/QrPages.kt): QR encode/decode only, Apache 2.0.
    implementation("com.google.zxing:core:3.5.3")
    // Calls: LiveKit for group rooms, and its bundled WebRTC build (package livekit.org.webrtc) for
    // 1:1 calls too, so the app carries one WebRTC engine, not two.
    implementation(libs.livekit.android)
    // Sign-in: passkeys and Sign in with Google through the system Credential Manager (auth/).
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Glass bottom bar: background blur of only the strip behind the bar (RenderEffect on 12+).
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    // Liquid glass for the tab bar (Apache-2.0, kyant0/AndroidLiquidGlass). 1.0.0 is the line that
    // matches our Compose 1.9 / Kotlin 2.2; 2.x needs Compose 1.12 + Kotlin 2.4.
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("com.mikepenz:aboutlibraries-core:12.2.4")
    // Home screen widget (system/FariinWidget.kt).
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Maps for Share Location and the location card (ui/thread/LocationMaps.kt): the phone's own map
    // engine in Play Services, as iOS uses the system map. Only a thin client ships in the APK.
    implementation("com.google.android.gms:play-services-maps:20.1.0")
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // The real org.json for the local-cache codec tests (Android ships it; plain JVM tests do not).
    testImplementation("org.json:json:20240303")
    testImplementation(libs.lazysodium.java)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.play.services)
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}
