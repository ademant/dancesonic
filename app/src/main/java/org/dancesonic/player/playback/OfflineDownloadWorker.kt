package org.dancesonic.player.playback

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.dancesonic.player.DanceSonicApp
import java.util.concurrent.TimeUnit

/** Asks for a download pass under the network the settings allow. */
fun interface DownloadScheduler {
    fun schedule(allowMobileData: Boolean)
}

/**
 * Runs the offline download passes in WorkManager (#139), so they survive the app's process and wait for the right
 * network. The work is unique: a request while one waits or runs is merged into it, and the pass it runs picks up what
 * was asked for. A change of the mobile data setting replaces the waiting work, so its network constraint follows.
 */
class WorkDownloadScheduler(
    private val workManager: () -> WorkManager,
) : DownloadScheduler {
    @Volatile
    private var lastAllowMobileData: Boolean? = null

    override fun schedule(allowMobileData: Boolean) {
        val network = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request =
            OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        val changed = lastAllowMobileData != null && lastAllowMobileData != allowMobileData
        lastAllowMobileData = allowMobileData
        // REPLACE also stops a pass that runs now; the next one continues from what is on disk.
        val policy = if (changed) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager().enqueueUniqueWork(WORK_NAME, policy, request)
    }

    companion object {
        /** The unique name of the download work. */
        const val WORK_NAME = "offline-downloads"
        private const val BACKOFF_SECONDS = 60L
    }
}

/**
 * Runs the passes of the app's [OfflineDownloads]. A pass that failed or found the network unusable is retried with
 * backoff; one that finished, or has no server to download from, ends the work.
 */
class OfflineDownloadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val passes: () -> DownloadPasses?,
) : CoroutineWorker(context, params) {
    /** The constructor WorkManager uses: the passes come from the app container. */
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        { (context.applicationContext as? DanceSonicApp)?.container?.offline },
    )

    override suspend fun doWork(): Result {
        val downloads = passes() ?: return Result.success()
        return if (downloads.runPasses()) Result.retry() else Result.success()
    }
}
