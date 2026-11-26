# PAY 업무 규칙 대장 — 결제 생성·주문 키·결제시도·승인·결제창·UNKNOWN·결제 조회 (초안 rev 0.1)

- 작성 2026-11-25, RULE-GOV-001 2단계(도메인별 대장 초안 추출). 형식은 `docs/rules/README.md`. 이 초안은 사용자 확인 전이다 — `제안` 행은 Coordinator 적용이며 확정이 아니다.
- 원천: `docs/DECISIONS.md`(D-AUTH-*, D-AUTH1-*, D-AUTHN-*, D-CP-*, D-CPA-*, D-CPC-*, D-CPD-*, D-CPQ-*, D-CHECKOUT-*, D-INST-CODE-*), `harness/questions.json`, baseline, 명세, `apps/payment-api`·`apps/institution-simulator` 코드·시험.
- 제외(업무 규칙 아님): HTTP 경로·코드 이름 선택, 화면 배치(D-CHECKOUT-UI-001 시안), 실행 단위·포트 배치(D-AUTHN-004 실행 위치, D-AUTHN-006), 작업 순서(D-CPC-006), 잠금 순서·JPA/jOOQ 선택, ID 접두 형식 등 가역 관례.

약어(근거·적용 명세 열):

| 약어 | 문서 |
|---|---|
| 시도 | `docs/baseline/PayCore-결제시도-규칙-권장안.md`(머리말 "사용자 확정") |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md`(머리말 "사용자 확정", 4·5번) |
| 범위 | `docs/baseline/PayCore-최초구현범위-권장안.md`(§1~§3만 D-AUTH-001로 채택, §4·§5는 미채택) |
| 초안 | `docs/baseline/PAYCORE-CREATE-PAYMENT-IMPLEMENTATION-CONTRACT-v0.1.md`(기술 초안, "기존 업무 정책은 확정") |
| A04·A06·INV | `docs/baseline/architecture/04-…`, `06-…`, `10-architecture-invariants.md` |
| 05-security | `docs/baseline/architecture/05-security-architecture.md` |
| CPC·CPA·CPQ | `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md`·`CP-API-001-CREATE-PAYMENT-API.md`·`CP-QUERY-001-PAYMENT-QUERY.md` |
| AC1·AU·AN | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`·`AUTH-001-AUTHORIZATION.md`·`AUTHN-001-PAYMENT-WINDOW.md` |
| CR·IC | `docs/repository/CHECKOUT-REACT-001.md`·`INST-CODE-001-INSTITUTION-CODE-FORMAT.md` |

검증 열의 시험 파일은 `apps/payment-api/src/test/kotlin/com/paycore/payment/**`(표시 `(sim)`은 `apps/institution-simulator/src/test/**`). 요약은 시험의 Given/Then 문장을 줄인 것이다. 시험 이름·주석에 BR-ID는 아직 없다(RULE-GOV-001 6단계).

## 1. 주문 키·요청 식별자·가맹점 API 인증

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-001 | 주문 키는 (environment, MID, merchantOrderId)이며 셋 중 하나라도 다르면 다른 주문이다(주문은 MID 범위) | 확정 | 사용자 | D-CPD-001, 시도 §1 | CPC §2 | OrderKeySpec::세 부분 중 하나만 달라도 다른 주문; CreatePaymentServiceSpec::다른 MID·환경의 같은 주문 ID·키는 독립 주문; CreatePaymentApiSpec::같은 MID TEST·LIVE Credential은 별개 주문 |
| BR-PAY-002 | environment는 TEST·LIVE 두 값뿐이고 Simulator/실기관 구분은 environment가 아니라 기관 Route 정보로 관리한다 | 확정 | 사용자 | D-CPD-001 | CPC §1, AC1 §9.4 | OrderKeySpec::environment는 정확히 TEST·LIVE |
| BR-PAY-003 | TEST 거래는 실기관으로 전송하지 않으며 현재 어떤 환경도 실기관(REAL)으로 나가지 않는다 — REAL backend 설정은 기동을 거부한다 | 확정 | 사용자 | D-CPD-001, D-AUTHN-004, 허용 §5.5 | AC1 §9.4, AU §5.6, AN §13.6 | ConfiguredInstitutionRouteRegistrySpec::REAL·모르는 backend·LIVE simulator 거부; AuthenticationBackendIsolationSpec::LIVE는 backend 없음·REAL 기동 거부 |
| BR-PAY-004 | environment·MID는 인증 Credential로 정하고 요청 body로 바꾸지 않으며, body merchantAccountId가 인증 MID와 다르면 거절한다 | 확정 | 기준 | 초안 §3, INV-14 | CPC §2·§9 | ApiSecuritySpec::body MID≠인증 MID는 403 MERCHANT_SCOPE_MISMATCH·그 MID 정보 비노출; CreatePaymentApiSpec::environment·MID는 Credential에서 |
| BR-PAY-005 | merchantOrderId·멱등키는 trim·대소문자 변환·정규화 없이 입력 그대로 비교하고, DB 업무 키 컬럼은 후행 공백까지 구분하는 NO PAD binary collation을 쓴다 | 확정 | 사용자 | D-CP-001 | CPC §3.1·§12 | MerchantRequestIdFormatSpec::대소문자만 다르면 다른 값; CreatePaymentServiceSpec::대소문자만 다른 키는 다른 키; CreatePaymentApiSpec::CP-12 API 동일성 = DB 키 동일성; PaymentSchemaSpec::업무 키 컬럼 utf8mb4_0900_bin |
| BR-PAY-006 | merchantOrderId·멱등키(생성·confirm 공통)는 1~64자 `[A-Za-z0-9-_.:]`만 허용하고 공백·비ASCII·제어문자는 거절한다 | 확정 | 사용자 | D-CPC-002 | CPC §3, AU §4.1 | MerchantRequestIdFormatSpec::D-CPC-002 안 값 허용·밖 값 거절; CreatePaymentRequestParserSpec::Idempotency-Key는 D-CPC-002 형식 하나 |
| BR-PAY-007 | 가맹점 공개 API 인증은 (MID, 환경)별 secret API key(pc_test_sk_/pc_live_sk_ + keyId + 256bit 난수)를 Authorization: Bearer로 보내고, 저장은 keyId+SHA-256만, 교체 중 (MID, 환경)당 활성 키는 최대 2개, v1 권한 범위는 공개 가맹점 API 전체다 | 확정 | 사용자 | D-CPA-001 | CPA §5 | ApiSecuritySpec::없음·형식 오류·다른 환경 접두·폐기·만료·lease 만료 키는 모두 401; DemoCredentialCommandSpec::keyId + SHA-256만 저장 |
| BR-PAY-008 | API key는 MASTER가 발급·소유하고 PAYMENT는 전파된 로컬 projection으로 검증하며, 폐기는 우선 전파하고 무기한 cache를 쓰지 않는다. 발급·전파(MASTER-CREDENTIAL-001) 전까지 운영 인증은 fail-closed다 | 확정 | 사용자 | D-CPA-001 | CPA §5 | ApiSecuritySpec::Credential 폐기 뒤 재생 401; ApiSecuritySpec::runtime 계정은 credential 행 수정·삭제 불가 |

## 2. 금액·통화

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-009 | 금액은 통화 minor unit 정수(부동소수 금지)이고 1 이상·BIGINT 이하만 계약에서 강제하며, 건당·일 등 업무 상한은 코드에 두지 않고 기준정보 한도 정책으로 관리한다 | 확정 | 사용자 | D-CPC-005 | CPC §4 | MoneySpec::1 최소·0과 음수 거절·Long.MAX 허용·부동소수 필드 없음; PaymentSchemaSpec::amount_minor 1 미만 거절 |
| BR-PAY-010 | amount는 JSON 정수이며 서버는 정확한 정수 파서로 검증한다(소수점·지수·문자열·+·선행 0·Long 초과 거절). 2^53-1 초과 금액은 가맹점이 정확한 정수 직렬화를 써야 함을 API 문서에 명시한다 | 확정 | 사용자 | D-CPC-009 | CPC §3.1 | CreatePaymentRequestParserSpec::정확한 양의 JSON 정수가 아닌 amount 거절; ::Long.MAX까지 부동소수 없이 정확 |
| BR-PAY-011 | 통화는 ISO 4217 3자 대문자를 그대로 받고, 허용 통화와 통화별 minor unit은 코드가 아니라 MID·기관 계약 기준정보(Snapshot)로 활성화한다 | 확정 | 사용자 | D-CP-002 | CPC §4·§13 | MoneySpec::코드 허용 목록 없음; CreatePaymentApiSpec::형식은 맞으나 미활성 통화는 422 CURRENCY_NOT_ENABLED; EntitlementEligibilitySpec::USD 미활성 거절 |
| BR-PAY-012 | 초기 구현·검증 통화 fixture는 KRW(0자리)·USD(2자리)다 | Demo값 | 사용자 | D-CP-002 | CPC §4 | MoneySpec::KRW 0·USD 2 fixture; CheckoutWebSpec::USD 1050 → 10.50 표시 |
| BR-PAY-013 | 생성은 거래통화를 그대로 보존만 한다. 외화 승인 거래의 가맹점 정산 통화·환율은 정하지 않았다 | 미결(Q-SETTLE-CURRENCY) | 사용자 | D-CP-002, Q-SETTLE-CURRENCY | CPC §4 | 없음 |
| BR-PAY-014 | 승인은 KRW만: Route capability의 지원 통화(Demo [KRW]) 밖 통화 Payment의 confirm은 422 PAYMENT_NOT_ELIGIBLE로 저장하고 기관에 보내지 않는다 | 확정 | 사용자 | D-AUTH1-002, D-AUTH-001 | AU §4.3-9 | ConfirmStartSpec::KRW 외 Payment는 PAYMENT_NOT_ELIGIBLE 저장·전송 없음 |
| BR-PAY-015 | 승인된·진행 중인 주문의 금액·통화·MID·Site 결제 근거는 바꾸지 않고, 같은 주문 재시도도 원 금액·통화·Scope를 유지한다. 바뀐 주문은 새 merchantOrderId를 쓴다 | 확정 | 기준 | 시도 §3 | CPC §9 | OrderPaymentControlSpec::다른 금액·통화·Site는 활성 중에도 안전 종료 뒤에도 충돌 |

