# File Sync for Android (local MVP)

Android application ID and Kotlin package: `com.ntolb.syncassist`.

This first milestone implements local profiles and sets, lets users select local
UTF-8 text files through Android's document picker, stores app data in
`files/sets.json`, and synchronizes the selected files with a last-change-wins
policy. It does not connect to Google Drive or use OAuth. See [ROADMAP.md](./ROADMAP.md)
for the planned phases and the requirements explicitly deferred from this MVP.

Supported text extensions in this MVP are `.txt`, `.md`, `.html`, `.py`, `.js`,
and `.java`. Other formats, including DOC/DOCX, PDF, and Google Docs, are outside
this MVP. Their future support is expected to use format conversion; the
requirement is to preserve the text exactly, with formatting and rendering
metadata handled separately as described in the roadmap. A `.gdoc` is a
link/shortcut to an online Google document, not a local document copy.

## Sync behavior and limitations

- For the exact execution order of file selection/validation, similarity
  scoring, source selection, overwrites, backups, and failure handling, see
  [SYNC-ALGORITHM.md](./SYNC-ALGORITHM.md).
- Each set needs at least two files to synchronize.
- A newly created set stays paused from automatic syncing until the user
  manually syncs that set. The global **Sync now** action explicitly enables
  and syncs all sets; a per-set **Sync** action affects only that set.
- Choose a master version for each set. On first sync, its text initializes
  every file in that set, avoiding a timestamp-based guess between files that
  were all added at once. The master is not used as the ongoing source of truth.
- Files are decoded as strict UTF-8. Malformed UTF-8 is rejected instead of
  being silently replaced with different characters; a UTF-8 byte-order mark
  is preserved as part of the synchronized text.
- After initialization, when files differ from the saved baseline, the app
  uses the newest last-modified time reported by the document provider and
  writes that entire text to the other files in the set. This is
  last-change-wins, not line-by-line merging.
- A file added after the set's first sync has no baseline yet, so it is
  considered changed and competes by its provider modification time; it is not
  automatically overwritten from the original master.
- Providers that do not report modification times may cause ties; ties are
  resolved deterministically by file order in the set.
- Before a set's first sync (and again after files are added), the app checks
  that its files look alike. File names must be loosely similar (about 50%) and
  at least 70% of each file's text must also appear in another file in the set;
  content is compared by sentence/line-level word runs, so moved paragraphs and
  punctuation/case differences still match. For text with fewer than five
  meaningful words, the check instead measures shared meaningful words against
  the longer text; a missing word-pattern match is never treated as automatic
  approval. Common English words do not inflate the score, while very short
  text made only of common words matches only when its words are the same.
  If a check fails, the set is not synced and a warning offers "Sync anyway".
  Approval is remembered for the set. Both checks can be turned off in the
  menu's Settings.
- Empty (or whitespace-only) files skip the checks and are handled first: they
  cannot be the master, cannot win a sync, and are always filled from a file
  that has text. If every file in a set is empty, syncing pauses until one is
  written to, then the others are filled without any checks.
- Every version of the synced files is kept: before and after any overwrite the
  app commits the contents to a per-profile Git repository in app-private
  storage (`files/backups/<profile id>`), using the bundled JGit library
  because Android apps have no `git` command. History is not yet browsable in
  the app.
- The MVP syncs when opened/resumed and schedules Android background work at
  the platform minimum periodic interval (15 minutes). Android may defer jobs;
  it does not promise instant or uninterrupted background sync. The product
  goal for later phases is to sync as often as the platform permits and to
  reconcile after interruptions using the most-recent-change-wins policy.
- Files are selected with Android's Storage Access Framework. Access is stored
  as a persisted `content://` URI grant rather than a raw filesystem path;
  Android scoped storage does not give apps reliable access to arbitrary paths.
- Selected document providers must grant read/write access and retain the URI
  permission for background jobs to synchronize successfully.
- Opening a file in an external editor uses an optional Android “display over
  other apps” permission to show a **Return to SyncAssist** button. The app
  requests that special permission before launching the editor.

## Build

Open this folder in Android Studio and sync the Gradle project, or use the
included Gradle wrapper from PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

JDK 17, Android SDK Platform 35, and Android Build Tools 35.0.0 are required.
The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The current app version is maintained in the root `gradle.properties` as
`app.versionName` and `app.versionCode`; update both for each new packaged
app build and record the build in [BUILD-VERSIONS.md](./BUILD-VERSIONS.md).
The version name is user-facing, while the integer version code must increase
for every packaged build.
