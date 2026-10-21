package com.ntolb.syncassist

data class SyncFile(
    val name: String,
    val type: String,
    val location: String,
    val baselineHash: String = "",
)

data class SyncSet(
    val id: String,
    val name: String,
    val files: List<SyncFile> = emptyList(),
    val masterFileLocation: String? = null,
    val similarityApproved: Boolean = false,
    val syncEnabled: Boolean = true,
    val conflictRetryAt: Long? = null,
)

enum class SyncPattern {
    AGGRESSIVE,
    LAZY,
    INATTENTIVE,
    STUBBORN,
}

enum class ConflictResolution {
    PAUSE,
    RETRY,
}

data class AppSettings(
    val syncPattern: SyncPattern = SyncPattern.AGGRESSIVE,
    val conflictResolution: ConflictResolution = ConflictResolution.PAUSE,
    val syncIntervalMinutes: Long = 15,
    val conflictRetryMinutes: Long = 15,
    val gainFocusOnConflict: Boolean = false,
    val commentsEnabled: Boolean = true,
    val autoSyncNotifications: Boolean = true,
    val singleSetSyncNotifications: Boolean = true,
    val allSetsSyncNotifications: Boolean = true,
    val syncToast: Boolean = true,
    val syncVibration: Boolean = false,
    val syncSound: Boolean = false,
    val setCreatedNotifications: Boolean = false,
    val fileAddedNotifications: Boolean = false,
    val changeToast: Boolean = false,
    val changeVibration: Boolean = false,
    val changeSound: Boolean = false,
    val theme: String = "Default",
)

data class SimilaritySettings(
    val checkNames: Boolean = true,
    val checkContent: Boolean = true,
)

data class Profile(
    val id: String,
    val uname: String,
    val email: String,
    val profilePic: String? = null,
    val colors: List<String> = listOf("#6750A4", "#EADDFF"),
    val drives: List<DriveAccount> = emptyList(),
    val sets: List<SyncSet> = emptyList(),
)

data class DriveAccount(
    val email: String,
    val signedIn: Boolean = false,
)

data class AppState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val similarity: SimilaritySettings = SimilaritySettings(),
    val settings: AppSettings = AppSettings(),
)
