# CP-CONTRACT-001 CreatePayment 확정 계약 v1.0

작성 2026-10-06 KST. 기술 초안 `docs/baseline/PAYCORE-CREATE-PAYMENT-IMPLEMENTATION-CONTRACT-v0.1.md`(이하 "초안")를 사용자 결정으로 확정한 판이다. 초안의 업무 규칙은 바꾸지 않았다. 변경점은 §15.

표시: **확정(D-…)** = 사용자 결정(`docs/DECISIONS.md`), **관례** = 금융 의미가 없는 가역적 구현 선택(이 문서에서 결정·기록), **미결** = §16 질문 후보 또는 후속 작업.

이 문서는 계약이다. 코드로 구현된 범위는 §14에 따로 적는다(domain 값 객체·fingerprint 계산만). API·DB·Outbox·Port 구현은 CP-DB-001 / MASTER-SNAPSHOT-001 / CP-API-001이며 아직 실행 검증되지 않았다.

## 1. 범위

- PAYMENT가 결제 의도(Payment REQUESTED)와 주문 활성 슬롯 점유(OrderPaymentControl)를 만든다. 첫 결제수단은 국내 신용카드(CARD).
- 기관 인증·승인·매입·정산·송금은 하지 않는다. PaymentTransaction·AuthorizationAttempt를 만들지 않는다. 외부 호출 없음.
- MASTER를 같은 DB TX로 읽거나 쓰지 않는다. MID 소유·Site 관계·계약/개통 준비·허용 통화·Snapshot freshness는 PAYMENT의 required Port(§13)로만 검사한다(D-CPC-006: Port 계약만 정의, 구현은 MASTER-SNAPSHOT-001/CP-API-001).
- Simulator/REAL은 이 API의 축이 아니다(D-CPD-001). TEST 환경 요청은 실기관으로 전송되지 않는다(Route 규칙은 AUTH).

## 2. Command와 신뢰 컨텍스트

| 구분 | 필드 | 출처 | 규칙 |
|---|---|---|---|
| 신뢰 컨텍스트 | authenticatedPrincipal, authenticatedMid, environment(TEST/LIVE) | 인증 Credential(API 키 등)에서 서버가 결정 | body 값으로 덮어쓰지 않는다. environment는 body에 받지 않는다(관례) |
| 신뢰 컨텍스트 | idempotencyKey | `Idempotency-Key` 헤더(관례) | D-CPC-002 형식. 없으면 400 |
| 신뢰 컨텍스트 | requestId/correlationId | 서버 생성(가맹점 `X-Request-Id`가 있으면 별도 보존, 관례) | 로그·이벤트 상관 ID. 업무 키 아님 |
| 신뢰 컨텍스트 | evaluatedAt | 서버 clock | Snapshot freshness·createdAt 기준. 가맹점 시각을 쓰지 않는다 |
| body | merchantAccountId | 가맹점 | **authenticatedMid와 같아야 한다**. 다르면 403 `MERCHANT_SCOPE_MISMATCH`(§9). 명시적으로 받아 잘못된 키 사용을 드러낸다(관례) |
| body | merchantSiteId, merchantOrderId, amount, currency, paymentMethod, orderName | 가맹점 | §3 |

OrderKey = (environment[컨텍스트], merchantAccountId[=authenticatedMid], merchantOrderId[body]) — 확정(시도 §1, D-CPD-001).

## 3. 요청 schema

`POST /v1/payments` (경로·버전 표기는 관례), `Content-Type: application/json; charset=utf-8`.

| 필드 | JSON 타입 | 필수 | 형식·규칙 | 근거 |
|---|---|---|---|---|
| merchantAccountId | string | 예 | 비어 있지 않음, 짝 없는 서로게이트 거절(§3.1), 입력 그대로 비교. 형식은 MASTER 발급 규칙(MASTER-DOMAIN-001) | 관례(fail-closed, PG 발급 값) |
| merchantSiteId | string | 예 | 비어 있지 않음, 짝 없는 서로게이트 거절(§3.1), 입력 그대로 비교. 형식은 MASTER 발급 규칙 | 관례 |
| merchantOrderId | string | 예 | 1~64자, `^[A-Za-z0-9\-_.:]{1,64}$`, 공백·비ASCII·제어문자 거절, 대소문자 구분 | **확정 D-CPC-002, D-CP-001** |
| amount | integer | 예 | 통화 minor unit 정수, 1 ≤ amount ≤ 9223372036854775807 | **확정 D-CPC-005** |
| currency | string | 예 | ISO 4217 alphabetic `^[A-Z]{3}$`, 대소문자 변환 없음. 허용 여부는 기준정보(§4) | **확정 D-CP-002**, 형식 엄격성은 관례 |
| paymentMethod | string | 예 | 현재 `CARD`만. 허용 여부는 개통 기준정보(§13) | 초기 확정(국내 신용카드) |
| orderName | string | 아니오 | 표시용. 1~100자(길이 단위 — UTF-16 code unit/code point/UTF-8 byte — 는 CP-API-001에서 확정, 이월 L-8), 제어문자·짝 없는 서로게이트 거절(관례, §3.1). 고정 입력 아님 | 관례, D-CPC-001 |

