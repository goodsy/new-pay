# CAN — 취소·환불·거래 운영 업무 규칙 대장 (초안 rev 0.1)

- rev 0.1 (2026-11-25, RULE-GOV-001 2단계 추출 초안 — 사용자 확인 전). 형식은 `docs/rules/README.md`.
- 범위: 전액·부분취소, 반환 잔액, 늦은 승인/취소 결과 보정(사고 hold·FORCED·P1), 운영자 보정 취소(PAYMENT 재검사·ADMIN 승인·실행·회복·대사), 수수료·정산으로 넘기는 취소 사실, 계좌 환불 범위, 가맹점 노출, 거래 운영 조회, 운영 홈 KPI의 취소 차감 정의.
- 원천: `docs/DECISIONS.md` D-CANCEL-001~013·D-CANCEL1-001~008·D-OPS-002~010·D-TXOPS-001~005·D-ADMIN-HOME-001~002·D-CPQ-002·005·D-WH-001, `harness/questions.json`, 명세 CANCEL-CONTRACT-001 rev 1.3·CANCEL-001 rev 0.3·ADMIN-PAYMENT-OPS-001 rev 0.4·TXN-OPS-001 rev 0.2·TXN-LIST-ENRICH-001 rev 0.1b·ADMIN-HOME-KPI-001 rev 0.3a, baseline 초기 확정사항 38·53·58·85·87·96행·architecture/02·06·08·10, 코드 `apps/payment-api`·`apps/internal-admin` 2026-11-25 작업 트리.
- 상태 판정 기준: 사용자 답(D-ID) 또는 baseline 확정 문장을 그대로 옮긴 것만 `확정`. 명세의 "도출"·구현 결정(CC-/CN-/I-/C-)·Coordinator 판정에서 나온 업무 의미는 `제안`(출처 `적용`). 화면 배치·문구·구현 방식(잠금 순서, 표·열, effect key 모양 등)은 넣지 않았다.
- 경계: 매입 쪽 취소 처리(D-ACQ-002·009·012·013, D-FFR-*)는 FIN, 서비스 간 서명 인증(D-OPS-001)·관리자 계정·역할 일반은 ADM, 가맹점 조회·Webhook 일반은 PAY·WH 대장 소관이다. 여기에는 취소에 직접 걸리는 부분만 두었고 3단계 종합에서 중복을 정리한다.

## 1. 취소 대상·시작 조건

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-001 | 가맹점 취소는 Payment·Authorization이 모두 AUTHORIZED이고 확정 AUTHORIZATION 거래가 있을 때만 시작하고, 아니면 `PAYMENT_NOT_CANCELLABLE`로 거절(결과 저장)한다. | 확정 | 사용자 | D-CANCEL-011, D-AUTH-003 | CANCEL-CONTRACT-001 §4.8, CANCEL-001 §4.9 | `CancelStartSpec::CX-07 REQUESTED…EXPIRED PAYMENT_NOT_CANCELLABLE`, `CancelStartServiceSpec::REQUESTED is PAYMENT_NOT_CANCELLABLE` |
| BR-CAN-002 | 승인 결과 미확정(AUTHORIZING·UNKNOWN) Payment는 누구의 취소도 거절하고, 원 Attempt 조회·대사로 승인 결과를 먼저 확정한다. | 제안 | 적용 | CANCEL-CONTRACT-001 §13 말미 N-2(Coordinator 도출 채택), CLAUDE.md(UNKNOWN은 원 Attempt 조회 우선) | CANCEL-CONTRACT-001 §4.8 | `CancelStartSpec::CX-07` |
| BR-CAN-003 | 운영자 보정 취소는 Authorization에 확정 승인 거래가 있으면 Payment가 FAILED/EXPIRED(RECON이 늦은 승인을 기록한 P1)여도 시작하고, 거래가 없는 FAILED Payment는 운영자도 `PAYMENT_NOT_CANCELLABLE`이다. | 확정 | 사용자 | D-CANCEL-011, D-CANCEL-013 | CANCEL-CONTRACT-001 §4.8·§4.10 | `CancelStartSpec::CX-27 operator starts, merchant PAYMENT_NOT_CANCELLABLE`, `CancelStartSpec::operator cancel on FAILED without transaction` |
| BR-CAN-004 | 취소는 원 거래의 전액·부분취소이며 기관이 지원하는 범위 안에서만 한다. | 확정 | 기준 | PayCore-최초구현범위-권장안 §1(D-AUTH-001 채택) | CANCEL-CONTRACT-001 §4.2 | `CancelStartSpec::CX-08 installment partial refused, full accepted` |
| BR-CAN-005 | 부분취소 허용은 원 경로 기관 프로파일 Capability(PaymentPlan·cardType별)로 판정하고 PG 자체 금지 규칙(예: 할부 부분취소 금지)을 두지 않으며, 판정할 수 없으면(프로파일 없음·버전 미지원) 취소를 시작하지 않는다(fail-closed, 저장 없음). | 확정 | 사용자 | CANCEL-CONTRACT-001 §4.2 도출(K-2 + AUTH CA-04), D-GOV-010 ① | CANCEL-CONTRACT-001 §4.2, CANCEL-001 §4.3-6 | `CancelStartSpec::CX-09 no capability no cancel`, `CancelStartServiceSpec::check card partial PARTIAL_CANCEL_NOT_SUPPORTED`, `CancelStartServiceSpec::sim-van-1 TemporarilyUnavailable` |
| BR-CAN-006 | 취소는 신규 승인 권리(eligibility·lease·한도)를 요구하지 않는다 — MID 신규 결제 중지·계약 철회·Snapshot lease 만료에도 처리하되 유효한 Credential 또는 운영 권한과 원 경로가 필요하다. | 확정 | 기준 | 결제허용-기관연동 §4.3, 초기 확정사항 53행, D-MD-006 | CANCEL-CONTRACT-001 §4.4 | `CancelStartServiceSpec::K-3 cancel still starts, admission never consulted`, `CancelPaymentApiSpec::K-3 valid credential still starts` |
| BR-CAN-007 | 가맹점 키 폐기·가맹점 종료 뒤 남은 취소 의무는 운영자 보정 취소(목적 MerchantTerminatedObligation)로만 처리한다. | 확정 | 사용자 | D-CANCEL-005 | CANCEL-CONTRACT-001 §4.11 | `CancelStartSpec::merchant-terminated obligation is an allowed operator purpose` |
| BR-CAN-008 | 가맹점은 인증된 MID·환경 소유 Payment만 취소할 수 있고, 없거나 다른 MID·환경이면 존재를 드러내지 않는 404(결과 저장 없음)다. | 확정 | 기준 | INV-14, A05·A08, D-CPA-001 | CANCEL-001 §4.1 | `CancelStartServiceSpec::another MID/environment NotFound`, `CancelPaymentApiSpec::other-MID paymentId 404` |
| BR-CAN-009 | PG는 자체 취소 기한을 두지 않으며, 기관 기한 경과는 기관 거절로 FAILED가 된다. | 확정 | 사용자 | D-CANCEL-004 | CANCEL-CONTRACT-001 §4.4-7 | `CancelStartSpec::no PG-owned cancel deadline`, `VanCancellationSpec::one millisecond later DECLINED CANCEL_PERIOD_EXPIRED`(Simulator) |
| BR-CAN-010 | Payment당 진행 중(OPEN·UNKNOWN) 취소는 1건이며, 그 동안 새 취소는 가맹점·운영자 모두 `CANCEL_IN_PROGRESS`로 거절하고 UNKNOWN 취소는 결과 확정까지 다음 취소를 막는다. | 확정 | 사용자 | D-CANCEL-003 | CANCEL-CONTRACT-001 §4.4-4, CANCEL-001 §7.1 | `CancelStartSpec::CX-13`, `CancelStartConcurrencySpec::K1 exactly one starts`, `OperatorCorrectionCancelSpec::cancel in progress blocks the correction` |
| BR-CAN-011 | 이 Payment를 대상으로 한 활성 incident hold(사유 무관)가 있으면 가맹점 취소는 `ORDER_ON_HOLD`(저장)로 거절하고 운영자 보정 취소만 허용하며, 같은 주문의 다른 Payment에만 hold가 있으면 가맹점 취소를 허용한다. | 확정 | 사용자 | D-CANCEL-001, D-CANCEL-005 | CANCEL-CONTRACT-001 §4.10 | `CancelStartSpec::CX-14`, `CancelStartConcurrencySpec::K12 with a hold merchant ORDER_ON_HOLD, operator starts` |
| BR-CAN-012 | 승인 성공 주문은 전액 취소 뒤에도 다시 승인하지 않는다(재구매는 새 주문번호) — 취소는 Payment 상태와 주문의 승인 이력을 바꾸지 않고 반환 상태를 새 승인 허용 조건으로 쓰지 않는다. | 확정 | 기준 | 초기 확정사항 38행, CLAUDE.md, 결제시도 §2·§3 | CANCEL-CONTRACT-001 §4.4 | `CancellationResolutionSpec::CR-13 after a full return the order still refuses a new payment`, `CancelStartSpec::CX-10` |
| BR-CAN-013 | 승인 후 취소·부분취소는 일 한도를 복원하지 않는다(취소 경로는 일 한도 사용량을 읽지도 쓰지도 않는다). | 확정 | 사용자 | D-AUTH-007 | CANCEL-CONTRACT-001 §5.4 | `CancelStartSpec::CX-10 daily limit untouched`, `CancellationResolutionSpec::completion never touches the daily limit usage` |

