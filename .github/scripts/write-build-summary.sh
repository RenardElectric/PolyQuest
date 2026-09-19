#!/usr/bin/env bash
set -euo pipefail

case "$BUILD_OUTCOME" in
  success) result="Passed" ;;
  failure) result="Failed" ;;
  cancelled) result="Cancelled" ;;
  *) result="${BUILD_OUTCOME:-Not run}" ;;
esac

row() {
  printf '| %s | %s |\n' "$1" "$2"
}

{
  echo "## ${MOD_NAME:-Mod} build"
  echo
  row "Detail" "Value"
  row "---" "---"
  row "Result" "**$result**"
  row "Mod version" "\`${MOD_VERSION:-Unknown}\`"
  row "Minecraft" "\`${MINECRAFT_VERSION:-Unknown}\`"
  row "Toolchain" "Java \`${JAVA_VERSION:-Unknown}\`, Gradle \`${GRADLE_VERSION:-Unknown}\`"

  if [[ -n "$SHORT_SHA" ]]; then
    row "Commit" "[\`$SHORT_SHA\`]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/commit/$(git rev-parse HEAD))"
  fi

  if [[ "$UPLOAD_OUTCOME" == "success" && -n "$ARTIFACT_URL" ]]; then
    row "Artifact" "[\`$ARTIFACT_NAME\`]($ARTIFACT_URL) (retained for 14 days)"
    row "Artifact SHA-256" "\`$ARTIFACT_DIGEST\`"
  else
    row "Artifact" "Not available (\`${UPLOAD_OUTCOME:-not run}\`)"
  fi

  shopt -s nullglob
  jars=(build/libs/*.jar)
  if (( ${#jars[@]} > 0 )); then
    echo
    echo "### Packaged files"
    echo
    for jar in "${jars[@]}"; do
      printf -- "- %s — %s bytes\n" "$(basename "$jar")" "$(stat --format='%s' "$jar")"
    done
  fi
} >> "$GITHUB_STEP_SUMMARY"
