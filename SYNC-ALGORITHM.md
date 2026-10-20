# File validation, similarity checks, and sync algorithm

This document describes the behavior implemented in the current local-text MVP,
traced from the UI and background-job entry points through validation,
similarity scoring, source selection, overwrites, backups, and saved state.
It is a description of current code, not a claim that every proposal in
`personal/scratch` has been implemented.

## Scope

- Sync operates on the local files explicitly added to sets. Google Drive,
  Google Docs, DOC/DOCX, and PDF support are not implemented in this MVP.
- The supported extensions are `.txt`, `.md`, `.html`, `.py`, `.js`, and
  `.java`. Regardless of extension, each selected file is read and written as
  UTF-8 plain text. There is no language parser, document converter, or
  format-specific content extraction.
- The set stores a document-provider `content://` URI, not an ordinary
  filesystem path. The provider must keep the app's permission and support
  reading and writing that URI.
- Sync means copying one whole file's text over the other files in its set.
  This is not a line-by-line or paragraph merge.

Relevant implementation:
[MainActivity.kt](./app/src/main/java/com/ntolb/syncassist/MainActivity.kt),
[LocalSyncEngine.kt](./app/src/main/java/com/ntolb/syncassist/LocalSyncEngine.kt),
[Similarity.kt](./app/src/main/java/com/ntolb/syncassist/Similarity.kt),
[Utf8Text.kt](./app/src/main/java/com/ntolb/syncassist/Utf8Text.kt),
[StateStore.kt](./app/src/main/java/com/ntolb/syncassist/StateStore.kt), and
[VersionBackup.kt](./app/src/main/java/com/ntolb/syncassist/VersionBackup.kt).

## How files enter a set

1. In the set editor, **Add local files** opens Android's document picker with
   `ACTION_OPEN_DOCUMENT`, `CATEGORY_OPENABLE`, and multi-select enabled. The
   MIME request is `*/*`; the app does not rely on the picker to filter types.
   The app starts the temporary **Return to SyncAssist** overlay before
   opening the picker, so returning to the app does not depend on device Back.
2. On return, a cancelled result, missing result data, or missing pending set
   causes the handler to stop without changing the set.
3. The handler collects the selected URIs from `ClipData` (or the single
   `data` URI) and removes duplicate URIs in that selection.
4. It asks the provider for `DISPLAY_NAME`, falling back to the URI's final
   path segment and then `"file"` if no display name is available.
5. It takes the lowercase extension after the last dot. Only the six
   extensions listed above are accepted. An unsupported item gets a toast and
   is skipped; the other selected files can still be added.
6. For each supported file, the app tries to persist the read/write URI grant
   returned by Android. If permission persistence throws `SecurityException`,
   that file is not added and an error dialog is shown. The app does not read
   the file or test that it is writable during selection.
7. Successfully accepted entries start with an empty baseline hash. New
   entries are merged with existing set entries by URI, so an already-present
   URI is not duplicated.
8. If the number of files increased, previous similarity approval is cleared.
   Otherwise approval is retained. Removing a file does not clear approval.
   The stored name and extension are the values captured when the file was
   added; this MVP does not refresh them if a provider-side rename occurs.
9. A newly created set starts with syncing disabled. It remains excluded from
   automatic app-resume and scheduled sync until the user manually syncs that
   set or explicitly runs the global **Sync now** action. The per-set manual
   action enables only that set; the global action enables every set before
   syncing.

Choosing a file as master stores its URI. The UI permits selecting any file;
the engine validates whether that master can be used at first sync. Opening a
file sends an `ACTION_EDIT` intent with the stored MIME type and URI read/write
grants. If no installed app can handle that intent, the user gets an error.

## When sync runs

Sync runs from these entry points:

1. Returning from an external editor starts a silent, targeted sync for the
   edited file's set. It does not enable a paused set. The **Sync now** and
   **Sync this set now** buttons enable the relevant sets and start a sync with
   a completion or failure toast.