헤더 `Idempotency-Key`: 1~64자, `^[A-Za-z0-9\-_.:]{1,64}$`, 대소문자 구분 — **확정 D-CPC-002, D-CP-001**.

### 3.1 파싱 규칙(관례, fail-closed)

- 알 수 없는 필드는 400 `INVALID_REQUEST`(무시하지 않는다 — 멱등 fingerprint의 닫힌 필드 집합 보장).
- 같은 이름 필드 중복은 400. `null`은 필드 없음과 같지 않다: 필수 필드 `null`은 400, 선택 필드 `null`은 400(없으면 생략).
- amount는 JSON integer만 허용: 소수점·지수(`1e3`)·문자열·부호 `+`·선행 0·Long 초과는 400 `INVALID_AMOUNT`. 서버는 부동소수점으로 파싱하지 않는다(정확한 정수 파서). 주의: JavaScript Number는 2^53-1 초과 정수를 정확히 표현하지 못하므로 그 범위 금액은 가맹점이 정확한 정수 직렬화를 써야 한다(D-CPC-009 확정).
- 문자열 정규화(trim, Unicode NFC, 대소문자 변환) 없음. 형식 밖이면 거절(D-CP-001).
- **짝 없는 UTF-16 서로게이트는 400 `INVALID_REQUEST`**(관례, Reviewer L-3): JSON `\uD800` 같은 escape로 들어온 lone high/low surrogate, 순서가 뒤바뀐 쌍, 잘못된 UTF-8 bytes를 담은 모든 문자열 필드(merchantAccountId, merchantSiteId, orderName 포함)를 거절한다. 치환(`?`·U+FFFD)해서 받지 않는다. 이유: UTF-8 인코딩·JDBC 저장에서 `?`로 바뀌면 `"a\uD800"`과 `"a?"`가 같은 fingerprint·같은 DB 키가 된다. 올바른 서로게이트 쌍(보충 문자, 예: 이모지)은 정상 문자다. domain `MerchantAccountId`·`MerchantSiteId` 생성자도 같은 이유로 거부한다(최후 방어선, §3.3과 같은 관례). merchantOrderId·멱등키는 D-CPC-002 ASCII 제한으로 이미 거절된다(시험으로 확인). orderName 검사 구현은 CP-API-001.

### 3.2 검증 순서(관례)

1) 인증(401) → 2) 헤더·body shape·형식(400) → 3) body MID = 인증 MID(403) → 4) 멱등 기록 예약/조회(§8) → 5) eligibility Port(§13: 404/422/503) → 6) 주문 판정(§7, §9) → 7) commit → 응답.
같은 키의 저장된 결과가 있으면 5)·6)을 다시 하지 않고 저장 결과를 반환하되 **현재 조회 권한(인증 MID·environment)은 매번 다시 확인**한다(D-CP-003, CP-API-001).

### 3.3 형식 검사 위치(관례)

merchantOrderId·멱등키 형식(D-CPC-002)은 domain 값 객체(`MerchantOrderId`, `IdempotencyKey`) 생성자에서 강제한다. 이유: OrderKey·멱등 범위 키는 DB의 영구 식별자(VARCHAR(64) binary)이므로 web 외 경로(Batch·재처리·관리자)로도 형식 밖 키가 생기지 않아야 한다. web adapter는 같은 규칙으로 먼저 400을 만들고, 생성자 예외는 최후 방어선이다(ARCHITECTURE-CONVENTIONS: application.provided DTO의 jakarta.validation 금지 → web DTO·domain 생성자 검증). 예외 메시지에 입력값을 넣지 않는다(로그 주입·민감 입력 보호). 주의: 형식을 나중에 좁히면 기존 저장 키를 복원할 수 없으므로 형식 변경은 확장만 허용하고 축소는 migration 계획과 함께 사용자 결정을 받는다.

## 4. Money 계약 — 확정 D-CP-002, D-CPC-005

