# CAN-OPS-POL-001 취소 운영 정책 정합 검증 — 미확정 결제 취소·보정 취소 실행 검증·동시성

> 상태: **rev 0.1b — 정합 검증 보고서(초안, 독립 리뷰 2회 반영)**.
> - 사용자 결정 D-GOV-015 ①~④(2026-11-26, Q-RC-7·8·9 답)를 확정 정책 문장으로 옮기고, 현재 코드·명세·DB 제약·시험과 항목별로 대조했다.
> - 이 문서는 소스·시험·대장(`docs/rules/CAN.md`)·`docs/DECISIONS.md`·명세를 바꾸지 않는다. 시험 기대값을 바꾸지 않았다. §3 불일치는 목록일 뿐 고치지 않았다. §5 대장 변경안은 Coordinator가 반영한다.
>
> 기준: `main` HEAD `45d18d2`(작업 트리의 다른 문서 변경은 읽지 않았다). 작성 2026-11-26 KST. 시험은 **실행하지 않았다** — 시험 열은 시험 코드에 해당 이름이 있는지만 확인한 것이고 통과 증거가 아니다.
> 근거 결정: **D-GOV-015 ①~④**(직접 근거), D-CANCEL-001~013, D-CANCEL1-001~008, D-OPS-001~010, D-ADMIN-003, D-AUTH-002·008·009.
> 관련 대장 행: BR-CAN-002·010·016·024·025·029~031·034·037·046~070. 관련 진단: RULE-CONFIRM-001 §2 R3(Q7·Q8·Q9).

**개정 이력**
- **0.1** (2026-11-26): 첫 작성.
- **0.1a** (2026-11-26, 독립 리뷰 반영 — 이 문서만 변경. 리뷰는 정책 문장·사실 표본 모두 맞다고 판정):
  - ID: BR-CAN-112~122는 KPI-POLICY-001이 이미 쓰므로, 새 행을 **BR-CAN-123~133**으로 다시 매겼다(장기 미확정 행 분리 뒤 11행. 0.1b에서 BR-CAN-134 추가).
  - C-M1: 장기 미확정 행을 나눴다. 적용 원칙은 확정 행(BR-CAN-126)으로 두고, 알림 기준값은 LIVE Gate 항목으로 뺐다(아래 C-M2·Q-COP-3 철회와 맞춤 — Q-ID가 없으므로 `미결(Q-…)` 행을 만들지 않았다).
  - C-M2: `미결(LIVE Gate)` 형식을 없앴다. 망취소·자동 보상 취소의 구분·별도 정책은 사용자 원문이므로 `확정`이고, 내용 결정은 대장 밖 LIVE Gate 항목이다.
  - C-M3: §5.2 표를 7열(적용 명세 포함)로 바꿨다. BR-CAN-068의 경합 창 예외를 진단 절 기록안(§5.3)에도 적었다.
  - C-n1: ADMIN TX1이 승인 기록과 실행 본문을 실제로 대조하는지 코드로 확인했다. 대조하지 않고 값을 그대로 옮길 뿐이므로 P2-2는 **불일치**로 유지하고 질문 Q-COP-4를 더했다(0.1b에서 질문 철회 → Coordinator 결정 CD-1). T-4는 그대로 둔다.
  - C-n2: D-9·T-8에 웹 승인 경로(`OwnerServiceApprovalService.kt:186`)를 더했다.
  - C-n3: D-4·Q-COP-2에 'A–C 사이 잔액 revision 변경 → `OPERATOR_TARGET_STALE`' 경우를 더했다. Q-COP-2를 ⓐ·ⓑ로 나눴다.
  - Q-COP-3(장기 미확정 알림 기준값)은 질문에서 빼고 LIVE Gate 항목으로 돌렸다.
- **0.1b** (2026-11-26, 리뷰 CHANGES_REQUIRED 반영 — 이 문서만 변경):
  - C-1(MAJOR): Q-COP-4를 질문에서 뺐다. ②-2는 이미 확정된 정책이라 재질문이 된다. BR-CAN-127의 구현 위치는 **Coordinator 결정(가역 구현 위치) CD-1**로 §4.5에 기록했다.
  - C-2: Q-COP-2 ⓑ는 재승인 규칙만 묻는다. 숫자(24h)는 LIVE Gate로 보냈고, BR-CAN-132 문장 범위도 같게 맞췄다.
  - C-3: Q-COP-1 영향 칸에 'RECON Use Case가 없으면 해제 조건을 채울 수 없음 → hold 해제 불가·그 거래 지급 무기한 보류'를 적었다.
  - NIT: BR-CAN-131을 ②-9(멱등)와 ②-8(추가 보정 새 2인 승인) 두 행으로 나눴다(새 BR-CAN-134).

---

## 1. 확정 정책 문장(D-GOV-015 원문을 번호별로 옮김)

### ① BR-CAN-002 — 승인 결과 미확정 결제의 취소(Q7, 거절 유지)

- ①-1 AUTHORIZING·UNKNOWN 상태에서는 가맹점·운영자 모두 일반 취소를 허용하지 않는다.
- ①-2 원승인 Attempt의 결과 조회·대사를 먼저 한다.
- ①-3 승인 성공이 확정된 뒤에는 취소 가능 잔액을 검증한 다음 취소를 허용한다.
- ①-4 승인 실패가 확정되면 취소 대상이 없다.
- ①-5 미확정 상태에서 운영자 권한이나 2인 승인으로 일반 취소를 우회 실행할 수 없다.
- ①-6 거절 사유를 명확히 응답하고, 중복 요청을 막는다.
- ①-7 장기 미확정 거래에는 재조회·대사·운영 알림을 적용한다.
- ①-8 승인 응답 timeout에 따른 망취소·자동 보상 취소는 일반 취소와 구분하며, 카드사·VAN 규격에 따른 별도 정책으로 둔다.
- ①-9 취소 예약 기능을 추가하지 않는다.

### ② BR-CAN-065 — 무결성 플래그 기록 후 PAYMENT 확정 금융 결과 반영(Q8)

- ②-1 ADMIN은 보정 요청·승인 정책의 소유자이고, PAYMENT는 금융 실행과 실제 금융 상태의 소유자다.
- ②-2 실행 전에 승인 내용과 실행 요청의 거래 식별자·금액·통화·보정 유형을 검증하고, 불일치하면 실행을 차단한다.
- ②-3 이미 발생한 확정 금융 결과는 승인 내용과 달라도 실제 사실로 반영한다.
- ②-4 기존 승인 내용은 바꾸지 않고, 실제 결과와 별도로 보존한다.
- ②-5 불일치하면 무결성 플래그·운영 경보·감사 이력을 남긴다.
- ②-6 미확정 결과를 성공으로 처리하지 않는다.
- ②-7 불일치 거래의 추가 취소·정산·지급 제한 기준을 정의한다(**미정**).
- ②-8 추가 금융 보정은 별도 2인 승인을 거친다.
- ②-9 결과 반영·경보는 멱등이고 정합해야 한다.

### ③ BR-CAN-067·068 — 보정 취소 요청·승인 동시성(Q9, 현행 유지)

- ③-1 결제당 미해결 보정 취소 요청은 최대 1건이고, 중복은 409 `DUPLICATE_UNRESOLVED`다.
- ③-2 같은 멱등키로 재전송하면 기존 결과를 돌려주는지 검증한다.
- ③-3 진행 중인 일반 취소가 있으면 신규 보정 취소 요청을 거절한다.
- ③-4 승인 단계의 일시 충돌은 승인 대기를 유지하고 TOTP를 소모하지 않는다.
- ③-5 승인 대기 만료·재승인 기준을 정의한다(**미정**).
- ③-6 요청·승인·실행 단계마다 거래 상태와 취소 가능 잔액을 검증한다.
- ③-7 일반 취소와 보정 취소 사이의 동시성·멱등은 PAYMENT가 최종 보장한다.
- ③-8 UNKNOWN이면 추가 취소보다 기존 요청의 결과 조회·대사를 우선한다.

### ④ 공통

- ④-1 기존 동시성 제어와 DB 제약을 검증한다.
- ④-2 BR-CAN-002·065·067·068을 일관되게 관리한다.
- ④-3 불필요한 모듈 분리, 복잡한 상태·승인 구조를 만들지 않는다.

---

## 2. 정책 항목별 검증표

판정: **일치**(규칙·검증·제약·시험이 모두 있음) / **불일치**(정책과 다른 동작이 있거나 정책 요구가 일부 빠짐, 심각도는 §3) / **시험 없음**(코드는 정책대로지만 그 조건을 직접 확인하는 시험이 없음) / **확인 불가**(기준이 미정이거나 이번 조사 범위 밖).

