#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_TAG:?RELEASE_TAG is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

release_sha="${RELEASE_SHA:-HEAD}"
ai_dir="${AI_WORKSPACE:-.release-ai}"
output_dir="${OUTPUT_DIR:-$ai_dir/.release-context}"
previous_tag="$(git describe --tags --abbrev=0 --match 'v[0-9]*' "$release_sha" 2>/dev/null || true)"

mkdir -p "$output_dir"

if [[ -n "$previous_tag" ]]; then
  base_ref="$previous_tag"
  commit_range="$previous_tag..$release_sha"
  release_kind="Update from $previous_tag to $RELEASE_TAG"
else
  base_ref="$(git hash-object -t tree /dev/null)"
  commit_range="$release_sha"
  release_kind="Initial release $RELEASE_TAG"
fi

{
  echo "Release: $RELEASE_TAG"
  echo "Target commit: $release_sha"
  echo "Previous tag: ${previous_tag:-none}"
  echo "Release kind: $release_kind"
} > "$output_dir/metadata.txt"

git log --format='%H%x09%s' "$commit_range" > "$output_dir/commits.txt"
git diff --stat "$base_ref" "$release_sha" -- > "$output_dir/stat.txt"
git diff --name-status --find-renames "$base_ref" "$release_sha" -- > "$output_dir/files.txt"
git diff --no-ext-diff --find-renames --find-copies "$base_ref" "$release_sha" -- > "$output_dir/changes.diff"
find "$ai_dir" -type f -exec sha256sum {} + | sort > "$RUNNER_TEMP/copilot-inputs.before"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "previous_tag=$previous_tag" >> "$GITHUB_OUTPUT"
fi