- Money = (amountMinor: Long, currency: CurrencyCode). 금액은 **항상 통화 minor unit 정수**(KRW 1원 = 1, USD 1센트 = 1). Float/Double 금지, 계산·비교에 소수 표현을 쓰지 않는다.
- 1 이상, BIGINT(=Long.MAX_VALUE) 이하만 계약에서 강제한다. 건당·일 한도 등 업무 상한은 MID·계약 기준정보 한도 정책이며 코드에 운영 상한을 두지 않는다(D-CPC-005). 한도의 권위 판정은 승인 시작(AUTH, AUTH-CONTRACT-001)에서 한다(D-CPC-008). D-CPC-008이 허용한 생성 시 건당 한도 사전 거절은 **선택**이며 **v1 CreatePayment는 한도 사전 검사를 하지 않는다**(관례 — Coordinator 결정, 가역적: 나중에 넣으면 §13 NotEligible reason과 §9 세부 사유를 확장하는 계약 개정으로 추가). 일 한도는 점유하지 않는다. 따라서 한도 초과 금액도 생성 단계에서는 REQUESTED Payment가 될 수 있고 승인 시작에서 거절된다.
- 통화 코드는 ISO 4217 alphabetic 3자. **허용 통화 목록을 코드에 두지 않는다**: MID·기관 계약 기준정보(MASTER → Snapshot)로 활성화한다. 통화별 minor unit(0~4)도 기준정보 값(`CurrencyUnit(code, minorUnit)`)으로 받는다. 초기 구현·검증 fixture는 KRW(0)·USD(2)뿐이다.
- 직렬화: JSON `"amount": 10000, "currency": "KRW"`, `"amount": 1050, "currency": "USD"`(=10.50 USD). 주(major) 단위 소수 문자열은 표시용 변환(`Money.toMajorUnitString(unit)`)으로만 만든다.
- 외화 거래의 정산 통화·환율은 미결(Q-SETTLE-CURRENCY, SETTLEMENT-CONTRACT-001). CreatePayment는 거래통화를 그대로 보존만 한다.

## 5. 응답 schema(생성 결과) — 확정 D-CP-003

| 필드 | 타입 | 뜻 |
|---|---|---|
| paymentId | string | 서버 생성 Payment 식별자(내부·외부 공통 참조, 추측 불가 형식은 관례: `pay_` + 26자 ULID 계열) |
| paymentKey | string | 결제창·후속 고객 단계에서 Payment를 지목하는 외부 키. 128bit 이상 난수(관례). **자격 증명이 아니다**: 후속 API도 MID 인증을 요구한다 |
| outcome | string | `CREATED`(이 요청이 새 Payment 생성) / `EXISTING_ACTIVE_PAYMENT`(다른 키·같은 주문·같은 고정 입력의 활성 시도 재사용) |
| lifecycleAtCreation | string | Payment 생성 당시 상태. 항상 `REQUESTED` |
| paymentCreatedAt | string(ISO-8601 UTC, ms) | Payment 생성 시각(서버 clock) |
| merchantOrderId, amount, currency | | 요청 확인용 echo(가맹점 자신의 값, 민감정보 아님) |

- 같은 멱등키·같은 내용 재요청은 **최초 결과를 그대로**(같은 paymentId·outcome·시각) 반환하고 HTTP 200 + 헤더 `Idempotent-Replayed: true`(관례).
- 응답에 **현재 상태를 넣지 않는다**. 현재 상태(AUTHORIZING/UNKNOWN/AUTHORIZED 등)는 GetPayment 조회 API로만 제공한다(D-CP-003). 생성 결과는 기관 성공을 뜻하지 않는다.
- 카드정보·Secret·기관 응답 원문 없음(생성 단계에는 카드정보가 없다).

## 6. OrderIntentFingerprint — 확정 D-CPC-001, 정의는 이 문서(코드 구현됨)

### 6.1 구성

고정 주문 입력 = **merchantSiteId + amount + currency**. MID·environment는 OrderKey 자체라 별도로 넣지 않는다. 결제수단·주문명 등 표시 정보는 비교하지 않으며 최초 Payment 값을 저장한다(D-CPC-001).

### 6.2 v1 정규 표현과 hash

```
token(s)   = <UTF-8 byte length of s, 10진수> ":" s
canonicalV1 = token("paycore.order-intent") + token("1") + token(merchantSiteId) + token(amountMinor 10진수) + token(currency)
fingerprint = "v1:" + lowercase hex( SHA-256( UTF-8(canonicalV1) ) )        // 67자
```

