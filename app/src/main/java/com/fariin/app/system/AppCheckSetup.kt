package com.fariin.app.system

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * App Check, as iOS AppCheckSetup does it: builds for users prove themselves with Google Play
 * Integrity; test builds use the debug provider (its token is printed to the log once, and must
 * be added in the Firebase console before enforcement is switched on). Nothing is enforced on
 * the server yet, so a missing or refused token changes nothing today.
 */
object AppCheckSetup {
    fun install(debuggable: Boolean) {
        runCatching {
            val appCheck = FirebaseAppCheck.getInstance()
            if (debuggable) {
                // Reflection, so the debug provider stays out of the builds users get.
                val factory = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                    .getMethod("getInstance").invoke(null) as com.google.firebase.appcheck.AppCheckProviderFactory
                appCheck.installAppCheckProviderFactory(factory)
            } else {
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            }
            appCheck.setTokenAutoRefreshEnabled(true)
        }
    }
}