경로 약어: `PD` = `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/domain`, `PA` = `…/payment/payment/application`, `PQ` = `…/payment/payment/adapter/query`, `PM` = `apps/payment-api/src/main/resources/db/migration/payment`, `PT` = `apps/payment-api/src/test/kotlin/com/paycore/payment/payment`, `AA` = `apps/internal-admin/src/main/kotlin/com/paycore/admin/approval`, `AM` = `apps/internal-admin/src/main/resources/db/migration/admin`, `AT` = `apps/internal-admin/src/test/kotlin/com/paycore/admin/approval`.

### 2.1 ① 미확정 결제 취소

| # | 정책 | 도메인 규칙(file:line) | API 검증 | DB 제약/락 | 시험 | 판정 |
|---|---|---|---|---|---|---|
| P1-1 | ①-1 가맹점 일반 취소 불허 | `PD/CancelStart.kt:88-93` — 가맹점은 Payment·Authorization 모두 AUTHORIZED이고 확정 거래가 있어야 한다. 아니면 `PAYMENT_NOT_CANCELLABLE` | 가맹점 API → `PA/CancelStartService.kt:149`(merchantTx) → 409 `PAYMENT_NOT_CANCELLABLE`, 멱등 결과로 저장(BR-CAN-024) | 결과 행 = 멱등 기록. 잠금 순서 control → Payment → Authorization → 잔액(`PA/CancelStartService.kt:355-371`) | `PT/domain/CancelStartSpec.kt::CX-07 …REQUESTED, AUTHORIZING, UNKNOWN, FAILED and EXPIRED are rejected with PAYMENT_NOT_CANCELLABLE`(L89-103), `PT/application/CancelStartServiceSpec.kt::a REQUESTED payment is PAYMENT_NOT_CANCELLABLE`(L149, 실제 DB — REQUESTED만) | **일치** |
| P1-2 | ①-1·①-5 운영자(2인 승인 포함) 취소·우회 불가 | `PD/CancelStart.kt:88-92` — 운영자도 `authorization?.transaction`이 있어야 한다(개시자와 무관하게 거래가 없으면 거절). 거래는 AUTHORIZED이거나 RECON이 기록한 FAILED에만 있다: `PD/PaymentAuthorization.kt:138-145`(UNKNOWN → AUTHORIZED 때만 거래 설정), `:154-163`(FAILED + RECON만), `:249-251`(복원 불변식 — OPEN·UNKNOWN에 거래 금지). Payment AUTHORIZED 전이는 같은 연산(`PD/OrderPaymentControl.kt:168-183`) | ADMIN 요청 advisory `AA/application/OwnerServiceApprovalService.kt:139-144`(`!target.cancellable` → `PAYMENT_NOT_CANCELLABLE_ADVISORY`). `cancellable`은 AUTHORIZATION 거래 행 존재(`PA/OperatorCorrectionCancelService.kt:50`, `PQ/JooqCancellationViewReader.kt:142-153`). 승인 TX1 `:240-242`(`!current.cancellable` → `INVALIDATED(TARGET_CHANGED)`). PAYMENT 쓰기 진입점은 서명 채널의 실행·기한 확인 두 개뿐(`adapter/web/admin` — `@PostMapping` 3개 중 나머지 하나는 조회 `payment-searches`). 승인을 거쳐도 `CancelStart`의 같은 관문을 지난다 | `payment_transaction` 거래 1건 UNIQUE(V17), 같은 잠금 순서 | **운영자 개시자로 AUTHORIZING·UNKNOWN을 거절하는 시험이 없다.** CX-07은 기본 개시자(가맹점)만 쓴다. 운영자 거래 없음 거절은 FAILED만 있다(`CancelStartSpec.kt:365 operator cancel on a FAILED payment without a recorded transaction`). ADMIN 대상 조회는 REQUESTED만(`AdminOperatorCancellationApiSpec::the target lookup … a payment without authorization is shown not cancellable`) | **시험 없음** |
| P1-3 | ①-2 원승인 Attempt 조회·대사 우선 | 승인 회복 `PA/AuthorizationRecoveryService.kt:40` — 원 Attempt 조회만, 예산·연령 상한이면 `AuthorizationOutcomeUnresolved` 이관(상태 불변, D-AUTH-009). 운영자 확정 출구 없음 | 취소 쪽 진입점은 없음(P1-1·P1-2로 거절) | 회복 일정 행 | `PT/application/AuthorizationRecoverySpec.kt::the next inquiry finds the original decision…`(L103), `…AuthorizationOutcomeUnresolved once, UNKNOWN …`(L204) | **일치**(이관된 UNKNOWN을 확정하는 RECON Use Case는 없다 — BR-CAN-031 LIVE Gate, §3 D-3) |
| P1-4 | ①-3 승인 확정 뒤 잔액 검증 후 허용 | `PD/CancelStart.kt:101-105`(통화, `ALREADY_FULLY_CANCELLED`, 금액 ≤ 취소 가능액), 취소 가능액 = max(0, 승인 − 완료 − 예약 − FORCED)(BR-CAN-015) | 422 `CANCEL_AMOUNT_EXCEEDS_BALANCE`·`CURRENCY_MISMATCH` | `PM/V15__cancellation.sql:165` `CHECK (completed_minor + reserved_minor <= authorized_minor)`, 잔액 행 `FOR UPDATE`(`PA/CancelStartService.kt:371`) | `CancelStartSpec::CX-04`, `CX-06`, `ReturnBalanceSpec::reservation guard`, `CancelStartConcurrencySpec::K1`·`K5` | **일치** |
| P1-5 | ①-4 승인 실패 확정 → 대상 없음 | `PD/CancelStart.kt:91`(거래 없음 → 거절). 예외: RECON이 늦은 승인을 기록한 FAILED P1은 운영자만(D-CANCEL-011, `:90`) | 409 `PAYMENT_NOT_CANCELLABLE` | — | `CancelStartSpec::CX-07`(declined), `CancelStartSpec.kt:365`(운영자 FAILED 거래 없음), `CX-27`(P1 예외) | **일치** |
| P1-6 | ①-6 거절 사유 명확 응답 | 한 code `PAYMENT_NOT_CANCELLABLE`이 REQUESTED·AUTHORIZING·UNKNOWN·FAILED·EXPIRED를 모두 덮는다(`PD/CancelStart.kt:26-27` 주석) | 409 `PAYMENT_NOT_CANCELLABLE` 하나. "결과 확정 전 — 기다리면 취소 가능"과 "승인 실패 — 취소 대상 없음"을 응답으로 구분할 수 없다(명세 CANCEL-CONTRACT-001 §8 표도 하나) | — | CX-07(code만 확인) | **불일치**(LOW — §3 D-5) |
| P1-7 | ①-6 중복 요청 방지 | 가맹점: (Payment, merchantCancelId) 업무 키(BR-CAN-020), 거절도 30일 결과 재생(BR-CAN-024·025). 운영자: approvalId별 첫 결정(BR-CAN-051) | 같은 키·같은 본문 → 저장 결과, 다른 본문 → 409 `IDEMPOTENCY_KEY_REUSED` | `uk_payment_cancellation_merchant_key`(V15:40), 멱등 기록 UNIQUE(V20), `uk_operator_approval_decision_approval`(V27:23), ADMIN `uk_admin_approval_request_unresolved`(V5:35) | `CancelStartServiceSpec::the same key and body replays the stored result…`(L107), `CancelStartConcurrencySpec::K2`, `AdminOperatorCancellationApiSpec::…the same bytes again replay it` | **일치** |
| P1-8 | ①-7 장기 미확정 재조회·대사·운영 알림 | 재조회·이관: 승인 P1-3, 취소 `CancellationOutcomeUnresolved`(BR-CAN-030), ADMIN `EXECUTION_UNKNOWN` 무기한 재전송·오래되면 경보(D-OPS-003, backlog gauge — `OwnerExecutionRecoverySpec::the backlog gauges`) | — | — | 이관 이벤트·지표 시험은 있음. **운영 알림의 수신처·임계값·재알림 기준은 정해진 것이 없다**(예산·연령은 환경변수 주입, 운영 기본값 없음 — `application.yml:79-82` 주석, D-AUTH-009) | **확인 불가**(알림 기준값 = LIVE Gate 항목 — §4.4) |
| P1-9 | ①-8 망취소·자동 보상 취소 별도 정책 | 구현 없음. 자동 보상 취소 금지(D-AUTH-002·008), UNKNOWN 승인 망취소 없음(CANCEL-CONTRACT-001 §13 N-2) | — | — | — | **일치**(도입 안 됨 = 별도 정책 전 상태, §4.1) |
| P1-10 | ①-9 취소 예약 기능 금지 | 취소 예약(정해진 시각 실행) API·화면·job 없음. 코드의 "반환 예약(`ReturnReservation`)"은 진행 중 취소의 잔액 점유로 다른 개념이다(용어 혼동 주의) | — | — | — | **일치** |

