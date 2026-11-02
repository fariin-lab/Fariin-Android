import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "com.fariin.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fariin.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }

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
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)
    // Crash reports, as iOS (Telemetry.swift): stack traces and phone facts only, never message text.
    implementation(libs.firebase.crashlytics)
    // The launch screen (Android 12 API, back-ported), and the app-wide foreground/background signal.
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.process)
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
    // Voice notes, videos and the full-screen video player (Media3, the Android media stack).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // Calls: LiveKit for group rooms, and its bundled WebRTC build (package livekit.org.webrtc) for
    // 1:1 calls too, so the app carries one WebRTC engine, not two.
    implementation(libs.livekit.android)
    // Sign-in: passkeys and Sign in with Google through the system Credential Manager (auth/).
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // The real org.json for the local-cache codec tests (Android ships it; plain JVM tests do not).
    testImplementation("org.json:json:20240303")
    testImplementation(libs.lazysodium.java)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.play.services)
}
