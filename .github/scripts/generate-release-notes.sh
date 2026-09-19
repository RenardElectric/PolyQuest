#!/usr/bin/env bash
set -euo pipefail

ai_dir="${AI_WORKSPACE:-.release-ai}"
notes="$ai_dir/release-notes.md"

if [[ -e "$notes" ]]; then
  echo "::error::release-notes.md unexpectedly exists before Copilot runs."
  exit 1
fi

(
  cd "$ai_dir"
  copilot \
    --agent=release-notes \
    --prompt $'Generate release notes from these files, reading each completely:\n\n@.release-context/changes.diff\n@.release-context/files.txt\n@.release-context/stat.txt\n@.release-context/metadata.txt\n@.release-context/commits.txt' \
    --allow-tool=read \
    --allow-tool='write(release-notes.md)' \
    --disable-builtin-mcps \
    --no-custom-instructions \
    --no-experimental \
    --no-remote \
    --no-remote-export \
    --no-auto-update \
    --no-ask-user
)

if [[ ! -s "$notes" ]]; then
  echo "::error::Copilot did not generate release notes."
  exit 1
fi
