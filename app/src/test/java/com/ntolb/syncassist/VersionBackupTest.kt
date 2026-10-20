package com.ntolb.syncassist

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VersionBackupTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun keepsEveryVersionInGit() {
        val root = temp.newFolder("backups")
        val backup = VersionBackup(root, temp.newFolder("home"))
        val files = listOf(BackupFile("a.txt", "content://a", "one"), BackupFile("b.txt", "content://b", "two"))

        backup.snapshot("profile", "set", files, "first")
        backup.snapshot("profile", "set", files, "unchanged, should not commit")
        backup.snapshot("profile", "set", files.map { it.copy(content = "three") }, "second")

        Git.open(root.resolve("profile")).use { git ->
            val log = git.log().call().toList()
            assertEquals(listOf("second", "first"), log.map { it.fullMessage })
            assertTrue(root.resolve("profile").walkTopDown().any { it.isFile && it.readText() == "three" })
        }
    }

    @Test
    fun findsPreviousAndFirstNonBlankFileVersions() {
        val root = temp.newFolder("backups")
        val backup = VersionBackup(root, temp.newFolder("home"))
        val file = BackupFile("a.txt", "content://a", "   ")

        backup.snapshot("profile", "set", listOf(file), "initial whitespace")
        backup.snapshot("profile", "set", listOf(file.copy(content = "first text")), "first text")
        backup.snapshot("profile", "set", listOf(file.copy(content = "second text")), "second text")

        assertEquals(
            "second text",
            backup.latestPreviousVersion("profile", "set", file, "current text"),
        )
        assertEquals(
            "first text",
            backup.latestPreviousVersion("profile", "set", file, "second text"),
        )
        assertEquals("first text", backup.firstTextVersion("profile", "set", file))
    }

    @Test
    fun returnsNoPreviousVersionWhenOnlyCurrentContentWasSaved() {
        val root = temp.newFolder("backups")
        val backup = VersionBackup(root, temp.newFolder("home"))
        val file = BackupFile("a.txt", "content://a", "only version")

        backup.snapshot("profile", "set", listOf(file), "only version")

        assertEquals(null, backup.latestPreviousVersion("profile", "set", file, "only version"))
        assertEquals(null, backup.firstTextVersion("profile", "set", BackupFile("b.txt", "content://b", "")))
    }
}
