# ADMIN-BOOT-002 — bootstrap SECURITY_ADMIN 2명 + APPROVER 1명(역할 부여 교착 해소)

- 상태: 구현 명세 rev 0.4(2026-10-30, 구현자 — rev 0.1 리뷰 REVIEW_PASS 뒤 마무리: rev 0.2 D-ADMIN1-023 지표·Q-2 결정·Minor-1·2, rev 0.3 명령 모드 주기 job 전면 차단 I-11, rev 0.4 최종 리뷰 CHANGES_REQUIRED 반영 I-12~I-14). DONE 판정은 Verifier·Reviewer·Coordinator.
- 근거 결정: **D-ADMIN1-022**(Q-ADMIN-BOOT-ROLE, 2026-10-30), **D-ADMIN1-023**(Q-ADMIN-SA-FLOOR, 2026-10-30 — 운영 하한 + 경보). 유지 결정: D-ADMIN1-014·015(자격 발급 근거 배제), D-ADMIN1-010~012(오프라인 복구).
- 기준: HEAD `17289a5`(feat/foundation-setup). 선행 명세 [ADMIN-BOOT-001](ADMIN-BOOT-001-ADMIN-FOUNDATION.md) §4.9·§4.9.1·§4.9.2, 관례 [ARCHITECTURE-CONVENTIONS §14](ARCHITECTURE-CONVENTIONS.md).

## 1. 문제

bootstrap은 SECURITY_ADMIN(SA) 1명 + APPROVER 1명만 만든다. 새 계정은 SA만 1인 생성할 수 있고(§4.5.1), 생성한 SA는 D-ADMIN1-014/015에 따라 그 계정의 권한 상승(`ROLE_GRANT`·`ROLE_REACTIVATE`) 요청자·승인자에서 배제된다. `ROLE_GRANT` 요청자는 SA여야 하므로(`ApprovalKind.ROLE_GRANT` 요청 역할 = SECURITY_ADMIN) bootstrap 직후에는 **어떤 새 계정에도 역할을 줄 수 없다**(2026-10-29 Demo: 409 `CREDENTIAL_ISSUER`). D-ADMIN1-015는 유지하고, 시스템 근거(배제 대상 없음)를 가진 SA를 하나 더 bootstrap에서 만든다.

## 2. 현황 조사(`17289a5`)

| 항목 | 현재 |
|---|---|
| 명령 인자 | `--paycore.admin.command=bootstrap --output=tty\|file:<path> --security-admin-login --security-admin-name --approver-login --approver-name`(`OneShotCommandLine.parse` — 하나라도 없거나 공백이면 `USAGE` exit 2, DB 접근 없음) |
| 입력 검사 | `OneShotCommandService.parsePair` — LoginName·DisplayName 형식, 두 login 같으면 `Invalid(INVALID_INPUT)` → `USAGE`. 표시명 중복은 검사하지 않음 |
| 1회성 | 같은 TX: `admin_account` 비어 있음(비잠금 보조) → `admin_bootstrap(id=1)` INSERT(PK·`CHECK id=1` — 패자 1062 → `LOST`, 별도 TX 감사) → 계정·GRANT 이벤트(actor `SYSTEM_BOOTSTRAP`)·ENROLL 코드·감사 → 커밋 → 출력 |
| 고정 행 열 | `security_admin_actor_id`·`approver_actor_id` NOT NULL(V1). runtime 권한 SELECT·INSERT(append-only) |
| 계정 근거 | `CredentialBasis.system(issueId)` — issuers 없음(배제 대상 없음, n-H 신뢰 경계) |
| 출력 | `CodeOutput`: TTY(`System.console()`+terminal) 또는 `CREATE_NEW` 0600 파일(디렉터리 그룹/기타 쓰기 거부, symlink 교체 방어), 줄 형식 `<login>\t<purpose>\t<code>`. logger·표준 출력 일반 쓰기 없음. `IssuedCodeLine`/`Done.toString`은 원문 숨김 |
| 감사 | actor_kind `SYSTEM_BOOTSTRAP`: 계정마다 `ACCOUNT_CREATED(role=…)`·`ENROLLMENT_CODE_ISSUED(purpose=ENROLL)` + `BOOTSTRAP:SUCCESS(accounts=2)`. 거부·패배는 별도 TX `BOOTSTRAP:REFUSED/LOST(reason=…)` |
| 복구 조건 | `recoverStep`: 활성 SA > 0 **그리고** 활성 APPROVER > 0이면 `NOT_NEEDED` 거부(D-ADMIN1-010) |