### 2.2 ② 보정 취소 실행 검증·결과 반영

| # | 정책 | 도메인 규칙(file:line) | API 검증 | DB 제약/락 | 시험 | 판정 |
|---|---|---|---|---|---|---|
| P2-1 | ②-1 소유 분리 | PAYMENT만 `PaymentCancellation.open`·`ReturnBalance.reserve`를 부른다(`PD/CancelStart.kt:71` 주석, ArchUnit). ADMIN은 승인 행·명령 행·실행 행만 쓴다 | ADMIN→PAYMENT HMAC 서명 채널만(BR-CAN-057), PAYMENT는 ADMIN 승인 상태를 동기 조회하지 않음(BR-CAN-049) | DB 분리(ADMIN·PAYMENT 각자 schema) | `ArchitectureSpec`(진입점 FQN), `AdminOperatorCancellationApiSpec` | **일치** |
| P2-2 | ②-2 **실행 전 거래 식별자·금액·통화·보정 유형 검증 후 불일치 차단** | PAYMENT: `PD/CancelStart.kt:117-121` — 실행 시점 값으로 지문을 다시 계산해 명령의 `approvedTargetFingerprint`와 다르면 `OPERATOR_TARGET_STALE`. 지문 입력 = paymentId·금액·통화·잔액 revision·approvalId·요청자·승인자·목적 종류·근거 참조(`PD/OperatorCancelTarget.kt:25-35`). 별도로 통화 = 원 거래 통화(`:101`), 금액 ≤ 취소 가능액(`:105`). ADMIN: 승인 TX1에서 불변 명령 행 `pinned_target`과 현재 대상(revision·통화·취소 가능·금액)을 비교해 다르면 `INVALIDATED(TARGET_CHANGED)`(`AA/application/OwnerServiceApprovalService.kt:240-242`), 같은 명령 행 값으로 지문과 본문을 만든다(`:243-256`). **이 과정은 대조가 아니라 값 옮김이다**(rev 0.1a 코드 확인). ① TX1은 명령 행 값을 그대로 본문 입력으로 넘긴다(`paymentops/application/PaymentOpsService.kt:63-66`). ② 만든 본문을 명령 행·승인 행과 다시 비교하는 단계가 없다. 본문 생성자 `init`은 형식만 검사한다(`paymentops/domain/OperatorCancelExecutionBody.kt:25-34`). ③ 회복 재전송도 저장 바이트를 대조 없이 보낸다(`OwnerExecutionRecoveryService.kt:150`). 저장 바이트는 UPDATE 권한이 없어 바뀌지는 않는다(V6). ④ TX1이 실제로 대조하는 것은 승인 행 대상 ↔ 명령 행 고정 값(`assertSameTarget` `:303-307`)과, 고정 값 ↔ PAYMENT 현재 대상(`:240-242`)뿐이다 | **PAYMENT가 비교하는 기준은 ADMIN 승인 기록이 아니라 같은 서명 본문 안에 든 지문이다.** 지문과 본문 필드는 ADMIN이 같은 TX1에서 함께 만든다. 그래서 PAYMENT 검사가 잡는 것은 (a) 그 사이 잔액 변경, (b) 본문 안 필드끼리의 어긋남이다. "ADMIN이 승인한 내용과 다른 본문이 서명되어 도착한 경우"(서명키 오용·ADMIN 본문 생성 결함)는 PAYMENT가 실행 전에 막지 못한다. 이 경우는 사후에만 잡힌다 — 결정 내용 대조(P2-5), 대사 `FINGERPRINT_MISMATCH`(D-OPS-008). 거래 식별자는 paymentId만 묶는다. 원 승인 거래 ID는 지문에 없지만 Payment당 Authorization 1건이라 paymentId에서 정해진다 | ADMIN 명령 행 INSERT 전용(`AM/V5`·V6 권한 `SELECT, INSERT`), PAYMENT 결정 행 UNIQUE(approval_id) | 금액·revision: `CancelStartSpec::an approval for another amount or for an older balance revision is stale`(L226). approvalId·요청자·승인자·목적 종류·근거: `…binds approvalId, requester, approver, purpose type and purpose reference…`(L237). API: `AdminOperatorCancellationApiSpec::a stale approved target (revision differs) is 409 OPERATOR_TARGET_STALE`. ADMIN 독립 계산: `OwnerServiceApprovalFlowSpec::200 STARTED → EXECUTED: … oct1 computed independently`. **paymentId만 바꾼 지문 시험, 통화만 바꾼 운영자 시험은 없다** | **불일치**(MEDIUM — §3 D-1, 구현 위치는 Coordinator 결정 CD-1 §4.5) |
| P2-3 | ②-3 확정 금융 결과는 실제 사실 반영 | ADMIN: 기록 결정을 플래그 뒤 그대로 반영(`AA/application/OwnerOutcomeApplier.kt:92-101`, 보류 없음). PAYMENT: 기관 결과가 요청과 다르면 덮어쓰지 않고 관찰 기록 + hold(BR-CAN-034·035) — 실제 효과는 FORCED·관찰로 남는다 | 결정 조회 응답 → ADMIN 반영 | `payment_transaction` CANCEL 1건 UNIQUE(V17:17), 관찰 UNIQUE(V15:126) | `OwnerExecutionRecoverySpec::DecisionContentCheckSpec …the decision is still applied (EXECUTED with the flag)`(L163-175), `CancellationResolutionSpec::CR-09·CR-10·CR-11` | **일치** |
| P2-4 | ②-4 승인 내용 불변·실제 결과와 별도 보존 | ADMIN 명령 행(`admin_operator_cancel_command`) INSERT 전용, 실행 행 본문(`request_body`)·지문은 갱신 권한 없음 — 결과는 `owner_reference`·`owner_result_code` 별도 열(V6 권한 `grant_runtime_column_update` 목록). PAYMENT 결정 행 INSERT 전용(V28), 거절 감사 INSERT 전용(V16:11) | — | 런타임 계정 권한(UPDATE 1142 거부) | `AdminSchemaSpec`·`AdminDatabaseAccountSpec`(V5 주석이 지정 — 이번에 이름만 확인), `OwnerExecutionRecoverySpec::…the same stored body bytes are resent (no regeneration)` | **일치** |
| P2-5 | ②-5 불일치 시 플래그·경보·감사 | 결정 조회 대조 `AA/application/OwnerExecutionRecoveryService.kt:226-238`(paymentId·금액·통화·요청자·승인자·지문) → `integrity_alert{DECISION_CONTENT_MISMATCH}` + 플래그 먼저 + 감사 `APPROVAL_INTEGRITY_MISMATCH`(`OwnerOutcomeApplier.kt:92-95`). 파생 409(`APPROVAL_CONFLICT`·`APPROVAL_IDENTITY_MISMATCH`) → `EXECUTION_UNKNOWN` + 플래그 + 경보(`OwnerServiceApprovalService.kt:186`, `OwnerOutcomeApplier.kt:120`). 대사 발견 `FINGERPRINT_MISMATCH` | — | 대사 발견 `UNIQUE(kind, approval_id)`(`AM/V5:107`) | `DecisionContentCheckSpec`, `OwnerServiceApprovalFlowSpec::result mapping: derived 409 → EXECUTION_UNKNOWN + integrity flag`, `IntegrityFlagNoResendSpec`, `OperatorDecisionReconciliationRunSpec` | **일치**(플래그는 ADMIN에만 있다. PAYMENT·FINANCIAL은 모른다 → P2-9) |
| P2-6 | ②-6 미확정 결과를 성공으로 처리 금지 | `EXECUTION_UNKNOWN`은 PAYMENT 확정 응답으로만 끝난다(D-OPS-003, 수동 종료 없음). 취소 UNKNOWN을 자동 FAILED로 만들지 않는다(BR-CAN-028) | ADMIN `EXECUTED` = "PAYMENT가 취소를 내구 시작함(STARTED)"이지 반환 완료가 아니다(명세 ADMIN-PAYMENT-OPS-001 §4.13 끝). 명세는 "화면은 결정 조회의 `cancellationStatus`로 보인다"고 적었다. 그런데 PAYMENT가 내려 주는 `cancellationStatus`(`AdminChannelResponses.kt:53`)를 ADMIN 코드·화면은 읽지 않는다. 승인 상세는 `실행됨 — 취소 참조 …`를 success 색으로 보인다(`frontend/src/components/OwnerApprovalDetail.tsx:105-106`, `lib/statusTone.ts:8`). 취소의 실제 상태는 거래 상세 화면(`screens/TransactionsScreen.tsx:147-156`)에만 나온다 | — | 화면 시험 없음 | **불일치**(LOW — §3 D-7) |
| P2-7 | ②-8 추가 금융 보정은 별도 2인 승인 | 운영자 취소마다 새 approvalId. 첫 결정이 그 승인을 소진한다(BR-CAN-051). PAYMENT 재검사로 요청자 ≠ 승인자(BR-CAN-047) | ADMIN 요청 OPERATOR, 승인 APPROVER + TOTP(BR-CAN-058) | `ck_payment_cancellation_initiator`(V15:57-63, 요청자 ≠ 승인자 DB 이중 방어) | `AdminOperatorCancellationApiSpec::business rejections are decisions (the approval is consumed)`, `OwnerServiceApprovalFlowSpec::SELF_APPROVAL` | **일치** |
| P2-8 | ②-9 결과 반영·경보 멱등·정합 | 이미 종료된 승인에 다시 반영하면 바꾸지 않고 다르면 `outcome_divergence`(`OwnerOutcomeApplier.kt:83-86`). 플래그는 처음 값 유지(`OwnerExecution.kt:75-78`). 감사는 반영 TX 안 1건. 단 경보 지표 `integrity_alert`는 TX 밖에서 먼저 올린다(`OwnerExecutionRecoveryService.kt:231`) — TX2가 실패해 다음 주기에 다시 처리하면 한 번 더 오른다 | — | 승인 행 → 실행 행 잠금, 회복 선점 `SKIP LOCKED` | `OutcomeDivergenceSpec`(L401-413), `OwnerExecutionClaimRaceSpec`. 경보 지표 중복은 시험 없음 | **일치**(감사·플래그. 지표 카운터 중복 가능 — §3 D-9) |
| P2-9 | ②-7 불일치 거래의 추가 취소·정산·지급 제한 | 제한 없음. `DECISION_CONTENT_MISMATCH`로 `EXECUTED`가 되면 `unresolved_key`가 풀려(`AM/V13:55-`) 같은 Payment에 새 보정 요청이 가능하다. PAYMENT에는 hold·표지가 없어 가맹점 추가 취소·FINANCIAL 정산·지급이 평소대로 진행된다 | — | — | 현행 동작 시험 없음 | **확인 불가**(기준 미정 — §4.2) |

