package com.ntolb.syncassist

import android.content.Context
import java.io.IOException

object SyncRunTracker {
    const val SYNC_INTERVAL_MS = 15 * 60 * 1000L

    private const val PREFERENCES_NAME = "sync_schedule"
    private const val LAST_SYNC_AT_KEY = "last_sync_at"

    fun shouldCatchUp(
        lastSyncAt: Long,
        now: Long,
        intervalMillis: Long = SYNC_INTERVAL_MS,
    ): Boolean = lastSyncAt <= 0L || now - lastSyncAt >= intervalMillis

    fun shouldCatchUp(
        context: Context,
        now: Long = System.currentTimeMillis(),
        intervalMillis: Long = SYNC_INTERVAL_MS,
    ): Boolean {
        val lastSyncAt = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getLong(LAST_SYNC_AT_KEY, 0L)
        return shouldCatchUp(lastSyncAt, now, intervalMillis)
    }

    @Throws(IOException::class)
    fun record(context: Context, syncedAt: Long = System.currentTimeMillis()) {
        val saved = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_SYNC_AT_KEY, syncedAt)
            .commit()
        if (!saved) throw IOException("Could not save the last successful sync time.")
    }
}
