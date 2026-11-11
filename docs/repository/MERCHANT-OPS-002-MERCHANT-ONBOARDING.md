# MERCHANT-OPS-002 가맹점 등록·개통 명령 명세 (rev 0.8.4)

- **rev 0.1** (2026-11-08, Implementer — 명세 초안. 코드·Gradle 실행 없음). B1(1인 등록 화면)만 상세 설계, B2~B5는 개요와 미결. 자기 완료 판정 아님(Verifier·Reviewer 별도).
- **rev 0.2** (2026-11-08, Implementer — 사용자 결정 **D-MOPS-006**(Q-MOPS-10~15) 반영, Coordinator가 C-1~C-11 권장안 채택. 코드·Gradle 실행 없음). Q-MOPS-12는 사용자가
  **대안**(기존 businessId 비노출)을 골랐다 → §3.3 C-12(MASTER 관리 채널도 거절 reference를 내보내지 않음), Q-MOPS-14 → 새 거절 `CONTRACT_START_RETROACTIVE`(§3.4).
  B1-1·B1-2 수용 기준(§8.1) 추가. Q-MOPS-16·17은 PENDING 유지. §13 기록.
- **rev 0.3** (2026-11-08, Implementer — rev 0.2 독립 검토 FAIL(MAJOR 2·MINOR 6·NIT 3) 반영, 사용자 결정 변경 없음. 코드·Gradle 실행 없음). 403 감사 brn 마스킹 규칙 정정(M-1), 응답 유실
  시험을 MASTER 실제 HTTP·MySQL과 ADMIN stub로 분리(M-2), correlationId 고정(C-14), 버킷 예약·환불(C-13 개정), nonce 불필요 근거(§3.1), 쓰기 경로 전수 시험, 종료일 문구, Revision 소급 회귀 시험. §13 기록.
- **rev 0.4** (2026-11-08, Implementer — rev 0.3 재검토 PASS의 m-7·n-4 반영): C-14 correlationId = `mos.<submissionId>`(MASTER 키 아님 — 멱등키 로그 금지 규칙 유지), 사전 거절은 ADMIN 요청 ID. §13 기록.
- **rev 0.5** (2026-11-10, Implementer — 사용자 결정 **D-MOPS-007**(Q-MOPS-16~18 모두 권장안) 반영, **B2 상세 설계(§9.1~§9.11, §8.2)**. 코드·Gradle 실행 없음).
  B1은 완료·커밋 `89c6fac`. B2를 Demo 단위 B2-1(Revision 등록 1인 + 소급 거절) → B2-2(MASTER 승인 근거 + 계약 승인 2인) → B2-3(첫 Default MID 2인)으로 나눈다(C-25).
  §8 n-1 회귀 시험은 D-MOPS-007 ② 근거로 **반대 기대**(Revision 소급 거절)로 바뀐다. 새 질문 후보 Q-MOPS-19(승인 근거 참조), 가역 C-15~C-25(권장안 채택 표시). §11·§12·§13 갱신.
- **rev 0.6** (2026-11-10, Implementer — rev 0.5 독립 리뷰 **FAIL**(M-1·M-2·m-1~m-5·n-1~n-8) 수정 + 사용자 결정 **D-MOPS-008**(Q-MOPS-19~21 모두 권장안). 코드·Gradle 실행 없음).
  C-18 대안 채택(MASTER 결정 조회·기한 확인 전용 호출, 회복 순서 PAYMENT·FINANCIAL과 같게 — §9.4·§9.5), 저장되지 않는 응답 매핑·종결(C-28), 근거 참조 필수(V9 열·`mop1` 확정), Revision 등록자≠승인자(C-27),
  Demo seed 진입 관문(C-26), 근거 행 즉시 flush(m-1), 만료 훅 위치·순서(m-4), ORPHAN 대사 후보·LIVE Gate·executed 비교 지표(m-3, L-13). §11 Q-19~21 답변됨, §13 기록.
- **rev 0.6.1** (2026-11-10, Implementer — rev 0.5 리뷰 NIT n-1~n-8 원문 반영, 결정 변경 없음. 코드·Gradle 실행 없음). §2·§9.1 해석 표시, §9.3 FK 없음·키 유도 전제, Revision 번호 정규식(C-30), L-14·L-15, §11.1 지문 출처.
- **rev 0.6.3** (2026-11-10, Implementer — B2-1 Demo 시연 지적 반영, Coordinator 결정 C-32(가역 UI). 정책 변경 없음): Revision 폼 성공 뒤 완료 상태·"승인 전" 표기 통일.
- **rev 0.6.4** (2026-11-10, Implementer — B2-2 MASTER 구현 중 Coordinator 결정 C-33): 기한 확인 응답 모양 = 실행과 같은 §3.3 모양(`NOT_YET_DUE`만 `{"decision":"NOT_YET_DUE"}`). 정책 변경 없음.
- **rev 0.6.5** (2026-11-10, Implementer — B2-2 ADMIN 구현, Coordinator 지시의 작은 정정 2건 + ADMIN 가역 구현 선택 기록. 정책·기대값 변경 없음): `decided_via` NULL은 1048, `EnvironmentDeploymentMode` 위치 adapter/integration. §13 기록.
- **rev 0.7** (2026-11-10, Implementer — **B2-3 첫 Default MID 발급 구현**(MASTER endpoint `first-default-merchant-accounts` + ADMIN 승인 종류 `MERCHANT_FIRST_MID_ISSUANCE`·화면) + B2-2 리뷰 반영(n-1 거울 값 기동 검사, n-2 성공 reference 대조, n-3 Demo 값 LIVE Gate L-16, n-4 주석) + Demo 개선(C-34). 정책 변경 없음). §13 기록.
- **rev 0.7.1** (2026-11-10, Implementer — B2-3 리뷰(REVIEW_PASS 조건부, MAJOR 1) 반영: M-1 근거 경로 원자성 장애 주입, m-1 C-34 REFERENCE_MISMATCH 소음 감소·finding, m-2 MID 행렬, m-3 화면 선택값, NIT, Demo 화면 지적 3건. 정책 변경 없음). §13 기록.
- **rev 0.8** (2026-11-11, Implementer — **B3 개통 상세 설계(§9.13~§9.24, 시험·수용 §8.3)**. 코드·migration·빌드·커밋 없음, 자기 완료 판정 아님). B2 커밋 `1b5538d` 기준 코드 확인.
  B3를 Demo 단위 B3-1(수단 개통 등록 1인·승인 2인 + Site 심사 승인 2인/거절 1인) → B3-2(통화 활성화 2인) → B3-3(카드 Route 등록 1인·승인 2인) → B3-4(재개·Site 재확인 2인 — B5 뒤, C-38)로 나눈다.
  "결제 가능" 시점(§9.19): B3-2 뒤 **결제 생성 판정**까지, **승인 시작은 B4(수수료·정산 배정·한도) 뒤**, 새 MID의 가맹점 API 호출은 Credential 경로 밖(L-17). 새 질문 후보 **Q-MOPS-22~24**(PENDING), 가역 C-35~C-44(권장안 채택 표시).
- **rev 0.8.1** (2026-11-11, Implementer — rev 0.8 독립 리뷰 조건부 PASS 반영, Coordinator 지시. 정책 변경 없음): MAJOR-1 Q-MOPS-24를 "1인 등록자 ≠ 그 대상의 2인 승인자"(Route·결제수단 개통·Site)로 확대,
  C-45(개통 승인 `primarySiteId` 고정), minor-2~7·nit-1·3, C-35 Coordinator 채택(1인 거절 버튼은 숨기지 않음). §13 기록.
- **rev 0.6.2** (2026-11-10, Implementer — rev 0.6.1 재리뷰 FAIL(새 M-1·m-a~m-c·n-a~n-d) 반영, Coordinator 결정 C-31. 코드 변경 없음 — 이 rev 뒤 B2-1 구현 착수).
- 기준: branch `feat/foundation-setup`, B1 커밋 `89c6fac`(rev 0.4까지는 HEAD `1ad6ed8` 기준). 선행: MERCHANT-OPS-001 rev 0.1(DONE — 조회 화면·ADMIN→MASTER 서명 채널), MASTER-DOMAIN-001, MASTER-SNAPSHOT-001/002, DEMO-SEED-001.
- 표기: **사용자 결정** = `docs/DECISIONS.md`. **Q-MOPS-n** = 사용자 질문 후보(미결 — Coordinator 경유, 답 전 해당 운영 동작 구현 금지). **C-n** = Coordinator가 정할 가역 선택(권장안 표시).
  **L-n** = 알려진 위험·한계. rev 0.2부터 Q-MOPS-10~15는 **답변됨**(D-MOPS-006), C-1~C-13은 **채택**(가역). rev 0.5부터 Q-MOPS-16~18 **답변됨**(D-MOPS-007).
- 근거: D-MOPS-001~007, D-ADMIN-007(소유 서비스 재검사), D-CANCEL1-006(소유 DB 거절 감사 선례), D-ACQA-001~005·D-OPS-003·006·007(OWNER_SERVICE 2-TX·회복 job 선례), ACQ-ADMIN-001 B1-A, D-MOPS-001~005, D-MD-001~018, D-MS-002·003, D-MS2-001~005, D-ADMIN-001~008, D-ACQA-001(비교용 — 조작 키 분리 선례), MERCHANT-OPS-001 §3~§5,
  ADMIN-PAYMENT-OPS-001 §4.9~§4.20(소유 서비스 2-TX), MASTER-DOMAIN-001 §2.2·§2.3·§3.1~§3.3·§5, DEMO-SEED-001 §4.2, ARCHITECTURE-CONVENTIONS §14, 테스트 아키텍처 Test Fixture Policy.

## 0. 한눈에

| 배치 | 화면(메뉴 "가맹점 관리") | 명령 | 승인 | 이 문서 |
|---|---|---|---|---|
| **B1** | 새 탭 **"가맹점 등록"** | registerBusiness·registerMerchant·registerMerchantSite·registerContract(초안, Revision 1)·addServiceAgreement | OPERATOR 1인(D-MOPS-004) | **상세 설계(§3~§8)** |
| **B2** | 승인함 새 종류 2개 + 조회 상세 패널 버튼 | registerContractRevision(1인 — B2-1)·approveContractRevision(2인 — B2-2)·issueFirstDefaultMerchantAccount(2인 — B2-3) | 1인 + 2인(D-MOPS-004·007) | **상세 설계(§9.1~§9.11, 시험·수용 §8.2)** |
| **B3** | 상세 패널 "개통" 영역 + 승인함 새 종류 7개 | 개통 등록(1인)·결제수단 개통 승인·Site 적용 확인·Site 심사 APPROVED(2인)/REJECTED(1인)·통화 활성화·카드 Route 개통 승인·재개(2인) | D-MOPS-004·007 ③ | **상세 설계(§9.13~§9.24, 시험·수용 §8.3)** — B3-3은 Q-MOPS-22·24, B3-2는 Q-MOPS-23 답 대기 |
| B4 | 상세 패널 "한도·수수료" | 한도 상향 등록/교체(2인)·한도 하향(1인)·한도 종료(2인)·수수료·정산 정책 배정·배정 종료(2인) | D-MOPS-004·007 ③ | 개요 |
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
| D-MOPS-004 | 원문: 등록 5종(Business·Merchant·Site·계약 초안·CSA)은 **OPERATOR 1인**. 계약 승인·첫 Default MID·결제수단 개통 승인·Site 적용 확인·Site 심사 APPROVED·통화 활성화·카드 Route 개통 승인·한도 상향 등록/교체·수수료 정책 배정은 **2인**(요청자≠승인자). 안전 방향(중지·철회·종료)은 1인 즉시. **해석(원문 아님 — review n-3)**: 2인의 역할·인증 "요청 OPERATOR, 승인 APPROVER + 직전 TOTP"는 D-ADMIN-003·004(2인 승인·승인자 재인증)와 D-ACQA-002(재매입 — 같은 틀) 선례를 적용한 것이다 |
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
- 범위: B1은 **계약 등록(Revision 1)만**. **B2-1부터 `registerContractRevision`에도 같은 규칙**(사용자 결정 D-MOPS-007 ② — Q-MOPS-18 답변, §9.2). 아래 "DB CHECK 없음" 이유는 그대로 유지된다.
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
  답이 오면 이 시험의 기대값은 그 결정 근거로만 바꾼다. → **rev 0.5: 답 도착(D-MOPS-007 ②, 2026-11-10)** — B2-1에서 이 시험(`ContractSpec` "Q-MOPS-18 boundary"·
  `MerchantOnboardingServiceSpec` "Q-MOPS-18 regression")은 **반대 기대**(`CONTRACT_START_RETROACTIVE`, 저장·재생, Revision 행 불변)로 바뀐다(§8.2 B2-1-2). 시험 이름·주석에 결정 ID를 적는다.
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
   `registerContractRevision`은 과거 시작을 여전히 받는다(n-1 회귀 — Q-MOPS-18 경계). *(B1 시점 기준. B2-1에서 D-MOPS-007 ②로 반대 기대 — §8.2 B2-1-2)*
3. Scope: MERCHANT/SITES 둘 다, 다른 가맹점 Site → `SCOPE_SITE_OF_OTHER_MERCHANT`, `scopeSiteIds` 51개 400. 같은 계약 CSA 두 번 → `SERVICE_KIND_DUPLICATE`(reference 없음).
4. ADMIN 날짜 변환(C-7): `2026-11-09` → `2026-11-08T15:00:00.000Z`, 종료일 배타. 감사 2행(시도·결과), targetRef = 계약 ID·CSA ID.
5. 계약 등록·CSA 추가는 권리 revision·Snapshot을 바꾸지 않는다(미승인 — MID 없음).
6. Vitest: ④ Scope 선택(③ Site 체크), 시작일 최솟값 오늘 KST, 종료일 "이 날 0시 종료(미포함)" 문구(m-6), 소급 거절 문구, ⑤ 자동 채움, 완료 패널 "가맹점 조회에서 보기".
7. Demo(Coordinator): ④ 시작일 내일 → ⑤ → 조회 상세에 계약 Revision 1 **미승인**·CSA·MID 0 → 시작일 어제 입력 시 거절 문구. "결제 불가(B2·B3 뒤)" 안내 확인.

### 8.2 B2 시험·수용 기준(배치별 — Verifier 확인 항목, C-25 전달 순서 B2-1 → B2-2 → B2-3)

공통 증거는 §8.1과 같다(exit code·시험 개수·0건/skip 실패·보고서·source fingerprint·시각, 대조군 단언). Fixture 규칙도 §8과 같다: 승인 근거는 이름 있는 Fixture
`ApprovalEvidenceFixtures.distinctActors(approvalId = …)`·`sameActor()`·`windowExceeded()`·`expiredAt(…)`(무효 의도를 이름에), 날짜·창 경계는 명시 값(자동 생성 금지 — 금액·상태 연관값과 같은 이유).
Fixture Monkey는 형식 조합(`apr_`·`adm_` ULID 문자열)에만. Fixture는 `src/test`에만.

**B2-1 계약 Revision 등록(1인) + Revision 소급 거절(D-MOPS-007 ②)**
1. Domain `ContractSpec`: `registerRevision` KST 경계 표 — §8 Revision 1 표와 같은 값(`at` = 2026-11-08T15:00Z에서 `from` = 11-08T15:00Z 허용·11-08T14:59:59.999Z 거절, `at` = 11-08T14:59:59.999Z에서
   `from` = 11-07T15:00Z 허용, 미래 허용). 판정 순서: `CONTRACT_MERCHANT_MISMATCH`가 먼저, 그다음 `CONTRACT_START_RETROACTIVE`. 변이: 비교를 `at`으로 바꾸면 같은 날 시작 허용 사례 RED.
2. **기대 반전(사용자 결정 근거)**: 기존 "Q-MOPS-18 boundary"(`ContractSpec`)·"Q-MOPS-18 regression"(`MerchantOnboardingServiceSpec`)은 **D-MOPS-007 ②**로 기대를 `CONTRACT_START_RETROACTIVE`로 바꾼다
   (이름·주석에 `D-MOPS-007 ②` 기록). 소급 Revision 등록 → 저장된 거절·같은 키 재생·Revision 수 불변·이력/Outbox 0. 그 밖 기존 MASTER 시험(`EntitlementRevisionMatrixSpec`·`MerchantEntitlementServiceSpec`·
   `FirstDefaultIssuanceAtomicitySpec`·`EntitlementSnapshotSpec` — Revision `from` ≥ 명령 시각과 같은 KST 날)은 **기대값 변경 없이** 통과해야 한다(착수 시 실행으로 확인 — 실패하면 Fixture 시각을 고치지 말고 보고).
3. MASTER `contract-revisions` endpoint: B1 채널 행렬(무서명 401·엄격 본문 400·쓰기 permit 503·같은 키 재생·다른 본문 409·C-12 거절 reference 없음) + 성공 reference `ctr_…#<n>`.
   쓰기 handler 전수 집합(m-5) = {`merchant-searches`, B1 5개, `contract-revisions`}.
4. ADMIN `POST /api/merchant-registrations/contract-revisions`: OPERATOR만(그 밖 403 + 감사 `FORBIDDEN`), 날짜 변환 C-7, 감사 2행(`MERCHANT_CONTRACT_REVISION_REGISTER`, targetRef = 계약 ID, 성공 detail `rev=<n>` — C-22),
   응답 모양 표가 이 종류에서만 `ctr_…#n`을 허용(다른 종류에 `#`가 오면 해석 불가 = Unknown), 결과 불명 §5.3과 같은 잠금·같은 submissionId 재전송.
5. Revision 등록은 권리 revision·Snapshot을 바꾸지 않는다(미승인).
6. Vitest: 조회 상세 계약 영역 "새 Revision 등록"(OPERATOR만), Scope·시작일(최솟값 오늘 KST)·종료일 문구, 소급 거절 문구, 성공 뒤 상세 갱신(Revision n **미승인**).
7. Demo(Coordinator): §9.11 B2-1.

**B2-2 MASTER 승인 근거 + 계약 승인(2인)**
1. Domain `ApprovalEvidenceSpec`: 요청자 = 승인자 → `APPROVAL_SAME_ACTOR`, 창 경계(`executeBefore` = `approvedAt` → 거절, 최대 창 정확히 = 허용·+1ms 거절, `approvedAt` = now + skew 허용·+1ms 거절),
   `lockedNow` = `executeBefore` → `APPROVAL_EXPIRED`(배타 끝), −1ms 허용. 판정 순서 §9.3 표 1→2.
   **순서 시험(m-4)**: 기한 지난 근거 + 없는 계약 → `CONTRACT_NOT_FOUND`(잠금 전 업무 거절이 만료보다 먼저), 기한 지난 근거 + 있는 계약·이미 승인된 Revision → `APPROVAL_EXPIRED`(잠금 뒤 훅이 Domain 판정보다 먼저),
   `lockedNow()` 없이 `beginWrites()` → IllegalState·rollback. `ContractSpec`: `approveWithEvidence`에서 Revision 등록자 = 승인자 → `APPROVER_IS_REVISION_REGISTRANT`(근거 없는 `approve`는 같은 행위자 허용 — C-31)(Revision 1 = 계약 등록자, Revision 2 = Revision 등록자), 끝난 Revision은 `REVISION_ENDED`가 먼저, 이미 승인된 Revision보다 먼저(D-MOPS-008 ③).
2. `MerchantApprovalCommandParserSpec`(단위): `idempotencyKey` ≠ `mop1.<approvalId>` 400, `actor` 필드가 오면 400(C-17), `requestedBy`·`approvedBy` 정규형(`admin:adm_<ULID 대문자>`) 위반 400(소문자 ULID 포함),
   `approvalFingerprint` ≠ MASTER 재계산 400, `evidenceRef` 없음·형식 위반(공백·제어문자·129자) 400, 모르는 key·중복 key·`null`·자료형 강제 변환 400, 오류에 값 없음.
   `mop1` 지문 **독립 벡터**(evidenceRef 포함 확정 정규 문자열, ADMIN 시험과 같은 입력·같은 hex — C-23), evidenceRef 한 글자 변경 → 다른 지문.
3. `ApprovedCommandAtomicitySpec`(MySQL — 실제 DB rollback): 성공 시 계약 승인·권리 revision +1·Snapshot·이력·Outbox·`admin_request` 결과·`admin_approval_evidence` EXECUTED 행이 **한 TX**
   (근거 행 쓰기 뒤 장애 주입 → 전부 0, 결과 저장 직전 장애 → 전부 0). 같은 행위자 → `REJECTED`(APPROVAL_SAME_ACTOR) + 근거 REJECTED 행 + 계약 불변. 직접 INSERT로 EXECUTED·같은 행위자 → CHECK 3819.
   같은 approvalId를 다른 operation으로 → `APPROVAL_ID_ALREADY_USED`, 근거 행 추가 0. 같은 바이트 재전송 → `replayed=true`·근거 행 1개. **기한 뒤 재전송**: 이미 실행된 건은 재생 SUCCEEDED(만료로 바뀌지 않음),
   실행된 적 없는 건은 `APPROVAL_EXPIRED` 저장(근거 REJECTED 행, `decided_via = EXECUTE`).
   **결정 조회·기한 확인(C-18 개정)**: 결정 조회는 NONE/DECIDED 모양·쓰기 0(전 표 CHECKSUM 불변); 기한 확인은 기한 전 `NOT_YET_DUE` + **예약 행도 남지 않음**, 기한 뒤 결정 없음 → `APPROVAL_EXPIRED` 저장(`decided_via = DEADLINE_CONFIRM`)·
   업무 본문 대역 호출 0·업무 표 CHECKSUM 불변, 이후 같은 바이트 실행 재전송 → 그 만료 결과 **재생**(실행 안 됨), 결정 있음 → 재생(성공은 성공). 실행 진행 중(예약 행 잠금 보유) 기한 확인 → 503 `REQUEST_IN_PROGRESS`.
   **rev 0.6.2 추가**: 기한 확인 + 같은 행위자 → `APPROVAL_SAME_ACTOR` 저장·근거 행(`DEADLINE_CONFIRM`)·재요청 재생(503 반복 없음), + 창 위반 → `APPROVAL_WINDOW_INVALID` 같음(m-a), 직접 INSERT로 `DEADLINE_CONFIRM` + 다른 code(예: `REVISION_ENDED`) → 3819, `decided_via` NULL → **1048**(열이 NOT NULL이라 CHECK 전에 거부 — rev 0.6.5 정정)·다른 값 → 3819(n-b);
   기한 확인 + 다른 operation에 이미 쓰인 approvalId → `APPROVAL_ID_ALREADY_USED`·근거 행 추가 0(m-b); 기한 전 → `NOT_YET_DUE`·그 키 `admin_request` 0행·재시도 0회(m-c);
   불변식(n-a): 시험 끝에 승인 operation의 `admin_request` 결과 행 전부에 대해 근거 행 1:1(`APPROVAL_ID_ALREADY_USED` 제외) 단언.
   **C-31**: 근거 경로 등록자 = 승인자 → `APPROVER_IS_REVISION_REGISTRANT`, seed 경로(`system:demo-seed`·Demo)는 같은 행위자 등록·승인 그대로 성공(`DemoMerchantSeedServiceSpec` 기대 불변).
   **m-1 실제 MySQL 두 연결**: 같은 approvalId·다른 operation 두 요청 동시 → 하나 SUCCEEDED(근거 EXECUTED 1행), 다른 하나 `APPROVAL_ID_ALREADY_USED`(근거 행 추가 0), 재시도가 `StoreConstraint(businessKey)`로 수렴(커밋 시 예외로 500이 나지 않음).
4. `MasterSchemaSpec`(V9 — CHECK 3819·PK/UNIQUE 1062·형식 CHECK), `MasterDatabaseAccountSpec`(V10 — runtime 계정 `admin_approval_evidence` UPDATE·DELETE 1142).
5. `MerchantApprovalApiSpec`(보안 체인 + MockMvc + MySQL): 무서명 401, 응답 모양, 거절에 reference 없음(C-12), 쓰기 permit 공유 503, 로그·지표에 서명 키·서명·멱등 키 원문 없음(correlationId `mop.<approvalId>`는 허용),
   `paycore.master.admin_channel.approval_rejected{operation,rejection}`(승인 근거 거절 5종만 label)·`approval_executed{operation}`. 쓰기 handler 전수 집합 = {B2-1 집합, `contract-revision-approvals`, `approval-decisions`, `approval-deadline-confirmations`}.
   **근거 없는 Use Case 진입 차단(D-MOPS-008 ②, C-26)**: ArchUnit — 근거 없는 두 메서드의 호출자는 `DemoMerchantSeedService`뿐, 관리 채널 컨트롤러는 근거 있는 명령만.
   Use Case 단위: 행위자 `system:demo-seed` + `isDemo()=true`만 실행, `system:other`·`admin:…`·LIVE 모드(`isDemo()=false`) → IllegalState·TX 없음·행 0. 기존 `DemoMerchantSeedServiceSpec` 기대값 불변.