### 2.3 ③ 보정 취소 요청·승인 동시성

| # | 정책 | 도메인 규칙(file:line) | API 검증 | DB 제약/락 | 시험 | 판정 |
|---|---|---|---|---|---|---|
| P3-1 | ③-1 결제당 미해결 1건, 409 `DUPLICATE_UNRESOLVED` | 승인 행 INSERT의 1062 → `DUPLICATE_UNRESOLVED`(`AA/application/OwnerServiceApprovalService.kt:117-122`) | 409 + 감사 | 생성 열 `unresolved_key`(PENDING·EXECUTING·EXECUTION_UNKNOWN이면 `PAYMENT:<id>`) UNIQUE — `AM/V5:33-35`, 최신 정의 `AM/V13:55-` | `OwnerServiceApprovalFlowSpec::a second request … is 409 DUPLICATE_UNRESOLVED`(L121), `UnresolvedApprovalRaceSpec::two approval requests for the same Payment created concurrently`(L81, 실제 DB 경합), `…PENDING, EXECUTING and EXECUTION_UNKNOWN each block…`(L117), `…a terminal state … frees the Payment`(L125) | **일치** |
| P3-2 | ③-2 같은 멱등키 재전송 → 기존 결과 | 실행 단계: approvalId가 멱등키다. 같은 내용 hash → 같은 결정 재생, 다른 내용 → 파생 409(`PA/CancelStartService.kt:229-233`, `:253-276`). ADMIN은 저장 본문 바이트를 그대로 재전송한다. **요청 생성 단계**(`POST /api/approvals/operator-correction-cancel`)에는 멱등키가 없다. 같은 요청을 다시 보내면 기존 approvalId가 아니라 409 `DUPLICATE_UNRESOLVED`가 온다 — 중복 생성은 막히지만 "기존 결과 반환"은 아니다 | 실행 200 `existing=true`. 요청 재전송 409 | `uk_operator_approval_decision_approval`(V27:23) + 1062 시 TX 전체 rollback·재시도(`PA/CancelStartService.kt:215-219` 주석) | `AdminOperatorCancellationApiSpec::the same approvalId and bytes at once: one decision, one cancellation…`(L324), `…a response lost because the client closed the connection: the resend replays…`(L446), `OwnerExecutionRecoverySpec::the same stored body bytes are resent`. 요청 단계 재전송 = DUPLICATE_UNRESOLVED 시험(L121) | **불일치**(LOW, 요청 단계만 — §3 D-6) |
| P3-3 | ③-3 진행 중 일반 취소 → 신규 보정 요청 거절 | ADMIN advisory `inFlightCancel` → `CANCEL_IN_PROGRESS_ADVISORY`(`OwnerServiceApprovalService.kt:139-140`, 비권위). PAYMENT 권위 판정 `PD/CancelStart.kt:99` → `CANCEL_IN_PROGRESS` | 요청 409 advisory, 실행 409 | `uk_payment_cancellation_in_flight`(V15:37-42, OPEN·UNKNOWN 생성 열), `uk_return_reservation_in_flight`(V15:203) | `OwnerServiceApprovalFlowSpec::advisory (L-7)…`(L109), `OperatorCorrectionCancelSpec::a cancel in progress blocks the correction…`(L170), `CancelStartConcurrencySpec::K12 … without a hold: one starts and the other is CANCEL_IN_PROGRESS`(L139) | **일치** |
| P3-4 | ③-4 승인 단계 일시 충돌 → 대기 유지·TOTP 미소모 | A 단계 대상 재조회에서 진행 중 취소면 `TARGET_BUSY`, PENDING 유지, TOTP 검증 전(`OwnerServiceApprovalService.kt:174` → TOTP는 TX1 `:227`). **경합 창**: A 조회 뒤 C 실행 전에 가맹점 취소가 시작되면 `CANCEL_IN_PROGRESS`, 잔액 revision이 바뀌면 `OPERATOR_TARGET_STALE`(`PD/CancelStart.kt:119-120`)로 PAYMENT가 결정을 기록한다. 두 경우 모두 승인은 `OWNER_REJECTED`로 소진되고 TOTP도 이미 소비된다(D-OPS-005 "일시 거절도 승인 소진") | A 409 `TARGET_BUSY`, 경합 시 OWNER_REJECTED | 승인 행 `FOR UPDATE`(TX1), PAYMENT 잠금 아래 최종 판정 | `OwnerServiceApprovalFlowSpec::TARGET_BUSY (L-7)…TOTP not verified or consumed, no POST`(L155), `OwnerApprovalConcurrencySpec::…the other is ALREADY_DECIDED without consuming its TOTP step`, 응답 매핑만 `…409 rejection → OWNER_REJECTED`(L181, stub 응답). **경합 창 실제 시험 없음** | **불일치**(LOW, 경합 창 — §3 D-4, 질문 §4 Q-COP-2 ⓐ) |
| P3-5 | ③-5 승인 대기 만료·재승인 | PENDING 유효 24h(`apps/internal-admin/src/main/resources/application.yml:98`, Demo), 만료 판정은 TX 시각(`AA/domain/ApprovalRequest.kt:66-72`), TX1에서 만료 판정이 TOTP보다 먼저(`OwnerServiceApprovalService.kt:211-215` → `:227`). 만료 정리 job 1분(`application.yml:100-102`). 실행 기한 24h(`application.yml:119`, BR-CAN-054 Demo). 재승인 = 새 요청·새 approvalId(종료 상태는 `unresolved_key` NULL) | 만료 → `ApprovalOutcome.Expired`, 감사 `APPROVAL_EXPIRED` | `unresolved_key` 종료 시 NULL | `AT/domain/ApprovalDecisionTableSpec.kt::at or after expires_at (TX time) every command expires…`(L102), `ApprovalExpiryIsolationSpec`(job). **운영자 보정 취소 승인 시점 만료 → TOTP 미소모·POST 없음 시험 없음** | **시험 없음**(값은 Demo, 기준 미정 — §4.3, Q-COP-2 ⓑ) |
| P3-6 | ③-6 단계마다 거래 상태·잔액 검증 | 요청: advisory(`:139-144` — 취소 가능·금액 ≤ 잔액·진행 중). 승인: TX1 비교(`:240-242`). 실행: `PD/CancelStart.kt:88-121` 전체 | 요청 409 advisory, 승인 `INVALIDATED(TARGET_CHANGED)`, 실행 409·422 | 실행 잠금 순서 + CHECK(V15:165) | `advisory (L-7)`, `TARGET_CHANGED: the revision moved since the request → INVALIDATED…`(L163), `CX-04`, `OPERATOR_TARGET_STALE` 시험 | **일치** |
| P3-7 | ③-7 PAYMENT가 최종 보장 | 일반·보정 취소가 같은 단계 서비스 `startCancel`(`PA/CancelStartService.kt:368-`)과 같은 잠금 순서(control → Payment → Authorization → 잔액, `:355-371`)를 탄다 | — | `uk_payment_cancellation_in_flight`(V15:42), `uk_return_reservation_in_flight`(V15:203), `ck_return_balance_within_authorized`(V15:165), `uk_operator_approval_decision_approval`(V27:23), `uk_payment_cancellation_approval`(V15:41) | `CancelStartConcurrencySpec::K12 an operator correction cancel and a merchant cancel on the same payment at once`(L130-153, 실제 연결), `…m-3 the same approvalId on two different payments at once`(L155), `AdminOperatorCancellationApiSpec::the same approvalId and payment with different content, serialized by the payment lock`(L360-406) | **일치** |
| P3-8 | ③-8 UNKNOWN이면 기존 결과 조회·대사 우선 | UNKNOWN 취소는 다음 취소를 막는다(BR-CAN-010, `PD/CancelStart.kt:99`). 회복은 원 취소 Attempt 조회만(BR-CAN-029). ADMIN `EXECUTION_UNKNOWN`은 결정 조회를 먼저 하고 같은 본문을 재전송한다. 수동 확정 없음 | 409 `CANCEL_IN_PROGRESS` | in-flight UNIQUE | `CancelStartSpec::an OPEN or UNKNOWN cancel blocks any new cancel, for merchants and operators alike`(L107), `OwnerExecutionRecoverySpec::a due EXECUTION_UNKNOWN approval (recovery order: recorded decision first)`(L116), `CancellationRecoverySpec::one recovery inquiry with the original cancel request ID…no resend` | **일치** |

