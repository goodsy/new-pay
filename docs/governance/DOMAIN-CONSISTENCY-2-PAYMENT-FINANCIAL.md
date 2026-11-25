# 도메인 정합성 진단 2 — 결제·금융 (DOMAIN-CONSISTENCY-2-PAYMENT-FINANCIAL)

- 작성: 2026-11-25, 기준 커밋 `41139db`(main). 진단 전용 — 소스·DB·기존 문서·git 무변경, 정책 확정 없음.
- 작업 트리: `harness/tasks.json` 미커밋 수정(+53/−2), `docs/rules/`·`RULE-GOV-001` 미추적. 이 진단은 HEAD 코드 기준이며 위 파일에 의존하지 않는다(`docs/rules/`에는 현재 `README.md`·`WH.md`만 있고 PAY·CAN·FIN은 아직 없음).
- 표기: **[사실]** = 파일:줄·명령으로 확인, **[추정]** = 해석, **[미확인]** = 확인하지 못함. 판정 = **일치 / 문서만 / 코드만 / 불일치**.
- 경로 약어:
  - `PAY/` = `apps/payment-api/src/main/kotlin/com/paycore/payment/payment/`
  - `PAYDB/` = `apps/payment-api/src/main/resources/db/`
  - `FIN/` = `apps/financial-batch/src/main/kotlin/com/paycore/financial/`
  - `FINDB/` = `apps/financial-batch/src/main/resources/db/`
- 시험은 실행하지 않았다(존재·코드 경로만 확인). 게이트 결과는 기존 기록을 인용하지 않고 판정에 쓰지 않았다.

---

## 1. 결론 요약

1. **승인·취소·UNKNOWN·멱등·append-only·TX 경계 규칙은 코드에서 지켜지고 있다.** [사실]
   - 기관 호출이 DB TX 밖에 있다(`PAY/application/AuthorizationDispatchService.kt:84`의 게이트 TX가 끝난 뒤 `:105`에서 호출, 취소는 `CancellationDispatchService.kt:75`·`:92`).
   - 결과 반영·Outbox가 한 로컬 TX에 있다(`AuthorizationResultService.kt:119-137`, `:146`).
   - timeout·NOT_FOUND·lease 만료는 종료 근거가 아니다(`PAY/domain/TerminationEvidence.kt`, `AuthorizationResolution.kt:39`, `CancellationResolution.kt:51`).
   - 확정 사실 표에는 runtime 계정에 UPDATE·DELETE 권한이 없다(`PAYDB/runtime-privilege/payment/V10…:9`, `V2…:20,30,38`, `FINDB/runtime-privilege/financial/V3…`, `V6…`).
   - 지금 데이터를 깨뜨리는 결함은 찾지 못했다.
2. **문제는 "끝맺음 경로"가 없다는 것이다.** 사건을 감지하고 막는 쪽(hold·UNKNOWN 유지·대사 이관 이벤트)은 구현되어 있다. 반면 그 사건을 해소하거나 금융 사실로 기록하는 쪽(RECON)은 없다.
   - 기관이 실제로 낸 효과(종료 뒤 늦은 승인, 거절 뒤 늦은 취소 성공)가 PAYMENT 거래로 기록되지 못한다.
   - 그 효과가 FINANCIAL에 전달되지도 않는다.
   - PAYMENT의 사건 hold가 FINANCIAL에 전달되지 않는다. 그래서 매입은 사건이 걸린 결제도 정상 매입한다(§5.3). **LIVE 전환 전 반드시 막아야 하는 P0 두 건**이다.
3. **정산(Settlement)·지급(Payout)·대사(Reconciliation)는 문서만 있다.** financial-batch 모듈은 `paymentfact`·`acquiring` 둘뿐이고, 정산·지급·대사 Aggregate·표·Use Case는 0개다. 수수료 Snapshot은 버전 문자열 하나다(`policy_assignment_version`, `FINDB/migration/financial/V2__payment_fact_inbox.sql:81,125`). [사실]
4. **정책 충돌 1건이 사용자 결정을 기다린다.** "매입 전송 완료" 선지급 기준을 유지할지가 확정 문서와 D-ACQ-005 사이에서 어긋난다. 또 그 기준의 근거에 Implementer 해석(I-9 RENAMED)이 들어가 있다.
5. 문제 수: **P0 2 · P1 6 · P2 4 · P3 1 · P4 6 = 19건**(§7).

---

## 2. 기존 진단(DOMAIN-ASSESSMENT, CURRENT-STATE) 대비

기존 진단의 기준 커밋은 `378aff7`이다. 그 뒤 29커밋 사이에 `apps/payment-api/src/main`·`apps/financial-batch/src/main`의 변경은 **0줄**이다(`git diff --stat 378aff7..HEAD`로 확인, 시험 파일 1개만 바뀜). 그래서 결제·금융 코드에 대한 기존 사실 기술은 그대로 유효하다. [사실]

| 기존 진단 절 | 판정 | 내용 |
|---|---|---|
| DOMAIN-ASSESSMENT §1-1, §3.1 "금융 도메인 규칙은 순수 Domain에 잘 모여 있다" | **확인** | Payment·OrderPaymentControl·Resolution·ReturnBalance 규칙과 줄 위치를 재확인했다(§4.1·§4.2). |
| DOMAIN-ASSESSMENT §3.1 IncidentHold/Release "해제는 2인 승인 + 재대사 MATCH로만" | **보완(반박 아님)** | 규칙은 domain에만 있고 **실행 경로가 없다**. `IncidentHoldSettlement`를 부르는 main 호출자가 0개이고, runtime 권한은 `incident_hold_release` SELECT만이다(`PAYDB/runtime-privilege/payment/V10__authorization_runtime_privileges.sql:14-15`, 주석: "해제 쓰기 경로 없음"). 보존 자산이지만 아직 운영 기능은 아니다. |
| DOMAIN-ASSESSMENT §3.1 PaymentAuthorization "FAILED에도 RECON 늦은 승인 거래" | **보완** | `recordLateApprovalByReconciliation`(`PAY/domain/PaymentAuthorization.kt:154`) 주석에 "지금 main 호출자는 없다"(`:152`)고 되어 있다. |
| DOMAIN-ASSESSMENT §3.1 "도메인 이벤트 타입은 없다(Outbox 레코드로만)" | **확인** | `ApplicationEventPublisher`·`@EventListener`가 payment·financial main에서 0개다. |
| DOMAIN-ASSESSMENT §3.3 financial domain "정산·대사 도메인 없음" | **확인** | 모듈이 `paymentfact`·`acquiring`뿐이다(§4.4~4.6). |
| DOMAIN-ASSESSMENT §4.3 "financial acquiring에는 provided package-info가 없다" | **확인** | `paymentfact/application/provided/package-info.java`만 있다. |
| DOMAIN-ASSESSMENT §6 "재구현 후보(사용자 결정 필요): 1:1 Mapper + 다중 Jpa*Entity 구조" | **달라짐 — 결정됨** | D-PROC-004 "순수 Domain과 JPA Entity 분리 원칙을 유지한다"(DECISIONS, 378aff7 뒤 추가). 서비스·DB 분리도 D-PROC-005로 유지가 결정됐다. |
| DOMAIN-ASSESSMENT §7-7 보존 자산 "financial HoldReleaseRule" | **확인 + 한계** | 자동 해제는 ORIGINAL_UNKNOWN만이다(D-FFR-003). 다른 보류 5종과 REVIEW_REQUIRED는 해소 경로가 없다(Q-ACQA-07 PENDING) → DC-P-07. |
| CURRENT-STATE §5 "UNKNOWN 재조회·Recovery — 코드 있음" | **보완** | 재조회·대사 이관(`AuthorizationOutcomeUnresolved`)까지만 있다. 이관 이벤트의 소비자가 없다(DC-P-04). |
| CURRENT-STATE §5 "매입 — 있음 / 기관·원장 대사 — 없음" | **확인** | 매입 결과 UNKNOWN 재매입은 D-ACQ-014 때문에 "대사 전 거절"이다(`FIN/acquiring/domain/ReacquisitionRule.kt:33`). 즉 대사가 없으면 영구히 막힌다. |
| CURRENT-STATE §4 "PAYMENT→FINANCIAL 결제 사실: relay → HMAC HTTP → inbox" | **확인 + 범위 지적** | 전달 대상은 `FINANCIAL_FACT` 범주뿐이다(`PAY/adapter/persistence/JpaFinancialFactRelayStore.kt:144`). `RISK_INCIDENT`·`OPERATIONAL_RISK`는 전달되지 않는다 → DC-P-02, DC-P-11. |
| CURRENT-STATE §5 "tasks 70개: DONE 51 / …" | **달라짐** | 현재 작업 트리의 tasks.json은 41개(BACKLOG 19 / DONE 15 / IN_PROGRESS 7)다(미커밋 수정, D-PROC-002 정리로 추정). 미결 질문 15개는 목록이 같다. |
| CURRENT-STATE §3 "설계 9종 중 relay·worker는 별도 프로세스가 아니다" | **확인** | Relay는 payment-api 안의 스케줄러다(`PAY/adapter/batch/FinancialFactRelayScheduler.kt`). baseline 01과의 차이는 기존 진단이 이미 다뤘다. |