## 2. 금액·반환 잔액

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-014 | 취소 통화는 원 승인 거래 통화와 같아야 하며 다르면 `CURRENCY_MISMATCH`로 거절한다(환전 없음). | 확정 | 사용자 | CANCEL-CONTRACT-001 §5.2 도출(결제시도 §3 "승인된 주문의 금액·통화 변경 안 함"), D-GOV-010 ① | CANCEL-CONTRACT-001 §5.2 | `CancelStartSpec::CX-06 another currency is rejected` |
| BR-CAN-015 | 완료 반환 + 진행 중 예약 ≤ 원 승인액을 항상 지키고, 취소 가능액 = max(0, 승인 − 완료 − 예약 − FORCED)이며, 요청액이 1 이상 취소 가능액 이하가 아니면 거절한다. | 확정 | 기준 | A08 Test Fixture Policy(반환 불변식), A02(부분취소 누적 보호), D-CANCEL-006, D-CPQ-005 | CANCEL-CONTRACT-001 §4.3 | `ReturnBalanceSpec::reservation guard`, `ReturnBalanceSpec::CB-07`, `CancelStartSpec::CX-04 7,001 rejected` |
| BR-CAN-016 | 같은 Payment의 동시 취소 요청에서도 누적 반환이 승인액을 넘지 않도록 잠금/버전으로 보호한다(조회 후 무보호 쓰기 금지). | 확정 | 기준 | A02 "제약과 정책", CLAUDE.md Concurrency Control | CANCEL-CONTRACT-001 §9.2, CANCEL-001 §4.7 | `CancelStartConcurrencySpec::K1`, `CancelStartConcurrencySpec::K5 two full cancels only one starts` |
| BR-CAN-017 | 반환 상태는 완료 반환 = 승인액이면 FULLY_CANCELLED, 0보다 크면 PARTIALLY_CANCELLED, 아니면 NONE이며 FORCED는 넣지 않는다 — 전액 반환 뒤 추가 취소는 `ALREADY_FULLY_CANCELLED`, FORCED만으로 취소 가능액이 0이면 금액 초과 거절이다. | 확정 | 사용자 | D-ADMIN-HOME-002 ②("ReturnStatus 정의와 같음"), D-CANCEL-006, D-CANCEL-009 | CANCEL-CONTRACT-001 §4.3, §4.8 | `ReturnBalanceSpec::CB-01`, `CancelStartSpec::CX-05`, `CancelStartSpec::zero cancellable only from FORCED is AMOUNT_EXCEEDS_BALANCE` |
| BR-CAN-018 | 결과를 모르는 취소(OPEN·UNKNOWN)의 반환 예약은 유지하고 timeout·NOT_FOUND·lease 만료로 해제하지 않으며, 기관 확정 거절·전송 전 실패 확정·기관 무효과 확정에서만 해제하고 기관 확정 성공에서만 소비한다. | 확정 | 기준 | CLAUDE.md(timeout/NOT_FOUND/lease 만료만으로 무효과 단정 금지), INV-11 | CANCEL-CONTRACT-001 §4.3·§4.6 | `ReturnBalanceSpec::release on a confirmed no-effect outcome`, `CancellationResolutionSpec::CR-06·CR-07` |
| BR-CAN-019 | 범위 FULL은 완료·진행 중 반환이 없는(해제된 예약만 있는) Payment의 원 승인 전액 1회 취소이고, 그 밖(앞선 부분취소 뒤 남은 잔액 전부 포함)은 PARTIAL이며 시작 시점에 고정한다. | 확정 | 사용자 | CANCEL-CONTRACT-001 CC-04, D-GOV-010 ① | CANCEL-CONTRACT-001 §4.2 | `CancelStartSpec::CX-01·CX-02·CX-03`, `CancelStartSpec::CB-02 a declined cancel is not a return` |

## 3. 요청 식별·멱등

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-020 | 가맹점 취소는 `merchantCancelId`(D-CPC-002 형식, 받은 그대로 비교)가 필수이고 (Payment, merchantCancelId)가 업무 효과 키다 — 같은 ID·같은 내용은 Idempotency-Key가 달라도 같은 취소, 같은 ID·다른 내용은 409 `CANCEL_REQUEST_CONFLICT`(저장)다. | 확정 | 사용자 | D-CANCEL-002 | CANCEL-CONTRACT-001 §4.5, CANCEL-001 §4.3-3 | `PaymentCancellationSpec::same merchantCancelId + same money = same request`, `CancelStartServiceSpec::EXISTING_CANCELLATION / CANCEL_REQUEST_CONFLICT`, `CancelStartConcurrencySpec::K3/K4` |
| BR-CAN-021 | 실패한 취소의 재시도는 새 merchantCancelId로 하며, FAILED 취소의 같은 ID는 그 취소의 기존 결과다. | 확정 | 사용자 | D-CANCEL-002 | CANCEL-CONTRACT-001 §4.5 | `PaymentCancellationSpec::after the cancellation FAILED the same id still means the same request` |
| BR-CAN-022 | 업무 거절로 취소 행이 생기지 않은 merchantCancelId는 새 Idempotency-Key로 다시 쓸 수 있다. | 제안 | 적용 | CANCEL-001 §4.8(n-8) | CANCEL-001 §4.8 | `CancelStartServiceSpec::(n-8) rejected merchantCancelId can be used again` |
| BR-CAN-023 | 업무 키((Payment, merchantCancelId)·운영자 approvalId) UNIQUE는 멱등 기록 30일 보존과 무관하게 영구다. | 확정 | 사용자 | CANCEL-CONTRACT-001 §4.5 "업무 키의 영속성", D-GOV-010 ① | CANCEL-CONTRACT-001 §4.5, §9.2 | `PaymentCancellationSpec::business key (permanent, not tied to the 30-day retention)` |
| BR-CAN-024 | 취소 API 멱등 범위는 (environment, 인증 MID, CancelPayment, Idempotency-Key)와 정규화 내용(paymentId·merchantCancelId·금액·통화) 비교이고, 같은 키·다른 내용은 409 `IDEMPOTENCY_KEY_REUSED`, 업무 거절도 결과로 저장해 30일 재생하며 30일 뒤 같은 키는 새 요청이다. | 확정 | 기준 | 결제시도 §5, D-CP-001, D-CPC-004, D-CPC-007 | CANCEL-CONTRACT-001 §4.5, CANCEL-001 §4.8 | `CancelStartServiceSpec::same key and body replays…IDEMPOTENCY_KEY_REUSED`, `CancelStartServiceSpec::renewed as a new request (30 days)`, `CancelPaymentApiSpec::replays a stored rejection` |
| BR-CAN-025 | 같은 키 재요청은 그 취소의 현재 결과를 돌려주며 기관을 다시 호출하지 않는다. | 확정 | 사용자 | CANCEL-CONTRACT-001 CC-05(D-AUTH-004 선례), D-GOV-010 ① | CANCEL-001 §4.3-1 | `CancelFlowSpec::same key replays the current result without a new institution call` |

## 4. 취소 상태·결과 반영·UNKNOWN 회복

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-026 | 취소는 OPEN → (UNKNOWN →) COMPLETED / FAILED로만 진행하고 종료 상태는 되돌리지 않는다. | 확정 | 기준 | CLAUDE.md(확정 금융 사실 append-only) | CANCEL-CONTRACT-001 §4.6 | `PaymentCancellationSpec::a completed cancellation holds exactly one CANCEL transaction and is terminal` |
| BR-CAN-027 | 기관이 확정한 취소 성공(금액·통화·원거래 참조 일치) 1건 = CANCEL 거래 1건(append-only)이며, 거절·무효과·UNKNOWN·중복 수신은 거래가 아니라 관찰로만 남긴다. | 확정 | 사용자 | D-AUTH-003 | CANCEL-CONTRACT-001 §4.6 | `CancellationResolutionSpec::CR-01·CR-02`, `CancellationResolutionSpec::CR-08 same APPROVED again no effect` |
| BR-CAN-028 | 취소 FAILED 근거는 기관 확정 거절·기관 무효과 확정·전송 전 실패 확정뿐이고, 쓰기 시작 이후 실패·timeout·끊김·무결성 실패·lease 만료는 UNKNOWN, 조회 NOT_FOUND·PENDING은 UNKNOWN 유지이며 자동 FAILED로 만들지 않는다. | 확정 | 기준 | CLAUDE.md, INV-11, 06-reliability "timeout 거래를 모두 실패 처리 금지" | CANCEL-CONTRACT-001 §4.6, CANCEL-001 §7.2 | `CancellationResolutionSpec::CR-03·CR-04·CR-05·CR-06·CR-07`, `CancelFlowSpec::transport failures after the request was written` |
| BR-CAN-029 | 취소 UNKNOWN 회복은 원 취소 Attempt의 기관 요청 ID 조회로만 하고 새 기관 요청 ID 생성·새 취소 재전송을 하지 않는다. | 확정 | 기준 | CLAUDE.md, 결제시도 §4, 06-reliability, D-CANCEL1-003 | CANCEL-CONTRACT-001 §4.7, CANCEL-001 §7.1 | `CancellationRecoverySpec::one recovery inquiry with the original cancel request ID…no resend` |
| BR-CAN-030 | 회복 예산·연령 상한을 넘기면 취소는 UNKNOWN·예약 RESERVED를 유지한 채 대사로 이관(`CancellationOutcomeUnresolved`)하고, 이관된 취소 UNKNOWN에는 임시 출구(운영자 확정·같은 ID 재전송)를 두지 않는다 — 확정은 RECON만. | 확정 | 사용자 | D-CANCEL1-003 | CANCEL-001 §7.1 | `CancellationRecoverySpec::NOT_FOUND until the budget, then CancellationOutcomeUnresolved once; UNKNOWN·RESERVED stay` |
| BR-CAN-031 | 이관된 취소 UNKNOWN을 대사 근거로 확정하는 RECON Use Case가 생기기 전에는 LIVE를 허용하지 않는다. | 확정 | 사용자 | D-CANCEL1-003 | CANCEL-001 §20 | 없음(LIVE Gate 항목) |
| BR-CAN-032 | 무효과 종결은 전송 때 취소 Attempt에 고정한 Route 프로파일 버전이 현재 버전과 같을 때만 요청하고, 다르거나 NULL이면 조회·이관만 한다. | 확정 | 사용자 | D-CANCEL1-004 | CANCEL-001 §7.1 | `CancellationRecoverySpec::D-CANCEL1-004 route profile version changed withholds closeIfAbsent`, `PaymentCancellationSpec::dispatch gate pins the route profile version` |
| BR-CAN-033 | 기관 호출은 DB TX 밖에서 하고, 취소 상태·반환 예약·CANCEL 거래·요청 결과·Outbox는 같은 PAYMENT 로컬 TX로 반영한다. | 확정 | 기준 | CLAUDE.md, INV-04 | CANCEL-001 §4.2 | `CancelStartServiceSpec::TX1 faults every row rolls back`, `CancelFlowSpec::TX2 failure after the VAN approved` |