### 2.4 ④ 공통

| # | 정책 | 근거 | 판정 |
|---|---|---|---|
| P4-1 | ④-1 동시성 제어·DB 제약 검증 | P1-4·P3-1·P3-3·P3-7의 UNIQUE·CHECK·잠금·실제 연결 시험을 이번에 대조했다(시험 실행은 안 함) | **일치** |
| P4-2 | ④-2 BR-CAN-002·065·067·068 일관 관리 | 네 행 모두 아직 `제안`/`적용`이다. 확정 문장·조건 행은 §5 변경안 | **확인 불가**(대장 반영 대기) |
| P4-3 | ④-3 불필요한 모듈 분리·복잡 상태 금지 | 이 문서는 새 모듈·상태를 제안하지 않는다. §4 질문의 권장안도 기존 hold·승인 구조를 다시 쓰는 범위로 제한했다 | **일치** |

### 2.5 집계

| 판정 | 수 | 항목 |
|---|---|---|
| 일치 | 20 | P1-1·3·4·5·7·9·10, P2-1·3·4·5·7·8, P3-1·3·6·7·8, P4-1·3 |
| 불일치 | 5 | P1-6, **P2-2**, P2-6, P3-2, P3-4 |
| 시험 없음 | 2 | P1-2, P3-5 |
| 확인 불가 | 3 | P1-8, P2-9, P4-2 |
| 합계 | 30 | |

---

## 3. 불일치·결손 목록(수정하지 않음)

| ID | 심각도 | 내용 | 금액 위험 | 관련 |
|---|---|---|---|---|
| D-1 | **MEDIUM** | ②-2 "실행 전 승인 내용과 실행 요청을 검증하고 불일치하면 차단"이 어느 쪽에서도 명시 대조로 구현되어 있지 않다.<br>• ②-1 기준으로 승인 기록의 소유자는 ADMIN이다. 그런데 ADMIN TX1은 불변 명령 행 값을 본문으로 옮길 뿐, 만든 본문(거래 식별자·금액·통화·보정 유형)을 승인 기록과 다시 비교해 차단하는 단계가 없다. 재전송도 같다(P2-2, C-n1 코드 확인).<br>• PAYMENT는 같은 서명 본문 안의 지문으로 자기 정합만 보고, 잔액 revision·통화·잔액을 재검사한다. ADMIN 승인 기록과는 대조하지 않는다(BR-CAN-049 — 사용자 결정 D-CANCEL1-001).<br>• 정상 경로에서는 값 옮김이 구조상 정확해 결과가 맞는다. 그러나 ADMIN 본문 생성·직렬화 결함이나 서명키 오용이 있으면, 승인과 다른 금액·목적의 서명 본문이 그 Payment의 취소 가능 잔액 안에서 실행된다.<br>• 탐지는 사후뿐이다(결정 내용 대조 P2-5, 대사 `FINGERPRINT_MISMATCH`).<br>• paymentId만, 통화만 바꾼 지문 시험도 없다.<br>→ Coordinator 결정 CD-1(§4.5)로 구현 위치를 정함. 단, 서명키 오용은 ADMIN 대조로 막을 수 없다 | 있음(조건부 — 승인보다 큰 금액이 잔액 한도 안에서 반환될 수 있음, 승인액 초과 반환은 CHECK로 불가) | P2-2, BR-CAN-048·049 |
| D-2 | **MEDIUM** | ②-7 불일치 거래의 후속 제한이 없다. `DECISION_CONTENT_MISMATCH`·`FINGERPRINT_MISMATCH`는 ADMIN 실행 행·대사 발견에만 남는다. 승인은 `EXECUTED`로 끝나 `unresolved_key`가 풀리고, PAYMENT hold·FINANCIAL 지급 보류로 이어지지 않는다 | 있음(불일치 원인 조사 전에 같은 Payment의 추가 반환·정산·지급이 진행됨) | P2-9, §4.2 |
| D-3 | HIGH(LIVE 전제, 이미 LIVE Gate) | ①은 지켜지지만 출구가 없다. 미확정 중 기관이 실제로 승인한 경우(D-AUTH-008 조건 상충, 이관된 영구 UNKNOWN)에는 거래가 없어 취소할 수 없다. 이를 확정하는 RECON Use Case도 없다(BR-CAN-031·038). 고객 대금을 돌려줄 PG 경로가 없다 | 있음(고객 대금 묶임 — 이중 반환 위험은 없음) | P1-3, RECON-CONTRACT-001 BACKLOG |
| D-4 | LOW | ③-4 승인 단계 경합 창(A 조회 뒤, PAYMENT 실행 전)에는 두 경우가 있다. 둘 다 PAYMENT가 거절 결정을 기록하고, 승인이 `OWNER_REJECTED`로 소진되며, TOTP는 TX1에서 이미 소비된 뒤다. (1) 가맹점 취소가 시작됨 → `CANCEL_IN_PROGRESS`. (2) 잔액 revision이 바뀜(예: 가맹점 취소 완료·실패 반영) → 본문 지문은 A 시점 revision이므로 `OPERATOR_TARGET_STALE`(`PD/CancelStart.kt:119-120`). D-OPS-005("일시 거절도 승인 소진")와 D-GOV-015 ③("승인 단계 일시 충돌은 대기 유지·TOTP 미소모")의 적용 범위가 겹친다. 실제 경합 시험이 없다 | 없음(fail-closed, 운영자가 새 2인 승인을 다시 받아야 함) | P3-4, Q-COP-2 ⓐ |
| D-5 | LOW | ①-6 `PAYMENT_NOT_CANCELLABLE` 하나가 "결과 확정 전"과 "취소 대상 없음"을 함께 덮는다. 가맹점은 결제 조회로 따로 확인해야 한다 | 없음 | P1-6 |
| D-6 | LOW | ③-2 ADMIN 보정 요청 생성에 멱등키가 없어, 재전송하면 기존 결과 대신 409 `DUPLICATE_UNRESOLVED`가 온다. 중복 생성은 막힌다 | 없음 | P3-2 |
| D-7 | LOW | ②-6 명세 §4.13은 "화면은 `cancellationStatus`로 보인다"고 했지만 ADMIN은 그 필드를 읽지 않는다. 승인 상세의 `EXECUTED`는 success 색 "실행됨"이다. 취소가 UNKNOWN·FAILED여도 승인 화면만 보면 완료로 오인할 수 있다(거래 상세에는 취소 상태가 보임) | 간접(오인 시 후속 보정 누락) | P2-6 |
| D-8 | LOW | 시험 결손: 운영자 개시자로 AUTHORIZING·UNKNOWN 거절(도메인·API), 실제 DB 서비스 단의 AUTHORIZING·UNKNOWN 거절(REQUESTED만 있음), paymentId·통화 단독 지문 변경, 운영자 보정 승인 시점 만료의 TOTP 미소모, 승인 경합 창 | — | P1-2, P2-2, P3-4, P3-5 |
| D-9 | LOW | ②-9 `integrity_alert` 지표를 반영 TX 밖에서 먼저 올린다. 두 곳이다: 회복 job(`OwnerExecutionRecoveryService.kt:231`), 웹 승인 경로 TX2 전(`OwnerServiceApprovalService.kt:186`). 그래서 TX2가 실패한 뒤 회복 job이 다시 처리하면 같은 건으로 한 번 더 오른다. 감사·플래그는 멱등이다 | 없음 | P2-8 |
| D-10 | LOW(대장) | BR-CAN-002 검증 열 `CancelStartSpec::CX-07`은 가맹점 경로만 확인한다. 규칙 문장 "누구의 취소도"의 운영자 부분은 시험이 없다 | — | P1-2, §5 |