- 값은 입력 그대로(정규화 없음). amountMinor는 부호·선행 0 없는 10진수(Long.toString), currency는 검증된 3자 대문자.
- **UTF-8 인코딩은 엄격하다**(token 길이 계산과 hash 입력 모두): malformed 입력(짝 없는 서로게이트)은 `?`·U+FFFD로 치환하지 않고 오류로 보고한다(REPORT, 관례 — Reviewer L-3). 입력 단계에서 이미 400·생성자 거부(§3.1)이므로 이 오류는 최후 방어선이다. well-formed 문자열의 bytes는 표준 UTF-8과 같아 §6.3 golden vector는 바뀌지 않는다(v1 동결 전 정정이며 v1 결과값 변경 없음). 구현 `Utf8Text.encode`.
- 길이 접두로 구분자 없이도 경계가 모호하지 않다(예: site `x`+12와 site `x1`+2가 다르다). merchantSiteId 형식이 MASTER에서 어떻게 정해져도 안전하다.
- 계산은 domain `OrderIntent(merchantSiteId, money).fingerprint()`(순수 Kotlin + JDK MessageDigest). API·DB·domain이 같은 함수를 쓴다(초안 CP-12).

### 6.3 golden vector(계약 시험 고정값, Python hashlib으로 독립 계산)

| site | amount | currency | canonicalV1 | fingerprint |
|---|---|---|---|---|
| demo-site-001 | 10000 | KRW | `20:paycore.order-intent1:113:demo-site-0015:100003:KRW` | `v1:2d6f6ae82692623252de0081f409974629600801d4b17bd5901fc16c22d2d3f3` |
| demo-site-001 | 1050 | USD | `20:paycore.order-intent1:113:demo-site-0014:10503:USD` | `v1:8704c2d2eac85ec8cdfc55b8278996183f0529622a64d590d1854799f2770e14` |
| x | 12 | KRW | `20:paycore.order-intent1:11:x2:123:KRW` | `v1:87e4a95435874434a2820c58536799b64dc0800a59fdd6417b9e4a713fa9d85d` |
| x1 | 2 | KRW | `20:paycore.order-intent1:12:x11:23:KRW` | `v1:8fd0ccda2b6bd3722b67004a09b1e11813a682c260bb930f0e58e8938394a6da` |

Demo 값이며 운영 Site·금액이 아니다.

### 6.4 버전 필드와 호환 규칙(관례 — 업무 의미 변경 시 사용자 결정)

- 저장값에 버전 접두(`v1:`)를 포함한다. control은 최초 Payment의 fingerprint(버전 포함)를 영구 보존한다.
- **v1 알고리즘은 동결**한다. 계산 방식을 바꾸면 새 버전(v2)을 추가하고 v1 계산을 제거하지 않는다(OrderPaymentControl은 영구 보존이므로 v1도 영구 지원).
- 비교 규칙: application은 요청의 fingerprint를 **저장된 control의 버전으로** 계산해 비교한다. 서로 다른 버전 값을 직접 비교하지 않는다. 직접 비교되면 불일치 → FixedIntentConflict(새 Payment 없음, fail-closed)이므로 금융 위험은 없지만 오분류이므로 CP-API-001 시험으로 막는다.
- 구성 필드 자체를 바꾸는 변경(예: 결제수단 포함)은 업무 의미 변경이라 사용자 결정 대상이다.

## 7. 멱등 요청 fingerprint(요청 전체) — intent fingerprint와 구분

| 구분 | 멱등 요청 fingerprint | OrderIntentFingerprint |
|---|---|---|
| 목적 | 같은 키 재요청이 같은 내용인지(같으면 저장 결과, 다르면 충돌) | 다른 키·같은 주문이 같은 고정 입력인지 |
| 범위 | (environment, authenticatedMid, operation=`CreatePayment`, idempotencyKey) | OrderKey |
| 입력 | 요청 body 전체(닫힌 필드 집합: merchantAccountId, merchantSiteId, merchantOrderId, amount, currency, paymentMethod, orderName) | merchantSiteId, amount, currency |
| 예 | 같은 키로 orderName만 바뀜 → **충돌** | 다른 키로 orderName만 바뀜 → 같은 고정 입력(재사용) |

정의(관례, CP-API-001 구현):

```
canonicalRequestV1 = token("paycore.create-payment-request") + token("1")
                   + 각 필드(위 순서) token(name) + (값이 있으면 "1" + token(value) | 없으면 "0")
requestFingerprint = "r1:" + lowercase hex(SHA-256(UTF-8(canonicalRequestV1)))
```

amount는 10진 정수 문자열로 넣는다. JSON 원문 bytes·공백·필드 순서는 fingerprint에 영향을 주지 않는다(파싱 후 정규 표현). 헤더(Idempotency-Key 외)는 넣지 않는다. 버전 규칙은 §6.4와 같고 보존 기간(30일) 안에서만 의미가 있다. UTF-8 인코딩은 §6.2와 같은 엄격 규칙(malformed 거부)이며 orderName도 §3.1로 짝 없는 서로게이트를 400 거절한다(구현 CP-API-001). r1 golden vector는 CP-API-001에서 독립 계산으로 고정한다(이월 L-8).

## 8. 멱등 기록 보존 — 확정 D-CPC-004

