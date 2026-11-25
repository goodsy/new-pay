# FIN-FACT-RELAY-001 PAYMENT → FINANCIAL 결제 사실 전달 명세 (rev 0.2)

> **S3-DOC-001 문서 정합 표시(2026-11-25 — 코드·시험·정책 변경 없음, 자기 완료 판정 아님)**: 이후 사용자 결정으로 확정·보완된 해석 옆에 `→ 확정`/`→ 결정됨`/`→ 현행화` 표시만 붙였다(원문 보존). 바뀐 곳: §2 I-2(상한 없는 재시도 + 밀림 경보 = 사용자 답 D-GOV-005 ③ / 4xx 격리 기준 유지·경보의 LIVE 조건 = Coordinator 기록), §1 제외 열의 RISK_INCIDENT 전달(D-GOV-001 ④ — OPERATIONAL_RISK는 결정 없음), §9 L-1(FIN-DBACCOUNT-001 DONE). 근거 FIN 대장 (b)·WH 대장 진단 (b)-2·(b)-3, RULE-CONFIRM-001 C(BR-FIN-008·BR-WH-077). 현재 유효 규칙 정본은 `docs/rules/FIN.md`·`WH.md`(D-GOV-006 ②).

작성 2026-11-05 KST(rev 0.2 — 사용자 결정 D-FFR-001~004·독립 리뷰 REVIEW_FAIL M-1/m-1~m-3/NIT 반영, §12), branch `feat/foundation-setup`(기준 commit `df6fa0e` + Coordinator의 미커밋 harness/DECISIONS 변경). Implementer 작성 — 자기 완료 판정 아님(Verifier·Reviewer 별도).

근거: 사용자 결정 D-ACQ-001(Q-ACQ-01)·D-ACQ-002(Q-ACQ-02)·D-ACQ-007(Q-BATCH-META), AUTH-001 §4.10·CANCEL-001 §4.10(PaymentAuthorized·PaymentCancelled v1 payload — `OutboxStore.kt`),
CANCEL-CONTRACT-001(취소 기록/상계는 FINANCIAL이 판단, 원승인 전 취소는 보류), MASTER-SNAPSHOT-001 §4.7·§5.1(MASTER→PAYMENT 권리 Relay·Inbox — 재사용 패턴),
ADMIN-PAYMENT-OPS-001 §4.2(시각 창 있는 서명 v1 형식), WEBHOOK-001 D-WH-009(Outbox 열 단위 권한), ARCHITECTURE-CONVENTIONS §9·§14, TECHNOLOGY-AND-PRINCIPLES §3–§5.

## 1. 범위

| 포함 | 제외(후속) |
|---|---|
| PAYMENT: FINANCIAL_FACT Outbox 행(PaymentAuthorized·PaymentCancelled v1)을 서명 내부 HTTP로 FINANCIAL에 push하는 Relay(발견·claim lease·backoff 재시도·격리·ACK 기록·적체 gauge) | 매입 파일 생성·전송·결과(ACQUIRING-001), 보류 해제 규칙(ACQUIRING-001 — D-ACQ-002), 정산(SETTLEMENT-*) |
| FINANCIAL(financial-batch): 내부 Inbox(서명·시각 창·봉투 검증) + 결제 사실 투영(승인·취소) + 보류(Hold) — Inbox와 투영이 같은 로컬 TX | RISK_INCIDENT·OPERATIONAL_RISK 이벤트 전달(RECON 작업), 격리·UNSUPPORTED 재처리 도구 **→ RISK_INCIDENT만 결정됨(D-GOV-001 ④, S3-DOC-001): PAYMENT 사고 보류는 사고 사실을 FINANCIAL로 전달해 매입 후보·정산을 보류한다 — 구현은 후속 작업(이 명세 범위 밖 그대로, 미구현). OPERATIONAL_RISK(`*OutcomeUnresolved` 등) 전달은 결정 없음** |
| FINANCIAL 첫 migration: Spring Batch metadata(BATCH_*) main V1(D-ACQ-007) + 결제 사실 V2 | FINANCIAL DB 계정 분리·표 단위 runtime 권한(D-FFR-002 → 별도 작업 FIN-DBACCOUNT-001, ACQUIRING-001 전) |
| 두 앱 독립 계약 golden `payment-fact-relay-v1.golden.json`(harness Python 생성) | mTLS·내부망 바인딩·운영 키 배포(LIVE Gate) |

Kafka/Redis는 도입하지 않는다(ADR-001). 전달 안전성은 PAYMENT Outbox(업무 TX와 원자적) → at-least-once push → FINANCIAL Inbox effect key UNIQUE + 같은 로컬 TX 투영이 지킨다. 브로커가 이 불변식을 대신하지 않는다.

## 2. 사용자 결정과 Implementer 해석(구분)

