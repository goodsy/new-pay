# MERCHANT-BIZ-LOOKUP-001 사업자등록번호 정확 일치 사업자 조회 명세 (rev 0.3)

- **rev 0.1** (2026-11-14, Implementer — 명세 초안. 코드·migration·빌드·커밋 없음). 기준 HEAD `68c2558`. D-ADMIN-UI-004 ⑦·수용 기준 적용, C-1~C-10 권장, Q-BL-1 PENDING.
- **rev 0.2** (2026-11-14, Implementer — 사용자 답 **D-BIZLOOKUP-001 Q-BL-1 = "내부 키 응답, 화면 미표시"**(권장) 적용 + 저장소 대조. 코드·migration·빌드·커밋 없음, 자기 완료 판정 아님 — Reviewer 별도).
  기준: main, HEAD `7a50459`(MERCHANT-OPS-002 B3-2 이후 — 이 작업의 참조 파일은 B3-2에서 `adminApi.ts`만 바뀌었고 참조 줄은 그대로). 결정 고정 시험(화면 `biz_` 0건·평문 번호는 조회 요청에서만) 추가, 참조 정정(§12).
- **rev 0.3** (2026-11-14, Implementer — rev 0.2 독립 리뷰 **REVIEW_PASS(minor 2·NIT 2)** 반영 + Coordinator 결정 C-11·C-12. 코드·migration·빌드·커밋 없음, 정책 변경 없음): 403 문구는 기존 일반 문구 유지(C-11), 사업자 조회 전용 일반 조회 창(C-12), 지표 tag key 일관성, L-1 MASTER 키 유출 위험. §12 기록.
- 표기: **사용자 결정** = `docs/DECISIONS.md`. **Q-BL-n** = 사용자 질문(모두 답변됨 — §10). **C-n** = Coordinator 가역 선택(권장안). **L-n** = 알려진 위험·한계.
- 근거: **D-ADMIN-UI-004 ⑦(Q-ADMIN-UI-7 "사업자 조회 기능 추가")**, **D-BIZLOOKUP-001(Q-BL-1)**, D-MOPS-002·003(Q-MOPS-03·05·06·07·08)·005·006 ③④·010 ①, D-MD-008, D-ADMIN-UI-004 ③④, `harness/tasks.json` MERCHANT-BIZ-LOOKUP-001 수용 기준,
  MERCHANT-OPS-001 §3·§4·§5(등록번호 검색·oracle 방어), MERCHANT-OPS-002 §4.3·C-13(등록번호 버킷 공유)·§9.29, ADMIN-UI-MERCHANT-002, ARCHITECTURE-CONVENTIONS §14, 테스트 아키텍처 Test Fixture Policy.

## 0. 한눈에

| 항목 | 결정·제안 |
|---|---|
| 무엇 | 사업자등록번호 10자리 **정확 일치**로 **사업자(Business)** 하나를 찾는다 — 가맹점이 0개인 사업자도 "가맹점 등록 ① 사업자 선택"에서 고를 수 있게(D-ADMIN-UI-004 ⑦) |
| 권한 | **OPERATOR만**(수용 기준 — 가맹점 등록 화면과 같은 역할, D-ADMIN-UI-004 ④). 기존 가맹점 BRN 검색(OPERATOR·APPROVER)은 그대로 |
| 응답 | `{"items":[…]}` 0개 또는 1개, 항목 = **상호·마스킹 번호(`123*******`)·내부 키 `businessId`**만. 평문 번호 없음. 찾음·못 찾음 같은 모양(200) |
| 내부 키 | **`businessId`는 응답에 포함, 화면에 그리지 않는다**(D-BIZLOOKUP-001) — 가맹점 등록 요청 본문에만 쓴다. 평문 번호는 **조회 요청에서만 1회** 전송. D-MOPS-006 ③은 사업자 등록 **명령 거절 응답** 범위로 해석·유지 |
| 감사 | **모든 시도**(D-MOPS-003): 새 action `MERCHANT_BUSINESS_BRN_LOOKUP`, detail `brn=123*******;results=N`(형식 위반 `brn=INVALID`), 찾으면 `targetRef = businessId` |
| 속도 제한 | 기존 **등록번호 버킷 공유**(`RegistrationNumberBudget` — 가맹점 BRN 검색·사업자 등록 중복 결과와 같은 버킷, Demo 10/분 — **실제로 묶는 통제**) + `BusinessLookupService` **자체** 일반 조회 창(Demo 30/분 — 가맹점 조회 창과 별개, C-12) — C-3 |
| endpoint | **새 endpoint**(C-1): MASTER `POST /internal/v1/admin/business-lookups`, ADMIN `POST /api/business-lookups`. 가맹점 검색 endpoint·계약 불변 |
| 화면 | 가맹점 등록 ① "사업자 선택"이 가맹점 검색 대신 사업자 조회를 부른다. `BusinessRef`·`startWithBusiness`·사업자 등록 화면 handoff 불변 |
| 배치 | BL-1 MASTER 조회 → BL-2 ADMIN 조회·감사 → BL-3 화면 연결. **막힌 배치 없음** |

## 1. 현재 상태(코드 확인 — HEAD `7a50459`)

### 1.1 기존 등록번호 검색(MERCHANT-OPS-001 — 가맹점 단위)
- MASTER `POST /internal/v1/admin/merchant-searches`(`MerchantOpsController.kt:27-34`, 서명 v1·`ADMIN_SERVICE`·조회 permit `guarded` → `AdminChannelPermits.read` L51-52, busy = 503 `ADMIN_READ_BUSY`) → 엄격 해석 `MerchantSearchRequestParser.kt:20-44`
  (중복 key·뒤따르는 토큰 거부 L20-23, 알려진 6필드 L25, `registrationNumber` `^[0-9]{10}$` L29, 위반은 null → 400 `REQUEST_MALFORMED` L29-30 컨트롤러, 값 미반환).
- jOOQ `JooqMerchantDirectoryReader.kt:25-51`(`@Transactional(readOnly = true, isolation = REPEATABLE_READ)` L25): **`FROM merchant JOIN business`**(L38-39) — 등록번호 조건 `BUSINESS.REGISTRATION_NUMBER.eq(…)`(L31, 바인드 값만), 결과는 가맹점 행 + SQL 마스킹 `MasterSqlText.maskedRegistrationNumber()`(`MasterSqlText.kt:18` — `LEFT(번호, 3)` + `*******`).
  → **가맹점이 0개인 사업자는 결과 0건**(조인 때문 — 이 작업의 원인).
- ADMIN `POST /api/merchant-searches`(`MerchantController.kt:37-49`): 415(비 JSON)·413(16 KiB 초과, 상한 + 1바이트까지만 읽음 — `MAX_SEARCH_BODY_BYTES` L99) 읽기 전 거절 **감사 없음**(Q-MOPS-08), 지표 `paycore.admin.merchant_ops.search_body_rejected{reason}`(L55·L102),
  엄격 해석 `MerchantSearchBodyParser.kt:38-76`(해석 실패 시 "등록번호 시도 여부"만 — 첫 최상위 객체 직속 key `registrationNumber` 규칙 L58-75, Q-MOPS-07).
