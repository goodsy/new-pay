# 검증 명령·증거 수집 계약 (HARNESS-001)

작성 2026-10-03 KST. 명령 원본: `harness/commands.json`(schemaVersion 10). 이 문서는 그 명령을 언제·어떻게 쓰고 무엇을 성공으로 볼지 정한다.

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
| 2 | HARNESS-SELFTEST | exit 0, 출력이 정확히 `OK`(skip 없음), `Ran N tests` N ≥ `commands.json` HARNESS-SELFTEST minTests(값은 commands.json이 원본 — 이 표에 숫자를 두지 않는다, CP-QUERY-001 F-4) |
| 3 | GRADLE-CHECK | exit 0, timeout 없음, `--console=plain` 출력에 `BUILD SUCCESSFUL`과 모든 실행 단위의 `> Task :apps:<module>:test`(결과 표시 없음=실제 실행) 존재, 출력에 `FROM-CACHE`/`:test UP-TO-DATE`/`:test NO-SOURCE`/`:test SKIPPED` 없음. `--no-build-cache --rerun-tasks` |
| 4 | TEST-SUMMARY | exit 0. 보고서 존재, 테스트 수 ≥ minTests, failures/errors/skips = 0, 모든 보고서 timestamp ≥ GRADLE-CHECK 시작 UTC(자동 치환) |
| 5 | IMAGE-DIGESTS | exit 0. 로컬 이미지 ID 기록(사용 증거 아님, §6) |
| 6 | FINGERPRINT | 값이 존재하고 1번과 같으며 `--expect-fingerprint`와 일치 |

`--expect-fingerprint`는 필수다(없으면 exit 2). 1번 FINGERPRINT가 기대값과 다르면 빌드 없이 즉시 FAIL한다. GRADLE-CHECK 성공 후 테스트 XML을 증거 로그 디렉터리로 복사해, 이후 실행이 build/를 덮어써도 컨테이너 시작 로그(system-out)와 보고서 hash가 남는다.

빌드 파일(`*.gradle.kts`, `gradle/**`)이나 의존성이 바뀐 작업은 `GRADLE-DEPS-<MODULE>`(모듈별 resolved 버전)과 `CVE-SCAN`(runtime SBOM + osv-scanner, 네트워크 필요)도 실행해 보고한다. CVE-SCAN의 SBOM 생성은 `./gradlew --no-build-cache --rerun-tasks ...:cyclonedxDirectBom`으로 매번 실제 실행한다(build cache에서 복원된 SBOM은 증거가 아니다. `test_cve_scan.py`가 argv를 검사한다). CVE-SCAN은 알려진 취약점이 1건이라도 있으면 FAIL(exit 1), 도구/네트워크 오류는 exit 3이다.

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
- 보호(`harness/scripts/fingerprint.sh`의 `guarded_ignored` pathspec과 동일): `apps/*/src/**`, `libs/*/src/**`, `build-logic/src/**`, `build-logic/*.kts`(build-logic 바로 아래 Gradle 스크립트만), `harness/scripts`, `harness/tests`, `harness/fixtures`, `docker`, `gradle` 아래에 `.gitignore`로 무시된 파일이 있으면 FINGERPRINT가 실패한다(fingerprint에 안 잡히는 테스트 입력 차단). 예외: 경로에 `/__pycache__/`가 든 파일은 무시한다. 이 목록 밖(예: 루트 `*.gradle.kts`, `gradle.properties`, `build-logic/build/`, `apps/*/build/`)의 무시된 파일은 검사하지 않는다. 루트 Gradle 파일은 추적 파일이면 fingerprint에 포함된다.
- 한계: 저장소 밖 입력(환경변수 `GRADLE_OPTS`/`JAVA_TOOL_OPTIONS`/`TESTCONTAINERS_*` 등, `~/.gradle/init.d`, `~/.gradle/gradle.properties`, `~/.testcontainers.properties`)은 fingerprint에 없다. 대신 `VERIFY-GATE` 증거의 `environment`에 이름과 값/파일 hash로 기록한다. 값이 달라졌을 때 차단하지는 않으므로 Verifier가 이전 증거와 비교해 차이를 보고한다.