| ID | 내용 | 종류 |
|---|---|---|
| D-ACQ-001 (Q-ACQ-01) | PAYMENT Outbox → 서명된 내부 HTTP push → FINANCIAL Inbox(effect key unique, Inbox+투영 같은 로컬 TX). MASTER→PAYMENT 권리 전달 패턴 재사용. cross-DB SQL 금지 | 사용자 결정 |
| D-ACQ-002 (Q-ACQ-02) | 매입 대상은 PAYMENT 승인 확정 건만. … **원거래를 모르는 취소는 Hold** | 사용자 결정(이 작업은 Hold **입력**만 만든다 — 매입 판단은 ACQUIRING-001) |
| D-ACQ-007 (Q-BATCH-META) | Spring Batch metadata(BATCH_*)는 FINANCIAL DB 같은 schema. 보존·정리 정책은 별도 | 사용자 결정 |
| D-FFR-001 (Q-FFR-01) | PAYMENT runtime에 `outbox_event.publish_status`·`published_at` **두 열만** 열 단위 UPDATE(D-WH-009 확장). payload 등 나머지 열 1143·DELETE 1142 유지. 미전달 발견 비용을 미전달 행 수로 줄이는 용도 | 사용자 결정(2026-11-05) — V34, ACK TX에서 PENDING → PUBLISHED |
| D-FFR-002 (Q-FFR-02) | FINANCIAL DB 계정 분리는 별도 작업 FIN-DBACCOUNT-001(ACQUIRING-001 전) | 사용자 결정 — 이번 범위 밖 |
| D-FFR-003 (Q-FFR-03) | 정합성 보류 5종 유지. ORIGINAL_UNKNOWN은 원승인 도착 + 금액·통화·식별 MATCH 시 append-only 해제 기록으로 자동 해제, 불일치는 운영자 검토, 보류 원본 기록은 수정하지 않음 — **구현은 ACQUIRING-001** | 사용자 결정 |
| D-FFR-004 (Q-FFR-04) | 모르는 schema 버전 사실은 UNSUPPORTED로 원문 보존 후 ACK. 재처리 도구는 후속 | 사용자 결정 |
| ~~I-1~~ | (rev 0.2) D-FFR-001로 대체. rev 0.1 해석: PAYMENT Outbox 행·열을 바꾸지 않는다. 전달 상태는 새 표 `financial_fact_relay`(Outbox 행당 1행)에 둔다. `publish_status`(V1에서 "FINANCIAL 전달 표시"로 예약)를 갱신하려면 runtime에 그 열의 UPDATE 권한이 필요한데 D-WH-009가 "publish_status 수정은 계속 거부(1143)"로 정했기 때문이다. 대가: 발견 비용이 FINANCIAL_FACT 행 수에 비례 | 대체됨(D-FFR-001) |
| I-2 **→ 일부 사용자 답(S3-DOC-001): '상한 없이 재시도 유지 + 오래 밀린 사실 경보'는 사용자 답(Q-GOV-U10c → D-GOV-005 ③). '현 4xx 격리 기준 유지'·'밀림 경보는 LIVE 조건'은 Coordinator 기록 — RULE-CONFIRM-001 Q-C 확인 대기(대장 BR-FIN-008·BR-WH-077 `제안`)** | HTTP 분류: **400·409·422 + 본문 `error`가 그 상태의 FINANCIAL 계약 코드일 때만 격리**(rev 0.2 검토 m-1 — 프록시·다른 서버의 4xx로 격리하지 않는다. 그 내용은 다시 보내도 거절된다). 401(키 교체 실수·시계 오차)·404·411·413·415·429·3xx·5xx·연결 실패·읽을 수 없는 ACK는 **재시도**(상한 없음, 연령 gauge 경보). MASTER Relay(모든 4xx 격리)와 다른 점: 금융 사실은 설정 오류가 고쳐지면 스스로 전달돼야 하고 재처리 도구가 아직 없다 | Implementer 해석 |
| I-3 → D-FFR-004 | 지원하지 않는 (eventType, schemaVersion)은 FINANCIAL Inbox에 **UNSUPPORTED로 원문 보존 + ACK**(투영 없음, error 로그·counter). PAYMENT는 DELIVERED로 두고 error 로그. 권리 Inbox의 UNSUPPORTED 처리와 같은 모양. 재처리 도구는 없음(§9 L-5) | **사용자 결정 D-FFR-004**(rev 0.1에서는 해석) |
| I-4 → D-FFR-003 | 보류는 append-only `payment_fact_hold`. **해제 경로·표를 만들지 않는다**(늦게 도착한 원승인도 보류를 풀지 않는다 — 현재 연결 여부는 원승인 표 join으로 판단 가능). 해제 규칙(원승인 도착 + MATCH → append-only 해제 기록, 불일치 운영자 검토)은 ACQUIRING-001이 구현 | **사용자 결정 D-FFR-003**(이 작업은 해제를 구현하지 않음) |
| I-5 → D-FFR-003 | ORIGINAL_UNKNOWN 외 fail-closed 정합성 보류 5종: ORIGINAL_MISMATCH(원승인과 승인 ID·Payment·통화·승인액·기관 원거래번호 불일치), RETURN_SEQUENCE_CONFLICT(같은 원승인·같은 반환 순번의 다른 취소), RETURN_EXCEEDS_AUTHORIZED(받은 취소 합계 > 원승인액), AUTHORIZATION_CONFLICT(같은 Payment의 두 번째 다른 승인 거래), IDENTITY_MISMATCH(같은 Payment의 환경·MID·사이트·주문번호 불일치). 사실은 잃지 않고 투영하되 보류로 표시 | **사용자 결정 D-FFR-003**(유지) |
| I-6 | 같은 effect key를 다른 eventId가 주장하면 409 `EFFECT_KEY_CONFLICT`(저장 없음). PAYMENT Outbox effect_key·event_id가 모두 UNIQUE라 정상 경로에서는 생기지 않는 계약 오류 | Implementer 해석 |
| R-1 | 서명 v1: `PayCore-Fact-Signature: t=,kid=,v1=` + canonical `paycore.payment-fact.v1`·POST·경로·t·SHA-256(본문). ADMIN 채널 서명 형식(시각 창 300초 Demo)을 이 채널 전용 키 공간으로 재사용. nonce 없음(Inbox 멱등) | 가역 구현 선택 |
| R-2 | 봉투 본문은 Outbox JSON 열을 읽은 문자열의 키 순서를 유지한 compact JSON — 재전송마다 같은 바이트(FINANCIAL이 같은 eventId를 원문 SHA-256으로 DUPLICATE 판정) | 가역 구현 선택 |
| R-3 | FINANCIAL 수신은 financial-batch에 둔다(servlet 웹 앱으로 전환 — FINANCIAL 소유 앱은 이것 하나). HTTP 표면은 진입 필터가 Inbox 경로·health만 남긴다 | 가역 구현 선택 |
| R-4 | 결제별 직렬화 행 `payment_fact_stream`(IODKU + `FOR UPDATE`) — 같은 Payment 사실의 검사가 경합하지 않는다 | 가역 구현 선택 |
| R-5 | PAYMENT `financial_fact_relay_ack`(append-only ACK 감사 — DUPLICATE ACK도 남김) | 가역 구현 선택 |
| R-6 | (rev 0.2, 검토 M-1) 겹친 전송 방지: Relay가 **행마다** 보내기 직전 `claim_until − now > 전송 예산(연결 + 요청 상한) + 1초`를 확인하고, 모자라면 남은 행의 claim을 풀고 회차를 끝낸다. dispatcher는 전송 예산 안에 반드시 반환한다(응답 본문까지 포함한 전체 기한 + 취소, 본문 상한). 행 단위 lease 갱신(행마다 claim_until 연장 TX) 대신 고른 이유: 추가 TX 없이 같은 보장, 배치 크기와 무관한 상한. 대가: 느린 FINANCIAL에서 회차당 처리 수가 lease/지연으로 제한(처리량 — 겹친 전송 아님) | 가역 구현 선택 |
| R-7 | (rev 0.2 n-2) HttpClient HTTP/1.1 고정. (m-2) FINANCIAL Spring Data 인터페이스는 `Repository`만 상속·선언 메서드만(save·delete·flush 없음). (m-3) claim 색인 `(status, outbox_id)` = 정렬 순서 | 가역 구현 선택 |

### 2.1 기술 선택 메모(TECHNOLOGY-AND-PRINCIPLES §3–§5 — 이번 변경에 연결)