## 3. 결제 생성·주문 활성 시도

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-016 | 주문 하나에 Payment 이력은 1:N이며 고객의 새 시도는 새 paymentId·paymentKey를 가진 새 Payment다 | 확정 | 기준 | 시도 §1 | CPC §12 | OrderPaymentControlSpec::확정 거절 뒤 새 키 → generation 2·거절 이력 보존 |
| BR-PAY-017 | 주문당 활성 Payment는 REQUESTED를 포함해 하나이며, 주문 단위 영속 슬롯(OrderPaymentControl)으로 점유·해제·승인 시작을 직렬화한다 | 확정 | 기준 | 시도 §1·§5 | CPC §11 | OrderPaymentControlSpec::같은 내용 다른 키는 기존 활성으로 수렴·두 번째 점유 거절; CreatePaymentConcurrencySpec::CP-04 같은 새 주문 다른 키 동시 → 한 Payment |
| BR-PAY-018 | 같은 주문의 고정 입력은 merchantSiteId·amount·currency이며 결제수단·주문명 등 표시 정보는 비교하지 않고 최초 값을 저장한다 | 확정 | 사용자 | D-CPC-001 | CPC §6 | OrderIntentSpec::고정 입력은 site와 money뿐; CreatePaymentServiceSpec::CP-04 표시 이름만 다르면 재사용 |
| BR-PAY-019 | 다른 키·같은 주문·같은 고정 입력이고 현재 Payment가 REQUESTED·AUTHORIZING·UNKNOWN이면 새 Payment 없이 기존 Payment를 돌려준다 | 확정 | 기준 | 시도 §1·§5, CLAUDE.md 핵심 규칙 | CPC §9 | OrderPaymentDecisionTableSpec::REQUESTED·AUTHORIZING·UNKNOWN 같은 입력 재사용; CreatePaymentApiSpec::CP-05 활성 Payment 재사용 200 |
| BR-PAY-020 | 같은 주문에 다른 고정 입력이 오면 현재 상태와 무관하게 충돌로 거절하고 새 Payment를 만들지 않는다 | 확정 | 기준 | 시도 §1·§6-8, D-CPC-001 | CPC §9 | OrderPaymentDecisionTableSpec::다른 고정 입력은 충돌; CreatePaymentConcurrencySpec::CP-04 다른 금액 동시 → Payment 하나·나머지 충돌 |
| BR-PAY-021 | 현재 Payment가 AUTHORIZING·UNKNOWN·AUTHORIZED이면 같은 주문에 새 Payment를 만들지 않는다 | 확정 | 기준 | 시도 §2, CLAUDE.md 핵심 규칙 | CPC §9 | OrderPaymentControlSpec::UNKNOWN 중 새 카드·새 키 → 새 Payment 없음; CreatePaymentServiceSpec::CP-05 진행 중·승인 |
| BR-PAY-022 | 승인 성공 이력이 있는 주문은 전액 취소·환불 뒤에도 새 Payment·재승인을 허용하지 않고(기존 승인 Payment 참조), 재구매는 새 merchantOrderId로 한다 | 확정 | 기준 | 시도 §2·§3, CLAUDE.md 핵심 규칙 | CPC §9 | OrderPaymentControlSpec::승인 뒤 전액 취소·환불 주문 차단 유지·새 주문 ID 허용; CreatePaymentServiceSpec::승인(전액 환불 포함) → AUTHORIZED_NEW_ORDER_REQUIRED |
| BR-PAY-023 | 같은 주문의 새 Payment(새 키)는 FAILED면 기관 확정 거절·전송 전 실패 확정(미전송)·기관 무효과 종결 확인, EXPIRED면 (미전송 그리고 인증/세션 폐기) 또는 기관 무효과 종결 확인이 있을 때만 허용한다 | 확정 | 사용자 | D-CPD-002 | CPC §9, AC1 §5.5 | TerminationEvidenceSpec::상태×근거 안전 종료 표; CreatePaymentApiSpec::CP-06 근거별 새 키 결과 |
| BR-PAY-024 | timeout·조회 NOT_FOUND·lease 만료·근거 없음은 새 Payment 근거가 아니며 원 시도 확인 전까지 같은 주문을 막는다 | 확정 | 사용자 | D-CPD-002, 시도 §2 | CPC §9 | CreatePaymentApiSpec::timeout·NOT_FOUND·lease 만료는 증거 아님(409 ORDER_RESULT_UNCONFIRMED); CreatePaymentServiceSpec::CP-06 근거 불충분 |
| BR-PAY-025 | D-CPD-002 표의 상태–근거 짝이 맞을 때만 인정하고 표 밖 교차 조합(FAILED+미전송·세션 폐기, EXPIRED+전송 전 실패 확정 등)은 불충분으로 차단한다 | 확정 | 사용자 | D-CPD-003 | AC1 §5.5 | TerminationEvidenceSpec::상태×근거 표; OrderPaymentDecisionTableSpec::FAILED/EXPIRED × 근거 |
| BR-PAY-026 | incident hold가 걸린 주문의 같은 입력 생성 재요청은 활성 시도가 있어도 재사용하지 않고 차단 응답(ORDER_ON_HOLD)에 기존 paymentId를 참조로 담으며, 새 Payment·승인 시작이 없다 | 확정 | 사용자 | D-CPD-004 | CPC §9 | CreatePaymentServiceSpec::D-CPD-004 hold 중 같은 입력 INCIDENT_HOLD·현재 Payment 참조; CreatePaymentApiSpec::hold는 ORDER_ON_HOLD + 참조·재생 |
| BR-PAY-027 | 생성은 멱등 결과·주문 슬롯 점유·Payment(REQUESTED)·Outbox를 같은 PAYMENT 로컬 TX로 커밋하고 실패하면 아무것도 남기지 않는다. 생성은 기관 호출·PaymentTransaction·Attempt를 만들지 않는다 | 확정 | 기준 | 시도 §5, 초안 §2·§5, CLAUDE.md | CPC §1·§11 | CreatePaymentServiceSpec::CP-01 Payment·control·결과·이벤트 함께 커밋; CreatePaymentRollbackSpec::각 저장 지점 실패 시 아무것도 남지 않음 |
| BR-PAY-028 | 새 Payment를 만들 때만(CREATED) PaymentRequested v1 통합 이벤트(비금융)를 같은 PAYMENT TX Outbox에 기록하고, 재사용·재생·거절은 이벤트를 만들지 않는다. 금융 효과 이벤트와 이름·의미를 구분한다 | 확정 | 사용자 | D-CPC-003 | CPC §10 | CreatePaymentServiceSpec::CP-01 이벤트 1·CP-04 재사용은 이벤트 없음; CreatePaymentApiSpec::CP-01 이벤트는 계약 §10 |
| BR-PAY-029 | 생성은 MID 소유·Site 관계(Assignment)·계약/개통 준비·허용 통화·결제수단·Snapshot freshness를 PAYMENT 로컬 Snapshot으로 검사하고, Snapshot 누락·만료·확인 불가 버전이면 거절한다(fail-closed). MASTER DB를 같은 TX로 읽지 않는다 | 확정 | 기준 | 초안 §1·§3, 허용 §4.2, D-CPC-006 | CPC §13 | CreatePaymentEligibilitySpec::다른 Merchant Site는 NotFoundOrForbidden; ::validUntil 지남 → SNAPSHOT_EXPIRED; EntitlementEligibilitySpec::미전파 MID SNAPSHOT_MISSING·lease 만료 SNAPSHOT_EXPIRED |
| BR-PAY-030 | 생성 시 판정은 승인 시작 시 재판정을 대신하지 않는다 | 확정 | 기준 | 허용 §4.1 끝, 초안 §5-1 | CPC §13, AC1 §4.6 | ConfirmStartSpec::admission은 TX1 안에서 승인 시작과 같은 시각으로 평가 |
| BR-PAY-031 | 일 한도는 생성으로 점유하지 않고 거래 한도의 권위 판정은 승인 시작에서 한다(생성 시 건당 한도 사전 거절은 선택 — v1은 하지 않음) | 확정 | 사용자 | D-CPC-008 | CPC §4, AC1 §4.5 | 없음 |
| BR-PAY-032 | Payment 생성 시 '생성 시각 + 결제 유효기간(버전 있는 설정)'을 절대 만료 시각으로 고정 저장하고 재사용·재생은 연장하지 않으며, 토큰·결제창 세션·인증 세션의 만료는 이 시각을 넘지 않게 자른다 | 확정 | 사용자 | D-AUTHN-005 | AN §7.5.1 | CreatePaymentInlineExpirySpec::만료 1ms 전 재사용·연장 없음; CheckoutTokenIssuanceSpec::토큰 만료를 결제창 시각에서 자름; CheckoutExchangeSpec::결제창 세션 만료를 결제창 시각에서 자름 |
| BR-PAY-033 | 결제 유효기간 수치(Demo `PT30M`)는 운영값이 아니며 설정이 없거나 0 이하면 기동하지 않는다 | Demo값 | 사용자 | D-AUTHN-005, D-AUTH-009 | AN §7.5.1 | CheckoutValiditySettingsSpec::0·음수·초 미만 기간은 기동 거부 |
| BR-PAY-034 | 만료 시각 도입 전(V7 이전)에 생성된 REQUESTED Payment는 배포 시점에 유효한 설정의 유효기간으로 created_at + 유효기간을 채우고 설정 버전을 기록한다. 이미 지난 건은 다음 만료 작업에서 EXPIRED, 채운 뒤에는 만료 시각 없는 REQUESTED를 허용하지 않는다 | 확정 | 사용자 | D-AUTHN-007 | AN §7.5.6 | OldVolumeStartSpec::REQUESTED만 채움·다음 회차 EXPIRED·이후 시각 없는 REQUESTED 거부; CheckoutWindowCheckMigrationStrategySpec::옛 볼륨 한 번 기동으로 V8_1 |