## 5. 증거 기록

- 증거 JSON: verdict, problems, 기대/전/후 fingerprint, headRevision/dirty, notBefore, 환경 snapshot, 각 명령의 argv·exit·timedOut·cleanupUnverified·environmentError·시작/종료 UTC·violations·출력 끝 4000자·전체 로그 경로와 sha256.
- 보고서별 sha256·timestamp는 TEST-SUMMARY 전체 로그에 있다(출력 길이와 관계없이 보존).
- Coordinator는 Verifier 보고와 증거를 대조해 `harness/tasks.json`의 작업 `evidence`에 요약 기록한다. 증거 파일은 `build/` 아래라 커밋하지 않는다.

## 6. 컨테이너 사용 증거

`IMAGE-DIGESTS`는 tag가 가리키는 로컬 이미지 ID만 기록한다. 예전 실행에서 받은 이미지가 남아 있어도 exit 0이므로 이번 실행에서 컨테이너를 썼다는 증거가 아니다. 실제 사용 증거는 smoke 테스트의 MySQL `8.4.` 버전 assertion과 보고서 system-out의 컨테이너 시작 로그다. Verifier가 이를 확인해 보고한다.

## 7. 모듈/테스트 추가 시 갱신 규칙

- 새 Gradle 모듈(실행 단위·시험 라이브러리)을 추가하면 같은 변경에서 모두 갱신한다(시험 라이브러리는 SBOM/CVE·GRADLE-DEPS 대상이 아님):
  - `TEST-SUMMARY` argv에 그 모듈의 `build/test-results/test` 경로, 하한(minTests) 조정
  - `GRADLE-CHECK.expectedReports`와 `outputMustMatch`의 `^> Task :apps:<module>:test$`
  - `GRADLE-DEPS-<MODULE>` 명령
  - `harness/scripts/cve-scan.py`의 `MODULES`(빠지면 그 모듈은 취약점 검사를 받지 않는다)