---

## 3. 실행 단위·모듈 지도 [사실]

| 실행 단위 | Modulith 모듈 | 대상 도메인 | 소유 DB |
|---|---|---|---|
| payment-api | `payment.payment`(domain 78파일), `payment.entitlement` | Payment, Refund(카드 취소) | PAYMENT |
| financial-batch | `financial.paymentfact`, `financial.acquiring` | Acquisition(+결제 사실 투영·보류) | FINANCIAL |
| (없음) | — | Settlement, Payout, Reconciliation | (FINANCIAL 예정 — 문서) |
| internal-admin | `paymentops`, `financialops`, `approval` | 운영 명령(보정 취소·재매입 2인 승인) | ADMIN |
| institution-simulator | `van` 등 | 가상 VAN·매입사 | SIMULATOR |

주의(이름): internal-admin `approval` 모듈의 `Reconciliation*`(`OperatorDecisionReconciliationScheduler`, `JpaReconciliationFindingStore` 등)은 **관리자 승인 결정과 소유 서비스 결정을 맞춰 보는 작업**이다. 금융 대사(RECON)가 아니다 → DC-P-16.

---

## 4. 도메인별 대조

### 4.1 Payment (주문·Payment·Attempt·Authorization)

**Aggregate [사실]**

| Root | 식별자 | 경계(포함) | 위치 |
|---|---|---|---|
| OrderPaymentControl | OrderKey(environment, MID, merchantOrderId) | 현재 Payment·generation·고정 입력 fingerprint·과거 승인 ID·IncidentHold/Release(append-only) | `PAY/domain/OrderPaymentControl.kt` |
| Payment | PaymentId(+slotGeneration) | 상태, 종료 근거와 출처(append-only), 결제창 시각 | `PAY/domain/Payment.kt:29-203` |
| PaymentAuthorization | AuthorizationId(Payment 1:1) | 고정 금액·Plan·세션·판정 버전, AuthorizationAttempt, 관찰(append-only), AuthorizationTransaction | `PAY/domain/PaymentAuthorization.kt:28-40` |
| DailyLimitUsage | (MID, 통화, 환경, 날짜) | 한도 점유 | `PAY/domain/DailyLimitUsage.kt` |
| AuthenticationSession | 세션 ID | 카드 인증 lifecycle, 귀속 1회 | `PAY/domain/AuthenticationSession.kt` |

- 승인 확정 한 TX에서 control·Payment·Authorization·한도를 함께 잠그고 저장한다(`AuthorizationResultService.kt:120-133`). **여러 Aggregate를 한 로컬 TX로 묶는 것은 의도된 설계**다. 문서(OrderPaymentControl.kt 주석 "같은 PAYMENT DB의 같은 로컬 TX", CP 계약 §2·§5)와 일치한다.

**상태와 전이**

| 대상 | 상태 | 전이 | 근거 |
|---|---|---|---|
| Payment | REQUESTED, AUTHORIZING, UNKNOWN, AUTHORIZED, FAILED, EXPIRED | REQUESTED→AUTHORIZING(control 경유만)→UNKNOWN→AUTHORIZED/FAILED/EXPIRED. 종료 3상태는 불가역 | `Payment.kt:67-131` |
| PaymentAuthorization | OPEN, UNKNOWN, AUTHORIZED, FAILED | Resolution만 바꾼다 | `PaymentAuthorization.kt:8,28-36` |
| AuthorizationAttempt | PREPARED, DISPATCHING, ABANDONED_BEFORE_DISPATCH, OUTCOME_UNKNOWN, RESOLVED | 전송 전 포기만 PRE_TRANSMISSION 근거 | `AuthorizationAttempt.kt:34-49` |

**불변식·규칙 대조**