## 3. 설계

1. **인자**: 기존 네 인자 이름은 그대로 두고 `--second-security-admin-login`·`--second-security-admin-name`을 추가한다. 여섯 인자 모두 **필수**다. 기존 네 인자만 주면 `USAGE`(exit 2, DB 무변경) — 1명 SA bootstrap을 허용하면 교착을 다시 만든다(fail-closed). 따라서 기존 호출과 **호환되지 않는다**(의도).
2. **Use Case 입력**: `BootstrapCommand(securityAdmin, secondSecurityAdmin, approver, correlationId)`.
3. **중복 거부**: 세 login이 모두 달라야 하고, 세 표시명도 모두 달라야 한다(같으면 `Invalid(INVALID_INPUT)` → `USAGE`, TX 없음). 표시명 검사는 이번에 추가 — 감사·승인 화면은 표시명 스냅샷을 보이므로 SA 두 명을 구별할 수 있어야 한다(가역 결정 I-3). 같은 사람 금지는 계속 운영 절차.
4. **1회성 유지**: 같은 TX에서 고정 행 INSERT 뒤 SA-A → SA-B → APPROVER 순으로 계정·GRANT 이벤트·ENROLL 코드·감사를 만든다. 패자·거부·재실행 규칙은 바뀌지 않는다(`ACCOUNTS_EXIST`·`LOST`).
5. **고정 행**: migration 없이 기존 열 그대로 — `security_admin_actor_id` = SA-A, `approver_actor_id` = APPROVER. SA-B는 같은 correlation의 감사 `ACCOUNT_CREATED(role=SECURITY_ADMIN)`와 역할 이벤트(GRANT, actor `SYSTEM_BOOTSTRAP`)로 추적한다. 고정 행은 1회성 권위(PK)가 본 역할이고 actor 열은 참고 기록이다(가역 결정 I-4, 대안 = V6 nullable 열 추가는 Coordinator 판단으로 남김 — §8).
6. **출력**: 세 줄(SA-A·SA-B·APPROVER 순), 같은 TTY/0600 파일 규칙. 감사 `BOOTSTRAP:SUCCESS` detail `accounts=3`. 원문은 감사·로그·toString에 없다.
7. **배제 규칙 불변**: SA-B도 시스템 근거라 SA-A가 만든 계정에 대해 배제되지 않는다. SA-A 요청은 계속 `CREDENTIAL_ISSUER`(D-ADMIN1-015). 배제 판정 코드(`ApprovalParticipants`)는 수정하지 않는다.

## 4. 복구 명령(D-ADMIN1-010)과의 정합

- 조건은 **바꾸지 않는다**: 활성 SA 0명 또는 활성 APPROVER 0명일 때만 동작. 2명 중 1명만 남으면(예: SA-A 정지) 복구는 `NOT_NEEDED`로 거부된다 — 이번 작업은 이 동작을 시험으로 고정해 기록한다(D-ADMIN1-010 문언 그대로).
- 복구 작업 B(새 SA·APPROVER 쌍)는 이번 결정 대상이 아니다(D-ADMIN1-022는 bootstrap만). 복구 B 직후에도 새 SA는 1명이므로, 그 SA가 만든 계정에는 같은 교착이 생길 수 있다 → 질문 후보 §8 Q-1.
- 남은 SA 1명(예: SA-B)만으로 할 수 있는 일(코드 기준 분석):
  - 남은 SA가 **직접 만든** 계정에는 역할 부여 불가(D-ADMIN1-015).
  - 다른 근거로 만들어진 계정(예: 잃은 SA가 만든 계정, 시스템 근거 계정)에는 남은 SA 요청 + APPROVER 승인(요청자≠승인자≠대상)으로 `ROLE_GRANT SECURITY_ADMIN` 가능 → SA 2명 회복 경로가 **조건부로** 있다. 단 APPROVER가 1명뿐이면 그 APPROVER 자신을 SA로 올릴 수는 없다(대상≠승인자).
  - 잃은 SA의 `PASSWORD_RESET`(남은 SA 요청 + APPROVER 승인) 뒤 `ROLE_REACTIVATE`는 원인 재설정의 요청자·승인자가 배제되므로(H-A) 제3의 SA가 없으면 불가.
  - 따라서 "SA 1명 남음"은 복구 명령도 일반 경로도 보장하지 않는 상태가 될 수 있다. → **D-ADMIN1-023으로 결정**(§8 Q-1): 복구 조건은 그대로 두고, 그 상태에 들어가기 전에 운영 하한(활성 SA ≥ 2·APPROVER ≥ 2) 미만을 지표·경보로 드러낸다(§11).