## 5. 사고 hold·늦은 결과·이중 승인 보정

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-034 | 취소 결과 상충(금액·통화·원거래 참조 불일치, 부분 성공)은 덮어쓰지 않고 관찰을 기록한 뒤 그 Payment에 hold를 열고 사건(HIGH "Unexpected refund", 승인액 초과 가능 시 CRITICAL)을 내며, 진행 중 취소는 UNKNOWN·예약을 유지하고 자동 재취소하지 않는다. | 확정 | 사용자 | D-CANCEL-006, 정산 §18 | CANCEL-CONTRACT-001 §4.6 | `CancellationResolutionSpec::CR-09·CR-10`, `CancelFlowSpec::conflicting VAN result…CANCEL_RESULT_CONFLICT hold` |
| BR-CAN-035 | FAILED 뒤 늦은 취소 성공은 FAILED를 유지하고 두 번째 거래를 만들지 않으며, 해제된 예약을 FORCED로 재기록(승인액 초과도 기록, 초과 표시)하고 hold를 연다. | 확정 | 사용자 | D-CANCEL-006 | CANCEL-CONTRACT-001 §4.3·§4.6 | `CancellationResolutionSpec::CR-11`, `ReturnBalanceSpec::CB-07`, `CancelFlowSpec::late success riskHint HIGH / CRITICAL` |
| BR-CAN-036 | COMPLETED 취소에 반대 결과나 다른 기관 거래번호의 성공이 오면 상태를 유지하고 두 번째 거래 없이 hold를 연다. | 확정 | 사용자 | D-CANCEL-006 | CANCEL-CONTRACT-001 §4.6 | `CancellationResolutionSpec::CR-12` |
| BR-CAN-037 | 취소 사건 hold가 해제되기 전에는 그 Payment의 가맹점 추가 취소를 막고 운영자 보정 취소는 허용한다. | 확정 | 사용자 | D-CANCEL-006("hold 해제 전 그 Payment 추가 취소 차단") + D-CANCEL-001·005 — 계약 §4.10이 "(가맹점) 취소"로 한정, D-GOV-004 ③, D-GOV-010 ② | CANCEL-CONTRACT-001 §4.10 | `CancelStartSpec::FORCED + hold → merchant ORDER_ON_HOLD`, `CancellationResolutionSpec::a hold opened by one cancellation does not stop a later operator correction cancel` |
| BR-CAN-038 | 취소 사건 hold와 FORCED 예약은 2인 승인 + 재대사 MATCH 해제 규칙(RECON)이 정해지기 전에는 해제·소비·해제할 경로가 없다(fail-closed). | 확정 | 사용자 | D-AUTH-002, D-CANCEL-006, CLAUDE.md(보정 후 MATCH 확인 전 해결 금지) | CANCEL-CONTRACT-001 §4.6 | `CancelStartSpec::releasing a cancel-result hold is refused until RECON-CONTRACT-001`, `ReturnBalanceSpec::FORCED return is never consumed nor released` |
| BR-CAN-039 | 취소 결과 충돌 사건은 사건마다 이벤트 1건을 내고 사건 수를 영속하며, 활성 hold가 이미 있으면 새 hold를 열지 않는다. | 확정 | 사용자 | D-CANCEL1-002 | CANCEL-001 §4.10 | `CancellationResolutionSpec::another cancellation's incident on a held payment…without a new hold`, `CancelResultConcurrencySpec::K13a` |
| BR-CAN-040 | 사건이 기록된 진행 중 취소에 새 충돌 확정 관찰이 오면 사건을 +1 하되 상태는 바꾸지 않고, 같은 attempt·같은 outcome·같은 확정 조건(금액·원거래 PTX 등)의 결정이 다른 출처로 다시 오면 같은 사건, 조건이 하나라도 다르면 새 사건이다. | 확정 | 사용자 | D-CANCEL1-005, D-CANCEL1-007 | CANCEL-001 §18.2b·§18.2c | `CancellationResolutionSpec::K13 in-flight / COMPLETED / FAILED`, `CancellationResolutionSpec::H-1 …new incident`, `CancelResultConcurrencySpec::K13a·K13b·K13c` |
| BR-CAN-041 | 사건이 기록된 취소의 이후 결과는 기록만 하고 자동 확정·종료하지 않으며, hold는 같은 Payment의 다른 취소 결과 반영을 막지 않는다. | 확정 | 사용자 | CANCEL-CONTRACT-001 CC-16(D-CANCEL-006·D-AUTH-008 원칙), D-GOV-010 ① | CANCEL-CONTRACT-001 §12, CANCEL-001 §7.1 | `CancellationRecoverySpec::in-flight cancellation with an incident (CC-16)` |
| BR-CAN-042 | 이중 승인 보정은 늦게 확정된 P1을 취소한다 — RECON이 hold를 유지한 채 P1 승인을 거래로 기록하고, 운영자 보정 취소로 P1을 취소한 뒤, 그 취소를 보정 참조로 DOUBLE hold를 해제하며 P2는 유지한다. | 확정 | 사용자 | D-CANCEL-010, D-AUTH-011 | CANCEL-CONTRACT-001 §4.10 | `CancelStartSpec::CX-22 double-approval hold, operator correction starts`(보정 시작만 — RECON 기록·해제 없음) |
| BR-CAN-043 | RECON 기록 뒤 P1 Payment는 FAILED/EXPIRED를 유지하고 Authorization에만 늦은 승인 거래가 추가되며, 주문당 AUTHORIZED 1건·새 결제 제한은 바뀌지 않는다. | 확정 | 사용자 | D-CANCEL-011 | CANCEL-CONTRACT-001 §4.10 | `CancelStartSpec::CX-27 P1 stays FAILED with the order history unchanged`, `CancelStartSpec::RECON recording transition only adds a matching transaction once` |
| BR-CAN-044 | 이중 승인 경로에서 RECON이 hold 중 P1 승인을 기록하는 같은 PAYMENT TX에서 PaymentAuthorized를 발행한다(hold 중 이벤트 없음·해제 판정 전 거래 없음 규칙의 유일한 예외). | 확정 | 사용자 | D-CANCEL-012 | CANCEL-CONTRACT-001 §4.10 | 없음(RECON-CONTRACT-001 BACKLOG) |
| BR-CAN-045 | 단일 늦은 승인을 REAL_APPROVAL_CONFIRMED로 해제한 뒤에도 Payment는 FAILED를 유지하고, 반환은 그 Case에 묶인 2인 승인 운영자 보정 취소로만 하며 가맹점 취소 경로는 열지 않는다. | 확정 | 사용자 | D-CANCEL-013 | CANCEL-CONTRACT-001 §4.8·§4.10 | `CancelStartSpec::CX-27`(취소 관문만 — 해제 경로 없음) |

