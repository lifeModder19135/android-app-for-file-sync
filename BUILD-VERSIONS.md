# App build versions

The Android app's current version is defined in the root `gradle.properties`:

- `app.versionName`: user-facing semantic version
- `app.versionCode`: Android's monotonically increasing package version

Update both values before producing each new packaged app build, and add a row
to the history below. The version code must always increase. Unit-test and
development tasks that do not package an APK do not require a version bump.

| Version name | Version code | Date | Build notes |
|---|---:|---|---|
| 0.1.0 | 1 | Not recorded | Existing local MVP version before the build-version history was added; the original build notes were not versioned. |
| 0.1.1 | 2 | 2026-10-17 | First tracked build after adding conservative similarity scoring for very short non-empty text; retains the existing empty-file rules. |