## 5. 문서 정정

- ADMIN-PAYMENT-OPS-001 부록 D 4항: bootstrap 인자 6개·코드 3개, OPERATOR 부여는 "계정을 만든 SA가 아닌 **다른** SA가 ROLE_GRANT 요청 → APPROVER 승인"으로 정정.
- ADMIN-BOOT-001 §4.9.1: 현행화 주석 한 줄(ADMIN-BOOT-002로 SA 2명 + APPROVER 1명).

## 6. 시험 계획(실제 MySQL 8.4 Testcontainers, 전용 컨텍스트 `one-shot-bootstrap`)

| 시험 | 내용 |
|---|---|
| `BootstrapSecondSecurityAdminSpec` ① | 명령 줄(file 0600) bootstrap → 계정 3·고정 행 1·코드 3, 역할 {SA}·{SA}·{APPROVER}, 세 계정 issuers 없음, 감사 7행 모두 `SYSTEM_BOOTSTRAP`·원문 없음·`accounts=3`, 고정 행 열 = SA-A·APPROVER |
| ① 출력 형식(rev 0.2 Minor-2) | 모든 bootstrap 호출에서 파일의 비주석 줄 = 정확히 3개, 순서 SA-A → SA-B → APPROVER, 목적 `ENROLL` |
| ② 역할 부여 교착 해소 | 세 계정 등록(실제 TOTP) → SA-A가 계정 X 생성·X 등록 → **실제 HTTP**(2단계 로그인 세션·CSRF, MockMvc) SA-A `POST /api/approvals` ROLE_GRANT OPERATOR = **409 `{"error":"CREDENTIAL_ISSUER"}`**(rev 0.2 Minor-1), Use Case도 `Rejected(CREDENTIAL_ISSUER)` → SA-B HTTP 요청 201 → APPROVER TOTP 승인 `Executed` → X 활성 역할 {OPERATOR} → 부여 뒤에도 SA-A HTTP VIEWER 요청 409. SA-A의 승인 단계 배제는 기존 `CredentialIssuerExclusionSpec`이 보장 |
| ③ 재실행 거부 | 성공 뒤 두 번째 bootstrap → `CONDITION_REFUSED`(ACCOUNTS_EXIST), 계정 3 유지, 거부 감사 1행 |
| ④ 인자 누락·중복 | 두 번째 SA 인자 누락(구 4인자 형식)·login 중복(SA-B=SA-A, SA-B=APPROVER)·표시명 중복 → `USAGE`, DB 무변경, 파일 미생성 |
| ⑤ 복구 정합 | 셋 등록 후 SA-B가 SA-A 정지 → `recover(...)` = `Refused(NOT_NEEDED)`(D-ADMIN1-010 유지) |
| 기존 `BootstrapCommandSpec` | 계정·코드 수 2→3, 감사 5→7, 출력 login 집합 3개, 경합 시험 `Done.codes` 3 — D-ADMIN1-022로 기대 결과 자체가 바뀐 것(완화 아님 — §7) |
| `OneShotMode*Spec` 3개 | args에 두 번째 SA 인자 추가(가드 시험 목적 유지 — 없으면 Accepted 시험이 출력 거부 전에 USAGE로 끝나 가드 경로를 시험하지 못함). rev 0.2: `OneShotModeAcceptedSpec`은 gauge job을 켠 채로 명령 모드에서 그 job 빈이 없음을 추가 단언 |
| ⑥ 활성 역할 gauge(rev 0.2, D-ADMIN1-023) | bootstrap 직후(등록 전) refresh = SA 0·APPROVER 0 → 세 계정 등록 후 SA 2·APPROVER 1 → SA-B가 SA-A 정지(갱신 전 gauge는 직전 값) → refresh SA 1 → SA-B 요청 + APPROVER TOTP 승인 `ACCOUNT_REACTIVATE` → refresh SA 2. Use Case 반환값과 `MeterRegistry`의 `paycore.admin.accounts.active{role}` 값 모두 단언 |
| `ActiveRoleHolderGaugeSchedulerSpec`(rev 0.2) | job 켬(비웹 컨텍스트) → 스케줄러 빈 존재, 기동 직후 첫 실행이 두 역할 gauge를 등록 |
| `OneShotModeWebSpec`(rev 0.4 재작성, I-12) | 실제 서블릿 웹 구성(`SpringApplicationBuilder`, DB 없이 — 실패가 빈 정의 단계라 닿지 않음)에 명령 속성을 환경변수 `PAYCORE_ADMIN_COMMAND`·설정 속성·인자 세 경로로 → 각각 기동 실패, 원인 체인에 고정 code `ONE_SHOT_COMMAND_IN_WEB_CONTEXT`(다른 이유의 실패와 구별). **기존 기대 변경**: MOCK 웹 + 인자 → runner `MODE_REFUSED`(컨텍스트는 뜸)에서 → 기동 실패로. 완화가 아니라 강화 — 웹 프로세스가 명령 속성을 가진 채 서비스하는 경로 자체가 없어진다 |
| `PeriodicJobsWebContextSpec`(rev 0.4) | 명령 속성 없는 MOCK 웹 + 네 job 켬 → 네 job 빈 모두 존재, 가드 빈 없음, 예약 작업 > 3 |
| `ActiveRoleHolderGaugeServiceSpec`(rev 0.4, DB 없는 Application 시험) | 갱신 TX 읽기 전용 표시(I-13) · 성공 시 역할 gauge·SUCCESS counter·경과 0 → 시계 +90초 경과 90 · 실패 2회 → 역할 gauge 직전 값 유지·FAILURE 2·경과는 마지막 성공부터 계속 증가(200) → 다시 성공하면 새 값·경과 0 (I-14) |
| `OneShotModePeriodicJobsSpec`(rev 0.3, I-11) | 만료·회복·대사·gauge job을 **모두 켠** 설정 + 명령 모드 → 네 job 빈 모두 없음, 모든 `ScheduledTaskHolder`의 예약 작업 0, runner는 모드 거부(만료 job 켬) |

