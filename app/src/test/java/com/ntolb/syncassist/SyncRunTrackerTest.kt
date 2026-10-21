package com.ntolb.syncassist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRunTrackerTest {
    @Test
    fun catchUpIsNeededWhenNoSyncHasBeenRecorded() {
        assertTrue(SyncRunTracker.shouldCatchUp(lastSyncAt = 0L, now = 1L))
    }

    @Test
    fun catchUpWaitsUntilTheSyncIntervalHasElapsed() {
        val lastSyncAt = 1000L

        assertFalse(
            SyncRunTracker.shouldCatchUp(
                lastSyncAt,
                lastSyncAt + SyncRunTracker.SYNC_INTERVAL_MS - 1,
            ),
        )
        assertTrue(
            SyncRunTracker.shouldCatchUp(
                lastSyncAt,
                lastSyncAt + SyncRunTracker.SYNC_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun catchUpUsesTheConfiguredInterval() {
        val lastSyncAt = 1000L
        val configuredInterval = 30 * 60 * 1000L

        assertFalse(
            SyncRunTracker.shouldCatchUp(
                lastSyncAt,
                lastSyncAt + configuredInterval - 1,
                configuredInterval,
            ),
        )
        assertTrue(
            SyncRunTracker.shouldCatchUp(
                lastSyncAt,
                lastSyncAt + configuredInterval,
                configuredInterval,
            ),
        )
    }
}
