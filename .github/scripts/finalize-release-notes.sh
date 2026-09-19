#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_TAG:?RELEASE_TAG is required}"

notes="${AI_WORKSPACE:-.release-ai}/release-notes.md"

# The GitHub release already has a title, so discard any leading H1.
sed -i '/[^[:space:]]/,$!d' "$notes"
sed -i '1{/^[[:space:]]*#[[:space:]]/d;}' "$notes"
sed -i '/[^[:space:]]/,$!d' "$notes"

if [[ ! -s "$notes" ]]; then
  echo "::error::Release notes were empty after validation."
  exit 1
fi

printf '\n\n' >> "$notes"
if [[ -n "${PREVIOUS_TAG:-}" ]]; then
  echo "**Full changelog:** [$PREVIOUS_TAG...$RELEASE_TAG]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/compare/$PREVIOUS_TAG...$RELEASE_TAG)" >> "$notes"
else
  echo "**Commit history:** [$RELEASE_TAG]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/commits/$RELEASE_TAG)" >> "$notes"
fi