핵심 변이(하나씩 적용 → RED 확인 → 원복): M1 두 번째 SA 미생성(계정 생성 호출 제거), M2 SA-B를 SECURITY_ADMIN 대신 APPROVER로 생성, M3 배제 규칙 우회(`ApprovalParticipants` 권한 상승 배제 끔), M4 login 3중 중복 검사 제거(SA-B=APPROVER 허용), M5 두 번째 SA 인자 선택화(구 4인자 허용).
rev 0.3 변이: P1 `OneShotModePeriodicJobGuard`의 빈 제거 중단(가드 무력화).
rev 0.4 변이: W1 가드의 웹 판정 제거(웹이어도 job만 지우고 진행), R1 갱신 TX 읽기 전용 해제, R2 실패 counter 호출 제거.
rev 0.2 변이: N1 gauge 대상에서 APPROVER 제외, N2 gauge 기록 호출 제거, N3 스케줄러 등록 조건에서 명령 모드 제외 조건 삭제, N4 활성 판정에서 `status = 'ACTIVE'` 조건 삭제(정지 계정도 셈), N5 생성 순서 SA-B ↔ APPROVER 교환(Minor-2 순서 단언), N6 배제 규칙 우회(Minor-1 HTTP 409 단언).

## 7. 기존 시험 기대값 변경 근거

`BootstrapCommandSpec`의 수량 기대(2계정·2코드·감사 5·login 집합)는 D-ADMIN1-022가 bootstrap 결과 자체를 "SA 2 + APPROVER 1"로 정했기 때문에 3계정·3코드·감사 7로 바뀐다. 1회성(고정 행 1)·0600·비노출·거부 exit·경합 패자 `LOST` 1·감사 actor 등 **검사 항목은 하나도 빠지지 않고** 오히려 SA-B 역할·근거 검사가 늘어난다. 구 4인자 형식은 이제 `USAGE`로 거부되는 것이 기대값이다.

## 8. 질문과 결정

