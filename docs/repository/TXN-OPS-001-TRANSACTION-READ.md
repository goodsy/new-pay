# TXN-OPS-001 거래 목록·상세 조회(read-only) 명세 (rev 0.2)

> **S3-DOC-001 문서 정합 표시(2026-11-25 — 코드·시험·정책 변경 없음, 자기 완료 판정 아님)**: §2 표의 D-TXOPS-001 '기간 필수'와 바로 아래 I-3(정확 조회는 기간 면제)이 같은 표 안에서 다르다(CAN 대장 진단 (c)-8, NP-99). 사용자 결정이 아직 없으므로 문장은 바꾸지 않고 **미결 표시**만 붙였다 — RULE-CONFIRM-001 Q11(BR-CAN-089·094) 답 전까지 현행 동작(I-3) 유지.

작성 2026-11-04 KST, branch `feat/foundation-setup`(기준 commit `2148eca`). Implementer 작성 — 자기 완료 판정 아님(Verifier·Reviewer 별도).

근거: 사용자 결정 2026-11-04(AskUserQuestion — Q-TXOPS-01..05 → D-TXOPS-001..005), ADMIN-PAYMENT-OPS-001 §4.2–4.3·§4.7·§4.20(ADMIN → PAYMENT 서명 채널·조회 permit·질의 시간 상한),
MERCHANT-OPS-001(같은 모양의 직전 작업 — 엄격 검색 본문·16 KiB·415·응답 허용 필드 모양·상세 감사), CP-QUERY-001(한 RR 읽기 전용 TX 상세 읽기), D-MS2-005(기관 내부 계정 값),
TZ-FIX-001(시각은 `DATE_FORMAT` 문자열), ARCHITECTURE-CONVENTIONS §9·§14, 07-operations(거래 ID를 지표 label로 쓰지 않는다, 관리 조회가 승인 DB를 무겁게 하지 않는다).

## 1. 범위

| 포함 | 제외(후속) |
|---|---|
| 기존 ADMIN → PAYMENT 서명 채널에 **읽기 endpoint 2개** 추가(새 키·새 채널 없음 — 같은 키·같은 필터·같은 조회 permit) | CSV/엑셀 내려받기(D-TXOPS-004) |
| 거래 목록 검색(생성일 기간 필수 + 환경·MID·상태 필터, 결제 ID 정확 조회, MID + 가맹점 주문번호 조회, 최신순 keyset) | 운영자 보정 취소 사유·근거·요청자·승인자 표시(개시 주체만 — D-TXOPS-005, 기존 승인 화면에서만 확인) |
| 거래 상세(Payment·승인·거래 사실·취소 이력·반환 잔액·hold 여부 — 한 일관 읽기) | 카드번호·BIN·끝 4자리·구매자 개인정보(PAYMENT DB에 열 자체가 없다) |
| 상세 조회 감사 `PAYMENT_VIEWED`(D-TXOPS-002) | 다중 인스턴스 속도 제한 합산·mTLS(LIVE Gate — MERCHANT-OPS-001과 같다) |
| 관리자 화면 "결제 관리 > 거래 조회"(목록·상세 패널) | 주문명 부분 검색(색인 없는 LIKE — 규모 검토 후) |

읽기 전용이다: PAYMENT 업무 표에 쓰지 않는다(jOOQ `adapter.query`, 읽기 전용 TX). 스키마 변경은 목록 색인 추가 migration 하나(V31 — 데이터·권한 변경 없음). ADMIN은 상세 조회 감사 1행만 쓴다(ADMIN DB 로컬 TX).

## 2. 사용자 결정과 Implementer 해석(구분)

| ID | 내용 | 종류 |
|---|---|---|
| D-TXOPS-001 (Q-TXOPS-01) | 생성일 기간 **필수**(최대 31일 — Demo 값), 선택 필터 환경(TEST/LIVE)·MID·결제 상태, 결제 ID 정확 조회, MID + 가맹점 주문번호 조회, 최신순 keyset 페이지, 상세(승인·취소 이력·잔액) | 사용자 결정 |
| D-TXOPS-002 (Q-TXOPS-02) | **상세만** `PAYMENT_VIEWED` 감사(반환 전 기록, 감사 실패 → 결과 미반환), 목록 검색은 감사하지 않는다 | 사용자 결정 |
| D-TXOPS-003 (Q-TXOPS-03) | 목록은 주문명 **앞 10자만**(나머지 숨김), 상세(감사 대상)에서 주문명 전체·승인번호·기관 거래번호. 기관 내부 계정 값(`institution_account` — D-MS2-005)은 **어디에도** 내보내지 않는다(SELECT하지 않는다) | 사용자 결정 |
| D-TXOPS-004 (Q-TXOPS-04) | CSV/내보내기 없음 | 사용자 결정 |
| D-TXOPS-005 (Q-TXOPS-05) | 거래 조회의 취소 이력에는 **개시 주체 종류(`initiatorKind` = MERCHANT·OPERATOR)만** 표시한다. 운영자 보정 취소의 사유(`operator_reason`)·근거(`purpose_kind`·`purpose_ref`)·요청자·승인자·approvalId는 이 화면·API에 없고 **기존 승인 화면에서만** 본다 | 사용자 결정(AskUserQuestion 2026-11-04 "표시만 유지 (Recommended)" — rev 0.1의 I-2 해석을 확정) |
| I-1 | 조회 역할 = VIEW_MASKED 4역할(VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN) — 기존 결제 대상 조회(I-23)·가맹점 조회와 같은 역할 집합 | **Implementer 해석**(사용자 결정 아님 — Reviewer 확인 대상) |
| ~~I-2~~ | (rev 0.2) 사용자 결정 D-TXOPS-005로 확정되어 해석 목록에서 뺐다 | — |
| I-3 **(미결 표시 — D-TXOPS-001 문언 '기간 필수'와 다른 해석. 사용자 확인은 RULE-CONFIRM-001 Q11(BR-CAN-089 `제안`), S3-DOC-001)** | 기간 필수는 **목록(필터) 검색**에 적용한다. 결제 ID 정확 조회와 MID + 주문번호 조회는 색인으로 범위가 묶이므로 기간 없이 허용(주면 같은 검사 후 함께 적용). 기간은 `[createdFrom, createdTo)` UTC 순간, `createdFrom < createdTo ≤ createdFrom + 31일` | **Implementer 해석**(조회 모양 — 금융 의미 없음) |
| I-4 | 화면 날짜는 Asia/Seoul 달력일로 입력받아 그 날 00:00 KST 순간(UTC)으로 바꿔 보낸다(끝 날짜 포함 → 다음 날 00:00 KST 미만). 서버는 순간만 받는다 | Implementer 해석(표시 관례) |
| I-5 | 목록 주문명 앞 10자는 SQL `LEFT(order_name, 10)`(utf8mb4 문자 = code point)로만 읽는다 — 전체 주문명이 목록 경로의 애플리케이션 메모리로 오지 않는다. `orderNameTruncated`(10자 초과 여부)만 함께 준다 | Implementer 해석 |
| I-6 | 반환 잔액은 저장 값(승인·완료·예약·FORCED·revision)만 보여 준다. 취소 가능액 계산식은 화면·조회에서 다시 만들지 않는다(금융 식 중복 금지 — 기존 대상 조회 `cancellableMinor`가 권위) | Implementer 해석 |
| R-1 | 새 서명 경로 2개는 기존 채널 계약(서명 v1·16 KiB·query 금지·kid)을 그대로 쓴다. golden 서명 벡터 파일은 바꾸지 않는다(경로 무관 계산) | 가역 구현 선택 |
| R-2 | 검색은 ADMIN 화면 `POST /api/payment-searches`·PAYMENT `POST /internal/v1/admin/payment-searches`(본문 — 가맹점 주문번호·MID가 URL·접근 로그에 남지 않게, 서명 채널은 query 금지) | 가역 구현 선택 |
| R-3 | ADMIN 쪽 조회는 기존 `paymentops` 모듈(PAYMENT 채널 소유 모듈)에 둔다 — 같은 `lookup` permit pool·같은 `lookup-per-minute` 값(새 설정 없음, actor별 창은 대상 조회와 별도) | 가역 구현 선택 |

