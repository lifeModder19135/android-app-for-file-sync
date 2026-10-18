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
)