**주요 금액 위험 요약**: D-1(실행 전 승인 기록 ↔ 실행 본문 명시 대조 없음 — ADMIN·PAYMENT 양쪽 모두. 승인보다 큰 반환 가능, 조건부), D-2(불일치 뒤 후속 반환·지급 무제한), D-3(미확정 실제 승인의 반환 출구 없음 — LIVE Gate).

---

## 4. '정의 필요' 항목 — 현재 결정·Demo 값 사실과 질문 후보

### 4.1 망취소·자동 보상 취소(①-8)

- 현재 결정: 늦은 성공·상충은 자동 보상 취소 없이 hold + 대사 Case(D-AUTH-002·008). UNKNOWN 승인 망취소 없음(CANCEL-CONTRACT-001 §13 N-2).
- 현재 코드: 망취소 전송·기관 프로필 Capability·별도 상태 없음. Simulator 기관 규격의 망취소 지원 여부는 이번에 확인하지 않았다.
- 판단: 실제 카드사·VAN 규격이 있어야 정할 수 있다. **질문으로 올리지 않고 LIVE Gate 항목**으로 둔다(기관 규격 확보 후 질문).

### 4.2 불일치 거래의 추가 취소·정산·지급 제한(②-7)

- 현재 사실: 제한 없음(D-2). 플래그는 ADMIN 실행 행에만 있다. 취소 결과 상충(기관 쪽)은 PAYMENT hold로 가맹점 추가 취소를 막고 운영자 보정만 허용한다(BR-CAN-037) — 기존 구조로 쓸 수 있다.
- 질문 후보 **Q-COP-1**(아래).

### 4.3 승인 대기 만료·재승인(③-5)

- Demo 값: PENDING 유효 24h, 만료 정리 job 1분, 실행 기한 24h(BR-CAN-054), PAYMENT 최대 실행 창 24h(`payment-max-execution-window`). 모두 "운영값 아님" 표시.
- 현재 동작: 만료는 TX 시각으로 판정하고 TOTP 전에 끝난다. 만료·무효·거절·OWNER_REJECTED 뒤 재개는 항상 새 요청(새 approvalId, 새 2인 승인)이며 옛 승인을 다시 쓰지 않는다. `EXECUTION_UNKNOWN`은 만료가 없고 실행 기한 뒤 기한 확인 호출로만 끝난다(D-OPS-009).
- 질문 후보 **Q-COP-2 ⓑ**(ⓐ 경합 창 예외와 함께).

### 4.4 장기 미확정 알림(①-7)

- 현재 사실: 승인·취소 회복은 조회 예산·최대 연령(환경변수 주입, 운영 기본값 없음, D-AUTH-009·CN-29)을 넘기면 이관 이벤트(`AuthorizationOutcomeUnresolved`·`CancellationOutcomeUnresolved`, OPERATIONAL_RISK)를 낸다. ADMIN `EXECUTION_UNKNOWN`은 backlog gauge(가장 오래된 나이)를 낸다. 알림 수신처·임계값·재알림 주기는 없다.
- 판단(rev 0.1a): 재조회·대사·알림을 적용한다는 원칙은 사용자 원문이므로 확정 행(BR-CAN-126)으로 둔다. 기준값(예산·최대 연령·재알림 주기·수신처)은 기관 규약·운영 체계가 있어야 정할 수 있으므로 **질문에서 빼고 LIVE Gate 항목**(대장 밖)으로 둔다. 그 전까지 Demo 값은 D-AUTH-009대로 버전 있는 설정이다.

### 4.5 실행 전 승인 내용 대조 위치(②-2 — D-1) — Coordinator 결정 CD-1(가역 구현 위치)

- 현재 사실: ADMIN은 값을 옮기기만 하고 대조하지 않는다. PAYMENT는 지문 자기 정합과 잔액·통화를 재검사한다(P2-2).
- ②-2는 사용자가 확정한 정책(D-GOV-015 ②)이다. 남은 것은 어디서 대조하느냐는 가역적인 구현 위치뿐이라 질문으로 올리지 않는다(rev 0.1b, 리뷰 C-1).
- **CD-1 (BR-CAN-127 구현):**
  - ADMIN이 서명 직전(승인 TX1)과 회복 재전송·기한 확인 전송 직전에 저장 본문을 해석한다.
  - 그 값을 승인 행·명령 행과 대조한다. 대조 항목: paymentId·금액·통화·목적 종류·근거·요청자·승인자·지문.
  - 다르면 전송하지 않고 무결성 위반(지표 + 감사)을 기록한다. 승인은 EXECUTING/EXECUTION_UNKNOWN을 유지한다(fail-closed, 수동 종료 없음).
  - PAYMENT는 현행대로 둔다(지문 자기 정합 + 잔액·통화 재검사, BR-CAN-049 동기 조회 없음).
- 한계:
  - ADMIN 대조는 ADMIN 본문 생성·직렬화 결함을 막는다.
  - **서명키 오용**(ADMIN을 거치지 않고 서명된 본문)은 막지 못한다. 이 경우는 사후 탐지(P2-5 결정 내용 대조, 대사 `FINGERPRINT_MISMATCH`)와 키 관리(D-OPS-001 키 공간 분리·교체, 운영 mTLS LIVE Gate)로만 다룬다.
  - PAYMENT가 ADMIN에 동기 조회하게 하는 안은 BR-CAN-049(D-CANCEL1-001)를 바꾸는 일이다. 사용자가 원할 때만 질문 대상으로 한다.
- 시험: T-4 유지. 구현 작업 때 T-10(본문 변조 주입 → 전송 없음)을 추가한다.

### 질문 후보(3개 — Q-COP-1, Q-COP-2 ⓐ, Q-COP-2 ⓑ)

| ID | 질문 | 권장안 | 대안 | 영향 | 막히는 작업 |
|---|---|---|---|---|---|
| **Q-COP-1** | 승인 내용과 PAYMENT 실행 결정이 어긋난 거래(`DECISION_CONTENT_MISMATCH`·`FINGERPRINT_MISMATCH`)의 후속 제한을 어떻게 할까? | ADMIN은 플래그가 있는 Payment에 새 보정 요청을 받지 않는다(해제는 2인 승인). PAYMENT에는 기존 incident hold를 같은 방식(가맹점 추가 취소 차단, 운영자 보정만 허용, 해제 = 2인 승인 + 대사 MATCH)으로 연다. FINANCIAL은 hold 사건으로 그 거래 지급을 보류한다. 신호 채널은 기존 ADMIN→PAYMENT 서명 채널에 명령 하나를 더한다 | A. 현행 유지(플래그·경보·감사만, 운영자 수동 조치). B. 그 Payment의 모든 추가 취소를 차단(운영자 포함) — 보정 경로까지 막힘 | 권장안은 hold reason 추가(CHECK migration), 서명 채널 명령 1개, FINANCIAL 지급 보류 연결. A는 변경 없음 + D-2 위험 유지. **주의: 권장안의 hold 해제 조건(2인 승인 + 대사 MATCH)은 RECON Use Case가 없는 지금은 채울 수 없다(BR-CAN-038). 따라서 RECON 전에는 hold를 풀 수 없고, 그 거래의 지급은 무기한 보류된다** | BR-CAN-065 조건 행 확정, RECON-CONTRACT-001 해제 설계 |
| **Q-COP-2 ⓐ** | 승인 단계 경합 창(ADMIN이 대상을 조회한 뒤 PAYMENT가 실행하기 전)에서 PAYMENT 거절이 승인을 소진하는 현행(D-OPS-005)을 D-GOV-015 ③ "일시 충돌 → 대기 유지·TOTP 미소모"의 예외로 둘까? 경우는 둘이다: (1) 가맹점 취소 시작 → `CANCEL_IN_PROGRESS`, (2) 잔액 revision 변경 → `OPERATOR_TARGET_STALE`. 둘 다 결정 기록·승인 소진·TOTP 소비 | 예외로 두고 현행 유지. 소진되면 새 요청·새 2인 승인을 받는다. 사전 `TARGET_BUSY`·`TARGET_CHANGED`로 대부분 막히고, approvalId별 결정 1회 원칙(BR-CAN-051)을 지킨다 | A. PAYMENT가 두 code를 결정으로 기록하지 않고(비소진) ADMIN이 PENDING으로 되돌림 — D-OPS-005 변경, 상태 복귀·TOTP 반환 구조가 복잡해짐(④-3과 충돌). B. (1)만 비소진 | 권장안은 코드 변경 없음, 진단 기록 + 경합 시험 T-5 추가. A·B는 PAYMENT 결정 규칙·ADMIN 상태기 변경 | BR-CAN-068 진단 표기 확정 |
| **Q-COP-2 ⓑ** | 보정 취소 승인이 만료·무효·거절·소진된 뒤 재승인 규칙은? | 재개는 항상 새 요청(새 approvalId, 새 2인 승인)으로 하고 옛 승인은 되살리거나 재사용하지 않는다. `EXECUTION_UNKNOWN`은 만료하지 않고 PAYMENT 확정 응답 또는 기한 확인 호출로만 끝낸다(현행). 대기 유효·실행 기한 숫자(현재 Demo 24h)는 질문이 아니라 LIVE Gate 항목 | A. 만료된 승인을 같은 승인자가 재확인해 되살림 — 재사용 금지 원칙·결정 1회(BR-CAN-051)와 충돌. B. `EXECUTION_UNKNOWN`에도 만료를 둠 — 실행 여부가 불명인 채 종료돼 이중 반환 위험(D-OPS-003과 충돌) | 권장안은 코드 변경 없음, BR-CAN-132 확정 | BR-CAN-132 |

