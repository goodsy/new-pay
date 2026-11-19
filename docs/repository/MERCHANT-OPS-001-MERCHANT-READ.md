# MERCHANT-OPS-001 가맹점 조회(read-only) 명세 (rev 0.1)

작성 2026-11-03 KST, branch `feat/foundation-setup`(기준 commit `cc0a882`). Implementer 작성 — 자기 완료 판정 아님(Verifier·Reviewer 별도).

근거: 사용자 결정 2026-11-03(AskUserQuestion — 범위 read-only 먼저, Q-MOPS-02 상세 조회만 감사, Q-MOPS-03 전체 사업자등록번호 검색 포함·OPERATOR 이상·매 검색 감사),
D-ADMIN-001..004(관리자 인증·권한 — Q-ADMIN-AUTH 답변), D-MD-008(사업자등록번호 노출 통제), D-MS2-005(`institutionMerchantRef`는 감사 전용),
ADMIN-PAYMENT-OPS-001 §4.1–4.3·§4.9–4.20(ADMIN → 소유 서비스 서명 채널 템플릿), D-ADMIN-UI-003(서버 기능이 있는 메뉴만), ARCHITECTURE-CONVENTIONS §14.

## 1. 범위

| 포함 | 제외(후속) |
|---|---|
| ADMIN → MASTER 서명 내부 채널(MASTER 검증 측 + ADMIN 호출 측) | 등록·개통·정지 명령(maker-checker) → **MERCHANT-OPS-002**(BACKLOG, Q-MOPS-01 대기) |
| 가맹점 목록·검색(keyset), 상세(한 번의 일관 읽기), 변경 이력(감사 항목 투영) | 사업자등록번호 마스킹 해제(UNMASK) → Q-MOPS-04(PROPOSED, 보류) |
| 전체 사업자등록번호 정확 일치 검색(OPERATOR·APPROVER, 매 시도 감사) | 대표자명·정산 계좌(MASTER에 없음 — MASTER-PAYOUT-ACCOUNT-001 backlog) |
| 상세 조회 감사(`MERCHANT_VIEWED`) | 이름 부분 검색(색인 없는 LIKE — 규모 검토 후) |
| 관리자 화면 "가맹점 관리"(목록·상세 패널·이력) | mTLS(LIVE Gate — ADMIN-PAYMENT-OPS-001과 같다) |

읽기 전용이다: MASTER의 업무 표에 쓰지 않는다(jOOQ `adapter.query`, 읽기 전용 TX). ADMIN은 감사 1행만 쓴다(ADMIN DB 로컬 TX).

## 2. 사용자 결정·이번 작업의 가역 선택

| ID | 내용 | 종류 |
|---|---|---|
| D-MOPS-001 | 범위 read-only 먼저(목록·상세·이력 + 서명 채널). 명령은 MERCHANT-OPS-002. 이번 작업에 UNMASK 없음 | 사용자 결정 |
| D-MOPS-002 (Q-MOPS-02) | 접근 감사는 **상세 조회만**(`MERCHANT_VIEWED`: 행위자·시각·merchantId). 목록·이력 조회는 감사하지 않는다. 결제 대상 조회(I-23 — 조회 감사 없음)와 다른 이유: 개인사업자의 사업자등록번호는 개인정보일 수 있고 상세는 한 사업자를 특정해 보는 행위다 | 사용자 결정 |
| D-MOPS-003 (Q-MOPS-03) | 전체 사업자등록번호 정확 일치 검색을 이번 작업에 포함. **OPERATOR·APPROVER 역할**만(아래 §5.3), **모든 시도 감사**, 감사에 평문 저장 금지, oracle 취급(찾음/못 찾음 응답 모양 동일, 속도 제한, 형식 검사 후 호출, 오류·로그에 값 없음). 응답은 마스킹 값만 | 사용자 결정 + 역할·감사 형태는 사용자 직접 답변(Q-MOPS-05/06, 2026-11-04) |
| R-1 | 감사 detail은 마스킹 값(`brn=123*******`)과 결과 수만, 대상은 `targetRef = businessId`(찾은 경우). keyed hash는 쓰지 않는다(§5.4 근거) | 가역 구현 선택(보안 의미 — Reviewer 확인 대상) |
| R-2 | MASTER 채널 키 공간은 PAYMENT 채널·권리 채널과 분리(`paycore.master.admin-channel.*` / `paycore.admin.master-ops.*`) — ADMIN은 기동 때 PAYMENT ops 키와 같으면 거부 | 가역 구현 선택 |
| R-3 | 목록 검색은 ADMIN에서 `POST /api/merchant-searches`(본문 — 등록번호가 URL·접근 로그·브라우저 기록에 남지 않게), MASTER에서도 `POST`(서명 호출 query 금지) | 가역 구현 선택 |

## 3. ADMIN → MASTER 서명 채널(MASTER 검증 측 — `master.merchant.adapter.web.admin`)

ADMIN-PAYMENT-OPS-001 §4.2 계약과 **같은 서명 v1**(헤더 `PayCore-Admin-Signature: t=<초>,kid=<id>,v1=<64 hex>`, canonical
`paycore.admin-call.v1\nMETHOD\npath\nt\nhex(sha256(body))`, HMAC-SHA256, 판정 순서 크기 → query → 문법 → 창 → kid → 서명, query 외 거절은 원인 비구별 401).
코드 공유 없음 — 계약 golden `admin-call-signature-v1.golden.json`을 세 앱(payment-api·internal-admin·master-api)이 같은 바이트로 갖고 harness 시험이 독립 생성과 대조한다.

- 의존성: `spring-boot-starter-security`(Boot BOM 관리 버전 — 새 버전 고정 없음). 기본 사용자 생성(`UserDetailsServiceAutoConfiguration`)은 제외한다.
- 보안 체인 두 개(서블릿 웹 컨텍스트에서만 — demo-seed 1회성 명령은 비웹):
  1. `/internal/v1/admin/**`: STATELESS, CSRF·form·basic·logout·request cache·anonymous 끔, 서명 필터 → 권한 `ADMIN_SERVICE`만.
  2. 그 밖 전부: `GET /actuator/health`만 허용, 나머지 거부(401 `{"error":"UNAUTHENTICATED"}`, 로그인 화면·`WWW-Authenticate` 없음).
- 본문 상한 16 KiB(서명 전), 응답 `Cache-Control: no-store`(보안 기본 헤더), 요청 ID 응답 헤더 `PayCore-Request-Id`.
- 설정 `paycore.master.admin-channel`(기본값 없음 — 없으면 기동 실패): `signing-keys[1..2]{key-id, secret(Base64 ≥ 32바이트)}`, `clock-window`(1초..10분, 정수 초 — Demo 300초),
  `read-permits`(1..64 — Demo 4). toString에 비밀 없음.