| 규칙(문서) | 문서 근거 | 코드 | 판정 |
|---|---|---|---|
| 주문 key = env+MID+merchantOrderId | CLAUDE.md, 시도 §1, DOMAIN-DEFINITION §3.2 | `OrderKey`, `OrderKeyColumns.kt` | 일치 |
| REQUESTED 포함 활성 하나, 다른 키·같은 내용은 재사용, 다른 내용은 충돌 | 시도 §1·§5, CLAUDE.md | `OrderPaymentControl.decide` `:97-115`(FixedIntentConflict → hold → 과거 승인 → 상태 순서) | 일치 |
| AUTHORIZING/UNKNOWN/AUTHORIZED에서 새 결제 금지, 전액 반환 뒤에도 재승인 금지 | 시도 §2, CLAUDE.md | `decide` `:103-108`. `authorizedPaymentId`는 지우지 않는다 | 일치 |
| FAILED/EXPIRED는 충분한 근거가 있을 때만, 교차 근거는 인정 안 함 | D-CPD-002·003 | `TerminationEvidence.kt`(근거 종류별 허용 상태), `Payment.hasSafeTermination` `:107` | 일치 |
| timeout/NOT_FOUND/lease 만료로 무효과 단정 금지 | CLAUDE.md, 시도 §2 | `TerminationEvidence` TIMEOUT·INQUIRY_NOT_FOUND·LEASE_EXPIRED = 빈 집합. `AuthorizationResolution.kt:188,208` | 일치 |
| 근거 출처 표(관찰/포기/만료) | AUTH-CONTRACT-001 §5.5 | `Payment.requireSource` `:137-146` | 일치 |
| 오래된 결과가 새 점유를 풀지 못함 | DOMAIN-DEFINITION §4.1 | `ensureCurrent` + generation(`:123-135`) | 일치 |
| Payment당 Authorization 하나, **Attempt 여러 개 허용**(동시 실행 하나) | 시도 §1, DOMAIN-DEFINITION §3.2 "AuthorizationAttempt" 행 | 초기 계약은 "dispatch Attempt 1개"(CA-13, `PaymentAuthorization.kt:32`). reconstitute는 ≥1·진행 중 ≤1만 검사(`:232-236`) | **일치(축소 구현)** — 같은 문서 안에 두 표현이 섞여 있다 → DC-P-15 |
| 요청 전액 승인만, 부분 승인은 상충 hold | 시도 §1, D-AUTH-008 | `PaymentAuthorization.kt:143` "only a full approval", D-AUTH-008 경로 | 일치 |
| 일 한도는 취소로 복원하지 않음 | D-AUTH-007 | `DailyLimitUsage.kt:105-111,144` | 일치 |
| connect 실패(바이트 0) = 전송 전 실패 확정 | AUTH-001 §5.2, AU-34 | `SimulatorVanAuthorizationAdapter.kt:25-26,92,95`. 쓰기 시작 뒤는 OutcomeUnknown(`:114,117`) | 일치 |
| 늦은 성공·상충은 자동 보상취소 없이 hold + 대사 Case | D-AUTH-002 | hold 개설·이벤트 append는 있다(`AuthorizationResultService.kt:151-155`). **대사 Case 생성 주체는 없다** | **문서만(후반부)** → DC-P-01·03 |
| hold 해제 = 2인 + MATCH | D-AUTH-002 | domain `IncidentHoldRelease.kt:35-40`. 실행 경로·쓰기 권한은 없다 | **문서만(실행)** → DC-P-03 |
| 승인 확정 TX = Payment + Authorization + **요청 멱등성 결과** + Outbox | baseline 02 §트랜잭션 경계 2 | 생성 멱등은 최초 생성 결과를 고정한다(D-CP-003). confirm은 authorizationId 참조로 현재 결과를 반환한다(D-AUTH-004). TX2는 멱등 행을 갱신하지 않는다 [사실: `AuthorizationResultService.apply`에 멱등 저장소 없음] | 일치(후속 결정 D-CP-003·D-AUTH-004가 02를 구체화) |

**연동**

| 방향 | 방식 | 코드 | 판정 |
|---|---|---|---|
| PAYMENT → VAN | TX1(Attempt·요청 ID) → 게이트 TX → TX 밖 TCP → TX2 | `AuthorizationDispatchService.kt:77-117` | 일치 |
| PAYMENT 회복 | PREPARED fencing / DISPATCHING lease 만료 → UNKNOWN / OUTCOME_UNKNOWN 조회 → 예산·연령 소진 시 이관 | `AuthorizationRecoveryService.kt:110-205` | 일치 |
| 무효과 종결 요청 | Route Capability `confirmsNoEffect` + 같은 프로파일 버전 + 보존기간 안 | `AuthorizationRecoveryService.kt:149` | 일치(가상 규격. 실제 VAN에 있다고 가정하지 않는다 — DOMAIN-DEFINITION §3.2) |
| PAYMENT → FINANCIAL | `PaymentAuthorized`(FINANCIAL_FACT) Outbox → Relay → Inbox | `AuthorizationEvents.kt:49`, Relay §5.2 | 일치 |
| PAYMENT → RECON | `AuthorizationIncidentDetected`(RISK_INCIDENT), `AuthorizationOutcomeUnresolved`(OPERATIONAL_RISK) | 쓰기만 있음(`AuthorizationEvents.kt:79-80,97`). 소비자 0 | **문서만(소비)** → DC-P-02·04·11 |

### 4.2 Refund (취소·부분취소·환불)

**Aggregate [사실]**

| Root | 식별자 | 경계 | 위치 |
|---|---|---|---|
| PaymentCancellation | CancellationId. 업무 키 (Payment, merchantCancelId) 또는 운영자 approvalId | 상태, CancelAttempt 1개, 관찰, CancelTransaction | `PAY/domain/PaymentCancellation.kt` |
| ReturnBalance | Payment 1:1 | ReturnReservation(RESERVED/CONSUMED/RELEASED/FORCED), 업무 revision | `PAY/domain/ReturnBalance.kt` |
| (Refund — 계좌 환불) | — | **v1 범위 밖**(D-CANCEL-007) | 코드 없음 |

**상태**: CancellationStatus OPEN, UNKNOWN, COMPLETED, FAILED(`PaymentCancellation.kt:6`). COMPLETED ⇔ CANCEL 거래 하나. FAILED는 충분한 근거로만 된다(`:108`, `:243-247`).

| 규칙 | 문서 근거 | 코드 | 판정 |
|---|---|---|---|
| 검사 순서: hold → 진행 중 → 잔액 → Capability → 2인 승인 | CANCEL-CONTRACT-001 §4, D-CANCEL-001·003·005 | `CancelStart.kt:68-69,102-118` | 일치 |
| INV-C1 `completed + reserved ≤ authorized`. FORCED는 따로 합산하고 초과를 표시 | CANCEL-CONTRACT-001 H-1, D-CANCEL-006 | `ReturnBalance.kt:83,110-114,145-148,171,221` | 일치 |
| Payment당 진행 중 취소 1건(UNKNOWN도 차단) | D-CANCEL-003 | `ReturnBalance.reserve` `:113`, `reconstitute` `:204` | 일치 |
| UNKNOWN에서 새 Attempt 금지, NOT_FOUND·timeout은 UNKNOWN 유지 | K-14 | `CancellationResolution.kt:33,51` | 일치 |
| 거절 뒤 늦은 성공 = 상태 유지·**거래 없음**·FORCED·hold | D-CANCEL-006 | `CancellationResolution.kt:54`, `OrderPaymentControl.reportCancellationResultConflict` `:232-239`(출처 null) | 일치 — 단, 실제 반환 사실이 FINANCIAL에 전달될 길이 없다 → DC-P-01 |
| CANCEL_RESULT_CONFLICT hold 해제는 RECON 전까지 거절 | D-CANCEL-006, CANCEL-CONTRACT-001 m-1 | `OrderPaymentControl.kt:252-255` | 일치(fail-closed) → DC-P-03 |
| 수수료·VAT 반환은 원승인 Snapshot 누적 비례(계산은 SETTLEMENT) | D-CANCEL-008, CLAUDE.md | CANCEL 거래가 원 AUTHORIZATION 거래를 참조한다(`PaymentTransaction.kt:40-52`). 정책 버전 사본은 이벤트에 있다(`OutboxPayloadJson.kt:79`). 계산은 없다 | **문서만(계산)** |
| 정산 거래일 = 기관 취소 거래일(PG 확정 시각과 섞지 않음) | DOMAIN-DEFINITION §3.2, 정산 §3 | `CancelTransaction.institutionTransactionDate`(`PaymentTransaction.kt:40-42`), FINANCIAL 열 `institution_transaction_date`(`V2__payment_fact_inbox.sql:78,122`) | 일치(보존까지만) |
| 이중 승인 보정 = 늦게 확정된 P1을 운영자 보정 취소 | D-CANCEL-011 | `CancelStart.kt:86`이 허용. 단 P1 거래를 기록하는 RECON이 없어 **실제로는 도달할 수 없다** | **문서만(선행 조건)** → DC-P-01 |
| 계좌 환불 | D-CANCEL-007 범위 밖. baseline 02 표에는 "Refund 거래 상태"가 PAYMENT 원본으로 남아 있다 | 없음 | 문서만(범위 밖 표시 누락) → DC-P-19 |