- ADMIN Use Case `MerchantLookupService.kt:72-103`: 조회 역할(`VIEW_ROLES` L183, `viewer` L129-130) → **등록번호 역할 OPERATOR·APPROVER**(L81, L186 — D-MOPS-003) → 형식(L82) → **등록번호 버킷**(L84 `RegistrationNumberBudget.admit`) → 일반 조회 버킷(L88) → MASTER(TX 밖, L92) → 감사 `MERCHANT_BRN_SEARCHED`(L197) 결과 `FORBIDDEN·INVALID_INPUT·RATE_LIMITED·OK·UNAVAILABLE`,
  detail `brn=123*******;results=N`(L75·L98), 찾으면 `targetRef = businessId`(L96-98), 감사 실패 → `AuditUnavailable`·결과 미반환(`audited` L143-164). 해석 실패 시도 감사 `rejectMalformedSearch`(L61-70 — detail 고정 `brn=INVALID`).
- 버킷 `RegistrationNumberBudget.kt:7-28`: actor별 분당, **조회와 사업자 등록(중복 결과)이 같은 버킷**(MERCHANT-OPS-002 C-13, D-MOPS-006 ④), 원자적 예약 `ActorMinuteWindows.tryReserve`(L23), 인스턴스 메모리(L-9 LIVE Gate). 한도 Port `MerchantOpsLimits.kt:7-13`,
  Demo 값 `internal-admin/src/main/resources/application.yml:140-141`(`lookup-per-minute: 30`, `registration-number-searches-per-minute: 10`). 지표 Port `MerchantOpsMetrics`(`paycore.admin.merchant_ops.rate_limited{kind}`).
- 감사 Domain `AdminAuditEvent.kt:54` `TARGET_REF` — `biz_<ULID>` 허용(기존). action 열 `VARCHAR(48)`(ADMIN `V1__admin_core.sql:150` — 값 목록 CHECK 없음 → migration 불필요).

### 1.2 MASTER 사업자 저장·마스킹
- `business` 표(`V1__master_core.sql:20-34`): `registration_number CHAR(10)` 평문(L22), `UNIQUE uk_business_registration_number`(L29) → 번호당 사업자 하나. Domain `BusinessRegistrationNumber.kt:9-17`(`masked()` 앞 3자리 L15, `toString` 마스킹 L17), `Business.kt:14-16`(`requireActive` → `BUSINESS_NOT_ACTIVE`).
- 명령 측 존재 조회 `BusinessRepository.findIdByRegistrationNumber`(`BusinessRepository.kt:12-13` — 사업자 등록 중복 판정용, JPA). 조회(CQRS)용 Reader는 없음. provided Query Port `MerchantOpsQuery.kt:13-19`(`search`·`detail`·`history`).
- D-MOPS-010 ①(MERCHANT-OPS-002 §9.29): 사업자 **등록 요청 지문**의 번호를 HMAC으로 — 이 조회는 멱등 기록·지문이 없으므로 **해당 없음**(조회는 `admin_request`를 쓰지 않는다). 감사는 HMAC을 쓰지 않는다(Q-MOPS-06 — 앞 3자리 + targetRef).

### 1.3 화면(ADMIN-UI-MERCHANT-002)
- `MerchantRegistrationScreen.tsx:24-31` KDoc: ① 사업자는 기존 가맹점 검색으로 고르며(L27-28) "한계: 가맹점이 하나도 없는 기존 사업자는 이 검색으로 찾을 수 없다(Coordinator 보고 대상)"(L29), 사업자 ID 화면 미표시(L30).
- `BusinessPicker`(L173-259): 평문 번호는 화면 상태만·보낸 즉시 비움(L175-176, L193-194), `api.merchantSearch({ registrationNumber })`(L197) 결과를 `businessId`로 묶어 `BusinessRef` 목록(L198-202), 빈 결과 문구 "찾은 사업자가 없습니다(가맹점이 있는 사업자만 찾습니다)."(L242), "선택" → `offerBusiness`(L137, L248).
- `BusinessRef { businessId, name, masked }`(`lib/registration.ts:20-28` — "businessId는 화면에 그리지 않는 내부 키" KDoc), `startWithBusiness`(**`MerchantRegistrationScreen.tsx:155`** — 사업자 등록 화면에서 넘어올 때 마법사를 비우고 그 사업자로 시작), 호출 `App.tsx:235-238`,
  사업자 등록 화면 `BusinessRegistrationScreen.tsx:63`(성공 시 `BusinessRef` 생성 — `maskRegistrationNumber`)·L108·L129(`onContinue`), 중복 거절 고정 문구 KDoc L21("D-MOPS-006 ③ — 기존 사업자 ID·링크 없음").
- API 클라이언트 `api/adminApi.ts:734`(타입)·`:809`(`merchantSearch` → `/api/merchant-searches`). 시험 대역 `test/fakeApi.ts:37`(`notStubbed`).

## 2. 사용자 결정 적용(재질문하지 않음)

| 결정 | 적용 |
|---|---|
| D-ADMIN-UI-004 ⑦ / Q-ADMIN-UI-7 "사업자 조회 기능 추가 (Recommended)" | 등록번호 정확 일치 사업자 조회, D-MOPS-003 감사·한도, 응답 상호·마스킹 번호, 별도 작업 = 이 문서 |
| **D-BIZLOOKUP-001 / Q-BL-1 "내부 키 응답, 화면 미표시 (Recommended)"** | 조회 응답에 `biz_` 내부 키 포함 — **화면에 그리지 않고** 가맹점 등록 요청 본문에만 사용. 원문 번호는 조회 요청에서만 1회 전송(가맹점 등록 요청·ADMIN 요청 기록·재전송 바이트에 원문 없음). 사업자등록번호를 식별자로 쓰는 안은 D-MD-008 마스킹·D-MOPS-010 HMAC 방향과 충돌하고 정정 가능 값이라 채택하지 않음(사용자 확인). **D-MOPS-006 ③**(사업자 등록 중복 거절 응답에 기존 businessId 비노출)은 **등록 명령 거절 응답 범위**로 해석·유지 → 결정 고정 시험 BL-2-9·BL-3-1·BL-3-2 |
| tasks.json 수용 기준 | ① 정확 일치만, 매 시도 감사·한도, 응답 상호·마스킹 번호·내부 키만(평문 미노출) ② 가맹점 등록 ①에 연결 ③ 권한 **OPERATOR**, 기존 규칙 불변 |
| D-MOPS-003(Q-MOPS-03·05·06) | 모든 시도 감사, 감사·로그·지표·오류에 평문 금지, oracle 취급(같은 모양·속도 제한·호출 전 형식 검사), 감사 = 앞 3자리 + 결과 수 + 찾으면 targetRef = businessId(keyed hash 미사용) |
| Q-MOPS-07 | JSON 자료형이 깨진 본문도 시도면 `INVALID_INPUT`(`brn=INVALID`) 감사, 중복 key 거부 |
| Q-MOPS-08 | 상한 초과 본문은 해석 없이 413·감사 없음(16 KiB Demo 값) |
| D-MOPS-006 ④ | 등록번호 결과(중복 포함)는 등록번호 검색 횟수 제한에 포함 → 이 조회도 같은 버킷(C-3) |
| D-MOPS-006 ③ | 사업자 등록 **명령**의 중복 거절은 "이미 등록된 번호"만 — 그 응답·화면·감사에 기존 businessId 없음(불변 — D-BIZLOOKUP-001 해석) |
| D-MOPS-002 | 목록은 감사 없음 — 단, 등록번호가 오가는 조회는 예외(D-MOPS-003) |
| D-MD-008 | 번호 10자리 숫자만(하이픈 정규화 없음 — 화면 자동 하이픈은 표시만, 보낼 때 숫자만), Business 하나 |
| D-ADMIN-UI-004 ③ | `businessId`는 화면에 그리지 않는다(내부 키 — 저장 본문에만) |

