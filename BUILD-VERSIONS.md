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
| 0.1.2 | 3 | 2026-10-18 | New sets pause automatic sync until manually started; added targeted single-set sync, blank-file similarity exclusion, and return-to-app overlay for external editors. |
| 0.1.3 | 4 | 2026-10-18 | Renamed the Kotlin/Java package, Android namespace, and application ID to `com.ntolb.syncassist`. This installs as a separate Android app from earlier IDs and does not inherit their private app data. |
| 0.1.4 | 5 | 2026-10-18 | Prevents ordinary activity resumes from triggering sync-all; returning from an external editor silently syncs only that set and does not enable paused sets. |
| 0.1.5 | 6 | 2026-10-18 | Adds usable set navigation and confirmed deletion, confirms files added from Android's multi-select picker, catches up after a missed 15-minute sync interval, and defers mismatch warnings until the sets list is visible. |
