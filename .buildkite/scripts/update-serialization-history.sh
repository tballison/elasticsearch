#!/bin/bash
set -euo pipefail

if [[ -z "${BUILDKITE_PULL_REQUEST:-}" ]]; then
  echo "Not a pull request, skipping serialization history update"
  exit 0
fi

if ! git diff --exit-code; then
  echo "Changes are present before updating serialization history, not running"
  git status
  exit 0
fi

NEW_COMMIT_MESSAGE="[CI] Update serialization history files"

echo "--- Generating updated serialization history files"
.ci/scripts/run-gradle.sh generateSerializationHistory

if git diff --quiet; then
  echo "No serialization history changes found after update. Skipping auto commit."
  exit 0
fi

git config --global user.name elasticsearchmachine
git config --global user.email 'infra-root+elasticsearchmachine@elastic.co'

gh pr checkout "${BUILDKITE_PULL_REQUEST}"
git add -A .
git commit -m "$NEW_COMMIT_MESSAGE"
git push

# After the git push, the new commit will trigger a new build within a few seconds and this build should get cancelled
# So, let's just sleep to give the build time to cancel itself without an error
# If it doesn't get cancelled for some reason, then exit with an error, because we don't want this build to be green (we just don't want it to generate an error either)
sleep 300
exit 1
