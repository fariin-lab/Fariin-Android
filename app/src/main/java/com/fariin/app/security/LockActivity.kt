package com.fariin.app.security

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.FariinTheme

/**
 * The App Lock cover: a plain page with a lock and "Unlock Fariin", and the phone's own unlock
 * prompt over it (BiometricPrompt with the device PIN allowed; KeyguardManager's confirm page on
 * Android 9). Back sends Fariin to the background instead of past the lock.
 */
class LockActivity : ComponentActivity() {
    private val confirm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) done()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        setContent {
            FariinTheme {
                val c = Fariin.colors
                Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = c.label, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Fariin is locked", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "Unlock Fariin", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.bg,
                            modifier = Modifier.clip(CircleShape).background(c.label).clickable { ask() }
                                .padding(horizontal = 28.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
        if (savedInstanceState == null) ask()
    }

    private fun done() {
        AppLock.onUnlocked()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun ask() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) { done(); return }     // the phone lock was removed: nothing to ask
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Unlock Fariin", null)
            if (i == null) done() else confirm.launch(i)
            return
        }
        val b = BiometricPrompt.Builder(this).setTitle("Unlock Fariin")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        }
        b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { done() }
        })
    }
}