- 기동 거부: context path 비어 있지 않음, admin-channel 키의 **kid 또는 바이트**가 권리 채널 dispatch 키(`paycore.master.entitlement.dispatch.signing-key[-id]`)와 같음
  (Binder로 속성만 읽음 — integration adapter 타입 의존 없음).
- 조회 permit(대기 0): 없으면 즉시 503 `ADMIN_READ_BUSY`. 지표 `paycore.master.admin_channel.auth{result,reason}`·`…busy{endpoint}`.

### 3.1 MASTER 내부 API(서명 필요, 모두 읽기)

| 메서드·경로 | 응답 |
|---|---|
| `POST /internal/v1/admin/merchant-searches` 본문 `{"merchantId"?, "businessId"?, "lifecycle"?, "registrationNumber"?, "after"?, "limit"?}` | 200 `{"items":[…],"nextCursor":<merchantId>\|null}` / 400 `REQUEST_MALFORMED` |
| `GET /internal/v1/admin/merchants/{merchantId}` | 200 상세 / 404 `MERCHANT_NOT_FOUND`(형식 아닌 ID 포함) |
| `GET /internal/v1/admin/merchants/{merchantId}/history/after/{seq}` | 200 `{"items":[…],"nextCursor":<seq>\|null}` / 404 / 400 |

- 공통 503 `ADMIN_READ_BUSY`·`TEMPORARILY_UNAVAILABLE`. 오류 본문은 `{"error":"<CODE>"}`만(요청 값 없음).
- 검색 본문: 알 수 없는 키·타입 불일치·형식 위반은 400(어느 필드인지 말하지 않는다 — 등록번호를 되돌려 주지 않는다). `limit` 1..50(없으면 20), `after`는 merchantId 형식,
  `lifecycle` ∈ {ACTIVE, TERMINATED}, `registrationNumber` 정확히 ASCII 숫자 10자리. 정렬 merchant_id 오름차순 keyset(`merchant_id > after`), `limit + 1` 행으로 다음 cursor 판정.
- 검색 본문 필드는 MERCHANT-NAME-SEARCH-001로 확장(`displayNamePrefix` 가맹점명 앞부분 — [MERCHANT-NAME-SEARCH-001](MERCHANT-NAME-SEARCH-001-MERCHANT-NAME-SEARCH.md)).
- 목록 항목: `merchantId, merchantName, businessId, businessName, registrationNumberMasked, lifecycle, entitlementRevision, midCount, createdAt`.
  **찾음/못 찾음 응답 모양 동일**(항목 수만 다르다).
- 상세(§3.2)와 이력(§3.3) 모양. 시각은 `…T…Z` ms 3자리 UTC 문자열.

### 3.2 상세 읽기(한 번의 일관 읽기)

`JooqMerchantDetailReader` — 모든 문장을 **한 읽기 전용 REPEATABLE READ TX**에서(구조 조회 review m-2와 같은 이유 — 그 사이 커밋된 발급·개통이 일부 표에만 보이는 찢어진 상세 금지).
시각은 저장 UTC 벽시계를 SQL `DATE_FORMAT`으로 읽는다(TZ-FIX-001 — JVM 시간대 무관). 등록번호는 SQL에서 `CONCAT(LEFT(registration_number, 3), '*******')`로만 읽는다(평문이
애플리케이션 메모리로 오지 않는다).

```
merchant {merchantId, name, lifecycle, entitlementRevision, createdAt}
business {businessId, name, registrationNumberMasked, lifecycle}
sites[] {merchantSiteId, channelType, channelIdentifier, lifecycle, review: {decision, decidedAt} | null}   // 최신 심사 결정
contracts[] {contractId, createdAt, revisions[] {revisionNo, scopeKind, effectiveFrom, effectiveTo, approvedAt, scopeSiteIds[]},
             agreements[] {agreementId, serviceKind, addedAt}}
accounts[] {merchantAccountId, lifecycle, isDefault, issuingContractId, issuingAgreementId, issuingRevisionNo, issuedAt,
            assignments[] {assignmentId, merchantSiteId, role, status, effectiveFrom, effectiveTo},
            paymentMethods[] {activationId, paymentMethod, status, registeredAt},
            currencies[] {enablementId, currencyCode, effectiveFrom, effectiveTo, endedAt},
            cardRoutes[] {activationId, paymentMethod, acquirerCode, routeRef, status, registeredAt}}
limits[] {policyId, scopeKind, agreementId, merchantAccountId, currencyCode, perTxKind, perTxAmount, dailyKind, dailyAmount, effectiveFrom, effectiveTo, endedAt}
propagation {latestRevision, latestRevisionIssuedAt, latestAckRevision, latestAckAt, effectConfirmedAt, effectBasis} | null   // 기존 EntitlementPropagationQuery(별도 읽기)
```

**절대 내보내지 않는 값**: `card_route_activation.institution_merchant_ref`(D-MS2-005 — 읽는 SQL에 열 자체가 없다), `master_change_history.detail`(그 안에 기관 가맹점 번호 평문),
사업자등록번호 평문, evidence_ref·reason 자유 문구(이번 범위 밖 — 필요하면 별도 결정).
한도 금액은 원 단위 정수(Demo 값 — 운영값 아님)를 그대로 보여 준다(설정값이며 금융 사실 아님).

### 3.3 변경 이력(감사 항목 투영)

`JooqMerchantHistoryReader` — 같은 TX에서 가맹점 소유 집계 ID(Business·Merchant·MerchantSite·Contract·ContractServiceAgreement·MerchantAccount·SiteAssignment·
PaymentMethodActivation·SiteActivationConfirmation·CurrencyEnablement·PolicyAssignment·TransactionLimitPolicy·CardRouteActivation)를 모으고
`(aggregate_type, aggregate_id) IN (…)`(기존 색인 `ix_master_change_history_aggregate`) + `id > seq` 오름차순 50행 + 1.
투영 열: `seq(id), occurredAt, operation, aggregateType, aggregateId, changeType, actorRef` — **`detail`·`request_record_id`는 SELECT하지 않는다**.
PolicyVersion(전역 정책)은 가맹점 소유가 아니라 제외. 위험: 집계가 매우 많은 가맹점은 IN 목록이 길어진다(Demo 규모 문제 없음 — 대량 시 페이지 전략 재검토, §9).

## 4. ADMIN 호출 측(`admin.merchantops`)

- `HttpMasterOpsGateway`(JDK `HttpClient` — 프록시 없음·redirect NEVER, connect/read timeout, 응답 256 KiB + 1바이트까지만 읽음 — 상세가 결제 대상보다 크다, 초과는 해석 불가).
  **DB TX 안이면 `IllegalStateException`**. 실패는 예외가 아니라 값(`Unavailable(reason)`). 자체 permit(Semaphore 대기 0 — PAYMENT ops permit과 별개: MASTER 장애가
  승인·결제 운영을 막지 않는다). 매 호출 새 `t`. 경로 변수는 형식 검사한 값만.
