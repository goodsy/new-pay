# CreatePayment + Order Active Check 구현 계약 v0.1

2026-10-03 KST / 상태: 기술 계약 초안, NOT READY / 기존 업무 정책은 확정, 기술 후보·잔여 입력 검토 필요

## 1. Scope

PAYMENT에서 결제 의도와 주문 활성 점유를 생성한다. 최초 지원 수단은 확정된 국내 카드 범위다. 실제 출시 통화·상품 범위와 Demo fixture는 구별한다. 기관 인증/승인·매입·정산·송금은 실행하지 않는다. MASTER를 같은 DB TX로 수정하지 않고 로컬 버전 Snapshot을 사용한다.

## 2. Roots와 불변조건

기술 후보:

- Payment Root: paymentId/key, MID/Site/orderId, requested Money, method, lifecycle=REQUESTED, 초기 금융 요약, version.
- OrderPaymentControl Root: OrderKey, 고정 주문 입력 fingerprint, currentPaymentId, generation/version, 성공 이력/차단 근거. 가맹점 상품 주문 전체를 PG Aggregate로 가져오지 않는다.
- IdempotencyRequest 기술 record: key namespace, canonical payload/hash version, result reference, 요청 처리 기록.
- Outbox 기술 record: 채택한 CreatePayment integration event 계약. 금융 AUTHORIZATION 이벤트와 구분한다.

주문 control과 Payment는 다른 Root이나 같은 PAYMENT DB의 필요한 로컬 TX로 원자 저장한다. PaymentTransaction/AuthorizationAttempt는 생성하지 않는다. 식별자/key는 서버가 생성한다.

규칙: amount>0, amount/currency/Site/MID/order intent 불변, OrderKey별 활성/성공 점유 하나. 재요청은 존재한 요청 결과를 조회하며 원칙적으로 현재 권한을 다시 확인한다. 새 시도는 무효과 FAILED/안전한 EXPIRED 근거가 검증되고 과거 승인 성공이 없어야 한다.

## 3. Command·Result·오류

CreatePaymentCommand 후보:

- 신뢰된 CommandContext: authenticatedActor/MID scope/environment, idempotencyKey, requestId/correlationId, 평가 시각(서버 clock).
- merchantAccountId, merchantSiteId, merchantOrderId, Money(amount,currency), paymentMethod, 계약된 주문 입력.
- MID/environment를 요청 body에서 받아도 신뢰된 인증 범위와 일치하는지 검증한다. 지원 여부·소유권·Site 관계·계약 준비·Snapshot freshness를 검사한다.

CreatePaymentResult 후보: paymentId, paymentKey, lifecycleAtCreation, createdAt, operationId. 재요청은 저장된 생성 결과 참조를 반환하고 현재 상태는 별도 GetPayment/GetOperation으로 조회한다. 기관 성공을 뜻하지 않는다. 현재 상태를 함께 반환한다면 original result와 currentStatus를 계약에서 명시적으로 구별한다.

오류 후보: InvalidAmount/UnsupportedCurrencyOrMethod, NotFoundOrForbidden, IdempotencyConflict, OrderPayloadConflict, EligibilityNotReady, OrderBlocked, ResultPending, RetryableLocalConflict. HTTP status/code·응답 보안 계약은 별도 고정한다. timeout 자체를 Payment FAILED/UNKNOWN으로 새 상태 전이시키지 않는다.

## 4. 주문 재사용 판정

| 기존 상태/근거 | 같은 OrderKey·같은 고정 입력 | 다른 고정 입력 |
|---|---|---|
| 없음 | 새 REQUESTED와 점유 생성 | 해당 없음 |
| REQUESTED | 기존 생성 결과 재사용 | 충돌 |
| AUTHORIZING/UNKNOWN | 기존 생성/operation 참조, 새 Payment 없음 | 충돌 |
| AUTHORIZED/과거 승인 성공 | 기존 성공 Payment 참조, 새 Payment 없음 | 충돌/새 주문 요구 |
| FAILED + 무효과 확정 | 새 요청키라면 새 Payment, 같은 요청키는 과거 결과 | 새 주문 요구 |
| EXPIRED + 안전한 종료 | 새 요청키라면 새 Payment, 같은 요청키는 과거 결과 | 새 주문 요구 |
| 종결 근거 없음/상충/incident hold | 새 Payment 금지, 확인/복구 | 거절 |

같은 멱등키는 현재 Payment가 FAILED여도 자동 새 시도를 만들지 않는다. 과거 CreatePayment 성공을 재사용한다. 새 고객 시도는 새 키와 새 Payment다. 전액 반환 후에도 과거 승인 성공으로 주문을 재개하지 않는다.

## 5. TX·경합 알고리즘 후보

1. 인증/shape 검증 및 로컬 eligibility Snapshot 확인. TX 밖의 사전 검사는 최종 원자 점유를 대신하지 않는다.
2. 같은 요청키 record를 경쟁 안전하게 예약/조회한다. 같은 키 다른 payload 거절, 완료면 현재 권한 확인 후 과거 result 반환.
3. OrderKey control 행을 Unique insert/upsert 등 검증한 방식으로 확보하고 잠근다. 최초 행이 없을 때의 경쟁도 처리한다. 단순 Payment 목록 SELECT 후 INSERT는 금지.
4. 고정 fingerprint·current Payment·generation·성공/무효과 근거를 검사한다. REQUESTED 포함 점유는 기존 결과에 연결하고 다른 입력은 충돌이다.
5. 필요할 때만 새 Payment 생성+control 점유·generation 갱신+현재 요청 성공 결과+Outbox를 같은 TX로 커밋한다. 기존 Payment 재사용 경로도 현재 요청키가 같은 결과에 연결되게 저장하고 생성 Outbox를 반복 만들지 않는다.
6. response는 commit 뒤 반환한다. rollback이면 부분 점유/Payment/성공결과/event가 남지 않는다.