## 3. MASTER 조회(BL-1 — `master.merchant.adapter.web.admin`)

### 3.1 endpoint
- `POST /internal/v1/admin/business-lookups` — 기존 채널 체인 그대로: 서명 v1·`hasAuthority(ADMIN_SERVICE)`(D-MOPS-005 — 새 권한 없음), 본문 16 KiB, query 금지, **조회 permit**(`AdminChannelPermits.read` — 쓰기 permit과 독립), `Cache-Control: no-store`, `PayCore-Request-Id`, `@ConditionalOnWebApplication(SERVLET)`(`MerchantOpsController`에 handler 추가 — 같은 `guarded`).
- 본문 엄격 해석 `BusinessLookupRequestParser`(`MerchantSearchRequestParser.kt:20-23`과 같은 Jackson 설정 — 중복 key·뒤따르는 토큰 거부, 문자열만): **정확히 `{"registrationNumber":"<10자리>"}`** — 필드 하나, 필수, JSON 문자열, `^[0-9]{10}$`. 위반은 400 `REQUEST_MALFORMED`(필드·값 미반환).
- 응답 200 `{"items":[{"businessId":"biz_…","businessName":"…","registrationNumberMasked":"123*******"}]}` — **0개 또는 1개**(UNIQUE). NOT_FOUND 상태 code를 쓰지 않는다(찾음·못 찾음 같은 모양 — §5.3). `lifecycle`·가맹점 수·생성 시각 등 다른 필드 없음(사용자 결정 "상호·마스킹 번호만" + 내부 키 — D-BIZLOOKUP-001).
- 로그: `operation=BusinessLookup correlationId=… result=OK items=N`(번호·마스킹 값 없음 — 기존 검색 `MerchantOpsController.kt:32`와 같음).

### 3.2 구조(ARCHITECTURE-CONVENTIONS §14)
- provided Query Port: 기존 `MerchantOpsQuery`(`MerchantOpsQuery.kt:13-19`)에 `lookupBusiness(number: BusinessRegistrationNumber): BusinessLookupItem?` 추가(C-2 — 같은 관리 조회 묶음, 새 Use Case 타입보다 작다. 가역).
- required Reader Port `master.merchant.application.required.BusinessLookupReader`(jOOQ 조회 Port `…Reader` 규칙), 구현 `adapter.query.JooqBusinessLookupReader`:
  `SELECT business_id, display_name, <maskedRegistrationNumber()> FROM business WHERE registration_number = ?`(바인드 값 — `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` 기존 Reader와 같음). **원문 열은 SELECT 목록에 없다**(시험: select 필드 집합 단언).
- 명령 측 `BusinessRepository.findIdByRegistrationNumber`는 재사용하지 않는다(명령 Aggregate Port를 조회에 쓰지 않음 — CQRS, MERCHANT-OPS-001 §3 관례).
- 응답 직렬화 `MerchantOpsJson.businessLookup(item?)`.

## 4. ADMIN 조회·감사(BL-2 — `admin.merchantops`)

### 4.1 endpoint
- `POST /api/business-lookups` — 세션 + CSRF, `Cache-Control: no-store`, 본문 바이트로 받아 엄격 해석. 읽기 전 거절(Q-MOPS-08 그대로 — **감사 없음**): 비 JSON 415 `UNSUPPORTED_MEDIA_TYPE`(본문 미읽기), 선언 길이 또는 실제 바이트 > 16 KiB → 413 `REQUEST_TOO_LARGE`(상한 + 1바이트까지만 읽음),
  지표 `paycore.admin.merchant_ops.search_body_rejected` 재사용, tag key는 **모든 등록에서 같은 `{reason, endpoint}`**(C-6 — Micrometer·Prometheus는 같은 meter 이름에 다른 tag key 집합을 섞으면 등록 거부·누락이 난다):
  기존 가맹점 검색 등록(`MerchantController.kt:55`)도 `endpoint=merchant-search`를 붙이고 새 endpoint는 `endpoint=business-lookup`. 두 컨트롤러가 같은 상수(tag key·값)를 공유한다. `reason` 값 집합은 기존 그대로.
- 응답: 200 MASTER 문서 그대로(`items` 0/1 — `MasterResponseShape`에 문법: `businessId` `^biz_<ULID>$`, `registrationNumberMasked` `^[0-9]{3}\*{7}$`, `businessName` 1..100자, 모르는 필드 → 해석 불가 = fail-closed 503), 400 `INVALID_INPUT`, 403 `FORBIDDEN`, 429 `RATE_LIMITED`, 503 `MASTER_UNAVAILABLE`·`AUDIT_UNAVAILABLE`. 오류 본문에 입력 값 없음.
- **"원문이 응답에 섞이면 거부"**: MASTER 응답에 `registrationNumber` 필드(원문)가 오면 모르는 필드로 fail-closed(503) — 대조군 시험.
- 응답의 `businessId`는 D-BIZLOOKUP-001에 따라 **브라우저까지 전달**한다(가맹점 등록 요청 본문용). ADMIN은 응답 바이트를 저장하지 않는다(조회 — `admin_request` 없음).