- 응답 검증: MASTER 응답을 **허용 필드 목록(모양 표)** 으로 검사해 다시 만든다. 알 수 없는 필드·타입 위반·형식 위반이면 해석 불가(fail-closed — MASTER가 실수로 새 필드를
  내보내도 화면에 흘리지 않는다). `institutionMerchantRef`·`detail`은 모양 표에 없다.
- `MasterOpsSettings`(`paycore.admin.master-ops` — 기본값 없음): `base-url`(경로·query 없음), `key-id`, `signing-key`(Base64 ≥ 32), `connect-timeout`, `read-timeout`,
  `permits`(1..64), `lookup-per-minute`(1..10000 — Demo 30), `registration-number-searches-per-minute`(1..1000 — Demo 10). toString 마스킹.
- 기동 거부: context path, LIVE + Demo/시험 키, **master-ops 키의 kid 또는 바이트 = payment-ops 키**(Environment 속성으로 읽음 — paymentops 타입 의존 없음).

### 4.1 화면 API(세션 인증, `Cache-Control: no-store`)

| 메서드·경로 | 권한 | 응답 |
|---|---|---|
| `POST /api/merchant-searches` 본문 = MASTER 검색 본문과 같은 키 | VIEW_MASKED(4 역할), `registrationNumber`가 있으면 §5.3 역할 | 200 `{items,nextCursor}` / 400 `INVALID_INPUT` / 403 `FORBIDDEN` / 413 `REQUEST_TOO_LARGE`(16 KiB 초과 — Q-MOPS-08) / 415 `UNSUPPORTED_MEDIA_TYPE` / 429 `RATE_LIMITED` / 503 `MASTER_UNAVAILABLE`·`AUDIT_UNAVAILABLE` |
| `GET /api/merchants/{merchantId}` | VIEW_MASKED | 200 상세 / 400 / 403 / 404 `MERCHANT_NOT_FOUND` / 429 / 503 |
| `GET /api/merchants/{merchantId}/history?after=<seq>` | VIEW_MASKED | 200 `{items,nextCursor}` / 400 / 403 / 404 / 429 / 503 |

POST는 기존 SPA CSRF 규칙을 따른다(세션 + `X-XSRF-TOKEN`). 역할은 요청마다 계정 현재 행(ACTIVE)으로 판정한다(D-08).

## 5. 감사·권한·oracle 방어

### 5.1 상세 감사 `MERCHANT_VIEWED`(D-MOPS-002)
MASTER가 답한 경우(FOUND → `OK`, NOT_FOUND → `NOT_FOUND`) 감사 1행: actor, 시각, `targetRef = merchantId`, detail 없음. 감사 쓰기 실패 → 503 `AUDIT_UNAVAILABLE`,
**상세를 돌려주지 않는다**(감사 실패 = 행위 실패). MASTER 불가(503)는 본 것이 없어 감사하지 않는다. 목록·이력은 감사하지 않는다.

### 5.2 등록번호 검색 감사 `MERCHANT_BRN_SEARCHED`(D-MOPS-003) — 모든 시도
순서: 계정 ACTIVE + 조회 역할(아니면 403, 감사 없음 — 다른 조회와 같다) → **등록번호 역할 검사**(아니면 403 + 감사 `FORBIDDEN`) → 형식(ASCII 숫자 10자리, 아니면 400 + 감사
`INVALID_INPUT`, detail `brn=INVALID`) → 등록번호 전용 속도 제한(초과 429 + 감사 `RATE_LIMITED`) → 일반 조회 속도 제한 → MASTER 호출(TX 밖) → 감사(`OK` detail
`brn=123*******;results=N`, 찾았으면 `targetRef = businessId` / `UNAVAILABLE`) — 같은 감사 TX가 실패하면 503 `AUDIT_UNAVAILABLE`, 결과를 돌려주지 않는다.

**사용자 답변(정책)**
- **Q-MOPS-07(2026-11-04 "기록함")**: JSON 자료형부터 틀린 등록번호 검색 본문(예: `registrationNumber`가 숫자·배열)도 등록번호 검색 **시도로 감사**한다. 순서는 위와 같다: 계정·조회 역할(아니면 403,
  감사 없음) → 등록번호 역할(아니면 403 + 감사 `FORBIDDEN`) → 400 `INVALID_INPUT` + 감사 `INVALID_INPUT`. 두 감사 모두 detail은 고정 값 `brn=INVALID`, targetRef 없음 — 본문에서 아무것도
  꺼내지 않는다(원문은 감사·로그·오류 응답 어디에도 없다). 속도 제한 앞(형식 위반). 감사 실패 → 503 `AUDIT_UNAVAILABLE`. 등록번호 시도가 아닌 깨진 본문은 400 `INVALID_INPUT`, 감사 없음(D-MOPS-002 그대로).
- **Q-MOPS-08(2026-11-04 "거절만, 감사 없음")**: 상한을 넘는 검색 본문은 **해석하지 않고 거절**한다(413 `REQUEST_TOO_LARGE`). 내용을 읽지 않았으므로 시도 판정을 하지 않고 **감사하지 않는다**.
  거절은 지표 `paycore.admin.merchant_ops.search_body_rejected{reason=TOO_LARGE}`로만 센다. 상한 16 KiB(Demo 값 — MASTER 채널 상한과 같음, 운영값 아님).

**구현 해석(가역) — 근거: 검색 조건이 될 수 없는 본문**(Implementer 판단, 사용자 결정 아님 — Reviewer 확인 대상)
- 엄격 해석: 화면 본문은 `MerchantSearchBodyParser`(adapter.web)가 MASTER 파서와 같은 자료형 규칙으로 읽는다 — JSON 객체 하나, 알려진 6개 필드만, 중복 key·뒤따르는 토큰 거부, 강제 변환 없음
  (문자열 필드는 JSON 문자열, `limit`은 JSON 정수, `null` 거부), 빈 본문도 실패. 값의 형식(ID 모양·10자리·범위)은 Use Case가 검사한다(기존 감사 순서 유지 — 형식 위반 등록번호는
  `brn=INVALID`, 형식이 맞는 등록번호에 다른 필드 위반은 마스킹 값으로 `INVALID_INPUT` 감사).