## 6. 운영자 보정 취소 — PAYMENT 재검사·결정 기록

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-046 | 운영자 보정 취소는 사고·대사 보정(Case 참조) 또는 가맹점 종료 뒤 취소 의무(종료 기록 참조) 목적에만 허용하고, 사유·행위자를 기록하며 2인 승인('Critical 해결' 등급)을 거친다. | 확정 | 사용자 | D-CANCEL-005, D-ADMIN-003 | CANCEL-CONTRACT-001 §4.11, CANCEL-001 §5.1 | `OperatorCorrectionCancelSpec::starts with the audit columns`, `CancelStartSpec::merchant-terminated obligation` |
| BR-CAN-047 | PAYMENT는 운영자 보정 취소 명령의 요청자 ≠ 승인자, 사유·목적 근거 존재, 승인 대상 버전 일치를 다시 검사해 어긋나면 업무 거절하고 자기 감사에 남긴다(관리자 앱 단독 실행 불가). | 확정 | 사용자 | D-ADMIN-007, D-CANCEL-005 | CANCEL-CONTRACT-001 §4.4-7, CANCEL-001 §5.2 | `CancelStartSpec::requester == approver…rejected`, `OperatorCorrectionCancelSpec::same person, missing reason or basis, stale target rejected`, `PaymentCancellationSpec::open refuses requester == approver` |
| BR-CAN-048 | 승인 대상 지문은 paymentId·금액·통화·반환 잔액 revision·approvalId·요청자·승인자·목적 종류·근거 참조로 만들고 사유 문구는 넣지 않으며, revision은 잔액이 업무적으로 바뀔 때마다 올라간다. | 확정 | 사용자 | CANCEL-CONTRACT-001 CC-13, D-GOV-010 ① | CANCEL-001 §5.1 | `CancelStartSpec::approved target binds approvalId, requester, approver, purpose…reason not bound`, `OperatorCancelTargetGoldenSpec`, `OperatorCancelFingerprintGoldenSpec`(ADMIN) |
| BR-CAN-049 | PAYMENT는 운영자 보정 취소 실행 때 ADMIN에 승인 상태를 동기 확인하지 않는다(승인 상태 실재 확인은 인증된 호출자 책임). | 확정 | 사용자 | D-CANCEL1-001 | CANCEL-001 §5.2 | 없음 |
| BR-CAN-050 | PAYMENT가 운영자 보정 취소 명령을 거절하면 거절마다 PAYMENT DB에 append-only 감사 1행(approvalId·요청자·승인자 내부 ID·거절 사유·시각, 개인정보·사유 문구 없음)을 같은 로컬 TX에 남기고 중복 UNIQUE를 두지 않는다. | 확정 | 사용자 | D-CANCEL1-006, D-CANCEL1-008 | CANCEL-001 §18 표, CANCEL-CONTRACT-001 §18 | `OperatorCorrectionCancelSpec::(D-CANCEL1-008) one row per rejection; no reason text`, `OperatorCorrectionCancelSpec::audit row and the rejection share the TX` |
| BR-CAN-051 | PAYMENT는 approvalId별 첫 확정 결정(실행 수락 또는 업무 거절)을 INSERT 전용 UNIQUE 기록으로 남기고 같은 approvalId에는 같은 결정을 돌려주며, 일시 거절(예: CANCEL_IN_PROGRESS)도 그 승인을 소진해 새 승인이 필요하다. | 확정 | 사용자 | D-OPS-005 | ADMIN-PAYMENT-OPS-001 §4.4 | `AdminOperatorCancellationApiSpec::business rejections are decisions (the approval is consumed)`, `AdminOperatorCancellationApiSpec::temporary rejection stays`, `AdminOperatorCancellationApiSpec::concurrency…one decision` |
| BR-CAN-052 | 같은 approvalId에 내용(결제·금액·통화·요청자·승인자·목적·근거·사유·지문·승인 시각·기한)이 다른 명령은 새 결정 없이 409로 거절하고(요청자·승인자만 다르면 `APPROVAL_IDENTITY_MISMATCH`, 그 밖 `APPROVAL_CONFLICT`) 기존 결과를 돌려주지 않는다. | 제안 | 적용 | CANCEL-001 CN-32(Reviewer 확인 요청), ADMIN-PAYMENT-OPS-001 §4.4.2 | ADMIN-PAYMENT-OPS-001 §4.4.1·§4.4.2 | `AdminOperatorCancellationApiSpec::another amount APPROVAL_CONFLICT, other participants APPROVAL_IDENTITY_MISMATCH`, `OperatorCorrectionCancelSpec::another requester or approver identity is APPROVAL_IDENTITY_MISMATCH` |
| BR-CAN-053 | PAYMENT는 승인 시각 + 실행 기한(서명된 본문) 안에서만 새 운영자 보정 취소를 시작하고, 지나면 `APPROVAL_EXECUTION_EXPIRED`로 거절·감사하며, 이미 시작된 approvalId 재요청은 기한과 무관하게 기존 결과다. | 확정 | 사용자 | D-OPS-002 | ADMIN-PAYMENT-OPS-001 §4.4.3 | `AdminOperatorCancellationApiSpec::now >= executeBefore is APPROVAL_EXECUTION_EXPIRED`, `AdminOperatorCancellationApiSpec::already started approval replays after its deadline` |
| BR-CAN-054 | 운영자 보정 취소 실행 기한은 승인 후 24시간이다. | Demo값 | 사용자 | D-OPS-002 | ADMIN-PAYMENT-OPS-001 §4.4.3, §13 | `AdminOperatorCancellationApiSpec::a window over 24h…APPROVAL_WINDOW_INVALID` |
| BR-CAN-055 | 기한 확인 전용 호출에서 PAYMENT는 자기 시각 기준 now ≥ executeBefore이고 그 approvalId 결정이 없을 때만 REJECTED(`APPROVAL_EXECUTION_EXPIRED`)를 기록하고 취소를 시작하지 않으며, 기한 전이면 아무것도 기록하지 않는다. | 확정 | 사용자 | D-OPS-009 | ADMIN-PAYMENT-OPS-001 §4.4.5 | `AdminOperatorCancellationApiSpec::deadline confirmation…no cancellation`, `ArchitectureSpec::deadlineConfirmationNeverStartsCancel` |
| BR-CAN-056 | 보정 목적 근거 참조는 Demo·TEST에서 형식 검사(1~200자, 허용 문자)만 하고 감사에 기록하며, 원천 대조(RECON Case·MASTER 종료 기록)는 LIVE 차단 조건이다. | 확정 | 사용자 | D-OPS-004 | ADMIN-PAYMENT-OPS-001 §4.18 | `OperatorCancelInputRulesSpec`(ADMIN), `CancelStartSpec::over-long or malformed reason or basis reference`(원천 대조는 없음 — LIVE Gate) |
| BR-CAN-057 | 운영자 보정 취소 실행 명령은 서명 인증된 ADMIN 내부 채널로만 PAYMENT에 들어온다(공개 API·다른 adapter에서 호출 불가). | 확정 | 사용자 | D-OPS-001, D-ADMIN-007 | ADMIN-PAYMENT-OPS-001 §4.2·§4.8 | `ArchitectureSpec`(진입점 FQN 허용 규칙 — `archfixture/operatorcancel/PublicPathOperatorCancelController` 위반 fixture), `AdminOperatorCancellationApiSpec` |

## 7. 운영자 보정 취소 — ADMIN 승인·실행·회복·대사

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-058 | 운영자 보정 취소 요청은 OPERATOR, 승인은 APPROVER + TOTP 재입력이며, 한 승인에서 요청자 ≠ 승인자다(역할을 겸해도 자기 요청은 승인할 수 없다). | 확정 | 사용자 | D-ADMIN-003, D-ADMIN-004, D-ADMIN1-004 | ADMIN-PAYMENT-OPS-001 §4.12·§4.13 | `OwnerServiceApprovalFlowSpec::a non-OPERATOR is denied`, `OwnerServiceApprovalFlowSpec::SELF_APPROVAL` |
| BR-CAN-059 | 요청 뒤 승인 대상(잔액 revision·통화·취소 가능액)이 바뀌면 그 승인은 무효(INVALIDATED)다. | 확정 | 사용자 | D-ADMIN-003("요청 후 대상 변경 시 무효") | ADMIN-PAYMENT-OPS-001 §4.13 | `OwnerServiceApprovalFlowSpec::TARGET_CHANGED → INVALIDATED` |
| BR-CAN-060 | 승인이 곧 실행이다 — 승인 TX 커밋 직후 PAYMENT를 호출하고, 결과가 불명확하면 같은 approvalId·같은 저장 본문으로 재전송하며 감사의 실행자는 승인자다. | 확정 | 사용자 | D-ADMIN1-006 | ADMIN-PAYMENT-OPS-001 §4.13·§4.16 | `OwnerServiceApprovalFlowSpec::200 STARTED → EXECUTED`, `OwnerExecutionRecoverySpec::the same stored body bytes are resent` |
| BR-CAN-061 | EXECUTION_UNKNOWN은 PAYMENT 확정 응답(실행/거절)으로만 끝나고, 자동 재전송은 상한 없이 backoff로 계속하며 오래되면 경보하고, 수동 "미실행 확정"은 없다. | 확정 | 사용자 | D-OPS-003 | ADMIN-PAYMENT-OPS-001 §4.15·§4.16 | `OwnerExecutionRecoverySpec::no attempt limit`, `OwnerExecutionRecoverySpec::decision lookup unavailable → state unchanged` |
| BR-CAN-062 | 실행 중·불명 승인의 요청자 또는 승인자가 정지·역할 상실되면 실행 명령 재전송을 멈추고 EXECUTION_UNKNOWN을 유지·경보하되 결정 조회는 계속해 PAYMENT 기록 결정을 반영한다. | 확정 | 사용자 | D-OPS-006 | ADMIN-PAYMENT-OPS-001 §4.16 | `OwnerExecutionRecoverySpec::ResendHaltOnIneligibleParticipantSpec` |
| BR-CAN-063 | 참여자 정지로 멈춘 재전송은 자격을 다시 얻어도 재개하지 않는다. | 확정 | 사용자 | ADMIN-PAYMENT-OPS-001 I-26, D-GOV-010 ① | ADMIN-PAYMENT-OPS-001 §4.16 | `OwnerExecutionRecoverySpec::…no resume after the role is granted back` |
| BR-CAN-064 | PAYMENT의 `APPROVAL_CONFLICT`·`APPROVAL_IDENTITY_MISMATCH`는 확정 응답이 아니며, ADMIN은 종료하지 않고 EXECUTION_UNKNOWN·무결성 경보를 유지한 채 재전송 없이 결정 조회로 PAYMENT 기록 결정을 반영한다. | 확정 | 사용자 | D-OPS-007 | ADMIN-PAYMENT-OPS-001 §4.15 | `OwnerServiceApprovalFlowSpec::result mapping: derived 409 → EXECUTION_UNKNOWN + integrity flag`, `OwnerExecutionRecoverySpec::IntegrityFlagNoResendSpec` |
| BR-CAN-065 | PAYMENT 기록 결정의 내용이 ADMIN 승인과 다르면 무결성 플래그를 먼저 남기고 경보·감사한 뒤 그 결정을 그대로 반영한다(반영을 보류하지 않음). | 제안 | 적용 | ADMIN-PAYMENT-OPS-001 I-35(§12.2 "보류를 원하면 사용자 질문") | ADMIN-PAYMENT-OPS-001 §4.15 | `OwnerExecutionRecoverySpec::DecisionContentCheckSpec` |
| BR-CAN-066 | 재전송이 중단됐거나 결정이 없는 미해결 승인은 실행 기한 경과 후 기한 확인 전용 호출로 정리하고, REJECTED(EXPIRED)면 OWNER_REJECTED로 종료해 그 Payment의 새 승인 요청 차단을 푼다. | 확정 | 사용자 | D-OPS-009 | ADMIN-PAYMENT-OPS-001 §4.15·§4.16 | `OwnerExecutionRecoverySpec::DeadlineConfirmationRecoverySpec ①~⑤` |
| BR-CAN-067 | Payment당 미해결(PENDING·EXECUTING·EXECUTION_UNKNOWN) 운영자 보정 취소 승인은 1건이며 두 번째 요청은 409 `DUPLICATE_UNRESOLVED`다. | 제안 | 적용 | ADMIN-PAYMENT-OPS-001 §4.11·§4.12(unresolved_key — 사용자 결정 없음) | ADMIN-PAYMENT-OPS-001 §4.12 | `OwnerServiceApprovalFlowSpec::409 DUPLICATE_UNRESOLVED` |
| BR-CAN-068 | 진행 중 취소가 있는 Payment는 보정 취소 요청 단계에서 거절하고, 승인 단계에서는 승인을 PENDING으로 두고 TOTP를 소비하지 않는다(일시 거절로 승인이 소진되는 것을 막음). | 제안 | 적용 | ADMIN-PAYMENT-OPS-001 I-32(L-7) | ADMIN-PAYMENT-OPS-001 §4.12·§4.13 | `OwnerServiceApprovalFlowSpec::advisory (L-7)`, `OwnerServiceApprovalFlowSpec::TARGET_BUSY` |
| BR-CAN-069 | PAYMENT 운영자 결정 목록과 ADMIN 승인을 주기적으로 대사해 지문이 맞는 승인 없는 실행·상태 불일치를 경보하며, 대사는 승인 상태를 바꾸지 않는다. | 확정 | 사용자 | D-OPS-008(상태 불변·발견 종류는 §4.17 적용) | ADMIN-PAYMENT-OPS-001 §4.17 | `OperatorDecisionReconciliationRunSpec::each finding is recorded once…no approval state changes`, `OperatorDecisionReconciliationRunSpec::STATE_DIVERGENCE`, `OperatorDecisionReconciliationRunSpec::ReconciliationFullScanSpec` |
| BR-CAN-070 | 운영자 보정 취소의 근거 참조·사유 문구는 APPROVER와 요청자 본인에게만 별도 권한 조회로 보이고 볼 때마다 조회 감사를 남기며, 목록·그 밖 역할에는 숨긴다. | 확정 | 사용자 | D-OPS-010 | ADMIN-PAYMENT-OPS-001 §4.20(S8) | `OperatorCancelJustificationHttpSpec::requester and an APPROVER read it…audited`, `OperatorCancelJustificationHttpSpec::another OPERATOR, SECURITY_ADMIN, VIEWER get 403` |

