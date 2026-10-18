# Product roadmap

This roadmap records the agreed product direction beyond the local-files MVP.
Later phases are planned requirements, not claims about features already
implemented.
For a concise read-back of deferred and potential changes, see
[PROJECT-CHANGE-NOTES.md](./PROJECT-CHANGE-NOTES.md). Testing observations and
their status remain tracked in [TESTING-NOTES.md](./TESTING-NOTES.md).

## Product invariants

- The text content must remain exactly the same when represented in another
  supported file format. Conversion must not silently alter, omit, or rewrite
  the text.
- Formatting such as bold and italic is part of the information to preserve.
  The set data will store formatting positions/metadata and shared rendering
  rules once per set so they apply consistently to its files.
- Plain `.txt` is the canonical text representation for conversion. Each other
  supported file type has one import path to canonical TXT and one export path
  from canonical TXT, using the set's metadata to reconstruct formatting and
  other supported document features.
- Sync should run as often as the device and operating system permit. The app
  cannot sync while the device is off or while Android prevents it from
  running.
- When syncing has been interrupted, reconcile on restart/resumption with a
  most-recent-change-wins policy. This is conflict resolution; it is not a
  promise of lossless merging of simultaneous edits.
- Format-specific metadata, encryption, and conversion details can be added
  when supporting the relevant formats. They must not compromise the exact-text
  invariant.

## Phases

### Phase 1: Local text-file MVP

- Create profiles and sets and select local files through Android's Storage
  Access Framework.
- Persist app state locally and synchronize supported UTF-8 text files in each
  set.
- Use last-change-wins after detecting changes.
- Establish the basic set-management UI and validate the sync behavior. This UI
  is test scaffolding, not the final design.
- No Google Drive integration or conversion of DOC/DOCX, PDF, or Google Docs in
  this phase.

### Phase 2: Set-level formatting and conversion foundation

- Define the canonical TXT representation and a set-data schema for rendering
  rules, formatting positions (including bold and italic), and any other
  information TXT cannot represent. Store shared rules once per set.
- Design and test a two-way adapter contract for each format: source format to
  canonical TXT plus set metadata, and canonical TXT plus set metadata back to
  the target format. Avoid direct pairwise converters between every format.
- Preserve text exactly through the canonical representation; define and test
  how character encoding, line endings, and formatting positions are handled
  so conversion does not silently change text.
- Surface conversion limitations or failures; never report a successful
  conversion when text or formatting was lost.

### Phase 3: Additional document formats

- Add format support incrementally (for example DOCX and PDF) using one import
  and one export adapter per format from the Phase 2 canonical-TXT foundation.
- Specify per-format read/write capabilities and how text and formatting are
  represented in TXT and set metadata before enabling sync for that format.
- Treat Google Docs as a distinct integration, not as a local `.gdoc` text file.

### Phase 4: Google Drive and Google Docs

- Add account authorization and Drive file discovery/selection.
- Support Google Docs through the appropriate Google APIs and conversion
  pathways.
- Keep credentials and provider-specific metadata separate from the shared
  set-level formatting rules and local file contents.

### Phase 5: Sync reliability and product completion

- Improve background scheduling, retry/recovery, sync status, and handling of
  unavailable providers while respecting Android's execution limits.
- On app/device restart or after any sync gap, detect changes and apply
  most-recent-change-wins.
- Complete the broader UI (select-a-file-then-act pattern: files shown as icons
  in a grid or list, with one action bar that applies to the selected file),
  error handling, backup/recovery, and release
  readiness work from the full product specification.

## Current milestone boundary

The current implementation is Phase 1 only. Its periodic background work is
subject to Android scheduling and is not continuous. Later phases must preserve
the product invariants above; do not reinterpret the current plain-text file
allowlist as a limitation on the long-term file-format goal.