6. `MerchantApprovalLostResponseSpec`(실제 HTTP + MySQL — M-2a와 같은 방식): 커밋 뒤 응답 읽기 전 연결 종료 → 같은 저장 바이트 재전송(새 `t`·서명) → `replayed=true`, `admin_request`·근거 행·Revision 승인 각 1.
7. `MerchantApprovalConcurrencySpec`(실제 MySQL 두 연결): 같은 Revision을 다른 approvalId 둘이 동시에 → 1 SUCCEEDED, 1 `REVISION_ALREADY_APPROVED`(근거 행 2 — EXECUTED 1·REJECTED 1), 같은 바이트 동시 → 행 1.
8. ADMIN `AdminSchemaSpec`(V9 — 종류·대상 CHECK 3819, `MERCHANT:` 미해결 UNIQUE 1062, `mop1` 지문 CHECK, 명령 행 FK 1452)·`AdminDatabaseAccountSpec`(V10 — 명령 표 SELECT·INSERT만).
9. `MerchantOpsApprovalServiceSpec`: 요청 OPERATOR만(세션 역할 비권위 + TX 안 계정 재검사), **evidenceRef 필수·형식**(없음·공백·130자 400, 감사·로그에 값 없음), 미리 보기 부적격 409 `TARGET_NOT_ELIGIBLE_ADVISORY`(이미 승인·끝난 Revision·없는 Revision), 같은 가맹점 미해결 409 `DUPLICATE_UNRESOLVED`,
   승인 APPROVER + 직전 TOTP·요청자 = 승인자 403(기존 참여자 규칙), **승인자 = pinned registeredBy 403**(OPERATOR·APPROVER 두 역할 계정이 등록한 Revision — 상태·TOTP 불변, 감사 `APPROVAL_DENIED`), **pinned ≠ 현재 → INVALIDATED(TARGET_CHANGED)**·MASTER 미호출, MASTER 조회 실패 → 503·상태 불변·TOTP 미소비,
   근거 보기(APPROVER·요청자 200 + 감사, 그 밖 403, 목록·상세 응답에 evidenceRef 없음),
   TX1 커밋 뒤에만 POST(TX 안 호출 거부), TX2 매핑(SUCCEEDED → EXECUTED + reference, REJECTED → OWNER_REJECTED + code, 409 → 무결성 플래그, Unknown·503·Busy → EXECUTION_UNKNOWN),
   저장 본문 = POST 바이트(재생성 없음), 감사·로그에 MASTER 멱등 키 원문 없음.
10. `MerchantOpsRecoverySpec`(PAYMENT·FINANCIAL 회복 시험과 같은 순서 단언): ① 결정 조회 DECIDED(지문 일치) → 반영·POST 0, 지문 불일치 → 무결성 플래그 먼저 + 반영 + 경보, NONE → 다음 단계, 조회 불가 → UNKNOWN 기록·다음 주기.
    ② 기한 + 여유 뒤 → **기한 확인 호출만**(실행 POST 0) → `APPROVAL_EXPIRED` → OWNER_REJECTED / 재생 성공 → EXECUTED / `NOT_YET_DUE` → 다음 주기.
    ③ 참여자 자격 상실 → 재전송 중단(1회 감사 `APPROVAL_RESEND_HALTED`), 기한 전에는 조회만, 기한 뒤 기한 확인으로 종결. ⑤ 기한 전 실행 재전송은 **같은 저장 바이트**.
    **무결성 플래그 건 수렴**: 실행 409 → 플래그(1회)·실행 POST 중단 → 결정 조회 DECIDED(다른 지문)면 반영·플래그 유지, NONE이면 기한 확인 → 409 → `OWNER_UNRESOLVABLE` finding 1회·경보·EXECUTION_UNKNOWN 유지(자동 종결 없음).
    **400 지속**: stub MASTER가 실행·기한 확인 모두 400 → 첫 400에 재전송 중단(`OWNER_MALFORMED` 1회), 이후 실행 POST 0, 조회는 backoff(상한 PT30M)로 계속, 기한 + 여유 뒤 기한 확인 400 → `OWNER_UNRESOLVABLE` finding 1회·경보,
    상태 EXECUTION_UNKNOWN 유지(OWNER_REJECTED·EXECUTED로 바뀌지 않음), N주기 돌려도 POST 총수 상한 고정(무한 재전송 없음). 401·404 → UNKNOWN·경보·다음 주기(중단 아님). 413 → 400과 같음.
11. `MerchantApprovalFlowHttpSpec`(실제 로그인 세션 + MySQL + stub MASTER): OPERATOR 요청 → APPROVER TOTP 승인 → EXECUTED, **응답 유실**(stub 첫 요청 read timeout → EXECUTION_UNKNOWN → job 재전송 →
    stub이 받은 두 본문 바이트 동일·서명/`t`만 다름 → EXECUTED), CSRF 없는 POST 거부, VIEWER 403.
12. Vitest: 승인함 종류 라벨·상세(Scope·KST 날짜·"0시 종료(미포함)"·**Revision 등록자**, 본인이 등록자면 승인 버튼 대신 안내)·"근거 보기"(APPROVER·요청자만)·결과 문구(거절 사유별·결과 확인 중),
    조회 상세 "승인 요청"(OPERATOR·미승인·끝나지 않은 Revision만, 근거 참조 필수 입력)·승인 대기 표시·Revision 등록자 표시.
13. Demo(Coordinator): §9.11 B2-2.

**B2-3 첫 Default MID 발급(2인)**
1. `first-default-merchant-accounts` endpoint가 B2-2 1~7과 같은 행렬(종류만 다름 — Revision 등록자 규칙은 계약 승인에만, MID 발급에서는 등록자 = 승인자여도 통과함을 시험으로 고정). 성공 reference `mid_…`.
   결정 조회·기한 확인이 이 operation도 처리. 쓰기 handler 전수 집합 = {B2-2 집합, `first-default-merchant-accounts`} — 승인·개통·한도·정지(B3~B5) 경로 없음.
2. 원자성: MID·Default 지정·PRIMARY 연결·권리 revision·Snapshot·이력·Outbox·`admin_request`·근거 EXECUTED 행이 한 TX(기존 `FirstDefaultIssuanceAtomicitySpec`에 근거 행 단언 추가 — 기존 단언은 바꾸지 않는다).
3. 경합: 같은 가맹점에 다른 approvalId 둘 동시 → 1 `mid_…`, 1 `DEFAULT_ALREADY_ISSUED`(merchant 잠금 직렬화), MID 1개.
4. ADMIN: 요청 미리 보기 적격(MID 0·승인된 적용 Revision·그 계약 CSA·Scope 안 ACTIVE Site), pinned 비교·TX2 매핑·회복은 B2-2와 같은 시험에 종류 행 추가.
5. Vitest: "첫 MID 발급 요청" 버튼 표시 조건, 선택 폼(CSA·Revision·PRIMARY Site), 승인 뒤 상세에 MID 1·Default·PRIMARY.
6. Demo(Coordinator): §9.11 B2-3. **결제는 여전히 불가**(결제수단 개통·Site 적용 확인 등은 B3) 안내 확인.

### 8.3 B3 시험·수용 기준(rev 0.8 — 배치별 Verifier 확인 항목, C-38 전달 순서 B3-1 → B3-2 → B3-3 → (B5) → B3-4)

공통 증거는 §8.1·§8.2와 같다(exit code·시험 개수·0건/skip 실패·보고서·source fingerprint·시각·대조군 단언). **RED 먼저**: 각 배치 첫 커밋 전 아래 1·2번(parser·근거 경로)과 새 handler 전수 집합 단언이 실패하는 보고서를 남긴다.
Fixture: 개통 상태는 이름 있는 Fixture(`ActivationFixtures.underReview(mid)`·`active(evidence)`·`suspended(…)`, `SiteReviewFixtures.pending/approved/suspended/rejected`, `RouteFixtures.underReview(acq, route, imr)`), 승인 근거는 §8.2 Fixture 재사용,
기간·시각 경계는 명시 값. Fixture Monkey는 ID 형식 조합에만. 기존 시험이 근거 없는 B3 메서드를 부르는 곳은 seed 경로 행위자(`OnboardingFixture.seedRequest()`)로 **준비 경로만** 바꾼다(B2-2 선례) — 이력 actor·결과 기대값이 바뀌는 단언이 있으면 고치지 말고 보고(C-40).

**B3-1 수단 개통 등록(1인)·개통 승인(2인) + Site 심사 승인(2인)/거절(1인)**
1. MASTER parser(단위): `payment-method-activations`(1인 B1 공통 필드), `payment-method-activation-approvals`(명령 필드 `activationId`·`primarySiteId` — C-45)·`site-review-approvals`(근거 공통 9 + **`operation` 필드 = 경로의 operation** — C-39, 다르면 400), `site-review-rejections`(1인 — **`decision` 필드가 오면 400**, C-40).
   `mop1` 독립 벡터(operation `ApprovePaymentMethodActivation`·`DecideSiteReview`, Python 계산 hex를 MASTER·ADMIN 양쪽 시험에), 명령 필드 한 글자 변경 → 다른 지문. `reasonCode` 허용 목록 밖 400(C-37).
   **기한 확인 교차 수용 금지(minor-4)**: B3 본문의 `operation`이 다른 B3 operation이면 400, B3 본문(`operation` 있음)을 B2 모양 해석기가 받지 않고 B2 본문(`operation` 없음)을 B3 해석기가 받지 않음 — 한 본문은 정확히 한 해석기만 수용(단언).
2. 근거 경로 Use Case(MySQL — `ApprovedCommandAtomicitySpec` 확장, 기존 단언 불변): 개통 승인 성공 시 Activation ACTIVE·PRIMARY Site 적용 확인 1행·전이 이력·권리 revision +1·Snapshot issue·이력·Outbox·`admin_request`·근거 EXECUTED 행이 **한 TX**
   (근거 행 쓰기 직후·결과 저장 직전 장애 주입 → 전부 0, 주입 지점을 하나씩 끄는 변이 RED 기록). Domain `evidence_ref` = 승인 근거 `evidenceRef`(C-36 — 두 열 값 동일 단언). Site 심사 승인도 같은 행렬(심사 결정 행 seq +1·`site_…#n`).
   같은 행위자 → `APPROVAL_SAME_ACTOR` 저장·업무 불변, 기한 지난 근거 + 잠금 뒤 → `APPROVAL_EXPIRED`, 잠금 전 거절(`ACTIVATION_NOT_FOUND`·`SITE_NOT_FOUND`)이 만료보다 먼저(m-4 순서 고정), 결정 조회·기한 확인이 두 operation을 처리(본문 `operation`으로 분기 — C-39).
   불변식 n-a(승인 operation 결과 ↔ 근거 행 1:1)를 새 operation까지 단언. **C-45**: 실행 시점 열린 PRIMARY ≠ 본문 `primarySiteId`(또는 PRIMARY 없음) → `PRIMARY_SITE_MISMATCH` 저장(근거 REJECTED 행·Activation 불변).
3. **채널 분리(C-40)**: 1인 `site-review-rejections`로는 APPROVED·SUSPENDED를 만들 수 없음(Use Case `rejectSiteReview`가 REJECTED 고정), 2인 `site-review-approvals`는 APPROVED 고정. ArchUnit: 근거 없는 `approvePaymentMethodActivation`·`decideSiteReview`·`enableCurrency`·`approveCardRouteActivation`·`confirmSiteActivation`·두 resume의 호출자는 `DemoMerchantSeedService`뿐,
   관리 채널 컨트롤러는 근거 있는 메서드·1인 등록·`rejectSiteReview`만. Use Case 단위: 그 메서드들에 `system:other`·`admin:…`·LIVE 모드 → IllegalState·TX 없음·행 0. `DemoMerchantSeedServiceSpec`·`DemoSeedSnapshotGoldenSpec` 기대값 불변.
4. 경합(실제 MySQL 두 연결, 3회): 같은 Activation에 다른 approvalId 둘 → 1 SUCCEEDED, 1 `ACTIVATION_TRANSITION_NOT_ALLOWED`(근거 EXECUTED 1·REJECTED 1, PRIMARY 확인 1행); 같은 가맹점의 개통 승인과 Site 심사 승인 동시 → 둘 다 성공·merchant 잠금 직렬화·revision +2·Snapshot 2건(시험 단언은 결과·revision·Snapshot 수 — `data_lock_waits` 관찰은 하지 않는다);
   같은 MID 개통 등록 두 submissionId 동시 → 1 `act_…`, 1 `ACTIVATION_DUPLICATE`(**merchant 잠금 대기 뒤 `findOpen` 판정** — `MerchantOnboardingService.registerPaymentMethodActivation`; 생성 열 UNIQUE는 보조 방어선이라 정상 경로에서 1062를 거치지 않는다. 시험 `ActivationConcurrencySpec` ③의 단언 = 결과 집합 {SUCCEEDED, ACTIVATION_DUPLICATE}·거절 응답에 `reference` 키 없음(C-12)·Activation 1행).
   **1인 거절과 2인 승인이 같은 Site에 동시(minor-2)** → 정확히 하나만 결정 행(seq 1), 다른 하나 `SITE_REVIEW_TRANSITION_NOT_ALLOWED`(APPROVED→REJECTED·REJECTED→APPROVED 모두 불허), 승인이 졌으면 근거 REJECTED 행.
5. 응답 유실(실제 HTTP + MySQL): **새 handler 4개 전부**(`payment-method-activations`·`payment-method-activation-approvals`·`site-review-approvals`·`site-review-rejections` — minor-3) 각각 커밋 뒤 연결 종료 → 같은 저장 바이트 재전송 `replayed=true`, 행 각 1.
6. `MasterSchemaSpec`(V11 — 근거 operation CHECK 9값 허용·그 밖 3819). 쓰기 handler 전수 집합 = {B2-3 집합, `payment-method-activations`, `payment-method-activation-approvals`, `site-review-approvals`, `site-review-rejections`} — 집합 갱신 근거는 이 항목(§13 rev 0.6.3 선례, 기대 완화 아님).
7. MASTER 상세(jOOQ): 계정별 `siteConfirmations[{confirmationId, merchantSiteId, paymentMethod, activationId, confirmedAt, revoked}]` 추가, `evidence_ref`·`reason` 열은 SELECT 목록에 없음(기존 비노출 규칙 시험 확장).
8. ADMIN: `AdminSchemaSpec`(V11 — 새 종류·대상·`command_kind` CHECK 3819, **미해결 키 C-35**: 같은 그룹 1062·다른 그룹 공존·B2 두 종류 `MERCHANT:<id>` 의미 불변), 1인 등록 서비스(OPERATOR만·감사 2행·응답 모양 `act_…`·`site_…#n`은 이 종류에만),
   `MerchantOpsApprovalServiceSpec` 종류 행(evidenceRef 필수·미리 보기 부적격 409·pinned ≠ 현재 → INVALIDATED·MASTER 조회 실패 503·pinned `registeredBy` 표시), `MerchantOpsRecoverySpec` 종류 행(결정 조회 → 기한 확인 `operation` 본문 → 재전송 순서), `MerchantApprovalFlowHttpSpec` 종류 행(stub 응답 유실 → 같은 바이트).
   **`MasterResponseShape`(minor-4)**: 결정 조회 응답의 `operation` 허용 목록에 B3-1 두 operation 추가, operation별 reference 문법(`ApprovePaymentMethodActivation` → `act_`, `DecideSiteReview` → `site_…#n`), 목록 밖 operation·문법 불일치는 해석 불가(Unknown — fail-closed).
9. Vitest: 상세 "개통" 영역 버튼 표시(역할·상태별), Site 거절 확인 대화상자("되돌릴 수 없음 — 새 Site로 재신청"), 개통 점검표 "참고(권위 아님)" 표기(C-43), 승인함 종류 라벨·pinned 상세.
10. Demo(Coordinator): §9.24 B3-1.
11. **DONE 증거(minor-7)**: 바뀐 기존 시험 파일 목록과 파일별 바뀐 단언(준비 호출의 행위자만 seed로 바꾼 곳 vs 기대값을 바꾼 곳 — 후자는 0이어야 함), actor 단언 변경 여부(이력 `actor_ref` 단언이 있는 시험), Q-MOPS-24 답 전 **2인 승인 경로의 등록자 검사 미구현** 상태 표시(pinned·화면 표시만).

**B3-2 통화 활성화(2인)** — Q-MOPS-23 답 뒤
1. parser `currency-enablements`: `currency` = `KRW`만(C-41 — 그 밖 400, ADMIN 값 객체도 같음), `effectiveFrom`은 Q-MOPS-23 답의 형식, `effectiveTo` 선택. `mop1` 벡터(`EnableCurrency`).
2. 원자성·근거 행·같은 행위자·만료·결정 조회·기한 확인 = B3-1 2와 같은 행렬. Domain 거절 `CURRENCY_ENABLEMENT_RETROACTIVE`(효력 시작 < 잠금 뒤 시각 — 승인 대기 중 시작 시각이 지난 경우 포함)·`CURRENCY_ENABLEMENT_OVERLAP` 저장.
3. 경합: 같은 (MID, KRW) 다른 approvalId 둘 → 1 `cur_…`, 1 `CURRENCY_ENABLEMENT_OVERLAP`(merchant 잠금 아래 Domain 중첩 판정 — 열린 기록 생성 열 UNIQUE는 보조 방어선). 쓰기 handler 집합 + `currency-enablements`.
4. Snapshot 내용(골든 확장): 새 MID의 통화 구간이 Snapshot에 들어가고 Outbox HIGH 1건(전파 경로 §9.19). ADMIN·Vitest 종류 행. Demo §9.24 B3-2.

**B3-3 카드 Route 등록(1인)·승인(2인)** — Q-MOPS-22·24 답 뒤
1. parser `card-route-activations`(1인 — `acquirerCode`·`routeRef` 형식 `[A-Z0-9]{1,32}`, `institutionMerchantRef` `[A-Za-z0-9._:-]{1,64}`)·`card-route-activation-approvals`(2인, `ApproveCardRouteActivation` 벡터).
2. **비노출(D-MS2-005)**: 기관 가맹점 참조 원문이 ADMIN DB 모든 표·ADMIN 감사·로그(appender 포획)·지표·오류 응답·MASTER 로그·Outbox 통합 이벤트에 없음, MASTER는 `card_route_activation`·변경 이력 detail·Snapshot에만(기존 단언 확장). 관리 조회 표시는 Q-MOPS-22 답의 마스킹 형식.
3. Q-MOPS-24 답이 권장안이면(B3-1 Activation·Site 행은 B3-1 DONE 전 같은 방식): 근거 경로 Route 등록자 = 승인자 → `APPROVER_IS_ROUTE_REGISTRANT`(Domain `CardRouteActivation.approveWithEvidence`, 같은 MID·MID ACTIVE 확인 뒤·상태 전이 판정 앞 — 순서 시험) + ADMIN TX1 403, seed 경로는 검사 없음(C-31과 같은 구조).
4. 경합: 같은 (MID, CARD, 매입사) 등록 동시 → 1 `cra_…`, 1 `CARD_ROUTE_DUPLICATE`; 같은 Route 승인 둘 → 1 성공·1 `CARD_ROUTE_TRANSITION_NOT_ALLOWED`. 응답 유실·원자성 = B3-1과 같은 행렬. 쓰기 handler 집합 + 2.

**B3-4 재개(수단·Route)·Site 적용 재확인(2인)** — B5 1인 중지·철회 뒤(C-38)
1. `payment-method-activation-resumptions`·`card-route-activation-resumptions`·`site-activation-confirmations`: 근거 행렬 = B3-1 2. 재개 근거가 과거 근거와 같으면 `ACTIVATION_EVIDENCE_NOT_NEW`·`CARD_ROUTE_EVIDENCE_NOT_NEW` 저장(C-36 — 승인 근거 = 재개 기관 근거).
   재개 시 기존 Site 확인 유지(D-MS-003 — 확인 행 추가 0). 재확인: UNDER_REVIEW 중 PRIMARY → `PRIMARY_CONFIRMED_BY_APPROVAL`, 유효 확인 있음 → `SITE_CONFIRMATION_DUPLICATE`, 열린 Assignment 없음 → `ASSIGNMENT_NOT_OPEN`.
2. 쓰기 handler 집합 + 3. ADMIN·Vitest 종류 행. Demo §9.24 B3-4.

## 9. B2 상세 설계(rev 0.6 — D-MOPS-004·005·007·008, D-ADMIN-007, D-DEMO-001·005, C-11·C-15~C-32)

### 9.1 범위와 단계

| 단계(C-25) | 명령 | 승인 | 화면 | MASTER 변경 | ADMIN 변경 |
|---|---|---|---|---|---|
| **B2-1** | registerContractRevision | OPERATOR 1인 — **해석**(review n-4): D-MOPS-004 "계약 초안"을 Revision 추가에도 적용(승인 전에는 권리 효과 없음 — 미승인 Revision은 권리 계산·Snapshot에 쓰이지 않음, D-MOPS-007 ③ "UNDER_REVIEW로 남는 등록 = 1인"과 같은 방향) | 조회 상세 "새 Revision 등록" | Domain 소급 거절 확장(§9.2) + endpoint 1 | 화면 API 1 + 감사 code 1 |
| **B2-2** | approveContractRevision | 2인(요청 OPERATOR·승인 APPROVER + 직전 TOTP, 요청자≠승인자) | 승인함 종류 `MERCHANT_CONTRACT_APPROVAL` + 상세 "승인 요청" | 승인 근거 표 V9/V10·실행기 확장·endpoint 3(실행·결정 조회·기한 확인 — §9.3·§9.4)·상세 조회 `registeredBy` | 승인 종류·V9/V10·OWNER_SERVICE 2-TX·회복(§9.5) |
| **B2-3** | issueFirstDefaultMerchantAccount | 2인(같은 틀) | 승인함 종류 `MERCHANT_FIRST_MID_ISSUANCE` + 상세 "첫 MID 발급 요청" | endpoint 1 | 종류 행 추가 |

포함(rev 0.6): 업무 근거 참조 필수(D-MOPS-008 ①), Revision 등록자≠승인자(D-MOPS-008 ③), MASTER 쓰기 효과 없는 결정 조회·기한 확인 전용 호출(C-18 개정 — PAYMENT·FINANCIAL 회복 선례).
제외: B3~B5 명령(§9.12), Demo seed 동작 변경(D-MOPS-008 ② — 경로 유지, 진입 관문만 강화 C-26), 키 분리(D-MOPS-005 — 사용자 선택 유지), 자동 대사 job(L-2·m-3 — 후보·LIVE Gate).
Kafka/Redis 없음: 미해결 승인 하나는 ADMIN UNIQUE, 최초 실행은 MASTER `admin_request` UNIQUE(operation, key)·근거 PK(approvalId)·업무 잠금(merchant·contract 행)이 지킨다. Redis lock·Kafka 전달은 이 보장을 대신하지 못한다.

### 9.2 B2-1 계약 Revision 등록(1인)

- **Domain**(D-MOPS-007 ②): `Contract.registerRevision(scope, period, at, by)`에서 `CONTRACT_MERCHANT_MISMATCH` 판정 **뒤** `period.from < SeoulMidnight.startOfDay(at)`이면
  `MasterRuleViolation(CONTRACT_START_RETROACTIVE)`(§3.4와 같은 함수·같은 경계). `at`은 서비스가 contract 잠금 뒤 읽은 `lockedNow()`(기존). 당사자 비활성 `BUSINESS_NOT_ACTIVE`는 서비스가 먼저 판정(기존 순서 유지).
  새 거절 code 없음(기존 값 재사용 — 영속 이름 불변). DB CHECK 없음(§3.4 n-3 이유 그대로).
- **MASTER** `POST /internal/v1/admin/merchant-operations/contract-revisions`(B1 채널·공통 필드·엄격 해석 그대로): `contractId`, `scopeMerchantId`, `scopeSiteIds`(≤ 50), `effectiveFrom`, `effectiveTo`(선택)
  → `RegisterContractRevisionCommand` → 성공 reference **`ctr_<ULID>#<n>`**(기존 Use Case 반환값). 거절 reference 없음(C-12).
- **ADMIN** `POST /api/merchant-registrations/contract-revisions` 본문 `submissionId, contractId, scopeMerchantId, scopeSiteIds[], effectiveFromDate, effectiveToDate?`(C-7 날짜 변환).
  판정 순서 §4.4 0~7(등록번호 단계 없음), OPERATOR만, `MasterCommandKind.REGISTER_CONTRACT_REVISION("contract-revisions", …)`, 키 `mo1.<계정 ULID>.<submissionId>`·correlationId `mos.<submissionId>`(C-4·C-14 그대로).
  응답 모양 표: 이 종류의 SUCCEEDED reference만 `^ctr_[0-9A-HJKMNP-TV-Z]{26}#[1-9][0-9]{0,9}$` + 값 ≤ 2147483647 허용.
- 감사 `MERCHANT_CONTRACT_REVISION_REGISTER`(시도·결과 2행), targetRef = **계약 ID**(성공도 — `#n`은 `TARGET_REF` 형식 밖), 성공 detail에 `rev=<n>` 추가(C-22). 자유 문구 없음.
- 화면: §9.6. 권리 영향: 없음(미승인 Revision은 권리 계산에 쓰이지 않음 — 기존 Use Case가 Snapshot을 발행하지 않는다).

### 9.3 MASTER 승인 근거 공통 계약(B2-2·B2-3 — D-MOPS-007 ①, D-ADMIN-007)

**본문 공통 필드**(승인 명령 endpoint 전용 — B1 공통 필드와 다름):