## 8. 수수료·정산으로 넘기는 취소 사실

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-071 | 취소 수수료/VAT 반환은 원승인의 수수료·정책 Snapshot을 기준으로 하고 취소일 현재 정책을 다시 적용하지 않는다. | 확정 | 기준 | 초기 확정사항 85행, 정산 Control Policy §1·§29, INV-10 | CANCEL-CONTRACT-001 §5.1 | `PaymentCancellationSpec::copies the original policy and entitlement versions`, `CancelStartSpec::CB-08 never a current one`(PAYMENT 전달만 — 계산 없음) |
| BR-CAN-072 | 수수료 계산은 SETTLEMENT 소유이며 PAYMENT는 계산하지 않고 원 Authorization의 정책·권리 버전과 원 거래 ID를 취소 사실에 복사해 넘긴다. | 확정 | 사용자 | CANCEL-CONTRACT-001 §5.1 도출(K-4 + D-MS2-003), D-CANCEL-008("계산 소유는 SETTLEMENT"), D-GOV-010 ① | CANCEL-CONTRACT-001 §5.1 | `PaymentCancellationSpec::CX-11` |
| BR-CAN-073 | 부분취소의 k번째 수수료 반환 = round(원 수수료 × 누적 취소액 ÷ 승인액) − 이전 반환 합이고 VAT도 같으며, 반올림은 원승인 Snapshot 규칙, 마지막 취소가 끝전을 정확히 흡수하고 별도 취소 수수료는 없다. | 확정 | 사용자 | D-CANCEL-008 | CANCEL-CONTRACT-001 §5.1 | 없음(SETTLEMENT-001 BACKLOG — PAYMENT 입력만 `CancellationResolutionSpec::CR-01 completion 1/3,000`) |
| BR-CAN-074 | 고정 수수료와 그 VAT는 누적 취소액이 승인액에 닿는 취소(단일 전액 취소 또는 마지막 부분취소)에서 반환하고, 누적 비례식은 정률 부분과 그 VAT에만 쓰며, 최소 수수료 하한은 고정 부분으로 본다. | 확정 | 사용자 | D-CANCEL-009 | CANCEL-CONTRACT-001 §5.1 | 없음(계산 미구현 — 판정 입력만 `CancellationResolutionSpec::last PARTIAL…FULLY_CANCELLED (D-CANCEL-009)`) |
| BR-CAN-075 | 완료된 취소는 FINANCIAL 사실 이벤트 1건으로 원 거래 ID·승인액·Payment 안 반환 순번·반환 후 누적액·반환 상태·기관 취소 거래일·PG 확정 시각·개시자 종류를 넘기고 카드번호·Secret·운영자 개인정보·기관 계약계정은 넣지 않는다. | 확정 | 사용자 | CANCEL-CONTRACT-001 CC-08, CANCEL-001 CN-13, D-AUTH-003(업무 키 = transactionId), D-GOV-010 ① | CANCEL-CONTRACT-001 §9.3, CANCEL-001 §4.10 | `CancelFlowSpec::PaymentCancelled once`, `CancelFlowSpec::PaymentCancelled names the OPERATOR initiator` |
| BR-CAN-076 | 취소의 정산 거래일은 취소일이며 원 승인일로 돌리지 않고, 승인과 취소는 각각 독립 정산 항목이다. | 확정 | 기준 | 초기 확정사항 87·96행, 정산 §3·§29 | CANCEL-CONTRACT-001 §5.3 | 없음(SETTLEMENT BACKLOG) |
| BR-CAN-077 | 취소일은 기관이 확정한 취소 사실의 날짜(기관 취소 거래일)이고 PG 회복·확정 시각과 섞지 않는다(시간대·기관 거래일 규약은 미결). | 확정 | 사용자 | CANCEL-CONTRACT-001 §5.3 도출(K-8 + K-9), Q-CALENDAR PROPOSED, D-GOV-002 ③, D-GOV-010 ② | CANCEL-CONTRACT-001 §5.3 | `PaymentCancellationSpec::institution business date is preserved`(보존만) |
| BR-CAN-078 | PAYMENT는 매입 전/후로 취소를 나누지 않고 원 경로로 기관 취소 1건만 요청하며, 매입 쪽 처리는 FINANCIAL이 취소 사실로 정한다. | 확정 | 사용자 | CANCEL-CONTRACT-001 §4.9 도출(INV-02 + 초기 58행), D-GOV-010 ① | CANCEL-CONTRACT-001 §4.9 | 없음 |

## 9. 계좌 환불(Refund)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-079 | v1에서 계좌 환불은 범위 밖이며 카드 결제 반환은 카드 취소로만 하고, API·운영 안내에 계좌 환불 미지원을 표시한다. | 확정 | 사용자 | D-CANCEL-007 | CANCEL-CONTRACT-001 §6, CANCEL-001 §4.11 | 없음(반환 종류가 취소뿐임은 `HomeKpiReaderSpec::T-K1-12 type CHECK {AUTHORIZATION, CANCEL}`; 안내 표시는 시험·화면 없음) |
| BR-CAN-080 | 취소 FAILED도 기관의 늦은 성공이 나중에 확인될 수 있으므로 최종 무반환으로 단정해 다른 수단으로 반환하지 않도록 안내하고, PayCore는 다른 반환 수단으로 자동 전환하지 않는다. | 확정 | 사용자 | CANCEL-001 §4.11(D-CANCEL-006·007에서 옮긴 안내 문구), D-GOV-010 ① | CANCEL-001 §4.11 | 없음 |

## 10. 가맹점 노출(조회·통지)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-081 | AUTHORIZED Payment의 운영자 보정 취소는 가맹점 조회의 취소 이력에 개시자 OPERATOR로만 보이고 운영자 신원·사유·approvalId·목적은 보이지 않는다. | 확정 | 사용자 | D-CPQ-002 | CP-QUERY-001 | `GetPaymentApiSpec::authorized Payment with an operator correction cancel`, `PaymentStatusViewsSpec::merchant and operator cancels (D-CPQ-002)` |
| BR-CAN-082 | 가맹점에 FAILED/EXPIRED로 알려진 P1의 늦은 승인 거래와 그 운영자 취소는 가맹점 조회·Webhook에서 숨기고 FAILED와 실패 분류만 보인다. | 확정 | 사용자 | D-CPQ-002, D-CANCEL-013, D-WH-001 | CP-QUERY-001, WEBHOOK-001 §4.1 | `PaymentStatusViewsSpec::only FAILED and its category are shown`, `MerchantNotificationPolicySpec`, `MerchantNotificationFanoutServiceSpec::P1 operator cancel suppressed` |
| BR-CAN-083 | AUTHORIZED Payment의 운영자 보정 취소 결과는 취소 완료/실패 Webhook으로 알리되 개시자 OPERATOR만 담는다(merchantCancelId·운영자 신원·사유 없음). | 확정 | 사용자 | D-WH-001 | WEBHOOK-001 | `MerchantWebhookBodyJsonSpec::payment.cancelled by an operator: initiator only` |
| BR-CAN-084 | 가맹점 조회·취소 응답의 취소 가능액은 FORCED를 뺀 권위 값 max(0, …)을 그대로 보이며, 다른 금액과의 차로 FORCED를 역산할 수 있는 것은 허용된 추론이다. | 확정 | 사용자 | D-CPQ-005 | CP-QUERY-001, ADMIN-PAYMENT-OPS-001 F-1 | `OperatorCorrectionCancelSpec::cancellable amount is 0, never negative`, `OperatorCorrectionCancelSpec::FORCED within the balance keeps the remainder` |
| BR-CAN-085 | 가맹점 취소 FAILED 응답은 실패 분류(DECLINED·NOT_SENT·NO_EFFECT)만 주고 기관 거절 세부 분류는 노출·영속하지 않는다. | 확정 | 사용자 | CP-QUERY-001 F-7(Coordinator 판정 11), D-GOV-010 ① | CANCEL-001 §4.1 정정, CANCEL-CONTRACT-001 §18 | `CancelPaymentApiSpec::200 FAILED with failureCategory DECLINED` |
| BR-CAN-086 | 취소 응답·이벤트에는 카드번호·기관 원문·내부 정책 버전·기관 계약계정을 넣지 않는다. | 확정 | 기준 | CLAUDE.md(Secret·카드정보 비노출), D-MS2-005 | CANCEL-CONTRACT-001 §8, CANCEL-001 §4.1 | `CancelPaymentApiSpec::no institution account, raw message or card data` |

