#!/usr/bin/env bash
set -euo pipefail

: "${DEFAULT_BRANCH:?DEFAULT_BRANCH is required}"
: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

if [[ "$GITHUB_REF_TYPE" != "branch" || "$GITHUB_REF_NAME" != "$DEFAULT_BRANCH" ]]; then
  echo "::error::Releases must be run from the default branch ($DEFAULT_BRANCH)."
  exit 1
fi

if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
  echo "::error::Version must look like 1.2.3 or 1.2.3-beta.1 (without a leading v)."
  exit 1
fi

git fetch --no-tags origin "refs/heads/$GITHUB_REF_NAME"
if [[ "$(git rev-parse FETCH_HEAD)" != "$GITHUB_SHA" ]]; then
  echo "::error::$GITHUB_REF_NAME changed since this release started. Run it again."
  exit 1
fi

release_tag="v$RELEASE_VERSION"
if ! git check-ref-format "refs/tags/$release_tag"; then
  echo "::error::$release_tag is not a valid Git tag."
  exit 1
fi

if git ls-remote --exit-code --refs --tags origin "refs/tags/$release_tag" >/dev/null 2>&1 ||
    gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "::error::Tag or release $release_tag already exists."
  exit 1
fi

current_version="$(sed -n 's/^mod_version=//p' gradle.properties | tr -d '\r')"
if [[ -z "$current_version" ]]; then
  echo "::error::mod_version was not found in gradle.properties."
  exit 1
fi

if [[ "$current_version" != "$RELEASE_VERSION" ]]; then
  sed -i "s/^mod_version=.*/mod_version=$RELEASE_VERSION/" gradle.properties
  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  git add gradle.properties
  git commit -m "chore: release $release_tag"

  # GITHUB_TOKEN pushes do not trigger push workflows; release.yml calls build.yml directly.
  git push origin "HEAD:refs/heads/$GITHUB_REF_NAME"
else
  echo "gradle.properties is already set to $RELEASE_VERSION; reusing the current commit."
fi

{
  echo "version=$RELEASE_VERSION"
  echo "tag=$release_tag"
  echo "sha=$(git rev-parse HEAD)"
} >> "$GITHUB_OUTPUT"