## 4. 멱등 요청

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-035 | 멱등 범위는 (environment, 인증 MID, operation, key)이며 같은 키·같은 내용(정규화된 요청 전체 fingerprint)은 저장된 결과, 같은 키·다른 내용은 충돌로 거절한다 | 확정 | 기준 | 시도 §5, CLAUDE.md 핵심 규칙 | CPC §7·§9, AU §4.8 | CreatePaymentServiceSpec::CP-03 같은 키 표시 이름만 바뀌어도 IdempotencyKeyReused; CreatePaymentConcurrencySpec::CP-03 같은 키 동시 → 하나 생성·나머지 재생/충돌; ConfirmStartSpec::같은 키 다른 본문 KEY_REUSED |
| BR-PAY-036 | CreatePayment 같은 키 재요청은 최초 생성 결과(paymentId, paymentKey, 생성 당시 상태 REQUESTED)를 그대로 반환하고 현재 상태는 조회 API로만 제공한다 | 확정 | 사용자 | D-CP-003 | CPC §5, CPQ §0 | CreatePaymentApiSpec::같은 키 최초 결과 바이트 동일 재생·현재 상태 없음; ::AUTHORIZED 뒤에도 lifecycleAtCreation REQUESTED; GetPaymentApiSpec::만료 뒤 GET은 EXPIRED·재생은 생성 시 결과 |
| BR-PAY-037 | 같은 키 재요청도 매번 현재 Credential·MID·환경 권한을 다시 확인한다 | 확정 | 기준 | 시도 §5, D-CP-003, D-AUTH-004 | CPC §3.2, AU §4.8 | ApiSecuritySpec::Credential 폐기 뒤 재생 401·다른 MID Credential은 멱등 조회 전 403 |
| BR-PAY-038 | 멱등 기록 보존은 30일(운영값 확정)이고 경과 뒤 같은 키는 새 요청이다. 주문 통제는 영구라 기록 만료가 동시 활성 Payment·이중 승인을 만들지 않는다 | 확정 | 사용자 | D-CPC-004 | CPC §8 | CreatePaymentServiceSpec::30일 직전 재생·경과 시 새 요청(주문 규칙 적용); ApiFailureSpec::T0+30d 활성 재사용·승인 주문 차단·근거 불충분 차단 |
| BR-PAY-039 | 생성의 업무 거절(다른 고정 입력·hold·과거 승인·종료 근거 불충분)도 그 키의 결과로 저장해 보존 기간 동안 같은 거절을 재현하고, 새 고객 시도는 새 키로만 한다 | 확정 | 사용자 | D-CPC-007 | CPC §9 | CreatePaymentServiceSpec::FIXED_INTENT_CONFLICT 저장·재생; CreatePaymentApiSpec::ORDER_RESULT_UNCONFIRMED 30일 재생·ORDER_INTENT_CONFLICT·ORDER_ON_HOLD 재생 |
| BR-PAY-040 | confirm 멱등 기록도 30일 보존한다(D-CPC-004의 operation 공통 적용) | 확정 | 사용자 | D-CPC-004, AU CA-07, D-GOV-010 ① | AC1 §4.4, AU §4.8 | 없음 |
| BR-PAY-041 | 일시 장애·판정 불가(권리 Snapshot·한도 정책 없음/모호·DB 일시 오류)는 멱등 결과로 저장하지 않으며 Payment를 바꾸지 않고, 같은 키 재시도가 다시 판정된다 | 확정 | 사용자 | D-AUTH1-001 | AU §4.9, CPC §9 | ConfirmStartSpec::Unavailable·LimitPolicyUnavailable 503 미저장·정책 등록 뒤 같은 키 시작; CreatePaymentApiSpec::eligibility 거절은 미저장 |
| BR-PAY-042 | 응답 유실·commit 결과 불명확·호출 timeout은 새 결제의 근거가 아니며 새 키가 아니라 같은 키 재요청·조회로 확인한다 | 확정 | 사용자 | D-AUTH-001(범위 §3-6), 초안 §5 | CPC §9, AU §4.9 | ApiFailureSpec::afterCommit 실패 503 뒤 같은 키 재생; ::응답 유실 뒤 같은 키 재생; CreatePaymentConcurrencySpec::commit 중 연결 끊김 → 같은 키로 실제 상태 확인 |

## 5. 고객 인증·결제창

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-043 | 결제는 가맹점 서버 생성 → PG 결제창 → 카드사/인증기관 인증 → 가맹점 서버 confirm → PG 승인 순서이며, 인증 성공은 승인 성공이 아니고 인증 뒤에도 Payment는 REQUESTED다 | 확정 | 사용자 | D-AUTH-001, 범위 §3 | AC1 §4.1 | CardAuthenticationFlowSpec::VERIFIED 뒤 successUrl redirect; ConfirmFlowSpec::confirm → 200 AUTHORIZED |
| BR-PAY-044 | 결제창 진입은 가맹점 서버가 MID 인증 API로 짧은 만료의 1회용 결제창 토큰을 받아 하며, paymentKey는 자격 증명이 아니다 | 확정 | 사용자 | D-AUTHN-001 | AN §5 | CheckoutTokenApiSpec::201 paymentId·checkoutUrl·expiresAt; CheckoutTokenIssuanceSpec::다른 MID·환경 Payment는 PAYMENT_NOT_FOUND |
| BR-PAY-045 | 결제창 토큰은 256bit 난수이고 DB에는 hash만 두며 주문·Payment·MID·금액·통화·환경·만료에 바인딩하고, 1회 교환 뒤 HttpOnly·Secure·SameSite 쿠키 세션과 CSRF 토큰으로 전환한다 | 확정 | 사용자 | D-AUTHN-001 | AN §5.2·§6·§12 | CheckoutTokenIssuanceSpec::원문은 checkoutUrl에만·digest와 바인딩 저장; CheckoutExchangeSpec::사용·만료 토큰은 같은 InvalidToken; CheckoutWebSpec::__Host- 쿠키·CSRF 없거나 틀리면 403 |
| BR-PAY-046 | successUrl/failUrl은 https이고 host가 그 결제 Site의 ACTIVE WEB 채널 host와 문자열로 정확히 같을 때만 허용하며 정규화하지 않는다. APP 채널·종료 Site·Snapshot에 없는 Site는 거절한다 | 확정 | 사용자 | D-AUTHN-002 | AN §8 | MerchantReturnUrlSpec::정확히 같은 문자열만 일치·거절 사유별 1행; CheckoutTokenIssuanceSpec::host 없음·다른 host RETURN_URL_NOT_ALLOWED |
| BR-PAY-047 | redirect 대상은 토큰에 저장된 검증 URL뿐이며 요청 파라미터·Host 헤더로 redirect 대상을 정하지 않는다 | 확정 | 기준 | 05-security(return URL 허용 목록), D-AUTHN-002 | AN §8.3 | MerchantReturnUrlSpec::host를 바꿀 값은 encode되어 host 유지; CheckoutWebSpec::조작된 Host 헤더는 redirect·CSP를 만들지 않음 |
| BR-PAY-048 | 인증 결과는 PG 서버가 인증기관에 서버 간 조회로 확인하고 state(세션별 난수)·바인딩(paymentId·금액·통화·MID·환경·Plan·카드사)·유효기간을 대조한다. 조회 실패면 VERIFIED가 아니며 브라우저 표시만 신뢰하지 않는다 | 확정 | 사용자 | D-AUTHN-003, 범위 §3-4 | AN §7.3 | AuthenticationResultCheckSpec::R6·R5·R7~R10 세션 실패·R11 일치 시 VERIFIED; CardAuthenticationFlowSpec::state가 세션을 못 고르면 기관 호출 없이 거절 |
| BR-PAY-049 | 인증 결과 조회가 timeout·연결·5xx·형식 위반·404로 확인 불가이면 세션을 FAILED로 바꾸지 않고 IN_PROGRESS로 두어 같은 복귀 URL 재조회를 허용한다(TTL 경과 시 EXPIRED) | 확정 | 사용자 | D-AUTHN-003, CLAUDE.md timeout 원칙, D-GOV-010 ① | AN §7.3 R3 | AuthenticationResultCheckSpec::R3 확인 불가 조회는 IN_PROGRESS 유지; CardAuthenticationFlowSpec::확인 불가 뒤 같은 복귀 URL로 VERIFIED 수렴 |
| BR-PAY-050 | AuthenticationSession은 CREATED·IN_PROGRESS·VERIFIED·FAILED·EXPIRED의 별도 lifecycle이고, 같은 인증 근거는 하나의 논리 승인 operation에만 한 번 귀속되며 되돌리지 않는다 | 확정 | 사용자 | D-AUTH-001 | AC1 §4.3·§5.3 | AuthenticationSessionSpec::한 승인 operation에 한 번 귀속·두 번째 거절; ConfirmConcurrencySpec::인증 시작과 confirm 경합 |
| BR-PAY-051 | 같은 Payment의 비종료 세션은 하나, 귀속 세션은 최대 하나이며 새 인증 시작은 미귀속 세션(VERIFIED 미귀속 포함)을 대체(SUPERSEDED)해 사용 가능한 인증 근거를 하나로 한다 | 확정 | 사용자 | D-AUTH-001, AC1 §3, AN AN-07, D-GOV-010 ① | AN §7.2 | AuthenticationSessionSpec::Payment당 열린 세션 하나; AuthenticationSessionStartSpec::미귀속 VERIFIED도 대체 |
| BR-PAY-052 | 인증 실패·사용자 취소 뒤 같은 결제창에서 다시 인증할 수 있고, 재인증·조건 해소 뒤 새 키로 confirm할 수 있다 | 확정 | 사용자 | D-AUTH-005 | AC1 §5.3, AN §6.3 | AuthenticationSessionSpec::실패 세션은 같은 결제창 새 시도를 막지 않음; AuthorizationStartSpec::거절 뒤 조건 해소 시 새 키로 시작 |
| BR-PAY-053 | VERIFIED 뒤 다른 증거는 거절하고 덮어쓰지 않으며 같은 증거는 멱등이고, FAILED·EXPIRED 세션의 늦은 인증 성공은 승인 권한을 만들지 않는다 | 확정 | 사용자 | D-AUTH-001, AC1 §5.3, D-GOV-010 ① | AN §7.3 P1~P3 | AuthenticationSessionSpec::VERIFIED 뒤 다른 증거 거절; ::종료 세션의 늦은 성공 무시; AuthenticationResultCheckSpec::P1·P2·P3 |
| BR-PAY-054 | 귀속 뒤 세션 TTL 만료는 시작·전송한 승인을 FAILED/EXPIRED로 바꾸지 않고, 전송 게이트는 인증 증거 유효기간을 보지 않으며 만료된 증거는 기관이 재검증해 확정 거절한다 | 확정 | 사용자 | D-AUTH1-004, 범위 §3 | AC1 §4.3, AU §4.4 | AuthenticationSessionSpec::귀속 뒤 TTL·실패는 세션을 바꾸지 않음; VanAuthorizationSpec(sim)::validUntil 지난 증거는 기관 확정 DECLINED |
| BR-PAY-055 | 결제 유효기간이 지난 REQUESTED Payment는 EXPIRED(NOT_TRANSMITTED_AND_SESSION_DISCARDED)로 끝내고 미귀속 세션·결제창 세션·토큰을 닫으며 PaymentExpired를 같은 PAYMENT TX Outbox에 1건 남긴다. 승인을 시작한(전송했거나 불명확한) Payment는 시간 경과로 끝내지 않는다 | 확정 | 사용자 | D-AUTHN-005, D-AUTHN-008, 시도 §3 | AN §7.5 | RequestedPaymentExpirySpec::미귀속 세션 먼저 만료 뒤 안전 종료; ::승인 시작된 Payment는 결제창 만료로 못 끝냄; CheckoutExpiryConcurrencySpec::PaymentExpired 정확히 1·근거 1행 |
| BR-PAY-056 | PaymentExpired는 비금융 통합 이벤트이며 payload에 paymentKey·카드정보·Secret·개인정보가 없다 | 확정 | 사용자 | D-AUTHN-008 | AN §7.5.7 | OutboxNotificationPayloadsSpec::PaymentExpired는 공개 분류만 |
| BR-PAY-057 | 결제 유효기간 만료 뒤 같은 주문은 새 키로 새 Payment를 만들 수 있다 | 확정 | 사용자 | D-AUTHN-005, D-CPD-002 | AN §7.5.3 | CreatePaymentInlineExpirySpec::만료 지난 Payment를 inline 만료하고 generation 2 생성 |
| BR-PAY-058 | 결제창은 카드번호·CVC·인증 비밀을 받지 않고, 결제창 화면 응답만 같은 출처 고정 번들(script-src 'self')을 쓰며 inline script·외부 origin·connect-src를 허용하지 않는다 | 확정 | 사용자 | D-CHECKOUT-CSP-001 | CR §4 | CheckoutWebSpec::보안 헤더; CheckoutShellScanSpec::같은 출처 외부 자산만·위반 fixture 검출; CheckoutErrorPageLeakSpec::고정 문구·서버 링크만 |
| BR-PAY-059 | 결제창의 카드사(발급사 코드)·Plan 선택은 서버가 allowlist·정책으로 다시 판정하고 인증 결과 발급사와 대조하며, 카드사 개통 판정은 서버가 검증한 매입사 코드로 한다 | 확정 | 사용자 | D-MS2-004, D-AUTHN-003 | AN §6.2 | CheckoutAuthenticationWebSpec::allowlist 밖 카드사 422; AuthenticationResultCheckSpec::R7 issuerCode≠선택 카드사 실패; AuthorizationAdmissionSpec::ACTIVE 개통 없는 매입사 거절 |

