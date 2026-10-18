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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: StateStore
    private val io = Executors.newSingleThreadExecutor()
    private var state = AppState()
    private var currentSetId: String? = null
    private var pendingSetId: String? = null
    private var pendingExternalFile: SyncFile? = null
    private var pendingExternalEdit: Pair<String, String>? = null
    private var awaitingOverlayPermission = false
    private val pendingBlocked = mutableListOf<BlockedSet>()
    private var warningShowing = false

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
        scheduleSync()
        render()
        if (SyncRunTracker.shouldCatchUp(this)) runSync(showResult = false)
    }

    override fun onResume() {
        super.onResume()
        ReturnToAppOverlayService.stop(this)
        val externalEdit = pendingExternalEdit
        pendingExternalEdit = null
        if (::store.isInitialized && externalEdit != null) {
            runSync(showResult = false, profileId = externalEdit.first, setId = externalEdit.second)
        }
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            val file = pendingExternalFile
            pendingExternalFile = null
            if (Settings.canDrawOverlays(this) && file != null) {
                launchExternalEditor(file)
            } else if (file != null) {
                showError("Overlay permission is required to show the return-to-app button.")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT_SET_ID, currentSetId)
        outState.putString(STATE_PENDING_SET_ID, pendingSetId)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Use OnBackPressedDispatcher for production builds.")
    override fun onBackPressed() {
        if (currentSetId != null) {
            currentSetId = null
            render()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
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
        if (activeProfile == null) {
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
        root.addView(actionButton("← Back to sets") {
            currentSetId = null
            render()
        })
        root.addView(title(set.name))
        root.addView(body("Local UTF-8 text files only: .txt, .md, .html, .py, .js, .java"))
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
            startActivityForResult(intent, REQUEST_FILES)
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
        updateSet(
            profile,
            set.copy(
                files = merged,
                similarityApproved = set.similarityApproved && merged.size == set.files.size,
            ),
        )
        val addedCount = merged.size - set.files.size
        if (addedCount > 0) {
            Toast.makeText(this, "Added $addedCount file(s) to ${set.name}.", Toast.LENGTH_LONG).show()
        } else if (uris.isNotEmpty()) {
            Toast.makeText(this, "No new supported files were added.", Toast.LENGTH_LONG).show()
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
            var shouldNotify = showResult
            var blocked = emptyList<BlockedSet>()
            val outcome = try {
                val engine = LocalSyncEngine(contentResolver, store, VersionBackup.forContext(this))
                val result = if (profileId != null && setId != null) {
                    engine.syncSet(profileId, setId)
                } else {
                    engine.syncAll()
                }
                blocked = result.blocked
                if ((profileId == null || setId == null) && blocked.isEmpty()) {
                    SyncRunTracker.record(this)
                }
                "Sync complete. Updated ${result.updatedSets} set(s)."
            } catch (error: IOException) {
                shouldNotify = true
                "Sync failed: ${error.message ?: error.javaClass.simpleName}"
            } catch (error: SecurityException) {
                shouldNotify = true
                "Sync failed because access to a selected file expired."
            }
            runOnUiThread {
                // The engine saves on its own; reload so later saves keep its baselines.
                try {
                    synchronized(StateStore.processLock) { state = store.load() }
                } catch (error: Exception) {
                    showError("Saved app data could not be loaded: ${error.message}")
                }
                if (shouldNotify) Toast.makeText(this, outcome, Toast.LENGTH_LONG).show()
                if (shouldNotify || blocked.isNotEmpty()) refresh()
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
        if (warningShowing || isFinishing || currentSetId != null || activeProfile() == null) return
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
        AlertDialog.Builder(this)
            .setTitle("These files may not belong together")
            .setMessage(
                "Set \"${next.setName}\" was not synced.\n\n${lines.joinToString("\n")}\n\n" +
                    "Syncing overwrites files with the newest version. Every version is saved in the backup history, " +
                    "and you won't be asked again for this set unless you add more files.",
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
        val checks = booleanArrayOf(state.similarity.checkNames, state.similarity.checkContent)
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMultiChoiceItems(
                arrayOf(
                    "Warn when file names don't look alike",
                    "Warn when file contents don't look alike",
                ),
                checks,
            ) { _, which, checked -> checks[which] = checked }
            .setPositiveButton("Save") { _, _ ->
                updateState(state.copy(similarity = SimilaritySettings(checks[0], checks[1])))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleSync() {
        val scheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
        val component = ComponentName(this, LocalSyncJobService::class.java)
        val job = JobInfo.Builder(SYNC_JOB_ID, component)
            .setPersisted(true)
            .setPeriodic(SYNC_INTERVAL_MS)
            .build()
        scheduler.schedule(job)
    }

    private fun updateSet(profile: Profile, set: SyncSet) =
        updateProfile(profile.copy(sets = profile.sets.map { if (it.id == set.id) set else it }))

    private fun updateProfile(profile: Profile) =
        updateState(state.copy(profiles = state.profiles.map { if (it.id == profile.id) profile else it }))

    private fun updateState(next: AppState) {
        synchronized(StateStore.processLock) {
            try {
                store.save(next)
                state = next
                refresh()
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
        private const val SYNC_INTERVAL_MS = SyncRunTracker.SYNC_INTERVAL_MS
        private val SUPPORTED_TYPES = setOf("txt", "md", "html", "py", "js", "java")
    }
}