- **시도 판정 규칙**(가장 좁은 안전 규칙): 본문을 앞에서부터 토큰으로 읽어(중복 key 허용, 첫 문법 오류에서 멈춤) **첫 최상위 JSON 객체의 직속 key로 `registrationNumber`가 나오면** 시도다 —
  값의 자료형(숫자·배열·객체·`null`)·다른 필드의 오류·그 key 뒤의 문법 오류와 무관하고, key의 `\u` escape는 해석된 이름으로 비교한다. **시도가 아닌 경우**: 최상위가 객체가 아님,
  중첩 객체 안의 같은 이름, 대소문자가 다른 key, 그 key에 닿기 전의 문법 오류(파서 중첩 한도 초과 포함), 첫 객체 뒤에 덧붙은 내용, **빈 본문**. 이 본문들은 어떤 해석(엄격·Spring 기본 Jackson
  모두)으로도 등록번호 검색 조건이 될 수 없다.
- **읽기 전 거절(감사 없음)**: Content-Type이 `application/json`(type/subtype 일치, charset 허용)이 아니거나 없으면 **415 `UNSUPPORTED_MEDIA_TYPE`** — 본문을 읽지 않는다(시도 판정 없음, 감사
  없음, 지표 `reason=UNSUPPORTED_MEDIA_TYPE`). Q-MOPS-08 상한은 선언 길이(`Content-Length`)가 넘으면 한 바이트도 읽지 않고, 선언이 없거나(chunked) 작게 선언돼도 **상한 + 1바이트까지만**
  읽어 판정한다. 413·415 응답은 다른 오류와 같은 `{"error":"<CODE>"}` + `Cache-Control: no-store`(매핑 `consumes` 대신 컨트롤러 안에서 검사). 상한은 설정값이 아니라 상수(`MasterOps` 설정은
  기본값 없는 관례 — Demo 고정값으로 문서화). 관리자 화면은 두 경우를 만들지 않아 프론트 오류 code 목록은 바꾸지 않았다(오면 일반 오류로 표시).

### 5.3 등록번호 검색 역할(사용자 결정 Q-MOPS-05, 2026-11-04)
**OPERATOR·APPROVER**(업무 운영 역할). VIEWER 제외(조회 전용 역할에 개인 식별 검색을 주지 않는다), SECURITY_ADMIN 제외(계정·보안 관리 역할 — 가맹점 업무 식별 필요 없음,
최소 권한), UNMASK 단독 제외(해제 권한이지 검색 권한 아님). 서버만 판정(화면은 입력란 표시만).

### 5.4 감사에 평문을 두지 않는 방식(R-1, 사용자 결정 Q-MOPS-06, 2026-11-04)
- 마스킹 값(앞 3자리)은 1,000개 버킷이라 원 번호를 복원할 수 없다. 찾은 경우 `targetRef = businessId`가 **정확히 어느 사업자를 조회했는지**를 남긴다(등록번호는 Business에
  UNIQUE — 결과는 한 사업자뿐). 못 찾은 검색은 "없는 번호를 찾았다"는 사실과 마스킹 값·시각·행위자만 남는다.
- keyed hash(HMAC)를 쓰지 않는 이유: 새 비밀 키(관리·교체·LIVE 가드)가 늘고, 10자리 숫자 공간(10¹⁰)은 키가 유출되면 전수 대입으로 복원되므로 이득이 작다. 기존
  `login-name-hmac-key` 재사용은 용도 분리 위반이라 하지 않는다. 같은 미존재 번호의 반복 탐색은 행위자·시각·마스킹 값 + 속도 제한 지표로 본다.
- 로그·예외·오류 응답·지표 label에 등록번호(전체·마스킹 모두)를 넣지 않는다. 형식 오류 응답은 `INVALID_INPUT`만.

### 5.5 oracle 방어
찾음/못 찾음 응답 모양 동일(200 + 빈 목록), 등록번호 검색 전용 actor별 분당 상한(Demo 10 — 인스턴스 메모리, 다중 인스턴스 합산은 LIVE Gate), 일반 조회 상한(Demo 30)과 별도.
MASTER 검색 본문은 서명 채널로만, query 문자열 금지. 지표 `paycore.admin.merchant_ops.rate_limited{kind}`.

## 6. 8대 원칙별 완료 증거

시험 이름은 실제 spec 클래스와 그 안의 `Then` 문구다(리뷰 MINOR-4, 2026-11-04 — 계획 단계의 별도 클래스 이름 `MasterAdminChannelSecuritySpec`·`MasterCallSignerGoldenSpec`·
`MasterOpsSettingsSpec`·`MerchantOpsIsolationSpec`은 만들지 않았고 아래 실제 spec 안에 있다). "미포함"은 시험이 없다는 뜻이다.