같은 슬롯을 변경하는 승인 시작·만료·FAILED 종결은 control→Payment 순서의 동일 잠금/조건부 쓰기를 따른다. 이미 점유한 요청키 뒤에 추가 역순 잠금을 만들지 않도록 전체 Use Case의 순서를 검토한다. 교착 재시도는 확인된 로컬 rollback에 한정하며 동일 의도로 수행한다. 오래된 결과의 해제는 currentPaymentId+generation 매칭을 요구하여 새 시도 점유를 풀지 못한다.

commit 결과 불명확/응답 유실은 새 키를 만들지 않고 동일 키 GetOperation/재요청으로 확인한다. Write 가능한 원본 DB에서 요청/업무키로 확인한다. CreatePayment는 외부 금융 효과가 없지만 존재 여부를 모르는 상태에서 새 Payment를 임의 생성하지 않는다.

## 6. DB 논리 제약 후보

| 테이블 | 제약 |
|---|---|
| payment | PK paymentId, UNIQUE paymentKey, version NOT NULL, 양수 요청 금액/명시 currency, INDEX(environment,MID,orderId) |
| order_payment_control | UNIQUE/PK(environment,MID,orderId), version/generation NOT NULL, currentPaymentId 참조 |
| idempotency_request | UNIQUE(environment,authenticated MID,operation,key), fingerprint/result ref |
| outbox | eventId UNIQUE, 업무 이벤트의 안정 effect key, schemaVersion/producer/aggregate 근거 |

Payment의 MID+orderId UNIQUE는 금지한다. control의 OrderKey UNIQUE는 이력 1:N과 양립한다. Cross-DB FK는 없다. 생성/점유 순서의 FK·nullable 설계는 실제 MySQL에서 검증한다. NULL 포함 UNIQUE나 Redis lock만으로 활성 하나를 보장했다고 보지 않는다.

키 길이·대소문자/Unicode/공백 정규화·DB collation, 금액 precision/scale·지원 통화의 minor unit, 멱등키 보존·retention는 READY 전에 계약한다. SQL 최적화는 구현 세부여도 키 동일성/금액 범위는 금융 계약이다.

## 7. 필수 TDD

CP-01 정상 생성: Payment REQUESTED 하나, control 점유 하나, 요청 성공 결과 하나, 생성 Outbox 하나, 승인 금융 사실/기관 호출 0.

CP-02 0/음수·미지원 금액/통화/수단: 거절, 부분 record 없음.

CP-03 같은 키 같은 payload 동시: 한 Payment/result/event. 같은 키 다른 금액/Site/통화: 충돌.

CP-04 다른 키 같은 주문 같은 payload 동시: 같은 활성 Payment로 수렴, 요청별 결과 연결. 다른 payload: 하나만 유효/충돌, 이중 생성 없음.

CP-05 REQUESTED/AUTHORIZING/UNKNOWN/AUTHORIZED: 새 Payment 없음, 기존 참조. 과거 승인 후 전액 반환도 차단 유지.

CP-06 FAILED/EXPIRED: 근거 있는 새 키만 새 시도, 같은 키는 원 결과. 근거 없는 종료/timeout/NOT_FOUND는 거절.

CP-07 다른 MID/환경·계약 Scope 밖 Site·만료 Snapshot·키 폐기: 거절, 민감정보 미노출.

CP-08 저장 각 지점 실패: Payment/control/요청 성공/Outbox 원자 rollback. commit 후 응답 유실: 같은 키로 기존 생성 확인.

CP-09 generation이 바뀐 뒤 이전 Attempt 결과/만료 처리: 새 점유 해제 금지. 늦은 상충 성공은 incident block/복구 절차를 촉발하고 새 생성 허용 안 함.

CP-10 저장 ID/key 충돌·DB deadlock·부모 control 최초 insert 경쟁: 검증한 재개/거절, 금융 의미 유지. 임의 key 충돌이 사용자 payload 충돌로 오해되지 않게 분리.

CP-11 Domain/Application의 기술 누수 및 PAYMENT 밖 접근 금지.

CP-12 고정 input hash 정규화/DB collation 사례: API 동일성과 DB 키 동일성이 일치.

Domain/Port 시험 뒤 실제 DB/migration·다중 connection 경쟁·장애를 검증한다. Mock 단독은 CP-03/04/08의 증거가 아니다. Fixture는 운영 정책/기관 인증을 대신하지 않는다.

## 8. READY 전 체크

- 실제 저장소·모듈·JDK/DB/version·명령·허용 경로/테스트 보고서 위치.
- 고정 주문 입력/요청 fingerprint schema와 order/key 정규화·DB collation.
- CP result original/current 조회 계약, 오류/HTTP 코드, 금액·통화 지원 fixture와 실제 출시 계약의 구분.
- OrderPaymentControl/행 잠금/첫 insert 경쟁/해제 근거와 idempotency record 물리 설계.
- PaymentCreated 등의 실제 이벤트명·payload·version·effect key 또는 통합 미적용의 승인된 범위.
- readiness Snapshot·유효기간/테스트 fixture·불명확 결과 확인 절차.

위 항목이 고정되기 전 NOT READY다. 금융 정책을 더 추측하라는 요청이 아니라 해당 기능의 실행 가능한 기술 계약을 검토하는 단계다.