### 2.1 기술 선택 메모(TECHNOLOGY-AND-PRINCIPLES §3–§5 — 이번 변경에 연결)

- **JPA**: 이번 작업은 JPA Entity·Mapper·Repository를 바꾸지 않는다. 조회는 영속성 컨텍스트를 거치지 않는 jOOQ DTO 읽기라 변경 감지·LAZY 로딩·`@Version`이 관여하지 않고(Entity를 화면에 직렬화하지
  않는다 — OSIV 무관), 업무 상태 변경은 기존 JPA 명령 경로(승인·취소 Use Case)만 한다. 순수 Domain 객체도 쓰지 않는다(조회 DTO는 `application.provided`, Port 구현은 `adapter.query`).
- **jOOQ CQRS**: `JooqAdminPaymentReader`(adapter.query) — SELECT만(ArchUnit `queryAdapterDoesNotWriteWithJooq`), 모든 문장에 질의 시간 상한 hint, 상세는 한 RO·RR TX. jOOQ는 Boot BOM 관리 버전
  그대로이고 codegen은 V31을 포함한 migration에서 재생성된다(색인만 — 생성 코드 표·열 변화 없음).
- **버전·의존성**: 새 라이브러리·버전 고정 없음(Boot BOM 조합 유지). 프론트도 새 npm 의존성 없음.
- **Kafka/Redis**: 쓰지 않는다. 속도 제한은 기존과 같은 인스턴스 메모리 창(L-2)이고 권위 데이터는 PAYMENT DB만 읽는다 — 캐시·메시지가 금융 안전성(소유 DB 권위·감사)을 대신하지 않는다.

## 3. PAYMENT 내부 API(서명 필요 — `payment.adapter.web.admin.AdminPaymentController`)

기존 ADMIN 채널 보안 체인(`/internal/v1/admin/**` — 서명 필터 → `ADMIN_SERVICE`)·내부 connector 전용·조회 permit(`AdminChannelPermits.read`, 대기 0)·질의 시간 상한
(`AdminReadLimits` — MySQL `MAX_EXECUTION_TIME(2000)` hint, 초과 3024 → 503 + `paycore.payment.admin_channel.query_timeout{endpoint}`)을 그대로 쓴다.

| 메서드·경로 | 응답 |
|---|---|
| `POST /internal/v1/admin/payment-searches` | 200 `{"items":[…],"nextCursor":{"createdAt","paymentId"}\|null}` / 400 `REQUEST_MALFORMED` |
| `GET /internal/v1/admin/payments/{paymentId}` | 200 상세 / 404 `PAYMENT_NOT_FOUND`(형식 아닌 ID 포함) |

공통 503 `ADMIN_READ_BUSY`(permit 없음 — `busy{endpoint=payment_search|payment_detail}`)·`TEMPORARILY_UNAVAILABLE`(저장소 실패·시간 초과 — 로그는 분류만). 오류 본문은 `{"error":"<CODE>"}`만.

### 3.1 검색 본문(엄격)

JSON 객체 하나, 알려진 키만, 중복 key·뒤따르는 토큰·`null` 거부, 자료형 강제 변환 없음. 어느 필드가 틀렸는지 말하지 않는다(400 `REQUEST_MALFORMED`).

| 키 | 형식 |
|---|---|
| `createdFrom`·`createdTo` | `yyyy-MM-ddTHH:mm:ss.SSSZ`(UTC ms 3자리) — `createdFrom < createdTo ≤ createdFrom + 31일` |
| `environment` | `TEST`·`LIVE` |
| `merchantAccountId` | `^[A-Za-z0-9._:-]{1,64}$` |
| `merchantOrderId` | `^[A-Za-z0-9._:-]{1,64}$`(D-CPC-002), `merchantAccountId` 필수 |
| `paymentId` | `^pay_[ULID]$` — 있으면 **다른 키 금지**(정확 조회) |
| `status` | `REQUESTED`·`AUTHORIZING`·`UNKNOWN`·`AUTHORIZED`·`FAILED`·`EXPIRED` |
| `after` | 객체 `{"createdAt": 시각, "paymentId": pay_ULID}`(정확히 두 키) |
| `limit` | JSON 정수 1..50(없으면 20) |

모드(I-3): `paymentId` → 정확 조회(0..1행). `merchantOrderId` → MID + 주문번호(기간 선택). 그 밖 → 기간 필수. 필터는 모두 AND, 정확 일치.

### 3.2 정렬·keyset

`ORDER BY created_at DESC, payment_id DESC`, 다음 쪽 `(created_at, payment_id) < (after.createdAt, after.paymentId)`(SQL `created_at <= c AND (created_at < c OR payment_id < p)` —
같은 created_at 동률을 payment_id로 끊는다). `limit + 1` 행으로 다음 cursor 판정. 시각 비교 값은 `CAST('…' AS DATETIME(3))` 리터럴(바인딩 시간대 변환 없음 — `UtcWallClock.literal`).