| 원칙 | 이번 작업의 의미 | 증거(시험 — master = `apps/master-api`, admin = `apps/internal-admin`) |
|---|---|---|
| Financial Consistency | 금융 상태를 바꾸지 않는다. 상세는 한 스냅샷(찢어진 구조 없음) | master `MerchantOpsReaderSpec` "every statement of one detail read runs in one read-only REPEATABLE READ transaction", master `ArchitectureSpec` 규칙 `queryAdapterDoesNotWriteWithJooq` |
| Transaction Safety | MASTER 호출은 ADMIN DB TX 밖, 감사는 ADMIN 로컬 TX, 실패 시 결과 미반환 | admin `MasterOpsGatewaySpec` "calling inside an active DB transaction is refused before any HTTP call", admin `MerchantLookupServiceSpec` "an audit failure returns AUDIT_UNAVAILABLE and no results; MASTER was called outside, the audit inside a transaction"·"an audit failure withholds the detail" |
| Idempotency | 조회는 부작용 없음(같은 요청 = 같은 결과). 서명 nonce 없음(명령 없음) | master `MerchantOpsApiSpec` "Idempotency: repeating the same search, detail and history returns identical bodies and leaves every MASTER table unchanged (row counts and checksums)" — 검색 3종(등록번호·사업자·없는 번호)·상세(있음·없음)·이력을 두 번씩, 상태·본문 동일, MASTER 스키마 전 표 `COUNT(*)`·`CHECKSUM TABLE` 전후 동일 |
| Concurrency Control | 대기 0 permit(MASTER·ADMIN 각자), ADMIN permit은 PAYMENT ops와 분리 | master `MasterAdminChannelSettingsSpec` "a context path is refused; the permits answer busy without waiting when exhausted", master `MerchantOpsApiSpec` "Concurrency Control: with every read permit held, a signed call is 503 ADMIN_READ_BUSY at once and counted per endpoint", admin `MasterOpsGatewaySpec` "a saturated MASTER permit answers busy immediately and does not slow PAYMENT ops lookups (separate pools)" |
| Recoverability | MASTER 장애 = 503 값(예외 전파 없음), 재시도 안전(읽기) | admin `MasterOpsGatewaySpec` "404 MERCHANT_NOT_FOUND is NotFound; other 404 and 5xx are Unavailable; an oversized body is unreadable"·"a read timeout and a refused connection are values, not exceptions"·"the read timeout also bounds reading the body…", admin `MerchantLookupHttpSpec` "MASTER unavailable is 503 MASTER_UNAVAILABLE (no cause in the body) and the detail is not audited…", master `MerchantOpsApiSpec` "Recoverability: a storage failure is 503 TEMPORARILY_UNAVAILABLE on every endpoint and the exception message (possibly bind values) is never logged" |
| Reconciliation | 해당 없음(읽기 전용) — 권리 전파 상태(propagation)를 상세에 표시해 대사 확인을 돕는다 | master `MerchantOpsReaderSpec` 상세 시험(`propagation` 존재), master `MerchantOpsApiSpec` 상세 최상위 키에 `propagation`. 전파 값 자체는 기존 `EntitlementPropagationQuerySpec` |
| Auditability | 상세 조회·모든 등록번호 검색 감사, 평문 없음 | admin `MerchantLookupServiceSpec` 등록번호 검색 5개 `Then`·상세 감사 `Then`, admin `MerchantLookupHttpSpec` VIEWER·OPERATOR/APPROVER·SECURITY_ADMIN 감사 행 단언(`brn=778*******`, 감사 표 열 전체에서 원문 0건). 형식이 깨진 본문(Q-MOPS-07): admin `MerchantLookupServiceSpec` Given "a search body that failed strict parsing (Q-MOPS-07)" 3개 `Then`, admin `MerchantLookupHttpSpec` "strict search body (Q-MOPS-07)…"(시도 11종 감사 `brn=INVALID` — JSON escape key 2종 포함·비시도 12종 감사 없음 — 깊은 중첩 포함·MASTER 미호출·응답·로그에 원문 없음)·"a malformed registration-number attempt by a VIEWER is 403…". 읽기 전 거절은 감사 없음(Q-MOPS-08): admin `MerchantLookupHttpSpec` "oversized search body (Q-MOPS-08)…"·"a non-JSON content type is 415…", admin `MerchantSearchBodyLimitSpec` 3개 `Then`(감사 행 없음·Use Case 미호출). 상한 없는 감사 행은 §9.1 L-3 |
| Observability | 채널 인증·busy·속도 제한 지표, 로그는 분류만 | 지표: master `MerchantOpsApiSpec` "accepted calls are counted…"·Concurrency `Then`(busy{endpoint}), master `MasterAdminCallSignatureGoldenSpec` 검증 사례별 REJECTED{reason}, admin `MerchantLookupServiceSpec` 속도 제한 Port 호출(`REGISTRATION_NUMBER`·`LOOKUP`), admin `MerchantLookupHttpSpec`·`MerchantSearchBodyLimitSpec` 본문 거절 지표 `paycore.admin.merchant_ops.search_body_rejected{reason}`(TOO_LARGE·UNSUPPORTED_MEDIA_TYPE) — Micrometer adapter의 지표 이름 `paycore.admin.merchant_ops.rate_limited` 단언은 **미포함**. 로그: master `MerchantOpsApiSpec` "Non-exposure in logs: no signing key, signature header or v1 value and no full registration number on accepted or rejected calls"·"search by the full registration number…", admin `MasterOpsGatewaySpec` "no signing key, signature header, v1 value or registration number appears in any log line or exception message…", admin `MerchantLookupHttpSpec` OPERATOR/APPROVER `Then`(등록번호·키·서명) — 모두 캡처 동작 대조군 포함 |

## 7. 수락 기준

1. MASTER: 무서명·틀린 서명·오래된 t·모르는 kid → 401 `UNAUTHENTICATED`(원인 비구별), query → 400, 16 KiB 초과 → 413, 등록된 `/internal/v1/admin/` handler 전부 무서명 401.
   `/internal/v1/admin` 밖 경로는 health 외 401. golden 서명 벡터·검증 사례 전부가 MASTER 필터에서 같은 결과.
2. MASTER 기동 거부: admin-channel 설정 없음·키 형식 위반·context path·권리 채널 키와 kid/바이트 같음. 실패 메시지에 키 없음. demo-seed 명령(비웹)은 admin-channel 설정 없이 동작.
3. 목록: 필터(merchantId·businessId·lifecycle·등록번호) 정확 일치, keyset 다음 cursor, limit 상한, 마스킹 등록번호·MID 수, 찾음/못 찾음 같은 모양.
4. 상세: §3.2 모양 전부, 한 RO·RR TX, Asia/Seoul JVM에서도 UTC 시각 그대로, 없는 ID 404.
5. **비노출**: 응답 바이트(목록·상세·이력)에 `institutionMerchantRef` 키·값, `detail` 키, 등록번호 평문이 없다 — 대조군으로 같은 DB에 그 값이 실제로 있음(카드 Route 기관 번호, 이력 detail)을 확인한다.
6. 이력: 가맹점 소유 집계 이력만, `detail` 없음, keyset cursor.
7. ADMIN: 서명 헤더가 golden 벡터와 바이트 일치(같은 파일), TX 안 호출 거부, timeout·연결 실패·비정상 응답·모양 위반(알 수 없는 필드 포함)·과대 응답 → Unavailable, permit 없음 → busy.
8. ADMIN 기동 거부: master-ops 설정 누락, payment-ops 키와 kid/바이트 같음, LIVE + Demo 키.
9. 권한: 비활성·역할 없음 403. 등록번호 검색 VIEWER·SECURITY_ADMIN 403 + 감사, OPERATOR·APPROVER 허용. 모든 등록번호 검색 시도 감사(평문 없음), 상세 조회 감사, 목록·이력 감사 없음.
10. 감사 실패 → 503 `AUDIT_UNAVAILABLE`, 결과 미반환. 속도 제한 429.
11. 화면: 조회 역할에 "가맹점 관리" 메뉴, 목록·검색·다음 쪽, 등록번호 입력란은 OPERATOR·APPROVER에만, 상세 패널(기본정보·Site·계약/CSA·MID/개통·권리 전파·변경 이력), Vitest.

## 8. 시험 목록(실제 — 리뷰 MINOR-4로 계획 이름을 실제 spec에 맞춤)

master-api(`com.paycore.master.merchant.adapter.web.admin`·`…adapter.query`):
- `MasterAdminCallSignatureGoldenSpec` — golden 헤더 계약, 서명 벡터 바이트 일치, 검증 사례를 실제 진입 필터에 넣은 결과·사유·상태·지표, 16 KiB 상한 413, context path 401.
- `MasterAdminChannelSettingsSpec` — 권리 dispatch 키와 kid/바이트 분리, 설정 검증(기본값 없음·키 길이·창·permit), toString 비밀 없음, context path 거부, permit 대기 0(busy 지표),
  Spring 기동 거부(설정 없음·키 공유·context path), 비웹 1회성 명령은 admin-channel 설정 불필요.