- **JPA(도메인 영속화)**: FINANCIAL 순수 domain(`PaymentFact`·`AuthorizedPaymentFact`·`CancelledPaymentFact`·`PaymentFactProjectionRule`)은 Spring/JPA 없음(ArchUnit `domainIsPure` 대상이 생겨 false로 전환). `Jpa*Entity`는 `adapter.persistence`에만 있고 `PaymentFactPersistenceMapper`가 옮긴다(행 → domain은 domain 생성자를 다시 거쳐 존재할 수 없는 사실을 거부).
  - **역할**: 사실·Inbox·보류 Entity는 `@Immutable` + 모든 열 `updatable=false`, Store는 `persist`만 한다. 직렬화 행 `JpaPaymentFactStreamEntity`만 잠가 읽은 뒤 변경 감지로 `fact_count`를 올리고 `@Version`이 함께 오른다.
  - **예**: 같은 Payment의 승인과 취소 두 건이 동시에 오면 세 TX가 `payment_fact_stream` 행의 `refresh(PESSIMISTIC_WRITE)`(= `SELECT … FOR UPDATE`)에서 줄을 선다. 뒤 TX는 앞 TX가 커밋한 승인을 이력으로 읽어 취소를 보류 없이 반영하고, 승인보다 먼저 줄을 선 취소는 `ORIGINAL_UNKNOWN`으로 보류된다(FR-I8 — 어느 쪽이든 보류 표시와 `original_known_at_receipt`가 일치).
  - **이유**: 이력 검사(원승인 존재·합계·순번)는 서로 다른 사실 행 사이의 조건이라 행별 `@Version`으로 못 지킨다 — 부모 행 비관적 잠금이 직렬화하고, effect key·event_id UNIQUE가 DB 이중 방어다. `@Immutable`이라 변경 감지·merge가 확정 사실을 UPDATE하는 경로가 없고 cascade·orphanRemoval 연관이 없어 지우는 경로도 없다.
  - **주의**: persist 뒤 즉시 `flush`해 UNIQUE·CHECK 위반을 Port 호출 안에서 분류한다(flush ≠ commit — 커밋은 Use Case TX 끝 한 번). flush 실패 뒤 TX는 rollback 전용이며 새 TX로 처음부터 재판정한다(최대 3회). IODKU(native)는 영속성 컨텍스트를 거치지 않으므로 `find` 뒤 `refresh`로 최신 값을 읽는다. 충돌·중복 판정 TX는 `setRollbackOnly`로 아무것도 남기지 않는다(IODKU로 만든 빈 직렬화 행 포함 — FR-I2).
  - PAYMENT `JpaFinancialFactRelayEntity`: claim은 native `FOR UPDATE SKIP LOCKED`(JPA 표준 잠금 API에 없는 조합)로 잠근 뒤 그 행만 `find`해 변경 감지로 claim을 표시, 결과 반영은 `find(…, PESSIMISTIC_WRITE)` 뒤 변경 감지 + `@Version`. `JpaFinancialFactRelayAckEntity`는 `@Immutable` persist만. Outbox Entity(`JpaOutboxEventEntity`, `@Immutable`)는 건드리지 않는다.
- **jOOQ(CQRS 조회)**: PAYMENT Relay 적체(PENDING 수·최고 연령·재시도·격리·미발견 수)는 `JooqFinancialFactRelayBacklogReader`(읽기 전용 TX, 판정에 쓰지 않음) → provided `FinancialFactRelayBacklogQuery` → gauge. FINANCIAL은 아직 jOOQ 조회가 없다(codegen 미도입 — 첫 조회 화면/작업에서 도입).
- **버전**: 새 라이브러리·override 없음. financial-batch에 Boot 4.1.1 BOM 관리 starter(webmvc·data-jpa·actuator)와 카탈로그 기존 `jackson-module-kotlin`을 더했다(payment-api와 같은 조합). Batch 6.0.5 metadata migration은 jar 원본(`schema-mysql.sql` SHA-256 `774f0a4f…`)과 본문 동일(diff로 확인).
- **Kafka/Redis**: 미도입. backlog·다중 consumer·replay 요구가 실제로 생기면 Dispatcher Port 뒤 adapter로 붙일 수 있지만, 그때도 exactly-once 효과는 Inbox effect key UNIQUE + 로컬 TX가 지킨다(Kafka 트랜잭션은 FINANCIAL DB 효과를 포함하지 않는다).

## 3. 상태 모델

PAYMENT `financial_fact_relay`(Outbox 행당 1행 — Relay가 발견할 때 생성, 업무 TX는 쓰지 않음):

```
(없음) --discover--> PENDING(attempts 0, next_attempt_at = now)
PENDING --claim(owner, claim_until)--> PENDING(claimed)  ※ lease 만료 시 다른 인스턴스가 다시 claim
PENDING(claimed) --ACK(2xx, 같은 eventId·effectKey, consumer=financial-batch)--> DELIVERED(ack_outcome = 처음 결과)   [종결]
PENDING(claimed) --재시도 분류--> PENDING(attempts+1, next_attempt_at = now + backoff, claim 해제)
PENDING(claimed) --400·409·422--> QUARANTINED(attempts+1, 실패 코드)   [완료 아님 — 경보, 재전송 안 함]
```
DELIVERED·QUARANTINED는 바뀌지 않는다(늦은 ACK는 ACK 기록만 추가, 늦은 재시도·격리 표시는 `ALREADY_FINAL`, claim을 잃은 표시는 `CLAIM_LOST`로 덮어쓰지 않음).

FINANCIAL Inbox 결과(`payment_fact_inbox.outcome`): `APPLIED`(투영, 보류 없음) · `HELD`(투영 + 보류 행) · `UNSUPPORTED`(원문만). 응답 전용 값 `DUPLICATE`(같은 eventId·같은 원문 재전송 — `originalOutcome` = 저장된 결과).
보류 사유(`payment_fact_hold.reason`, append-only, 해제 없음): ORIGINAL_UNKNOWN(D-ACQ-002) · ORIGINAL_MISMATCH · RETURN_SEQUENCE_CONFLICT · RETURN_EXCEEDS_AUTHORIZED · AUTHORIZATION_CONFLICT · IDENTITY_MISMATCH(I-5).

## 4. 계약

### 4.1 채널(PAYMENT → FINANCIAL, golden `payment-fact-relay-v1.golden.json`)

