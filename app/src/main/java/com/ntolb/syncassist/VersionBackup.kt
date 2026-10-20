package com.ntolb.syncassist

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class BackupFile(val name: String, val location: String, val content: String)

/**
 * Keeps every synced version of every file in a local Git repository per profile,
 * so overwritten text can always be recovered.
 */
class VersionBackup(private val root: File, private val homeDir: File) {
    @Throws(IOException::class)
    fun snapshot(profileId: String, setId: String, files: List<BackupFile>, message: String) {
        synchronized(lock) {
            withBackupRepository(profileId, create = true) { dir ->
                val git = if (File(dir, ".git").exists()) Git.open(dir) else Git.init().setDirectory(dir).call()
                git.use {
                    val setDir = File(dir, setId).apply { mkdirs() }
                    files.forEach { file ->
                        File(setDir, backupName(file)).writeText(file.content, Charsets.UTF_8)
                    }
                    it.add().addFilepattern(setId).call()
                    if (!it.status().call().isClean) {
                        val author = PersonIdent("File Sync", "filesync@localhost")
                        it.commit().setAuthor(author).setCommitter(author).setMessage(message)
                            .setSign(false).call()
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    fun latestPreviousVersion(
        profileId: String,
        setId: String,
        file: BackupFile,
        currentContent: String,
    ): String? = versions(profileId, setId, file)
        .firstOrNull { it != currentContent }

    @Throws(IOException::class)
    fun firstTextVersion(profileId: String, setId: String, file: BackupFile): String? =
        versions(profileId, setId, file).lastOrNull(String::isNotBlank)

    private fun versions(profileId: String, setId: String, file: BackupFile): List<String> =
        synchronized(lock) {
            withBackupRepository(profileId, create = false) { dir ->
                if (!File(dir, ".git").exists()) return@withBackupRepository emptyList()
                Git.open(dir).use { git ->
                    val path = "$setId/${backupName(file)}"
                    git.log().addPath(path).call().map { commit ->
                        val treeWalk = TreeWalk.forPath(git.repository, path, commit.tree)
                            ?: throw IOException("Backup history is missing $path.")
                        treeWalk.use {
                            git.repository.open(it.getObjectId(0)).bytes.toString(Charsets.UTF_8)
                        }
                    }
                }
            }
        }

    private fun <T> withBackupRepository(
        profileId: String,
        create: Boolean,
        action: (File) -> T,
    ): T {
        try {
            // JGit reads user.home for config lookup; Android does not define it.
            System.setProperty("user.home", homeDir.absolutePath)
            val dir = File(root, profileId)
            if (create) dir.mkdirs()
            return action(dir)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Backup history operation failed: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun backupName(file: BackupFile): String {
        val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val id = MessageDigest.getInstance("SHA-256").digest(file.location.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(8)
        return "$id-$safe"
    }

    companion object {
        private val lock = Any()

        fun forContext(context: android.content.Context) =
            VersionBackup(context.filesDir.resolve("backups"), context.filesDir)
    }
}