- `MerchantOpsApiSpec`(계획의 `MasterAdminChannelSecuritySpec` 내용 포함 — 실제 보안 필터 체인 + MockMvc + MySQL) — 등록 handler 전수·그 밖 경로 무서명 401(health만 허용),
  오래된 t·다른 키 401·query 400, **두 체인의 권한 규칙 자체**(M-3), permit 소진 503 `ADMIN_READ_BUSY`, 저장소 실패 503 `TEMPORARILY_UNAVAILABLE`(예외 문구 로그 없음),
  비노출 대조군·응답 모양·찾음/못 찾음 같은 모양·400/404 code, 반복 조회 동일·MASTER 표 불변(Idempotency), 로그에 키·서명·v1·등록번호 없음, 지표.
- `MerchantOpsReaderSpec`(실제 MySQL) — 목록 필터·keyset, 상세 모양·한 RO·RR TX·Asia/Seoul JVM, 이력 범위·cursor, 비노출 대조군.
- `ArchitectureSpec` — ALLOW_EMPTY 갱신(web adapter 생김), `queryAdapterDoesNotWriteWithJooq`.

internal-admin(`com.paycore.admin.merchantops`):
- `MasterOpsGatewaySpec`(계획의 `MasterCallSignerGoldenSpec`·`MasterOpsSettingsSpec`·`MerchantOpsIsolationSpec` 내용 포함 — 실제 HTTP 대역 `StubMasterOpsServer`) — golden 서명 벡터
  바이트 일치, 응답 허용 필드 모양(fail-closed), 404/5xx/과대 응답/timeout/연결 실패 → 값, 본문이 늦게 오는 응답도 read timeout 안에서 종료, 로그에 키·서명·v1·등록번호 없음,
  TX 안 호출 거부, 전용 permit(PAYMENT ops 조회 영향 없음), 설정 검증·기동 거부(PAYMENT ops 키 공유·LIVE Demo 키·context path).
- `MerchantLookupServiceSpec` — 조회 역할·형식, 등록번호 검색 역할·감사·속도 제한·감사 실패, 상세 감사·감사 실패, 공통 속도 제한, 형식이 깨진 본문(Q-MOPS-07 — 시도 감사·비시도
  무감사·비활성 403·감사 실패).
- `MerchantLookupHttpSpec`(실제 로그인 세션 + MySQL 감사 행) — 미인증 401, 역할별 검색·상세·이력, 감사 행(평문 없음), MASTER 불가 503 `MASTER_UNAVAILABLE`(감사 없음),
  엄격 본문(Q-MOPS-07 — 시도/비시도 행렬, VIEWER 시도 403 감사), 크기 상한 413·비 JSON 415(Q-MOPS-08 — 감사 없음·MASTER 미호출·지표·no-store 오류 모양, 정확히 16 KiB는 처리),
  CSRF 없는 POST 거부, 응답·로그에 등록번호·키·서명 없음.
- `MerchantSearchBodyLimitSpec`(컨트롤러 직접 — MockMvc는 Content-Length를 본문에서 만든다) — 선언 길이 없음(chunked)·작게 선언된 과대 본문은 상한 + 1바이트까지만 읽고 413, 선언 길이 초과는 한 바이트도
  읽지 않고 413, Use Case 미호출, 지표.

harness: `test_admin_call_signature_golden.py` TARGETS에 master-api 추가(golden 내용 불변).
frontend: `MerchantsScreen.test.tsx`, navigation 시험 갱신, labels 시험 갱신.

**미포함(시험 없음)**: Micrometer adapter 지표 이름 `paycore.admin.merchant_ops.rate_limited{kind}` 단언(Use Case는 Port 호출로 단언), 다중 인스턴스 속도 제한 합산(LIVE Gate),
mTLS(LIVE Gate), §9.1의 각 한계 항목.

## 9. 위험·미결

- Q-MOPS-01(PROPOSED — 명령의 2인 승인 범위, MERCHANT-OPS-002만 막음), Q-MOPS-04(PROPOSED·보류 — 등록번호 해제 필요성).
- 다중 인스턴스 속도 제한 합산, mTLS, 운영 timeout·permit 값은 LIVE Gate(Demo 값).
- 집계가 매우 많은 가맹점의 이력 IN 목록·상세 크기(256 KiB 상한 초과 시 ADMIN이 해석 불가로 503) — 규모가 커지면 상세를 블록별 endpoint로 나누는 것을 검토.
- master-api는 이제 `paycore.master.admin-channel` 설정이 없으면 웹 프로세스가 기동하지 않는다 → 로컬 Demo 실행에 Demo 키 추가 필요(`.env.example` 안내, 저장소에 키 값 없음).

### 9.1 알려진 한계·LIVE Gate 항목(리뷰 2026-11-04 — 이번 작업에서 고치지 않음, 정책 결정 아님)