2. `MainActivity.onCreate()` schedules a persisted Android `JobScheduler` job
   at a fixed 15-minute periodic interval. Android can defer jobs. The job
   service calls the same sync engine across all saved profiles and sets.
3. At app launch, `MainActivity` checks the timestamp of the last completed,
   unblocked all-set sync. It immediately starts a catch-up sync if none has
   completed or at least one 15-minute interval has elapsed since it
   completed. Scheduled jobs and foreground sync-all runs record this
   timestamp only when no set was blocked; a targeted one-set sync does not.
   This ensures a background similarity warning is surfaced by a foreground
   catch-up run rather than being silently lost.

Ordinary activity resumes—including returning from the file picker—do not
start a sync. Newly created sets therefore remain uninspected while the user is
adding files and preparing the set, until a manual sync action enables them.
The engine also skips any set with fewer than two stored files before reading
file contents or running similarity checks.

Similarity warning results are queued in the activity and shown only while the
sets list is visible, never over the set editor. Returning to the sets list
shows the set name and each mismatch's filename, closest match, measured score,
and threshold. Android's document picker owns its own navigation; its Back
control returns to the set editor, and selected supported documents are added
to that set when the picker returns.

The activity uses a single-thread executor. The job service has its own
single-thread executor. `LocalSyncEngine.syncAll()` and `syncSet(profileId,
setId)` synchronize on the shared `StateStore.processLock`, so engine runs
coordinate with each other and state reads/writes using that lock.

## Sync algorithm, in execution order

### 1. Load saved state and visit every set

`syncAll()` loads the app state, including every profile, set, similarity
settings, file URI, baseline hash, master URI, and the set's
`similarityApproved` and `syncEnabled` flags. It visits every set in every
profile, but skips sets whose `syncEnabled` is false; it does not restrict
background work to the currently active profile. The targeted `syncSet()`
loads the same full state but processes only the requested profile/set,
preserving all other sets in saved state.

Sets with fewer than two stored files are skipped without reading their files.

**NOTE:** This is where/how files with 0 or tiny text amount should be filtered out.

### 2. Read and observe every file in an eligible set

For each file in set order, the engine:

1. Parses its stored URI.
2. Opens an input stream through `ContentResolver`. A missing stream fails the
   sync with an `IOException`.
3. Decodes the complete stream as strict UTF-8. Malformed or unmappable byte
   sequences fail rather than being silently replaced. A UTF-8 BOM is retained
   as a character in the text.
4. Parses the private comment markers `!!!>` and `<!!!`. The markers and all
   enclosed text are removed from sync text. Whitespace immediately around a
   comment is represented in sync text by one newline if it contained a
   newline, otherwise one space if it contained whitespace. Each comment
   separately records the exact surrounding whitespace, the next
   non-whitespace text as its anchor, and whether it occupies its own line. An
   unmatched or nested marker is an `IOException` for the set. All files are
   parsed before any write to that set occurs.
5. Computes SHA-256 over the comment-free sync text re-encoded as UTF-8. The
   hash is used to compare current text with the saved baseline; comment-only
   edits do not make a file changed.
6. Queries the provider's `COLUMN_LAST_MODIFIED`. If a usable value is not
   returned, it performs a display-name query and uses timestamp `0`; if that
   also yields nothing, it still uses `0`.
7. Stores an observation containing the set index, original text, comment-free
   sync text, comments and anchors, current hash, modification time, and saved
   baseline hash.

If reading any file fails, that set cannot continue through validation and
source selection.

### 3. Classify empty files before similarity checking

The comment-free sync text's `isBlank()` defines empty: zero-length text,
whitespace-only text, and text containing only comments are empty. The engine
removes these observations from the similarity-check inputs before running
either check.

- **All files empty:** return for this set immediately. No similarity warning,
  master validation, overwrite, baseline update, or backup occurs. The blank
  baselines leave an uninitialized set in its initial state. A later run can
  proceed after text is entered in at least one file.
- **Exactly one file has text:** similarity checks are skipped. On initial
  sync, that one non-empty file is selected as source without requiring a
  master; the empty files are filled from it.