- 위 등록 누락은 `harness/tests/test_module_registry.py`(HARNESS-SELFTEST)가 실제 `settings.gradle.kts`·`apps/*/build.gradle.kts`·`commands.json`·`cve-scan.py`를 읽어 실패로 만든다(MASTER-BOOT-001). 이 시험은 `HARNESS-SELFTEST.minTests`가 harness 시험 수와 같은지도 본다(시험을 추가하면 하한도 올린다).
- 현재 하한은 전체 합계(625)만 검사한다. PAYMENT-DBACCOUNT-001 review round1 수정으로 621 → 625(payment-api DatabaseAccountSeparationSpec 12 → 15: 옛 volume 재실행 수렴·procedure 좁히기/넓히기·schema 객체 감시, master-api MasterDatabaseAccountSpec 8 → 9: schema 객체 감시). PAYMENT-DBACCOUNT-001에서 606 → 621(payment-api 395 → 407: DatabaseAccountSeparationSpec 4 → 12(임시 계정 DB 단위 DML 형태 시험을 실제 초기화 스크립트 계정의 표 단위 권한 시험으로 교체), MainConfigurationSpec 3 신규, PaymentApiContextSmokeSpec 4 → 5; master-api 44 → 47: MasterDatabaseAccountSpec 5 → 8 — 2026-10-08 구현자 전체 check 실행 결과는 tasks.json PAYMENT-DBACCOUNT-001 implementation). 이전 기록: 606 = 562 + master-api 44. MASTER-BOOT-001에서 4번째 실행 단위 `apps/master-api`를 추가하며 562 → 606(master-api: ArchitectureSpec 22, MasterApiContextSmokeSpec 6, MasterDatabaseAccountSpec 5, MasterUtcSessionSpec 5, MainConfigurationSpec 4, TestInfrastructureSpec 2 — 모듈 단위 실행 결과), HARNESS-SELFTEST 59 → 68(test_module_registry 9). 다른 모듈의 수는 아래 이전 기록이다. 이전 기록: 하한 562(payment-api 395, financial-batch 26, internal-admin 29, libs/test-support 112). CP-DB-001 리뷰 round1 수정으로 550 → 562(payment-api +12: CreatePaymentEligibilitySpec 8, UtcStorageSpec 3, EligibilityPortWiringSpec 2, CreatePaymentServiceSpec 17 → 16 — gate 예외 시험을 EligibilitySpec으로 이동). 이 값도 spec별 부분 실행 결과로 계산했으며 전체 check 실행은 호스트 메모리 부족으로 중단돼 미확인이다(Verifier 확인 대상). CP-DOMAIN-001에서 201 → 383(payment-api domain 시험 182개 추가: OrderKeySpec 10, TerminationEvidenceSpec 43, PaymentSpec 46, OrderPaymentDecisionTableSpec 57, OrderPaymentControlSpec 26), 리뷰 수정(M-3 늦은 성공 hold 멱등 2건, L-1 Payment ID 재사용 거부 2건)으로 383 → 387(OrderPaymentControlSpec 30). CP-CONTRACT-001에서 387 → 436(payment-api domain 시험 49개 추가: MerchantRequestIdFormatSpec 22, MoneySpec 13, OrderIntentSpec 13, OrderKeySpec 10 → 11), 리뷰 round1 L-3(짝 없는 서로게이트 거부·엄격 UTF-8) 수정으로 436 → 445(OrderIntentSpec 13 → 19, OrderKeySpec 11 → 14). CP-DB-001에서 445 → 550(payment-api +98: ReconstitutionSpec 9, PaymentSchemaSpec 16, PaymentStoreAdapterSpec 16, PersistenceFailuresSpec 5, CreatePaymentServiceSpec 17, CreatePaymentConcurrencySpec 11, CreatePaymentRollbackSpec 15, IncidentHoldConvergenceSpec 1, CreatePaymentBulkSpec 2, DatabaseAccountSeparationSpec 4, ArchitectureSpec 20 → 22; libs/test-support +7: queryAdapterDoesNotWriteWithJooq meta-test). 이 합계는 spec별 부분 실행 결과의 합이며 전체 check 한 번의 실행으로 아직 확인되지 않았다(Verifier 확인 대상). 또 CP-DB-001부터 check는 verifyRuntimeHasNoTestTools(apps)·verifyNoInstancio(전 모듈)를 포함한다. 모듈별 하한은 아직 없으며, 모듈별 `> Task :apps:<module>:test` 실행 규칙과 Verifier의 모듈별 개수 보고로 보완한다.
- AUTHN-001(2026-10-17): `GRADLE-CHECK.minTests`·`TEST-SUMMARY` 하한 1103 → **1481**(구현자 전체 `--no-daemon --no-build-cache --rerun-tasks check` 한 번의 실행, 2026-10-17T03:13:30Z exit 0: payment-api 965, master-api 319, institution-simulator 30, financial-batch 26, internal-admin 29, libs/test-support 112, 실패·오류·skip 0 — Verifier 확인 대상). 새 실행 단위 `apps/institution-simulator`의 등록(TEST-SUMMARY 경로·expectedReports·`GRADLE-DEPS-INSTITUTION-SIMULATOR`·cve-scan `MODULES`)은 같은 작업에서 했다(AUTHN-001 명세 §18). HARNESS-SELFTEST 하한은 81 그대로.
  - **배포 메모(AUTHN-001 검토 #4 n-5)**: PAYMENT V7 → V8_1 업그레이드(AUTHN-001 첫 배포) 동안 **AUTHN-001 이전 payment-api 인스턴스가 하나도 떠 있지 않아야 한다**(구 인스턴스를 모두 내린 뒤 새 인스턴스를 올린다 — rolling 혼재 금지). 구 인스턴스가 결제창 시각 없는 REQUESTED를 계속 넣으면 기동 전략이 재확인·재채우기(최대 3회) 뒤에도 남은 행 때문에 V8_1을 시도하지 않고 기동을 거부한다(fail-closed, AUTHN-001 명세 §20 R13·§22-11). 배포 파이프라인이 생기면 이 조건을 그 절차의 검사로 옮긴다.
- AUTH-001(2026-10-19, S8 기록): 새 실행 단위·새 의존성 없음 → `GRADLE-DEPS-<MODULE>`·cve-scan `MODULES` 변화 없음(GRADLE-DEPS×5 hash 비교는 VERIFY-GATE 보고). harness 시험 2종:
  `harness/tests/test_van_contract_golden.py`(4건 — 가상 VAN 계약 sim-van-1 golden을 Python 표준 라이브러리로 독립 생성해 `apps/{institution-simulator,payment-api}/src/test/resources/contracts/van-contract-sim-van-1.golden.json`과 바이트 비교, 같은 요청 재전송 payload hash 동일·업무 변경 시 다름, 프레임 배치·HMAC, 모든 메시지 첫 필드 `type`·null 없음 — 시험 전용 키만), `test_simulator_isolation.py` 확장(1건 — Simulator main 소스(van 모듈 포함, van 소스 존재 확인)가 PayCore 앱 패키지 `com.paycore.{payment,master,financial,admin,batch}`를 import하지 않음, 위반·주석 판별 자기 시험 포함). HARNESS-SELFTEST 하한 81 → **86**(S3, `test_module_registry`가 하한 = harness 시험 수를 요구). GRADLE-CHECK·TEST-SUMMARY 하한 1586 → **1875**(2026-10-19 precheck 실측 — master-api 320, payment-api 1,279, financial-batch 26, internal-admin 29, institution-simulator 107, test-support 112). golden 재생성(`--write`)은 검토된 계약 변경일 때만이며, 재생성 뒤 두 앱 시험(Simulator 프레임 codec·PG stub VAN)이 함께 통과해야 한다.
  - **배포 메모(AUTH-001 §20 — Coordinator 판정 2026-10-18)**: PAYMENT V9~V14(V11 기존 표 ALTER·V14 `authorization_attempt.route_profile_version` 추가 포함 — V14도 같은 전제, AUTH-001 §20-6) 배포 동안 **AUTH-001 이전 payment-api 인스턴스가 하나도 떠 있지 않아야 한다**(모두 내린 뒤 새 인스턴스 — rolling 혼재 금지). 이전 인스턴스는 V11 뒤에도 종료 근거를 출처 열 NULL로 써서 CHECK는 통과하지만 출처 없는 행이 생긴다. 기동 순서는 `CheckoutWindowCheckMigrationStrategy` 그대로(V8_1 pending이면 V8 → pin·채우기·재확인 → V8_1·V9~V14). **기존 Simulator 볼륨**은 `docker/simulator-mysql/init/01-simulator-database.sh`를 한 번 다시 실행해 표 단위 권한으로 수렴시켜야 Simulator가 기동한다(AU-20, `VanLedgerPrivilegeGuard`). 필수 설정 `paycore.authorization.timing.recovery.version`(기본값 없음)과 Route·timing 기동 검사는 AUTH-001 §20-3·4. 배포 파이프라인이 생기면 이 조건을 그 절차의 검사로 옮긴다.
- CANCEL-001(2026-10-20, S8 기록 — 구현자 보고, Verifier 확인 대상): 새 실행 단위·새 의존성 없음 → `GRADLE-DEPS-<MODULE>`·cve-scan `MODULES` 변화 없음(빌드 파일 불변 — GRADLE-DEPS×5 hash 비교는 VERIFY-GATE 보고).
  harness 시험: `test_van_contract_golden.py`가 sim-van-2 golden(`van-contract-sim-van-2.golden.json` — sim-van-1 부분 바이트 동일·sim-van-1 golden sha256 고정·취소 4종 메시지)도 독립 생성·바이트 비교한다(4 → 9건).
  `HARNESS-SELFTEST`·`GRADLE-CHECK`·`TEST-SUMMARY` 하한은 Coordinator가 이 작업의 전체 check 실측(CANCEL-001 명세 §18.11)으로만 올린다 — 구현자는 `commands.json`을 바꾸지 않았다. 골든 줄끝은 `.gitattributes`로
  `-text`(`*.golden.json`, `apps/*/src/test/resources/contracts/**` — CRLF 변환이 바이트 비교를 깨지 않게, 명세 §18.6).
  - **배포 메모(CANCEL-001 §20 — Coordinator 판정 rev 0.2)**: PAYMENT V15~V20(V17 기존 표 ALTER·V20 `idempotency_request` ALTER 포함)과 Route 설정 `sim-van-2`·취소 Capability·**새 profile-version** 배포 동안
    **CANCEL-001 이전 payment-api 인스턴스가 하나도 떠 있지 않아야 한다**(모두 내린 뒤 새 인스턴스 — rolling 혼재 금지). 이전 인스턴스는 등록부가 `sim-van-1`만 허용해 새 설정으로 기동하지 못하고, 이전 코드는
    `CancelPayment` 멱등 행·취소 참조 열을 모른다. 승인 Route profile-version 변경으로 배포 시점에 진행 중인 승인 UNKNOWN Attempt는 무효과 종결 경로를 잃고 조회·이관만 한다(D-AUTH1-007/D-CANCEL1-004 — fail-closed, Coordinator 수용).
    Simulator는 V5~V7(취소 원장 표·조회 로그 operation 열·권한) 뒤 같은 VAN 포트에서 두 계약을 받으므로 먼저 올려도 된다. 필수 설정 `paycore.cancellation.timing.*`(recovery.version 포함, 기본값 없음)과
    취소 기동 검사는 CANCEL-001 §20. **LIVE Gate 조건(D-CANCEL1-003)**: 이관된 취소 UNKNOWN을 확정하는 RECON Use Case 전에는 LIVE 불가.
- CP-QUERY-001(2026-10-20 — 구현자 기록, Verifier 확인 대상): 새 실행 단위·새 의존성·migration 없음(빌드 파일 불변 → `GRADLE-DEPS-<MODULE>`·cve-scan `MODULES` 변화 없음). `VERIFY-GATE.timeoutSeconds` 1800 → 5400
  (Coordinator 적용 — 내부 단계 최악 2760초 + 단계당 kill 대기 20초보다 커야 한다, F-3). 이 관계를 `test_module_registry.py`의 `test_verify_gate_timeout_covers_its_stages`(1800 대조)·
  `test_verify_gate_timeout_boundary`(경계: 합 = 통과, 합 − 1 = 문제)가 고정한다 → harness 시험 91 → **93**(하한은 Coordinator가 실측으로 올린다). `GRADLE-CHECK`·`TEST-SUMMARY` 하한도 이 작업의
  전체 check 실측(CP-QUERY-001 §18)으로 Coordinator가 올린다 — 구현자는 `commands.json`을 바꾸지 않았다. 배포 메모: schema 변경이 없고 새 endpoint(`GET /v1/payments/{paymentId}`)는 기존 공개 connector·Bearer 체인 그대로다(혼재 배포 제약 없음). 배포 파이프라인이 생기면 이 조건들을 그 절차의 검사로 옮긴다.
- WEBHOOK-001(2026-10-21 — 구현자 기록, Verifier 확인 대상): 새 실행 단위·Gradle 모듈 없음. S2까지 빌드 파일 불변(HttpClient 5는 S3에서 BOM 관리 버전으로 추가 예정 → 그때 GRADLE-DEPS×5·CVE-SCAN).
  - **배포 메모(WEBHOOK-001 명세 §20.1 — D-WH-009)**: PAYMENT V21~V24 배포 전에 **기존 MySQL 볼륨에서 초기화 스크립트를 한 번 다시 실행**한다(`docker compose exec mysql bash /docker-entrypoint-initdb.d/01-owned-databases.sh` —
    새 열 단위 helper `paycore_payment_privilege.grant_runtime_column_update`, 재실행 안전). 실행하지 않으면 V24가 실패해 payment-api가 기동하지 않는다(fail-closed). **이전 payment-api 인스턴스를 모두 내린 뒤** 적용한다
    (V23의 DEFAULT 전환 사이 INSERT는 SKIPPED가 된다). 배포 전 Outbox 행은 SKIPPED_PRE_DEPLOY. 필수 설정 `paycore.webhook.*`(기본값 없음). 배포 파이프라인이 생기면 이 조건을 그 절차의 검사로 옮긴다.
  - S3~S5 추가(2026-10-22): payment-api 빌드에 `org.apache.httpcomponents.client5:httpclient5`(Boot 4.1.1 BOM 관리 버전 5.6.4 — override 없음) → GRADLE-DEPS×5·CVE-SCAN 재실행 대상. PAYMENT V25·V26(시도 원장),
    Simulator V8·V9(`merchant_webhook_receipt` — 기존 Simulator 볼륨은 init 재실행 불필요: 기존 procedure로 표 권한만 준다). 새 harness 시험 `test_webhook_signature_golden.py` 4건(하한은 Coordinator가 실측으로 올린다 —
    구현자는 `commands.json`을 바꾸지 않았다). 배포 메모 추가: 이전 payment-api 인스턴스를 모두 내린 뒤 V25~V26 적용(시도 원장 없는 이전 delivery 코드와 혼재 금지 — 이전 코드에는 delivery가 없다),
    `paycore.webhook.{transport,delivery}`·`delivery.job` 필수(기본값 없음, claim-lease > dns-timeout + request-deadline + 5s 기동 검사), HttpClient wire·header 로거를 DEBUG로 올리지 않는다(서명 헤더·본문 노출 — 기본 WARN 고정),
    Simulator 가상 가맹점 수신기는 기본 0개(`paycore.simulator.merchant.receivers` 미설정 = 모든 수신 404)이며 로컬 E2E에서만 켠다.
    payment-api 인스턴스 시계는 NTP로 동기화한다 — claim lease는 인스턴스 시각으로 쓰고 비교하므로 시계 차이가 5s 여유를 넘으면 다른 인스턴스가 이르게 다시 claim할 수 있다(중복 전송 — at-least-once 범위 안, 가맹점은 eventId로 중복 제거).
    처리량: 한 claim은 min(batch, floor(claim-lease / 한 시도 예산))개라 Demo 값(lease 60s·예산 22s)에서는 회차당 최대 2건이다(job batch 50은 사실상 미적용) — 인스턴스당 처리량은 lease/예산과 fixed-delay가 정하며,
    운영값(lease·batch·fixed-delay·인스턴스 수)은 LIVE Gate에서 처리량 실측과 함께 정한다(WEBHOOK-001 §20.1 7항).
- ADMIN-BOOT-001(2026-10-24~26 — 구현자 기록, Verifier 확인 대상): 새 실행 단위 없음(`apps/internal-admin`은 기존 등록 그대로). 새 판정 명령 `TEST-SUMMARY-FRONTEND`(S1 — Vitest JUnit
  `apps/internal-admin/build/test-results/frontend`, `--not-before` 신선도)와 프론트 공급망 검사(lockfile 규칙·npm CVE canary — TECH-BASELINE §9)를 추가했다. internal-admin 빌드 의존성은 S1~S5에서
  Boot 4.1.1 BOM 관리 starter(security·session-jdbc·actuator·data-jpa·jooq·flyway)만 늘었고 S6~S8은 변화 없음 → `GRADLE-DEPS-INTERNAL-ADMIN`·cve-scan `MODULES` 변경 없음(등록은 이미 있음).
  새 컨테이너 이미지 없음(MySQL 8.4.11 그대로 — `IMAGE-DIGESTS` 변화 없음). S8 체크포인트 실측(명세 §25.9): 전체 미측정(전체 check 중단 — 명세 §25.9)(internal-admin 미측정), Vitest 39, HARNESS-SELFTEST 139.
  하한 제안(구현자는 `commands.json`을 바꾸지 않았다 — Coordinator가 실측으로 올린다): `GRADLE-CHECK.minTests`·`TEST-SUMMARY` → 미측정(전체 check 중단 — 명세 §25.9), `TEST-SUMMARY-FRONTEND` → 39.
  - **배포 메모(ADMIN-BOOT-001 §25 — D-15)**: internal-admin의 actuator는 별도 management 포트(`PAYCORE_ADMIN_MANAGEMENT_PORT`, 기본 바인딩 127.0.0.1 — 변수가 없으면 HTTP actuator 없음, **빈 값 금지**)에서
    `GET /actuator/health`만 익명으로 답한다. 웹 포트에는 actuator가 없다. 운영에서 `org.springframework.web`·`org.springframework.security` 로거를 DEBUG로 올리지 않는다(요청 단편이 로그에 남을 수 있다).
- `minTests`는 현재 실행되는 테스트 수 이하의 하한으로 두고, 테스트를 추가하면 올린다. 테스트를 지우거나 skip해서 하한을 낮추는 변경은 사용자 정책 변경 없이 하지 않는다.
- 새 컨테이너 이미지를 쓰면 `IMAGE-DIGESTS` argv에 추가한다.
- 명령/하한/출력 규칙 변경은 fingerprint에 반영되므로 해당 작업의 Reviewer가 diff로 확인한다.

## 8. Timeout

- 명령별 `timeoutSeconds`를 넘기면 `VERIFY-GATE`가 프로세스 그룹에 SIGKILL을 보내고 최대 10초 더 기다린 뒤 FAIL로 기록한다. 그룹을 벗어난 자손이 남아 pipe를 잡고 있으면 pipe를 닫고 `cleanupUnverified=true`로 표시한다(무한 대기 없음). 단순 자식(`test_timeout_is_failure_and_bounded`)과 새 session으로 빠져나가 pipe를 잡은 손자(`test_escaped_descendant_holding_pipe_is_bounded`) 두 경우를 시험한다. 실제 Gradle JVM이 모두 정리되는지는 timeout 발생 시 Verifier가 `ps`로 확인한다.
- 메모리: 판정 명령(`--no-build-cache --rerun-tasks check`)은 그대로이고, 빌드 메모리 상한은 `gradle.properties`·`build-logic`(Kotlin in-process 컴파일, test JVM `maxHeapSize=1g`, `maxParallelForks=1`)이 정한다. 근거·측정: TECH-BASELINE §7.2(MASTER-BOOT-001). 메모리 부족으로 중단된 실행은 FAIL/환경 문제로 기록하고 성공으로 보지 않는다.
- 작업 단위 timeout은 `harness/tasks.json`의 `taskTimeoutSeconds`. 수정 반복은 실패당 3회까지, 초과 시 원인과 다음 조치를 보고한다.

## 9. 아직 하지 않은 것 (후속)

- HARNESS-002: 범용 자동 Runner(명령 allowlist 실행기, Claude CLI 연동, 중단/재개 시험), CI 연동.
- 비밀 탐지(gitleaks 등)와 CVE-SCAN의 CI 정기 실행: HARNESS-002.
