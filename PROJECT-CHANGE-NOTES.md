# Project change notes

This is a read-back index of agreed future work and potential changes discussed
for the project. It is not a replacement for the full requirements in
[ROADMAP.md](./ROADMAP.md). Testing observations, outcomes, and statuses are
tracked separately in [TESTING-NOTES.md](./TESTING-NOTES.md).

## Agreed future work

### Replace the sync-test scaffolding UI

The current UI exists to exercise the file-sync logic and is not the final
interface. Replace the repeated per-file controls with a grid or list of file
icons, selection/highlighting, and a shared action area that acts on the
selected file. The broader screens and navigation remain guided by the final
scope document; see Phase 5 in the roadmap. Keep behavior testable while the
UI is replaced.

### Complete file-history recovery

The app records per-profile Git snapshots before and after overwrites using
JGit in app-private storage. Browsing and restoring prior versions from the app
are not implemented yet. Add recovery UI and validate restoration without
weakening the rule that a sync must stop if its safety snapshot fails.

### Improve background reliability

The local MVP schedules periodic work subject to Android's minimum interval
and execution limits, and catches up at app launch when the last completed
all-set run is at least one interval old. Later work should improve retry,
recovery, sync status, and reconciliation after provider or device
interruptions; do not promise continuous or instant sync where Android cannot
provide it.

### Add format conversion and cloud integrations

The agreed direction is to preserve text exactly through a canonical TXT
representation, with one import and one export path per supported format.
Formatting positions and shared rendering rules belong in set metadata.
DOC/DOCX, PDF, Google Drive, and Google Docs remain future work; `.gdoc` is a
link to an online document, not a local text file. See Phases 2-4 in the
roadmap.

## Similarity checks: implementation and validation

Content similarity retains the 70% threshold and sentence/line-level word-run
comparison for ordinary text. Empty and whitespace-only files keep their
special sync policy: they do not act as sources, empty sets wait, and empty
files are filled from text-bearing files before similarity checking.

Very short non-empty text now uses meaningful-word overlap rather than relying
on shingles. Common English words do not inflate that score, and unrelated
single-word files do not pass just because no shingles can be formed. This
policy has targeted unit tests. Manual testing with the user's intended text
patterns remains useful; any proposal to tune the threshold or tokenization
should be recorded as a new observation in `TESTING-NOTES.md` before changing
the behavior.

## Recent sync workflow changes

- Newly created sets start paused from automatic app-resume and scheduled sync.
  The user can start one set explicitly or use the global Sync now action to
  enable and sync all sets. Previously saved sets default to enabled when the
  new persisted field is absent.
- Blank and whitespace-only file contents are excluded inside the similarity
  evaluator from both filename and content checks, so callers cannot
  accidentally generate warnings for blank entries.
- A targeted one-set sync entry point is available separately from sync-all.
- Opening a file requests Android's explicit “display over other apps”
  permission and, if granted, shows a temporary **Return to SyncAssist**
  overlay while the external editor is open.
- Set-editing screens have an explicit **Home — sets** control that does not
  depend on device Back. The **Return to SyncAssist** overlay is shown over
  Android's document picker and external editors; sets can be deleted without
  deleting their underlying documents, and picker selections are confirmed
  when added.
- Every in-app screen and the external-screen return overlay displays the
  loaded app version to support emulator testing.
- A scheduled all-set run with mismatch warnings does not advance the
  last-successful-sync time; the next app launch performs a foreground
  catch-up so its warning can be shown. Warnings are deferred until the sets
  list is visible and include the affected set and mismatch details.

## Application identity

The Kotlin/Java package, Android namespace, and application ID are
`com.ntolb.syncassist`. The earlier `com.teches.filesync` identity belonged to
another project and must not be reintroduced. Changing the application ID
creates a separate Android installation with separate private state; preserve
old installs unless the user explicitly requests removal or data migration.

## Version record

The packaged app version is maintained as `app.versionName` and
`app.versionCode` in the root `gradle.properties`. For every new packaged app
build, increment the version code, update the version name, and add its purpose
to [BUILD-VERSIONS.md](./BUILD-VERSIONS.md). Do not bump versions merely for
unit-test or development tasks that do not package the app.