색인(V31, append-only migration — 데이터·권한 변경 없음, 한 `ALTER TABLE … ADD INDEX …, ADD INDEX …, ALGORITHM=INPLACE, LOCK=NONE` — 리뷰 MINOR-2): `ix_payment_created (created_at, payment_id)`(MID 없는 기간 검색), `ix_payment_mid_created (merchant_account_id, created_at, payment_id)`
(MID 기간 검색 — 환경은 잔여 조건, MID당 최대 2값). 주문번호 조회는 기존 `uk_payment_order_generation (environment, merchant_account_id, merchant_order_id, slot_generation)`
(환경 없으면 `environment IN ('TEST','LIVE')` 두 범위). 쓰기 비용: Payment INSERT마다 색인 2개 갱신(관리 조회의 전체 스캔을 막는 대가 — LIVE Gate에서 부하 측정).

### 3.3 목록 항목

`paymentId, environment, merchantAccountId, merchantOrderId, orderNamePreview(앞 10자 | null), orderNameTruncated, amountMinor, currency, status, createdAt`.
찾음/못 찾음 응답 모양 동일(항목 수만 다르다). 시각은 `…T…Z` ms 3자리 UTC 문자열(`DATE_FORMAT`).

### 3.4 상세(한 번의 일관 읽기 — `JooqAdminPaymentReader.detail`)

모든 문장을 **한 읽기 전용 REPEATABLE READ TX**에서 읽는다(CP-QUERY-001 CQ-05b와 같은 이유 — 그 사이 커밋된 취소가 일부 표에만 보이는 찢어진 상세 금지). 첫 문장이 Payment 행(범위 키
environment·MID를 함께 읽는다)이고 이후 문장은 모두 `payment` join으로 **같은 payment_id·environment·merchant_account_id**를 다시 건다(`payment_transaction`에는 환경·MID 열이 없다).

```
payment {paymentId, environment, merchantAccountId, merchantSiteId, merchantOrderId, orderName|null, amountMinor, currency, paymentMethod, status, createdAt}
onHold                                      // 해제 기록 없는 incident_hold(기존 정의 — D-CPQ-001)
terminationEvidence [code…]                 // payment_termination_evidence seq 순
authorization {authorizationId, status, failureEvidence|null, planKind, installmentMonths|null, cardType, openedAt} | null
transactions [{transactionId, type, amountMinor, currency, planKind|null, installmentMonths|null, cardType|null, approvalNumber|null, providerTransactionId,
               cancellationId|null, originalTransactionId|null, cancelScope|null, institutionTransactionDate, institutionBusinessDate|null, confirmedAt, recordedBy}]
cancellations [{cancellationId, initiatorKind, merchantCancelId|null, scope, status, failureEvidence|null, amountMinor, currency, openedAt}]
returnBalance {currency, authorizedMinor, completedMinor, reservedMinor, forcedMinor, revision} | null
```

**절대 내보내지 않는 값**(읽는 SQL에 열 자체가 없다): `authorization_attempt.institution_account`·`cancel_attempt.institution_account`(D-MS2-005 — 이 조회는 attempt 표를 읽지 않는다),
`payment_cancellation.operator_reason`·`purpose_kind`·`purpose_ref`·`requested_by`·`approved_by`·`approval_id`·`approved_target_fingerprint`(D-TXOPS-005), `source_result_id`·`source_attempt_id`
(기관 결과 원문 키), `payment_key`, 카드·개인정보(열 없음).

## 4. ADMIN 호출 측(`admin.paymentops`)

- `HttpPaymentOpsGateway`에 `searchPayments`·`paymentDetail` 추가 — 같은 서명기(`AdminCallSigner`, 같은 키)·같은 `lookup` permit(대기 0 — 없으면 `PAYMENT_OPS_BUSY`)·DB TX 안이면
  `IllegalStateException`·응답 64 KiB + 1바이트까지만 읽음. 실패는 값(`PaymentReadResult.Unavailable(reason)`).
- 응답 검증: `PaymentResponseShape`(허용 필드 모양 표 — 정확한 키 집합, 형식 정규식, 정수 범위, 목록 상한). 알 수 없는 필드·형식 위반 → 해석 불가(fail-closed). `institutionAccount`·
  `operatorReason` 등은 모양 표에 없다. 목록 `orderNamePreview`는 10자 이하만 통과(PAYMENT가 실수로 전체 이름을 보내도 화면에 흘리지 않는다).
  응답 해석은 중복 key를 거부한다(리뷰 NIT — `STRICT_DUPLICATE_DETECTION`).
- **요청-응답 대응 검사**(리뷰 MINOR-1): 모양이 맞아도 이 요청의 답이 아니면 해석 불가 — 항목 수 ≤ 요청 limit(없으면 20), 결제 ID 정확 조회는 그 결제 0..1건, 준 환경·MID·주문번호·상태와 같은 항목만,
  기간이 있으면 `createdAt ∈ [from, to)`, cursor가 있으면 `(createdAt, paymentId) < cursor`, 미리보기 일관성(잘림이면 정확히 10 code point, 미리보기 없음이면 잘림 아님). 상세는 응답 paymentId = 요청.
  주문명은 빈 문자열이 될 수 없다(CP-API-001 `OrderNameRule` — 1..100 code point, 없으면 NULL)라 모양 표는 빈 미리보기를 받지 않는다(리뷰 NIT 확인).
- 설정 추가 없음(R-3): `paycore.admin.payment-ops.*` 그대로. `.env.example` 변경 없음.

### 4.1 화면 API(세션 인증, `Cache-Control: no-store`)

| 메서드·경로 | 권한 | 응답 |
|---|---|---|
| `POST /api/payment-searches` 본문 = PAYMENT 검색 본문과 같은 키 | VIEW_MASKED(I-1) | 200 `{items,nextCursor}` / 400 `INVALID_INPUT` / 403 `FORBIDDEN` / 413 `REQUEST_TOO_LARGE`(16 KiB 초과 — 해석 전) / 415 `UNSUPPORTED_MEDIA_TYPE`(본문 읽기 전) / 429 `RATE_LIMITED` / 503 `PAYMENT_UNAVAILABLE` |
| `GET /api/payments/{paymentId}` | VIEW_MASKED | 200 상세 / 400 / 403 / 404 `PAYMENT_NOT_FOUND` / 429 / 503 `PAYMENT_UNAVAILABLE`·`AUDIT_UNAVAILABLE` |

