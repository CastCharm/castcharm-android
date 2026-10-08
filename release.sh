#!/usr/bin/env bash
# Publish the APK that Android Studio just built as a GitHub Release.
#
#   1. In Android Studio: Build → Generate Signed Bundle / APK → APK → release.
#      That writes app/release/app-release.apk (signed with your release key).
#   2. Run:  ./release.sh            (from WSL or any shell with gh + git)
#
# What it does:
#   - reads versionName from app/build.gradle.kts, so the tag is v<versionName>
#   - refuses to run with uncommitted changes, a missing/stale APK, or an
#     existing tag, so you can't publish the wrong build by accident
#   - copies the APK to castcharm-v<version>.apk (a stable name pattern that
#     update tools like Obtainium can follow from release to release)
#   - creates the tag and the release on GitHub with notes generated from the
#     commits since the previous tag, and attaches the APK
#
# One-time setup:  sudo apt install gh && gh auth login
#
# Options:
#   --notes "text"   use this text instead of generated notes
#   --draft          create the release as a draft so you can edit it first
#   --dry-run        show what would happen and stop

set -euo pipefail
cd "$(dirname "$0")"

APK="app/release/app-release.apk"
GRADLE="app/build.gradle.kts"
NOTES=""
DRAFT=""
DRY_RUN=""

while [ $# -gt 0 ]; do
  case "$1" in
    --notes)   NOTES="$2"; shift 2 ;;
    --draft)   DRAFT="--draft"; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }

command -v gh  >/dev/null || die "GitHub CLI not found. Install with: sudo apt install gh && gh auth login"
command -v git >/dev/null || die "git not found"
gh auth status >/dev/null 2>&1 || die "gh is not logged in. Run: gh auth login"

VERSION="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/p' "$GRADLE" | head -1)"
[ -n "$VERSION" ] || die "could not read versionName from $GRADLE"
TAG="v$VERSION"
ASSET="castcharm-$TAG.apk"

[ -f "$APK" ] || die "$APK not found — build a signed release APK in Android Studio first"

# The APK must be newer than the last change to the source tree, or you're
# about to publish a build that doesn't contain what's committed.
NEWEST_SRC="$(find app/src app/build.gradle.kts -type f -newer "$APK" 2>/dev/null | head -1 || true)"
[ -z "$NEWEST_SRC" ] || die "$APK is older than $NEWEST_SRC — rebuild the signed APK first"

[ -z "$(git status --porcelain)" ] || die "working tree has uncommitted changes — commit (or stash) first"

git fetch --tags --quiet
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists — bump versionName/versionCode in $GRADLE"
gh release view "$TAG" >/dev/null 2>&1 && die "release $TAG already exists on GitHub"

PREV_TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"

echo "Release:     $TAG (versionCode $CODE)"
echo "APK:         $APK ($(du -h "$APK" | cut -f1)) → $ASSET"
echo "Since:       ${PREV_TAG:-<first release>}"
echo "Commit:      $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD)"
[ -n "$DRAFT" ] && echo "Mode:        draft"
if [ -n "$DRY_RUN" ]; then echo "(dry run — nothing published)"; exit 0; fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp "$APK" "$TMP/$ASSET"

# Tag the exact commit the APK was built from, then let gh create the release
# from that tag. --generate-notes writes the changelog from commits/PRs since
# the previous tag; --notes replaces it when you'd rather write your own.
git tag -a "$TAG" -m "CastCharm for Android $VERSION"
git push origin "$TAG"

if [ -n "$NOTES" ]; then
  gh release create "$TAG" "$TMP/$ASSET" --title "CastCharm for Android $VERSION" --notes "$NOTES" $DRAFT
else
  gh release create "$TAG" "$TMP/$ASSET" --title "CastCharm for Android $VERSION" --generate-notes $DRAFT
fi

echo "Published: $(gh release view "$TAG" --json url -q .url)"