- `POST /internal/v1/payment-facts`(FINANCIAL 내부 포트, `Content-Type: application/json`, Content-Length 필수, 본문 ≤ `max-body-bytes`, query 금지).
- 본문(compact, 필드 순서 고정): `envelopeVersion`(1)·`eventId`(소문자 UUID)·`effectKey`·`eventType`·`schemaVersion`·`eventCategory`(`FINANCIAL_FACT`)·`producer`·`aggregateType`·`aggregateId`·`aggregateVersion`·`occurredAt`(UTC ms)·`payload`(Outbox payload 그대로 — 카드번호·Secret·인증 원문·기관 계약계정 없음, PAYMENT 계약).
- 서명 헤더 `PayCore-Fact-Signature: t=<unix 초>,kid=<id>,v1=<hex>`; canonical = `paycore.payment-fact.v1` LF `POST` LF `/internal/v1/payment-facts` LF t LF hex(SHA-256(본문)). 검증 순서 문법 → 시각 창(|now−t| ≤ 300초 Demo, 경계 포함) → kid(교체 중 2키) → HMAC(상수 시간). 거절은 모두 401 `UNAUTHENTICATED`.
- FINANCIAL 응답: 200 ACK `{consumer:"financial-batch", eventId, effectKey, outcome[, originalOutcome]}`(내구 커밋 뒤) / 400 `ENVELOPE_INVALID`(JSON·중복 키·필드·eventCategory) / 422 `CONTENT_INVALID`(지원 사실 payload 형식, effectKey ≠ `<eventType>:<transactionId>`, aggregate ≠ 사실) / 409 `EVENT_ID_CONFLICT`·`EFFECT_KEY_CONFLICT` / 503 `TEMPORARILY_UNAVAILABLE`(저장 실패·커밋 결과 불명확) / 진입 필터 400 `QUERY_NOT_ALLOWED`·411·413·415(Spring)·404(그 밖 경로·method).
- PAYMENT 분류: 2xx → ACK 검증(consumer·eventId·effectKey 일치, outcome 집합, DUPLICATE ⇔ originalOutcome) 실패면 재시도 `ACK_UNREADABLE`; 400 `ENVELOPE_INVALID`·409 `EVENT_ID_CONFLICT`/`EFFECT_KEY_CONFLICT`·422 `CONTENT_INVALID` → 격리(실패 코드 `HTTP_<상태>_<코드>`); 그 밖(코드 없는 4xx 포함) → 재시도(golden `rejectCodes`·`statusCases`).
- 키: 채널 전용 키 공간 — PAYMENT는 권리 inbox·ADMIN 채널·가맹점 Webhook 시험 서명 키(rev 0.2 n-5)와 바이트·kid가 같으면 기동 거부(`FinancialFactRelayKeySeparation`). 두 앱 모두 운영 기본값 없음(키·창·상한·endpoint·포트·주소가 없으면 기동 실패).

### 4.2 FINANCIAL 수신 TX(`PaymentFactInboxService`, READ COMMITTED 로컬 TX 하나)

```
(지원 사실) payment_fact_stream IODKU → FOR UPDATE
  → inbox event_id 조회: 있으면 같은 원문 SHA-256 → DUPLICATE(처음 결과) / 다르면 EVENT_ID_CONFLICT   (둘 다 rollback)
  → inbox effect_key 조회: 있으면 EFFECT_KEY_CONFLICT(rollback)
  → 이력(같은 Payment 승인·취소) → PaymentFactProjectionRule.decide → inbox INSERT + 사실 INSERT + 보류 INSERT + stream fact_count+1
(지원 안 함) inbox 조회 → UNSUPPORTED INSERT
COMMIT → ACK. commit 단계 실패 → 응답 없음(503) → PAYMENT 재전송 → DUPLICATE로 수렴. 교착·UNIQUE·version 충돌 → 새 TX 재판정(최대 3회)
```

### 4.3 투영 판정(`PaymentFactProjectionRule`, 순수 domain)

§2 D-ACQ-002·I-5의 사유를 받은 이력으로 계산한다. 사실은 보류여도 투영한다(확정 사실을 잃지 않는다). `original_known_at_receipt`는 수신 시점 원승인 존재 여부(이후 불변).

### 4.4 PAYMENT Relay 회차(`FinancialFactRelayService`, `@Scheduled` trigger `FinancialFactRelayScheduler`)

```
TXa discover: INSERT … SELECT(FINANCIAL_FACT·publish_status = PENDING Outbox 중 전달 행 없는 것, Outbox id 순, LIMIT) … ON DUPLICATE KEY UPDATE — Outbox는 consistent read(잠금 없음)
TXb claim: SELECT … FOR UPDATE SKIP LOCKED(PENDING·due·claim 없음/만료, outbox_id 순 = 색인 (status, outbox_id)) → claim_owner/claim_until 표시 → COMMIT
행마다: claim_until − now ≤ 전송 예산 + 1초이면 남은 행 claim 해제(TXr) 후 회차 종료(deferred) — R-6
  (TX 밖) 서명 HTTP(JDK HttpClient HTTP/1.1, redirect·proxy 없음, 전송 예산 = 연결 + 요청 상한 안에 반드시 반환)
  TXc 결과 반영(ACK → DELIVERED + ACK 기록 + Outbox PUBLISHED(D-FFR-001) · 재시도 backoff · 격리). 한 행의 예상 밖 예외는 그 행만 실패로 세고 계속
```
backoff = initial × 2^attempts(상한 max) + jitter(비율). 재시도 상한 없음. claim lease > 연결 + 요청 상한 + 1초(기동 검사 `FinancialFactRelayTimingConsistency`).

## 5. DB 표·migration

| DB | 파일 | 내용 |
|---|---|---|
| FINANCIAL | `db/migration/financial/V1__spring_batch_metadata.sql` | D-ACQ-007: BATCH_* 9표(Spring Batch 6.0.5 `schema-mysql.sql` 본문 그대로). 시험용 사본 `src/test/resources/db/testmigration/financial/V0_1__…`은 삭제 |
| FINANCIAL | `db/migration/financial/V2__payment_fact_inbox.sql` | `payment_fact_inbox`(UNIQUE event_id·effect_key, outcome CHECK, 원문 JSON + SHA-256 + 서명 kid), `payment_fact_stream`(결제별 직렬화·첫 신원·건수·version), `payment_fact_authorization`·`payment_fact_cancellation`(PK transaction_id, UNIQUE source_event_id·effect_key, 금액·통화·Plan·카드 CHECK, 취소→원승인 FK 없음), `payment_fact_hold`(UNIQUE (transaction_id, reason), 사유 CHECK) |
| PAYMENT | `db/migration/payment/V32__financial_fact_relay.sql` | `outbox_event` 색인 `ix_outbox_event_financial_pending(event_category, publish_status, id)`(온라인 DDL — 미전달 행만 보는 발견 질의), `financial_fact_relay`(상태 CHECK·claim 쌍 CHECK·실패 코드 형식·claim 색인 `(status, outbox_id)`), `financial_fact_relay_ack`(append-only). rev 0.2에서 색인 두 개를 바꿨다(이 migration은 미커밋·운영/Demo DB 미적용 전제 — 적용된 DB가 있으면 새 migration으로 바꿔야 한다) |
| PAYMENT | `db/runtime-privilege/payment/V33__financial_fact_relay_privileges.sql` | relay = SELECT·INSERT·UPDATE, ack = SELECT·INSERT(주석을 실제 SQL과 맞춤 — n-1) |
| PAYMENT | `db/runtime-privilege/payment/V34__outbox_publish_status_privileges.sql` | D-FFR-001: `grant_runtime_column_update('outbox_event', 'publish_status, published_at')`(부여만 — V24 알림 두 열 유지, 표 단위 helper 재호출 없음). 기대값 변경(DatabaseAccountSeparationSpec SHOW GRANTS·열별 1143 시험, OutboxNotificationMigrationSpec publish 1143 → 허용)은 **완화가 아니라 사용자 결정 D-FFR-001에 따른 것**이며 payload 등 나머지 열 1143·DELETE 1142는 그대로 시험한다 |