### 4.2 판정 순서(Use Case — `BusinessLookupService` 분리, C-4: 역할 집합이 달라 `MerchantLookupService`에 두면 상수 혼동 위험)
1. 계정 현재 행 ACTIVE + 조회 역할(`VIEW_ROLES`) — 아니면 403, **감사 없음**(MERCHANT-OPS-001 §5.2와 같은 앞단: 세션이 없거나 조회 권한조차 없는 호출).
2. **사업자 조회 역할 = OPERATOR** — 아니면 403 + 감사 `FORBIDDEN`(APPROVER 단독·VIEWER·SECURITY_ADMIN 계정의 시도도 남긴다).
3. 형식(ASCII 숫자 10자리) / 본문 해석 실패 → 400 + 감사 `INVALID_INPUT`(detail 고정 `brn=INVALID`, targetRef 없음).
4. 등록번호 버킷(`RegistrationNumberBudget.admit` — **공유**, C-3) → 초과 429 + 감사 `RATE_LIMITED` + 지표 `rate_limited{kind=REGISTRATION_NUMBER}`.
5. 일반 조회 창 — **`BusinessLookupService` 자체 창**(C-12: actor별 분당 `lookupPerMinute`(Demo 30) 값을 쓰되 `MerchantLookupService`의 `lookupWindows`와 공유하지 않는 별도 `ActorMinuteWindows`) → 초과 429 + 감사 `RATE_LIMITED` + 지표 `rate_limited{kind=LOOKUP}`.
   이 창은 보조 장치다 — 공유 등록번호 버킷(4번, 10/분)이 먼저 걸리므로 정상 순서에서는 이 창에 닿지 않는다. 분리 이유: 사업자 조회가 가맹점 목록·상세 창을 소모해 가맹점 조회 화면이 429가 되는 간섭을 막는다(정보 노출 통제는 4번이 담당).
6. MASTER 호출(`MasterOpsGateway.lookupBusiness` — 새 메서드, **DB TX 밖**, 조회 permit, redirect NEVER, 응답 상한 4 KiB).
7. 감사(별도 로컬 TX — 기존 `audited` 틀): 200이면 `OK`, detail `brn=123*******;results=0|1`, 찾으면 `targetRef = businessId` / MASTER 불가·응답 해석 불가 → `UNAVAILABLE`(detail 마스킹 값). **감사 쓰기 실패 → 503 `AUDIT_UNAVAILABLE`, 결과를 돌려주지 않는다**(감사 실패 = 행위 실패).
- 로그: `operation=business-brn-lookup result=… audit=…`(값 없음).

### 4.3 해석 실패 본문의 "시도" 판정(Q-MOPS-07 적용 — C-5)
- 이 endpoint는 **등록번호 조회 전용**이다. 가맹점 검색처럼 "등록번호 시도인지"를 key로 가를 필요가 없다 → **읽기 전 거절(413·415)을 지난 모든 본문은 시도**로 본다: 빈 본문·비 객체·모르는 필드·중복 key·자료형 오류·뒤따르는 토큰 모두 2→3 순서(OPERATOR 아니면 `FORBIDDEN`, 맞으면 `INVALID_INPUT`) 감사, detail `brn=INVALID`.
- 이유: Q-MOPS-07의 "가장 좁은 시도 규칙"은 다목적 검색 본문에서 등록번호와 무관한 오류를 감사 대상에서 빼려는 것이었다. 단일 목적 endpoint에서는 모든 호출이 등록번호 조회 의도이므로 넓게 감사하는 쪽이 "매 시도 감사"(D-MOPS-003)에 맞다. 본문 원문·값은 어디에도 남기지 않는다(고정 detail).
- 시도 감사도 속도 제한 **앞**(형식 위반 — 기존과 같음): 깨진 본문 대량 전송은 버킷을 소모하지 않지만 감사 행으로 남는다(L-3).

### 4.4 새 endpoint를 두는 이유(C-1 — 가맹점 검색에 mode 추가안과 비교)

| | (권장) 새 `business-lookups` | (대안) `merchant-searches`에 `{"target":"BUSINESS"}` 같은 mode |
|---|---|---|
| 결과 단위 | 사업자 0/1 — 응답 모양이 다르다 | 같은 endpoint가 두 모양을 돌려줌(ADMIN `MasterResponseShape`·화면 타입 분기) |
| 역할 | OPERATOR만(수용 기준) | 같은 endpoint 안에서 mode별 역할이 달라짐(OPERATOR·APPROVER vs OPERATOR) — 판정 분기 오류 위험 |
| 감사 의미 | 새 action — `results=N`이 사업자 수 | `MERCHANT_BRN_SEARCHED`의 `results`가 mode에 따라 가맹점 수/사업자 수로 바뀜(감사 해석 혼동) |
| 기존 규칙 불변(수용 기준 ③) | 가맹점 검색 parser·시험·Q-MOPS-07 시도 규칙 변경 0 | parser 필드·시도 규칙·시험 기대값 변경 |
| 비용 | 컨트롤러 handler·parser·Reader 1개씩 | 분기 코드 |

### 4.5 감사 action(C-7)
- `MERCHANT_BUSINESS_BRN_LOOKUP`(28자 ≤ 48) — `admin.merchantops` 감사 계열(`MERCHANT_BRN_SEARCHED`·`MERCHANT_BUSINESS_REGISTER`)과 같은 접두라 감사 화면 필터에서 묶인다. 결과 code 집합은 `MERCHANT_BRN_SEARCHED`와 같다(`FORBIDDEN·INVALID_INPUT·RATE_LIMITED·OK·UNAVAILABLE`).
- `NOT_FOUND` 결과 code를 따로 두지 않는다 — 못 찾음은 `OK`·`results=0`(기존 검색과 같은 기록 방식, 감사 열람자에게도 같은 정보량).
- ADMIN migration 없음(action 열 값 목록 CHECK 없음 — §1.1), `TARGET_REF` 형식 기존(`biz_`).

## 5. 감사·권한·oracle 방어

### 5.1 권한
- **OPERATOR만**. APPROVER가 가맹점 BRN 검색(D-MOPS-003)은 할 수 있지만 이 조회는 가맹점 등록(OPERATOR — D-MOPS-006 ①) 준비 단계라 쓰지 않는다. OPERATOR·APPROVER 겸직 계정은 OPERATOR로 통과. 서버만 판정(화면은 이미 OPERATOR 화면).

### 5.2 속도 제한 — 버킷 공유(C-3 권장)
- 공유 근거: 이 조회가 주는 정보("이 번호의 사업자가 있다 + 상호")는 가맹점 BRN 검색·사업자 등록 중복 거절과 **같은 oracle의 상위 집합**(가맹점 0개 사업자까지 드러남)이다. 버킷을 나누면 한 행위자가 세 경로로 분당 한도의 세 배를 탐색할 수 있다(D-MOPS-006 ④가 등록 중복을 검색 버킷에 넣은 이유와 같음).
- 대안(별도 버킷): 가맹점 등록 업무량이 BRN 검색과 겹쳐 429가 자주 나면 고려 — 지금 Demo 값 10/분은 사람 입력 속도에 충분. 값은 Demo 값, 운영값 아님(LIVE Gate — 다중 인스턴스 합산 L-9 그대로).
- 버킷 소모 시점: 형식 통과 뒤(4번) — 형식 위반은 소모하지 않는다(기존 순서). MASTER 불가(503)도 이미 소모(재시도 남용 방지 — 기존 검색과 같음).

