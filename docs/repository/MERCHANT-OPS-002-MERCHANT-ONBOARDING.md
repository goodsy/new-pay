# MERCHANT-OPS-002 가맹점 등록·개통 명령 명세 (rev 0.4)

- **rev 0.1** (2026-11-08, Implementer — 명세 초안. 코드·Gradle 실행 없음). B1(1인 등록 화면)만 상세 설계, B2~B5는 개요와 미결. 자기 완료 판정 아님(Verifier·Reviewer 별도).
- **rev 0.2** (2026-11-08, Implementer — 사용자 결정 **D-MOPS-006**(Q-MOPS-10~15) 반영, Coordinator가 C-1~C-11 권장안 채택. 코드·Gradle 실행 없음). Q-MOPS-12는 사용자가
  **대안**(기존 businessId 비노출)을 골랐다 → §3.3 C-12(MASTER 관리 채널도 거절 reference를 내보내지 않음), Q-MOPS-14 → 새 거절 `CONTRACT_START_RETROACTIVE`(§3.4).
  B1-1·B1-2 수용 기준(§8.1) 추가. Q-MOPS-16·17은 PENDING 유지. §13 기록.
- **rev 0.3** (2026-11-08, Implementer — rev 0.2 독립 검토 FAIL(MAJOR 2·MINOR 6·NIT 3) 반영, 사용자 결정 변경 없음. 코드·Gradle 실행 없음). 403 감사 brn 마스킹 규칙 정정(M-1), 응답 유실
  시험을 MASTER 실제 HTTP·MySQL과 ADMIN stub로 분리(M-2), correlationId 고정(C-14), 버킷 예약·환불(C-13 개정), nonce 불필요 근거(§3.1), 쓰기 경로 전수 시험, 종료일 문구, Revision 소급 회귀 시험. §13 기록.
- **rev 0.4** (2026-11-08, Implementer — rev 0.3 재검토 PASS의 m-7·n-4 반영): C-14 correlationId = `mos.<submissionId>`(MASTER 키 아님 — 멱등키 로그 금지 규칙 유지), 사전 거절은 ADMIN 요청 ID. §13 기록.
- 기준: branch `feat/foundation-setup`, HEAD `1ad6ed8`. 선행: MERCHANT-OPS-001 rev 0.1(DONE — 조회 화면·ADMIN→MASTER 서명 채널), MASTER-DOMAIN-001, MASTER-SNAPSHOT-001/002, DEMO-SEED-001.
- 표기: **사용자 결정** = `docs/DECISIONS.md`. **Q-MOPS-n** = 사용자 질문 후보(미결 — Coordinator 경유, 답 전 해당 운영 동작 구현 금지). **C-n** = Coordinator가 정할 가역 선택(권장안 표시).
  **L-n** = 알려진 위험·한계. rev 0.2부터 Q-MOPS-10~15는 **답변됨**(D-MOPS-006), C-1~C-13은 **채택**(가역).
- 근거: D-MOPS-001~005, D-MD-001~018, D-MS-002·003, D-MS2-001~005, D-ADMIN-001~008, D-ACQA-001(비교용 — 조작 키 분리 선례), MERCHANT-OPS-001 §3~§5,
  ADMIN-PAYMENT-OPS-001 §4.9~§4.20(소유 서비스 2-TX), MASTER-DOMAIN-001 §2.2·§2.3·§3.1~§3.3·§5, DEMO-SEED-001 §4.2, ARCHITECTURE-CONVENTIONS §14, 테스트 아키텍처 Test Fixture Policy.

## 0. 한눈에

| 배치 | 화면(메뉴 "가맹점 관리") | 명령 | 승인 | 이 문서 |
|---|---|---|---|---|
| **B1** | 새 탭 **"가맹점 등록"** | registerBusiness·registerMerchant·registerMerchantSite·registerContract(초안, Revision 1)·addServiceAgreement | OPERATOR 1인(D-MOPS-004) | **상세 설계(§3~§8)** |
| B2 | 승인함 새 종류 + 상세 패널 버튼 | approveContractRevision·issueFirstDefaultMerchantAccount (+ registerContractRevision 1인) | 2인 | 개요(§9) |
| B3 | 상세 패널 "개통" 버튼 | 결제수단 개통 승인·Site 적용 확인·Site 심사 APPROVED·통화 활성화·카드 Route 개통 승인 | 2인 | 개요 |
| B4 | 상세 패널 "한도·수수료" | 한도 상향 등록/교체·수수료 정책 배정 | 2인 | 개요 |
| B5 | 상세 패널 "중지·종료" | 개통 중지/종료·Site 확인 철회·통화 종료 | 1인 즉시 | 개요 |

B1을 끝내면 Demo에서 "사업자 → 가맹점 → Site → 계약 초안 → CSA"를 화면으로 만들고, 기존 "가맹점 조회" 탭 상세에서 그대로 확인할 수 있다. 아직 MID·개통이 없으므로
**결제는 불가**다(B2·B3 뒤). Kafka/Redis는 쓰지 않는다 — 멱등·경합은 MASTER `admin_request` UNIQUE(operation, key)·행 잠금·업무 UNIQUE가 지킨다.

## 1. 범위와 제외(B1 기준)

| 포함(B1) | 제외(후속·다른 작업) |
|---|---|
| MASTER 내부 쓰기 endpoint 5개(`/internal/v1/admin/merchant-operations/**`, 기존 서명 v1·`ADMIN_SERVICE`) | 2인 승인 명령 전부(B2~B4), 승인함 새 종류 |
| ADMIN 화면 API 5개 + "가맹점 등록" 탭(단계형 폼, 성공 ID를 다음 단계로 연결) | 계약 Revision 추가 등록(B2 — 초안 성격이라 1인 후보) |
| ADMIN 감사(명령마다, 등록번호 앞 3자리 마스킹), 속도 제한, 지표 | Business·Merchant·Site·Contract·MID 종료(도메인 메서드 없음 — §9 B5, MASTER-DOMAIN-001 §5 묶음 B) |
| 결과 불명(timeout) 처리: 같은 키 재전송 + 조회 화면 read-back | 사업자등록번호 마스킹 해제(Q-MOPS-04 보류 유지) |
| MASTER 쓰기 permit(대기 0) | mTLS·수신 측 Demo 키 거부(LIVE Gate — MERCHANT-OPS-001 L-1) |

MASTER Use Case 시그니처·migration은 바꾸지 않는다(B1은 대부분 adapter + ADMIN). 예외: ① Domain 규칙 하나 추가 — 계약 시작일 소급 거절(D-MOPS-006 ⑤, §3.4,
`MasterRejection` enum **추가만** — VARCHAR 열이라 migration 없음, 착수 시 `admin_request` 결과 열 CHECK 유무 확인), ② ADMIN 감사 targetRef 형식(C-6).

## 2. 사용자 결정 적용(재질문하지 않음)

| 결정 | 적용 |
|---|---|
| D-MOPS-004 | 등록 5종(Business·Merchant·Site·계약 초안·CSA)은 **OPERATOR 1인**. 계약 승인·첫 Default MID·결제수단 개통 승인·Site 적용 확인·Site 심사 APPROVED·통화 활성화·카드 Route 개통 승인·한도 상향 등록/교체·수수료 정책 배정은 **2인**(요청자≠승인자, APPROVER + 직전 TOTP — ACQ_REACQUISITION과 같은 틀). 안전 방향(중지·철회·종료)은 1인 즉시 |
| D-MOPS-005 | ADMIN→MASTER 명령은 **기존 조회 키(`ADMIN_SERVICE`)로 서명**한다. 별도 조작 키·권한 없음(사용자가 권장안 "조작 전용 키"와 다르게 선택, 2026-11-08). **위험 기록**: 조회 키(`paycore.admin.master-ops.signing-key`)가 유출되면 서명 채널로 등록뿐 아니라 B2 이후 2인 명령까지 MASTER에 직접 보낼 수 있다(ADMIN 승인함 우회). 완화: 2인 명령은 MASTER가 승인 기록(승인 ID·요청자·승인자)을 받아 형식·요청자≠승인자를 다시 검사(§9 B2), 멱등 키·ADMIN 감사·MASTER 변경 이력은 그대로. 키 교체 절차·유출 탐지 지표는 L-1 |
| **D-MOPS-006**(Q-MOPS-10~15, 2026-11-08) | ① 등록 역할 **OPERATOR만**. ② ADMIN 감사는 MASTER 호출 **전 시도 행 + 결과 행**, ADMIN 명령 표 없음. ③ 중복 등록번호는 "이미 등록된 사업자등록번호입니다"만 — **기존 businessId는 ADMIN 응답·화면·감사에 없음**(사용자 선택 — 권장안과 다름). ④ 등록번호가 오간 **모든 시도**(성공·중복·403·형식 오류) 감사 — 형식 오류(해석 실패 포함)만 `brn=INVALID`, 그 밖(403 포함) 앞 3자리(§4.4), **중복 결과는 등록번호 검색 속도 제한에 포함**. ⑤ 계약 효력 시작일이 **KST 오늘 이전이면 MASTER가 거절**(새 거절 코드). ⑥ 같은 사업자 아래 같은 가맹점 이름 허용, 중복 클릭은 같은 요청키 재전송으로 방지 |
| D-MOPS-002·003, Q-MOPS-06 | 등록번호는 감사·로그·지표·오류 응답에 **평문 금지**, 감사 detail은 `brn=123*******`(앞 3자리)만 |
| D-MD-008 | 등록번호 10자리 숫자만(하이픈 정규화 없음 — 거절), Business 하나. 활성 채널 식별값 중복 불가 |
| D-MD-009 | Site 채널 식별값은 소문자 정규형만(자동 정규화 없음 — 화면도 바꾸지 않고 오류 표시) |
| D-MD-004·013 | 계약 Scope: Site 미지정 = MERCHANT Scope, 지정 = SITES Scope. 계약 대상 Merchant는 첫 Scope로 고정 |
| D-MD-001·002 | CSA 1건 = 서비스 종류 1개(현재 `CARD_PG_PAYMENT`만), 같은 계약 안 중복 거절 |
| D-ADMIN-001~004 | 세션 인증·역할은 요청마다 계정 현재 행으로 판정, POST는 CSRF(`X-XSRF-TOKEN`) |
| D-ADMIN-UI-003 | 서버 기능이 생긴 뒤에만 탭을 보인다(B1 서버 배포 전 탭 없음) |