POST는 기존 SPA CSRF 규칙(세션 + `X-XSRF-TOKEN`). 역할은 요청마다 계정 현재 행(ACTIVE)으로 판정한다. 화면 본문은 `TransactionSearchBodyParser`(MERCHANT-OPS-001 `MerchantSearchBodyParser`와
같은 자료형 규칙)로 엄격 해석하고, 값 형식·모드 규칙(§3.1)은 Use Case가 PAYMENT 호출 **전에** 검사한다. 413·415는 지표 `paycore.admin.ops.transaction_search_body_rejected{reason}`만 센다.

## 5. 감사·권한·노출

### 5.1 상세 감사 `PAYMENT_VIEWED`(D-TXOPS-002)
PAYMENT가 답한 경우(FOUND → `OK`, NOT_FOUND → `NOT_FOUND`) 감사 1행: actor, 시각, `targetRef = paymentId`, detail 없음. **감사 쓰기가 끝난 뒤에만** 상세를 돌려준다 — 감사 실패 →
503 `AUDIT_UNAVAILABLE`, 상세 미반환(감사 실패 = 행위 실패). PAYMENT 불가(503)는 본 것이 없어 감사하지 않는다. 목록 검색(결제 ID 정확 조회 포함)은 감사하지 않는다.

### 5.2 권한(I-1)
계정 ACTIVE + VIEW_MASKED 역할 아니면 403(감사 없음, PAYMENT 미호출). 형식 위반 400(PAYMENT 미호출). 속도 제한 actor별 분 단위 고정 창(`lookup-per-minute`, Demo 값 — 인스턴스 메모리).

### 5.3 노출 규칙(D-TXOPS-003, D-TXOPS-005, I-5)
- 목록: 주문명 앞 10자 + 잘림 여부만. 승인번호·기관 거래번호·전체 주문명 없음.
- 상세: 전체 주문명·승인번호·기관 거래번호(`providerTransactionId`) 표시(감사 대상).
- 어디에도 없음: 기관 내부 계정 값, 운영자 취소 사유·근거·요청자·승인자, 결제 key.
- 로그·예외·지표 label: 주문명·주문번호·MID·paymentId·서명·키를 넣지 않는다(지표 label은 endpoint·reason 고정 분류만 — 07-operations).

## 6. 8대 원칙별 완료 증거

시험 이름은 실제 spec 클래스와 그 안의 `Then`/`test` 문구다(§8). "미포함"은 시험이 없다는 뜻이다.

| 원칙 | 이번 작업의 의미 | 증거(시험 — payment = `apps/payment-api`, admin = `apps/internal-admin`) |
|---|---|---|
| Financial Consistency | 금융 상태를 바꾸지 않는다. 상세는 한 스냅샷(찢어진 상세 없음) | payment `AdminPaymentReaderSpec` "every statement of one detail read runs in one read-only REPEATABLE READ transaction (no torn detail)"(첫 문장 뒤 다른 연결의 취소 TX2 커밋을 끝까지 기다려도 뒤 문장들이 커밋 전 스냅샷 — `@@transaction_isolation = REPEATABLE-READ`, `read_only = 1`), payment `ArchitectureSpec` "adapter.query does not write with jOOQ…" |
| Transaction Safety | PAYMENT 호출은 ADMIN DB TX 밖, 감사는 ADMIN 로컬 TX, 감사 실패 시 결과 미반환 | admin `TransactionLookupServiceSpec` "PAYMENT is called outside a transaction and the audit inside one; an audit failure withholds the detail (AUDIT_UNAVAILABLE)"(변이 M-1로 결함 검출 확인 — §10), admin `PaymentReadGatewaySpec` "calling inside an active DB transaction is refused before any HTTP call". admin `TransactionAuditFailureHttpSpec` "the detail is 503 AUDIT_UNAVAILABLE with no PAYMENT value in the body and no audit row…"(실제 감사 저장 실패 주입) |
| Idempotency | 조회는 부작용 없음(같은 요청 = 같은 결과) | payment `AdminPaymentApiSpec` "Idempotency: repeating the same searches and detail returns identical bodies and leaves every PAYMENT table unchanged (row counts and checksums)" |
| Concurrency Control | 대기 0 permit(PAYMENT 조회 permit 공유·ADMIN lookup pool), keyset 동률 처리 | payment `AdminPaymentControllerSpec` "Concurrency Control: both endpoints are 503 ADMIN_READ_BUSY at once, without calling the query, and counted per endpoint", payment `AdminPaymentReaderSpec` "ties on created_at are broken by paymentId: pages never skip or repeat a row", admin `PaymentReadGatewaySpec` "a saturated lookup pool answers busy immediately without calling PAYMENT; a read timeout and a refused connection are values" |
| Recoverability | PAYMENT 장애·시간 초과 = 503 값, 재시도 안전(읽기) | payment `AdminPaymentControllerSpec` "Recoverability: each is 503 TEMPORARILY_UNAVAILABLE; a timeout is counted per endpoint; the exception message is never logged", admin `PaymentReadGatewaySpec` "detail: 200 parsed exactly; 404 PAYMENT_NOT_FOUND is NotFound; other 404, 5xx, unknown fields, malformed values and oversized bodies are Unavailable", admin `TransactionLookupHttpSpec` "PAYMENT unavailable is 503 PAYMENT_UNAVAILABLE (no cause in the body) and the detail is not audited (nothing was seen)" |
| Reconciliation | 해당 없음(읽기 전용, 대사 기능 아님) — 상세가 거래 사실(승인·취소 거래)·반환 잔액·hold를 한 화면에 보여 운영자 확인을 돕는다. 대사 Case·보정은 RECON 작업 몫 | payment `AdminPaymentReaderSpec` "the detail reads payment, authorization, transactions, cancellations, return balance and hold of that payment only" |
| Auditability | 상세 조회 감사(반환 전), 목록 미감사 | admin `TransactionLookupServiceSpec` "FOUND and NOT_FOUND are audited as PAYMENT_VIEWED with the payment as target before the result is returned; PAYMENT unavailable is not audited"·"searches, including the exact paymentId lookup, are never audited (D-TXOPS-002)", admin `TransactionLookupHttpSpec` "searches without audit (no-store, 10-character name only); the detail is audited (PAYMENT_VIEWED, target payment) before it is returned with the full name"(실제 MySQL 감사 행) |
| Observability | busy·질의 시간 초과·본문 거절·속도 제한 지표(label은 고정 분류), 로그는 분류만 | payment `AdminPaymentControllerSpec` busy·query_timeout `Then`, payment `AdminPaymentApiSpec` "Non-exposure in logs: no signature header or v1 value, no order name, merchant order id or MID on accepted and rejected calls", admin `TransactionLookupHttpSpec` "over 16 KiB is 413…counted"·"no MID, merchant order id, order name, payment id or signature appears in any log line", admin `PaymentReadGatewaySpec` "no signing key, signature header or v1 value, order name, merchant order id or MID appears in any log line", admin `TransactionLookupServiceSpec` 속도 제한 Port 호출 — Micrometer adapter 이름 `paycore.admin.ops.transaction_rate_limited` 단언은 **미포함** |