### 5.3 찾음·못 찾음 구별 불가 범위
- 같은 것: HTTP 상태(200), 응답 골격(`items` 배열), 감사 결과 code(`OK`), 버킷 소모, MASTER 질의 경로(UNIQUE 색인 한 번 — 두 경우 같은 계획), ADMIN 처리 경로(감사 TX 한 번).
- 다른 것(본질 — 이 기능의 목적): 항목 유무와 응답 길이. 이것은 권한(OPERATOR)·매 시도 감사·버킷으로 통제한다(D-MOPS-003 "oracle 취급"의 의미). 응답 길이를 패딩하지 않는다(C-8 — TLS 안 길이 차이는 인증된 OPERATOR 자신에게 이미 본문으로 보이는 정보).
- 시간: 못 찾음에서 감사 detail이 짧은 정도의 차이만. 측정 가능한 별도 지연을 두지 않는다(C-8). 시험은 "두 경로 모두 MASTER 1회·감사 1회" 호출 수 단언으로 고정.

### 5.4 평문 비노출·내부 키 범위(D-BIZLOOKUP-001)
- ADMIN: 감사(detail·reason·targetRef)·로그(appender)·지표 label·오류 본문·예외 메시지·`MasterOpsGateway` 실패 사유에 번호(전체·마스킹 모두 — 로그·지표는 마스킹 값도 금지, MERCHANT-OPS-001 §5.4) 0건. 감사 detail만 마스킹 값.
- MASTER: 로그·400 본문·응답 바이트에 원문 0건(응답은 SQL 마스킹 값만). `admin_request`·변경 이력을 쓰지 않는다(조회).
- 화면: 평문 번호는 입력 상태에만, 보낸 즉시 비움, 메모리·브라우저 저장소·URL·응답 사본에 없음(기존 `BusinessPicker` 규칙 그대로). **평문 번호가 나가는 요청은 조회 요청 하나뿐** — 가맹점 등록·계약 초안 저장 요청 본문은 `businessId`만 담는다.
- `businessId`: ADMIN 응답·화면 메모리(`BusinessRef`)·저장 요청 본문·감사 targetRef에만. **DOM(텍스트·속성·`title`·`aria-*`·`data-*`)에 `biz_` 0건**. React `key`는 DOM에 나가지 않으므로 기존처럼 써도 된다.
- 사업자 등록 명령의 중복 거절(D-MOPS-006 ③)은 그대로 businessId를 돌려주지 않는다 — 이 조회가 같은 번호로 businessId를 돌려주는 것은 별도 감사·버킷·역할을 거친 조회 결과다(D-BIZLOOKUP-001 해석).

## 6. 화면 연결(BL-3 — ADMIN-UI 가역 세부, Coordinator 결정)
- `BusinessPicker`(`MerchantRegistrationScreen.tsx:173-259`): `api.merchantSearch({ registrationNumber })`(L197) → **`api.businessLookup({ registrationNumber })`**(새 `adminApi` 메서드 `/api/business-lookups` — 타입 L734 근처·구현 L809 근처, `test/fakeApi.ts`에 `notStubbed('businessLookup')`). 결과 `items`(0/1) → `BusinessRef { businessId, name: businessName, masked: registrationNumberMasked }` — 타입·`offerBusiness`·`startWithBusiness` 불변(묶기 Map L198-202는 0/1이라 단순화 가능).
  한 번의 "찾기" = ADMIN 감사 1행(`MERCHANT_BUSINESS_BRN_LOOKUP`) — 지금처럼 `MERCHANT_BRN_SEARCHED`가 남지 않는다(같은 의도를 두 action으로 쪼개지 않음).
- 빈 결과 문구(L242): "찾은 사업자가 없습니다(가맹점이 있는 사업자만 찾습니다)." → **"이 번호로 등록된 사업자가 없습니다. 사업자 등록에서 먼저 등록하세요."**(사업자 등록 메뉴 링크 — D-ADMIN-UI-004 ④ 화면 분리 그대로).
- KDoc `MerchantRegistrationScreen.tsx:24-31`의 L27-29("기존 조회 기능 … 새 서버 API 없음 … 한계: 가맹점이 하나도 없는 기존 사업자는…") 삭제·근거 갱신(D-ADMIN-UI-004 ⑦·D-BIZLOOKUP-001), `BusinessPicker` 주석 L173 갱신.
- 사업자 등록 화면 중복 거절("이미 등록된 사업자등록번호입니다") 아래 안내 한 줄: "가맹점 등록 ① 사업자 선택에서 이 번호로 찾을 수 있습니다." — **번호를 자동으로 넘기지 않는다**(C-9: 평문을 화면 간 메모리로 운반하지 않음, 사용자가 다시 입력 = 다시 감사·버킷 1회). 사업자 ID·링크 대상 사업자는 없다(D-MOPS-006 ③ — KDoc L21 유지).
- 사업자 ID는 그리지 않는다(D-ADMIN-UI-004 ③·D-BIZLOOKUP-001) — 상호·마스킹 번호만. 가맹점 조회 탭의 가맹점 BRN 검색(`MERCHANT_BRN_SEARCHED`, `MerchantsScreen.tsx:412`)은 변경 없음.

## 7. 8대 원칙 적용

| 원칙 | 의미(이 작업) | 증거(§8) |
|---|---|---|
| Financial Consistency | 쓰기·금액·권리 변화 없음. 고른 사업자로 가맹점 등록하는 판정 권위는 기존 MASTER `registerMerchant`(business 잠금·`BUSINESS_NOT_ACTIVE`) | BL-1-5(쓰기 0), 기존 등록 시험 불변 |
| Transaction Safety | MASTER 호출은 DB TX 밖, 감사는 별도 로컬 TX 하나 — 감사 실패면 결과 미반환 | BL-2-4 |
| Idempotency | 조회라 멱등 기록 없음(`admin_request` +0). 같은 조회 반복은 매번 감사·버킷 1회(의도된 비멱등 — 감사 대상 행위) | BL-1-5, BL-2-2 |
| Concurrency Control | 버킷 확인·차감 원자적(기존 `ActorMinuteWindows`), 동시 호출 N > 한도 → 정확히 한도만 통과 | BL-2-3 |
| Recoverability | MASTER 불가·응답 해석 불가 → 503 + `UNAVAILABLE` 감사, 재시도는 사용자 재입력(버킷 소모) — 상태 없음 | BL-2-4 |
| Reconciliation | 감사 `targetRef = businessId` ↔ MASTER `business` 행(사후 대조 가능), 못 찾음은 마스킹 값·시각·행위자 | BL-2-2 |
| Auditability | **모든 시도** 감사(403·400·429·OK·UNAVAILABLE·깨진 본문), 413·415만 감사 없음(Q-MOPS-08). 원문 번호는 조회 요청 1곳에서만 전송(D-BIZLOOKUP-001) | BL-2-1·2, BL-3-1 |
| Observability | `rate_limited{kind}`, `search_body_rejected{reason,endpoint}`, 조회 결과 카운터 `paycore.admin.merchant_ops.business_lookup{result}`(값 label 없음), canary 비노출 | BL-2-5 |

