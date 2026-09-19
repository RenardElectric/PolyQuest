#!/usr/bin/env bash
set -euo pipefail

read_property() {
  sed -n "s/^$1=//p" "${2:-gradle.properties}" | tr -d '\r' | head -n 1
}

for property in mod_id mod_name mod_version minecraft_version java_version; do
  value="$(read_property "$property")"
  [[ -n "$value" ]] || {
    echo "::error::Could not read project versions from gradle.properties"
    exit 1
  }
  printf -v "$property" '%s' "$value"
done

gradle_url="$(read_property distributionUrl gradle/wrapper/gradle-wrapper.properties)"
gradle_archive="${gradle_url##*/}"
if [[ "$gradle_archive" =~ ^gradle-(.+)-(bin|all)\.zip$ ]]; then
  gradle_version="${BASH_REMATCH[1]}"
else
  echo "::error::Could not read the Gradle version from gradle-wrapper.properties"
  exit 1
fi

short_sha="$(git rev-parse --short=7 HEAD)"
artifact_name="${mod_id}-${mod_version}-minecraft-${minecraft_version}-${short_sha}"

{
  echo "mod_name=$mod_name"
  echo "mod_version=$mod_version"
  echo "minecraft_version=$minecraft_version"
  echo "java_version=$java_version"
  echo "gradle_version=$gradle_version"
  echo "short_sha=$short_sha"
  echo "artifact_name=$artifact_name"
} >> "$GITHUB_OUTPUT"