작성 시점(rev 0.2) FINANCIAL은 단일 계정(DB ALL)이라 append-only가 DB 권한으로 강제되지 않았다(§9 L-1 → FIN-DBACCOUNT-001, D-FFR-002에서 SELECT·INSERT만으로 강제). 현재 방어는 `@Immutable`·persist 전용 Store·UPDATE/DELETE 코드 경로 없음(FR-I11).

## 6. 실패·회복

| 상황 | 동작 | 증거 |
|---|---|---|
| 중복 전달(재시도·lease 겹침·응답 유실 뒤 재전송) | 같은 eventId·같은 바이트 → DUPLICATE(처음 결과), 투영 1회 | FR-I1·I7, FR-H1, FR-R3 |
| 같은 eventId·다른 본문 / 같은 effect key·다른 eventId | 409, 저장 없음(직렬화 행도 rollback) → PAYMENT 격리 | FR-I2·I3, FR-H5 |
| 원승인보다 먼저 온 취소(순서 뒤바뀜) | 투영 + HELD(ORIGINAL_UNKNOWN), 이후 원승인 APPLIED, 뒤 취소 APPLIED. 이전 보류는 유지(해제는 ACQUIRING-001) | FR-I4, FR-H2, FR-D2 |
| FINANCIAL 커밋 뒤 응답 유실 | PAYMENT TIMEOUT → 재시도 → DUPLICATE ACK → DELIVERED(ack_outcome = 원래 결과) | FR-R3, FR-I10(커밋 뒤 실패 TX 관리자), FR-P3 |
| FINANCIAL 커밋 전 저장 실패 | 전체 rollback → 503 → 재전송 시 1회 반영 | FR-I9 |
| FINANCIAL 중단·연결 거부·5xx | 재시도(backoff, 상한 없음), PENDING 연령 gauge 경보 | FR-R2, FR-P3 |
| Relay crash(claim 커밋 뒤 표시 전) | lease 동안 다른 인스턴스는 건너뜀 → 만료 뒤 재claim·전달, crash 소유자의 늦은 표시는 ALREADY_FINAL | FR-R7 |
| 다중 Relay 인스턴스 | SKIP LOCKED로 claim 분리 + 행마다 남은 lease 확인(R-6) — lease가 끝난 뒤 보내는 일이 없어 다른 인스턴스의 재claim과 겹치지 않는다 | FR-R6, FR-R9(느린 FINANCIAL: 지연 × 배치 > lease) |
| poison message(FINANCIAL이 계약 코드로 내용 거절 400·409·422) | QUARANTINED(완료 아님 — 재전송 없음, 격리 gauge > 0 경보, 재처리 도구는 §9 L-5) | FR-R4 |
| 계약 코드 없는 4xx(프록시·잘못된 endpoint) | 재시도(격리 안 함 — m-1) | FR-P3 statusCases |
| 위조·변조·오래된 서명·모르는 kid | 401, 저장 없음. PAYMENT는 재시도(키 설정 수정 후 자동 전달 — I-2) | FR-H3, golden verifyCases |
| 믿을 수 없는 ACK(다른 eventId·consumer) | DELIVERED로 표시하지 않고 재시도(`ACK_UNREADABLE`) | FR-R5, FR-P2 |
| 지원하지 않는 eventType/schema | FINANCIAL UNSUPPORTED 보존 + ACK, 양쪽 error 로그(I-3) | FR-I6 |

### 6.1 8대 원칙별 완료 증거

| 원칙 | 적용 | 증거(시험) |
|---|---|---|
| Financial Consistency | 확정 사실 append-only 투영(`@Immutable`, persist 전용), 금액은 minor 정수·통화 형식 CHECK, 취소 합계·통화·승인액 정합성 → 어긋나면 보류(값을 고치지 않음) | FR-D3·D4·D8, FR-I5·I11 |
| Transaction Safety | FINANCIAL Inbox + 투영 + 보류 + 직렬화 행 = 로컬 TX 하나, 커밋 뒤에만 ACK. PAYMENT 외부 호출은 TX 밖(claim TX 커밋 뒤), Outbox는 업무 TX와 원자적(기존) | FR-I9·I10, FR-R1·R3 |
| Idempotency | eventId·effect key UNIQUE, 원문 SHA-256 동일성, 재전송 바이트 동일(R-2), PAYMENT 발견 IODKU | FR-I1·I2·I3·I7, FR-R2(같은 바이트), golden envelopes |
| Concurrency Control | FINANCIAL 결제별 IODKU(배타 행 잠금) + `FOR UPDATE` 직렬화 + UNIQUE 이중 방어, PAYMENT claim `SKIP LOCKED` + lease + 행마다 남은 lease 확인 + `@Version` | FR-I7·I8·I12, FR-R6·R7·R9 |
| Recoverability | at-least-once 재전송(상한 없음), lease 만료 회복, 커밋 결과 불명확 → 응답 안 함 → DUPLICATE 수렴 | FR-R2·R3·R7, FR-I10 |
| Reconciliation | 보류 사유 6종이 매입·대사 입력(D-ACQ-002), `original_known_at_receipt`, PAYMENT ACK 기록과 FINANCIAL Inbox를 eventId로 맞출 수 있음. 실제 차이 Case·MATCH는 ACQUIRING/RECON 후속 | FR-I4·I5, FR-D2..D7 |
| Auditability | FINANCIAL Inbox 원문 + SHA-256 + 서명 kid + 수신 시각, 보류 행(사유·근거 eventId), PAYMENT ACK 기록(consumer·outcome·original·claim 소유자·시각) | FR-I1(Inbox 열), FR-R1·R3(ACK 행) |
| Observability | PAYMENT gauge `paycore.financial.relay.{pending,pending.oldest_age_seconds,retrying,quarantined,undiscovered}` + counter `paycore.financial.relay{result}`, FINANCIAL counter `paycore.financial.payment-fact.inbox{result}`, 구조화 로그(eventId·분류만, 본문·키 없음). 경보 임계값은 LIVE Gate | FR-R4(격리 gauge), FR-R8(미발견 0), FR-H1(counter) |

