package com.fariin.app.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import com.fariin.app.data.CryptoHub
import com.fariin.app.device.DeviceRegistry
import com.fariin.app.push.PushRouter
import com.fariin.app.ui.photo.FariinPhotoFetcher
import com.fariin.app.ui.theme.FariinTheme
import com.fariin.app.ui.thread.ThreadRoute
import com.google.firebase.auth.FirebaseAuth

/**
 * A chat in an Android 11+ bubble: the same chat screen as the app (ThreadRoute: messages,
 * composer, sends, read marks), hosted on its own in the small floating window. Back collapses
 * the bubble. While it is expanded the chat counts as open, so its new messages do not post a
 * second notification (the same rule as the chat open in the app).
 */
class BubbleActivity : ComponentActivity() {
    private var cid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = FirebaseAuth.getInstance().currentUser?.uid
        val chat = intent.getStringExtra(PushRouter.EXTRA_OPEN_CID)
        if (me == null || chat == null) { finish(); return }
        cid = chat
        DeviceRegistry.init(this)
        enableEdgeToEdge()
        val crypto = CryptoHub.get(applicationContext)
        setContent {
            FariinTheme {
                ThreadRoute(chat, me, crypto, onBack = { moveTaskToBack(true) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The notification stays: cancelling it would also remove the bubble.
        cid?.let { PushRouter.activeChatId = it }
    }

    override fun onPause() {
        if (PushRouter.activeChatId == cid) PushRouter.activeChatId = null
        super.onPause()
    }

    companion object {
        fun intent(c: Context, cid: String): Intent = Intent(c, BubbleActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // A distinct data per chat: each chat is its own bubble (documentLaunchMode always).
            .setData(Uri.parse("fariin-bubble://chat/$cid"))
            .putExtra(PushRouter.EXTRA_OPEN_CID, cid)
    }
}