## 7. 수락 기준

1. PAYMENT: 새 경로 2개 무서명·틀린 서명 401(기존 필터), query 400, 16 KiB 초과 413(기존 필터 — 새 시험은 서명 401만 확인), 내부 connector 전용(공개 포트 404 — 기존 `ApiEntryFilter`).
2. 검색 본문 엄격성: 모르는 키·중복 key·`null`·자료형 위반·기간 없음·31일 초과·역전·`paymentId`와 다른 키 혼합·주문번호에 MID 없음·limit 범위 밖 → 400 `REQUEST_MALFORMED`(요청 값 없음).
3. 목록: 기간·환경·MID·상태 정확 일치, 최신순, keyset(동률 포함) 누락·중복 없음, limit 상한, 주문명 앞 10자 + 잘림 여부, 찾음/못 찾음 같은 모양.
4. 상세: §3.4 모양 전부, 한 RO·RR TX, Asia/Seoul JVM에서도 UTC 시각 그대로, 없는 ID 404.
5. **비노출**: 응답 바이트(목록·상세)에 `institution_account` 값·운영자 사유·근거 문구가 없다 — 대조군으로 같은 DB에 그 값이 실제로 있음을 확인한다. 목록에 전체 주문명 없음.
6. ADMIN: 서명 헤더 독립 검증(대역이 golden 시험 키로 검증), TX 안 호출 거부, timeout·연결 실패·비정상 응답·모양 위반(알 수 없는 필드 포함)·과대 응답 → Unavailable, permit 없음 → busy.
7. 권한: 비활성·역할 없음 403(PAYMENT 미호출). VIEW_MASKED 4역할 허용.
8. 감사: 상세만 `PAYMENT_VIEWED`(OK·NOT_FOUND, targetRef = paymentId), 반환 전 기록, 감사 실패 → 503 `AUDIT_UNAVAILABLE` 결과 미반환, 목록 미감사.
9. 화면 본문 상한 413·비 JSON 415(해석 전, 감사 없음, 지표), CSRF 없는 POST 거부, 속도 제한 429.
10. 화면: 조회 역할에 "결제 관리 > 거래 조회" 메뉴, 기간(KST 날짜)·환경·MID·상태 필터, 결제 ID·MID + 주문번호 조회, 다음 쪽, 상세 패널, Vitest.

## 8. 시험 목록

실제 spec 클래스(새 시험 수: payment-api +22, internal-admin +26(rev 0.1 20 + 리뷰 반영 6), 관리자 프론트 +9).

payment-api(`com.paycore.payment.payment.adapter.query`·`…adapter.web.admin`·`…architecture`):
- `AdminPaymentReaderSpec`(10 — 실제 MySQL·운영 confirm/cancel 경로 Named Fixture) — 기간 `[from, to)`·환경·MID·상태 정확 일치·최신순, 동률 keyset(누락·중복 없음), MID 없는 기간 검색·limit·cursor,
  결제 ID·주문번호 조회, 주문명 10자·잘림 여부(목록 직렬화에 전체 이름 없음), 상세 모양·범위(다른 Payment의 취소 미포함)·hold, 비노출 대조군(`institution_account`·`operator_reason`이 DB에
  실제로 있음), 한 RO RR TX(스냅샷 probe), Asia/Seoul JVM, EXPLAIN(모드별 색인·전체 스캔 없음·filesort 없음 — 주문번호 조회는 filesort 허용).
- `AdminPaymentSearchRequestParserSpec`(3) — 엄격 본문·모드 규칙·기간(31일 경계 포함)·형식, 잘못된 UTF-8.
- `AdminPaymentControllerSpec`(3 — 컨트롤러 직접, 대역 Query) — permit 소진 busy·지표, 저장소 실패·시간 초과 503(예외 문구 로그 없음·query_timeout 지표), 형식 위반 400/404(Query 미호출).
- `AdminPaymentApiSpec`(6 — 실제 보안 필터 체인 + 내부 connector + MySQL) — 무서명 401·query 400, 목록·상세 응답 키 순서·모양, 404 code(형식 아닌 ID 포함), 엄격 본문 400(값 echo 없음)·찾음/못 찾음
  같은 모양, 응답 바이트 비노출 대조군, Idempotency(PAYMENT 스키마 전 표 `COUNT(*)`·`CHECKSUM TABLE` 전후 동일), 로그 비노출(서명 v1·주문명·주문번호·MID·키).
- `ArchitectureSpec` — 진입 컨트롤러 목록에 `AdminPaymentController` 추가(경로 규칙·비참조 규칙 대상), 규칙 `adminPaymentQueryOnlyFromAdminEntry` + 위반 fixture `AdminPaymentQueryCaller`
  (fixture가 자기 규칙만 위반하는 행렬 시험에 포함). 시험 수 불변(기존 `test` 안).
- 기대값 변경(V31 migration 추가 — 정책 변경 아님): `PaymentApiContextSmokeSpec`·`DatabaseAccountSeparationSpec`·`OldVolumeStartSpec`·`CheckoutWindowCheckMigrationStrategySpec`의 적용 migration
  버전 목록에 `31`을 더했다.

internal-admin(`com.paycore.admin.paymentops`):
- `PaymentReadGatewaySpec`(7 — 리뷰 반영 +2: 요청-응답 대응 fail-closed(MINOR-1), 응답 중복 key 거부(NIT); 실제 HTTP 대역 `StubPaymentOpsServer`, 대역이 golden ② 시험 키로 서명 독립 검증) — 검색 본문 키·순서·query 없음, 허용 필드 모양(fail-closed: 모르는 필드·
  `institutionAccount` 필드·다른 paymentId·자료형 위반·과대 → Unavailable, 10자 넘는 미리보기 거부·10 code point 이모지는 통과), TX 안 호출 거부, lookup permit busy·timeout·연결 실패, 로그 비노출.