## 7. 수락 기준

1. PaymentAuthorized·PaymentCancelled 사실이 중복·순서 뒤바뀜·응답 유실에도 FINANCIAL에 정확히 한 번 반영된다(effect key unique, Inbox + 투영 같은 로컬 TX) — FR-I1·I3·I4·I7·I9·I10, FR-R2·R3·R6·R7.
2. 원승인을 모르는 취소는 보류 상태(HELD + `ORIGINAL_UNKNOWN`)로 투영된다(D-ACQ-002 Hold 입력) — FR-D2, FR-I4, FR-H2.
3. 서명·시각 창·kid·봉투·크기·경로 검증이 저장 전에 끝나고 거절은 저장을 남기지 않는다; 키는 채널 전용이고 기본값이 없다 — FR-H3·H4·H6, FR-P4, golden.
4. PAYMENT Relay는 Outbox의 publish_status·published_at만 바꾸고(D-FFR-001, ACK와 같은 TX) 가맹점 Webhook fan-out 열·payload는 건드리지 않는다; 발견은 미전달 행만 본다 — FR-R1(publish_* 밖 열 불변), FR-R8·R8b, DatabaseAccountSeparationSpec(열 단위 권한 4열·나머지 1143).
8. 느린 FINANCIAL·다중 인스턴스에서도 같은 사실을 겹쳐 보내지 않는다(검토 M-1) — FR-R9.
5. BATCH_* metadata가 FINANCIAL main migration에 있고 Batch 재시작 smoke가 그 위에서 통과한다(D-ACQ-007) — MainMigrationLocationSpec, TechSmokeBatchRestartSpec.
6. 아키텍처 규칙: FINANCIAL `paymentfact` 모듈이 domain 순수성·계층·JPA/Spring Data/Controller 위치 규칙을 **비어 있지 않은 대상으로** 통과(allowEmpty false 전환) — financial ArchitectureSpec, payment ArchitectureSpec·OutboxLockReadScanSpec.
7. 두 앱 계약이 harness golden(독립 Python 생성)과 바이트 일치 — `harness/tests/test_payment_fact_relay_golden.py`(7건), FR-P1·P2·P3, PaymentFactRelayGoldenSpec.

## 8. 시험 목록

| 앱 | Spec | 내용(건수 — 실행 결과는 §11) |
|---|---|---|
| financial-batch | `paymentfact.domain.PaymentFactProjectionRuleSpec` | FR-D1..D9 투영 판정·값 계약(13) |
| financial-batch | `paymentfact.adapter.web.PaymentFactRelayGoldenSpec` | golden 서명 판정·봉투 해석(3) |
| financial-batch | `paymentfact.application.PaymentFactInboxDbSpec` | 실제 MySQL FR-I1..I11: 멱등·충돌·순서·합계·UNSUPPORTED·8중 동시 전달·같은 Payment 동시 사실·커밋 전 실패 rollback·커밋 뒤 응답 유실·append-only·동시 취소 합계 직렬화 FR-I12(16) |
| financial-batch | `paymentfact.adapter.web.PaymentFactInboxHttpSpec` | 실제 Tomcat 포트 FR-H1..H6: ACK 모양·DUPLICATE·HELD·401 6종·400/422·409·404/400/415/413/411·health(9) |
| financial-batch | `paymentfact.adapter.persistence.PaymentFactRepositorySurfaceSpec`(rev 0.2 m-2) | Spring Data 표면에 save·delete·flush 없음(1) |
| financial-batch | `MainMigrationLocationSpec`(기대값 갱신 — D-ACQ-007), `TechSmokeBatchRestartSpec`(V0.1 → main V1), `architecture.ArchitectureSpec`(allowEmpty true → false 9개) | |
| payment-api | `payment.adapter.integration.FinancialFactRelayContractSpec` | golden 봉투·서명 바이트, ACK 12건·상태 분류·statusCases 12건(실제 dispatcher·소켓), timeout(전송 예산 안 반환)→DUPLICATE, 설정·키 분리(Webhook 키 포함)·lease 검사 |
| payment-api | `payment.application.FinancialFactRelayDbSpec` | 실제 MySQL + 운영 confirm/취소 경로 + HTTP 대역 FR-R1..R9(R8b·R9 rev 0.2) |
| payment-api | `payment.adapter.persistence.DatabaseAccountSeparationSpec`(기대값 추가 — 새 표 2개 권한·ACK 1142) | |
| harness | `harness/tests/test_payment_fact_relay_golden.py` | golden 독립 생성·두 앱 파일 바이트 비교(7) |

Fixture: FINANCIAL `PaymentFactFixture`(Named — 취소는 원승인에서 파생, 의도된 불일치는 이름·`copy`로 드러냄), PAYMENT `CancelFixture`(기존 운영 경로). Fixture Monkey 미사용(핵심 규칙은 명시 Fixture — Test Fixture Policy). 시험 대역: `StubFinancialInbox`(JDK HttpServer, golden 서명 검증), `CommitThenFailTransactionManager`(커밋 뒤 예외), 실패 주입 Store 위임.

## 9. 위험·미결

### 9.1 알려진 한계·LIVE Gate 항목

