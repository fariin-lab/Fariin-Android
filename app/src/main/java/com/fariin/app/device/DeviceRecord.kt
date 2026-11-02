package com.fariin.app.device

/**
 * The pure parts of the device record (Kulan-iOS/Kulan/Services/DeviceRegistry.swift), kept free of
 * Android and Firebase so the unit tests can check them.
 *
 * The record lives at `users/{uid}/devices/{deviceId}` with the iOS field names:
 *   model       what the Devices list shows ("Pixel 8"; iOS writes "iPhone")
 *   hardware    for support, not shown ("Google,husky"; iOS writes "iPhone16,1")
 *   os          "Android 16" (iOS: "iOS 18.1")
 *   appVersion  "1.0 (1)", the iOS "short (build)" shape
 *   platform    "android" (Android only; iOS writes none and the iPhone's list ignores it)
 *   lastSeenAt  server time, every write
 *   createdAt   server time, only when the record is new (the rules demand exactly that)
 *   authTime    this sign-in's `auth_time` in seconds, only when the record is new
 *   fcmToken    this phone's push token, so a remote sign-out can strip exactly it
 */
object DeviceRecord {
    const val PLATFORM = "android"

    /** The iPhone's DeviceNames maps "x86_64" / "arm64" to "Simulator"; an Android hardware string
     *  never equals those, so it always falls back to [model]. */
    fun hardware(manufacturer: String, device: String): String {
        val h = listOf(manufacturer.trim(), device.trim()).filter { it.isNotEmpty() }.joinToString(",")
        return if (h == "x86_64" || h == "arm64" || h.isEmpty()) "android,$h" else h
    }

    fun os(release: String): String = "Android ${release.ifBlank { "?" }}"

    fun appVersion(versionName: String?, versionCode: Long): String =
        "${versionName?.ifBlank { null } ?: "?"} ($versionCode)"

    /** The fields every registration write carries (lastSeenAt and the new-record fields are added
     *  by the caller, they need Firebase types). */
    fun baseFields(model: String, hardware: String, os: String, appVersion: String): Map<String, Any> = mapOf(
        "model" to model.ifBlank { "Android phone" },
        "hardware" to hardware,
        "os" to os,
        "appVersion" to appVersion,
        "platform" to PLATFORM,
    )

    /**
     * What a registration that read the record from the SERVER decides (DeviceRegistry.swift:173-184):
     * no record but this install registered it before means it was taken away (another phone
     * signed in, or this one was signed out from elsewhere).
     */
    enum class Check { REVOKED, NEW_RECORD, EXISTING }

    fun check(existsOnServer: Boolean, hadRegistered: Boolean): Check = when {
        !existsOnServer && hadRegistered -> Check.REVOKED
        !existsOnServer -> Check.NEW_RECORD
        else -> Check.EXISTING
    }

    /** Why this phone was signed out, read from the server's notes (RootView.swift:285-343). */
    enum class Reason { SIGNED_OUT_ELSEWHERE, NEW_PHONE, DEVICE_LIMIT, ACCOUNT_SUSPENDED }

    fun reason(replaced: Boolean, refusedForLimit: Boolean): Reason = when {
        replaced -> Reason.NEW_PHONE
        refusedForLimit -> Reason.DEVICE_LIMIT
        else -> Reason.SIGNED_OUT_ELSEWHERE
    }

    const val MAX_DEVICES = 5

    /** The iOS alert, word for word (RootView.swift:330-343). */
    fun noticeTitle(r: Reason): String = when (r) {
        Reason.ACCOUNT_SUSPENDED -> "Account suspended"
        Reason.NEW_PHONE -> "Logged in on another phone"
        Reason.DEVICE_LIMIT -> "Too many devices"
        Reason.SIGNED_OUT_ELSEWHERE -> "Logged out"
    }

    fun noticeMessage(r: Reason): String = when (r) {
        Reason.ACCOUNT_SUSPENDED ->
            "This account can't be used right now. If you think this is a mistake, contact us at fariin.com/help."
        Reason.NEW_PHONE ->
            "Your account was logged in on another phone, so it was logged out here. Fariin works on one phone at a time. To use it here again, log in."
        Reason.DEVICE_LIMIT ->
            "Your account is already logged in on $MAX_DEVICES devices. On one of them, open Settings > Devices and log out a device you no longer use, then log in here again."
        Reason.SIGNED_OUT_ELSEWHERE -> "This device was logged out from another device."
    }
}
