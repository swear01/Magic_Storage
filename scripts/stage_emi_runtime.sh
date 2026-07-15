#!/usr/bin/env bash
set -euo pipefail

EMI_VERSION="${1:?usage: stage_emi_runtime.sh <emi-version>}"
COMMAND=(
  ./gradlew
  stageEmiRuntime
  "-Pemi_version=${EMI_VERSION}"
  --console=plain
  --no-daemon
)

if "${COMMAND[@]}"; then
  exit 0
fi

echo "EMI runtime staging failed; retrying the same Gradle command once." >&2
"${COMMAND[@]}"