## 11. 거래 운영 조회

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-087 | 관리자 거래 목록은 생성일 기간 필수 + 환경·MID·결제 상태 필터, 결제 ID 정확 조회, MID + 가맹점 주문번호 조회, 최신순 keyset 페이지를 제공하고, 상세는 승인·취소 이력·반환 잔액을 보인다. | 확정 | 사용자 | D-TXOPS-001 | TXN-OPS-001 §3 | `AdminPaymentReaderSpec::period [from, to), filters exact, newest first`, `AdminPaymentReaderSpec::the detail reads payment, authorization, transactions, cancellations, return balance and hold` |
| BR-CAN-088 | 거래 목록 조회 기간은 최대 31일이다. | Demo값 | 사용자 | D-TXOPS-001 | TXN-OPS-001 §3.1, §9.1 L-3 | `TransactionLookupServiceSpec::at most 31 days and not inverted` |
| BR-CAN-089 | 결제 ID 정확 조회와 MID + 주문번호 조회는 기간 없이 허용한다. | 제안 | 적용 | TXN-OPS-001 I-3(Implementer 해석) | TXN-OPS-001 §2 | `TransactionLookupServiceSpec::the period is required (except the exact paymentId and order lookups)` |
| BR-CAN-090 | 거래 상세 조회만 PAYMENT_VIEWED로 감사하고(반환 전 기록, 감사 실패 시 결과 미반환) 목록 검색(결제 ID 정확 조회 포함)은 감사하지 않는다. | 확정 | 사용자 | D-TXOPS-002 | TXN-OPS-001 §5.1 | `TransactionLookupServiceSpec::FOUND and NOT_FOUND are audited as PAYMENT_VIEWED`, `TransactionLookupServiceSpec::searches…never audited`, `TransactionAuditFailureHttpSpec::503 AUDIT_UNAVAILABLE` |
| BR-CAN-091 | 거래 목록은 주문명 앞 10자와 잘림 여부만, 상세(감사 대상)는 전체 주문명·승인번호·기관 거래번호를 보이고, 기관 내부 계정 값은 어디에도 내보내지 않는다. | 확정 | 사용자 | D-TXOPS-003, D-MS2-005 | TXN-OPS-001 §5.3 | `AdminPaymentReaderSpec::only the first 10 characters`, `AdminPaymentReaderSpec::non-exposure: the institution account…in no field` |
| BR-CAN-092 | 거래 목록 CSV/엑셀 내려받기는 제공하지 않는다. | 확정 | 사용자 | D-TXOPS-004 | TXN-OPS-001 §1 | 없음 |
| BR-CAN-093 | 거래 상세의 운영자 보정 취소는 개시 주체 종류만 보이고, 사유·근거·요청자·승인자·approvalId는 기존 승인 화면에서만 본다. | 확정 | 사용자 | D-TXOPS-005 | TXN-OPS-001 §3.4·§5.3 | `AdminPaymentReaderSpec::non-exposure: …the operator reason…in no field` |
| BR-CAN-094 | 거래 조회·상세는 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN만 할 수 있다. | 제안 | 적용 | TXN-OPS-001 I-1(Implementer 해석) | TXN-OPS-001 §5.2 | `TransactionLookupServiceSpec::view roles (I-1)` |
| BR-CAN-095 | 관리 조회는 반환 잔액의 저장 값만 보이고 취소 가능액 식을 화면·조회에서 다시 만들지 않는다. | 확정 | 사용자 | TXN-OPS-001 I-6, D-GOV-010 ① | TXN-OPS-001 §2 | `AdminPaymentReaderSpec::the detail reads…return balance` |
| BR-CAN-096 | 거래 목록의 가맹점 표시값(상호·가맹점 코드)은 MASTER 응답이 일부라도 이상하면 전부 버리고 MID만 보이며(틀린 상호를 다른 거래에 붙이지 않음) 목록 자체는 그대로 돌려준다. | 확정 | 사용자 | TXN-LIST-ENRICH-001 §3.3(Coordinator C-), D-GOV-010 ① | TXN-LIST-ENRICH-001 §3.3 | `TransactionDetailLabelSpec::a label failure still audits and returns the detail with merchant null and merchantLabels UNAVAILABLE`, `TransactionLookupHttpSpec::T-E3-8` |

## 12. 운영 홈 KPI(취소 차감 정의)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-097 | 오늘 순승인액 = [KST 오늘 0시, 조회 시각)에 확정된 승인 금액 합 − 같은 구간에 확정된 취소 금액 합이며, 부분취소·이전 날짜 승인의 오늘 취소를 포함하고 음수가 될 수 있다. | 확정 | 사용자 | D-ADMIN-HOME-001 ①② | ADMIN-HOME-KPI-001 §3.2 | `HomeKpiReaderSpec::T-K1-3 net 7,000`, `HomeKpiReaderSpec::T-K1-5 net −5,000`, `HomeKpiReaderSpec::T-K1-15 operator correction cancel subtracted` |
| BR-CAN-098 | 오늘 승인 건수는 오늘 확정된 승인 중 조회 시각까지 전액취소되지 않은 결제 수다(부분취소 건 포함). | 확정 | 사용자 | D-ADMIN-HOME-001 ③ | ADMIN-HOME-KPI-001 §3.3 | `HomeKpiReaderSpec::T-K1-4 net 0 and count 0`, `HomeKpiReaderSpec::T-K1-13 full cancel after asOf` |
| BR-CAN-099 | 종료(FAILED/EXPIRED) Payment에 추가된 늦은 승인과 그 보정 취소는 순승인액·건수에서 둘 다 빼고 "확인 필요"로 센다. | 확정 | 사용자 | D-ADMIN-HOME-002 ① | ADMIN-HOME-KPI-001 §3.2, §11 C-6 | `HomeKpiReaderSpec::T-K1-8 both…excluded`("확인 필요" 셈은 없음 — ADMIN-HOME-ATTENTION-001 BACKLOG) |
| BR-CAN-100 | 늦은 취소 성공 FORCED는 차감·전액취소 판정에서 빼고 "확인 필요"로 센다. | 확정 | 사용자 | D-ADMIN-HOME-002 ② | ADMIN-HOME-KPI-001 §3.2 | `HomeKpiReaderSpec::T-K1-7 FORCED is neither subtracted nor part of the full-cancel test`("확인 필요" 셈은 없음) |
| BR-CAN-101 | KPI의 "오늘 완료" 시각 원천은 PG 확정 시각(confirmed_at, KST)이며 정산 금액이 아님을 표시한다. | 확정 | 사용자 | D-ADMIN-HOME-002 ③ | ADMIN-HOME-KPI-001 §3.1 | `HomeKpiReaderSpec::T-K1-1 confirmed_at is the PG confirmation instant`, `HomeKpiReaderSpec::T-K1-17 counts on the day it was confirmed`, `HomeScreen.test.tsx::PG 확정 시각 기준 · 정산 금액 아님` |
| BR-CAN-102 | KPI는 통화별·환경별(TEST/LIVE 합산 금지)로 나누고, 결과 미확정(UNKNOWN)·진행 중 승인·취소는 금액에서 빼고 "확인 필요"로 센다. | 확정 | 사용자 | D-ADMIN-HOME-001 "코디네이터 구현 기본값(사용자 확인 대상 아님)", D-GOV-010 ⑤ | ADMIN-HOME-KPI-001 §0, §3.4 | `HomeKpiReaderSpec::T-K1-6 unconfirmed facts`, `HomeKpiReaderSpec::T-K1-6 full cancel left OPEN`(환경·통화 분리는 DB 수준 시험 없음 — §14 L-7) — UNKNOWN·진행 중 건수 셈 없음(KPI-POLICY-001 G-1, ADMIN-HOME-ATTENTION-001) |
| BR-CAN-103 | 지급 예정액은 정산 기능이 구현되기 전까지 숫자 없이 "정산 기능 준비 전"으로 표시한다(가짜 숫자 금지). | 확정 | 사용자 | D-ADMIN-HOME-001 ④ | ADMIN-HOME-KPI-001 §2 | `HomeScreen.test.tsx::no fabricated numbers…"정산 기능 준비 전"` |
| BR-CAN-104 | 운영 홈 KPI는 조회 전용·비권위 표시값이며 금융 효과가 없고 정산·대사 판정에 쓰지 않는다. | 확정 | 사용자 | D-ADMIN-HOME-001("조회 전용·금융 효과 없음") | ADMIN-HOME-KPI-001 §0, §14 L-2 | 없음 |
| BR-CAN-109 | 운영 홈 KPI의 승인금액은 집계 구간에 확정된 승인 거래 금액의 합(활성 hold 건 포함 여부는 Q-KPI-2), 취소금액은 같은 구간에 확정된 취소 거래 금액의 합(전액·부분취소, 이전 날짜 승인의 취소 포함), 순결제금액(= 현 화면 순승인액, 명칭은 Q-KPI-3)은 승인금액 − 취소금액(음수 가능)이며 모두 (환경, 통화)별 minor unit 정수다. | 확정 | 사용자 | D-GOV-010 ⑤, D-ADMIN-HOME-001 ①②, KPI-POLICY-001 §2.2 N-1 | ADMIN-HOME-KPI-001 §3.2 | `HomeKpiReaderSpec::T-K1-3 a partial cancel today of today's approval`, `HomeKpiReaderSpec::T-K1-5 yesterday's approval cancelled today`, `HomeKpiReaderSpec::T-K1-15 an operator correction cancel`, `HomeKpiWindowSpec::figures … net = approved − cancelled and may be negative` — 화면은 순액만 표시(승인·취소금액 표시·명칭 확인 불가 — KPI-POLICY-001 V-13) |
| BR-CAN-110 | 운영 홈 KPI 금액은 확정 거래 하나당 그 확정일에 한 번만 세고, 같은 사실의 재처리(결과 재수신·재조회·Recovery·대사 재확인·같은 요청키 재시도)로 다시 세지 않는다. 반복 부분취소는 각 확정 취소 거래를 한 번씩 차감하고, 누적 확정 취소액이 승인액과 같아지면 전액취소로 보아 승인 건수에서 뺀다(늦은 성공 FORCED는 누적액에 넣지 않는다). | 확정 | 사용자 | D-GOV-010 ⑤, D-ADMIN-HOME-001 ③, D-ADMIN-HOME-002 ②, KPI-POLICY-001 §2.2 N-2(원칙 부분) | ADMIN-HOME-KPI-001 §3.2·§3.3 | `HomeKpiReaderSpec::T-K1-4 full cancels today`(부분 3,000 + 7,000 → 건수 0), `HomeKpiReaderSpec::T-K1-7 a FORCED late success of a declined cancel`, `CancelStartSpec::CX-04 after 3,000 completed 7,001 is rejected` — KPI 수준 재처리 뒤 합계·건수 불변 시험 없음(KPI-POLICY-001 V-20·G-4) |
| BR-CAN-111 | 운영 홈 KPI는 집계 기준일·구간(KST 기준일 0시부터 조회 시각 전까지)과 시간대, PG 확정 시각 기준임을 응답과 화면에 명시한다. | 확정 | 사용자 | D-GOV-010 ⑤, D-ADMIN-HOME-001 ②, KPI-POLICY-001 §2.2 N-3 | ADMIN-HOME-KPI-001 §3.1 | `AdminHomeKpiApiSpec::T-K2-3 exact body bytes`, `HomeKpiGatewaySpec::T-K3-5 shape and this-request violations`(응답 기준일·시간대 일치) — 화면 구간·시간대 표기 불일치("HH:MM 기준"만, KPI-POLICY-001 V-31·G-5) |

