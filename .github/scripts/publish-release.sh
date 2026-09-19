#!/usr/bin/env bash
set -euo pipefail

: "${MOD_NAME:?MOD_NAME is required}"
: "${RELEASE_TAG:?RELEASE_TAG is required}"
: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${TARGET_SHA:?TARGET_SHA is required}"

git fetch --no-tags origin "refs/heads/$GITHUB_REF_NAME"
if [[ "$(git rev-parse FETCH_HEAD)" != "$TARGET_SHA" ]]; then
  echo "::error::$GITHUB_REF_NAME changed while the release was being prepared. Run it again."
  exit 1
fi

if git ls-remote --exit-code --refs --tags origin "refs/tags/$RELEASE_TAG" >/dev/null 2>&1 ||
    gh release view "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "::error::Tag or release $RELEASE_TAG already exists."
  exit 1
fi

shopt -s nullglob
jars=(release-assets/*.jar)
if (( ${#jars[@]} == 0 )) || [[ ! -s release-inputs/release-notes.md ]]; then
  echo "::error::Release artifacts or notes are missing."
  exit 1
fi

args=(
  release create "$RELEASE_TAG"
  "${jars[@]}"
  --repo "$GITHUB_REPOSITORY"
  --target "$TARGET_SHA"
  --title "$MOD_NAME $RELEASE_VERSION"
  --notes-file release-inputs/release-notes.md
)
if [[ "$RELEASE_VERSION" == *-* ]]; then
  args+=(--prerelease)
fi

release_url="$(gh "${args[@]}")"
{
  echo "## Release published"
  echo
  echo "[$RELEASE_TAG]($release_url) was built, tagged, and published with Copilot-generated release notes."
} >> "$GITHUB_STEP_SUMMARY"