- **Q-1(SA 하한) → 결정 D-ADMIN1-023**(Q-ADMIN-SA-FLOOR, 사용자 선택 "운영 하한+경보", 2026-10-30): 복구 조건(D-ADMIN1-010)은 유지한다. LIVE Gate 운영 하한은 활성 SECURITY_ADMIN ≥ 2, 활성 APPROVER ≥ 2이며, 하한 미만은 지표·경보로 드러낸다. 코드 변경은 활성 역할 수 지표뿐이다(§11). 검토했던 대안: B 복구 작업 B도 SA 2명 생성, C 복구 조건을 "활성 SA ≤ 1"로 확장 — 둘 다 채택하지 않음.
- **Q-2(고정 행 기록) → Coordinator 결정 "열 추가 없음"**(2026-10-30, 보안 의미 없음): `admin_bootstrap`은 V1 열 그대로(SA-A·APPROVER), migration 없음. SA-B는 같은 correlation의 감사 `ACCOUNT_CREATED(role=SECURITY_ADMIN)`와 역할 이벤트(GRANT, actor `SYSTEM_BOOTSTRAP`)로 추적한다(I-4 확정).

## 9. 가역적 구현 결정

| ID | 결정 |
|---|---|
| I-1 | 인자 이름 `--second-security-admin-login/-name`, 필수. 구 4인자 형식은 USAGE |
| I-2 | `BootstrapCommand`에 `secondSecurityAdmin` 필드(provided 계약 변경 — 호출자는 batch adapter와 시험뿐) |
| I-3 | 세 login·세 표시명 모두 서로 달라야 함(INVALID_INPUT) |
| I-4 | 고정 행 열 불변(SA-A·APPROVER), SA-B는 감사·역할 이벤트로 추적 — **Q-2 Coordinator 결정으로 확정**(열 추가 없음, migration 없음) |
| I-5 | 생성·출력 순서 SA-A → SA-B → APPROVER, 감사 `accounts=3` |
| I-6 | 복구 명령 코드·조건 불변 |
| I-7 | (rev 0.2) 지표 이름 `paycore.admin.accounts.active`, tag `role`=`SECURITY_ADMIN`·`APPROVER`(고정 분류값 — 기존 `paycore.admin.*` 명명·`MicrometerAdminAccountMetrics` adapter 관례, gauge 등록 방식은 `MicrometerOwnerOpsMetrics`의 `ConcurrentHashMap<String, AtomicLong>` 관례) |
| I-8 | (rev 0.2) 갱신 방식 = 주기 job(`account.adapter.batch.ActiveRoleHolderGaugeScheduler`, Demo 1분, 기동 직후 1회) → provided `RefreshActiveRoleHoldersUseCase` → `ActiveRoleHolderGaugeService`(읽기 전용 로컬 TX, 기존 `AdminAccountRepository.countActiveHolders` 재사용 — 복구 조건과 같은 "활성" 정의). 명령마다 갱신하지 않는다(호출 지점이 많고 지표는 근사로 충분 — 지연 ≤ 주기) |
| I-9 | (rev 0.2) gauge는 첫 갱신 때 등록(갱신 전 0으로 오경보하지 않음). 갱신 실패 시 직전 값 유지 + 경고 한 줄(`operation=active-role-gauge result=error`) |
| I-10 | (rev 0.2) job 등록 조건 = `paycore.admin.accounts.active-role-gauge.enabled=true`. 시험 설정은 끔(값 단언에 끼어들지 않게). rev 0.3에서 명령 모드 조건은 I-11로 옮김 |
| I-11 | (rev 0.3 — Coordinator 지시, 리뷰 관찰 7) **1회성 명령 프로세스에는 주기 job이 하나도 없다**. 판정은 명령 모드 여부 하나(`paycore.admin.command` 있음 — `OneShotCommandRunner`와 같은 조건): `account.adapter.batch.OneShotModePeriodicJobGuard`(BeanDefinitionRegistryPostProcessor, 명령 모드에서만 등록)가 빈 정의 단계에서 애플리케이션 패키지(`com.paycore.admin.`)의 `@Scheduled`·`@Schedules` 메서드를 가진 빈을 모두 지운다 — 만료·회복·대사·gauge와 이후 추가될 job이 각자의 켬 설정과 무관하게 빠진다. 조사한 관례: 기존 job은 각자 `@ConditionalOnProperty(<job>.enabled)`이고 진입점(`effectiveArguments`)은 만료 job만 끄는 인자를 덧붙였다(그래서 회복·대사 job이 명령 프로세스에 등록될 수 있었다). 대안 ① 진입점에 job별 끔 인자 나열 — job 추가마다 누락 위험·`@SpringBootTest` 경로에서 검증 불가라 기각, ② job마다 명령 모드 조건 추가 — approval 모듈 파일 변경(허용 경로 밖)·같은 판정의 중복이라 기각. 위치 이유: 기본 패키지 루트에는 진입점만 둘 수 있고(ArchitectureSpec), 명령 모드의 소유자는 `account.adapter.batch`다(다른 모듈 타입을 import하지 않는다 — 어노테이션으로만 찾는다). 진입점의 만료 job 끔 인자와 runner의 모드 가드(스케줄러 켬이면 거부)는 그대로 둔다(중복 방어). gauge job의 자체 명령 모드 조건은 제거하고 다른 job과 같은 `@ConditionalOnProperty` 관례로 맞췄다. **근거 범위**: 이 가드는 D-ADMIN1-023(지표)이 아니라 **ADMIN-BOOT-001 §4.9 "명령 모드는 비웹·스케줄러 없이"의 보완**이다(리뷰 관찰 7). **한계**(rev 0.4 Nit — KDoc에도 기록): 판정은 빈 정의의 `beanClassName`이라 ① `@Bean` factory-method 정의 빈, ② 애플리케이션 패키지(`com.paycore.admin.`) 밖 클래스, ③ `@Scheduled` 없이 `SchedulingConfigurer`·직접 `TaskScheduler`로 예약하는 코드는 대상이 아니다. 현재 job은 모두 패키지 안 `@Component` + `@Scheduled`이고, 명령 모드 예약 작업 0은 `OneShotModePeriodicJobsSpec`이 `ScheduledTaskHolder`로 확인한다. 새 job을 다른 방식으로 만들면 이 한계를 다시 본다 |
| I-12 | (rev 0.4 — 최종 리뷰 Major-1, Coordinator 결정 **fail-closed**) 명령 속성 `paycore.admin.command`가 **웹 애플리케이션 컨텍스트**에 있으면(인자·설정 파일·환경변수 `PAYCORE_ADMIN_COMMAND` 어느 경로든) 가드가 빈 정의 단계에서 `IllegalStateException(ONE_SHOT_COMMAND_IN_WEB_CONTEXT …)`으로 **기동을 실패**시킨다(웹 서버·DB 연결 전, 메시지에 값 없음). job 제거는 비웹 명령 컨텍스트에서만. 이전 동작(웹 서버는 뜨고 runner만 `MODE_REFUSED` 경고 한 줄 — 그리고 rev 0.3 가드라면 서비스 중 job이 조용히 지워짐)은 제거됐다. runner의 `web-context` 분기는 중복 방어로 남는다. 판정 근거: `ApplicationContextAware`로 받은 컨텍스트가 `WebApplicationContext`인지(이 앱은 서블릿 전용), 받지 못하면 웹으로 본다(fail-closed) |
| I-13 | (rev 0.4 — 리뷰 Minor-1) gauge 갱신 TX는 **읽기 전용 정의**(`LocalTransactions(readOnly = true)` — account 모듈 실행기에 기본값 false인 선택 인자 추가, 기존 호출 불변). JPA flush 없음·JDBC 연결 읽기 전용 힌트. 시험은 TX 동기화의 읽기 전용 표시로 확인 |
| I-14 | (rev 0.4 — 리뷰 Minor-2) 갱신 결과 counter `paycore.admin.accounts.active.refresh{result=SUCCESS\|FAILURE}`와 마지막 성공 이후 경과 gauge `paycore.admin.accounts.active.refresh.age_seconds`(첫 성공 때 등록, **수집 시점**에 `AdminClock`으로 계산 — job이 멈춰도 값이 늘어난다). 실패 시 역할 gauge는 직전 값 유지(실패 counter만 증가, 예외는 job이 경고 한 줄로 기록) |