- **Two or more files have text:** proceed to similarity checks if approval is
  not already stored for the set. On initial sync, an empty master is rejected
  rather than allowed to overwrite the non-empty files.
- On a later sync, empty files are never allowed to win. If at least one
  non-empty file remains, an empty file can be repaired from a non-empty
  source even if none of the non-empty files differs from its baseline.

The `Similarity.content()` function itself also treats either blank argument
as a full match, but ordinary sync excludes blank files first. That
standalone-function behavior therefore does not determine the engine's
empty-file policy.

### 4. Decide whether to run similarity checks

Similarity checking occurs only when at least two files have non-whitespace
comment-free text and `similarityApproved` is false. It happens before master
validation and before any backup or overwrite. Comment text is not passed to
the name/content similarity evaluator; names remain the actual filenames.

`SimilarityCheck.evaluate()` also filters blank/whitespace-only content before
comparing names or text. It requires exactly one content value per filename;
misaligned inputs are rejected rather than silently paired incorrectly.

The saved app settings independently enable or disable the name check and the
content check. Disabling a check omits its issues; if no enabled check reports
an issue, the set is approved for future syncs.

For each file, the algorithm compares it with every other text-bearing file
and finds its best-scoring peer. A file is flagged only if even its best peer
is below the applicable threshold. Thus, a file need not resemble every other
file; it must resemble at least one other file. In a two-file set, each file
is the other's only peer.

#### 4a. Name score

For each filename:

1. Remove the suffix after the last dot (the extension).
2. Lowercase and extract Unicode letter/digit runs. Punctuation and spaces
   separate tokens.
3. If either resulting joined name is empty, score `0`.
4. Compute three scores:
   - Jaccard similarity of the sets of name tokens.
   - `1.0` if either joined name contains the other; otherwise `0.0`.
   - `1 - LevenshteinDistance / longerJoinedNameLength`.
5. Use the maximum of those scores.

The name threshold is fixed at `0.50`. A best-peer name score below 50% is a
name issue.

#### 4b. Content score

For each text pair:

1. If either entire input is blank, return `1.0`. In normal sync this case is
   already removed before checking.
2. Lowercase and extract Unicode letter/digit runs. These are the normalized
   words; punctuation is discarded.
3. Filter a fixed list of common English words to get each text's
   **meaningful-word** list.
4. If the shorter meaningful-word list contains fewer than five words, use
   multiset token overlap instead of shingles:
   - Count each meaningful word in each file.
   - Sum the shared counts, using the smaller count for each word.
   - Divide by the larger meaningful-word count.
   - If both meaningful-word lists are empty, return `1.0` only if the complete
     normalized word sequences are identical and non-empty; otherwise return
     `0.0`. If only one meaningful-word list is empty, return `0.0`.
5. Otherwise split text at whitespace following `.`, `!`, or `?`, and at
   newline boundaries. The punctuation itself is not part of words. This lets
   sentence/line units match independently of their position in the file.
6. Within each segment, extract normalized words and make overlapping word
   windows. Window width is five, or the entire segment if it has two to four
   words. Segments with fewer than two words make no windows.
7. Keep a window only if at least two words in that window are not in the
   common-word list. Count duplicate window occurrences.
8. Intersect the two window-count maps by summing the minimum occurrence count
   for each shared window. Divide by the larger total number of windows.
9. If either side has no qualifying windows, fall back to the short-text
   meaningful-word overlap described in step 4.

The content threshold is fixed at `0.70`. Each file must have at least one
peer whose score is 70% or higher. Case, punctuation, and segment position do
not directly matter to normalized tokens/windows; moved sentences or lines can
still match. The score is based on the larger count of meaningful words in
the short-text fallback, or qualifying shingles in the normal path.

#### 4c. What happens on a failed check

If any enabled check reports issues, the engine returns the original set and
the report immediately. It has not chosen a source, backed up files, written
files, or advanced baselines. The UI displays each failing file, its best
match, and a percentage (truncated to an integer).