## 6. 승인 시작(confirm)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-060 | 신규 승인 전송은 허용 §4.1의 13개 조건(Credential·Merchant·MID·계약/권리·Site Scope·카드사/Route 개통·주문/금액/인증 바인딩·Route 준비·수수료/정산 정책 버전·지급 준비·슬롯/실행 점유·Snapshot lease/긴급 차단·한도/인프라)을 모두 충족할 때만 허용하고, PAYMENT 승인 Use Case가 최종 판정하며 근거 버전·판정 시각을 Authorization에 고정한다. MID 발급만으로 결제를 허용하지 않는다 | 확정 | 기준 | 허용 §4.1 | AC1 §4.6, AU §4.3 | AuthorizationAdmissionSpec::Admitted 근거(권리 버전·정책 Assignment·Route·한도); ::개통 없는 매입사 CARD_COMPANY_NOT_ENABLED; ConfirmStartSpec::Refused → PAYMENT_NOT_ELIGIBLE |
| BR-PAY-061 | 로컬 권리 Snapshot·lease가 만료·누락·확인 불가 버전이면 신규 승인을 막고, 차단 버전은 늦게 온 과거 허용 이벤트로 되돌리지 않으며 전송 게이트에서 다시 확인한다 | 확정 | 기준 | 허용 §4.2 | AC1 §4.6, AU §4.4 | AuthorizationAdmissionSpec::lease 만료 SNAPSHOT_EXPIRED; ::오래된 revision STALE·route 재개 불가; AuthorizationDispatchSafetySpec::TXd가 새 권리로 재판정 GATE_REFUSED·전송 없음 |
| BR-PAY-062 | 계약 철회·MID 신규 결제 중지는 신규 승인만 막고 기존 승인 결과 조회·UNKNOWN 복구·취소·정산은 별도 권한으로 계속하며, 이미 전송했거나 불명확한 승인은 철회·TTL을 근거로 실패·자동 취소하지 않는다 | 확정 | 기준 | 허용 §4.2·§4.3 | AC1 §4.6 | 없음 |
| BR-PAY-063 | confirm 본문의 금액·통화·주문 ID 재진술이 Payment 생성 값과 다르면 승인을 시작하지 않고 충돌로 거절·저장한다 | 확정 | 사용자 | 범위 §3-6, AU CA-08, D-GOV-010 ① | AC1 §4.4 | ConfirmStartSpec::재진술이 고정 입력과 다르면 CONFIRM_CONTENT_CONFLICT·시작 없음 |
| BR-PAY-064 | confirm 같은 키 재요청은 그 operation의 현재 결과를 반환하고(AUTHORIZED/FAILED 확정 뒤 불변, 미확정은 진행 중/확인 중), 승인 시작 전 거절은 저장된 거절을 재생하며, 재생은 기관을 다시 호출하지 않는다 | 확정 | 사용자 | D-AUTH-004, 시도 §5 | AU §4.8 | ConfirmFlowSpec::같은 키 재생은 현재 AUTHORIZED·기관 재호출 없음; ConfirmStartSpec::다른 키 다른 내용 거절 저장·재생; ConfirmPaymentApiSpec::Idempotent-Replayed·새 기관 호출 없음 |
| BR-PAY-065 | 다른 키·같은 Payment·같은 내용(귀속 세션의 authenticationRef와 재진술 일치) confirm은 활성 operation을 재사용하고 새 Attempt·귀속을 만들지 않으며, 내용이 다르면 충돌로 거절한다 | 확정 | 기준 | CLAUDE.md 핵심 규칙, 시도 §1 | AC1 §4.4, AU §4.3-5a | ConfirmStartSpec::다른 키 같은 내용 REUSED_ACTIVE_AUTHORIZATION; ::다른 authenticationRef·재진술은 CONFIRM_CONTENT_CONFLICT; ConfirmConcurrencySpec::동시 confirm → Authorization 1·Attempt 1 |
| BR-PAY-066 | 승인 시작 거절(한도·허용 조건·인증 근거 무효·Plan 거절, 기관 미전송)이면 Payment는 REQUESTED를 유지하고 세션은 귀속되지 않으며 거절은 그 키 결과로 저장·재생된다 | 확정 | 사용자 | D-AUTH-005 | AC1 §5.1, AU §4.3-10 | ConfirmStartSpec::VERIFIED 아닌 세션 AUTHENTICATION_NOT_VERIFIED·REQUESTED 유지; AuthorizationStartSpec::거절 시 어떤 Aggregate도 바뀌지 않음 |
| BR-PAY-067 | 한도 정책 없음·모호와 권리 판정 불가는 503 일시 장애(저장 없음, REQUESTED 유지)이며 정책 등록 뒤 같은 키 재시도로 승인이 시작된다 | 확정 | 사용자 | D-AUTH1-001, D-AUTH-006 | AU §4.9 | ConfirmStartSpec::Unavailable·LimitPolicyUnavailable 503 미저장·정책 등록 뒤 같은 키 시작 |
| BR-PAY-068 | Payment당 논리 Authorization은 하나이고 동시에 실행 중인 외부 승인 Attempt는 하나다(초기 계약: Authorization당 dispatch Attempt 1개, 전송 전 포기 뒤 고객 새 시도는 새 Payment) | 확정 | 기준 | 시도 §1 | AC1 §3·§5.2 | ConfirmConcurrencySpec::Authorization 1·Attempt 1; AuthorizationResolutionSpec::PREPARED→DISPATCHING 한 번·재 dispatch 거절 |
| BR-PAY-069 | 승인 시작과 REQUESTED 만료·종료는 같은 주문 슬롯·Payment에서 직렬화되어 한쪽만 성공하고, 결제 유효기간이 지난 결제창의 confirm은 그 TX에서 만료를 커밋한 뒤 거절한다(오래된 화면 승인 금지) | 확정 | 기준 | 시도 §3·§6-3, D-AUTHN-005 | AC1 §4.2, AU §4.3-4 | ConfirmConcurrencySpec::confirm vs 만료 작업 어느 쪽이 이겨도 한 결과; ConfirmStartSpec::결제창 시각 경과 → 같은 TX 만료·PAYMENT_TERMINATED; PaymentSpec::만료된 결제창은 승인 시작 불가 |
| BR-PAY-070 | FAILED·EXPIRED Payment의 confirm은 종료로 거절·저장하고(새 시도는 생성 새 키), incident hold 주문·과거 승인 주문은 승인을 시작하지 않는다 | 확정 | 기준 | 시도 §2, D-CPD-004 | AC1 §10.1, AU §4.3-5b·5d | ConfirmStartSpec::종료 Payment PAYMENT_TERMINATED; AuthorizationStartSpec::hold → INCIDENT_HOLD 거절·변화 없음 |
| BR-PAY-071 | Attempt 생성(PREPARED·기관 요청 ID 확정)·Payment AUTHORIZING·세션 귀속·한도 점유·멱등 결과는 같은 TX1로 커밋하고, 기관 전송은 그 커밋 뒤 DB TX·잠금 밖에서 한다 | 확정 | 기준 | 시도 §5, 허용 §5.3, CLAUDE.md | AC1 §4.1·§4.2, AU §4.2 | ConfirmStartSpec::TX1 커밋 내용(AUTHORIZING·OPEN·PREPARED·귀속·예약·ACCEPTED); ::insert·한도 갱신 뒤 실패 시 아무것도 없음; ConfirmFlowSpec::VAN 처리 중 잠금·DB TX 없음 |
| BR-PAY-072 | 결과 확정은 관찰·Authorization/Attempt 결과·Payment 요약·주문 통제·한도·PaymentTransaction·Outbox를 같은 PAYMENT 로컬 TX로 반영하고, 반영이 실패하면 원 Attempt 조회로 복구한다 | 확정 | 기준 | 시도 §5, INV-04, 허용 §5.3-5 | AC1 §6, AU §4.6 | ConfirmFlowSpec::TX2 실패 뒤 금융 사실 없음·VAN 승인 1; AuthorizationRecoverySpec::다음 조회에서 원 결정 → AUTHORIZED 1·재승인 없음 |
| BR-PAY-073 | 전송 게이트에서 현재 차단·lease를 재확인하고 실행 의도(DISPATCHING)를 커밋한 뒤에만 기관에 연결하며, 게이트 통과 전 포기된 Attempt와 connect 단계 실패(같은 요청 ID 재연결 예산 소진, byte 미송신)만 '전송 전 실패 확정' 근거다 | 확정 | 기준 | 허용 §5.3-2, D-CPD-002 | AC1 §5.2·§5.5, AU §4.4 | AuthorizationDispatchSafetySpec::GATE_REFUSED 전송 없음·FAILED(PRE_TRANSMISSION); AuthorizationRecoveryConcurrencySpec::DISPATCHING과 ABANDONED 중 하나만; ConfirmFlowSpec::연결 거부 예산 뒤 FAILED(NOT_SENT) |
| BR-PAY-074 | dispatch lease 만료·처리 중단을 '기관 호출 안 함'으로 가정하지 않고 UNKNOWN으로 둔다 | 확정 | 기준 | A06, CLAUDE.md 핵심 규칙 | AU §4.5·§6.1 | AuthorizationDispatchSafetySpec::lease 뒤 OUTCOME_UNKNOWN·절대 FAILED 아님; AuthorizationRecoverySpec::dispatch lease 만료 → OUTCOME_UNKNOWN |