- `TransactionLookupServiceSpec`(6) — 역할 행렬(I-1), 형식·모드 규칙(PAYMENT 미호출), 검색 미감사, 상세 감사 순서(PAYMENT → 감사 → 결과)·감사 실패·TX 경계, 속도 제한.
- `TransactionLookupHttpSpec`(9 — 실제 로그인 세션 + MySQL 감사 행, `MerchantLookupHttpSpec`과 같은 Spring 컨텍스트) — 401, VIEWER 검색·상세·404·400, PAYMENT 불가 503(감사 없음),
  OPERATOR·APPROVER·SECURITY_ADMIN 허용·역할 없음 403, 엄격 본문 400(PAYMENT 미호출·감사 없음), 기간 검색·cursor 본문 그대로 전달(서명 유효), 413·415(지표), CSRF 없는 POST 403, 로그 비노출.
- `TransactionSearchBodyLimitSpec`(3 — 리뷰 MINOR-3, 컨트롤러 직접): chunked·작게 선언된 과대 본문은 상한 + 1바이트까지만 읽고 413, 선언 길이 초과는 한 바이트도 읽지 않고 413, Use Case 미호출, 지표.
- `TransactionAuditFailureHttpSpec`(1 — 리뷰 MINOR-5, `LogoutAuditFailureSpec`과 같은 컨텍스트): 감사 저장 실패 → 503 `AUDIT_UNAVAILABLE`, 본문에 PAYMENT 값 없음·감사 행 없음, 저장 정상화 뒤 200·감사 1행.
- 변경: `StubPaymentOpsServer`에 거래 검색·상세 응답 추가(기존 경로 불변). `TransactionLookupHttpSpec` 기간 검색 대역 항목의 createdAt을 요청 기간·cursor 안으로 고쳤다(MINOR-1 검사 도입으로 드러난 대역 값 오류 — 기대 응답 불변).

frontend(`apps/internal-admin/frontend`): `TransactionsScreen.test.tsx`(6), `navigation.test.ts`(+1, 기대값 변경 — 결제 관리 탭에 거래 조회), `App.test.tsx`(+1), `noRuntimeStyles.test.tsx`(+1),
`labels.test.ts`·`HomeScreen.test.tsx` 기대값 갱신(PAYMENT_VIEWED 이름, 바로가기에 거래 조회).

**미포함(시험 없음)**: Micrometer adapter 지표 이름 `paycore.admin.ops.transaction_rate_limited` 단언, 다중 인스턴스 속도 제한 합산·mTLS
(LIVE Gate), 실제 PAYMENT(Demo 앱) ↔ ADMIN 종단 간 호출(시험은 각 쪽 실제 HTTP + 공유 응답 모양 — 두 쪽 모양 일치는 같은 필드 목록을 손으로 맞췄다, §9.1 L-7).

## 9. 위험·미결

- 사용자 결정이 아닌 Implementer 해석(I-1 조회 역할, I-3 정확 조회의 기간 면제)은 Reviewer 확인 대상이다(I-2는 rev 0.2에 D-TXOPS-005로 확정). 노출 범위를 넓히는 쪽의 결정은
  하지 않았다. 금융 상태·금액 의미는 바꾸지 않는다(I-6 — 저장 값만 표시).
- 상세 응답 64 KiB 상한(ADMIN 기존 상수): 취소가 매우 많은 Payment(약 90건 이상)는 해석 불가 503 — Demo 규모 문제 없음, 규모가 커지면 취소 이력 페이지 분리 검토.

### 9.1 알려진 한계·LIVE Gate 항목

| ID | 내용 | 처리 |
|---|---|---|
| L-1 | MERCHANT-OPS-001 L-1과 같다: PAYMENT 수신 측은 LIVE에서 Demo·golden 공개 시험 키를 거부하지 않는다(ADMIN 호출 측만 거부) | LIVE Gate(기존 항목 — 이번 작업이 새 경로를 같은 채널에 얹었다) |
| L-2 | 속도 제한은 actor별 고정 1분 창(인스턴스 메모리), 대상 조회와 같은 값(`lookup-per-minute`)을 별도 창으로 쓴다 | 기록만(다중 인스턴스 합산과 함께 LIVE Gate) |
| L-3 | 기간 31일·limit 50·질의 시간 상한 2초·permit은 Demo 값 | LIVE Gate(운영 부하 측정 후) |
| L-4 | 상태 필터는 색인이 없다(기간·MID 범위 안의 잔여 조건). 31일 상한이 스캔 범위를 묶는다 | 기록만 |
| L-5 | 목록 색인 2개가 Payment INSERT 비용을 늘린다 | LIVE Gate 부하 측정 |
| L-6 | (rev 0.2 닫힘 — 리뷰 MINOR-5) HTTP 수준 감사 저장 실패 503: `TransactionAuditFailureHttpSpec`이 기존 `LogoutAuditFailureSpec`과 같은 컨텍스트 구성(`SwitchableAuditStoreConfig`)으로 확인한다 | **닫힘** |
| L-7 | PAYMENT 응답 모양(`AdminPaymentController`)과 ADMIN 허용 모양(`PaymentResponseShape`)은 golden 파일 없이 두 쪽 시험이 같은 필드 목록을 각각 단언한다(MERCHANT-OPS-001과 같은 방식). 한쪽만 바뀌면 ADMIN이 fail-closed로 503을 낸다(정보 노출 방향 아님) | 기록만(필요하면 계약 golden 추가 — 후속) |
| L-8 | 목록 ID 형식: ADMIN 모양 표는 Payment ID `pay_`+ULID, 승인·거래·취소 ID `<접두사>_`+ULID, 승인번호·기관 거래번호·MID·주문번호는 `[A-Za-z0-9._:-]{1,64}`만 받는다. 실제 기관이 다른 문자를 쓰면 상세가 해석 불가 503이 된다(Simulator 값은 통과) | 실제 기관 연동 시 형식 재확인(LIVE Gate) |
| L-9 | 화면 기본 기간은 KST 오늘 포함 7일(표시 관례), 상태 필터는 기간 검색에만 | 기록만 |
| L-10 | V31 운영 적용(리뷰 MINOR-2): 온라인 DDL(`ALGORITHM=INPLACE, LOCK=NONE`)이라 색인 생성 중 DML은 막지 않지만 시작·끝에 **배타 metadata lock(MDL)** 이 필요하다 — 그 앞에 열린 긴 TX(관리 조회·배치·replica 지연 포함)가 있으면 ALTER가 MDL을 기다리고, 기다리는 동안 뒤 DML이 모두 줄을 선다. Demo DB(실행 중 PAYMENT 앱)에는 아직 적용되지 않았다(재기동 때 Flyway가 적용) | **LIVE Gate**: 적용 전 `SET SESSION lock_wait_timeout`을 짧게(예: 수 초) 두고 실패 시 재시도하는 절차, 긴 TX(`information_schema.innodb_trx`)·MDL 대기(`performance_schema.metadata_locks`) 확인, 저부하 시간 적용, 표 크기에 따른 소요 시간·임시 공간(`innodb_online_alter_log_max_size`) 측정. 운영값은 운영 결정 |

