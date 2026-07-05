package jp.viastrasse.cabinet.preview

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.viastrasse.cabinet.data.CabinetRepository

class PreviewWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return runCatching {
            CabinetRepository(applicationContext).processPreviewQueue(limit = 50)
        }.fold(
            onSuccess = { report ->
                if (report.remaining > 0) {
                    enqueue(applicationContext)
                }
                Result.success()
            },
            onFailure = {
                Result.retry()
            },
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "cabinet-preview-queue"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PreviewWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
