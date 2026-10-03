# 검증 명령·증거 수집 계약 (HARNESS-001)

작성 2026-10-03 KST. 명령 원본: `harness/commands.json`(schemaVersion 5). 이 문서는 그 명령을 언제·어떻게 쓰고 무엇을 성공으로 볼지 정한다.

## 1. 실행 위치

- 모든 명령은 WSL2 Ubuntu 안에서 `/home/sykim/workspace/paycore`를 cwd로 실행한다. Windows 측 git/Gradle은 쓰지 않는다(UNC 소유자 불일치, 줄바꿈·실행 비트 손실).
- Windows 호스트에서 진입할 때만 `hostInvocation`(`wsl.exe -d Ubuntu --cd ... --`)을 앞에 붙인다. `wsl.exe`는 argv를 WSL shell을 거쳐 실행하므로 shell 메타문자가 든 인자는 직접 넘기지 않는다. gate 단계는 `VERIFY-GATE`가 `subprocess`(shell=False)로 실행한다.

## 2. 표준 검증 절차

작업 하나의 Verifier 검증은 한 명령이다.

```
python3 harness/scripts/verify-gate.py <TASK_ID> --expect-fingerprint <Coordinator가 준 sha256:...>
```

`VERIFY-GATE`가 아래 순서를 실행하고 `build/harness-evidence/<TASK_ID>-<UTC>.json`과 단계별 전체 로그 디렉터리를 남긴다. 단계가 실패하면 그 자리에서 멈춘다.

| 순서 | commandId | 성공 조건 |
|---|---|---|
| 1 | FINGERPRINT | exit 0. HEAD 확인 가능, 추적 파일 삭제 없음, source/harness 경로에 무시된 파일 없음 |
| 2 | HARNESS-SELFTEST | exit 0, 출력이 정확히 `OK`(skip 없음), `Ran N tests` N ≥ minTests(38) |
| 3 | GRADLE-CHECK | exit 0, timeout 없음, `--console=plain` 출력에 `BUILD SUCCESSFUL`과 모든 실행 단위의 `> Task :apps:<module>:test`(결과 표시 없음=실제 실행) 존재, 출력에 `FROM-CACHE`/`:test UP-TO-DATE`/`:test NO-SOURCE`/`:test SKIPPED` 없음. `--no-build-cache --rerun-tasks` |
| 4 | TEST-SUMMARY | exit 0. 보고서 존재, 테스트 수 ≥ minTests, failures/errors/skips = 0, 모든 보고서 timestamp ≥ GRADLE-CHECK 시작 UTC(자동 치환) |
| 5 | IMAGE-DIGESTS | exit 0. 로컬 이미지 ID 기록(사용 증거 아님, §6) |
| 6 | FINGERPRINT | 값이 존재하고 1번과 같으며 `--expect-fingerprint`와 일치 |

`--expect-fingerprint`는 필수다(없으면 exit 2). 1번 FINGERPRINT가 기대값과 다르면 빌드 없이 즉시 FAIL한다. GRADLE-CHECK 성공 후 테스트 XML을 증거 로그 디렉터리로 복사해, 이후 실행이 build/를 덮어써도 컨테이너 시작 로그(system-out)와 보고서 hash가 남는다.

빌드 파일(`*.gradle.kts`, `gradle/**`)이나 의존성이 바뀐 작업은 `GRADLE-DEPS-<MODULE>`(모듈별 resolved 버전)과 `CVE-SCAN`(runtime SBOM + osv-scanner, 네트워크 필요)도 실행해 보고한다. CVE-SCAN은 알려진 취약점이 1건이라도 있으면 FAIL(exit 1), 도구/네트워크 오류는 exit 3이다.

## 3. 판정 규칙

| verdict | exit | 의미 | 작업 상태 |
|---|---|---|---|
| PASS | 0 | 모든 조건 충족 | Reviewer 판정 대기(아직 DONE 아님) |
| FAIL | 1 | 실제 검사 실패: 단계 실패, timeout, 출력 규칙 위반, fingerprint 변경·누락·기대값 불일치 | FAILED(원인이 Docker 미기동 등 환경이면 Verifier가 ENVIRONMENT_BLOCKED로 분류) |
| ENVIRONMENT_ERROR | 3 | 실행 파일이 없어 명령을 시작하지 못함 | ENVIRONMENT_BLOCKED |

- `VERIFY-GATE` PASS는 **검증 증거**일 뿐 DONE이 아니다. Reviewer 판정과 Coordinator 확인 후 DONE.
- 테스트 0건, 보고서 없음, minTests 미달, skip, 오래된/cache 복원 보고서, timeout, 검사 전후 fingerprint 불일치는 모두 성공이 아니다.
- 검사 후 코드가 바뀌면(진행 상태 JSON 제외) 증거는 무효이며 다시 실행한다.

## 4. Fingerprint 범위

