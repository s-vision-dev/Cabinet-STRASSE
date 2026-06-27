# AGENTS.md

## Communication

- Responses and work notes should be written in Japanese.
- Before code work, confirm the target directory and current Git branch.
- Do not shift decisions back to the user when a reasonable project-local assumption can be made.

## Project Direction

- Cabinet-STRASSE targets Android.
- The Android layer is implemented in Kotlin.
- The lower-level core is implemented in Rust.
- SQLite direct access is the persistence strategy.
- Do not use Room as the primary database layer.
- Android-specific responsibilities such as UI, SAF, MediaStore, ContentResolver, WorkManager, permissions, Intents, Deep Links, and ContentProvider integration remain on the Kotlin side.
- Cabinet core responsibilities such as schema management, migrations, metadata rules, search, Smart Folder evaluation, versioning rules, backup import/export logic, hashing, and duplicate detection should be placed in Rust where practical.
- App-to-app integration must use explicit contracts such as Intent, Deep Link, ContentProvider, or STRASSE Platform contracts. Do not directly read another STRASSE app's private SQLite database.

## Versioning

- Initial app version is `0.1.1`.
- Initial build number starts at `1`.
- `version.properties` stores the app version name.
- `build-number.txt` stores the next build number used by the release script.

## Release Build

- When the user says `リリースビルド`, run `pwsh -ExecutionPolicy Bypass -File scripts/release_build.ps1`.
- The release script performs the following sequence:
  1. Run the release build.
  2. Copy release artifacts to Dropbox.
  3. Increment the build number.
  4. Commit release-related tracked changes.
  5. Push the current branch.
- Do not run the release script with Windows PowerShell 5.1 because UTF-8 strings may be parsed incorrectly. Use PowerShell 7 or later via `pwsh`.
- Release artifacts are copied under `Dropbox\STRASSE\Cabinet-STRASSE\releases` by default.

## Line Endings

- Use LF line endings throughout this repository.
- Keep generated build artifacts, logs, and local temporary files out of commits.
- Do not commit files under `Documents` unless the user explicitly requests it.

