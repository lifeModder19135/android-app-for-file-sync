package com.teches.filesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPolicyTest {
    @Test
    fun choosesTheChangedFileWithTheLatestModificationTime() {
        val older = ObservedFile(0, "old", "changed-a", 100L, "base")
        val newer = ObservedFile(1, "new", "changed-b", 200L, "base")

        assertEquals(newer, SyncPolicy.chooseLastChanged(listOf(older, newer)))
    }

    @Test
    fun doesNotChooseAnUnchangedFile() {
        val unchanged = ObservedFile(0, "same", "base", 100L, "base")

        assertNull(SyncPolicy.chooseLastChanged(listOf(unchanged)))
    }

    @Test
    fun prefersAChangedFileOverAnUnchangedFileWithANewerTimestamp() {
        val changed = ObservedFile(0, "edited", "changed", 100L, "base")
        val unchanged = ObservedFile(1, "same", "base", 500L, "base")

        assertEquals(changed, SyncPolicy.chooseLastChanged(listOf(changed, unchanged)))
    }

    @Test
    fun resolvesModificationTimeTiesByFileOrder() {
        val first = ObservedFile(0, "first", "changed-a", 100L, "base")
        val second = ObservedFile(1, "second", "changed-b", 100L, "base")

        assertEquals(second, SyncPolicy.chooseLastChanged(listOf(first, second)))
    }

    @Test
    fun choosesTheFileWithTheLatestProviderModificationTime() {
        val earlier = ObservedFile(0, "earlier", "changed-a", 1_700_000_000_000L, "base")
        val later = ObservedFile(1, "later", "changed-b", 1_700_000_005_000L, "base")

        assertEquals(later, SyncPolicy.chooseLastChanged(listOf(earlier, later)))
    }

    @Test
    fun considersASetInitialOnlyWhenEveryFileLacksABaseline() {
        val first = ObservedFile(0, "first", "hash-a", 100L, "")
        val second = ObservedFile(1, "second", "hash-b", 200L, "")
        val addedLater = ObservedFile(2, "new", "hash-c", 300L, "")

        assertEquals(true, SyncPolicy.isInitialSync(listOf(first, second)))
        assertEquals(
            false,
            SyncPolicy.isInitialSync(listOf(first, second.copy(baselineHash = "old"))),
        )
        assertEquals(false, SyncPolicy.isInitialSync(emptyList()))
        assertEquals(false, SyncPolicy.isInitialSync(listOf(addedLater)))
    }

    @Test
    fun newFileWithNoBaselineCompetesByModificationTimeAfterInitializationSetup() {
        val existingChanged = ObservedFile(0, "existing edit", "edit", 200L, "old")
        val addedLater = ObservedFile(1, "new file", "new", 300L, "")

        assertEquals(addedLater, SyncPolicy.chooseLastChanged(listOf(existingChanged, addedLater)))
    }

    private fun file(index: Int, content: String, modifiedAt: Long = 100L, baseline: String = "") =
        ObservedFile(index, content, "hash-$content", modifiedAt, baseline)

    @Test
    fun allEmptyFilesWaitWithNoSource() {
        val files = listOf(file(0, ""), file(1, "  \n"))

        assertNull(SyncPolicy.chooseSource(files, files[0], initial = true))
        assertNull(SyncPolicy.chooseSource(files.map { it.copy(baselineHash = "x") }, null, initial = false))
    }

    @Test
    fun lonelyNonEmptyFileBecomesInitialSourceEvenIfMasterIsEmpty() {
        val empty = file(0, "")
        val filled = file(1, "text")

        assertEquals(filled, SyncPolicy.chooseSource(listOf(empty, filled), empty, initial = true))
    }

    @Test
    fun emptyMasterIsRejectedWhenSeveralFilesHaveText() {
        val empty = file(0, "")

        assertNull(SyncPolicy.chooseSource(listOf(empty, file(1, "a"), file(2, "b")), empty, initial = true))
    }

    @Test
    fun emptyFileNeverWinsEvenWhenItIsTheNewestChange() {
        val edited = file(0, "kept", modifiedAt = 100L, baseline = "base")
        val cleared = file(1, "", modifiedAt = 900L, baseline = "base")

        assertEquals(edited, SyncPolicy.chooseSource(listOf(edited, cleared), null, initial = false))
    }

    @Test
    fun emptyFileIsRefilledEvenWhenNothingElseChanged() {
        val kept = file(0, "kept", modifiedAt = 100L, baseline = "hash-kept")
        val cleared = file(1, "", modifiedAt = 900L, baseline = "hash-kept")

        assertEquals(kept, SyncPolicy.chooseSource(listOf(kept, cleared), null, initial = false))
    }

    @Test
    fun nothingToDoWhenNoFileChangedAndNoneAreEmpty() {
        val a = file(0, "same", baseline = "hash-same")
        val b = file(1, "same", baseline = "hash-same")

        assertNull(SyncPolicy.chooseSource(listOf(a, b), null, initial = false))
    }
}
