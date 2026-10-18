package com.teches.filesync

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
}