연동: `PaymentCancelled`(FINANCIAL_FACT, `CancellationEvents.kt:41`) → FINANCIAL 취소 투영. `CancellationIncidentDetected`(RISK_INCIDENT, `:77`)와 `CancellationOutcomeUnresolved`(OPERATIONAL_RISK, `:95`)는 소비자가 없다.

### 4.3 Acquisition (매입·재매입) — financial-batch

**Aggregate·규칙 [사실]**

| 대상 | 식별자 | 내용 | 위치 |
|---|---|---|---|
| PaymentFact 투영 | transactionId(PK), effect_key·source_event_id UNIQUE | 원본이 아님. 사실을 만들지 않는다 | `FINDB/migration/financial/V2…:86-88` |
| PaymentFactHold / HoldRelease | (거래, 사유) | 보류 6종. ORIGINAL_UNKNOWN만 자동 해제(일치하면 RELEASED, 아니면 REVIEW_REQUIRED) | `FIN/paymentfact/domain/HoldReason.kt`, `HoldReleaseRule.kt` |
| AcquisitionFile | AcquisitionFileId(매입사·환경·업무일·세대) | 3축: 전송(SEALED…TRANSMITTED/UNKNOWN/QUARANTINED/WITHHELD), 수신(NONE/RECEIVED/FILE_REJECTED/RECEIPT_CONFLICT), 결과(AWAITING/COMPLETE/DEADLINE_PASSED). 봉인 바이트 불변 | `FIN/acquiring/domain/AcquisitionFile.kt:14,163,244-250` |
| AcquisitionRecord | (거래, 종류 SALE/CANCEL, 세대) | KRW만, minor 정수 | `AcquisitionRecord.kt:10,48` |
| Reacquisition | 원파일 참조 새 세대 | 2인 승인, 대상 버전, UNKNOWN은 대사 전 거절 | `ReacquisitionRule.kt:20,33` |

| 규칙 | 문서 근거 | 코드 | 판정 |
|---|---|---|---|
| 매입 대상은 PAYMENT 승인 확정 건만. 전송 전 전액 취소는 제외, 부분 취소는 상계 | D-ACQ-002·012 | `AcquisitionCandidateRule.kt:19-25,66` | 일치 |
| 원거래 모르는 취소는 Hold | D-ACQ-002, baseline 03 §순서 | HoldReason.ORIGINAL_UNKNOWN, 자동 해제 D-FFR-003 | 일치 |
| ACK ≠ 건별 결과. 마감까지 결과 없으면 UNKNOWN → 대사, 자동 재전송 금지 | D-ACQ-004 | `RecordResultRule.kt:9,22,63` | 일치(대사 소비자 없음 → DC-P-08) |
| 원 SALE ACCEPTED일 때만 취소 매입. UNKNOWN이면 대사·경보 | D-ACQ-009 | `CancelRecordRule.kt:14,22-23`(RECONCILE) | 일치(RECONCILE 소비자 없음 → DC-P-08). DOMAIN-DEFINITION §3.3은 "HOLD"라고 쓴다 → DC-P-17 |
| "매입 요청" = **외부기관 전송 완료**(파일 생성·시작·부분 업로드는 불충분) | 정산지급방식 "확정: 매입 요청 인정 시점" | TRANSMITTED 근거 3종 RENAMED / VERIFIED_FINAL_PRESENT / ACK_HASH_MATCH(`TransmissionOutcome.kt:32-40`, ACQUIRING-001 I-9) | **불일치 가능성 — 확인 필요.** RENAMED는 "rename 성공 응답 + 재접속 확인 실패"다. 기준 문서는 "라이브러리 송신 호출 성공만으로 전송 완료가 증명된다고 가정하지 않는다"고 한다. I-9는 Implementer 해석이다 → DC-P-06 |
| 매입 결과에 따른 지급 보류 = SETTLEMENT가 정함 | D-ACQ-005 | 매입은 사실 기록·지표만 | 일치 |
| 재매입 2인 승인 | D-ACQ-011 | admin `financialops` + FINANCIAL 재검사 | 일치 |
| 사건 hold(PAYMENT)가 걸린 결제의 매입 보류 | (명시 문서 없음. D-AUTH-002 "대사 Case"와 정산 D "위험 건 Hold"에서 도출) | 대기 사유는 FINANCIAL 자체 보류만 본다(`AcquisitionCandidateRule.kt:22-24`). PAYMENT RISK_INCIDENT는 전달되지 않는다 | **문서·코드 모두 공백** → DC-P-02 |

### 4.4 Settlement (정산) — 문서만

| 항목 | 문서 정의 | 코드 | 판정 |
|---|---|---|---|
| SettlementEntry(승인·취소 각각 독립 Entry), transactionDate → settlementBaseDate → settlementDueDate | 정산 정책 §3-5, DOMAIN-DEFINITION §3.3 | 없음 | 문서만 |
| SettlementStatement(적격성 판정 후 확정), Payable(금액 불변) | 정산 정책 말미 A, §10 | 없음 | 문서만 |
| Fee/VAT = 원승인 Snapshot, 버전 있는 기준정보 | CLAUDE.md, 정산 §1-2·말미 B | PAYMENT·FINANCIAL에 `policy_assignment_version` **문자열만** 있다. MASTER 정책 내용은 UNDEFINED/DEMO(DOMAIN-ASSESSMENT §3.2). MASTER→FINANCIAL 정책 Snapshot 채널이 없다 | 문서만(버전 참조만 코드) → DC-P-09 |
| BusinessCalendar(PAYCORE_KR, 버전), SettlementCycle DAILY_OFFSET(D+2 Demo) | 정산 §6-8 | 매입 마감 정책만 있다(`CutoffPolicy.kt`). 영업일 = 달력일(Demo 임시, D-ACQ-003) | 문서만(달력 미결 Q-CALENDAR) |
| FINANCIAL 소비 TX = Inbox + Projection + **정산 후보** | baseline 02 예시, DOMAIN-DEFINITION §4.2 | Inbox + 투영 + 보류까지다(`PaymentFactInboxService.kt:72-104`). 정산 후보는 없다 | 문서만 → DC-P-10 |
| 외화 정산 통화 | Q-SETTLE-CURRENCY PROPOSED | 매입은 KRW만(I-5 해석) | 미결 |
| 작업 | SETTLEMENT-CONTRACT-001, SETTLEMENT-001 = BACKLOG | — | — |

### 4.5 Payout (지급·선지급) — 문서만

