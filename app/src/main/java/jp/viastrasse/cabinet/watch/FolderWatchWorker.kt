package jp.viastrasse.cabinet.watch

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.viastrasse.cabinet.data.CabinetFiles
import jp.viastrasse.cabinet.data.CabinetRepository
import jp.viastrasse.cabinet.preview.PreviewWorker
import java.io.File
import java.util.concurrent.TimeUnit

class FolderWatchWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return runCatching {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val folders = prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty()
            if (folders.isEmpty()) {
                return@runCatching 0
            }
            val repository = CabinetRepository(applicationContext)
            val inboxDir = File(applicationContext.filesDir, "inbox/watch").apply { mkdirs() }
            var imported = 0
            val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
            folders.forEach { folder ->
                val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folder)) ?: return@forEach
                root.listFiles()
                    .filter { it.isFile }
                    .forEach { document ->
                        val marker = markerFor(document)
                        if (marker in seen) {
                            return@forEach
                        }
                        val displayName = sanitizeFileName(document.name ?: "document")
                        val destination = uniqueDestination(inboxDir, displayName)
                        applicationContext.contentResolver.openInputStream(document.uri).use { input ->
                            requireNotNull(input) { "Input stream is null." }
                            destination.outputStream().use { output -> input.copyTo(output) }
                        }
                        repository.registerFile(
                            path = destination.absolutePath,
                            displayName = displayName,
                            mimeType = document.type ?: "application/octet-stream",
                            size = destination.length(),
                            sourceKind = "watch-folder",
                            note = "監視フォルダから自動取り込み",
                        )
                        seen += marker
                        imported += 1
                    }
            }
            prefs.edit().putStringSet(KEY_SEEN, seen).apply()
            if (imported > 0) {
                PreviewWorker.enqueue(applicationContext)
            }
            imported
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val PREFS_NAME = "cabinet-folder-watch"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_SEEN = "seen"
        private const val UNIQUE_PERIODIC_WORK_NAME = "cabinet-folder-watch-periodic"
        private const val UNIQUE_NOW_WORK_NAME = "cabinet-folder-watch-now"

        fun rememberFolder(context: Context, treeUri: Uri, root: DocumentFile? = null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val folders = prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toMutableSet()
            folders += treeUri.toString()
            val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
            root?.listFiles()
                ?.filter { it.isFile }
                ?.forEach { seen += markerFor(it) }
            prefs.edit()
                .putStringSet(KEY_FOLDERS, folders)
                .putStringSet(KEY_SEEN, seen)
                .apply()
        }

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<FolderWatchWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<FolderWatchWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun markerFor(document: DocumentFile): String {
            return "${document.uri}|${document.lastModified()}|${document.length()}"
        }

        private fun uniqueDestination(directory: File, displayName: String): File =
            CabinetFiles.uniqueDestination(directory, displayName)

        private fun sanitizeFileName(value: String): String =
            CabinetFiles.sanitizeFileName(value)
    }
}