## 3. MASTER 쓰기 endpoint(B1 — `master.merchant.adapter.web.admin`)

### 3.1 채널

- MERCHANT-OPS-001 §3 체인을 그대로 쓴다: 서명 v1, 본문 16 KiB 상한, query 금지, `hasAuthority(ADMIN_SERVICE)`(D-MOPS-005 — 새 권한 없음). 응답 `Cache-Control: no-store`, `PayCore-Request-Id`.
- **쓰기 permit**(C-1 권장): `AdminChannelPermits`에 조회와 별개의 `write` Semaphore(대기 0, 설정 `write-permits` 1..16 — Demo 2, 기본값 없음). 없으면 즉시
  503 `ADMIN_WRITE_BUSY`. 지표 `paycore.master.admin_channel.busy{endpoint}` 재사용.
- 서명 principal은 kid뿐이다 — **운영자 신원은 MASTER가 검증할 수 없다**. 본문의 `actor`는 ADMIN이 세션에서 채운 기록용 참조(`ActorRef` KDoc과 같은 의미).
  채널은 `actor`가 `admin:` 접두(C-4)인지만 형식 검사한다(`system:demo-seed` 등 다른 이름공간을 채널로 쓰지 못하게).
- 명령 실행은 기존 `MerchantOnboardingService`(MASTER 로컬 TX 하나 — 멱등 기록·업무 변경·변경 이력·Outbox·권리 Snapshot 같은 TX). 컨트롤러는 TX를 열지 않는다.
- **쓰기 채널에 nonce가 필요 없는 이유**(review m-3 — 컨트롤러 KDoc에도 같은 문장): 서명 범위인 본문에 `idempotencyKey`가 들어 있다. 시계 창(Demo 300초) 안에서 같은 바이트를
  다시 보내면 같은 키·같은 본문이므로 `admin_request`가 **재생 결과**를 돌려줄 뿐 새 행·새 효과가 없다(키를 바꾸면 서명이 깨진다). 창 밖은 401. 따라서 재전송 공격은 기존 결과의
  재조회로 수렴한다. 시험: `MerchantOperationsApiSpec` "a captured signed command replayed within the clock window adds no rows"(같은 서명 헤더·바이트 두 번 → 두 번째 `replayed=true`,
  MASTER 전 표 행 수·`CHECKSUM TABLE` 불변).
- 컨트롤러는 `@ConditionalOnWebApplication(SERVLET)`(MERCHANT-OPS-001 §10 결함과 같은 이유 — demo-seed 비웹 명령).

### 3.2 경로·본문(모두 POST, ID는 경로가 아니라 서명 본문 — C-2)

공통 필드(모든 본문): `idempotencyKey`(1..64 `[A-Za-z0-9-_.:]`), `actor`(`admin:` + ADMIN 계정 ID, ≤64), `correlationId`(1..128 printable ASCII).
엄격 해석(MERCHANT-OPS-001 `MerchantSearchRequestParser`와 같은 규칙): JSON 객체 하나, 알려진 필드만, 중복 key·뒤따르는 토큰·자료형 강제 변환·`null`(선택 필드 제외) 거부.

| 경로(`/internal/v1/admin/merchant-operations/…`) | 명령 필드 | Use Case | 성공 reference |
|---|---|---|---|
| `businesses` | `registrationNumber`(ASCII 숫자 10자리), `displayName` | registerBusiness | `biz_…` |
| `merchants` | `businessId`, `displayName` | registerMerchant | `mer_…` |
| `merchant-sites` | `merchantId`, `channelType`(WEB·APP), `channelIdentifier` | registerMerchantSite | `site_…` |
| `contracts` | `partyBusinessId`, `scopeMerchantId`, `scopeSiteIds`(배열, 비면 MERCHANT Scope, 최대 50 — C-2), `effectiveFrom`(`…Z` ms), `effectiveTo`(`…Z` ms 또는 생략) | registerContract | `ctr_…`(Revision 1) |
| `service-agreements` | `contractId`, `serviceKind`(`CARD_PG_PAYMENT`) | addServiceAgreement | `csa_…` |

값 검사는 Domain 값 객체(`BusinessRegistrationNumber`·`DisplayName`·`SiteChannel`·`*Id`·`EffectivePeriod`)가 한다 — 위반은 **400 `REQUEST_MALFORMED`**(어느 필드인지 말하지 않음,
`admin_request`에 저장되지 않음 — Use Case 호출 전). `EFFECTIVE_PERIOD_EMPTY`처럼 Use Case 안 판정은 저장되는 업무 거절이다.

### 3.3 응답

| 결과(`AdminCommandResult`·`AdminCommandFailure`) | HTTP | 본문 |
|---|---|---|
| `Succeeded` | 200 | `{"outcome":"SUCCEEDED","reference":"<id>","replayed":<bool>}` |
| `Rejected` | 200 | `{"outcome":"REJECTED","rejection":"<MasterRejection>","replayed":<bool>}` — **`reference` 키 없음**(C-12) |
| `IdempotencyKeyReused` | 409 | `{"error":"IDEMPOTENCY_KEY_REUSED"}` |
| `RequestInProgress` | 503 | `{"error":"REQUEST_IN_PROGRESS"}` — 같은 키로 다시 |
| `OutcomeUnknown` | 503 | `{"error":"OUTCOME_UNKNOWN"}` — 같은 키로 다시(새 키 금지) |
| `IdentifierCollision`·`TemporarilyUnavailable` | 503 | `{"error":"TEMPORARILY_UNAVAILABLE"}` — 같은 키로 다시 |
| permit 없음 | 503 | `{"error":"ADMIN_WRITE_BUSY"}`(Use Case 미호출 — 재전송 안전) |

200은 "저장된 판정"이다(성공·업무 거절 모두 같은 키 재요청에 재생). 오류 본문에 요청 값 없음. 거절 사유 enum은 **추가만**(영속 값).

**거절 reference 비노출(C-12, D-MOPS-006 ③)**: Use Case는 지금처럼 `Rejected.reference`(중복 등록번호면 기존 businessId)를 `admin_request`에 저장·재생한다 — 바꾸지 않는다.
**MASTER 관리 채널 adapter가 응답을 만들 때 모든 거절의 reference를 버린다**(B1 거절 전부 — 화면이 reference를 쓰지 않는다. 특히 `SITE_CHANNEL_DUPLICATE`의 reference도
다른 가맹점의 siteId일 수 있다). 그래서 기존 businessId는 **채널을 건너지 않는다**. ADMIN도 방어로 MASTER 응답 모양 표에서 `reference`를 REJECTED에 허용하지 않는다
(오면 해석 불가 = Unknown — fail-closed). 기존 사용처 확인: `DemoMerchantSeedService`는 거절 reference를 쓰지 않고(`StepRejected(step, rejection)`만),
`MerchantOnboardingServiceSpec`·`FirstDefaultIssuanceAtomicitySpec`의 reference 단언은 Use Case 수준이라 그대로 통과한다(adapter만 바뀜). B2 이후 reference가 필요한
거절이 생기면 거절별 허용 목록으로 연다(그때 결정).

