# AGENTS.md

## Communication

- Responses and work notes should be written in Japanese.
- Before code work, confirm the target directory and current Git branch.
- Do not shift decisions back to the user when a reasonable project-local assumption can be made.

## Project Direction

- Cabinet by VIASTRASSE targets Android.
- The Android layer is implemented in Kotlin.
- The lower-level core is implemented in Rust.
- SQLite direct access is the persistence strategy.
- Do not use Room as the primary database layer.
- Android-specific responsibilities such as UI, SAF, MediaStore, ContentResolver, WorkManager, permissions, Intents, Deep Links, and ContentProvider integration remain on the Kotlin side.
- Cabinet core responsibilities such as schema management, migrations, metadata rules, search, Smart Folder evaluation, versioning rules, backup import/export logic, hashing, and duplicate detection should be placed in Rust where practical.
- App-to-app integration must use explicit contracts such as Intent, Deep Link, ContentProvider, or STRASSE Platform contracts. Do not directly read another STRASSE app's private SQLite database.

## UI Strings

- All user-facing Japanese text lives in `app/src/main/res/values/strings.xml`.
- Do not add Japanese string literals to Kotlin UI code.
- Values persisted to the database are the exception: `CabinetItem.note` defaults and
  reference titles stay as Kotlin literals, because a locale change must not alter stored data.
- Classes without a `Context` (enums, data classes) hold a `@StringRes` id instead of the text,
  and the caller resolves it with `getString`.

## Colors

- `jp.viastrasse.cabinet.theme.CabinetColors` and `res/values/colors.xml` intentionally hold the
  same palette, because an XML theme cannot reference a Kotlin object.
- `CabinetColorsSyncTest` verifies the two stay in sync. Update both when changing a color.
- Shared UI dimensions and type scale live in `jp.viastrasse.cabinet.ui.CabinetMetrics` / `CabinetType`.

## Shared Utilities

- File name sanitising, unique destination paths and human readable sizes live in
  `jp.viastrasse.cabinet.data.CabinetFiles`. Do not reimplement them per class.

## Versioning

- Initial app version is `0.1.1`.
- Initial build number starts at `1`.
- `version.properties` stores the app version name.
- `build-number.txt` stores the next build number used by the release script.

## Git Commit Messages

- Commit messages must be written in Japanese.
- Use about three lines of meaningful detail, not only a short title.
- Mention the implemented feature, the main technical change, and the verification or version/build-number update when relevant.

## Git Scope

- Do not run `git add`, `git commit`, `git revert`, `git reset`, `git push`, or any other Git history/index operation in repositories outside `D:\Cabinet-STRASSE`.
- Other STRASSE app directories may be read or may receive user-requested instruction files, but their Git repositories must never be modified unless the user explicitly names that repository and explicitly orders a Git operation for it in the same request.
- When this project needs coordination notes for other apps, create or edit the requested files only; leave commit, revert, push, and staging in those repositories to the user.

## Release Build

- For code-work verification before an expected release, prefer `./gradlew.bat :app:compileReleaseKotlin` instead of `:app:compileDebugKotlin`.
- Treat `compileReleaseKotlin` as compile verification only: Kotlin type/reference checks plus the release variant's prerequisite build tasks. It is not runtime behavior verification.
- When `compileReleaseKotlin` has already succeeded and no relevant inputs changed, do not add manual skip logic to the release script. Let Gradle's UP-TO-DATE checks skip repeated release Kotlin compilation during `assembleRelease`.
- When the user says `リリースビルド`, run `pwsh -ExecutionPolicy Bypass -File scripts/release_build.ps1`.
- The release script performs the following sequence:
  1. Run the release build.
  2. Copy release artifacts to Dropbox.
  3. Increment the build number.
  4. Commit release-related tracked changes.
  5. Push the current branch.
- Do not run the release script with Windows PowerShell 5.1 because UTF-8 strings may be parsed incorrectly. Use PowerShell 7 or later via `pwsh`.
- Release APK is copied to `Dropbox\Cabinet by VIASTRASSE\Cabinet by VIASTRASSE-release.apk` by default.

## Line Endings

- Use LF line endings throughout this repository.
- Keep generated build artifacts, logs, and local temporary files out of commits.
- Do not commit files under `Documents` unless the user explicitly requests it.