- **Sync anyway:** mark that set `similarityApproved = true`, save the state,
  then run sync again. The approval is remembered and prevents another
  similarity prompt for that set. Adding another distinct file clears it.
- **Not now:** leave the set unchanged and proceed to the next blocked set in
  the current prompt queue.
- Cancelling/dismissing the dialog leaves the blocked set unchanged.

Similarity is not rerun on every subsequent content edit once a set is
approved. Removing files also does not revoke approval in the current UI.

### 5. Determine whether this is the initial sync and validate the master

A set is initial only if it has at least two observations and **every** saved
baseline hash is blank. A set with any nonblank baseline is treated as an
established set, even if other files have no baseline.

If initial and more than one file contains text:

1. Find the stored master by matching `masterFileLocation` to a file URI.
2. If there is no matching file, stop with a “choose a master” error.
3. If the selected master is empty, stop with a “choose a master that has
   text” error.

If exactly one file contains text, the master is not required. Master
validation follows similarity checking, so a failed similarity report is
shown before any missing/empty-master error.

### 6. Select the source text

- **Initial set with multiple non-empty files:** use the selected non-empty
  master. It seeds the set only for first sync.
- **Initial set with exactly one non-empty file:** use that sole text-bearing
  file, regardless of master selection.
- **Established set:** among non-empty files whose current hash differs from
  their saved baseline, choose the one with the greatest provider
  last-modified time.
- A file's comments do not affect emptiness, change detection, or source
  selection.
- If changed-file timestamps tie, the greater set index wins, i.e. the later
  file in stored set order.
- An unchanged file does not win merely because it has a newer timestamp.
- A file with no baseline has a current non-empty hash different from the
  blank baseline, so it competes as changed after initialization according to
  its timestamp.
- If no non-empty file changed but one or more files are empty, choose the
  non-empty file with the latest timestamp (ties by later index) to repair
  empty files.
- If all files are non-empty and no hash differs from its baseline, there is
  no source and no text is propagated.

### 7. Back up, overwrite, and update baselines

The app constructs a `VersionBackup` for both foreground and scheduled syncs.
If at least one non-source file's current hash differs from the chosen source
hash:

1. Before writing, snapshot the currently observed contents of every file in
   the set.
2. The backup is stored in app-private storage under
   `files/backups/<profileId>/<setId>/`. A filename combines a sanitized
   display name with the first eight hex characters of the SHA-256 hash of its
   URI, so same-named files at different URIs have distinct backup names.
3. JGit initializes/opens one repository per profile, stages that set's
   directory, and commits only when its working tree is dirty.
4. For each non-source file whose observed hash differs from the source hash,
   combine the source's comment-free sync text with the destination's own
   comments, placed at their anchors, then open its URI for output in `"wt"`
   mode and write the result as UTF-8. Comments in the source are not copied
   into other files. A null output stream or thrown provider error fails the
   sync.
5. Set every file's baseline hash in saved state to the source hash, including
   the source and files that already matched it.
6. If writes were needed, snapshot the resulting contents, including each
   destination's preserved comments, as an after-sync version.

When files are added to a set, the app also records each file's current
contents as its initial saved version. In the set detail view, restore actions
select from that file's Git history: the last-version action chooses the
newest saved contents different from the current contents, while the first
version action chooses the oldest saved contents that are not whitespace-only.
Both actions require explicit confirmation. Before restoring, the app saves
the current file; after a successful write it saves the restored contents.
Restore operations affect only the selected file.

Each comment is anchored to the next non-whitespace text after its closing
marker. Whitespace and newlines are skipped when locating that anchor. The
destination's whitespace immediately before and after each comment is kept;
the source's whitespace immediately before the anchor is replaced by that
file-local spacing so it is not duplicated. Inline comments remain inline;
comments that were alone on a line remain block comments, with a line added
when needed. If the anchored text no longer occurs in the new content, the
comment is retained at the end rather than discarded. No non-whitespace
source text is replaced by a comment.

