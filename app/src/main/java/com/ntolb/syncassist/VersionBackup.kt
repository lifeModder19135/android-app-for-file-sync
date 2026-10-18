package com.ntolb.syncassist

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
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
            try {
                // JGit reads user.home for config lookup; Android does not define it.
                System.setProperty("user.home", homeDir.absolutePath)
                val dir = File(root, profileId).apply { mkdirs() }
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
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException("Backup failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
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
