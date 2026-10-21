package com.ntolb.syncassist

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors

class LocalSyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            try {
                val store = StateStore(this)
                val state = store.load()
                if (!SyncSettingsPolicy.shouldScheduleBackground(state.settings.syncPattern)) {
                    jobFinished(params, false)
                    return@execute
                }
                val engine = LocalSyncEngine(contentResolver, store, VersionBackup.forContext(this))
                val profileId = params.extras.getString(EXTRA_PROFILE_ID)
                val setId = params.extras.getString(EXTRA_SET_ID)
                val result = if (profileId != null && setId != null) {
                    engine.syncSet(profileId, setId)
                } else {
                    engine.syncAll()
                }
                if (profileId == null || setId == null) SyncRunTracker.record(this)
                result.blocked.forEach { blocked ->
                    blocked.retryAt?.let { retryAt ->
                        try {
                            scheduleRetry(this, blocked, retryAt)
                        } catch (error: IOException) {
                            Log.e(TAG, "Could not schedule a conflict retry for ${blocked.setName}.", error)
                        }
                    }
                }
                AppNotifier.sync(
                    this,
                    state.settings,
                    SyncNotificationKind.AUTOMATIC,
                    "Automatic sync complete. Updated ${result.updatedSets} set(s).",
                )
                if (result.blocked.isNotEmpty() && state.settings.gainFocusOnConflict) {
                    openForConflict(result.blocked.first())
                }
            } catch (error: IOException) {
                Log.e(TAG, "Local file sync failed.", error)
            } catch (error: SecurityException) {
                Log.e(TAG, "A file permission expired; sync was not completed.", error)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Could not complete the scheduled local sync.", error)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private fun openForConflict(blocked: BlockedSet) {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_CONFLICT_SET_ID, blocked.setId)
                    .putExtra(EXTRA_CONFLICT_SET_NAME, blocked.setName),
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Android prevented opening the app for a sync conflict.", error)
        }
    }

    companion object {
        private const val TAG = "FileSync"
        const val EXTRA_PROFILE_ID = "sync_profile_id"
        const val EXTRA_SET_ID = "sync_set_id"
        const val EXTRA_CONFLICT_SET_ID = "conflict_set_id"
        const val EXTRA_CONFLICT_SET_NAME = "conflict_set_name"
        private const val RETRY_JOB_ID_BASE = 700_000

        fun scheduleRetry(context: android.content.Context, blocked: BlockedSet, retryAt: Long) {
            val scheduler = context.getSystemService(JobService.JOB_SCHEDULER_SERVICE) as JobScheduler
            val extras = PersistableBundle().apply {
                putString(EXTRA_PROFILE_ID, blocked.profileId)
                putString(EXTRA_SET_ID, blocked.setId)
            }
            val delay = (retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val job = JobInfo.Builder(
                retryJobId(blocked),
                ComponentName(context, LocalSyncJobService::class.java),
            )
                .setPersisted(true)
                .setMinimumLatency(delay)
                .setExtras(extras)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                throw IOException("Android did not accept the scheduled conflict retry.")
            }
        }

        fun retryJobId(blocked: BlockedSet): Int =
            RETRY_JOB_ID_BASE + Math.floorMod("${blocked.profileId}:${blocked.setId}".hashCode(), 200_000)
    }
}