## 13. 대체된 규칙

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-105 | 운영자 보정 취소는 provided Use Case만 두고 inbound 진입점·서비스 간 인증을 열지 않는다(운영 환경 실행 경로 없음). | 대체됨→BR-CAN-057 | 사용자 | D-CANCEL1-001(진입점 부분), D-OPS-001 | CANCEL-001 §5.3(현행화 주석 있음) | 없음(구 ArchUnit 규칙은 진입점 FQN 허용으로 바뀜) |
| BR-CAN-106 | 같은 approvalId·같은 금액이면 기존 취소 결과, 다른 금액이면 충돌로 판정한다. | 대체됨→BR-CAN-051, BR-CAN-052 | 적용 | CANCEL-CONTRACT-001 CC-12, CANCEL-001 CN-10 → D-OPS-005·CN-32 | CANCEL-CONTRACT-001 §4.5·§4.11 | `OperatorCorrectionCancelSpec::the same approval and amount returns the existing cancellation`(결정 행 없는 옛 데이터 경로 `OperatorDecisionLegacySpec`) |
| BR-CAN-107 | 가맹점 취소 FAILED(DECLINED)에 기관 거절 분류(`declineCategory`: CANCEL_PERIOD_EXPIRED 등)를 노출한다. | 대체됨→BR-CAN-085 | 적용 | CANCEL-001 CN-02 → CP-QUERY-001 F-7 | CANCEL-001 §4.1(정정 표시) | 없음 |
| BR-CAN-108 | "같은 기관 결정" = 같은 attempt + 결정번호/PTX이면 다른 출처로 와도 같은 사건이다. | 대체됨→BR-CAN-040 | 사용자 | D-CANCEL1-005 → D-CANCEL1-007(확정 조건까지 같아야 같은 사건) | CANCEL-001 §18.2c | — |

## 진단

### (a) 대체 사슬

1. 운영자 보정 취소 진입점: D-CANCEL1-001("Use Case만, 진입점은 ADMIN-BOOT-001이 연다") → 실제로는 ADMIN-PAYMENT-OPS-001이 D-OPS-001(HMAC 서명 채널)로 열었다. "PAYMENT는 ADMIN에 승인 상태를 동기 조회하지 않는다"는 남아 있다(BR-CAN-049). 진입점 부분 BR-CAN-105 → BR-CAN-057.
2. 운영자 업무 키: CC-12/CN-10(approvalId UNIQUE, 같은 금액 = 기존 결과) → CN-32(요청자·승인자 다르면 `APPROVAL_IDENTITY_MISMATCH`) → D-OPS-005(approvalId별 첫 결정 INSERT-only, 내용 hash 비교) + D-OPS-007(파생 충돌은 비확정). BR-CAN-106 → BR-CAN-051·052.
3. 거절 감사: D-CANCEL1-006(PAYMENT DB 거절 감사 행) → D-CANCEL1-008(거절마다 1행, UNIQUE 없음) → D-OPS-005(결정 행 추가, 감사 행은 요청마다 유지). 셋 모두 유효, 뒤가 앞을 구체화.
4. 사건 동일성: D-CANCEL1-002(사건마다 이벤트) → D-CANCEL1-005(같은 기관 결정 다른 출처 = 같은 사건) → D-CANCEL1-007(확정 조건까지 같아야 같은 사건). BR-CAN-108 → BR-CAN-040.
5. 수수료 반환: D-CANCEL-008("고정 수수료는 전액 취소 때만") → D-CANCEL-009("전액 취소" = 누적 취소액이 승인액에 닿는 취소, 최소 수수료 하한 = 고정 부분). 대체가 아니라 구체화.
6. 늦은 승인 거래 시점: D-AUTH-003("늦은/상충 승인은 hold 해제 판정 전 거래 미생성")·AUTH §8.1("보류 중 PaymentAuthorized 없음") → D-CANCEL-011(P1 Authorization에 RECON 기록 거래)·D-CANCEL-012(이중 승인 경로 한정 유일 예외). PAY 대장의 D-AUTH-003 행에 예외 표시가 필요하다.
7. 미해결 승인 출구: D-OPS-003(기한 지나면 다음 재전송이 확정 거절로 자동 종결) + D-OPS-006(정지 시 재전송 중단) 사이 구멍 → D-OPS-009(기한 확인 전용 호출).
8. 가맹점 거절 분류 노출: CN-02(declineCategory 노출) → CP-QUERY-001 F-7 Coordinator 판정 11(노출·영속 안 함). BR-CAN-107 → BR-CAN-085(이 대체는 사용자 결정이 아니라 Coordinator 판정이다).
9. 거래 상세의 운영자 취소 표시: TXN-OPS-001 I-2(Implementer 해석) → D-TXOPS-005(사용자 확정).
10. 홈 KPI 시각 원천: D-ADMIN-HOME-001 ②("정산일 규칙과 같은 완료일 기준") → D-ADMIN-HOME-002 ③(PG 확정 시각 confirmed_at). (b)-2 참조.

### (b) 결정 간 충돌

1. **D-CANCEL-006 vs D-CANCEL-001·005**: D-CANCEL-006은 "hold 해제 전 그 Payment **추가 취소** 차단"이라고만 했고, 계약 §4.10이 이를 "(가맹점) 취소"로 좁혀 운영자 보정은 허용한다(BR-CAN-037, `CancelStart.kt:96`). 보정이 해제의 선행 조건이라는 D-CANCEL-001·005와 맞추려면 좁히는 게 자연스럽지만, 사용자가 확인한 적은 없다.
2. **D-ADMIN-HOME-001 ② vs D-ADMIN-HOME-002 ③ vs 정산 거래일**: ②는 "정산일 규칙과 같은 완료일 기준"이라고 했는데, ③은 PG 확정 시각(confirmed_at)을 골랐다. 정산 거래일은 기관 취소 거래일·영업일 보정이다(BR-CAN-076·077). 늦게 확정된 UNKNOWN 취소나 자정 근처 건은 두 날짜가 다르다. 화면 표지 "정산 금액 아님"으로 고지했을 뿐, 결정 문장 둘이 서로 다른 기준을 말한다.
3. **D-CANCEL1-001 문언 vs 실제 작업**: 진입점을 ADMIN-BOOT-001이 연다고 했지만 실제로는 ADMIN-PAYMENT-OPS-001이 열었다. 의미 충돌은 없고 결정 문장만 사실과 다르다.
4. **D-TXOPS-001 "생성일 기간 필수" vs I-3**: 결제 ID 정확 조회·MID+주문번호 조회는 기간을 면제했다(BR-CAN-089). 결정 문언과 다른 해석이다.
5. **D-CANCEL-002 "실패한 취소 재시도는 새 ID" vs n-8**: 업무 거절(취소 행 없음)된 ID는 재사용을 허용한다(BR-CAN-022). "실패"를 FAILED 취소로만 읽은 해석이라 충돌은 아니지만, 경계가 결정에 적혀 있지 않다.