## 10. 8대 원칙(이번 변경 범위)

- Transaction Safety·Recoverability: 세 계정·고정 행·코드·감사가 한 로컬 TX — 실패 시 전부 rollback(기존 경합 시험이 3계정 기준으로 유지).
- Idempotency·Concurrency Control: 고정 행 PK 1회성 그대로(재실행 `ACCOUNTS_EXIST`, 경합 `LOST`).
- Auditability: 계정마다 `ACCOUNT_CREATED`·`ENROLLMENT_CODE_ISSUED`, `BOOTSTRAP accounts=3`. 기존 지표 라벨 불변.
- Observability(rev 0.2): `paycore.admin.accounts.active{role}` gauge — 운영 하한(D-ADMIN1-023) 미만을 경보로 드러낸다. 값 변화는 실제 MySQL에서 정지·재활성으로 단언(§6 ⑥).
- Financial Consistency·Reconciliation: 금융 데이터 무관(ADMIN DB 계정만). 해당 없음을 명시.
- JPA/jOOQ: 스키마·Entity 변경 없음. gauge 집계는 기존 `JpaAdminAccountRepository.countActiveHolders`(native `COUNT(*)` — `active_roles`가 MySQL SET이라 JPQL로 `FIND_IN_SET`을 쓰지 않는 기존 선택)를 그대로 쓴다. 예: SA-A 정지 → `status='ACTIVE'` 조건에서 빠져 1. 이유: 복구 조건과 같은 정의를 한 곳에서 유지. 주의: 이 집계는 잠금 없는 읽기라 지표용 근사이며 권위 판정(복구·자동 재활성)은 각 명령 TX가 회차 잠금 아래에서 다시 센다. jOOQ CQRS 조회는 이번 범위 밖(목록·검색 화면이 아니라 단일 집계). BOM·의존성 변경 없음. Kafka/Redis 무관.