| 항목 | 문서 정의 | 코드 | 판정 |
|---|---|---|---|
| 지급 모드 2종(입금 후 / 선지급) + 선지급 기준 3종(승인 완료 / 매입 전송 완료 / 매입 확정) | 정산지급방식 "확정", 정산 정책 말미 A, CLAUDE.md | 없음. 기준 근거 데이터 중 "승인 완료"(PaymentFact)와 "매입 확정"(ACCEPTED)은 있고, "매입 전송 완료"(TRANSMITTED)는 DC-P-06의 해석 문제가 있다 | 문서만 |
| Payout 단일 활성 실행·fencing, lease 만료만으로 미호출 가정 금지, UNKNOWN이면 예약 유지 | DOMAIN-DEFINITION §3.3, 정산지급방식 §선지급 | 없음 | 문서만 |
| SettlementHold / PayoutHold(ADVANCE_ONLY / ALL_PAYOUT), 하나 해제로 다른 Hold 유지 | 정산 §13-14 | 없음 | 문서만 |
| 지급계좌 | MASTER-PAYOUT-ACCOUNT-001 BACKLOG | 없음 | 문서만 |
| 은행 Simulator | DOMAIN-DEFINITION §3.4 | institution-simulator에 은행 모듈 없음 [사실: 모듈 acquirer·authentication·merchant·van만] | 문서만 |

### 4.6 Reconciliation (대사·Adjustment·Hold) — 문서만(입력 이벤트·보류 표지는 코드에 있음)

| 항목 | 문서 정의 | 코드 | 판정 |
|---|---|---|---|
| ReconciliationCase, Risk LOW/MEDIUM/HIGH/CRITICAL별 처리 | 정산 §15-21, D-AUTH-002 | 없음 | 문서만 |
| Auto Recovery(원장 변경 없음) ≠ Adjustment(차액 보정 기록, UPDATE 금지) | 정산 §16, CLAUDE.md | 없음 | 문서만 |
| 보정 후 재대사 MATCH 전에는 해결 금지 | 정산 §21, CLAUDE.md | 입력 측 강제만 있다: `IncidentHoldRelease.reconciliationMatchRef` 필수(`IncidentHoldRelease.kt:37`). 그 값을 만드는 주체는 없다 | 문서만(형태만 코드) |
| 대사 입력: AuthorizationOutcomeUnresolved, CancellationOutcomeUnresolved, *IncidentDetected, 매입 RECONCILE·UNKNOWN | AUTH-CONTRACT-001 표 402-403, CANCEL-CONTRACT-001 481-482, ACQUIRING-001 | 생산은 있고(PAYMENT Outbox 행, FINANCIAL 기록) 소비는 없다 | **코드만(생산)** |
| RECON이 늦은 승인 거래 기록(+ PaymentAuthorized, D-CANCEL-012 예외) | D-CANCEL-010~012 | domain 메서드만 있다(`PaymentAuthorization.kt:154`, 호출자 0) | 코드만(미사용 domain) |
| Kill switch, Dry run, Replay/Backfill, 자동 보정 범위 | 정산 §22-27, Q-CORRECTION | 없음 | 문서만(미결) |
| 작업 | RECON-CONTRACT-001, RECON-001 = BACKLOG | — | — |

---

## 5. 횡단 검사 (UNKNOWN·timeout·멱등·append-only·TX 경계)

### 5.1 TX 경계 [사실]

| 검사 | 결과 | 근거 |
|---|---|---|
| 외부 기관 호출이 DB TX 밖인가 | 예 | `AuthorizationDispatchService.kt:84`(게이트 TX 종료) → `:105`(호출) → `:111`(TX2 별도). 취소는 `CancellationDispatchService.kt:75,92`. Relay는 `FinancialFactRelayService.kt:54-55,79,81`(TXa/TXb → TX 밖 HTTP → TXc). Use Case가 TX를 소유하고 이미 TX가 있으면 거부한다(`LocalTransactions.kt` `check(!isActualTransactionActive())`) |
| 업무 효과 + Outbox가 같은 TX인가 | 예 | `AuthorizationResultService.kt:119-137`, `CancellationResultService.kt:125,159-169`, 이관 이벤트 `AuthorizationRecoveryService.kt:187-201` |
| Inbox + 투영이 같은 TX인가 | 예 | `PaymentFactInboxService.kt:58,72-104`. 중복은 `setRollbackOnly` 후 저장 결과를 응답한다 |
| commit 결과를 모를 때 | 다시 보내지 않고 회복에 맡긴다 | `AuthorizationDispatchService.kt:85-88`(COMMIT_UNKNOWN → 미전송 → lease 만료 → UNKNOWN) |
| cross-DB | 0건(CURRENT-STATE §4 확인 결과를 재사용. 이번에 다시 grep하지 않음) | — |

### 5.2 멱등 [사실]

- PAYMENT 이벤트 effect_key(`PaymentAuthorized:{transactionId}`, `PaymentCancelled:{transactionId}`, `…Unresolved:{attemptId}`).
- FINANCIAL Inbox `UNIQUE(event_id)`·`UNIQUE(effect_key)`(`V2__payment_fact_inbox.sql:29-30`). 같은 eventId·다른 바이트 = EventIdConflict. 같은 effect·다른 eventId = EffectKeyConflict(`PaymentFactInboxService.kt:76-87`).
- baseline 02 "Inbox (consumer, eventId) Unique": 소비자가 하나라 event_id 단독 UNIQUE + 업무 키 UNIQUE로 같은 효과를 낸다 → 일치.

### 5.3 금융 사실 전달 범위 — 핵심 공백 [사실]

- Relay 발견 질의는 `event_category = 'FINANCIAL_FACT'`만 본다(`JpaFinancialFactRelayStore.kt:144`). FIN-FACT-RELAY-001 §범위표(14행)는 RISK_INCIDENT·OPERATIONAL_RISK 전달을 "RECON 작업"으로 미뤄 두었다.
- 그 결과, PAYMENT가 hold한 결제에 대해 FINANCIAL은 아는 것이 없다.
  - **이중 승인(D-AUTH-011)**: 두 번째 승인의 PaymentAuthorized는 전달된다.
  - **늦은 취소 성공(D-CANCEL-006)**: 반환 효과가 PaymentCancelled로 전달되지 않는다.
  - **CONFLICTING(D-AUTH-008)**: 승인 사실 자체가 없다.
- 매입 후보 규칙은 FINANCIAL 자체 보류만 확인한다(`AcquisitionCandidateRule.kt:22-24`). 그래서 늦은 취소 성공이 있었던 결제의 SALE도 다음 정기 파일에 포함된다.
- [추정] 실제 기관에서는 반환된 승인의 SALE 매입이 거절되거나, 가맹점에 전액이 정산될 수 있다(기관 규약은 미확인). 현재는 TEST·Simulator에서만 발현된다. LIVE 매입은 봉인 단계에서 막혀 있다(ACQUIRING-001 LIVE_BLOCKED).

### 5.4 append-only [사실]

| 표 | runtime 권한 | 판정 |
|---|---|---|
| payment_transaction, institution_observation, cancel_observation, payment_termination_evidence, incident_hold, outbox_event(두 열 갱신 예외 D-FFR-001·D-WH-009) | SELECT, INSERT | 일치 |
| incident_hold_release | **SELECT만** | 해제 쓰기 경로가 아직 없음(V10:14 주석) |
| FINANCIAL 사실·보류·해제·매입 파일·레코드·결과·전송 시도 표 | SELECT, INSERT(+ 상태 열만 열 단위 UPDATE) | 일치(`FINDB/runtime-privilege/financial/V3,V6,V8,V11,V15,V18,V20`) |
| payment, order_payment_control, payment_authorization, payment_cancellation, return_balance, return_reservation, daily_limit_usage | SELECT, INSERT, UPDATE | 상태 요약·통제 행(사실 아님). 사실은 별도 표다 → 일치 |

### 5.5 UNKNOWN의 끝 [사실]