**기술 연결(이번 변경에 한함)**
- **jOOQ(CQRS)**: MASTER 조회는 `JooqBusinessLookupReader` 한 질의(UNIQUE 색인 정확 일치, SQL 마스킹). JPA Entity·Repository 변경 없음(조회에 Aggregate를 로드하지 않는다 — Domain 불변).
- **JPA**: 해당 없음(새 Entity·열·migration 없음). ADMIN 감사 쓰기는 기존 `AdminAuditRecorder`(JPA) 그대로 — 이 작업이 JPA 매핑을 바꾸지 않으므로 JPA 선택 설명 대상 없음.
- **Kafka/Redis**: 도입하지 않는다. 버킷은 기존 인스턴스 메모리(LIVE Gate L-9 — 다중 인스턴스 합산은 별도 결정). 분산 캐시가 감사·속도 제한의 정확성을 대신하지 않는다.

## 8. 시험·수용 기준(배치별 — Verifier 확인 항목, MERCHANT-OPS-002 §8.4 형식)

공통: exit code·시험 개수·0건/skip 실패·보고서·source fingerprint·시각·대조군 단언. **RED 먼저**.
Fixture: `RegistrationNumberFixtures.CANARY = "7319024865"`(다른 Fixture 번호와 겹치지 않는 비노출 대조군, 마스킹 `731*******`), `BusinessFixtures.withoutMerchants(number = CANARY)`(가맹점 0개 — 이 작업의 업무 조건이 이름에 드러남), `BusinessFixtures.withMerchant(…)`(대조군).
Fixture Monkey는 형식 조합(ID·번호 모양)에만, `src/test`에만.

**BL-1 MASTER 조회**
1. **parser**(`BusinessLookupRequestParserSpec`): 정상 1건, 9·11자리·하이픈·전각 숫자·숫자 JSON·`null`·배열·중복 key·모르는 필드·뒤따르는 토큰·빈 본문·필드 없음 → 모두 null(400), 오류 본문에 입력 값 없음.
2. **Reader(MySQL)**(`JooqBusinessLookupReaderSpec`): 가맹점 0개 사업자 → 1건(**대조군**: 같은 번호로 기존 `JooqMerchantDirectoryReader` → 0건 — 이 작업의 원인 재현), 가맹점 있는 사업자 → 1건, 없는 번호 → null, 마스킹 `731*******`, select 필드 집합에 `BUSINESS.REGISTRATION_NUMBER` 원문 미포함.
3. **API**(`BusinessLookupApiSpec`): 서명 없음·시계 창 밖 401, `ADMIN_SERVICE` 아님 403, query 문자열 400, 16 KiB 초과 413, 찾음·못 찾음 둘 다 200 + `items` 배열(키 집합 동일), 찾음 항목 키 집합 = `{businessId, businessName, registrationNumberMasked}`(D-BIZLOOKUP-001 고정), 응답 바이트에 canary 원문 0건, `no-store`,
   조회 permit 소진 시 503 `ADMIN_READ_BUSY`(쓰기 permit과 독립 — 쓰기 permit 0이어도 조회 성공).
4. **비노출(MASTER)**: appender 포획 로그·400 본문에 canary 0건(마스킹 값도 로그에 0건).
5. **쓰기 0**: 호출 전후 `admin_request`·`master_change_history`·Outbox 행 수 불변, 쓰기 handler 전수 집합 불변 단언(새 handler는 조회 permit).
6. 변이: (a) Reader가 `merchant` 조인으로 회귀 → 2 RED, (b) SELECT에 원문 열 추가 → 2·3 RED, (c) parser 모르는 필드 허용 → 1 RED, (d) 못 찾음에 404 → 3 RED.

**BL-2 ADMIN 조회·감사**
1. **판정 순서 행렬**(`BusinessLookupServiceSpec`): 비활성 계정·조회 역할 없음 → 403·감사 0행 / APPROVER 단독·VIEWER·SECURITY_ADMIN → 403 + `FORBIDDEN` 1행(`brn=` 마스킹 또는 INVALID) / OPERATOR + 형식 위반 → 400 + `INVALID_INPUT`(`brn=INVALID`) / 버킷 초과 → 429 + `RATE_LIMITED` / 정상 → MASTER 1회 + `OK`.
   각 행 MASTER stub 호출 수 단언(403·400·429는 0회).
2. **감사 내용**: 찾음 → detail `brn=731*******;results=1`·targetRef `biz_…`, 못 찾음 → `results=0`·targetRef 없음, action `MERCHANT_BUSINESS_BRN_LOOKUP`, 결과 code 집합 = `MERCHANT_BRN_SEARCHED`와 같음. **매 시도 1행**(같은 번호 3회 → 3행).
3. **버킷 공유**(C-3): 사업자 조회 10회 → 11번째 사업자 조회 429, **같은 분 안 가맹점 BRN 검색도 429**, 사업자 등록(중복 번호)도 버킷 예약 실패(대조군: 다른 actor는 통과). 동시 20 요청(같은 actor) → 정확히 10 통과(원자성).
   **창 분리(C-12)**: 등록번호 버킷 한도를 대역 값으로 크게 둔 시험에서 사업자 조회 30회 → 31번째 429(`kind=LOOKUP`), 같은 분 가맹점 목록·상세는 200(가맹점 조회 창 미소모). 반대로 가맹점 상세 30회 뒤에도 사업자 조회 200.
4. **실패 경로**: MASTER 503·timeout·응답 해석 불가(모르는 필드·원문 `registrationNumber` 필드 포함·마스킹 문법 위반) → 503 `MASTER_UNAVAILABLE` + `UNAVAILABLE` 감사, 감사 저장 실패(대역 Recorder 예외) → 503 `AUDIT_UNAVAILABLE`·**응답 본문에 결과 없음**.
5. **비노출(ADMIN)**: canary가 ADMIN 전 표 문자열 열(information_schema 전수 `LIKE '%7319024865%'`) 0건(마스킹 값 `731*******`은 감사 detail에만), 로그·지표 label·오류 본문·Gateway 예외 메시지 0건.
6. **HTTP(실제 로그인 세션 — `BusinessLookupHttpSpec`)**: CSRF 없음 403, 415·413(선언 길이·chunked 실제 길이 둘 다)·감사 0행·지표 +1(`endpoint=business-lookup`; 가맹점 검색 415도 같은 meter·같은 tag key 집합 `{reason, endpoint}`로 +1 — registry에서 그 meter의 모든 Id tag key 집합이 하나임을 단언), **깨진 본문 전부 시도**(빈 본문·비 객체·숫자 번호·중복 key → OPERATOR 400 + `INVALID_INPUT`, APPROVER 단독 403 + `FORBIDDEN`, 둘 다 detail `brn=INVALID`, C-5), `no-store`.
7. **기존 규칙 불변**: `MerchantLookupServiceSpec`·`MerchantSearchBodyParserSpec`·`MerchantControllerSpec` 기대값 변경 0(보고서에 파일별 "변경 없음" 목록 — C-6으로 기존 `search_body_rejected` 단언에 `endpoint=merchant-search` tag가 더해지는 곳은 tag 추가만, 횟수·`reason` 기대 불변 — 목록에 따로 표시), 가맹점 BRN 검색 역할 OPERATOR·APPROVER 그대로(APPROVER 단독 → 가맹점 검색 200·사업자 조회 403 대조).
8. 변이: (a) 역할 집합에 APPROVER 추가 → 1 RED, (b) 별도 버킷 사용 → 3 RED, (c) 깨진 본문 감사 제거(가맹점 검색의 시도 규칙 재사용) → 6 RED, (d) 감사 실패 시 결과 반환 → 4 RED, (e) detail에 원문 → 5 RED, (f) 못 찾음에 `NOT_FOUND` code → 2 RED, (g) 응답에서 `businessId` 제거 → 9 RED.
9. **결정 고정(D-BIZLOOKUP-001 — 시험 이름·주석에 결정 ID)**: ADMIN 200 응답 항목에 `businessId`(`^biz_<ULID>$`) 포함·`businessName`·`registrationNumberMasked`만(키 집합 단언), 평문 `registrationNumber` 키 없음.
   대조군(D-MOPS-006 ③ 불변): 같은 번호로 사업자 등록 → 중복 거절 응답 본문·감사 행에 `biz_` 0건(기존 시험 그대로 통과 — 변경 없음 목록에 포함).

