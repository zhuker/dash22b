# Project Instructions

## Important

- The `example/` folder is for reference only. Never try to compile it or use its code directly.
- Do not add Co-Authored-By lines to git commits.

## Versioning and releases

Version identity lives in **git tags**, not in `build.gradle.kts`. `app/build.gradle.kts`
shells out to `git describe --tags --dirty` at configure time and feeds `versionName`,
`versionCode` (commit count) and a few `BuildConfig` fields. Never hardcode a version.

- Release build (HEAD exactly on a clean tag): `0.3.0-fuel-calibration`, `IS_RELEASE_BUILD = true`.
- Anything else: `0.3.0-fuel-calibration-1-g4798128-dirty`, `IS_RELEASE_BUILD = false`.

The Messages tab welcome banner shows that string plus `BuildConfig.WHATS_NEW`, so the
version running on the phone can be read off the screen with no cable attached.

Tag naming: `vMAJOR.MINOR.PATCH-<branch>` for branch prereleases (`v0.3.0-fuel-calibration`),
plain `vMAJOR.MINOR.PATCH` for a release off `main`.

### Cutting a release

```
scripts/release.sh v0.4.0-presets --dry    # preflight only
scripts/release.sh v0.4.0-presets          # tag, build, push, gh release create
```

The script tags *before* building (so the APK is stamped with the tag), removes the tag if
the build fails, and refuses to run on a dirty tree or without a notes file.

**Write `release-notes/<tag>.md` first.** Its first non-blank line is the one-sentence
summary shown on the phone; the whole file becomes the GitHub release body. See
`release-notes/README.md`. Drafting that summary from `git log <last-tag>..HEAD` is a good
thing to ask Claude for — it is the only step the script cannot do itself.

If `git fetch --tags` has never run in a fresh clone, `git describe` finds no tag and the
build falls back to `0.0.0-nogit`.