- 이관 뒤에도 UNKNOWN을 유지한다(D-AUTH-009). 보존기간이 지나면 조회를 멈춘다(`AuthorizationRecoveryService.kt:212-215`, 주석: "RECON exact-match Recovery만 남는다").
- 그 Recovery는 없다. 따라서 그 주문은 영구히 새 결제가 막히고(AUTHORIZED와 같은 효과), 취소 UNKNOWN이면 반환 예약 RESERVED가 남아 그 Payment의 추가 취소도 막힌다(D-CANCEL-003).
- fail-closed라 데이터가 손상되지는 않는다. 다만 운영상 해소할 수 없다.

---

## 6. 정책 분류

| 분류 | 정책 | 근거 | 비고·대안(해결하지 않음) |
|---|---|---|---|
| 확정 현행 | 주문 key·활성 하나·새 시도 조건·전액 반환 후 재승인 금지 | 시도 §1-3, D-CPD-001~004, CLAUDE.md | 코드 일치(§4.1) |
| 확정 현행 | 종료 근거 표·교차 근거 불인정·출처 표 | D-CPD-002·003, AUTH-CONTRACT-001 §5.5 | 일치 |
| 확정 현행 | UNKNOWN 유지·이관 후에도 유지·예산은 버전 있는 Demo 설정 | D-AUTH-009 | 일치. 운영값은 LIVE Gate |
| 확정 현행 | 늦은 성공·상충 = hold + 자동 보상취소 없음 + 2인·MATCH 해제 | D-AUTH-002·008·011~013, D-CANCEL-006 | 감지는 코드, 해제는 문서만 |
| 확정 현행 | 확정 사실 1건 = 거래 1건 = 이벤트 1건 | D-AUTH-003 | 일치 |
| 확정 현행 | 취소 검사 순서·반환 잔액 식·진행 중 1건·한도 미복원 | D-CANCEL-001~006, D-AUTH-007 | 일치 |
| 확정 현행 | 결제 사실 전달 Outbox → 서명 HTTP → Inbox(같은 TX) | D-ACQ-001, D-FFR-001~004 | 일치 |
| 확정 현행 | 매입 대상·상계·ACK/결과 분리·UNKNOWN → 대사·재매입 2인 | D-ACQ-002~014 | 일치(대사 소비 없음) |
| 확정 현행 | 지급 모드 2종 + 선지급 기준 3종 + 매입 요청 = 외부기관 전송 완료 | 정산지급방식 "확정", 정산 정책 말미 A, CLAUDE.md | 코드 없음 |
| 확정 현행 | 정산 거래일 = 승인/취소/Refund 완료일(기관 거래일), 승인·취소 독립 Entry | 정산 §3, CLAUDE.md | FINANCIAL에 기관 거래일 보존(§4.2) |
| 확정 현행 | 계좌 Refund v1 범위 밖 | D-CANCEL-007 | baseline 02 표 미반영(DC-P-19) |
| 미결 | 영업일·D+N·cutoff·timezone | Q-CALENDAR | 매입은 달력일 Demo 임시(D-ACQ-003) |
| 미결 | 자동 보정 허용 범위·고위험 지급 차단 | Q-CORRECTION | RECON-CONTRACT-001 선행 |
| 미결 | 정산·대사 Job 식별 JobParameters | Q-BATCH-JOBPARAM | — |
| 미결 | 외화 정산 통화·환율 | Q-SETTLE-CURRENCY | 매입 KRW 한정은 Implementer 해석 I-5 |
| 미결 | 매입 격리 해소·REVIEW_REQUIRED 해소·전송 보류 해소의 금융 의미 | Q-ACQA-05·06·07·10 (PENDING) | ACQ-ADMIN-002 대기 |
| 미결 | 요율·세율·반올림·한도·노출 수치 | 정산 정책 말미 E | Demo 값만 |
| 미결 | CANCEL_RESULT_CONFLICT 해제 판정·FORCED 출구 | CANCEL-CONTRACT-001 m-1 | RECON-CONTRACT-001 |
| **충돌** | "매입 전송 완료" 선지급 기준의 존속 | 정산지급방식 "확정: 선지급 거래 기준 3종"·CLAUDE.md "승인 완료/매입 전송 완료/매입 확정 선지급 기준 유지" ↔ D-ACQ-005 사용자 원문 "승인시점과 매입결과 선택가능" + 적용문 "매입 전송 완료 기준의 존속 여부는 정산 작업에서 확인" | 대안: (a) 3종 유지(기존 확정 우선), (b) D-ACQ-005를 2종으로 축소한 새 결정으로 보고 CLAUDE.md·정산지급방식 갱신, (c) 지급 기준(시점) 축과 선지급 근거 축을 별개로 정의. SETTLEMENT-CONTRACT-001 착수 시 질문 |
| 코드≠문서 | "매입 전송 완료" 근거에 RENAMED(재확인 실패) 포함 | ACQUIRING-001 I-9(Implementer 해석) ↔ 정산지급방식 §매입 요청 "라이브러리 송신 호출 성공만으로 … 가정하지 않는다" | 대안: (a) RENAMED 유지(서버 rename 응답 = 게시 완료), (b) 정산 적격성은 VERIFIED_FINAL_PRESENT·ACK_HASH_MATCH만, (c) RENAMED는 전송 축 TRANSMITTED 유지 + 정산 적격성에서 별도 등급 |
| 코드≠문서 | RISK_INCIDENT 이벤트 effect_key 형식 | AUTH-CONTRACT-001 표 403행 `{paymentId}:{reason}` ↔ AUTH-001 373행·코드 `AuthorizationEvents.kt:80` `…:{occurrence}` | 코드·AUTH-001이 최신(D-AUTH-012 occurrence 도입). 문서 정정 대상 |
| 코드≠문서 | Attempt 수 | 시도 §1·DOMAIN-DEFINITION "여러 Attempt 허용" ↔ CA-13·코드 "초기 1개" | 축소 구현(허용 범위 안). 표현 정리 대상 |
| 과거·폐기 후보 | baseline 02 PAYMENT 원본의 "Refund 거래 상태", FINANCIAL 원본의 "AgentSettlement" | D-CANCEL-007(범위 밖), DOMAIN-DEFINITION §3.3 AgentSettlement "미결(A00)" | 삭제가 아니라 "v1 범위 밖/미결" 표시 후보 |
| 과거·폐기 후보 | baseline 01 실행 단위 9종(payment-relay, financial-worker/-api 별도 프로세스) | CURRENT-STATE §3(이미 통합 운영), D-PROC-005(현행 유지) | baseline 01을 현행 배치로 갱신할지는 사용자 판단 |
| 과거·폐기 후보 | DOMAIN-ASSESSMENT §6 "재구현 후보: Mapper·다중 Entity 축소" | D-PROC-004로 유지 결정 | 진단 문서 상태 표시 후보 |

---

## 7. 문제 목록