| ID | 리뷰 | 내용 | 처리 |
|---|---|---|---|
| L-1 | MINOR-2 | master-api의 admin-channel 수신 측은 LIVE에서 Demo·golden 공개 시험 키를 **거부하지 않는다**(ADMIN 호출 측 `MasterOpsConfiguration`만 LIVE + Demo 키 거부). payment-api의 ADMIN 채널 수신 측도 같은 빈틈이다 | **LIVE Gate**: LIVE 기동 전 MASTER·PAYMENT 수신 측에 Demo·golden 키 거부 가드 추가(별도 작업 — 두 서비스 같은 방식) |
| L-2 | MINOR-3 | JDK `HttpClient`의 요청 timeout은 응답 헤더까지만 덮는다. 이번 조합(Boot BOM의 Spring Framework 7.0.x `JdkClientHttpRequestFactory.setReadTimeout`)에서는 본문 읽기도 같은 timeout이 끊는다 — 시험 `MasterOpsGatewaySpec` "the read timeout also bounds reading the body…"(헤더 즉시·본문 첫 바이트 뒤 3초 지연, read-timeout 400 ms → 2초 안에 `Unavailable`). 크기는 256 KiB + 1바이트 상한. 즉 묶이는 것: 연결(connect-timeout), 헤더·본문 수신 시간(read-timeout — Spring 동작에 기댄다, BOM 갱신 시 이 시험이 회귀를 잡는다), 본문 크기(256 KiB). 본문 단계 timeout의 분류 값은 `TIMEOUT`이 아니라 `CALL_FAILED`다(결과는 같은 503 `MASTER_UNAVAILABLE` — 로그·지표 분류만 다르다) | 기록만. 분류 정밀화는 필요 시 후속 |
| L-3 | MINOR-6 | 등록번호 검색 시도는 결과와 관계없이 **매번 감사 1행**(D-MOPS-003 "모든 시도 감사")이고 행 수 상한이 없다. 판정 순서상 `FORBIDDEN`(역할 없음)·`INVALID_INPUT`(형식 위반)은 속도 제한 **앞**이라 속도 제한이 감사 행 수를 줄이지 않는다(`RATE_LIMITED` 거절도 매번 1행). 완화 요소: ACTIVE 계정의 2단계 로그인 세션 + 조회 역할 + CSRF가 있어야 도달한다 | **LIVE Gate 검토**: 감사 표 증가 감시·보존 정책, 거절 시도 반복 탐지(지표·경보). 감사 생략·병합은 감사 정책 변경이라 사용자 결정 없이 하지 않는다 |
| L-4 | MINOR-7 | internal-admin 1회성 명령 모드(`--paycore.admin.command=…`)도 `paycore.admin.master-ops.*` 설정을 요구한다(`MasterOpsConfiguration`이 웹 조건부가 아니다 — 명령은 MASTER를 부르지 않는데도) | 한계로 기록. 명령 실행 환경에도 master-ops 설정(Demo 키)을 준다. 조건부 빈 전환은 후속 |
| L-5 | NIT-1 | 등록번호 검색 결과는 한 사업자의 가맹점이며 한 쪽 최대 20건(기본 limit)이다. 화면은 등록번호 검색에 다음 쪽 버튼을 주지 않는다(등록번호를 다시 보내는 페이지 요청을 만들지 않는다) | 화면이 이미 안내한다(`MerchantsScreen` — "결과가 더 있습니다… 사업자 ID로 검색해 주세요"). 사업자 검색은 keyset 다음 쪽 지원. **갱신(2026-11-13, ADMIN-UI-MERCHANT-002 / D-ADMIN-UI-004 ⑥)**: 사업자 ID를 화면에서 뺐으므로 안내 대신 "다음" 버튼이 등록번호 검색 결과에 이미 있던 내부 businessId로 사업자 검색 다음 쪽을 잇는다 — 등록번호는 다시 보내지 않는다(Vitest 고정), 새 정보 노출 없음 |
| L-6 | NIT-2 | 속도 제한은 actor별 **고정 1분 창**(인스턴스 메모리)이라 창 경계에서 최대 2배까지 몰릴 수 있다 | 기록만(다중 인스턴스 합산과 함께 LIVE Gate) |
| L-7 | NIT-3 | (해결 — Q-MOPS-07, 사용자 답변 2026-11-04 "기록함") 자료형이 틀린 화면 본문이 감사 없이 400이 되던 빈틈. 이제 엄격 해석 실패가 등록번호 검색 시도면 감사한다(§5.2) | **닫힘** |
| L-8 | NIT-4 | (해결 — Q-MOPS-07) 화면 본문의 중복 key·모르는 필드·자료형 강제 변환 수용. RED 때 실제로 `{"registrationNumber":<숫자>}`가 문자열로 변환돼 검색되고 `{"limit":"5"}`가 받아들여졌다. 이제 `MerchantSearchBodyParser`가 거부한다(§5.2) | **닫힘** |

## 10. 검증 기록(Implementer 실행 — Verifier 재검증 대상, 2026-11-04 KST)

시험 순서 고지: master-api 운영 코드는 시험보다 먼저 작성했다(TDD RED 증거 없음). 대신 핵심 비노출 보장은 아래 **변이 시험**으로 시험이 결함을 잡는지 확인했다(변이 적용 → RED 확인 → 원본 복원 → 복원 확인).

| 명령 | 결과 |
|---|---|
| `./gradlew :apps:internal-admin:test --tests 'com.paycore.admin.merchantops.*' --tests 'com.paycore.admin.architecture.ArchitectureSpec'` | exit 0 — MerchantLookupHttpSpec 7, MerchantLookupServiceSpec 10, MasterOpsGatewaySpec 10, ArchitectureSpec 33 |
| `./gradlew :apps:internal-admin:test` | exit 0 — 618건, 실패·오류·skip 0 |
| `./gradlew :apps:master-api:test` (1차) | exit 1 — DemoSeedCommandSpec I-3 실패: 비웹 명령 컨텍스트에서 `MerchantOpsController`가 웹 전용 `AdminChannelPermits`를 요구(실제 결함). 수정: 컨트롤러 `@ConditionalOnWebApplication(SERVLET)` |
| `./gradlew :apps:master-api:test` (수정 후) | exit 0 — 437건, 실패·오류·skip 0 |
| **변이 M-1**(master): 상세 Route SELECT의 `ROUTE_REF`를 `INSTITUTION_MERCHANT_REF`로, 이력 SELECT의 `ACTOR_REF`를 `CAST(detail AS CHAR)`로 바꿈 → `--tests …MerchantOpsApiSpec --tests …MerchantOpsReaderSpec` | exit 1(RED) — 4건 실패: Reader 상세 비노출·이력 비노출, API 상세 비노출·이력 비노출. 원본 복원(변이 문자열 0건 확인) |
| **변이 M-2**(admin): 감사 detail 마스킹 `"brn=" + number.take(3) + "*******"` → `"brn=" + number`(평문) → `--tests …MerchantLookupHttpSpec --tests …MerchantLookupServiceSpec` | exit 1(RED) — 6건 실패(HTTP 3: VIEWER 거부 감사·OPERATOR/APPROVER 감사 평문 검색·SECURITY_ADMIN 거부 감사, Use Case 3). 원본 복원(마스킹 식 1건 확인) |
| `python3 harness/tests/test_admin_call_signature_golden.py` | exit 0(6건) — TARGETS에 master-api 추가 |
| 관리자 프론트 tsc + Vitest(위임 구현자) | exit 0 — 132건(이전 107) |

새 시험 수: master-api +64(golden 38·설정 10·API 10·Reader 6), internal-admin +27, 프론트 +25. harness minTests: GRADLE-CHECK·TEST-SUMMARY 3329 → 3427(CHECKOUT-REACT-001 gate 기록 트리 3336 + 91), TEST-SUMMARY-FRONTEND 132 → 157(관리자 132 + 결제창 25).

#### 10.1 리뷰 반영(CHANGES_REQUESTED 후, Implementer 실행 2026-11-04 KST — Verifier 재검증 대상)