- 보존 30일(운영값 확정). 기준 시각은 멱등 기록 생성 시각(관례).
- 30일 경과 후 같은 키는 **새 요청**으로 처리한다. 이때도 주문 통제(OrderPaymentControl)는 영구라 같은 주문은 §9 판정을 받는다: 활성 시도가 있으면 재사용(200 `EXISTING_ACTIVE_PAYMENT`), 과거 승인이면 409 등. 즉 만료가 **동시 활성 Payment·이중 승인을 만들지 않는다**. 단, 30일 사이 원 시도가 FAILED/EXPIRED로 안전 종료(근거 충분)됐다면 같은 키의 새 요청은 정당하게 두 번째 Payment(새 generation)를 만들 수 있다 — 주문 1:N Payment 이력 규칙과 같다.
- 보존 기간 안의 삭제·캐시 eviction으로 기록을 잃지 않는다(권위 저장은 PAYMENT DB). 삭제 Job은 30일 경과 행만 지운다(CP-DB-001 이후).
- 같은 키 동시 요청은 UNIQUE(environment, mid, operation, key) 예약으로 직렬화한다. 먼저 커밋된 결과가 기준이다.

## 9. 판정 → 응답 매핑 — HTTP 코드 선택은 관례

오류 응답 형식(관례): `{"error": {"code": "...", "message": "...(고정 문구)", "retryable": bool, "referencePaymentId": "...?", "requestId": "..."}}`.

| 경우 | 판정/원인 | HTTP | code | 새 Payment | 멱등 결과 저장 | 비고 |
|---|---|---|---|---|---|---|
| 새 주문 / 안전 종료 뒤 새 키 | `CreateNewPayment` | 201 | — (생성 결과, outcome `CREATED`) | 1 | 저장 | Outbox PaymentRequested 1(§10) |
| 같은 키·같은 내용 재요청 | 저장된 결과 | 200 | — (최초 결과 그대로, `Idempotent-Replayed: true`) | 0 | 기존 | 현재 권한 재확인. 현재 상태 미포함 |
| 다른 키·같은 주문·같은 고정 입력, 활성(REQUESTED/AUTHORIZING/UNKNOWN) | `UseExistingPayment` | 200 | — (outcome `EXISTING_ACTIVE_PAYMENT`, 기존 paymentId) | 0 | 저장 | Outbox 없음(이미 생성 이벤트 있음) |
| 같은 키·다른 내용 | 멱등 충돌 | 409 | `IDEMPOTENCY_KEY_REUSED` | 0 | 기존 유지 | 기존 결과·내용 미노출 |
| 같은 주문·다른 고정 입력(Site/금액/통화) | `FixedIntentConflict` | 409 | `ORDER_INTENT_CONFLICT` | 0 | D-CPC-007 저장·30일 재생 | referencePaymentId = 현재 Payment. 기존 금액·Site 미노출. 변경 주문은 새 merchantOrderId |
| incident hold | `Blocked(INCIDENT_HOLD)` | 409 | `ORDER_ON_HOLD` | 0 | D-CPC-007 저장·30일 재생 | **referencePaymentId 포함(D-CPD-004)**. 활성 시도가 있어도 재사용 아님 |
| 과거 승인 성공(전액 반환 후 포함) | `Blocked(AUTHORIZED_NEW_ORDER_REQUIRED)` | 409 | `ORDER_ALREADY_AUTHORIZED` | 0 | D-CPC-007 저장·30일 재생 | referencePaymentId = 승인 Payment. 재구매는 새 주문 ID |
| FAILED/EXPIRED 근거 불충분 | `Blocked(TERMINATION_EVIDENCE_INSUFFICIENT)` | 409 | `ORDER_RESULT_UNCONFIRMED` | 0 | D-CPC-007 저장·30일 재생 | referencePaymentId = 현재 Payment. 원 시도 확인·복구 후 새 키 |
| 형식·필드 오류 | shape | 400 | `INVALID_REQUEST` / `INVALID_AMOUNT` / `INVALID_CURRENCY` / `INVALID_IDEMPOTENCY_KEY` | 0 | 저장 안 함(멱등 예약 전) | 오류 필드 이름만, 값 echo 없음 |
| 인증 없음·Credential 폐기 | 인증 | 401 | `UNAUTHENTICATED` | 0 | 저장 안 함 | |
| body MID ≠ 인증 MID | 범위 | 403 | `MERCHANT_SCOPE_MISMATCH` | 0 | 저장 안 함 | 다른 MID 존재 여부를 말하지 않음 |
| Site 없음·다른 MID Site·Assignment 무효 | eligibility `NOT_FOUND_OR_FORBIDDEN` | 404 | `MERCHANT_SITE_NOT_FOUND` | 0 | 저장 안 함 | 없음과 권한 없음을 구분하지 않음 |
| 통화·결제수단 미활성, 계약 효력 밖, MID 신규 결제 중지·차단 | eligibility `NOT_ELIGIBLE` | 422 | `PAYMENT_NOT_ELIGIBLE` | 0 | 저장 안 함 | 세부 사유는 고정 분류(`CURRENCY_NOT_ENABLED`·`METHOD_NOT_ENABLED`·`CONTRACT_NOT_EFFECTIVE`·`MERCHANT_SUSPENDED`)만, 내부 버전·정책값 미노출. 한도 사유 없음(v1은 생성 시 한도 사전 검사 안 함, §4 관례) |
| Snapshot 누락·만료·확인 불가 버전 | eligibility `UNAVAILABLE` | 503 | `ELIGIBILITY_UNAVAILABLE` | 0 | 저장 안 함 | fail-closed. `Retry-After`. 같은 키로 재시도 |
| 잠금 timeout·deadlock 재시도 소진·DB 일시 오류 | 로컬 일시 실패(rollback 확인) | 503 | `TEMPORARILY_UNAVAILABLE` | 0(rollback) | 저장 안 함 | 같은 키로 재시도. commit 불명확은 같은 키 재요청/조회로 확인(새 키 금지). MySQL lock wait timeout(문장 rollback만 될 수 있음)의 TX rollback 보장·분류는 CP-API-001(이월 L-4) |
| 같은 키가 처리 중(앞 요청 미커밋, 대기 timeout) | 멱등 예약 경합 | 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 0 | — | retryable=true |
| StaleSlot·PaymentIdReuse 등 내부 경합 | domain 예외 | (내부 재판정 후 위 행) / 실패 시 503 | `TEMPORARILY_UNAVAILABLE` | 0 | 저장 안 함 | 사용자 payload 충돌로 오해되게 409 `*_CONFLICT`를 쓰지 않는다(초안 CP-10) |