## 10. 검증 기록(Implementer 실행 — Verifier 재검증 대상, 2026-11-04 KST)

환경: WSL Ubuntu, 기준 HEAD `2148eca` + 미커밋 변경, Testcontainers MySQL 8.4.11, 호스트 메모리 7.8 GB(Demo 앱 실행 중 — 전체 `check`·모듈 전체 시험은 Coordinator 지시로 실행하지 않음).
시험 순서: payment-api·internal-admin 모두 **운영 코드는 `TODO()` stub(타입·서명만)으로 두고 시험을 먼저 써서 RED를 확인한 뒤** 구현했다. 관리자 프론트 화면은 시험보다 먼저 작성했다(RED 증거 없음).

| 명령 | 결과 |
|---|---|
| **RED-1** `./gradlew :apps:payment-api:test --tests '…adapter.web.admin.AdminPayment*' --tests '…adapter.query.AdminPaymentReaderSpec' --tests '…architecture.ArchitectureSpec'` (stub) | exit 1 — 22건 중 21건 실패: Reader 10·Parser 3·Controller 3 `NotImplementedError`, API 5건 500(stub 컨트롤러). API "unsigned … 401; query 400" 1건은 기존 서명 필터라 통과. ArchitectureSpec 49 통과 |
| GREEN-1 같은 명령(구현 + V31) | exit 1 — 22건 중 2건 실패, **시험 결함**: Jackson 3 `JsonNode`에 Kotlin `map` 사용(NPE), 비노출 단언 `shouldNotContain "institution"`이 명세 필드 `institutionTransactionDate`에 걸림 → `iterator().asSequence()`·키 이름 `institutionAccount`/`institution_account`/`operatorReason` 단언으로 고침(기준 완화 아님 — 값 대조군 단언은 그대로). Reader 10건 첫 실행 통과 |
| GREEN-2 `… AdminPayment* … AdminPaymentReaderSpec … ArchitectureSpec … PaymentApiContextSmokeSpec … DatabaseAccountSeparationSpec … PaymentSchemaSpec` | exit 1 — `AdminPaymentApiSpec` 1건: 두 취소가 같은 시험 시각이라 순서가 cancellationId 순(명세 §3.4 동률 규칙 그대로) — 시험이 시계를 1초씩 밀도록 고침. 나머지 통과(Reader 10, Parser 3, Controller 3, API 5, Architecture 49, ContextSmoke 5, DatabaseAccountSeparation 17, PaymentSchema 16) |
| GREEN-3 `… AdminPaymentApiSpec … AdminChannelSecurityChainSpec … AdminCallSignatureGoldenSpec … AdminChannelMetricsContractSpec … OldVolumeStartSpec … CheckoutWindowCheckMigrationStrategySpec` | exit 1 — `CheckoutWindowCheckMigrationStrategySpec` 1건 `history().size` 31 기대(migration 수 기대값 — V31 추가로 32). 나머지 통과(API 6, SecurityChain 9 — 등록 handler 전수 무서명 401에 새 경로 2개 포함, SignatureGolden 36, MetricsContract 2, OldVolume 4) |
| GREEN-4 `… CheckoutWindowCheckMigrationStrategySpec`(기대값 31 → 32) | exit 0 — 6건 통과 |
| **RED-2** `./gradlew :apps:internal-admin:test --tests 'com.paycore.admin.paymentops.Transaction*' --tests '…PaymentReadGatewaySpec' --tests '*ArchitectureSpec*'` (stub) | exit 1 — 20건 중 18건 실패(`NotImplementedError`·stub 500). 통과 2건은 기존 보안(미인증 401·CSRF 없는 POST 403). ArchitectureSpec 33 통과 |
| GREEN-5 같은 명령(구현) | exit 0 — 20건(Gateway 5·Service 6·HTTP 9) + ArchitectureSpec 33, 실패·skip 0 |
| **변이 M-1**(admin, 감사 전 반환 가드): `TransactionLookupService.audited`의 catch가 `AuditUnavailable` 대신 `outcome()` 반환 → `--tests '…TransactionLookupServiceSpec'` | exit 1(RED) — 6건 중 1건 실패: "PAYMENT is called outside a transaction and the audit inside one; an audit failure withholds the detail (AUDIT_UNAVAILABLE)" (`expected AuditUnavailable but was Found`). 원본 복원 — sha256 `1ce81ad0c032…` 변이 전과 동일, `MUTATION` 문자열 0건 |
| GREEN-6 `./gradlew :apps:internal-admin:test --tests 'com.paycore.admin.paymentops.*' --tests '…merchantops.MasterOpsGatewaySpec' --tests '*ArchitectureSpec*'` | exit 0 — paymentops 53(기존 PaymentOps* golden·gateway·비노출·permit 격리 33 + 새 20), MasterOpsGateway 12, ArchitectureSpec 33, 실패·skip 0 |
| `./gradlew :apps:internal-admin:frontendTypecheck :apps:internal-admin:frontendTest` | exit 0 — tsc 통과, Vitest 22 파일 141건(이전 132 + 9) |
| `python3 -B harness/tests/test_admin_call_signature_golden.py -v` | exit 0 — 6건(golden 파일 불변) |
| `python3 -B -m unittest discover -s harness/tests` (minTests 변경 후) | exit 0 — 163건 OK |

