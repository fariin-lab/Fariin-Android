package com.fariin.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** The automatic chat backup: daily or weekly, on Wi-Fi unless mobile data is allowed. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.success()
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid != uid) return Result.success()
        return when (ChatBackup.backUp(applicationContext, uid, automatic = true)) {
            is ChatBackup.Result.Failed -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private fun name(uid: String) = "chat-backup-$uid"
        fun schedule(ctx: Context, uid: String) {
            val wm = WorkManager.getInstance(ctx)
            val f = ChatBackup.frequency(ctx, uid)
            if (!ChatBackup.ENABLED || f == "off") { wm.cancelUniqueWork(name(uid)); return }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(if (f == "weekly") 7L else 1L, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (ChatBackup.mobileData(ctx, uid)) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true).build())
                .setInputData(androidx.work.workDataOf("uid" to uid)).build()
            wm.enqueueUniquePeriodicWork(name(uid), ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