- `retryable`: 503·`IDEMPOTENCY_REQUEST_IN_PROGRESS`만 true. 409 업무 거절은 같은 키 재시도로 결과가 바뀌지 않는다는 뜻이며 업무 거절도 그 키의 결과로 저장해 보존 기간 동안 같은 거절을 재현한다(D-CPC-007). CP-DB-001은 결과 종류 컬럼으로 성공·거절을 구분한다.
- timeout 자체로 Payment 상태를 바꾸지 않는다.

### 9.1 오류 응답 보안(확정 원칙: A05, 허용 §4)

- 요청값(merchantOrderId·금액·키)을 메시지에 echo하지 않는다. 다른 MID·다른 환경 자원의 존재 여부를 드러내지 않는다(403/404 통일 규칙).
- 409 응답은 같은 인증 MID의 자기 주문에 대해서만 referencePaymentId를 준다(같은 MID 범위 밖 주문은 OrderKey가 달라 판정 자체가 없다).
- 내부 예외 메시지·stack·SQL·Snapshot 버전·정책 값·기관 코드는 응답에 넣지 않는다. 로그는 requestId·paymentId로 상관하고 카드정보·Secret을 남기지 않는다.

## 10. PaymentRequested v1 이벤트 — 확정 D-CPC-003

결제 생성(outcome `CREATED`)과 **같은 PAYMENT 로컬 TX**로 Outbox에 기록한다. 재사용(`EXISTING_ACTIVE_PAYMENT`)·재요청·거절은 이벤트를 만들지 않는다.

| envelope | 값 |
|---|---|
| eventId | 서버 생성 UUID(재발행 시 같은 값) |
| eventType | `PaymentRequested` |
| schemaVersion | 1 |
| eventCategory | `INTEGRATION_NON_FINANCIAL` — 금융 효과 이벤트(`PaymentAuthorized` 등, AUTH-CONTRACT-001)와 이름·의미 구분 |
| producer | `payment-api/payment` |
| aggregateType / aggregateId | `Payment` / paymentId |
| aggregateVersion | Payment 생성 시 영속 version(최초값, CP-DB-001이 0 또는 1로 고정) |
| occurredAt | Payment 생성 시각(서버 clock, UTC) |
| correlationId / causationId | requestId / 멱등 기록 ID(`CreatePayment` 요청) |
| effectKey | `PaymentRequested:{paymentId}` — Payment당 하나, Outbox UNIQUE |

payload: environment, merchantAccountId, merchantSiteId, merchantOrderId, paymentId, amount(minor 정수), currency, paymentMethod, slotGeneration, orderIntentFingerprint, lifecycle(`REQUESTED`), requestedAt. 제외: paymentKey(결제창 지목 키, 소비자 불필요), orderName, 카드정보·Secret·개인정보.

