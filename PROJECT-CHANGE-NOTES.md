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

The local MVP syncs on app open/resume and schedules periodic work subject to
Android's minimum interval and execution limits. Later work should improve
retry, recovery, sync status, and reconciliation after provider or device
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

## Version record

The packaged app version is maintained as `app.versionName` and
`app.versionCode` in the root `gradle.properties`. For every new packaged app
build, increment the version code, update the version name, and add its purpose
to [BUILD-VERSIONS.md](./BUILD-VERSIONS.md). Do not bump versions merely for
unit-test or development tasks that do not package the app.