- **L-1 FINANCIAL DB 계정 분리 없음**: 단일 계정(ALL)이라 Inbox·사실·보류 append-only를 DB가 1142로 강제하지 못한다. D-FFR-002에 따라 별도 작업 **FIN-DBACCOUNT-001**(ACQUIRING-001 전)이 계정·role·runtime 권한 migration을 만든다. → **해소 예정: FIN-DBACCOUNT-001 DONE 시**(docs/repository/FIN-DBACCOUNT-001-FINANCIAL-DB-ACCOUNTS.md). **→ 현행화(S3-DOC-001): FIN-DBACCOUNT-001 = DONE(`harness/archive/tasks-done-2026-11-22.json`)**
- **L-2 mTLS·내부망**: 채널 인증은 HMAC 서명 + 시각 창. 운영은 내부망 바인딩 + mTLS, ACK 응답 위조 방어(현재는 TLS 없는 loopback Demo — 응답 본문은 서명되지 않는다)는 LIVE Gate.
- **L-3 발견 질의 비용**: (rev 0.2 해소 — D-FFR-001) 발견·미발견 gauge는 `publish_status = PENDING` FINANCIAL_FACT 행만 본다. 비용 = 미전달 행(PENDING·QUARANTINED 전달 행 포함) 수. 격리 행이 쌓이면 그만큼 남는다(격리 gauge 경보).
- **L-4 순서**: 같은 Payment의 사실도 재시도가 끼면 순서가 바뀔 수 있다(취소가 먼저 도착 → ORIGINAL_UNKNOWN 보류). Relay가 앞 사실 실패 시 뒤 사실을 막지는 않는다(보류가 정의된 처리 — D-ACQ-002).
- **L-5 격리·UNSUPPORTED 재처리 도구 없음**: QUARANTINED 행 재큐·FINANCIAL UNSUPPORTED 원문 재처리는 후속(운영자 승인 절차 필요).
- **L-6 보류 해제 없음**: 늦게 도착한 원승인도 이 작업에서는 ORIGINAL_UNKNOWN 보류를 풀지 않는다. D-FFR-003 자동 해제(MATCH → append-only 해제 기록)는 ACQUIRING-001이 구현한다.
- **L-7 운영값**: 시각 창 300초·본문 64 KiB·batch 50·lease 30초·backoff 1초~1분·jitter 0.2·경보 임계값은 Demo. BATCH_* 보존·정리 정책은 운영 정책(D-ACQ-007).
- **L-9 인스턴스 간 시계 차이**: R-6의 lease 판단은 각 인스턴스 시계로 계산한 claim_until을 쓴다. 시계 차이가 여유 1초를 넘으면 겹친 전송이 가능하다(그래도 FINANCIAL Inbox가 멱등 — 중복 효과 없음). 운영 NTP 허용 오차·여유 값은 LIVE Gate.
- **L-10 처리량**: 느린 FINANCIAL에서는 회차당 (lease − 예산 − 1초)/지연 행만 보낸다. 운영 lease·배치·주기 값은 부하 측정 후(LIVE Gate).
- **L-11 진행 보장 없음(재검토 m-A, Coordinator 수용)**: 기동 검사(lease > 예산 + 1초)는 discover·claim TX 시간을 포함하지 않는다. lease가 예산에 너무 가까우면 회차마다 claim → 해제만 반복할 수 있다(금융효과 없음). 한 행도 못 보낸 회차는 WARN `result=STARVED`와 대기 연령 gauge로 드러나며, 운영값은 lease ≫ 예산(LIVE Gate). 겹친 전송 금지는 보내는 쪽 기준이다 — 기한 초과로 취소한 요청이 이미 FINANCIAL에 도착했다면 수신 쪽 처리는 겹칠 수 있고 Inbox 멱등으로 DUPLICATE 수렴한다(재검토 n-B). FR-R9의 RED 판별 여유는 약 0.5초다(재검토 n-D) — Coordinator RED 실행 1회에서 실패 확인.
- **L-8 기존 Demo volume**: payment V32·V33, financial V1·V2는 다음 기동 때 Flyway가 적용한다(paycore_financial은 단일 계정 ALL이라 별도 init 재실행 불필요). financial-batch는 이제 HTTP 포트·주소·키가 없으면 기동하지 않는다(.env.example 참조).

## 10. Coordinator 질문 — rev 0.2: 모두 답변됨(D-FFR-001~004, §2). 아래는 rev 0.1 원문 기록