- 이 이벤트는 금융 사실이 아니다. FINANCIAL은 이것으로 정산 후보·채무를 만들지 않는다(정산 근거는 승인 금융 사실).
- 전달은 at-least-once(Outbox Relay → 수신 Inbox). 현재 소비자 없음. Kafka는 도입하지 않았다(ADR-001) — 이 계약은 브로커와 무관하다.

## 11. TX·경합(초안 §5 유지)

같은 PAYMENT TX: 멱등 기록(결과 포함) + OrderPaymentControl 생성/점유(generation) + Payment(REQUESTED) + Outbox(CREATED일 때). 응답은 commit 뒤. rollback이면 아무것도 남지 않는다. control 최초 insert 경쟁은 UNIQUE(OrderKey) + 재판정, 목록 SELECT 후 INSERT 금지. 외부 호출 없음. 잠금 순서: 멱등 기록 → control → Payment(CP-DB-001에서 실제 MySQL로 검증).

## 12. DB 논리 제약 후보(CP-DB-001로 넘김)

| 테이블 | 컬럼/제약 | 근거 |
|---|---|---|
| 공통 | 모든 업무 키 문자열 컬럼 `COLLATE utf8mb4_0900_bin`(NO PAD binary), 서버 기본 collation 상속 금지, information_schema 검사 | D-CP-001 |
| order_payment_control | environment VARCHAR(4)/ENUM(TEST,LIVE), merchant_account_id VARCHAR(64)\*, merchant_order_id VARCHAR(64) CHARACTER SET ascii 또는 utf8mb4 + bin collation, **PK/UNIQUE(environment, merchant_account_id, merchant_order_id)**, fixed_intent_fingerprint VARCHAR(80) NOT NULL, current_payment_id NOT NULL, generation BIGINT NOT NULL, authorized_payment_id NULL, version BIGINT NOT NULL | 시도 §1, D-CPC-002 |
| payment | PK payment_id, UNIQUE payment_key, merchant_site_id VARCHAR(64)\*, amount_minor **BIGINT NOT NULL CHECK(amount_minor >= 1)**, currency **CHAR(3)** bin + CHECK(REGEXP '^[A-Z]{3}$'), payment_method, order_name NULL, order_intent_fingerprint VARCHAR(80), slot_generation BIGINT, status, eligibility_snapshot_version, created_at, version. INDEX(environment, mid, merchant_order_id) — **UNIQUE 금지**(주문 1:N) | D-CPC-005, D-CP-002 |
| idempotency_request | **UNIQUE(environment, merchant_account_id, operation, idempotency_key)**, idempotency_key VARCHAR(64) bin, request_fingerprint VARCHAR(80), result_kind, payment_id, result_payload/참조, created_at, expires_at(=created_at+30일) INDEX | D-CPC-004, D-CPC-002 |
| incident_hold | UNIQUE(order key, payment_id, reason) | CP-DOMAIN-001 M-3 |
| outbox | event_id UNIQUE, effect_key UNIQUE, event_type, schema_version, aggregate_id, aggregate_version, occurred_at, payload JSON, 발행 상태 | D-CPC-003 |

\* MID·Site 길이 64는 MASTER 발급 규칙 확정 전 후보(관례). MASTER-DOMAIN-001에서 다르게 정하면 CP-DB-001 migration 전에 맞춘다. Cross-DB FK 없음.

## 13. required Port — eligibility Snapshot(D-CPC-006: 계약만, 미구현)

```
PaymentEligibilityPort.evaluate(
  environment, authenticatedMid, merchantSiteId, currency, paymentMethod, evaluatedAt
): EligibilityResult
EligibilityResult =
  Eligible(snapshotVersion, currencyUnit: CurrencyUnit(code, minorUnit), validUntil)
| NotFoundOrForbidden                                 // Site 없음·다른 Merchant/MID·Assignment 무효
| NotEligible(reason: CURRENCY_NOT_ENABLED | METHOD_NOT_ENABLED | CONTRACT_NOT_EFFECTIVE | MERCHANT_SUSPENDED)   // 한도 코드 없음(§4)
| Unavailable(reason: SNAPSHOT_MISSING | SNAPSHOT_EXPIRED | VERSION_UNSUPPORTED)   // fail-closed
```

- 구현은 PAYMENT 로컬 Snapshot 투영 조회(MASTER DB 직접 조회 금지). MASTER-SNAPSHOT-001 전까지 구현체 없음, CP-API-001은 그 뒤.
- Payment에 eligibility snapshotVersion을 보존한다(Auditability). 생성 시 검사는 승인 시 재검사(허용 §4.1)를 대신하지 않는다.
- currencyUnit은 Money 표시·검증에 쓰는 기준정보 minor unit 출처다(코드 허용 목록 대체).
- 한도: v1 Port는 건당·일 한도를 평가하지 않고 NotEligible에 한도 reason이 없다. 한도 권위 판정은 승인 시작(AUTH-CONTRACT-001, D-CPC-008). 생성 시 건당 사전 검사는 D-CPC-008상 선택이며 v1은 채택하지 않았다(관례, 가역적 — §4).