## 7. 결제수단 범위·PaymentPlan

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-075 | 초기 범위는 KRW, 국내 발급 신용카드+체크카드(지원 카드사/상품은 기관 프로파일 allowlist), 일시불(신용·체크)과 기관/카드/가맹점이 허용하는 일반 유이자 할부이며 결제수단은 CARD다 | 확정 | 사용자 | D-AUTH-001 | AC1 §3 | ConfirmStartSpec::cardType×plan 행렬(신용 일시불·신용 할부·체크 일시불 시작) |
| BR-PAY-076 | 초기 제외: 해외 발급/외화/환전, 무이자·부분무이자 프로모션, 빌링·수기/비인증 결제, 카드포인트 복합결제, 기프트/선불/직불망 카드, 부분 승인·다중 수단 분할 결제 | 확정 | 사용자 | D-AUTH-001, 시도 §1 | AC1 §3 | AuthenticationResultCheckSpec::R10 CREDIT·CHECK 밖 cardType 실패; AuthorizationResolutionSpec::부분 승인은 CONFLICTING hold |
| BR-PAY-077 | 개방 순서는 신용 일시불 먼저 → UNKNOWN·취소 검증 → 체크 일시불·일반 할부다 | 대체됨→BR-PAY-078 | 사용자 | D-AUTH-001, 범위 §2 | — | 없음 |
| BR-PAY-078 | 신용 일시불·체크 일시불·일반 할부를 모두 AUTH-001에서 연다(체크·할부 승인의 취소 검증은 CANCEL-001 전까지 없음, 가상 기관 TEST 한정임을 재확인 후 유지) | 확정 | 사용자 | D-AUTH1-003 | AC1 §19.10-1 | ConfirmStartSpec::신용 일시불·신용 할부·체크 일시불 시작 |
| BR-PAY-079 | 체크카드 할부·정책 밖 개월·최소금액 미달·프로파일 미지원 cardType은 승인 시작에서 거절하고 기관에 보내지 않으며, 기관도 체크 할부·미지원 개월을 재검증해 거절한다 | 확정 | 사용자 | D-AUTH-001, 범위 §2 | AC1 §5.4, AU §4.3-9·10 | PaymentPlanSpec::체크 할부 PG 단계 거절; ConfirmStartSpec::체크 할부·정책 밖 개월·최소금액 미만 PAYMENT_PLAN_NOT_ALLOWED; VanAuthorizationSpec(sim)::체크 할부·프로파일 밖 개월 DECLINED |
| BR-PAY-080 | 할부 허용 개월·최소금액·카드/상품 제한은 버전 있는 Capability/정책의 Demo 값이며 실제 기관 규약 확인 전 운영값이 아니다 | Demo값 | 사용자 | D-AUTH-001, D-AUTH-009 | AC1 §3·§5.4 | PaymentPlanSpec::개월 기술 범위 2..99·운영 개월은 정책; PaymentPlanPrecheckSpec::정책 개월·최소금액 |
| BR-PAY-081 | cardType의 최종 근거는 인증·기관 결과이며 클라이언트 선택만 신뢰하지 않는다 | 확정 | 사용자 | 범위 §5(미채택 절), AC1 §3, D-GOV-010 ① | AC1 §3 | 없음 |

## 8. 거래 한도

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-082 | 거래 한도 범위는 MID+통화이고 사용량은 환경별로 분리하며 정책은 TEST/LIVE 공통이다 | 확정 | 사용자 | D-AUTH-006 | AC1 §4.5, AU §4.3-7 | TransactionLimitSpec::다른 MID·통화는 세지 않음 |
| BR-PAY-083 | 한도 정책 원본은 MASTER의 버전 있는 TransactionLimitPolicy(CSA 기본 + MID Override, 효력 기간)이며 권리 Snapshot으로 전파되고, 적용 정책은 정확히 1개여야 하며 없음·모호는 승인 시작 차단(fail-closed), 무제한은 명시 값만 인정한다 | 확정 | 사용자 | D-AUTH-006 | AC1 §4.5 | TransactionLimitSpec::없음 Missing·하나 Resolved·둘 Ambiguous; ::무제한은 명시 값만; AuthorizationStartSpec::정책 없음·모호 → LimitPolicyUnavailable |
| BR-PAY-084 | 한도 수치는 Demo 값이며 운영값이 아니다 | Demo값 | 사용자 | D-AUTH-006 | AC1 §4.5 | 없음 |
| BR-PAY-085 | 건당 한도 초과는 승인 시작 거절이며(같은 금액은 허용) 응답에 한도값을 노출하지 않는다 | 확정 | 사용자 | D-CPC-008, D-AUTH-006 | AC1 §4.5 | TransactionLimitSpec::건당 같으면 허용·1 초과 거절; ConfirmStartSpec::한도 경계 정확히·1 minor unit 초과 LIMIT_EXCEEDED |
| BR-PAY-086 | 일 한도의 날짜는 승인 시작 시각의 Asia/Seoul 날짜이며 점유 때 고정한다 | 확정 | 사용자 | D-AUTH-007 | AU §4.3-7 | TransactionLimitSpec::승인 시작 Asia/Seoul 날짜(KST 자정 경계); ConfirmStartSpec::대기 중 자정 경과 시 TX1 재시작·새 업무일 |
| BR-PAY-087 | 일 한도는 AUTHORIZING·UNKNOWN·hold 동안 점유를 유지하고, 기관 확정 거절·전송 전 실패 확정·무효과 확정이면 해제, 승인이면 소비한다. 같은 범위 동시 confirm도 합계가 일 한도를 넘지 않는다 | 확정 | 사용자 | D-AUTH-007 | AC1 §4.5 | TransactionLimitSpec::일 한도 RESERVED+CONSUMED; AuthorizationResolutionSpec::timeout → UNKNOWN 한도 유지; ConfirmFlowSpec::DECLINED 한도 RELEASED; ConfirmConcurrencySpec::동시 confirm 예약 합계 ≤ 일 한도 |
| BR-PAY-088 | 승인 후 취소·환불은 일 한도를 복원하지 않는다(일 승인 총액 기준) | 확정 | 사용자 | D-AUTH-007 | AC1 §4.5 | TransactionLimitSpec::소비된 점유는 복원되지 않음 |
| BR-PAY-089 | 확정 거절로 해제한 뒤 같은 Attempt의 늦은 승인이 오면 승인 시작일 날짜의 일 한도를 FORCED로 다시 점유한다(한도 초과여도 거부하지 않고 강제 기록·초과 표시, 기관 승인이 있을 수 있는 동안 이후 새 승인 판정 합에 포함). hold 해제가 NO_EFFECT면 재해제, REAL_APPROVAL이면 소비 | 확정 | 사용자 | D-AUTH-010 | AC1 §5.6·§19.8 | TransactionLimitSpec::FORCED 재점유·차단 합계 포함; AuthorizationResolutionSpec::초과여도 강제 기록·EXCEEDED; IncidentHoldReleaseSpec::NO_EFFECT 재해제·REAL 소비 |

## 9. 기관 결과·UNKNOWN 회복

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-090 | timeout·연결 끊김·조회 NOT_FOUND·PENDING·lease 만료만으로 금융 효과가 없다고 단정하지 않고 UNKNOWN으로 둔다 | 확정 | 기준 | INV-11, 시도 §2, CLAUDE.md 핵심 규칙 | AC1 §5.1·§7, AU §6.3 | AuthorizationResolutionSpec::PENDING·UNKNOWN·NOT_FOUND는 끝내지 않음; OrderPaymentControlSpec::timeout·NOT_FOUND로 닫을 수 없음 |
| BR-PAY-091 | write 시작 이후 실패·read timeout·끊김·프레이밍/상관관계/무결성 실패는 UNKNOWN이고, PREPARED(미전송 증명) Attempt는 UNKNOWN이 되지 않는다 | 확정 | 기준 | 허용 §5.3, A04 | AC1 §5.2, AU §4.5 | SimulatorVanAuthorizationAdapterSpec::응답 없이 닫힘 CONNECTION_LOST·read timeout TIMEOUT; AuthorizationResolutionSpec::dispatch 뒤 실패 UNKNOWN·PREPARED는 UNKNOWN 불가; ConfirmFlowSpec::응답 유실 202 UNKNOWN |
| BR-PAY-092 | 응답을 요청에 연결하고 금액·통화·원 참조·무결성을 검증하며 검증 실패 응답은 성공으로 처리하지 않는다 | 확정 | 기준 | 허용 §5.3-4 | AC1 §6-4 | SimulatorVanAuthorizationAdapterSpec::echo 불일치 CORRELATION·MAC 불일치 INTEGRITY·형식 오류 FRAMING |
| BR-PAY-093 | UNKNOWN에서는 새 승인·다른 VAN failover·고객 새 시도(새 키·새 카드)를 보내지 않고 원 Attempt의 원 기관 요청 ID로 조회·통보·대사만 한다(복구에 새 기관 요청 ID를 만들지 않음) | 확정 | 기준 | 시도 §4·§6-5, A04, CLAUDE.md 핵심 규칙 | AC1 §7, AU §6.3 | OrderPaymentControlSpec::UNKNOWN 중 새 카드·새 키 거절; AuthorizationRecoverySpec::두 번째 AUTH_REQUEST 없음; SimulatorVanAuthorizationAdapterSpec::조회 요청은 원 요청 ID echo |
| BR-PAY-094 | 재생·조회 복구·작업 재시작은 기관 승인을 새로 실행하지 않는다 | 확정 | 기준 | 시도 §5, INV-16, A06 | AC1 §7 | AuthorizationRecoverySpec::원 결정 → AUTHORIZED 1·재승인 없음; AuthorizationRecoveryConcurrencySpec::UNKNOWN Attempt마다 조회 1·승인 1 |
| BR-PAY-095 | 기관 거절은 다른 VAN 우회 근거가 아니며 라우팅 전환은 전송 전 실패가 확실하거나 미승인이 확인된 때만 허용한다 | 확정 | 기준 | 시도 §4, A04 | AC1 §5.2 | 없음 |
| BR-PAY-096 | 기관 무효과 확정은 Capability confirmsNoEffect가 있는 기관의 명시적 무효과 응답만 인정하고 NOT_FOUND는 무효과가 아니다 | 확정 | 기준 | 허용 §5.2, D-CPD-002 | AC1 §5.5, AU §6.3 | AuthorizationRecoverySpec::프로파일 A closableAfter 뒤 NO_EFFECT → FAILED·프로파일 B는 종결 요청 없음; SimulatorVanAuthorizationAdapterSpec::프로파일 B의 NO_EFFECT는 INTEGRITY |
| BR-PAY-097 | 조회 예산 소진·UNKNOWN 연령 상한 초과·반복 NOT_FOUND이면 Payment를 UNKNOWN 그대로 두고 대사 이관 이벤트를 내며 자동 FAILED하지 않는다(이관 뒤에도 UNKNOWN) | 확정 | 기준 | INV-11, D-AUTH-009, CLAUDE.md 핵심 규칙 | AC1 §7, AU §6.2 | AuthorizationRecoverySpec::예산 소진 → AuthorizationOutcomeUnresolved 1·UNKNOWN 불변; ::max age 이관·보존기간 뒤 조회 중지·UNKNOWN |
| BR-PAY-098 | confirm 대기·세션 TTL·timeout·조회 재시도 예산·대사 이관 기준은 버전 있는 설정이고 코드에 운영 기본값이 없으며 Simulator·시험 Demo 값만 쓴다. 운영값은 LIVE 준비 Gate에서 기관 규약 확인 후 사용자 결정 | Demo값 | 적용 | D-AUTH-009, Q-AUTH-07(APPLIED) | AU §6.2 | AuthorizationTimingSettingsSpec::connect 예산·lease·회복 시각 관계 위반 시 기동 거부; CheckoutValiditySettingsSpec::설정 오류 기동 거부 |
| BR-PAY-099 | 승인 전송 때 Route 프로파일(Capability) 버전을 Attempt에 고정하고, UNKNOWN 회복 때 현재 버전이 다르거나 고정 값이 없으면 무효과 종결 요청 없이 조회·대사 이관만 한다(fail-closed) | 확정 | 사용자 | D-AUTH1-007 | AU §5.8·§6.1 | AuthorizationResolutionSpec::TXd가 route profile 버전 고정; AuthorizationRecoverySpec::다른 버전이면 종결 요청 없음·승인은 반영 |
| BR-PAY-100 | 기관 요청 ID·ProviderTransactionId·승인번호·금액·통화·기관 거래일/업무일·응답 증거를 보존하고 기관 승인번호를 전역 UNIQUE로 가정하지 않는다 | 확정 | 기준 | 허용 §5.2 | AC1 §3 | AuthorizationResolutionSpec::같은 terms라도 PTX·승인번호·기관 거래일이 다르면 같은 승인 아님 |
| BR-PAY-101 | 응답·통보·조회·대사 결과가 상충하면 먼저 온 것이나 마지막 것을 정답으로 삼지 않고 기존 금융 사실을 덮어쓰지 않으며 증거와 예외 상태를 남긴다 | 확정 | 기준 | 허용 §5.2, 시도 §4 | AC1 §5.6 | AuthorizationResolutionSpec::상충 승인 → UNKNOWN + CONFLICTING hold; ::AUTHORIZED 뒤 반대 결과 상태 유지 + hold |