P1-6(거절 사유 구분)과 P3-2(요청 단계 멱등키)는 금융 의미를 바꾸지 않는 응답·호출 방식이라 질문으로 올리지 않았다. Coordinator 적용 후보로 남긴다(예: 409 본문에 판정 근거 detail 추가 — 기존 code 유지).

---

## 5. 대장 변경안(`docs/rules/CAN.md` — 반영은 Coordinator)

원칙:
- 기존 규칙 문장은 바꾸지 않는다. 상태·출처·근거만 바꾼다.
- 조건은 새 행(출처 `사용자`, 근거 D-GOV-015)으로 둔다.
- 새 ID는 **BR-CAN-123부터** 쓴다. BR-CAN-112~122는 KPI-POLICY-001이 이미 쓴다.
- 상태는 README 형식(`확정`·`미결(Q-…)`)만 쓴다. 사용자가 원칙을 정하고 내용(규격·운영값)만 남은 것은 `확정` 행으로 두고, 내용은 대장 밖 LIVE Gate 항목으로 적는다.

### 5.1 기존 행 상태 확정

| 행 | 현재 | 변경 | 근거 추가 | 검증 열 비고 |
|---|---|---|---|---|
| BR-CAN-002 | 제안 / 적용 | **확정 / 사용자** | D-GOV-015 ① | "운영자 개시자 시험 없음(CX-07은 가맹점만)" 표기 — D-10 |
| BR-CAN-065 | 제안 / 적용 | **확정 / 사용자** | D-GOV-015 ② | 그대로 |
| BR-CAN-067 | 제안 / 적용 | **확정 / 사용자** | D-GOV-015 ③ | `UnresolvedApprovalRaceSpec` 추가 |
| BR-CAN-068 | 제안 / 적용 | **확정 / 사용자** | D-GOV-015 ③ | "승인 단계 경합 창(A 조회 뒤 실행 전 `CANCEL_IN_PROGRESS`·`OPERATOR_TARGET_STALE`)은 BR-CAN-051(소진)을 따름 — Q-COP-2 ⓐ 답 전 현행" 표기. 같은 내용을 진단 절에도 남김(§5.3) |

### 5.2 새 행 후보(조건 문장 — 7열)

| ID | 규칙 | 상태 | 출처 | 근거 | 적용 명세 | 검증 |
|---|---|---|---|---|---|---|
| BR-CAN-123 | 승인 결과 미확정 상태에서는 운영자 권한·2인 승인으로도 일반 취소를 우회 실행할 수 없다. | 확정 | 사용자 | D-GOV-015 ① | CANCEL-CONTRACT-001 §4.8, ADMIN-PAYMENT-OPS-001 §4.12·§4.13 | 없음(D-8 — 코드 `CancelStart.kt:88-92`, 시험 T-1·T-2 필요) |
| BR-CAN-124 | 승인 응답 timeout에 따른 망취소·자동 보상 취소는 일반 취소와 구분하고 카드사·VAN 규격에 따른 별도 정책으로 정한다(내용은 LIVE Gate 항목). | 확정 | 사용자 | D-GOV-015 ①, D-AUTH-002·008 | CANCEL-CONTRACT-001 §13 N-2, AUTH-CONTRACT-001 §8 | 없음(기능 부재 — 자동 보상 취소 없음은 D-AUTH-002 시험) |
| BR-CAN-125 | 취소 예약 기능을 두지 않는다. | 확정 | 사용자 | D-GOV-015 ① | CANCEL-CONTRACT-001(해당 기능 없음) | 없음(기능 부재) |
| BR-CAN-126 | 장기 미확정 거래(승인·취소 UNKNOWN 이관, 보정 실행 불명)에는 재조회·대사·운영 알림을 적용한다(알림 기준값은 LIVE Gate 항목). | 확정 | 사용자 | D-GOV-015 ①, D-AUTH-009, D-CANCEL1-003, D-OPS-003 | AUTH-001 §4.4·§6, CANCEL-001 §7.1, ADMIN-PAYMENT-OPS-001 §4.16 | `AuthorizationRecoverySpec::…AuthorizationOutcomeUnresolved once`, `CancellationRecoverySpec::…CancellationOutcomeUnresolved once`, `OwnerExecutionRecoverySpec::the backlog gauges`(알림 규칙 시험 없음) |
| BR-CAN-127 | 보정 취소 실행 전에 승인 내용과 실행 요청의 거래 식별자·금액·통화·보정 유형을 검증하고 불일치하면 실행을 차단한다. | 확정 | 사용자 | D-GOV-015 ② | ADMIN-PAYMENT-OPS-001 §4.13 B, CANCEL-001 §5.1·§5.2 | 부분 — `CancelStartSpec::an approval for another amount or for an older balance revision is stale`, `…binds approvalId, requester, approver, purpose type and purpose reference`(PAYMENT 자기 정합). ADMIN 명시 대조 없음(D-1 — 구현 위치 CD-1: ADMIN 서명·재전송 직전 대조, T-4·T-10) |
| BR-CAN-128 | 보정 취소의 승인 내용은 바꾸지 않고 실제 실행 결과와 별도로 보존한다. | 확정 | 사용자 | D-GOV-015 ② | ADMIN-PAYMENT-OPS-001 §4.11, §4.4.4 | V6·V28 런타임 권한, `AdminDatabaseAccountSpec`(이름만 확인) |
| BR-CAN-129 | 미확정 실행 결과를 성공으로 처리하지 않는다. | 확정 | 사용자 | D-GOV-015 ②, D-OPS-003 | ADMIN-PAYMENT-OPS-001 §4.13·§4.15 | `OwnerExecutionRecoverySpec::the decision lookup is unavailable → … state unchanged`(화면 표시 D-7은 시험 없음) |
| BR-CAN-130 | 승인 내용과 실행 결과가 불일치한 거래의 추가 취소·정산·지급 제한 기준을 둔다. | 미결(Q-COP-1) | 사용자 | D-GOV-015 ② | — | — |
| BR-CAN-131 | 보정 취소 결과 반영과 무결성 경보는 멱등이고 정합해야 한다. | 확정 | 사용자 | D-GOV-015 ② | ADMIN-PAYMENT-OPS-001 §4.13 D·§4.15 | `OwnerExecutionRecoverySpec::OutcomeDivergenceSpec`(지표 중복 D-9, T-8) |
| BR-CAN-132 | 보정 취소 승인이 만료·무효·거절·소진된 뒤의 재승인 규칙을 둔다(대기 유효·실행 기한 숫자는 LIVE Gate 항목). | 미결(Q-COP-2) | 사용자 | D-GOV-015 ③ | ADMIN-PAYMENT-OPS-001 §4.13 | `ApprovalDecisionTableSpec::at or after expires_at…`, `UnresolvedApprovalRaceSpec::…a terminal state … frees the Payment` |
| BR-CAN-133 | 보정 취소는 요청·승인·실행 단계마다 거래 상태와 취소 가능 잔액을 검증하고, 일반·보정 취소 사이 동시성·멱등은 PAYMENT가 최종 보장한다. | 확정 | 사용자 | D-GOV-015 ③ | ADMIN-PAYMENT-OPS-001 §4.12·§4.13, CANCEL-CONTRACT-001 §9.1·§9.2 | `CancelStartConcurrencySpec::K12`, `OwnerServiceApprovalFlowSpec::advisory (L-7)`, `…TARGET_CHANGED…` |
| BR-CAN-134 | 추가 금융 보정은 새 2인 승인(새 approvalId)을 거친다. | 확정 | 사용자 | D-GOV-015 ②, D-OPS-005 | ADMIN-PAYMENT-OPS-001 §4.4·§4.13 | `AdminOperatorCancellationApiSpec::business rejections are decisions (the approval is consumed)`, `OwnerServiceApprovalFlowSpec::SELF_APPROVAL` |