### 3.4 계약 시작일 소급 거절(D-MOPS-006 ⑤ — Domain 규칙)

- 새 거절 **`CONTRACT_START_RETROACTIVE`**(`MasterRejection` 끝에 추가, 영속 이름).
- 위치: **Domain** `Contract.register(…, at, …)` — 판정 시각 `at`(executor가 잠금 뒤 읽은 `lockedNow()`, 서버 `MasterClock`)의 Asia/Seoul 날짜 00:00을 `SeoulMidnight`에 추가할
  `startOfDay(at): Instant`로 구하고 `firstPeriod.from < startOfDay(at)`이면 `MasterRuleViolation(CONTRACT_START_RETROACTIVE)`. adapter·ADMIN은 판정하지 않는다(ADMIN 날짜 선택기
  최솟값 = 오늘 KST는 안내일 뿐). 시각 입력은 기존 `at`이라 새 clock Port 없음.
- 같은 날 시작(`from` = 오늘 00:00 KST 또는 그 뒤 아무 시각)은 허용. 판정은 `Contract.register`에서 `party.requireActive()` 다음, 거절은 저장·재생(같은 키는 계속 같은 거절 — 날짜를 고치면 새 submissionId).
- 경계: KST 23:59에 "오늘"로 보낸 요청이 잠금 대기 뒤 자정을 넘겨 판정되면 거절된다(판정 시각 기준 — 의도된 동작, 화면 문구 "시작일이 오늘보다 앞입니다").
- 범위: B1은 **계약 등록(Revision 1)만**. `registerContractRevision`(B2)에 같은 규칙을 적용할지는 B2 착수 전 Coordinator가 사용자에게 확인한다(Q-MOPS-18, PENDING).
- **DB CHECK 없음(review n-3)**: 다른 소급 규칙(`ck_transaction_limit_policy_not_retroactive`·`ck_policy_assignment_not_retroactive` — V5 `effective_from >= created_at`)은 시각 비교라 CHECK로
  표현되지만, 이 규칙은 **KST 날짜** 기준(같은 날 앞선 시각 허용)이라 `created_at` 시각 비교와 다르고, Revision 1과 이후 Revision이 같은 표에 있어 CHECK를 걸면 Q-MOPS-18 답 전에
  Revision까지 묶인다. migration도 B1 범위 밖이라 Domain 규칙만 둔다(방어선은 Domain + 시험).
- 기존 시험 영향: Fixture는 `from = t0`, 명령 시각 = t0(같은 KST 날)이라 통과 예상 — 착수 시 `ContractSpec`·`MasterFixture`·`OnboardingFixture`·`EntitlementRevisionMatrixSpec`·
  DemoSeed(T0 ≥ 지금 + 10분이 이미 강제됨) 실행으로 확인. 기대값을 바꿔 통과시키지 않는다.

## 4. ADMIN 호출 측·화면 API(B1 — `admin.merchantops`)

### 4.1 Gateway

`MasterOpsGateway`에 쓰기 메서드 5개(또는 `command(kind, body)` 하나 — 구현 재량)를 추가하고 `HttpMasterOpsGateway`가 같은 서명기·설정을 쓴다(D-MOPS-005 — 키 하나).
- **DB TX 안 호출 거부**(기존 규칙), 매 호출 새 `t`, redirect NEVER, 응답 상한 4 KiB(명령 응답은 작다), 허용 필드 모양 표로 응답 재구성(알 수 없는 필드·형식 위반 = 해석 불가).
- 결과는 값: `Decided(outcome, rejection?, reference?(SUCCEEDED만), replayed)` / `KeyReused` / `Busy` / `RetrySameKey(reason)`(503 REQUEST_IN_PROGRESS·OUTCOME_UNKNOWN·TEMPORARILY_UNAVAILABLE) /
  **`Unknown(reason)`**(timeout·연결 끊김·해석 불가 — **효과 유무를 모른다**, 효과 없음으로 단정하지 않는다).
- permit: 조회 permit과 별개의 ADMIN 쓰기 permit(C-1, 대기 0 — Demo 2). MASTER 조회 장애가 등록을 막거나 그 반대가 되지 않게.

### 4.2 멱등 키(C-4 권장)

- 브라우저가 **폼 제출 단위 `submissionId`**(UUID v4, 소문자 hex 32자 — 하이픈 제거)를 만들고, 그 폼의 결과가 확정(200·409)될 때까지 **재전송에 같은 값**을 보낸다.
  결과 미확정 중 입력 수정은 막는다(§5.3). 확정 뒤 같은 폼을 다시 쓰면 새 `submissionId`.
- ADMIN 서버가 MASTER 키를 만든다: `mo1.<ADMIN 계정 ID의 ULID 26자>.<submissionId 32자>`(63자). 계정 ID를 넣는 이유: MASTER 범위 키는 (operation, key)라
  다른 운영자가 같은 `submissionId`를 보내면 남의 결과를 재생받는다 — 계정별로 이름공간을 나눈다. `submissionId` 형식 위반은 400.
- `actor` = `admin:<계정 ID>`. **`correlationId` = `mos.<submissionId>`**(C-14 rev 0.4, 36자, 계정 ID 없음): submissionId에서 파생하되 **MASTER 키가 아니다** — 기존 규칙
  "멱등키 값을 로그에 넣지 않는다"(`AdminCommandExecutor` KDoc — correlationId는 로그·Outbox에 실림)를 지킨다. 재전송마다 같아서, 재생 응답의 감사 행도 최초 실행 때 MASTER가 쓴
  변경 이력·Outbox의 correlationId와 그대로 맞는다. ADMIN 요청별 ID는 로그 필드(`requestId`)로만 남긴다.
- submissionId가 없는 사전 거절(엄격 해석 실패 400·403 — 본문을 믿지 않음, review n-4)의 감사 correlationId = **ADMIN 요청 ID**(MASTER를 부르지 않으므로 대조 대상 없음).

### 4.3 화면 API(세션 + CSRF, `Cache-Control: no-store`, 본문 16 KiB·`application/json`만 — MERCHANT-OPS-001 §5.2 Q-MOPS-08과 같은 읽기 전 거절)

| 메서드·경로 | 본문 | 응답 |
|---|---|---|
| `POST /api/merchant-registrations/businesses` | `submissionId, registrationNumber, displayName` | 200 `{outcome, reference?(성공만), rejection?, replayed}` — 거절에 reference 없음 |
| `POST /api/merchant-registrations/merchants` | `submissionId, businessId, displayName` | 〃 |
| `POST /api/merchant-registrations/merchant-sites` | `submissionId, merchantId, channelType, channelIdentifier` | 〃 |
| `POST /api/merchant-registrations/contracts` | `submissionId, partyBusinessId, scopeMerchantId, scopeSiteIds[], effectiveFromDate, effectiveToDate?`(Asia/Seoul 날짜 `YYYY-MM-DD` — C-7) | 〃 |
| `POST /api/merchant-registrations/service-agreements` | `submissionId, contractId, serviceKind` | 〃 |

공통 오류: 400 `INVALID_INPUT`, 401, 403 `FORBIDDEN`, 409 `IDEMPOTENCY_KEY_REUSED`, 413, 415, 429 `RATE_LIMITED`, 503 `MASTER_BUSY`·`MASTER_RETRY_SAME_KEY`·
**`MASTER_OUTCOME_UNKNOWN`**·`AUDIT_UNAVAILABLE`. 오류 본문 `{"error":"<CODE>"}`만.

### 4.4 판정 순서(ADMIN Use Case `MerchantRegistrationService`)

**사업자 등록 감사의 `brn` 값**(D-MOPS-006 ④, review M-1 — MERCHANT-OPS-001 `MerchantLookupService` 선례 `searchByRegistrationNumber`·`rejectMalformedSearch`와 같은 규칙):
본문이 **엄격 해석에 성공하고 등록번호가 형식(ASCII 숫자 10자리)에 맞으면** 어느 결과(403 포함)든 `brn=123*******`(앞 3자리). **엄격 해석 실패(시도 판정은 Q-MOPS-07 규칙 — 첫 최상위
객체의 직속 key `registrationNumber`)이거나 번호 형식 위반일 때만** `brn=INVALID`(본문에서 아무것도 꺼내지 않음).

0. 컨트롤러가 엄격 해석(읽기 전 거절 413·415는 감사 없음). 해석 실패: 사업자 등록 시도면 `rejectMalformed` 경로(역할 없음 403 / 그 밖 400, 둘 다 감사 `brn=INVALID`), 다른 4종은 400·감사 없음.
1. 계정 ACTIVE + 등록 역할 **OPERATOR만**(D-MOPS-006 ①). 아니면 403 + 감사 `FORBIDDEN` — **5종 모두**(사업자 등록 brn은 위 규칙).
2. 값 형식(MASTER Domain 규칙과 같은 검사를 ADMIN 값 객체로 — C-10, 화면 즉시 안내용, 권위는 MASTER). 위반 400, MASTER 미호출. 사업자 등록은 감사 `INVALID_INPUT`(brn은 위 규칙 —
   번호는 맞고 사업자명만 틀리면 마스킹 값). 다른 4종의 형식 위반은 감사 없음(등록번호 없음 — 지표만).