- 포함: git 추적 + 미추적(비무시) 저장소 파일의 실행 비트(755/644)와 SHA-256. `harness/commands.json`(gate 정의) 포함. clone별 umask 차이(644/664)는 값에 영향이 없다.
- 제외: `.gitignore` 대상(build/, .env, .idea/, `__pycache__`), 진행 상태 파일 `harness/tasks.json`, `progress.json`, `questions.json`. 상태 파일은 검증 결과를 기록하느라 검증 후에 바뀌므로 제외하고, 판정 기준을 바꾸는 **저장소 파일**은 모두 포함한다.
- 보호: `apps/*/src`, `harness/scripts`, `harness/tests`, `docker`, `gradle` 아래에 무시된 파일이 있으면 FINGERPRINT가 실패한다(fingerprint에 안 잡히는 테스트 입력 차단).
- 한계: 저장소 밖 입력(환경변수 `GRADLE_OPTS`/`JAVA_TOOL_OPTIONS`/`TESTCONTAINERS_*` 등, `~/.gradle/init.d`, `~/.gradle/gradle.properties`, `~/.testcontainers.properties`)은 fingerprint에 없다. 대신 `VERIFY-GATE` 증거의 `environment`에 이름과 값/파일 hash로 기록한다. 값이 달라졌을 때 차단하지는 않으므로 Verifier가 이전 증거와 비교해 차이를 보고한다.

## 5. 증거 기록

- 증거 JSON: verdict, problems, 기대/전/후 fingerprint, headRevision/dirty, notBefore, 환경 snapshot, 각 명령의 argv·exit·timedOut·cleanupUnverified·environmentError·시작/종료 UTC·violations·출력 끝 4000자·전체 로그 경로와 sha256.
- 보고서별 sha256·timestamp는 TEST-SUMMARY 전체 로그에 있다(출력 길이와 관계없이 보존).
- Coordinator는 Verifier 보고와 증거를 대조해 `harness/tasks.json`의 작업 `evidence`에 요약 기록한다. 증거 파일은 `build/` 아래라 커밋하지 않는다.

## 6. 컨테이너 사용 증거

`IMAGE-DIGESTS`는 tag가 가리키는 로컬 이미지 ID만 기록한다. 예전 실행에서 받은 이미지가 남아 있어도 exit 0이므로 이번 실행에서 컨테이너를 썼다는 증거가 아니다. 실제 사용 증거는 smoke 테스트의 MySQL `8.4.` 버전 assertion과 보고서 system-out의 컨테이너 시작 로그다. Verifier가 이를 확인해 보고한다.

## 7. 모듈/테스트 추가 시 갱신 규칙

- 새 Gradle 모듈(실행 단위)을 추가하면 같은 변경에서 모두 갱신한다:
  - `TEST-SUMMARY` argv에 그 모듈의 `build/test-results/test` 경로, 하한(minTests) 조정
  - `GRADLE-CHECK.expectedReports`와 `outputMustMatch`의 `^> Task :apps:<module>:test$`
  - `GRADLE-DEPS-<MODULE>` 명령
  - `harness/scripts/cve-scan.py`의 `MODULES`(빠지면 그 모듈은 취약점 검사를 받지 않는다)
- 현재 하한은 전체 합계(27: payment-api 13, financial-batch 5, internal-admin 9)만 검사한다. 모듈별 하한은 아직 없으며, 모듈별 `> Task :apps:<module>:test` 실행 규칙과 Verifier의 모듈별 개수 보고로 보완한다.
- `minTests`는 현재 실행되는 테스트 수 이하의 하한으로 두고, 테스트를 추가하면 올린다. 테스트를 지우거나 skip해서 하한을 낮추는 변경은 사용자 정책 변경 없이 하지 않는다.
- 새 컨테이너 이미지를 쓰면 `IMAGE-DIGESTS` argv에 추가한다.
- 명령/하한/출력 규칙 변경은 fingerprint에 반영되므로 해당 작업의 Reviewer가 diff로 확인한다.

## 8. Timeout

- 명령별 `timeoutSeconds`를 넘기면 `VERIFY-GATE`가 프로세스 그룹에 SIGKILL을 보내고 최대 10초 더 기다린 뒤 FAIL로 기록한다. 그룹을 벗어난 자손이 남아 pipe를 잡고 있으면 pipe를 닫고 `cleanupUnverified=true`로 표시한다(무한 대기 없음). 단순 자식(`test_timeout_is_failure_and_bounded`)과 새 session으로 빠져나가 pipe를 잡은 손자(`test_escaped_descendant_holding_pipe_is_bounded`) 두 경우를 시험한다. 실제 Gradle JVM이 모두 정리되는지는 timeout 발생 시 Verifier가 `ps`로 확인한다.
- 작업 단위 timeout은 `harness/tasks.json`의 `taskTimeoutSeconds`. 수정 반복은 실패당 3회까지, 초과 시 원인과 다음 조치를 보고한다.

## 9. 아직 하지 않은 것 (후속)

- HARNESS-002: 범용 자동 Runner(명령 allowlist 실행기, Claude CLI 연동, 중단/재개 시험), CI 연동.
- 비밀 탐지(gitleaks 등)와 CVE-SCAN의 CI 정기 실행: HARNESS-002.