넣지 않은 조건: 아래 조건은 기존 행과 뜻이 같다. 근거 열에 D-GOV-015를 더하는 것으로 충분하다.
- ①-2: BR-CAN-002 본문
- ①-3: BR-CAN-015
- ①-4: BR-CAN-001·003
- ①-6 중복 방지: BR-CAN-020·024·051
- ③-2: BR-CAN-025·051
- ③-8: BR-CAN-010·029

LIVE Gate 항목(대장 밖, 기존 LIVE Gate 목록에 추가 후보):
- (a) 망취소·자동 보상 취소의 실제 규격과 정책 내용(BR-CAN-124)
- (b) 장기 미확정 알림의 예산·최대 연령·재알림 주기·수신처(BR-CAN-126)
- (c) 보정 취소 승인 대기 유효 시간·실행 기한 숫자(현재 Demo 24h — BR-CAN-054·132)

### 5.3 진단 절(`CAN.md` 끝 `## 진단`) 기록안

- **BR-CAN-068 ↔ BR-CAN-051 적용 범위**: BR-CAN-068은 승인 단계의 일시 충돌을 PENDING 유지·TOTP 미소모로 다룬다. 그러나 이것은 A 단계 사전 조회에서 잡힌 경우(`TARGET_BUSY`)에만 해당한다. A 조회 뒤 PAYMENT 실행 전에 생긴 충돌은 BR-CAN-051(D-OPS-005 "일시 거절도 승인 소진")을 따른다. 승인이 소진되고, TOTP는 이미 소비된 뒤다. 경우는 둘이다: 진행 중 취소 시작 → `CANCEL_IN_PROGRESS`, 잔액 revision 변경 → `OPERATOR_TARGET_STALE`. Q-COP-2 ⓐ 답 전에는 현행이다. 실제 경합 시험이 없다(T-5).
- **BR-CAN-127 구현 부분 일치**: 실행 전 대조는 PAYMENT 지문 자기 정합과 잔액·통화 재검사뿐이다. ADMIN이 승인 기록과 서명 본문을 명시 대조하는 단계는 없다. 구현 위치는 Coordinator 결정 CD-1(§4.5)이고, 서명키 오용은 사후 탐지·키 관리로만 다룬다.

---

## 6. 필요한 새 시험 시나리오(Given/When/Then — 작성하지 않음)

| ID | Given | When | Then | 대상 |
|---|---|---|---|---|
| T-1 | Payment AUTHORIZING(Authorization OPEN), 그리고 UNKNOWN(Authorization UNKNOWN). 지문이 맞는 2인 승인 운영자 개시자 | `CancelStart.start` | 둘 다 `PAYMENT_NOT_CANCELLABLE`, 어떤 Aggregate도 바뀌지 않음 | `CancelStartSpec`(P1-2) |
| T-2 | 실제 DB에 UNKNOWN Payment. 서명된 ADMIN 실행 명령 | `POST` 실행 | 409 `PAYMENT_NOT_CANCELLABLE`, REJECTED 결정 1행, 거절 감사 1행, 취소·예약·회복 일정 0행. 같은 바이트 재전송은 같은 409 + 감사 1행 추가 | `AdminOperatorCancellationApiSpec`(P1-2) |
| T-3 | 실제 DB에 AUTHORIZING·UNKNOWN Payment. 가맹점 키 | 가맹점 취소 | 409 `PAYMENT_NOT_CANCELLABLE` 저장, 같은 키 재생 | `CancelStartServiceSpec`(P1-1 보강) |
| T-4 | payment A용 지문을 가진 명령의 paymentId를 B로 바꿈(B도 AUTHORIZED) | 실행 | `OPERATOR_TARGET_STALE`, B에 변화 없음 | `CancelStartSpec`(P2-2) |
| T-5 | 승인 PENDING, A 단계에서 진행 중 취소 없음. 이어서 (1) stub로 C 직전에 가맹점 취소가 시작됨, (2) C 직전에 잔액 revision이 바뀜(가맹점 부분취소 완료) | 승인 | 현행: (1) `OWNER_REJECTED(CANCEL_IN_PROGRESS)`, (2) `OWNER_REJECTED(OPERATOR_TARGET_STALE)`. 둘 다 TOTP 소비, `unresolved_key` NULL, PAYMENT REJECTED 결정 1행. Q-COP-2 ⓐ 답에 따라 기대값 확정 | `OwnerServiceApprovalFlowSpec`·`AdminOperatorCancellationApiSpec`(P3-4) |
| T-6 | 운영자 보정 승인 PENDING, `expires_at` 경과 | APPROVER가 올바른 TOTP로 승인 | `EXPIRED`, TOTP 검증·소비 없음, PAYMENT POST 없음, 감사 `APPROVAL_EXPIRED`, 새 요청 가능 | `OwnerApprovalStepUpSpec`(P3-5) |
| T-7 | `DECISION_CONTENT_MISMATCH`로 EXECUTED된 Payment | 같은 Payment 새 보정 요청, 가맹점 추가 취소 | 현행 특성 시험: 둘 다 허용됨을 기록. Q-COP-1 답 뒤 차단으로 기대값 변경 | `OwnerExecutionRecoverySpec`·`CancelStartServiceSpec`(P2-9) |
| T-8 | 불일치 결정. (1) 회복 job 첫 주기의 TX2 실패 주입, (2) 웹 승인 경로에서 파생 409 응답 + TX2 실패 주입 | 회복 2주기 / 웹 승인 뒤 회복 1주기 | 플래그 1회, 감사 1행(`APPROVAL_INTEGRITY_MISMATCH` 또는 `APPROVAL_EXECUTION_UNKNOWN`), 최종 상태 정합. `integrity_alert` 증가 수를 기록(현행 각 2 — `OwnerExecutionRecoveryService.kt:231`, `OwnerServiceApprovalService.kt:186`) | `OwnerExecutionRecoverySpec`·`OwnerServiceApprovalFlowSpec`(P2-8) |
| T-9 | 실제 DB에 UNKNOWN 취소가 있는 Payment | 운영자 실행 명령과 가맹점 취소 동시 | 둘 다 `CANCEL_IN_PROGRESS`, 운영자 결정 REJECTED 1행(승인 소진), 진행 중 취소 1건 유지 | `CancelStartConcurrencySpec`(P3-8) |
| T-10 | (CD-1 구현 뒤) 승인 TX1에서 본문 직렬화 결과의 금액을 명령 행과 다르게 주입, 그리고 회복 재전송 직전 저장 본문과 명령 행이 다르도록 주입 | 승인 / 회복 1주기 | PAYMENT POST 없음, 무결성 위반 지표·감사 1행, 승인 EXECUTING/EXECUTION_UNKNOWN 유지 | `OwnerServiceApprovalFlowSpec`·`OwnerExecutionRecoverySpec`(P2-2) |

---

## 7. 하지 않은 것

- 소스·시험·migration·대장·`docs/DECISIONS.md`·명세·`harness/*` 수정, 커밋. 작업 트리의 다른 변경(`docs/DECISIONS.md`·`harness/tasks.json` 등 다른 작업의 수정 파일)은 건드리지 않았고, `docs/DECISIONS.md`만 읽었다.
- 시험·게이트 실행. 시험 열은 이름이 있는지만 확인한 것이고 통과 증거가 아니다.
- 승인 회복(`AuthorizationRecoveryService`) 전체 경로, FINANCIAL 쪽 취소 처리, RECON, Simulator 기관 프로필의 망취소 지원 여부 조사.
- `AdminSchemaSpec`·`AdminDatabaseAccountSpec`의 V6 권한 시험 내용 확인(이름만 확인).
- 화면 동작 확인(Demo 실행 없음). P2-6은 코드 읽기로만 판단했다.
- 질문의 사용자 제시 — Coordinator가 한다.