3. 속도 제한: 등록 버킷(actor별 분당 — Demo 20, 조회 버킷과 별도) 초과 → 429 + 감사 `RATE_LIMITED`. **사업자 등록은 등록번호 검색 버킷(Demo 10/분)에서 1회를 원자적으로 예약**
   (C-13 — 확인과 차감이 한 동작, 남은 횟수 0이면 429 + 감사, MASTER 미호출).
4. 시도 감사 행 `REQUESTED`(D-MOPS-006 ②)를 ADMIN 로컬 TX로 커밋 — 실패하면 503 `AUDIT_UNAVAILABLE`, MASTER 미호출(예약 환불).
5. MASTER 호출(TX 밖).
6. 결과가 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`면 예약을 **확정**(D-MOPS-006 ④ — 중복 응답은 "그 번호가 있다"는 검색 결과와 같은 정보). 성공·다른 거절·409는 **환불**(0 아래로 내려가지 않음 —
   창이 바뀌었으면 환불하지 않는다). **Unknown·503은 확정**(중복이었을 수 있다 — 보수적, 재전송 결과로 다시 환불하지 않는다).
7. 결과 감사 행(ADMIN 로컬 TX) → 응답. 감사 실패 시 503 `AUDIT_UNAVAILABLE` + 화면은 "결과 확인 필요"(MASTER 효과는 이미 커밋됐을 수 있다 — 같은 `submissionId` 재전송이
   재생 결과로 감사를 다시 시도한다). 시도 행은 이미 있으므로 ADMIN 감사에 흔적이 남는다.

## 5. 화면 "가맹점 등록" 탭(B1)

### 5.1 배치

`navigation.ts` `merchants` 메뉴 tabs에 `{ page: 'merchantRegistration', label: '가맹점 등록' }` 추가 — **OPERATOR에게만** 보인다(서버 판정이 권위, 화면은 표시만).
메뉴 설명 "조회 전용 — 등록·변경은 별도 작업"을 "조회·등록"으로 고친다. 새 화면 `MerchantRegistrationScreen.tsx`.

### 5.2 단계형 폼(한 화면, 위에서 아래로)

| 단계 | 입력 | 이전 단계에서 자동 채움 | 성공 뒤 표시 |
|---|---|---|---|
| ① 사업자 | 등록번호(숫자 10자리, `type=password` 아님·자동완성 끔·제출 뒤 입력란 비움), 사업자명 | — | businessId, 마스킹 등록번호(입력값에서 화면이 계산 — 서버가 평문을 돌려주지 않음) |
| ② 가맹점 | 가맹점명 | businessId | merchantId |
| ③ Site | 채널(WEB·APP), 식별값 | merchantId | merchantSiteId |
| ④ 계약 초안 | Scope(가맹점 전체 / 선택 Site — ③에서 만든 Site 체크), 시작일(선택기 최솟값 = 오늘 KST — 안내, 판정은 MASTER §3.4), 종료일(선택 — 입력란 옆 고정 문구 **"이 날 0시 종료(미포함)"**, C-7 배타 끝) | partyBusinessId, scopeMerchantId | contractId(Revision 1, **미승인**) |
| ⑤ CSA | 서비스 종류(`카드 PG 결제` 하나) | contractId | agreementId |

- 각 단계 위에 "기존 ID로 이어서" 입력(예: 이미 있는 businessId로 ②부터). ID는 형식만 화면이 검사한다.
- 완료 패널: 만든 ID 목록 + **"가맹점 조회에서 보기"**(기존 상세 `GET /api/merchants/{merchantId}` — `MERCHANT_VIEWED` 감사 그대로) + "다음: 계약 승인·MID 발급은 B2(아직 없음)" 안내.
- 업무 거절은 사유별 한국어 문구(예: `BUSINESS_REGISTRATION_NUMBER_DUPLICATE` → **"이미 등록된 사업자등록번호입니다"만** — 기존 사업자 ID·링크 없음(D-MOPS-006 ③),
  `SITE_CHANNEL_DUPLICATE`, `SCOPE_SITE_OF_OTHER_MERCHANT`, `SERVICE_KIND_DUPLICATE`, `BUSINESS_NOT_ACTIVE`, `MERCHANT_NOT_ACTIVE`, `EFFECTIVE_PERIOD_EMPTY`,
  `CONTRACT_START_RETROACTIVE` → "시작일이 오늘보다 앞입니다"). 모르는 거절 code는 일반 문구. `replayed=true`면 "이전에 처리된 결과입니다" 표시.
- 같은 사업자 아래 같은 이름 가맹점은 허용(D-MOPS-006 ⑥) — 화면은 경고하지 않는다. 중복 클릭은 제출 중 버튼 비활성 + 같은 `submissionId` 재전송으로 막는다.

### 5.3 결과 불명(503 `MASTER_OUTCOME_UNKNOWN`·`MASTER_RETRY_SAME_KEY`·`AUDIT_UNAVAILABLE`, 네트워크 오류)

- 폼을 **잠그고**(입력 수정 불가) "같은 내용 다시 보내기"(같은 `submissionId`)만 준다. 성공·거절이 오면 잠금 해제. 409 `IDEMPOTENCY_KEY_REUSED`는 결함 신호로 표시하고 새 제출 허용.
- "취소하고 새로 입력"을 고르면 경고: "앞 요청이 이미 처리됐을 수 있습니다 — 가맹점 조회로 먼저 확인하세요". 특히 **가맹점·계약은 업무 유일키가 없어**(D-MOPS-006 ⑥ — 이름 중복 허용) 새 키로 다시 보내면 중복 행이 생길 수 있다.
- `submissionId`·입력은 화면 메모리에만 둔다(새로고침 시 사라짐 — 등록번호를 브라우저 저장소에 두지 않는다). 새로고침 뒤 확인 수단은 조회 화면(L-3).

## 6. 감사·권한(B1)

| 항목 | 내용 |
|---|---|
| 역할 | 등록 5종 **OPERATOR만**(D-MOPS-006 ①). 탭·버튼 표시도 같은 역할 |
| 감사 행위(C-5 채택 — 명령별 code) | `MERCHANT_BUSINESS_REGISTER`, `MERCHANT_REGISTER`, `MERCHANT_SITE_REGISTER`, `MERCHANT_CONTRACT_REGISTER`, `MERCHANT_AGREEMENT_ADD` |
| 행 수 | MASTER까지 가는 시도 = **2행**(시도 `REQUESTED` → 결과), 호출 전 거절(403·400·429) = 결과 1행(D-MOPS-006 ②) |
| resultCode | `REQUESTED`(시도 행), `OK`, `REJECTED`, `KEY_REUSED`, `FORBIDDEN`, `INVALID_INPUT`, `RATE_LIMITED`, `BUSY`, `OUTCOME_UNKNOWN`(Unknown·RetrySameKey) |
| targetRef | 성공 = 만든 ID. 시도·거절·그 밖 = 입력의 상위 ID(②~⑤ — 운영자가 입력한 ID). 사업자 등록은 성공일 때만(**중복 거절에 기존 businessId 없음** — D-MOPS-006 ③). `site_`는 C-6으로 허용 |
| detail(고정 `key=value`) | `sub=<submissionId 앞 8자>;replayed=<bool>;rejection=<code>`(전체 submissionId는 correlationId `mos.<submissionId>` — C-14, MASTER 키 원문은 기록하지 않음) + 사업자 등록은 **모든 행**에 `brn`: 엄격 해석 성공 + 형식 맞는 번호면 403 포함 `brn=123*******`, 해석 실패·형식 위반만 `brn=INVALID`(§4.4). Site는 `channel=WEB`만(식별값 미기록 — MASTER 이력에 있음) |
| 넣지 않는 값 | 등록번호 평문, **중복 시 기존 businessId**, 사업자명·가맹점명(자유 문구), 채널 식별값, MASTER 키 원문, 서명 |
| 감사 실패 | 행위 실패(503 `AUDIT_UNAVAILABLE`). 단 MASTER 효과는 되돌리지 않는다 — §4.4 6 |
| MASTER 측 | 기존 `master_change_history`(actor = `admin:<계정 ID>`, Business는 마스킹 등록번호만)·Outbox 그대로 |

## 7. 8대 원칙 적용(B1)

| 원칙 | 의미 | 증거(계획 시험) |
|---|---|---|
| Financial Consistency | 금액 없음. 결제 권리 입력 변경은 Site 등록(revision +1 — MID 없으면 발행 없음)뿐, 계약 초안은 미승인이라 권리 불변 | `MerchantOperationsApiSpec` Site 등록 뒤 revision·Snapshot 발행 0(MID 없음) |
| Transaction Safety | MASTER: 명령 하나 = 로컬 TX 하나(기존). ADMIN: 외부 호출 TX 밖, 감사는 ADMIN 로컬 TX | `MasterOpsGatewaySpec` TX 안 쓰기 호출 거부, `MerchantRegistrationServiceSpec` 호출·감사 TX 순서 |
| Idempotency | 같은 키·같은 본문 = 과거 결과(`replayed=true`), 같은 키·다른 본문 = 409, 계정별 키 이름공간 | MASTER HTTP 재전송 재생·충돌, ADMIN 키 파생(다른 계정 같은 submissionId → 다른 MASTER 키) |
| Concurrency Control | 같은 등록번호·채널 동시 등록 → 하나 성공·하나 업무 거절(UNIQUE 재판정), 같은 키 동시 2건 → 하나 실행·하나 재생/진행 중. 쓰기 permit 대기 0 | 실제 MySQL 두 연결 경합 시험(`CountDownLatch`), permit 소진 503 |
| Recoverability | timeout·연결 끊김 = Unknown(효과 단정 없음), 같은 키 재전송으로 수렴, 응답 유실 시험 | MASTER 실제 HTTP + MySQL: 커밋 뒤 클라이언트가 응답을 읽기 전 연결을 끊음 → 같은 키 재전송 `replayed=true`, `admin_request`·`business` 각 1행(§8 M-2a). ADMIN stub: 재전송이 같은 MASTER 키·시도/결과 감사 쌍(M-2b) |
| Reconciliation | ADMIN 감사(actor·correlationId) ↔ MASTER 변경 이력·Outbox(actor·correlationId) 대조 가능 — correlationId가 재전송에도 같다(C-14). 대사 job은 만들지 않음(L-2) | 재전송 뒤 감사 행 correlationId = 최초 실행의 MASTER Outbox correlationId 단언 |
| Auditability | 명령마다 감사, 등록번호 마스킹, 자유 문구 없음 | 감사 표 전 열에 등록번호 원문 0건(대조군 포함), 변이: 마스킹 제거 → RED |
| Observability | 결과별 counter, 불명 결과 WARN(민감값 없음) | 지표·로그 캡처 시험(서명 키·서명·**MASTER 멱등 키 원문**·등록번호·채널 식별값 없음 — correlationId `mos.…`는 허용) |

지표: MASTER `paycore.master.admin_channel.command{operation,outcome}`(outcome = SUCCEEDED·REJECTED·KEY_REUSED·FAILED), 기존 `adminOutcomeUnknown` 등 재사용.
ADMIN `paycore.admin.merchant_ops.command{operation,result}`, `paycore.admin.merchant_ops.command_unknown{operation}`(경보 후보), `…rate_limited{kind=REGISTRATION}`. label에 ID·값 없음.

## 8. 시험·수용 기준(B1)

Fixture: Test Fixture Policy 그대로 — Domain Factory(`Business.register` 등) → 이름 있는 Fixture(`OnboardingCommandFixtures.registerBusiness(brn = "7781234560")` 등, 업무 조건이 이름에 드러나게)
→ Fixture Monkey(형식 조합 생성 — 값 객체 불변식을 만족하는 것만). 무효 Fixture는 의도를 이름에 둔다(`withUpperCaseHost`, `withHyphenatedBrn`). Fixture는 `src/test`에만.

**MASTER(apps/master-api)**
- `ContractSpec`(Domain, 추가 — D-MOPS-006 ⑤): 이름 있는 Fixture로 KST 경계 표 — `at` = 2026-11-08T15:00Z(KST 11-09 00:00)에서 `from` = 11-08T15:00Z 허용, 11-08T14:59:59.999Z
  `CONTRACT_START_RETROACTIVE`, `at` = 11-08T14:59:59.999Z(KST 11-08 23:59)에서 `from` = 11-07T15:00Z(KST 11-08 00:00) 허용, 미래 `from` 허용, 비활성 당사자는 기존 `BUSINESS_NOT_ACTIVE`가 먼저.
  `SeoulMidnightSpec`: `startOfDay` 경계(UTC 15:00 전후). 변이: 비교를 `at`(자정 아님)으로 바꾸면 같은 날 시작 허용 사례가 RED.
- `MerchantOnboardingServiceSpec`(추가): 소급 계약 등록 → 저장된 거절·같은 키 재생·계약 행 0·이력/Outbox 0. 기존 reference 단언(중복 등록번호 = 기존 businessId)은 **Use Case 수준이라 그대로**.
  **회귀(review n-1 — Q-MOPS-18 경계 고정)**: B1에서 `registerContractRevision`은 과거 시작 Revision을 **여전히 받는다**(성공) — Q-MOPS-18 답 전 규칙이 Revision으로 번지지 않았음을 고정.
  답이 오면 이 시험의 기대값은 그 결정 근거로만 바꾼다.
- `MerchantOperationsRequestParserSpec`(단위): 필드 5종 본문 엄격 해석 — 모르는 key·중복·강제 변환·`null`·`actor` 접두 위반·Site 50개 초과 → 400, 오류에 값 없음.
- `MerchantOperationsApiSpec`(보안 체인 + MockMvc + MySQL): 무서명 401(새 handler 전수), 5종 성공 응답 모양, 업무 거절 200 + 재생, 키 재사용 409, `OutcomeUnknown`·`RequestInProgress` 매핑(Use Case 대역),
  쓰기 permit 소진 503 + 조회 permit과 독립, 저장소 실패 503(예외 문구 미로그), 로그에 서명 키·서명·MASTER 멱등 키 원문·등록번호 평문 없음(대조군 — correlationId `mos.…`는 있어도 됨), 지표.
  **C-12**: 중복 등록번호·중복 채널 거절 응답 바이트에 `reference` 키와 기존 ID 문자열이 없다(대조군 — 같은 DB `admin_request` 결과에는 기존 businessId가 있음). 변이: adapter가 reference를 내보내면 RED.
  **nonce(m-3)**: 같은 서명 헤더·바이트를 시계 창 안에서 두 번 → 두 번째 `replayed=true`, MASTER 전 표 행 수·`CHECKSUM TABLE` 불변.
  **쓰기 경로 전수(review m-5 — D-MOPS-005 위험 범위 고정)**: `RequestMappingHandlerMapping`에서 `/internal/v1/admin/**`의 GET 아닌 handler 집합이 **정확히** {`POST /merchant-searches`(조회), B1 5개 POST}
  — 승인·개통·한도·정지 경로가 없다. B2 이후 경로를 추가하는 작업은 이 기대 집합을 그 배치 결정 근거로 갱신한다.
- `MerchantOperationsLostResponseSpec`(**M-2a**, 실제 HTTP 서버 — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + 실제 MySQL + 실제 서명 필터): 서명한 `businesses` 요청을 raw 소켓으로 보내고
  MASTER 커밋 뒤(서버 측 커밋 완료 신호 — 시험용 Port 대역 또는 `admin_request` 결과 행 폴링) **응답을 읽기 전에 연결을 끊는다** → 같은 키·같은 본문 재전송(새 `t`·새 서명) → 200
  `replayed=true`, 같은 reference, `admin_request` 그 키 **1행**, `business` 그 등록번호 **1행**, 이력·Outbox 각 1건.
- `MerchantOperationsConcurrencySpec`(실제 MySQL 두 연결): 같은 등록번호 다른 키 동시 → 1 성공 1 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`, 같은 채널 동시 → 1 성공 1 `SITE_CHANNEL_DUPLICATE`,
  같은 키·같은 본문 동시 → 행 1개.
- `MasterAdminChannelSettingsSpec` 갱신: `write-permits` 검증·기본값 없음. `ArchitectureSpec`: 컨트롤러가 Domain 저장 Port를 직접 부르지 않음(Use Case만).

**ADMIN(apps/internal-admin)**
- `MasterOpsGatewaySpec` 확장: 쓰기 서명 golden 일치, 응답 모양 fail-closed, timeout·연결 끊김·과대 응답 → `Unknown`, 503 code별 분류, TX 안 거부, 쓰기 permit 별도.
- `MerchantRegistrationServiceSpec`: OPERATOR만 허용(VIEWER·APPROVER·SECURITY_ADMIN 403 + 감사 `FORBIDDEN` — 사업자 등록은 **형식 맞는 번호면 `brn=778*******`**, 엄격 해석 실패·형식 위반이면
  `brn=INVALID` — review M-1 행렬), 형식 400(MASTER 미호출, 사업자 등록 감사 `INVALID_INPUT`), 속도 제한(등록 버킷), **등록번호 버킷 예약·확정·환불**(중복·Unknown 확정, 성공·다른 거절·409·시도 감사 실패 환불,
  0 아래 없음, 같은 actor 동시 2건이 남은 1회를 둘 다 쓰지 못함 — C-13), correlationId = `mos.<submissionId>`(재전송 동일, MASTER 키와 다름 — C-14; 사전 거절은 ADMIN 요청 ID — n-4), 감사·로그 어디에도 MASTER 키 원문 없음, 키 파생(계정 이름공간), 시도 행 → 결과 행 순서,
  **시도 행 감사 실패 → MASTER 미호출**, 결과 감사 실패 503, Unknown 감사 `OUTCOME_UNKNOWN`, MASTER 응답에 REJECTED + `reference`가 오면 해석 불가(Unknown).
- `MerchantRegistrationHttpSpec`(실제 로그인 세션 + MySQL + stub MASTER): 역할별 5종, 중복 등록번호 응답·감사 행 어디에도 기존 businessId 없음(stub이 일부러 넣어 보낸 경우 포함), CSRF 없는 POST 거부, 413·415, 감사 행 등록번호 원문 0건, 403 감사 brn 행렬(M-1: 엄격 해석 성공·형식 맞음 → 마스킹 / 해석 실패·형식 위반 → INVALID).
  **응답 유실(M-2b — stub이라 MASTER 효과는 단언하지 않는다)**: stub이 첫 요청에 응답하지 않음(read timeout) → 503 `MASTER_OUTCOME_UNKNOWN` → 같은 submissionId 재전송 → stub이 받은 두 요청의
  `idempotencyKey`·`correlationId`·명령 필드가 **동일**(서명·`t`만 다름), 감사 행이 (REQUESTED, OUTCOME_UNKNOWN), (REQUESTED, OK) 두 쌍이고 같은 correlationId.
- harness: `test_admin_call_signature_golden.py` 변경 없음(같은 서명 v1) — 쓰기 경로 벡터를 golden에 추가할지는 C-9.

**Frontend(Vitest)** `MerchantRegistrationScreen.test.tsx`: OPERATOR만 탭 표시, 단계 자동 채움, 성공 ID 표시, 거절 문구(중복 등록번호는 고정 문구만·ID 없음, 소급 시작일), 종료일 입력란 옆 "이 날 0시 종료(미포함)" 문구(m-6), **결과 불명 시 폼 잠금 + 같은 submissionId 재전송**, 등록번호 입력란 제출 뒤 비움·응답 미표시, 조회 탭 이동. navigation·labels 시험 갱신.

### 8.1 수용 기준(배치별 — Verifier 확인 항목, C-8 전달 순서)

공통 증거: 등록된 명령의 exit code·시험 개수(0건·skip은 실패)·보고서 경로·source fingerprint·시각. 등록번호·채널 식별값·기존 businessId가 로그·감사·지표·오류 응답에 없다는 단언은 대조군과 함께.

**B1-1 사업자·가맹점·Site**
1. MASTER `businesses`·`merchants`·`merchant-sites` 3 endpoint가 서명 + `ADMIN_SERVICE`로만 열림: 무서명·틀린 서명 401, 쓰기 permit 소진 503 `ADMIN_WRITE_BUSY`(조회 permit과 독립), 엄격 본문 400.
   `/internal/v1/admin/**`의 GET 아닌 handler 집합 = {`merchant-searches`, B1-1까지 열린 쓰기 경로}(m-5 — B1-2 뒤 B1 5개).
2. 같은 키·같은 본문 → `replayed=true`·MASTER 행 수 불변, 같은 키·다른 본문 409, 다른 계정 같은 submissionId는 서로 다른 MASTER 키. 같은 서명 바이트를 시계 창 안에서 재전송해도 새 행 0(m-3).
3. 실제 MySQL 경합: 같은 등록번호 다른 키 동시 → 1 성공 1 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`, 같은 채널 동시 → 1 성공 1 `SITE_CHANNEL_DUPLICATE`, **같은 키·같은 본문 동시 → 업무 행 1개·`admin_request` 1행**
   (다른 쪽은 `replayed=true` 또는 503 `REQUEST_IN_PROGRESS` 뒤 재전송 시 재생 — m-4).
4. **중복 등록번호 거절의 MASTER 응답·ADMIN 응답·화면·ADMIN 감사에 기존 businessId 없음**(C-12·D-MOPS-006 ③). 등록번호 버킷: 예약 → 중복·Unknown 확정, 성공·다른 거절 환불, 0 아래 없음, 동시 예약이 남은 횟수를 넘지 않음(C-13).
5. 역할: OPERATOR만 200, 그 밖 403 + 감사 `FORBIDDEN`. 사업자 등록 모든 시도 감사(시도 행 + 결과 행). brn: **엄격 해석 성공 + 형식 맞는 번호면 403 포함 `brn=앞3*******`**, 해석 실패·형식 위반만 `brn=INVALID`(M-1).
   감사 표 전 열 등록번호 원문 0건.
6. 응답 유실(M-2): **(a) MASTER** 실제 HTTP 서버 + 실제 MySQL — 커밋 뒤 클라이언트가 응답을 읽기 전 연결 종료 → 같은 키 재전송 → `replayed=true`, 그 키 `admin_request` 정확히 1행·`business` 정확히 1행.
   **(b) ADMIN** stub — 재전송 요청의 MASTER `idempotencyKey`·`correlationId`·명령 필드가 첫 요청과 동일, 감사가 (REQUESTED, OUTCOME_UNKNOWN)·(REQUESTED, OK) 두 쌍. 시도 행 감사 실패 시 MASTER 미호출.
7. Vitest: OPERATOR만 탭, ①~③ 자동 채움, 결과 불명 시 폼 잠금 + 같은 submissionId 재전송, 등록번호 입력란 제출 뒤 비움.
8. Demo(Coordinator): OPERATOR로 ①~③(Demo 값 — 운영값 아님: 등록번호 `0000000001`처럼 실행마다 새 값, Site `demo-shop2.paycore.test`) → "가맹점 조회"에서 새 가맹점·Site·이력(actor `admin:…`) →
   같은 등록번호 재등록 시 "이미 등록된 사업자등록번호입니다"만 → VIEWER 탭 없음·API 403.

**B1-2 계약 초안·CSA**
1. MASTER `contracts`·`service-agreements` endpoint가 B1-1 1·2와 같은 채널·멱등 성질(같은 시험 행렬에 2 endpoint 추가).
2. Domain `CONTRACT_START_RETROACTIVE`: KST 경계 표(§8 `ContractSpec`) 통과, 소급 거절은 저장·재생되고 계약 행·이력·Outbox 0. 기존 MASTER 시험 전체가 기대값 변경 없이 통과.
   `registerContractRevision`은 과거 시작을 여전히 받는다(n-1 회귀 — Q-MOPS-18 경계).
3. Scope: MERCHANT/SITES 둘 다, 다른 가맹점 Site → `SCOPE_SITE_OF_OTHER_MERCHANT`, `scopeSiteIds` 51개 400. 같은 계약 CSA 두 번 → `SERVICE_KIND_DUPLICATE`(reference 없음).
4. ADMIN 날짜 변환(C-7): `2026-11-09` → `2026-11-08T15:00:00.000Z`, 종료일 배타. 감사 2행(시도·결과), targetRef = 계약 ID·CSA ID.
5. 계약 등록·CSA 추가는 권리 revision·Snapshot을 바꾸지 않는다(미승인 — MID 없음).
6. Vitest: ④ Scope 선택(③ Site 체크), 시작일 최솟값 오늘 KST, 종료일 "이 날 0시 종료(미포함)" 문구(m-6), 소급 거절 문구, ⑤ 자동 채움, 완료 패널 "가맹점 조회에서 보기".
7. Demo(Coordinator): ④ 시작일 내일 → ⑤ → 조회 상세에 계약 Revision 1 **미승인**·CSA·MID 0 → 시작일 어제 입력 시 거절 문구. "결제 불가(B2·B3 뒤)" 안내 확인.

## 9. B2~B5 개요(설계는 각 배치 착수 시 — 질문 먼저)

**B2 계약 승인·첫 Default MID(2인)** — ADMIN 승인함 새 종류 `MERCHANT_ONBOARDING`(가칭, OWNER_SERVICE 2-TX, 요청 OPERATOR·승인 APPROVER + TOTP, 대상 종류 `MERCHANT`),
명령 표(승인 ID별 본문·지문), 회복 job·결과 반영은 ACQ_REACQUISITION 틀 재사용. MASTER 쪽:
- 현재 `approveContractRevision`은 "요청자≠승인자 통제는 Q-ADMIN-AUTH 이후(행위자 기록만)"이고 `ActorRef`는 인증 신원이 아니다 → MASTER가 **승인 기록**(approvalId·requesterRef·approverRef·승인 시각)을 받아
  형식·요청자≠승인자를 검사하고 자기 표에 남겨야 한다(D-ADMIN-007 "소유 서비스 재검사"). 새 MASTER 표·migration·`AdminOperation` 추가 필요 → Q-MOPS-16(PENDING).
- 승인 종류 2개(계약 승인 / 첫 MID 발급 — C-11 채택). registerContractRevision의 소급 규칙 적용 여부는 Q-MOPS-18(PENDING).
- 멱등 키는 승인 ID 기반(`apr_…` + 명령 번호). D-MOPS-005 위험(L-1)이 가장 큰 배치 — 키 유출 시 MASTER 직접 호출로 승인 우회 가능함을 Reviewer가 재확인.

**B3 개통(2인)** — approvePaymentMethodActivation(근거 + PRIMARY Site 적용 확인 동반 D-MD-016), confirmSiteActivation, decideSiteReview(APPROVED), enableCurrency, approveCardRouteActivation.
미결: 개통 **등록**(registerPaymentMethodActivation·registerCardRouteActivation — UNDER_REVIEW라 결제 불가)이 1인인지(D-MOPS-004 목록에 없음 → Q-MOPS-17), 카드 Route 기관 가맹점 참조(D-MS2-005 감사 전용) 입력·전송·감사 형태, Site 심사 REJECTED가 안전 방향인지.

**B4 한도·수수료(2인)** — registerTransactionLimitPolicy·replace(상향), assignPolicy(FEE_SCHEDULE). 미결: **한도 하향**·**한도 종료**(종료 = 상한 없음 → 실질 완화)·정산 정책 배정·
endPolicyAssignment의 1인/2인 분류(Q-MOPS-17에 포함), "상향" 판정 기준(기존 대비 비교 — 통화·건당/일 각각). registerPolicyVersion은 전역 정책이라 이 작업 밖. 한도·요율 값은 Demo 값 — 운영값 아님.

**B5 안전 방향(1인 즉시)** — suspend/terminate PaymentMethodActivation·CardRouteActivation, revokeSiteActivationConfirmation, endCurrencyEnablement. 미결: **resume**(재개 = 완화 방향 → 2인 권장, Q-MOPS-17),
Business·Merchant·Site·Contract·MerchantAccount **종료 메서드가 Domain에 없음**(per-activation suspend/terminate만 — MASTER-DOMAIN-001 §5 묶음 B, D-MD-006 "생명주기 + 신규 결제 차단 기록"은 MASTER-LIFECYCLE-001 미착수) → B5에서 가맹점 단위 정지는 할 수 없다.

## 10. Coordinator 결정(가역 — rev 0.2: C-1~C-11 권장안 **채택**, C-12·C-13 추가. rev 0.3: C-13 개정·C-14 추가 — 채택 권장)

| ID | 선택 | 채택(권장안) | 대안 |
|---|---|---|---|
| C-1 | MASTER·ADMIN 쓰기 permit | **조회와 별도 Semaphore(대기 0, Demo 2)** — 조회 폭주가 등록을, 등록이 권리 relay를 막지 않게 | 조회 permit 공유 |
| C-2 | 경로·ID 위치 | **명령별 POST 경로, ID는 서명 본문**(경로 변수 형식 검사·로그 노출 줄임), `scopeSiteIds` ≤ 50 | REST 중첩 경로(`/contracts/{id}/service-agreements`) |
| C-3 | 업무 거절 HTTP | **200 + `outcome=REJECTED`**(저장된 판정 — 재생과 같은 모양) | 422 |
| C-4 | 키·actor 파생 | **`mo1.<계정 ULID>.<submissionId>`, actor `admin:<계정 ID>`**, 브라우저가 제출 단위 submissionId | 브라우저 키 그대로 전달 / ADMIN 명령 표가 키 발급(D-MOPS-006 ②로 명령 표 없음) |
| C-5 | 감사 action | **명령별 5개 code** | 하나(`MERCHANT_ONBOARDING_COMMAND`) + detail `op=` |
| C-6 | `site_` targetRef | **ADMIN `AdminAuditEvent.TARGET_REF`를 `[a-z]{3,4}_ULID`로 확장**(열 길이 64 안) + 시험 | Site 감사는 merchantId를 targetRef, siteId는 detail |
| C-7 | 계약 기간 입력 | **Asia/Seoul 날짜 → 그 날 00:00 KST의 UTC 시각**, 종료일은 그 날 00:00(배타). 소급은 MASTER Domain이 거절(§3.4) | 시각 직접 입력 |
| C-8 | B1 Demo 분할 = **전달 순서** | **B1-1(사업자·가맹점·Site — 조회 탭에서 새 가맹점 보임) → B1-2(계약 초안·CSA)** 두 번 보여 준다(§8.1) | B1 한 번 |
| C-9 | 쓰기 경로 서명 벡터 | golden 파일 불변(서명 v1은 경로 무관), **쓰기 경로 사례는 각 앱 시험에만** | golden에 쓰기 경로 벡터 추가(세 앱 동시 갱신) |
| C-10 | ADMIN 값 검사 | **MASTER와 같은 형식 규칙을 ADMIN 값 객체로 복제**(코드 공유 없음 — 모듈 경계) + 경계 사례 표를 양쪽 시험에 | ADMIN은 형식 검사 없이 전달(MASTER 400만) |
| C-11 | B2 승인 단위 | 계약 승인과 첫 MID 발급을 **승인 종류 2개**(각각 2인 — 중간 상태가 명시적) | 한 승인 건에서 연속 실행 |
| C-12 | 거절 reference 채널 노출(D-MOPS-006 ③ 뒤) | **MASTER 관리 채널 adapter가 B1 거절 전부에서 reference를 내보내지 않음**(기존 ID가 채널을 건너지 않음) + ADMIN 응답 모양 표가 REJECTED의 `reference`를 거부(fail-closed). Use Case·`admin_request`·seed·기존 시험은 그대로(§3.3 확인 결과) | MASTER는 그대로 보내고 ADMIN만 버림(ID가 채널·ADMIN 메모리를 지남) |
| C-13 (rev 0.3 개정) | 등록번호 버킷 사용 시점 | **호출 전 1회 원자적 예약**(확인·차감 한 동작 — 같은 actor 동시 요청이 남은 1회를 둘 다 쓰지 못함) → 중복·Unknown·503 **확정**, 성공·다른 거절·409·시도 감사 실패 **환불**(0 아래 없음, 창이 바뀌면 환불 없음) | 호출 전 확인만·중복 시 사후 소모(확인과 소모 사이 경합 — review m-2로 폐기) / 항상 1 소모 |
| C-14 (rev 0.4 개정 — Coordinator 결정) | 재전송에도 고정된 상관 ID | **`correlationId` = `mos.<submissionId>`**(재전송마다 같아 재생 응답의 감사 행이 최초 실행의 MASTER 이력·Outbox와 같은 값, 36자 ≤ ADMIN 감사 64·MASTER 128, 계정 ID 없음). MASTER 키가 아니라 기존 "멱등키 값을 로그에 넣지 않는다" 규칙 유지. 사전 거절(400 해석 실패·403)은 ADMIN 요청 ID | rev 0.3안 correlationId = MASTER 키(멱등키 로그 금지 규칙과 충돌 — review m-7로 폐기) / 감사 detail에 submissionId 전체 |

## 11. 사용자 질문

### 11.1 답변됨(rev 0.2 — D-MOPS-006, 2026-11-08, `harness/questions.json`)

| ID | 답 | 권장안과 |
|---|---|---|
| Q-MOPS-10 | 등록 5종 OPERATOR만 | 같음 |
| Q-MOPS-11 | MASTER 호출 전 시도 감사 + 결과 감사, ADMIN 명령 표 없음 | 같음 |
| Q-MOPS-12 | 중복 등록번호는 "이미 등록된 사업자등록번호입니다"만, 기존 businessId는 ADMIN 응답·화면·감사에 없음 → §3.3 C-12 | **다름(대안 선택)** |
| Q-MOPS-13 | 등록번호가 오간 모든 시도(성공·중복·403·형식 오류) 감사 — 형식 오류만 `brn=INVALID`, 그 밖(403 포함) 앞 3자리, 중복 결과는 등록번호 검색 속도 제한에 포함 → §4.4 | 같음 |
| Q-MOPS-14 | 계약 시작일이 KST 오늘 이전이면 MASTER 거절 → §3.4 `CONTRACT_START_RETROACTIVE` | 같음 |
| Q-MOPS-15 | 같은 사업자 아래 같은 이름 가맹점 허용, 중복 클릭은 같은 요청키 재전송으로 방지 | 같음 |

### 11.2 PENDING(Coordinator 경유 — 답 전 해당 배치 운영 동작 구현 금지)

**Q-MOPS-16 MASTER 2인 재검사 기록(B2 착수 전)** — 권장안: MASTER `admin_approval_evidence`(가칭) 표에 approvalId·요청자·승인자·지문을 명령과 같은 TX로 남기고 요청자≠승인자 재검사. 대안: ADMIN만 판정(MASTER는 행위자 기록만 — D-ADMIN-007과 다름). 영향: MASTER migration·D-MOPS-005 위험 완화 정도.

**Q-MOPS-17 D-MOPS-004 목록에 없는 명령의 1인/2인 분류(B3~B5 착수 전)** — 개통 등록(UNDER_REVIEW)·카드 Route 등록, resume(개통·Route 재개), 한도 하향·종료, 정산 정책 배정, 정책 배정 종료, Site 심사 REJECTED.
권장안: 등록(UNDER_REVIEW)·Site 심사 REJECTED·한도 하향 = 1인, **resume·한도 종료·정산 정책 배정·배정 종료 = 2인**(효과가 완화 방향이거나 돈의 조건). 대안: 목록에 없는 것은 모두 2인.

**Q-MOPS-18 계약 Revision 등록의 소급 거절(B2 착수 전, 새 후보)** — D-MOPS-006 ⑤는 "계약 효력 시작일"이다. B1은 첫 등록(Revision 1)에만 적용한다.
권장안: `registerContractRevision`에도 같은 규칙(`CONTRACT_START_RETROACTIVE`). 대안: Revision은 소급 허용하고 승인(2인)에서 확인. 영향: B2 Revision 등록 화면·Domain.

## 12. 위험·미결

| ID | 내용 | 처리 |
|---|---|---|
| L-1 | D-MOPS-005: 조회 키 하나가 명령까지 서명. 유출 = MASTER 직접 등록·(B2 이후) 2인 명령 시도 가능 | 사용자 선택으로 수용. 완화: Q-MOPS-16 MASTER 재검사, `command{operation}` 지표 급증 경보, 키 2개 교체 슬롯(`signing-keys[1..2]`) 절차 문서화(LIVE Gate) |
| L-2 | ADMIN 감사 ↔ MASTER 변경 이력 자동 대사 없음 | correlationId·actor로 수동 대조 가능. 대사 job은 필요 시 후속 |
| L-3 | 새로고침·브라우저 종료 뒤 결과 불명 건은 화면에 남지 않는다(submissionId 메모리) | 조회 화면 read-back. Business만 만든 경우(가맹점 0)는 기존 조회 API가 가맹점 중심이라 **사업자만 있는 건을 볼 수 없다** — 사업자 ID 검색은 가맹점 목록을 돌려준다. 필요하면 사업자 조회 endpoint 후속 |
| L-4 | `admin_request.request_fingerprint`는 등록번호를 포함한 무염 SHA-256 | 같은 DB의 `business.registration_number`가 이미 평문이라 추가 노출은 아님. 기록만 |
| L-5 | 등록번호·채널은 Demo 실행마다 새 값 필요(중복 거절) | walk-through 값 규칙 문서화 |
| L-6 | MASTER-DOMAIN-001 §3.3은 "Site 등록은 entitlement revision을 올리지 않는다"고 쓰지만 코드는 올린다(review M-2) | 이 명세는 코드 기준. 문서 정정은 Coordinator 판단 |
| L-7 | 기존 businessId 비노출(D-MOPS-006 ③)이어도 "이미 등록됨" 응답 자체는 그 번호가 있다는 정보다(oracle) | D-MOPS-006 ④대로 모든 시도 감사 + 중복 결과는 등록번호 검색 버킷 소모(C-13). OPERATOR는 이미 등록번호 정확 검색 권한이 있어 새 권한 확대 아님. **기존 businessId 자체도 등록번호 정확 검색(MERCHANT-OPS-001 — 결과 행의 businessId)으로 여전히 알 수 있고, 그 검색과 이 중복 응답은 같은 등록번호 버킷을 쓴다**(review n-2) — D-MOPS-006 ③은 등록 응답·화면·감사에서의 노출만 막는다 |
| L-9 | 등록번호 버킷·등록 버킷은 **인스턴스 메모리**(MERCHANT-OPS-001 L-6과 같음) — ADMIN 여러 인스턴스면 actor별 상한이 인스턴스 수만큼 늘고, 재기동 시 초기화된다(review m-2) | LIVE Gate(공유 저장소 기반 제한과 함께). Demo 단일 인스턴스 |
| L-8 | `CONTRACT_START_RETROACTIVE`는 판정 시각 기준이라 KST 자정 직전 제출은 잠금 대기 뒤 거절될 수 있다 | 의도된 동작(§3.4). 화면 문구로 안내 |

**재개 동작**: Coordinator가 MERCHANT-OPS-002 acceptanceCriteria·allowedWritePaths를 이 명세 §8.1로 등록 → **B1-1부터** TDD(RED 먼저) → Verifier gate → Demo 화면 → B1-2 같은 순서.
Q-MOPS-16·18은 B2, Q-MOPS-17은 B3~B5 착수 전에 묻는다.

## 13. 리뷰·결정 기록

| rev | 날짜 | 누가 | 내용 |
|---|---|---|---|
| 0.1 | 2026-11-08 | Implementer | 초안. B1 상세, B2~B5 개요, C-1~C-11, Q-MOPS-10~17 후보 |
| 0.2 | 2026-11-08 | Implementer(사용자 답 D-MOPS-006, Coordinator C 채택 반영) | Q-MOPS-10~15 답변 반영(§2·§4.4·§5·§6). Q-MOPS-12 **대안 선택** → C-12: MASTER 관리 채널 adapter가 거절 reference를 내보내지 않고 ADMIN도 거부 — `DemoMerchantSeedService`(reference 미사용)·`MerchantOnboardingServiceSpec`/`FirstDefaultIssuanceAtomicitySpec`(Use Case 수준 단언) 확인, 영향 없음. Q-MOPS-14 → §3.4 Domain 규칙 `CONTRACT_START_RETROACTIVE`(`Contract.register`, 판정 시각 `at`의 KST 자정, `SeoulMidnight.startOfDay`) + 시험. C-13(등록번호 버킷 시점) 추가. §8.1 B1-1·B1-2 수용 기준 분리. Q-MOPS-16·17 PENDING 유지, Q-MOPS-18 새 후보(B2 전). L-7·L-8 추가. 코드·빌드·커밋 없음 |
| 0.3 | 2026-11-08 | Implementer(rev 0.2 독립 검토 FAIL 반영 — 사용자 결정 변경 없음) | **M-1** 사업자 등록 감사 brn: 엄격 해석 성공 + 형식 맞는 번호면 403 포함 앞 3자리 마스킹, 해석 실패·형식 위반만 `brn=INVALID`(`MerchantLookupService` 선례) — §4.4 0~2·§6·§8·§8.1 B1-1-5. **M-2** 응답 유실 기준 분리: (a) MASTER 실제 HTTP + MySQL `MerchantOperationsLostResponseSpec`(커밋 후 응답 읽기 전 연결 종료 → 재전송 `replayed=true`, `admin_request`·`business` 각 1행), (b) ADMIN stub은 같은 MASTER 키·감사 쌍만 — §7·§8·§8.1 B1-1-6. m-1 C-14 correlationId = MASTER 키. m-2 C-13 원자적 예약·환불, L-9. m-3 §3.1 nonce 불필요 근거 + KDoc + 창 안 재전송 무변화 시험. m-4 같은 키 동시 1행(B1-1-3). m-5 쓰기 handler 전수 시험(D-MOPS-005 범위). m-6 종료일 문구 + Vitest. n-1 Revision 소급 허용 회귀 시험. n-2 L-7 보강. n-3 §3.4 DB CHECK 없는 이유. 코드·빌드·커밋 없음 |
| 0.4 | 2026-11-08 | Implementer(rev 0.3 재검토 PASS + MINOR 1·NIT 1 반영, Coordinator 결정) | **m-7** C-14 개정: correlationId = `mos.<submissionId>`(재전송 고정·36자·계정 ID 없음, MASTER 키 아님) — 기존 "멱등키 값을 로그에 넣지 않는다" 규칙(`AdminCommandExecutor` KDoc) 유지, MASTER 키 원문은 §6 넣지 않는 값·§7 Observability·§8 로그 시험에 계속 금지. **n-4** submissionId 없는 사전 거절(400 해석 실패·403)의 correlationId = ADMIN 요청 ID(§4.2). 코드·빌드·커밋 없음 |