## 10. 확정 금융 사실·이벤트

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-102 | 기관이 확정한 금융 효과 1건 = PaymentTransaction 1건(append-only)이고 승인 성공은 AUTHORIZATION 거래다. 거절·무효과·UNKNOWN·중복 수신은 거래가 아닌 Attempt 관찰(append-only)이다 | 확정 | 사용자 | D-AUTH-003 | AC1 §3·§6 | PaymentTransactionSpec::AUTHORIZATION 타입·승인 금액; AuthorizationResolutionSpec::일치 APPROVED → 거래 정확히 1; DatabaseAccountSeparationSpec::append-only 확정 사실 표 UPDATE·DELETE 거부 |
| BR-PAY-103 | PaymentAuthorized 1건 = 거래 1건이며 effect_key·FINANCIAL 업무 키는 transactionId다 | 확정 | 사용자 | D-AUTH-003 | AC1 §11, AU §4.10 | ConfirmFlowSpec::PaymentAuthorized 1회; AuthorizationResultConcurrencySpec::AUTHORIZED 1·거래 1·PaymentAuthorized 1 |
| BR-PAY-104 | 같은 최종 결과의 중복 응답·통보·조회·대사는 금융 효과를 하나만 만든다 | 확정 | 기준 | 시도 §5, INV-06 | AC1 §5.6 | AuthorizationResolutionSpec::같은 관찰 키 두 번 효과 없음; ::다른 출처의 같은 승인 효과 없음; AuthorizationRecoveryConcurrencySpec::어느 순서든 거래 1 |
| BR-PAY-105 | '같은 승인'은 금액·통화·Plan·cardType과 ProviderTransactionId·승인번호·기관 거래일이 모두 같은 것이며, 하나라도 다르면 별개 승인 가능성으로 보고 상충 hold를 연다(두 번째 거래 없음) | 확정 | 사용자 | D-AUTH-003, AC1 review M-1, D-GOV-010 ① | AC1 §5.6 | AuthorizationResolutionSpec::같은 terms 다른 PTX·승인번호·기관 거래일 → CONFLICTING hold; ::식별자·terms 모두 같으면 효과 없음 |
| BR-PAY-106 | 같은 관찰 키에 다른 내용이 오면 첫 관찰을 유지하고 상충 증거로 append하며 CONFLICTING hold를 연다(진행 중이면 UNKNOWN) | 확정 | 사용자 | D-AUTH-002, AC1 review m-1, D-GOV-010 ① | AC1 §5.6 | AuthorizationResolutionSpec::진행 중 상충 증거·첫 관찰 유지·UNKNOWN + hold; ::승인·실패 상태 유지 + hold |
| BR-PAY-107 | AUTHORIZED는 되돌리지 않으며 종료 Payment(AUTHORIZED·FAILED·EXPIRED)의 상태를 늦은 결과로 덮어쓰지 않는다 | 확정 | 기준 | CLAUDE.md 핵심 규칙(확정 금융 사실 append-only), 시도 §4 | AC1 §5.1·§6-2 | PaymentSpec::종료 Payment에 늦은 결과는 모든 전이 거부·상태 유지 |
| BR-PAY-108 | hold 중 늦은·상충 승인은 해제 판정 전 거래를 만들지 않고 정산 대상 PaymentAuthorized를 내보내지 않으며 사건 이벤트로만 전달한다(이중 승인 보정을 위한 RECON 기록은 D-CANCEL-012 예외 — CAN 대장) | 확정 | 사용자 | D-AUTH-003, D-CANCEL-012 | AC1 §8.1 | AuthorizationResolutionSpec::늦은 승인 FAILED 유지·거래 없음·SUCCESS_AFTER_TERMINATION hold |
| BR-PAY-109 | 승인 실패·전송 전 실패는 비금융 PaymentAuthorizationFailed, 새 hold 개설은 AuthorizationIncidentDetected, 대사 이관은 AuthorizationOutcomeUnresolved로 상태 변경과 같은 TX에 기록하고 금융 사실 이벤트와 분류를 나눈다 | 확정 | 사용자 | D-AUTH-002, D-AUTH-009, AC1 CA-19, D-GOV-010 ① | AC1 §11, AU §4.10 | ConfirmFlowSpec::DECLINED PaymentAuthorizationFailed 1·부분 승인 AuthorizationIncidentDetected 1; AuthorizationRecoverySpec::예산 소진 Unresolved 1 |

## 11. 늦은 성공·상충 결과·incident hold

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-110 | 거절·만료로 종결한 Payment에 기관의 늦은 성공(또는 상충 결과)이 오면 상태를 덮어쓰지 않고 자동 보상취소 없이 incident hold + 대사 Case(HIGH/CRITICAL)로 수동 검토한다 | 확정 | 사용자 | D-AUTH-002 | AC1 §8 | AuthorizationResolutionSpec::늦은 승인 FAILED 유지·SUCCESS_AFTER_TERMINATION hold; AuthorizationResultConcurrencySpec::hold 1·Incident 1; OrderPaymentControlSpec::같은 늦은 성공 3회 → hold 1 |
| BR-PAY-111 | 종결 전(AUTHORIZING·UNKNOWN) 조건 상충 승인(금액·통화·Plan·cardType 불일치, 부분 승인)은 Payment·Authorization UNKNOWN 유지 + CONFLICTING_INSTITUTION_RESULT hold + 사건 이벤트(CRITICAL 힌트)이며 AUTHORIZED·PaymentAuthorized·자동 취소가 없고 한도 점유를 유지한다 | 확정 | 사용자 | D-AUTH-008 | AC1 §5.6 | AuthorizationResolutionSpec::상충 승인 → UNKNOWN + hold·거래 없음·한도 유지; ConfirmFlowSpec::부분 승인 202 UNKNOWN + hold + Incident 1 |
| BR-PAY-112 | AUTHORIZED 뒤 반대 결과·다른 금액 승인이 오면 상태를 유지하고 CONFLICTING hold를 열며 두 번째 거래·자동 취소가 없다 | 확정 | 사용자 | D-AUTH-002 | AC1 §5.6 | AuthorizationResolutionSpec::AUTHORIZED 뒤 반대 결과 hold·자동 취소 없음 |
| BR-PAY-113 | 상충 hold 뒤 들어온 결과는 기록만 하고 자동 확정하지 않는다(해소는 D-AUTH-002 수동 방식) | 확정 | 사용자 | D-AUTH-008 | AC1 §16 | AuthorizationResolutionSpec::conflict hold 뒤 결과는 기록만·자동 확정 없음 |
| BR-PAY-114 | incident hold 동안 그 주문의 새 Payment·결제창 토큰 발급·승인 시작을 막는다 | 확정 | 사용자 | D-CPD-004, 시도 §4 | AC1 §8.1, AN §5.2 | AuthorizationStartSpec::hold → 거절·변화 없음; OrderPaymentControlSpec::늦은 성공 hold 뒤 새 Payment 승인 시작 불가; CheckoutTokenIssuanceSpec::hold ORDER_ON_HOLD |
| BR-PAY-115 | hold 해제는 2인 승인(요청자≠승인자) + 재대사 MATCH 증거 + 사유가 필요하고, 해제 기록은 append-only이며 hold 행을 지우지 않고 한 hold 해제가 다른 hold를 없애지 않는다 | 확정 | 사용자 | D-AUTH-002 | AC1 §8.2 | IncidentHoldReleaseSpec::요청자 승인 불가·MATCH·사유 필수; ::하나 해제해도 다른 hold 유지; ::한 번만 해제 |
| BR-PAY-116 | hold 해제 판정이 실제 기관 승인(REAL_APPROVAL_CONFIRMED)이면 그 주문은 승인 성공 이력 주문(새 Payment 금지)이 되고, 무효과(NO_EFFECT_CONFIRMED)면 원 종료 근거가 유지되어 새 Payment가 가능하다 | 확정 | 사용자 | 시도 §2, D-CPD-002, AC1 §8.2(도출), D-GOV-010 ① | AC1 §8.2 | IncidentHoldReleaseSpec::REAL → 성공 이력·새 Payment 없음; ::NO_EFFECT → 새 Payment 허용 |
| BR-PAY-117 | 이전 generation 늦은 승인 hold 중 현재 generation 승인이 확정되면 현재 승인은 거래·PaymentAuthorized로 기록하고 주문 hold는 유지하며, 해제는 실제 이중 승인 확인 + 보정(취소) 참조 + 재대사 MATCH + 2인 승인으로 한다 | 확정 | 사용자 | D-AUTH-011 | AC1 §5.6 | AuthorizationResolutionSpec::현재 승인 거래 기록·주문 hold 유지; IncidentHoldReleaseSpec::DOUBLE_APPROVAL_CORRECTED는 보정 참조·MATCH·2인 필요 |
| BR-PAY-118 | NO_EFFECT로 해제한 뒤 같은 Payment에 새 승인 증거(새 관찰 키)가 오면 새 hold(기존 재사용 아님) + 일 한도 FORCED 재점유 + 주문 새 Payment 재차단이며, 해제는 다시 2인 승인 + 재대사 MATCH다 | 확정 | 사용자 | D-AUTH-012 | AC1 §5.6 | IncidentHoldReleaseSpec::NO_EFFECT 해제 뒤 새 hold occurrence 2; AuthorizationResolutionSpec::새 hold + FORCED 재점유 + Incident |
| BR-PAY-119 | REAL_APPROVAL_CONFIRMED·DOUBLE_APPROVAL_CORRECTED로 해제한 뒤에도 새 관찰 키의 승인(다른 기관 거래 식별자)·반대 결과가 오면 새 hold(occurrence+1) + 주문 차단(해당 시 FORCED 재점유)이며, 해제 판정은 확인한 그 건만 설명한다 | 확정 | 사용자 | D-AUTH-013 | AC1 §5.6 | IncidentHoldReleaseSpec::REAL 해제 뒤 새 hold·주문 재차단; AuthorizationResolutionSpec::DOUBLE 해제 뒤 반대 결과 새 CONFLICTING hold |
| BR-PAY-120 | hold 해제 때 해제 대상이 아닌 미해명 흡수 승인 관측이 남아 있으면 판정 종류(REAL·DOUBLE·NO_EFFECT)와 무관하게 같은 해제 TX에서 hold occurrence+1을 자동 개설한다(fail-closed) | 확정 | 사용자 | D-AUTH1-005, D-AUTH1-006 | AU §16.14·§16.16 | IncidentHoldUnexplainedApprovalSpec::REAL 해제 시 B 출처 occurrence 2; ::NO_EFFECT 해제 시 occurrence 2; AuthorizationResolutionSpec::해제가 흡수 승인에서 새 hold |

