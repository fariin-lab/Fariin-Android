package com.fariin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import com.fariin.app.data.AuthRepository
import com.fariin.app.ui.auth.SignInFlow
import com.fariin.app.ui.photo.FariinPhotoFetcher
import com.fariin.app.ui.theme.FariinTheme

class MainActivity : ComponentActivity() {
    private val auth by lazy { AuthRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(FariinPhotoFetcher.Factory()) }
                    .build()
            }
            FariinTheme {
                val initial = remember { auth.currentUser?.uid }
                val uid by auth.uid.collectAsState(initial = initial)
                if (uid == null) {
                    SignInFlow(auth)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Signed in") }
                }
            }
        }
    }
}
