#!/usr/bin/env bash
#
# Cut a dash22b release: tag -> build -> GitHub release.
#
#   scripts/release.sh v0.4.0-presets          # cut the release
#   scripts/release.sh v0.4.0-presets --dry    # validate only, change nothing
#   scripts/release.sh v0.4.0-presets --local  # tag + build only, publish nothing
#
# Version identity lives in git tags, not in build.gradle.kts. `git describe` feeds
# versionName / versionCode / BuildConfig at build time, so the tag created here is
# what the phone reports in the Messages tab.
#
# Before running, release-notes/<tag>.md must exist. Its first non-blank line is the
# one-sentence summary shown on the phone; the whole file becomes the release body.
# Ask Claude to draft it from `git log <last-tag>..HEAD`.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="${1:-}"
MODE="${2:-}"

if [[ -z "$TAG" ]]; then
    echo "usage: scripts/release.sh <tag> [--dry|--local]" >&2
    echo "  e.g. scripts/release.sh v0.4.0-presets" >&2
    exit 1
fi

case "$MODE" in
    ""|--dry|--local) ;;
    *) echo "error: unknown mode '$MODE' (expected --dry or --local)" >&2; exit 1 ;;
esac

NOTES="release-notes/${TAG}.md"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
APK="app/build/outputs/apk/debug/app-debug.apk"

# --- preflight -------------------------------------------------------------

fail() { echo "error: $*" >&2; exit 1; }

[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-.+)?$ ]] \
    || fail "tag must look like v0.4.0 or v0.4.0-presets, got '$TAG'"

[[ -f "$NOTES" ]] \
    || fail "missing $NOTES -- write the release notes first (see release-notes/README.md)"

head -n1 "$NOTES" | grep -q '[^[:space:]]' \
    || fail "$NOTES starts with a blank line; the first line must be the one-sentence summary"

SUMMARY="$(grep -m1 '[^[:space:]]' "$NOTES")"
[[ ${#SUMMARY} -le 160 ]] \
    || fail "summary is ${#SUMMARY} chars; keep it under 160 so it fits the phone banner"

# A dirty tree would produce a "-dirty" versionName baked into the released APK.
[[ -z "$(git status --porcelain --untracked-files=no)" ]] \
    || fail "working tree has uncommitted changes to tracked files; commit them first"

git rev-parse "$TAG" >/dev/null 2>&1 \
    && fail "tag $TAG already exists"

[[ "$MODE" == "--local" ]] || command -v gh >/dev/null || fail "gh CLI not found"

echo "tag:      $TAG"
echo "branch:   $BRANCH"
echo "summary:  $SUMMARY"
echo "notes:    $NOTES"

if [[ "$MODE" == "--dry" ]]; then
    echo
    echo "dry run: preflight passed, nothing changed"
    exit 0
fi

# --- tag, build, release ---------------------------------------------------

# Tag BEFORE building so `git describe` resolves to exactly $TAG and the APK is
# stamped as a release build rather than "N commits past <previous tag>".
echo
echo "==> tagging"
git tag -a "$TAG" -m "$SUMMARY"

echo "==> building"
if ! ./gradlew --quiet clean assembleDebug; then
    git tag -d "$TAG"
    fail "build failed; tag removed"
fi

[[ -f "$APK" ]] || { git tag -d "$TAG"; fail "expected APK at $APK"; }

# Verify the APK actually carries the tag, so a stale Gradle configuration cache
# can never ship an APK that misreports its own version.
BAKED="$(unzip -p "$APK" AndroidManifest.xml 2>/dev/null | strings | grep -m1 -F "${TAG#v}" || true)"
if [[ -z "$BAKED" ]]; then
    echo "warning: could not confirm '${TAG#v}' inside the APK manifest" >&2
    echo "         check the Messages tab banner after installing" >&2
fi

SHA="$(shasum -a 256 "$APK" | cut -d' ' -f1)"
echo "==> apk sha256: $SHA"

if [[ "$MODE" == "--local" ]]; then
    echo
    echo "local mode: tagged and built, nothing pushed or published."
    echo "  apk:     $APK"
    echo "  install: adb install -r $APK"
    echo "  publish: scripts/release.sh $TAG   (after deleting the local tag: git tag -d $TAG)"
    exit 0
fi

echo "==> pushing"
git push origin "$BRANCH"
git push origin "$TAG"

echo "==> creating GitHub release"
gh release create "$TAG" "$APK" \
    --title "$TAG" \
    --notes-file "$NOTES" \
    --prerelease \
    --target "$(git rev-parse HEAD)"

echo
echo "done: $(gh release view "$TAG" --json url -q .url)"
echo "install with: adb install -r $APK"
