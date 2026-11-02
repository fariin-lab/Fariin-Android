package com.fariin.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.MessageDecryptor
import com.fariin.app.data.NoDecryptor
import com.fariin.app.ui.auth.SignInFlow
import com.fariin.app.ui.chats.ChatsUiState
import com.fariin.app.ui.chats.ChatsViewModel
import com.fariin.app.ui.debug.DemoChats
import com.fariin.app.ui.photo.FariinPhotoFetcher
import com.fariin.app.ui.shell.MainShell
import com.fariin.app.ui.theme.FariinTheme

class MainActivity : ComponentActivity() {
    private val auth by lazy { AuthRepository() }

    /** TODO: the crypto package's decryptor, once it is ported. */
    private val decryptor: MessageDecryptor = NoDecryptor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // Debug builds only: `adb shell am start -n com.fariin.app/.MainActivity --ez demo true`
        // shows the shell with made-up chats, no sign-in and no Firebase.
        val demo = debuggable && intent.getBooleanExtra("demo", false)
        setContent {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(FariinPhotoFetcher.Factory()) }
                    .build()
            }
            FariinTheme {
                if (demo) {
                    MainShell(DemoChats.ME, ChatsUiState(loading = false, chats = DemoChats.chats()),
                        DemoChats.decryptor, onSignOut = { finish() })
                } else {
                    val initial = remember { auth.currentUser?.uid }
                    val uid by auth.uid.collectAsState(initial = initial)
                    val me = uid
                    if (me == null) SignInFlow(auth) else SignedIn(me)
                }
            }
        }
    }

    @Composable
    private fun SignedIn(me: String) {
        val vm: ChatsViewModel = viewModel(key = "chats-$me") { ChatsViewModel(me) }
        val chats by vm.state.collectAsState()
        MainShell(me, chats, decryptor, onSignOut = { auth.signOut() })
    }
}
