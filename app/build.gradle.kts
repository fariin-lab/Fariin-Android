plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
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

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.lazysodium.java)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.play.services)
}