| ID | 우선 | 문제 | 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향(대안) |
|---|---|---|---|---|---|---|---|---|
| DC-P-01 | P0 | 기관이 확정한 금융 효과가 PAYMENT 거래·FINANCIAL 사실로 기록될 경로가 없다. ① 종료(FAILED/EXPIRED) 뒤 늦은 승인 ② 거절된 취소의 늦은 성공(FORCED, "거래 없음") ③ 이관된 UNKNOWN 중 실제 승인·취소된 건 | `PaymentAuthorization.kt:152-154`("main 호출자 없음"), `CancellationResolution.kt:54`, D-CANCEL-006·010~012, RECON-001 BACKLOG | PAYMENT 거래·FINANCIAL 투영·매입·(향후)정산 | 상 | [추정] 고객 청구·반환 사실과 가맹점 정산이 어긋난다. 현재는 hold로 주문만 막는다. TEST 한정 발현 | Simulator에서 늦은 성공 주입 → `payment_transaction`·`payment_fact_*` 행 수 확인(기존 시험 ResolutionSpec의 기대값과 비교) | (a) RECON-CONTRACT-001에서 exact-match Recovery로 거래 기록 + D-CANCEL-012 이벤트, (b) 그 전까지 LIVE 차단 조건으로 명시(LIVE Gate 체크리스트) |
| DC-P-02 | P0 | PAYMENT 사건(hold·RISK_INCIDENT·OPERATIONAL_RISK)이 FINANCIAL에 전달되지 않는다. 그래서 매입(향후 정산·지급)이 사건이 걸린 결제를 막지 못한다 | `JpaFinancialFactRelayStore.kt:144`(FINANCIAL_FACT만), `AcquisitionCandidateRule.kt:22-24`(FINANCIAL 보류만), FIN-FACT-RELAY-001 범위표 14행 | 매입 파일 구성, 향후 정산 적격성·선지급 | 중 | [추정] 반환이 일어난 승인의 SALE 매입, 이중 승인 건의 정상 정산 | 늦은 취소 성공 시나리오 뒤 다음 봉인 파일에 해당 SALE이 포함되는지 확인([미확인] 시험 존재 여부) | (a) RISK_INCIDENT를 FINANCIAL에 전달해 결제 보류(PAYMENT_HELD)로 투영, (b) FINANCIAL이 매입·정산 직전 PAYMENT 조회 API로 hold 확인(동기 의존 증가), (c) 정산에서만 차단(매입은 허용) — 사용자 결정 필요 |
| DC-P-03 | P1 | PAYMENT IncidentHold 해제 실행 경로가 없다(Use Case·관리자 명령·INSERT 권한 0). CANCEL_RESULT_CONFLICT는 domain이 해제를 거절한다 | `PAYDB/runtime-privilege/payment/V10…:14-15`, `IncidentHoldSettlement` 호출자 0, `OrderPaymentControl.kt:252-255` | 주문·취소 운영 | 중 | hold된 주문은 영구히 새 결제·가맹점 취소가 막힌다(fail-closed) | `grep IncidentHoldSettlement` main 호출자 수, 권한 표 | RECON-CONTRACT-001에서 해제 명령(ADMIN 2인 → PAYMENT) + MATCH 참조 생산자 정의 |
| DC-P-04 | P1 | 대사 이관된 UNKNOWN(승인·취소)을 끝낼 주체가 없다. 이관 이벤트는 Outbox에 쓰이지만 소비자가 없다 | `AuthorizationRecoveryService.kt:186-215`, `CancellationRecoveryService.kt:205-212`, AUTH-CONTRACT-001 402행 | 주문 슬롯·반환 예약·한도 점유 | 상 | 주문 영구 차단, 반환 예약 RESERVED 영구, 한도 점유 지속 | 이관 이벤트 행의 publish_status 분포 확인(Demo DB, [미확인]) | (a) RECON exact-match Recovery, (b) 관리자 수동 확인 명령(2인) — 금융 의미이므로 사용자 결정 |
| DC-P-05 | P1 | 선지급 기준 "매입 전송 완료"의 존속이 문서 간에 충돌한다 | 정산지급방식 "확정"·CLAUDE.md 핵심 규칙 ↔ D-ACQ-005 | SETTLEMENT-CONTRACT-001 전체 | 하(결정) / 중(반영) | 정산 적격성 모델이 달라진다 | 문서 대조(§6 충돌 행) | §6 대안 (a)(b)(c) 중 사용자 선택 |
| DC-P-06 | P1 | "전송 완료" 근거에 RENAMED(rename 응답만, 재확인 실패)가 포함된다. 금융 확정성 의미를 Implementer 해석(I-9)이 정했다 | `TransmissionOutcome.kt:32-40`, ACQUIRING-001 I-9(75행), 정산지급방식 §매입 요청 | 선지급 "매입 요청" 적격성(향후), 결과 마감 시각 기준(I-49) | 하 | 확인되지 않은 전송이 선지급 근거가 될 수 있다(정산 구현 시) | 근거 종류별 TRANSMITTED 비율, 정산 계약에서의 사용처 | §6 대안 (a)(b)(c). CLAUDE.md "기관 확정성 미결은 질문" 대상 |
| DC-P-07 | P1 | FINANCIAL 정합성 보류 5종과 REVIEW_REQUIRED는 해소 경로가 없다 → 해당 결제의 매입이 영구 대기한다 | `HoldReason.kt`, `HoldReleaseRule.kt`(ORIGINAL_UNKNOWN만), Q-ACQA-07 PENDING, ACQ-ADMIN-002 BACKLOG | 매입·향후 정산 | 중 | 미매입 → 기관 미입금 → 가맹점 미지급 | 보류 건수 지표, WaitReason 로그 | Q-ACQA-07 답 후 ACQ-ADMIN-002 B4 |
| DC-P-08 | P1 | 매입 결과 UNKNOWN 레코드와 취소 매입 RECONCILE 결정의 소비자(대사)가 없다. D-ACQ-014 때문에 UNKNOWN 재매입은 대사 전 거절이라 영구 미매입이 된다 | `ReacquisitionRule.kt:33`, `CancelRecordRule.kt:14,22`, `RecordResultRule.kt:63` | 매입·기관 대사 | 상 | 미매입·취소 미반영이 대사 없이 남는다 | UNKNOWN 레코드·RECONCILE 건수 | RECON-001 범위에 매입 대사 포함 여부 결정 |
| DC-P-09 | P2 | 수수료·VAT "원승인 Snapshot"이 버전 문자열뿐이다. 버전 → 요율 내용을 FINANCIAL이 얻을 채널(MASTER→FINANCIAL)이 없고, MASTER 정책 내용도 UNDEFINED/DEMO다 | `V2__payment_fact_inbox.sql:81,125`, `OutboxPayloadJson.kt:79,124`, DOMAIN-ASSESSMENT §3.2, baseline 03 "설정 버전 없으면 대기" | 정산 아키텍처 | 상 | 정산 착수 시 새 서비스 간 계약 필요 | 설계 검토 | (a) MASTER→FINANCIAL 정책 Snapshot Outbox(권리 Snapshot 패턴 재사용), (b) PAYMENT 사실에 요율 사본 포함(PAYMENT가 정책 내용 의존), (c) FINANCIAL이 MASTER API를 버전으로 조회 |
| DC-P-10 | P2 | Settlement·Payout·Reconciliation 모듈·Aggregate·표 0개. 문서(baseline 02 예시, DOMAIN-DEFINITION §4.2)는 "정산 후보 생성"을 소비 TX의 일부로 서술한다 | financial-batch 모듈 2개, `PaymentFactInboxService.kt:72-104` | FINANCIAL 후반부 전체 | 상 | 돈 흐름 후반부 부재(기존 진단 CURRENT-STATE §5와 같음) | 모듈 목록 | SETTLEMENT-CONTRACT-001부터 착수. 문서에 "미구현" 표시 |
| DC-P-11 | P2 | RISK_INCIDENT·OPERATIONAL_RISK Outbox 행이 소비자 없이 PENDING으로 쌓인다. 보존·정리 정책이 없고, baseline 03은 소비 확인 없는 삭제를 금지한다 | `AuthorizationEvents.kt:79,97`, `CancellationEvents.kt:77,95`, `JpaFinancialFactRelayStore.kt:144` | PAYMENT DB 운영 | 하 | 무한 누적. 백로그 gauge가 "미전달"을 오해할 소지 [추정] | 범주별 PENDING 행 수 | DC-P-02 해결과 함께 소비자 지정, 또는 "소비자 없음" 상태 명시 |
| DC-P-12 | P2 | 조회 전용 값(HomeKpiFigures·HomeKpiWindow)이 `payment.domain`에 있다(Aggregate 아님) | `PAY/domain/HomeKpiFigures.kt:1-15` | domain 패키지 경계 | 하 | domain 의미 희석(경미) | ArchUnit 규칙 검토 | query 모델 패키지로 이동 후보(가역 — 관례로 결정 가능) |
| DC-P-13 | P3 | 관리자 화면에 hold 해제 요청, UNKNOWN 이관 목록 처리, REVIEW_REQUIRED·격리 해소 화면이 없다(보류 상태 표시만) | `apps/internal-admin/frontend/src/screens/TransactionsScreen.tsx`·`PaymentOpsScreen.tsx`·`AcquisitionsScreen.tsx`("보류" 문구 표시만), ACQ-ADMIN-002 BACKLOG | 운영 | 중 | 운영자가 사건을 보고도 처리할 수 없다 | 화면 점검 | RECON·ACQ-ADMIN-002 계약 후 화면(사용자 메모: 화면 착수 전 알림) |
| DC-P-14 | P4 | AUTH-CONTRACT-001 이벤트 표의 RISK_INCIDENT effect_key에 occurrence가 없다(코드·AUTH-001에는 있음) | AUTH-CONTRACT-001 403행, AUTH-001 373행, `AuthorizationEvents.kt:80` | 문서 | 하 | 계약 독자 혼동 | 문서 대조 | 계약 문서 정정 또는 "AUTH-001이 대체" 표시 |
| DC-P-15 | P4 | Attempt 수 서술이 섞여 있다(DOMAIN-DEFINITION §3.2 "여러 Attempt 가능"과 같은 표 "dispatch Attempt 1개(초기)") | DOMAIN-DEFINITION §3.2, `PaymentAuthorization.kt:32` | 문서 | 하 | 경미 | 문서 대조 | "허용 범위 = 여러 개, 현재 구현 = 1개" 두 칸으로 분리 |
| DC-P-16 | P4 | "Hold"가 4계통(PAYMENT IncidentHold / FINANCIAL PaymentFactHold / 매입 WITHHELD·QUARANTINED / 정산 SettlementHold·PayoutHold)이고, admin "Reconciliation"은 금융 대사와 동명이의다 | §3 주의, 각 enum | 용어 | 하 | 운영·문서 혼동 | 용어사전 대조 | 용어사전에 계통별 정식 명칭 + admin 쪽 "결정 대조"로 개명 후보 |
| DC-P-17 | P4 | DOMAIN-DEFINITION §3.3 용어가 코드와 다르다: "AcquisitionSubmission"(코드 AcquisitionFile), "원 SALE 불명확이면 취소 매입 HOLD"(코드 RECONCILE) | DOMAIN-DEFINITION §3.3, `AcquisitionFile.kt:14`, `CancelRecordRule.kt:23` | 문서 | 하 | 경미 | 문서 대조 | 용어사전 갱신 |
| DC-P-18 | P4 | 기존 진단 CURRENT-STATE §5 수치(tasks 70 / DONE 51)가 현재 tasks.json(41 / DONE 15)과 다르다 | `harness/tasks.json`(작업 트리) | 문서 | 하 | 상태 판단 혼동 | 집계 | 진단 문서에 기준 시점 명시 유지(이미 기준 커밋 표기됨) |
| DC-P-19 | P4 | baseline 02 소유 표에 v1 범위 밖(Refund)·미결(AgentSettlement) 표시가 없다 | baseline 02 표 16-17행, D-CANCEL-007 | 문서 | 하 | 범위 오해 | 문서 대조 | 주석 표시(원문 보존 원칙상 별도 문서에 표시하는 방안도 있음) |

