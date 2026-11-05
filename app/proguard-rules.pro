# Fariin release build: R8 keep rules.
# Most libraries ship their own consumer rules inside their AAR (Firebase, Media3, Coil, AndroidX,
# Compose, LiveKit). The rules below cover what those miss or what this app reaches by name.

# ---- Crash reports readable after shrinking (Crashlytics) ----
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
-keep public class * extends java.lang.Exception

# ---- libsodium through lazysodium + JNA ----
# JNA finds Java methods, fields and Structure layouts from native code by name, and calls back
# into Java (Callback interfaces). Nothing in it may be renamed or removed.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
-keep class com.goterl.lazysodium.** { *; }
-keep class com.goterl.resourceloader.** { *; }
# JNA's desktop helpers (AWT, Swing) are never used on Android.
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn com.sun.jna.**
-dontwarn org.slf4j.**

# ---- WebRTC (LiveKit's build, package livekit.org.webrtc) and LiveKit ----
# The native engine calls Java classes and methods by name through JNI.
-keep class livekit.org.webrtc.** { *; }
-keep class org.webrtc.** { *; }
-keep class io.livekit.android.** { *; }
# LiveKit's signalling messages are protobuf-lite classes (package livekit); protobuf-lite reads
# their fields by reflection.
-keep class livekit.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn org.webrtc.**
-dontwarn livekit.org.webrtc.**

# ---- Sign-in: Credential Manager, Sign in with Google ----
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# ---- Firebase ----
# The app reads Firestore documents as maps (no toObject), so no model classes need keeping.
# Callables answer with plain maps too.

# ---- kotlinx.serialization (used inside LiveKit) ----
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> { static <1>$Companion Companion; }

# ---- CoroutineSafety: found by kotlinx.coroutines through META-INF/services ----
-keep class com.fariin.app.CoroutineSafety { <init>(); *; }