### (c) 명세 본문이 현재 결정과 다른 곳

1. CANCEL-CONTRACT-001 제목·입력 표는 "D-CANCEL-001~012"인데 본문은 D-CANCEL-013까지 반영했다. §4.5·§5.1 "(질문 배경 기록:) … → §13"과 §6 "권장" 문단에는 답 전 문장이 남아 있다(머리말에서 결정이 우선한다고 선언).
2. CANCEL-CONTRACT-001 §4.5·§4.11 CC-12 "같은 approvalId·같은 금액 = 기존 operation"은 D-OPS-005(내용 hash 전체 비교)·CN-32와 다르다. §18 편차 표에만 CN-32가 있고 D-OPS-005는 없다.
3. CANCEL-CONTRACT-001 §9.3: `CancellationIncidentDetected` effect key `:{occurrence}`는 D-CANCEL1-002 `:{cancellationId}:{incidentSeq}`와 다르고(§18에 표시), `PaymentCancelled` 키는 "`{cancellationId}` 검토"로 남아 있는데 CN-13은 `{transactionId}`로 정했다.
4. CANCEL-001 §5.2·§5.3: "진입점 없음·서비스 간 인증 없음"은 현행화 주석만 달고 본문을 그대로 두었다(ADMIN-PAYMENT-OPS-001로 대체). §4.1 CN-02 declineCategory도 정정 표시만 했다.
5. 오류 code 이름이 경로마다 다르다: 가맹점 API는 `CANCEL_AMOUNT_EXCEEDS_BALANCE`(CANCEL-001 §4.9), domain과 운영자 경로는 `AMOUNT_EXCEEDS_BALANCE`(ADMIN-PAYMENT-OPS-001 §4.4.1, `CancelStart.kt:36`). 의미 차이는 없고 대장에서 code 이름은 규칙으로 두지 않았다.
6. CANCEL-CONTRACT-001 §9.1은 "`return_balance` FOR UPDATE가 직렬화 지점"이라고 했지만, CANCEL-001 §4.7은 실제 직렬화 지점이 control X이고 return_balance는 두 번째 방어라고 정정했다(구현 관례라 대장 밖).
7. D-ADMIN-HOME-001 ②는 "취소·환불 합계"를 차감한다고 했다. 환불은 v1 범위 밖이고(D-CANCEL-007) ADMIN-HOME-KPI-001 §3.5가 "새 반환 타입이 생기면 재정의"로 처리했다. 결정 문언에 범위 밖 항목이 남아 있다.
8. TXN-OPS-001 §2 표는 D-TXOPS-001 행에 "기간 필수"를 적고, 바로 아래 I-3이 정확 조회는 면제한다고 적었다. 결정 행 문언과 적용이 같은 표 안에서 다르다.

### (d) 시험 없는 확정 규칙

| 규칙 | 이유 |
|---|---|
| 대상 BR-CAN-031 이관 UNKNOWN 정리 전 LIVE 금지 | LIVE Gate 항목, 자동 검사 없음 |
| 대상 BR-CAN-044 RECON 기록 TX의 PaymentAuthorized(D-CANCEL-012) | RECON-CONTRACT-001·RECON-001 BACKLOG |
| 대상 BR-CAN-042·045 이중 승인·단일 늦은 승인 해제 경로 | 취소 시작 관문만 시험, RECON 기록·해제 Use Case 없음 |
| 대상 BR-CAN-049 ADMIN 동기 조회 없음 | 부재를 확인하는 구조 시험이 없음 |
| 대상 BR-CAN-056 근거 원천 대조 LIVE 차단 | 형식 검사만 시험, LIVE Gate 차단 조건 검사 없음 |
| 대상 BR-CAN-073·074 누적 비례·고정 수수료 반환 | SETTLEMENT-001 BACKLOG, PAYMENT 입력값만 시험 |
| 대상 BR-CAN-076 취소 정산 거래일 = 취소일 | SETTLEMENT BACKLOG |
| 대상 BR-CAN-079 계좌 환불 미지원 안내 표시 | 안내 문구가 명세에만 있고 API 문서·관리자 화면·시험 모두 없음 |
| 대상 BR-CAN-092 CSV 미제공 | 부재 규칙, 시험 없음 |
| 대상 BR-CAN-099·100의 "확인 필요로 센다" 부분 | 제외만 시험, 셈은 ADMIN-HOME-ATTENTION-001 BACKLOG |
| 대상 BR-CAN-104 KPI 비권위 | 사용처 제한 검사 없음 |

또 확정 규칙 대부분은 시험이 있지만 시험 이름·주석에 BR-ID가 없다. 현재 연결 고리는 CX-/CR-/CB-/K-·T-K 번호와 D-ID뿐이다(RULE-TRACE 6단계 대상).

### (e) 코드가 규칙과 다른 곳

1. `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/domain/CancelStart.kt:96` — hold 검사가 `initiator is CancelInitiator.Merchant`일 때만 걸린다. D-CANCEL-006 문언("추가 취소 차단")보다 좁고, 계약 §4.10 해석(BR-CAN-037, 제안)과는 같다. 사용자 확인 전까지는 문언과 다른 곳으로 둔다.
2. D-CANCEL-007 "API·운영 안내에 계좌 환불 미지원 표시": `apps/` 전체(kt·ts·tsx·html·yaml·json)를 grep해도 "계좌 환불" 안내가 없다. 문구는 `docs/repository/CANCEL-001-CANCELLATION.md:381-400`에만 있다. CANCEL-001은 "운영 화면이 생기면 옮긴다"고 했는데, 관리자 거래 조회·보정 취소 화면은 이미 있다.
3. D-ADMIN-HOME-002 ①②의 "확인 필요로 셈": `apps/internal-admin/frontend/src/screens/HomeScreen.tsx:377`의 "확인이 필요한 업무" 카드는 기존 원천(승인 대기·대사 발견)만 센다. UNKNOWN 승인·진행 중 취소·FORCED·종료 Payment 늦은 승인은 세지 않는다(C-6, ADMIN-HOME-ATTENTION-001 BACKLOG로 Coordinator가 미룸).
4. 그 밖에 이번에 대조한 판정(CancelStart 검사 순서·거절 code, ReturnStatus 식 `ReturnBalance.kt:173-175`, 홈 KPI `p.status='AUTHORIZED'` 조건 `JooqHomeKpiReader.kt:42·56`, 가맹점 조회의 P1 숨김 `PaymentStatusViews.kt:74`)은 규칙과 일치했다. 전수 대조는 아니다.

### (f) 미결·질문 후보

| 후보 | 권장안 | 막히는 것 |
|---|---|---|
| Q-CAN-1 취소 사건 hold 중 운영자 보정 취소 허용 범위(D-CANCEL-006 문언) | 가맹점만 차단, 운영자 보정 허용(현행 BR-CAN-037) | 대장 확정, RECON 해제 설계 |
| Q-CAN-2 "취소일"(정산 거래일) 원천 = 기관 취소 거래일 vs PG 확정 시각, 홈 KPI와 정산의 날짜 차이 고지 범위 | 정산 = 기관 취소 거래일(시간대는 Q-CALENDAR), KPI = PG 확정 시각 유지 | SETTLEMENT-CONTRACT-001 |
| Q-CAN-3 승인 결과 미확정(AUTHORIZING·UNKNOWN) Payment 취소 불가(N-2, Coordinator 도출) | 거절 유지, 승인 결과 확정 우선 | 대장 확정 |
| Q-CAN-4 부분취소를 기관 Capability로만 판정하고 PG 자체 금지(할부 부분취소 등)를 두지 않음 | 현행 유지 | 실제 기관 연동 시 정책 |
| Q-CAN-5 Payment당 미해결 보정 취소 승인 1건(`DUPLICATE_UNRESOLVED`)과 진행 중 취소 시 요청·승인 보류(I-32) | 현행 유지 | 대장 확정 |
| Q-CAN-6 PAYMENT 기록 결정이 ADMIN 승인 내용과 다를 때 "플래그 후 반영"(I-35) vs "반영 보류" | 플래그 후 반영(보류하면 Payment 차단 영구화) | 대장 확정 |
| Q-CAN-7 같은 approvalId 내용 불일치를 파생 충돌로 거절하고 기존 결과를 돌려주지 않음(CN-32, Reviewer 확인 요청 상태) | 현행 유지(fail-closed) | 대장 확정 |
| Q-CAN-8 거래 조회 역할(I-1)과 정확 조회 기간 면제(I-3) | 현행 유지 | 대장 확정 |
| Q-CAN-9 홈 KPI Coordinator 기본값(통화·환경 분리, UNKNOWN·진행 중 제외) — D-ADMIN-HOME-001이 "사용자 확인 대상 아님"으로 둔 금액 정의 | 현행 유지, 사용자 확인 | 대장 확정 |
| Q-CAN-10 계좌 환불 미지원 안내를 관리자 화면(거래 상세·보정 취소 요청)과 가맹점 API 문서에 언제 옮길지 | 질문이 아니라 Coordinator 적용 작업(D-CANCEL-007 이행) — 작업 카드 등록 | — |
| 기존 PROPOSED: Q-CORRECTION(자동 보정 범위) | — | RECON-CONTRACT-001(취소 사건 hold·FORCED 출구, 이관 UNKNOWN 확정) |
| 기존 PROPOSED: Q-CALENDAR(시간대·기관 거래일·마감) | — | 취소 정산 거래일(BR-CAN-077) |
| 기존 PROPOSED: Q-ADMIN-APPROVER-STAFF(APPROVER 인원) | — | 운영자 보정 취소 LIVE 운영 |
| 기존 PROPOSED: Q-SETTLE-CURRENCY | — | 외화 취소 정산 |
