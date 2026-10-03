#!/usr/bin/env bash
# 검증 대상 source fingerprint (HARNESS-GUIDE §7).
# git 추적 파일 + 미추적(비무시) 파일의 SHA-256 manifest를 만들고 manifest 자체의 SHA-256을 출력한다.
# 빌드 산출물/.gitignore 대상(.env, build/, .idea/)은 제외된다. harness 진행 상태 파일(tasks/progress/questions)만 제외한다. commands.json(검증 gate 정의)은 포함한다.
set -euo pipefail
cd "$(dirname "$0")/../.."

manifest="$(git ls-files -z --cached --others --exclude-standard \
  | grep -zv -E '^harness/(tasks|progress|questions)\.json$' \
  | sort -z \
  | xargs -0 sha256sum)"

if [[ "${1:-}" == "--manifest" ]]; then
  printf '%s\n' "$manifest"
fi
echo "headRevision=$(git rev-parse HEAD)"
echo "dirty=$([[ -n "$(git status --porcelain)" ]] && echo true || echo false)"
echo "files=$(printf '%s\n' "$manifest" | wc -l)"
echo "codeFingerprint=sha256:$(printf '%s\n' "$manifest" | sha256sum | cut -d' ' -f1)"