## 11. 활성 역할 운영 하한 지표(rev 0.2 — D-ADMIN1-023)

| 항목 | 내용 |
|---|---|
| 지표 | `paycore.admin.accounts.active{role=SECURITY_ADMIN\|APPROVER}` gauge = 계정 `ACTIVE` + 그 역할 현재 보유 수 |
| 갱신 | 주기 job(Demo 1분, 설정 `paycore.admin.accounts.active-role-gauge.fixed-delay`), 기동 직후 1회. 값의 지연 ≤ 주기 |
| 경보 기준 | 어느 역할이든 **2 미만**(운영 하한). 계열 없음(갱신 job 미동작·관리 포트 미수집)도 경보 대상. **갱신 정체**(rev 0.4 — I-14): `paycore.admin.accounts.active.refresh.age_seconds` > 갱신 주기 × 3(Demo 180초) 또는 계열 없음, `paycore.admin.accounts.active.refresh{result=FAILURE}` 증가 — 실패가 이어지면 역할 gauge는 직전 값으로 남아 하한 경보를 놓칠 수 있기 때문. 경보 시스템·수집 경로는 운영 설정(이 저장소에 없음) |
| 갱신 TX | 읽기 전용 로컬 TX(I-13), 잠금 없음 |
| 알려진 상태 | bootstrap 직후 APPROVER는 1명(D-ADMIN1-022)이라 **처음부터 하한 미만** — 운영 개시 전 두 번째 APPROVER를 SA-A 생성 + SA-B ROLE_GRANT APPROVER 요청 + 기존 APPROVER 승인으로 추가해야 경보가 풀린다 |
| 1회성 명령 모드 | job 미등록 — 모든 주기 job과 함께 `OneShotModePeriodicJobGuard`가 지운다(I-11) |

## 12. LIVE Gate 항목(rev 0.2)

- 활성 SECURITY_ADMIN ≥ 2, 활성 APPROVER ≥ 2 운영 하한 유지 절차(D-ADMIN1-023) — 운영 개시 전 두 번째 APPROVER 추가 포함.
- `paycore.admin.accounts.active{role}` < 2 경보 규칙과 계열 부재 경보를 운영 경보 시스템에 등록, 수집 경로(관리 포트 — D-15) 확인.
- 갱신 정체 경보(rev 0.4): `paycore.admin.accounts.active.refresh.age_seconds` > 주기 × 3 또는 부재, `paycore.admin.accounts.active.refresh{result=FAILURE}` 증가.
- 배포 설정에 `PAYCORE_ADMIN_COMMAND`·`paycore.admin.command`를 웹 서비스 환경에 두지 않는다(두면 기동 실패 — I-12 fail-closed).
- 갱신 주기 운영값(Demo 1분).
- 복구 조건(D-ADMIN1-010)은 0명일 때만이므로, 하한 경보를 받으면 0명이 되기 전에 일반 2인 승인 경로로 보충하는 runbook.