| 필드 | 형식 | 의미 |
|---|---|---|
| `idempotencyKey` | **정확히 `mop1.<approvalId>`**(35자) — 다르면 400 | 승인 하나 = MASTER 명령 하나(C-17) |
| `correlationId` | `mop.<approvalId>` | 로그·이력·Outbox 상관 ID(approvalId는 ADMIN 감사에 이미 있는 비밀 아닌 값. 키 원문 자체는 여전히 로그 금지). **전제(review n-5)**: 이 값에서 멱등 키 `mop1.<approvalId>`를 유도할 수 있다 — 멱등 키는 비밀이 아니며, 명령 효과에는 서명(D-MOPS-005 키)이 필요하다는 전제로 수용한다 |
| `approvalId` | `^apr_[0-9A-HJKMNP-TV-Z]{26}$` | ADMIN 승인 ID |
| `requestedBy`·`approvedBy` | 정규형 `^admin:adm_[0-9A-HJKMNP-TV-Z]{26}$`(대소문자 변형 거부 — 문자열 비교가 신원 비교가 되게) | 요청자·승인자 참조 |
| `approvedAt`·`executeBefore` | `…Z` ms | 승인 시각(ADMIN 시계)·실행 기한(배타) |
| `evidenceRef` | `^[A-Za-z0-9._:#/-]{1,128}$`(재매입 `evidence_ref`와 같은 형식, 자유 문구 불가) — **필수** | 업무 근거 참조(계약서 번호 등 — D-MOPS-008 ①) |
| `approvalFingerprint` | `^mop1:[0-9a-f]{64}$` — MASTER 재계산과 다르면 400 | ADMIN 실행 행 지문 = MASTER 근거 지문(대조용 — C-23) |

`actor` 필드는 **없다**(오면 400): MASTER가 `ActorRef(approvedBy)`로 정한다(실행자 = 승인자 — ADMIN `executorActorId`와 같음, C-17). 형식 위반은 400 `REQUEST_MALFORMED`(저장 안 함 — Use Case 호출 전).

**`mop1` 지문**(C-23): UTF-8 `"\n"` 연결 줄의 SHA-256 소문자 hex — `mop1`, operation code(`ApproveContractRevision`·`IssueFirstDefaultMerchantAccount`), approvalId, requestedBy, approvedBy,
approvedAt epoch ms, executeBefore epoch ms, **evidenceRef**(D-MOPS-008 ①), 그다음 명령 필드(§9.4 표 순서). correlationId는 넣지 않는다(내용 아님). 이 줄 순서가 **확정 정규 문자열**이다(rev 0.6). 두 앱이 코드 공유 없이 같은 **독립 벡터**로 시험한다(acd1 선례).

**저장되는 판정**(MASTER 로컬 TX 하나 — 실행기 `executeApproved`, 멱등 기록 예약 뒤·업무 본문 순서):

| # | 판정 | 거절(`MasterRejection` 끝에 추가 — 영속 이름) | 판정 위치·시각 | 근거 행 |
|---|---|---|---|---|
| 1 | requestedBy = approvedBy | `APPROVAL_SAME_ACTOR` | Domain `ApprovalEvidence.admit` | REJECTED 행 |
| 2 | `executeBefore ≤ approvedAt`, `executeBefore − approvedAt > max-execution-window`, `approvedAt > now + max-clock-skew` | `APPROVAL_WINDOW_INVALID` | Domain(`now` = 예약 시각) | REJECTED 행 |
| 3 | 같은 approvalId의 근거 행이 이미 있음(다른 operation — 같은 operation이면 같은 키라 재생/409) | `APPROVAL_ID_ALREADY_USED` | Application(Store 조회) | **없음**(PK 충돌) — `admin_request` 거절 + WARN + 지표 |
| 4 | 업무 잠금 뒤 `lockedNow ≥ executeBefore` | `APPROVAL_EXPIRED` | 실행기 훅(아래 m-4) — 잠금 뒤 시각 | REJECTED 행 |
| 5 | (계약 승인, **근거 있는 경로만** — C-31) Revision의 `registeredBy` = evidence.approvedBy | **`APPROVER_IS_REVISION_REGISTRANT`**(새 값 — D-MOPS-008 ③) | Domain `Contract.approveWithEvidence(revisionNo, at, approver)` 안, `REVISION_ENDED` 판정 뒤·`REVISION_ALREADY_APPROVED` 앞. 근거 없는 `Contract.approve`(seed)는 이 검사를 하지 않는다(D-MOPS-008 ②·D-DEMO-005 예외). Domain은 행위자 이름을 모른다(하드코딩 금지 — 경로 구분은 Application이 어느 메서드를 부르는지로) | REJECTED 행 |
| 6 | 기존 업무 규칙(REVISION_ALREADY_APPROVED·REVISION_ENDED·DEFAULT_ALREADY_ISSUED·REVISION_NOT_APPLICABLE 등) | 기존 값 | Domain(기존) | REJECTED 행 |
| — | 성공 | — | — | **EXECUTED 행**(쓰기 단계, 업무 변경·이력·Outbox·Snapshot·결과와 같은 TX) |

- 1·2를 400이 아닌 **저장된 거절**로 두는 이유: D-ADMIN-007 "자기 감사에 기록"·D-CANCEL1-006 선례(소유 DB 거절 감사 행). 같은 행위자 시도는 ADMIN 승인함을 우회한 호출(D-MOPS-005 키 유출) 신호라 MASTER에 증거가 남아야 한다. 지표 `paycore.master.admin_channel.approval_rejected{operation,rejection}` — `APPROVAL_SAME_ACTOR`·`APPROVAL_ID_ALREADY_USED`는 경보 후보.
- 거절 근거 행도 남긴다(C-15). 동시 경합(§8.2 B2-2-7)에서 PK 충돌은 기존 실행기의 "업무 유일키 충돌 → 새 TX 재판정" 규칙으로 3번 판정에 수렴.
- **만료 판정 위치(m-4)**: `executeApproved`의 `Work.lockedNow()`가 시각을 읽은 **직후** 실행기 훅이 `evidence.requireNotExpired(now)`를 부른다(본문 코드가 빠뜨릴 수 없게 — 본문은 훅을 직접 부르지 않는다).
  **예외(의도된 순서)**: `lockedNow()` 전에 던지는 업무 거절(`CONTRACT_NOT_FOUND`·`REVISION_NOT_FOUND` — 잠금 대상을 찾는 단계, MID의 `MERCHANT_NOT_FOUND` 등)은 만료보다 **먼저** 저장된다. 둘 다 무효과 거절이라 안전 차이는 없고, 순서는 시험으로 고정한다(§8.2 B2-2-1).
  `lockedNow()`를 부르지 않고 쓰기 단계로 가는 본문은 결함 — `beginWrites()`가 "만료 판정 없음"이면 IllegalState(TX rollback, 시험).
- **Revision 등록자 검사(D-MOPS-008 ③)**: 저장 위치는 **기존** `contract_revision.registered_by`(V1, `ContractRevision.registeredBy` — 새 migration 열 불필요, 확인 완료). Revision 1의 등록자는 계약 등록자(B1 `registerContract` actor)다.
  비교는 정규형 문자열 그대로(`admin:adm_<ULID>`). 첫 MID 발급에는 적용하지 않는다(결정 범위가 "그 Revision을 승인").
- **기한 경과 뒤 실행 요청**: 실행 endpoint는 결과가 있으면 재생, 없으면 4번으로 `APPROVAL_EXPIRED`를 저장한다(늦게 도착한 정상 요청의 안전한 종결). 그러나 ADMIN 회복은 기한 뒤 **실행 요청을 보내지 않는다** —
  기한 확인 전용 호출(§9.4)을 쓴다(review M-2, D-OPS-006·009 취지: 기한 뒤에는 실행 경로를 타지 않는다).
- **MASTER가 검증할 수 없는 것(L-10)**: 서명 principal은 kid뿐이라 requestedBy·approvedBy가 실제 ADMIN 계정·실제 TOTP 승인인지는 모른다. 재검사는 형식·서로 다름·창·1회 사용·업무 규칙까지다. 키가 유출되면 서로 다른 두 `adm_` 값을 지어내 통과할 수 있다(D-MOPS-005 위험 — 사용자 수용). 근거 행·ADMIN 승인 행 대사로 사후 탐지(L-2·L-10).

**구조**(ARCHITECTURE-CONVENTIONS §14):
- Domain `master.merchant.domain.ApprovalEvidence`(순수 — approvalId, requestedBy/approvedBy `ActorRef`, approvedAt, executeBefore, fingerprint; `admit(now, window)`·`requireNotExpired(lockedNow)`), `ApprovalWindow`(최대 창·skew — 설정에서 Application으로 전달).
- Application: `AdminCommandExecutor.executeApproved(operation, request, evidence, fingerprintFields, body)` — 기존 `execute`는 바꾸지 않는다(B1·seed 경로 불변). fingerprintFields에 근거 6필드 포함(같은 키·다른 근거 = 409).
  새 명령 `ApprovedContractRevisionApprovalCommand(request, evidence, contractId, revisionNo)`·`ApprovedFirstDefaultIssuanceCommand(request, evidence, …)` — Use Case `MerchantOnboardingUseCase`에 메서드 2개 추가.
  결정 조회·기한 확인 Use Case `ApprovalDecisionUseCase`(`decision(approvalId)`·`confirmDeadline(operation, request, evidence, fields)` — §9.4).
- **Demo seed 근거 없는 경로(D-MOPS-008 ②, D-DEMO-001·005 — C-26)**: 기존 `approveContractRevision`·`issueFirstDefaultMerchantAccount`(근거 없음)는 유지하되 Use Case 진입에서
  ① 행위자가 **정확히 `system:demo-seed`**, ② required Port `DeploymentModePort.isDemo()`가 true일 때만 실행한다(아니면 결함 IllegalState — TX 없음, 감사 WARN). Port 구현은 **별도 adapter bean** `EnvironmentDeploymentMode`(**adapter/integration** — rev 0.6.5 정정: adapter/batch는 ArchUnit `batchAdapterUsesOnlyApplication`이 required Port 구현을 막는다)로, 기존 `DemoCommandModeGate.isDemo(environment)`(EnvironmentPostProcessor의 정적 함수)에 위임한다 — 후처리기 자체는 bean이 아니므로 Port 구현이 될 수 없다(review n-d).
  운영(LIVE) 차단: 설정 관문(LIVE 기동 시 seed 거부 — 기존)·ArchUnit(근거 없는 두 메서드의 호출자는 `DemoMerchantSeedService`뿐, 관리 채널 컨트롤러는 근거 있는 명령만)·handler 전수 시험.
- 기록 저장 Port `master.merchant.application.required.ApprovalEvidenceStore`(`find(approvalId)`·`record(…)`), 구현 `JpaApprovalEvidenceStore` + `JpaApprovalEvidenceEntity` + `ApprovalEvidenceJpaRepository`(adapter/persistence).
  `record`는 `persist` 뒤 **즉시 `flush`**하고 PK/UNIQUE 위반을 `StoreConstraint(businessKey = true)`로 번역한다(review m-1 — 실행기의 업무 유일키 재시도 경로로 들어가 새 TX에서 판정 3으로 수렴).
- 설정 `paycore.master.admin-channel.approval.max-execution-window`(Demo `PT24H`)·`max-clock-skew`(Demo `PT1M`) — **기본값 없음**(없으면 기동 실패), Demo 값 — 운영값 아님.

**MASTER migration**(C-21 — 최신 schema V7·privilege V6, V8은 비움):
- `V9__admin_approval_evidence.sql` — 표 `admin_approval_evidence`(INSERT-only):
  `approval_id VARCHAR(30) PK`, `operation VARCHAR(48)`(CHECK 두 값), `request_record_id VARCHAR(64) UNIQUE`(**FK 없이 UNIQUE만** — 실제 V1 `admin_request.request_record_id`는 UNIQUE가 아니라 FK 대상이 될 수 없음, review n-2 확인),
  `requested_by`·`approved_by VARCHAR(64)`(형식 CHECK), `approved_at`·`execute_before DATETIME(3)`, **`evidence_ref VARCHAR(128) NOT NULL`**(CHECK `^[A-Za-z0-9._:#/-]{1,128}$` — D-MOPS-008 ①),
  `approval_fingerprint CHAR(69)`(CHECK `^mop1:[0-9a-f]{64}$`), `outcome VARCHAR(16)`(`EXECUTED`·`REJECTED`), `result_code VARCHAR(48) NULL`, `result_reference VARCHAR(64) NULL`,
  `decided_via VARCHAR(16)`(`EXECUTE`·`DEADLINE_CONFIRM` — 기한 확인 호출이 만든 만료 행 구별), `recorded_at DATETIME(3)`, collation `utf8mb4_0900_bin`. **rev 0.6에서 열 확정**(D-MOPS-008).
  CHECK 추가: `decided_via IS NOT NULL AND decided_via IN ('EXECUTE','DEADLINE_CONFIRM')`(review n-b — 열도 NOT NULL), `decided_via = 'EXECUTE' OR (outcome = 'REJECTED' AND result_code IN ('APPROVAL_EXPIRED','APPROVAL_SAME_ACTOR','APPROVAL_WINDOW_INVALID'))`(review m-a — 기한 확인 경로의 판정 1·2 거절도 저장 가능, 3819 → 503 영구 반복 없음). Revision 등록자는 기존 `contract_revision.registered_by`를 쓴다(새 열 없음).
  **불변식(review n-a)**: 승인 operation(`ApproveContractRevision`·`IssueFirstDefaultMerchantAccount`)의 `admin_request` 저장 결과에는 — `APPROVAL_ID_ALREADY_USED` 거절을 제외하고 — 같은 TX에서 쓴 근거 행이 **반드시** 있다(`request_record_id`로 1:1). 시험 §8.2 B2-2-3.
  CHECK: `outcome = 'REJECTED' OR requested_by <> approved_by`(**DB 방어선** — EXECUTED는 같은 행위자 불가), `outcome = 'REJECTED' OR execute_before > approved_at`,
  `(outcome = 'EXECUTED' AND result_code IS NULL AND result_reference IS NOT NULL) OR (outcome = 'REJECTED' AND result_code IS NOT NULL AND result_reference IS NULL)`.
- `V10__admin_approval_evidence_privileges.sql`(db/runtime-privilege/master): runtime 계정 SELECT, INSERT만(UPDATE·DELETE 없음 — append-only).

### 9.4 MASTER 승인 endpoint(B2-2·B2-3 — `master.merchant.adapter.web.admin`)

| 경로(`/internal/v1/admin/merchant-operations/…`) | 명령 필드(문자열) | Use Case | 성공 reference |
|---|---|---|---|
| `contract-revision-approvals`(B2-2) | `contractId`, `revisionNo`(`^[1-9][0-9]{0,9}$` + 값 ≤ 2147483647 — C-30) | approveContractRevision(근거 있음) | `ctr_…#<n>` |
| `first-default-merchant-accounts`(B2-3) | `merchantId`, `contractId`, `agreementId`, `revisionNo`, `primarySiteId` | issueFirstDefaultMerchantAccount(근거 있음) | `mid_…` |

| `approval-decisions`(B2-2, **쓰기 효과 없음**) | `approvalId`(+ 공통 `correlationId`만 — 키·근거 필드 없음) | `ApprovalDecisionUseCase.decision` — 근거 행 조회(읽기 TX) | 아래 결정 응답 |
| `approval-deadline-confirmations`(B2-2, **실행 경로 없음**) | 실행 요청과 **같은 저장 바이트**(근거·명령 필드·키 — 같은 parser) | `confirmDeadline` | §3.3 실행 응답(재생 포함) 또는 `{"decision":"NOT_YET_DUE"}`(C-33) |

**결정 조회**(C-18 개정 — FINANCIAL `decision`·PAYMENT 결정 조회 선례): 근거 행(`admin_approval_evidence` by approval_id)을 읽어
`{"decision":"NONE"}` 또는 `{"decision":"DECIDED","operation":"…","outcome":"SUCCEEDED|REJECTED","reference":"…"(성공만),"rejection":"…"(거절만),"approvalFingerprint":"mop1:…","decidedVia":"EXECUTE|DEADLINE_CONFIRM"}`.
`NONE`은 "아직 커밋된 결정 없음"일 뿐 **효과 없음의 근거가 아니다**(진행 중 TX가 곧 커밋할 수 있다) — ADMIN은 NONE으로 종결하지 않는다. 조회 permit 사용(쓰기 permit과 독립). 감사·로그에 근거 참조 값 없음.
`APPROVAL_ID_ALREADY_USED` 거절은 근거 행이 없어 이 조회에 나오지 않는다(원래 approvalId의 결정이 나온다).

**기한 확인 전용 호출**(review M-2): MASTER 로컬 TX 하나 — 멱등 예약(실행과 **같은 operation·같은 키·같은 지문** — 그래서 실행과 직렬화되고, 이후 실행 재전송은 이 결과를 재생한다) →
① 이미 결과가 있으면 그 결과 재생(성공이면 성공), ② 판정 1·2(근거 형식 — `APPROVAL_SAME_ACTOR`·`APPROVAL_WINDOW_INVALID`)는 실행 경로와 같이 **저장**(근거 REJECTED 행 `decided_via = DEADLINE_CONFIRM`, review m-a), 판정 3(같은 approvalId가 다른 operation에 사용) → `APPROVAL_ID_ALREADY_USED` 저장·**근거 행 없음**(review m-b), ③ 그 밖에 MASTER 시각 `now < executeBefore` → **TX 전체 rollback**(예약도 남기지 않음 — review m-c: Use Case가 전용 신호 `DeadlineNotYetDue`(내부 예외, `MasterRuleViolation`·재시도 분류 **비대상**)를 던지면 실행기가 rollback만 하고 저장·재시도 없이 `NotYetDue` 결과로 바꾼다. 시험 기준 "그 키 `admin_request` 0행") 후 200 `{"decision":"NOT_YET_DUE"}`,
④ `now ≥ executeBefore` → `APPROVAL_EXPIRED` 거절 저장 + 근거 REJECTED 행(`decided_via = DEADLINE_CONFIRM`). **업무 본문(잠금·Domain·쓰기)은 절대 호출하지 않는다**(시험: 본문 대역 호출 0, 업무 표 CHECKSUM 불변).
진행 중이면 503 `REQUEST_IN_PROGRESS`. **응답 모양(C-33, rev 0.6.4)**: 실행 endpoint와 같은 §3.3 모양 — `{"outcome":"SUCCEEDED","reference":…,"replayed":…}`·`{"outcome":"REJECTED","rejection":…,"replayed":…}`(거절 reference 없음 — C-12)·409·503, 기한 전만 `{"decision":"NOT_YET_DUE"}`.
결정 조회 모양을 쓰지 않는 이유: 판정 3 `APPROVAL_ID_ALREADY_USED`는 자기 근거 행이 없어 `decidedVia`·지문을 정할 수 없다. `decidedVia`가 필요하면 ADMIN은 결정 조회(`approval-decisions`)를 따로 부른다.

- 채널: 서명 v1·`ADMIN_SERVICE`(D-MOPS-005 — 새 키·권한 없음)·본문 16 KiB·query 금지·`@ConditionalOnWebApplication(SERVLET)`. 실행·기한 확인은 **쓰기 permit 공유**(C-1), 결정 조회는 조회 permit. 응답 표는 §3.3 그대로(거절 reference 없음 — C-12).
- 쓰기 handler 전수 집합(m-5)에는 GET 아닌 handler 전부가 들어간다 — 두 새 경로도 포함(§8.2).
- 계약 승인은 기존 Use Case 그대로 권리 revision +1·Snapshot 발행(같은 TX), 첫 MID는 MID·Default·PRIMARY·Snapshot(같은 TX — 기존). 근거 행만 더해진다.
- 잠금 순서: 기존(계약 승인 merchant → contract, MID business → merchant → contract) 유지. 근거 PK 확인(판정 3)은 잠금 **전**(존재 조회만 — 잠금 없음), PK 충돌은 쓰기 단계 flush에서 재시도로 수렴.

### 9.5 ADMIN 승인함 새 종류(OWNER_SERVICE 2-TX — ADMIN-PAYMENT-OPS-001 §4.9~§4.20·ACQ-ADMIN-001 B1-A 틀 재사용)

**종류**(`ApprovalKind`, C-11 — 각각 2인):
- `MERCHANT_CONTRACT_APPROVAL(OWNER_SERVICE, OPERATOR, MERCHANT)`, `MERCHANT_FIRST_MID_ISSUANCE(OWNER_SERVICE, OPERATOR, MERCHANT)` — 승인자 APPROVER + 직전 TOTP, `mayActivatePrivilegedRole` false.
- `ApprovalTargetKind.MERCHANT`: `target_ref` = merchantId(`^mer_[0-9A-HJKMNP-TV-Z]{26}$`), `target_security_version` = 0(대상 버전은 pinned 비교 + MASTER 재판정 — C-16).
- 미해결 하나: `unresolved_key` = `MERCHANT:<merchantId>` — **두 종류 합쳐 가맹점당 미해결 하나**(C-20).
- `OwnerExecution` 지문 `mop1`, 소유 참조 `ctr_…#n`(계약 승인)·`mid_…`(첫 MID) — `conformsTo(kind)`에 종류별 문법 추가.

**MASTER 상세 조회 확장**(D-MOPS-008 ③): `MerchantDetailView.RevisionDetail`에 `registeredBy`(정규 actor 문자열) 추가 — jOOQ `JooqMerchantDetailReader`가 기존 `contract_revision.registered_by`를 읽는다(새 열 없음). ADMIN 상세 응답·pinned·승인 상세에 표시.

