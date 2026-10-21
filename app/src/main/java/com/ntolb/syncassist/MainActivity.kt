package com.ntolb.syncassist

import android.app.Activity
import android.app.AlertDialog
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Patterns
import android.widget.ArrayAdapter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class RestoreTarget {
        LAST,
        FIRST_TEXT,
    }

    private lateinit var store: StateStore
    private val io = Executors.newSingleThreadExecutor()
    private var state = AppState()
    private var currentSetId: String? = null
    private var pendingSetId: String? = null
    private var pendingFilePickerSetId: String? = null
    private var pendingExternalFile: SyncFile? = null
    private var pendingExternalEdit: Pair<String, String>? = null
    private var awaitingOverlayPermission = false
    private val pendingBlocked = mutableListOf<BlockedSet>()
    private var warningShowing = false
    private var showingSettings = false
    private var hasWindowFocus = false
    private val foregroundHandler = Handler(Looper.getMainLooper())
    private val foregroundSync = object : Runnable {
        override fun run() {
            val pattern = state.settings.syncPattern
            val shouldRun = SyncSettingsPolicy.shouldRunWhileForeground(pattern, hasWindowFocus)
            if (!shouldRun || isFinishing) return
            runSync(showResult = false)
            foregroundHandler.postDelayed(this, state.settings.syncIntervalMinutes * 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = StateStore(this)
        currentSetId = savedInstanceState?.getString(STATE_CURRENT_SET_ID)
        pendingSetId = savedInstanceState?.getString(STATE_PENDING_SET_ID)
        try {
            state = store.load()
        } catch (error: Exception) {
            showError("Saved app data could not be loaded: ${error.message}")
        }
        state.profiles.forEach { profile ->
            profile.sets.forEach { set ->
                saveInitialVersions(profile.id, set.id, set.files)
            }
        }
        scheduleSync()
        render()
        showConflictLaunchMessage(intent)
        val interval = state.settings.syncIntervalMinutes * 60_000L
        if (SyncSettingsPolicy.shouldScheduleBackground(state.settings.syncPattern) &&
            SyncRunTracker.shouldCatchUp(this, intervalMillis = interval)
        ) {
            runSync(showResult = false)
        }
    }

    override fun onResume() {
        super.onResume()
        ReturnToAppOverlayService.stop(this)
        val externalEdit = pendingExternalEdit
        val canSyncAfterEdit = state.settings.syncPattern == SyncPattern.AGGRESSIVE ||
            state.settings.syncPattern == SyncPattern.LAZY ||
            (state.settings.syncPattern == SyncPattern.INATTENTIVE && hasWindowFocus)
        if (externalEdit != null && canSyncAfterEdit) {
            pendingExternalEdit = null
            runSync(showResult = false, profileId = externalEdit.first, setId = externalEdit.second)
        } else if (state.settings.syncPattern != SyncPattern.INATTENTIVE) {
            pendingExternalEdit = null
        }
        scheduleForegroundSync(runImmediately = externalEdit == null)
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            val file = pendingExternalFile
            val pickerSetId = pendingFilePickerSetId
            pendingExternalFile = null
            pendingFilePickerSetId = null
            if (Settings.canDrawOverlays(this)) {
                if (file != null) launchExternalEditor(file)
                if (pickerSetId != null) launchFilePicker(pickerSetId)
            } else if (file != null || pickerSetId != null) {
                pendingSetId = null
                showError("Overlay permission is required to show the return-to-app button.")
            }
        }
    }

    override fun onPause() {
        foregroundHandler.removeCallbacks(foregroundSync)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasWindowFocus = hasFocus
        if (state.settings.syncPattern == SyncPattern.INATTENTIVE && hasFocus && !isFinishing) {
            val externalEdit = pendingExternalEdit
            if (externalEdit != null) {
                pendingExternalEdit = null
                runSync(showResult = false, profileId = externalEdit.first, setId = externalEdit.second)
                scheduleForegroundSync(runImmediately = false)
            } else {
                scheduleForegroundSync(runImmediately = true)
            }
        } else if (state.settings.syncPattern != SyncPattern.LAZY && !hasFocus) {
            foregroundHandler.removeCallbacks(foregroundSync)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT_SET_ID, currentSetId)
        outState.putString(STATE_PENDING_SET_ID, pendingSetId)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            synchronized(StateStore.processLock) { state = store.load() }
        } catch (error: Exception) {
            showError("Saved app data could not be loaded: ${error.message}")
        }
        showConflictLaunchMessage(intent)
    }

    @Deprecated("Use OnBackPressedDispatcher for production builds.")
    override fun onBackPressed() {
        if (showingSettings) {
            showingSettings = false
            render()
        } else if (currentSetId != null) {
            currentSetId = null
            render()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        foregroundHandler.removeCallbacks(foregroundSync)
        io.shutdown()
        super.onDestroy()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        setContentView(root)

        val activeProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }
        if (showingSettings && activeProfile != null) {
            renderSettings(root)
        } else if (activeProfile == null) {
            renderProfileChooser(root)
        } else if (currentSetId != null) {
            val set = activeProfile.sets.firstOrNull { it.id == currentSetId }
            if (set == null) {
                currentSetId = null
                renderSets(root, activeProfile)
            } else {
                renderSetEditor(activeProfile, set)
            }
        } else {
            renderSets(root, activeProfile)
        }
    }

    private fun renderProfileChooser(root: LinearLayout) {
        root.addView(versionTag())
        root.addView(title("File Sync"))
        root.addView(body("Create a local profile to start syncing text files."))
        state.profiles.forEach { profile ->
            root.addView(
                actionButton("Sign in as ${profile.uname}") {
                    updateState(state.copy(activeProfileId = profile.id))
                },
            )
        }
        root.addView(actionButton("New profile") { showCreateProfile() })
    }

    private fun renderSets(root: LinearLayout, profile: Profile) {
        root.addView(versionTag())
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title("Sets"), LinearLayout.LayoutParams(0, -2, 1f))
        val menu = TextView(this).apply {
            text = "⋮"
            textSize = 28f
            contentDescription = "Profile menu"
            setPadding(dp(14), dp(4), dp(8), dp(4))
            setOnClickListener { showMenu(this) }
        }
        header.addView(menu)
        root.addView(header)
        root.addView(body("Profile: ${profile.uname}"))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profile.sets.forEach { set ->
            list.addView(
                actionButton("${set.name}  ·  ${set.files.size} files") {
                    currentSetId = set.id
                    render()
                },
            )
            list.addView(
                actionButton("${if (set.syncEnabled) "Sync" else "Start sync for"} ${set.name}") {
                    runSync(showResult = true, profileId = profile.id, setId = set.id)
                },
            )
            list.addView(actionButton("Delete ${set.name}") { confirmDeleteSet(profile, set) })
        }
        if (profile.sets.isEmpty()) {
            list.addView(body("No sets yet. Create a set and add at least two local text files."))
        }
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        root.addView(actionButton("Add set") { showCreateSet() })
        root.addView(actionButton("Sync now") { runSync(showResult = true) })
        root.addView(actionButton("Profile") { showProfile(profile) })
        root.post { showNextBlockedWarning() }
    }

    private fun renderSetEditor(profile: Profile, set: SyncSet) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(versionTag())
        root.addView(actionButton("Home — sets") {
            currentSetId = null
            render()
        })
        root.addView(title(set.name))
        root.addView(body("Local UTF-8 text files only: .txt, .md, .html, .py, .js, .java"))
        root.addView(
            body(
                "Private comments: put text between !!!> and <!!!. Sync ignores the comment text " +
                    "and keeps each file's comments with the text immediately following them.",
            ),
        )
        val initialSyncPending = set.files.none { it.baselineHash.isNotBlank() }
        val master = set.files.firstOrNull { it.location == set.masterFileLocation }
        if (initialSyncPending && set.files.size < 2) {
            root.addView(
                body("Add at least two files. You'll then choose which one is the master for the first sync."),
            )
        } else if (initialSyncPending) {
            root.addView(
                body(
                    if (master == null) {
                        "Choose a master version. Its text seeds the set on the first sync only."
                    } else {
                        "Initial master: ${master.name}. After the first sync, the most recently modified changed file wins."
                    },
                ),
            )
            root.addView(
                actionButton(if (master == null) "Choose master version" else "Change master version") {
                    chooseMasterVersion(profile, set)
                },
            )
        } else {
            root.addView(
                body("Initial sync complete. The most recently modified changed file wins."),
            )
        }
        if (!set.syncEnabled) {
            root.addView(body("Syncing is paused for this new set until you manually start it."))
        }
        set.files.forEach { file ->
            root.addView(
                actionButton(
                    "${if (file.location == set.masterFileLocation) "Master · " else ""}${file.name}  (${file.type})",
                ) {
                    openFile(file)
                },
            )
            root.addView(actionButton("Revert to last version") {
                confirmRestore(profile, set, file, RestoreTarget.LAST)
            })
            root.addView(actionButton("Revert to 1st version") {
                confirmRestore(profile, set, file, RestoreTarget.FIRST_TEXT)
            })
            root.addView(
                actionButton("Remove ${file.name} from set") {
                    updateSet(
                        profile,
                        set.copy(
                            files = set.files.filterNot { it.location == file.location },
                            masterFileLocation = set.masterFileLocation
                                .takeUnless { it == file.location },
                        ),
                    )
                },
            )
        }
        root.addView(
            body(
                "Select files in Android's picker, then tap its Open/Select action to add them. " +
                    "Use the picker's Back control to return without adding.",
            ),
        )
        root.addView(actionButton("Add local files") {
            pendingSetId = set.id
            if (Settings.canDrawOverlays(this)) {
                launchFilePicker(set.id)
            } else {
                pendingFilePickerSetId = set.id
                AlertDialog.Builder(this)
                    .setTitle("Enable return-to-app button")
                    .setMessage(
                        "Allow SyncAssist to display a Return to SyncAssist button over Android's " +
                            "file picker. This provides a visible way back without the emulator Back control.",
                    )
                    .setNegativeButton("Cancel") { _, _ ->
                        pendingFilePickerSetId = null
                        pendingSetId = null
                    }
                    .setPositiveButton("Open settings") { _, _ ->
                        awaitingOverlayPermission = true
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                    .show()
            }
        })
        root.addView(
            actionButton(if (set.syncEnabled) "Sync this set now" else "Start syncing this set") {
                runSync(showResult = true, profileId = profile.id, setId = set.id)
            },
        )
    }

    private fun renderCurrentSet() {
        render()
    }

    private fun showCreateProfile() {
        val name = EditText(this).apply { hint = "Username" }
        val email = EditText(this).apply {
            hint = "Email"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(email)
        }
        AlertDialog.Builder(this)
            .setTitle("New profile")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val uname = name.text.toString().trim()
                        val address = email.text.toString().trim()
                        if (uname.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                            Toast.makeText(
                                this,
                                "Enter a username and a valid email address.",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@setOnClickListener
                        }
                        val profile = Profile(UUID.randomUUID().toString(), uname, address)
                        updateState(
                            state.copy(
                                profiles = state.profiles + profile,
                                activeProfileId = profile.id,
                            ),
                        )
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun showCreateSet() {
        val input = EditText(this).apply { hint = "Set name" }
        AlertDialog.Builder(this)
            .setTitle("Add set")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            input.error = "Enter a set name"
                            return@setOnClickListener
                        }
                        val profile = activeProfile() ?: return@setOnClickListener
                        val set = SyncSet(UUID.randomUUID().toString(), name, syncEnabled = false)
                        currentSetId = set.id
                        updateProfile(
                            profile.copy(
                                sets = profile.sets + set,
                            ),
                        )
                        AppNotifier.change(
                            this,
                            state.settings,
                            ChangeNotificationKind.SET_CREATED,
                            "Set \"$name\" created.",
                        )
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun confirmDeleteSet(profile: Profile, set: SyncSet) {
        AlertDialog.Builder(this)
            .setTitle("Delete set?")
            .setMessage("Remove \"${set.name}\" and its saved sync settings? The files themselves will not be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                pendingBlocked.removeAll { it.profileId == profile.id && it.setId == set.id }
                updateProfile(profile.copy(sets = profile.sets.filterNot { it.id == set.id }))
            }
            .show()
    }

    private fun chooseMasterVersion(profile: Profile, set: SyncSet) {
        if (set.files.isEmpty()) {
            Toast.makeText(this, "Add files to this set before choosing a master.", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val labels = set.files.map { file ->
            if (file.location == set.masterFileLocation) "${file.name} (current master)" else file.name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose master version")
            .setItems(labels) { _, index ->
                updateSet(profile, set.copy(masterFileLocation = set.files[index].location))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProfile(profile: Profile) {
        AlertDialog.Builder(this)
            .setTitle(profile.uname)
            .setMessage("${profile.email}\n\nLocal-only MVP. Drive accounts are not configured.")
            .setItems(arrayOf("Edit profile", "Sign out", "New profile")) { _, which ->
                when (which) {
                    0 -> showEditProfile(profile)
                    1 -> updateState(state.copy(activeProfileId = null))
                    2 -> showCreateProfile()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEditProfile(profile: Profile) {
        val name = EditText(this).apply { setText(profile.uname) }
        val email = EditText(this).apply { setText(profile.email) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(email)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit profile")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val newName = name.text.toString().trim()
                val newEmail = email.text.toString().trim()
                if (newName.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    Toast.makeText(this, "Enter a username and valid email.", Toast.LENGTH_SHORT).show()
                } else {
                    updateProfile(profile.copy(uname = newName, email = newEmail))
                }
            }
            .show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Home").setOnMenuItemClickListener {
                currentSetId = null
                render()
                true
            }
            menu.add("See profile").setOnMenuItemClickListener {
                activeProfile()?.let { showProfile(it) }
                true
            }
            menu.add("New profile").setOnMenuItemClickListener {
                showCreateProfile()
                true
            }
            menu.add("Settings").setOnMenuItemClickListener {
                showSettings()
                true
            }
            menu.add("Sign out").setOnMenuItemClickListener {
                updateState(state.copy(activeProfileId = null))
                true
            }
            show()
        }
    }

    @Deprecated("Use the Activity Result API in production builds.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILES) return
        val setId = pendingSetId ?: return
        pendingSetId = null
        if (resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.data?.let(::add)
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }.distinct()
        val profile = activeProfile() ?: return
        val set = profile.sets.firstOrNull { it.id == setId } ?: return
        val wasEstablishedSet = set.files.any { it.baselineHash.isNotBlank() }
        val additions = mutableListOf<SyncFile>()
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "file"
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in SUPPORTED_TYPES) {
                Toast.makeText(this, "Unsupported file type: $name", Toast.LENGTH_LONG).show()
                continue
            }
            try {
                val flags = data.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags)
                additions.add(SyncFile(name, extension, uri.toString()))
            } catch (error: SecurityException) {
                showError("Could not keep access to $name: ${error.message}")
            }
        }
        val merged = (set.files + additions).distinctBy { it.location }
        val addedFiles = merged.filter { added -> set.files.none { it.location == added.location } }
        updateSet(
            profile,
            set.copy(
                files = merged,
                similarityApproved = set.similarityApproved && merged.size == set.files.size,
            ),
        )
        val addedCount = merged.size - set.files.size
        if (addedCount > 0) {
            saveInitialVersions(profile.id, set.id, addedFiles)
            val addedMessage = "Added $addedCount file(s) to ${set.name}."
            val changeToastWillNotify = wasEstablishedSet &&
                state.settings.fileAddedNotifications && state.settings.changeToast
            if (!changeToastWillNotify) {
                Toast.makeText(this, addedMessage, Toast.LENGTH_LONG).show()
            }
            if (wasEstablishedSet) {
                AppNotifier.change(
                    this,
                    state.settings,
                    ChangeNotificationKind.FILE_ADDED,
                    addedMessage,
                )
            }
        } else if (uris.isNotEmpty()) {
            Toast.makeText(this, "No new supported files were added.", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveInitialVersions(profileId: String, setId: String, files: List<SyncFile>) {
        io.execute {
            val errors = mutableListOf<String>()
            val backup = VersionBackup.forContext(this)
            files.forEach { file ->
                try {
                    synchronized(StateStore.processLock) {
                        val input = contentResolver.openInputStream(Uri.parse(file.location))
                            ?: throw IOException("Could not read ${file.name}.")
                        val content = Utf8Text.read(input)
                        backup.snapshot(
                            profileId,
                            setId,
                            listOf(BackupFile(file.name, file.location, content)),
                            "Initial version of ${file.name}",
                        )
                    }
                } catch (error: IOException) {
                    errors.add("Could not save the first backup of ${file.name}: ${error.message}")
                } catch (error: SecurityException) {
                    errors.add("Could not save the first backup of ${file.name}: file access was denied.")
                }
            }
            if (errors.isNotEmpty()) {
                runOnUiThread { errors.forEach(::showError) }
            }
        }
    }

    private fun confirmRestore(
        profile: Profile,
        set: SyncSet,
        file: SyncFile,
        target: RestoreTarget,
    ) {
        val title = when (target) {
            RestoreTarget.LAST -> "Revert to last version?"
            RestoreTarget.FIRST_TEXT -> "Revert to 1st version?"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("This change is final! Are you sure you want to proceed?")
            .setNegativeButton("Back", null)
            .setPositiveButton("Proceed") { _, _ ->
                restoreVersion(profile.id, set, file, target)
            }
            .show()
    }

    private fun restoreVersion(profileId: String, set: SyncSet, file: SyncFile, target: RestoreTarget) {
        io.execute {
            val outcome = try {
                synchronized(StateStore.processLock) {
                    val uri = Uri.parse(file.location)
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not read ${file.name}.")
                    val currentContent = Utf8Text.read(input)
                    val backup = VersionBackup.forContext(this)
                    val backupFile = BackupFile(file.name, file.location, currentContent)
                    val restoredContent = when (target) {
                        RestoreTarget.LAST -> backup.latestPreviousVersion(
                            profileId,
                            set.id,
                            backupFile,
                            currentContent,
                        )
                        RestoreTarget.FIRST_TEXT -> backup.firstTextVersion(profileId, set.id, backupFile)
                    } ?: return@synchronized "No saved version is available for ${file.name}."

                    backup.snapshot(
                        profileId,
                        set.id,
                        listOf(backupFile),
                        "Before reverting ${file.name}",
                    )
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(restoredContent)
                    } ?: throw IOException("Could not write ${file.name}.")
                    backup.snapshot(
                        profileId,
                        set.id,
                        listOf(backupFile.copy(content = restoredContent)),
                        "After reverting ${file.name}",
                    )
                    "Restored ${file.name} from its saved version."
                }
            } catch (error: IOException) {
                "Could not restore ${file.name}: ${error.message ?: error.javaClass.simpleName}"
            } catch (error: SecurityException) {
                "Could not restore ${file.name}: file access was denied."
            }
            runOnUiThread {
                Toast.makeText(this, outcome, Toast.LENGTH_LONG).show()
                if (outcome.startsWith("Restored ")) refresh()
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun openFile(file: SyncFile) {
        if (!Settings.canDrawOverlays(this)) {
            pendingExternalFile = file
            AlertDialog.Builder(this)
                .setTitle("Enable return-to-app button")
                .setMessage(
                    "SyncAssist needs permission to show a small button over the text editor, " +
                        "so you can return here even if the editor's Back button is unavailable.",
                )
                .setNegativeButton("Cancel") { _, _ -> pendingExternalFile = null }
                .setPositiveButton("Open settings") { _, _ ->
                    awaitingOverlayPermission = true
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                .show()
            return
        }
        launchExternalEditor(file)
    }

    private fun launchFilePicker(setId: String) {
        pendingSetId = setId
        pendingFilePickerSetId = null
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startService(Intent(this, ReturnToAppOverlayService::class.java))
            startActivityForResult(intent, REQUEST_FILES)
        } catch (error: android.content.ActivityNotFoundException) {
            pendingSetId = null
            stopService(Intent(this, ReturnToAppOverlayService::class.java))
            showError("No file picker is available on this device.")
        } catch (error: SecurityException) {
            pendingSetId = null
            stopService(Intent(this, ReturnToAppOverlayService::class.java))
            showError("Could not open the file picker: ${error.message}")
        }
    }

    private fun launchExternalEditor(file: SyncFile) {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(Uri.parse(file.location), mimeType(file.type))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            pendingExternalEdit = activeProfile()?.let { profile ->
                profile.sets.firstOrNull { set ->
                    set.id == currentSetId && set.files.any { it.location == file.location }
                }?.let { set -> profile.id to set.id }
            }
            startService(Intent(this, ReturnToAppOverlayService::class.java))
            startActivity(Intent.createChooser(intent, "Edit ${file.name}"))
        } catch (error: android.content.ActivityNotFoundException) {
            pendingExternalEdit = null
            stopService(Intent(this, ReturnToAppOverlayService::class.java))
            showError("No installed app can edit ${file.name}.")
        } catch (error: SecurityException) {
            pendingExternalEdit = null
            stopService(Intent(this, ReturnToAppOverlayService::class.java))
            showError("Could not open the return-to-app button: ${error.message}")
        }
    }

    private fun runSync(
        showResult: Boolean,
        profileId: String? = null,
        setId: String? = null,
    ) {
        val nextState = when {
            showResult && setId != null && profileId != null -> state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.id != profileId) profile else profile.copy(
                        sets = profile.sets.map { set ->
                            if (set.id == setId) set.copy(syncEnabled = true) else set
                        },
                    )
                },
            )
            showResult && setId == null -> state.copy(
                profiles = state.profiles.map { profile ->
                    profile.copy(sets = profile.sets.map { it.copy(syncEnabled = true) })
                },
            )
            else -> state
        }
        if (nextState != state) {
            try {
                synchronized(StateStore.processLock) {
                    store.save(nextState)
                    state = nextState
                }
            } catch (error: IOException) {
                showError("Could not save sync settings: ${error.message}")
                return
            }
        }
        io.execute {
            var blocked = emptyList<BlockedSet>()
            val kind = if (!showResult) {
                SyncNotificationKind.AUTOMATIC
            } else if (profileId != null && setId != null) {
                SyncNotificationKind.SINGLE_SET
            } else {
                SyncNotificationKind.ALL_SETS
            }
            val outcome = try {
                val engine = LocalSyncEngine(contentResolver, store, VersionBackup.forContext(this))
                val result = if (profileId != null && setId != null) {
                    engine.syncSet(profileId, setId, force = showResult)
                } else {
                    engine.syncAll(force = showResult)
                }
                blocked = result.blocked
                if (state.settings.syncPattern == SyncPattern.AGGRESSIVE) {
                    blocked.forEach { item ->
                        item.retryAt?.let { LocalSyncJobService.scheduleRetry(this, item, it) }
                    }
                }
                if (profileId == null || setId == null) {
                    SyncRunTracker.record(this)
                }
                "Sync complete. Updated ${result.updatedSets} set(s)."
            } catch (error: IOException) {
                "Sync failed: ${error.message ?: error.javaClass.simpleName}"
            } catch (error: SecurityException) {
                "Sync failed because access to a selected file expired."
            }
            runOnUiThread {
                // The engine saves on its own; reload so later saves keep its baselines.
                try {
                    synchronized(StateStore.processLock) { state = store.load() }
                } catch (error: Exception) {
                    showError("Saved app data could not be loaded: ${error.message}")
                }
                AppNotifier.sync(this, state.settings, kind, outcome)
                if (showResult || blocked.isNotEmpty()) refresh()
                if (state.settings.syncPattern != SyncPattern.AGGRESSIVE) {
                    val nextRetryAt = blocked.mapNotNull { it.retryAt }.minOrNull()
                    if (nextRetryAt != null) {
                        scheduleForegroundSync(
                            runImmediately = false,
                            delayMillis = (nextRetryAt - System.currentTimeMillis()).coerceAtLeast(0L),
                        )
                    }
                }
                enqueueBlockedWarnings(blocked)
            }
        }
    }

    private fun enqueueBlockedWarnings(blocked: List<BlockedSet>) {
        blocked.forEach { item ->
            if (pendingBlocked.none { it.profileId == item.profileId && it.setId == item.setId }) {
                pendingBlocked.add(item)
            }
        }
        showNextBlockedWarning()
    }

    private fun showNextBlockedWarning() {
        if (warningShowing || isFinishing || showingSettings || currentSetId != null || activeProfile() == null) return
        while (pendingBlocked.isNotEmpty()) {
            val candidate = pendingBlocked.removeAt(0)
            val setStillExists = state.profiles.any { profile ->
                profile.id == candidate.profileId && profile.sets.any { it.id == candidate.setId }
            }
            if (setStillExists) {
                showBlockedWarning(candidate)
                return
            }
        }
    }

    private fun showBlockedWarning(next: BlockedSet) {
        warningShowing = true
        val lines = buildList {
            next.report.nameIssues.forEach {
                add("• Name: \"${it.fileName}\" is only ${percent(it.score)} like its closest match \"${it.bestMatchName}\".")
            }
            next.report.contentIssues.forEach {
                add("• Content: \"${it.fileName}\" shares only ${percent(it.score)} of its text with its closest match \"${it.bestMatchName}\" (needs ${percent(Similarity.CONTENT_THRESHOLD)}).")
            }
        }
        val conflictResolutionMessage = when (state.settings.conflictResolution) {
            ConflictResolution.PAUSE -> "Syncing is paused for this set until you start it again."
            ConflictResolution.RETRY ->
                "The app will check this set again after ${formatMinutes(state.settings.conflictRetryMinutes)}."
        }
        AlertDialog.Builder(this)
            .setTitle("These files may not belong together")
            .setMessage(
                "Set \"${next.setName}\" was not synced.\n\n${lines.joinToString("\n")}\n\n" +
                    "Syncing overwrites files with the newest version. Every version is saved in the backup history.\n\n" +
                    conflictResolutionMessage,
            )
            .setPositiveButton("Sync anyway") { _, _ ->
                warningShowing = false
                approveAndSync(next)
                showNextBlockedWarning()
            }
            .setNegativeButton("Not now") { _, _ ->
                warningShowing = false
                showNextBlockedWarning()
            }
            .setOnCancelListener {
                warningShowing = false
                showNextBlockedWarning()
            }
            .show()
    }

    private fun approveAndSync(blocked: BlockedSet) {
        val profile = state.profiles.firstOrNull { it.id == blocked.profileId } ?: return
        val set = profile.sets.firstOrNull { it.id == blocked.setId } ?: return
        updateSet(profile, set.copy(similarityApproved = true))
        runSync(showResult = true, profileId = profile.id, setId = set.id)
    }

    private fun percent(score: Double) = "${(score * 100).toInt()}%"

    private fun showSettings() {
        showingSettings = true
        render()
    }

    private fun renderSettings(root: LinearLayout) {
        root.addView(versionTag())
        root.addView(actionButton("Home — sets") {
            showingSettings = false
            render()
        })
        root.addView(title("Settings"))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )

        val settings = state.settings
        content.addView(title("General"))
        content.addView(body("Sync behavior and conflict handling"))
        val pattern = spinner(
            listOf(
                "Aggressive — background checks while Android allows",
                "Lazy — check while the app is open",
                "Inattentive — check while the app is open and focused",
                "Stubborn — sync only when manually requested",
            ),
            settings.syncPattern.ordinal,
        )
        content.addView(body("Sync pattern"))
        content.addView(pattern)

        val conflictResolution = spinner(
            listOf(
                "Stop syncing until manually started",
                "Retry after the selected interval",
            ),
            if (settings.conflictResolution == ConflictResolution.PAUSE) 0 else 1,
        )
        content.addView(body("Resolution mode"))
        content.addView(conflictResolution)

        val syncInterval = intervalControl(
            content,
            "Sync interval",
            settings.syncIntervalMinutes,
            SYNC_INTERVAL_PRESETS,
            15,
        )
        content.addView(body("Android background periodic jobs cannot run more frequently than every 15 minutes."))
        val conflictInterval = intervalControl(
            content,
            "Conflict retry interval",
            settings.conflictRetryMinutes,
            CONFLICT_INTERVAL_PRESETS,
            1,
        )
        val gainFocus = settingSwitch(content, "Bring the app to the foreground on conflict", settings.gainFocusOnConflict)
        content.addView(body("Android may prevent the app from opening automatically while it is in the background."))
        val comments = settingSwitch(content, "Enable private comments", settings.commentsEnabled)

        content.addView(title("Sync notifications"))
        val automaticSync = settingSwitch(content, "Automatic syncs", settings.autoSyncNotifications)
        val singleSync = settingSwitch(content, "Manual single-set syncs", settings.singleSetSyncNotifications)
        val allSync = settingSwitch(content, "Manual all-sets syncs", settings.allSetsSyncNotifications)
        content.addView(body("Delivery methods"))
        val syncToast = settingSwitch(content, "Toast message", settings.syncToast)
        val syncVibration = settingSwitch(content, "Vibration", settings.syncVibration)
        val syncSound = settingSwitch(content, "Sound", settings.syncSound)

        content.addView(title("Change notifications"))
        val setCreated = settingSwitch(content, "Set created", settings.setCreatedNotifications)
        val fileAdded = settingSwitch(content, "File added to an existing set", settings.fileAddedNotifications)
        content.addView(body("Delivery methods"))
        val changeToast = settingSwitch(content, "Toast message", settings.changeToast)
        val changeVibration = settingSwitch(content, "Vibration", settings.changeVibration)
        val changeSound = settingSwitch(content, "Sound", settings.changeSound)

        content.addView(title("Similarity checks"))
        val checkNames = settingSwitch(content, "Warn when file names don't look alike", state.similarity.checkNames)
        val checkContent = settingSwitch(content, "Warn when file contents don't look alike", state.similarity.checkContent)

        content.addView(title("Theme"))
        content.addView(body("Theme: Default. Theme selection is reserved for a later update."))

        conflictResolution.onItemSelectedListener = selectionListener {
            conflictInterval.container.visibility = if (it == 1) View.VISIBLE else View.GONE
        }
        conflictInterval.container.visibility =
            if (settings.conflictResolution == ConflictResolution.RETRY) View.VISIBLE else View.GONE

        root.addView(actionButton("Save settings") {
            val syncMinutes = syncInterval.readMinutes()
            val retryMinutes = conflictInterval.readMinutes()
            if (syncMinutes == null || retryMinutes == null) {
                Toast.makeText(
                    this,
                    "Enter a valid interval from 1 minute up to 365 days. Sync checks must be at least 15 minutes apart.",
                    Toast.LENGTH_LONG,
                ).show()
                return@actionButton
            }
            val nextSettings = AppSettings(
                syncPattern = SyncPattern.entries[pattern.selectedItemPosition],
                conflictResolution = if (conflictResolution.selectedItemPosition == 0) {
                    ConflictResolution.PAUSE
                } else {
                    ConflictResolution.RETRY
                },
                syncIntervalMinutes = syncMinutes,
                conflictRetryMinutes = retryMinutes,
                gainFocusOnConflict = gainFocus.isChecked,
                commentsEnabled = comments.isChecked,
                autoSyncNotifications = automaticSync.isChecked,
                singleSetSyncNotifications = singleSync.isChecked,
                allSetsSyncNotifications = allSync.isChecked,
                syncToast = syncToast.isChecked,
                syncVibration = syncVibration.isChecked,
                syncSound = syncSound.isChecked,
                setCreatedNotifications = setCreated.isChecked,
                fileAddedNotifications = fileAdded.isChecked,
                changeToast = changeToast.isChecked,
                changeVibration = changeVibration.isChecked,
                changeSound = changeSound.isChecked,
                theme = settings.theme,
            )
            showingSettings = false
            updateState(
                state.copy(
                    settings = nextSettings,
                    similarity = SimilaritySettings(checkNames.isChecked, checkContent.isChecked),
                ),
            )
            scheduleForegroundSync(runImmediately = true)
        })
    }

    private fun settingSwitch(parent: LinearLayout, label: String, checked: Boolean) =
        Switch(this).apply {
            text = label
            isChecked = checked
            setPadding(0, dp(4), 0, dp(4))
            parent.addView(this)
        }

    private fun spinner(items: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_item,
            items,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected)
    }

    private data class IntervalControl(
        val container: LinearLayout,
        val readMinutes: () -> Long?,
    )

    private fun intervalControl(
        parent: LinearLayout,
        label: String,
        initialMinutes: Long,
        presets: List<Long>,
        minimum: Long,
    ): IntervalControl {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(body(label))
        val labels = presets.map(::formatMinutes) + "Custom"
        val choice = spinner(labels, presets.indexOf(initialMinutes).takeIf { it >= 0 } ?: presets.size)
        container.addView(choice)
        val customValue = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter a number"
            val initialIsPreset = presets.contains(initialMinutes)
            setText(if (initialIsPreset) "" else if (initialMinutes % 60L == 0L) {
                (initialMinutes / 60L).toString()
            } else {
                initialMinutes.toString()
            })
        }
        val customUnit = spinner(listOf("Minutes", "Hours"), if (initialMinutes % 60L == 0L && initialMinutes >= 60L) 1 else 0)
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(customValue, LinearLayout.LayoutParams(0, -2, 1f))
            addView(customUnit, LinearLayout.LayoutParams(0, -2, 1f))
            visibility = if (choice.selectedItemPosition == presets.size) View.VISIBLE else View.GONE
        }
        container.addView(customRow)
        choice.onItemSelectedListener = selectionListener {
            customRow.visibility = if (it == presets.size) View.VISIBLE else View.GONE
        }
        parent.addView(container)
        return IntervalControl(container) {
            if (choice.selectedItemPosition < presets.size) {
                presets[choice.selectedItemPosition]
            } else {
                val value = customValue.text.toString().toLongOrNull() ?: return@IntervalControl null
                val multiplier = if (customUnit.selectedItemPosition == 1) 60L else 1L
                if (value < 1 || value > Long.MAX_VALUE / multiplier) return@IntervalControl null
                (value * multiplier).takeIf { it in minimum..MAX_INTERVAL_MINUTES }
            }
        }
    }

    private fun selectionListener(onSelected: (Int) -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) = onSelected(position)

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    private fun formatMinutes(minutes: Long): String {
        if (minutes % 60L != 0L) return "$minutes minutes"
        val hours = minutes / 60L
        return "$hours hour${if (hours == 1L) "" else "s"}"
    }

    private fun scheduleForegroundSync(runImmediately: Boolean, delayMillis: Long? = null) {
        foregroundHandler.removeCallbacks(foregroundSync)
        if (!SyncSettingsPolicy.shouldRunWhileForeground(state.settings.syncPattern, hasWindowFocus)) return
        if (runImmediately) {
            foregroundHandler.post(foregroundSync)
        } else {
            foregroundHandler.postDelayed(
                foregroundSync,
                delayMillis ?: state.settings.syncIntervalMinutes * 60_000L,
            )
        }
    }

    private fun scheduleSync() {
        val scheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
        if (!SyncSettingsPolicy.shouldScheduleBackground(state.settings.syncPattern)) {
            scheduler.cancel(SYNC_JOB_ID)
            return
        }
        val component = ComponentName(this, LocalSyncJobService::class.java)
        val job = JobInfo.Builder(SYNC_JOB_ID, component)
            .setPersisted(true)
            .setPeriodic(state.settings.syncIntervalMinutes * 60_000L)
            .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            showError("Android could not schedule background syncing. Check the device's battery or job-scheduling restrictions.")
        }
    }

    private fun updateSet(profile: Profile, set: SyncSet) =
        updateProfile(profile.copy(sets = profile.sets.map { if (it.id == set.id) set else it }))

    private fun updateProfile(profile: Profile) =
        updateState(state.copy(profiles = state.profiles.map { if (it.id == profile.id) profile else it }))

    private fun updateState(next: AppState) {
        val settingsChanged = next.settings != state.settings
        synchronized(StateStore.processLock) {
            try {
                store.save(next)
                state = next
                refresh()
                if (settingsChanged) scheduleSync()
            } catch (error: IOException) {
                showError("Could not save app data: ${error.message}")
            }
        }
    }

    private fun refresh() {
        if (::store.isInitialized) {
            if (currentSetId == null) render() else renderCurrentSet()
        }
    }

    private fun activeProfile(): Profile? =
        state.profiles.firstOrNull { it.id == state.activeProfileId }

    private fun showError(message: String) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle("File Sync").setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    private fun showConflictLaunchMessage(intent: Intent?) {
        val setId = intent?.getStringExtra(LocalSyncJobService.EXTRA_CONFLICT_SET_ID) ?: return
        val setName = intent.getStringExtra(LocalSyncJobService.EXTRA_CONFLICT_SET_NAME) ?: "a set"
        intent.removeExtra(LocalSyncJobService.EXTRA_CONFLICT_SET_ID)
        intent.removeExtra(LocalSyncJobService.EXTRA_CONFLICT_SET_NAME)
        window.decorView.post {
            if (isFinishing) return@post
            AlertDialog.Builder(this)
                .setTitle("Sync conflict")
                .setMessage("The files in \"$setName\" may not belong together. Review the set before syncing.")
                .setPositiveButton("Review set") { _, _ ->
                    val profile = state.profiles.firstOrNull { candidate ->
                        candidate.sets.any { it.id == setId }
                    }
                    if (profile != null) {
                        state = state.copy(activeProfileId = profile.id)
                        currentSetId = setId
                        render()
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTextColor(android.graphics.Color.BLACK)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(8), 0, dp(12))
    }

    private fun versionTag() = TextView(this).apply {
        text = "SyncAssist · v${BuildConfig.VERSION_NAME}"
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.rgb(52, 70, 110))
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(8))
        contentDescription = "SyncAssist version ${BuildConfig.VERSION_NAME}"
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun mimeType(extension: String): String = when (extension) {
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "html" -> "text/html"
        "py" -> "text/x-python"
        "js" -> "text/javascript"
        "java" -> "text/x-java-source"
        else -> "*/*"
    }

    companion object {
        private const val STATE_CURRENT_SET_ID = "currentSetId"
        private const val STATE_PENDING_SET_ID = "pendingSetId"
        private const val REQUEST_FILES = 21
        private const val SYNC_JOB_ID = 51429
        private const val MAX_INTERVAL_MINUTES = 525_600L
        private val SYNC_INTERVAL_PRESETS = listOf(15L, 30L, 60L, 300L, 720L, 1440L)
        private val CONFLICT_INTERVAL_PRESETS = listOf(5L, 10L, 15L, 30L, 60L, 300L, 720L, 1440L)
        private val SUPPORTED_TYPES = setOf("txt", "md", "html", "py", "js", "java")
    }
}