**BL-3 화면 연결**
1. **Vitest**(`MerchantRegistrationScreen.test.tsx`): ① "찾기"가 `api.businessLookup({ registrationNumber })`를 부르고 `merchantSearch`는 부르지 않음, 가맹점 0개 사업자 결과 → "선택" → ②로 진행·가맹점 저장 본문 `businessId` = 결과 값, 빈 결과 → 새 문구 + 사업자 등록 링크, 보낸 뒤 입력란 비움·메모리 키에 번호 없음.
   **결정 고정(D-BIZLOOKUP-001)**: (a) **DOM에 `biz_` 0건** — ①~④ 각 단계에서 `container.innerHTML`(텍스트·모든 속성 포함) 전수 검사, 찾은 목록·선택된 사업자·④ 검토 화면 포함. (b) **평문 번호는 조회 요청에서만**: `fakeApi`의 모든 메서드 호출 인수를 직렬화해 원문 10자리(대조군 번호)가 `businessLookup` 첫 인수에만 1회 나타남 —
   `registerMerchant`·`registerMerchantSite`·`registerContract`·`addServiceAgreement`(`adminApi.ts:740-744`) 등 저장 호출 본문에는 0건이고 `businessId`만 있음. (c) `sessionStorage`·`localStorage`·`location.href`에 원문·`biz_` 0건.
   **기대 반전(근거 D-ADMIN-UI-004 ⑦ — 이름·주석에 결정 ID)**: L84-101 시험(제목 "existing registration-number search", L97 `merchantSearch` 호출 단언 → `businessLookup`, L99-100 "같은 사업자의 가맹점 두 건은 하나로 묶인다" 단언 → 응답 0/1이므로 "항목 1개" 단언으로 교체),
   L116-132 시험(L126 `merchantSearch` 미호출 → `businessLookup` 미호출, L131 빈 결과 문구 → 새 문구). 대역만 바뀌는 곳(기대값 불변): L136-140·L187·L768의 `merchantSearch` stub → `businessLookup` stub. L1021(가맹점 조회 탭 이동 뒤 `merchantSearch` 미호출)은 그대로. 그 밖 기대값 변경 0(목록 보고).
2. `BusinessRegistrationScreen.test.tsx`: 중복 거절 아래 안내 문구, 번호가 다른 화면 메모리로 넘어가지 않음(`startWithBusiness` 호출 0 — 중복 경로에는 `businessId`가 없으므로), 중복 거절 화면 DOM `biz_` 0건(D-MOPS-006 ③ 대조).
3. 오류 코드 문구(C-11): **새 문구 없음** — 403은 기존 일반 문구 `ERROR_MESSAGES.FORBIDDEN` "이 작업을 할 권한이 없습니다."(`errors/messages.ts:9`), 429·503도 기존 `ERROR_MESSAGES` 그대로. `messages.ts` 변경 0(시험: 403 응답 → 그 문구 표시).
4. 변이: (a) 화면이 `merchantSearch`로 되돌림 → 1 RED, (b) 번호를 메모리에 저장 → 1 RED, (c) 찾은 목록에 `businessId`를 표시(텍스트 또는 `data-*`·`title` 속성) → 1(a) RED, (d) 가맹점 저장 본문에 원문 번호를 함께 보냄 → 1(b) RED.

**Demo(Coordinator — Demo 값, 운영값 아님)**: OPERATOR가 "사업자 등록"에서 새 사업자 등록 → 다른 탭(새로 고침)에서 "가맹점 등록" ① → 번호 입력 → 상호·`123*******` 표시(사업자 ID 없음) → "선택" → 가맹점 등록 성공.
없는 번호 → 안내 문구. APPROVER 단독 계정 → 403. 감사 화면에서 `MERCHANT_BUSINESS_BRN_LOOKUP` 행들(마스킹 값·결과 수·찾은 경우 사업자 대상) 확인. 11번째 연속 조회 → 429.

## 9. Coordinator 결정(가역)
C-1 새 endpoint(가맹점 검색 mode 아님 — §4.4) / C-2 MASTER provided Port는 `MerchantOpsQuery` 확장 / C-3 등록번호 버킷 공유 + 일반 조회 버킷 / C-4 ADMIN Use Case `BusinessLookupService` 분리 /
C-5 단일 목적 endpoint라 413·415 뒤 모든 본문을 시도로 감사 / C-6 본문 거절 지표에 `endpoint` label / C-7 action `MERCHANT_BUSINESS_BRN_LOOKUP`, 못 찾음 = `OK`·`results=0` / C-8 응답 길이·시간 패딩 없음 /
C-9 사업자 등록 중복 거절에서 번호 자동 전달 없음(안내 문구만) / C-10 배치 BL-1 → BL-2 → BL-3(서버 배포 뒤 화면 — D-ADMIN-UI-003) /
**C-11(rev 0.3 — Coordinator 결정, 리뷰 m-1)** 403 화면 문구는 기존 일반 문구 "이 작업을 할 권한이 없습니다." 유지(새 문구 없음) /
**C-12(rev 0.3 — Coordinator 결정, 리뷰 m-2)** `BusinessLookupService`는 자체 일반 조회 창(30/분, 가맹점 조회 창과 공유 안 함), 실제 통제는 공유 등록번호 버킷.
(C-6 rev 0.3 보강: `search_body_rejected`의 모든 등록이 같은 tag key `{reason, endpoint}` — 리뷰 NIT.)

## 10. 사용자 질문(모두 답변됨 — PENDING 없음)

