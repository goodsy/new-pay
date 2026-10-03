#!/usr/bin/env bash
# 검증 대상 source fingerprint (HARNESS-GUIDE §7).
# git 추적 파일 + 미추적(비무시) 파일의 "mode sha256 path" manifest를 만들고 manifest 자체의 SHA-256을 출력한다.
# 빌드 산출물/.gitignore 대상(.env, build/, .idea/)은 제외된다. harness 진행 상태 파일(tasks/progress/questions)만 제외한다.
# commands.json(검증 gate 정의)은 포함한다. mode는 실행 비트만(755/644) 반영해 clone별 umask 차이로 값이 바뀌지 않게 한다.
set -euo pipefail
cd "$(dirname "$0")/../.."

head="$(git rev-parse --verify HEAD)" || { echo "FAIL: git HEAD not resolvable" >&2; exit 1; }

# 무시 패턴에 걸려 fingerprint에서 빠지지만 빌드/테스트 결과에 영향을 줄 수 있는 위치의 파일은 거부한다.
guarded_ignored="$(git ls-files --others --ignored --exclude-standard -- \
  'apps/*/src/*' 'harness/scripts' 'harness/tests' 'docker' 'gradle' | grep -v '/__pycache__/' || true)"
if [[ -n "$guarded_ignored" ]]; then
  echo "FAIL: ignored files under source/harness paths are not fingerprinted:" >&2
  printf '%s\n' "$guarded_ignored" >&2
  exit 1
fi

manifest_file="$(mktemp)"
trap 'rm -f "$manifest_file"' EXIT
while IFS= read -r -d '' f; do
  if [[ ! -f "$f" ]]; then
    echo "FAIL: tracked file missing from working tree: $f" >&2
    exit 1
  fi
  if [[ -x "$f" ]]; then mode=755; else mode=644; fi
  printf '%s %s %s\n' "$mode" "$(sha256sum -- "$f" | cut -d' ' -f1)" "$f" >> "$manifest_file"
done < <(git ls-files -z --cached --others --exclude-standard \
           | grep -zv -E '^harness/(tasks|progress|questions)\.json$' \
           | sort -z)

if [[ "${1:-}" == "--manifest" ]]; then
  cat "$manifest_file"
fi
echo "headRevision=$head"
echo "dirty=$([[ -n "$(git status --porcelain)" ]] && echo true || echo false)"
echo "files=$(wc -l < "$manifest_file")"
echo "codeFingerprint=sha256:$(sha256sum < "$manifest_file" | cut -d' ' -f1)"
