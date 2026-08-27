# Release notes

One file per release tag: `release-notes/<tag>.md`, e.g. `v0.3.0-fuel-calibration.md`.

Format matters, because the file has two consumers:

1. **First non-blank line** = a single-sentence summary of what's new. `app/build.gradle.kts`
   reads it into `BuildConfig.WHATS_NEW`, which the Messages tab shows as its welcome
   banner. Keep it to one sentence on one line — it is displayed on a phone.
2. **The whole file** becomes the GitHub release body via `gh release create --notes-file`.

`scripts/release.sh` refuses to cut a release if the file for the tag is missing, so the
summary can never silently drift from the build.

Builds that are not exactly on a tag (or that have uncommitted changes) ignore these files
and fall back to a generated "Dev build, N commits past <tag>" string instead.