**ADMIN migration**(C-21 — 최신 V7·V8 다음): `V9__merchant_ops_approval.sql` — 운영 대사 finding 종류 CHECK `ck_admin_ops_reconciliation_finding_kind`(V5에 이미 있음 — review n-c 확인)를 **DROP 후 기존 6값 + `OWNER_UNRESOLVABLE`로 재생성**, `ck_admin_approval_request_kind`·`_target`(MERCHANT 대상은 두 종류만, 두 종류는 MERCHANT 대상만, target_ref 형식, version 0)·`_owner_states`(두 종류 추가) 재생성,
`unresolved_key` 식에 `WHEN 'MERCHANT' THEN CONCAT('MERCHANT:', target_ref)` 추가(V7과 같은 표 재작성 주의), `ck_admin_owner_execution_fingerprint` `^(oct1|acd1|mop1):…`,
표 `admin_merchant_ops_command`(INSERT-only: `approval_id` PK·FK, `command_kind`, `merchant_id`, `pinned_target JSON`, **`evidence_ref VARCHAR(128) NOT NULL`**(형식 CHECK — D-MOPS-008 ①), `created_at` — 자유 문구 없음. 근거 참조는 pinned·승인 행 사본에 넣지 않는다(별도 조회 전용).
`V10__merchant_ops_approval_privileges.sql`: 명령 표 SELECT, INSERT만. 시험 §8.2 B2-2-8.

**요청**(OPERATOR — `admin.approval.application.MerchantOpsApprovalService`, `AcquiringOpsApprovalService`와 같은 모양):

| 메서드·경로 | 본문 | 응답 |
|---|---|---|
| `POST /api/approvals/merchant-contract-approval`(B2-2) | `merchantId, contractId, revisionNo`(JSON 정수), **`evidenceRef`**(필수) | 201 `{approvalId}` |
| `POST /api/approvals/merchant-first-mid`(B2-3) | `merchantId, contractId, agreementId, revisionNo, primarySiteId`, **`evidenceRef`**(필수) | 201 `{approvalId}` |
| `GET /api/approvals/{id}/justification`(기존 endpoint) | — | 이 두 종류는 `{approvalId, evidenceRef}`만 — APPROVER와 요청자 본인만, 조회마다 감사 `APPROVAL_JUSTIFICATION_VIEWED`(D-MOPS-008 ①·D-ACQA-005·D-OPS-010 그대로) |

오류: 400 `INVALID_INPUT`, 403, 404 `MERCHANT_NOT_FOUND`, 409 `TARGET_NOT_ELIGIBLE_ADVISORY`·`DUPLICATE_UNRESOLVED`, 503 `MASTER_UNAVAILABLE`·`AUDIT_UNAVAILABLE`. 세션·CSRF·`no-store`·16 KiB·JSON만(기존 승인 API와 같음).

순서: 입력 형식 → 세션 역할 OPERATOR(비권위) → **MASTER 상세 조회**(조회 경로 `MasterOpsGateway.detail` — TX 밖, 조회 permit) → 미리 보기 적격(advisory):
계약 승인 = 그 가맹점의 계약·Revision 존재, 미승인, 끝나지 않음 / 첫 MID = MID 0개, 승인된 Revision, 그 계약의 CSA, PRIMARY Site가 Revision Scope 안·ACTIVE →
TX(승인 행 INSERT(미해결 UNIQUE) → 요청자 계정 잠금·재검사 ACTIVE·OPERATOR → 명령 행 INSERT(pinned) → 감사 `APPROVAL_REQUESTED`) → 201. 적격 판정의 권위는 MASTER(§9.3 5번)다.
evidenceRef 형식은 서버가 검사(앞뒤 공백을 자르지 않고 거부 — 재매입과 같음), 앞뒤 값은 감사·로그·목록·상세에 없음.
pinned(표시·비교용): 계약 승인 `{merchantId, merchantName, contractId, revisionNo, scopeKind, scopeSiteIds, effectiveFrom, effectiveTo, approved:false, registeredBy}`(**registeredBy** — D-MOPS-008 ③, MASTER 상세의 Revision 등록자),
첫 MID `{merchantId, merchantName, accountCount:0, contractId, agreementId, revisionNo, approved:true, effectiveFrom, effectiveTo, primarySiteId, channelType}` — 사업자등록번호·채널 식별값 없음.

**승인**(`POST /api/approvals/{id}/approve` — 기존 분기 `isOwnerService` → OWNER_SERVICE 2-TX):
1. **A**(TX 밖): MASTER 상세 재조회. 실패 → 503 `MASTER_UNAVAILABLE`, 상태·TOTP 불변.
2. **TX1**: 승인 행 잠금 → 계정 행(오름차순) → 판정표·참여자(승인자 ACTIVE·APPROVER, 요청자≠승인자 — 기존, **계약 승인은 승인자 ≠ pinned registeredBy** — 같으면 403 `APPROVER_IS_REGISTRANT`·상태·TOTP 불변, D-MOPS-008 ③)·직전 TOTP 소비·요청자 재검사(ACTIVE·OPERATOR) →
   **pinned vs 현재**(다르면 `INVALIDATED(TARGET_CHANGED)`, MASTER 미호출) → 본문 바이트 생성(정규 JSON — 필드 순서 고정)·`mop1` 지문 → 실행 행(attempt 1, `executeBefore` = 승인 + execution-window, 다음 시도 = 승인 + stale-after) → `EXECUTING` → 감사 `APPROVAL_EXECUTING`.
3. **C**(TX 밖): `MasterOpsGateway.executeApproved(kind, storedBytes)` — 저장 바이트 그대로 POST(새 `t`·새 서명, TX 안이면 거부, 쓰기 permit, redirect NEVER, 응답 4 KiB).
4. **TX2**(`OwnerOutcomeApplier` — 회복 job과 같은 규칙): SUCCEEDED → `EXECUTED` + 소유 참조(종류 문법 검사) + `APPROVAL_EXECUTED` / REJECTED → `OWNER_REJECTED` + code + `APPROVAL_OWNER_REJECTED` /
   409 `IDEMPOTENCY_KEY_REUSED` → 무결성 플래그(D-OPS-007, 한 번만) + `APPROVAL_INTEGRITY_VIOLATION` / Unknown·503(REQUEST_IN_PROGRESS·OUTCOME_UNKNOWN·TEMPORARILY_UNAVAILABLE)·`ADMIN_WRITE_BUSY` → `EXECUTION_UNKNOWN` + lastFailure + `APPROVAL_EXECUTION_UNKNOWN`.
   응답 200 `{status, reference?, rejection?}`. TX2 실패(감사 불가 등)는 행을 EXECUTING으로 두고 회복 job이 이어받는다(MASTER 효과는 이미 커밋됐을 수 있다 — 효과 없음으로 단정 안 함).

**결과 불명·응답 유실·회복**(`OwnerExecutionRecoveryService`에 종류 분기 추가 — PAYMENT·FINANCIAL과 **같은 순서**, C-18 개정, review M-1·M-2):
0. 선점(`FOR UPDATE SKIP LOCKED`, 시도 +1).
1. **결정 조회**(`approval-decisions`): DECIDED면 내용 대조(`approvalFingerprint` = 실행 행 지문, operation = 종류) → 일치면 TX2 규칙으로 반영(SUCCEEDED → EXECUTED, REJECTED → OWNER_REJECTED),
   불일치면 **무결성 플래그 먼저**(I-35) 기록·경보 뒤 결정 반영(종결하되 플래그 유지 — 사람 검토). 조회 불가(Unknown·503) → UNKNOWN 기록 후 다음 주기. NONE → 2.
2. **기한 단계**: ADMIN 시각 `now ≥ executeBefore + deadline-grace`면 **기한 확인 전용 호출**(저장 바이트) → DECIDED면 1과 같이 반영(`APPROVAL_EXPIRED` → OWNER_REJECTED, 재생 성공 → EXECUTED),
   `NOT_YET_DUE`(MASTER 시계가 늦음) → 다음 주기(실행 재전송 안 함), 503·Unknown → 다음 주기. 재전송 중단·무결성 플래그와 **무관하게** 이 단계가 먼저다. 기한 뒤에는 **실행 POST를 보내지 않는다**.
3. 참여자 재검사(D-OPS-006): 요청자 ACTIVE·OPERATOR, 승인자 ACTIVE·APPROVER — 아니면 재전송 중단(1회 기록·감사 `APPROVAL_RESEND_HALTED`·경보), 이후 1·2만(기한 단계가 종결).
4. 무결성 플래그가 있으면 실행 POST 없음 — 1·2(조회·기한 확인)로만 수렴(D-OPS-007).
5. 그 밖(기한 전) → 같은 저장 바이트 실행 POST → 아래 매핑.

**MASTER 응답 → TX2·회복 매핑**(웹 TX2와 회복 job 공통, review M-1 — 저장되지 않는 응답 포함):

| MASTER 응답 | 효과 판단 | ADMIN 상태·기록 | 이후 |
|---|---|---|---|
| 200 SUCCEEDED / REJECTED(재생 포함) | 확정 | EXECUTED + reference / OWNER_REJECTED + code | 종결 |
| 409 `IDEMPOTENCY_KEY_REUSED` | 같은 키에 **다른 지문**의 결정이 MASTER에 있음(결함·우회 신호) | 무결성 플래그(한 번만, `APPROVAL_INTEGRITY_VIOLATION`)·EXECUTION_UNKNOWN | 실행 POST 중단, 1(조회)로 그 결정을 읽어 반영(플래그 유지), NONE이면 2(기한 확인 — 같은 키라 409가 오면 아래 "수렴 불가") |
| 503 `REQUEST_IN_PROGRESS`·`OUTCOME_UNKNOWN`·`TEMPORARILY_UNAVAILABLE`·`ADMIN_WRITE_BUSY`, timeout·연결 끊김·응답 해석 불가 | 모름(효과 단정 안 함) | EXECUTION_UNKNOWN + lastFailure | 다음 주기 1부터 |
| 401(서명·시계 창), 404(endpoint 없음 — 배포 불일치) | MASTER 판정 전 거부 → 그 호출의 효과 없음. 이전 시도 효과는 모름 | EXECUTION_UNKNOWN + lastFailure `MASTER_UNAUTHORIZED`·`MASTER_NOT_FOUND`, 경보 | backoff로 다음 주기 1부터(일시 원인 — 키 교체·시계) |
| 400 `REQUEST_MALFORMED`, 413 | 저장 안 됨 → 그 호출 효과 없음. 같은 바이트는 계속 거부될 것 | EXECUTION_UNKNOWN + **재전송 중단 `OWNER_MALFORMED`**(1회 기록·경보) | 실행 POST 중단, 1·2만 |

**수렴 불가(종결 경로)**: 기한 + 여유 뒤에도 (a) 결정 조회 NONE이고 기한 확인이 400/413(저장 바이트 자체 거부 — "400 지속") 또는 409(같은 키 다른 지문)로 끝나면 자동 종결하지 않는다 —
EXECUTION_UNKNOWN 유지 + 기존 운영 대사 finding(`admin_ops_reconciliation_finding`, 종류 `OWNER_UNRESOLVABLE` 추가 — 1회) + 경보. 조회·기한 확인 시도는 backoff 상한(Demo `PT30M`)으로 계속(POST 실행은 없음).
사람의 종결은 이 작업 범위 밖(기존 운영 결정 대사 절차·후속). 이유: 효과 유무를 MASTER 결정으로 확인할 수 없는 건을 "효과 없음"으로 닫지 않는다(CLAUDE.md UNKNOWN 규칙).
설정(Demo 값 — 운영값 아님, 기본값 없음): `paycore.admin.merchant-ops.approval.execution-window` `PT1H`(≤ MASTER `max-execution-window`), `stale-after` = connect + read timeout + 30초, `deadline-grace` `PT2M`(≥ MASTER skew + read timeout), 회복 backoff 상한 `PT30M`.
**시계 차이 감시**(LIVE Gate 항목 — B2 구현 범위 아님): ADMIN이 MASTER 응답 `Date`와 자기 시각 차이를 지표로 내고 `deadline-grace`·MASTER skew보다 커지면 경보. Demo는 같은 호스트 시계.

**MASTER 거절 → 화면 문구**(모르는 code는 일반 문구): `REVISION_ALREADY_APPROVED` "이미 승인된 Revision입니다", `REVISION_ENDED` "효력이 끝난 Revision입니다", `REVISION_NOT_FOUND`, `CONTRACT_NOT_FOUND`,
`DEFAULT_ALREADY_ISSUED` "이미 기본 MID가 있습니다", `REVISION_NOT_APPROVED`, `REVISION_NOT_APPLICABLE` "이 Revision으로는 발급할 수 없습니다", `SITE_OUTSIDE_CONTRACT_SCOPE`, `SITE_NOT_ACTIVE`, `AGREEMENT_NOT_FOUND`,
`MERCHANT_NOT_ACTIVE`, `APPROVAL_EXPIRED` "실행 기한이 지나 처리되지 않았습니다 — 다시 요청하세요", `APPROVER_IS_REVISION_REGISTRANT` "Revision 등록자는 승인할 수 없습니다"(ADMIN TX1이 먼저 막으므로 MASTER에서 나오면 경보), `APPROVAL_SAME_ACTOR`·`APPROVAL_WINDOW_INVALID`·`APPROVAL_ID_ALREADY_USED` "시스템 점검 필요(관리자에게 알림)"(ADMIN 경로에서는 나오면 결함 — 경보).

### 9.6 화면

- **승인함**(`ApprovalsScreen.tsx`): 종류 라벨 "가맹점 계약 승인"·"첫 MID 발급", 대상 = 가맹점 이름·ID. 상세: pinned 값(Scope 종류·Site ID 목록·시작/종료 KST 날짜 + "이 날 0시 종료(미포함)", **계약 승인은 "Revision 등록자"**(D-MOPS-008 ③ — 본인이면 승인 버튼 대신 "등록자는 승인할 수 없습니다"), 첫 MID는 CSA·Revision·PRIMARY Site ID·채널 종류),
  **근거 참조는 "근거 보기" 버튼으로 별도 조회**(APPROVER·요청자만 버튼 표시, 서버가 권위, 조회마다 감사 — 목록·상세 응답에는 없음),
  상태별 표시(EXECUTED → 만든 reference·"가맹점 조회에서 보기", OWNER_REJECTED → §9.5 문구, EXECUTION_UNKNOWN → "결과 확인 중(자동 재확인)", INVALIDATED → "요청 뒤 대상이 바뀌어 무효"). 승인·거절 = APPROVER + TOTP(기존 UI), 철회 = 요청자.
- **가맹점 조회 상세**(`MerchantsScreen.tsx` 상세 패널, 버튼은 OPERATOR에게만 — 서버 판정이 권위):
  계약 영역 "새 Revision 등록"(B2-1 — 등록 탭 ④와 같은 입력 컴포넌트), Revision마다 배지 미승인/승인됨 + 미승인·끝나지 않은 Revision에 "승인 요청"(B2-2),
  MID 영역이 비어 있고 승인 Revision·CSA·Scope 안 ACTIVE Site가 있으면 "첫 MID 발급 요청"(B2-3 — CSA·Revision·PRIMARY Site 선택). 두 요청 폼 모두 **근거 참조 입력 필수**(형식 안내 문구, 자유 문구 불가). 요청 성공 → approvalId + "승인함에서 보기".
  Revision 행에 등록자(actor) 표시.
  **승인 대기 표시**: ADMIN 상세 API `GET /api/merchants/{merchantId}` 응답에 `pendingApprovals[{approvalId, kind, status}]`(ADMIN DB 승인 행 — jOOQ 조회, C-24)를 더해 "승인 대기 중"이면 요청 버튼을 숨긴다.
  `pendingApprovals`는 승인함 목록을 볼 수 있는 역할에게만(그 밖은 빈 배열 — 기존 승인 목록 권한 규칙).
- B1 등록 탭 완료 패널 문구 "다음: 계약 승인·MID 발급은 B2(아직 없음)" → "다음: 가맹점 조회 상세에서 계약 승인 요청"(B2-2 배포 뒤 — D-ADMIN-UI-003: 서버 기능이 생긴 뒤에만 버튼 표시).

### 9.7 감사·권한

| 항목 | B2-1 | B2-2·B2-3 |
|---|---|---|
| 역할 | OPERATOR만(1인) | 요청 OPERATOR, 승인·거절 APPROVER + 직전 TOTP(요청자≠승인자, **계약 승인은 Revision 등록자≠승인자** — D-MOPS-008 ③), 철회 요청자, 근거 보기 APPROVER·요청자 |
| ADMIN 감사 | `MERCHANT_CONTRACT_REVISION_REGISTER` 시도·결과 2행(§6 규칙), targetRef 계약 ID, 성공 `rev=<n>` | 기존 승인 감사(`APPROVAL_REQUESTED`·`_EXECUTING`·`_EXECUTED`·`_OWNER_REJECTED`·`_EXECUTION_UNKNOWN`·`_INVALIDATED`·`_RESEND_HALTED`·`_INTEGRITY_VIOLATION`·`_DENIED`·`_EXPIRED`·`_JUSTIFICATION_VIEWED`), target_ref = merchantId, approvalId 열. 등록자 승인 시도 403은 `APPROVAL_DENIED`(detail `reason=APPROVER_IS_REGISTRANT`) |
| MASTER | `admin_request`·`master_change_history`(actor `admin:…`)·Outbox(기존) | + `admin_approval_evidence`(요청자·승인자·승인 ID·승인 시각·기한·근거 참조·지문·결과·`decided_via` — 거절 포함, C-15). 이력 actor = approvedBy |
| 넣지 않는 값 | 등록번호·채널 식별값·자유 문구·MASTER 키 원문·서명 | 같음 + 본문 바이트·TOTP·**근거 참조 값**(ADMIN 감사·로그·지표 — 별도 조회 감사도 값 없음. MASTER는 근거 행에만) |
| 감사 실패 | §6과 같음 | 기존 2-TX 규칙(요청 TX 실패 = 요청 없음, TX2 실패 = 회복 job) |

### 9.8 8대 원칙 적용(B2)

| 원칙 | 의미 | 증거(§8.2) |
|---|---|---|
| Financial Consistency | 금액 없음. 권리 입력 변경은 계약 승인·첫 MID만이며 Snapshot과 같은 TX. 확정 사실(승인·근거)은 append-only(근거 표 UPDATE·DELETE 권한 없음) | B2-2-3·4, B2-3-2 |
| Transaction Safety | MASTER: 업무 효과·근거 행·결과·이력·Outbox·Snapshot 한 로컬 TX. ADMIN: TX1 → TX 밖 POST → TX2, 외부 호출 중 잠금 없음 | 장애 주입 rollback(B2-2-3), TX 안 호출 거부(B2-2-9) |
| Idempotency | 승인 하나 = 키 하나(`mop1.<approvalId>`), 저장 바이트 재전송, 같은 키·다른 근거 409, approvalId 1회 사용 | B2-2-2·3·6 |
| Concurrency Control | 가맹점당 미해결 승인 하나(ADMIN UNIQUE), MASTER merchant/contract 행 잠금으로 같은 Revision·같은 가맹점 MID 직렬화, 근거 PK | 실제 MySQL 두 연결(B2-2-7, B2-3-3) |
| Recoverability | 응답 유실 = EXECUTION_UNKNOWN(효과 단정 없음), 회복 = 결정 조회 → 기한 확인(실행 경로 없음) → 참여자 재검사 → 무결성 플래그면 조회만 → 같은 바이트 재전송. 확정 불가 건은 자동 종결하지 않고 finding·경보 | 실제 HTTP 연결 끊기(B2-2-6), stub 응답 유실(B2-2-11), 회복·400 지속·무결성 수렴(B2-2-10) |
| Reconciliation | ADMIN 실행 행(approvalId·`mop1`·소유 참조) ↔ MASTER 근거 행(같은 값) 1:1 대조 가능, 회복 1단계가 건별로 대조. 대사 job은 만들지 않음(L-2·L-10) — **확장 후보(m-3)**: MASTER 근거 행 중 ADMIN에 없는 approvalId = `ORPHAN_OWNER_DECISION`(우회 신호) finding. **LIVE Gate 차단 조건**: 이 ORPHAN 대사(또는 동등 탐지)가 동작하기 전에는 LIVE에서 B2 2인 명령을 열지 않는다 | 흐름 시험 두 표 값 일치(B2-2-11), 회복 지문 불일치 → 플래그(B2-2-10) |
| Auditability | ADMIN 승인 이력 + 근거 참조(별도 조회·감사) + MASTER 자기 근거(요청자·승인자·시각·근거 참조·지문, 거절 포함 — D-ADMIN-007·D-MOPS-007 ①·008 ①), Revision 등록자≠승인자(008 ③) | B2-2-1·3·9 |
| Observability | MASTER `approval_rejected{operation,rejection}`(경보: SAME_ACTOR·ID_ALREADY_USED·REVISION_REGISTRANT), **`approval_executed{operation}` counter**(m-3) ↔ ADMIN EXECUTED 수 `paycore.admin.approval.owner_executed{kind}` 비교 지표(차이 = ORPHAN 후보 — 대시보드·경보 후보), 기존 owner ops 지표(EXECUTION_UNKNOWN age·재전송 중단·무결성·`OWNER_UNRESOLVABLE`), 상관 ID `mop.<approvalId>`. 시계 차이 지표는 LIVE Gate | B2-2-5·10 |

### 9.9 기술 선택 연결(TECHNOLOGY-AND-PRINCIPLES §2~§5 — 이번 변경에 한함)

- **JPA(근거 행)** — 하는 일: `JpaApprovalEvidenceEntity`를 `persist`만 한다(`@Immutable`, 변경 감지 대상 아님). 예: 계약 승인 TX에서 contract·merchant 갱신과 근거 INSERT가 같은 로컬 TX로 commit된다.
  이유: 근거는 append-only 사실이라 갱신 경로가 없어야 하고(권한도 SELECT·INSERT만), 순수 Domain `ApprovalEvidence`와 Entity를 분리해 Domain이 JPA를 모르게 한다(Mapper는 adapter).
  주의(review m-1): `persist`만 하면 PK(approvalId) 충돌이 commit 시점 flush에서야 드러나 실행기가 업무 거절로 분류하지 못한다 → **`record`가 `persist` 직후 `flush`**하고 제약 위반을 `StoreConstraint(businessKey = true)`로 번역한다.
  그러면 실행기의 업무 유일키 재시도(새 TX)가 판정 3(`APPROVAL_ID_ALREADY_USED`)으로 수렴한다. flush는 그 시점까지의 다른 변경도 SQL로 보내지만 commit은 아니다(실패 시 같은 TX 전체 rollback).
  Entity equals/hashCode는 approvalId만, toString에 지문·행위자·근거 참조 없음(data class 금지).
- **JPA(기존 잠금·Version)** — 계약 승인·MID 발급은 기존 `PESSIMISTIC_WRITE` 잠금(merchant → contract)과 `@Version` 갱신을 그대로 쓴다. 근거 판정 4(만료)는 잠금 **뒤** 시각이라 잠금 대기 중 기한이 지나면 거절된다(의도).
- **jOOQ(CQRS 조회)** — ADMIN 상세의 `pendingApprovals`는 기존 jOOQ `ApprovalRequestListReader`에 대상 조건만 더한 DTO 조회(ADMIN DB 안 — MASTER DB와 JOIN 없음). MASTER는 기존 jOOQ `JooqMerchantDetailReader`에 `registered_by` 열만 추가(D-MOPS-008 ③).
  결정 조회(`approval-decisions`)는 한 행 PK 조회라 JPA Store(`ApprovalEvidenceStore.find`)로 읽는다 — 화면용 복합 조회가 아니므로 jOOQ Reader를 새로 두지 않는다.
- **버전**: 새 의존성 없음 — Boot BOM 관리 조합(현 빌드 고정값) 그대로. Flyway V9/V10 두 앱, jOOQ codegen은 ADMIN V9 schema에서 재생성.
- **Kafka/Redis**: 도입하지 않는다. 2인 실행의 1회성·순서·회복은 소유 DB 제약(UNIQUE·PK·CHECK)·로컬 TX·같은 바이트 재전송이 지킨다 — 메시지 브로커나 분산 lock이 이를 대신한다고 보지 않는다.

### 9.10 결정·질문 연결

재질문하지 않는 것: D-MOPS-004(2인 범위)·005(조회 키 서명)·006·007·**008**(근거 참조 필수·Demo seed 경로·Revision 등록자≠승인자), D-ADMIN-007, D-DEMO-001·005, C-11(승인 종류 2개). 새 질문 후보 없음(rev 0.6). 가역 결정 C-15~C-32(§10).

### 9.11 Demo 시나리오(Coordinator — Demo 값, 운영값 아님)

- **B2-1**: OPERATOR로 B1에서 만든 가맹점 상세 → "새 Revision 등록"(시작일 내일, MERCHANT Scope) → Revision 2 **미승인** 표시·권리 revision 불변 → 시작일 어제로 다시 → "시작일이 오늘보다 앞입니다".
- **B2-2**: OPERATOR(A)가 Revision 1 "승인 요청" → 상세에 "승인 대기 중" → A 화면 승인함에는 승인 버튼 없음(OPERATOR — API 직접 호출도 403), APPROVER 역할을 함께 가진 계정이 자기 요청을 승인하면 403(요청자=승인자 — 기존 참여자 규칙) → APPROVER(B)가 TOTP로 승인 → 상태 EXECUTED·reference `ctr_…#1` →
  조회 상세 Revision 1 **승인됨**·변경 이력 `ContractRevisionApproved`(actor `admin:<B>`)·권리 revision +1. 승인 전 B가 "근거 보기"로 근거 참조 확인(감사 화면에 `APPROVAL_JUSTIFICATION_VIEWED` — 값 없음).
  **등록자 규칙**: OPERATOR·APPROVER 두 역할 Demo 계정 C가 Revision 2를 등록하고 A가 승인 요청 → C 화면에 "등록자는 승인할 수 없습니다", B는 승인 가능.
  (MASTER 같은 행위자·등록자 거절, 응답 유실, 무결성·400 지속 회복은 시험 보고서로 보여 준다 — 화면 경로가 없다.)
- **B2-3**: A가 "첫 MID 발급 요청"(CSA·Revision 1·PRIMARY Site) → B 승인 → EXECUTED `mid_…` → 상세 MID 1개·Default·PRIMARY Site → 같은 가맹점에 다시 요청 시 버튼 없음(MID 있음).
  **"결제는 아직 불가(결제수단 개통·Site 적용 확인 = B3)"** 안내 확인.

### 9.12 B3~B5 개요(설계는 각 배치 착수 시 — 분류는 D-MOPS-007 ③으로 확정)

**B3 개통** — 2인: approvePaymentMethodActivation(근거 + PRIMARY Site 적용 확인 동반 D-MD-016), confirmSiteActivation, decideSiteReview(APPROVED), enableCurrency, approveCardRouteActivation,
resume(결제수단·Route 재개 — D-MOPS-007 ③). 1인: 개통 등록(registerPaymentMethodActivation·registerCardRouteActivation — UNDER_REVIEW로 남음), decideSiteReview(REJECTED)(D-MOPS-007 ③).
2인 명령은 §9.3 승인 근거 계약을 그대로 쓴다(operation CHECK·종류만 추가). **rev 0.8: 상세 설계는 §9.13~§9.24**, 남은 미결은 Q-MOPS-22(기관 가맹점 참조 형태)·Q-MOPS-23(통화 효력 시작)·Q-MOPS-24(Route 등록자≠승인자).

**B4 한도·수수료** — 2인: registerTransactionLimitPolicy·replace(상향), endTransactionLimitPolicy(한도 종료 — 상한 없음 = 완화), assignPolicy(FEE_SCHEDULE·정산 정책), endPolicyAssignment(D-MOPS-004·007 ③).
1인: 한도 하향(D-MOPS-007 ③). 남은 미결: "상향/하향" 판정 기준(기존 대비 비교 — 통화·건당/일 각각, 섞인 변경의 분류). registerPolicyVersion은 전역 정책이라 이 작업 밖. 한도·요율 값은 Demo 값 — 운영값 아님.

**B5 안전 방향(1인 즉시)** — suspend/terminate PaymentMethodActivation·CardRouteActivation, revokeSiteActivationConfirmation, endCurrencyEnablement. (재개는 B3 2인.)
Business·Merchant·Site·Contract·MerchantAccount **종료 메서드가 Domain에 없음**(MASTER-DOMAIN-001 §5 묶음 B, D-MD-006 — MASTER-LIFECYCLE-001 미착수) → B5에서 가맹점 단위 정지는 할 수 없다.

## 9B. B3 개통 상세 설계(rev 0.8 — D-MOPS-004·005·007 ③·008, D-MD-016~018, D-MS-002·003·005, D-MS2-004·005, C-35~C-44)

### 9.13 범위·하위 배치(C-38 — 전달 순서 = Demo 순서)

코드 확인(B2 커밋 `1b5538d`): MASTER Use Case는 모두 있고 **근거 없는 경로**(`AdminCommandExecutor.execute`)만 있다 — `MerchantEntitlementService`·`MerchantAuthorizationTermsService`의 executor는 `ApprovalEvidenceStore` 없이 생성된다.
Demo seed(`DemoMerchantSeedService`)가 그중 `approvePaymentMethodActivation`·`decideSiteReview(APPROVED)`·`enableCurrency`·`approveCardRouteActivation`을 행위자 `system:demo-seed`로 부른다. 따라서 B2와 같이 **근거 경로 메서드(`…WithEvidence`)를 새로 두고**, 근거 없는 2인 메서드는 seed 관문(C-26 구조)으로 묶는다(C-40).

| 배치 | 명령(분류 D-MOPS-007 ③) | MASTER endpoint(`/internal/v1/admin/merchant-operations/…`) | ADMIN | 질문 |
|---|---|---|---|---|
| **B3-1** | registerPaymentMethodActivation(1인) · approvePaymentMethodActivation(2인, PRIMARY Site 적용 확인 동반 D-MD-016) · decideSiteReview APPROVED(2인) · decideSiteReview REJECTED(1인) | `payment-method-activations`(1인) · `payment-method-activation-approvals` · `site-review-approvals` · `site-review-rejections`(1인) | 1인 화면 API 2 · 승인 종류 2 · 상세 "개통" 영역·개통 점검표 | 착수 가능. **2인 경로 DONE은 Q-MOPS-24 답 뒤**(MAJOR-1) |
| **B3-2** | enableCurrency(2인) | `currency-enablements` | 승인 종류 1 | **Q-MOPS-23** |
| **B3-3** | registerCardRouteActivation(1인) · approveCardRouteActivation(2인) | `card-route-activations`(1인) · `card-route-activation-approvals` | 1인 화면 API 1 · 승인 종류 1 | **Q-MOPS-22·24** |
| **B3-4** | resumePaymentMethodActivation · resumeCardRouteActivation · confirmSiteActivation(2인) | `payment-method-activation-resumptions` · `card-route-activation-resumptions` · `site-activation-confirmations` | 승인 종류 3 | 없음 — **B5 뒤**(C-38) |

- **B3-4를 B5 뒤로 두는 이유(코드 확인)**: 재개는 SUSPENDED가, Site 재확인은 철회된 확인이 있어야 Demo에서 의미가 있다. 중지·철회는 B5(1인)다. 또 ADDITIONAL Assignment 생성 명령이 MASTER에 없다(`MerchantOnboardingUseCase` KDoc — MASTER-LIFECYCLE-001)
  → 새 가맹점에서 `confirmSiteActivation`이 쓰이는 경우는 PRIMARY 확인 철회 뒤 재확인뿐이고(`SiteConfirmationRules.confirm` — UNDER_REVIEW 중 PRIMARY는 `PRIMARY_CONFIRMED_BY_APPROVAL`), 그 전에는 `ASSIGNMENT_NOT_OPEN`·`SITE_CONFIRMATION_DUPLICATE`만 보인다.
- **해석(원문 아님)**: Site 심사 SUSPENDED(APPROVED → SUSPENDED)는 D-MOPS-004 "안전 방향(중지) 1인 즉시"에 해당 → **B5**(1인). SUSPENDED → APPROVED는 decideSiteReview(APPROVED)라 2인(B3-1 endpoint 그대로).
- 제외: B4 명령(한도·정책 배정 — "승인 가능"에는 필요, §9.19), B5 명령, ADDITIONAL Assignment·추가 MID(MASTER-LIFECYCLE-001), 가맹점 API Credential 발급(MASTER-CREDENTIAL-001 — L-17), registerPolicyVersion(전역 정책).
- Kafka/Redis 없음: 같은 근거 계약(§9.3)·MASTER merchant 행 잠금·업무 UNIQUE(열린 Activation·열린 통화 기록·열린 Route 생성 열)·`admin_request` UNIQUE가 직렬화와 1회성을 지킨다.

### 9.14 MASTER endpoint(B3 — `master.merchant.adapter.web.admin`, 서명 v1·`ADMIN_SERVICE`, 쓰기 permit 공유, 응답 §3.3·C-12)

**1인**(B1 공통 필드 `idempotencyKey`(`mo1.…`)·`actor`·`correlationId` — `AdminCommandExecutor.execute`):

| 경로 | 명령 필드 | Use Case | 성공 reference |
|---|---|---|---|
| `payment-method-activations`(B3-1) | `merchantAccountId`, `paymentMethod`(`CARD`만 — 기존 enum) | `registerPaymentMethodActivation`(기존 그대로) | `act_…` |
| `site-review-rejections`(B3-1) | `merchantSiteId`, `reasonCode`(C-37), `evidenceRef`(선택 — 형식 §9.3) — **`decision` 필드 없음**(오면 400) | **새 `rejectSiteReview`**(REJECTED 고정 — 기존 `SiteReviewRules.decide` 재사용, C-40). **operation `RejectSiteReview`**(새 `AdminOperation` — `admin_request`·지표 label·이력 operation이 2인 `DecideSiteReview`와 분리, nit-1) | `site_…#<seq>` |
| `card-route-activations`(B3-3) | `merchantAccountId`, `paymentMethod`, `acquirerCode`, `routeRef`, `institutionMerchantRef`(Q-MOPS-22) | `registerCardRouteActivation`(기존) | `cra_…` |

**2인**(§9.3 근거 공통 9필드 + **`operation`**(C-39) + 명령 필드, `executeApproved`, 키 `mop1.<approvalId>`, 행위자 = approvedBy, Domain 기관 근거 = 근거 `evidenceRef`(C-36)):

| 경로 | `operation`(= AdminOperation code, `mop1` 2번째 줄) | 명령 필드(`mop1` 끝 줄 순서) | 새 Use Case(근거 경로) | 성공 reference |
|---|---|---|---|---|
| `payment-method-activation-approvals`(B3-1) | `ApprovePaymentMethodActivation` | `activationId`, `primarySiteId`(C-45) | `approvePaymentMethodActivationWithEvidence` | `act_…` |
| `site-review-approvals`(B3-1) | `DecideSiteReview` | `merchantSiteId`, `reasonCode` | `approveSiteReviewWithEvidence`(APPROVED 고정) | `site_…#<seq>` |
| `currency-enablements`(B3-2) | `EnableCurrency` | `merchantAccountId`, `currency`, `effectiveFrom`(Q-MOPS-23), `effectiveTo`(선택 — 없으면 빈 줄) | `enableCurrencyWithEvidence` | `cur_…` |
| `card-route-activation-approvals`(B3-3) | `ApproveCardRouteActivation` | `activationId`(`cra_`) | `approveCardRouteActivationWithEvidence` | `cra_…` |
| `payment-method-activation-resumptions`(B3-4) | `ResumePaymentMethodActivation` | `activationId` | `resumePaymentMethodActivationWithEvidence` | `act_…` |
| `card-route-activation-resumptions`(B3-4) | `ResumeCardRouteActivation` | `activationId`(`cra_`) | `resumeCardRouteActivationWithEvidence` | `cra_…` |
| `site-activation-confirmations`(B3-4) | `ConfirmSiteActivation` | `merchantAccountId`, `merchantSiteId`, `paymentMethod` | `confirmSiteActivationWithEvidence` | `sac_…` |

- 근거 경로 메서드는 기존 본문을 함수로 빼서 공용한다(B2-3 `issueFirstDefaultBody` 방식): 잠금 순서(**경로별 실제 코드**: 수단 개통 승인·재개 = merchant → merchant_account → payment_method_activation, Route 승인·재개 = merchant → merchant_account → card_route_activation, 통화 활성화·Site 재확인 = merchant → merchant_account, Site 심사 승인·1인 거절 = **merchant만**(Site 행은 잠그지 않고 잠금 뒤 다시 읽는다), 1인 수단 개통 등록 = merchant → merchant_account — rev 0.8.3 nit-2)·`lockedNow()`(만료 훅이 여기서 돈다 — §9.3 판정 4)·Domain 판정·`beginWrites()`·이력·Outbox·`publishEntitlement`는 그대로. 근거 행만 더해진다.
  잠금 전 거절(`ACTIVATION_NOT_FOUND`·`CARD_ROUTE_NOT_FOUND`·`MERCHANT_ACCOUNT_NOT_FOUND`·`SITE_NOT_FOUND`)은 만료보다 먼저 저장된다(m-4와 같은 의도된 순서 — 시험 고정).
- 두 서비스의 executor를 `ApprovalEvidenceStore`·`ApprovalWindow`와 함께 생성한다(B2 `MerchantOnboardingService`와 같음). 새 Domain 규칙은 Q-MOPS-24 답(Route 등록자)뿐 — 그 밖은 기존 Domain 판정을 바꾸지 않는다.
- 결정 조회(`approval-decisions`)는 operation 무관(근거 행 PK)이라 변경 없음. 기한 확인(`approval-deadline-confirmations`)은 B3 본문의 `operation` 필드로 해석기를 고른다(C-39), B2 두 본문은 기존 모양 판정 그대로(불변).
- 거절 → 화면 문구(모르는 code는 일반 문구): `ACTIVATION_DUPLICATE` "이미 개통 등록이 있습니다", `ACTIVATION_TRANSITION_NOT_ALLOWED` "지금 상태에서는 할 수 없습니다", `ACTIVATION_EVIDENCE_NOT_NEW` "이전에 쓴 근거 참조입니다 — 새 근거가 필요합니다",
  `SITE_REVIEW_TRANSITION_NOT_ALLOWED`, `SITE_NOT_ACTIVE`, `MERCHANT_ACCOUNT_NOT_ACTIVE`, `CURRENCY_ENABLEMENT_RETROACTIVE` "효력 시작 시각이 이미 지났습니다 — 다시 요청하세요", `CURRENCY_ENABLEMENT_OVERLAP`, `CARD_ROUTE_METHOD_NOT_ACTIVATED`, `CARD_ROUTE_DUPLICATE`, `CARD_ROUTE_TRANSITION_NOT_ALLOWED`,
  `CARD_ROUTE_EVIDENCE_NOT_NEW`, `PRIMARY_CONFIRMED_BY_APPROVAL`, `ASSIGNMENT_NOT_OPEN`, `SITE_CONFIRMATION_DUPLICATE`, 근거 5종은 §9.5 문구.

### 9.15 MASTER 근거 계약 확장(§9.3 그대로 + 아래만)

- `admin_approval_evidence.operation` CHECK에 B3 operation 7개 추가(V11 — §9.22). 그 밖 열·CHECK·`decided_via` 규칙·불변식 n-a 그대로. `result_reference VARCHAR(64)` ≥ 가장 긴 B3 reference(`site_` 31자 + `#` + 10자리 = 42).
- **`operation` 본문 필드(C-39)**: B3 승인 endpoint는 본문에 `operation`을 필수로 받고 경로의 operation과 다르면 400. 이미 `mop1` 2번째 줄이므로 지문 의미는 바뀌지 않는다. 같은 필드 집합을 가진 승인·재개(`activationId` 하나)를 기한 확인이 구별할 수 있게 하는 목적이다.
- **Domain 기관 근거 = 승인 근거(C-36)**: D-MS-003은 승인·재개에 기관 근거 참조를 요구하고, D-MOPS-008 ①은 2인 요청의 업무 근거 참조를 요구한다. B3에서는 같은 문서 참조이므로 한 입력값을 둘 다에 쓴다(형식 동일 — `EvidenceRef` `[A-Za-z0-9._:/#-]{1,128}`).
  MASTER에서 Domain 근거는 기존대로 개통 이력·변경 이력·Outbox 참조 이벤트 payload에 남는다(D-MS-003 기존 설계 — 상세 조회 SELECT에는 없음). ADMIN에서는 B2와 같이 감사·로그·목록·상세에 없고 "근거 보기"로만.
- **PRIMARY 고정(C-45)**: 개통 승인은 PRIMARY Site 적용 확인을 함께 기록하므로(D-MD-016) 승인자가 본 PRIMARY를 명령 필드 `primarySiteId`로 고정해 `mop1`에 넣는다. MASTER는 잠금·`lockedNow()` 뒤 열린 PRIMARY(`ActivationApproval`과 같은 판정 `isOpenAt(at)`)가 없거나 다르면 **`PRIMARY_SITE_MISMATCH`**(`MasterRejection` 끝에 추가 — `CONTRACT_MERCHANT_MISMATCH` 명명 관례)를 저장한다(근거 REJECTED 행). 근거 없는 seed 경로는 기존 그대로(필드 없음).
- **근거 입력란 의미(minor-5)**: 수단 개통 승인·통화·Route·재개 = "기관(카드사/VAN) 회신 참조", Site 심사 = "PG 심사 근거" — 화면 라벨·승인 상세에 표시. 형식·저장 규칙은 같다(C-36 유지 — 질문 승격 안 함, Coordinator 결정).
- **seed 관문(C-40)** — *rev 0.8.2 구현 기록(B3-1)*: 이번 배치는 `approvePaymentMethodActivation`과 `decideSiteReview`의 **APPROVED 결정만** 관문 뒤로 옮겼다(REJECTED·SUSPENDED는 1인·안전 방향이라 관문 없음 — 관리 채널의 1인 거절은 `rejectSiteReview`). 나머지 5개(`enableCurrency`·`approveCardRouteActivation`·`confirmSiteActivation`·두 resume)는 각 배치(B3-2~4)에서 같은 방식으로 옮긴다. 원안: 근거 없는 2인 메서드 7개(`approvePaymentMethodActivation`·`decideSiteReview`·`enableCurrency`·`approveCardRouteActivation`·`confirmSiteActivation`·`resumePaymentMethodActivation`·`resumeCardRouteActivation`)는 진입에서 `requireDemoSeedPath`(행위자 정확히 `system:demo-seed` + `DeploymentModePort.isDemo()`).
  1인 명령(등록 2개·`rejectSiteReview`)과 B5 안전 방향 명령은 관문 없음. 근거: D-MOPS-008 ②(Demo seed 근거 없는 승인 경로 유지 + 진입 관문)의 B3 적용.

### 9.16 ADMIN 승인 종류·미해결 키·요청(B3 — `admin.approval`·`admin.merchantops`, §9.5 틀 그대로)

| 종류(`ApprovalKind`, OWNER_SERVICE·OPERATOR·MERCHANT) | `command_kind` | 요청 경로(`POST /api/approvals/…`) | 요청 본문(+ `evidenceRef` 필수) | 소유 참조 문법 | 미리 보기(advisory) |
|---|---|---|---|---|---|
| `MERCHANT_PAYMENT_METHOD_ACTIVATION`(B3-1) | `PAYMENT_METHOD_ACTIVATION` | `merchant-payment-method-activation` | `merchantId, merchantAccountId, activationId` | `act_` | Activation UNDER_REVIEW·MID ACTIVE·열린 PRIMARY 있음 |
| `MERCHANT_SITE_REVIEW_APPROVAL`(B3-1) | `SITE_REVIEW_APPROVAL` | `merchant-site-review-approval` | `merchantId, merchantSiteId, reasonCode` | `site_…#n` | Site ACTIVE·심사 PENDING 또는 SUSPENDED |
| `MERCHANT_CURRENCY_ENABLEMENT`(B3-2) | `CURRENCY_ENABLEMENT` | `merchant-currency-enablement` | `merchantId, merchantAccountId, currency, effectiveFrom…, effectiveToDate?` | `cur_` | MID ACTIVE·같은 통화 열린 기록 없음 |
| `MERCHANT_CARD_ROUTE_ACTIVATION`(B3-3) | `CARD_ROUTE_ACTIVATION` | `merchant-card-route-activation` | `merchantId, merchantAccountId, routeActivationId` | `cra_` | Route UNDER_REVIEW·수단 개통 열림 |
| `MERCHANT_PAYMENT_METHOD_RESUME`(B3-4) | `PAYMENT_METHOD_RESUME` | `merchant-payment-method-resume` | `merchantId, merchantAccountId, activationId` | `act_` | SUSPENDED |
| `MERCHANT_CARD_ROUTE_RESUME`(B3-4) | `CARD_ROUTE_RESUME` | `merchant-card-route-resume` | `merchantId, merchantAccountId, routeActivationId` | `cra_` | SUSPENDED |
| `MERCHANT_SITE_ACTIVATION_CONFIRMATION`(B3-4) | `SITE_ACTIVATION_CONFIRMATION` | `merchant-site-activation-confirmation` | `merchantId, merchantAccountId, merchantSiteId, paymentMethod` | `sac_` | 열린 Assignment·유효 확인 없음·Activation 종료 아님 |

- 종류 이름 ≤ 40(`kind VARCHAR(40)`), `command_kind` ≤ 32 확인. 승인자 APPROVER + 직전 TOTP, 요청자≠승인자(기존 참여자 규칙). **Q-MOPS-24(MAJOR-1 확대)** 답이 권장안이면 수단 개통·Site 심사·Route 승인 모두 승인자 ≠ pinned `registeredBy`(403 `APPROVER_IS_REGISTRANT` — B2-2와 같은 모양) — 답 전에는 pinned·화면 표시만(검사 미구현).
- **pinned**(표시·비교용 — 사업자등록번호·채널 식별값·근거 참조·기관 가맹점 참조 원문 없음): 공통 `{merchantId, merchantName, merchantAccountId}` + 종류별 대상 상태(Activation `{activationId, paymentMethod, status, primarySiteId, registeredBy}`, Site `{merchantSiteId, channelType, reviewStatus, reasonCode, registeredBy}`(Site의 `registeredBy` = `merchant_site.created_by` — MAJOR-1, 표시용),
  통화 `{currency, effectiveFrom, effectiveTo}`, Route `{routeActivationId, acquirerCode, routeRef, status, registeredBy, institutionMerchantRefMasked}`(Q-MOPS-22·24), 재개 `{…, status:SUSPENDED}`, 재확인 `{merchantSiteId, paymentMethod, activationId, activationStatus}`). 승인 A 단계 재조회 값과 다르면 INVALIDATED(TARGET_CHANGED).
- **미해결 키(C-35 — rev 0.8.1 Coordinator 채택)**: 대상 그룹별 가맹점당 하나. B2 두 종류는 `MERCHANT:<merchantId>` **그대로**(C-20 의미 불변), B3는 `MERCHANT:<그룹>:<merchantId>` — 그룹 `PM`(수단 승인·재개)·`SITE_REVIEW`·`CURRENCY`·`ROUTE`(Route 승인·재개)·`SITE_CONFIRM`(최대 9 + 12 + 1 + 30 = 52 ≤ 80).
  MASTER 직렬화(merchant 행 잠금)는 키와 무관하게 정확성을 지키므로, 키는 "같은 대상에 대한 중복 대기 요청"만 막는다. 화면은 같은 그룹 승인 대기 중 그 그룹의 2인 요청 버튼만 숨기고, **Site의 1인 "심사 거절" 버튼은 숨기지 않는다**(안전 방향 동작을 막지 않음 — Coordinator 결정. 거절이 먼저 커밋되면 대기 중 승인은 MASTER에서 `SITE_REVIEW_TRANSITION_NOT_ALLOWED`로 끝난다).
- **열린 PRIMARY 판정 차이(rev 0.8.4 — B3-1 코드 리뷰 m-1, 의도된 보수적 판정)**: ADMIN 미리 보기(`MerchantApprovalChannelService`)와 화면(`MerchantActivationArea.openPrimarySite`)은 PRIMARY Assignment를 **ACTIVE·기간 안**일 때만 열린 것으로 본다. MASTER `SiteAssignment.isOpenAt`은 ENDED가 아니고 기간 안이면 열린 것으로 본다 — ADMIN이 더 좁아 요청을 덜 허용할 뿐(fail-closed) 더 허용하지는 않는다. 고정 PRIMARY의 권위 판정은 MASTER 실행 TX(`PRIMARY_SITE_MISMATCH`, C-45).
- **`MasterResponseShape`(minor-4)**: 결정 조회 응답 `operation` 허용 목록에 배치별 operation을 추가하고 operation별 성공 reference 문법(`act_`·`site_…#n`·`cur_`·`cra_`·`sac_`)을 검사한다. 목록 밖 operation·문법 불일치는 해석 불가(fail-closed). 1인 응답 모양 표는 종류별(`act_`, Site 거절 `site_…#n`).
- 1인 화면 API(B1 틀 — `MerchantRegistrationService`, OPERATOR만, 감사 시도·결과 2행, `mo1.<계정 ULID>.<submissionId>`·`mos.<submissionId>`): `POST /api/merchant-registrations/payment-method-activations`(감사 `MERCHANT_PAYMENT_METHOD_ACTIVATION_REGISTER`, targetRef = MID),
  `…/site-review-rejections`(감사 `MERCHANT_SITE_REVIEW_REJECT`, targetRef = siteId, detail `reason=<code>`), `…/card-route-activations`(B3-3, 감사 `MERCHANT_CARD_ROUTE_ACTIVATION_REGISTER`, targetRef = MID, detail `acq=<code>` — 기관 가맹점 참조 처리 Q-MOPS-22). 응답 모양 표: `site_…#n`은 Site 거절 종류에만(B2-1 `ctr_…#n`과 같은 방식).
- 회복: `OwnerExecutionRecoveryService` 가맹점 분기에 종류 행 추가(순서 §9.5 그대로 — 결정 조회 → 기한 확인(`operation` 포함 저장 바이트) → 참여자 재검사 → 무결성 플래그면 조회만 → 같은 바이트 재전송). C-34 `REFERENCE_MISMATCH` 대조는 종류별 pinned 대상 ID(`act_`·`cra_`·`site_…#`의 site 부분)로.

### 9.17 화면(D-ADMIN-UI-003 — 서버 배포된 배치의 버튼만)

- **가맹점 조회 상세 → MID 행 아래 "개통" 영역**(버튼은 OPERATOR에게만, 서버가 권위): 결제수단(상태 배지 UNDER_REVIEW·ACTIVE·SUSPENDED·TERMINATED, "개통 등록"(1인) → "개통 승인 요청"(2인, 근거 참조 필수 — 라벨 "기관(카드사/VAN) 회신 참조")), Site 표(등록자 표시, 심사 상태 + "심사 승인 요청"(2인 — 근거 라벨 "PG 심사 근거")·"심사 거절"(1인 — 확인 대화상자 "되돌릴 수 없습니다. 재신청은 새 Site로", 사유 코드 선택)),
  Site 적용 확인 목록(유효/철회), 통화(B3-2 "통화 활성화 요청"), 카드 Route(B3-3 "Route 등록"·"개통 승인 요청", 기관 가맹점 참조는 Q-MOPS-22 형식 표시), 재개·재확인 버튼(B3-4). 승인 대기 중이면 같은 그룹 버튼 숨김(`pendingApprovals` — C-24·C-35).
- **개통 점검표(C-43 — "참고, 권위 아님")**: MASTER 상세 값으로 화면이 계산 — ① 계약 적용 중(승인 Revision이 오늘 포함) ② Site 심사 APPROVED ③ 수단 ACTIVE ④ PRIMARY 적용 확인 유효 ⑤ 통화 활성(KRW) → "결제 생성 조건" / ⑥ 카드 Route ACTIVE ⑦ 수수료·정산 배정·한도(B4 — 지금은 "B4 이후") → "승인 시작 조건" / ⑧ 전파: 최신 revision을 PAYMENT가 ACK(`propagation.effectConfirmedAt`).
  문구: "이 표는 참고용입니다. 실제 판정은 결제 시점에 PAYMENT가 합니다". 가맹점 API Credential 상태는 표시하지 않는다(MASTER 상세에 없음 — L-17).
- **승인함**: 종류 라벨 7개, 상세 pinned(대상 상태·Site·통화 기간 KST·Route 매입사/routeRef/마스킹 참조·등록자), "근거 보기"(APPROVER·요청자만), 상태별 문구는 §9.6 그대로.
- B2 등록 탭·첫 MID 패널의 "결제수단·Route 개통 전 결제 불가" 문구 → 배치 배포 뒤 "가맹점 조회 상세 → 개통"으로 안내.

### 9.18 감사·권한(B3)

| 항목 | 1인(등록 2·Site 거절) | 2인(7종류) |
|---|---|---|
| 역할 | OPERATOR만 | 요청 OPERATOR, 승인·거절 APPROVER + 직전 TOTP(요청자≠승인자, Route는 Q-MOPS-24), 철회 요청자, 근거 보기 APPROVER·요청자 |
| ADMIN 감사 | 명령별 code 3개(§9.16) 시도·결과 2행, 자유 문구 없음 | 기존 승인 감사 code(§9.7), target_ref = merchantId |
| MASTER | `admin_request`·변경 이력(actor `admin:…`)·Outbox·Snapshot(기존) | + `admin_approval_evidence`(operation 7 추가), 이력 actor = approvedBy |
| 넣지 않는 값 | 등록번호·채널 식별값·근거 참조·**기관 가맹점 참조 원문**(D-MS2-005)·MASTER 키·서명 | 같음 + 본문 바이트·TOTP |

D-MOPS-005 위험 추가 기록(L-1 확장): 조회 키가 유출되면 1인 `site-review-rejections`로 Site를 **되돌릴 수 없게** 거절할 수 있다(REJECTED 종결 — D-MS-005). 금융 효과는 없음(결제 차단 방향). 탐지: `command{operation=DecideSiteReview}` 지표·감사 대조(L-2).

### 9.19 "결제 가능" 시점과 전파 경로(코드 확인)

전파: 모든 B3 명령은 같은 MASTER TX에서 `work.publishEntitlement` → `entitlement_snapshot_issue` + Outbox `ENTITLEMENT_SNAPSHOT`(HIGH) → TX 밖 `EntitlementSchedules` → `EntitlementRelayService` → `HttpEntitlementDispatcher`(HMAC 서명 — D-MS-004)
→ PAYMENT 권리 투영 저장 → ACK → MASTER `EntitlementPropagationQuery.merchant`(상세 `propagation` — `effectConfirmedAt`). 발행만으로 효력 확인을 표시하지 않는다(ACK 또는 이전 lease 만료).

| 배치 뒤 | PAYMENT `EntitlementEvaluator`(CreatePayment 판정 F1·E1~E5) | `AuthorizationTermsEvaluator`(승인 시작 A2~A4) |
|---|---|---|
| B2-3 | E3 `METHOD_NOT_ENABLED`(Site 심사 PENDING) | — |
| B3-1 | E3·E4 통과 → E5 `CURRENCY_NOT_ENABLED` | — |
| **B3-2** | **Allowed**(Snapshot ACK·lease 유효·계약 Revision이 그 시각 포함일 때) — **처음으로 "결제 생성 판정 통과"** | A2 `CARD_COMPANY_NOT_ENABLED` |
| B3-3 | Allowed | A2 통과 → A3 `POLICY_ASSIGNMENT_MISSING`(수수료·정산 배정 없음) |
| B4 | Allowed | A3 통과, A4 한도 없으면 `AuthorizationStart` `LimitPolicyUnavailable` 거절 → 한도 등록 뒤 **승인 시작 가능** |

- 따라서 **B3만으로는 승인(결제 완료)이 불가**하다. 수수료·정산 정책 배정(assignPolicy — 정책 버전은 seed가 만든 Demo 버전 재사용)과 한도 등록은 B4다.
- **가맹점 API 호출 불가(L-17)**: PAYMENT Demo Credential은 한 행뿐(V29 `demo_credential_issuance` id = 1 CHECK — seed MID). 새로 만든 MID로 가맹점 서버가 CreatePayment를 부를 Credential 경로가 없다(MASTER-CREDENTIAL-001). 그래서 B3 Demo는 결제 시도가 아니라 상세 상태·개통 점검표·전파 ACK로 보여 준다.
- 계약 Revision 시작일이 미래면(B2 Demo의 "내일") E2 `CONTRACT_NOT_EFFECTIVE` — Demo는 오늘 시작 Revision이 승인된 가맹점을 쓴다.

### 9.20 8대 원칙 적용(B3)

| 원칙 | 의미 | 증거(§8.3) |
|---|---|---|
| Financial Consistency | 금액 없음. 결제 허용 입력(개통·확인·심사·통화·Route)만 바뀌며 Snapshot과 같은 TX. 확정 사실(전이 이력·확인·심사 결정·근거 행)은 append-only, Site 거절은 종결(D-MS-005) | B3-1 2·7, B3-2 4 |
| Transaction Safety | MASTER: 업무 효과·근거 행·결과·이력·Outbox·Snapshot 한 로컬 TX, 전파는 TX 밖. ADMIN: TX1 → TX 밖 POST → TX2 | 장애 주입 rollback(B3-1 2) |
| Idempotency | 승인 하나 = `mop1.<approvalId>`, 1인은 `mo1.…`, 저장 바이트 재전송, `operation` 필드로 기한 확인 대상 고정(C-39) | B3-1 1·5 |
| Concurrency Control | MASTER merchant 행 잠금이 직렬화 지점(경로별 순서 §9.14), 잠금 아래 Domain 판정(열린 Activation·통화 중첩·Route 중복), 생성 열 UNIQUE는 보조 방어선, ADMIN 그룹별 미해결 하나(C-35) | 실제 MySQL 두 연결(B3-1 4, B3-2 3, B3-3 4) |
| Recoverability | B2와 같은 회복 순서·종결 규칙(C-18·C-28), 응답 유실 = EXECUTION_UNKNOWN, 효과 단정 없음 | B3-1 5·8 |
| Reconciliation | ADMIN 실행 행 ↔ MASTER 근거 행 1:1(approvalId·`mop1`·소유 참조), Snapshot revision ↔ PAYMENT ACK(전파 조회) | B3-1 2·8, §9.19 |
| Auditability | ADMIN 감사(1인 code 3·승인 감사) + MASTER 근거 행·개통 이력(Domain 근거 = 승인 근거 C-36)·Route 기관 참조는 MASTER 이력에만(D-MS2-005) | B3-1 2, B3-3 2 |
| Observability | 기존 `approval_rejected{operation,rejection}`·`approval_executed{operation}`에 operation 7 추가, `command{operation}`(1인), 전파 지표(`EntitlementPropagationGauges`)·승인 조건 누락 지표(`AuthorizationTermsCoverageGauges`)로 "결제 생성 가능 ↔ 승인 불가" 차이 관찰 | B3-1 6 |

### 9.21 기술 선택 연결(이번 변경에 한함)

- **JPA** — 새 Entity 없음. 근거 행은 B2 `JpaApprovalEvidenceEntity`(`@Immutable`, `persist` 뒤 즉시 `flush` → `StoreConstraint(businessKey)`)를 그대로 쓴다. 예: 개통 승인 TX에서 `payment_method_activation` 갱신(`@Version`)·전이 이력 INSERT·PRIMARY 확인 INSERT·근거 INSERT가 한 로컬 TX로 commit.
  잠금은 기존 `PESSIMISTIC_WRITE`(경로별 순서 §9.14 — Site 심사는 merchant만). 주의(rev 0.8.3 minor-6 정정): 개통 등록·통화·Route 중복은 **merchant 잠금 아래에서 Domain이 먼저 판정**하므로 경합 패자는 잠금 대기 뒤 곧바로 `ACTIVATION_DUPLICATE` 등으로 거절된다(1062를 거치지 않음). 열린 Activation·Route·통화 **생성 열 UNIQUE는 보조 방어선**이며, 그 위반이 나면(결함·잠금 우회) flush 시점 1062 → 기존 업무 유일키 재시도(새 TX)로 같은 거절에 수렴한다. 근거 행 PK(approvalId)는 잠금 밖 판정이라 이 재시도 경로가 주 경로다(B2 m-1). 순수 Domain(`PaymentMethodActivation`·`CardRouteActivation`·`SiteReviewRules`)은 JPA를 모른다(Mapper는 adapter).
- **jOOQ(CQRS)** — MASTER `JooqMerchantDetailReader`에 계정별 Site 적용 확인 목록(+ Q-MOPS-24면 Route `registered_by`, Q-MOPS-22면 SQL 마스킹 값) 추가 — `evidence_ref`·`reason`·`institution_merchant_ref` 원문은 SELECT하지 않는다. ADMIN `pendingApprovals`는 기존 Reader 그대로(종류만 늘어남).
- **버전** — 새 의존성 없음(Boot BOM 관리 조합 그대로). Flyway MASTER V11·ADMIN V11, ADMIN jOOQ codegen 재생성.
- **Kafka/Redis** — 도입하지 않는다. Snapshot 전파는 기존 Outbox + 서명 HTTP relay + ACK이고, 승인 1회성·직렬화는 소유 DB 제약·로컬 TX다. 브로커·분산 lock은 이 보장(같은 TX의 Outbox, ACK 기반 효력 확인)을 대신하지 못한다.

### 9.22 migration(C-21 관례 확인: MASTER·ADMIN 모두 최신 V10 — 홀수 schema·짝수 privilege, C-42)

- **MASTER `V11__admin_approval_evidence_b3_operations.sql`**: `ck_admin_approval_evidence_operation`을 **한 ALTER 문**(`DROP CHECK …, ADD CONSTRAINT …` — nit-3, 중간 상태 없음)으로 9값(B2 2 + `ApprovePaymentMethodActivation`·`DecideSiteReview`·`EnableCurrency`·`ApproveCardRouteActivation`·`ResumePaymentMethodActivation`·`ResumeCardRouteActivation`·`ConfirmSiteActivation`)으로 재생성. 새 표·열 없음 → privilege 변경 없음, **V12 비움**(V8 선례).
  B3 operation 7개를 B3-1에서 한 번에 넣는다(rev 0.6.5 ADMIN V9 선례 — 앱은 각 배치 전까지 그 operation 경로를 만들지 않는다). `admin_request`에는 operation CHECK가 없음(V1 확인).
- **ADMIN `V11__merchant_ops_activation_approval.sql`**: `ck_admin_approval_request_kind`·`_target`(MERCHANT 대상 = 가맹점 9종류)·`_owner_states` 재생성, `unresolved_key` 생성 식에 C-35 그룹 분기(표 재작성 — V7·V9와 같은 주의), `ck_admin_merchant_ops_command_kind` 9값. 표·권한 변경 없음 → **V12 비움**.
- Q-MOPS-22·24 답에 따라 열이 필요해지면(예: ADMIN에 기관 참조 HMAC 보관) MASTER·ADMIN V13/V14로 따로 둔다(B3-3 착수 시 확정).

### 9.23 결정·질문 연결

재질문하지 않는 것: D-MOPS-004·005·007 ③(2인/1인 분류)·008(근거 필수·seed 예외·Revision 등록자), D-MD-016~018, D-MS-002·003·005, D-MS2-004·005(서명 Snapshot 전달·내부 식별자 등급). 새 질문 후보 **Q-MOPS-22·23·24**(§11.2). 가역 C-35~C-44(§10).

### 9.24 Demo 시나리오(Coordinator — Demo 값, 운영값 아님)

- **B3-1**: B2-3까지 끝난 가맹점(오늘 시작 Revision 승인·MID·PRIMARY Site) → OPERATOR(A) "개통 등록(CARD)" → `act_…` UNDER_REVIEW·권리 revision +1 → A "개통 승인 요청"(근거 참조) → APPROVER(B) TOTP 승인 → ACTIVE + PRIMARY 적용 확인 표시 →
  A "Site 심사 승인 요청" → B 승인 → APPROVED → 점검표 ①~④ 통과·⑤ 미충족, 전파 ACK 표시. 두 번째 Site로 "심사 거절"(확인 대화상자) → REJECTED·다시 버튼 없음. (경합·응답 유실·seed 관문은 시험 보고서.)
- **B3-2**: A "통화 활성화 요청(KRW, Q-MOPS-23 형식)" → B 승인 → `cur_…` → 점검표 "결제 생성 조건 충족" + "승인 시작 조건: Route·수수료·정산·한도 필요(B3-3·B4)" + "새 MID는 가맹점 API Credential이 없어 Demo 결제 호출 불가(L-17)" 안내 확인.
- **B3-3**: A "Route 등록"(매입사·routeRef·기관 가맹점 참조) → 마스킹 표시 → "개통 승인 요청" → B 승인(등록자 규칙 Q-MOPS-24) → ACTIVE → 점검표 ⑥ 통과.
- **B3-4**(B5 뒤): B5로 수단 중지·PRIMARY 확인 철회 → "재개 요청"(새 근거)·"Site 재확인 요청" → 승인 → ACTIVE·유효 확인.

## 10. Coordinator 결정(가역 — rev 0.2: C-1~C-11 권장안 **채택**, C-12·C-13 추가. rev 0.3: C-13 개정·C-14 추가 — 채택 권장. rev 0.5: C-15~C-26 B2 — **권장안 채택 표시**, Reviewer 확인 대상. rev 0.6: C-18 대안 채택·C-26 사용자 결정 확정·C-27~C-29 추가. rev 0.6.1: C-30. rev 0.6.2: C-31. rev 0.6.3: C-32. rev 0.6.4: C-33. rev 0.8: C-35~C-44 B3 — 권장안 채택 표시, C-35는 Coordinator 결정·C-36은 질문 승격 판단 대상)

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
| C-15 (rev 0.5) | MASTER 근거 행 범위 | **성공(EXECUTED)과 저장된 거절(REJECTED) 모두 근거 행**(D-ADMIN-007 자기 감사·D-CANCEL1-006 선례 — 같은 행위자 시도의 흔적이 MASTER에 남음). 예외: `APPROVAL_ID_ALREADY_USED`는 PK 충돌이라 `admin_request` 거절 + WARN + 지표만 | 성공만 근거 행(거절은 `admin_request`에만 — 요청자·승인자 값이 남지 않음) |
| C-16 (rev 0.5) | D-ADMIN-007 "대상 버전" 해석 | **명령 필드 자체가 대상 고정값**(Revision 내용 불변 — `contractId#revisionNo`, 첫 MID는 "최초 1회" 규칙·CSA·Site ID) + ADMIN 승인 시 pinned vs 현재 비교(INVALIDATED) + MASTER 업무 규칙 재판정. `target_security_version` = 0 | 가맹점 `entitlementRevision`을 대상 버전으로(Site 등록 등 무관한 변경에도 승인 무효 — 불필요한 재요청) |
| C-17 (rev 0.5) | 승인 명령 키·actor | **키 = `mop1.<approvalId>`(MASTER가 일치 검사), correlationId = `mop.<approvalId>`, `actor` 필드 없음 — MASTER가 approvedBy로 정함**(실행자 = 승인자, 둘이 어긋날 여지 제거) | B1처럼 `mo1.<계정>.<id>` + 별도 `actor`(승인 하나가 여러 키를 가질 수 있음) |
| C-18 (rev 0.6 개정 — Coordinator 결정, review M-1·M-2) | 결과 불명 회복 수단 | **대안 채택: MASTER 쓰기 효과 없는 두 호출** — ① 서명된 결정 조회 `approval-decisions`(근거 행 반환, NONE은 효과 없음 근거 아님) ② 기한 확인 전용 `approval-deadline-confirmations`(MASTER 시각 now ≥ executeBefore이고 결정 없을 때만 `APPROVAL_EXPIRED` 저장, 실행 본문 호출 없음, 기한 전이면 rollback·`NOT_YET_DUE`). ADMIN 회복 순서 = PAYMENT·FINANCIAL과 같음(결정 조회 → 기한 확인 → 참여자 재검사 → 무결성 플래그면 조회만 → 같은 바이트 재전송). 기한 뒤 실행 POST 없음 | (rev 0.5안 폐기) 조회 endpoint 없이 저장 바이트 재전송으로 확인·기한 뒤 실행 재전송 = 기한 확인 — 재전송 중단 건이 기한까지 미확정(M-1), 기한 단계가 실행 경로를 탐(M-2) |
| C-19 (rev 0.5) | 실행 창·시계 | **Demo 값**: ADMIN execution-window `PT1H`, deadline-grace `PT2M`, MASTER max-execution-window `PT24H`·max-clock-skew `PT1M` — 기본값 없음, 운영값 아님(LIVE Gate) | ACQ와 같은 `PT24H` 창 |
| C-20 (rev 0.5) | ADMIN 미해결 키 | **`MERCHANT:<merchantId>` — 두 종류 합쳐 가맹점당 미해결 하나**(온보딩은 순차: 계약 승인 → MID) | 종류별 키(계약 승인 대기 중 MID 요청 가능 — 그러나 MID는 승인 Revision이 필요해 실익 없음) |
| C-21 (rev 0.5) | migration 번호 | **MASTER V9(schema)·V10(privilege), V8은 비움**(기존 홀수 = schema·짝수 = privilege 관례, MASTER V7은 privilege 짝 없음). **ADMIN V9·V10**. Flyway는 빈 번호 허용 — 이후 V8 추가 금지(outOfOrder 쓰지 않음) | MASTER V8(schema)·V9(privilege)(번호 연속, 관례 깨짐) |
| C-22 (rev 0.5) | Revision 등록 감사 targetRef | **계약 ID + 성공 detail `rev=<n>`**(`TARGET_REF` 형식 `[a-z]{3,4}_ULID` 유지) | targetRef 형식에 `#n` 허용(감사 열 규칙 확장) |
| C-23 (rev 0.5) | `mop1` 지문 | **두 앱이 같은 정규 문자열로 독립 계산, MASTER 불일치 400**, 같은 독립 벡터를 양쪽 시험에(harness golden 불변 — C-9와 같은 이유) | MASTER가 계산만 하고 ADMIN 값은 검사 안 함(대조 불가 결함이 숨음) |
| C-24 (rev 0.5) | 상세 화면 승인 대기 표시 | **ADMIN 상세 API에 `pendingApprovals`(ADMIN DB jOOQ 조회 — 승인 목록 권한 역할에게만)** | 화면이 승인함 목록을 따로 불러 거름(가맹점별 필터 없음) |
| C-25 (rev 0.5) | B2 Demo 분할 = 전달 순서 | **B2-1(Revision 등록 1인) → B2-2(승인 근거 기반 + 계약 승인) → B2-3(첫 MID)** 세 번 보여 준다(§8.2·§9.11) | B2-2·B2-3 한 번 |
| C-26 (rev 0.6 — 사용자 결정 D-MOPS-008 ②로 확정, D-DEMO-001·005 근거) | Demo seed의 근거 없는 승인 경로 | **유지 + 진입 관문**: 근거 없는 두 메서드는 행위자 **정확히 `system:demo-seed`** 그리고 required Port `DeploymentModePort.isDemo()` true일 때만(구현 = `DemoCommandModeGate.isDemo`에 위임하는 별도 adapter bean — review n-d), 아니면 결함 거부. 운영 차단 = 설정 관문(LIVE seed 거부 — 기존)·ArchUnit(호출자 `DemoMerchantSeedService`만)·handler 전수 시험 | (사용자 결정으로 대안 없음 — 이전 대안 'seed도 근거 행 기록'은 채택 안 됨) |
| C-27 (rev 0.6) | Revision 등록자≠승인자 구현 위치(D-MOPS-008 ③) | **MASTER Domain `Contract.approveWithEvidence`(근거 경로만 — C-31)에 새 거절 `APPROVER_IS_REVISION_REGISTRANT`**(REVISION_ENDED 뒤·ALREADY_APPROVED 앞, 기존 `contract_revision.registered_by` 사용 — 새 열 없음) + ADMIN 승인 TX1 403 `APPROVER_IS_REGISTRANT`(pinned registeredBy — MASTER 상세에 `registeredBy` 추가). 첫 MID 발급에는 적용 안 함 | ADMIN만 검사(D-MOPS-008 ③ 양쪽 검사와 다름 — 불가) |
| C-28 (rev 0.6, review M-1) | 저장되지 않는 MASTER 응답 매핑·종결 | 409 → 무결성 플래그·실행 POST 중단·조회로 수렴, 400·413 → 재전송 중단 `OWNER_MALFORMED`·조회·기한 확인만, 401·404 → UNKNOWN·경보·다음 주기, 503·timeout → UNKNOWN. 기한 뒤에도 확정 불가(조회 NONE + 기한 확인 400/409) → **자동 종결 없음**, `OWNER_UNRESOLVABLE` finding 1회·경보, 조회 backoff 상한 Demo PT30M | 확정 불가 건을 기한 뒤 '효과 없음'으로 자동 종결(UNKNOWN 규칙 위반 — 불가) |
| C-29 (rev 0.6) | 기한 확인 결과 표식 | 근거 행 `decided_via`(`EXECUTE`·`DEADLINE_CONFIRM`) + CHECK(DEADLINE_CONFIRM은 APPROVAL_EXPIRED 거절만). 결정 조회 응답에 `decidedVia` | 표식 없음(만료가 늦은 실행 요청인지 회복 job인지 구별 불가) |
| C-30 (rev 0.6.1, review n-7) | Revision 번호 상한 | **Domain·DB에 상한 없음 확인**(`Contract` 연속 번호 규칙·V1 `revision_no INT`·`ck_contract_revision_no >= 1`뿐) → 새 Domain 규칙을 만들지 않고 MASTER parser·ADMIN 응답 모양 정규식을 INT 범위(`[1-9][0-9]{0,9}` + ≤ 2147483647)로 맞춤. reference 최대 41자 ≤ 열 64 | Domain 상한 9999 + 새 거절(업무 규칙 추가 — 근거 없음) |
| C-31 (rev 0.6.2 — Coordinator 결정, 재리뷰 새 M-1) | Revision 등록자≠승인자 적용 범위 | **근거 있는 경로에만**: `Contract.approveWithEvidence(revisionNo, at, approver)`가 ENDED 뒤·ALREADY_APPROVED 앞에서 registeredBy와 비교. 근거 없는 seed 경로(`system:demo-seed` + Demo 모드)는 D-MOPS-008 ②·D-DEMO-005 예외로 검사 없음. Domain에 행위자 이름 하드코딩 금지 | 모든 경로에 적용(seed 1인 승인이 깨짐 — D-DEMO-005와 충돌) / Domain이 `system:` 접두로 분기(하드코딩) |
| C-32 (rev 0.6.3 — Coordinator 결정, B2-1 Demo 지적) | Revision 폼 성공 뒤·승인 상태 표기 | **성공하면 완료 상태**(입력 읽기 전용·제출 버튼 없음 — 같은 폼 재클릭으로 새 submissionId의 Revision n+1 중복 생성 방지), "다른 Revision 새로 입력"만 새 submissionId·빈 폼. 승인 상태 표기는 상세 표 기준 **"승인 전"**으로 통일(등록 성공 문구 포함) | 성공 뒤에도 바로 재입력 가능(B1 단계 방식 — 같은 내용 중복 위험) / "미승인" 유지 |
| C-33 (rev 0.6.4 — Coordinator 결정, B2-2 구현 중) | 기한 확인(`approval-deadline-confirmations`) 응답 모양 | **실행과 같은 §3.3 모양**(성공·거절·재생·409·503), 기한 전만 `{"decision":"NOT_YET_DUE"}` — `APPROVAL_ID_ALREADY_USED`에는 자기 근거 행이 없어 `decidedVia`가 없다. ADMIN은 실행 응답 해석기를 그대로 쓰고, `decidedVia`·지문 대조는 결정 조회로 한다 | 결정 조회 모양(`decidedVia` 포함 — rev 0.6 §9.4 문구, 판정 3에서 값이 정의되지 않음) |
| C-34 (rev 0.7 — Implementer 가역 선택, Coordinator 확인 대상) | B2-2 리뷰 n-2 처리·B2-3 미리 보기·Demo 개선 | **n-2**: 성공 reference가 이 승인의 대상이 아니면(계약 승인 = pinned `contractId#revisionNo`, 첫 MID = `mid_` 문법) EXECUTED로 닫지 않고 EXECUTION_UNKNOWN + 무결성 플래그 `REFERENCE_MISMATCH`(실행 POST 중단 — 조회·기한 확인으로만, 사람 검토) + 경보. **rev 0.7.1(B2-3 리뷰 m-1, Coordinator 채택)**: 경보는 플래그를 처음 세울 때 1회, 같은 응답 반복(회복 결정 조회 등)은 `quietIfUnchanged`로 감사·lastFailure를 다시 쌓지 않으며, 운영 대사 finding `OWNER_UNRESOLVABLE`(detail `REFERENCE_MISMATCH`)을 1회 남긴다 — 자동 종결 없음(C-28과 대칭). 시험: 회복 N주기 POST 0·감사 1·finding 1. **B2-3 미리 보기(advisory)**: MID 0개 → Revision 있음·승인·끝나지 않음(적용 Revision 판정 — 지금 적용 또는 시작 시 적용 — 은 MASTER 권위라 화면·ADMIN은 거르지 않음) → 그 계약 CSA → Site 있음·ACTIVE·Scope 안 순서로 첫 사유를 감사 detail에. A 단계 재조회가 부적격이면 현재 = 없음(TARGET_CHANGED). **화면**: 승인 요청 결과는 상세 재조회 뒤에도 패널 위에 남김(닫기 전까지·다른 가맹점을 열면 지움), 승인 대기 배너에 대상(Revision n·첫 MID 발급) — 상세 API `pendingApprovals[].revisionNo`(계약 승인만, 고정 대상 JSON에서), 감사 화면 "대상" = targetActorId 또는 targetRef(L-13 감사 대상 열 — API에 `targetRef` 추가) | n-2를 IntegrityViolation(rollback·EXECUTING 유지)으로 — 회복 job이 같은 응답을 계속 받아 반복 |
| C-35 (rev 0.8 — **rev 0.8.1 Coordinator 채택**) | B3 ADMIN 미해결 키(C-20의 B3 적용) | **대상 그룹별 가맹점당 하나**: B2 두 종류는 `MERCHANT:<merchantId>` 그대로, B3는 `MERCHANT:<그룹>:<merchantId>`(그룹 `PM`·`SITE_REVIEW`·`CURRENCY`·`ROUTE`·`SITE_CONFIRM` — 승인·재개는 같은 그룹). 개통은 서로 다른 대상(수단·Site·통화·Route)을 병렬 요청하는 것이 자연스럽고, MASTER merchant 잠금이 실행을 직렬화하므로 정확성은 키에 기대지 않는다. Site 여러 개의 심사는 같은 그룹이라 순차. **rev 0.8.1**: 그룹 대기 중에도 Site 1인 거절 버튼은 숨기지 않음(안전 방향) | (a) C-20 그대로 `MERCHANT:<id>` 하나(가장 단순·migration 식 변경 없음, Demo에서 4번 순차 승인 — B4도 같은 키에 묶임) (b) 대상 객체별 키(`act_`·`site_`·`cra_`…) — 가장 병렬, 새 열(대상 하위 참조) 필요 |
| C-36 (rev 0.8 — rev 0.8.1 Coordinator: 질문으로 올리지 않음, 입력란 의미 표시 minor-5) | 2인 B3 명령의 근거 입력 | **승인 근거 `evidenceRef`(D-MOPS-008 ①) = Domain 기관 근거 참조(D-MS-003) 한 값** — 형식이 같고(`EvidenceRef`·V9 CHECK) 같은 문서 참조이며, 두 입력은 불일치 위험만 만든다. MASTER Domain 근거는 기존 저장 위치(개통 이력·변경 이력·Outbox 참조 payload) 그대로, ADMIN은 B2 규칙(감사·목록에 없음, 근거 보기만) | 두 값을 따로 입력(승인 근거 = 내부 결재 번호, 기관 근거 = 카드사 심사 번호) — 본문·지문·화면 필드 1개 추가 |
| C-37 (rev 0.8) | Site 심사 사유(`DecisionReason` — Domain은 자유 문구 허용) | **ADMIN 고정 코드 목록**(Demo: 승인 `REVIEW_PASSED`, 거절 `DOCUMENT_INSUFFICIENT`·`PROHIBITED_BUSINESS`·`SITE_UNREACHABLE`·`OTHER_POLICY`)을 코드 문자열로 전송, MASTER parser도 같은 목록만(C-10 복제). 자유 문구 불가 규칙(B1·B2)과 같음. 목록은 Demo 값 — 운영 사유 체계 아님(LIVE Gate) | 자유 문구 입력(개인정보·로그 위험, 기존 규칙과 다름) |
| C-38 (rev 0.8) | B3 분할·순서 | **B3-1 → B3-2 → B3-3 → (B5) → B3-4**(§9.13): 각 배치 Demo, B3-4(재개·재확인)는 중지·철회(B5 1인)가 있어야 화면으로 보여 줄 수 있어 B5 뒤. 질문 대기 배치(B3-2 Q-23, B3-3 Q-22·24)는 B3-1 진행 중 답을 받는다. B4는 "승인 시작 가능"에 필요 — B3-3 뒤 B4 권장(Coordinator) | B3 한 번에 / B3-4를 B3-3 직후(시험 보고서로만 시연) |
| C-39 (rev 0.8) | 기한 확인 해석기의 operation 판별 | **B3 승인 본문에 `operation` 필수 필드**(경로와 같아야 함, 이미 `mop1` 2번째 줄) — 승인·재개가 같은 필드 모양(`activationId`)이라 본문 모양으로 구별할 수 없다. B2 두 본문은 기존 모양 판정 유지(저장 바이트 불변) | 기한 확인 endpoint를 operation별 경로로 분리(C-2 경로 규칙과 다름) / 헤더로 전달(서명 대상 밖 위험) |
| C-40 (rev 0.8 — D-MOPS-008 ② 적용) | 1인/2인 채널 분리·seed 관문 | **Site 심사는 결정값을 본문에 받지 않는다**: 1인 `site-review-rejections` → 새 `rejectSiteReview`(REJECTED 고정), 2인 `site-review-approvals` → `approveSiteReviewWithEvidence`(APPROVED 고정). 근거 없는 2인 메서드 7개는 `requireDemoSeedPath` + ArchUnit(호출자 `DemoMerchantSeedService`만). 기존 시험의 준비 호출은 seed 행위자로(B2-2 선례) | 한 endpoint + `decision` 필드(1인 채널로 APPROVED를 보낼 수 있어 2인 우회 — 불가) |
| C-41 (rev 0.8 — CLAUDE.md 확정 규칙 적용) | 통화 활성화 대상 | **KRW만**(ADMIN 값 객체·MASTER parser 모두). MASTER `currency_unit`에 USD가 있으나 "실제 통화 추가안은 사용자 채택 전"(CLAUDE.md) — USD는 사용자 채택 뒤 별도 | currency_unit 전체 노출(미채택 통화 개통 — 불가) |
| C-42 (rev 0.8) | migration 번호·범위 | **MASTER V11(operation CHECK 9값)·ADMIN V11(종류·대상·상태·미해결 키·command_kind)을 B3-1에서 한 번에**, 두 앱 V12 비움(privilege 변경 없음 — V8 선례). 이후 V12 추가 금지(outOfOrder 없음) | 배치마다 migration(V11·V13·V15 — CHECK 재생성 반복) |
| C-43 (rev 0.8) | B3 Demo 표시 | **MASTER 상세 확장(계정별 Site 적용 확인 목록 — 근거·사유 없음) + 화면 개통 점검표("참고, 권위 아님")** — PAYMENT 판정을 화면이 대신하지 않는다(§9.17). 결제 가능 여부의 권위는 PAYMENT | PAYMENT에 판정 미리 보기 API 추가(서비스 간 새 조회 채널 — 범위 밖) |
| C-45 (rev 0.8.1 — 리뷰 minor-1, Coordinator 채택) | 개통 승인의 PRIMARY Site 고정 | **명령 필드 `primarySiteId`를 `mop1`에 포함**, MASTER는 실행 시점 열린 PRIMARY와 다르면(없음 포함) `PRIMARY_SITE_MISMATCH` 저장 — 승인자가 본 PRIMARY와 다른 Site에 적용 확인이 기록되지 않게 | pinned 비교만(ADMIN A 단계 — 승인 TX1 뒤 MASTER 실행까지의 변경은 못 막음) |
| C-44 (rev 0.8) | 매입사 코드·routeRef 입력(B3-3) | **형식 검사만**(`[A-Z0-9]{1,32}` — MASTER V7과 같음) + 화면에 Demo seed 값 안내. 틀린 routeRef는 PAYMENT 기관 프로파일이 없어 승인 시작에서 막힌다(fail-closed) | ADMIN 설정 허용 목록에서 선택(Demo 값 관리 지점 증가) |

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

**답변됨(rev 0.5 — D-MOPS-007, 2026-11-10, 모두 권장안)**

| ID | 답 | 적용 |
|---|---|---|
| Q-MOPS-16 | 2인 승인 명령은 MASTER도 승인 근거(요청자·승인자·승인 ID·시각 — *지문은 D-MOPS-007 문구에 없고 C-23 추가분*)를 명령과 같은 로컬 TX로 저장하고 요청자≠승인자를 재확인, 위반 시 거절 | §9.3(`admin_approval_evidence` V9/V10, `APPROVAL_SAME_ACTOR` 등 저장된 거절, C-15) |
| Q-MOPS-17 | D-MOPS-004에 없는 명령: UNDER_REVIEW로 남는 등록·Site 심사 REJECTED·한도 하향 = 1인, 재개·한도 종료·정산 정책 배정·배정 종료 = 2인 | §0·§9.12(B3~B5 개요) |
| Q-MOPS-18 | 계약 Revision 등록에도 효력 시작일이 KST 오늘 이전이면 `CONTRACT_START_RETROACTIVE` | §3.4·§9.2, §8 n-1 회귀 시험 기대 반전(§8.2 B2-1-2) |

**답변됨(rev 0.6 — D-MOPS-008, 2026-11-10, 모두 권장안, `harness/questions.json`)**

| ID | 답 | 적용 |
|---|---|---|
| Q-MOPS-19 | B2 2인 승인 요청(계약 승인·첫 MID)의 업무 근거 참조 **필수** — 재매입 `evidence_ref`와 같은 형식(자유 문구 불가), APPROVER·요청자만 별도 조회·조회마다 감사(D-ACQA-005·D-OPS-010과 같음), MASTER 본문·`mop1` 지문·근거 행에 포함 | §9.3(본문·정규 문자열·V9 `evidence_ref` 확정), §9.5(요청 본문·명령 표·근거 보기), §9.6·§9.7 |
| Q-MOPS-20 | Demo seed의 근거 없는 승인 경로는 Demo 예외(D-DEMO-001·005)로 유지, 행위자 `system:demo-seed`·Demo 모드에서만, 운영은 설정 관문·ArchUnit으로 차단 | §9.3 구조·C-26 |
| Q-MOPS-21 | 계약 Revision 등록자≠그 Revision 승인자 — ADMIN·MASTER 양쪽 검사, 승인 화면에 등록자 표시 | §9.3 판정 5(`APPROVER_IS_REVISION_REGISTRANT`), §9.5 TX1 403, §9.6, C-27(기존 `registered_by` 사용 — migration 열 불필요) |

### 11.2 PENDING

rev 0.8 — B3 질문 후보 3건(`harness/questions.json` PENDING, Coordinator가 사용자에게 묻는다). 답 전 해당 배치의 운영 동작은 구현하지 않는다. B3-1은 막히지 않는다.

| ID | 질문 | 권장안 | 대안 | 영향 | 막히는 배치 |
|---|---|---|---|---|---|
| **Q-MOPS-22** | 카드 Route 기관 가맹점 참조(D-MS2-005 내부 식별자 등급)의 입력·전송·감사·관리 조회 형태 | ① OPERATOR가 1인 Route 등록 화면에서 입력(형식 `[A-Za-z0-9._:-]{1,64}`), ADMIN은 **저장하지 않고** 서명 본문으로만 MASTER에 전달 — ADMIN DB·감사·로그·지표·오류에 원문 없음(감사 detail은 `imr=PRESENT`만). ② MASTER는 기존대로 `card_route_activation`·변경 이력 detail·서명 Snapshot에만(Outbox 통합 이벤트 제외 — 기존 코드). ③ 관리 조회(상세·승인 pinned)는 MASTER SQL이 만든 **뒤 4자리 마스킹 `****1234`**(4자 이하는 `****`)만, 원문 조회 기능 없음 — 승인자는 근거 문서의 뒤 4자리와 대조. ④ 정정은 수정 없이 종료(B5) 후 재등록 | (a) 원문 조회 기능 — APPROVER·요청자만, 조회마다 감사(D-ACQA-005 "근거 보기" 방식) (b) 마스킹 형식 앞 2 + 뒤 2 (c) ADMIN이 HMAC만 보관해 MASTER 값과 사후 대조(ADMIN migration 열 추가) | MASTER 상세 jOOQ(마스킹 SQL)·ADMIN pinned·감사·화면 입력/표시, 로그 비노출 시험. **확정 때 함께 확인(rev 0.8.3 nit-4)**: 기존 `registerCardRouteActivation`이 `institutionMerchantRef` 원문을 요청 지문 입력에 넣는다(`MerchantAuthorizationTermsService` 지문 필드 목록) — 저장되는 `admin_request.request_fingerprint`가 값 공간이 작은 원문의 무염 SHA-256이 될 수 있다(L-4 등록번호 선례와 같은 성격). 지문 입력에서 빼거나 HMAC으로 바꿀지 Q-22 답과 함께 정한다 | B3-3 |
| **Q-MOPS-23** | 2인 통화 활성화의 효력 시작 시각. Domain은 `from ≥ 기록 시각`(소급 금지 D-MD-018)을 정확한 시각으로 비교하므로 KST 날짜 00:00 입력은 "오늘"을 쓸 수 없다(승인 대기 동안 지나면 `CURRENCY_ENABLEMENT_RETROACTIVE`) | **두 선택지**: "승인 실행 즉시"(본문 `effectiveFrom: "AT_EXECUTION"` — `mop1`에 포함, MASTER가 잠금 뒤 시각으로 확정, 실행 창 Demo ≤ 1시간) 또는 "KST 날짜 00:00(내일 이후)". 승인 화면에 어느 쪽인지 표시 | (a) 날짜만(가장 이른 효력 = 내일 00:00 KST — Demo 당일 시연 불가) (b) 요청 시각 기준 자동(요청 + execution-window — 승인이 늦으면 거절) | MASTER parser·Use Case(새 Domain 규칙 없음 — from = lockedNow), ADMIN 요청 본문·pinned·화면 | B3-2 |
| **Q-MOPS-24**(rev 0.8.1 확대 — MAJOR-1) | 1인으로 등록한 사람이 그 대상의 2인 승인자가 될 수 있는지(D-MOPS-008 ③ Revision 규칙의 확대): ① 카드 Route 등록자 → Route 개통 승인(B3-3), ② 결제수단 개통 등록자 → 개통 승인(B3-1), ③ Site 등록자 → Site 심사 승인(B3-1). 등록이 승인 대상을 정하므로 같은 사람이면 2인 통제가 사실상 1인 | **셋 다 금지**: ADMIN 승인 TX1(pinned `registeredBy` — 403 `APPROVER_IS_REGISTRANT`) + MASTER Domain 근거 경로(`…WithEvidence`) 양쪽, 새 거절 `APPROVER_IS_ROUTE_REGISTRANT`·`APPROVER_IS_ACTIVATION_REGISTRANT`·`APPROVER_IS_SITE_REGISTRANT`, seed 경로는 예외(C-31 구조), 화면에 등록자 표시. **등록자 열 확인(코드)**: Route `card_route_activation.registered_by`(V5), Activation `payment_method_activation.registered_by`(V1 — `PaymentMethodActivation.registeredBy`), Site `merchant_site.created_by`(V1 — `MerchantSite.createdBy`) 모두 있음 → migration 불필요 | (a) 허용하고 표시만 (b) Route만 금지(rev 0.8 원안) (c) Route·Activation만(Site 등록은 B1 등록 화면이라 등록자와 심사자가 같은 경우가 흔함) | MASTER Domain 3곳·상세 jOOQ `registeredBy`(Activation·Site·Route), ADMIN TX1 403·pinned | B3-3, **B3-1 2인 승인 경로의 DONE 판정**(B3-1 구현은 pinned·화면 표시까지 — 검사는 답 뒤) |

## 12. 위험·미결

| ID | 내용 | 처리 |
|---|---|---|
| L-1 | D-MOPS-005: 조회 키 하나가 명령까지 서명. 유출 = MASTER 직접 등록·(B2 이후) 2인 명령 시도 가능 | 사용자 선택으로 수용. 완화: MASTER 재검사(D-MOPS-007 ① — §9.3 근거 행·요청자≠승인자·1회 사용·실행 기한), `command{operation}`·`approval_rejected` 지표 경보, 키 2개 교체 슬롯(`signing-keys[1..2]`) 절차 문서화(LIVE Gate). 한계는 L-10 |
| L-2 | ADMIN 감사 ↔ MASTER 변경 이력 자동 대사 없음 | correlationId·actor로 수동 대조 가능. 대사 job은 필요 시 후속 |
| L-3 | 새로고침·브라우저 종료 뒤 결과 불명 건은 화면에 남지 않는다(submissionId 메모리) | 조회 화면 read-back. Business만 만든 경우(가맹점 0)는 기존 조회 API가 가맹점 중심이라 **사업자만 있는 건을 볼 수 없다** — 사업자 ID 검색은 가맹점 목록을 돌려준다. 필요하면 사업자 조회 endpoint 후속 |
| L-4 | `admin_request.request_fingerprint`는 등록번호를 포함한 무염 SHA-256 | 같은 DB의 `business.registration_number`가 이미 평문이라 추가 노출은 아님. 기록만 |
| L-5 | 등록번호·채널은 Demo 실행마다 새 값 필요(중복 거절) | walk-through 값 규칙 문서화 |
| L-6 | MASTER-DOMAIN-001 §3.3은 "Site 등록은 entitlement revision을 올리지 않는다"고 쓰지만 코드는 올린다(review M-2) | 이 명세는 코드 기준. 문서 정정은 Coordinator 판단 |
| L-7 | 기존 businessId 비노출(D-MOPS-006 ③)이어도 "이미 등록됨" 응답 자체는 그 번호가 있다는 정보다(oracle) | D-MOPS-006 ④대로 모든 시도 감사 + 중복 결과는 등록번호 검색 버킷 소모(C-13). OPERATOR는 이미 등록번호 정확 검색 권한이 있어 새 권한 확대 아님. **기존 businessId 자체도 등록번호 정확 검색(MERCHANT-OPS-001 — 결과 행의 businessId)으로 여전히 알 수 있고, 그 검색과 이 중복 응답은 같은 등록번호 버킷을 쓴다**(review n-2) — D-MOPS-006 ③은 등록 응답·화면·감사에서의 노출만 막는다 |
| L-9 | 등록번호 버킷·등록 버킷은 **인스턴스 메모리**(MERCHANT-OPS-001 L-6과 같음) — ADMIN 여러 인스턴스면 actor별 상한이 인스턴스 수만큼 늘고, 재기동 시 초기화된다(review m-2) | LIVE Gate(공유 저장소 기반 제한과 함께). Demo 단일 인스턴스 |
| L-8 | `CONTRACT_START_RETROACTIVE`는 판정 시각 기준이라 KST 자정 직전 제출은 잠금 대기 뒤 거절될 수 있다(B2-1부터 Revision 등록도 같음) | 의도된 동작(§3.4). 화면 문구로 안내 |
| L-10 | MASTER 재검사는 **형식·서로 다름·창·1회 사용·업무 규칙**까지다. 서명 principal이 kid뿐이라 requestedBy·approvedBy가 실제 ADMIN 계정·실제 TOTP 승인인지는 MASTER가 모른다 — 조회 키 유출 시 서로 다른 두 `adm_` 값을 지어내 2인 명령을 실행할 수 있다(D-MOPS-005 위험의 남은 부분) | 사용자 수용(D-MOPS-005). 탐지: `approval_rejected` 경보, ADMIN 승인 행 ↔ MASTER 근거 행 대조(approvalId·`mop1` — ADMIN에 없는 approvalId = 우회 신호). 대사 job은 후속(L-2와 함께) |
| L-11 | (rev 0.6 갱신) 결정 조회·기한 확인으로도 확정할 수 없는 건(조회 NONE + 기한 확인 400 지속·409)은 자동 종결되지 않고 EXECUTION_UNKNOWN으로 남는다(C-28) | `OWNER_UNRESOLVABLE` finding·경보. 사람의 종결 절차는 후속(기존 운영 결정 대사 절차 확장 후보). rev 0.5의 "재전송 중단 건이 기한까지 미확정"은 결정 조회로 해소 |
| L-12 | (rev 0.6 갱신) 근거 없는 승인 메서드가 Demo seed용으로 남는다(D-MOPS-008 ② — 사용자 결정) | `system:demo-seed` 정확 일치·`DeploymentModePort.isDemo()`·LIVE seed 거부·ArchUnit·handler 전수 시험 |
| L-13 | **LIVE Gate 항목(B2)**: ① ADMIN↔MASTER 시계 차이 지표·경보(`deadline-grace`·skew 초과), ② ORPHAN 대사(MASTER 근거 행 중 ADMIN에 없는 approvalId — `ORPHAN_OWNER_DECISION` finding) 또는 동등 탐지, ③ `approval_executed` ↔ ADMIN EXECUTED 수 비교 경보, ④ 실행 창·grace·skew 운영값 확정. **②가 동작하기 전에는 LIVE에서 B2 2인 명령을 열지 않는다**(D-MOPS-005 키 유출 우회 탐지 — L-10) | LIVE Gate 차단 조건으로 기록. Demo 범위 아님 |
| L-14 | (review n-6) ADMIN 시계가 순간적으로 MASTER보다 `max-clock-skew`(Demo PT1M) 넘게 앞서면 `approvedAt > now + skew`로 `APPROVAL_WINDOW_INVALID`가 **저장**되어 그 승인(approvalId·키)이 영구 소진된다 | 안전 방향(실행 안 됨)이라 수용. 운영자는 새 승인 요청으로 재시도. 시계 차이 지표(L-13 ①)로 탐지 |
| L-15 | (review n-8 — D-OPS-004 선례) 업무 근거 참조(D-MOPS-008 ①, 필수)는 **Demo에서는 형식 검사만** 한다(계약서·심사 기록 원천과 대조하지 않음) | **LIVE Gate 차단 조건**: 근거 참조의 원천 대조(계약 문서·심사 기록 저장소)가 동작하기 전에는 LIVE에서 B2 2인 명령을 열지 않는다. 사용자 결정 '필수'는 바꾸지 않는 LIVE Gate 메모 |
| L-16 | (B2-2 리뷰 n-3) internal-admin `application.yml`의 가맹점 승인 값 — `paycore.admin.master-ops`(timeout·permit·속도 제한), `paycore.admin.merchant-ops.approval`(execution-window PT1H·deadline-grace PT2M·backoff-initial PT30S·backoff-max PT30M·master-max-execution-window PT24H)과 MASTER `admin-channel.approval`(max-execution-window PT24H·max-clock-skew PT1M)은 **Demo 값, 운영값 아님** | **LIVE Gate**: 운영값 확정·두 앱 거울 값 동시 변경 절차(ADMIN 기동 검사가 execution-window ≤ 거울 값을 강제 — n-1). L-13 ④와 같은 항목 |
| L-17 | (rev 0.8, 코드 확인) 새로 만든 MID에는 가맹점 API Credential이 없다 — PAYMENT Demo Credential은 한 행뿐(V29 `demo_credential_issuance` id = 1 CHECK, seed MID). B3·B4를 마쳐도 Demo에서 그 MID로 CreatePayment를 호출할 수 없다 | B3 Demo는 상세 상태·개통 점검표·전파 ACK로 보여 준다(C-43). 새 MID 결제 시연에는 MASTER-CREDENTIAL-001(또는 D-DEMO-002 범위의 Demo 발급 확장 — 사용자 결정 필요)이 선행 — Coordinator 후속 작업 판단 |
| L-18 | (rev 0.8) D-MOPS-005 조회 키 유출 시 1인 `site-review-rejections`로 Site를 되돌릴 수 없게 거절할 수 있다(REJECTED 종결 D-MS-005). 금융 효과 없음(결제 차단 방향)이지만 복구는 새 Site 등록뿐 | L-1과 같이 수용 범위. 탐지 `command{operation=DecideSiteReview}`·감사 대조(L-2) |
| L-19 | (rev 0.8, C-36) 2인 승인 근거 참조가 Domain 기관 근거로도 쓰여 MASTER 개통 이력·변경 이력·Outbox 참조 이벤트 payload에 남는다(D-MS-003 기존 저장 위치). B2의 계약 승인 근거는 근거 행에만 있었다 | 근거 참조는 Secret 아님(D-MOPS-008 ① — 형식 제한·자유 문구 불가). MASTER 상세 조회 SELECT에는 없음. rev 0.8.1 Coordinator: 질문으로 올리지 않음. **재검토 조건**: 참조 이벤트 Outbox를 다른 서비스로 relay하게 되면(현재는 `ENTITLEMENT_SNAPSHOT`만 relay — `EntitlementRelayService`) payload의 `evidenceRef` 포함 여부를 다시 판단 |
| L-20 | (rev 0.8) B3 후에도 승인 시작은 불가 — 수수료·정산 정책 배정(A3)·한도(A4)는 B4. 정책 버전 등록(registerPolicyVersion)은 전역 정책이라 이 작업 밖이며 Demo는 seed가 만든 Demo 버전을 배정한다 | §9.19 표. B4 설계 때 정책 버전 선택 화면 범위 확정 |

**재개 동작**(rev 0.8): ① Coordinator가 Q-MOPS-22~24를 사용자에게 묻고(`harness/questions.json` PENDING → ASKED), C-35(미해결 키)를 결정. ② rev 0.8 독립 리뷰. ③ PASS 뒤 Coordinator가 B3 acceptanceCriteria(§8.3)·allowedWritePaths(아래 후보)를 등록 →
**B3-1부터** TDD(RED 먼저 — parser·근거 경로·handler 집합) → Verifier gate → Demo(§9.24) → B3-2(Q-23 답 뒤) → B3-3(Q-22·24 답 뒤) → B4·B5 설계(C-38 순서는 Coordinator) → B3-4.
**allowedWritePaths 후보**(B3 — 배치마다 좁힌다): `apps/master-api/src/main/kotlin/com/paycore/master/merchant/{domain,application,adapter/web/admin,adapter/persistence,adapter/query}/**`,
`apps/master-api/src/main/resources/db/migration/master/V11__admin_approval_evidence_b3_operations.sql`, `apps/master-api/src/test/kotlin/com/paycore/master/{merchant,architecture}/**`,
`apps/internal-admin/src/main/kotlin/com/paycore/admin/{approval,merchantops,audit}/**`, `apps/internal-admin/src/main/resources/db/migration/admin/V11__merchant_ops_activation_approval.sql`, `apps/internal-admin/src/main/resources/application.yml`(C-37 사유 목록 설정이 필요할 때만),
`apps/internal-admin/src/test/kotlin/com/paycore/admin/{approval,merchantops,audit,architecture}/**`, `apps/internal-admin/src/test/kotlin/com/paycore/admin/AdminSchemaSpec.kt`, `apps/internal-admin/frontend/src/{screens,api,components}/**`, 이 명세 §13. 제외: STUDY.md, `harness/tasks.json`·`questions.json`(Coordinator), PAYMENT·FINANCIAL 앱, 다른 migration.

## 13. 리뷰·결정 기록

| rev | 날짜 | 누가 | 내용 |
|---|---|---|---|
| 0.1 | 2026-11-08 | Implementer | 초안. B1 상세, B2~B5 개요, C-1~C-11, Q-MOPS-10~17 후보 |
| 0.2 | 2026-11-08 | Implementer(사용자 답 D-MOPS-006, Coordinator C 채택 반영) | Q-MOPS-10~15 답변 반영(§2·§4.4·§5·§6). Q-MOPS-12 **대안 선택** → C-12: MASTER 관리 채널 adapter가 거절 reference를 내보내지 않고 ADMIN도 거부 — `DemoMerchantSeedService`(reference 미사용)·`MerchantOnboardingServiceSpec`/`FirstDefaultIssuanceAtomicitySpec`(Use Case 수준 단언) 확인, 영향 없음. Q-MOPS-14 → §3.4 Domain 규칙 `CONTRACT_START_RETROACTIVE`(`Contract.register`, 판정 시각 `at`의 KST 자정, `SeoulMidnight.startOfDay`) + 시험. C-13(등록번호 버킷 시점) 추가. §8.1 B1-1·B1-2 수용 기준 분리. Q-MOPS-16·17 PENDING 유지, Q-MOPS-18 새 후보(B2 전). L-7·L-8 추가. 코드·빌드·커밋 없음 |
| 0.3 | 2026-11-08 | Implementer(rev 0.2 독립 검토 FAIL 반영 — 사용자 결정 변경 없음) | **M-1** 사업자 등록 감사 brn: 엄격 해석 성공 + 형식 맞는 번호면 403 포함 앞 3자리 마스킹, 해석 실패·형식 위반만 `brn=INVALID`(`MerchantLookupService` 선례) — §4.4 0~2·§6·§8·§8.1 B1-1-5. **M-2** 응답 유실 기준 분리: (a) MASTER 실제 HTTP + MySQL `MerchantOperationsLostResponseSpec`(커밋 후 응답 읽기 전 연결 종료 → 재전송 `replayed=true`, `admin_request`·`business` 각 1행), (b) ADMIN stub은 같은 MASTER 키·감사 쌍만 — §7·§8·§8.1 B1-1-6. m-1 C-14 correlationId = MASTER 키. m-2 C-13 원자적 예약·환불, L-9. m-3 §3.1 nonce 불필요 근거 + KDoc + 창 안 재전송 무변화 시험. m-4 같은 키 동시 1행(B1-1-3). m-5 쓰기 handler 전수 시험(D-MOPS-005 범위). m-6 종료일 문구 + Vitest. n-1 Revision 소급 허용 회귀 시험. n-2 L-7 보강. n-3 §3.4 DB CHECK 없는 이유. 코드·빌드·커밋 없음 |
| 0.4 | 2026-11-08 | Implementer(rev 0.3 재검토 PASS + MINOR 1·NIT 1 반영, Coordinator 결정) | **m-7** C-14 개정: correlationId = `mos.<submissionId>`(재전송 고정·36자·계정 ID 없음, MASTER 키 아님) — 기존 "멱등키 값을 로그에 넣지 않는다" 규칙(`AdminCommandExecutor` KDoc) 유지, MASTER 키 원문은 §6 넣지 않는 값·§7 Observability·§8 로그 시험에 계속 금지. **n-4** submissionId 없는 사전 거절(400 해석 실패·403)의 correlationId = ADMIN 요청 ID(§4.2). 코드·빌드·커밋 없음 |
| 0.5 | 2026-11-10 | Implementer(사용자 결정 D-MOPS-007 반영 — B1 완료 `89c6fac` 뒤) | Q-MOPS-16~18 답변(모두 권장안)을 §11.1로 이동. **B2 상세 설계** §9.1~§9.11: B2-1 Revision 등록 1인 + Domain `registerRevision` 소급 거절(D-MOPS-007 ②) + MASTER `contract-revisions`·ADMIN 화면 API·감사(C-22); MASTER 승인 근거 공통 계약(§9.3 — 본문 필드, `mop1` 지문, 저장되는 판정 5단계·새 거절 `APPROVAL_SAME_ACTOR`·`APPROVAL_WINDOW_INVALID`·`APPROVAL_ID_ALREADY_USED`·`APPROVAL_EXPIRED`, `admin_approval_evidence` V9/V10 — V8 비움); MASTER endpoint `contract-revision-approvals`·`first-default-merchant-accounts`; ADMIN 승인 종류 `MERCHANT_CONTRACT_APPROVAL`·`MERCHANT_FIRST_MID_ISSUANCE`(OWNER_SERVICE 2-TX, 대상 MERCHANT, ADMIN V9/V10), 결과 불명·회복(저장 바이트 재전송·기한 단계 — 조회 endpoint 없음), 화면(승인함·조회 상세 버튼·승인 대기 표시), 감사·권한, 8대 원칙, 기술 선택 연결(JPA `@Immutable` 근거 행·jOOQ 승인 대기 조회·Kafka/Redis 미도입 근거), Demo 시나리오. §8.2 B2-1/B2-2/B2-3 시험·수용 기준(§8 n-1 회귀 시험 **기대 반전** — D-MOPS-007 ② 근거). §9.12 B3~B5 개요를 D-MOPS-007 ③ 분류로 갱신. C-15~C-26 추가(권장안 채택 표시, C-26은 질문 승격 판단 대상). 새 질문 후보 **Q-MOPS-19**(승인 근거 참조). L-1 갱신, L-10~L-12 추가. 코드·빌드·커밋 없음 |
| 0.6 | 2026-11-10 | Implementer(rev 0.5 독립 리뷰 FAIL 수정 + 사용자 결정 D-MOPS-008, Coordinator 결정 C-18 대안) | **M-1**(결과 조회 없음 → 영구 EXECUTION_UNKNOWN)·**M-2**(기한 단계가 실행 재전송 — D-OPS-006·009 취지 위반): MASTER `approval-decisions`(서명 결정 조회, 쓰기 없음)·`approval-deadline-confirmations`(기한 전 rollback·`NOT_YET_DUE`, 기한 뒤 결정 없을 때만 `APPROVAL_EXPIRED` 저장, 업무 본문 호출 없음) 추가(§9.4), ADMIN 회복 = 결정 조회 → 기한 확인 → 참여자 재검사 → 무결성 플래그면 조회만 → 같은 바이트 재전송(§9.5), 409·400·401·404·413 매핑과 `OWNER_UNRESOLVABLE` 종결 경로(C-28), 근거 행 `decided_via`(C-29), §8.2 B2-2-3·10에 결정 조회·기한 확인·무결성 수렴·400 지속 시험, 시계 차이 지표는 LIVE Gate(L-13). **m-1** `JpaApprovalEvidenceStore.record` 즉시 flush → `StoreConstraint(businessKey=true)`, §9.9 문구 정정, 같은 approvalId·다른 operation 동시 MySQL 시험. **m-2**(→ D-MOPS-008 ②) C-26 `system:demo-seed` 정확 일치 + `DeploymentModePort.isDemo()`, D-DEMO-001·005 근거. **m-3** ORPHAN 대사 후보·LIVE Gate 차단 조건·`approval_executed` ↔ ADMIN EXECUTED 비교 지표(§9.8, L-13). **m-4** 만료 훅 = `lockedNow()` 직후 실행기 훅, 잠금 전 업무 거절이 먼저(의도), `lockedNow` 없는 쓰기 결함, 순서 시험(B2-2-1). **m-5**(→ D-MOPS-008 ③) `APPROVER_IS_REVISION_REGISTRANT`(MASTER Domain)·ADMIN TX1 403, 등록자 저장 위치는 기존 `contract_revision.registered_by` 확인(새 열 없음), MASTER 상세 jOOQ `registeredBy`, pinned·승인 상세 표시(C-27). D-MOPS-008 ① 근거 참조 필수 — 본문·`mop1` 정규 문자열·MASTER V9 `evidence_ref`·ADMIN 명령 표 열 확정, 별도 조회·감사. **n-1~n-8**: Coordinator 메시지·`harness/tasks.json`에 NIT 원문이 없어 **반영 못 함 — 원문 전달 필요**(임의 추정 반영 안 함). Q-MOPS-19~21 답변됨(§11.1), PENDING 없음. 코드·빌드·커밋 없음 |
| 0.6.1 | 2026-11-10 | Implementer(rev 0.5 리뷰 NIT 원문 수령 — Coordinator 전달) | **n-1** §11.1 Q-MOPS-16 "지문"은 C-23 추가분으로 표시. **n-2** V9 `request_record_id` FK 없이 UNIQUE만 확정(V1에서 UNIQUE 아님). **n-3** §2 D-MOPS-004 원문과 해석(요청 OPERATOR·승인 APPROVER + 직전 TOTP = D-ADMIN-003·004·D-ACQA-002 선례) 분리. **n-4** §9.1 B2-1 1인 분류를 "계약 초안"의 해석으로 명시. **n-5** §9.3 correlationId에서 키 유도 가능 — 키 비밀 아님·서명 필요 전제. **n-6** L-14 시계 앞섬 시 `APPROVAL_WINDOW_INVALID` 저장·승인 소진(안전 방향 수용). **n-7** 코드 확인: Domain·DB에 Revision 상한 없음 → 정규식을 INT 범위로(C-30, 새 Domain 규칙 없음). **n-8** L-15 근거 참조 Demo 형식 검사만·LIVE 원천 대조 Gate 차단 조건(D-OPS-004 선례, D-MOPS-008 ① 결정 불변). 코드·빌드·커밋 없음 |
| 0.6.2 | 2026-11-10 | Implementer(rev 0.6.1 재리뷰 FAIL — 이전 지적 해소, 새 지적 반영) | **새 M-1 → C-31**: Revision 등록자≠승인자는 근거 경로(`Contract.approveWithEvidence`)에만, seed 경로는 D-MOPS-008 ②·D-DEMO-005 예외, Domain 행위자 이름 하드코딩 금지, 시험(seed 성공 불변 + 근거 경로 거절). **m-a** 기한 확인 경로 판정 1·2 거절도 저장(CHECK 확장 — 3819 → 503 반복 없음) + 시험. **m-b** 기한 확인 판정 3 → `APPROVAL_ID_ALREADY_USED`·근거 행 없음 + 시험. **m-c** NOT_YET_DUE = 전용 신호 `DeadlineNotYetDue`(재시도 분류 비대상) → rollback, 시험 "`admin_request` 0행". **n-a** 승인 operation 결과 ↔ 근거 행 1:1 불변식 + 시험. **n-b** `decided_via` NOT NULL + IN CHECK. **n-c** ADMIN V5 finding kind CHECK는 이미 있음 → DROP 후 `OWNER_UNRESOLVABLE` 추가로 확정. **n-d** `DeploymentModePort` 구현 = `DemoCommandModeGate.isDemo` 위임 별도 adapter bean. 코드·빌드·커밋 없음 |
| 0.6.3 | 2026-11-10 | Implementer(B2-1 Demo 시연 지적, Coordinator 결정 C-32) | Revision 폼 성공 뒤 완료 상태(제출 버튼 없음·입력 읽기 전용, "다른 Revision 새로 입력"만 새 submissionId), "승인 전" 표기 통일(상세 표·B1 계약 초안·B2-1 성공 문구). 정책·기대값 변경 없음 |
| 0.6.3(기록 추가) | 2026-11-10 | Implementer(Verifier master-api check 실패 1건 — Coordinator 승인) | `MerchantOpsApiSpec` 관리 채널 401 전수 시험의 handler 수 8 → 9: B2-1 `POST …/merchant-operations/contract-revisions` 추가에 따른 **경로 목록 갱신**(§8 "B2 이후 경로를 추가하는 작업은 이 기대 집합을 그 배치 결정 근거로 갱신"·B1-2 선례). 개수 대신 경로 9개 집합을 단언해 새 경로가 401 검사 대상임을 고정. 기대 완화 아님 |
| 0.6.4 | 2026-11-10 | Implementer(B2-2 MASTER 구현 — Coordinator 결정 C-33) | §9.4 기한 확인 응답을 실행과 같은 §3.3 모양으로 정정(`NOT_YET_DUE`만 `{"decision":"NOT_YET_DUE"}`), 표 "Use Case·성공 reference" 칸 갱신, §10 C-33 추가. 근거: `APPROVAL_ID_ALREADY_USED`에는 근거 행이 없어 `decidedVia`를 정할 수 없다. 정책·기대값 변경 없음 |
| 0.6.5 | 2026-11-10 | Implementer(B2-2 ADMIN 구현 — Coordinator 지시 정정, 정책·기대값 변경 없음) | **정정 2건**: ① §8.2 B2-2-3 `decided_via` NULL 직접 INSERT는 열이 NOT NULL이라 MySQL **1048**(3819 아님 — 다른 값만 3819). ② §9.3 `EnvironmentDeploymentMode` 위치 = **adapter/integration**(adapter/batch는 ArchUnit `batchAdapterUsesOnlyApplication`이 required Port 구현을 막는다 — MASTER 코드 위치 그대로). **ADMIN 가역 구현 선택(Coordinator 확인 대상, 업무 의미 변경 없음)**: V9 종류 CHECK·미해결 키·명령 표 `command_kind`(`CONTRACT_APPROVAL`·`FIRST_MID_ISSUANCE`)에 B2-3 종류를 미리 둠(앱은 B2-3 전까지 만들지 않음 — §9.5 V9 문구), 근거 참조는 재매입과 같은 문법 + 카드 번호 모양 거부(ACQ-ADMIN-001 B1-A 리뷰 M-1 선례 — "같은 형식"), 결정 조회는 ADMIN 조회 permit·실행/기한 확인은 쓰기 permit(MASTER와 같은 분리), 회복 backoff = `backoff-initial` Demo PT30S부터 두 배·상한 `backoff-max` PT30M(가맹점 종류에만 — 기존 종류 backoff 불변), 400·413은 실행 응답이면 `Malformed`(재전송 중단 `OWNER_MALFORMED` 1회)·409는 무결성 플래그 + 감사 `APPROVAL_INTEGRITY_VIOLATION` 1회(`OwnerOutcomeApplier.apply(integrityAudit = true)` — 기존 종류 기본값 false), `pendingApprovals`는 merchantops 공개 SPI `MerchantPendingApprovals`(approval 모듈 jOOQ Reader + QueryService가 구현 — 모듈 순환 없음)를 ADMIN 상세 응답에 붙임. B2-1 리뷰 n-a(세션 경계 "결과 확인이 필요한 요청이 있습니다 — 가맹점 조회로 확인하세요" 1회 안내) 반영, n-b(`REFERENCE_MISMATCH` → OUTCOME_UNKNOWN → `command_unknown{operation}` 지표·WARN)는 기존 `MerchantRegistrationServiceSpec` review n-3 시험이 이미 단언함을 확인(코드 변경 없음) |
| 0.7 | 2026-11-10 | Implementer(B2-3 구현 + B2-2 리뷰(REVIEW_PASS 조건부) 반영 + Demo 개선 — 정책 변경 없음) | **B2-3**: MASTER `POST …/first-default-merchant-accounts`(서명 v1·쓰기 permit, 본문 = 근거 공통 9 + 명령 필드 5 — merchantId·contractId·agreementId·revisionNo·primarySiteId, `mop1` operation `IssueFirstDefaultMerchantAccount` — Python 독립 벡터 `5763462d…`·`1121aa32…`를 MASTER·ADMIN 양쪽 시험에), Use Case `issueFirstDefaultMerchantAccountWithEvidence`(근거 실행기 + 기존 발급 본문 공용 — Revision 등록자 규칙 없음, 시험으로 고정), 결정 조회·기한 확인은 기존 endpoint가 operation으로 일반화(기한 확인 해석기가 본문 모양으로 둘 중 하나). 쓰기 handler 전수 집합에 경로 1 추가(기대값 갱신 근거 §8.2 B2-3-1). 실제 MySQL 경합(같은 가맹점 다른 approvalId 둘 → 1 `mid_`·1 `DEFAULT_ALREADY_ISSUED`, MID 1개 — 3회). ADMIN 승인 종류 `MERCHANT_FIRST_MID_ISSUANCE`(V9 CHECK·미해결 키·명령 표 `FIRST_MID_ISSUANCE` 그대로 사용 — migration 없음), pinned `PinnedFirstMidTarget`(§9.5 필드), 미리 보기 적격 C-34, 회복 분기·소유 참조 `mid_`. 화면: 조회 상세 "첫 MID 발급 요청"(OPERATOR·MID 0·승인 Revision·CSA·ACTIVE Site — Revision·CSA·PRIMARY Site 선택, 근거 참조 필수), 승인함 "첫 MID 발급" 상세. **B2-2 리뷰**: m-2 변이 4종(기한 뒤 실행 POST 허용·기한 확인 409 finding 제거·결정 조회 지문 불일치 플래그 생략·`requireDemoSeedPath` isDemo 제거) 모두 RED 확인·원복 sha256 동일, n-1 ADMIN `master-max-execution-window` 거울 값 기동 검사, n-2 C-34, n-3 L-16, n-4 결정 조회 `replayed=true` 주석. **Demo 개선(C-34)**: 요청 결과 유지, 승인 대기 배너 대상, 감사 화면 대상 열(targetRef — L-13 감사 대상 열 해소) |
| 0.7.1 | 2026-11-10 | Implementer(B2-3 리뷰 REVIEW_PASS 조건부 — MAJOR 1·MINOR 3·NIT 5 + Demo 지적 3 반영, 정책 변경 없음) | **M-1** §8.2 B2-3-2: `FirstDefaultIssuanceAtomicitySpec`에 근거 경로 Then 추가(기존 단언 불변) — 근거 행 쓰기 직후·ADMIN_COMPLETE 장애 주입 시 MID·Default·PRIMARY·권리 revision·Snapshot·이력·Outbox 불변, 근거 행 0·`admin_request` 0, 재시도 1회 실행·근거 EXECUTED 1행(재요청은 재생). 주입 지점을 하나씩 끄는 변이 RED 기록. **m-1** C-34 보강(위 표). **m-2** `FirstMidApprovalApiSpec`·`MerchantApprovalLostResponseSpec`에 MID 행: 같은 키 다른 본문 409, 쓰기 permit 소진 503, `approval_executed{operation=IssueFirstDefaultMerchantAccount}`, 실제 HTTP 응답 유실 후 재전송 재생, 기한 뒤 미실행 → APPROVAL_EXPIRED(`DEADLINE_CONFIRM`) 저장·재생, 경합 근거 행 EXECUTED 1·REJECTED(`DEFAULT_ALREADY_ISSUED`) 1 + `data_lock_waits` 관찰. **m-3** 첫 MID 폼: Revision 변경 시 CSA·Site를 새 옵션 첫 값으로, 보내기 조건에 "선택값이 현재 옵션 안" — 두 계약 Vitest. **NIT** n-1 기한 확인 KDoc 위치, n-2 설정 toString, n-3 감사 targetRef 변이, n-4 승인 0단계 읽기 1회, n-5 변이 로그 형식(대상 파일·명령·sha). **Demo 지적**: 승인 대기 배너의 MID 중복 표기 제거, 등록 화면 다음 단계 문구(계약 승인 → 첫 MID 발급, 결제수단·Route 개통 전 결제 불가)·페이지 설명, 첫 MID 폼 제목·Revision 선택지 "ctr_… #n · YYYY-MM-DD 시작"(KST) |
| 0.8 | 2026-11-11 | Implementer(B3 상세 설계 — B2 커밋 `1b5538d` 기준 코드 확인, 코드·migration·빌드·커밋 없음, 정책 변경 없음) | **B3 상세 설계** §9.13~§9.24·§8.3: 하위 배치 B3-1(수단 개통 등록 1인·승인 2인 + Site 심사 승인 2인/거절 1인) → B3-2(통화 2인) → B3-3(Route 등록 1인·승인 2인) → (B5) → B3-4(재개·Site 재확인 2인)(C-38). 코드 확인: B3 Use Case는 모두 있으나 근거 없는 `execute` 경로뿐(두 서비스 executor에 근거 Store 없음) → `…WithEvidence` 7개·`rejectSiteReview` 신설, 근거 없는 2인 메서드 7개 seed 관문(C-40 — Demo seed가 4개 사용 확인). MASTER endpoint 1인 3·2인 7, 본문 `operation` 필드(C-39 — 승인·재개 본문 모양 동일), Domain 기관 근거 = 승인 근거(C-36), Site 사유 코드(C-37), KRW만(C-41 — CLAUDE.md), ADMIN 종류 7·미해결 키 그룹(C-35 Coordinator 결정 대상)·pinned·1인 화면 API 3·감사 code 3, 화면 개통 영역·점검표(C-43), migration MASTER V11·ADMIN V11(V12 비움 — C-42). **"결제 가능"(§9.19 — PAYMENT `EntitlementEvaluator`·`AuthorizationTermsEvaluator`·`AuthorizationStart` 확인)**: B3-2 뒤 결제 생성 판정 Allowed가 처음, 승인 시작은 B4(정책 배정·한도) 뒤, 새 MID는 Credential 없음(L-17). 새 질문 후보 Q-MOPS-22(기관 가맹점 참조 형태)·23(통화 효력 시작)·24(Route 등록자≠승인자) PENDING — B3-1은 막히지 않음. L-17~L-20, 재개 동작·allowedWritePaths 후보 갱신 |
| 0.8.1 | 2026-11-11 | Implementer(rev 0.8 독립 리뷰 조건부 PASS — Coordinator 지시 반영, 정책 변경 없음) | **MAJOR-1** Q-MOPS-24를 "1인 등록자 ≠ 그 대상의 2인 승인자"(Route·결제수단 개통·Site)로 확대 — 등록자 열 코드 확인(`card_route_activation.registered_by`·`payment_method_activation.registered_by`·`merchant_site.created_by`, migration 불필요), 막히는 배치에 B3-1 2인 경로 DONE 추가, Activation·Site pinned `registeredBy`(표시용), `harness/questions.json` 갱신. **C-45**(minor-1) 개통 승인 `primarySiteId` 고정·`PRIMARY_SITE_MISMATCH`. minor-2 1인 거절 ↔ 2인 승인 같은 Site 경합 시험, minor-3 응답 유실 handler 4개 전부, minor-4 기한 확인 교차 수용 금지·`MasterResponseShape` operation 허용 목록·reference 문법, minor-5 근거 입력란 의미 표시·L-19 relay 재검토 조건(C-36 질문 안 올림), minor-7 B3-1 DONE 증거 항목, nit-1 1인 거절 operation `RejectSiteReview` 분리, nit-3 V11 한 ALTER. **C-35 Coordinator 채택** + Site 1인 거절 버튼은 그룹 대기 중에도 숨기지 않음. **minor-6·nit-2·nit-4 원문 미수령** — Coordinator 메시지에 지적 원문이 없어 임의 추정 반영하지 않음(원문 전달 필요, rev 0.6 n-1~n-8 선례) |
| 0.8.2 | 2026-11-11 | Implementer(B3-1 MASTER 구현 기록 — 정책·기대값 완화 없음, 자기 완료 판정 아님) | MASTER B3-1 구현: endpoint 4개(`MerchantActivationController` — 1인 `payment-method-activations`·`site-review-rejections`, 2인 `payment-method-activation-approvals`·`site-review-approvals`), Use Case `approvePaymentMethodActivationWithEvidence`·`approveSiteReviewWithEvidence`·`rejectSiteReview`(operation `RejectSiteReview` — nit-1), Domain `ActivationApproval.approve(expectedPrimarySiteId)` → `PRIMARY_SITE_MISMATCH`(C-45, 전이 판정 뒤), 기한 확인 해석기 4개 중 정확히 하나(minor-4), 사유 코드 `SiteReviewReasonCodes`(C-37), V11 한 ALTER(nit-3), 상세 조회 `sites[].registeredBy`·`paymentMethods[].registeredBy`·`accounts[].siteConfirmations[]`(근거·사유 없음 — C-43). seed 관문은 이번 배치 대상 2개만(§9.15 기록). **Q-MOPS-24 답 전**: 2인 경로의 등록자 검사 없음 — 시험으로 현재 상태 고정. 기존 시험 변경: 경로·migration·문장 수 목록 갱신과 근거 없는 2인 호출의 준비 행위자 seed화뿐(기대값 완화 0 — 목록은 보고서). 증거 `~/gate-logs/mops-b31/`(RED 115/23 실패 → GREEN 118/0, 상세 RED 21/2 → GREEN 21/0, 모듈 check 610/0, 변이 4/4 검출·sha 복원) |
| 0.8.3 | 2026-11-11 | Implementer(rev 0.8 리뷰 minor-6·nit-2·nit-4 원문 수령 — Coordinator 전달, 명세만, 코드 변경 없음) | **minor-6** §8.3 B3-1-4(개통 등록 경합 = merchant 잠금 아래 `findOpen` 판정, UNIQUE 보조 방어선, `data_lock_waits` 관찰 문구 삭제 — `ActivationConcurrencySpec` 실제 단언과 일치), B3-2-3 같은 정정, §9.20 Concurrency 행, §9.21 JPA 설명 정정. **nit-2** §9.14 경로별 실제 잠금 순서(Site 심사는 merchant만). **nit-4** Q-MOPS-22 영향란(§11.2·`harness/questions.json`)에 Route 등록 지문의 기관 가맹점 참조 원문 입력 확인 메모. Coordinator: `frontend/src/test/fakeApi.ts` stub 변경 승인 |
| 0.8.4 | 2026-11-11 | Implementer(B3-1 코드 리뷰 조건부 PASS 반영 — 정책·기대값 완화 없음) | M-2 `ActivationApprovalAtomicitySpec` Site 심사 승인 근거 경로 행렬(장애 주입 3지점·같은 행위자·`SITE_NOT_FOUND`/`APPROVAL_EXPIRED` 순서·기한 확인) — 시험 추가라 변이로 검출 확인. m-1 열린 PRIMARY 판정 차이(§9.16 기록, KDoc 정정 — ADMIN은 ACTIVE만, 보수적). m-2 Vitest(B3 그룹 대기가 B2 버튼을 숨기지 않음). n-1 ADMIN V11 DROP/ADD 한 ALTER. n-3 기한 확인 해석기 B3 operation 교차 null 단언. 증거 `~/gate-logs/mops-b31/`(r1-*, mutations/summary-r1.txt, changed-files-all.txt, changed-tests.md) |