| ID | 질문 | 답 | 결정 | 적용 |
|---|---|---|---|---|
| Q-ADMIN-UI-7 | 가맹점 0개 사업자 선택 방법 | "사업자 조회 기능 추가 (Recommended)" | D-ADMIN-UI-004 ⑦ | 이 문서 전체 |
| **Q-BL-1** | 조회 응답에 내부 키 `businessId`를 넣는가(D-MOPS-006 ③ 사업자 등록 중복 거절의 businessId 비노출과의 관계) | **"내부 키 응답, 화면 미표시 (Recommended)"**(2026-11-14 — 첫 응답 "사업자 번호를 id 대체"는 원문 노출·정정 가능 값 문제 설명 뒤 재질문) | D-BIZLOOKUP-001 | §0·§2·§4.1·§5.4·§6, 결정 고정 시험 BL-1-3·BL-2-9·BL-3-1(a)(b)(c)·BL-3-2, 변이 BL-2-8(g)·BL-3-4(c)(d). D-MOPS-006 ③은 등록 명령 거절 응답 범위로 유지 |

새 질문 없음(rev 0.2 저장소 대조에서 금융·보안·키 동일성 미결을 새로 찾지 못함).

## 11. 위험·한계
- **L-1** 이 조회는 가맹점 0개 사업자의 존재·상호까지 드러내는 oracle이다(의도된 기능). 통제: OPERATOR·매 시도 감사·공유 버킷. 다중 인스턴스 버킷 합산·이상 탐지 경보(같은 actor 연속 못 찾음)는 LIVE Gate.
  **MASTER endpoint 측(rev 0.3 — 리뷰 NIT)**: 감사·actor 버킷은 ADMIN에만 있다. `ADMIN_SERVICE` 서명 키가 새면 MASTER `business-lookups`를 직접 불러 감사·버킷 없이 번호 → 상호를 탐색할 수 있다(동시성은 조회 permit만 제한). 기존 `merchant-searches` 등록번호 검색과 같은 성질의 위험이며 이 작업이 새로 만드는 범주는 아니지만 가맹점 0개 사업자까지 범위가 넓어진다.
  완화: 키는 환경변수로만·LIVE에서 Demo 키 기동 실패(기존), MASTER 로그 `operation=BusinessLookup` 건수 지표·키 교체 절차·MASTER 측 서비스 단위 속도 제한은 LIVE Gate.
- **L-2** 사업자 종료(MASTER-LIFECYCLE-001)가 아직 없어 모든 사업자는 ACTIVE다. 종료가 생기면 응답에 상태가 없으므로 종료 사업자도 "선택"되고 가맹점 등록에서 MASTER가 `BUSINESS_NOT_ACTIVE`로 거절한다(안전 — fail-closed). 그때 응답 필드 추가 여부를 다시 본다(사용자 결정 "상호·마스킹 번호만" 범위 확인 필요).
- **L-3** 깨진 본문(C-5)은 버킷을 소모하지 않고 감사 행만 만든다 — 대량 전송 시 감사 표 증가. 기존 가맹점 검색과 같은 성질(형식 위반은 속도 제한 앞). 일반 조회 버킷을 형식 앞에 둘지는 LIVE Gate 검토(지금 순서 변경은 D-MOPS-003 순서 위반이라 하지 않음).
- **L-4** 같은 사람이 가맹점 등록 중 번호를 여러 번 조회하면 버킷(10/분)을 빨리 쓴다 — Demo 값, 운영값은 LIVE Gate.
- **L-5(rev 0.2)** `businessId`는 브라우저 메모리·개발자 도구 네트워크 탭에서 보인다(D-BIZLOOKUP-001이 허용한 범위 — 화면 표시만 금지). 같은 OPERATOR가 기존 가맹점 BRN 검색으로 이미 받는 값과 같은 수준이며 인증·권한 근거가 아니다(가맹점 등록 판정은 MASTER 권위).

## 12. 기록
| rev | 내용 |
|---|---|
| 0.1 | 초안. D-ADMIN-UI-004 ⑦·수용 기준 적용, C-1~C-10 권장, Q-BL-1 PENDING |
| 0.2 | **Q-BL-1 답 적용(D-BIZLOOKUP-001 "내부 키 응답, 화면 미표시", 권장)**: §0·§2·§4.1·§5.4·§6 반영, D-MOPS-006 ③ = 등록 명령 거절 응답 범위로 해석 명시, 결정 고정 시험(BL-1-3 키 집합, BL-2-9, BL-3-1 DOM `biz_` 0건(속성 포함)·원문 번호는 조회 요청에서만·저장소/URL 0건, BL-3-2 대조)·변이 추가, L-5, 질문 표 ANSWERED. **저장소 대조(HEAD `7a50459`)**: `startWithBusiness` 위치 정정(`lib/registration.ts` L155 → **`MerchantRegistrationScreen.tsx:155`** — `registration.ts`는 29줄), 감사 틀 `audited` L151-164 → **L143-164**, KDoc 범위 L25-30 → L24-31(한계 문장 L29), `BusinessRef` KDoc L20-28, `business` 표 V1:20-34, `Business.kt:14-16`, `BusinessRepository.kt:12-13`, parser Jackson 설정 L20-23, Reader `REPEATABLE_READ` L25, 조회 permit `guarded` L51-52·busy code `ADMIN_READ_BUSY`, 지표 전체 이름(`MerchantController.kt:55·102`, `MerchantOpsMetrics`), Demo 한도 `application.yml:140-141`, Vitest 기대 반전 범위를 실제 줄로 구체화(L84-101·L116-132 반전, L136-140·L187·L768 대역만, L1021 불변), action 길이 31 → 28자 정정. 그 밖 참조(`MerchantOpsController.kt:27-34`, `JooqMerchantDirectoryReader.kt:31·38-39`, `MasterSqlText.kt:18`, `MerchantController.kt:37-49`, `MerchantSearchBodyParser.kt:38-76`, `MerchantLookupService.kt:61-70·72-103`, `RegistrationNumberBudget.kt:7-28`, `MerchantOpsLimits.kt:7-13`, `AdminAuditEvent.kt:54`, ADMIN `V1:150`, `App.tsx:235-238`, `BusinessRegistrationScreen.tsx:63·108·129`, `adminApi.ts:734·809`) 확인 — 변경 없음 |
| 0.3 | rev 0.2 독립 리뷰 **REVIEW_PASS(minor)** 반영. m-1: 403 문구 새로 만들지 않음 — 기존 `ERROR_MESSAGES.FORBIDDEN`(C-11, BL-3-3 문구 변경 삭제), m-2: `BusinessLookupService` 자체 일반 조회 창(C-12 — §0·§4.2·BL-2-3 창 분리 시험), NIT: `search_body_rejected` tag key 일관성(C-6 보강·BL-2-6·BL-2-7), L-1에 MASTER endpoint의 `ADMIN_SERVICE` 키 유출 oracle 위험 추가 |