## 12. 결제 조회(GetPayment)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-121 | v1 결제 조회는 paymentId로만 한다(merchantOrderId 조회는 후속) | 확정 | 사용자 | D-CPQ-003 | CPQ §1 | GetPaymentApiSpec::query parameter·body는 400 |
| BR-PAY-122 | 조회는 인증 environment·MID·paymentId로 소유를 강제하고, 없음·다른 MID·다른 환경·형식 오류를 같은 404로 응답해 존재를 드러내지 않는다 | 확정 | 기준 | 05-security, INV-14 | CPQ §0 | GetPaymentApiSpec::다른 MID·다른 환경·없음·형식 오류 같은 404·echo 없음; PaymentStatusReaderSpec::SQL에서 범위 강제 |
| BR-PAY-123 | 현재 상태는 REQUESTED·IN_PROGRESS(AUTHORIZING)·UNKNOWN('확인 중' 안내)·AUTHORIZED·FAILED(DECLINED·NOT_SENT·NO_EFFECT 분류)·EXPIRED로 표현한다 | 확정 | 사용자 | D-AUTH-001(범위 §3-8), D-CP-003 | AC1 §10.1, CPQ §5 | PaymentStatusViewsSpec::AUTHORIZING은 IN_PROGRESS·UNKNOWN 유지; ::FAILED 근거별 분류; GetPaymentApiSpec::pending confirm은 UNKNOWN + 안내 |
| BR-PAY-124 | 조회 응답은 그 Payment 자신의 활성 hold 여부만 boolean onHold로 노출하고 사유·회차·관찰·FORCED/초과 금액·대사 진행은 노출하지 않는다 | 확정 | 사용자 | D-CPQ-001 | CPQ §4.4 | GetPaymentApiSpec::활성 hold onHold true·사유 없음; PaymentStatusViewsSpec::Payment 자신의 hold만 |
| BR-PAY-125 | 운영자 보정 취소는 취소 이력에 initiator OPERATOR로만 표시하고(신원·사유·approvalId·목적 비노출), 가맹점에 FAILED/EXPIRED로 알려진 Payment의 늦은 승인 거래와 그 운영자 취소는 조회에서 숨기고 FAILED와 분류만 보인다 | 확정 | 사용자 | D-CPQ-002, D-CANCEL-013 | CPQ §5 | PaymentStatusViewsSpec::운영자 취소는 initiator OPERATOR만; ::P1은 FAILED와 분류만; GetPaymentApiSpec::운영자 데이터 없음 |
| BR-PAY-126 | v1 조회에는 기간 제한이 없고 삭제 경로를 만들지 않는다(보관·파기는 LIVE Gate 별도 정책) | 확정 | 사용자 | D-CPQ-004 | CPQ §1 | DatabaseAccountSeparationSpec::append-only 확정 사실 표 DELETE 거부(부분) |
| BR-PAY-127 | cancellableAmount는 FORCED 예약을 뺀 권위 값 max(0, …)를 그대로 보이며, 승인액·취소액·진행액과의 차로 FORCED 금액을 역산할 수 있는 것은 허용된 추론이다 | 확정 | 사용자 | D-CPQ-005 | CPQ §18.6 | PaymentStatusViewsSpec::FORCED 뺀 권위 값(10,000 − 4,000); ::음수 없이 0 |
| BR-PAY-128 | 생성·confirm·조회 응답과 로그·이벤트에 카드번호·인증 증거·기관 원문·기관 계약계정·내부 정책/권리/Route 버전·Secret을 넣지 않고, 조회 응답은 캐시를 금지한다 | 확정 | 기준 | CLAUDE.md 검증과 안전, 05-security, D-MS2-005 | CPQ §0, AU §4.1 | ConfirmPaymentApiSpec::카드·증거·기관 계정·원문 없음; GetPaymentApiSpec::계약 필드만·no-store; AuthorizationRecoverySpec::로그에 기관 요청 ID·계약계정·HMAC 키 없음 |
| BR-PAY-129 | 가맹점은 승인 확정(AUTHORIZED) 뒤에만 주문 완료·상품 제공을 하도록 API 문서에 안내한다 | 확정 | 사용자 | D-AUTH-001(범위 §3-8) | AC1 §10.2 | 없음 |

## 13. 기관 식별 코드

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-130 | 기관 식별 코드(매입사·발급사(=결제창 카드사)·인증 기관·Route 코드, VAN 기관 코드)는 전 앱 공통 `^[A-Z0-9]{1,32}$`(하이픈·밑줄 금지)이며, 가상 카드 ID·기관 가맹점 참조 등 식별 코드가 아닌 값은 제외한다 | 확정 | 사용자 | D-INST-CODE-001 | IC §2 | AuthenticationBackendIsolationSpec::카드사 코드가 공통 형식 아니면 기동 거부; AuthorizationTermsContentSpec::매입사·route 코드 형식 위반은 계약 위반 |

## 14. 오류 응답 보안

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-PAY-131 | 오류 응답·헤더·로그는 요청값(MID·Site·주문 ID·키·주문명·금액)을 그대로 싣지 않고 다른 MID·환경 자원의 존재를 드러내지 않으며, 409 참조 paymentId는 같은 인증 MID의 자기 주문에만 준다 | 확정 | 기준 | 05-security, INV-14 | CPC §9.1 | CreatePaymentApiSpec::오류 본문·헤더에 보낸 값 없음; MerchantRequestIdFormatSpec::오류 메시지 echo 없음; ApiFailureSpec::충돌 경로 로그에 주문·키 값 없음 |

---

## 진단

규칙 수: 131 — 확정 114, 제안 10, Demo값 5, 미결 1, 대체됨 1.

### (a) 대체 사슬

| 이전 | 이후 | 내용 |
|---|---|---|
| BR-PAY-077(D-AUTH-001 개방 순서) | BR-PAY-078(D-AUTH1-003) | 신용 일시불 먼저 → 세 가지 동시 개방. 범위 §2 본문은 그대로다(c-4) |
| 시도 §4 "자동 보상취소 여부는 별도 정책" | BR-PAY-110(D-AUTH-002) | 자동 보상취소 없음·hold + 수동 검토로 확정 |
| D-AUTH-003 최초 응답 "관찰마다 거래" | D-AUTH-003 재확인 "기관 확정 1건=1거래" | 같은 결정 행 안의 대체. 현재 유효는 BR-PAY-102 |
| D-AUTH-002 해제 규칙 | D-AUTH-011·012·013, D-AUTH1-005·006 | 대체가 아니라 확장(이중 승인, 해제 뒤 재개, 흡수 승인 자동 재개) |
| AUTH-001 rev 0.3 AU-26(게이트가 인증 증거 TTL 확인 → PRE_TRANSMISSION) | BR-PAY-054(D-AUTH1-004) | 미채택. 게이트는 TTL을 보지 않고 기관이 재검증 |
| AUTHN-001 rev 1.1 V7 이전 행 임시 422 경로 | BR-PAY-034(D-AUTHN-007) | 삭제, 배포 시점 설정으로 채움 |
| 초안 v0.1(NOT READY) | CP-CONTRACT-001 v1.1 | 생성 계약 정본은 CPC. 초안의 업무 규칙은 바뀌지 않음 |
| D-AUTH-010 사용자 문구 | AC1 §19.8 Coordinator 해석 정정(2026-10-15) | "FORCED는 새 승인 한도 판정 합에 포함" — 사용자 선택지 문구가 결정 행에 인용돼 있어 BR-PAY-089에 반영. 해석 단계가 있었던 규칙이다(P-6 유형) |
| D-CPD-002 FAILED/EXPIRED 시험 fixture `failedWithOnlyTimeout` 등 | AUTH-001 L-3 legacy 행 | REQUESTED에서 기관·전송 근거 종료 금지. 옛 행은 복원만 가능(차단 기대값 유지) |

### (b) 결정 간 충돌