harness minTests(하한 — 낮추지 않음): GRADLE-CHECK·TEST-SUMMARY 3445 → **3487**(이전 하한 + 새 Kotlin 시험 42 — 전체 측정 합계가 아니다, 전체 gate는 Coordinator 실행 예정), TEST-SUMMARY-FRONTEND 157 → **166**
(관리자 측정 141 + 결제창 25 — 결제창 수는 이전 기록 값).
`.env.example` 변경 없음(새 설정 없음 — 기존 `paycore.admin.payment-ops.*`·`paycore.payment.admin-ops.*` 그대로).

미실행(Verifier 대상): 전체 `check`(두 모듈 전체 시험·master-api·frontend 결제창), CVE 스캔, Demo 앱(8085/8081/8090)에서의 화면 수동 확인. Demo 실행 중인 PAYMENT 앱에는 V31이 아직 적용되지 않았다
(재기동 시 Flyway가 적용 — 색인 추가만).

파일 지문(sha256 앞 12자, 검증 시점): `JooqAdminPaymentReader.kt` 7ca7c260b13a, `AdminPaymentController.kt` e133c32ddfdf, `V31__payment_admin_list_indexes.sql` f7b14ddc0060,
`TransactionLookupService.kt` 1ce81ad0c032, `HttpPaymentOpsGateway.kt` a760a0853bd7.

#### 10.1 리뷰 반영(APPROVED + MINOR/NIT, Implementer 실행 2026-11-04 KST — Verifier 재검증 대상)

| 명령 | 결과 |
|---|---|
| 스펙 | D-TXOPS-005(Q-TXOPS-05 사용자 답변 "표시만 유지")를 §2 사용자 결정으로 옮기고 I-2를 해석 목록에서 뺐다. I-3은 해석으로 유지 |
| **RED** `./gradlew :apps:internal-admin:test --tests '…PaymentReadGatewaySpec' --tests '…TransactionSearchBodyLimitSpec' --tests '…TransactionAuditFailureHttpSpec'` | exit 1 — 11건 중 2건 실패: MINOR-1 "a search response that does not answer the request is unreadable…"(`other payment` → Found), NIT "a PAYMENT response with a duplicated key is unreadable"(Found). MINOR-3(3)·MINOR-5(1)는 **특성화 시험** — 이미 구현된 동작이라 첫 실행 통과(RED 없음) |
| 구현 | `HttpPaymentOpsGateway.answers`(요청-응답 대응 검사 — 실패는 shape 위반과 같은 `RESPONSE_UNREADABLE`), 응답 mapper `STRICT_DUPLICATE_DETECTION`, V31을 한 `ALTER … ALGORITHM=INPLACE, LOCK=NONE`로(미커밋·Demo DB 미적용 migration이라 수정 가능) |
| GREEN 1차 `--tests 'com.paycore.admin.paymentops.*' --tests '*ArchitectureSpec*'` | exit 1 — `TransactionLookupHttpSpec` 기간 검색 1건 503: 대역 항목 createdAt이 요청 기간 밖(새 검사가 맞게 거절) → 대역 값을 기간 안으로 고침 |
| payment-api `--tests '…web.admin.AdminPayment*' '…query.AdminPaymentReaderSpec' '…ArchitectureSpec' '…PaymentApiContextSmokeSpec' '…DatabaseAccountSeparationSpec' '…PaymentSchemaSpec' '…OldVolumeStartSpec' '…CheckoutWindowCheckMigrationStrategySpec'` | exit 0 — 새 V31(온라인 DDL)이 MySQL 8.4.11 Testcontainers에서 적용됨(EXPLAIN 시험이 두 색인 사용 확인). Reader 10·API 6·Controller 3·Parser 3·Architecture 49·ContextSmoke 5·DatabaseAccountSeparation 17·PaymentSchema 16·OldVolume 4·CheckoutWindowCheck 6, 실패·skip 0 |
| **변이 (a)** payment Reader 목록 미리보기 `LEFT(order_name,10)` → `order_name` 전체 → `--tests '…AdminPaymentReaderSpec'` | exit 1(RED) — 10건 중 1건: "the list shows only the first 10 characters of the order name and whether it was cut". 복원 sha `7ca7c260b13a…` 동일 |
| **변이 (b)** keyset `payment_id <` → `<=` | exit 1(RED) — 10건 중 1건: "ties on created_at are broken by paymentId: pages never skip or repeat a row"(행 반복). 복원 sha 동일 |
| **변이 (c)** admin `VIEW_ROLES` + `UNMASK` → `--tests '…TransactionLookupServiceSpec' '…TransactionLookupHttpSpec'` | exit 1(RED) — 15건 중 1건: Service "…UNMASK-only, no role and inactive accounts are forbidden…"(Forbidden 기대에 Found). HTTP spec은 UNMASK 단독 계정을 시험하지 않아 그쪽은 통과. 복원 sha `1ce81ad0c032…` 동일 |
| **변이 (d)** admin `ORDER_NAME_PREVIEW` 최대 10 → 100 code point → `--tests '…PaymentReadGatewaySpec'` | 1차 exit 0 — **변이 생존**(7건 통과: 10자 초과 미리보기 시험이 `truncated=true`라 MINOR-1 일관성 검사가 대신 잡았다). 시험 추가(잘림 표시 없는 11 code point 미리보기 → 해석 불가) 후 재실행 exit 1(RED) — 7건 중 1건 실패. 복원 sha `6886bf3d4b7a…` 동일 |
| GREEN 최종 `./gradlew :apps:internal-admin:test --tests 'com.paycore.admin.paymentops.*' --tests '*ArchitectureSpec*'` | exit 0 — paymentops 59(기존 33 + TXN 26), ArchitectureSpec 33, 실패·skip 0. 기존 PaymentOps golden·gateway spec은 strict 응답 mapper에서도 통과 |
| `python3 -B -m unittest discover -s harness/tests` / `test_admin_call_signature_golden.py` | exit 0 — 163 OK / 6 OK |

minTests: GRADLE-CHECK·TEST-SUMMARY 3487 → **3493**(+6 Kotlin: Gateway +2, BodyLimit 3, AuditFailureHttp 1 — 하한 + 추가 수), 프론트 166 유지(프론트 변경 없음).
파일 지문(sha256 앞 12자): `HttpPaymentOpsGateway.kt` 23c04b2445bf, `PaymentResponseShape.kt` 6886bf3d4b7a, `V31__payment_admin_list_indexes.sql` 8d569fda2b34, `JooqAdminPaymentReader.kt` 7ca7c260b13a,
`TransactionLookupService.kt` 1ce81ad0c032. 미실행: 전체 gate(Coordinator 예정). L-3·L-4(희귀 상태 필터 스캔)는 기록 유지.
