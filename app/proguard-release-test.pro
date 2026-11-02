# Only for `./gradlew connectedReleaseAndroidTest -PtestRelease` (never in a shipped build).
# The test APK shares the app's copy of Kotlin, coroutines and the classes it tests, so those must
# survive R8 with their names. Nothing here touches JNA, lazysodium or WebRTC: those stay under the
# shipped rules in proguard-rules.pro, which is the thing this run proves.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.fariin.app.crypto.** { *; }
-keep class com.fariin.app.media.** { *; }
-keep class com.fariin.app.call.** { *; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