1. **외화 범위**: D-CP-002(2026-10-06, "KRW + 외화 일부", 생성·검증 KRW·USD) ↔ D-AUTH-001(2026-10-15, 범위 §1 "KRW", "초기 제외: 외화") ↔ D-AUTH1-002(승인은 KRW만). 결과: 기준정보가 USD를 활성화하면 USD Payment는 **생성은 되지만 승인은 절대 안 되고** 결제 유효기간 만료로만 끝난다. Q-SETTLE-CURRENCY는 PROPOSED("나중에 결정")로 남아 있다.
2. **D-AUTH-009의 출처**: 결정 행이 "(질문 없이 기존 규칙 적용)"이고 Q-AUTH-07 상태는 APPLIED다. 사용자 확인 기록이 없다(BR-PAY-098을 출처 '적용'으로 둔 이유). 내용은 CLAUDE.md "Demo 값은 운영값 아님"과 일치해 실질 충돌은 아니다.
3. **D-AUTH-003 ↔ D-CANCEL-012**: "hold 해제 판정 전 거래 미생성"의 유일한 예외가 D-CANCEL-012(RECON 이중 승인 보정 기록)다. AC1 §8.1에 교차 참조가 있어 충돌은 아니지만 PAY·CAN 대장 양쪽에 같은 예외를 적어야 한다(BR-PAY-108).
4. 그 밖의 D-AUTH-008(상충 시 AUTHORIZED 없음) ↔ D-AUTH-011(hold 중 현재 generation 승인은 거래 기록)은 대상이 다르다(상충 승인 vs 조건 일치 승인) — 충돌 아님.

### (c) baseline·명세 본문이 현재 결정과 다른 곳

1. `CLAUDE.md:42` "실제 통화/체크카드/할부·인증 범위 추가안은 사용자 채택 전이다" — D-AUTH-001(범위 §1~§3 채택)·D-AUTH1-003(체크·할부 개방)과 다르다.
2. `docs/baseline/PayCore-최초구현범위-권장안.md:3` "상태: 제안, 사용자 확정 전" — §1~§3은 D-AUTH-001로 채택됐다. §2 "최초 수직 기능은 KRW 신용카드 일시불… 이후 체크·할부"(25행)는 D-AUTH1-003으로 대체됐다.
3. `docs/baseline/PayCore-초기-확정사항.md:122` "해당 추가 범위는 아직 사용자 확정 전이며 기존 신용카드 우선 결정을 체크카드 포함으로 자동 변경하지 않는다" — D-AUTH-001과 다르다. 같은 문서 출시 범위 "지원 통화, 일시불/할부, 체크카드… 별도 확정 대상"도 갱신 전이다.
4. `docs/baseline/PayCore-결제시도-규칙-권장안.md` §4 "자동 보상취소 여부는 별도 정책" — D-AUTH-002로 확정(자동 보상취소 없음).
5. `docs/repository/AUTH-001-AUTHORIZATION.md:246`(§4.2 그림) "게이트: A0~A2 재판정(now) + 인증 증거 유효(providerValidUntil)" — 같은 문서 §4.4·D-AUTH1-004·코드(`AuthorizationDispatchService.kt:136`은 admission만 재판정)는 게이트가 증거 유효기간을 보지 않는다.
6. `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md:314` "Q-ADMIN-AUTH(운영자 인증·역할 — PROPOSED)" — 답변됨(D-ADMIN-001·002). 해제 실행이 막힌 실제 원인은 RECON-CONTRACT-001(BACKLOG)뿐이다.
7. AC1 §3 표(`CardType` 행) "최종 근거는 기관 결과… 확정 D-AUTH-001, 범위 §5" — D-AUTH-001은 범위 §1~§3만 채택했다. §5(확장 경계)·§4(가상 인증기관)는 채택 기록이 없다(BR-PAY-081을 제안으로 둔 이유).
8. `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md:14`·`:43` "첫 결제수단은 국내 신용카드(CARD)", "초기 확정(국내 신용카드)" — CARD에 체크카드가 포함됐다(D-AUTH-001). 같은 문서 10행 "API·eligibility Port 구현은… 아직 없다"도 CP-API-001 이후 낡았다.
9. `docs/repository/DOMAIN-DEFINITION.md:163` 인증기관 "미결(범위 문서 채택 전)" — D-AUTH-001·D-AUTHN-004 이후 낡았다. 35행 "KRW + 일부 외화"는 승인 KRW 한정(D-AUTH1-002)을 적지 않았다.
10. 허용 §4.1-10·§4.2 끝 문단의 "별도 운영 정책"(지급계좌 변경이 신규 승인 차단인지, 철회 중 승인 성공 사후 처리, 전파 지연·lease·ACK 수치) — 결정이 없다(본문은 정확, (f) 후보).

### (d) 시험 없는(또는 일부만 있는) 확정 규칙

| BR | 공백 |
|---|---|
| BR-PAY-031 | 생성이 일 한도를 점유하지 않음을 확인하는 시험 없음 |
| BR-PAY-062 | 계약 철회·MID 중지 상태에서 기존 승인 조회·UNKNOWN 복구가 계속되는 시험 없음(신규 거절 시험만 있음) |
| BR-PAY-095 | failover 금지·허용 조건 시험 없음(failover 기능 자체 없음) |
| BR-PAY-129 | API 문서 안내 — 시험 대상 아님(문서 확인 필요) |
| BR-PAY-007 | (MID, 환경)당 활성 키 최대 2개 시험 없음 — 발급은 MASTER-CREDENTIAL-001(BACKLOG) |
| BR-PAY-082 | 일 한도 사용량의 환경별 분리 시험 없음(MID·통화 범위만) |
| BR-PAY-022 | 실제 취소 기능으로 전액 취소한 뒤 같은 주문 생성이 막히는 시험 없음 — 승인 상태를 직접 만든 domain·service 시험뿐이며 `CreatePaymentApiSpec.kt:260` 시험 이름이 "refund is not modelled yet"로 남아 있다(CANCEL-001 이후 낡음) |
| BR-PAY-070 | confirm API에서 hold 주문 409 ORDER_ON_HOLD·과거 승인 주문 409 ORDER_ALREADY_AUTHORIZED 시험 없음(domain `AuthorizationStartSpec`의 hold만) |
| BR-PAY-126 | "삭제 경로 없음"은 append-only 표 권한 시험으로 일부만 확인 |
| BR-PAY-040(제안) | confirm 멱등 30일 경계 시험 없음 |

### (e) 코드가 규칙과 다른 곳

1. `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/domain/PaymentMethod.kt:3-5` KDoc "초기 범위는 국내 신용카드(CARD)뿐… 체크카드·할부·인증 범위 추가는 사용자 채택 전" — BR-PAY-075·078과 다르다(동작은 규칙대로: CARD 아래 CREDIT·CHECK·할부 개방). 주석만 낡음.
2. `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/domain/OrderPaymentControl.kt:249` `releaseIncidentHold` — application 호출자가 없다. BR-PAY-115~120의 해제는 domain에만 있고 실행 Use Case(RECON-CONTRACT-001)가 없어 현재 hold는 해제될 수 없다(fail-closed — 규칙 위반은 아니나 확정 규칙의 실행 경로 없음).
3. `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/application/CreatePaymentService.kt:300`(`IDEMPOTENCY_RETENTION` 30일) — 만료는 조회 시 갱신으로만 처리하고 CPC §8의 "30일 경과 행 삭제 Job"은 없다. BR-PAY-038의 의미는 지켜지나 기록이 계속 쌓인다.
4. `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/application/ConfirmStartService.kt:199` — 승인은 KRW만(BR-PAY-014)인데 생성은 Snapshot이 허용하면 외화를 받는다(BR-PAY-011). (b)-1의 실행 결과이며 코드는 두 결정을 각각 따른다.
5. 그 밖에 확정 규칙과 반대로 동작하는 코드는 이번 조사 범위(PAY 결정·명세가 가리키는 domain·application·시험)에서 찾지 못했다.

### (f) 미결·질문 후보(Coordinator 경유)

| # | 질문 후보 | 권장안 | 대안 | 영향 / 막히는 작업 |
|---|---|---|---|---|
| F-1 | 외화(USD 등) 생성 허용과 승인 KRW 한정의 불일치(Q-SETTLE-CURRENCY 연결) | 정산 통화 결정 전까지 기준정보에서 외화를 활성화하지 않는다(생성도 KRW만, 운영 절차로 고정) | 현행 유지(생성 가능·승인 불가, 만료로 종료) / 생성 단계에서 KRW 외 거절 | 외화 Demo·검증, SETTLEMENT-CONTRACT-001 |
| F-2 | 제안 10건 일괄 확인: BR-PAY-040(confirm 30일), 049(인증 조회 실패 시 IN_PROGRESS), 051(세션 수·대체), 053(VERIFIED 뒤 증거·늦은 성공), 063(confirm 재진술 대조), 081(cardType 최종 근거 — 범위 §5 미채택), 105(같은 승인 정의), 106(같은 관찰 키 다른 내용), 109(사건·이관 이벤트 분류), 116(hold 해제 결과의 주문 효과) | 모두 현행 구현대로 확정 | 행별 수정 | 대장 확정(RULE-GOV-001 4단계), RULE-TRACE |
| F-3 | 범위 권장안 §4(가상 인증기관)·§5(확장 경계: cardType 근거, Port 분리) 채택 여부 | §4·§5를 현재 구현 기준으로 채택 | §5 중 cardType 근거만 채택 | BR-PAY-081, AC1 §3 근거 정정 |
| F-4 | 계약 철회·MID 신규 결제 중지 중에 진행 중 승인이 성공한 경우의 사후 처리(허용 §4.2 "별도 업무 정책") | 거래는 기관 확정 사실로 기록하고 정산은 보류, 운영 검토 Case | 자동 취소 / 정상 정산 | 권리 차단 시험, RECON·정산 |
| F-5 | 지급계좌 변경·오류가 신규 승인을 막는지 지급만 보류하는지(허용 §4.1-10) | 지급만 보류(신규 승인은 계속) | 신규 승인도 차단 | BR-PAY-060 조건 10, MASTER Snapshot |
| F-6 | 문서 정합(질문 아님, 사용자 확인 후 수정): `CLAUDE.md:42`, 범위 권장안 머리말·§2, 초기-확정사항 122행, 시도 §4, AUTH-001 246행, AC1 314행·§3 표, CPC 14·43행, DOMAIN-DEFINITION 35·163행, `PaymentMethod.kt` KDoc, `CreatePaymentApiSpec.kt:260` 시험 이름 | 한 커밋으로 현재 결정에 맞춤(CLAUDE.md는 사용자 지침이라 문구 확인 필요) | — | 착수 전 관문 정확도 |
| F-7 | LIVE Gate 운영 수치(결제 유효기간·토큰/세션 TTL·조회 예산·이관 기준·한도·할부 개월·최소금액·lease·전파 지연·ACK timeout) | 지금 묻지 않음 — LIVE 준비 Gate에서 기관 규약 확인 후 | — | LIVE 전환만 |
| F-8 | 결제창 약관 동의(CHECKOUT-TERMS-001, D-CHECKOUT-UI-001 "별도 작업") | 약관 원문·버전 정책을 사용자에게 받을 때 착수 | — | CHECKOUT-TERMS-001 |
| F-9 | hold 해제 실행(RECON-CONTRACT-001 BACKLOG) 전까지 hold된 주문은 영구 차단 — 운영상 허용 기간 | RECON-CONTRACT-001 우선순위 확인 | 임시 수동 절차 정의 | BR-PAY-115~120 실행 |