## 14. 이번 작업에서 구현한 코드(domain, 순수 Kotlin)

`apps/payment-api/src/main/kotlin/com/paycore/payment/payment/domain/`:
- `Money.kt`: `CurrencyCode`(ISO 4217 형식), `CurrencyUnit`(code + minorUnit 0..4, 기준정보 값), `Money`(amountMinor ≥ 1, `toMajorUnitString`).
- `OrderKey.kt`: `MerchantOrderId` D-CPC-002 형식 강제, `IdempotencyKey`(같은 형식), 공통 형식 검사, MID KDoc 정정, `MerchantAccountId` 짝 없는 서로게이트 거부(§3.1).
- `OrderIntent.kt`: `MerchantSiteId`(짝 없는 서로게이트 거부, §3.1), `OrderIntent(merchantSiteId, money).fingerprint()` v1(§6).
- `Utf8Text.kt`: Unicode 정합성 검사·엄격 UTF-8 인코딩(malformed REPORT, §6.2).
시험: `MoneySpec`, `MerchantRequestIdFormatSpec`, `OrderIntentSpec`(서로게이트 시험 포함), `OrderKeySpec` 갱신, `OrderFixture`가 v1 fingerprint를 실제 계산. 멱등 요청 fingerprint(§7)·오류 매핑(§9)·Outbox(§10)·Port(§13)는 문서 계약이며 코드 없음.

## 15. 초안 대비 변경점

| 초안 | 확정판 |
|---|---|
| 고정 주문 입력 "금액·통화·Site·MID 등" | Site + amount + currency(D-CPC-001), MID·env는 OrderKey |
| 키 정규화·길이 미결 | 그대로 비교(D-CP-001) + 1~64 ASCII 형식(D-CPC-002), 생성자 강제(관례) |
| 금액 precision 미결 | minor unit Long, 1..BIGINT, 한도는 기준정보(D-CPC-005), 통화는 기준정보 활성(D-CP-002) |
| result original/current 미결 | 저장된 최초 결과만, 현재는 GetPayment(D-CP-003). lifecycleAtCreation 항상 REQUESTED, outcome 추가 |
| 오류 후보 목록 | §9 코드·HTTP 표(관례). ResultPending → `IDEMPOTENCY_REQUEST_IN_PROGRESS`, RetryableLocalConflict → `TEMPORARILY_UNAVAILABLE` |
| PaymentCreated 등 이벤트 미정 | PaymentRequested v1(D-CPC-003), 비금융 분류·effectKey |
| 멱등 보존 미결 | 30일, 경과 후 새 요청, 주문 통제 영구(D-CPC-004) |
| MASTER Snapshot | required Port 계약만(D-CPC-006) |
| paymentMethod 고정 입력 여부 불명 | 고정 입력 아님, 멱등 fingerprint에는 포함 |

## 16. 미결·질문 후보(Coordinator 경유, 답 전 해당 동작 미구현)

| ID | 질문 | 권장안 | 대안 | 영향 | 막히는 작업 |
|---|---|---|---|---|---|
| Q-CPC-REJECT-REPLAY | → 확정 D-CPC-007: 업무 거절 저장·30일 재생 | | | | CP-DB-001, CP-API-001 |
| Q-CPC-LIMIT-STAGE | → 확정 D-CPC-008: 승인 시작에서 최종 판정, 생성 시 건당 한도만 선택적 사전 검사. v1 생성은 사전 검사 안 함(§4 관례) | | | | MASTER-SNAPSHOT-001, AUTH-CONTRACT-001 |
| Q-CPC-AMOUNT-JSON | → 확정 D-CPC-009: JSON 정수 유지 + 정확 정수 파서 | | | | CP-API-001 |

리뷰 이월(round1, 구현 안 함): L-4 lock wait timeout 분류(TX rollback 보장) → CP-API-001, L-8 r1 golden vector·orderName 길이 단위 → CP-API-001, L-9 버전 지정 fingerprint API(`fingerprint(version)`) → v2 도입 시.

비질문 후속: MID·Site 형식/길이(MASTER-DOMAIN-001), paymentId/paymentKey 생성기(CP-API-001), 멱등 기록 삭제 Job(CP-DB-001 이후). (DOMAIN-DEFINITION IdempotencyRequest 행은 D-CPC-004·007로 갱신 완료.)