| ID | 질문 | 권장안 | 대안 | 영향·막히는 작업 |
|---|---|---|---|---|
| Q-FFR-01 | PAYMENT runtime에 `outbox_event.publish_status`·`published_at` **열 단위 UPDATE**를 허용해 Relay가 PUBLISHED를 표시해도 되는가(D-WH-009 확장) | 허용(열 단위 2개, PENDING→PUBLISHED만 — V1이 이 용도로 예약한 열, 발견 비용을 미전달 행 수로 줄임) | A) 현행 유지(별도 표 anti-join) + 보존·archival로 규모 관리 B) 업무 TX가 Outbox와 같은 TX에 전달 행 INSERT(승인·취소 TX 코드 변경) | 지금 막힘 없음(Demo). 운영 규모 LIVE Gate. 허용 시 D-WH-009 기대값 2개(1143→허용) 변경 |
| Q-FFR-02 | FINANCIAL DB 계정 분리(migration·runtime role·표 단위 권한)를 별도 작업으로 할지, 이 작업 허용 경로를 넓힐지 | 별도 작업 FIN-DBACCOUNT-001(init 스크립트·compose·.env·권한 migration·계정 시험), ACQUIRING-001 전 | 이 작업 범위 확장(docker/mysql/init/**, compose.yaml 허용) | append-only의 DB 강제(1142). 막히는 것: LIVE, 권장상 ACQUIRING-001 |
| Q-FFR-03 | 보류 정책: (a) ORIGINAL_UNKNOWN 외 정합성 보류 5종(I-5)을 유지할지 (b) 늦게 도착한 원승인으로 ORIGINAL_UNKNOWN을 해제하는 규칙 | (a) 유지 (b) 원승인 도착 + MATCH 검사(신원·통화·승인액·합계) 통과 시 append-only 해제 기록으로 자동 해제, 불일치는 운영자 검토 — ACQUIRING-001에서 구현 | (b') 해제는 항상 운영자 승인 | 매입 상계 입력. 막히는 것: ACQUIRING-001 |
| Q-FFR-04 | 지원하지 않는 schema 사실을 FINANCIAL이 UNSUPPORTED로 보존·ACK하는 현재 처리(I-3)를 유지할지 | 유지(원문 FINANCIAL 보존, 양쪽 error 경보) + 재처리 도구 후속 | PAYMENT 쪽 재시도·격리(FINANCIAL 배포 뒤 자동 전달) | 스키마 진화 배포 순서. 지금 막힘 없음 |

## 11. 검증 기록(Implementer 실행 — Verifier 재검증 대상)

실행 환경: WSL Ubuntu(7.8 GB), JDK 25, Gradle wrapper, Docker Desktop(Testcontainers mysql:8.4.11). 운영 DB·키·endpoint 미사용(Demo golden 키만).

| 명령 | 결과 | 비고 |
|---|---|---|
| `./gradlew --console=plain :apps:financial-batch:test` (2026-11-04T16:38Z UTC 시작) | exit 0, **69건** 통과(실패·skip 0) | 이전 27건 → 69건(+42): ProjectionRule 13, Golden 3, InboxDb 16, InboxHttp 9, MainMigrationLocation 2→3 |
| `./gradlew :apps:payment-api:test --tests` FinancialFactRelayContractSpec·FinancialFactRelayDbSpec·DatabaseAccountSeparationSpec·`architecture.*`·PaymentApiContextSmokeSpec·MainConfigurationSpec·CheckoutWindowCheckMigrationStrategySpec | 125건 중 실패 4 → CheckoutWindowCheckMigrationStrategySpec 버전 목록 기대값(V32·V33 추가)만 실패 — 기대값에 V32·V33을 **추가**한 뒤 이 spec + OldVolumeStartSpec 재실행 exit 0(6 + 4건) | 새 시험 +32: ContractSpec 18, RelayDbSpec 14. DatabaseAccountSeparationSpec 17건 통과(권한 기대값 추가) |
| `./gradlew :apps:payment-api:test`(모듈 전체) | **미완료** — 호스트 메모리 부족으로 실행이 중단됨(시험 실패 아님). 재실행하지 않았다 | Verifier 전체 gate에서 확인 필요(마이그레이션 버전 목록을 고정한 다른 spec이 있는지 포함) |
| `./gradlew -q :apps:financial-batch:dependencies --configuration runtimeClasspath` | exit 0 | Boot 4.1.1 BOM: starter webmvc·data-jpa·actuator 4.1.1, hibernate-core 7.4.5.Final, spring-batch-core 6.0.5(새 override 없음) |
| `python3 -B -m unittest discover -s harness/tests` | exit 0, Ran 170, OK | 163 + golden 7 = 170(HARNESS-SELFTEST 하한 163 → 170, module-registry 정확 일치 검사 통과) |

harness 하한: GRADLE-CHECK `minTests`·TEST-SUMMARY 인자 3493 → 3567(= 3493 + financial 42 + payment 32). HARNESS-SELFTEST 163 → 170.

### 11.1 RED·변형(mutation) 확인

시험은 구현 골격과 함께 작성했으므로 순수한 선행 RED 증거 대신 아래 변형으로 시험이 결함을 잡는지 확인했다(각 변형 뒤 원본 복원 — `cmp`·git status로 확인).

| ID | 변형 | 결과 |
|---|---|---|
| M1 | Inbox effect key 조회 무력화 | 잡힘 — FR-I3 실패 |
| M2 | ORIGINAL_UNKNOWN 보류 제거 | 잡힘 — FR-D2, FR-I4 2건, FR-I8 실패 |
| M3 | eventId 충돌 TX를 rollback 대신 commit | 잡힘 — FR-I2(직렬화 행이 남음) |
| M4 | 서명 시각 창 무시 | 잡힘 — golden verifyCases, FR-H3 |
| M5 | 직렬화 행 `FOR UPDATE` 제거 | **살아남음(동등 변형)** — 기존 행 IODKU가 이미 배타 행 잠금을 잡는다. 그래서 FR-I12(동시 취소 합계)를 추가했다 |
| M5b | IODKU → `INSERT IGNORE`(공유 잠금) + `FOR UPDATE` 제거 | 잡힘 — FR-I12(두 취소 모두 APPLIED) |
| M5c | `INSERT IGNORE` + `FOR UPDATE` 유지 | FR-I7 실패(8중 동시 전달에서 S→X 승격 교착이 재시도 한도 초과) — IODKU 선택 근거 |
| P1 | 422를 재시도로 분류 | 잡힘 — FR-P3 |
| P2 | ACK eventId 일치 검사 제거 | 잡힘 — FR-P2 `other-event-id` |
| P3 | claim lease 조건 제거 | 잡힘 — FR-R7 |

## 12. rev 0.2 반영 기록(사용자 결정 D-FFR-001~004 + 독립 리뷰 REVIEW_FAIL)

| 항목 | 처리 |
|---|---|
| D-FFR-001 | 충돌 확인: main 코드에서 `publish_status`는 Outbox Entity INSERT의 PENDING 기록뿐이고 가맹점 fan-out·조회·다른 topic이 읽거나 쓰지 않는다(기존 시험 MerchantNotificationFanoutDbSpec "publish_status stays PENDING"은 fan-out이 이 열을 바꾸지 않는다는 단언이라 그대로 유효). V34 열 권한, ACK TX의 PK UPDATE, 발견·미발견 gauge 조건 `publish_status = 'PENDING'`, 색인 `(event_category, publish_status, id)` |
| D-FFR-002~004 | §2·§9 L-1·L-6 갱신(구현 변경 없음) |
| M-1 | R-6(행마다 남은 lease 확인 + 남은 행 claim 해제 + dispatcher 전송 예산 강제). 시험 FR-R9 |
| m-1 | 계약 코드 있는 4xx만 격리(golden `rejectCodes`·`statusCases` 추가, harness 시험 이름 변경 — 수 7 그대로) |
| m-2 | FINANCIAL Spring Data 4개를 `Repository` 상속으로 축소 + PaymentFactRepositorySurfaceSpec. 보류 표는 원래 Spring Data 인터페이스가 없다. PAYMENT relay 표도 Spring Data 없음 |
| m-3 | claim 색인 `(status, outbox_id)` = `ORDER BY outbox_id` |
| n-1·n-2·n-5·n-7 | V33 주석 정정, HTTP/1.1 고정, Webhook 시험 키 분리 검사(`whsec_` UTF-8 바이트와 base64url 디코드 둘 다), FR-I12 sleep → latch(둘째 TX가 1초 안에 끝나지 않음 = 잠금 대기 확인) |

### 12.1 검증 상태(rev 0.2 — Implementer 미실행)

rev 0.2 코드는 **컴파일·시험을 실행하지 못했다**. 이 세션에서 Gradle 실행이 호스트 메모리 부족으로 두 번 강제 중단됐고(시험 실패 아님 — 두 번째는 jOOQ codegen 단계), 도구 지침상 Implementer가 다시 띄우지 않았다.
따라서 아래 RED·GREEN은 **미확인**이며 Verifier/Coordinator 실행이 필요하다.

1. RED(M-1): `FinancialFactRelayService`의 행별 lease 확인 블록(`if (now().plus(dispatcher.sendBudget).plus(LEASE_MARGIN) > claimUntil) { … break }`)을 임시로 제거하고
   `./gradlew --console=plain :apps:payment-api:test --tests 'com.paycore.payment.payment.application.FinancialFactRelayDbSpec'` → FR-R9 실패 기대(8번째 행이 두 번 수신, deferred = 0).
2. GREEN: 블록을 되돌리고 같은 명령 + `FinancialFactRelayContractSpec`·`DatabaseAccountSeparationSpec`·`OutboxNotificationMigrationSpec`·`PaymentApiContextSmokeSpec`·
   `CheckoutWindowCheckMigrationStrategySpec`·`OldVolumeStartSpec`·`com.paycore.payment.architecture.*`.
3. `./gradlew --console=plain :apps:financial-batch:test` — FR-I12(latch)·PaymentFactRepositorySurfaceSpec 포함.
4. `python3 -B -m unittest discover -s harness/tests` (Implementer 실행 결과는 보고서 참조).

harness 하한(rev 0.2, 정적 계산 — 실행 확인 전): GRADLE-CHECK·TEST-SUMMARY 3567 → 3571(+4 = payment FR-R8b 1 + FR-R9 1 + ContractSpec 상태 분류 Then 1→2 로 +1, financial PaymentFactRepositorySurfaceSpec 1). HARNESS-SELFTEST 170 그대로(golden 시험 이름만 변경).
