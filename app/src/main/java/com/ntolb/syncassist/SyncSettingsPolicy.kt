package com.ntolb.syncassist

object SyncSettingsPolicy {
    fun shouldScheduleBackground(pattern: SyncPattern): Boolean =
        pattern == SyncPattern.AGGRESSIVE

    fun shouldRunWhileForeground(pattern: SyncPattern, hasWindowFocus: Boolean): Boolean =
        pattern == SyncPattern.LAZY ||
            (pattern == SyncPattern.INATTENTIVE && hasWindowFocus)

    fun shouldDeferConflictRetry(retryAt: Long?, now: Long): Boolean =
        retryAt != null && retryAt > now
}