우선순위별: **P0 2 / P1 6 / P2 4 / P3 1 / P4 6 = 19**

---

## 8. 사용자 결정 필요 항목 (Coordinator가 질문 등록·제시. 이 문서는 결정하지 않음)

| 후보 ID | 질문 | 권장안(참고) | 막히는 작업 |
|---|---|---|---|
| Q-DC-P-01 | PAYMENT 사건 hold를 FINANCIAL 매입·정산에 어떻게 반영할까(DC-P-02) | (a) RISK_INCIDENT 전달 → FINANCIAL 결제 보류로 투영 | 매입 파일 구성 수정, SETTLEMENT-CONTRACT-001 |
| Q-DC-P-02 | 선지급 "매입 전송 완료" 기준을 유지할까, D-ACQ-005대로 2종으로 줄일까(DC-P-05) | (a) 3종 유지 — 기존 확정 문서와 CLAUDE.md가 우선 | SETTLEMENT-CONTRACT-001 |
| Q-DC-P-03 | "전송 완료" 근거에 RENAMED(재확인 실패)를 인정할까(DC-P-06) | (c) 전송 축은 유지하고 정산 적격성은 재확인 근거만 | SETTLEMENT-CONTRACT-001, ACQUIRING 문서 I-9 |
| Q-DC-P-04 | RECON 이전 LIVE 전환 금지를 명시 조건으로 둘까(DC-P-01·03·04) | 예 — LIVE Gate 체크리스트 항목화 | LIVE 준비 |
| Q-DC-P-05 | 수수료 Snapshot 내용을 어떤 채널로 FINANCIAL에 줄까(DC-P-09) | (a) MASTER→FINANCIAL 정책 Snapshot Outbox | SETTLEMENT-001 |
| (기존) | Q-ACQA-05·06·07·10(격리·REVIEW_REQUIRED·전송 보류 해소 의미), Q-CORRECTION, Q-CALENDAR, Q-SETTLE-CURRENCY, Q-BATCH-JOBPARAM | 각 질문 기록 참조 | ACQ-ADMIN-002, RECON·SETTLEMENT |

---

## 9. 확인하지 못한 것 [미확인]

- 시험 실행 결과(게이트 미실행). DC-P-02 시나리오(늦은 취소 성공 뒤 SALE 매입 포함)를 직접 검증하는 시험이 있는지 찾지 않았다.
- Demo DB의 실제 행 분포(이관 이벤트 PENDING 수, 보류 건수).
- 실제 VAN·매입사 규약(늦은 성공·반환 승인 SALE 매입 처리) — 영향 서술은 추정이다.
- CP-API-001·CP-QUERY-001·TXN-OPS-001·ACQ-VIEW-001 본문 전체(필요한 절만 grep으로 대조).
- `docs/rules/PAY.md`·`CAN.md`·`FIN.md`는 아직 없어 대조하지 않았다.
