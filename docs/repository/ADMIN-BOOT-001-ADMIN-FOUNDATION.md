# ADMIN-BOOT-001 internal-admin 기반 명세 (rev 0.5 — D-ADMIN1-001~012·분할 판정·spec-1~4 검토 반영, 변경 기록 §16.3)

> **정합 표시(S3-DOC-001, 2026-11-25 — NP-97·PC-54·PC-58·ADM (a)-1·(c)-6·(c)-7, 원문 보존)**: 이 문서 작성(2026-10-24) 뒤 바뀐 것 — ① 부트스트랩 인원: D-ADMIN1-002(SECURITY_ADMIN 1 + APPROVER 1) → **D-ADMIN1-022(SECURITY_ADMIN 2 + APPROVER 1)**, 현행 명세 ADMIN-BOOT-002(§4.9.1·§12.1 표시). ② 마스킹 해제 첫 적용: '후속(첫 마스킹 데이터 화면)·틀만' → **D-FLOW-002 ①(KYC)**·D-FLOW-005 ⑧(§1.2·§4.5.1·§4.5.3 표시). ③ 직전 TOTP 재인증의 DEMO 예외: **D-ADMIN-AUTH-DEMO-001 ③**(데모 간편 로그인 세션만 생략, 감사 기록, 2인 승인 그대로 — ADMIN-DEMO-LOGIN-001 DONE)(§4.7.4 표시). ④ 복구 작업 B 직후 SA 1명 상태: ADMIN-BOOT-002 §8 Q-1은 **D-ADMIN1-023**(복구 조건 유지 + 운영 하한 활성 SA ≥ 2·APPROVER ≥ 2 미만 지표·경보)으로 답이 났으나, 작업 B 자체가 SA 2명을 만들지는 결정이 없다(ADM 대장 (f)-6 확인 후보) — §4.9.2 본문은 바꾸지 않고 표시만 했다. 시험 이름 정정(PC-26 계열)은 이 작업 범위 밖. 현행 규칙 정본: `docs/rules/ADM.md`.

> **S1 구현 기록(2026-10-24, 2단계 Implementer)**: §18. Coordinator가 §17.10 Q-S0-1~3을 모두 권장안으로 판정했다(§18.1). 자기 완료 판정 아님.
>
> **S0 실측 기록(2026-10-24, 2단계 Implementer)**: §10 S0 결과는 §17. 설계 결정은 바꾸지 않았고 각 절에는 §17 참조만 붙였다. **Coordinator 판단이 필요한 항목은 §17.10**(14일 규칙과 알려진 취약점 충돌 2건, test-summary의 Vitest 보고서 형식 문제 1건). 저장소 코드·빌드 파일 변경 없음(실측은 저장소 밖 임시 디렉터리).

> **rev 0.4 갱신(2026-10-24)**: spec-3(CHANGES_REQUIRED: H-1, m-1~m-4, n-1~n-4)과 Coordinator 판정을 반영했다(새 사용자 질문 없음). 변경 기록 §16.2. rev 0.4 문장이 앞 rev와 다르면 rev 0.4가 우선한다. m-4(열 단위 UPDATE 표의 `FOR UPDATE`)는 S0 실측 결과 대기.
>
> **rev 0.3 갱신(2026-10-24)**: Q-ADMIN1-11·12가 권장안으로 답변됐다 — **D-ADMIN1-011**(재설정·재등록은 등록 코드만으로), **D-ADMIN1-012**(복구 대상 기존 계정은 재등록 완료 시 복구 시점 SECURITY_ADMIN·APPROVER만 `SYSTEM_RECOVERY` 재활성).
> 명세 검토 spec-2(CHANGES_REQUIRED: H-A·H-B, m-A~m-D, n-A~n-H)와 Coordinator 판정(H-A·m-B fail-closed)을 반영했다. 미결 보안 질문은 없다. 변경 기록 §16 rev 0.3. **rev 0.3 문장이 앞 rev와 다르면 rev 0.3이 우선한다.**
>
> **rev 0.2 갱신(2026-10-24)**: rev 0.1 질문 Q-ADMIN1-01~08과 추가 Q-ADMIN1-09·10은 모두 권장안으로 답변됐다 — **D-ADMIN1-001~010**(`docs/DECISIONS.md`).
> Coordinator §13 판정으로 분할이 확정됐다 — **ADMIN-BOOT-001**(tasks.json AC 1~11), **ADMIN-PAYMENT-OPS-001**(운영자 보정 취소·서비스 간 인증 — 신규 BACKLOG), **WEBHOOK-ENDPOINT-001**(Webhook GAVE_UP 조회·재전송 이관).
> 명세 검토 spec-1(CHANGES_REQUIRED: H-1~H-4, m-1~m-12, n-1~n-9; n-10은 OPS 이관)을 반영했다. 변경 기록은 §16. **이 문서는 rev 0.2 기준으로 다시 썼다** — rev 0.1의 OPS·Webhook 설계는 §4.12(이관 참고)로 줄였다.
> rev 0.2에서 올린 질문 후보 2건(Q-ADMIN1-11·12)은 rev 0.3에서 D-ADMIN1-011·012로 확정됐다.

rev 0.1 작성 2026-10-24 KST, rev 0.2 같은 날. 기준 commit `e53ee4b`(WEBHOOK-001 DONE, branch `feat/foundation-setup`). **1단계 명세이며 코드·시험·빌드 파일 변경 없음.**
작업 트리의 Coordinator 소유 미커밋 변경(`CLAUDE.md`, `docs/DECISIONS.md`, `docs/TECHNOLOGY-AND-PRINCIPLES.md`, `harness/{tasks,questions,progress}.json`)은 읽기만 했다.
자기 작업의 승인·완료 판정을 하지 않는다(Reviewer·Verifier 별도).

| 약어 | 문서 | 상태 |
|---|---|---|
| 지침 | `CLAUDE.md` | 지침(미커밋 변경 포함) |
| 결정 | `docs/DECISIONS.md`(D-ADMIN-001~008, **D-ADMIN1-001~010**, D-FE-001, D-CANCEL-005, D-CANCEL1-001·006·008, D-WH-004·006·008) | 확정 |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md`(§1 8대 원칙, §2 React, §3 JPA, §4 CQRS·화면, §5 Kafka/Redis) | 사용자 지정 |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md`(§1, §3, §9.1·§9.2, §14, §19 열 단위 권한 helper) | 구현 관례 |
| 하네스 | `docs/repository/HARNESS-VERIFICATION-CONTRACT.md`(§2, §4 fingerprint 보호 경로, §7) | 기준 |
| 시험 | `docs/baseline/architecture/08-testing-verification-architecture.md`(Test Fixture Policy) | 사용자 지정 |
| 보안·배치·관측 | baseline 05(16·18·29~31·41행), 01(23행 — D-ADMIN-005 편차), 07(14·18·54행) | 기준 |
| 작업 | `harness/tasks.json` ADMIN-BOOT-001(AC 11개)·ADMIN-PAYMENT-OPS-001(AC 6개)·WEBHOOK-ENDPOINT-001(AC5 이관)·CHECKOUT-REACT-001 | Coordinator 소유 |
| 질문 | `harness/questions.json`(Q-ADMIN1-01~10 ANSWERED, Q-ADMIN-SEC-VALUES·Q-ADMIN-APPROVER-STAFF PROPOSED) | Coordinator 소유 |

---

## 0. 요약

- **목적**: internal-admin을 실제 운영자 기반으로 만든다 — 소유 ADMIN DB, 자체 계정·비밀번호·TOTP·실패 잠금·IP 허용 목록, 고정 4역할·불변 actor ID·append-only 감사, Spring Session JDBC 단일 세션·CSRF,
  2인 승인 틀과 직전 TOTP 재입력을 **ADMIN 소유 명령 6종**(역할 부여·잠금 해제·정지 해제·TOTP 재등록·비밀번호 재설정·역할 재활성)으로 실증, 1회용 부트스트랩·오프라인 복구 명령, React SPA 같은 출처 서빙과 프론트 빌드·시험·CVE 경로.
- **ADMIN 소유 명령의 핵심 구조(H-1)**: 승인 = 실행(D-ADMIN1-006)을 **단일 로컬 TX**로 한다 — 승인 행 잠금 재독 → 승인 판정 → 계정 행 잠금(actor ID 오름차순) → 승인자 TOTP 단계 소비 → 행위자·요청자 재검사 → 대상 `security_version` 재비교 → 계정 변경·세대 증가 → 감사(실행자 = 승인자).
  이 종류는 `EXECUTING`·`EXECUTION_UNKNOWN`에 **도달할 수 없다**(domain 판정표). commit 결과 불명확은 승인 행 재조회로 수렴한다. orchestrator는 `approval` application 하나이고 의존 방향은 `approval → account provided`뿐이다.
- **1회성 명령(H-2)**: 부트스트랩은 고정 PK 행 `admin_bootstrap(id=1)`을 계정 생성과 같은 TX에 INSERT하고 패자는 1062로 거부된다. 복구는 "활성 SECURITY_ADMIN 또는 APPROVER 0명 + **열린 복구 회차 행 UNIQUE**"로 동시 실행만 막고 회차별로 반복 가능하다.
  코드 출력은 TTY 또는 0600 새 파일만(logger 금지).
- **재설정·재등록(H-3, D-ADMIN1-009·011)**: 실행 시 대상 역할을 정지(이력 보존), 등록 코드는 실행자(승인자)에게 승인 응답으로 1회만 표시, 본인은 **등록 코드만으로** 해당 요소만 다시 설정(PASSWORD_RESET = 비밀번호만, TOTP_REENROLL = TOTP만),
  재등록 뒤 별도 2인 승인 `ROLE_REACTIVATE` — 그 요청자·승인자는 원인 재설정의 **요청자와 승인자(코드 수령자) 모두와 달라야** 한다(rev 0.3 H-A, fail-closed).
- **복구 역할(D-ADMIN1-012, rev 0.3 m-B)**: 복구 TX가 대상의 복구 시점 SECURITY_ADMIN·APPROVER 보유 집합을 코드 행에 기록하고 모든 역할을 정지, 완료 TX는 회차가 열려 있고 그 역할의 활성 계정 수가 여전히 0일 때만 그 역할을 `SYSTEM_RECOVERY` 재활성한다(아니면 일반 2인 승인).
- **전역 잠금 순서(rev 0.3 m-C, §4.13)**: 복구 회차 → 승인 행 → 계정 행(actor ID 오름차순) → 등록 코드 행. 1213·1205는 409 또는 비영 exit, 상태 불변.
- **권한·잠금 정밀화(m-1~m-7)**: 로그인은 모든 분기에서 BCrypt 1회·TX 1회·감사 1회(시간 비구별), 잠금 TX 안에서 LOCKED·한도 먼저 판정, TOTP 실패 계수는 승인 TX와 별도 TX로 커밋, 등록 코드 원자 소비, 역할은 append-only 이벤트 + 계정 행 현재값, 승인 요청·등록 코드는 열 단위 UPDATE, `security_version` 증가 사건 전수 열거.
- **하네스(m-8~m-10)**: fingerprint 보호 경로에 `apps/*/frontend` 소스 추가, Vitest `allowOnly:false`·`passWithNoTests:false`, npm canary lockfile에 dependencies·devDependencies 취약 패키지 각 1개(D-ADMIN1-007 — 둘 다 FAIL).
- **미결 보안 질문 없음**(§12 — D-ADMIN1-001~012 확정).
- 규모(§15): WEBHOOK-001의 약 **1.2~1.6배**(internal-admin Kotlin 시험 +370~500, Vitest +30~60, harness +6~10).

---

## 1. 범위와 범위 밖

### 1.1 범위(tasks.json ADMIN-BOOT-001 AC 1~11)

| 구분 | 내용 | AC |
|---|---|---|
| ADMIN DB | `paycore_admin`, migration/runtime 계정·role·procedure(표·열 단위), Flyway, Spring Session 표 | 1 |
| 계정·인증 | BCrypt, TOTP 암호화 저장, 2단계 로그인, 실패 합산 잠금(자동 해제 없음), IP 허용 목록, 본인 재설정·재등록·잠금 해제 불가 | 2, 9 |
| 역할·감사 | 4역할(겸직 허용·요청 단위 분리), SECURITY_ADMIN만 부여, 회수 1인, 불변 actor ID, append-only 감사, 감사 조회 권한, 마스킹 해제 틀 | 3, 9, 10 |
| 세션·CSRF | Spring Session JDBC, 새 로그인 우선, 세션 ID 재발급, 쿠키, 세션 저장 동기화 토큰 → 쿠키 → 헤더, Demo 값 | 4, 9 |
| 2인 승인·재인증 | 승인 틀, ADMIN 소유 명령 6종, 1인 즉시 명령(생성·정지·회수), 직전 TOTP | 5, 6, 10 |
| 재설정·복구 | 역할 정지 → 재활성 승인, 1회용 부트스트랩, 오프라인 복구 | 9, 11 |
| React SPA | Vite+TS 골격·레이아웃·오류 화면, 같은 출처, JSON API, 빌드·시험 Gradle check, lockfile, npm CVE(둘 다 FAIL) | 7, 9 |
| 시험 | Fixture Policy, 보안 시험, 실제 MySQL 경합·rollback | 8 |

### 1.2 범위 밖

| 항목 | 소유 | 근거 |
|---|---|---|
| 소유 서비스 내부 API·서비스 간 인증·운영자 보정 취소·`oct1:` golden·ArchUnit 완화·형식 오류 감사 | **ADMIN-PAYMENT-OPS-001** | 분할 판정(§4.12) |
| Webhook GAVE_UP 조회·수동 재전송·LIVE endpoint 2인 승인 | **WEBHOOK-ENDPOINT-001** | 분할 판정, D-WH-008 |
| 결제창 React 전환 | CHECKOUT-REACT-001 | D-FE-001 — 이 작업은 convention plugin으로 재사용만 보장 |
| MASTER 명령 화면·LIVE API key | MASTER-LIFECYCLE-001·MASTER-CREDENTIAL-001 등 | D-ADMIN-003 |
| 마스킹 해제 첫 적용 화면 | 후속(첫 마스킹 데이터 화면) | AC10, Coordinator 판정 → 답(D-FLOW-002 ①, 2026-11-25 — Q-PII-3 일부): 마스킹 해제의 **첫 실제 적용 데이터 = KYC(대표자 생년월일·실소유자 정보)**, 구현은 MERCHANT-FLOW-001(IN_PROGRESS — main 코드는 아직 틀만, `UnmaskService.kt:18,63` `NoDataSource`). `UNMASK`는 Demo seed에 미리 주지 않고 SECURITY_ADMIN이 기존 2인 역할 부여로 부여, 사유에 카드번호 패턴 거부 적용(D-FLOW-005 ⑧ — 코드 미적용, `UnmaskService.kt:32-33`) |
| 운영 mTLS·KMS·운영 IP·운영 보안 수치·APPROVER 인원 | LIVE Gate | Q-ADMIN-SEC-VALUES, Q-ADMIN-APPROVER-STAFF |
| 브라우저 E2E, npm 번들 SBOM | 후속 후보 | §13 |
| Kafka·Redis | 도입 안 함 | §9 |

---

## 2. AC 대응표(tasks.json ADMIN-BOOT-001 AC 1~11)

| # | AC 요지 | 절 | 계획 시험(이름 가역적) |
|---|---|---|---|
| 1 | ADMIN DB, 관리 메타데이터만, 표 단위 runtime 권한 | §4.2 | `AdminDatabaseAccountSpec`(SHOW GRANTS 정확 비교·표/열 권한·1142/1143·누락 표 감지·schema 객체 없음), `AdminSchemaSpec` |
| 2 | 해시·TOTP 암호화·잠금·IP·본인 재설정 불가 | §4.3·§4.8 | `PasswordHashingSpec`, `TotpVerifierSpec`(RFC 6238 벡터), `TotpSecretCipherSpec`, `LoginLockoutSpec`·`LoginLockoutConcurrencySpec`, `LoginPathEquivalenceSpec`, `IpAllowlistSpec`, `SelfServiceForbiddenSpec` |
| 3 | 4역할·SECURITY_ADMIN 부여·본인 불가·불변 actor ID·감사·마스킹 해제 권한+사유 | §4.5·§4.6 | `AdminPermissionSpec`, `RoleEventAppendOnlySpec`, `ActorIdImmutabilitySpec`(1143), `AdminAuditAppendOnlySpec`, `AuditFailureClosesActionSpec`, `UnmaskFrameworkSpec` |
| 4 | Spring Session JDBC·동시 1·ID 재발급·쿠키·CSRF·Demo 값 | §4.4 | `SessionFixationSpec`, `SingleSessionSpec`·`SingleSessionRaceSpec`, `SessionCookieAttributesSpec`, `SpaCsrfSpec`, `SessionTimeoutSpec`, `AnonymousSessionGrowthSpec` |
| 5 | 2인 승인 틀(요청자≠승인자·APPROVER·버전 무효·재검사 계약·안전 방향 1인) | §4.7 | `ApprovalRequestSpec`(domain 판정표 — ADMIN 소유 종류의 EXECUTING 도달 불가 포함), `ApprovalConcurrencySpec`, `ApprovalVersusCloseRaceSpec`, `SelfApprovalForbiddenSpec`, `StaleTargetInvalidatesSpec`, `DuplicatePendingSpec`·`DuplicatePendingRaceSpec`, `ApprovalLockOrderSpec`(1213) |
| 6 | 2인 승인·마스킹 해제 직전 TOTP 재입력 | §4.7.4 | `StepUpTotpSpec`, `StepUpTotpReuseRaceSpec`, `StepUpFailureCountsSpec` |
| 7 | React SPA·같은 출처·JSON·CSRF 헤더·Gradle check·lockfile·npm CVE | §4.10·§4.11 | Vitest, `SpaServingSpec`, harness `test_cve_scan.py`·`test_module_registry.py`·`test_fingerprint.py` 확장 |
| 8 | Fixture Policy·보안 시험 | §7 | §7 전부, `AdminFixture`·`ApprovalFixture` |
| 9 | D-ADMIN1-001~008 | §4.3~§4.7·§4.11.4 | `NewLoginWinsSpec`, `BootstrapSpec`, `AccountLifecycleSpec`, `RoleRuleSpec`, `LoginLockoutSpec`, `ApproveExecutesSpec`, npm canary 시험, `AuditQueryPermissionSpec` |
| 10 | ADMIN 소유 2인 승인 명령으로 틀 실증, 마스킹 해제는 틀까지 | §4.7.6 | `AdminOwnedCommandSpec`(6종 × 성공·무효·자기 승인·역할 상실·1213), `RevokeDuringApprovalRaceSpec` |
| 11 | D-ADMIN1-009·010(+ 011·012) | §4.7.7·§4.9·§4.3.6 | `CredentialResetSuspendsRolesSpec`, `ResetCodeDisclosureSpec`, `RoleReactivateSpec`, `EnrollmentPurposeFlowSpec`, `SuspendRevokesEnrollmentSpec`, `BootstrapConcurrencySpec`, `RecoveryCommandSpec`·`RecoveryConcurrencySpec`·`RecoveryRoleRestoreSpec` |

### 2.1 이관된 AC(분할 판정 2026-10-24 — 이 작업의 완료 조건이 아님)

| rev 0.1 AC | 내용 | 이관 작업 |
|---|---|---|
| 7 | 소유 서비스 내부 API·서비스 간 인증·장애 비전파 | ADMIN-PAYMENT-OPS-001 AC1 |
| 10 | 운영자 보정 취소 진입점·대상 조회·`oct1:`·revision 무효·APPROVER·TOTP | ADMIN-PAYMENT-OPS-001 AC2·AC5 |
| 11 | ArchUnit 진입점 규칙 완화 | ADMIN-PAYMENT-OPS-001 AC3 |
| 12 | 형식 오류 OperatorId·approvalId 감사 | ADMIN-PAYMENT-OPS-001 AC4 |
| — | spec-1 n-10(미해결 실행 시 새 승인 거부·회복 job·재조회 TX 밖) | ADMIN-PAYMENT-OPS-001 AC6 |
| 13 | Webhook GAVE_UP 조회·재전송·LIVE endpoint 2인 승인 | WEBHOOK-ENDPOINT-001 AC5 |

---

## 3. 현황 조사(2026-10-24, `e53ee4b`)

### 3.1 있는 것과 없는 것

| 영역 | 있는 것 | 없는 것 |
|---|---|---|
| internal-admin main | `InternalAdminApplication`(기본 사용자 자동 구성 제외), `security.SecurityConfig`(전부 인증 필요·인증 수단 없음) | 업무 모듈·DB·로그인·화면 |
| 의존성 | webmvc·thymeleaf·security·modulith-api·kotlin-reflect | JPA·Flyway·MySQL·jOOQ·Session JDBC·actuator |
| 시험(29) | `NoGeneratedDefaultUserSpec`, `TechSmokePageSecuritySpec`(Thymeleaf), `ArchitectureSpec`(DB 접근 금지), `TestInfrastructureSpec`(Testcontainers 부재) | — |
| 로컬 DB | compose mysql:8.4.11, `01-owned-databases.sh`(MASTER·PAYMENT 표 단위, PAYMENT 열 단위 helper) | ADMIN schema |
| 프론트 | 없음(WSL에 node/npm 없음, Windows npm만) | 전부 |
| CVE | runtime SBOM + osv-scanner 2.6.0 canary | npm |
| BOM | Spring Security 7.1.1, Spring Session 4.1.1, `spring-boot-starter-session-jdbc` 관리 | BouncyCastle |

### 3.2 발견

1. **F-1 internal-admin ArchUnit이 DB 접근을 금지한다** → §3.3 기대값 변경 기록.
2. **F-2 `TestInfrastructureSpec` Testcontainers 부재 시험이 반대가 된다** → §3.3.
3. **F-3 TECH-001 Thymeleaf smoke가 D-ADMIN-008로 근거를 잃는다** → §3.3.
4. **F-4~F-5, F-10**(`oct1:` golden 부재, 지문의 승인자 포함, 내부 HMAC 서명 범위) → ADMIN-PAYMENT-OPS-001 AC5로 이관(§4.12).
5. **F-6 Spring Session 기본 표 이름(대문자)이 권한 procedure 이름 규칙에 거부된다** → `admin_session`.
6. **F-7 `apps/*/src/**` 아래 프론트를 두면 `node_modules`가 fingerprint 보호 경로에 걸린다** → `apps/internal-admin/frontend/`, 그리고 frontend 소스를 보호 경로에 **추가**(m-8, §4.11.5).
7. **F-8 `FAIL_ON_PROJECT_REPOS` 때문에 Node 다운로드 저장소를 settings에 선언해야 한다**.
8. **F-9 Gradle dependency verification이 없다** → Node 압축 SHA-256 고정 검사.
9. **F-11 Argon2는 BOM 밖** → BCrypt.
10. **F-12 TOTP는 Spring Security 내장 수단이 아니다** → RFC 6238 adapter 구현.
11. **F-13(rev 0.2) ADMIN에 열 단위 UPDATE helper가 필요하다**: 승인 요청·등록 코드·계정 표의 생성 불변 열을 DB가 막으려면 `runtime_column_privilege_sql paycore_admin`을 초기화 스크립트에 추가해야 한다(PAYMENT 선례 §19, D-WH-009). 열 권한이 있는 표에 표 단위 helper를 다시 부르면 안 된다(관례 §19 측정 주의).
12. **F-14(rev 0.2) 부트스트랩·복구 runner의 계층 위치**: 알려진 계층(web·persistence·query·integration·batch) 밖에 CLI 계층이 없다. 기존 관례(`CancellationRecoveryScheduler`가 `adapter.batch`)를 따라 **비웹 inbound는 `account.adapter.batch`**에 두고 `batchAdapterUsesOnlyApplication`(provided만 호출)을 그대로 적용한다.

### 3.3 기존 시험 기대값 변경 기록(Coordinator 승인 2026-10-24 — 하한은 낮추지 않는다)

| 시험 | 기존 기대 | 새 기대 | 근거 |
|---|---|---|---|
| `ArchitectureSpec` "internal-admin has no database access dependencies" | `com.paycore.admin..` 전체가 `java.sql`·`javax.sql`·`jakarta.persistence`·`org.hibernate`·`org.jooq`·`org.springframework.jdbc`에 의존하지 않음 | 규칙을 **허용 패키지 정확 열거**로 교체(m-12): 영속 기술(JPA·Hibernate·JDBC·Spring Data)은 `com.paycore.admin.*.adapter.persistence`만, jOOQ는 `*.adapter.query`만(기존 `jooqOnlyInQueryAdapter`), Spring Session은 `com.paycore.admin.security.session`만(`org.springframework.session..` 허용, JPA·jOOQ·`java.sql` 금지), 부트스트랩·복구 runner(`account.adapter.batch`)는 영속 기술 금지·provided만. 공통 `security` 패키지의 영속 기술은 위 하위 패키지 외 금지. 위반 fixture 유지·추가: web이 `EntityManager` 주입, batch runner가 `JdbcTemplate` 사용, `security`가 jOOQ `DSLContext` 사용, `security.session` 밖 Spring Session JDBC 타입 사용 → 각각 실패 | D-ADMIN-005(ADMIN DB 소유 — baseline 01 23행 편차) |
| (rev 0.3 n-D) 위 교체 규칙의 `security.session` 허용 범위 | — | 기본은 `org.springframework.session..` 타입만. Spring Session JDBC 구성(표 이름·직렬화·`JdbcIndexedSessionRepository` 사용자 정의)에 `javax.sql.DataSource`·`org.springframework.jdbc..` 타입이 **필요한지 S0에서 확인**하고, 필요하면 그 타입만 `com.paycore.admin.security.session`에 한정 허용하는 행을 이 표에 **추가 기록**한 뒤 규칙에 반영한다(조용히 넓히지 않음 — 허용 변경은 Reviewer diff 대상). 필요 없으면 지금 규칙 그대로. **S0 결과(§17.6): 필요 없음 — 규칙 그대로**(표 이름·질의 교체·저장 방식은 `SessionRepositoryCustomizer<JdbcIndexedSessionRepository>`·Boot `spring.session.jdbc.*` 속성만으로 구성됨, 실측) | D-ADMIN-004(Spring Session JDBC) |
| `TestInfrastructureSpec` "test-support does not pull Testcontainers into the DB-less admin test classpath" | `org.testcontainers.mysql.MySQLContainer` 로드 실패 | 같은 클래스가 **시험 classpath에 있고 runtime classpath에 없음**(`verifyRuntimeHasNoTestTools`가 runtime 쪽을 이미 검사) — 시험 이름·주장 교체 | D-ADMIN-005 |
| `TechSmokePageSecuritySpec`(5건) + `ArchitectureSpec` TechSmoke 제외 확인(1건) | Thymeleaf 화면: 미인증 GET 401/403, 인증 GET 200 + CSRF hidden input, CSRF 없는 POST 403, CSRF 있는 POST 302, 미인증 POST 거부 / 시험 소스 컨트롤러가 ArchUnit import에서 제외 | 같은 보안 주장을 SPA/JSON으로: 미인증 `/api/**` 401, 인증 GET 200 + `__Host-PCADMIN-XSRF` 쿠키, 헤더 없는 POST 403, 맞는 헤더 POST 200, 미인증 POST 거부(5건 이상, `SpaCsrfSpec` 일부) / 시험 소스 `@RestController` 대역의 import 제외 확인(1건) — Thymeleaf 의존성·템플릿 제거 | D-ADMIN-008 |

---

## 4. 설계

### 4.1 모듈 구조(`com.paycore.admin.<module>`)

| 모듈 | 책임 | 주요 타입(§14 명명, 가역적) |
|---|---|---|
| `account` | 계정 생명주기, 비밀번호·TOTP, 로그인 2단계, 잠금, 역할 상태, 세션 세대, 등록 코드, 부트스트랩·복구 | domain `AdminAccount`(Aggregate), `AdminActorId`, `LoginName`, `AdminRole`, `RoleState`, `AccountStatus`, `TotpStep`, `LoginAttemptPolicy`, `AdminOwnedCommand`(sealed 6종); required `AdminAccountRepository`(→`JpaAdminAccountRepository`), `AdminRoleEventStore`, `EnrollmentCodeStore`, `OneShotCommandStore`(부트스트랩·복구 고정 행), `PasswordHasher`, `TotpVerifier`, `TotpSecretCipher`, `LoginNameDigester`, `AdminAccountListReader`(jOOQ); provided `AdminLoginUseCase`, `AdminSessionValidityQuery`, `AdminAccountCommandUseCase`(1인 명령), `AdminOwnedCommandExecutor`(승인 증거로 실행 — §4.7.3), `StepUpVerifier`, `BootstrapUseCase`, `RecoveryUseCase`, `AdminAccountQuery`; `adapter.batch` `OneShotCommandRunner` |
| `audit` | append-only 감사 기록·조회 | domain `AdminAuditEvent`; required `AdminAuditStore`, `AdminAuditReader`; provided `AdminAuditRecorder`, `AdminAuditQuery` |
| `approval` | 승인 요청 상태 기계, **ADMIN 소유 명령의 orchestrator** | domain `ApprovalRequest`(Aggregate), `ApprovalKind`, `ApprovalTargetSnapshot`, `ApprovalDecisionTable`; required `ApprovalRequestRepository`, `ApprovalRequestListReader`; provided `ApprovalUseCase`(요청·철회·승인·거절), `ApprovalQuery` |
| `console` | SPA 정적 번들 fallback·캐시 헤더 | `adapter.web` |
| `security`(공통, 기존) | SecurityFilterChain, IP filter, 세션 검사 filter, CSRF·헤더, `security.session`(Spring Session 구성) | provided Port만 호출 |

- 의존 방향: `approval → account`(provided), `approval → audit`, `account → audit`. **`account → approval` 금지**(Modulith verify + ArchUnit 명시 규칙). `security → account·audit`(provided).
- rev 0.1의 `paymentops`는 ADMIN-PAYMENT-OPS-001로 이관(이 작업에는 없다).

### 4.2 ADMIN DB(`paycore_admin`)

#### 4.2.1 계정·권한
- `01-owned-databases.sh`: `paycore_admin` schema, `paycore_admin_migration`·`paycore_admin`(role `paycore_admin_runtime`)·잠긴 grantor, `runtime_privilege_sql paycore_admin` + **`runtime_column_privilege_sql paycore_admin`**(F-13). `compose.yaml`·`.env.example`에 `PAYCORE_ADMIN_DB_PASSWORD`·`PAYCORE_ADMIN_DB_MIGRATION_PASSWORD`(`:?` fail-closed). 기존 볼륨은 스크립트 재실행(배포 메모 §14 R-6).
- Flyway `db/migration/admin` + `db/runtime-privilege/admin`, jOOQ codegen은 업무 schema 위치만. 시각 `DATETIME(3)` UTC, 문자열 키 `utf8mb4_0900_bin`.

#### 4.2.2 표와 권한(m-6 — 이름·자료형은 S2 확정)

| 표 | 성격 | runtime 권한 | 비고 |
|---|---|---|---|
| `admin_account` | Aggregate root: actor ID PK, login_name UNIQUE, 표시명, 상태, BCrypt 해시, TOTP 암호문·키 버전·`last_totp_step`, 실패 횟수, 잠금 시각, **현재 활성 역할 집합**, `session_generation`, `security_version`, `version` | SELECT, INSERT, **열 단위 UPDATE**(actor_id·login_name·created_at 제외) | DELETE 없음(actor ID 재사용 방지 — D-ADMIN-004). 생성 불변 열 UPDATE 1143 시험 |
| `admin_role_event` | **append-only** 역할 이벤트(GRANT·REVOKE·SUSPEND·REACTIVATE, 역할, 대상, 행위자·승인 ID, 원인 재설정 승인 ID, 시각) | SELECT, INSERT | 이력 원천. 계정 행의 현재 역할 집합은 같은 TX에서 갱신(요청마다 PK 단건으로 판정하기 위함). 두 값 일치는 `RoleStateConsistencySpec`(이벤트 재생 = 현재값) |
| `admin_enrollment_code` | 1회용 등록 코드(SHA-256 검증값 PK, 대상, 목적 ENROLL·PASSWORD_RESET·TOTP_REENROLL·RECOVERY, 발급 승인 ID, 만료) | SELECT, INSERT, **열 단위 UPDATE**(`used_at`, `revoked_at`만) | §4.3.6 |
| `admin_approval_request` | 승인 요청(approval_id PK, 종류, 대상, 대상 스냅샷·버전, 요청자 + 표시명, 요청 시각, 만료 시각, 상태, 결정자 + 표시명, 결정 시각, 결과 code, `version`, 생성 열 `pending_key` UNIQUE) | SELECT, INSERT, **열 단위 UPDATE**(상태·결정자·표시명·결정 시각·결과 code·version) | 생성 불변 열 UPDATE 1143 시험, 중복 PENDING 차단(n-6) |
| `admin_audit_event` | **append-only** 감사 | SELECT, INSERT | `@Immutable` |
| `admin_bootstrap` | 부트스트랩 1회성 고정 행(id=1 PK) | SELECT, INSERT | §4.9.1 |
| `admin_recovery_episode` | 복구 회차(회차 번호 PK, 열린 표시 생성 열 UNIQUE, 만료·종료 시각) | SELECT, INSERT, **열 단위 UPDATE**(`closed_at`만) | §4.9.2 |
| `admin_session`, `admin_session_attributes` | Spring Session JDBC(F-6) | SELECT, INSERT, UPDATE, DELETE | 만료 정리 |

- 열 단위 UPDATE와 Hibernate: Hibernate는 updatable 열 전체를 SET에 넣으므로 불변 열은 Entity에서 `updatable = false`로 둬야 열 권한과 맞는다(관례 §9.2 주의). helper 1회 8열 상한 — 필요하면 여러 번 호출.
- (rev 0.4 m-4) **S0 실측 결과: 허용됨(§17.2)** — MySQL 8.4.11 컨테이너에서 runtime role(기본 role) 경유로 표 단위 SELECT + 열 단위 UPDATE만 가진 네 표 모두 `FOR UPDATE`·`FOR SHARE` 성공, 두 번째 연결은 1205로 실제 대기, 불변 열 UPDATE는 1143. 대조: SELECT만 또는 SELECT·INSERT만인 표는 잠금 읽기 1142. 따라서 append-only 표(`admin_role_event`·`admin_audit_event`·`admin_bootstrap`)는 잠글 수 없다(설계도 잠그지 않음). 아래는 rev 0.4 원문: MySQL 8.4는 잠금 읽기(`SELECT … FOR UPDATE`)에 SELECT와 UPDATE 권한을 요구한다(관례 §9.2). 표 단위 UPDATE 없이 **열 단위 UPDATE만** 있는 표(`admin_account`·`admin_approval_request`·`admin_enrollment_code`·`admin_recovery_episode`)에서 runtime role로 `FOR UPDATE`가 허용되는지 S0에서 실제 컨테이너로 측정해 이 줄에 결과를 기록한다. 거부(1142)되면 이 설계의 잠금 경로가 성립하지 않으므로 대안(표 단위 UPDATE + Entity `updatable=false`·트리거 없는 불변 검사 등) 선택은 **Coordinator 판단**으로 남긴다 — 구현자가 임의로 권한을 넓히지 않는다.

### 4.3 계정·로그인·TOTP·잠금

#### 4.3.1 상태와 전이

| 상태 | 의미 | 전이 |
|---|---|---|
| `PENDING_ENROLLMENT` | 생성됨 또는 재설정·재등록·복구 대기(코드 유효). 계정 행에 **대기 사유**(`pending_purpose` = ENROLL·PASSWORD_RESET·TOTP_REENROLL·RECOVERY)와 **발급 근거**(`pending_basis` = 승인 ID 또는 복구 회차 번호, ENROLL은 발급 코드 ID)를 둔다(rev 0.3 H-B) | 등록 완료 → `ACTIVE`; (rev 0.4 m-2) 재설정 코드 재발급(새 2인 승인) → `PENDING_ENROLLMENT`(근거 = 새 approvalId), 역할 없는 계정 1인 재발급 → `PENDING_ENROLLMENT`(근거 = 새 발급 ID) |
| `ACTIVE` | 로그인 가능 | 실패 한도 → `LOCKED`, 정지 → `SUSPENDED`, 재설정·재등록 실행 → `PENDING_ENROLLMENT`(역할 정지) |
| `LOCKED` | 실패 한도 도달, 자동 해제 없음(D-ADMIN1-005) | `ACCOUNT_UNLOCK` 승인 → `ACTIVE`, 재설정·재등록 승인 → `PENDING_ENROLLMENT` |
| `SUSPENDED` | 정지(D-ADMIN1-003 1인 즉시) | `ACCOUNT_REACTIVATE` 승인 → `ACTIVE`(정지 직전 상태가 LOCKED였으면 LOCKED) |

`LOCKED`·`SUSPENDED`·`PENDING_ENROLLMENT` 전이는 **`session_generation`+1**(n-7 — 기존 세션 즉시 무효).

- (rev 0.3 H-B) **정지 TX는 대상의 미사용 등록 코드를 모두 `revoked_at` 처리**하고 대기 사유를 비운다(정지 해제는 정지 직전 상태로 돌아가되 폐기된 코드는 되살리지 않는다 — 재발급은 각 코드의 재발급 주체 규칙 §4.3.6). 정지 해제 뒤 대기 상태로 돌아간 계정은 새 코드 발급 전까지 등록을 완료할 수 없다.
- (rev 0.3 n-E) **SECURITY_ADMIN 본인 정지는 거부**한다(본인 권한 변경 불가·회수 규칙과 일관 — 대상 = 요청자이면 domain 거부). 본인 계정의 긴급 차단은 다른 SECURITY_ADMIN의 정지 또는 세션 로그아웃으로.

#### 4.3.2 2단계 로그인과 경로 동등성(m-4)

1. `POST /api/auth/password {loginName, password}`
   - (a) 비잠금 읽기로 해시와 `security_version`(없는 계정이면 **같은 cost의 고정 더미 해시**)을 가져와 **BCrypt 비교를 정확히 1회** 한다(잠금 밖 — 같은 계정 요청이 비교 시간 동안 줄 서지 않게).
   - (b) TX 1회: 계정 행 `FOR UPDATE` → **먼저** 상태(LOCKED·SUSPENDED·PENDING)와 한도 도달을 판정 → 그다음 BCrypt 결과 적용. 실패면 계수+1(한도 도달 시 `LOCKED`·세대+1·`security_version`+1). (a)와 (b) 사이 `security_version`이 바뀌었으면 결과를 적용하지 않고 일반 실패(계수 없음).
     성공이면 세션 ID 재발급 + 세션에 `PASSWORD_VERIFIED`·actor ID·읽은 `security_version`·단계 만료(Demo) 기록.
   - (c) 감사 1행(같은 TX). 없는 계정도 TX 1회·감사 1행(actor 없음, login_name은 비밀 키 HMAC — n-2).
   - 응답은 원인 비구분 `INVALID_CREDENTIALS` 하나(D-ADMIN1-005).
   - **시험(`LoginPathEquivalenceSpec`)**: 성공·틀린 비밀번호·없는 계정·LOCKED·SUSPENDED·PENDING·한도 도달 분기마다 `PasswordHasher.matches` 호출 1회, 같은 cost, TX 시작 1회, 감사 append 1회, 응답 본문·상태 코드 동일(성공 제외). 처리 시간 자체는 측정하지 않고 호출 수로 고정한다.
   - (rev 0.3 n-G) **잔여 시간차 수용 근거**: 분기 사이에 남는 차이(없는 계정의 PK 조회 결과 없음·행 잠금 유무, 수 ms)는 BCrypt 비교(수백 ms)보다 두 자릿수 작고 사내망 IP 허용 목록 안에서만 관측 가능하므로 수용한다(LIVE Gate 침투 시험에서 재평가).
   - (rev 0.3 m-D) `LoginCredentialChangeRaceSpec`: (a) BCrypt 비교와 (b) 잠금 TX 사이에 다른 연결이 비밀번호 재설정(`security_version`+1)을 커밋 → (b)는 결과를 적용하지 않고 일반 실패, 계수 불변, 세션 미발급.
2. `POST /api/auth/totp {code}` — `PASSWORD_VERIFIED` 세션에서만. TX 1회: 계정 `FOR UPDATE` → 상태·`security_version`이 비밀번호 단계에서 읽은 값과 같은지 재확인(다르면 세션 폐기·실패) → TOTP 검증(±창, `last_totp_step` 초과만) → 성공 시 `last_totp_step` 갱신·계수 0·**`session_generation`+1(새 로그인 우선, D-ADMIN1-001)**·세션 ID 재발급·`SecurityContext` 저장·감사 / 실패 시 계수+1(합산 — D-ADMIN1-005), 한도면 `LOCKED`.
3. `POST /api/auth/logout` — 세션 무효화·감사. 이전 세션의 다음 요청은 401 + `SESSION_REPLACED`(다른 곳 로그인) 또는 `SESSION_EXPIRED`로 구분(사용자 안내 — D-ADMIN1-001).

#### 4.3.3 비밀번호
- `PasswordHasher` → BCrypt(`{bcrypt}` 접두 Delegating), cost Demo, UTF-8 72바이트 상한·최소 길이 Demo(D-SV). 본인 비밀번호 변경 기능 없음(D-05).

#### 4.3.4 TOTP
- RFC 6238 SHA-1·6자리·30초, 창 ±1(Demo). `last_totp_step` 이하 거부 — 로그인·재인증 공통, 계정 행 잠금 아래.
- 시크릿 160비트, `TotpSecretCipher` → AES-256-GCM(nonce 96비트, AAD = actor ID + 키 버전), 키는 설정(시험 키만). **LIVE 배치 모드에서 알려진 Demo 키·키 없음이면 기동 실패**(n-4, §4.8.2).

#### 4.3.5 잠금
- 계수기는 비밀번호·로그인 TOTP·재인증 TOTP 실패 합산, 전체 로그인 성공 시 0, 자동 해제 없음, IP 허용 목록 밖은 세지 않음(D-ADMIN1-005). 한도 Demo.
- 재인증 TOTP 실패의 계수는 승인 TX와 **별도 TX**로 커밋한다(§4.7.4 — m-3).

#### 4.3.6 등록 코드(m-5)

| 항목 | 규칙 |
|---|---|
| 엔트로피·저장 | 160비트 `SecureRandom`(≥128비트), base32 표시, DB에는 SHA-256만 PK로. 원문은 발급 응답 1회(§4.7.7·§4.9) 외 어디에도 없음 |
| 대상당 활성 1개 | 새 코드 발급 TX가 같은 대상의 미사용 코드를 `revoked_at`으로 폐기 |
| 코드 행 열(rev 0.3) | 대상 actor ID, 목적, 발급 근거(승인 ID·복구 회차 번호·ENROLL 발급 ID), 복구 코드는 회차 번호와 **복구 시점 SECURITY_ADMIN·APPROVER 보유 집합**(§4.9.2), 만료, `used_at`, `revoked_at` |
| 목적별 설정 대상(D-ADMIN1-011, rev 0.3 m-A) | `ENROLL`·`RECOVERY` = 비밀번호 + TOTP 둘 다, `PASSWORD_RESET` = 비밀번호만(기존 TOTP 유지), `TOTP_REENROLL` = TOTP만(기존 비밀번호 유지). 어느 목적도 코드 외의 기존 요소를 요구하지 않는다 |
| 사용 흐름 | `POST /api/enroll/start {code}` → 코드 유효(미사용·미폐기·만료 전 — **서버 시계로 TX 안 판정**) 확인 후 서버 세션에 **등록 진행 상태**(진행 ID 무작위 128비트·코드 hash·대상·목적·발급 근거·단계 만료 Demo) 저장, 이 단계에서는 소비하지 않음 → 목적이 요구하는 요소만 받는다: 비밀번호 설정(BCrypt 해시 계산), TOTP 시크릿 생성(`otpauth` URI 1회, no-store)·확인 코드 검증 |
| 진행 중 대기 값(m-A, rev 0.4 n-1) | 새 비밀번호 해시·새 TOTP 시크릿은 **`TotpSecretCipher`로 암호화해 세션 속성에만** 둔다. AAD = 길이 접두 인코딩(`<UTF-8 바이트 수>:<값>` 연결)한 (문맥 태그 `paycore.admin.enroll-pending.v1`, 키 버전, 등록 진행 ID, 대상 actor ID, 목적, **용도 태그** `password-hash` 또는 `totp-secret`) — 용도 태그로 두 값을 서로 바꿔 끼울 수 없고, 저장 TOTP 암호문(AAD = actor ID + 키 버전, 문맥 다름)과도 섞이지 않는다(용도별 HKDF 하위 키는 S3에서 필요 시 추가 — 태그로 분리가 이미 보장됨). 계정 행에는 **완료 TX에서만** 쓴다. 완료 TX는 **자기 세션의 진행 ID 값만** 복호해 쓴다(다른 세션의 대기 값을 읽을 경로 없음). 세션 만료·폐기 시 대기 값도 사라진다 |
| 완료 TX(H-B, 잠금 순서 §4.13, rev 0.4 m-3) | **0단계 비잠금 선판정**: 코드 행·대상 계정을 잠금 없이 읽어 "복구 코드인가 / 완료 시 SECURITY_ADMIN·APPROVER가 활성화되는 계정인가(현재 역할 집합 또는 복구 기록 집합에 SA·APPROVER)"를 판단 → 그렇고 열린 회차가 있으면 **회차 행 `FOR UPDATE`** → 대상 계정 행 `FOR UPDATE` → **잠금 아래 선판정 재확인**(복구 여부·SA·APPROVER 활성화 여부·열린 회차 존재가 0단계와 다르면 전체 rollback 후 409 `RETRY_LATER` — 회차 잠금을 건너뛴 채 SA·APPROVER가 활성화되는 경로 차단) → **상태 = `PENDING_ENROLLMENT`이고 계정의 대기 사유·발급 근거가 코드 행의 목적·발급 근거와 같을 때만** 진행(LOCKED·SUSPENDED·ACTIVE 등 다른 상태, 사유 불일치면 전체 rollback, 실패 응답 `ENROLLMENT_NOT_APPLICABLE`) → 코드 행 원자 소비 `UPDATE admin_enrollment_code SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?` 영향 1(0이면 전체 rollback) → 목적별 요소 반영·`ACTIVE`·대기 사유 비움·`security_version`+1·세대+1 → (복구면 §4.9.2 역할 처리·회차 종료 판정) → 감사 → 커밋 |
| 동시 사용 | 같은 코드로 두 브라우저가 완료해도 한 쪽만 영향 1, **패자 세션의 시크릿·해시로 활성화된 계정 0건**(계정 행의 TOTP 암호문·해시가 승자 세션 값과 일치) — `EnrollmentCodeRaceSpec`(실제 MySQL 두 연결) |
| 정지·완료 경합(H-B) | `SuspendRevokesEnrollmentSpec`: 정지 TX가 미사용 코드를 폐기하고 대기 사유를 비움 → 그 뒤 완료 시도 rollback. 정지 TX와 완료 TX를 두 연결로 동시 실행 → 두 순서 모두 "정지가 먼저면 완료 거부(계정 SUSPENDED·코드 폐기), 완료가 먼저면 정지가 ACTIVE 계정을 정지(코드 사용됨)"만 나온다. LOCKED 상태 대상의 완료 시도도 거부(같은 규칙) |
| 재발급 주체(rev 0.4 H-1) | SECURITY_ADMIN 1인 재발급은 **대상 계정에 활성·정지 역할이 하나도 없을 때만**(계정 행 `FOR UPDATE` 아래 현재 역할 집합 + 정지 역할 이벤트로 판정 — D-ADMIN1-003 "역할 없는 계정" 문언). 목적이 `ENROLL`이어도 역할이 있는 대기 계정(부트스트랩 계정, 복구 작업 B 계정, 역할 부여 뒤 등록 전 계정)은 1인 재발급 거부. 역할이 있는(활성 또는 정지) 계정의 코드 재발급은 **새 2인 승인**(`PASSWORD_RESET`·`TOTP_REENROLL` 종류 — 만료·분실·승인 응답 commit 불명확 포함) 또는 조건이 맞을 때 **복구 명령**(§4.9.2)만. 시험 `EnrollReissueRoleBearingForbiddenSpec`(부트스트랩·작업 B·정지 역할 보유 대기 계정 각각 1인 재발급 거부·상태 불변·감사) |
| 재발급 전이(rev 0.4 m-2) | (a) 재설정 코드 재발급: `PENDING_ENROLLMENT` → `PENDING_ENROLLMENT`, 새 2인 승인 실행 TX가 기존 미사용 코드 폐기·새 코드 발급·대기 사유·**발급 근거를 새 approvalId로 교체**. (b) 1인 재발급(역할 없음): `PENDING_ENROLLMENT` → `PENDING_ENROLLMENT`, 기존 코드 폐기·새 코드 발급·`pending_basis`를 **새 발급 ID로 갱신**. 두 경우 모두 `security_version`+1(§4.7.5 "등록 코드 발급·폐기"). `EnrollmentCodeSpec`: 재발급 뒤 새 코드 완료 성공·옛 코드 거부(폐기 + 근거 불일치) |
| 확정 자격 없는 대상의 2인 재발급(rev 0.5 m-1) | 2인 재발급(`PASSWORD_RESET`·`TOTP_REENROLL` 승인) 실행 TX는 대상 계정 행 잠금 아래 **확정 자격(비밀번호 해시·TOTP 암호문)을 확인**한다. **둘 중 하나라도 없으면**(등록 전 역할 보유 계정 — 부트스트랩·작업 B·역할 부여 뒤 미등록) 승인 종류와 관계없이 대기 사유를 **`ENROLL`(근거 = 새 approvalId)**로 두어 완료 시 **두 요소를 모두** 설정하게 한다(없는 요소를 남기지 않는 fail-closed 선택). 둘 다 있으면 종류대로 한 요소만. 승인 종류는 추가하지 않으며 역할 정지(D-ADMIN1-009)·재활성 2인 승인 규칙은 그대로. `EnrollmentPurposeFlowSpec` 행: 등록 전 계정 2인 재발급 → 두 요소 설정·`ACTIVE`·역할 정지 유지, 한 요소만 있는 계정 → `ENROLL`로 두 요소 모두 설정 |
| 실패 입력 | 미존재·만료·사용됨은 같은 응답, 지표만(128비트 이상이라 추측 불가 — 계정 계수기에 넣지 않음). 분류(rev 0.3 n-C): `ENROLLMENT_CODE_INVALID`(미존재·만료·사용·폐기 — 원인 비구분), `ENROLLMENT_NOT_APPLICABLE`(완료 TX의 상태·사유 불일치), `ENROLLMENT_STEP_INVALID`(목적에 없는 요소 제출·TOTP 확인 실패·진행 상태 만료) |
| 목적별 흐름 시험 | `EnrollmentPurposeFlowSpec`: PASSWORD_RESET 완료 뒤 TOTP 암호문·키 버전 불변·비밀번호만 변경, TOTP_REENROLL 완료 뒤 해시 불변·TOTP만 변경, ENROLL·RECOVERY는 둘 다 설정, 목적에 없는 요소 제출은 거부 |

### 4.4 세션·쿠키·CSRF

#### 4.4.1 Spring Session JDBC
- 표 `admin_session`(Flyway, `initialize-schema=never`), idle = `maxInactiveInterval`(Demo), 절대 = 세션 `authenticatedAt` + 한도(Demo, 주입 시계), 정리 cron.
- 세션 속성은 actor ID·인증 단계·`session_generation`·읽은 `security_version`·`authenticatedAt`·CSRF 토큰·등록 진행 상태만. 역할은 세션에 두지 않는다.
- `SaveMode`(기본 `ON_SET_ATTRIBUTE`)와 같은 세션의 병렬 요청에서 속성 덮어쓰기(예: CSRF 토큰 재발급 손실)를 S0에서 확인한다(n-8). 권위는 계정 행(세대)이므로 속성 경합이 이중 유효 세션을 만들지는 않는다.
- `SecurityContext` 저장 형식(직렬화)은 S0 확인 후 필요하면 문자열 값 기반 `SecurityContextRepository`(D-08).
- **S0 결과(§17.5·§17.6)**: (1) Spring Session JDBC 4.1.1은 속성 표 이름을 `%TABLE_NAME%_ATTRIBUTES`로 고정해 `admin_session_ATTRIBUTES`를 찾는다(실측 500, `lower_case_table_names=0`) — 권한 procedure 이름 규칙(소문자)과 맞추려면 `SessionRepositoryCustomizer`로 질의 5개를 `admin_session_attributes`로 교체한다(실측 동작). (2) `ON_SET_ATTRIBUTE`에서 병렬 요청은 서로 다른 속성을 덮어쓰지 않고, **같은 속성은 늦게 끝난 요청 값이 이긴다**(CSRF 토큰은 토큰 발급·회전 경로에서만 쓴다). `ALWAYS`는 DB에서 읽은 세션에 효과가 없다(소스·실측 일치). (3) 기본 저장은 Java 직렬화(`ACED0005`), 손상된 `SPRING_SECURITY_CONTEXT`는 모든 요청 500·세션 행 잔존·`/error` 재처리도 실패 → 문자열 값만 저장하는 방식 권장(§17.5).

#### 4.4.2 새 로그인 우선·즉시 무효(D-ADMIN1-001)
- 세션 검사 filter가 요청마다 `AdminSessionValidityQuery`(PK 단건)로 상태·세대·현재 역할을 확인, 세대가 다르면 401 `SESSION_REPLACED`. 동시 로그인 두 개가 끝나도 세대는 행 잠금으로 직렬화되어 마지막 하나만 유효(`SingleSessionRaceSpec`). 잠금·정지·역할 회수·재설정도 세대 +1.

#### 4.4.3 쿠키·CSRF
- 세션 쿠키 `__Host-PCADMIN`(Secure·HttpOnly·SameSite=Strict·Path=/). CSRF는 **세션 저장 동기화 토큰**을 `__Host-PCADMIN-XSRF`(HttpOnly 아님) 쿠키로 전달, 상태 변경 요청은 `X-XSRF-TOKEN` 헤더로 회수해 **세션 토큰과 비교**(double-submit 위조 거부 시험). 세션 ID 재발급마다 토큰 재발급. Security 7.1.1 SPA 구성 사용 가능성은 S0(D-10). **S0 결과(§17.5): `csrf.spa()`는 `CookieCsrfTokenRepository.withHttpOnlyFalse()`(쿠키 저장 double-submit)라 D-10에 쓸 수 없다** — `HttpSessionCsrfTokenRepository` 계열(세션 저장) + 쿠키 전달 filter + 헤더 해석 handler를 직접 구성(`SpaCsrfTokenRequestHandler`는 `CsrfConfigurer`의 private 클래스라 재사용 불가).
- **익명 세션 증가(n-1)**: `GET /api/auth/csrf`는 토큰 저장을 위해 세션 행을 만든다 → 익명·`PASSWORD_VERIFIED`·등록 진행 세션의 `maxInactiveInterval`을 짧게(Demo 10분), 정적 자원·`/assets`는 세션을 만들지 않음, 이미 세션이 있으면 재사용, 지표 `paycore.admin.session.rows`(gauge)·`paycore.admin.session.anonymous_created`(counter), IP 허용 목록이 외부 생성을 막는다. 시험 `AnonymousSessionGrowthSpec`(정적 요청 N회 → 세션 행 0, csrf 요청 → 1, 만료 정리 뒤 0).

### 4.5 역할·권한

#### 4.5.1 권한 표(D-ADMIN-002, D-ADMIN1-004·008)

| 권한 | VIEWER | OPERATOR | APPROVER | SECURITY_ADMIN |
|---|---|---|---|---|
| 마스킹된 조회 | O | O | O | O |
| 운영 명령 요청(후속 작업의 업무 명령) | — | O | — | — |
| 2인 승인·거절 | — | — | O | — |
| 계정 생성·`ENROLL` 코드 재발급·정지·역할 회수(1인 즉시) | — | — | — | O |
| ADMIN 소유 2인 승인 명령 **요청** | — | — | — | O |
| 감사 조회 | 자기 기록 | 자기 기록 | 전체 | 전체 |
| 마스킹 해제 | 별도 권한 `UNMASK`(역할과 독립, 부여는 `ROLE_GRANT`와 같은 2인 승인) + 사유 + 직전 TOTP — 틀만(AC10) → 첫 적용 KYC(D-FLOW-002 ①), 사유 카드번호 패턴 거부(D-FLOW-005 ⑧), DEMO 간편 로그인 세션은 직전 TOTP 생략(D-ADMIN-AUTH-DEMO-001 ③) | | | |

- 겸직 허용. 한 승인 요청에서 요청자·승인자·대상 계정은 모두 달라야 한다(D-ADMIN1-004). 권한 판정은 domain `AdminPermission` 하나(서버) — React는 표시만.
- 역할 회수는 SECURITY_ADMIN 1인 즉시: TX에서 대상 계정 `FOR UPDATE` → 현재 역할 집합 갱신·`REVOKE` 이벤트·`security_version`+1·세대+1·감사. 대상 = 요청자 본인이면 거부(본인 권한 변경 불가).

#### 4.5.2 불변 actor ID
- `adm_` + ULID, 갱신 불가(`updatable=false` + 열 권한 1143), 계정 DELETE 없음. login_name 변경 기능 없음(D-06). 감사·승인 행은 actor ID + 기록 시점 표시명 스냅샷.

#### 4.5.3 마스킹 해제 틀(AC10)
- `UnmaskUseCase`(권한·사유 필수·길이 상한·직전 TOTP·감사 1행, 대상 데이터 공급 Port는 비어 있음) + 시험(`UnmaskFrameworkSpec` — 권한 없음 403·사유 없음 400·TOTP 필수·감사). 첫 실제 화면은 후속. → 답(D-FLOW-002 ①, 2026-11-25 — Q-PII-3 일부): 마스킹 해제의 **첫 실제 적용 데이터 = KYC(대표자 생년월일·실소유자 정보)**, 구현은 MERCHANT-FLOW-001(IN_PROGRESS — main 코드는 아직 틀만, `UnmaskService.kt:18,63` `NoDataSource`). `UNMASK`는 Demo seed에 미리 주지 않고 SECURITY_ADMIN이 기존 2인 역할 부여로 부여, 사유에 카드번호 패턴 거부 적용(D-FLOW-005 ⑧ — 코드 미적용, `UnmaskService.kt:32-33`).

### 4.6 관리자 감사

- 기록: 로그인 각 단계, 잠금, 로그아웃, 세션 교체·강제 종료, 인증 경로 IP 거부, 계정 생성·정지·코드 발급/사용/폐기, 역할 부여·회수·정지·재활성, 승인 요청·승인·거절·철회·만료·무효·실행 결과, 재인증 성공·실패, 마스킹 해제(사유), 감사 조회, 부트스트랩·복구(`SYSTEM_BOOTSTRAP`·`SYSTEM_RECOVERY`).
- 행: event_id, 시각, actor ID + 표시명 스냅샷, 실행자 구분(ADMIN 소유 명령은 **실행자 = 승인자**, D-ADMIN1-006), 행위·대상·결과 code·승인 ID·상관 ID·원격 IP. 미인증 시도의 login_name은 **비밀 키 HMAC-SHA256**(n-2 — 키는 설정, LIVE에서 Demo 키면 기동 실패). 비밀번호·코드·시크릿·세션 ID·CSRF 토큰 없음.
- **감사 저장 실패 = 행위 실패**: 상태 변경과 같은 TX. 상태 변경 없는 행위(로그인 실패 응답)도 감사 TX 실패면 성공 응답 없음(`AuditFailureClosesActionSpec`).
- **감사 조회(m-11, D-ADMIN1-008)**: `AdminAuditReader.search(scope, filter, page)` — scope는 `ALL` 또는 `SELF(actorId)`이고 SELF는 SQL WHERE `actor_id = ?`(서버가 세션 actor로 채움, 요청 값 무시). 조회 서비스는 결과를 돌려주기 **전에** 별도 작은 명령 TX로 `AUDIT_VIEWED` 감사 1행(필터 요약 — 자유 문구 없음)을 커밋하고, 실패하면 결과를 반환하지 않는다(503). 시험 `AuditQueryPermissionSpec`(VIEWER가 타인 actor 필터를 보내도 자기 행만, 감사 쓰기 실패 → 결과 없음).

### 4.7 2인 승인 틀·ADMIN 소유 명령

#### 4.7.1 종류와 실행 모델

| 종류 | 요청자 | 승인자 | 대상 버전 | 실행 모델 |
|---|---|---|---|---|
| `ROLE_GRANT`(SECURITY_ADMIN·`UNMASK` 포함) | SECURITY_ADMIN | APPROVER | 대상 `security_version` | **ADMIN 소유 단일 TX** |
| `ACCOUNT_UNLOCK` | SECURITY_ADMIN | APPROVER | 같음 | 같음 |
| `ACCOUNT_REACTIVATE`(정지 해제) | SECURITY_ADMIN | APPROVER | 같음 | 같음 |
| `TOTP_REENROLL` | SECURITY_ADMIN | APPROVER | 같음 | 같음(§4.7.7) |
| `PASSWORD_RESET` | SECURITY_ADMIN | APPROVER | 같음 | 같음(§4.7.7) |
| `ROLE_REACTIVATE` | SECURITY_ADMIN(≠ 재설정 요청자) | APPROVER(≠ 재설정 요청자) | 같음 + 원인 재설정 승인 ID | 같음 |
| (후속) `OPERATOR_CORRECTION_CANCEL` 등 소유 서비스 명령 | — | — | — | 2-TX(승인 TX → TX 밖 호출 → 결과 TX) — ADMIN-PAYMENT-OPS-001, §4.12 참고 |

1인 즉시(안전 방향 — 승인 요청 없음): 계정 생성(역할 없음)·`ENROLL` 코드 재발급·정지·역할 회수(D-ADMIN1-003·004).

#### 4.7.2 domain 판정표(`ApprovalDecisionTable` — 상태 × 명령)

| 상태 \ 명령 | 승인 | 거절 | 철회(요청자) | 만료 판정 | 대상 무효 |
|---|---|---|---|---|---|
| `PENDING` | ADMIN 소유 종류: 같은 TX에서 `EXECUTED` 또는 `INVALIDATED`·`OWNER_REJECTED`(재검사 실패) / 소유 서비스 종류: `APPROVED`+`EXECUTING` | `REJECTED` | `WITHDRAWN` | `EXPIRED` | `INVALIDATED` |
| 그 밖(전부 종료 상태) | `ALREADY_DECIDED`(변경 없음) | 같음 | 같음 | 변경 없음 | 변경 없음 |

- **ADMIN 소유 종류는 `APPROVED`·`EXECUTING`·`EXECUTION_UNKNOWN`에 도달할 수 없다** — 판정표 시험이 6종 × 모든 경로에서 이 세 상태가 나오지 않음을 고정한다(H-1). `EXECUTION_UNKNOWN`은 소유 서비스 종류 전용.
- 만료(n-5): 권위 판정은 **승인 TX 안에서 TX가 읽은 서버 시각**으로 한다(`expires_at <= now`면 `EXPIRED`로 바꾸고 커밋, 승인 실패 응답). 화면 표시용 만료 정리 job은 같은 판정 함수를 쓰고 권위가 아니다.
- 중복 PENDING(n-6): 같은 (종류, 대상) PENDING은 생성 열 `pending_key` UNIQUE로 생성 단계에서 거부(`DUPLICATE_PENDING`). 다른 종류의 PENDING은 허용되며, 하나가 실행돼 대상 `security_version`이 오르면 나머지는 승인 시 `INVALIDATED`(`DuplicatePendingSpec`).
  (rev 0.3 m-D) `DuplicatePendingRaceSpec`: 같은 (종류, 대상) 요청 생성을 실제 MySQL 두 연결에서 **동시에**(시작 장벽으로 두 INSERT가 겹치게) 실행 → 정확히 한 건 1062(`DUPLICATE_PENDING`), PENDING 행 1개, 감사는 성공 1·거부 1.

#### 4.7.3 ADMIN 소유 명령의 단일 TX(H-1, m-1, m-2)

`approval` application `ApproveAdminOwnedRequestService`(orchestrator)가 TX 하나(`LocalTransactions`, READ COMMITTED)에서:

0. (rev 0.4 m-3) 승인 행의 **불변 열(종류·대상)만 비잠금 읽기** — 종류가 SECURITY_ADMIN·APPROVER를 활성화할 수 있는 종류(`ROLE_GRANT`(SA·APPROVER 역할)·`ROLE_REACTIVATE`·`ACCOUNT_UNLOCK`·`ACCOUNT_REACTIVATE`)이고 열린 복구 회차가 있으면 **회차 행 `FOR UPDATE`**(§4.13, §4.9.2 활성 수 판정 직렬화). 종류·대상은 생성 불변 열(열 권한으로 UPDATE 불가)이라 1단계 잠금 뒤에도 같다(1단계에서 같음을 단언).
   (rev 0.5 n-1) 1단계에서 불변 열(종류·대상·`ROLE_GRANT`의 부여 역할)이 0단계 값과 다르면 정상 경로로는 생길 수 없는 **무결성 위반**으로 본다: 전체 rollback, HTTP 500 `INTERNAL_INTEGRITY`(재시도 유도 없음 — 409 아님), **별도 TX로 감사 1행**(approvalId·불일치 열 이름, 값 없음), 지표 `paycore.admin.integrity_violation{check}` + 경보. 시험 `ApprovalImmutableColumnIntegritySpec`(root 연결로 0단계 뒤 종류를 바꾸는 강제 불일치 주입 → 500·상태/계정/TOTP 단계 불변·감사 1행·지표 증가).
1. 승인 행 `FOR UPDATE` 재독 — 호출자가 보낸 값은 approvalId·TOTP 코드뿐, 종류·대상·요청자·대상 버전은 **잠근 행에서** 읽는다.
2. 판정표: 상태 `PENDING`, 만료 전(TX 시각), 승인자 ≠ 요청자, 승인자 ≠ 대상(D-ADMIN1-004).
3. `account` provided `AdminOwnedCommandExecutor.lockParticipants({요청자, 승인자, 대상})` — **actor ID 오름차순**으로 계정 행 `FOR UPDATE`. (0단계에서 회차가 없다고 본 뒤 그 사이 회차가 열린 경우는 §4.9.2 직렬화 한계 행 참고.)
4. **승인자 쪽 재검사**(m-1): 승인자 `ACTIVE`·현재 역할에 APPROVER·세션 세대가 이 요청을 보낸 세션 세대와 같음. 실패면 **상태 변경 없이 rollback, 403**(rev 0.3 n-A — 승인 권한이 없는 자는 승인 행을 바꿀 수 없다. 세션 세대 불일치는 401).
5. 승인자 TOTP 검증·**단계 소비**(`last_totp_step` 갱신 — 같은 TX). 실패면 TX 전체 rollback 후 §4.7.4의 별도 TX.
6. **요청자 쪽 재검사**(rev 0.3 n-A — 승인자 인증 뒤로 옮김): 요청자 `ACTIVE`·SECURITY_ADMIN 보유(요청 후 회수·정지면 승인 행 `INVALIDATED` + 감사 커밋, 실행 없음 — fail-closed). 근거: 미인증·무권한 호출이 남의 요청을 무효화할 수 없게, 승인 행을 바꾸는 판정은 TOTP로 인증된 APPROVER만 한다.
   이어서 대상 `security_version` = 승인 행의 대상 버전인지 재비교(다르면 `INVALIDATED` + 감사 커밋, 실행 없음). INVALIDATED 커밋에도 승인자 TOTP 단계 소비는 함께 커밋된다(같은 코드 재사용 방지).
7. 소유 재검사(D-ADMIN-007): `AdminOwnedCommandExecutor.execute(evidence)` — evidence(approvalId·종류·요청자·승인자·대상·대상 버전)는 잠근 행 값이며, account가 요청자≠승인자≠대상·버전 일치를 **다시** 확인한 뒤 계정 변경(상태·역할 이벤트·현재 역할·`security_version`+1·세대+1·코드 발급)을 한다. 실패면 `OWNER_REJECTED`(code) + 감사.
8. 승인 행 `EXECUTED`(결정자·결과), 감사 행(실행자 = 승인자) — 모두 같은 TX 커밋.

- **1213(교착)·1205(대기 시간 초과)**: TX 전체 rollback 후 **재시도 가능 오류**(HTTP 409 `RETRY_LATER`)로 응답한다. 성공이나 결과 불명확으로 매핑하지 않는다(시험 `ApprovalLockOrderSpec` — 잠금 순서 정상 경로 교착 0회, 강제 교착 주입 시 상태·감사 무변경 + 409).
- **commit 결과 불명확**(commit 단계 예외·응답 유실): 클라이언트·서버는 승인 행을 다시 읽어 수렴한다 — `EXECUTED`면 완료(코드 표시 종류는 코드를 다시 보여 주지 않으므로 새 승인 필요 — §4.3.6), `PENDING`이면 재승인 가능(TOTP 단계는 rollback으로 소비되지 않았거나, 소비됐다면 커밋된 것이므로 상태가 `EXECUTED`).
- (rev 0.3 m-D) `ApprovalVersusCloseRaceSpec`: 같은 PENDING 요청에 승인 vs 철회, 승인 vs 거절, 승인 vs 만료 정리 job을 실제 MySQL 두 연결로 동시 실행 → 승인 행 잠금으로 직렬화되어 정확히 하나의 종료 상태, 나머지는 `ALREADY_DECIDED`, 계정 변경은 승인이 이긴 경우에만 1회.
- `RevokeDuringApprovalRaceSpec`(m-1): 승인 TX와 승인자 역할 회수 TX를 실제 MySQL 두 연결로 경합 → 두 순서 모두에서 "회수가 먼저 커밋되면 승인 거부, 승인이 먼저 커밋되면 회수 뒤 승인자 세션 무효"만 나온다(역할 없는 실행 0건).

#### 4.7.4 직전 TOTP 재입력(AC6, m-3)
- 승인·거절·마스킹 해제 요청 본문에 `totpCode`(신선도 창 없음 — D-11). 성공 단계 소비는 **승인 TX 안**(§4.7.3-5).
- → 추가(D-ADMIN-AUTH-DEMO-001 ③, 2026-11-24 — 대체가 아닌 좁은 예외): `deployment-mode=DEMO` + 명시 설정이 켜져 있고 **데모 간편 로그인으로 만든 세션**일 때만 승인·반려·마스킹 해제 직전 TOTP를 생략하고 감사에 생략 사실(`STEP_UP_SKIPPED`·`DEMO_SKIPPED`)을 남긴다. 2인 승인(요청자≠승인자)은 그대로, LIVE에서 설정이 켜져 있으면 기동 거부. 운영 규칙은 위 문장 그대로. 명세 ADMIN-DEMO-LOGIN-001(DONE), PII-MASKING-POLICY §5.3.
- 실패: 승인 TX rollback → **별도 TX**에서 승인자 계정 `FOR UPDATE` → 계수+1(D-ADMIN1-005 합산), 한도면 `LOCKED`·세대+1(현재 세션도 끊김)·`security_version`+1 → 감사 → 커밋. 승인 행은 바뀌지 않는다.
- `StepUpTotpReuseRaceSpec`: 같은 승인자가 같은 코드로 서로 다른 두 요청을 동시에 승인 → 계정 행 잠금으로 직렬화되어 하나만 성공, 다른 하나는 단계 재사용 거부(+ 계수+1). `StepUpFailureCountsSpec`: 재인증 실패가 로그인 실패와 같은 계수기에 쌓여 한도에서 잠김·세션 종료.

#### 4.7.5 `security_version` 증가 사건 전수(m-7, D-13 — Coordinator 판정: 무효 범위를 넓히는 쪽만 구현 결정)

| 증가 | 사건 |
|---|---|
| O | 상태 전이 전부(→LOCKED·SUSPENDED·PENDING_ENROLLMENT·ACTIVE) |
| O | 역할 이벤트 전부(GRANT·REVOKE·SUSPEND·REACTIVATE, `UNMASK` 포함) |
| O | 비밀번호 해시 변경, TOTP 시크릿·키 버전 변경(등록·재등록·재암호화) |
| O | 등록 코드 발급·폐기(대상의 자격 경로 변경) — rev 0.4 m-2: `PENDING_ENROLLMENT` → `PENDING_ENROLLMENT` 재발급(2인 재설정 재발급의 근거 교체, 1인 재발급의 `pending_basis` 갱신)도 포함 |
| O | 표시명 변경(기능은 없지만 생기면 포함 — 승인 화면에 보인 대상과 다름) |
| — | 로그인 실패 계수 증가(한도 미도달), 계수 0 초기화 — 로그인 활동이 대기 중 승인을 무효로 만들지 않게(한도 도달은 상태 전이로 증가) |
| — | `last_totp_step` 갱신 — 같은 이유(인증 활동) |
| — | `session_generation` 증가 단독(새 로그인) — 같은 이유. 상태·역할 변경에 따른 세대 증가는 그 사건 자체가 증가 |
| — | JPA `version` — 기술 값 |

제외 집합은 모두 "인증 활동"이며 대상의 권한·자격·상태를 바꾸지 않는다. 시험 `SecurityVersionEventsSpec`이 위 표의 모든 행을 사건별로 고정한다(증가 행: +1, 제외 행: 불변). 이 목록에 없는 새 열이 생기면 시험이 Entity 열 목록과 대조해 실패한다(분류 누락 감지). 불완전한 사건은 발견되지 않았다.

#### 4.7.6 ADMIN 소유 명령 6종의 실행 내용
- `ROLE_GRANT`: 현재 역할 추가·GRANT 이벤트. `ACCOUNT_UNLOCK`: LOCKED → ACTIVE, 계수 0. `ACCOUNT_REACTIVATE`: SUSPENDED → 정지 직전 상태. 모두 `security_version`+1·세대+1.
- `TOTP_REENROLL`·`PASSWORD_RESET`·`ROLE_REACTIVATE`: §4.7.7.

#### 4.7.7 재설정·재등록과 역할 재활성(H-3, D-ADMIN1-009)

| 단계 | 내용 | 시험 |
|---|---|---|
| 실행(`PASSWORD_RESET`·`TOTP_REENROLL` 승인 TX) | 대상 → `PENDING_ENROLLMENT`, 활성 역할 전부 `SUSPEND` 이벤트(원인 = 이 approvalId, 이력 보존 — 삭제 없음), 현재 활성 역할 집합 비움, 해당 자격 무효화, 기존 미사용 코드 폐기 후 등록 코드(목적 = 종류, 발급 근거 = 이 approvalId) 발급, 계정 대기 사유·근거 기록(rev 0.3 H-B), 세대+1·`security_version`+1, 감사(실행자 = 승인자) | `CredentialResetSuspendsRolesSpec`(실행 뒤 역할 0·SUSPEND 이벤트 = 직전 역할 집합·이력 행 수 불변 + 증가) |
| 코드 표시 | 원문은 **승인 HTTP 응답에서 실행자(승인자)에게 1회만**(`Cache-Control: no-store`). 요청자 화면·승인 목록·승인 상세 재조회·감사·로그·지표에 없음. 별도 채널로 본인에게 전달(운영 절차) | `ResetCodeDisclosureSpec`(요청자 조회·승인 재조회·감사 검색·캡처 로그에 원문 0회, 승인 응답에만 1회) |
| 본인 재등록 | 등록 코드 흐름(§4.3.6) — 완료 시 `ACTIVE`, **역할은 정지 상태 유지** | 재등록 직후 권한 API 403 |
| 본인 재등록(D-ADMIN1-011) | **등록 코드만으로** 목적의 요소만 다시 설정(PASSWORD_RESET = 비밀번호, TOTP_REENROLL = TOTP — §4.3.6 목적별 표) | `EnrollmentPurposeFlowSpec` |
| 재활성(`ROLE_REACTIVATE` 승인 TX) | 대상 `ACTIVE`·원인 재설정 approvalId로 정지된 역할 존재 확인 → 요청자·승인자 모두 ≠ **원인 재설정의 요청자**이고 ≠ **원인 재설정의 승인자(코드 수령자)**(rev 0.3 H-A — Coordinator fail-closed 판정), 그리고 요청자≠승인자≠대상 → 그 역할들 `REACTIVATE` 이벤트·현재 역할 복원 | `RoleReactivateSpec`·`RoleRuleSpec` 행: 재설정 요청자가 재활성 요청 → 거부, 재설정 요청자가 재활성 승인 → 거부, **재설정 승인자가 재활성 요청 → 거부, 재설정 승인자가 재활성 승인 → 거부**, 재등록 전이면 거부, 정지된 역할만 복원(그 사이 회수된 역할은 복원 안 함) |

- 인원 영향(H-A): 한 계정의 재설정 → 재활성 한 주기에 서로 다른 관리자 계정이 최대 4개(재설정 요청자·승인자, 재활성 요청자·승인자) 필요하다 — Q-ADMIN-APPROVER-STAFF(LIVE Gate 운영 인원 확인)의 참고 수치.

#### 4.7.8 거절·철회 TX(rev 0.3 n-B)
- **거절**(APPROVER, TOTP 재입력): 승인과 같은 잠금 순서(§4.13 — 승인 행 → 승인자·요청자 계정 행 오름차순) → 승인자 쪽 재검사(실패 403, 상태 불변) → TOTP 검증·**성공 단계 소비는 같은 TX** → 판정표(PENDING만) → `REJECTED`·감사 → 커밋. TOTP 실패는 rollback 후 §4.7.4의 별도 실패 TX.
- **철회**(요청자 본인, TOTP 없음 — 안전 방향): 승인 행 → 요청자 계정 행 잠금 → 요청자 `ACTIVE`·세대 일치 확인 → PENDING만 `WITHDRAWN`·감사.
- 1213·1205는 승인과 같이 rollback 후 409 `RETRY_LATER`, 상태 불변(`ApprovalLockOrderSpec`에 거절·철회 행 추가).

### 4.8 IP 허용 목록·배치 모드

#### 4.8.1 IP 허용 목록
- 모든 경로 앞 servlet filter, `remoteAddr`만(`server.forward-headers-strategy=none`), 설정 필수·빈 목록 기동 실패, IPv4-mapped 정규화. actuator는 별도 management 포트(D-15).

#### 4.8.2 배치 모드와 Demo 비밀(n-4)
- `paycore.admin.deployment-mode: DEMO | LIVE`(필수, 기본값 없음). LIVE에서 TOTP 암호화 키·login_name HMAC 키가 없거나 저장소의 알려진 Demo 키 목록(SHA-256 지문)과 같으면 기동 실패, 보안 수치가 Demo 값이면 기동 실패(LIVE Gate 차단 — Q-ADMIN-SEC-VALUES). 시험 `LiveModeStartupGuardSpec`.

### 4.9 1회성 비웹 명령(H-2, D-ADMIN1-002·010)

- (rev 0.3 n-H) **신뢰 경계·위협 모델**: 이 명령을 실행할 수 있는 자는 ADMIN 호스트 접근 권한과 runtime DB 자격을 가진 운영자다 — 그는 이미 ADMIN DB를 runtime 권한으로 직접 바꿀 수 있으므로 명령은 그 권한을 **넓히지 않고**(runtime 계정 그대로) 실행 조건(빈 표·0명)·1회성·감사를 DB로 강제해 오용을 드러내는 장치다. 호스트·DB 자격 보호는 운영 통제(LIVE Gate)다.

공통(`account.adapter.batch.OneShotCommandRunner` — provided만 호출):
- **명시 argv 모드만**: `java -jar internal-admin.jar --paycore.admin.command=bootstrap --output=tty|file:<path>`(복구는 `recovery` + 하위 인자). 이 모드에서는 `spring.main.web-application-type=none`을 강제하고, 웹 서버 구성(server.port 지정 등)이나 스케줄러가 켜져 있으면 **기동 거부**. 웹 프로필로 기동한 프로세스는 명령 모드 인자를 거부.
- **runtime 계정**(migration 계정 아님), 표 단위 권한 그대로.
- **코드 출력**: `tty`면 `System.console()`이 있을 때만(없으면 DB 변경 전 거부), `file:`이면 새 파일을 `CREATE_NEW` + POSIX `rw-------`(0600)로 만들고 이미 있으면 DB 변경 전 거부. **S0 결과(§17.8)**: ext4에서는 umask와 무관하게 0600·기존 파일·dangling symlink 모두 `FileAlreadyExistsException`, 그러나 **drvfs(`/mnt/c`)에서는 권한 속성이 조용히 무시되어 `rwxrwxrwx`** — 생성 직후 권한을 다시 읽어 `rw-------`가 아니면 파일 삭제·DB 변경 전 거부해야 한다. JDK 25에서 TTY 판정은 `System.console() != null && isTerminal()`(표준 스트림 하나라도 리다이렉트되면 null 실측). logger·표준 출력 일반 쓰기 금지(컨테이너 stdout 수집 경로와 §6 비노출 충돌 해소). 출력 쓰기 실패 시: 코드는 DB 커밋 뒤 출력되므로, 출력 실패면 같은 명령의 재발급 경로(복구 회차 만료·재실행 또는 부트스트랩 불가 → 복구 명령)로 안내하고 비영 exit.
- 종료 코드: 성공 0, 조건 불충족·경합 패배·출력 거부 비영(서로 다른 값), 감사는 성공·패배·거부 모두 기록(패배·거부 감사는 별도 TX).

#### 4.9.1 부트스트랩
- (현행화 2026-10-30 — [ADMIN-BOOT-002](ADMIN-BOOT-002-BOOTSTRAP-SECOND-SA.md), D-ADMIN1-022) 아래 "SECURITY_ADMIN 1 + APPROVER 1"은 **SECURITY_ADMIN 2 + APPROVER 1**(인자 `--second-security-admin-login/-name` 추가·필수, 세 login·표시명 모두 다름, 코드 3개, 고정 행 열은 SA-A·APPROVER 그대로)로 바뀌었다. 1회성·출력·감사 규칙과 복구 조건(§4.9.2)은 그대로다.
- 같은 TX: `admin_account` 비어 있음 확인(비잠금 — 판정 보조일 뿐) → `admin_bootstrap(id=1)` INSERT → SECURITY_ADMIN 1 + APPROVER 1 계정(→ 대체: D-ADMIN1-022, 현행 SECURITY_ADMIN 2 + APPROVER 1·등록 코드 3개 — ADMIN-BOOT-002) (서로 다른 login_name·표시명 인자 — 같은 사람 금지는 운영 절차, 시스템은 서로 다른 계정만 강제) + 역할 GRANT 이벤트(actor `SYSTEM_BOOTSTRAP`) + 등록 코드 2개 + 감사 → 커밋 → 코드 출력.
- 동시 실행 패자는 `admin_bootstrap` PK 1062로 TX 전체 rollback → 패배 감사(별도 TX)·비영 exit. 빈 표 `COUNT … FOR UPDATE`에 의존하지 않는다(InnoDB 빈 범위 잠금은 경합 패자를 막지 못하거나 교착을 만든다).
- `BootstrapConcurrencySpec`: 실제 MySQL 두 연결에서 동시 실행 → 계정 정확히 2개(한 쌍)(→ 대체: D-ADMIN1-022, 현행 기대값은 ADMIN-BOOT-002 기준 — 이 문서에서 시험 단언을 재대조하지 않음)·`admin_bootstrap` 1행·패자 감사 1행·패자 비영 exit. `BootstrapSpec`: 계정이 있으면 거부, TTY 없고 file 미지정이면 DB 무변경 거부, 파일 권한 0600.

#### 4.9.2 오프라인 복구(D-ADMIN1-010) — 회차별 반복 가능, 동시 실행만 차단

| 항목 | 설계 |
|---|---|
| 동작 조건 | TX 안에서 활성 SECURITY_ADMIN 수 = 0 **또는** 활성 APPROVER 수 = 0(활성 = 계정 `ACTIVE` + 현재 역할 보유). 둘 다 1명 이상이면 거부 |
| 회차 행 | `admin_recovery_episode(episode_no PK, open_marker 생성 열 = CASE WHEN closed_at IS NULL THEN 1 END UNIQUE, opened_at, expires_at, closed_at)`. 한 번에 **열린 회차는 하나**만 존재 |
| 같은 TX 순서 | 만료된 열린 회차가 있으면 `closed_at` 기록(행 잠금) → 조건 판정 → 새 회차 INSERT(번호 = 최대+1) → 복구 작업 → 감사(`SYSTEM_RECOVERY`) → 커밋 → 코드 출력 |
| 동시 실행 | 두 실행이 함께 조건을 통과해도 열린 회차 UNIQUE(또는 같은 번호 PK) 1062로 하나만 커밋. 패자 감사·비영 exit(`RecoveryConcurrencySpec`) |
| 반복 | 회차는 그 회차의 코드가 모두 사용·폐기되거나 만료되면 닫힌다. **종료 판정은 완료 TX에서 회차 행 `FOR UPDATE` 뒤**(코드 행의 회차 번호 열로 남은 미사용 코드 수를 센다 — rev 0.3 m-B), 만료 회차는 다음 복구 실행 TX가 닫는다. 나중에 다시 0명이 되면 새 회차 가능(`RecoveryCommandSpec` — 1회차 완료 → 다시 0명 → 2회차 성공) |
| 작업 A(D-ADMIN1-012, rev 0.3 m-B) | 기존 계정에 복구 등록 코드 발급: 같은 TX에서 대상 계정 `FOR UPDATE` → 대상이 **복구 시점에 보유(활성 또는 정지)한 SECURITY_ADMIN·APPROVER 집합을 코드 행에 기록** → 대상의 **모든** 활성 역할 `SUSPEND`(원인 = 회차 번호), 그리고 **기록 대상 역할 중 이미 정지돼 있던 것(재설정 D-ADMIN1-009 등)에도 원인 = 회차인 `SUSPEND` 이벤트를 다시 append**(rev 0.4 m-1 — 완료 조건 "여전히 이 회차 원인으로 정지"를 이벤트 하나로 판정, 이력은 보존). (rev 0.5 n-2) 이벤트 재생 규칙: **이미 정지된 역할에 대한 SUSPEND 재append는 유효한 전이**(상태는 정지 그대로, 원인만 바뀜)이고, 역할의 정지 원인 = **그 역할의 최신 SUSPEND 이벤트 원인**이다. 따라서 원인이 회차로 바뀐 뒤에는 이전 재설정 approvalId를 근거로 한 `ROLE_REACTIVATE`는 "그 재설정 원인으로 정지된 역할 없음"으로 거부된다(fail-closed) → 기존 미사용 코드 폐기 → `PENDING_ENROLLMENT`(대기 사유 RECOVERY·근거 회차) → 세대+1·`security_version`+1 → 감사 |
| 작업 A 완료 TX | 잠금 순서 회차 행 → 계정 행 → 코드 행(§4.13). 등록 완료 공통 검사(§4.3.6) 뒤 **코드 행에 기록된 역할만** 대상으로, 역할마다: 회차가 열려 있고 만료 전이며, 그 역할이 대상에서 **여전히 이 회차 원인으로 정지 상태**(그 사이 REVOKE 이벤트 없음)이고, **그 역할의 활성 계정 수가 여전히 0**일 때만 `REACTIVATE`(actor `SYSTEM_RECOVERY`, 원인 = 회차). 조건을 못 맞춘 역할과 기록 밖 역할(OPERATOR·VIEWER·`UNMASK`, 복구 시점 이후 회수된 역할)은 정지 상태로 남아 **일반 `ROLE_REACTIVATE` 2인 승인**으로만 복원(Coordinator fail-closed 판정). 그다음 회차 종료 판정 |
| 활성 수 판정의 직렬화 | SECURITY_ADMIN·APPROVER를 활성화하는 모든 TX(작업 A 완료, 작업 B 계정 등록 완료, 부트스트랩 계정 등록 완료, `ROLE_GRANT`·`ROLE_REACTIVATE`·`ACCOUNT_UNLOCK`·`ACCOUNT_REACTIVATE` 실행)는 열린 회차가 있으면 회차 행을 먼저 잠근다(§4.13) — 활성 수 계산과 자동 재활성이 동시 활성화와 겹치지 않는다 |
| 작업 B | (표시 2026-11-25: 작업 B 직후 SA 1명이면 역할 부여 교착 가능 — D-ADMIN1-022는 bootstrap만 2명, D-ADMIN1-023은 복구 조건 유지 + 운영 하한 경보로 답함. 작업 B 인원 변경은 결정 없음 — ADM 대장 (f)-6 확인 후보. 본문 그대로) 새 계정 쌍(SECURITY_ADMIN + APPROVER) 생성 + 등록 코드 2개(부트스트랩과 같은 형태, 회차 번호 기록, actor `SYSTEM_RECOVERY`) — 역할은 생성 시 부여되며 계정 등록 완료로 활성. (rev 0.4 n-4) **작업 B 코드의 만료 시각 = 그 회차의 `expires_at`**(같은 TX에서 같은 값으로 기록 — 회차 만료 뒤 남은 B 코드가 회차 밖에서 SA·APPROVER를 활성화하는 경로 없음, 작업 A 코드도 같음). 역할 보유 계정이므로 코드 1인 재발급 불가(§4.3.6 H-1) |
| 직렬화 한계(rev 0.4 n-3 — spec-3 §2(1)) | 회차 행이 **없을 때** 시작한 SA·APPROVER 활성화 TX(0단계에서 열린 회차 없음 판정)는 회차 행을 잠그지 않는다. 그 TX가 진행 중에 복구 명령이 새 회차를 열면, 복구 TX의 "0명" 판정과 그 활성화가 직렬화되지 않아 결과적으로 활성 SA·APPROVER가 0이 아닌데 회차가 열리거나(복구가 불필요하게 실행됨) 활성 수가 1 늘어난 상태가 될 수 있다. **수용 근거**: (1) 이 경합은 권한을 **늘리는 쪽**(관리자가 하나 더 생김)으로만 어긋난다 — 복구 TX는 작업 A 대상 계정의 역할만 정지하고(대상 계정 행 잠금으로 같은 계정의 활성화와는 직렬화) 다른 계정의 활성 역할을 줄이지 않는다 — 복구가 막으려는 위험(관리 불능)과 반대 방향, (2) 복구는 호스트·DB 자격 보유자의 오프라인 실행이라 웹 활성화와 동시에 일어날 일이 드물고 감사(`SYSTEM_RECOVERY`·승인 실행)로 사후 확인된다, (3) (rev 0.5 n-3) 회차가 열린 뒤 시작한 활성화 TX와 작업 A 완료의 자동 재활성은 회차 행 잠금으로 직렬화되고 완료 TX가 회차 잠금 아래 활성 수를 다시 센다. 회차 없음으로 판정한 활성화 TX가 아직 커밋 전일 때 자동 재활성이 함께 일어나는 경우는 **그 TX의 잠금 대기 상한(1205) 안에서만 이론상 가능**하며, 어긋나는 방향은 (1)과 같다(관리자 증가). 완전 직렬화(항상 존재하는 고정 직렬화 행 잠금)는 모든 역할 활성화가 한 행을 잠가 처리량이 줄어 채택하지 않는다 — Coordinator가 다르게 보면 대안으로 승격 |
| 시험(rev 0.3 m-B) | `RecoveryRoleRestoreSpec`: ① 복구 중(회차 열림) 작업 B 또는 부트스트랩 계정이 먼저 등록 완료해 APPROVER 활성 수 > 0 → 작업 A 완료 시 APPROVER 자동 재활성 안 됨(정지 유지), ② 같은 회차 코드 2개를 두 연결로 동시 완료 → 회차 행 잠금으로 직렬화, 마지막 완료가 회차를 닫음(닫힌 회차 1·열린 회차 0), 같은 역할이 두 번 자동 재활성되지 않음, ③ 복구 시점 이후 회수된 역할은 복원 안 됨, ④ 복구 시점 기록에 없는 OPERATOR는 정지 유지, ⑤ 회차 만료 뒤 완료 → 등록은 거부(코드 만료), ⑥(rev 0.4 m-1) 재설정으로 이미 정지된 APPROVER를 가진 계정에 작업 A → 원인 = 회차 SUSPEND 이벤트 재기록 확인 → 완료 시 APPROVER 활성 수 0이면 복원, 그리고(rev 0.5 n-2) 원인이 회차로 바뀐 뒤 **이전 재설정 approvalId 근거 `ROLE_REACTIVATE` 요청·승인은 거부**, `RoleStateConsistencySpec` 재생에서 정지 → 정지(원인 교체)가 유효 전이, ⑦(n-4) 작업 B 코드 만료 시각 = 회차 만료 시각 |

### 4.10 React SPA·서빙

- `apps/internal-admin/frontend/`(package.json 정확 버전·`engines.node`, `package-lock.json` v3, `.npmrc` `save-exact`·`engine-strict`·`ignore-scripts`·`fund=false`·`audit=false`·registry 고정, vite·tsconfig·`index.html`·`src/`).
- 화면: 로그인(비밀번호 → TOTP), 등록(코드 → 비밀번호 → TOTP QR·확인), 공통 레이아웃(표시명·역할·로그아웃·역할별 메뉴), 계정 목록·상세·1인 명령(SECURITY_ADMIN), 승인함(승인·거절 + TOTP, 재설정 코드 1회 표시 화면 — 닫으면 다시 볼 수 없음 안내), 내 요청, 감사 조회, 오류 화면(401→로그인·`SESSION_REPLACED` 안내, 403, 404, 409 `RETRY_LATER`, 5xx).
- API client: `fetch` 래퍼 하나, `credentials: 'same-origin'`, 상태 변경에 `X-XSRF-TOKEN`.
- 서빙: Vite 출력 → `processResources` → `static/`. **캐시 헤더 분리(n-3)**: `/api/**` 응답은 모두 `Cache-Control: no-store`(Security 기본 cache 헤더 유지), `/assets/**`만 `private, max-age=31536000, immutable`(이 경로에서 Security cache 헤더 끔), `index.html`·SPA fallback은 `no-store`, 없는 asset 404. 시험 `SpaServingSpec`이 세 경로의 헤더를 각각 고정(assets에 `no-store` 없음, API에 `immutable` 없음).
- CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`, nosniff, `Referrer-Policy: no-referrer`, Permissions-Policy, HSTS(https). 빌드 산출물 inline script 없음 시험.
- 오류 응답: JSON `{error: <고정 code>}`만, Whitelabel 비활성, stack·메시지·입력 echo 없음.

### 4.11 프론트 툴체인·하네스

#### 4.11.1 방식
- **권장 A: node-gradle 플러그인**(고정 Node 다운로드). 기각: 시스템 Node(재현성), Windows npm(HARNESS §1), 대안 D: Docker node(플러그인 실패 시).

#### 4.11.2 상세(버전은 S0에서 게시 14일 이상 안정판 실측 후 고정)
- **S0 결과(§17.1·§17.3)**: Node 24.21.0(Krypton LTS)·node-gradle 7.1.0·npm 직접 의존 버전 표, Gradle 9.8.0 호환·F-8 실패 재현·settings ivy 저장소 동작·SHA-256 검사 동작. lockfile은 `--before=<cutoff>`로 만들어야 전이 의존까지 14일 규칙을 지킨다. Node·undici는 14일 규칙과 알려진 취약점이 충돌(§17.10).
- catalog: 플러그인 버전, `node` 버전(LTS 후보), `node-sha256-linux-x64`(공식 SHASUMS256 값). settings `dependencyResolutionManagement`에 nodejs.org dist ivy 저장소 `exclusiveContent`(F-8), 압축 SHA-256 검사 task(F-9).
- `build-logic` convention plugin `paycore.frontend`(CHECKOUT-REACT-001 재사용): task `frontendLockfileCheck`(v3·`resolved` registry 고정·`integrity` sha512), `frontendTypecheck`, `frontendTest`, `frontendBuild` — argv 고정. `check` ← 앞 셋, `processResources` ← build. `npm ci --ignore-scripts`, 캐시 `apps/internal-admin/.gradle/npm-cache`.

#### 4.11.3 Vitest와 집계(m-9)
- 설정 `allowOnly: false`(`.only` 있으면 실패), `passWithNoTests: false`(시험 0건 실패), JUnit 출력 `build/test-results/frontend/TEST-vitest.xml`.
- `it.todo`·`skip`이 JUnit에서 skipped로 집계되는지 S0 실측 — test-summary는 skip을 실패로 보므로 todo·skip 금지(ESLint 규칙 또는 Vitest 설정, S0 결정), Vitest 보고서 suite `timestamp` 존재 실측(없으면 Vitest 설정으로 맞춤, test-summary 기준은 바꾸지 않음).
- **S0 결과(§17.4)**: suite `timestamp`는 있으나 **루트가 `<testsuites>`(timestamp·skipped 속성 없음)라 현재 test-summary.py는 Vitest 보고서를 항상 stale로 FAIL**하고, 루트에 skipped가 없어 todo·skip을 0으로 센다(형식만 맞추면 skip 감지 구멍). `it.todo`·`it.skip`은 suite에서 `skipped`로 집계됨, `allowOnly:false`의 `.only` → exit 1, 시험 0건 → exit 1. 처리 방식은 §17.10 Q-S0-3.
- 보고서별 최소 건수: 현재 TEST-SUMMARY는 합계 하한만 본다 → frontend 보고서 디렉터리에 **별도 최소 건수**(예: 실측값)를 두는 것을 Coordinator에 제안(commands.json — §13-4).

#### 4.11.4 npm CVE(D-ADMIN1-007, m-10)
- `cve-scan.py` `FRONTENDS = ["apps/internal-admin/frontend"]`, osv `-L <lockfile> -L harness/fixtures/cve-control-vulnerable-npm/package-lock.json`.
- canary lockfile에 **`dependencies` 취약 패키지 1개 + `devDependencies` 취약 패키지 1개** — 각각 취약점 ≥1이 나와야 신뢰(둘 다 FAIL 대상임을 증명). argv에 dev 제외 계열 옵션이 없음을 `test_cve_scan.py`가 검사. 판정은 기존 Maven과 같음(패키지 수 일치·exit 일치). 예외는 `[[IgnoredVulns]]`만.
- `test_module_registry.py`: `package-lock.json`이 있는 `apps/*/frontend`는 모두 `FRONTENDS`에.

#### 4.11.5 fingerprint 보호 경로(m-8 — S0 하네스 변경, 2단계 허용 경로 추가 필요)
- `harness/scripts/fingerprint.sh` `guarded_ignored` pathspec에 `':(glob)apps/*/frontend/src/**'`, `':(glob)apps/*/frontend/*.json'`, `':(glob)apps/*/frontend/*.ts'`, `':(glob)apps/*/frontend/index.html'`, `':(glob)apps/*/frontend/.npmrc'` 추가(`node_modules`·빌드 출력 제외 — 루트 패턴만이라 하위 `node_modules`에 걸리지 않음). `test_fingerprint.py`에 "무시된 frontend 소스 → FAIL, node_modules → 통과" 시험.
- 저장소 밖 npm 입력: `~/.npmrc`·`npm_config_*` 환경변수가 `npm ci`에 영향(registry·ignore-scripts 덮어쓰기)을 주는지 S0 확인. 영향이 있으면 Gradle task가 `npm_config_userconfig`를 빈 파일로 고정하고 `npm_config_*`를 제거한 환경으로 실행, VERIFY-GATE 환경 snapshot에 기록(verify-gate.py — HVC §4 한계 항목과 같은 방식). **S0 결과(§17.7): 영향 있음** — 환경변수(`npm_config_*`·`NPM_CONFIG_*`)가 프로젝트 `.npmrc`의 registry·ignore-scripts·engine-strict를 덮고, `npm ci`가 lockfile `resolved` host를 그 registry로 바꿔 받는다. `~/.npmrc`는 프로젝트가 정하지 않은 키(`strict-ssl`·`script-shell` 등)를 더한다. 위 격리 실행이 필요하다.

### 4.13 전역 잠금 순서(rev 0.3 m-C — 모든 쓰기 TX가 이 순서의 부분열만 쓴다)

**복구 회차 행 → 승인 요청 행 → 계정 행(actor ID 오름차순) → 등록 코드 행**

| TX | 잠그는 행(순서대로) |
|---|---|
| 로그인 비밀번호·TOTP 단계, 재인증 실패 계수 TX | 계정 행(본인) |
| ADMIN 소유 명령 승인(§4.7.3) | (0단계: 승인 행 불변 열 비잠금 읽기) → (SA·APPROVER 활성화 종류 + 열린 회차) 회차 → 1단계 승인 행 → 요청자·승인자·대상 계정 오름차순 → (코드 발급 종류) 코드 행 |
| 거절(§4.7.8) | 승인 행 → 승인자·요청자 계정 오름차순 |
| 철회(§4.7.8) | 승인 행 → 요청자 계정 |
| 승인 요청 생성(rev 0.4 n-2 — 순서 변경) | **승인 행 INSERT 먼저**(`pending_key` UNIQUE 1062는 여기서) → 요청자·대상 계정 오름차순 `FOR UPDATE` → 요청자 상태·역할·세대, **대상 존재**(승인 요청 대상 FK 없음 — D-58·§19.7, 없으면 전체 rollback 후 거부)·`security_version` 스냅샷 재검사(실패면 전체 rollback). 새 행은 다른 TX에 보이지 않으므로 "승인 행 → 계정 행" 순서와 일치. (rev 0.5 n-4) 1062(`DUPLICATE_PENDING`)·재검사 실패 거부의 **감사는 rollback된 주 TX 밖 별도 TX**로 커밋(주 TX와 함께 사라지지 않게) |
| 등록 완료 잠금 재확인(rev 0.4 m-3) | 0단계 비잠금 선판정 → 회차 → 계정 → 잠금 아래 재확인 불일치면 rollback(§4.3.6 완료 TX) |
| 1인 명령(생성·정지·회수·`ENROLL` 재발급) | 행위자·대상 계정 오름차순 → (정지·재발급) 코드 행 |
| 등록 완료(§4.3.6) | (복구 코드 또는 SA·APPROVER가 활성화되는 계정) 열린 회차 → 대상 계정 → 코드 행 |
| 부트스트랩(§4.9.1) | 새 행 INSERT만(`admin_bootstrap` PK) |
| 복구 실행(§4.9.2) | 회차(만료 회차 종료 UPDATE·새 회차 INSERT) → 대상 계정(작업 A) → 코드 행 |
| 만료 정리 job | 승인 행(행마다 짧은 TX) |

- 1213(교착)·1205(대기 초과)는 어느 TX든 **전체 rollback** 후 HTTP 409 `RETRY_LATER`(웹) 또는 고유 비영 exit 코드(1회성 명령)로 끝내고, 성공·결과 불명확으로 매핑하지 않는다. 상태·감사·TOTP 단계·코드 소비가 모두 불변임을 시험한다(`ApprovalLockOrderSpec` — 승인·거절·철회·등록 완료·복구·정지 행, 강제 교착 주입).
- 잠근 행을 JPQL `@Lock`으로 다시 읽지 않는다(관례 §9). 외부 호출은 없다.

### 4.12 이관 참고(ADMIN-PAYMENT-OPS-001·WEBHOOK-ENDPOINT-001 — 이 작업의 범위 아님)

- **OPS**: rev 0.1 §4.9~§4.10의 설계(ADMIN → PAYMENT 내부 connector, method·path·시각·본문 hash HMAC·ADMIN 전용 키 공간, 요청 시 대상 스냅샷·승인 시 `oct1:` 계산·balanceRevision 재비교, `oct1:` harness golden 선행, 진입점 ArchUnit 완화, 형식 오류 `MALFORMED` 감사, 미해결 실행 시 새 승인 거부·`EXECUTION_UNKNOWN` 회복, 승인 전 PAYMENT 재조회는 DB TX 밖)는 그 작업 1단계 명세의 출발점이다.
  소유 서비스 종류는 **2-TX 모델**(승인 TX → TX 밖 호출 → 결과 TX)이며 §4.7.3의 단일 TX와 다르다. 이 작업은 판정표에 소유 서비스 종류 상태(`APPROVED`·`EXECUTING`·`EXECUTION_UNKNOWN`)를 정의만 하고 실행 경로는 만들지 않는다.
- **WEBHOOK**: GAVE_UP 조회·재전송·LIVE endpoint 2인 승인 종류는 WEBHOOK-ENDPOINT-001. 이 작업은 `ApprovalKind`를 확장 가능한 열거로 둔다.

---

## 5. JPA·jOOQ·CQRS(이번 변경에서 하는 일 → 예 → 이유 → 주의)

- **순수 domain + `Jpa*Entity`**: `AdminAccount`·`ApprovalRequest`가 판정표·불변식을 갖고 Entity는 행 모양만, Mapper `reconstitute`가 불가능 조합(ACTIVE인데 TOTP 없음, 종료 상태인데 결정자 없음)을 거부. 주의: data class 금지, equals/hashCode는 ID만, toString에 해시·암호문 없음.
- **비관적 잠금 + 잠금 순서**: 승인 TX는 승인 행 → 계정 행(actor ID 오름차순) 순으로 `FOR UPDATE`. 예: 승인자 A가 대상 B의 역할 부여를 승인하는 동안 SECURITY_ADMIN이 A의 역할을 회수하면 두 TX가 A 행에서 직렬화되어 "역할 없는 승인 실행"이 불가능. 이유: `@Version`만으로는 서로 다른 행(승인 행·여러 계정 행) 사이 불변식을 지키지 못한다. 주의: 1213·1205는 TX 전체 rollback 후 재시도 가능 오류, 잠근 행을 JPQL `@Lock`으로 다시 읽지 않는다(관례 §9).
- **BCrypt는 잠금 밖, 판정은 잠금 안**: 로그인 비교(수백 ms)를 잠금 밖에서 하고 잠금 TX는 상태·한도 판정 뒤 결과만 적용 — 같은 계정 공격 요청이 정상 요청을 줄 세우지 않는다. 사이 변경은 `security_version`으로 감지.
- **dirty checking + 열 단위 권한**: 계정·승인 요청은 managed Entity 필드 변경이 flush 때 UPDATE가 된다. 불변 열은 `updatable = false`라 SET에서 빠지고 DB 열 권한도 없어(1143) 이중 차단. 주의: Hibernate는 updatable 열 전체를 SET에 넣으므로 열 권한 목록 = updatable 열 목록이어야 한다(시험으로 대조).
- **`@Version`(기술) vs `security_version`·`session_generation`(업무)**: version은 매 UPDATE마다 바뀌고, `security_version`은 §4.7.5 사건만, 세대는 세션 유효성. 승인 대상 버전·세션 검사에 JPA version을 쓰지 않는다(로그인 활동마다 바뀌어 승인이 무효화됨).
- **`@Immutable` + `persist`**: 감사·역할 이벤트·1회성 행. `save`(merge) 금지 — SELECT·INSERT 권한과 맞음(UPDATE·DELETE 1142).
- **flush와 commit**: 쓰기 Port 끝 `flush()`로 login_name·`pending_key`·부트스트랩 PK·회차 UNIQUE 위반을 Port 안에서 분류. commit 단계 예외는 결과 불명확 → 승인 행 재조회로 수렴(§4.7.3).
- **OSIV off + DTO**, 연관 매핑 없음(역할 이벤트·감사는 별도 Entity).
- **Spring Session JDBC는 JPA가 아니다**: 같은 DataSource를 쓰는 별도 JDBC 접근, 업무 TX와 같은 TX라고 가정하지 않는다. 권위는 계정 행 세대.
- **jOOQ**: `AdminAccountListReader`, `ApprovalRequestListReader`, `AdminAuditReader`(scope 필수 인자 — SELF는 SQL 조건, keyset 페이지). 명령 판정에 쓰지 않음(ArchUnit: Reader 사용처는 조회 서비스뿐). 시각 `DATE_FORMAT` 읽기·UTC 리터럴 바인딩.
- **Kafka/Redis**: 세션·잠금·승인 직렬화·1회성은 소유 DB 행 잠금·UNIQUE가 지킨다. Redis 세션·분산 lock으로 대체하지 않는다.

---

## 6. 비노출·로그·관측

- 비노출: 비밀번호·TOTP 코드·시크릿·`otpauth` URI(등록 응답 1회 제외)·등록 코드(승인 응답·CLI 출력 1회 제외)·세션 ID·CSRF 토큰·암호화/HMAC 키. 설정 `toString` 마스킹, 기동 실패 보고에 키 없음. `AdminNonDisclosureSpec`(캡처 로그·예외 사슬·응답·감사·설정에 시험 비밀 0회). CLI 코드는 TTY·0600 파일만(§4.9).
- 로그: `operation·correlationId·actorId·result·failure` 분류만, 예외 클래스 사슬, Hibernate SQL 오류 원문 로거 OFF.
- 지표(label 유한값만): `paycore.admin.login{step,result}`, `paycore.admin.lockout`, `paycore.admin.ip_denied`, `paycore.admin.session.invalidated{reason}`, `paycore.admin.session.rows`·`anonymous_created`, `paycore.admin.approval{kind,result}`, `paycore.admin.approval.pending`·`pending.oldest_age_seconds`, `paycore.admin.approval.retry_later`, `paycore.admin.enrollment{result}`, `paycore.admin.audit.write_failed`, `paycore.admin.oneshot{command,result}`.

---

## 7. 시험 계획(실제 MySQL 8.4.11·실제 HTTP)

### 7.1 domain(순수)
- `AdminAccountSpec`(상태 전이 표·세대/버전 증가), `ApprovalDecisionTableSpec`(상태×명령 전수, ADMIN 소유 종류 EXECUTING/UNKNOWN 도달 불가), `AdminPermissionSpec`, `TotpStepSpec`, `SecurityVersionEventsSpec`(사건 표 전수 + 열 분류 누락 감지), `RoleRuleSpec`(요청자≠승인자≠대상, 재활성의 재설정 요청자 배제).

### 7.2 adapter·통합
- 암호: `TotpVerifierSpec`(RFC 6238 벡터 — harness `test_totp_rfc6238_golden.py`가 Python `hmac`으로 독립 생성한 golden), `TotpSecretCipherSpec`, `PasswordHashingSpec`.
- DB: `AdminDatabaseAccountSpec`(표·열 권한 정확 비교, 불변 열 1143, append-only 1142), `AdminSchemaSpec`, `RoleStateConsistencySpec`, `SessionTableSpec`.
- 로그인·잠금: `LoginLockoutSpec`, `LoginLockoutConcurrencySpec`(동시 N 실패 → 계수 정확), `LoginPathEquivalenceSpec`(분기별 BCrypt·TX·감사 호출 수), `NewLoginWinsSpec`·`SingleSessionRaceSpec`.
- 등록: `EnrollmentCodeSpec`(만료·폐기·재발급 주체), `EnrollmentCodeRaceSpec`(패자 시크릿·해시로 활성 0건), `EnrollmentPurposeFlowSpec`(목적별 요소), `EnrollmentPendingValueSpec`(대기 값은 암호화 세션 속성만·계정 행 무변경·다른 진행 ID AAD로 복호 실패·용도 태그 바꾸면 복호 실패·**저장 TOTP 문맥 AAD로 복호 실패** — rev 0.4 n-1), `EnrollReissueRoleBearingForbiddenSpec`(rev 0.4 H-1), `SuspendRevokesEnrollmentSpec`(정지 → 코드 폐기·완료 거부, 정지·완료 동시 경합, LOCKED 대상 완료 거부).
- 로그인 경합: `LoginCredentialChangeRaceSpec`(§4.3.2 (a)~(b) 사이 `security_version` 변경).
- 승인: `AdminOwnedCommandSpec`(6종 × 성공·대상 무효·자기 승인·승인자 역할 상실(403·상태 불변)·요청자 정지(TOTP 뒤 INVALIDATED)), `ApprovalConcurrencySpec`(동시 승인 1건), `ApprovalVersusCloseRaceSpec`(승인 vs 철회·거절·만료), `DuplicatePendingRaceSpec`(동시 생성 1062 한 건), **같은 대상·다른 종류 동시 생성 → 1213 0회**(S2 리뷰 M-1 — 승인 요청 대상 FK 제거 뒤 생성 TX 두 개가 같은 대상 계정을 잠가도 교착 없음, 대상 존재는 계정 `FOR UPDATE` 아래 확인), `RejectWithdrawSpec`(거절 TOTP 소비·실패 별도 TX, 철회 본인만), `ApprovalLockOrderSpec`(승인·거절·철회·등록 완료·복구·정지 TX의 1213·1205 → 409/비영 exit·무변경), `RevokeDuringApprovalRaceSpec`, `StepUpTotpSpec`·`StepUpTotpReuseRaceSpec`·`StepUpFailureCountsSpec`, `DuplicatePendingSpec`, `ApprovalExpirySpec`(TX 시각), `CredentialResetSuspendsRolesSpec`·`ResetCodeDisclosureSpec`·`RoleReactivateSpec`, 승인 TX 중간 실패 주입 → 상태·감사·TOTP 단계 모두 무변경, commit 후 응답 유실 → 재조회 수렴.
- 1회성: `BootstrapSpec`·`BootstrapConcurrencySpec`, `RecoveryCommandSpec`·`RecoveryConcurrencySpec`·`RecoveryRoleRestoreSpec`(§4.9.2 시험 ①~⑤), `OneShotModeGuardSpec`(웹 동시 기동 거부·TTY/0600).
- 계정 규칙: `SelfSuspendForbiddenSpec`(SECURITY_ADMIN 본인 정지 거부 — n-E), `RoleRuleSpec`(H-A 4행 포함).
- HTTP 보안: `SessionFixationSpec`, `SpaCsrfSpec`(TechSmoke 대체 포함), `SessionCookieAttributesSpec`, `SessionTimeoutSpec`, `AnonymousSessionGrowthSpec`, `PrivilegeEscalationSpec`, `SelfApprovalForbiddenSpec`, `IpAllowlistSpec`, `SpaServingSpec`, `ErrorNonDisclosureSpec`, `AuditFailureClosesActionSpec`, `AuditQueryPermissionSpec`, `LiveModeStartupGuardSpec`, `UnmaskFrameworkSpec`, `AdminNonDisclosureSpec`.
- ArchUnit: §3.3 교체 규칙 + 위반 fixture, `account → approval` 금지, Reader 사용처 제한.

### 7.3 프론트(Vitest)
- 레이아웃·역할별 메뉴, 로그인·등록 단계 전이, 오류 화면(401·`SESSION_REPLACED`·403·409·5xx), CSRF 헤더 부착(GET 없음), 승인 TOTP 필수, 재설정 코드 1회 표시 화면(닫으면 상태에서 제거). 서버 보안 판정은 Kotlin 시험이 권위.

### 7.4 Test Fixture Policy(n-9)
- Named Fixture는 공식 경로: `AdminFixture.enrolledActive()`(create → enroll → activate), `ApprovalFixture.pending(kind)`.
- 역할이 필요한 준비는 **핵심 규칙 시험에서 승인 경로로**(`AdminFixture.withRoleViaApproval(role)` — 요청·승인 Use Case 호출). 조회·화면 시험용 빠른 준비만 `AdminFixture.rolesSeededBypassingApproval_forReadOnlyTests(...)`처럼 **이름에 우회 의도 표시**, 핵심 규칙 시험에서는 사용 금지(검토로 확인).
- Fixture Monkey는 감사·목록 jOOQ 대량 행만(Named Fixture 뒤). Invalid Fixture는 `reconstitute` 거부 시험에만, 이름에 의도.

---

## 8. 8대 원칙 완료 증거 계획

| 원칙 | 적용 | 증거 |
|---|---|---|
| Financial Consistency | **해당 없음** — 금융 사실·금액 없음(ADMIN DB는 관리 메타데이터, AC1). 금융 명령은 ADMIN-PAYMENT-OPS-001 | — |
| Transaction Safety | ADMIN 소유 명령 단일 로컬 TX(상태·역할 이벤트·코드·감사·승인 행), 감사 실패 = 행위 실패, 외부 호출 없음 | 승인 TX 중간 실패 rollback, `AuditFailureClosesActionSpec`, commit 후 응답 유실 수렴 |
| Idempotency | 승인 결정 1회(`ALREADY_DECIDED`), 등록 코드 원자 소비, TOTP 단계 재사용 거부, 1회성 고정 행 | `ApprovalConcurrencySpec`, `EnrollmentCodeRaceSpec`, `StepUpTotpReuseRaceSpec`, `BootstrapConcurrencySpec` |
| Concurrency Control | 잠금 순서(승인 → 계정 오름차순), 계수 행 잠금, 세대 직렬화, UNIQUE(login_name·pending_key·부트스트랩·회차) | `LoginLockoutConcurrencySpec`, `SingleSessionRaceSpec`, `RevokeDuringApprovalRaceSpec`, `ApprovalLockOrderSpec`, `RecoveryConcurrencySpec` |
| Recoverability | JDBC 세션 재시작 유지, commit 불명확 → 승인 행 재조회, 복구 명령(회차 반복), 세션 저장소 장애 fail-closed | 재기동 시험, 세션 표 장애 주입(401/503), `RecoveryCommandSpec` |
| Reconciliation | **해당 없음** — 기관·원장 비교 없음. 내부 일관성(역할 이벤트 재생 = 계정 현재 역할)만 | `RoleStateConsistencySpec` |
| Auditability | **핵심** — 행위자·실행자(승인자)·사유·승인 ID·결과, 미인증 HMAC, 감사 조회도 감사 | `AdminAuditAppendOnlySpec`, 행위별 감사 표 시험, `AuditQueryPermissionSpec` |
| Observability | §6 지표·구조화 로그 | 지표 이름·label 집합 시험, `AdminNonDisclosureSpec` |

---

## 9. BOM·의존성·Kafka/Redis

- 추가(Boot 4.1.1 BOM 관리): data-jpa, flyway + flyway-mysql, mysql-connector-j, jooq + codegen(빌드 전용), **`spring-boot-starter-session-jdbc`(새 카탈로그 항목)**, actuator; 시험 testcontainers-mysql·spring-boot-testcontainers.
- 제거: internal-admin의 `spring-boot-starter-thymeleaf`(payment-api 결제창·Simulator 유지).
- BOM 밖: node-gradle 플러그인(빌드 전용), Node 배포판(SHA-256 고정), npm 패키지(lockfile·npm CVE). 빌드 파일 변경 → GRADLE-DEPS-INTERNAL-ADMIN·CVE-SCAN 재실행.
- Kafka/Redis 도입 안 함(D-ADMIN-004 JDBC 세션, DB 잠금·UNIQUE가 안전성 담당).

---

## 10. 단계별 구현 계획(2단계 TDD — 시험 먼저 → RED 기록 → 최소 구현 → 핵심 변이 RED)

| 단계 | 내용 | 선행 | 예상 시험 |
|---|---|---|---|
| S0 | 실측·고정: Node LTS·SHA-256, 플러그인, npm 후보 게시일, F-8, `--ignore-scripts`, Vitest JUnit(timestamp·todo/skip 집계), `~/.npmrc`·`npm_config_*` 영향, Security 7.1.1 SPA CSRF·`SecurityContext` 직렬화, Spring Session `SaveMode` 병렬, `CREATE_NEW`+0600 동작(WSL) — **결과 기록 후 의존성 추가 전 Coordinator 보고** | — | 0 |
| S1 | 프론트 골격·convention plugin·lockfile/typecheck/test/build task·`SpaServingSpec`·`.gitignore`·fingerprint 보호 경로·`cve-scan.py` npm(canary 2종)·harness 시험, Thymeleaf 제거·TechSmoke 대체(§3.3) | S0 | Kotlin 15~25, Vitest 10~15, harness 5~8 |
| S2 | ADMIN DB: 초기화 스크립트(표·열 helper)·compose·`.env.example`, migration(표 8종·권한·Spring Session 표), `AdminDatabaseAccountSpec`·`AdminSchemaSpec`, ArchUnit 교체(§3.3), `TestInfrastructureSpec` 교체, jOOQ codegen | S0 | 40~55 |
| S3 | `account`·`audit` domain·application: 상태·대기 사유·역할 이벤트·`security_version` 사건, 비밀번호·TOTP Port(RFC golden)·cipher, 잠금, 등록 코드(목적별 흐름·암호화 대기 값·원자 소비·완료 TX 상태/사유 검사), 정지의 코드 폐기·본인 정지 거부, 감사(실패 처리·조회 권한) | S2 | 115~145 |
| S4 | 인증 HTTP: 2단계 로그인(경로 동등성·자격 변경 경합), Spring Session·쿠키·세대 filter·만료·익명 세션, CSRF, IP, 배치 모드 가드, 오류 비노출 | S3 | 62~82 |
| S5 | `approval`: 판정표·단일 TX orchestrator·전역 잠금 순서·1213/1205·TOTP 재인증(별도 실패 TX)·승인자/요청자 재검사 순서·중복 PENDING(동시)·만료·거절/철회 TX·승인 vs 종료 경합, ADMIN 소유 명령 6종·1인 명령, 재설정 역할 정지·코드 1회 표시·재활성(H-A 배제 4행), 마스킹 해제 틀, jOOQ Reader | S4 | 105~135 |
| S6 | 1회성 명령: runner·모드 가드·TTY/0600·부트스트랩·복구 회차·작업 A 역할 기록/정지·완료 시 역할별 자동 재활성 조건·회차 종료 판정·활성 수 판정 직렬화 | S3·S5 | 35~45 |
| S7 | React 화면 | S4·S5 | Vitest 20~45 |
| S8 | 관측·비노출 묶음, 문서(관례 새 절·§1 표·HVC §7 기록·TECH-BASELINE 프론트 항목), 전체 check·GRADLE-DEPS·CVE-SCAN 실측 보고 | S1~S7 | 10~20 |

---

## 11. 구현 결정 목록(가역적 — Coordinator가 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| D-01 | 프론트 `apps/internal-admin/frontend/`, 소스는 fingerprint 보호 경로에 추가 | F-7, m-8 |
| D-02 | node-gradle + Node SHA-256 고정 + settings ivy 저장소 | F-8·F-9 |
| D-03 | BCrypt(`{bcrypt}`), 72바이트 상한 | F-11 |
| D-04 | TOTP RFC 6238 자체 구현(adapter), RFC 벡터 harness golden | F-12 |
| D-05 | 본인 비밀번호 변경 기능 없음 | 범위 최소화 |
| D-06 | login_name 변경 없음, actor ID 불변·계정 DELETE 없음 | D-ADMIN-004 |
| D-07 | TOTP 시크릿 AES-256-GCM, AAD = actor ID + 키 버전 | D-ADMIN-001 |
| D-08 | 세션 속성 최소화·역할은 요청마다 계정 행 | 회수 즉시 반영 |
| D-09 | 동시 로그인 1 = 세대(DB 잠금) | D-ADMIN1-001 |
| D-10 | CSRF = 세션 동기화 토큰 + 쿠키 전달 + 헤더 회수 | D-ADMIN-004·008 |
| D-11 | 재인증 TOTP는 요청 본문 포함, 성공 소비는 승인 TX, 실패 계수는 별도 TX | AC6, m-3 |
| D-12 | IP 거부: 인증 경로 감사 + 지표, 정적 지표만 | 감사 폭주 방지 |
| D-13 | 대상 버전 = `security_version`, 사건 표 §4.7.5(무효 범위를 넓히는 쪽만 — Coordinator 판정) | m-7 |
| D-14 | TechSmoke → SPA/JSON 시험 대체(§3.3) | F-3 |
| D-15 | actuator 별도 management 포트 | 공격 면 |
| D-16 | SPA 서빙 모듈 `console` | 책임 분리 |
| D-21 | ADMIN 소유 명령 orchestrator = `approval` application, `account` 역의존 금지 | H-1 |
| D-22 | 잠금 순서 승인 행 → 계정 행 actor ID 오름차순, 1213·1205 → 409 `RETRY_LATER` | m-2 |
| D-23 | 승인 시 요청자 상태·역할도 재검사(상실 시 `INVALIDATED`) | m-1, fail-closed |
| D-24 | 역할 = append-only 이벤트 + 계정 행 현재 집합(같은 TX), 승인 요청·등록 코드·복구 회차 = 열 단위 UPDATE, 계정 = 불변 열 제외 열 단위 UPDATE | m-6, F-13 |
| D-25 | 로그인 모든 분기 BCrypt 1·TX 1·감사 1, 더미 해시 같은 cost | m-4, D-ADMIN1-005 |
| D-26 | 등록 코드 160비트·SHA-256·완료 TX 원자 소비, `ENROLL` 재발급 1인·재설정 코드 재발급은 새 2인 승인 | m-5 |
| D-27 | 1회성 명령 runner는 `account.adapter.batch`, 명시 argv, 웹 동시 기동 거부, TTY 또는 `CREATE_NEW` 0600 파일 | H-2, F-14 |
| D-28 | 부트스트랩 고정 행 `admin_bootstrap(id=1)`, 복구는 열린 회차 UNIQUE(회차 반복 가능) | H-2, D-ADMIN1-010 |
| D-29 | `deployment-mode` 필수, LIVE에서 Demo 키·Demo 보안 수치면 기동 실패 | n-4 |
| D-30 | 미인증 login_name 감사는 비밀 키 HMAC | n-2 |
| D-31 | 감사 조회 = 결과 반환 전 별도 TX `AUDIT_VIEWED` 커밋, 실패 시 결과 없음, SELF 범위는 서버 SQL | m-11 |
| D-32 | 익명·전 단계 세션 짧은 idle, 정적 자원 세션 미생성, 세션 행 gauge | n-1 |
| D-33 | 캐시 헤더 경로별 분리(API no-store / assets immutable / index no-store) | n-3 |
| D-34 | 만료 권위 판정은 승인 TX 시각, 정리 job은 표시용 | n-5 |
| D-35 | 같은 (종류, 대상) PENDING 생성 거부(`pending_key` UNIQUE) | n-6 |
| D-36 | Vitest `allowOnly:false`·`passWithNoTests:false`, todo·skip 금지 | m-9 |
| D-37 | npm canary = dependencies 1 + devDependencies 1 취약 패키지 | m-10, D-ADMIN1-007 |
| D-38 | `ROLE_REACTIVATE` 요청자·승인자 ≠ 원인 재설정의 요청자·승인자 | rev 0.3 H-A, Coordinator fail-closed 판정 |
| D-39 | 계정 대기 사유·발급 근거 열, 정지 TX의 코드 폐기, 완료 TX의 상태·사유·근거 일치 검사 | rev 0.3 H-B |
| D-40 | 등록 대기 값은 `TotpSecretCipher` 암호화 세션 속성(AAD = 진행 ID + 대상 + 목적), 계정 행은 완료 TX에서만 | rev 0.3 m-A |
| D-41 | 복구 작업 A: 복구 시점 SA·APPROVER 집합을 코드 행에 기록·전 역할 정지, 완료 시 역할별 조건(회차 열림·만료 전·이 회차 원인 정지 유지·활성 수 0) 충족분만 `SYSTEM_RECOVERY` 재활성 | rev 0.3 m-B, D-ADMIN1-012, Coordinator fail-closed |
| D-42 | 전역 잠금 순서 회차 → 승인 행 → 계정 행 오름차순 → 코드 행(§4.13) | rev 0.3 m-C |
| D-43 | 승인자 쪽 재검사 실패 = 403·상태 불변, 요청자 상실 `INVALIDATED`는 승인자 TOTP 검증 뒤 | rev 0.3 n-A |
| D-44 | SECURITY_ADMIN 본인 정지 거부 | rev 0.3 n-E |
| D-SV | **Demo 보안 수치(운영값 아님, LIVE Gate — Q-ADMIN-SEC-VALUES 확장 대상, Coordinator 판정 2026-10-24)**: idle 15분, 절대 8시간, 익명·비밀번호 단계·등록 진행 세션 idle 10분(TOTP 대기 5분), 잠금 실패 5회, BCrypt cost 12, 비밀번호 최소 12자·72바이트 상한, TOTP 창 ±1, 등록 코드 유효 24시간, 승인 요청 유효 24시간, 복구 회차 유효 24시간, 세션 정리 주기 | D-ADMIN-004 |

---

## 12. 질문

### 12.1 확정(답변됨 — 재질문 금지)

| 결정 | 요지 | 반영 절 |
|---|---|---|
| D-ADMIN1-001 | 새 로그인이 기존 세션을 끊음(세대+1), 기존 화면 안내, 감사 | §4.3.2, §4.4.2 |
| D-ADMIN1-002 | 계정 표가 빌 때만 1회용 비웹 부트스트랩(runtime 계정), SECURITY_ADMIN 1 + APPROVER 1, 코드 1회, `SYSTEM_BOOTSTRAP` → 대체(인원): D-ADMIN1-022(SECURITY_ADMIN 2 + APPROVER 1, ADMIN-BOOT-002) | §4.9.1 |
| D-ADMIN1-003 | 역할 없는 계정 생성·정지 1인, 등록 코드(Demo 24시간) 별도 채널, 정지 해제 2인 | §4.3.6, §4.7.1 |
| D-ADMIN1-004 | 겸직 허용, 요청자·승인자·대상 모두 다름, 부여 2인·회수 1인 즉시 | §4.5.1, §4.7.3 |
| D-ADMIN1-005 | 실패 합산·성공 시 0·자동 해제 없음·비구별 응답·IP 밖 미계수 | §4.3.2·§4.3.5 |
| D-ADMIN1-006 | 승인이 곧 실행, 불명확은 같은 approvalId, 실행자 = 승인자 | §4.7.3 |
| D-ADMIN1-007 | npm dependencies·devDependencies 모두 FAIL, IgnoredVulns만 예외 | §4.11.4 |
| D-ADMIN1-008 | 감사 조회 SECURITY_ADMIN·APPROVER 전체, 나머지 자기 기록, 조회도 감사 | §4.6 |
| D-ADMIN1-009 | 재설정·재등록 시 역할 정지(이력 보존), 코드는 승인자에게 1회, 재등록 뒤 별도 2인 `ROLE_REACTIVATE`(재설정 요청자 배제) | §4.7.7 |
| D-ADMIN1-010 | 활성 SECURITY_ADMIN 또는 APPROVER 0명일 때만 오프라인 복구, runtime 계정·명시 argv·고정 행 UNIQUE·`SYSTEM_RECOVERY`, 기존 계정 코드 또는 새 계정 쌍 | §4.9.2 |
| D-ADMIN1-011 | 재설정·재등록은 등록 코드만으로 해당 요소만 다시 설정(다른 요소 요구 없음) | §4.3.6, §4.7.7 |
| D-ADMIN1-012 | 복구 대상 기존 계정은 재등록 완료 시 복구 시점 SECURITY_ADMIN·APPROVER만 `SYSTEM_RECOVERY` 재활성(조건은 Coordinator fail-closed 판정 — §4.9.2) | §4.9.2 |
| Q-ADMIN-SEC-VALUES | PROPOSED 유지 — Demo 값으로 진행, LIVE Gate 차단, 대상 목록 확장(D-SV) | §11 |

### 12.2 rev 0.2 질문 후보 — rev 0.3에서 확정

Q-ADMIN1-11 → **D-ADMIN1-011**, Q-ADMIN1-12 → **D-ADMIN1-012**(§12.1 표). 미결 보안 질문은 없다.

### 12.3 검토했으나 질문으로 올리지 않은 것
- `security_version` 사건 표(§4.7.5): 제외 집합이 모두 인증 활동이고 무효 범위를 좁히는 결정이 없어 Coordinator 판정 기준(넓히는 쪽만 구현 결정)에 맞음.
- 승인 시 요청자 재검사(D-23)·재설정 코드 재발급의 2인 승인(D-26)·`ENROLL` 코드 재발급 1인(D-26): 모두 기존 결정의 같은 등급 또는 더 엄격한 쪽.
- 부트스트랩 두 계정이 "서로 다른 사람"인지: 시스템은 서로 다른 계정만 강제, 사람 확인은 운영 절차(D-ADMIN1-002 문언 범위).

---

## 13. 범위 관찰(Coordinator 확인)

1. 분할은 판정됨(§2.1). 이 문서는 AC 1~11만 완료 조건으로 다룬다.
2. **하네스 변경 계획(2단계 허용 경로 필요)**: `harness/scripts/fingerprint.sh`(frontend 보호 경로), `harness/scripts/cve-scan.py`(npm), `harness/scripts/verify-gate.py`(npm 환경 snapshot — S0 결과에 따라), `harness/tests/**`, `harness/fixtures/cve-control-vulnerable-npm/**`.
3. **commands.json(Coordinator 소유)**: TEST-SUMMARY에 `apps/internal-admin/build/test-results/frontend`, GRADLE-CHECK `outputMustMatch`에 `:apps:internal-admin:frontendTest`, frontend 보고서 별도 최소 건수 검토(m-9), 하한 실측(낮추지 않음).
4. TECH-BASELINE·`technology-baseline.json`에 Node·npm·프론트 도구 항목.
5. 후속 후보: npm 번들 SBOM, 브라우저 E2E, 운영 mTLS·KMS·프록시 IP 신뢰(LIVE Gate).

---

## 14. 위험

| ID | 위험 | 완화 |
|---|---|---|
| R-1 | 프론트 툴체인이 fingerprint·TEST-SUMMARY·CVE와 맞물리지 않음 | S0 실측, S1 독립 완료 후 업무 단계 |
| R-2 | 플러그인·F-8 실패 | 대안 D(Docker node) 또는 Exec + 고정 압축 직접 다운로드 |
| R-3 | `--ignore-scripts`로 esbuild/rollup 바이너리 미설치 | S0 실측, 허용 목록 rebuild |
| R-4 | registry 장애·오프라인 | 캐시·`--prefer-offline`, 완전 오프라인 범위 밖 |
| R-5 | Vitest JUnit 형식 불일치 | Vitest 설정으로 맞춤 |
| R-6 | 기존 compose 볼륨·`.env`에 ADMIN 계정·변수 없음 | 배포 메모(스크립트 재실행), fail-closed 의도 |
| R-7 | Spring Session 직렬화·SaveMode | S0, 문자열 기반 저장소 |
| R-8 | 잠금 DoS(사내망) | IP·지표·감사, 해제 2인 — D-ADMIN1-005 수용 위험 |
| R-9 | 세션 검사 요청당 PK 조회 | 측정 기록 |
| R-10 | 승인 TX가 계정 3행 + 승인 행을 잠가 교착 가능 | 고정 순서, 409 재시도, `ApprovalLockOrderSpec` |
| R-11 | 재설정 승인 commit 불명확 시 코드 미전달 | 승인 행 재조회 → 새 2인 승인으로 재발급(D-26), 화면 안내 |
| R-12 | 열 단위 권한과 Hibernate SET 열 불일치 | 열 목록 대조 시험 |
| R-13 | 컨테이너에서 CLI TTY 부재 | `file:` 0600 출력, 볼륨 마운트 경로는 운영 절차 |

---

## 15. 규모 추정

- 기준: WEBHOOK-001 = +324건(2136 → 2460).
- ADMIN-BOOT-001(rev 0.3 범위): internal-admin Kotlin +370~500(현재 29 — rev 0.2 대비 목적별 등록·대기 값 암호화·정지/완료 경합·복구 역할 자동 재활성 조건·전역 잠금 순서·승인 vs 종료 경합·동시 중복 생성 시험 +40~50), Vitest +30~60, harness +6~10 → **WEBHOOK-001의 약 1.2~1.6배**(rev 0.2: 1.1~1.5배).
- ADMIN-PAYMENT-OPS-001(참고): 약 0.4~0.6배(그 작업 1단계에서 재추정).
- GRADLE-CHECK 하한 2460 → 실측(Kotlin + Vitest 합산, 약 2,860~3,020 예상), HARNESS-SELFTEST 97 → 실측. 하한은 실측으로만 올린다.

---

## 16. rev 0.2 변경 기록(2026-10-24)

| 검토·판정 | 반영 |
|---|---|
| 사용자 답변 D-ADMIN1-001~010 | §12.1 확정 표, 각 절 반영, rev 0.1 질문 보류 문구 삭제 |
| 분할 판정 | §1·§2 AC 1~11 기준 재작성, §2.1 이관 표, §4.1 `paymentops` 제거, OPS·Webhook은 §4.12 이관 참고로 강등 |
| Coordinator §13 판정 | §3.3 기존 시험 기대 변경 기록(하한 유지), D-SV를 Q-ADMIN-SEC-VALUES 확장 대상으로, D-13 fail-closed 기준(§4.7.5), 마스킹 해제 틀까지(§4.5.3) |
| H-1 | §4.7.2 판정표(ADMIN 소유 종류 EXECUTING/UNKNOWN 도달 불가), §4.7.3 단일 TX 순서·orchestrator·실행자·commit 불명확 수렴, §4.1 의존 방향 |
| H-2 | §4.9 고정 PK 행·패자 1062·`BootstrapConcurrencySpec`·명시 argv·웹 동시 기동 거부·TTY/0600, 복구 열린 회차 UNIQUE(회차 반복) |
| H-3 | §4.7.7 역할 정지·코드 승인자 1회 표시·재활성 승인·시험 고정 |
| H-4 | §2·§4.1·§4.12·§12 재구성 |
| m-1·m-2 | §4.7.3-3·4 행위자·요청자 재검사, 잠금 순서·1213, `RevokeDuringApprovalRaceSpec`·`ApprovalLockOrderSpec` |
| m-3 | §4.7.4 실패 계수 별도 TX·성공 소비 승인 TX·`StepUpTotpReuseRaceSpec` |
| m-4 | §4.3.2 LOCKED·한도 선판정, TOTP 단계 재확인, 경로 동등성(호출 수) |
| m-5 | §4.3.6 등록 코드 표 |
| m-6 | §4.2.2 역할 append-only 이벤트, 열 단위 UPDATE, F-13 |
| m-7 | §4.7.5 사건 전수·제외 집합·시험 |
| m-8 | §4.11.5 fingerprint 보호 경로·npm 환경 |
| m-9 | §4.11.3 Vitest 설정·todo/skip·보고서별 최소 건수 제안 |
| m-10 | §4.11.4 canary 2종·dev 제외 옵션 금지 시험 |
| m-11 | §4.6 감사 조회 감사·SQL 범위 |
| m-12 | §3.3 ArchUnit 허용 패키지 정확 열거·위반 fixture, F-14 |
| n-1~n-9 | §4.4.3(n-1), §4.6(n-2), §4.10(n-3), §4.8.2(n-4), §4.7.2(n-5·n-6), §4.3.1(n-7), §4.4.1(n-8), §7.4(n-9) |
| 새 미결 | Q-ADMIN1-11·12 질문 후보(§12.2) — rev 0.3에서 D-ADMIN1-011·012로 확정 |

### 16.1 rev 0.3 변경 기록(2026-10-24)

| 검토·판정 | 반영 |
|---|---|
| 사용자 답변 D-ADMIN1-011·012 | §12.1 표 추가, §12.2를 확정으로 교체, "답 전 보류" 문구 전부 삭제(n-F), §4.3.6 목적별 표, §4.7.7 본인 재등록 행, §4.9.2 작업 A |
| H-A(Coordinator fail-closed) | §4.7.7 재활성 배제 대상에 원인 재설정 승인자(코드 수령자) 추가, `RoleRuleSpec`·`RoleReactivateSpec` 4행, 인원 최대 4계정 → Q-ADMIN-APPROVER-STAFF 참고, D-38 |
| H-B | §4.3.1 대기 사유·발급 근거 열·정지 TX의 코드 폐기, §4.3.6 완료 TX 상태·사유·근거 일치 검사(다른 상태 전체 rollback), `SuspendRevokesEnrollmentSpec`(정지·완료 동시 경합), D-39 |
| m-A | §4.3.6 진행 중 대기 값 암호화 세션 속성(AAD = 진행 ID + 대상 + 목적), 계정 행은 완료 TX에서만, 목적별 설정 요소, `EnrollmentCodeRaceSpec` 패자 시크릿 활성 0건, `EnrollmentPendingValueSpec`, D-40 |
| m-B(Coordinator fail-closed) | §4.9.2 작업 A 기록·전 역할 정지, 완료 TX 역할별 조건(회차 열림·만료 전·이 회차 원인 정지 유지·활성 수 0), 회차 종료 판정은 완료 TX 회차 행 잠금 뒤(코드 행 회차 번호 열), 활성 수 판정 직렬화, `RecoveryRoleRestoreSpec` ①~⑤, D-41 |
| m-C | §4.13 전역 잠금 순서 표(모든 쓰기 TX), 1213·1205 → 409/비영 exit·상태 불변 시험, D-42 |
| m-D | `ApprovalVersusCloseRaceSpec`, `DuplicatePendingRaceSpec`(동시 명시), `LoginCredentialChangeRaceSpec`, H-B·m-B 시험, §10 예상 시험 수 갱신 |
| n-A | §4.7.3 승인자 쪽 재검사 실패 403·상태 불변, 요청자 상실 `INVALIDATED`는 TOTP 검증 뒤(근거 기록), D-43 |
| n-B | §4.7.8 거절·철회 TX(잠금 순서·TOTP 성공 소비·실패 별도 TX) |
| n-C | §4.3.6 실패 분류 3종을 목적별 흐름에 맞춤 |
| n-D | §3.3 `security.session`의 DataSource·spring-jdbc 필요 여부 S0 확인, 필요 시 표에 한정 허용 행을 추가 기록 |
| n-E | §4.3.1 SECURITY_ADMIN 본인 정지 거부, `SelfSuspendForbiddenSpec`, D-44 |
| n-G | §4.3.2 잔여 시간차 수용 근거 |
| n-H | §4.9 오프라인 명령 신뢰 경계·위협 모델 |
| 규모 | §15 1.2~1.6배, 하한 예상 2,860~3,020 |
| 새 미결 | 없음 |

### 16.2 rev 0.4 변경 기록(2026-10-24 — spec-3 CHANGES_REQUIRED, Coordinator 판정, 새 사용자 질문 없음)

| 검토·판정 | 반영 |
|---|---|
| H-1 | §4.3.6 재발급 주체: SECURITY_ADMIN 1인 재발급 조건을 "대상 계정에 활성·정지 역할이 하나도 없음"(계정 행 잠금 아래 판정)으로 고정, 역할 있는 대기 계정(부트스트랩·작업 B 포함)은 2인 재설정 또는 복구 명령만, `EnrollReissueRoleBearingForbiddenSpec` |
| m-1 | §4.9.2 작업 A: 기록 대상 역할 중 이미 정지된 것에도 원인 = 회차 SUSPEND 이벤트 재기록, `RecoveryRoleRestoreSpec` ⑥ |
| m-2 | §4.3.1 상태 표·§4.3.6 재발급 전이·§4.7.5 사건 표에 `PENDING_ENROLLMENT` → `PENDING_ENROLLMENT` 재발급(2인: 근거를 새 approvalId로 교체, 1인: `pending_basis`를 새 발급 ID로), `EnrollmentCodeSpec` 재발급 뒤 새 코드 성공·옛 코드 거부 |
| m-3 | §4.7.3 0단계(승인 행 불변 열 비잠금 읽기) → 회차 잠금 → 1단계 승인 행 잠금으로 재번호, §4.13 표 일치. §4.3.6 등록 완료 TX 0단계 비잠금 선판정 → 회차 → 계정 → 잠금 아래 재확인, 불일치 rollback |
| m-4 | §4.2.2 열 단위 UPDATE만 있는 표의 `FOR UPDATE` 허용 여부를 S0 실측 항목(결과 대기)으로 표시, 실패 시 대안은 Coordinator 판단 |
| n-1 | §4.3.6 대기 값 AAD: 문맥 태그 `paycore.admin.enroll-pending.v1`·키 버전·길이 접두 인코딩·용도 태그(비밀번호 해시/TOTP 시크릿), `EnrollmentPendingValueSpec` 저장 TOTP 문맥 AAD 복호 실패 |
| n-2 | §4.13 승인 요청 생성 순서 변경: INSERT 먼저 → 계정 잠금 → 재검사 |
| n-3 | §4.9.2 회차 행 없을 때 직렬화 한계와 수용 근거(권한 증가 방향만, 오프라인·감사, 회차 열린 뒤는 직렬화) |
| n-4 | §4.9.2 작업 B(및 A) 코드 만료 = 회차 만료 등식, ⑦ 시험 |
| 새 미결 | 없음(m-4 S0 실측 결과에 따른 대안 선택은 Coordinator 판단) |

### 16.3 rev 0.5 변경 기록(2026-10-24 — spec-4 PASS_WITH_FIXES, Coordinator 판정 = 리뷰 권장안, 새 사용자 질문 없음)

| 검토·판정 | 반영 |
|---|---|
| m-1 | §4.3.6 확정 자격 없는 대상의 2인 재발급: 비밀번호 해시·TOTP 암호문 중 하나라도 없으면 대기 사유 `ENROLL`(근거 = 새 approvalId)로 두 요소 모두 설정, 승인 종류 추가 없음·역할 정지 유지, `EnrollmentPurposeFlowSpec` 2행 |
| n-1 | §4.7.3 0단계/1단계 불변 열(종류·대상·부여 역할) 불일치 = 전체 rollback·500 `INTERNAL_INTEGRITY`·별도 TX 감사·지표·경보, `ApprovalImmutableColumnIntegritySpec` 강제 불일치 주입 |
| n-2 | §4.9.2 이벤트 재생 규칙(정지 → 정지 SUSPEND 재append 유효, 원인 = 최신 SUSPEND), 원인이 회차로 바뀐 뒤 이전 재설정 근거 `ROLE_REACTIVATE` 거부, 시험 ⑥ 고정 |
| n-3 | §4.9.2 직렬화 한계 근거 (3) 문구: 잠금 대기 상한 안에서만 이론상 가능, 방향은 관리자 증가 |
| n-4 | §4.13 승인 요청 생성의 1062·재검사 실패 거부 감사를 rollback된 주 TX 밖 별도 TX로 |
| 새 미결 | 없음 |

---

## 17. S0 실측 결과(2026-10-24 KST, 2단계 Implementer — 자기 완료 판정 아님)

- 기준: commit `e53ee4b`(branch `feat/foundation-setup`), 명세 rev 0.5(sha256 `9a58cf31…5d60`, S0 기록 전 값). 환경: WSL Ubuntu, OpenJDK 25.0.4.1, Gradle Wrapper 9.8.0, Docker MySQL `mysql:8.4.11`, osv-scanner 2.6.0.
- 방법: 모든 실측은 **저장소 밖 임시 디렉터리**(`/tmp/s0-*`)에서 했다. 저장소의 코드·빌드 파일·catalog·harness는 바꾸지 않았다(의존성 추가 전 보고 — §10 S0). Windows npm 사용 안 함(WSL에 Node 없음 — 아래 Node를 내려받아 사용).
- 14일 기준일(cutoff): **2026-10-10**(조회일 2026-10-24 − 14일). "안정판" = pre-release 없음·deprecated 아님.
- 네트워크 다운로드 출처: `https://nodejs.org/dist/`(index.json·Node 압축·SHASUMS256.txt·.asc), `https://raw.githubusercontent.com/nodejs/release-keys/HEAD/keys/<fpr>.asc`(서명 키), `https://registry.npmjs.org/`(패키지 메타데이터·tarball), `https://plugins.gradle.org/m2/`·`https://api.github.com/repos/node-gradle/gradle-node-plugin/releases`(플러그인), `https://repo1.maven.org/maven2/`(Boot 4.1.1 BOM·Security/Session 소스 jar), Docker Hub `mysql:8.4.11`(기존 사용 이미지).

### 17.1 버전 고정 후보(BOM 밖)

| 항목 | 후보 | 게시일 | 조회 출처·근거 | 비고 |
|---|---|---|---|---|
| Node.js | **24.21.0**(Krypton, Active LTS) | 2026-09-07 | `https://nodejs.org/dist/index.json`(2026-10-24 조회, `lts: "Krypton"`) | 26.x는 아직 LTS 아님(index `lts: false`) → 제외. cutoff 이후 24.22.0(10-14)·**24.22.1(10-20, security)** 있음 → §17.10 Q-S0-1 |
| Node 압축 SHA-256 | `node-v24.21.0-linux-x64.tar.gz` = `6e1db87ef58b8819e5d5402eff1536491b18edd8eb7bee5ef7897876e88dc5ff`(`.tar.xz` = `fd8e59d5a511510f6a298afb548f18c7d2b1be404d8b4a27d94fbe49f56cb2d6`) | — | `SHASUMS256.txt` 대조 OK, `SHASUMS256.txt.asc` GPG **Good signature**, 서명 키 `5BE8A3F6C8A5C01D106C0AD820B1A390B168D356`(nodejs/release-keys) | node-gradle은 `.tar.gz`를 받는다(실측 파일명 `node-24.21.0-linux-x64.tar.gz`). 동봉 npm 11.19.0을 쓰고 npm을 따로 설치하지 않는다 |
| (대안) Node 24.22.1 | `node-v24.22.1-linux-x64.tar.gz` = `eb6728e77c576a35162c122c45ccadf224d89ff00abc8dae46cc2897c6002d7c` | 2026-10-20 | 같은 방식, GPG Good signature(같은 키) | 14일 미달. Q-S0-1 결정용으로만 기록 |
| node-gradle 플러그인 `com.github.node-gradle.node` | **7.1.0** | 2024-09-27 | plugins.gradle.org `maven-metadata.xml`(latest/release = 7.1.0), POM `Last-Modified: Fri, 27 Sep 2024`, GitHub release `2024-09-27T13:16:57Z` | 최신 GA가 2년 전 — Gradle 9.8.0 호환은 §17.3 실측으로 확인 |
| react / react-dom | **19.3.0** | 2026-09-09 | `registry.npmjs.org/<pkg>` `time` 필드(2026-10-24 조회) | latest 19.3.1(10-14) 제외 |
| vite | **8.3.4** | 2026-10-08 | 같음 | latest 8.3.5(10-21) 제외. Vite 8은 번들러 rolldown(네이티브 binding) 사용 — esbuild·rollup 미설치 |
| vitest | **5.0.3** | 2026-09-30 | 같음 | peer `vite ^6.4 \|\| ^7 \|\| ^8` 만족, engines `node ^22.12 \|\| ^24` |
| typescript | **7.0.2** | 2026-07-08 | 같음 | 네이티브(Go) 컴파일러, `@typescript/typescript-linux-x64` optionalDependency, `tsc -p` 동작 실측 |
| @vitejs/plugin-react | **6.1.2** | 2026-10-05 | 같음 | peer `vite ^8` |
| @types/react / @types/react-dom | **19.3.0** | 2026-09-09 | 같음 | |
| jsdom | **30.1.2** | 2026-10-04 | 같음 | engines `node ^24.15.0` 만족. 전이 의존 undici → §17.10 Q-S0-2 |
| @testing-library/react | **16.3.3** | 2026-08-27 | 같음 | |
| @testing-library/jest-dom | **7.0.1** | 2026-08-09 | 같음 | |
| @testing-library/user-event | **14.6.7** | 2026-09-02 | 같음 | latest 14.6.8(10-13) 제외 |
| (참고, 지금 고정 안 함) react-router 8.4.0(09-15), eslint 10.12.0(10-02) | — | — | 같음 | 라우터는 S7 필요 시, ESLint는 §17.4 결정상 불필요 |

- 호환 실측: 위 조합으로 `npm install --package-lock-only` ERESOLVE·engine 경고 없음, lockfileVersion 3, 147 entries 전부 `resolved: https://registry.npmjs.org/…` + `integrity: sha512-…`. `npm ci --ignore-scripts` → `tsc -p` exit 0, `vitest run` 2 passed, `vite build` exit 0(산출 `index.html`에 inline `<script>` 0개, `<script type="module" crossorigin src="/assets/…">`만).
- **전이 의존과 14일 규칙(새 발견)**: 직접 의존만 고정하면 lockfile 147개 중 **23개가 cutoff 이후 게시본**(예: `rolldown 1.2.15`·binding 15종 10-21, `postcss 8.5.31` 10-18, `undici 8.12.3` 10-19). `npm install --package-lock-only --before=2026-10-10T23:59:59Z`로 만들면 **0개**이고 `npm ci`·tsc·vitest·build 모두 통과(rolldown 1.2.13). → lockfile 생성 절차에 `--before=<cutoff>`를 고정 기록하는 것을 권장(생성 명령은 개발자 작업, CI는 `npm ci`만). 단 이 lockfile은 osv에서 undici 취약점이 나온다(Q-S0-2).
- `--ignore-scripts`(R-3): lockfile의 `hasInstallScript`는 `fsevents`(macOS 전용 optional, Linux 미설치)뿐. rolldown·TypeScript 네이티브·lightningcss 바이너리는 optionalDependencies 플랫폼 패키지로 설치되어 스크립트 없이 동작(실측). 허용 목록 rebuild 불필요.

### 17.2 m-4 열 단위 UPDATE만 있는 표의 잠금 읽기(MySQL 8.4.11 실측)

설계 모양의 표 4개(`admin_account` 열 UPDATE `status, version` / `admin_approval_request` `status, decided_by, version` / `admin_recovery_episode` `closed_at` / `admin_enrollment_code` `used_at, revoked_at`)와 append-only 대조 표(`admin_audit_event` SELECT·INSERT)를 만들고, 권한은 **role에 주고 사용자의 DEFAULT ROLE**로 활성화(`CURRENT_ROLE()` = 그 role 확인 — 저장소 runtime role 방식과 같음).

| 시도 | 결과 |
|---|---|
| 네 표 각각 `SELECT … WHERE pk=? FOR UPDATE` | 성공(exit 0) |
| `admin_account` `FOR SHARE` | 성공 |
| 다른 연결이 같은 행 `FOR UPDATE` 보유 중 두 번째 `FOR UPDATE`(`innodb_lock_wait_timeout=2`) | **1205** — 잠금이 실제로 걸림 |
| 원자 소비 `UPDATE admin_enrollment_code SET used_at=? WHERE code_hash=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?` | 성공 |
| 불변 열 UPDATE(`login_name`, `target`) | **1143** |
| 대조: SELECT·INSERT만인 표 `FOR UPDATE` / SELECT만인 사용자 `FOR UPDATE` | **1142** "SELECT with locking clause command denied" |

결론: 설계의 잠금 경로는 성립한다(권한 확대 불필요). S2 `AdminDatabaseAccountSpec`에 같은 행(열 UPDATE 표 잠금 성공·append-only 표 잠금 1142)을 넣는 것을 권장한다.

### 17.3 Node·Gradle(F-8·F-9) 실측(저장소 밖 probe 프로젝트, Gradle 9.8.0)

| 시도 | 결과 |
|---|---|
| 플러그인 기본(`distBaseUrl` 기본값) + settings `FAIL_ON_PROJECT_REPOS` | **실패**: "Build was configured to prefer settings repositories over project repositories but repository 'Node.js' was added by plugin 'com.github.node-gradle.node'" — F-8 재현 |
| `node { distBaseUrl.set(null as String?) }` + settings `exclusiveContent { ivy("https://nodejs.org/dist/", pattern `v[revision]/[artifact](-v[revision]-[classifier]).[ext]`, `metadataSources { artifact() }`); filter { includeModule("org.nodejs","node") } }` | `nodeSetup`·`npmInstall`(`npmInstallCommand = "ci"`, args `--ignore-scripts`)·`NpmTask`(`exec -- tsc`, `exec -- vitest run`) 모두 성공, BUILD SUCCESSFUL. `--warning-mode=all`에서 **플러그인 deprecation 경고 0건**(probe 스크립트 자신의 `by creating` 경고 1건뿐) |
| F-9 SHA-256 검사: 같은 좌표 `org.nodejs:node:24.21.0:linux-x64@tar.gz`를 별도 configuration으로 해석해 SHA-256 비교 task | 값 일치(`6e1db87e…c5ff`). 플러그인과 같은 좌표라 같은 Gradle 캐시 파일을 본다 |

- 설정 캐시(configuration cache)는 저장소가 켜지 않았으므로 측정하지 않았다(S1에서 기존 빌드 설정 그대로).

### 17.4 Vitest JUnit·집계(m-9)

| 항목 | 실측 |
|---|---|
| 출력 | `reporters: ['default','junit']`, `outputFile.junit = build/test-results/frontend/TEST-vitest.xml` → 파일 1개 |
| 루트 요소 | **`<testsuites name tests failures errors time>` — `timestamp`·`skipped` 속성 없음** |
| suite 요소 | 파일별 `<testsuite … timestamp="2026-10-24T08:39:17.104Z" … skipped="N">`(UTC `Z`) |
| `it.todo`·`it.skip` | 둘 다 `<testcase>`에 `<skipped/>`, suite `skipped=2`. vitest exit **0** |
| `.only` + `allowOnly:false` | exit 1(실패 처리) |
| 시험 0건 + `passWithNoTests:false` | exit 1, 그래도 `tests="0"`인 빈 `<testsuites>` 보고서를 쓴다 |
| **현재 `harness/scripts/test-summary.py`에 실제 투입** | 정상 보고서 → `timestamp=None` → **"older than --not-before" FAIL(exit 1)**. todo/skip 보고서 → `skipped=0`으로 집계(루트에 skipped 없음) — 형식 문제만 고치고 루트를 읽는 방식을 두면 **skip 감지가 빠진다** |

- todo·skip 금지(D-36): 별도 ESLint 없이도 TEST-SUMMARY가 suite 단위 skipped를 세면 FAIL로 잡힌다(fail-closed). Gradle `frontendTest` 자체도 실패시키려면 Vitest 사용자 정의 reporter(`onTestRunEnd`에서 skipped·todo > 0이면 실패) — 새 npm 의존 없음. 결정은 Q-S0-3과 함께.

### 17.5 Security 7.1.1·Spring Session 4.1.1(Boot 4.1.1 BOM: Framework 7.0.9, Security 7.1.1, Session 4.1.1) — 소스와 실측

- `CsrfConfigurer.spa()`(spring-security-config 7.1.1 소스 234행): `csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()`, `requestHandler = new SpaCsrfTokenRequestHandler()`(private static final 클래스, 397행). → 토큰 저장소가 **쿠키**라 D-10(세션 저장 동기화 토큰 + 쿠키는 전달만)과 다르다. 쓰지 않는다. 직접 구성: `HttpSessionCsrfTokenRepository`(또는 문자열 값 저장 구현) + 응답에 `__Host-PCADMIN-XSRF` 쿠키를 쓰는 filter + 헤더는 raw 값·폼은 XOR 해석하는 handler(공개 클래스 `XorCsrfTokenRequestAttributeHandler`·`CsrfTokenRequestAttributeHandler` 조합).
- **SecurityContext 직렬화(실측)**: `HttpSessionSecurityContextRepository` 저장 시 `SPRING_SECURITY_CONTEXT` 속성이 Java 직렬화(`ACED0005`, 799바이트). 모든 속성이 Java 직렬화. DB의 그 바이트를 손상시키면 다음 요청이 **HTTP 500**(`SerializationFailedException` ← `StreamCorruptedException`), 세션 행은 지워지지 않아 만료까지 매 요청 500, `/error` 재처리도 세션 filter에서 다시 실패해 Tomcat 기본 오류 페이지가 나간다. Security 버전 올림 시 `serialVersionUID` 변화도 같은 경로.
- 권장(D-08 확정 방향, S4 구현 결정): 세션에는 **문자열 값만** 저장(actor ID·인증 단계·세대·`security_version`·`authenticatedAt`·CSRF 토큰 문자열·등록 진행 값 암호문 base64), `SecurityContextRepository`는 요청마다 그 값과 계정 행으로 `SecurityContext`를 만든다(세션에 저장 안 함). 추가로 `JdbcIndexedSessionRepository.setConversionService`로 String만 직렬화·역직렬화하는 변환기를 두면 임의 Java 역직렬화 경로가 없어진다(`org.springframework.core.convert` 타입 — §3.3 금지 목록 밖). 역직렬화 실패는 세션 폐기 + 401로 바꾸는 시험(`ErrorNonDisclosureSpec`·`SessionTableSpec`)을 S4에 추가 권장.
- **SaveMode(n-8, 실측 — 같은 세션 두 연결, 서버 측 시각 기록)**:
  - `ON_SET_ATTRIBUTE`(기본): R1이 세션을 읽고 1.5초 대기 중 R2가 `b`·`x`를 바꿈 → 최종 `a=1, b=2, x=fromR2` — **다른 속성 손실 없음**. 같은 속성 `t`를 둘 다 쓰면 **늦게 끝난 R1 값(`R1-slow`)이 최종** — 마지막 쓰기 승리.
  - `ALWAYS`: 같은 시나리오에서 손실이 **없었다**. 원인(소스 975~995행): DB에서 읽은 세션은 `new JdbcSession(delegate, pk, false)` 생성 **뒤에** 속성을 넣으므로 생성자의 ALWAYS 표시가 빈 속성 집합에 적용된다 → 읽은 세션에서는 ALWAYS가 ON_SET과 같게 동작. ALWAYS에 기대지 않는다.
  - 결론: `save-mode`는 기본 `ON_SET_ATTRIBUTE`를 명시, `ON_GET_ATTRIBUTE`·`ALWAYS` 금지. CSRF 토큰은 `GET /api/auth/csrf`(세션에 없을 때만)·세션 ID 재발급 시점에서만 쓴다. 같은 세션의 두 로그인 단계 병렬 요청은 계정 행 잠금·세대가 권위(§4.4.1 문장 유지).
- Boot 4.1.1 `JdbcSessionProperties`: `table-name`, `cleanup-cron`, `flush-mode`(기본 ON_SAVE), `save-mode`(기본 ON_SET_ATTRIBUTE), `initialize-schema`. 자동 구성은 앱의 `DataSource` bean을 쓴다(`@SpringSessionDataSource` 없으면 기본 DataSource — ADMIN DB 하나라 그대로).

### 17.6 n-D `security.session`에 DataSource·spring-jdbc 타입 필요 여부 — **필요 없음**

- 실측: `admin_session` + `admin_session_attributes`(소문자) 표를 만들고 `spring.session.jdbc.table-name=admin_session`만 주면 **500**(`Table 'test.admin_session_ATTRIBUTES' doesn't exist`, MySQL `lower_case_table_names=0`). 소스: 모든 속성 질의가 `%TABLE_NAME%_ATTRIBUTES` 고정.
- 해결 실측: `SessionRepositoryCustomizer<JdbcIndexedSessionRepository>` bean에서 `setCreateSessionAttributeQuery`·`setGetSessionQuery`·`setUpdateSessionAttributeQuery`·`setDeleteSessionAttributeQuery`·`setListSessionsByPrincipalNameQuery` 5개를 `admin_session_attributes`로 교체 → 저장·조회·SaveMode 시험 모두 동작. 이 구성 코드가 참조한 타입은 `org.springframework.session..`과 `String`뿐(`javax.sql.DataSource`·`org.springframework.jdbc..` 없음). 위 §17.5의 변환기를 추가해도 `org.springframework.core.convert..`만 더해진다.
- 따라서 §3.3 교체 규칙은 그대로 둔다(한정 허용 행 추가 없음). 표 이름은 §4.2.2대로 `admin_session`·`admin_session_attributes`(권한 procedure 규칙 `^[a-z][a-z0-9_]{0,63}$` 만족), 열 이름은 Spring Session 기본 대문자(MySQL 열 이름은 대소문자 무시 — 권한은 표 단위라 열 이름 규칙 해당 없음).

### 17.7 `~/.npmrc`·`npm_config_*` 영향(m-8 — 영향 있음)

| 입력 | `npm config get` 결과(프로젝트 `.npmrc`: registry 고정·ignore-scripts·engine-strict) |
|---|---|
| 프로젝트 `.npmrc`만 | `registry=https://registry.npmjs.org/ ignore-scripts=true strict-ssl=true engine-strict=true` |
| `~/.npmrc`가 registry·ignore-scripts·strict-ssl·script-shell 지정 | 프로젝트가 정한 registry·ignore-scripts는 유지, **`strict-ssl=false`·`script-shell=/tmp/evil.sh`는 적용됨**(프로젝트가 정하지 않은 키) |
| 환경 `npm_config_registry`·`npm_config_ignore_scripts=false`·`npm_config_engine_strict=false` | **셋 다 프로젝트 값을 덮음**. 대문자 `NPM_CONFIG_REGISTRY`도 적용 |
| 환경 `npm_config_ignore_scripts=false` + CLI `--ignore-scripts` | CLI가 이김(`true`) |
| `env -i` + `npm_config_userconfig=<빈 파일>` | 프로젝트 값만 |
| `npm_config_registry=https://env.example.invalid/` + `npm ci` | lockfile `resolved`가 registry.npmjs.org인데도 **env registry로 tarball 요청**(debug log `GET https://env.example.invalid/punycode/-/punycode-2.3.1.tgz … ENOTFOUND`) — integrity는 그대로 검사되지만 다운로드 출처가 바뀐다 |
| global config | 내려받은 Node 안 `etc/npmrc`(Gradle이 관리하는 배포판 — 사용자 입력 아님) |

- 권장(S1 구현): `paycore.frontend` npm task는 환경에서 `npm_config_*`·`NPM_CONFIG_*`를 모두 제거하고 `npm_config_userconfig=<build 안 빈 파일>`을 설정, argv에 `--ignore-scripts`를 명시(env보다 강함). 프로젝트 `.npmrc`에 `strict-ssl=true`도 고정. VERIFY-GATE 환경 snapshot에는 `npm_config_*` **이름 목록**(값 제외)과 `~/.npmrc` 존재·SHA-256만 기록(verify-gate.py 변경 — S1, harness 시험 먼저).

### 17.8 `CREATE_NEW` + 0600·TTY(WSL, JDK 25 실측)

| 위치 | 결과 |
|---|---|
| ext4 `/tmp`(umask 0022, 0000 둘 다) | `Files.createFile(p, rw-------)` → `rw-------`. 같은 경로 `CREATE_NEW` 재시도 → `FileAlreadyExistsException`. dangling symlink 경로에 `CREATE_NEW` → `FileAlreadyExistsException`(링크 따라가지 않음) |
| drvfs `/mnt/c/...` | 생성은 성공하지만 **권한 `rwxrwxrwx`**(POSIX 권한 속성 무시, 예외 없음) |
| `System.console()` | TTY 없음 → null. pty에서 표준 스트림 모두 연결 → `console != null`·`isTerminal() = true`. pty지만 stdout 또는 stdin 리다이렉트 → null |

- 권장(S6): 생성 직후 `Files.getPosixFilePermissions`가 정확히 `rw-------`인지 확인, 아니면 파일 삭제 후 DB 변경 전 거부(고유 비영 exit). 파일은 DB TX **전**에 만들고(실패 시 DB 무변경) 코드는 커밋 뒤 쓴다. TTY 판정은 `console != null && console.isTerminal()`(`-Djdk.console` 변경 대비).

### 17.9 npm CVE 사전 실측(cve-scan 설계 입력 — 하네스 변경은 S1)

- osv-scanner 2.6.0 `scan source --config=harness/osv-scanner.toml -L package-lock.json`은 lockfile entry 147개를 **모두** 센다("found 147 packages") — dev 144개·optional 플랫폼 47개 포함(기본으로 dev 제외 없음). → cve-scan 패키지 수 일치 판정의 기대값 = lockfile `packages`의 루트 제외 entry 수.
- 직접 의존만 고정한 lockfile: 취약점 0건(osv exit 0, 2026-10-24T08:47Z). `--before` lockfile: **undici 8.11.2(dev, jsdom 경유)** GHSA-xjj6-f33q-929c(CVSS 7.5)·GHSA-4qv8-2p9j-2685(5.9)·GHSA-f72v-6w45-q472(5.9), 수정 8.12.2(osv exit 1, 2026-10-24T08:54Z). 2단계 override lockfile(Q-S0-2 A): "found 147 packages"·"No issues found"(exit 0, 08:59Z).

### 17.10 Coordinator 판단 필요(구현 보류 — 금융 의미 아님, 버전·게이트 기준 정책)

| ID | 충돌 | 선택지 | Implementer 권장 | 막히는 작업 |
|---|---|---|---|---|
| Q-S0-1 | Node 14일 규칙 → 24.21.0이지만, 24.22.1(2026-10-20, 14일 미달)이 High CVE(CVE-2026-106069·106072 tls 비정규 IP, CVE-2026-105482 dns null byte, CVE-2026-105483 tls IP 정규화 회귀, CVE-2026-111497 brotli 등 — 출처 `https://nodejs.org/en/blog/release/v24.22.1`)를 고친다. Node는 빌드 도구(npm ci TLS·vite build)이며 운영 runtime·SBOM 밖 | A) 24.21.0(규칙 준수, 알려진 취약점 수용) / B) 24.22.1을 TECH-BASELINE §1.1과 같은 "보안 patch 예외"로(같은 24.x 라인, 근거·제거 조건 기록) / C) 24.22.1이 14일을 넘는 2026-11-03까지 S1 Node 고정 보류 | **B**(빌드 중 registry TLS 경로에 High 수정 포함, 선례와 같은 등급의 예외) | S1 catalog `node`·SHA 값 |
| Q-S0-2 | 전이 의존 14일 규칙을 지키는 `--before` lockfile에 undici 8.11.2 취약(High 1·Medium 2), 수정본 8.12.2(10-15)·8.12.3(10-19)은 14일 미달. D-ADMIN1-007상 devDependencies 취약점도 FAIL | A) `package.json` `overrides: { "undici": "8.12.2" }`를 보안 patch 예외로(GHSA·제거 조건 = jsdom 범위가 고친 버전 해석 + 14일 경과) / B) `--before` 없이 직접 의존만 고정(전이 23개 14일 미달 수용) / C) 2026-10-29(8.12.2 + 14일) 이후 lockfile 생성 | **A**(`--before` + 최소 수정 버전 override, 예외 목록을 TECH-BASELINE에 기록). 실측 주의: `--before`와 override를 **한 번에** 쓰면 `ETARGET`(cutoff 이후 버전 해석 불가). **2단계**(① override 없이 `npm install --package-lock-only --before=<cutoff>` → ② override 추가 후 `--before` 없이 `npm install --package-lock-only`)로 만들면 lockfile 차이는 `undici 8.11.2 → 8.12.2` 한 줄, cutoff 이후 게시본은 undici 1개뿐, `npm ci`·tsc·vitest(2 passed)·build 통과, osv "No issues found"(exit 0, 2026-10-24T08:59Z) | S1 lockfile·CVE-SCAN |
| Q-S0-3 | test-summary.py가 루트 요소만 읽어 Vitest `<testsuites>` 보고서를 항상 stale FAIL·skip 미집계. test-summary.py는 이번 허용 경로 밖, 명세는 "test-summary 기준은 바꾸지 않음" | A) test-summary.py가 루트가 `<testsuites>`면 **자식 suite마다** timestamp·counts를 읽도록 파서만 확장(기준 불변 — 0건·stale·skip·하한 판정 동일, `test_test_summary.py` RED 먼저: `<testsuites>` 루트 정상·stale·skip·0건·자식 timestamp 누락) / B) 프론트에 사용자 정의 Vitest reporter를 두어 suite마다 Gradle 형식 `TEST-<name>.xml`(`<testsuite>` 루트) 작성 — test-summary 무변경 | **A**(판정 로직을 harness 한 곳에 두고 자기 시험으로 고정. B는 프론트 코드 정확성에 게이트가 의존). A 선택 시 S1 허용 경로에 `harness/scripts/test-summary.py` 추가 필요 | S1 frontendTest 보고서·commands.json TEST-SUMMARY |

- commands.json 제안(직접 수정 안 함 — §13-3, 값은 S1 실측 뒤 확정): TEST-SUMMARY argv에 `apps/internal-admin/build/test-results/frontend` 추가, GRADLE-CHECK `outputMustMatch`에 `:apps:internal-admin:frontendTest`·`:apps:internal-admin:frontendTypecheck`·`:apps:internal-admin:frontendLockfileCheck` 추가, 프론트 보고서 별도 최소 건수(S1 실측값), 하한 2460은 S1 실측 합계로만 올림.
- **판정(Coordinator 2026-10-24)**: Q-S0-1 → B, Q-S0-2 → A(8.11.x patch가 있으면 우선 — 없음 확인, 8.12.2), Q-S0-3 → A(test-summary.py 허용 경로 추가). 그 밖의 권장안(npm 격리, 전이 의존 `--before`, `distBaseUrl=null` + ivy, Node SHA 검사, Vitest 설정)도 채택. 반영은 §18.

---

## 18. S1 구현 기록(2026-10-24 KST, 2단계 Implementer — 자기 완료 판정 아님)

### 18.1 판정 반영
- Node **24.22.1** 보안 patch 예외(TECH-BASELINE §9.1 — SHA-256·GPG·해소 권고·제거 조건 표). catalog `node`·`node-sha256-linux-x64`.
- undici **8.12.2** override: npm registry의 8.11.x는 8.11.0~8.11.2뿐(2026-10-24 조회) → 같은 minor 수정본 없음. `frontend/lockfile-policy.json`(cutoff·근거·제거 조건)과 `frontendLockfileCheck`(override ↔ 근거 대조)로 고정. 2단계 절차는 Gradle task `frontendLockfileRegenerate`로 스크립트화(재현 실측: 결과 lockfile = S0 2단계 probe와 entry·버전 동일, cutoff 이후 게시본은 undici 1개뿐).
- test-summary.py: 루트 `<testsuites>`면 자식 `<testsuite>`마다 timestamp·counts, 자식 없음·다른 루트는 FAIL. 판정 기준 불변.

### 18.2 구현 결정(가역 — 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| D-45 | node-gradle plugin은 Node 배포판 내려받기·압축 해제(`nodeSetup`)에만 쓰고, npm·tsc·vitest·vite는 `Exec` task + 격리 환경(`setEnvironment` 교체)으로 실행. argv 고정, `npm ci --ignore-scripts` | plugin의 NpmTask는 Gradle 프로세스 환경을 물려받아 `npm_config_*`를 제거할 수 없다(§17.7 격리 요구) |
| D-46 | build-logic Kotlin 클래스 패키지 `com.paycore.buildlogic.frontend` | `.gitignore`의 `**/build/`가 `com/paycore/build/` 디렉터리를 무시해 fingerprint 가드(`build-logic/src/**`의 무시 파일 거부)에 걸린다(발견) |
| D-47 | `frontendLockfileCheck`(오프라인): lockfile v3·registry 고정·sha512·루트 의존 = package.json·정확 버전·`engines.node` = catalog·`.npmrc` 필수 줄·override는 정책 파일 근거 필수 | §4.11.2, Q-S0-2 |
| D-48 | CSRF: `HttpSessionCsrfTokenRepository`(header `X-XSRF-TOKEN`) + 자체 `SpaCsrfTokenRequestHandler`(헤더 = 원문, 그 밖 = XOR) + `XsrfCookieFilter`(인증된 `/api/` 요청에서만 원문 토큰을 `__Host-PCADMIN-XSRF` 쿠키로 전달) | D-10. Security `csrf.spa()`는 쿠키 저장이라 미사용(§17.5). 토큰 저장 형식(세션 문자열 값)·Spring Session JDBC·`GET /api/auth/csrf`·세션 ID 재발급 시 재발급은 S4 |
| D-49 | `/assets/**` 별도 `SecurityFilterChain`(STATELESS·cache 헤더 끔·GET만), 앱 체인은 ERROR/FORWARD dispatch 허용·`/api/**` 인증·그 밖 GET 허용·나머지 거부 | n-3 |
| D-50 | 요청 캐시 비활성 | 익명 401마다 세션이 생기지 않게(n-1) |
| D-51 | SPA fallback: 소문자·숫자·하이픈 3단계 경로만, 첫 단계 `api`·`assets`·`error` 제외, `forward:/index.html` | 없는 API·asset을 index로 바꾸지 않음 |
| D-52 | 보안 거부 응답은 JSON `{"error":"UNAUTHENTICATED"|"FORBIDDEN"}`, Whitelabel 끔·stack/message 미포함 설정. 404·500 본문 JSON 고정은 S4(`ErrorNonDisclosureSpec`) | §4.10 |
| D-53 | 프론트 `apiFetch`: `/api/` 외 경로 거부, `credentials:'same-origin'`·`redirect:'error'`·`cache:'no-store'`, 상태 변경 요청은 XSRF 쿠키 값을 헤더로(쿠키 없으면 보내지 않음), 서버 오류 code는 알려진 목록만 신뢰 | §4.10, D-10 |
| D-54 | Permissions-Policy `camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=(), display-capture=()` | §4.10(값 미정 → 기능 거부 기본) |

### 18.3 RED → GREEN·핵심 변이(모든 변이는 원본 백업 후 복원, 복원 hash 확인)

| 대상 | RED(시각·원인) | GREEN | 변이(→ 결과) |
|---|---|---|---|
| test-summary.py | 09:06:43Z — 18건 중 3 실패(Vitest 보고서 stale, 합산 실패, skip이 0으로 집계) | 09:07:07Z 18/18 | M1 루트만 읽기·M2 suite 없음 허용·M3 첫 suite timestamp만·M4 알 수 없는 루트 허용 → 전부 RED(M4는 처음 생존 → 시험 강화 후 RED) |
| fingerprint.sh | 09:08:19Z — 무시된 프론트 소스 통과 | 09:08:39Z 16/16 | F1 src 가드 제거·F2 루트 설정 가드 제거·F3 프론트 전체 가드(node_modules 오탐) → 전부 RED |
| verify-gate.py | 09:09:04Z — `npmEnvNames` 없음(2 error) | 09:09:49Z 19/19 | G1 npm 값 hash 기록(처음 생존 → "hash도 금지" 시험 추가 후 RED)·G2 대소문자 구분·G3 `~/.npmrc` 미기록 → 전부 RED |
| cve-scan.py | 09:12:12Z — npm 함수·`FRONTENDS`·fixture 상수 없음 | 09:12:47Z(npm 15/15) | C1 합계만 요구(dev canary 미검출 허용)·C2 dev 제외 옵션·C3 개수만 비교·C4 중복 검사 제거·C5 exit 1 미요구·C6 fixture 구성 미검사·C7 경로 미구분(처음 생존 → 다른 경로 결과 시험 추가 후 RED) → 전부 RED |
| 프론트 Vitest | 09:24:31Z — 모듈 없음(3 파일 실패) | 09:25:50Z 13/13, 실제 보고서 test-summary PASS | FM1 CSRF 헤더 미부착·FM2 `credentials:'include'`·FM3 서버 문자열 신뢰·FM4 쿠키 없이 전송 → 전부 RED |
| frontendLockfileCheck | — (새 task) | 통과 | L1 비공식 registry·L2 범위 버전·L3 override 근거 불일치·L4 `ignore-scripts` 삭제·L5 sha1 → 전부 RED |
| npm 격리 | — | 적대 env(`npm_config_registry=https://env.example.invalid/`·`NPM_CONFIG_IGNORE_SCRIPTS=false`·`npm_config_strict_ssl=false`) + 빈 cache에서 `frontendInstall` 성공(09:29:34Z) | I1 상속 환경 유지 → `env.example.invalid`로 요청·실패(09:31:26Z) |
| internal-admin Kotlin | 09:34:53Z — 40건 중 15 실패(console 없음·전부 인증 필요·XSRF 쿠키 없음·기본 CSRF 헤더 이름) | 09:37:06Z 40/40 | B1 쿠키 저장 double-submit 저장소·B2 익명에도 쿠키·B4 요청 캐시 켜짐·B5 fallback 예외 제거·B6 immutable 제거·B7 헤더 원문 해석 제거 → RED. **B3 assets 체인 cache 헤더 유지는 생존(등가)**: 자원 처리기가 먼저 `Cache-Control`을 쓰면 Security `CacheControlHeadersWriter`는 덮지 않는다 — 명세 문언대로 명시적 disable 유지 |

### 18.4 실행 결과(S1 끝 체크포인트)
- 전체 `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(WSL 분리 실행, 09:42:47Z 시작, 19m45s): **BUILD FAILED — institution-simulator 1건**(`SimulatorApiContractSpec` "responses never echo card numbers": 무작위 `state` 값 `8c67edc1fb104111…`에 "4111"이 우연히 포함 — 기존 확률적 결함, S1 변경과 무관, 허용 경로 밖이라 미수정). 같은 모듈 `--rerun-tasks` 재실행(10:03~10:07Z) 210/210 PASS.
- 모듈별(--not-before 09:42:47Z): master-api 320, payment-api 1766, financial-batch 26, internal-admin 40, institution-simulator 210(재실행 PASS), test-support 112 → Kotlin 2474, Vitest 13, **합계 2487**.
- HARNESS-SELFTEST: 127건 중 2 실패 — 둘 다 commands.json 반영 대상(`frontendTest` 출력 규칙 미등록, 하한 97 ≠ 시험 수 127). 제안 diff를 복사본에 적용하면 registry 시험 13/13 OK.
- CVE-SCAN(10:07~10:08Z): PASS — 5개 모듈 + 프론트(scanned 147, npm canary 6건 검출, 취약점 0). internal-admin runtime 구성요소 52개(Thymeleaf 제거 전 58).
- FINGERPRINT: 작업 트리 그대로는 **exit 1** — 대체된 추적 파일 3개(TechSmokePageController·TechSmokePageSecuritySpec·`templates/techsmoke/page.html`) 삭제가 아직 stage되지 않아 "tracked file missing"(설계된 감지). 실제 index는 건드리지 않고 index 사본(`GIT_INDEX_FILE`)에 삭제만 반영하면 exit 0(files 1551, 무시된 보호 경로 파일 없음 — `node_modules/`·`.gradle/` 미검출). 커밋 시 `git rm`으로 해소된다.
- 실행 비트: Windows 경로(`\\wsl.localhost`) 편집이 `harness/scripts/*.py`·`fingerprint.sh`의 실행 비트(755)를 644로 바꿨다(발견) → HEAD와 같은 755로 복원, `git diff --summary`에 mode 변경 없음 확인.

### 18.5 S1 범위 밖으로 남긴 것
- S2: `TestInfrastructureSpec`(F-2)·ArchUnit DB 규칙 교체(F-1), ADMIN DB. S4: Spring Session JDBC·세션 쿠키 `__Host-PCADMIN`·문자열 세션 값·CSRF 발급 엔드포인트·404/500 JSON 본문·`AnonymousSessionGrowthSpec`(세션 행 수). S7: 화면·라우터.
- 프론트 보고서 **별도 최소 건수**: 현재 TEST-SUMMARY는 합계 하나만 본다. 별도 하한은 (a) commands.json에 `TEST-SUMMARY-FRONTEND`(같은 스크립트, 하한 13, frontend 디렉터리만)를 추가하고 verify-gate `GATE`에 단계를 넣거나(harness 시험 동반), (b) 합계 하한만 올리는 방식 — Coordinator 선택. → **(a)로 판정, §18.6에서 반영.**

### 18.6 S1 체크포인트 리뷰(PASS_WITH_FIXES) 반영(2026-10-24 — 이 절이 §18.2~18.5와 다르면 이 절이 우선)

| 지적 | 반영 | RED(실행 확인) → GREEN | 변이 |
|---|---|---|---|
| M1 익명 상태 변경 요청이 세션 생성 | `SessionBoundCsrfTokenRepository`(세션이 이미 있을 때만 `saveToken` 위임) — 앱 체인. assets 체인 `csrf { disable() }`(GET 외 denyAll). 시험: 익명 POST/PUT/DELETE × `/`·`/api/x`·`/assets/x` → Set-Cookie 0개·상태 코드 각 하나(`/` 403, `/api/x` 403, `/assets/x` 401 — CSRF 끈 체인에서 익명 denyAll은 entry point 401), MockMvc `getSession(false) == null` | RED 10:34:20Z: `POST /` → `JSESSIONID=…` 생성(리뷰 지적 실증), MockMvc 세션 존재 → GREEN 10:38:03Z | B8 래퍼 제거·B9 assets CSRF 재활성 → RED |
| M1 명세 | **S4 `GET /api/auth/csrf`는 세션을 명시적으로 만든 뒤 토큰을 발급한다**(이 엔드포인트만 익명 세션을 만든다 — n-1의 "csrf 요청 → 1행"과 일치). 그 밖의 익명 요청은 세션을 만들지 않는다 | — | — |
| M2 프론트 별도 하한 (a) | verify-gate `GATE` = FINGERPRINT → HARNESS-SELFTEST → GRADLE-CHECK → TEST-SUMMARY → **TEST-SUMMARY-FRONTEND** → IMAGE-DIGESTS → FINGERPRINT(같은 not-before 치환). **TEST-SUMMARY는 Kotlin JUnit 보고서만, TEST-SUMMARY-FRONTEND는 frontend 디렉터리만** — 한 보고서 디렉터리는 한 하한에만(이중 집계 금지: 겹치면 한쪽 시험 감소를 다른 쪽 하한이 가린다). `test_module_registry`가 단계 위치·argv 모양·하한 = `minTests`·디렉터리 무중복을, `test_verify_gate`가 단계 실행·not-before·실패 시 중단을 검사 | RED 10:23:30Z → GREEN(verify-gate 21/21; registry는 commands.json 반영 필요 — 제안 적용 복사본 15/15) | V1 단계 제거 → RED |
| m1 `.npmrc` 정확 허용 목록 | 키·값 정확 일치 7개(registry·save-exact·engine-strict·ignore-scripts·strict-ssl·fund·audit), 모르는 키·중복 키·scope registry(`@x:registry`)·인증(`//…`) 줄 거부, 누락 거부 | RED 10:25~10:26Z: 중복 registry·`strict-ssl=false` 추가·scope registry·`script-shell`이 통과 → GREEN 10:27:47Z | L6 중복 registry·L7 strict-ssl=false·L8 scope·L9 모르는 키 → RED |
| m2 override 해석 대조 | override 대상 패키지가 lockfile에서 그 버전으로만 존재 | RED: lockfile undici 8.11.2가 통과 → GREEN | L10 다른 버전·L11 lockfile에 없음 → RED |
| m2 한계 | **`before`(14일 규칙)는 오프라인에서 강제하지 못한다** — 게시 시각은 registry `time`에만 있다. 생성 절차(`frontendLockfileRegenerate`)가 지키고, lockfile의 각 entry 게시 시각을 registry `time`과 대조하는 **온라인 검사는 후속 항목**(CVE-SCAN처럼 네트워크 명령으로 분리, VERIFY-GATE 밖) | — | — |
| m3 익명 GET 범위 | **결정: SPA 문서 허용 목록**(`SpaDocumentRequestMatcher` — `GET /`·`/index.html`·화면 경로 1~3 세그먼트, 첫 세그먼트 `api`·`assets`·`error`·`actuator` 제외, **디코딩 전 원문 URI**로 판정). 이유: `/actuator/**`만 막는 거부 목록은 새 경로가 생길 때마다 익명 노출이 기본값이 된다(fail-open). 그 밖의 익명 GET은 401 JSON. 시험: `/actuator`·`/actuator/health`·`/actuator/env`·`/favicon.ico`·`/robots.txt` → 401 `{"error":"UNAUTHENTICATED"}` | RED: `/actuator` 200(SPA index) → GREEN | B11 actuator 제외 삭제·B12 GET `/**` 복귀 → RED |
| m4 오류 본문 경로 | **발견: Boot 4.0부터 `server.error.*`는 폐기 키(deprecation level=error, 대체 `spring.web.error.*`)라 S1의 `server.error.*` 설정은 적용되지 않았다**(spring-boot-web-server·spring-boot-autoconfigure 4.1.1 메타데이터). `spring.web.error.include-path: never`(Boot 4.1.1 기본값 **always**), whitelabel·include-* 키도 `spring.web.error.*`로 옮김. 시험: 404 본문에 경로 문자열 없음, `Accept: text/html` 404에 Whitelabel 없음 | RED: 404 본문 `"path":"/assets/missing-0000.js"` → GREEN | B13 include-path always·B14 whitelabel true → RED |
| m5 출력 규칙 | GRADLE-CHECK `outputMustMatch` 제안에 `frontendNodeVerify`·`frontendInstall` 추가(registry 시험이 프론트 task 5개를 검사) | — | — |
| m6 단언 고정·경로 조작 | 미인증 POST는 403 하나만. 경로 조작 → index로 가지 않음, 각 상태 하나: `/API/x` 401, `/api` 401, `/a/b/c/d` 401, `/error`(직접 요청) 401, `/approvals/` 401(**정책: 끝 슬래시는 정규 경로가 아니므로 SPA 경로 아님**), `/%61pprovals` 401(인코딩된 문자는 원문 URI 판정에서 화면 경로 아님), `/approvals%20inbox` 401, `/approvals%2Finbox` 400(Tomcat의 인코딩된 슬래시 거부) | RED: `/API/x` 404 → GREEN | B10 디코딩된 servletPath 판정·B15 끝 슬래시 허용 → RED |
| n1 test-summary 대조 | suite마다 `<testcase>`를 직접 세어(tests·failure·error·skipped) 합계 속성과 다르면 FAIL. 기존 시험 보고서 helper는 합계와 일치하는 `<testcase>`를 만들도록 고침(기준 강화 — 완화 아님). 실제 보고서(Kotlin 2474·Vitest 13)는 통과 | RED 10:23:30Z(5건) → GREEN | N1 skipped 미집계·N2 대조 제거·N3 tests 속성 신뢰 → RED |
| n2 BREACH | `SpaCsrfTokenRequestHandler` 주석: 원문 토큰은 Set-Cookie로만, S4 발급 응답 본문에 공격자 영향 내용과 함께 압축 가능 형태로 넣지 않음(본문이 필요하면 XOR 마스킹 값) | — | — |
| n3 public 가드 | fingerprint.sh에 `apps/*/frontend/public/**`, 시험(무시된 public 파일 FAIL, `node_modules/pkg/public` 통과) | RED 10:23:30Z → GREEN | F4 가드 제거 → RED |
| n4 SELFTEST purpose | commands.json 제안 diff에 새 시험 범주 문구 | — | — |
| n5 서버 code ↔ 상태 | code별 허용 상태(401·403·404·409·4xx·5xx) 대조, 어긋나면 일반 오류(4xx `REQUEST_REJECTED`, 5xx `SERVER_ERROR`) | RED 10:31:52Z → GREEN 10:32:24Z(Vitest 14/14) | FM5 무조건 신뢰·FM6 상태 기반 대체 → RED |
| 실행 비트 | Windows 경로 편집 뒤마다 WSL에서 `chmod 755` 후 `git diff --summary`에 mode 변경 없음 확인(최종 확인 §18.7) | — | — |

### 18.7 리뷰 반영 후 체크포인트(2026-10-24)
- 전체 `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(WSL 분리, 10:42:22Z 시작, 18m57s): **BUILD SUCCESSFUL**(시뮬레이터 확률적 결함 재발 없음). Kotlin: master-api 320·payment-api 1766·financial-batch 26·internal-admin 43·institution-simulator 210·test-support 112 = **2477**, Vitest **14**. 제안 하한으로 TEST-SUMMARY(Kotlin만, 2477) PASS·TEST-SUMMARY-FRONTEND(14) PASS.
- HARNESS-SELFTEST(11:01:45Z): 136건 중 실패 4·오류 2 — 전부 commands.json 반영 대상(SELFTEST 하한, TEST-SUMMARY-FRONTEND 미정의로 GATE timeout 계산 KeyError, 프론트 등록·무중복). 제안 diff 적용 복사본: registry 15/15·verify-gate 21/21 OK.
- CVE-SCAN(11:02Z): PASS(5개 모듈 0건, 프론트 147개·npm canary 6건 검출·0건).
- 실행 비트: `harness/scripts` 4개 755, `git diff --summary`에 mode 변경 없음. FINGERPRINT: 대체 파일 3개 삭제를 index 사본에 반영하면 exit 0(files 1553).

- 기존 시험 기대 변경 1건: `SpaServingSpec`의 "익명 `PUT /assets/…` → 403"은 리뷰 M1(assets 체인 CSRF 끔)으로 **401**(익명 denyAll의 entry point)이 됐다 — 거부라는 주장은 같고 상태 코드 하나로 고정(위 M1 시험에 포함).

---

## 19. S2 구현 기록(2026-10-24 KST, 2단계 Implementer — 자기 완료 판정 아님)

범위: §10 S2 — ADMIN DB(초기화 스크립트·compose·`.env.example`), migration(표 8종 + Spring Session 표·권한), `AdminDatabaseAccountSpec`·`AdminSchemaSpec`, ArchUnit 교체(F-1), `TestInfrastructureSpec` 교체(F-2), jOOQ codegen, actuator 익명 차단 시험(S1 m3). 기준: commands.json은 Coordinator가 S1 제안본(`0b8b5ec8…`)으로 반영한 상태.

### 19.1 변경

| 영역 | 내용 |
|---|---|
| 초기화 스크립트 `docker/mysql/init/01-owned-databases.sh` | ADMIN 블록: `paycore_admin` schema, `paycore_admin_migration`(ALL, GRANT OPTION 없음)·`paycore_admin`(role `paycore_admin_runtime` 기본 role)·잠긴 grantor, `runtime_privilege_sql paycore_admin` + `runtime_column_privilege_sql paycore_admin`(F-13) |
| compose·`.env.example` | `PAYCORE_ADMIN_DB_PASSWORD`·`PAYCORE_ADMIN_DB_MIGRATION_PASSWORD`(`:?` fail-closed), 기존 volume·.env 수렴 절차(스크립트 재실행), 앱용 `PAYCORE_ADMIN_DB_URL/USERNAME/MIGRATION_USERNAME` |
| migration `db/migration/admin` | V1 `admin_account`·`admin_role_event`·`admin_enrollment_code`·`admin_approval_request`·`admin_audit_event`·`admin_bootstrap`·`admin_recovery_episode`, V3 `admin_session`·`admin_session_attributes` |
| 권한 `db/runtime-privilege/admin` | V2 표 단위 SELECT·INSERT(7표) + 열 단위 UPDATE(계정 17열·승인 6열·코드 2열·회차 1열 — 표 helper 먼저, 열 helper 뒤, 8열 상한으로 나눠 호출), V4 세션 표 전체 DML |
| 앱 | `build.gradle.kts`(data-jpa·jooq·flyway·flyway-mysql·actuator·mysql-connector, 시험: testcontainers·spring-boot-testcontainers·spring-session-jdbc 라이브러리), `application.yml`(runtime 계정 DataSource·UTC driver 속성·Flyway migration 계정·UTC init-sql·`ddl-auto: validate`·OSIV off·jOOQ MYSQL·actuator health만·Hibernate JDBC 오류 원문 로그 OFF), jOOQ 생성기 `src/jooqCodegen`(업무 schema만, Spring Session 표 제외, 패키지 `com.paycore.admin.jooq.generated`) |
| catalog | `spring-session-jdbc`(testImplementation 전용 — F-1 fixture 컴파일, Boot auto-configuration 모듈이 아니라 시험 컨텍스트에서 Spring Session이 켜지지 않음, BOM 4.1.1 관리) |
| 시험 | 새 `AdminDatabaseAccountSpec`(21)·`AdminSchemaSpec`(12)·`support/AdminDbAccounts`·`support/AdminDbTest`, F-1 fixture 5종, `ArchitectureSpec`(교체 규칙 2 + fixture 1 + jOOQ 1), `TestInfrastructureSpec`(F-2), `SpaCsrfSpec`(actuator 1, `@AdminDbTest`), `SpaServingSpec`·`NoGeneratedDefaultUserSpec`(ADMIN DB 컨테이너 연결) |

### 19.2 구현 결정(가역 — 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| **D-55** | 초기화 스크립트의 ADMIN 블록은 ADMIN 비밀번호 **두 개가 모두** 있을 때만 실행, **하나만** 있으면 실패(exit 1, 빠진 변수 이름 출력), **둘 다 없으면 ADMIN만 건너뜀**(stderr 안내). compose.yaml은 두 변수를 `:?`로 요구하므로 compose 경로는 fail-closed 그대로 | master-api `MasterDatabaseAccountSpec`·payment-api `DatabaseAccountSeparationSpec`이 **같은 스크립트**를 컨테이너에 넣되 ADMIN 변수를 넘기지 않고 `paycore%` 계정·role·잠금 목록을 정확히 단언한다(허용 경로 밖). 무조건 실행이면 두 모듈 DB 시험 전체가 깨진다. ADMIN DB가 없으면 internal-admin이 기동하지 않으므로 건너뛰기가 권한을 넓히지 않는다. **Coordinator 검토 대상**: 대안은 ADMIN을 무조건 실행하고 두 모듈의 시험 구성·기대값을 함께 바꾸는 것(허용 경로 추가 필요) |
| D-56 | 역할 집합 열은 MySQL `SET('VIEWER','OPERATOR','APPROVER','SECURITY_ADMIN','UNMASK')`(활성·정지 각각) | 고정 값만 저장(strict mode 1265), PK 단건 판정, `FIND_IN_SET`으로 활성 수 계산(복구 조건 §4.9.2). JPA 매핑은 문자열 — S3에서 domain `Set<AdminRole>` 변환 |
| D-57 | 감사·역할 이벤트 PK는 `BIGINT AUTO_INCREMENT`(INSERT만), 승인·계정 PK는 문자열 ID(`adm_`·`apr_` + ULID) | append-only 순서·keyset 페이지(jOOQ Reader). 업무 ID는 외부 노출·재사용 금지 대상 |
| D-58 | FK: 역할 이벤트·등록 코드의 대상 → `admin_account`. **승인 요청의 대상 FK는 없음**(S2 리뷰 M-1 결정 (a) — §19.7). 요청자·부트스트랩 계정 참조도 FK 없음 | 역할 이벤트·코드는 계정과 같은 TX에서만 생겨 FK 공유 잠금이 순서와 충돌하지 않는다. 승인 요청 대상 FK는 INSERT가 대상 계정 행에 공유 잠금을 걸어 §4.13 "승인 행 INSERT → 계정 오름차순 `FOR UPDATE`"와 교착(1213 실측 재현)을 만든다 → 생성 TX가 **계정 `FOR UPDATE` 아래에서 대상 존재를 확인**(S5)하는 것으로 대체. 요청자 재검사는 승인 TX(§4.7.3). 부트스트랩 행은 S6에서 계정과 같은 TX |
| D-59 | 승인 종류 CHECK는 ADMIN 소유 6종만. 소유 서비스 종류(OPS·Webhook)는 그 작업의 migration이 넓힌다 | 확장 가능한 열거(§4.12) — 지금 없는 종류를 저장할 수 없게 |
| D-60 | 웹 포트의 actuator는 익명 401·인증 403(허용 목록 밖). 별도 management 포트(D-15)는 S8 | S1 m3 결정 유지. 이번에 actuator 의존성이 생겨 실제 엔드포인트가 있는 상태로 시험을 고정 |
| D-61 | F-1 security 금지 패키지 목록은 `PayCoreArchitectureRules`의 영속 기술 목록과 같은 값을 ArchitectureSpec에 둔다(공통 companion이 private, test-support는 허용 경로 밖) + `org.jooq..` | 공통 목록이 바뀌면 함께 바꾼다(주석) |

### 19.3 RED → GREEN·핵심 변이

- **RED**(11:17~11:20Z): 먼저 migration 디렉터리 없음으로 jOOQ 생성 task 실패(빌드 단계) → 빈 디렉터리를 임시로 두고 실행: 35건 중 11 실패 — DataSource 설정 없음(`Failed to determine a suitable driver class`)으로 DB·SPA·기동 시험 컨텍스트 실패, jOOQ 생성 표 없음, F-1 허용 대조가 Spring Session 규칙의 제외 패키지라 "검사 대상 0개"(→ 대조에는 `allowEmptyShould(true)`를 명시).
- **GREEN**(11:25:17Z): internal-admin 80건(43 → 80, +37: AdminDatabaseAccountSpec 21, AdminSchemaSpec 12, ArchitectureSpec 20→23, SpaCsrfSpec 8→9), 실패·skip 0.
- **변이 12/12 RED**(11:27~11:37Z, 모두 원본 복원 확인): D1 계정 표 단위 UPDATE 확대·D2 승인 열 UPDATE 제거(잠금 경로 상실 — m-4)·D3 감사 표 UPDATE·D4 불변 열 `login_name` UPDATE 허용·D5 `pending_key` UNIQUE 제거·D6 열린 회차 UNIQUE 제거·D7 부트스트랩 CHECK 제거·D8 `login_name` 대소문자 무시 collation·D9 스크립트 부분 변수 건너뛰기·D10 security 규칙에서 jOOQ 제외·D11 Spring Session 표 jOOQ 생성·D12 역할 이벤트 FK 제거.

### 19.4 S0 m-4 실측의 고정
`AdminDatabaseAccountSpec`이 실제 권한 migration으로: 열 단위 UPDATE 표 4개(계정·승인 요청·등록 코드·복구 회차)에서 `FOR UPDATE`·`FOR SHARE` 허용, SELECT·INSERT만인 append-only 표 3개(감사·역할 이벤트·부트스트랩)에서 `FOR UPDATE` 1142. 변이 D2(승인 요청 열 UPDATE 제거)가 이 시험을 깨뜨린다.

### 19.6 S2 체크포인트(2026-10-24)
- 전체 `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(WSL 분리, 11:37:33Z 시작, 20m42s): **BUILD SUCCESSFUL**. Kotlin: master-api 320·payment-api 1766·financial-batch 26·internal-admin **80**·institution-simulator 210·test-support 112 = **2514**, Vitest 14. master-api·payment-api DB 시험(같은 초기화 스크립트 사용)은 변경 없이 통과 — D-55가 의도대로 동작.
- HARNESS-SELFTEST(11:59:13Z): 136 OK(하네스 변경 없음).
- CVE-SCAN(11:59Z): PASS. internal-admin runtime 구성요소 52 → **106**(JPA·jOOQ·Flyway·actuator·MySQL 드라이버 추가), 취약점 0. 프론트 147·npm canary 6.
- GRADLE-DEPS-INTERNAL-ADMIN: exit 0, runtime에 testcontainers·spring-session 없음(spring-session-jdbc는 시험 전용).
- 실행 비트 변화 없음. FINGERPRINT: 대체 파일 3개 삭제를 index 사본에 반영하면 exit 0(files 1567).
- commands.json 제안: GRADLE-CHECK `minTests`·TEST-SUMMARY 하한 2477 → **2514**(TEST-SUMMARY-FRONTEND 14·HARNESS-SELFTEST 136 그대로). 제안 적용 복사본 registry 15/15 OK.

### 19.5 남은 것(S2 범위 밖)
- JPA Entity의 updatable 열 = 열 권한 목록 대조(R-12)는 Entity가 생기는 S3. Spring Session 운영 의존성·질의 교체·문자열 세션 값은 S4. 부트스트랩·복구 TX는 S6.

### 19.7 S2 체크포인트 리뷰(PASS_WITH_FIXES) 반영(2026-10-25 KST — 이 절이 §19.1~19.6과 다르면 이 절이 우선)

V1 migration은 배포 전이라 제자리 수정(새 버전 파일 없음).

| 항목 | 반영 | 증거 |
|---|---|---|
| **M-1** 승인 요청 대상 FK 교착 | 결정 (a): `fk_admin_approval_request_target` **제거**. 생성 TX가 요청자·대상 계정 `FOR UPDATE` 아래에서 **대상 존재를 확인**(S5 — §4.13 생성 행 갱신). D-58 갱신. S5 시험 목록(§7.2)에 "같은 대상·다른 종류 동시 생성 → 1213 0회" 추가 | `AdminSchemaSpec` M-1 시험(runtime 연결 2개: 각자 다른 종류 승인 INSERT → 계정 오름차순 `FOR UPDATE`, 시작 장벽): **RED 03:07Z `[1213]` 재현**(FK가 INSERT 시 대상 계정 행 공유 잠금 → 두 TX가 서로의 X 잠금 대기) → FK 제거 뒤 GREEN(오류 0). 변이 E1(FK 복원) RED |
| **m-1** 승인 상태 CHECK | `APPROVED`·`EXECUTING`·`EXECUTION_UNKNOWN` 제거 → `PENDING`·`EXECUTED`·`REJECTED`·`WITHDRAWN`·`EXPIRED`·`INVALIDATED`·`OWNER_REJECTED`만(소유 서비스 상태는 그 종류와 함께 넓힌다 — D-59와 같은 원칙) | `AdminSchemaSpec` 3상태 INSERT 3819. 변이 E2 RED |
| **m-2** D-55 보강 | 두 변수가 모두 없을 때 **`paycore_admin`·`paycore_admin_privilege` schema 또는 ADMIN 계정(`paycore_admin`·`_migration`·`_grantor`·`_runtime`) 중 하나라도 있거나 확인 질의가 실패하면 exit 1**(안내 문구에 두 변수 이름), 새 인스턴스에서만 건너뜀. `.env.example`에 "stderr에 건너뜀 안내가 **없어야** 한다" | `AdminDatabaseAccountSpec`: ADMIN 있는 컨테이너에서 변수 없이 실행 → exit 1·존재 안내·건너뜀 안내 없음. 변이 E5(존재해도 건너뜀) RED |
| **m-3** "ADMIN 없음" 시험 | 새 컨테이너(ADMIN 없이 초기화)에서 변수 없이 실행 → exit 0, stderr의 ADMIN 줄 = **정확한 건너뜀 문구 1줄**, ADMIN 상태(schema·계정·role 목록) 실행 전 = 실행 후 = 없음 | 변이 E6(문구 변경) RED |
| **m-4** FK 정렬 | 명세·시험을 FK 제거에 맞춤. 남은 FK(역할 이벤트·등록 코드 대상)는 1452 시험 유지 | `AdminSchemaSpec` |
| **n-2** 교차 열 CHECK | 명세 상태 정의(§4.3.1·§4.3.6·§4.9.2)와 대조해 추가: `(pending_purpose IS NULL) = (pending_basis IS NULL)`, `pending_purpose IS NULL OR status = 'PENDING_ENROLLMENT'`, `purpose <> 'RECOVERY' OR (recovery_episode_no·recovery_roles NOT NULL)`, `recovery_roles IS NULL OR purpose = 'RECOVERY'`. **불일치(역방향은 강제하지 않음 — 기록)**: ① `PENDING_ENROLLMENT ⇒ pending_purpose NOT NULL`은 정지 해제로 대기 상태에 돌아간 계정이 사유 없이 있을 수 있어(§4.3.1 H-B "새 코드 발급 전까지 완료 불가") 강제 불가, ② `recovery_episode_no ⇒ RECOVERY`는 복구 작업 B의 `ENROLL` 코드가 회차를 가질 수 있어(§4.9.2) 강제 불가 | `AdminSchemaSpec` 교차 열 3819. 변이 E3·E4 RED |
| **n-3** 낡은 주석 | `ArchitectureSpec`의 "JPA Entity가 없다(DB 없음 …)" 주석 교체, Entity가 생긴 S3에서 `jpaMappingsOnlyInPersistenceAdapter` 등 대상 있는 규칙을 `allowEmptyShould(false)`로(§20). `spring-session-jdbc`는 **BOM 관리 4.1.1**(testRuntimeClasspath에서 `spring-boot-dependencies:4.1.1` constraint로 해석 확인) | `ArchitectureSpec` 23 통과 |
| n-1 발급 근거 길이 | S3 확인: domain `PendingEnrollment` 근거 형식 `^[A-Za-z0-9_:]{1,40}$` = DB `pending_basis`·`basis` `VARCHAR(40)`. `enc_`/`apr_` + ULID = 30자, `episode:<n>` ≤ 40 | `AdminDomainValuesSpec` |
| n-4 열 helper 거부 목록 | **보류(기록만)** — 열 helper의 거부 목록 확장은 후속 권한 변경 때 함께 | — |

- RED(03:06~03:07Z): internal-admin DB 시험 37건 중 4 실패(존재하는 ADMIN에서 건너뜀·소유 서비스 상태 저장 가능·교차 열 규칙 없음·M-1 `[1213]`). GREEN 03:09:32Z. 변이 **E1~E6 6/6 RED**(03:10~03:16Z, 모두 원본 복원).
- 결과: `AdminSchemaSpec` 12 → 15, `AdminDatabaseAccountSpec` 21 → 22(없음 시험을 "ADMIN 있으면 실패"로 바꾸고 "새 인스턴스 건너뜀"을 추가).

## 20. S3 구현 기록(2026-10-25 KST, 2단계 Implementer — 자기 완료 판정 아님)

범위: §10 S3 — `account`·`audit` domain·application(+ 그 Port의 persistence·integration·query adapter). 진입 조건 R-12(첫 JPA Entity와 함께 Entity updatable·insertable 열 = `AdminDatabaseAccountSpec.ADMIN_RUNTIME_COLUMN_UPDATES` 대조 시험) 반영. HTTP·세션(S4)·승인(S5)·1회성 명령(S6)은 범위 밖.

### 20.1 변경

| 영역 | 내용 |
|---|---|
| `account.domain` | `AdminAccount`(불변 Aggregate — 생성·복원 불변식, 새 세션, 인증 성공·실패/잠금, TOTP 단계 소비, 정지(본인 거부·대기 사유 비움), 역할 회수·부여, 자격 재설정(역할 SUSPEND·원인 = 승인 ID), `ENROLL` 재발급(역할 없는 대기 계정만), 등록 완료(상태·대기 사유·근거 일치, 목적별 요소만)), `AdminActorId`·`LoginName`·`DisplayName`·`PendingEnrollment`·`PasswordHash`·`TotpCredential`·`LoginAttemptPolicy`·`RoleEvent`·`EnrollmentCode`/`EnrollmentCodeHash`·`AdminPermission`/`AdminAction`(권한 표 단일 기준)·`Base32` |
| `account.application` | provided: `AdminAccountCommandUseCase`(생성·`ENROLL` 재발급·정지·역할 회수), `EnrollmentUseCase`(begin·비밀번호·TOTP 시작·완료), `AuthenticationFailureRecorder`, `AdminRequestContext`. required: `AdminAccountRepository`·`AdminRoleEventStore`·`EnrollmentCodeStore`·`PasswordHasher`·`TotpVerifier`·`TotpSecretCipher`·`TotpSecretGenerator`·`EnrollmentCodeGenerator`·`LoginNameDigester`·`AdminSecuritySettings`·`AdminClock`·`AdminIdGenerator`·`PersistenceFailure`·`Versioned`. 서비스 3개 + 모듈 내부 `LocalTransactions`·`AccountAuditWriter` |
| `account.adapter` | persistence: `JpaAdminAccountEntity`·`JpaAdminRoleEventEntity`(`@Immutable`)·`JpaEnrollmentCodeEntity`(+`JpaEnrollmentCodeKey`)·mapper·`JpaAdminAccountRepository`·`JpaAdminRoleEventStore`·`JpaEnrollmentCodeStore`·`PersistenceFailures`. integration: `Rfc6238TotpVerifier`·`AesGcmTotpSecretCipher`·`BCryptPasswordHasher`·`SecureRandom*Generator`·`HmacLoginNameDigester`·`RandomAdminIdGenerator`(ULID)·`AdminSecurityProperties`·`AccountSecurityConfiguration` |
| `audit` | domain `AdminAuditEvent`·`AuditScope`, provided `AdminAuditRecorder`(MANDATORY)·`AdminAuditQuery`·`AuditFilter`, required `AdminAuditStore`·`AdminAuditReader`, `JpaAdminAuditEventEntity`(`@Immutable`)·`JpaAdminAuditStore`·`JooqAdminAuditReader`(jOOQ CQRS) |
| 설정 | `application.yml` `paycore.admin.security.*` Demo 값(D-SV: 실패 5회·창 ±1·코드 24h·진행 10분·최소 12자·72바이트·BCrypt 12·issuer) + 키 환경변수 `PAYCORE_ADMIN_TOTP_CIPHER_KEY_V1`·`PAYCORE_ADMIN_LOGIN_NAME_HMAC_KEY`(`.env.example` 빈 자리). 시험 설정(`src/test/resources/config`)에만 시험 키·BCrypt 4 |
| 시험 | domain `AdminAccountSpec` 25·`AdminDomainValuesSpec` 10·`AdminPermissionSpec` 8·`SecurityVersionEventsSpec` 2, adapter `AccountCryptoAdaptersSpec` 10(RFC 6238 golden 포함)·`AdminPersistenceMappingSpec` 5(R-12)·`AdminAccountPersistenceSpec` 9, application(실제 MySQL) `AdminAccountCommandSpec` 10·`EnrollmentFlowSpec` 18·`AuthenticationFailureSpec` 5·`AuditFailureClosesActionSpec` 5·`AuditQueryPermissionSpec` 5 = **Kotlin 112**, harness `test_totp_rfc6238_golden.py` 3(Python `hmac`으로 golden 독립 생성·검증) → **S3 합계 115**(§10 예상 115~145). 시험 지원: `AdminAccountFixture`(Named — 부트스트랩 경로), `AdminAccountStorage`(저장 측 Named Fixture), `TestTotp`(독립 TOTP 계산), `SwitchableAuditStoreConfig`(감사 실패 주입) |
| 아키텍처 | `ArchitectureSpec`: 대상이 생긴 규칙(domain 순수·core 의존·application Port 의존·JPA 매핑 위치·`@NamedInterface`)을 `allowEmptyShould(false)`로, Spring Data(미사용)·batch만 true 유지 |

### 20.2 구현 결정(가역 — 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| D-62 | `login_name`은 소문자·숫자·`._-` 3~64자, 대문자 거부(정규화하지 않음) | DB collation `utf8mb4_0900_bin`(대소문자 구분 UNIQUE)과 같은 기준을 입력에서 강제 — 대소문자만 다른 두 계정 불가 |
| D-63 | (S3 리뷰 M-2로 갱신) 부트스트랩 경로 `bootstrapped(role)`은 **SECURITY_ADMIN 또는 APPROVER 단일 역할만**(부트스트랩이 실제로 만드는 상태). 그 밖의 역할·겸직은 이름에 우회를 드러낸 `rolesGrantedBypassingApproval_untilS5(...)`. **S5에서 핵심 규칙 시험의 우회 준비를 `withRoleViaApproval`(요청·승인 Use Case)로 교체한다** — 사용처: `AdminAccountSpec`(재설정·역할 회수), `SecurityVersionEventsSpec`, `AdminPermissionSpec`, `AdminAccountPersistenceSpec`, `AdminAccountCommandSpec`(역할 회수·역할 이벤트 재생·잠금 순서), `EnrollmentFlowSpec`(TOTP_REENROLL·요소 누락) | 모든 상태는 domain 공식 전이로 만든다(reconstitute 조립 없음). 역할 이벤트 재생 = 현재 역할(시험). 재설정 대기는 S5 실행과 같은 `beginCredentialReset` 전이 |
| D-64 | `RECOVERY` 코드는 S6 전까지 begin·complete에서 `NOT_APPLICABLE`(fail-closed). 등록 완료 TX의 "0단계 선판정 → 열린 회차 잠금"은 S6에서 추가 | 복구 명령이 없어 열린 회차·복구 코드가 생기지 않는다 — 회차 잠금 없이 SA·APPROVER가 활성화되는 경로가 S3에 없다. 역할 복원(§4.9.2)은 S6 |
| D-65 | 입력 형식 오류(actor ID·login_name·표시명·역할 이름)는 TX·감사 없이 `INVALID_INPUT`(웹 400). 권한 없음은 `DENIED`, 업무 거절은 `REJECTED`(detail `reason=<code>`)로 **감사 1행 커밋**(상태 변경 없음) | 요청 검증 실패와 업무 판정을 구분. 감사 행 행위자 = 요청자 ID + 기록 시점 표시명 |
| D-66 | 감사 행위 code: `ACCOUNT_CREATED`·`ENROLLMENT_CODE_ISSUED`(detail `purpose=…[;revokedCodes=n]`)·`ACCOUNT_SUSPENDED`(detail `revokedCodes=n;revokedBases=<발급 근거,…>` — 정지 TX의 코드 폐기를 같은 행에, 근거 최대 8개, S3 리뷰 m-2)·`ROLE_REVOKED`(`role=…`)·`ENROLLMENT_COMPLETED`(`purpose=…`, 행위자 = 등록한 계정 / 적용 불가는 `REJECTED`·ANONYMOUS)·`AUTHENTICATION_FAILED`(결과 `COUNTED`·`LOCKED`·`NOT_COUNTED`, `step=…;failures=n`)·`ACCOUNT_LOCKED`·`AUDIT_VIEWED`(필터 요약 — 값 없음) | 고정 code·key=value 요약만(domain 정규식). 코드 원문·해시·시크릿 없음 |
| D-67 | (S3 리뷰 m-1로 갱신) 코드 무효(없음·만료·사용됨 — 대상 미확정 익명 시도)는 감사 없이 전체 rollback(지표 S8). **대상이 확정된 적용 불가**(유효했던 코드·진행 상태로, 그 사이 정지·근거 교체된 계정의 완료 시도)는 주 TX rollback 뒤 별도 TX로 `ENROLLMENT_COMPLETED:REJECTED:reason=NOT_APPLICABLE`(행위자 ANONYMOUS). 성공은 `ENROLLMENT_COMPLETED:SUCCESS` | §4.3.6 "지표만"은 코드 무효에 적용, 대상 계정에 대한 시도는 추적 가능해야 함(리뷰 판정) |
| D-68 | TOTP 확인 코드 검증·저장 문맥 재암호화는 완료 TX **밖**(순수 계산), 틀린 TOTP는 계정 실패 계수에 넣지 않음 | 잠금 시간 최소화. 등록은 미인증 흐름이라 계수 대상이 아님(D-ADMIN1-005는 로그인·재인증) |
| D-69 | 1인 명령 TX는 업무 UNIQUE(login_name)·1213·1205를 새 TX에서 처음부터 재판정(최대 3회 — 재판정하면 `LOGIN_NAME_TAKEN`으로 수렴). 인증 실패 기록은 1213·1205만 재시도. 등록 완료는 재시도 없음(`PersistenceFailure` 전파 → S4에서 409 `RETRY_LATER`) | 전체 rollback 뒤 재판정만 — 부분 효과 없음 |
| D-70 | 의존 방향 account → audit(`audit.application.provided`만). audit는 account 역할을 모른다: `AuditViewer.canViewAll`은 호출자(S4 웹)가 `AdminPermission.allows(…, VIEW_ALL_AUDIT)`로 계산 | Modulith 순환 없음(`modulesAreFreeOfCycles`) |
| D-71 | Spring Data Repository 미사용 — `EntityManager` 직접(잠금 읽기 `find + refresh(PESSIMISTIC_WRITE)`·조건부 JPQL UPDATE·Port 안 flush 분류를 명시) | master-api 관례와 같음 |
| D-72 | `otpauth` URI는 수동 percent-encoding(RFC 3986 unreserved 외 `%XX`) — `java.net`은 application 허용 목록 밖. issuer는 설정(Demo `PayCore Admin (Demo)`, `:` 금지) | Key Uri Format |
| D-73 | ID: actor `adm_`+ULID·발급 `enc_`+ULID(`SecureRandom` 80비트), 진행 ID 128비트 hex | 근거 열 40자 안(30자) |

### 20.3 JPA·jOOQ — 이번 코드의 역할·예·이유·주의

| 선택 | 이번 코드의 예 | 이유 | 주의 |
|---|---|---|---|
| 순수 Domain ↔ JPA Entity 분리 | `AdminAccount`(domain, Spring/JPA 없음) ↔ `JpaAdminAccountEntity` + `AdminAccountPersistenceMapper` | 업무 규칙을 DB 없이 시험(domain 45건)하고 영속 모양 변경이 규칙에 새지 않게 | Entity는 data class 아님·equals = PK·toString에 해시/암호문 없음, 매퍼가 `reconstitute`로 불변식 재검사 |
| `updatable = false` + R-12 대조 | `actor_id`·`login_name`·`created_at`, 코드 표는 `used_at`·`revoked_at`만 updatable | Hibernate는 updatable 열 **전부**를 UPDATE SET에 넣는다 → 권한 없는 열이 SET에 있으면 실행 시 1143 | `AdminPersistenceMappingSpec`이 Hibernate persister의 updatable·insertable 열을 `ADMIN_RUNTIME_COLUMN_UPDATES`와 정확 비교(변이 S3-M12 RED) |
| `@Version` + 행 잠금 | `lock()` = `find` + `refresh(PESSIMISTIC_WRITE)`, `update(current, next)`는 잠근 version과 다르면 `OptimisticConflict` | 오래된 객체로 최신 행을 덮지 않음. 동시 실패 계수는 행 잠금으로 직렬화(변이 S3-M6 RED) | 잠근 행을 JPQL `@Lock`으로 다시 읽지 않는다(관례 §9). `lockAll`은 actor ID 오름차순(§4.13) |
| `@Immutable` + persist만 | 역할 이벤트·감사 Entity | runtime 권한이 SELECT·INSERT만(append-only — UPDATE 시도는 1142) | merge 금지, IDENTITY PK라 persist 즉시 INSERT |
| 조건부 JPQL 대량 UPDATE | 코드 소비 `… WHERE used_at IS NULL AND revoked_at IS NULL AND expires_at > :now`(영향 1이면 성공), 미사용 코드 폐기 | 원자 소비 — 동시 완료는 한 쪽만(시험), 진행 중 만료도 TX 안 서버 시계로 거부(변이 S3-M3 RED) | 영속성 컨텍스트를 거치지 않으므로 `find`가 `refresh` |
| Port 안 flush + 제약 이름 분류 | `PersistenceFailures.translate` → `UniqueConflict(LOGIN_NAME)` 등 | application이 JDBC 예외를 모름, 1062 원문(키 값)을 로그·메시지에 내지 않음 | flush ≠ commit |
| `columnDefinition = "binary(32)"` | 코드 hash PK·감사 login_name HMAC | `ddl-auto: validate`가 `binary` ↔ `varbinary` 불일치로 기동 실패(실측) | 길이 고정 열만 |
| jOOQ CQRS 조회 | `JooqAdminAuditReader`: SELF 범위는 SQL `actor_id = ?`(변이 S3-M8 RED), 최신순 keyset, 시각은 `DATE_FORMAT` 문자열 → `Instant`, 비교 값은 UTC 리터럴 `CAST(… AS DATETIME(3))` | 목록·필터·페이지는 Entity 없이 조회 모델로. 쓰기 없음 | Reader 사용처 ArchUnit 규칙은 Reader가 늘어나는 S5 |

### 20.4 8대 원칙(S3 범위) 증거

| 원칙 | 증거 |
|---|---|
| Financial Consistency | 해당 없음(금액·금융 사실 없음) |
| Transaction Safety | 명령마다 단일 로컬 TX(상태·역할 이벤트·코드·감사), `AuditFailureClosesActionSpec` 5(생성·정지·거부·등록 완료·인증 실패 — 감사 실패 시 전부 rollback, 변이 S3-M7 RED), 등록 실패는 전체 rollback(코드 미소비) |
| Idempotency | 코드 원자 소비·동시 완료 1건(`EnrollmentFlowSpec` race — 계정에는 승자의 해시·시크릿만), 같은 draft 두 번 완료 거부, TOTP 단계 재사용 거부(domain) |
| Concurrency Control | 인증 실패 동시 10건 → 정확히 4·1·5(잠금 1회), 동시 4건 계수 손실 없음, 정지 vs 완료 경합 4회 모두 허용된 두 결과 중 하나, `lockAll` 오름차순 |
| Recoverability | commit 중 실패는 `CommitOutcomeUnknownException`(재시도 안 함), 1213·1205 재판정(D-69) — 부분 효과 없음 |
| Reconciliation | 해당 없음(기관·원장 비교 없음). 내부 일관성: 역할 이벤트 재생 = 계정 현재 역할(`AdminAccountCommandSpec`) |
| Auditability | 모든 1인 명령·거부·거절·등록 완료·인증 실패·잠금·감사 조회 감사(D-66), `AUDIT_VIEWED` 먼저 커밋·실패면 결과 없음(변이 S3-M9 RED), 코드 원문이 감사에 없음 |
| Observability | 지표는 S8. 비밀값 toString 마스킹(`PasswordHash`·`TotpCredential`·`SealedSecret`·`IssuedEnrollmentCode`·`AdminSecurityProperties`·draft) |

- BOM·의존성: S3에서 빌드 파일 변경 없음(BCrypt는 기존 spring-security-crypto, AES-GCM·HMAC은 JDK). Kafka/Redis 도입 없음 — 동시성·1회성 안전은 DB 행 잠금·조건부 UPDATE·UNIQUE가 담당(대체 주장 없음).

### 20.5 RED → GREEN·핵심 변이

- RED: domain(03:22:51Z, 미구현 컴파일 실패), 암호 adapter(03:28:30Z 컴파일 실패), RFC golden(03:26:03Z 실패 → 03:26:16Z GREEN). persistence·application 시험은 서비스와 같은 묶음으로 작성해 첫 실행(03:47Z)이 `ddl-auto: validate` 기동 실패(`code_hash` binary ↔ varbinary) → `columnDefinition` → 03:49Z 2건 실패(시험 측 null 결과 헬퍼·근거 형식) → 03:55Z GREEN. **업무 RED는 아래 변이로 확인**(서비스 동작을 하나씩 제거하면 해당 시험이 실패).
- 변이 1차(04:01~04:12Z)에서 **S3-M3(코드 소비 결과 무시)가 생존** — 계정 행 잠금·상태 검사가 같은 대상의 중복 완료를 이미 막아, 소비 결과가 의미 있는 경우는 "begin 뒤 완료 전 코드 만료"뿐이었다 → 시험 추가(만료 3초 코드, 완료 TX가 서버 시계로 거부·미소비). 실패 줄을 보이도록 실행 래퍼 교체 후 2차 전체 재실행.
- **변이 2차 14/14 RED**(04:14~04:24Z, 모두 원본 복원 — 실패한 시험 이름 확인): M1 정지 시 코드 폐기 제거(2)·M2 본인 정지 허용·M3 소비 결과 무시·M4 완료의 대기 사유 검사 제거·M5 틀린 TOTP 수용·M6 실패 계수 행 잠금 제거(2)·M7 감사 실패 삼킴(5)·M8 SELF 범위 SQL 미강제·M9 `AUDIT_VIEWED` 실패에도 결과 반환·M10 대기 값 AAD 용도 태그 제거(2)·M11 역할 보유 계정 1인 재발급·M12 Entity updatable ≠ 열 권한·M13 OPERATOR 전체 감사·M14 생성 권한 검사 제거.

### 20.6 남은 것(S3 범위 밖·후속 단계)
- S4: 로그인 2단계(경로 동등성·`LoginCredentialChangeRaceSpec`)·세션 세대 filter·없는 계정 감사(HMAC)·`AuditViewer` 계산·`PersistenceFailure` → 409·LIVE Demo 키 거부(n-4).
- S5: 승인 Use Case·`withRoleViaApproval` Fixture — **핵심 규칙 시험의 우회 준비(`rolesGrantedBypassingApproval_untilS5`)를 이것으로 교체**(사용처 D-63)·생성 TX 대상 존재 확인(M-1)·"같은 대상·다른 종류 동시 생성 1213 0회"·`ApprovalLockOrderSpec`은 대기 확인(`RowLockHolder`)·교착 지표 방식·Reader 사용처 ArchUnit·LOCKED 정지 시 `locked_at` 소실 처리(재활성 설계 — §20.8 m-4).
- S6: `RECOVERY` 완료(회차 잠금·역할 복원) — D-64. S8: 등록·인증 실패 지표.

### 20.7 S2 수정 + S3 체크포인트(2026-10-25)
- 전체 `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(WSL 분리, 04:25:45Z 시작, 22m14s): **BUILD SUCCESSFUL**. Kotlin: master-api 320·payment-api 1766·financial-batch 26·internal-admin **196**·institution-simulator 210·test-support 112 = **2630**(실패·오류·skip 0), Vitest 14. 등록 하한(2514·14)으로 TEST-SUMMARY·TEST-SUMMARY-FRONTEND PASS.
- HARNESS-SELFTEST(04:48:23Z): Ran **139**(136 + RFC golden 3), 실패 1 = `test_selftest_minimum_equals_number_of_harness_tests`(등록 `minTests` 136 ≠ 시험 수 139 — 아래 제안 적용 전 예상된 불일치, 다른 실패 없음). 제안 적용 복사본에서 `test_module_registry` 15 OK.
- CVE-SCAN(04:48:55Z): PASS — internal-admin runtime 106(S2와 같음 — S3 빌드 파일 변경 없음), 취약점 0, 프론트 147·canary 6.
- GRADLE-DEPS-INTERNAL-ADMIN: exit 0, runtime에 testcontainers·spring-session 0줄.
- 실행 비트: HEAD 대비 mode 변화 없음(harness/scripts 4개 755).
- commands.json 제안(Coordinator 적용 대상 — 이 단계에서 수정하지 않음): HARNESS-SELFTEST `minTests` 136 → **139**, GRADLE-CHECK `minTests`·TEST-SUMMARY 하한 2514 → **2630**(TEST-SUMMARY-FRONTEND 14 그대로).

### 20.8 S3 체크포인트 리뷰(PASS_WITH_FIXES) 반영(2026-10-25 KST — 이 절이 §20.1~20.7과 다르면 이 절이 우선)

commands.json은 Coordinator가 반영(SELFTEST 139, GRADLE-CHECK·TEST-SUMMARY 2630). 아래 각 항목의 RED는 "해당 수정만 되돌린 상태에서 새 시험이 실패"로 확인했다(변이 F1~F9 + S3-M3 재확인, 10/10 RED, 07:44~07:54Z — 실패 시험 이름 확인, 모두 원본 복원).

| 항목 | 반영 | 증거 |
|---|---|---|
| **M-1** 정지 역할만 보유한 대기 계정의 1인 재발급 | domain 시험 행(재설정 뒤 활성 0·정지 {APPROVER} → `ROLE_BEARING_ACCOUNT`) + 서비스 시험(`credentialResetPending` 계정에 SECURITY_ADMIN 재발급 → `ROLE_BEARING_ACCOUNT`, security_version·대기 사유·코드 행 불변, 감사 `ENROLLMENT_CODE_ISSUED:REJECTED:reason=ROLE_BEARING_ACCOUNT` 1행) | F1(`|| suspendedRoles.isNotEmpty()` 제거) → 두 시험 RED |
| **M-2** Fixture 정리 | (a) `bootstrapped(role)`은 **SECURITY_ADMIN 또는 APPROVER 단일 역할만**(그 밖은 `require` 실패). (b) 그 밖의 역할·겸직은 `rolesGrantedBypassingApproval_untilS5(...)`(행위자 `adm_ZZZ…`·승인 ID `apr_BYPASS_UNTIL_S5` 자리표시의 GRANT 전이 — 이름에 우회 표시, 저장 측 `AdminAccountStorage`도 같은 이름). `credentialResetPending(purpose, active)`는 호출부가 준비 경로를 드러내게 계정을 받는다. Fixture 주석을 실제 사용과 맞춤. (c) **S5에서 핵심 규칙 시험의 우회 준비를 `withRoleViaApproval`로 교체** — 사용처: `AdminAccountSpec`(재설정·역할 회수 Given), `SecurityVersionEventsSpec`, `AdminPermissionSpec`(역할 행·겸직·비활성), `AdminAccountPersistenceSpec`(왕복·역할 이벤트), `AdminAccountCommandSpec`(역할 회수·역할 이벤트 재생·잠금 순서), `EnrollmentFlowSpec`(TOTP_REENROLL·요소 누락 행) | 컴파일·전체 통과 |
| **M-3** 경합 시험 강화 | 시험 지원 `RowLockHolder`(세 번째 연결 — root, 행 `FOR UPDATE` 유지 → `performance_schema.data_lock_waits`로 대기 TX 수 확인 → 해제). ① 같은 코드 동시 완료: 대상 계정 행을 잡고 두 완료 TX가 **둘 다 대기**인 것을 확인한 뒤 해제 → 한 건만 완료, 패자 `CODE_INVALID`. ② "정지 대 완료"를 두 결정적 시험으로 분리: 코드 행을 잡아 **정지가 계정 잠금을 쥔 채 코드 행에서 대기**(→ 완료는 계정 행 대기)하게 하면 완료 `NOT_APPLICABLE`·코드 폐기, 반대로 **완료가 계정 잠금을 쥔 채 코드 소비에서 대기**하게 하면 ACTIVE 계정 정지·코드 사용됨. ③ 잠금 순서: 두 SECURITY_ADMIN이 서로를 대상으로(요청자·대상 역순) 역할 회수, 낮은 actor 행을 잡아 둘 다 대기시킨 뒤 해제 4회 → `INNODB_METRICS lock_deadlocks` 증가 0. ④ TOCTOU: 요청자 행을 잡아 정지 요청을 대기시키고 그 사이 세 번째 연결이 요청자를 SUSPENDED로 커밋 → 요청은 잠근 행으로 판정해 `DENIED`·대상 불변 | F2(`lockAll`의 `.sorted()` 제거) → ③ RED(교착 발생 — 재시도로 결과는 같아도 교착 지표로 검출). F3(권한을 잠금 전 비잠금 읽기로) → ④ RED. F9(정지의 코드 폐기 제거) → ② RED. 잡지 못한 변이 없음 — S5 `ApprovalLockOrderSpec`은 승인 행 포함 순서에 같은 방식(대기 확인 + 교착 지표)을 쓴다(의무) |
| **m-1** D-67 변경 | 완료 TX의 판정 순서: 코드 행 재확인(없음·대상/사유 불일치·**사용됨** → `CODE_INVALID`, 지표만) → domain 상태·사유 검사(정지·근거 교체 — 대상 확정 → `NOT_APPLICABLE`) → 원자 소비(만료는 TX 안 서버 시계 — 0이면 `CODE_INVALID`). `NOT_APPLICABLE`은 주 TX rollback 뒤 **별도 TX**로 `ENROLLMENT_COMPLETED:REJECTED:reason=NOT_APPLICABLE`(행위자 ANONYMOUS, 대상 = 계정) | F4(별도 TX 감사 제거) → 정지·재발급 시험 RED, F5(사용됨 검사 제거 → 재사용이 적용 불가로 감사됨) → 이중 완료·동시 완료 시험 RED, S3-M3 재확인 RED |
| **m-2** D-66 보완 | 정지 감사 detail = `revokedCodes=n;revokedBases=<근거,…>`(근거는 비밀 아님 — `enc_`/`apr_`+ULID·`episode:n`, detail 정규식 문자 집합 안). 최대 8개(40자 × 8 + 접두 ≈ 380자 < 500), 넘으면 `revokedBasesTruncated=true`. 저장 Port `revokeUnused`가 폐기한 코드의 근거를 발급 순으로 돌려준다(대상 계정 잠금 아래 — 조회 수 = 폐기 수 검사) | F6 → 정지 시험 RED |
| nit 감사 조회 시계 | `AdminAuditQueryService`가 `Clock.systemUTC()` 대신 주입 `AuditClock`(audit required Port, `SystemAuditClock`). account의 `AdminClock`은 다른 모듈 required Port라 Modulith 경계상 쓰지 않는다 | 컴파일·통과 |
| nit 비밀번호 최소 길이 | **Unicode code point 수**(서로게이트 쌍 = 1자)로 센다. 최대는 UTF-8 바이트(72) | F8(UTF-16 단위로 세기) → RED(이모지 11자 = 22 단위가 통과) |
| nit DB CHECK | `ck_admin_account_suspend_pair CHECK ((status = 'SUSPENDED') = (status_before_suspend IS NOT NULL))`(V1 제자리 — 미커밋) | `AdminSchemaSpec` 3819 시험, F7 RED |
| m-4 기록 | **S5 재활성 설계 항목**: LOCKED 계정을 정지하면 `locked_at`이 비워진다(domain `copy`가 LOCKED가 아니면 null) → `ACCOUNT_REACTIVATE`가 LOCKED로 되돌릴 때 잠금 시각을 무엇으로 둘지(원래 시각 보존 열 추가 / 재활성 시각 / 감사에서 복원) S5에서 결정 | 기록만 |

- 결과: internal-admin 196 → 202(AdminAccountSpec 26, AdminAccountCommandSpec 13, EnrollmentFlowSpec 19, AdminSchemaSpec 16).

## 21. S4 구현 기록(2026-10-25 KST, 2단계 Implementer — 자기 완료 판정 아님)

범위: §10 S4(인증 HTTP — 2단계 로그인·경로 동등성·자격 변경 경합, Spring Session·쿠키·세대 검사·만료·익명 세션, CSRF, IP, 배치 모드 가드, 오류 비노출) + S3 리뷰 이월(m-3 감사 조회 단일 경로·HTTP 시험, m-5 LIVE BCrypt 하한, Spring Session 운영 의존성·문자열 세션 값, `__Host-PCADMIN`, `GET /api/auth/csrf`, 404·500 JSON, `AnonymousSessionGrowthSpec`, 없는 계정 HMAC 감사, `PersistenceFailure` → 409). 승인(S5)·1회성 명령(S6)·화면(S7)·지표(S8)는 범위 밖.

### 21.1 변경

| 영역 | 내용 |
|---|---|
| 의존성 | catalog `spring-session-jdbc`(S2 시험 전용) → **`spring-boot-starter-session-jdbc`**(Boot 4.1.1 BOM — spring-session 4.1.1, runtime). Kafka/Redis 없음 — 세션 저장은 ADMIN DB(D-ADMIN-004), 안전성은 DB 행 잠금·세대 값이 담당 |
| `account.application` | provided `AdminLoginUseCase`(비밀번호·TOTP·로그아웃), `AdminSessionQuery`(+`AdminSessionView`·`AdminSessionPolicy`), `AdminSessionState`(세션 문자열 상태 — 읽기 실패 = 손상), `AuditViewUseCase`(감사 조회 단일 경로), `AdminRetryLaterException`. required `AdminCredentialReader`·`AdminSessionReader`(jOOQ — 비잠금 읽기). 서비스 `AdminLoginService`·`AdminSessionQueryService`·`AuditViewService`, `retryLaterOnConflict`(1213·1205·version 충돌 → 409, 명령·등록 완료·로그인 TX에 적용) |
| `account.adapter` | query `JooqAdminCredentialReader`·`JooqAdminSessionReader`. web `AuthController`(`/api/auth/csrf`·`password`·`totp`·`logout`·`me`)·`AuditController`(`/api/audit` — 쿼리는 필터뿐)·`AccountWebErrors`(409 `RETRY_LATER`·503 `AUDIT_UNAVAILABLE`). integration `DeploymentMode`·`AdminDeploymentProperties`·`LiveModeStartupGuard`(+`LiveModeGuardPassed`), `AdminSecurityProperties.session`(세션 한도) |
| `audit` | provided `AdminSecurityEventRecorder`(자기 TX — 인증 경로 IP 거부 감사), `AuditClock` |
| `security` | `AdminSecurityContextRepository`(요청마다 세션 문자열 상태 + 계정 현재 행 → `SecurityContext`, 세션에 저장 안 함), `StringSessionCsrfTokenRepository`(토큰 문자열만, 세션 있을 때만 저장 — S1 `SessionBoundCsrfTokenRepository` 대체), `IpAllowlist`·`IpAllowlistFilter`·`AdminNetworkProperties`, `JsonErrorResponses`(401 사유 code), `SecurityConfig`(로그인 경로 허용·`NullAuthenticatedSessionStrategy`·logout filter 끔). `security.session.AdminSessionConfiguration`(속성 질의 5개 소문자 표·문자열 전용 변환·쿠키 직렬화기)·`StringOnlySessionConversion` |
| `console.adapter.web` | `JsonErrorAdvice`(MVC 표준 오류 → 상태별 code, 그 밖 500 `INTERNAL_ERROR`)·`JsonErrorController`(`/error` — BasicErrorController 대체). 본문은 미리 정한 Content-Type의 고정 JSON 문자열(브라우저 `Accept: text/html`에도 406 없음) |
| 설정 | `spring.session.*`(table-name `admin_session`, initialize-schema never, save-mode on-set-attribute, flush on-save, 1분 정리, timeout 10m), `server.forward-headers-strategy: none`, `paycore.admin.deployment-mode`(env 필수)·`network.ip-allowlist`(env 필수)·`security.session.*`(Demo), `.env.example`에 `PAYCORE_ADMIN_DEPLOYMENT_MODE`·`PAYCORE_ADMIN_IP_ALLOWLIST` |
| 시험 | 새 `LoginServiceSpec` 7·`LoginHttpSpec` 10·`AnonymousSessionGrowthSpec` 1·`IpAllowlistSpec` 5·`ErrorNonDisclosureSpec` 1·`AuditQueryHttpSpec` 4·`LiveModeStartupGuardSpec` 5 = 33, `ArchitectureSpec` +1(감사 조회 API는 account.application만), `SpaCsrfSpec`(MockHttpSession → 세션 쿠키 이어 보내기). 시험 지원 `LoginReadyAccounts`(실제 BCrypt·실제 암호화 TOTP로 로그인 가능한 계정 — 역할 규칙은 Fixture와 같음), `AdminHttpClient`(쿠키 저장·XSRF 헤더), `LoginProbeConfig`(BCrypt 비교·새 TX·감사 append 계측과 비교 중 다른 연결 커밋 고리) |

### 21.2 구현 결정(가역 — 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| D-74 | 세션 상태는 문자열 속성 6개(`paycore.admin.stage`·`actorId`·`securityVersion`·`sessionGeneration`·`authenticatedAt`·`stepExpiresAt`) + CSRF 토큰 문자열. Spring Session 변환은 String ↔ UTF-8만(그 밖 값 저장은 실패) | §17.5 Java 역직렬화 경로 제거. 손상 값은 문자열로만 읽혀 형식 검사에서 거부 → 세션 폐기 + 401 `SESSION_EXPIRED`(시험) |
| D-75 | 401 사유: 세대 불일치·비활성 = `SESSION_REPLACED`(다른 곳 로그인·잠금·정지·역할 회수·재설정 — 세대 +1 사건 전체), idle 만료(쿠키는 왔는데 세션 없음)·절대 한도·손상 = `SESSION_EXPIRED`, 그 밖 `UNAUTHENTICATED` | D-ADMIN1-001 안내 구분. 세대 +1 사건의 세부 원인은 응답에 내지 않는다 |
| D-76 | 로그인 감사: 알려진 계정은 행위자 = 그 계정(주장된 신원)·대상 = 그 계정, `LOGIN_PASSWORD`(SUCCESS·FAILED·LOCKED·NOT_COUNTED·REJECTED `reason=STALE`)·`LOGIN_TOTP`·`LOGIN_SUCCEEDED`·`LOGOUT`. 잠금은 같은 행의 결과 `LOCKED`(별도 `ACCOUNT_LOCKED` 행 없음 — 분기마다 감사 1행 경로 동등성) | m-4 시험이 감사 append 수를 고정 |
| D-77 | Security의 인증 시점 세션 전략(세션 고정 방지·`CsrfAuthenticationStrategy`)을 끄고(`NullAuthenticatedSessionStrategy`) 웹 adapter가 로그인 각 단계에서 세션 ID·CSRF 토큰을 직접 재발급 | `SecurityContext`를 세션에 두지 않으므로 Security가 요청마다 "새 인증"으로 오판해 CSRF 토큰을 지우는 문제(실측 — 시험 대역 인증에서 재현)를 막는다. 재발급은 `LoginHttpSpec`이 고정(변이 G9·G10) |
| D-78 | IP 허용 목록 filter는 Spring Session·Security보다 앞(최상위 순서), 감사 기록기는 처음 거부할 때 얻는다(lazy). 인증 경로 거부만 익명 감사(`IP_DENIED`, detail `path=AUTH`), 감사 실패해도 거부 응답 유지(로그 분류만) | 웹 서버 시작 때 DataSource를 끌어오지 않게(실측: 시험의 동적 DB 속성보다 먼저 만들어져 기동 실패). 거부는 안전 방향 |
| D-79 | **LIVE 가드(잠정 — Q-ADMIN-SEC-VALUES 답변 전)**: LIVE에서 저장소 Demo 키(SHA-256 지문 2개), Demo 보안 수치(실패 한도·코드/진행 유효·최소 길이·BCrypt 12·Demo issuer·세션 한도 4개) 중 **하나라도** 같으면 기동 실패, BCrypt cost **하한 12**(m-5) 미만도 실패. 보고는 설정 이름만 | LIVE Gate 차단(운영 수치 미결 — 새 정책 승인 아님). 하한 12는 Demo 값과 같은 수의 잠정 하한이며 운영 하한은 사용자 답변으로 확정 |
| D-80 | 감사 조회 범위는 `AuditViewUseCase` 하나가 계정 현재 행(jOOQ PK)으로 계산, audit 조회 API(`AdminAuditQuery`)는 account.application만 의존(ArchUnit) | S3 리뷰 m-3. 요청은 범위를 담을 수 없다 |
| D-81 | 비밀번호 단계 (a)는 jOOQ 비잠금 읽기(TX 밖), TOTP 단계·로그아웃은 로컬 TX 1회. TOTP 단계 실패 계수는 같은 TX(상태 변경이 계수뿐) | 경로 동등성 시험이 새 TX 수를 셈 |
| D-82 | 비밀번호 JSON 본문은 문자열로 받는다(JSON 역직렬화가 불변 String을 만든다 — 메모리에서 지울 수 없음), Use Case에는 `CharArray`로 넘긴다 | 수용 한계 기록(요청 본문 로그 없음·toString 마스킹) |

### 21.3 RED → GREEN·핵심 변이

- RED: S4 시험을 먼저 작성 → 08:08:57Z 컴파일 RED(`AdminSecurityProperties.Session`·`LiveModeStartupGuard`·`DeploymentMode`·`IpAllowlist` 없음). 구현 뒤 첫 전체 실행(08:27Z) 3건 실패 — 실제 웹 서버 시험이 IP filter의 DataSource 조기 생성으로 기동 실패(D-78), `SpaCsrfSpec` 시험 대역 인증에서 Security `CsrfAuthenticationStrategy`가 세션 토큰을 지움(D-77), 컨테이너 `/error`가 `Accept: text/html`에 406 → 수정 → 08:39:39Z GREEN.

### 21.4 핵심 변이(S4) — 16/16 RED(08:42~08:57Z, 실패 시험 이름 확인, 모두 원본 복원)

G1 상태 검사보다 BCrypt 결과를 먼저 적용(LOCKED·SUSPENDED·PENDING 로그인) → 경로 동등성·합산 잠금 시험 · G2 (a)~(b) 사이 `security_version` 변화 무시 → m-D 시험 · G3 없는 계정 BCrypt 생략 → 비교 수 · G4 없는 계정 감사 생략 → 감사 수·HMAC · G5 TOTP 성공 시 세대 +1 생략 → 새 로그인 우선(Use Case·HTTP) · G6 요청마다 세대 비교 생략 → `SESSION_REPLACED`(새 로그인·역할 회수) · G7 절대 한도 생략 · G8 손상 세션 폐기 생략 · G9 로그인 단계 세션 ID 재발급 생략(고정) · G10 로그인 단계 CSRF 재발급 생략 · G11 csrf 엔드포인트가 재사용 대신 회전 · G12 문자열 전용 변환 제거(Java 직렬화 `ACED`) · G13 IP filter가 `X-Forwarded-For` 신뢰 · G14 LIVE BCrypt 하한 생략(m-5) · G15 감사 범위를 호출자 값으로(m-3) · G16 500 본문에 예외 메시지.
- 잡지 못한 변이: 없음. `retryLaterOnConflict`의 서비스 경계 적용(실제 1213·1205를 로그인·명령 TX에 일으키는 시험)은 강제 교착 주입이 승인 TX와 함께 S5 `ApprovalLockOrderSpec`의 의무다 — S4는 웹 매핑(409 본문)만 시험(`ErrorNonDisclosureSpec`).

### 21.5 8대 원칙(S4 범위) 증거

| 원칙 | 증거 |
|---|---|
| Financial Consistency | 해당 없음 |
| Transaction Safety | 비밀번호·TOTP 단계 각각 로컬 TX 1회(상태·계수·감사 함께), 외부 호출 없음, 1213·1205 → 전체 rollback 뒤 409(`retryLaterOnConflict`) |
| Idempotency | TOTP 단계 재사용 거부(같은 단계 코드 두 번 → 실패·계수), CSRF 토큰 발급 재사용(세션당 하나) |
| Concurrency Control | 두 세션의 동시 TOTP 단계 → 세대가 행 잠금으로 직렬화, 행의 세대 = 마지막 성공(SingleSessionRace), 자격 변경 경합(m-D) 결정적 재현 |
| Recoverability | Spring Session JDBC(재기동 뒤 세션 유지 — 세션 표가 원천), 손상 세션은 폐기 후 재로그인(500 반복 없음 — §17.5 실측 문제 해소) |
| Reconciliation | 해당 없음 |
| Auditability | 로그인 각 분기 감사 1행(없는 계정 HMAC), 로그아웃·인증 경로 IP 거부 감사, 감사 조회 범위는 현재 행 |
| Observability | 지표는 S8. 오류 응답 고정 code·로그는 예외 클래스 이름만 |

### 21.6 남은 것
- S5: 승인(재인증 TOTP는 `AuthenticationFailureRecorder` 별도 TX), `withRoleViaApproval`, `ApprovalLockOrderSpec`, 재활성의 `locked_at`(§20.8 m-4). S7: 화면(오류 code 매핑). S8: 지표(`paycore.admin.login{step,result}` 등)·`AdminNonDisclosureSpec`·management 포트(D-15)·HSTS.
- 실제 HTTP(내장 Tomcat) 시험은 `SpaServingSpec`뿐이고 인증 흐름은 MockMvc(실제 Spring Session JDBC·실제 MySQL·실제 filter 체인)다. `__Host-` 쿠키는 HTTPS(또는 localhost를 보안 문맥으로 취급하는 브라우저)가 전제 — 개발 서버 구성은 S7.

### 21.7 S3 수정 + S4 체크포인트(2026-10-25)
- 전체 `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(WSL 분리, 08:58:41Z 시작, 24m25s): **BUILD SUCCESSFUL**. Kotlin: master-api 320·payment-api 1766·financial-batch 26·internal-admin **236**(S3 수정 뒤 202 + S4 34)·institution-simulator 210·test-support 112 = **2670**(실패·오류·skip 0), Vitest 14. 등록 하한(2630·14)으로 TEST-SUMMARY·TEST-SUMMARY-FRONTEND PASS.
- HARNESS-SELFTEST(09:23:54Z): Ran 139 OK(하네스 변경 없음).
- CVE-SCAN(09:24:25Z): PASS — internal-admin runtime 106 → **111**(spring-session-core·spring-session-jdbc·spring-boot-session·spring-boot-session-jdbc·starter), 취약점 0, 프론트 147·canary 6.
- GRADLE-DEPS-INTERNAL-ADMIN: exit 0. runtime에 `spring-boot-starter-session-jdbc 4.1.1`·`spring-session-jdbc 4.1.1`·`spring-session-core 4.1.1`(모두 Boot BOM 관리), testcontainers 0줄.
- 실행 비트: HEAD 대비 mode 변화 없음(harness/scripts 4개 755).
- commands.json 제안(Coordinator 적용 대상 — 수정하지 않음): GRADLE-CHECK `minTests`·TEST-SUMMARY 하한 2630 → **2670**(HARNESS-SELFTEST 139·TEST-SUMMARY-FRONTEND 14 그대로). 제안 적용 복사본 `test_module_registry` 15 OK.

### 21.8 S4 체크포인트 리뷰(PASS_WITH_FIXES) 반영(2026-10-25 KST — 이 절이 §21.1~21.7과 다르면 이 절이 우선)

commands.json은 Coordinator가 반영(GRADLE-CHECK·TEST-SUMMARY 2670), D-79 수용. RED: 시험을 먼저 작성 → 09:46:11Z 컴파일 RED(새 API 없음) → 컴파일만 되는 빈 구현(stub)으로 09:51Z 실행해 **업무 RED 20건**(아래 각 시험; `SpaCsrfSpec` 7건은 `csrf()` 시험 post-processor가 공유 CsrfFilter 저장소를 객체 저장으로 바꾼 시험 측 문제 — 실제 토큰 흐름으로 교체) → 구현 → 10:06:46Z GREEN. 변이 H1~H15(H1'·H14' 재실행 포함) **15/15 RED**(10:07~10:22Z, 실패 시험 이름 확인, 모두 원본 복원).

| 항목 | 반영 | 증거 |
|---|---|---|
| **M-1** D-80 규칙이 검사 0건 | 원인: 첫 작성 때 셸 치환으로 `$BASE_PACKAGE`가 사라져 `resideInAPackage("..")`·`".account.application.."`·FQN `".audit.…AdminAuditQuery"`가 됨(어떤 의존도 맞지 않음). `"$BASE_PACKAGE.…"`로 고치고 `allowEmptyShould(false)`. 위반 fixture `archfixture.adapter.web.AuditQueryInWebAdapterFixture`(web adapter가 `AdminAuditQuery` 주입·`canViewAll = true`)가 규칙을 실패시키는지 `shouldThrow`. 전수 점검: internal-admin·전체 저장소 `*.kt`에서 점으로 시작하는 패키지 식별자·기본 패키지 없는 FQN 0건(나머지 규칙은 `$BASE_PACKAGE` 사용 + 공통 규칙은 test-support meta-test) | 변이 H1'(첫 작성본 그대로 복원) → fixture 시험 RED |
| **m-1** 로그아웃 | 결정: 세션 폐기는 **finally에서 항상**, 감사 실패는 공통 처리가 로그 분류 + 500 `INTERNAL_ERROR`(세션은 이미 폐기) — D-78과 같은 "안전 방향" 원칙(D-83) | `LogoutAuditFailureSpec`(감사 실패 주입 → 500·세션 행 0·다음 요청 401), H2 RED |
| **m-2** 오류 매핑 | `HttpMessageConversionException`·`TypeMismatchException`(MethodArgumentTypeMismatch 포함) → 400 `BAD_REQUEST`. `AccessDeniedException`은 advice가 다시 던져 Security가 403 `FORBIDDEN` | `ErrorNonDisclosureSpec` 행(형 변환·읽을 수 없는 본문·컨트롤러 안 거부), H3·H4 RED |
| **m-3** 바이트 상한 초과 비밀번호 | **실측(Spring Security 7.1.1 `BCryptPasswordEncoder`)**: 73바이트 `encode`는 `IllegalArgumentException("password cannot be more than 72 bytes")`, 그러나 73바이트 `matches`는 앞 72바이트의 해시와 **true**(절단 비교). → 로그인 서비스가 상한 초과를 저장 해시와 비교하지 않고 같은 cost 더미 비교 1회 + 감사 1행 + 틀린 비밀번호로 계수(D-84) | `LoginServiceSpec` probe 73바이트 행(비교 1·TX 1·감사 1), 72바이트 비밀번호 계정에 73바이트 입력 거부, `AccountCryptoAdaptersSpec`이 라이브러리 절단 동작을 고정. H5 RED |
| **m-4** 한도 도달 선판정 | domain `hasReachedFailureLimit`(설정 한도가 낮아져 ACTIVE인데 계수 ≥ 한도), `recordAuthenticationFailure`는 그 경우 계수·잠금 없음. 비밀번호 단계는 상태·`security_version` 다음, BCrypt 결과 적용 전에 거절(`NOT_COUNTED reason=LIMIT`) | domain·서비스 시험, H6·H7 RED |
| **m-5** LIVE 가드 | TOTP 창 Demo 값(1)·`spring.session.timeout` Demo 값(10분, 미설정도 거부) 추가. LIVE 허용 목록의 `0.0.0.0/0`·`::/0`(접두 0) 거부(security — 같은 `paycore.admin.deployment-mode` 키). **D-79 보강**: Demo 값 12와 하한 12가 겹쳐 LIVE에서 실제 허용되는 최소 cost는 **13**. **구조적 한계(LIVE Gate 확인 항목)**: `deployment-mode=DEMO`로 운영에 띄우면 가드 전체가 우회된다 — 배치 절차·운영 환경 변수 검증으로 막아야 한다 | `LiveModeStartupGuardSpec`(전체 목록 정확 비교), `IpAllowlistSpec`, H8·H9·H10 RED |
| **m-6** IP 거부 감사 범람 | **S8 이월 위험(기록만)**: 허용 목록 밖에서 인증 경로를 대량 호출하면 거부마다 감사 1행 — 지표·속도 제한·집계 감사는 S8 | — |
| **m-7** F2 결정적 검출 | `RowLockHolder.grantedRowLocksOfWaiters`: 대기 중 `performance_schema.data_locks`로 "기다리는 TX가 `admin_account`의 다른 행 잠금(GRANTED)을 쥐고 있지 않다"를 단언(교착 지표 단언도 유지). **S5 `ApprovalLockOrderSpec` 의무도 이 방식**(대기 확인 + data_locks 단언 + 교착 지표) | H15(`.sorted()` 제거) 1회 실행 RED |
| **m-8** 시험 공백 | idle 만료(쿠키는 있는데 세션 없음 → `SESSION_EXPIRED`), 밖 IP의 미인증 `/api/audit` → 403 `IP_DENIED`(401 아님 — filter 순서), `server.forward-headers-strategy=none` 단언, filter 안 예외 → 내장 Tomcat 실제 HTTP에서 JSON 500(`SpaServingSpec` + 시험 전용 `FilterBoomConfig`) | H11 RED |
| **m-9** 가드 시험 | 다른 설정은 모두 두고 모드만 뺀 실패의 원인이 `paycore.admin.deployment-mode is required`임을 단언, "모든 Demo 값"은 전체 목록 정확 비교 | — |
| nit | `XsrfCookieFilter` KDoc 실제와 일치. AUTHENTICATED 세션의 `security_version`을 요청마다 현재 행과 비교(불일치 `SESSION_REPLACED` — 세대 없이 security_version만 오르는 사건도 세션 종료). `verifyTotp`의 단계 만료·형식 오류는 계수 없이 `LOGIN_TOTP:REJECTED`(`reason=STEP_EXPIRED`·`FORMAT`) 1행. 비밀번호 `CharArray`는 finally에서 지움. `RowLockHolder` 대기 수는 holder TX가 막는 대기로 한정(`performance_schema.data_locks`로 holder TX ID — `innodb_trx`는 0.1초 캐시라 쓰지 않음). `IpAllowlist`: IPv4 octet 0~255 검증으로 이름 해석 가능성 제거(`isNumericLiteral`), 오류 메시지 위치를 `mapIndexed`로 | H12·H13·H14' RED |

- 결정 추가: **D-83** 로그아웃은 안전 방향 — 세션 폐기 우선, 감사 실패는 500(로그 분류). **D-84** 바이트 상한 초과 비밀번호는 저장 해시와 비교하지 않음(BCrypt 72바이트 절단 실측).
- 결과: internal-admin 236 → 251.

## 22. S5 구현 기록(2026-10-25~26 KST, 2단계 Implementer — 자기 완료 판정 아님)

범위: §10 S5(`approval` — 판정표·단일 TX orchestrator·전역 잠금 순서·1213/1205·TOTP 재인증(별도 실패 TX)·승인자/요청자 재검사 순서·중복 PENDING(동시)·만료·거절/철회 TX·승인 vs 종료 경합, ADMIN 소유 명령 6종(1인 명령은 S3), 재설정 역할 정지·코드 1회 표시·재활성(H-A 배제 4행), 마스킹 해제 틀, jOOQ Reader) + 이월 의무(§20.8 m-4 `locked_at`, §20.6·§20.8 M-2 `withRoleViaApproval`, §21.4·§21.8 m-7 `ApprovalLockOrderSpec` 방식·`retryLaterOnConflict` 서비스 경계 시험, S2 리뷰 M-1 같은 대상·다른 종류 동시 생성 1213 0회). 1회성 명령(S6)·화면(S7)·지표/비노출 묶음(S8)은 범위 밖.

### 22.1 재개 시점(2026-10-26 07:06 KST)과 TDD 순서의 정직한 기록

- 이전 구현 세션이 S5 도중(마지막 쓰기 2026-10-25 20:14 KST, `apps/internal-admin/build.gradle.kts`) 멈췄고 문맥이 없다. 재개 시 작업 트리에 S5 코드가 대부분 있었다: `approval` 모듈 전체(domain `ApprovalRequest`·`ApprovalKind`·`ApprovalStatus`·`ApprovalDecision`·`ApprovalParticipants`, application `ApprovalService`·`ApprovalQueryService`·Port, adapter persistence·query·web·batch·integration), account 쪽 `AdminOwnedCommandExecutor`/`AdminOwnedCommandService`·`RecoveryEpisodeStore`·`UnmaskUseCase`/`UnmaskService`/`UnmaskController`, 시험 `ApprovalDecisionTableSpec` 8·`AdminOwnedCommandSpec` 17·`ApprovalRaceSpec` 6·`ApprovalLockOrderSpec` 3·`ApprovalFailureInjectionSpec` 4·`ApprovalHttpSpec` 3과 Fixture 교체(`AdminAccountFixture.withRolesViaApproval`·`ApprovalPath`).
- 재개 첫 실행(`:apps:internal-admin:test`, 22:06~22:13Z, 로그 `~/gate-logs/s5-resume-20261025T220636Z.log`): 컴파일 통과·**299/0**(S4 끝 251 + 48).
- **그 코드의 "시험 먼저 → RED" 순서는 남은 기록이 없어 보일 수 없다**. 그래서 이 단계의 업무 RED 증거는 (1) 재개 뒤 새로 쓴 시험의 RED(§22.4)와 (2) 재개 전 코드 전체에 대한 핵심 변이 21건(§22.5 — 동작을 하나씩 제거하면 해당 시험이 실패)으로 대신한다.
- `build.gradle.kts`의 마지막 변경(이전 세션): 시험 JVM `spring.test.context.cache.maxSize=4`(D-90) — 의존성 변경 아님.

### 22.2 변경(재개 전 코드 + 재개 뒤 보강)

| 영역 | 내용 |
|---|---|
| `approval.domain` | `ApprovalRequest`(판정표: PENDING만 결정, 만료는 `expires_at <= TX 시각`, 승인자 ≠ 요청자·대상, 종료 상태는 `ALREADY_DECIDED`, `reconstitute`가 ADMIN 종류의 `APPROVED`·`EXECUTING`·`EXECUTION_UNKNOWN` 거부), `ApprovalKind` 6종(부여 역할·원인 필요 여부, SA·APPROVER 활성화 가능 종류, 코드 발급 종류), `ApprovalParticipants`(H-A `reactivationConflict`) |
| `approval.application` | `ApprovalService`(요청·승인·거절·철회·만료 — §4.7.3 orchestrator, 로컬 TX `maxAttempts = 1`), `ApprovalQueryService`(조회 권한 = 계정 현재 행). Port: `ApprovalRequestRepository`(+ **재개 뒤** `immutableColumns`·`dueForExpiry` 이동, D-86), `ApprovalRequestListReader`(조회 전용으로 축소), `ImmutableApprovalColumns`(파일 분리), `ApprovalEnvironment` |
| `approval.adapter` | persistence `JpaApprovalRequestEntity`·`JpaApprovalRequestRepository`(1062 `pending_key`·1213·1205·version 분류), query `JooqApprovalRequestListReader`(목록·상세), web `ApprovalController`(`/api/approvals` 생성·승인·거절·철회·목록·상세 — 고정 code, 모든 응답 `no-store`, 재설정 코드는 승인 응답 본문 1회), batch `ApprovalExpiryJob`(표시용), integration `ApprovalSettings`(유효 24h Demo — D-SV) |
| `account`(provided 확장) | `AdminOwnedCommandExecutor`(MANDATORY — `lockParticipants` 오름차순·`consumeStepUpTotp`·`execute`(증거 재검사 후 6종 실행)·`rolesSuspendedBy`·`lockOpenRecoveryEpisode`), `UnmaskUseCase`(틀 — 권한·사유·직전 TOTP·감사) |
| domain `AdminAccount` | `unlock`·`reactivate`(정지 직전 상태 복원 — **LOCKED였으면 `locked_at` 보존**, §20.8 m-4 → D-89)·`reactivateRoles`(원인 정지 역할만)·`beginCredentialReset`(대기 계정 재발급·확정 자격 없으면 ENROLL) |
| 재개 뒤 구현 | `ApprovalService.withdraw`의 만료·거부 감사(D-87), `UnmaskService` 권한 없음 감사(D-88), Reader 사용처 ArchUnit 규칙 + 위반 fixture `archfixture.application.ReaderInCommandServiceFixture`(D-86) |
| 시험(재개 뒤 추가·강화) | `ApprovalLockOrderSpec` 3 → 9(1213을 승인·거절·철회 3종으로, 승인 행 자체 1205, 로그인 비밀번호 단계 1205 → HTTP 409, TOTP 단계 1205, 정지 1205×재시도 → 409, 정지 1213 희생 → 새 TX 재판정 1회 적용, 등록 완료 1205 → 코드 미소비·재시도 성공), `ApprovalRaceSpec` 6 → 7(`ApprovalConcurrency` 신규, `RevokeDuringApproval` "승인 먼저" 순서를 순차 실행에서 **결정적 경합**으로 교체 — 기대 결과는 같고 강화), `AdminOwnedCommandSpec` 17 → 20(소유 재검사 계약 직접 시험, 대기 계정 2차 재설정의 1차 코드 폐기, 철회 감사·만료 철회 감사), `ApprovalHttpSpec` 3 → 5(거절·철회·목록 권한 HTTP, 마스킹 해제 사유 상한·권한 없음 감사), `ArchitectureSpec` 26 → 28. `ApprovalFailureInjectionSpec`의 0단계 고리는 Reader 대신 저장 Port를 감싼다(시험 기대값 불변) |

### 22.3 구현 결정(가역 — 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| D-85 | §4.7.3의 `ApproveAdminOwnedRequestService` 역할은 `ApprovalService` 하나가 요청·승인·거절·철회·만료와 함께 맡는다. 승인 TX는 재시도하지 않는다(`maxAttempts = 1`) — 1213·1205·version 충돌은 전체 rollback 뒤 `RETRY_LATER`(409) | 승인자의 TOTP 입력을 서버가 다시 쓰지 않는다(재시도 = 사용자 재요청). 1인 명령의 재판정 재시도(D-69)와 구분 |
| D-86 | **Reader 사용처 규칙**(§5 "Reader 사용처는 조회 서비스뿐"): `<module>.application.required.*Reader`는 `*QueryService`·`AuditViewService`와 adapter.query만 의존. 예외 1건 = D-81(`AdminLoginService → AdminCredentialReader`, 비밀번호 단계 (a) 비잠금 읽기). 그래서 승인 TX 0단계 불변 열 읽기와 만료 job 후보 읽기를 jOOQ Reader에서 **`ApprovalRequestRepository`(JPA 스칼라 JPQL)**로 옮겼다 | 명령 판정 경로가 조회 모델을 쓰지 않게. 0단계 읽기는 스칼라 조회라 Entity가 영속성 컨텍스트에 올라가지 않고, 1단계 `find + refresh(PESSIMISTIC_WRITE)`가 최신 커밋 값을 읽어 0↔1단계 대조(rev 0.5 n-1)가 그대로 성립(무결성 행 GREEN, 변이 S5-M13 RED) |
| D-87 | 철회 명령의 만료(`APPROVAL_EXPIRED`)·거부(`APPROVAL_DENIED:REFUSED reason=NOT_REQUESTER`)도 감사 1행(승인·거절과 같은 형식, 같은 TX) | 재개 전 코드는 철회 경로만 상태 변경(만료)·거부를 감사하지 않았다 — "모든 상태 변경·거부 감사"(D-65)와 불일치 |
| D-88 | 마스킹 해제 권한 없음도 `UNMASK_REQUESTED:DENIED`(detail `target=…`) 1행, **사유 자유 문구는 저장하지 않는다**(권한 없는 입력) | D-65와 같은 기준(권한 없음 = DENIED 감사). 사유는 권한 있는 요청에서만 감사 `reason` 열에 |
| D-89 | (§20.8 m-4) LOCKED 상태에서 정지한 계정은 `locked_at`을 보존(domain 불변식 "LOCKED에서 정지된 계정은 잠금 시각 유지")하고 `ACCOUNT_REACTIVATE`는 LOCKED + **원래 잠금 시각**으로 되돌린다. 자동 해제가 없으므로(D-ADMIN1-005) 잠금 시각은 표시·감사용이며 잠금 해제는 `ACCOUNT_UNLOCK` 2인 승인뿐 | 원래 사건 시각을 잃지 않는 쪽(대안: 재활성 시각 — 사건 시각 왜곡, 감사에서 복원 — 쓰기 경로가 감사를 읽게 됨). 변이 S5-M14 RED |
| D-90 | 시험 JVM `spring.test.context.cache.maxSize=4`(이전 세션 — 컨텍스트마다 MySQL 컨테이너 bean, 기본 32개면 WSL VM 메모리 고갈로 시험 정지 실측) | 시험 기반 설정만, 업무 기대값 무관 |
| D-91 | Fixture 이름은 `withRolesViaApproval`(명세 §7.4 `withRoleViaApproval`의 복수형 — 역할 여러 개): domain 시험은 `AdminAccountFixture.withRolesViaApproval`(승인 domain 경로 `ApprovalRequest.create → decideApproval → executed` 뒤 그 approvalId로 `grantRole`, 행위자 = 승인자), DB 시험은 `ApprovalPath.withRolesViaApproval`(실제 `ApprovalUseCase.request → approve`, 실제 TOTP). 우회 이름 `rolesGrantedBypassingApproval_untilS5`는 **사용처 0으로 제거**(D-63 사용처 전부 교체: `AdminAccountSpec`·`SecurityVersionEventsSpec`·`AdminPermissionSpec`·`AdminAccountPersistenceSpec`·`AdminAccountCommandSpec`·`EnrollmentFlowSpec`). 부트스트랩 단일 역할 `bootstrapped(SA|APPROVER)`와 역할 없는 `LoginReadyAccounts.withoutRoles`는 공식 경로라 유지 | S3 리뷰 M-2(c) |
| D-92 | 승인 HTTP 매핑: 201 생성·200 실행(재설정 종류만 `enrollmentCode` 1회)/종료, 403 `FORBIDDEN`(권한)·`SELF_APPROVAL`·`APPROVER_IS_TARGET`·`CAUSE_PARTICIPANT`·`STEP_UP_FAILED`, 409 `INVALIDATED`·`OWNER_REJECTED`(+`reason`)·업무 거절 code·`ALREADY_DECIDED`·`EXPIRED`·`RETRY_LATER`, 401 `SESSION_REPLACED`(세션 폐기), 404, 500 `INTERNAL_INTEGRITY`. 세션 세대는 서버 세션 문자열 상태에서 읽는다(요청 값 아님) | 고정 code만(§4.10), 재시도 유도는 `RETRY_LATER`만(무결성 위반은 409 아님 — n-1) |
| D-93 | rollback되는 거부(승인자 자격 없음·요청 생성 재검사 실패·`DUPLICATE_PENDING`·원인 참여자)는 별도 TX 감사(rev 0.5 n-4), 상태 변경 없이 끝나는 거부(자기 승인·만료 판정)는 주 TX에서 감사 | 감사가 rollback과 함께 사라지지 않게 |
| D-94 | 만료 정리 job 후보 읽기가 1213·1205면 그 회차를 건너뛴다(빈 목록). 행마다 짧은 TX로 잠근 뒤 다시 판정 | job은 표시용, 권위는 승인 TX 시각(n-5) |
| D-95 | 로그인 TX의 1213은 시험으로 일으키지 않는다: 비밀번호 (b)·TOTP 단계 TX는 계정 행 **하나**만 잠가 순환 대기가 생길 수 없다(감사 INSERT는 AUTO_INCREMENT). 로그인은 1205로 서비스 경계를 시험하고, 1213 경계는 행을 둘 이상 잠그는 승인·거절·철회·1인 명령(정지)으로 시험 | 실제 MySQL에서 재현 가능한 경로만 증거로 |

### 22.4 RED → GREEN(재개 뒤 추가 시험)

- 새 시험·강화 시험을 먼저 작성 → 구현 변경 전 실행(22:20:37Z 시작, 컴파일 통과, 로그 `~/gate-logs/s5-red1-20261025T222037Z.log`): **업무 RED 4건**만 실패 — `ArchitectureSpec` Reader 규칙(`ApprovalService`가 `ApprovalRequestListReader` 의존), `AdminOwnedCommandSpec` 철회 거부 감사·만료 철회 감사, `ApprovalHttpSpec` 마스킹 해제 권한 없음 감사. 나머지 새 시험(경합·1213/1205 경계·소유 재검사 계약·2차 재설정·HTTP 거절/철회)은 **재개 전 구현이 이미 만족해 첫 실행 GREEN**(특성 시험) — 이들의 업무 RED는 §22.5 변이로 보였다.
- 구현(D-86·D-87·D-88) → 22:26:13~22:29:20Z GREEN 80/0(approval 6 spec + ArchitectureSpec, 로그 `~/gate-logs/s5-green1-20261025T222613Z.log`). 승인 행 자체 1205 행 추가 뒤 22:31Z `ApprovalLockOrderSpec` 9/0.

### 22.5 핵심 변이(S5) — 21/21 RED(22:32~23:01Z, 실패 시험 이름 확인, 모두 원본 복원 + SHA-256 일치 확인)

실행기 `/tmp/s5_mut.py`(WSL — 각 변이는 정확히 1회 나타나는 문자열 치환, 결과 `~/gate-logs/s5-mutations.json`·변이별 로그 `~/gate-logs/s5-mut-<id>.log`).

| 변이 | 제거·변경한 동작 | RED 시험 |
|---|---|---|
| S5-M1 | `lockAll`의 actor ID 정렬 | `ApprovalLockOrderSpec` 겹치는 두 승인(대기 TX가 다른 계정 행 GRANTED 보유 — data_locks 단언) |
| S5-M2 | 승인자 APPROVER 재검사(TOTP 전) | 자격 없는 승인자 403·TOTP 미소비, 잠긴 승인자 거부 |
| S5-M3 | 요청자 자격 재검사(TOTP 뒤) | 요청자 정지 → `INVALIDATED` |
| S5-M4 | orchestrator의 대상 `security_version` 재비교 | 대상 변경 → `INVALIDATED`(소유 재검사가 `OWNER_REJECTED`로만 잡음) |
| S5-M5 | 소유 재검사의 참여자 서로 다름 확인 | 소유 재검사 계약 직접 시험 |
| S5-M6 | 재인증 실패 별도 TX 계수 | `StepUpFailureCounts`(합산 잠금) |
| S5-M7 | 재인증 TOTP 단계 소비 | `StepUpTotpReuseRace` |
| S5-M8 | 판정표의 만료(TX 시각) | 판정표 시험·승인 시 `EXPIRED` |
| S5-M9 | 재활성 승인자의 H-A 배제 | H-A 4행 시험 |
| S5-M10 | 실행 감사 detail에 코드 원문 | `ResetCodeDisclosure`(서비스·HTTP) |
| S5-M11 | 승인 경계의 `RETRY_LATER` 변환 | 1213 3종·1205 시험 |
| S5-M11b | 승인 저장 Port 1205 → `RETRY_LATER` | 승인 행 자체 1205 |
| S5-M12 | SA·APPROVER 활성화 종류의 복구 회차 잠금(0단계) | 회차 행 대기 시험 |
| S5-M13 | 0↔1단계 불변 열 무결성 검사 | `ApprovalImmutableColumnIntegrity` 행 |
| S5-M14 | 재활성의 원래 `locked_at` 보존 | `ACCOUNT_REACTIVATE` LOCKED 복원 |
| S5-M15 | 1062 `pending_key` → `DUPLICATE_PENDING` 분류 | 중복 PENDING 생성 |
| S5-M16 | 1인 명령의 1213 재판정 재시도(D-69) | 정지 1213 희생 → 1회 적용 |
| S5-M17 | 로그인 비밀번호 단계의 `retryLaterOnConflict` | 비밀번호 단계 1205 → HTTP 409 |
| S5-M18 | 승인자 세션 세대 비교 | `SESSION_REPLACED` |
| S5-M19 | 재설정 실행의 기존 미사용 코드 폐기 | 2차 재설정 → 1차 코드 무효 |
| S5-M20 | 종료 상태의 `ALREADY_DECIDED` | `ApprovalConcurrency`·`ApprovalVersusCloseRace` |

- 잡지 못한 변이: 없음. 시도하지 않은 것: `ROLE_REACTIVATE`가 원인 정지 역할 대신 정지 역할 전부를 복원하는 변이 — 현재 시험 장면(원인 뒤 회수된 역할은 정지 목록에서도 빠짐)에서는 두 집합이 같아 생존한다. 이를 가르는 장면은 "재설정 두 번" 경로인데 그 경로의 배제 규칙이 미결(§22.9 Q 후보)이라 기대값을 고정하지 않았다.

### 22.6 JPA·jOOQ — 이번 코드의 역할·예·이유·주의

| 선택 | 이번 코드의 예 | 이유 | 주의 |
|---|---|---|---|
| 순수 domain ↔ JPA Entity | `ApprovalRequest`(판정표·불변식, Spring/JPA 없음) ↔ `JpaApprovalRequestEntity`(행 모양) — `reconstitute`가 불가능 조합(ADMIN 종류의 소유 서비스 전용 상태, 종료 상태인데 결정자 없음) 거부 | 판정표 시험 8건을 DB 없이, 6종 × 모든 경로에서 `APPROVED`·`EXECUTING`·`EXECUTION_UNKNOWN` 불가(H-1)를 고정 | Entity data class 아님·equals = PK·toString에 스냅샷 없음 |
| 비관적 잠금 `find + refresh(PESSIMISTIC_WRITE)` | 승인 행(1단계)·참여자 계정 행(오름차순)·복구 회차 행 | `@Version`만으로는 승인 행 ↔ 여러 계정 행 사이 불변식(역할 없는 실행 0건)을 못 지킨다 — 회수 대 승인 경합 두 순서 시험 | 잠근 행을 JPQL `@Lock`으로 다시 읽지 않는다. 1213·1205는 Port 안에서 분류 → 409 |
| **스칼라 JPQL(이번 변경)** | 0단계 `SELECT r.kind, r.targetActorId, r.grantedRole …`, 만료 후보 `SELECT r.approvalId …` | Entity를 영속성 컨텍스트에 올리지 않아 1단계 잠금 읽기가 최신 값 — 0↔1단계 대조가 의미 있다. jOOQ Reader를 명령 경로에서 걷어내 CQRS 경계(D-86) | 같은 TX에서 먼저 `find`로 Entity를 올리면 최신 값은 `refresh`로만 읽힌다(`lock`이 refresh) |
| `updatable = false` + 열 권한 | 승인 행 생성 열(종류·대상·버전·스냅샷·부여 역할·원인·요청자·시각) — 결정 열(status·decided_*·result_code)·version만 UPDATE | 불변 열은 Hibernate SET에서 빠지고 DB 열 권한도 없어(1143) 이중 차단 — 무결성 위반 주입은 root 연결로만 가능 | R-12 대조 시험이 Entity updatable 열 = 권한 열 |
| 생성 열 미매핑 | `pending_key`(PENDING일 때 `kind:target`) UNIQUE | 같은 (종류, 대상) PENDING을 DB가 직렬화(동시 생성 1062 한 건 — 시험) | 매핑하면 Hibernate가 INSERT/UPDATE에 넣으려 한다 |
| `@Version` + dirty checking | 결정 열 변경은 잠근 managed Entity 필드 변경 → flush 때 UPDATE | 잠근 version과 다르면 `OptimisticConflict` → 409 | `security_version`(업무)과 JPA version(기술)을 섞지 않는다 |
| Propagation.MANDATORY | `AdminOwnedCommandExecutor`·저장 Port는 orchestrator의 로컬 TX 안에서만 | 승인 = 실행 단일 TX(H-1). 밖에서 부르면 `IllegalTransactionStateException`(소유 재검사 계약 시험) | 외부 호출 없음 |
| jOOQ CQRS | `JooqApprovalRequestListReader`(목록·상세 — `ApprovalQueryService`만), 시각 `DATE_FORMAT` 문자열 | 조회 모델에 코드 열 자체가 없다(ResetCodeDisclosure) | Reader 사용처 ArchUnit(D-86) |

### 22.7 8대 원칙(S5 범위) 증거

| 원칙 | 증거 |
|---|---|
| Financial Consistency | 해당 없음 — 금액·금융 사실 없음(ADMIN 관리 메타데이터). 소유 서비스 종류(2-TX)는 ADMIN-PAYMENT-OPS-001 |
| Transaction Safety | 승인 = 실행 단일 로컬 TX(승인 행·계정·역할 이벤트·코드·감사), 실행 감사 저장 실패 주입 → 전부 rollback(TOTP 단계 포함), 재인증 실패 계수는 rollback 뒤 별도 TX, rollback되는 거부 감사는 별도 TX, 외부 호출 없음. 변이 M6·M13 |
| Idempotency | 결정 1회(`ALREADY_DECIDED` — 동시 승인 2건 중 1건 실행, 패자 TOTP 미소비), TOTP 단계 재사용 거부(같은 코드 동시 2승인 → 1건), 대상당 사용 가능 코드 1개(2차 재설정 → 1차 폐기), 응답 유실 뒤 재조회 수렴·재승인 무효과. 변이 M7·M19·M20 |
| Concurrency Control | 전역 잠금 순서(회차 → 승인 행 → 계정 오름차순 → 코드): data_locks 단언 + 교착 지표 0(겹치는 승인·같은 대상 다른 종류 동시 생성), 강제 1213(승인·거절·철회·정지) → 무변경 409/재판정, 1205(승인·거절·철회·승인 행·로그인 두 단계·정지·등록 완료) → 409·무변경, `pending_key` 동시 생성 1062 1건, 승인 대 철회·거절·만료, 회수 대 승인 두 순서. 변이 M1·M11·M11b·M15·M16·M17 |
| Recoverability | 1213·1205·version 충돌은 전체 rollback 뒤 재시도 가능 응답(성공·결과 불명확으로 매핑하지 않음), 1205 해제 뒤 같은 TOTP 단계·같은 등록 draft로 다시 성공, commit 뒤 응답 유실은 승인 행 재조회로 수렴 |
| Reconciliation | 해당 없음(기관·원장 비교 없음). 내부 일관성: 역할 이벤트 재생 = 계정 현재 역할(재설정·회수 뒤 — `AdminAccountCommandSpec`) |
| Auditability | 요청·실행(실행자 = 승인자)·무효·소유 거절·거절·철회·만료(승인·거절·철회·job)·거부(별도 TX 포함)·무결성 위반·재인증 실패·마스킹 해제(성공 사유·권한 없음) 감사, 코드 원문은 감사·승인 행·조회에 없음. 변이 M10 |
| Observability | 지표 없음 — §6 `paycore.admin.approval{kind,result}`·`approval.pending`·`retry_later`·`integrity_violation{check}`(+ 경보)는 **S8 이월**. 시험의 교착 관측은 `INNODB_METRICS lock_deadlocks`(시험 도구) |

- BOM·의존성: S5에서 의존성 변경 없음(빌드 파일 변경은 시험 system property 1줄 — D-90). Kafka/Redis 도입 없음 — 승인 직렬화·중복 PENDING·1회 결정은 DB 행 잠금·UNIQUE·version이 담당하며, 메시지·캐시 기반 도구가 이 안전성을 대신한다고 보지 않는다.

### 22.8 남은 것(S5 범위 밖·이월)

- S6: `ApprovalLockOrderSpec` 복구 명령 행(1213·1205 → 고유 비영 exit), 작업 A/B의 회차 잠금 직렬화(0단계 회차 잠금 자체는 S5에서 시험).
- S8: 승인 지표·`integrity_violation` 지표와 경보(rev 0.5 n-1의 "지표 증가" 단언), `AdminNonDisclosureSpec`(캡처 로그에 코드 원문 0회 — §4.7.7 `ResetCodeDisclosureSpec`의 로그 부분), commit 단계 예외 실제 주입(현재는 "커밋 뒤 응답 유실 → 재조회" 순차 모사).
- S7: 승인함·내 요청·코드 1회 표시 화면.
- S8(재리뷰 nit-1): `ApprovalEnvironment` 시계 대역 + "승인이 행 잠금을 기다리는 동안 만료 시각 도래" 경합 시험(§22.11 m-2에서 제외한 부분).
- 시험 수: S5 Kotlin 추가는 §22.10 결과 기준(§10 예상 105~135보다 적다 — 경합·판정표 시험이 한 시험 안에서 종류·명령을 반복하는 밀도 높은 형태이며, 수를 맞추려고 쪼개지 않았다).

### 22.9 Coordinator 질문 후보(보안 의미 — 구현하지 않음)

**Q-S5-1 연속 재설정에서 H-A 배제 범위**(D-ADMIN1-009·rev 0.3 H-A): 재설정1(요청 R1·승인 P1)이 대상 역할을 정지(원인 = 재설정1)한 뒤, 같은 대상에 재설정2(R2·P2)가 실행되면 활성 역할이 없어 SUSPEND 이벤트가 추가되지 않고 역할의 정지 원인은 계속 재설정1이다. 이때 `ROLE_REACTIVATE(원인 = 재설정1)`의 배제 대상은 R1·P1뿐이라 **새 코드 수령자 P2가 재활성을 승인(또는 요청)할 수 있고 R2가 요청할 수 있다** — 코드 수령자 + 1명으로 역할 복원(단일 재설정에서는 코드 수령자·재설정 요청자 배제로 서로 다른 3명 필요). 같은 취지로, 미사용 재설정 코드의 수령자가 그 대상의 `ROLE_GRANT`를 승인하는 경로도 현재 일반 2인 규칙만 적용된다. (근거: 코드 판독 — `beginCredentialReset`은 활성 역할에만 SUSPEND를 만들고, `reactivationRefusal`은 원인 승인의 요청자·승인자만 배제한다. 기대값이 미결이라 시험으로 고정하지 않았다.)
- 권장안 A: 재설정 실행 시 이미 정지된 역할에도 원인 = 새 재설정 approvalId인 SUSPEND를 다시 append(rev 0.5 n-2 재생 규칙 "정지 원인 = 최신 SUSPEND 원인", 복구 작업 A m-1과 같은 방식) → 재활성은 최신 재설정만 원인으로 가능, 배제 = 최신 재설정의 R·P, 이전 원인 근거는 "그 원인으로 정지된 역할 없음"으로 거부. 함께, 대상에 미사용 재설정 코드가 있는 동안 그 코드 발급 승인의 요청자·승인자는 그 대상의 권한 상승 종류(`ROLE_GRANT`·`ROLE_REACTIVATE`)를 요청·승인할 수 없다.
- 대안 B: 재활성 배제 = 원인 이후 그 대상에 실행된 모든 코드 발급 승인의 요청자·승인자 합집합(이벤트 추가 없음). 대안 C: 대기 계정 2차 재설정 금지(rev 0.4 m-2·D-26의 2인 재발급과 충돌 — 비권장).
- 영향: A·B 모두 권한을 줄이는 방향(fail-closed), 운영 인원 요구 증가(Q-ADMIN-APPROVER-STAFF 참고 수치 갱신). 막히는 작업: 없음(S6·S7 진행 가능) — ADMIN-BOOT-001 DONE 판정 전 확정 필요. 현재 구현은 단일 재설정 H-A 4행만 강제한다.

### 22.10 S5 체크포인트(전체 검증, 2026-10-26 KST)

- 전체 `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(WSL 분리, 23:02:08Z 시작 → 23:30:11Z, 28m02s, 로그 `~/gate-logs/s5-gate/check.log`): **BUILD SUCCESSFUL**. Kotlin: master-api 320·payment-api 1766·financial-batch 26·internal-admin **313**(S4 끝 251 + S5 62 = 재개 전 48 + 재개 뒤 14)·institution-simulator 210·test-support 112 = **2747**(실패·오류·skip 0), Vitest 14.
- TEST-SUMMARY(`--not-before 2026-10-25T23:02:08Z`, 하한 2670): total 2747 PASS. TEST-SUMMARY-FRONTEND(하한 14): 14 PASS(가장 오래된 suite 23:04:56Z).
- HARNESS-SELFTEST(23:30:11Z): Ran **139** OK(하네스 변경 없음).
- CVE-SCAN(23:30:29~23:31:43Z): PASS — internal-admin runtime 111(S4와 같음 — S5 의존성 변경 없음), 취약점 0, 프론트 147·canary 6, 모든 모듈 canary 검출.
- GRADLE-DEPS-INTERNAL-ADMIN: exit 0, runtime에 testcontainers 0줄, spring-session 4.1.1(Boot BOM 관리).
- 변이 실행(§22.5)은 이 검증 전에 끝났고 모든 파일이 원본 hash로 복원됐다. FINGERPRINT는 실행하지 않았다(커밋 전 단계 — Verifier 몫).
- commands.json 제안(Coordinator 적용 대상 — 수정하지 않음): GRADLE-CHECK `minTests`·TEST-SUMMARY 하한 2670 → **2747**(HARNESS-SELFTEST 139·TEST-SUMMARY-FRONTEND 14 그대로).

### 22.11 S5 체크포인트 리뷰(PASS_WITH_FIXES) 반영(2026-10-26 KST — 이 절이 §22.1~22.10과 다르면 이 절이 우선)

Coordinator 지시의 "§22.10 S5 리뷰 반영"은 §22.10이 이미 체크포인트 결과라 이 절(§22.11)로 둔다. commands.json은 Coordinator가 반영(GRADLE-CHECK·TEST-SUMMARY 2747). 사용자 결정 D-ADMIN1-013·014·015(Q-S5-1~3, docs/DECISIONS.md)를 반영했고 §22.9 Q-S5-1은 해소됐다.
RED: 결정 시험을 먼저 작성(기존 Use Case API만 사용) → 00:01:30Z 실행(로그 `~/gate-logs/s5-fixred-20261026T000130Z.log`) **업무 RED 5건** — `AdminAccountSpec` 013 행, `CredentialIssuerExclusionSpec` 4건(연속 재설정 재활성 `Created` ≠ `NOTHING_TO_REACTIVATE`, 코드 수령자 승인 `Executed` ≠ `Refused`, 소유 재검사 `Executed`, 생성자 요청 `Created`). 리뷰 수정 시험(M-1·m-1·m-2·m-4)은 같은 실행에서 GREEN(특성 시험 — 변이로 RED 확인 대상). 구현 → 00:05~00:11Z internal-admin 전체 322/0.

| 항목 | 반영 | 증거 |
|---|---|---|
| **D-ADMIN1-013** 최신 재설정 기준 | domain `beginCredentialReset`이 활성 역할 + **이미 정지된 역할 전부**에 원인 = 새 재설정 SUSPEND를 append(이력 보존, 재생 규칙 "정지 원인 = 최신 SUSPEND 원인" — rev 0.5 n-2와 같음). 그래서 `ROLE_REACTIVATE`는 최신 재설정만 원인으로 가능하고 H-A 배제(원인 요청자·승인자)도 최신 재설정 기준 | `AdminAccountSpec` 013 행, `CredentialIssuerExclusionSpec` 연속 재설정(원인 = 이전 재설정 → `NOTHING_TO_REACTIVATE`, R2·P2 요청 `CAUSE_PARTICIPANT`·승인 `Refused`, 이전 재설정 승인자 P1은 이제 승인 가능 → 실행). 변이 R-N1 |
| **D-ADMIN1-014** 자격 발급 근거 배제 | domain 값 `CredentialBasis(basis, requester, approver)`를 계정에 보존(D-96): 계정 생성 = 1인 명령(생성자), `ENROLL` 재발급 = 1인 명령(수행자), 재설정 = 승인(요청자·승인자). 코드 사용·정지·역할 회수·부여와 무관하게 다음 코드 발급 때만 바뀐다. 불변식: 대기 코드 근거 = 자격 근거, 계정 자신은 근거 참여자 아님, 승인자는 요청자와 함께만. `ROLE_GRANT`·`ROLE_REACTIVATE`(`ApprovalKind.escalatesPrivilege`)에서 근거 참여자는 **요청 시** 거부(409 `CREDENTIAL_ISSUER`, 별도 TX 감사)·**승인 시** 거부(TOTP 전 403, 상태 불변, 별도 TX 감사)·**소유 재검사**(account `execute` → `OWNER_REJECTED CREDENTIAL_ISSUER`) — D-97 | High-1 장면(재설정 → 코드 사용 전 승인 거부 → 등록 → 정지 역할 회수 → 같은 역할 재부여 요청·승인 거부, 감사 5행), 다음 재설정(다른 사람) 뒤 배제 해제, 소유 재검사 직접 시험. 변이 R-N2·R-N3·R-N4·R-N8 |
| **D-ADMIN1-015** 생성자 배제 | 1인 생성의 `CredentialBasis` 요청자 = 생성 SECURITY_ADMIN(코드 수령자) | 생성자의 `ROLE_GRANT` 요청 거부(등록 전·후), 승인 거부, 다른 사람의 재설정 뒤 요청 가능. 변이 R-N6. 재발급 수행자(D-98)는 변이 R-N7(domain) |
| 영속 | `admin_account`에 `credential_basis`(NOT NULL)·`credential_basis_requester`·`credential_basis_approver`, CHECK `pending_basis IS NULL OR pending_basis = credential_basis`·승인자 ⇒ 요청자·서로 다름. **V1 제자리 수정**(미커밋 — S3 nit DB CHECK와 같은 관례), 열 단위 UPDATE 권한은 V2의 세 번째 helper 호출에 추가(8열 상한 안). R-12: `AdminDatabaseAccountSpec.ADMIN_RUNTIME_COLUMN_UPDATES`·`AdminPersistenceMappingSpec` 분류(INCREMENTS — 코드 발급과 함께만 바뀌며 그 사건이 +1)에 3열 추가(열 추가에 따른 목록 확장 — 기존 기대값 완화 아님). `AdminSchemaSpec` 원시 INSERT 도우미에 `credential_basis` 추가(시험 도구) | `AdminDatabaseAccountSpec`·`AdminPersistenceMappingSpec`·`AdminSchemaSpec` GREEN |
| **M-1** TOTP 소비 뒤 주입 | 대상의 기존 미사용 코드 행을 세 번째 연결이 잡아 `PASSWORD_RESET` 승인 TX가 **TOTP 단계 소비 뒤** `revokeUnused`에서 막히게 함: 1205·1213(무거운 holder가 승인 행 요구) 두 방식 → `RETRY_LATER`, 승인자 `last_totp_step` 불변(rollback), PENDING, 결정 감사 없음, 기존 코드 미사용·미폐기, **같은 TOTP 코드로 재승인 → 실행**(기존 코드 폐기 확인) | `ApprovalLockOrderSpec` 행(교착 지표 1213 +1·1205 0) |
| **m-1** 코드 비노출 | 요청자 본인의 "내 요청" 목록에 그 approvalId가 있음을 먼저 단언, 감사는 시험 시작 뒤 **모든** 행(action·detail·reason·correlation)을 root로 훑음 | `AdminOwnedCommandSpec` ResetCodeDisclosure 행. 변이 S5-M10 재확인 대상 |
| **m-2** 승인 대 만료 | 결과 쌍이 `(Expired, 0)` 또는 `(ALREADY_DECIDED, 1)`만, `APPROVAL_EXPIRED` 감사 정확히 1행, 대상 역할 없음. 시계를 제어한 "승인 대기 중 만료 도래" 경합은 하지 않았다 — `ApprovalEnvironment` 시계를 시험에서 바꾸는 대역이 없어(추가하면 다른 시험 컨텍스트 분리 필요) 이번 범위에서 제외, 현재 시험은 이미 만료된 행에 대한 행 잠금 경합 | `ApprovalRaceSpec` |
| **m-3** 변이 추가 | 요청 시 H-A 요청자 쪽(R-N9), 거절 경로 승인자 APPROVER 재검사(R-N10 — 새 시험: SA만 가진 계정의 거절 → `Denied`·TOTP 미소비), 철회 세션 세대(R-N11 — 새 시험: 낡은 세대 철회 → `SESSION_REPLACED`), `DUPLICATE_PENDING` 거부 감사 별도 TX(R-N12). 재개 전 코드의 RED는 변이로 대신(Coordinator 수용) | §22.11 변이 표 |
| **m-4** 생성 TX 1213 | 무거운 holder가 대상 행을 잡고, 생성 TX(승인 행 INSERT → 요청자 잠금 → 대상 대기)에 대해 holder가 요청자 행을 요구 → 생성 TX 희생 → `RETRY_LATER`, 승인 행 0·`APPROVAL%` 감사 0, 교착 지표 +1. D-95는 증거 범위 변경으로 수용됨 | `ApprovalLockOrderSpec` 행 |
| nit-1 | **만료는 승인자 권한 검사보다 먼저 적용된다**(판정표 2단계 — 시각으로 정해지는 사실): 만료 시각이 지난 PENDING에 승인·거절을 부른 호출자는 승인 권한이 없어도 그 행을 `EXPIRED`로 기록하고(감사 행위자 = 호출자) `EXPIRED`를 받는다. 결정이 아니라 job과 같은 사실 기록이며 실행·TOTP 소비는 없다 | 기록만 |
| nit-2 | 재인증 실패 계수 TX(`AuthenticationFailureRecorder`, 1213·1205 3회 재시도)가 끝내 실패하면 그 저장 실패가 `approve`/`reject` 밖으로 전파된다 → 웹 500 `INTERNAL_ERROR`. 승인 TX는 이미 rollback이라 상태·TOTP 단계 불변, **그 1회 실패는 계수되지 않는다**(같은 계정 행이 3번 연속 대기 초과일 때만). `ApprovalService.afterDecision` KDoc에 기록 — §22.12 질문 후보 | 기록만 |
| nit-3 | `AdminAccountStorage` KDoc 빈 참조 → `[ApprovalPath]`, `ApprovalRaceSpec` 정적 `secondResult` → 시험 지역 `AtomicReference`, `SecurityVersionEventsSpec` GRANT 행위자 → `RoleEventActor.Admin(승인자)` + 승인 ID | 컴파일·GREEN |
| S8 관찰(변경 없음) | 마스킹 해제 사유 자유 문구가 감사 `reason`에 원문 저장 — S8 비노출 묶음에서 검토 | — |

- 결정 추가: **D-96** 자격 발급 근거 = 계정 3열(근거·요청자·승인자), 코드 발급 때만 갱신, DB CHECK로 대기 근거 일치. **D-97** 배제 판정 위치 = 요청 시(409, `ROLE_REACTIVATE`는 기존 H-A `CAUSE_PARTICIPANT` 판정 뒤 — 기존 기대 code 유지)·승인 시(TOTP 전 403)·소유 재검사(`OWNER_REJECTED`), 거부 code `CREDENTIAL_ISSUER`. **D-98(재리뷰 m-2 — Coordinator 판정 (b)로 갱신)** 1인 `ENROLL` 재발급 수행자도 코드 수령자로 배제한다. 재발급은 **자격을 바꾸지 않으므로**(D-ADMIN1-015 "그 계정 자격이 바뀔 때까지") 등록 완료 전까지 배제 집합 = 이전 발급자들 ∪ 새 재발급 수행자(`CredentialBasis.carried`, 열 `credential_basis_carried`)이고, 등록 완료 때 실제로 쓴 코드의 발급자만 남는다(이전 생성자 해제). 재설정(2인)은 자격을 바꾸므로 이전 배제를 풀고 새 근거만 둔다. **D-99** 013의 SUSPEND 재append는 원인과 관계없이 정지된 역할 전부(복구 회차 원인 정지 포함 — S6 작업 A와 같은 재생 규칙).
- (재리뷰 m-1로 정정) `ROLE_REACTIVATE` 요청 시 자격 근거 판정은 동등 변이가 **아니다**: 최신 재설정 참여자가 **이전** 재설정을 원인으로 인용하면 H-A 판정은 통과하고 근거 판정이 `CREDENTIAL_ISSUER`로 막는다(판정이 없으면 `NOTHING_TO_REACTIVATE`) — 보안 결과는 거부로 같지만 **code가 다르다**. 시험 행 추가 + 변이 R-N5 RED(§22.13).
- 결과·변이·체크포인트: §22.12.

### 22.12 리뷰 반영 변이·체크포인트(2026-10-26 KST)

- 변이 **11/11 RED**(00:13~00:24Z, 실행기 `/tmp/s5_mut2.py`, 결과 `~/gate-logs/s5-review-mutations.json`, 모두 원본 복원 + SHA-256 일치): R-N1 013 SUSPEND 재append 제거(domain·연속 재설정) · R-N2 요청 시 `ROLE_GRANT` 근거 판정 제거 · R-N3 승인 시 근거 판정 무력화 · R-N4 소유 재검사 근거 판정 제거 · R-N6 1인 생성 근거를 시스템으로(생성자 배제 소실) · R-N7 재발급 수행자 소실(domain) · R-N8 저장 행의 근거 승인자 복원 누락 · R-N9 요청 시 H-A 요청자 쪽 · R-N10 거절 경로 APPROVER 재검사 · R-N11 철회 세션 세대 · R-N12 `DUPLICATE_PENDING` 거부 감사 별도 TX. 강화한 시험으로 S5-M10(코드 감사 노출)·S5-M11(경계 `RETRY_LATER`) 재확인 RED(00:25~00:28Z, `~/gate-logs/s5-recheck-mutations.json`).
- 전체 `--no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(00:28:09Z → 00:55:04Z, 26m53s, `~/gate-logs/s5-gate2/check.log`): **BUILD SUCCESSFUL**. master-api 320·payment-api 1766·financial-batch 26·internal-admin **322**·institution-simulator 210·test-support 112 = **2756**(실패·오류·skip 0), Vitest 14. TEST-SUMMARY(하한 2747) PASS, TEST-SUMMARY-FRONTEND(14) PASS, HARNESS-SELFTEST 139 OK(00:55:04Z), CVE-SCAN PASS(00:55:21~00:56:35Z — internal-admin runtime 111, 취약점 0, 프론트 147·canary 6), GRADLE-DEPS-INTERNAL-ADMIN exit 0(testcontainers 0줄).
- commands.json 제안(수정하지 않음): GRADLE-CHECK `minTests`·TEST-SUMMARY 하한 2747 → **2756**(SELFTEST 139·FRONTEND 14 그대로).
- 질문 후보(보안 의미 — 구현 결정하지 않음): **Q-S5-4** 재인증 실패 계수 TX가 3회 대기 초과로 실패하면 그 실패가 계수되지 않고 500이 난다(nit-2). 권장안: 현재 유지(승인 효과 없음·같은 계정 행 장기 잠금 때만) + S8 지표(`paycore.admin.lockout`류)로 관측, 대안: 계수 실패 시 409 `RETRY_LATER`로 바꾸거나 계정을 fail-closed로 잠금. **D-98** 재발급 수행자 배제 해석 확인.

### 22.13 S5 재리뷰(PASS_WITH_FIXES) 반영(2026-10-26 KST)

RED(01:06:05Z, `~/gate-logs/s5-rr-red-20261026T010605Z.log`): 재발급 배제 시험·DB CHECK 시험 2건 업무 RED(m-1 시험 행은 기존 구현이 만족 — 변이로 확인). 구현 → internal-admin 모듈 시험 **324/0**(01:08~01:14Z, `~/gate-logs/s5-rr-green-20261026T010805Z.log` — 지시대로 모듈만, 전체 check는 S6 체크포인트에서). 변이 **5/5 RED**(01:14~01:21Z, `~/gate-logs/s5-rereview-mutations.json`, 모두 복원·hash 일치).

| 항목 | 반영 | 증거 |
|---|---|---|
| m-1 | 연속 재설정 시험에 "최신 재설정 요청자 R2가 이전 원인 reset1을 인용 → `CREDENTIAL_ISSUER`" 행. §22.11 문구 정정(동등 변이 아님 — code가 다름) | R-N5(요청 시 `ROLE_REACTIVATE` 근거 판정 제거) RED |
| m-2 | D-98 판정 (b): `CredentialBasis.carried`(이전 발급자, 최대 32 — 넘으면 재발급 `REISSUE_ISSUER_LIMIT` 거절, 저장 열 `credential_basis_carried VARCHAR(1000)`, 열 권한·R-12·분류 추가), 재발급 = `reissuedBy`(이전 발급자 이월), 등록 완료 = `settledOnEnrollment`(이월 비움). 서비스 시험: 재발급 뒤 생성자·수행자 모두 요청 409·승인 403, 등록 완료 뒤 생성자 해제(요청 생성·다른 승인자로 실행, 생성자 승인으로 실행), 수행자는 계속 배제. domain 시험 기대값을 판정에 맞춰 갱신({수행자, 생성자} → 완료 뒤 {수행자}) | R-N13(재발급이 이월 없이 새 근거만) · R-N14(완료가 이월 유지) · R-N15(저장 행의 이월 복원 누락) RED |
| m-3 | CHECK `ck_admin_account_credential_not_self`: 근거 요청자·승인자 ≠ actor_id(NULL 허용). `AdminSchemaSpec` 6행(대기 근거 불일치·승인자만·요청자 = 승인자·요청자 = 계정·승인자 = 계정 → 3819, 정상 쌍 → 성공) | R-N16(CHECK 제거) RED |
| m-4 | **검토한 경로로 기록(코드 변경 없음)**: 근거 참여자도 `ACCOUNT_UNLOCK`·`ACCOUNT_REACTIVATE`는 요청·승인할 수 있고, 그 결과 다른 사람이 독립적으로 부여한 역할을 가진 계정이 다시 쓸 수 있게 된다. D-ADMIN1-014 범위는 권한 상승(`ROLE_GRANT`·`ROLE_REACTIVATE`)뿐이라 이 경로는 범위 안의 수용 경로다(역할 집합을 늘리지 않음 — 잠금 해제·정지 해제는 상태 복원) | — |
| nit-1 | §22.8 이월에 시계 대역·"승인 대기 중 만료 도래" 경합(S8) 추가 | — |
| nit-3 | **V1 제자리 수정 주의**: V1(`admin_core`)을 커밋 전 제자리로 바꿔 왔으므로(S3 nit·S5 근거 열·CHECK) 이미 적용된 **개발 DB 볼륨은 다시 만들어야 한다**(Flyway checksum 불일치로 기동 실패 — `docker compose down -v` 후 재기동). 시험은 매번 새 컨테이너라 영향 없음 | — |

- 질문 후보(보안·운영 의미 — 결정하지 않음): **Q-S6-0** 재발급 이월 발급자 상한 32명 초과 시 재발급 거절(fail-closed — 2인 재설정으로는 계속 가능). 권장: 유지(상한은 저장 열 크기에서 온 기술 값, 현실적으로 도달 불가), 대안: 별도 표로 무제한.

## 23. S6 구현 기록(2026-10-26 KST, 2단계 Implementer — 자기 완료 판정 아님)

범위: §10 S6(1회성 명령 — runner·모드 가드·TTY/0600 출력·부트스트랩·복구 회차·작업 A 역할 기록/정지·완료 시 역할별 자동 재활성 조건·회차 종료 판정·활성 수 판정 직렬화) + 이월(S5 `ApprovalLockOrderSpec` 복구 명령 행, 부트스트랩·작업 B `CredentialBasis.system`, 작업 A `system(episode)` + 정지 역할 전부 SUSPEND 재append(D-99) 시험 행, 호스트 운영자 신뢰 경계 기록, D-64 종료). 화면(S7)·지표/비노출(S8)은 범위 밖.

### 23.1 변경

| 영역 | 내용 |
|---|---|
| domain | `AdminAccount.beginRecovery`(대기 RECOVERY·근거 `episode:<n>`·두 자격 무효화·활성 + 이미 정지된 역할 전부 원인 = 회차 SUSPEND, 주체 `SystemRecovery`·자격 근거 시스템·SUSPENDED 대상 거부)·`recoveryRecordableRoles`(보유 SA·APPROVER — 활성 또는 정지)·`reactivateRecoveredRoles`(ACTIVE에서 아직 정지된 기록 역할만 REACTIVATE), `RecoveryBasis` |
| application | provided `OneShotCommandUseCase`(`bootstrap`·`recover`·`recordRefusal`, 결과 `Done`·`Refused`·`Lost`·`RetryLater`·`Invalid`, 코드 원문은 `Done`에만 — toString 마스킹). 서비스 `OneShotCommandService`(로컬 TX 1회, 재시도 없음). required `BootstrapStore`·`RecoveryEpisodeStore` 확장(`lockOpenEpisode`·`findOpen`·`nextEpisodeNo`·`open`·`close`·`closeExpired`)·`RecoverySettings`, `AdminAccountRepository.anyExists`·`countActiveHolders`, `EnrollmentCodeStore.countUsableForEpisode`, `StoreConstraint.BOOTSTRAP`·`RECOVERY_EPISODE`. `EnrollmentService` 완료 TX: 0단계 비잠금 선판정(회차 코드이거나 SA·APPROVER가 활성화되는 계정) → 열린 회차 행 잠금 → 계정 잠금 → 잠금 아래 재확인(다르면 rollback → 409) → … → 복구 코드 기록 역할별 재활성(회차 열림·만료 전·최신 이벤트 = 이 회차 SUSPEND·활성 수 0) → 회차 종료 판정(쓸 수 있는 코드 0 → 닫음·감사). D-64의 `RECOVERY` fail-closed 제거 |
| adapter.batch | `OneShotCommandRunner`(`--paycore.admin.command`가 있을 때만, **모드 가드**: 웹 애플리케이션 컨텍스트 또는 만료 정리 job 켜짐이면 DB 전에 `MODE_REFUSED`), `OneShotCommandLine`(인자 → 출력 준비(DB 전) → Use Case → 출력), `CodeOutput`(TTY = 터미널 있을 때만, 파일 = `CREATE_NEW` + POSIX `rw-------` 생성 뒤 권한 재확인·불일치면 삭제·거부, DB 실패면 빈 파일 삭제), `ConsoleAccess`·`SystemConsoleAccess`(`System.console()?.isTerminal`), `OneShotExit`(0 성공·1 실패·2 입력·3 모드·4 조건·5 경합 패배·6 출력 거부·7 커밋 뒤 출력 실패·8 재시도) |
| adapter.persistence | `JpaBootstrapStore`(native INSERT 즉시 — PK 1062), `JpaRecoveryEpisodeStore`(native — 잠금 읽기·INSERT·`closed_at` UPDATE, 시각은 `DATE_FORMAT` 문자열), 계정 수 `FIND_IN_SET` 집계, 코드 회차별 집계 |
| 진입점·설정 | `main`: 명령 인자가 있으면 `--spring.main.web-application-type=none`·`--paycore.admin.approval.expiry-job.enabled=false`를 **앞에** 덧붙이고 runner 종료 코드로 `exitProcess`. `SecurityConfig` 필터 체인 두 bean만 `@ConditionalOnWebApplication(SERVLET)`(비웹 명령 모드 기동 — 웹 모드 동작 불변). `paycore.admin.recovery.episode-validity: 24h`(D-SV Demo) |
| ArchUnit | `batchAdapterUsesOnlyApplication` 빈 대상 허용 해제(대상 있음) |
| 시험 | `BootstrapCommandSpec` 5(성공·0600·코드 원문 감사 없음·등록 완료·역할 보유 대기 계정 1인 재발급 거부 / TTY 없음·파일 있음·인자 누락/중복·알 수 없는 명령은 DB 전 거부(감사 2) / TTY 성공 / 계정 있음 거부 / **동시 2회 → 1 성공·1 LOST**), `OneShotModeWebSpec`·`OneShotModeSchedulerSpec`·`OneShotModeAcceptedSpec`(실제 `ApplicationRunner` 경로), `RecoveryCommandSpec` 11(조건 불충족, 작업 A 기록·정지·만료 = 회차·완료 시 SA만 복원·회차 닫힘·**반복 회차**, ⑥ D-99 재append·이전 재설정 인용 불가, ① 작업 B APPROVER 먼저 → 복원 안 함·⑦ B 코드 만료 = 회차 만료, ② 같은 회차 동시 완료 직렬화·한 번만 복원·마지막이 닫음, ③ 회수된 기록 역할 복원 안 함·⑤ 만료 뒤 코드 거부·만료 회차를 다음 실행이 닫음, 활성화 직렬화(회차 행 대기), SUSPENDED·없는 대상 거부, **RecoveryConcurrency 1 성공·1 LOST**, 복구 TX 1205·1213 → `RETRY_LATER`·무변경, 종료 코드 서로 다름), `AdminAccountSpec` +3(작업 A·SUSPENDED 거부·재활성), `EnrollmentFlowSpec` 1행 교체(§23.4) |

### 23.2 구현 결정(가역 — 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| D-100 | 명령 모드 = 비웹 + 스케줄러 끔을 진입점이 강제하고 runner가 실제 컨텍스트로 다시 확인(웹 컨텍스트·job 켜짐이면 DB 전 거부). 반대 값을 함께 주면 같은 키가 둘이 되어 기동 실패하거나 가드가 거부(fail-closed). 웹 서버 포트 설정 등은 비웹 컨텍스트에서 의미가 없어 별도 검사하지 않는다 | §4.9 "웹 동시 기동 거부". 웹 프로필 프로세스에 명령 인자가 오면 `MODE_REFUSED`(시험) |
| D-101 | 출력은 DB 변경 **전** 준비(파일 생성·권한 재확인 또는 터미널 확인), 거부도 감사(별도 TX). DB 단계 실패면 빈 파일 삭제. 커밋 뒤 쓰기 실패는 exit 7 + 표준 오류에 재발급 경로 안내(코드 없음) | §4.9 출력 규칙·S0 §17.8(drvfs 권한 무시) |
| D-102 | 복구 실행 TX: 열린 회차는 **비잠금 읽기**로 보고(만료면 조건부 UPDATE로 닫음, 열려 있으면 `EPISODE_OPEN` 거부), 동시 실행의 권위는 열린 회차 UNIQUE(패자 1062 → `LOST`·별도 TX 감사). 대상은 login_name으로 비잠금 확인 → 회차 INSERT 뒤 계정 오름차순 잠금 | 잠금 순서 §4.13(회차 → 계정 → 코드). 회차 행 `FOR UPDATE` 선잠금은 보조 인덱스 대기로 패자가 1062 대신 `EPISODE_OPEN`이 되는 경로를 만든다 — UNIQUE 하나를 권위로 |
| D-103 | (해석 — §23.6 Q-S6-1) **SUSPENDED 계정은 작업 A 대상이 아니다**(`TARGET_NOT_ELIGIBLE`, 전체 rollback) | 복구가 정지 해제(2인 승인 `ACCOUNT_REACTIVATE`)를 우회하지 않게 fail-closed |
| D-104 | 완료 TX 활성화 판정 = 코드에 회차 번호 또는 기록 역할이 있거나, 계정 현재 활성 역할에 SA·APPROVER. 0단계와 잠금 아래 판정·열린 회차가 다르면 409 | rev 0.4 m-3. 부트스트랩·작업 B 계정(역할 보유 대기)과 작업 A 코드 모두 회차 잠금 아래에서 활성 수를 센다 |
| D-105 | 회차 종료 판정 = 그 회차의 **쓸 수 있는**(미사용·미폐기·만료 전) 코드 0. 감사 `RECOVERY_EPISODE_CLOSED`. 만료 회차는 다음 복구 실행이 닫는다 | §4.9.2 |
| D-106 | 감사: `SYSTEM_BOOTSTRAP`·`SYSTEM_RECOVERY` 행위자(actor ID 없음), 행위 `BOOTSTRAP`·`RECOVERY`(SUCCESS·REFUSED·LOST, `reason=…`)·`ACCOUNT_CREATED`·`ENROLLMENT_CODE_ISSUED`(`purpose`·`episode`·`recorded`·`revokedCodes`) — 코드 원문 없음 | Auditability, 비노출 |
| D-107 | 시스템 경로 계정·코드는 `CredentialBasis.system`(부트스트랩·작업 B는 발급 ID, 작업 A는 `episode:<n>`) — 관리자 배제 대상 없음 | 이월 지시. **신뢰 경계(n-H)**: 시스템 경로의 코드 수령자는 호스트·DB 자격 보유 운영자이며, 그는 이미 runtime 권한으로 ADMIN DB를 바꿀 수 있다 — 명령은 권한을 넓히지 않고 조건·1회성·감사를 DB로 강제할 뿐이며, 호스트·자격 보호는 운영 통제(LIVE Gate)다 |
| D-108 | 1회성 명령 시험은 전용 컨텍스트(전용 컨테이너)에서 시험마다 업무 표를 root로 비운다(빈 표·0명 조건) | 다른 spec의 계정과 섞이지 않게 |

### 23.3 JPA·jOOQ — 이번 코드의 역할·예·이유·주의

| 선택 | 이번 코드의 예 | 이유 | 주의 |
|---|---|---|---|
| native SQL Store(Entity 없음) | 회차·부트스트랩 행 | 행이 작고 쓰기가 INSERT·열 1개 UPDATE뿐 — runtime 권한(SELECT·INSERT·`closed_at` UPDATE)과 같은 모양을 그대로 드러낸다 | INSERT는 즉시 실행돼 1062가 Port 안에서 분류된다(flush 불필요) |
| 고정 PK·UNIQUE가 1회성 권위 | `admin_bootstrap(id=1)`, 열린 회차 `open_marker` UNIQUE | 빈 표 `COUNT … FOR UPDATE`·갭 잠금에 기대지 않는다(READ COMMITTED에서는 갭 잠금이 없고, 같은 키 대기 둘이면 S→X 승격 교착 — 시험 설계 중 실측) | 패자는 1062 → `LOST` |
| 회차 행 = 잠금 순서의 맨 앞 | 완료 TX·승인 TX·복구 TX | 활성 수 판정과 자동 재활성을 직렬화(②·활성화 대기 시험) | 열린 회차 잠금 읽기는 보조 인덱스 레코드를 먼저 잡아 둘째 대기자는 첫 대기자에 막힌다(시험은 표 전체 대기 수로 확인) |
| 집계는 잠금 아래 | `countActiveHolders`·`countUsableForEpisode` | 회차 행을 쥔 TX 안에서 세어 동시 활성화와 겹치지 않음 | 회차 없을 때 시작한 활성화 TX와의 한계는 §4.9.2 n-3 수용 행 |

### 23.4 기존 시험 기대값 변경(계획된 교체 — 기록)
- `EnrollmentFlowSpec` "RECOVERY codes before S6 — begin에서 `NOT_APPLICABLE`"은 D-64가 명시한 **S6 전 임시** fail-closed였다. S6에서 RECOVERY 흐름이 열려 `begin`은 진행 상태를 주므로, 같은 보호 의도(복구 명령이 그 계정에 발급하지 않은 RECOVERY 코드로는 활성화 불가)를 완료 TX 판정(`NOT_APPLICABLE`·코드 미사용·계정 불변)으로 고정하는 행으로 교체했다. 하한·정책 완화가 아니라 단계 계획에 따른 교체다 — Coordinator 확인 대상.

### 23.5 8대 원칙(S6 범위) 증거

| 원칙 | 증거 |
|---|---|
| Financial Consistency | 해당 없음(금액·금융 사실 없음) |
| Transaction Safety | 부트스트랩·복구 각각 로컬 TX 1회(고정 행·회차·계정·역할 이벤트·코드·감사), 출력 준비는 TX 전·쓰기는 커밋 뒤(외부 호출 없음), 거부·패배 감사는 별도 TX |
| Idempotency | 부트스트랩 1회(고정 PK — 동시 2회 1 성공), 열린 회차 하나(UNIQUE — 동시 2회 1 성공), 코드 원자 소비, 같은 회차 동시 완료에도 역할 1회 복원 |
| Concurrency Control | 회차 → 계정 → 코드 잠금 순서, 활성 수 판정 직렬화(회차 행 대기 시험), 0단계·잠금 아래 재확인(409), 복구 TX 1205·1213 → `RETRY_LATER`·무변경 |
| Recoverability | 회차 반복(완료 → 다시 0명 → 다음 회차), 만료 회차는 다음 실행이 닫음, 커밋 뒤 출력 실패는 재발급 경로 안내(exit 7), 1213·1205는 무변경 재시도 가능 |
| Reconciliation | 해당 없음. 내부 일관성: 역할 이벤트 재생 규칙(정지 → 정지 재append 유효, 정지 원인 = 최신 SUSPEND) |
| Auditability | 성공·거부(출력 거부 포함)·패배 모두 감사, 시스템 행위자, 코드 원문 없음(시험) |
| Observability | 지표(`paycore.admin.oneshot{command,result}`)는 S8 이월. 종료 코드가 결과 분류(서로 다름 — 시험) |

- BOM·의존성: 변경 없음(JDK `java.nio` POSIX 권한·Spring Boot `ApplicationRunner`/`ExitCodeGenerator`). Kafka/Redis 없음 — 1회성·직렬화는 DB 고정 행·UNIQUE·행 잠금이 담당하며 메시지·캐시 도구가 그 안전성을 대신한다고 보지 않는다.

### 23.6 질문 후보(보안·운영 의미 — 결정하지 않음, 해당 동작은 fail-closed로 둠)
- **Q-S6-1** 복구 작업 A의 SUSPENDED 대상: 현재 거부(D-103). 대안: 허용하되 정지 상태 유지(코드는 정지 해제 2인 승인 뒤에만 사용 가능) — 관리 불능 복구에 정지 계정이 필요한 경우를 위한 것. 권장: 현재 유지(복구는 작업 B로 새 계정 쌍을 만들 수 있다).
- **Q-S6-2** `paycore.admin.recovery.episode-validity`(Demo 24h)를 LIVE 가드의 Demo 값 목록과 Q-ADMIN-SEC-VALUES 대상에 넣을지 — 넣으면 `LiveModeStartupGuardSpec`의 전체 목록 기대값이 늘어난다(기존 시험 기대 변경이라 이번에 하지 않음). 권장: 넣는다(D-SV에 이미 "복구 회차 유효 24시간"이 Demo로 있음).
- Q-S5-4·Q-S6-0(§22.12·§22.13) 미결 유지.

### 23.7 RED → GREEN·변이·체크포인트
- RED: S6 시험을 먼저 작성 → 01:36:16Z **컴파일 RED**(새 API 없음 183 오류, `~/gate-logs/s6-red-compile-20261026T013616Z.log`). 구현 뒤 01:48Z 실행 7건 실패(비웹 컨텍스트에서 `HttpSecurity` 없음 → 필터 체인만 웹 조건, 부트스트랩 동시 시험이 같은 PK 두 대기로 S→X 교착 → 첫 TX를 다른 UNIQUE에서 멈추는 설계로 교체, 열린 회차 보조 인덱스 대기 수 집계, 시험 측 만료 시각이 발급 시각보다 앞섬) → 01:57Z S6 spec 19/0 → `EnrollmentFlowSpec` D-64 행 교체(§23.4) → 02:06~02:14Z internal-admin **346/0**(`~/gate-logs/s5-s6-module2-20261026T020659Z.log`). 업무 RED는 아래 변이로 확인.
- 변이·전체 검증: §23.8.

### 23.8 S6 변이·체크포인트(2026-10-26 KST)

- 변이 **18/18 RED**(02:14~02:35Z, `/tmp/s6_mut.py`, 결과 `~/gate-logs/s6-mutations.json`, 모두 원본 복원 + SHA-256 일치): S6-M1 고정 행 INSERT 제거(동시 2회 → 계정 4) · M2 계정 존재 검사 제거 · M3 복구 조건 제거 · M4 작업 A가 활성 역할만 정지(D-99 재append 소실) · M5 기록 역할 비움 · M6 활성 수 0 조건 제거(①·② 이중 복원) · M8 회차 종료 제거 · M9 완료 TX 회차 잠금 대신 비잠금 읽기(직렬화 소실) · M10 작업 B 코드 만료 ≠ 회차 만료(⑦ — 시험 컨텍스트 회차 유효 2h로 구분) · M11 0600 속성 없이 생성 · M12 기존 파일 덮어쓰기 · M13 터미널 없이 TTY 출력 · M14 웹 컨텍스트 가드 제거 · M15 스케줄러 가드 제거 · M16 1062 → `LOST` 분류 제거 · M17 1213 → `RETRY_LATER` 제거 · M18 SUSPENDED 대상 허용 · M19 거부 감사 별도 TX 제거.
- 시도하지 않은 변이: 자동 재활성의 "최신 이벤트 = 이 회차 SUSPEND" 조건 제거 — 회수된 역할은 domain `reactivateRecoveredRoles`의 정지 집합 교집합이 이미 막고(③ 행), 복구 대기 중 재설정은 복구 코드를 폐기해 완료 자체가 불가하므로 관측 가능한 차이가 없다(방어 심층으로 유지).
- 전체 `--no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(02:36:26Z → 03:06:32Z, 30m04s, `~/gate-logs/s6-gate/check.log`): **BUILD SUCCESSFUL**. master-api 320·payment-api 1766·financial-batch 26·internal-admin **346**(S5 재리뷰 뒤 324 + S6 22)·institution-simulator 210·test-support 112 = **2780**(실패·오류·skip 0), Vitest 14. TEST-SUMMARY(하한 2756) PASS, TEST-SUMMARY-FRONTEND(14) PASS, HARNESS-SELFTEST 139 OK(03:06:32Z), CVE-SCAN PASS(03:06:49~03:08:13Z — internal-admin runtime 111, 취약점 0, 프론트 147·canary 6), GRADLE-DEPS-INTERNAL-ADMIN exit 0(testcontainers 0줄).
- commands.json 제안(수정하지 않음): GRADLE-CHECK `minTests`·TEST-SUMMARY 하한 2756 → **2780**(SELFTEST 139·FRONTEND 14 그대로). FINGERPRINT는 Verifier 몫(실행 안 함).
- 남은 것: S7(화면 — 승인함·코드 1회 표시 등), S8(지표 `paycore.admin.oneshot{command,result}`·승인 지표·`integrity_violation`·`AdminNonDisclosureSpec`·시계 대역 경합·IP 거부 감사 범람·마스킹 해제 사유 원문 저장 검토), Q-S5-4·Q-S6-0·Q-S6-1·Q-S6-2.

### 23.9 S6 체크포인트 리뷰(PASS_WITH_FIXES) 반영(2026-10-26 KST — 이 절이 §23.1~23.8과 다르면 이 절이 우선)

Q-S5-4는 D-ADMIN1-016(현재 유지 + S8 지표)으로 확정. RED: 시험을 먼저 쓰고 컴파일만 되는 골격(진입점 인자 함수 추출·LIVE 가드 인자 추가, 판정 없음)으로 03:19~03:22Z 실행 → **업무 RED 6건**(`~/gate-logs/s5-s6f-red-20261026T031959Z.log` 계열: symlink 교체 exit 0, 공유 디렉터리 exit 0, 죽은 회차 `EPISODE_OPEN`, 회차 원인 재활성 형식 예외, LIVE 목록 누락, 형식 오류 원인 500). 구현 → internal-admin 모듈 **354/0**(03:23~03:32Z). 변이 7/8 RED(03:34~03:43Z, 각 원본 복원·hash 일치, 변이별 로그 `~/gate-logs/s5-mut-F-*.log`) + F-m1은 아래 기록.

| 항목 | 반영 | 증거 |
|---|---|---|
| **M-1** 출력 TOCTOU | `CodeOutput.File`: `CREATE_NEW`+`NOFOLLOW_LINKS`+0600으로 연 **채널을 커밋 뒤까지 유지**하고 그 채널에 쓴다. 쓰기 전 경로가 여전히 그 파일(같은 file key·symlink 아님)인지 확인, 아니면 쓰지 않고 exit 7(링크 대상 불변). 그룹·기타 쓰기 가능 디렉터리는 DB 전 거부(`OUTPUT_DIRECTORY_SHARED`). DB 실패 때는 자기가 만든 파일만 지운다 | `BootstrapCommandSpec` symlink 교체 행(exit 7·대상 불변·계정은 커밋됨)·공유 디렉터리 행. 변이 F-M1(동일성 확인 제거)·F-M1b(디렉터리 검사 제거) RED |
| **M-2** 죽은 회차 | 열린 회차가 만료됐거나 **쓸 수 있는 코드 0**이면 조건부 UPDATE(`WHERE closed_at IS NULL`)로 닫고(감사 `RECOVERY_EPISODE_CLOSED reason=EXPIRED|NO_USABLE_CODE`) 진행, 쓸 수 있는 코드가 있으면 `EPISODE_OPEN`. 잠금 순서 회차(UPDATE) → 계정 → 코드 유지(코드 집계는 비잠금 읽기 — 회차 코드는 발급 TX 밖에서 다시 쓸 수 있게 되지 않는다) | 잘못된 대상 → 정지로 코드 폐기 → 다음 복구 성공·이전 회차 닫힘, 쓸 수 있는 코드가 있으면 거부. 변이 F-M2 RED |
| **M-3 (a)** 회차 원인 재활성 | `ApprovalCause`: `ROLE_REACTIVATE` 원인 = 재설정 승인 ID 또는 `episode:<n>`. 회차 원인은 시스템 원인이라 원인 참여자 배제가 없고(일반 2인 규칙 — 요청자 ≠ 승인자 ≠ 대상), 자격 근거도 시스템(D-ADMIN1-014 관리자 배제 없음 — 그 사이 재설정이 있었다면 그 근거가 적용). 복원 대상 = 최신 이벤트가 그 회차 SUSPEND인 역할 | 작업 A 대상의 OPERATOR(기록 밖)와 활성 보유자 조건에 걸린 SA를 2인 승인으로 복원, 다른 회차·그 회차로 정지되지 않은 계정은 `NOTHING_TO_REACTIVATE`. 변이 F-M3(회차 원인 분기 제거 → `CAUSE_NOT_FOUND`) RED |
| m-1 만료 회차 동시 복구 | 닫기 UPDATE가 0행이고 다시 읽은 열린 회차 번호가 다르면 경합 패배(`LOST` — `UniqueConflict(RECOVERY_EPISODE)`로 같은 경로), 같으면 `EPISODE_OPEN` | 장벽 시험(세 번째 연결이 만료 회차 행을 잡아 두 실행을 닫기 UPDATE에 줄 세움 → 첫째 성공·둘째 LOST). **변이 F-m1은 결정적으로 잡지 못했다**: 계측 실행 2회에서는 둘째가 이 0행 분기에 도달(`Refused(DBG_2)`·변이 시 `Refused(EPISODE_OPEN)` 관측)했지만, 같은 변이의 일반 실행 4회(빌드 캐시 끔·`--rerun`)는 통과했다 — 두 경로(0행 분기·1062) 모두 결과가 `LOST`라 시험은 결과를 고정하지만 분기 도달은 InnoDB 잠금 부여 순서에 달려 결정적이지 않다. 후속: 잠금 부여 순서에 기대지 않는 고리(시험 대역 저장 Port로 UPDATE 직전 정지) — Coordinator 판단 |
| m-2 원인 형식 | Use Case가 TX 전에 원인 형식 검사 → `INVALID_INPUT`, HTTP 400(승인 컨트롤러: `INVALID_INPUT`만 400, 업무 거절은 409) | `ApprovalHttpSpec` 행(400·승인 행 0·감사 0). 변이 F-m2 RED |
| m-3 Fixture 이름 | `rolesGrantedViaDomainApprovalPath_noLiveApprovers`(KDoc: 실제 승인 경로는 활성 SA·APPROVER를 늘려 0명 조건 시험에 못 씀), 형식이 맞는 고정 승인 ID | 컴파일·GREEN |
| m-4 감사 비노출 | 작업 A·B 경로에서 시험 중 **모든** 감사 행(행위·결과·detail·reason·상관 ID)에 코드 원문 없음 | `RecoveryCommandSpec` 두 행 |
| nit-1 | 진입점 인자 계산 순수 함수 `isCommandMode`·`effectiveArguments` + 단위 시험. 모드 거부 시 로그 한 줄(`operation=one-shot result=mode-refused reason=web-context|scheduler-enabled` — 예: `PAYCORE_ADMIN_COMMAND` 환경변수가 웹 프로세스에 닿음) | `OneShotArgumentsSpec`, 변이 F-nit1 RED |
| nit-2 | 커밋 뒤 쓰기 실패 안내에 파일 상태(비었거나 일부·교체됐을 수 있음 — 지울 것)를 적는다(코드 없음) | — |
| nit-3 | **수용 기록**: 빈 DB에서 부트스트랩과 복구 작업 B를 동시에 실행하면 둘 다 성공할 수 있다(서로 다른 1회성 권위 — 고정 행·회차 UNIQUE). 결과는 관리자 증가 방향이고 실행자는 호스트 운영자뿐(§4.9.2 n-3·n-H) | — |
| Q-S6-2 | (Coordinator 판정) LIVE 가드 Demo 목록에 `paycore.admin.recovery.episode-validity`(24h) 추가 — `LiveModeStartupGuardSpec` 전체 목록 기대값 확장(강화) | 변이 F-Q RED |

- 결정 추가: **D-109** 출력 파일 = 연 채널 유지·경로 동일성 확인·공유 디렉터리 거부. **D-110** 죽은 회차(만료 또는 쓸 수 있는 코드 0) 자동 종료. **D-111** `ROLE_REACTIVATE` 원인 형식 2종(승인 ID·`episode:<n>`), 회차 원인은 원인 참여자 배제 없음. **D-112** 승인 생성의 입력 형식 오류는 HTTP 400.
- 시험 수: internal-admin 346 → 354(+8).

## 24. S7 구현 기록(2026-10-26 KST, 2단계 Implementer — 자기 완료 판정 아님)

범위: §10 S7(React 화면 — §4.10, D-ADMIN-008): 로그인(비밀번호 → TOTP), 등록(코드 → 비밀번호 → TOTP → 완료), 계정 목록·생성·1인 명령·2인 승인 요청, 승인함(승인·거절 + 직전 TOTP)·내 요청(철회), 등록 코드 1회 표시, 감사 조회, 오류 code 표(서버 JSON의 모든 고정 code), CSRF 쿠키 → 헤더(기존 `apiFetch`), 저장소·URL에 비밀 없음. **조사 중 발견**: 화면이 쓸 HTTP API 중 **등록 흐름과 계정 목록·1인 명령 엔드포인트가 없었다**(S3·S4는 Use Case·인증 HTTP까지) — 같은 단계에서 추가했다(허용 경로 안, 기존 Use Case를 그대로 노출).

### 24.1 변경

| 영역 | 내용 |
|---|---|
| 서버 — 등록 HTTP | `EnrollmentController` `POST /api/enroll/{start,password,totp,complete}`(미인증 — `GET /api/auth/csrf`의 익명 세션 + CSRF). 진행 상태는 `EnrollmentSessionState`의 **문자열 속성만**(코드 hash·암호화된 대기 값은 base64, 코드 원문 없음 — D-40·D-74). `start`에서 세션 ID·CSRF 재발급과 로그인 상태 제거, `totp` 응답의 `otpauthUri`는 1회(no-store), `complete` 성공 시 세션 폐기. 실패 code `ENROLLMENT_CODE_INVALID`·`ENROLLMENT_NOT_APPLICABLE`·`ENROLLMENT_STEP_INVALID`·`PASSWORD_POLICY`(400), 1213·1205 → 409 `RETRY_LATER`. `SecurityConfig`: 이 4경로만 익명 POST 허용 |
| 서버 — 계정 HTTP | `AccountController` `GET /api/accounts`(jOOQ `AdminAccountListReader` → `AccountQueryService` — 조회 권한 = 계정 현재 행 ACTIVE + VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN, 비밀값 열은 SELECT하지 않음), `POST /api/accounts`(201 + 코드 1회), `POST /api/accounts/{id}/suspend`·`/enrollment-code`(코드 1회)·`/roles/{role}/revoke`. 결과: 403 `FORBIDDEN`·400 `INVALID_INPUT`·409 `<업무 code>`, 모두 no-store |
| 프론트 — API | `adminApi.ts`(화면이 쓰는 API 전부 — `apiFetch` 하나로, 경로 세그먼트 인코딩, 감사는 필터만 쿼리로), `errorCodes.ts`(서버 code ↔ 허용 HTTP 상태 표 — 어긋나면 일반 오류), `messages.ts`(code → 고정 문구) |
| 프론트 — 화면 | `App`(라우터·저장소 없음, 메모리 상태만 — 역할별 메뉴는 표시만, 세션 종료 code는 로그인 화면 + 안내), `LoginScreen`, `EnrollmentScreen`(TOTP는 **설정 키 문자열** 표시 — QR 아님, D-116), `AccountsScreen`, `ApprovalsScreen`(6자리 TOTP 전에는 승인·거절 비활성), `AuditScreen`, `OneTimeCode`(1회 표시 대화상자 — 닫으면 부모 상태에서 제거) |
| 의존성 | **npm 의존성 추가 없음**(lockfile 불변 — 14일 규칙·CVE 대상 변화 없음). Gradle 의존성 변경 없음 |
| 시험 | Kotlin `AccountEnrollmentHttpSpec` 3(생성 → 코드 1회 → 목록에 없음 → 새 브라우저로 등록 4단계 → 세션 재발급·폐기 → 새 자격으로 로그인 / 등록 실패 code / 1인 명령 code·권한·입력 오류). Vitest 14 → **35**(+21: `errorCodes` 2, `LoginScreen` 3, `EnrollmentScreen` 3, `OneTimeCode` 1, `ApprovalsScreen` 3, `AccountsScreen` 3, `AuditScreen` 2, `App` 4) — 각 화면 시험은 브라우저 저장소가 비어 있음(코드·비밀 비보관)을 단언 |

### 24.2 구현 결정(가역 — 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| D-113 | 등록 HTTP: 익명 세션(기존 csrf 엔드포인트) + 문자열 세션 속성, 시작 때 세션 ID·CSRF 재발급(고정 방지)·로그인 상태 제거, 완료 때 세션 폐기, 코드 무효·적용 불가는 진행 상태도 지움 | D-40·D-74·§4.3.6. 등록은 미인증 흐름이라 로그인 세션과 섞지 않는다 |
| D-114 | 계정 HTTP: 목록은 jOOQ Reader(조회 서비스만 — D-86 규칙 통과), 1인 명령은 기존 Use Case 결과를 고정 code로만 옮긴다 | CQRS·권한은 서버 |
| D-115 | SPA는 라우터·브라우저 저장소를 쓰지 않는다(화면 상태는 메모리, URL에 비밀·코드 없음). 메뉴는 `GET /api/auth/me`의 현재 역할로 **표시만** | §4.10·D-ADMIN-008, 권한 판정은 서버 |
| D-116 | TOTP 등록은 QR 대신 수동 입력용 설정 키(base32) 문자열을 1회 보여 준다 | QR 인코더는 npm 의존성(14일 규칙·CVE·lockfile) 또는 자체 구현이 필요 — §24.5 Q-S7-1 |
| D-117 | 등록 코드는 `OneTimeCode` 대화상자로만, 닫으면 부모 상태에서 제거(4자 묶음 표시, "닫으면 다시 볼 수 없음" 안내) | §4.7.7 |
| D-118 | 오류 code는 서버가 쓰는 HTTP 상태와 함께 올 때만 그 code(예: `CREDENTIAL_ISSUER` 403·409, `IP_DENIED` 403), 그 밖 업무 code는 일반 거절 문구 | S1 리뷰 n5 원칙 확장 |

### 24.3 개발 서버·HTTPS 메모(`__Host-` 쿠키)
- 세션·XSRF 쿠키는 `__Host-` 접두라 `Secure`·`Path=/`·Domain 없음이 필수다. 브라우저는 **HTTPS**에서만 저장하며, Chrome·Firefox는 `http://localhost`를 보안 문맥으로 취급해 로컬 개발에서도 동작한다(그 밖의 호스트 이름·IP의 HTTP는 쿠키가 저장되지 않아 로그인할 수 없다).
- 화면은 Spring 앱이 같은 출처 정적 번들로 서빙한다(`processResources` ← `frontendBuild`). Vite dev server는 프록시를 두지 않았다 — 개발은 번들을 빌드해 앱(https 또는 `http://localhost`)에서 확인한다. dev server 프록시를 둘 때도 같은 출처·HTTPS 규칙을 지켜야 한다.

### 24.4 RED → GREEN·변이
- 서버 RED: `AccountEnrollmentHttpSpec` 먼저 → 04:02:09Z 실행 3건 실패(404·401 — 엔드포인트 없음, `~/gate-logs/s5-s7-red-20261026T040209Z.log`) → 구현 → 31/0(+ArchitectureSpec). 계정 목록 Reader·조회 서비스 4파일은 시험보다 먼저 썼다(그 RED는 변이 K-M4로 대신).
- 프론트 RED: 04:06:54Z typecheck·Vitest 실패(새 모듈 없음·새 code 타입 없음, App 4건·errorCodes 2건 실패, `~/gate-logs/s7-fe-red-20261026T040654Z.log`) → 구현 → 04:10:24Z **35/0**(typecheck 포함).
- 변이 **10/11 RED**(04:11~04:17Z, `/tmp/s7_mut.py`, `~/gate-logs/s7-mutations.json`, 모두 원본 복원·hash 일치 — 빌드 캐시 끔·`--rerun`): FE-M1 1회 코드 닫기가 상태를 지우지 않음 · FE-M2 TOTP 6자리 전 승인 허용 · FE-M3 code의 HTTP 상태 검사 제거 · FE-M4 로그인 실패 뒤 비밀번호 유지 · FE-M5 세션 종료 code에 로그인 화면으로 가지 않음 · FE-M7 "다시 볼 수 없음" 안내 제거 · K-M1 등록 시작 세션 ID 재발급 제거 · K-M2 완료 세션 폐기 제거 · K-M3 `INVALID_INPUT` 400 → 409 · K-M4 계정 목록 권한 검사 제거.
- 생존 FE-M6(완료 뒤 설정 키 상태를 지우지 않음): 완료 단계에서 설정 키 영역이 그려지지 않아 DOM으로 관측할 수 없고, 화면을 떠나면 컴포넌트 상태가 사라진다 — 관측 가능한 차이 없음(동등)으로 기록.

### 24.5 질문 후보(보안·운영 의미 — 결정하지 않음)
- **Q-S7-1** TOTP 등록 QR: 명세 §4.10은 "TOTP QR"인데 QR 인코더가 없다. 대안 (a) npm QR 라이브러리 추가(14일 규칙·CVE·lockfile·라이선스 검토), (b) 자체 QR 인코더(시험 부담), (c) 현재처럼 설정 키 수동 입력만. 권장: (c) 유지 + LIVE 전 (a) 검토.
- **Q-S7-2** `ROLE_REACTIVATE` 요청 화면(원인 재설정 ID·`episode:<n>` 선택)은 만들지 않았다 — 원인 후보 목록 조회 API가 없다. 권장: 후속(화면에서 원인을 자유 입력받지 않도록 조회 API와 함께).
- 마스킹 해제 화면은 AC10대로 틀까지(대상 데이터 공급자 없음 — 첫 실제 화면은 후속). (→ 답: 첫 적용 = KYC, D-FLOW-002 ① — MERCHANT-FLOW-001)

### 24.6 8대 원칙(S7 범위)

| 원칙 | 증거 |
|---|---|
| Financial Consistency | 해당 없음 |
| Transaction Safety | 화면은 서버 Use Case만 호출(외부 호출 없음), 등록 완료는 기존 완료 TX |
| Idempotency | 1회 표시 코드는 다시 볼 수 없음(서버도 재표시 없음), 승인 결정은 서버의 `ALREADY_DECIDED` 문구로 |
| Concurrency Control | 409 `RETRY_LATER`를 재시도 안내로(서버 판정 그대로) |
| Recoverability | 세션 종료 code → 로그인 화면 + 이유 안내, 등록 단계 오류는 처음부터 |
| Reconciliation | 해당 없음 |
| Auditability | 화면이 하는 모든 행위는 서버가 감사(화면은 감사 범위를 보내지 않음 — 필터만) |
| Observability | 오류는 고정 code·고정 문구만(서버 메시지·입력 echo 없음) |

### 24.7 S7 체크포인트(전체 검증, 2026-10-26 KST)
- 전체 `--no-daemon --console=plain --no-build-cache --rerun-tasks --continue check`(04:22:05Z → 04:52:33Z, 30m26s, `~/gate-logs/s7-gate/check.log`): **BUILD SUCCESSFUL**. master-api 320·payment-api 1766·financial-batch 26·internal-admin **357**(S6 수정 뒤 354 + S7 3)·institution-simulator 210·test-support 112 = **2791**(실패·오류·skip 0), Vitest **35**.
- TEST-SUMMARY(하한 2780) PASS, TEST-SUMMARY-FRONTEND(하한 14) 35 PASS, HARNESS-SELFTEST 139 OK(04:52:33Z), CVE-SCAN PASS(04:52:51~04:54:13Z — internal-admin runtime 111, 취약점 0, 프론트 147·canary 6, **lockfile SHA-256 불변** `15a4ed3e…` = npm 의존성 변화 없음), GRADLE-DEPS-INTERNAL-ADMIN exit 0.
- commands.json 제안(수정하지 않음): GRADLE-CHECK `minTests`·TEST-SUMMARY 하한 2780 → **2791**, TEST-SUMMARY-FRONTEND 하한 14 → **35**(SELFTEST 139 그대로). FINGERPRINT는 Verifier 몫.
- 남은 것: S8(지표·`AdminNonDisclosureSpec`·시계 대역 경합·IP 거부 감사 범람·마스킹 해제 사유 원문 검토·management 포트·HSTS·문서), Q-S5-? 이월 없음(Q-S5-4 → D-ADMIN1-016), 미결 Q-S6-0·Q-S6-1·Q-S7-1·Q-S7-2, §23.9 F-m1 결정성 후속.

### 24.8 S7 체크포인트 리뷰(PASS_WITH_FIXES) 반영(2026-10-26 KST — 이 절이 §24.1~24.7과 다르면 이 절이 우선)

Q-S6-0·Q-S6-1은 Coordinator가 fail-closed로 수용(미결 아님). Q-S7-3 → **D-ADMIN1-017**(SECURITY_ADMIN만 login_name).

| 항목 | 반영 | 증거 |
|---|---|---|
| S6 m-1 결정성 | 장벽을 Coordinator 설계로 교체: 세 번째 연결이 작업 A 대상 **계정 행**을 잡는다 → TX1이 만료 E1을 닫고(E1 X 잠금) E2 INSERT 뒤 대상 행에서 대기(`awaitWaiters admin_account 1`) → TX2 시작 → 일관 읽기로 E1이 열린 것으로 보이고 닫기 UPDATE가 TX1의 E1 잠금에서 대기(`awaitWaiters admin_recovery_episode 1, blockedByHolder = false`) → 놓으면 TX1 커밋 → TX2 UPDATE 0행 → 다시 읽은 열린 회차 E2 → LOST. `findOpen()`은 `closed_at IS NULL ORDER BY episode_no DESC LIMIT 1`(클러스터 행 — 생성 열 UNIQUE 보조 인덱스 경유 일관 읽기는 같은 키의 미커밋 INSERT가 있으면 이전 버전을 놓친다) | 경로 확인 probe(06:23Z, 시험 뒤 원복·hash 일치): `pool-4-thread-2 close=false again=2` → 변이 줄 도달. F-m1 **RED**(06:31:40Z) |
| **F-m1 생존 원인(새 발견)** | 장벽·캐시가 아니라 **Kotest 6.2.5 동등 비교**: `(true to results[1]) shouldBe (true to OneShotResult.Lost)`는 `Pair`(data class)의 필드별 비교라 기대 쪽 `data object`(필드 없음)와 비교하면 실제 값이 `Refused`여도 통과한다. 임시 probe spec(실행 뒤 삭제)으로 확인: `pair` 통과, `list`·직접 비교·data class끼리는 실패. internal-admin의 같은 꼴 5곳(RecoveryCommandSpec 2·`(mode to outcome)` 1, ApprovalLockOrderSpec `(op|mode to outcome)` 2)을 **직접 비교**(`results[1] shouldBe Lost`, `withClue(mode) { outcome shouldBe RetryLater }`)로 바꿨다 — 기대값은 그대로(Lost·RetryLater), 비교만 실제로 강제 | 변이 EQ-lost·EQ-retry-oneshot·EQ-retry-approval **RED**(06:32~06:37Z — 수정 전에는 같은 변이가 통과했을 꼴). 다른 모듈은 단순 grep로 같은 꼴 0건(정밀 검사는 Reviewer 몫) |
| m-1 | `errorCodes.ts`: 표에 없는 문자열 `error`는 `REQUEST_REJECTED`, HTTP 상태로 분류는 본문 code가 없을 때만. `client.test.ts`의 `403 {error:'<script>x</script>'}` 기대값을 `FORBIDDEN` → `REQUEST_REJECTED`로(Coordinator 판정 — 기존 기대값 변경 기록) | Vitest 행 `(409,{error:'ROLE_NOT_HELD'})`→REQUEST_REJECTED, `(409,null)`→RETRY_LATER. 변이 FE-m1 RED |
| m-2 → D-ADMIN1-017 | 계정 목록: SECURITY_ADMIN 조회만 `loginName`, 그 밖 역할은 **열을 SELECT하지 않고**(jOOQ `takeIf`) 응답 키도 없다. 정렬은 표시명·actorId. 화면은 열이 없으면 로그인 이름 열을 숨긴다 | HTTP: VIEWER·OPERATOR·APPROVER 본문에 login_name·`"loginName"` 없음, SA만 있음. 변이 D17-query·FE-D17 RED |
| m-3 | 등록 화면: `ENROLLMENT_CODE_INVALID`·`NOT_APPLICABLE`·`STEP_INVALID`면 'code' 단계로, 설정 키·계획·입력 삭제 | Vitest 행. 변이 FE-m3(설정 키 지우기 제거)는 **DOM 수준 동치**(다음 'totp' 단계는 항상 새 키를 받으므로 남은 키가 보이지 않는다 — 메모리 보유만) → 관측 가능한 FE-m3b(단계 복귀 제거)로 대체 RED |
| m-4 | (a) XSRF 헤더 없는 상태 변경 403·무변경 (c) 시작 전 CSRF 토큰으로 비밀번호 단계 403 (d) 로그인 세션에서 등록 시작 → 그 세션은 인증 API 401 (e) 허용 목록 밖 403 `IP_DENIED`. **(b) 편차**: 세션 없는 시작은 401이 아니라 **403** — CSRF filter가 컨트롤러 전에 거부한다(세션이 없으면 토큰도 없음). 기대값을 실제 동작(403)으로 적고 보고한다 | HTTP 시험 |
| m-5 | IP 거부 감사를 `/api/enroll/`에도(`path=ENROLL`) — `recordIpDenied(area)` 고정 값 `AUTH`·`ENROLL`만 | 변이 m5-enroll RED |
| nit-1·2 | 불필요한 `storage.securityAdmin()` 제거, `CodeOutput` 권한 실패 분기는 file key 확인 뒤에만 삭제 | — |
| nit-3·4 | nit-3은 §25.5 범람 항목에 등록 시작·TOTP 비용 포함, nit-4는 차이만 기록 | — |

검증: internal-admin 모듈 시험 362/0(S7 수정 직후), 수정한 두 spec 재실행 RecoveryCommandSpec 15/0·ApprovalLockOrderSpec 11/0(동등 비교 수정 뒤), Vitest 37/0(RED 4 기록 뒤). 변이 결과
`~/gate-logs/s7-fix-mutations.json`(1차 — F-m1·F-m1-index 생존 → 원인 분석)·`~/gate-logs/s7-fix-mutations-2.json`(2차 — 5/5 RED). 변이 실행기는 `--no-build-cache --rerun`. F-m1-index(열린 회차 읽기를
보조 인덱스로 되돌림)는 결과 수준 동치 후보(두 경로 모두 LOST — §25.7 재실행 결과).

## 25. S8 구현 기록(2026-10-26 KST, 2단계 Implementer — 자기 완료 판정 아님)

범위: §10 S8(관측·비노출 묶음, 문서, 전체 check·GRADLE-DEPS·CVE-SCAN) + 이월(D-ADMIN1-016 지표, `integrity_violation` 경보, commit 단계 예외 실제 주입, `ApprovalEnvironment` 시계 대역 경합,
IP 거부·등록 범람 검토, 마스킹 해제 사유 검토, management 포트 D-15·HSTS, Q-S7-2 `ROLE_REACTIVATE` 원인 후보 API·화면).

### 25.1 변경

| 영역 | 변경 |
|---|---|
| 지표 | Port `AdminAccountMetrics`·`ApprovalMetrics` → `MicrometerAdminAccountMetrics`·`MicrometerApprovalMetrics`. 로그인(`AdminLoginService` — 감사와 같은 결과 분류, TX 뒤), 잠금, 재인증 실패 계수 누락(`AuthenticationFailureService` — D-ADMIN1-016, 실패 분류 `DEADLOCK`·`LOCK_TIMEOUT`·`OTHER`), 등록(`EnrollmentService` 네 단계 — D-67 코드 무효는 지표만), 1회성 명령(`OneShotCommandService`), 승인(`ApprovalService` — 요청·승인·거절·철회·만료 job, 종류 label은 TX 안에서 읽은 값, `COMMIT_UNKNOWN`), `retry_later`, `integrity_violation{check}`, IP 거부 `ip_denied{area}`(`IpAllowlistFilter` — 감사 없는 경로도 `OTHER`로) |
| 비노출 | `AdminNonDisclosureSpec`(새) — §25.3 |
| management 포트 | `management.server.port: ${PAYCORE_ADMIN_MANAGEMENT_PORT:-1}`(없으면 HTTP actuator 없음), `address` 기본 `127.0.0.1`. `ManagementServerRequestMatcher`(Boot management 자식 컨텍스트 namespace) + `managementSecurityFilterChain`(`@Order(0)` — `GET /actuator/health`만 익명, 그 밖 거부, 세션·CSRF 없음). `.env.example`에 변수(빈 값 금지 안내) |
| HSTS | 공통 헤더에 `Strict-Transport-Security: max-age=31536000 ; includeSubDomains`를 **모든 응답**에(`AnyRequestMatcher`) |
| Q-S7-2 | domain `RoleSuspensions.byCause`(+ `RoleChange` — `RoleEvent`가 구현, 실행 쪽 `rolesSuspendedBy`도 이것으로 교체: 같은 판정), jOOQ `AdminRoleEventReader`, `AccountQuery.reactivationCauses`(ACTIVE SECURITY_ADMIN만 — 아니면 403), `GET /api/accounts/{actorId}/reactivation-causes`, 화면 `ReactivationRequest`(정지 역할 있는 행만, 서버 후보에서만 선택 — 자유 입력 없음) |
| 시험 | `AdminObservabilitySpec`·`AdminNonDisclosureSpec`·`RoleSuspensionsSpec`(새), `ApprovalFailureInjectionSpec`(commit 단계 주입 2·시계 경합 1·무결성 지표 단언), `SpaServingSpec`(management 포트·HSTS), `AccountEnrollmentHttpSpec`(원인 후보), `RecoveryCommandSpec`(oneshot 지표), Vitest 2 |
| 문서 | 관례 §1 표(internal-admin 소유 DB)·§20(새 절), HVC §7, TECH-BASELINE §9.2, 이 절 |

### 25.2 구현 결정(가역 — 다르게 보면 질문으로 승격)

| ID | 결정 | 이유 |
|---|---|---|
| D-119 | 지표는 로컬 TX가 끝난 뒤 1회(rollback된 시도는 결과 분류 `RETRY_LATER`·`ERROR`·`COMMIT_UNKNOWN`) | 지표가 커밋되지 않은 결과를 세지 않게 |
| D-120 | label: 로그인 `step` `PASSWORD`·`TOTP`, 결과 = 감사 결과 code + `UNKNOWN_ACCOUNT`(없는 계정 — 응답은 같은 `INVALID_CREDENTIALS`)·`RETRY_LATER`·`ERROR`. 승인 `{operation,kind,result}`(명세의 `{kind,result}`에 operation 추가 — 요청·승인의 DENIED 등을 구분) | 고정 유한 값, 응답 비구분은 그대로(지표는 내부) |
| D-121 | 1회성 명령 지표는 등록하지만 기본 관측은 종료 코드·감사다(짧은 비웹 프로세스 — management 포트 없음, push 수집기 미구성) | 지표만으로 보인다고 주장하지 않음 |
| D-122 | management 포트 변수 없음 = `-1`(HTTP actuator 없음 — 노출 없는 쪽으로 실패하되 웹은 기동), 기본 바인딩 loopback | D-15 공격 면, 명령 모드에 쓰지 않는 변수를 강제하지 않음 |
| D-123 | HSTS를 모든 응답에(`max-age` 1년·`includeSubDomains`, `preload` 없음) | TLS는 앞단에서 끝나고 `forward-headers-strategy=none`이라 https로 보이지 않는다. 평문 응답의 HSTS는 무시된다(RFC 6797 §8.1) |
| D-124 | 원인 후보 = 역할마다 마지막 이벤트가 원인 있는 SUSPEND인 원인(실행 쪽과 같은 domain 함수). 후보 조회는 원인 유효성(실행된 재설정·참여자 배제)을 판정하지 않는다 — 요청·승인 TX가 판정 | 화면 자유 입력 제거(Q-S7-2), 판정 경로 하나 |
| D-125 | 마스킹 해제 사유 자유 문구는 **바꾸지 않는다**(감사 `reason` 열에 원문, 로그·응답·지표에 없음 — 시험으로 고정). 사유 안의 카드번호 같은 패턴 마스킹은 감사 의미를 바꾸므로 질문(Q-S8-B) | 감사 의미 보존 |

### 25.3 비노출(`AdminNonDisclosureSpec`)

한 번의 흐름(로그인·틀린 비밀번호·**따옴표 없는 비밀번호 토큰의 틀린 JSON**·계정 생성·등록 3단계·재설정 승인(HTTP)·마스킹 해제 실패/성공)에서 `com.paycore` 로거를 DEBUG로 두고 루트 logger의 메모리 appender로
메시지·인자·MDC·예외 사슬(cause·suppressed)을 모은다. 비밀값: 비밀번호 계열 4(틀린 JSON 토큰 포함)·TOTP 코드 4(6자리는 앞뒤 영숫자 경계로 판정)·TOTP 설정 키·등록 코드·재설정 코드·세션 쿠키·세션 ID(base64 해석)·CSRF 토큰·시험 암호화/HMAC 키·사유.
단언: ① 로그 0회, ② 감사(사유 열 제외)·승인 행·등록 코드 행 0회, 세션 속성 행에는 CSRF·세션 값 외 비밀 0회(등록 진행 값은 암호문), 사유는 `UNMASK_REQUESTED|SUCCESS` 행 `reason` 열에만,
③ 1회 표시 값은 자기 응답 하나(생성·`/enroll/totp`·승인)에만, 그 밖 비밀값은 어떤 응답에도 없음. 변이: 로그인 비밀번호 DEBUG 로그 추가·실행 감사 detail에 코드 추가 → RED(§25.7).
프레임워크 로거는 운영 수준 그대로 본다 — `org.springframework.web` DEBUG는 파서 오류 문구(토큰 원문)·요청 단편을 남길 수 있으므로 운영 금지(관례 §20).

### 25.4 commit 단계 예외·시계 대역

- `CommitFaultTransactionManager`(시험 — `JpaTransactionManager` 상속, 무장한 스레드의 다음 commit 1회): (a) DB commit 뒤 응답 유실 → `CommitOutcomeUnknownException`·지표 `COMMIT_UNKNOWN` +1, 다시 읽으면 EXECUTED,
  재승인 ALREADY_DECIDED, 실행 감사 1행. (b) DB에 닿기 전 실패 → 호출자에게 같은 `COMMIT_UNKNOWN`, 실제로는 PENDING·TOTP 단계 미사용, 재승인 1회 실행.
- **실측 발견(보고 — 결정하지 않음, Q-S8-D)**: (b)를 처음에는 `TransactionSystemException`(TransactionException)으로 주입했더니 Spring이 rollback하지 않고(`processCommit` — `rollbackOnCommitFailure = false` 기본값이면
  TransactionException 분기는 rollback 없이 완료 처리) 바로 이은 재승인이 `RETRY_LATER`였다(잠금이 남은 것으로 보임 — 원인은 완전히 분리하지 못함). 시험은 실제 rollback이 일어나는 런타임 예외로 바꿨다.
  운영에서 JDBC commit 실패가 TransactionException으로 오는 경우의 연결·잠금 정리는 Hikari 반납 rollback에 기대는 상태다.
- `OffsetApprovalEnvironment`(시험 — `ApprovalSettings` 위임 + offset): 세 번째 연결이 승인 행을 잡은 동안 승인이 그 행에서 대기(`awaitWaiters`) → 시계 +25h → 놓으면 잠금 뒤 TX 시각으로 판정해 **EXPIRED** 커밋,
  실행 없음, 승인자 TOTP 단계 미사용, 감사 `APPROVAL_REQUESTED`·`APPROVAL_EXPIRED`. 변이(판정 시각을 0단계 전으로) RED.

### 25.5 IP 거부·등록 요청 범람(nit-3 포함 — 결정하지 않음, Q-S8-A)

현재: 허용 목록 밖의 미인증 경로(`/api/auth/`·`/api/enroll/`) 거부마다 감사 1행(별도 TX) + 모든 거부 지표. 허용 목록 **안**의 비용: 로그인 비밀번호 단계는 BCrypt 1회(cost 12 — 없는 계정도 더미 1회),
등록 시작은 SHA-256 1회 + 읽기 TX 1회, 등록 TOTP는 시크릿 생성 + AES-GCM 1회 + 읽기 1회, 등록 비밀번호는 BCrypt 1회(유효한 진행 세션 필요). 감사 집계(IP·창 단위 1행 + 억제 건수)나 속도 제한
(초과분 감사 생략)은 **감사 완전성 의미를 바꾸므로 구현하지 않았다** — 지표 `ip_denied{area}`·`login{…}`·`enrollment{…}`로 관측만 한다.

### 25.6 질문 후보(보안 의미 — 구현 결정하지 않음, Coordinator가 사용자에게)

| ID | 질문 | 권장안 | 대안 | 막히는 것 |
|---|---|---|---|---|
| Q-S8-A | IP 거부·등록 요청 범람 시 감사 1행/거부를 유지할지 | 유지 + 지표 경보(감사 완전성 우선, 범람은 앞단 WAF/속도 제한 — LIVE Gate) | (b) IP·1분 창 집계 1행 + 억제 건수, (c) 앱 속도 제한(초과 감사 생략) | 운영 배치 기준(기능 차단 없음) |
| Q-S8-B | 마스킹 해제 사유에 카드번호 같은 패턴이 들어오면 | 현재 유지(원문 감사, 화면 안내로 금지) + 후속 대상 데이터 공급자 작업에서 재검토 | 저장 전 PAN 패턴 거부(400) 또는 마스킹 저장 | AC10 실제 공급자(후속) |
| Q-S8-C | 승인 `targetSnapshot`(JSON)의 loginName이 승인 조회 역할 전원에게 보인다 — D-ADMIN1-017 범위인가 | 범위에 포함(스냅샷에서 loginName 제거 또는 SA만) | 범위 밖(승인 판단 정보로 유지) | 승인 목록 응답 |
| Q-S8-D | commit 단계 TransactionException의 rollback(`rollbackOnCommitFailure`) | 전 모듈 `LocalTransactions` 관리자에 `true` 검토(별도 작업 — 모든 앱 영향) | 현재 유지(Hikari 반납 rollback 의존) | 없음(관찰) |
| (보고) | S7 m-4(b) 기대 401 → 실제 403(CSRF가 컨트롤러 전) | 403 유지 | 세션 없는 등록 시작만 CSRF 예외 + 401 | 없음 |

### 25.7 RED → GREEN·변이

- TDD 순서의 정직한 기록: 화면(원인 후보)은 시험 먼저 → **RED 2**(06:43:47Z, 동작 실패) → 구현 → Vitest 39/0. 백엔드 지표·management 포트·HSTS·원인 API는 **구현을 먼저 쓰고** 시험을 붙였다 →
  시험이 그 동작을 실제로 잡는지는 변이로 증명한다(아래 — 변이마다 원본 복원 SHA-256 일치).
- 첫 실행에서 시험 쪽 결함 2건: commit 대역을 명령 생성(세대 읽기 TX) 전에 무장 → 그 TX가 소비(명령을 먼저 만들고 무장으로 수정), Boot 4.1.1 health 응답에 probe 그룹 이름 포함(`{"groups":["liveness","readiness"],"status":"UP"}` — 상세 없음, 기대값을 실측으로).
- 변이 결과: **18/18 RED**(06:59:29Z~07:17:03Z, `~/gate-logs/s8-mutations.json`, 모두 원본 복원·SHA-256 일치): M8-1 로그인 지표 제거·M8-2 잠금 지표 제거·M8-3 계수 누락 분류(LOCK_TIMEOUT) 제거·M8-4 IP 거부 지표 제거·M8-5 등록 결과 label 고정·M8-6 승인 kind label UNKNOWN·M8-7 무결성 지표 제거·M8-8 COMMIT_UNKNOWN→ERROR·M8-9 oneshot 지표 제거·M8-10 management 체인 health 허용 제거·M8-11 HSTS 제거·M8-12 원인 후보 SA 검사 제거·M8-13 `RoleSuspensions` 마지막→첫 이벤트·M8-14 판정 시각을 잠금 전으로·M8-15 비밀번호 DEBUG 로그 추가·M8-16 실행 감사 detail에 코드 추가·M8-FE1 선택 대신 첫 후보 전송·M8-FE2 정지 역할 없는 행에도 재활성 버튼. 추가로 F-m1-index(열린 회차 읽기를 보조 인덱스로 되돌림)를 동등 비교 수정 뒤 재실행(07:17:41Z) → **생존**: 두 경로(닫기 0행 → LOST / INSERT 1062 → LOST) 모두 같은 결과·같은 감사라 결과 수준 동치 변이로 기록한다(클러스터 행 읽기는 읽기 정확성을 위해 유지).
- 대상 spec 실행(GREEN): 06:52~06:58Z `s5-s8-first`·`s5-s8-second`·`s5-s8-third`(ApprovalFailureInjectionSpec 7/0, SpaServingSpec 14/0, AdminObservabilitySpec 6/0, AdminNonDisclosureSpec 1/0, AccountEnrollmentHttpSpec 9/0, RecoveryCommandSpec 15/0, RoleSuspensionsSpec 2/0). Vitest 39/0(06:44:22Z).

### 25.8 8대 원칙(ADMIN-BOOT-001 전체 — S1~S8)

| 원칙 | 증거(대표) |
|---|---|
| Financial Consistency | 관리자 기반은 금융 사실을 만들지 않는다 — 금융 표·다른 소유 DB 접근 없음(ADMIN DB만, ArchUnit·DB 계정 권한 시험). 권한 변경의 일관성: 역할 이벤트(append-only)와 계정 현재 역할 집합을 같은 TX에서, 원인 후보와 실행이 같은 domain 판정(`RoleSuspensions`) |
| Transaction Safety | 모듈별 로컬 TX(READ COMMITTED·이미 TX면 거부), 외부 호출 없음, 승인 단일 TX orchestrator(rollback되는 거부는 별도 TX 감사), commit 결과 불명은 재시도 없이 500 + 다시 읽어 수렴(실제 commit 단계 주입 2경로 — §25.4) |
| Idempotency | 1회 표시 코드는 해시 + 원자 소비 UPDATE(재사용 무효), 승인 결정은 `ALREADY_DECIDED`(이중 실행 없음 — 실행 감사 1행), 부트스트랩 고정 행 PK·열린 회차 UNIQUE(경합 패배 LOST), 중복 PENDING UNIQUE |
| Concurrency Control | 전역 잠금 순서(§4.13 — 회차 → 승인 행 → 계정 오름차순 → 코드), 행 잠금 + `@Version`, 1213·1205 → 409 `RETRY_LATER`(전체 rollback), 실제 MySQL 경합 시험(`RowLockHolder` 장벽 — 승인 vs 종료·동시 실패 계수·회차 m-1·만료 도래), Kotest data object 동등 비교 함정 제거(§24.8) |
| Recoverability | 오프라인 복구(회차·작업 A/B·자동 재활성 조건 fail-closed), 죽은 회차 닫기, 코드 출력 실패 시 재발급 경로 안내(코드 이미 커밋), 세션 종료 code → 로그인 화면, 등록 단계 오류 → 처음부터 |
| Reconciliation | 해당 없음(금융 대사 없음). 대신 감사 조회 단일 경로와 역할 이벤트 이력으로 계정 현재 상태를 재구성·대조할 수 있다 |
| Auditability | 모든 관리자 행위·거부·무결성 위반·IP 거부(미인증 경로)·1회성 명령 감사(행위자·실행자 = 승인자 D-ADMIN1-006), 감사 표 append-only(runtime 권한 SELECT·INSERT), 마스킹 해제 사유 원문은 감사 `reason`에만 |
| Observability | 고정 code 응답·예외 클래스 이름 로그, 지표(§25.1 — label 유한값, 커밋 결과만, 경보 대상 2종), management 포트 health(상세 없음), `AdminNonDisclosureSpec`(로그·저장 행·응답 비노출) |

JPA·jOOQ 설명은 관례 §20(이번 코드의 역할·예·이유·주의 — 잠금 읽기·불변 열·조건부 닫기 UPDATE·클러스터 행 읽기), BOM 호환은 Boot 4.1.1 관리 버전만(새 Gradle 의존성 S8 없음 — GRADLE-DEPS 결과 §25.9).
Kafka/Redis는 도입하지 않았다 — 세션·잠금·멱등은 ADMIN DB와 Spring Session JDBC가 지키며, broker·캐시가 권한·감사 안전성을 대신한다고 보지 않는다(ADR-001).

### 25.9 S8 체크포인트(전체 검증)

- **전체 `--continue check`는 완료되지 않았다(환경 사유 — 업무 RED/PASS 아님)**: 07:18Z 시작(`~/gate-logs/s8-gate/check.start`) 직후 Claude Code가 시스템 메모리 부족으로 백그라운드 명령을 중단했다(financial-batch:test 진행 중, 07:20Z). 남은 Gradle JVM은 정리했다. 지시에 따라 스스로 재실행하지 않았다 — Coordinator 지시 후 `/tmp/s8_gate_inner.sh`(하한 2791·프론트 35)로 재실행한다.
- 따라서 이 체크포인트의 전체 개수·TEST-SUMMARY·CVE-SCAN·GRADLE-DEPS는 **미측정**. 확인된 것: HARNESS-SELFTEST 139 OK(07:2xZ, 단독 실행), 프론트 lockfile SHA-256 `15a4ed3e…` HEAD와 동일(npm 의존성 변화 없음), S8에서 Gradle 빌드 파일 변경 없음(새 Gradle 의존성 없음 — GRADLE-DEPS 변화 없을 것으로 예상, 미측정).
- 예상 개수(미검증): internal-admin = 362(S7 수정 뒤) + 신규/추가 시험 → 전체 check로 확정.

### 25.10 남은 것(이 작업 범위 밖·후속)

- 지표 중 gauge(`approval.pending`·`pending.oldest_age_seconds`·`session.rows`·`anonymous_created`)와 `session.invalidated{reason}`·`audit.write_failed`는 만들지 않았다(Reader 기반 gauge — 후속 관측 작업).
- **LIVE 전 QR 라이브러리 검토**(Q-S7-1 후속): 현재는 설정 키 수동 입력만. QR을 넣으려면 npm 라이브러리(14일 규칙·CVE·lockfile·라이선스 검토) 또는 자체 인코더(시험 부담) 중 선택 — LIVE Gate 항목.
- 마스킹 해제 실제 대상 데이터 공급자(AC10 — 후속 작업), 원인 후보 화면의 원인 상세(재설정 종류·시각) 표시.
- Q-S8-A~D 답변 대기(위 표).

### 25.11 S8 리뷰(PASS_WITH_FIXES) 반영(2026-10-26 KST — 이 절이 §25.1~25.10과 다르면 이 절이 우선)

Coordinator 체크포인트(분리 실행): check 31m43s BUILD SUCCESSFUL, TEST-SUMMARY 2811/0(internal-admin 377), 프론트 39, SELFTEST 139, CVE PASS, DEPS ok(`~/gate-logs/s8-gate`) → commands.json Kotlin 2811·프론트 39.
사용자 결정: **D-ADMIN1-018**(Q-S8-A — 거부마다 감사 1행 유지 + `ip_denied` 지표 경보, 앞단 속도 제한은 배치 항목), **D-ADMIN1-019**(Q-S8-B — 사유 원문 유지·길이 상한, PAN 패턴 거부는 첫 실제 마스킹 해제 작업에서 재검토 → 재검토 답: D-FLOW-005 ⑧ PAN 패턴 거부 적용 — 코드 미적용 `UnmaskService.kt:32-33`),
**D-ADMIN1-020**(Q-S8-C — 리뷰 M-1), **D-ADMIN1-021**(Q-S8-D).

| 항목 | 반영 |
|---|---|
| M-1 = D-ADMIN1-020 | 승인 대상 스냅샷에 login_name을 **저장하지 않는다**(`ApprovalService.snapshotJson` — 읽을 때 지우는 대신 원천에서 제거: 승인 조회는 VIEWER·OPERATOR·APPROVER도 하고, 화면은 스냅샷을 쓰지 않으며, SA는 계정 목록에서 login_name을 본다). 기존 행은 개발 DB뿐(배포 전). HTTP 시험: VIEWER·OPERATOR·APPROVER·SA 목록·상세에 login_name·`"loginName"` 없음, 저장 스냅샷에도 없음. 변이 M9-1 |
| D-ADMIN1-021 | `spring.transaction.rollback-on-commit-failure: true`(internal-admin `application.yml`만 — payment-api·master-api 무변경). 시험 대역 관리자도 `TransactionManagerCustomizers`로 같은 설정을 받는다. 실제 주입: DB에 닿기 전 `TransactionSystemException` → `COMMIT_UNKNOWN` → 세 번째 연결이 `innodb_lock_wait_timeout = 1`로 승인 행·승인자·대상 계정 행을 바로 잠근다 → PENDING·TOTP 단계 미사용 → 재승인 1회 실행. 변이 M9-2(설정 false) |
| m-2 D-119 | 강제 1205·1213(TOTP 소비 뒤 코드 행 대기) 시험에 지표 단언: `approval{APPROVE,PASSWORD_RESET,EXECUTED}` +0, `approval.retry_later{APPROVE}` +1. 변이 M9-3(실행 단계 직전 TX 안에서 EXECUTED 기록) |
| m-2 management | 변이 M9-4(management 체인 `anyRequest().denyAll()` 제거 — `/actuator/env`·POST 행이 잡는지), 기본 주소 단언 `management.server.address = 127.0.0.1`(SpaServingSpec) + 변이 M9-5 |
| Kotest 함정 | `KotestEqualityProbeSpec`(internal-admin 시험 — 실측 고정): Pair 안 data object 기대값(vacuous로 예측), 직접·List 비교, 같은 속성 이름의 sealed 하위형(`Refused(code)` vs `Rejected(code)` — 직접·Pair), `shouldBeIn` Pair(ApprovalRaceSpec:172 꼴). **실측 결과는 모듈 시험 실행 뒤 기록(§26.2 — 이번 라운드 실행이 환경 사유로 중단됨)**. vacuous로 나오면 그 꼴의 internal-admin 사용처를 equals 기반으로 바꾼다. 저장소 전체 감사는 TEST-ASSERT-001 |
| nit-1 | `errorCodes.ts` `genericFor`: 401은 본문 code와 무관하게 `UNAUTHENTICATED`(세션 종료) — 알려진 401 code(INVALID_CREDENTIALS·SESSION_*)는 그대로. Vitest 행·변이 M9-FE1 |
| nit-2 | `client.test.ts`에 403·401 본문 없음 행 |
| nit-3 | `EnrollmentController.start`의 세션 없음 분기에 "CSRF가 먼저 403 — 도달 불가 방어 분기" 주석 |
| nit-4 | HSTS `includeSubDomains`를 LIVE Gate 항목으로 기록(§26.4) |
| nit-5 | 계정 목록 상한 500을 넘으면 `truncated: true`(한 행 더 읽어 판정 — `AccountListPage`), 화면은 "앞의 500개만" 상태 문구. 단위 시험 `AccountListTruncationSpec`(Port 대역)·Vitest·변이 M9-6·M9-7·M9-FE2 |
| nit-6 | `BootstrapCommandSpec` — `all{}` 앞에 감사 행 수(5) 단언 |
| F-m1-index | `JpaRecoveryEpisodeStore.findOpen`에 결과 수준 동치 변이와 그래도 클러스터 행 읽기를 유지하는 이유 주석 |
| D-ADMIN1-018·019 | 코드 변경 없음(현재 동작이 결정과 같다). 앞단 속도 제한·PAN 패턴 거부는 §26.4·§26.3 |

### 25.12 S8 리뷰 반영 검증(2026-10-26 KST)

- 컴파일(main·test) 성공. **internal-admin 모듈 시험 + Vitest 실행은 끝나지 않았다(환경 사유 — 업무 RED/PASS 아님)**: 백그라운드 실행을 Claude Code가 호스트 메모리 부족으로 중단했다(이번 세션 두 번째). 남은 JVM은 정리했고, 지시에 따라 스스로 재실행하지 않았다.
- 준비된 명령(Coordinator 지시 후, Gradle 하나씩): `bash /tmp/s5_run.sh s9-module 'com.paycore.admin.*'` → `bash /tmp/s7_fe.sh s9` → 변이 `python3 /tmp/s9_mut.py`(M9-1~7·M9-FE1~2, 결과 `~/gate-logs/s9-mutations.json`).
- 따라서 이 절의 RED/GREEN·변이 결과와 Kotest 실측은 **미측정**이다.

## 26. 최종 게이트 준비(2026-10-26 KST — 구현자 정리, Verifier·Reviewer 확인 대상)

### 26.1 커밋 대상 변경(작업 범위 — harness 상태 파일·CLAUDE.md·DECISIONS.md·STUDY.md 제외)

| 묶음 | 경로 |
|---|---|
| 앱 코드 | `apps/internal-admin/src/main/kotlin/com/paycore/admin/{account,approval,audit,console}/**`, `…/security/**`(새 파일 + `SecurityConfig.kt` 수정), `InternalAdminApplication.kt`, `src/main/java/**/package-info.java`(NamedInterface) |
| 영속·설정 | `src/main/resources/db/migration/admin/V1__admin_core.sql`·`V3__admin_session.sql`, `db/runtime-privilege/admin/V2·V4`, `src/main/resources/application.yml`, `src/jooqCodegen/**` |
| 화면 | `apps/internal-admin/frontend/**`(package.json·package-lock.json·lockfile-policy.json·.npmrc·index.html·tsconfig·vite/vitest 설정·`src/**`) |
| 시험 | `apps/internal-admin/src/test/kotlin/com/paycore/admin/**`(TechSmoke 페이지 2개·템플릿 삭제 포함), `src/test/resources/config/application.yml`·`contracts/totp-rfc6238.golden.json` |
| 빌드·배치 | `apps/internal-admin/build.gradle.kts`, `build-logic/build.gradle.kts`·`build-logic/src/main/kotlin/paycore.frontend.gradle.kts`·`com/paycore/buildlogic/frontend/**`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `compose.yaml`, `docker/mysql/init/01-owned-databases.sh`, `.env.example`, `.gitignore` |
| 하네스 스크립트·시험 | `harness/scripts/{cve-scan.py,fingerprint.sh,test-summary.py,verify-gate.py}`, `harness/tests/{test_cve_scan,test_fingerprint,test_module_registry,test_test_summary,test_verify_gate,test_totp_rfc6238_golden}.py`, `harness/fixtures/cve-control-vulnerable-npm/**` |
| 문서 | `docs/repository/ADMIN-BOOT-001-ADMIN-FOUNDATION.md`(새), `ARCHITECTURE-CONVENTIONS.md`, `HARNESS-VERIFICATION-CONTRACT.md`, `TECH-BASELINE.md` |

`docs/TECHNOLOGY-AND-PRINCIPLES.md`·harness 상태 파일·`CLAUDE.md`·`docs/DECISIONS.md`의 변경은 Coordinator 몫이다(구현자는 손대지 않았다).

### 26.2 구현 결정(가역 — S8 이후 추가분)

D-119(지표 = TX 뒤 1회), D-120(login·approval label), D-121(oneshot 관측 한계), D-122(management 포트 없음 = -1·loopback), D-123(HSTS 전 응답), D-124(원인 후보 = 실행과 같은 domain 판정), D-125(사유 원문 유지 → D-ADMIN1-019로 확정),
D-126(D-ADMIN1-020 구현 방식: 저장하지 않음 — 읽을 때 제거 대신), D-127(계정 목록 잘림 = 상한+1 읽기, 응답 `truncated`), D-128(401은 알려진 401 code 외 모두 세션 종료 — 화면).

### 26.3 이월(이 작업 밖)

TEST-ASSERT-001(Kotest 동등 비교 함정 저장소 전체 감사 — 입력: §24.8·`KotestEqualityProbeSpec`), gauge 지표(`approval.pending`·`oldest_age`·`session.rows`·`anonymous_created`)·`session.invalidated`·`audit.write_failed`,
마스킹 해제 실제 대상 공급자 + PAN 패턴 거부 재검토(D-ADMIN1-019), 원인 후보 화면의 원인 상세, ADMIN-PAYMENT-OPS-001·WEBHOOK-ENDPOINT-001(§4.12 이관), payment-api·master-api의 `rollbackOnCommitFailure` 판단(D-ADMIN1-021은 internal-admin만).

### 26.4 LIVE Gate 누적 항목

- (현행화 2026-10-30 — [ADMIN-BOOT-002 §11·§12](ADMIN-BOOT-002-BOOTSTRAP-SECOND-SA.md), D-ADMIN1-023) 운영 하한 활성 SECURITY_ADMIN ≥ 2·APPROVER ≥ 2와 `paycore.admin.accounts.active{role}` < 2 경보가 아래 표에 더해진다(bootstrap 직후 APPROVER 1명 — 운영 개시 전 보충).

| 항목 | 내용 |
|---|---|
| 보안 수치 | Demo 값(로그인 실패 한도 5·TOTP 창 ±1·등록 코드 24h·단계 10m·비밀번호 12자/72바이트·BCrypt 12·세션 idle/절대·승인 유효 24h·회차 24h)은 운영값이 아니다 — Q-ADMIN-SEC-VALUES 답변 후 확정(`LiveModeStartupGuard`가 Demo 값·Demo 키로 LIVE 기동을 거부) |
| 배치 모드 | `PAYCORE_ADMIN_DEPLOYMENT_MODE=DEMO`만 동작 가능한 구조적 한계(LIVE는 위 수치·키·BCrypt 하한·전 주소 허용 목록 거부가 모두 풀려야 기동) |
| HSTS | `includeSubDomains`가 관리자 호스트의 하위 도메인 전부에 HTTPS를 강제한다 — 운영 도메인 구조 확인 후 유지/제거(`preload`는 쓰지 않음) |
| TOTP QR | QR 라이브러리 검토(14일 규칙·CVE·lockfile·라이선스) 또는 자체 인코더 — 현재는 설정 키 수동 입력(Q-S7-1) |
| 앞단 속도 제한 | D-ADMIN1-018: 감사 1행/거부 유지, 범람은 앞단(WAF·프록시) 속도 제한 — 배치 항목 |
| management 포트 | `PAYCORE_ADMIN_MANAGEMENT_PORT`(빈 값 금지)·주소(기본 loopback — 수집기 위치에 맞게 명시), 웹 포트와 다른 네트워크 노출 정책 |
| 로그 수준 | `org.springframework.web`·`org.springframework.security` DEBUG 금지(요청 단편) |
| 키 | TOTP 암호화 키·login_name HMAC 키는 비밀 저장소 주입(저장소·이미지에 없음), 키 회전 절차(키 버전 표) |

## 27. 개정 이력 — 문서 정합

| 일자 | 내용 |
|---|---|
| 2026-11-25 | S3-DOC-001 갈래 A(문서 정합, NP-97·PC-54·PC-58 일부·ADM (a)-1·(c)-6·(c)-7): 머리 정합 표시 추가, §1.2 마스킹 해제 첫 적용·§4.5.1 권한 표·§4.5.3·§24 S7 구현 기록 줄(마스킹 해제 화면)에 D-FLOW-002 ①·D-FLOW-005 ⑧ 표시, §4.7.4에 D-ADMIN-AUTH-DEMO-001 ③ 예외 추가 표시, §4.9.1 본문·`BootstrapConcurrencySpec` 줄·§12.1 D-ADMIN1-002 행에 D-ADMIN1-022 표시, §4.9.2 작업 B에 D-ADMIN1-023 답·남은 확인 후보 표시(ADM (f)-6), D-ADMIN1-019 기록 줄에 재검토 답 표시. 원문 무변경, 정책·시험 이름·코드 변경 없음 |