If no write is needed, no before/after snapshot is requested, but baseline
hashes are still advanced to the source hash. The `VersionBackup` catches
backup errors and surfaces them as `IOException`; an error before overwrite
therefore prevents writes. Snapshots are local Git history, not a push to a
remote GitHub repository.

### 8. Save state and report outcomes

After all profiles and sets have been processed, `syncAll()` saves the updated
state if it differs from the loaded state. `StateStore` writes
`files/sets.json` using Android `AtomicFile`; a failed write calls
`failWrite()` and propagates the error.

The foreground path reloads state after the engine returns, then shows a
completion/failure toast and any similarity warning dialogs. The scheduled
job logs I/O and expired-permission failures; it does not display interactive
similarity dialogs. A result blocked by similarity checks is returned by the
engine, but the job service does not persist a queue for later UI display.

`updatedSets` counts sets whose saved `SyncSet` value changed, including
approval or baseline changes; it does not strictly count only sets where file
text was overwritten.

## Failure and interruption behavior

- Failure to read or decode a file aborts the current `syncAll()` call.
- Expired read/write permissions are reported in the foreground or logged by
  the job service.
- A failed pre-overwrite backup blocks that overwrite.
- An unmatched or nested comment marker fails parsing before any file in the
  set is overwritten.
- Writes across multiple document providers are not transactional. If a write
  fails after earlier files were already written, the app does not roll those
  writes back. The pre-sync Git snapshot remains available, and a later run
  will compare current hashes with saved baselines to reconcile again.
- State is saved only after all sets finish. Thus an error in a later set can
  occur after an earlier set has already changed external files but before the
  accumulated baseline changes are saved.
- Provider timestamps can be missing (treated as zero) or tied. In that case
  set order determines the winner among changed files.
- The scheduled 15-minute job is subject to Android's scheduling limits; it
  does not mean continuous or guaranteed 15-minute sync. It processes only
  sets that have been explicitly enabled.

## Opening an external editor

When the user opens a file, the app first checks Android's special
“display over other apps” permission. If it is not granted, the user is asked
to open the relevant system settings; the editor is launched after the user
returns only if permission was granted. With permission, the app starts
`ReturnToAppOverlayService` and opens an `ACTION_EDIT` chooser. The service
shows a non-focusable **Return to SyncAssist** overlay button. Pressing it
brings the existing `MainActivity` task to the front, retaining the selected
set screen, then removes the overlay. Resuming the app also stops the overlay.

The same overlay is shown over the Android document picker, providing a direct
route back to the set editor even when emulator/device Back controls do not
work. Android requires explicit approval for this special permission, which
the app requests before opening either external screen. The overlay is not
shown during normal app use. This is best-effort UI recovery and does not
guarantee that an external editor will save a file or retain Android URI
permissions.

## Related proposals not implemented

The following ideas are present in `personal/scratch` but are **not** current
behavior:

- Compare text volume first and skip the main comparison for files with an
  obviously different amount of text. The current code has no token-count or
  text-volume gate before similarity scoring.
- Make the shingle/group size adapt to the largest file. The implemented
  shingle width is fixed at five words; the short-text fallback is selected
  from the smaller meaningful-word count in the pair.
- Add a user setting for stricter behavior on tiny text. The current settings
  only turn the filename and content checks on or off. Thresholds and the
  common-word list are fixed.
- Let the user choose what to do after a sync conflict (stop until manually
  re-enabled, or retry on an interval), and add a “gain focus on sync
  conflict” setting. These controls are not implemented. Current source
  selection is most-recent-modified-wins among files changed from baseline,
  and the 15-minute periodic scheduler is not conflict-configurable.
- Replace the test-scaffolding UI with the final profile/set/file-navigation
  interface. The current UI is intentionally a minimal local testing surface.

## Tests covering this behavior

Unit tests currently cover similarity scoring, outlier detection, empty-file
source selection, changed-file/time ordering, UTF-8 BOM and malformed-input
behavior, comment parsing/anchoring/preservation, and Git snapshot history.
They are JVM unit tests. Document-provider
reads/writes, Android scheduling behavior, and partial provider failures have
not been integration-tested on a real device.