| 명령 | 결과 |
|---|---|
| **변이 M-3a**(master): 기본 체인 `it.anyRequest().denyAll()` → `permitAll()` → `--tests …MerchantOpsApiSpec --tests …MasterAdminChannelSettingsSpec`(새 시험 추가 전) | exit 1(RED) — 20건 중 1건 실패: "paths outside the admin channel are denied (401) except GET /actuator/health…" |
| **변이 M-3b**(master): admin 체인 `hasAuthority(ADMIN_SERVICE)` → `permitAll()` → 같은 명령(새 시험 추가 전) | exit 0 — **변이 생존**(실패 0/20). 서명 필터가 먼저 거절해 권한 규칙을 관찰하는 시험이 없었다 → 시험 추가 |
| M-3b 유지 + 새 시험 "defense in depth (M-3): the admin chain's own rule grants only ADMIN_SERVICE…" → `--tests …MerchantOpsApiSpec` | exit 1(RED) — 13건 중 1건 실패(그 새 시험: `someoneElse` 허용 expected false but was true) |
| M-3a 재적용 + 새 시험 → `--tests …MerchantOpsApiSpec` | exit 1(RED) — 13건 중 2건 실패(기존 경로 거부 시험 + 새 규칙 시험) |
| M-3 복원 확인 | `AdminChannelSecurityConfiguration.kt`는 미추적 파일이라 `git diff`로 볼 수 없다 → 변이 전 복사본과 `git diff --no-index` 차이 없음, sha256 `b195eb7e…8461` 변이 전과 동일, `permitAll` 1곳(health)만 |
| 변이(로그, master): 필터에 `log.info("mutation header={}", header)` → `--tests …MerchantOpsApiSpec` | exit 1(RED) — 1건 실패("Non-exposure in logs…"). 원본 복원(`--no-index` 차이 없음) |
| 변이(로그, admin): 게이트웨이가 서명 헤더를 INFO 로그 → `--tests …MasterOpsGatewaySpec --tests …MerchantLookupHttpSpec` | exit 1(RED) — 19건 중 2건 실패(게이트웨이 로그 시험, HTTP OPERATOR/APPROVER 시험). 원본 복원(`--no-index` 차이 없음) |
| `./gradlew :apps:master-api:test`(리뷰 반영 후 전체) | exit 0 — **442건**(이전 437 + 5), 실패·오류·skip 0 |
| `./gradlew :apps:internal-admin:test --tests 'com.paycore.admin.merchantops.*'`(중간 — MASTER_UNAVAILABLE 시험 추가 전) | exit 0 — 29건. 본문 지연 시험은 처음 `TIMEOUT`을 기대해 RED(실제 `CALL_FAILED`) → 관찰값으로 고정(§9.1 L-2) |
| `./gradlew :apps:internal-admin:test`(전체) | **미완료** — 실행 중 호스트 메모리 부족으로 세션 도구가 중단시킴(업무 PASS/FAIL 아님, 환경). `MerchantLookupHttpSpec` "MASTER unavailable is 503 MASTER_UNAVAILABLE…" 시험은 **아직 한 번도 실행되지 않았다** — Verifier 재실행 필요 |
| `python3 -B -m unittest discover -s harness/tests` | exit 0 — 163건 OK(minTests 변경 후) |
| **Q-MOPS-07 RED**: 새 시험 + Use Case 메서드 `rejectMalformedSearch`를 `TODO()`로만 둔 상태 → `--tests …MerchantLookupServiceSpec --tests …MerchantLookupHttpSpec` | exit 1(RED) — 23건 중 5건 실패: Use Case 3건 `NotImplementedError`, HTTP 2건 실제 결함(`{"registrationNumber":7781234560}`이 숫자→문자열 강제 변환으로 **검색되어 200**, VIEWER 시험의 `{"limit":"5"}`가 200) |
| **Q-MOPS-07 GREEN**: `MerchantSearchBodyParser` + 컨트롤러 바이트 본문 + `rejectMalformedSearch` 구현 → `./gradlew :apps:internal-admin:test --tests 'com.paycore.admin.merchantops.*'` | exit 0 — 35건, 실패·오류·skip 0(직전 미실행이던 "MASTER unavailable is 503 MASTER_UNAVAILABLE…" 포함) |
| `./gradlew :apps:internal-admin:test`(전체)·전체 gate | Coordinator 지시로 이번에 실행하지 않음(메모리) — Coordinator가 전체 gate로 실행 예정 |
| **재리뷰(CHANGES_REQUESTED, MAJOR 없음) — Q-MOPS-08 RED**: 새 시험 + 컨트롤러 구조만 변경(새 생성자·상수·본문 `readAllBytes`, `consumes` 유지, 상한 없음) → `--tests …MerchantSearchBodyLimitSpec --tests …MerchantLookupHttpSpec` | exit 1(RED) — 15건 중 6건 실패: 크기 상한 3건 `OutOfMemoryError`(상한 없는 본문 읽기 — 버퍼링 결함 그대로 드러남), HTTP 과대 본문 413 기대에 400(해석·감사됨), 415 지표 0(Spring 기본 응답), 그리고 시험 데이터 오류 1건(escape key + 올바른 번호 본문은 정상 검색이라 200이 맞다 — Kotlin 문자열이 escape를 미리 풀어 JSON escape를 시험하지 못하고 있었다 → `\\u004e` 일반 문자열 + 숫자 값으로 고침) |
| **Q-MOPS-08 GREEN**: 컨트롤러 안 Content-Type 검사(415)·선언 길이·`readNBytes(상한 + 1)`(413)·지표 → `./gradlew :apps:internal-admin:test --tests 'com.paycore.admin.merchantops.*' --tests '*ArchitectureSpec*'` | exit 0 — 73건(merchantops 40·ArchitectureSpec 33), 실패·오류·skip 0 |
| MINOR-A | `MerchantLookupService` 주석 "사용자 위임 결정 D-MOPS-003" → "사용자 직접 답변 Q-MOPS-05, 2026-11-04 (D-MOPS-003)". 변경 범위(main·test·명세) 다른 "위임"은 §10 "위임 구현자"(하위 구현자 뜻 — 사용자 결정 아님)뿐 |

특성화 시험(작성 직후 통과): Idempotency(반복 조회·표 불변), 로그 비노출(양쪽), MASTER busy·저장소 실패 503 — 운영 코드가 이미 요구를 만족해 RED가 없다. 로그 시험은 위 변이로 결함을 잡음을 확인했다.
Idempotency·busy·저장소 실패 시험은 변이 확인을 하지 않았다.

새 시험(리뷰 반영): master `MerchantOpsApiSpec` +5(M-3 권한 규칙, Concurrency busy, Recoverability 저장소 실패, Idempotency, 로그 비노출), admin `MasterOpsGatewaySpec` +2(본문 지연 timeout, 로그 비노출),
admin `MerchantLookupHttpSpec` +1(MASTER_UNAVAILABLE) — 기존 OPERATOR/APPROVER 시험에 키·서명 로그 단언 추가. harness minTests: GRADLE-CHECK·TEST-SUMMARY 3427 → 3435(+8, 측정 합계가 아니라 하한 + 추가 수), 프론트 157 유지.
Q-MOPS-07 추가 시험: admin `MerchantLookupServiceSpec` +3, `MerchantLookupHttpSpec` +2 → minTests 3435 → 3440(같은 방식 — 하한 + 추가 수).
Q-MOPS-08 추가 시험: admin `MerchantSearchBodyLimitSpec` +3, `MerchantLookupHttpSpec` +2(413·415 — 기존 Q-MOPS-07 시험에는 행 3개 추가, 시험 수 불변) → minTests 3440 → 3445.

