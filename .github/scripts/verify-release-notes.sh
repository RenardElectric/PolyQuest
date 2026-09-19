#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

ai_dir="${AI_WORKSPACE:-.release-ai}"
notes="$ai_dir/release-notes.md"

find "$ai_dir" -type f ! -path "$notes" -exec sha256sum {} + \
  | sort > "$RUNNER_TEMP/copilot-inputs.after"
diff -u "$RUNNER_TEMP/copilot-inputs.before" "$RUNNER_TEMP/copilot-inputs.after"
git diff --exit-code -- .

unexpected_files="$({
  while IFS= read -r file; do
    [[ "$file" == "$ai_dir/"* ]] || printf '%s\n' "$file"
  done < <(git ls-files --others --exclude-standard)
} || true)"
if [[ -n "$unexpected_files" ]]; then
  echo "::error::Copilot created unexpected files outside its isolated workspace."
  printf '%s\n' "$unexpected_files"
  exit 1
fi
