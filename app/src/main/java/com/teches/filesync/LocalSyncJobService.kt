package com.teches.filesync

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors

class LocalSyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            try {
                LocalSyncEngine(contentResolver, StateStore(this), VersionBackup.forContext(this)).syncAll()
            } catch (error: IOException) {
                Log.e(TAG, "Local file sync failed.", error)
            } catch (error: SecurityException) {
                Log.e(TAG, "A file permission expired; sync was not completed.", error)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val TAG = "FileSync"
    }
}
