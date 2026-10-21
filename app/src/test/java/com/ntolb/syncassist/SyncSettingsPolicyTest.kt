package com.ntolb.syncassist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSettingsPolicyTest {
    @Test
    fun onlyAggressiveUsesBackgroundScheduling() {
        assertTrue(SyncSettingsPolicy.shouldScheduleBackground(SyncPattern.AGGRESSIVE))
        assertFalse(SyncSettingsPolicy.shouldScheduleBackground(SyncPattern.LAZY))
        assertFalse(SyncSettingsPolicy.shouldScheduleBackground(SyncPattern.INATTENTIVE))
        assertFalse(SyncSettingsPolicy.shouldScheduleBackground(SyncPattern.STUBBORN))
    }

    @Test
    fun foregroundPatternsRespectAppFocus() {
        assertTrue(SyncSettingsPolicy.shouldRunWhileForeground(SyncPattern.LAZY, false))
        assertTrue(SyncSettingsPolicy.shouldRunWhileForeground(SyncPattern.LAZY, true))
        assertFalse(SyncSettingsPolicy.shouldRunWhileForeground(SyncPattern.INATTENTIVE, false))
        assertTrue(SyncSettingsPolicy.shouldRunWhileForeground(SyncPattern.INATTENTIVE, true))
        assertFalse(SyncSettingsPolicy.shouldRunWhileForeground(SyncPattern.AGGRESSIVE, true))
        assertFalse(SyncSettingsPolicy.shouldRunWhileForeground(SyncPattern.STUBBORN, true))
    }

    @Test
    fun conflictRetryIsDeferredUntilItsSavedTime() {
        assertFalse(SyncSettingsPolicy.shouldDeferConflictRetry(null, 100L))
        assertTrue(SyncSettingsPolicy.shouldDeferConflictRetry(101L, 100L))
        assertFalse(SyncSettingsPolicy.shouldDeferConflictRetry(100L, 100L))
    }

    @Test
    fun syncDefaultsPreserveCurrentBehaviorAndRequestedNotifications() {
        val settings = AppSettings()
        assertTrue(settings.commentsEnabled)
        assertTrue(settings.autoSyncNotifications)
        assertTrue(settings.singleSetSyncNotifications)
        assertTrue(settings.allSetsSyncNotifications)
        assertFalse(settings.setCreatedNotifications)
        assertFalse(settings.fileAddedNotifications)
        assertFalse(settings.changeToast)
        assertFalse(settings.changeVibration)
        assertFalse(settings.changeSound)
        assertEquals(SyncPattern.AGGRESSIVE, settings.syncPattern)
        assertEquals(ConflictResolution.PAUSE, settings.conflictResolution)
        assertEquals(15L, settings.syncIntervalMinutes)
        assertFalse(settings.gainFocusOnConflict)
    }
}
