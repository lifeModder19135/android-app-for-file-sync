package com.teches.filesync

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException
import java.security.MessageDigest

data class ObservedFile(
    val index: Int,
    val content: String,
    val hash: String,
    val modifiedAt: Long,
    val baselineHash: String,
)

object SyncPolicy {
    fun isInitialSync(files: List<ObservedFile>): Boolean =
        files.size >= 2 && files.all { it.baselineHash.isBlank() }

    /** Whitespace-only files count as empty: they carry no content worth protecting or syncing from. */
    fun isEmpty(file: ObservedFile): Boolean = file.content.isBlank()

    fun chooseLastChanged(files: List<ObservedFile>): ObservedFile? =
        files.filter { it.hash != it.baselineHash }.maxWithOrNull(
            compareBy<ObservedFile> { it.modifiedAt }.thenBy { it.index },
        )

    /**
     * Picks the file whose text is copied to the rest of the set. Empty files can never be the
     * source (or the master), so they are filled from the others. Returns null when every file is
     * empty (sync waits until one is written) or when there is nothing to propagate.
     */
    fun chooseSource(files: List<ObservedFile>, master: ObservedFile?, initial: Boolean): ObservedFile? {
        val filled = files.filterNot(::isEmpty)
        if (filled.isEmpty()) return null
        if (initial) {
            return if (filled.size == 1) filled[0] else master?.takeUnless(::isEmpty)
        }
        return chooseLastChanged(filled)
            ?: if (filled.size < files.size) {
                filled.maxWith(compareBy<ObservedFile> { it.modifiedAt }.thenBy { it.index })
            } else {
                null
            }
    }
}

data class BlockedSet(val profileId: String, val setId: String, val setName: String, val report: SimilarityReport)

data class SyncResult(val updatedSets: Int, val blocked: List<BlockedSet>)

private data class SetOutcome(val set: SyncSet, val report: SimilarityReport? = null)

class LocalSyncEngine(
    private val resolver: ContentResolver,
    private val store: StateStore,
    private val backup: VersionBackup? = null,
) {
    @Throws(IOException::class)
    fun syncAll(): SyncResult = synchronized(StateStore.processLock) {
        val state = store.load()
        var synchronizedSets = 0
        val blocked = mutableListOf<BlockedSet>()
        val updatedProfiles = state.profiles.map { profile ->
            profile.copy(
                sets = profile.sets.map { set ->
                    val outcome = syncSet(profile.id, set, state.similarity)
                    outcome.report?.let { blocked.add(BlockedSet(profile.id, set.id, set.name, it)) }
                    if (outcome.set != set) synchronizedSets++
                    outcome.set
                },
            )
        }
        if (updatedProfiles != state.profiles) {
            store.save(state.copy(profiles = updatedProfiles))
        }
        SyncResult(synchronizedSets, blocked)
    }

    private fun overwrite(file: SyncFile, content: String) {
        resolver.openOutputStream(Uri.parse(file.location), "wt")?.bufferedWriter(
            Charsets.UTF_8,
        )?.use {
            it.write(content)
        } ?: throw IOException("Could not write ${file.name}.")
    }

    private fun snapshot(profileId: String, set: SyncSet, contents: List<String>, message: String) {
        backup?.snapshot(
            profileId,
            set.id,
            set.files.mapIndexed { index, file -> BackupFile(file.name, file.location, contents[index]) },
            message,
        )
    }

    private fun syncSet(profileId: String, set: SyncSet, settings: SimilaritySettings): SetOutcome {
        if (set.files.size < 2) return SetOutcome(set)

        val observed = set.files.mapIndexed { index, file ->
            val uri = Uri.parse(file.location)
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Could not read ${file.name}.")
            val content = Utf8Text.read(input)
            ObservedFile(
                index = index,
                content = content,
                hash = sha256(content),
                modifiedAt = modifiedAt(uri),
                baselineHash = file.baselineHash,
            )
        }

        val filled = observed.filterNot(SyncPolicy::isEmpty)
        // All files empty: wait until one is written to; baselines stay blank so the set is still initial.
        if (filled.isEmpty()) return SetOutcome(set)

        var checkedSet = set
        if (filled.size >= 2 && !set.similarityApproved) {
            val report = SimilarityCheck.evaluate(
                filled.map { set.files[it.index].name },
                filled.map { it.content },
                settings,
            )
            if (report.hasIssues) return SetOutcome(set, report)
            checkedSet = set.copy(similarityApproved = true)
        }
        val before = observed.map { it.content }

        val initial = SyncPolicy.isInitialSync(observed)
        var master: ObservedFile? = null
        if (initial && filled.size > 1) {
            val masterIndex = set.files.indexOfFirst { it.location == set.masterFileLocation }
            if (masterIndex < 0) {
                throw IOException("Choose a master version for set \"${set.name}\" before syncing.")
            }
            master = observed[masterIndex]
            if (SyncPolicy.isEmpty(master)) {
                throw IOException(
                    "The master of set \"${set.name}\" is empty. Choose a master that has text, then sync again.",
                )
            }
        }

        val source = SyncPolicy.chooseSource(observed, master, initial) ?: return SetOutcome(checkedSet)
        return SetOutcome(propagate(profileId, checkedSet, observed, before, source.index, source))
    }

    private fun propagate(
        profileId: String,
        set: SyncSet,
        observed: List<ObservedFile>,
        before: List<String>,
        sourceIndex: Int,
        source: ObservedFile,
    ): SyncSet {
        val needsWrite = set.files.indices.any { it != sourceIndex && observed[it].hash != source.hash }
        if (needsWrite) snapshot(profileId, set, before, "Before sync of \"${set.name}\"")
        val updatedFiles = set.files.mapIndexed { index, file ->
            if (index != sourceIndex && observed[index].hash != source.hash) overwrite(file, source.content)
            file.copy(baselineHash = source.hash)
        }
        if (needsWrite) {
            snapshot(profileId, set, set.files.map { source.content }, "After sync of \"${set.name}\"")
        }
        return set.copy(files = updatedFiles)
    }

    private fun modifiedAt(uri: Uri): Long {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column)
            }
        }
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return 0L
        }
        return 0L
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
