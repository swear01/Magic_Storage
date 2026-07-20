#!/usr/bin/env bash
set -euo pipefail

EMI_VERSION="${1:?usage: stage_emi_runtime.sh <emi-version> <modrinth-version-id>}"
EMI_RUNTIME_VERSION="${2:?usage: stage_emi_runtime.sh <emi-version> <modrinth-version-id>}"
COMMAND=(
  ./gradlew
  stageEmiRuntime
  "-Pemi_version=${EMI_VERSION}"
  "-Pemi_runtime_version=${EMI_RUNTIME_VERSION}"
  --console=plain
  --no-daemon
)

if "${COMMAND[@]}"; then
  exit 0
fi

echo "EMI runtime staging failed; retrying the same Gradle command once." >&2
"${COMMAND[@]}"
