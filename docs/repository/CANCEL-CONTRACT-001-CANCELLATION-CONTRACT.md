# CANCEL-CONTRACT-001 전액/부분취소 계약 명세 (rev 1.3 — 사용자 답변 D-CANCEL-001~012 + 명세·중간 코드 review 반영 + 2단계 T1~T7)

> **rev 1.1(2026-10-17)**: §13 질문 8개는 모두 답변됐다(**D-CANCEL-001~008**, `docs/DECISIONS.md`) — 모두 권장안 채택, Q-CANCEL-08은 "지금 확정"(SETTLEMENT로 미루지 않음).
> Coordinator 판정: N-2(UNKNOWN 승인 망취소 없음)는 도출로 채택, 나머지 N 항목은 기술대로. 구현 작업은 새 작업 **CANCEL-001**(AUTH-001 뒤 — API·영속·Simulator 취소·UNKNOWN 회복),
> AUTH-001 범위는 그대로. 선지급과 반환 예약은 SETTLEMENT-CONTRACT-001 AC에 추가됨. 아래 본문의 "미결 Q-CANCEL-xx"·"권장" 표현은 해당 D-CANCEL 결정이 우선한다(§13.0 대응표).
> **rev 1.2(2026-10-17)**: 명세 review(SPEC_PASS_WITH_FIXES) H-1·H-2·m-1~m-11·nit 반영 — 대응표 §0. 2단계 T1~T4 실행 기록은 §17(구현자 보고, Verifier 판정 아님).
> **rev 1.3(2026-10-17)**: U-1 → **D-CANCEL-009**(§5.1), U-2 → **D-CANCEL-010**(§4.10·§14.1) 반영. T1~T4 중간 코드 review(REVIEW_PASS_WITH_FIXES) m-A~m-D·n-1~n-5·편차 3·4 반영,
> T5~T7 실행 기록은 §17.5~. U-3 → **D-CANCEL-011**(늦게 확정된 P1의 모양·취소 관문, §4.8·§4.10)·**D-CANCEL-012**(RECON 기록 TX의 PaymentAuthorized — AUTH §8.1·D-AUTH-003의 유일한 예외, §4.10·§14.1) 반영.

작성 2026-10-17 KST. 1단계(코드 변경 없음)로 작성, rev 1.2에서 2단계 T1~T4 domain 코드를 추가했다(§17). 기준 commit `50db007`(TZ-FIX-001). 작업 트리에는 Coordinator 소유 상태 파일
(`harness/tasks.json`·`harness/progress.json`) 미커밋 변경이 있으며 이 문서는 읽기만 했다. 이 문서는 새 업무 정책을 채택하지 않는다 —
§13 질문 후보는 답변 전 해당 운영 동작을 구현하지 않는다. 2단계 TDD(순수 domain 타입·불변식·판정표 시험)는 Coordinator가 질문을 처리한 뒤
시작한다. 취소 API·기관 전송 Adapter·DB migration·가상 VAN 취소 operation은 후속 구현 작업이고 정산 계산은 SETTLEMENT-*이다(tasks.json AC).

표시(AUTH-CONTRACT-001과 같다): **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 규칙
(새 정책 아님 — 의심되면 Coordinator가 질문으로 올린다), **구현 결정(CC-xx)** = 금융 의미가 없는 가역적 선택(§12), **미결(Q-CANCEL-xx)** = §13
질문 후보, **Demo** = Simulator·시험 전용 값(운영값 아님), **범위 관찰** = 작업 경계에 관한 Coordinator 확인 사항(§14).

입력 문서(sha256 앞 8자…끝 4자, 2026-10-17 읽은 시점):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 결정 | `docs/DECISIONS.md`(D-AUTH-001~013, D-CPD-*, D-CP-*, D-CPC-*, D-MD-006, D-MS2-*, D-CPA-001, D-ADMIN-*, **D-CANCEL-001~012**) | 확정 | `50ecc26b…7776` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | `a053e150…8c1f` |
| 색인 | `docs/BASELINE-INDEX.md` | 기준 | `f2461472…6251` |
| 작업 | `harness/tasks.json` CANCEL-CONTRACT-001 AC 7개, CANCEL-001(BACKLOG) | Coordinator 소유(미커밋) | `1e90eeac…6ca3` |
| 질문 | `harness/questions.json`(Q-CANCEL-01~08 답변 → D-CANCEL-001~008, Q-CALENDAR·Q-CORRECTION PROPOSED) | Coordinator 소유 | `62080384…000b` |
| 정산 | `docs/baseline/PayCore-Settlement-Control-Policy-v0.1.md` §1·§3·§18·§29·말미 B·D | 확정 | `68ca892b…bd69` |
| 초기 | `docs/baseline/PayCore-초기-확정사항.md` 38·53·58·85·87·96행 | 확정 | `818cc7a7…45c5` |
| 시도 | `docs/baseline/PayCore-결제시도-규칙-권장안.md` §1~§6 | 확정 | `9151261e…f5c1` |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` §4.3·§5.1~§5.5 | 확정 | `a8b02b77…7ae5` |
| 구조 | `docs/baseline/PayCore-v0.1-도메인구조-Seed-MID-Site-확정.md` 56·546·549행 | 확정 | `9d237eb9…81fb` |
| 범위 | `docs/baseline/PayCore-최초구현범위-권장안.md` §1(D-AUTH-001로 채택) | §1~§3 확정 | `3cf08d32…11be` |
| 착수 | `docs/baseline/PAYCORE-VIBE-CODING-IMPLEMENTATION-READINESS-GUIDE-v0.2.md` §4 | 구현 검토용 초안 | `a6d4f312…e327` |
| 대안 | `docs/baseline/PayCore-금융통제-관계-자동대사-대안.md` §1·§4·§6 | 대안 이력(정산이 우선) | `18f353eb…4426` |
| 검토 | `docs/baseline/PayCore-실서비스-착수검토-2026-10-03.md` "취소·환불" | 역사 자료 | `a263dbf1…8c9c` |
| A02·A03·A06·A08 | `docs/baseline/architecture/02·03·06·08` | 아키텍처 자료 | `33464065…1a1e` · `45a25538…9950` · `f0f57739…54c1` · `086f9300…9152` |
| 정의 | `docs/repository/DOMAIN-DEFINITION.md`(§3.2 Cancellation·Refund 행, G-5) | 색인 | `ce93923b…f03c` |
| 승인 | `docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md`(§3·§5·§7·§8.2 307행·§18-6 563행) | 확정 계약 + 2단계 반영 | `511dde80…973d` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md` §9·§12·§14 | 관례 | `50cec03e…3634` |
| 코드 | `payment/domain/` `Payment.kt` `ec499619…1f35`, `PaymentStatus.kt` `dc4a5db7…0658`, `PaymentTransaction.kt` `f4d1e6f2…3bfb`, `PaymentAuthorization.kt` `ac4f0d9c…8019`, `InstitutionObservation.kt` `63df5849…bb45`, `OrderPaymentControl.kt` `375462d5…267c`, `DailyLimitUsage.kt` `893cfc9e…7798`, `Money.kt` `63627c6e…f66c`, `AuthorizationAttempt.kt` `ce663050…d138`, `IncidentHold.kt` `d6309e02…edd4`, `IncidentHoldRelease.kt` `9c28db21…347d` | `50db007` | — |

(전체 hash는 Verifier가 같은 파일에 `sha256sum`으로 재현한다. 위 표는 식별용 축약이다. "코드" 행은 1단계 조사 기준(`50db007`) hash이며 2단계 변경 파일은 §17.1.)

---

## 0. rev 1.2 명세 review 반영 대응표

| 지적 | 반영 | 위치 |
|---|---|---|
| **H-1** FORCED 초과가 CHECK·reconstitute를 깸 | FORCED는 별도 합(`forcedMinor`). CHECK·domain은 `completed + reserved ≤ authorized`만 강제, `cancellable = max(0, authorized − completed − reserved − forced)`, `excess = max(0, completed + reserved + forced − authorized)`(FORCED에서만). reconstitute는 FORCED발 초과만 허용. CB-07·`paymentWithForcedReturnAfterLateCancelSuccess()` | §4.3, §4.1, §9.2, §10 INV-C1·C8, §11.3 |
| **H-2** 운영자 보정 취소 영구 업무 키 | **CC-12: UNIQUE(approvalId)** — ADMIN 2인 승인 요청 ID(`OperatorApprovalId`)를 `PaymentCancellation`에 저장. 같은 승인 = 기존 operation, 같은 승인·다른 내용 = 충돌 | §4.5, §4.11, §9.2, §12, CX-16 |
| m-1 FORCED 출구·취소 hold 해제 | 새 `IncidentReason.CANCEL_RESULT_CONFLICT`, 그 hold 해제는 RECON-CONTRACT-001 전까지 거절(fail-closed). INV-C2가 미래 출구 자리를 남김. §14 RECON 인계 | §4.6, §10, §14-5 |
| m-2 이중 승인 보정 대상 | 대상·P1 거래 기록 시점은 RECON-CONTRACT-001 미결(U-2 질문 중)로 다시 씀 | §4.10 |
| m-3 소유 서비스 재검사 | 운영자 입력 = 요청자·승인자·approvalId·사유·목적/근거·승인 대상 fingerprint(`OperatorCancelTarget` = paymentId·금액·통화·ReturnBalance revision). CancelStart가 같은 사람·사유 없음·근거 없음·stale 대상을 거절 | §4.4-7, §4.11, CX-17 |
| m-4 운영자 목적 | `OperatorCancelPurpose` = {IncidentOrReconCorrection(caseRef), MerchantTerminatedObligation(terminationRef)} | §4.11, CX-18 |
| m-5 행·변이 RED | D-CANCEL-002 형식·충돌·FAILED 뒤 같은 ID, D-CANCEL-004 오래된 승인, D-CANCEL-006 순서, CB-01 순번 1/2·누적 3,000/10,000. 변이 RED M1~M6(§17) — CR-12 두 번째 거래 변이는 T5 | §11, §17 |
| m-6 CANCEL-001 제약 | UNIQUE(payment_id, merchant_cancel_id), Payment당 진행 중 1건(종료 시 NULL인 생성 컬럼 + UNIQUE), CANCEL 거래 UNIQUE(cancellation_id), UNIQUE(approval_id), 이벤트 키 `PaymentCancelled:{cancellationId}` 검토 | §9.2, §14-1 |
| m-7 잠금 | TX2·회복은 항상 control부터(Resolution이 hold를 열 수 있음) | §9.1 |
| m-8 | 매입 뒤 Capability fail-closed 주장 삭제 → ACQUIRING-001·기관 거절 | §4.9 |
| m-9 | AC 표를 tasks.json 현재 AC 7개로 | §1 |
| m-10 | PaymentCancelled에 `authorizedAmount`·`returnStatusAfter` | §9.3 |
| m-11 | CR-09·10·12는 hold 열림을 확인(T5) | §11.2 |
| nit | 남은 Q-0x 참조 제거, T4 = 검사 1~7 전부, 입력 hash 갱신, 기관 취소 거래일 필수, step 3은 이 Payment의 **모든** 활성 hold(정산 말미 D), 업무 키 UNIQUE는 영구(30일 보존과 무관), CB-08(정책 변경 사이 부분취소), §4.10 확정 표기 | 각 절 |

---

## 1. AC 대응표(tasks.json CANCEL-CONTRACT-001 → 명세 절 → 계획 시험)

| # | AC(tasks.json 요약) | 절 | 시험(2단계 = 순수 domain) |
|---|---|---|---|
| 1 | (D-AUTH-003) 취소·부분취소는 각각 새 PaymentTransaction, 원승인 Snapshot 수수료/VAT, 취소 UNKNOWN 회복, 일 한도 미복원(D-AUTH-007) | §4.6, §5.1, §4.7, §5.4 | `PaymentTransactionSpec`, `CancelStartSpec` CX-10·11, (T5) `CancellationResolutionSpec` |
| 2 | 1단계 명세(조사·흐름·불변식·UNKNOWN 회복·Snapshot·정산일·한도·질문 후보) | 이 문서, §13 | — |
| 3 | 2단계: 확정 규칙을 domain 타입·불변식·판정표 시험으로(TDD), Refund 범위 기록 | §6, §10, §11, §17 | §11 CX·CR·CB 행 |
| 4 | 범위 밖: 취소 API·기관 Adapter(CANCEL-001), 정산 계산(SETTLEMENT-*), 실제 기관 | §2, §14 | — |
| 5 | (D-CANCEL-001~007) hold 중 취소·merchantCancelId 업무 키·진행 중 1건·PG 기한 없음·운영자 보정 2인·상충/늦은 성공 사고·계좌 환불 범위 밖을 domain·판정표로 | §4.4~§4.11, §6 | `CancelStartSpec`·`PaymentCancellationSpec`·`ReturnBalanceSpec`, (T5) Resolution |
| 6 | (D-CANCEL-008) 누적 비례 배분 규칙 명세 기록(계산은 SETTLEMENT), PaymentCancelled에 원승인 정책 버전·원거래 ID | §5.1, §9.3 | CX-11, CB-08 |
| 7 | VERIFY-GATE PASS(새 fingerprint)·테스트 수 1485 이상·SELFTEST 81 이상, 빌드 파일 변경 시 DEPS/CVE | §11.5, §17 | Verifier(2단계는 빌드 파일 불변) |

---

## 2. 범위와 범위 밖

### 2.1 범위(이 계약)

- 카드 승인 거래의 **승인취소(Cancellation)**: 전액취소와 부분취소(여러 번), 같은 Payment에 대한 동시 취소 요청, 취소 요청 멱등, 취소 operation
  상태(UNKNOWN 포함)와 원 취소 Attempt 조회·대사에 의한 회복, 반환 가능 금액 불변식, incident hold와의 관계, 취소 가능한 Payment·주문 상태.
- 금액 규칙: 통화, minor unit 정수, 정산 거래일(취소 완료일)에 필요한 사실 보존, 원승인 수수료/VAT Snapshot 참조 전달, 일 한도 미복원.
- 2단계 domain 타입·불변식·판정표 시험 계획(§10·§11).

### 2.2 범위 밖(후속 작업)

| 항목 | 소유 작업 | 이 계약이 정하는 것 |
|---|---|---|
| 취소 API(`POST /v1/payments/{paymentId}/cancel` 등)·오류 매핑·멱등 기록 저장 | **CANCEL-001**(Coordinator 등록, AUTH-001 뒤) | 의미·거절 분류·멱등 범위(§4.5, §8) |
| 가상 VAN 취소 operation·TCP 전문·Adapter | 같은 후속 작업 + institution-simulator | Port 결과 분류(§7) |
| DB migration·JPA Store·Use Case·실DB 경합 시험 | 같은 후속 작업 | 잠금 순서·제약 권장(§9) |
| 수수료/VAT 반환 계산·반올림·정산 Entry·영업일 | SETTLEMENT-CONTRACT-001·SETTLEMENT-001 | PAYMENT가 넘길 참조(§5.1) |
| 매입 전/후 취소의 매입 파일 처리 | ACQUIRING-001 | PAYMENT는 매입 상태로 분기하지 않음(§4.9) |
| 늦은 승인 거래 기록(hold 유지 중 — D-CANCEL-010·011)·이중 승인 보정 실행·DOUBLE 해제 | RECON-CONTRACT-001 | 보정 취소가 이 계약의 취소 경로를 쓴다(§4.10) |
| 운영자 화면·2인 승인 실행 | ADMIN-BOOT-001(D-ADMIN-*) | 개시자·행위자 기록(§4.11, D-CANCEL-005) |
| 계좌환불(Refund) | **v1 범위 밖(D-CANCEL-007)** | §6 |
| 실제 기관 연동·운영 키·REAL endpoint | 개발 요청 밖(CLAUDE.md) | — |

---

## 3. 현황 조사

### 3.1 기준 문서가 이미 정한 것(확정)

| # | 규칙 | 출처 |
|---|---|---|
| K-1 | 기관이 확정한 금융 효과 1건 = PaymentTransaction 1건(append-only). 취소·부분취소는 **각각 새 거래**. 거절·무효과·UNKNOWN·중복 수신은 거래가 아니라 관찰 | D-AUTH-003 |
| K-2 | 최초 범위의 취소 = "원 거래의 전액/부분취소, **기관 지원 범위 내**" | 범위 §1 표(D-AUTH-001 채택) |
| K-3 | 취소는 원 거래·반환 권한·**원 경로** 조건으로 처리하고 신규 승인 권리와 분리한다. 계약 철회/MID 신규 결제 중지에도 기존 카드 취소는 처리. 폐기된 API 키를 허용한다는 뜻은 아니며 유효한 별도 Credential/운영 권한이 필요 | 허용 §4.3, D-MD-006("차단은 신규 승인만 막고 조회·취소·정산은 허용 문서 표대로"), 초기 53행 |
| K-4 | 승인/취소/조회는 PAYMENT 소유, 매입·입금·지급·대사는 FINANCIAL 소유. 카드 승인·취소·결과 조회는 TCP 소켓(가상 VAN) | 초기 58·59행, 허용 §5.1 |
| K-5 | Domain은 authorize/cancel/inquire 같은 업무 의도만 다루고 Socket·기관 DTO는 Adapter에 한정 | 허용 §5.1, A04, INV-08 |
| K-6 | 기관 요청 namespace = 기관 + 환경 + 기관 계약계정 + operation + 요청 ID. 같은 ID·같은 payload는 기존 결과, 다른 payload는 충돌. NOT_FOUND는 기관 확정성 규칙 없이는 실패가 아님. 원 승인 참조·금액·통화·기관 거래일/업무일 보존, 승인번호 전역 UNIQUE 가정 금지 | 허용 §5.2 |
| K-7 | 수수료 정책 적용일 = **원 승인일**. 취소 시 현재 정책을 다시 적용하지 않고 원승인 FeeCalculation·Policy Snapshot(FeeType·계산 방식·요율/정액·반올림·VAT 계산·세율·VAT 반올림)으로 reversal | 정산 §1·§29, 초기 85행, 구조 56·546행 |
| K-8 | 정산 거래일 = 금융사실이 실제 발생한 날짜: 승인일 / **취소일** / Refund 완료일. 승인과 취소는 각각 독립 SettlementEntry. 취소 수수료의 원승인 참조와 취소 정산의 취소 거래일은 구분 | 정산 §3·§29, 초기 87·96행, 구조 549행 |
| K-9 | Refund 완료일은 확정 외부 반환 사실의 **업무시각** 기반이며 PAYMENT에 늦게 기록된 수신시각과 구분. 적용 시간대/기관 거래일 규약은 별도 상세화 | 정산 말미 B |
| K-10 | 정률/정액 반환 비율·**부분취소 마지막 잔액**·별도 취소비 등의 구체 정책값은 **별도 확정**. 반올림 방식은 버전 있는 기준정보에서 선택(실제 값 미고정) | 정산 말미 B·E, 초기 85행 |
| K-11 | 일 한도: 승인 후 취소·환불은 **미복원**(일 승인 총액 기준) | D-AUTH-007, `DailyLimitUsage` KDoc |
| K-12 | 승인 성공 주문은 전액 취소/환불 후에도 다시 승인하지 않는다. 재구매는 새 주문번호. 반환 상태 FULL을 새 승인 허용 조건으로 쓰지 않는다 | 초기 38행, 시도 §2·§3·§6-7, CP계약 CP-05 |
| K-13 | 확정 금융 사실 append-only, 외부 호출은 DB TX 밖, 업무 효과·요약·요청 결과·Outbox는 소유 DB 같은 TX | CLAUDE.md, A02, INV-04, 원칙 §1 |
| K-14 | timeout·조회 NOT_FOUND·lease 만료만으로 금융효과가 없다고 단정하지 않는다. UNKNOWN은 원 Attempt 결과 조회/대사 우선. 복구 조회와 신규 금융 요청 분리 | CLAUDE.md, 시도 §4, INV-11 |
| K-15 | 부분취소 누적금액에는 낙관적 버전 또는 명시적 잠금. 조회 후 무보호 쓰기 금지 | A02 "제약과 정책" |
| K-16 | 반환 금액 불변식 예: `cancelledAmount + refundedAmount + reservedReturnAmount ≤ authorizedAmount`. 정상 Fixture는 이를 만족해야 하며 관계 금액을 독립 random으로 만들지 않음. Fixture 이름 예 `completedCancellation()`·`paymentWithReservedReturn()` | A08 Test Fixture Policy |
| K-17 | Payment.lifecycle과 ReturnStatus를 구분. PaymentTransaction은 append-only 별도 record이며 무제한 Aggregate 컬렉션으로 적재하지 않는다. 반환 완료액 + 예약액 ≤ 승인액을 Payment 공통 경합 제어로 검사 | 착수 §4(초안 — 업무 규칙은 K-15·K-16과 같은 방향) |
| K-18 | 원 SALE 결과가 불명확하면 취소 매입은 HOLD. 선행 승인보다 취소가 먼저 도착하면 보류 후 누락 복구 | 허용 §5.4-1, A03 "순서·버전·오류" |
| K-19 | 자동 보상취소 없음. 늦은 성공·상충 결과는 incident hold + 대사 Case(HIGH/CRITICAL) 수동 검토, 해제는 2인 승인 + 재대사 MATCH. 이중 승인 해제에는 **보정(취소) 참조** 필수 | D-AUTH-002·011, AUTH §8.2(307행 "취소는 Cancellation 계약(G-5)") |
| K-20 | 다른 MID의 거래를 ID만으로 조회·취소하는 것 금지 | A05, A08 "다른 MID의 조회·취소·정산 접근" |
| K-21 | 금액은 통화 minor unit 정수, Float/Double 금지. 허용 통화는 MID·기관 계약 기준정보, 초기 구현·검증 KRW·USD. 외화 정산 통화·환율은 미결(Q-SETTLE-CURRENCY) | D-CP-002, D-CPC-005, A02 |
| K-22 | API 멱등 범위 = (environment, 인증된 MID, operation, key) + 정규화 payload fingerprint. 같은 키·다른 내용 충돌, 재조회에도 현재 권한 검사. 보존 30일, 409 업무 거절도 저장·재생 | 시도 §5, D-CP-001, D-CPC-002·004·007 |
| K-23 | 시간·재시도 수치(timeout·조회 예산·대사 이관)는 버전 있는 설정, 코드 운영 기본값 없음, Demo만 | D-AUTH-009 |
| K-24 | 가맹점 API key 권한 범위 v1 = 공개 가맹점 API 전체((MID, 환경) 단위) | D-CPA-001 |
| K-25 | 2인 승인 대상(요청자≠승인자, APPROVER): 재지급·고액 조정·Critical 해결·강제 Hold 해제·선지급 예외 + 열거 목록. 취소 자체는 목록에 없음 | D-ADMIN-003 |
| K-26 | 대사 위험: "Unexpected refund" = HIGH, "Unexplained external success" = CRITICAL | 정산 §18 |

참고(역사 자료 — 채택 근거 아님, 위 확정 규칙과 같은 방향): 검토 문서 "취소·환불" — "원 결제수단 취소는 PAYMENT의 공통 반환 예약 후 원 승인
경로를 사용한다. 성공 시 예약 소비·요약·금융 사실을 같은 로컬 TX로 반영한다. 확정 무효과 실패만 예약을 해제하고 UNKNOWN은 유지한다."
대안 §1 표 "VAT 반올림 방식/단위, 반환 비율·고정비 반환·마지막 잔액"은 미결 목록이었다(K-10이 현행).

### 3.2 기존 domain 코드가 지원하는 것과 없는 것(`50db007`)

| 영역 | 있는 것 | 없는 것(이 계약이 정의) |
|---|---|---|
| `PaymentStatus` | REQUESTED·AUTHORIZING·UNKNOWN·AUTHORIZED·FAILED·EXPIRED. KDoc: "취소/환불 상태는 별도 요약이며 이 생명주기를 바꾸지 않는다(AUTHORIZED는 반환 후에도 성공 이력)" | 반환 요약(ReturnStatus) |
| `Payment` | 승인 상태 전이·종료 근거. **금액을 갖지 않는다**(`intent` fingerprint만) | — (금액 원본은 Authorization의 거래) |
| `PaymentAuthorization` | Payment 1:1, `intent`(Money), `admission.policyAssignmentVersion`(`pa1:fee=…;stl=…` — 원승인 수수료·정산 정책 Assignment 버전), `limitPolicyVersion`, Attempt(원 경로 `ProviderRequestRef`: 기관·환경·기관 계정), AUTHORIZED ⇔ `transaction` 하나. `reconstitute` 없음(AUTH-001) | 취소 operation·취소 Attempt·취소 관찰 |
| `PaymentTransaction` | `PaymentTransactionType { AUTHORIZATION }`, `terms: ApprovedTerms`(Money·Plan·cardType·승인번호·ProviderTransactionId·기관 거래일), `sourceObservation`, `confirmedAt`. KDoc: "취소·부분취소는 CANCEL-CONTRACT-001에서 새 타입" | CANCEL 타입, 원거래 참조, 취소 조건 값 |
| `InstitutionObservation`·`AttemptState`·`ObservationSource`·`InstitutionOutcome` | 승인용 관찰·dispatch 상태 의미(PREPARED/DISPATCHING/ABANDONED_BEFORE_DISPATCH/OUTCOME_UNKNOWN/RESOLVED), `sameInstitutionContent` | 취소용 관찰(승인 조건 `ApprovedTerms`가 Plan·cardType을 필수로 가져 그대로 쓸 수 없다) |
| `OrderPaymentControl` | `authorizedPaymentId`를 지우지 않음 → 전액 취소 뒤에도 `decide()`가 `AUTHORIZED_NEW_ORDER_REQUIRED`(K-12 **이미 지원**). incident hold·`IncidentHoldRelease`(DOUBLE_APPROVAL_CORRECTED는 `correctionRef` 필수) | 취소와 hold의 관계(Q-CANCEL-01), 취소 결과 사건 reason(Q-CANCEL-06) |
| `DailyLimitUsage` | CONSUMED를 되돌리는 연산 없음, KDoc "취소·환불은 일 한도를 복원하지 않는다"(K-11 **이미 지원**) | — (취소는 이 Aggregate를 건드리지 않는다) |
| `Money` | minor unit 정수 ≥ 1, 0·음수는 "해당 작업에서 별도 타입" | 0을 허용하는 잔액 타입(반환 가능 금액 0 = 전액 취소 완료) |
| 영속(V1·V3·V5·V7·V8_1) | payment·control·incident_hold·idempotency·outbox·인증 세션 표 | `payment_authorization`·거래·취소 표 전부(AUTH-001도 아직 없음) |
| application | 결제창 `CheckoutCancellationService`는 **고객의 결제창 이탈**(세션 종료)이며 카드 승인취소가 아니다 | 취소 Use Case 전부 |

결론: 취소에 필요한 **금액 원본(승인 거래)·원 경로·원승인 정책 버전**은 이미 `PaymentAuthorization`에 고정되어 있고, **전액 취소 뒤 재승인 금지**와
**일 한도 미복원**은 기존 코드가 이미 만족한다. 없는 것은 취소 operation·반환 가능 금액 통제·CANCEL 거래 표현·취소 관찰과 판정표다.

---

## 4. 취소 모델

### 4.1 타입 개요(2단계 순수 Kotlin, `com.paycore.payment.payment.domain` — 같은 모듈·같은 TX, CA-01과 같은 근거)

| 타입(가칭) | 종류 | 의미·불변식 | 근거 |
|---|---|---|---|
| `PaymentCancellation` | Aggregate Root(PAYMENT DB) | 취소 요청 1건 = 논리 취소 operation 1건. 대상 Payment·Authorization·**원 AUTHORIZATION 거래 ID**, 취소 금액(Money), 범위(FULL/PARTIAL), 개시자·행위자, 원 경로, 상태 OPEN→(UNKNOWN→)COMPLETED/FAILED, 취소 Attempt·취소 관찰(append-only), 완료 시 CANCEL 거래 하나 | K-1·K-3·K-6, 구현 결정 CC-01 |
| `CancelAttempt` | `PaymentCancellation` 안의 Entity | 기관 취소 실행 시도. 기관 요청 ID(namespace operation = 취소), dispatch 상태는 승인 `AttemptState` 의미 재사용. 초기 계약: 취소 operation당 dispatch Attempt 1개 | K-6, AUTH CA-12·13 선례 |
| `CancelObservation` | append-only 값 | 기관 취소 결과 한 건: 출처(RESPONSE/INQUIRY/NOTIFICATION/RECONCILIATION), 분류(APPROVED/DECLINED/NO_EFFECT_CONFIRMED/PENDING/UNKNOWN/NOT_FOUND), 확정 취소 조건(`CancelConfirmedTerms`: 취소 Money·원 거래 참조·취소 승인번호(있으면)·ProviderTransactionId·**기관 취소 거래일(필수)**), 원문 hash, 관찰 시각 | K-6, AUTH §3 관찰 정의 |
| `ReturnBalance` | Aggregate Root(PAYMENT DB, Payment 1:1) | 반환 가능 금액 통제: 원 승인 Money(불변), 완료(CONSUMED)·진행 중(RESERVED)·FORCED 합을 **따로** 두고 `cancellable = max(0, authorized − completed − reserved − forced)`, `excess`(FORCED발)·업무 `revision`(운영자 승인 대상 버전)·완료 순번(CC-08). 불변식 §4.3 | K-15·K-16·K-17, CC-02, review H-1 |
| `ReturnReservation` | 값 | (cancellationId, 금액, 상태 RESERVED/CONSUMED/RELEASED/FORCED, FORCED의 초과 표시, CONSUMED의 완료 순번) | K-16, D-CANCEL-006, CC-08 |
| `ReturnStatus` | enum(파생) | NONE / PARTIALLY_CANCELLED / FULLY_CANCELLED. Payment 생명주기와 별개(K-17). 새 승인 판단에 쓰지 않는다(K-12) | K-12·K-17 |
| `CancelStart` | domain 서비스 | 취소 시작 판정 — 모든 검사 뒤에만 `PaymentCancellation.open` + `ReturnBalance.reserve`(§4.4). 거절은 결과 값, 결함은 예외 | AUTH `AuthorizationStart` 선례(관례 §12) |
| `CancellationResolution` | domain 서비스 | 관찰 1건 + 현재 상태 → 전이·예약 소비/해제·사건 판정표(§4.6, §11.2) | AUTH `AuthorizationResolution` 선례 |
| `PaymentTransaction`(확장) | 값 | `PaymentTransactionType.CANCEL` 추가. CANCEL 거래 = 취소 Money·원 AUTHORIZATION 거래 ID·cancellationId·`CancelConfirmedTerms`·출처 관찰·확정 시각 | K-1, 구현 결정 CC-03 |
| (철회) `NonNegativeMinor` | — | 별도 타입 대신 `ReturnBalance`의 0 이상 `Long` 속성(`cancellableMinor`·`excessMinor`)으로 표현(구현 편차 §17.3) | `Money` KDoc |

용어: "취소 operation" = 하나의 `PaymentCancellation`. "부분취소" = 원 승인 금액보다 작은 취소 또는 이미 반환이 있는 Payment의 취소. "반환" = 이
계약에서는 카드 승인취소만(계좌환불은 §6).

### 4.2 전액취소와 부분취소

- **확정(K-1·K-2)**: 전액·부분 모두 각각 새 거래이며 기관이 지원하는 범위에서만 한다.
- **도출(K-2 + AUTH CA-04)**: "기관 지원 범위"는 기관 프로파일 Capability로 판정한다 — `partialCancelSupported`(PaymentPlan·cardType별), 부분취소
  횟수·최소 금액 등 기관 제약은 버전 있는 Capability 정책(Demo). 일반 할부의 부분취소 허용 여부도 이 Capability가 정한다(PG 자체 금지 규칙을
  새로 만들지 않는다). Capability 판정 불가(프로파일 없음·버전 미지원)는 fail-closed 거절.
- **구현 결정 CC-04 — 범위 값**: 취소 시작 시점에 고정한다. FULL = 반환 이력·진행 중 예약이 없는 Payment에서 원 승인 전액을 한 번에 취소, PARTIAL
  = 그 밖의 모든 취소(이미 부분취소가 있은 뒤 남은 잔액 전부를 취소하는 마지막 건 포함). 기관 전문에서 전체/부분 취소 구분이 다르면 Adapter가
  이 값과 잔액으로 변환한다(domain에 전문 값 없음).
- 여러 번의 부분취소: 각 건이 독립 operation·독립 거래다. 순서·동시 진행은 §4.3·D-CANCEL-003(Payment당 진행 중 1건).

### 4.3 반환 가능 금액 불변식

```text
authorized  = 원 AUTHORIZATION 거래 Money (불변)
completed   = Σ CONSUMED 예약 금액 (= Σ CANCEL 거래 금액)
reserved    = Σ RESERVED 예약 금액 (결과 미확정 — Payment당 최대 1건, D-CANCEL-003)
forced      = Σ FORCED 예약 금액 (늦은 취소 성공 재기록 — D-CANCEL-006, 따로 합산: review H-1)

불변식(INV-C1, DB CHECK와 같은 식): completed + reserved ≤ authorized
cancellable = max(0, authorized − completed − reserved − forced)
excess      = max(0, completed + reserved + forced − authorized)   ← FORCED에서만 생긴다(초과 표시, RECON CRITICAL 힌트)
취소 시작 허용: 1 ≤ amount ≤ cancellable ∧ amount.currency == authorized.currency
```

- **확정(K-15·K-16·K-17)**: 완료 + 예약 ≤ 승인액, 부분취소 누적은 잠금/버전으로 보호, 정상 Fixture는 불변식을 만족.
- **확정 D-CANCEL-006 + review H-1**: FORCED는 승인액을 넘어도 기록한다(실제 반환이 있었을 수 있다는 증거). 그래서 FORCED를 완료·예약 합에 넣지 않고 따로 두며,
  CHECK·reconstitute는 `completed + reserved ≤ authorized`만 강제하고 FORCED발 초과는 `excess`로 표시한다. 재현: 10,000 전액 취소 거절 → 새 10,000 취소 완료 →
  첫 취소 늦은 성공 = FORCED 10,000, excess 10,000, cancellable 0(CB-07).
- **도출(K-14 + K-16)**: 결과를 모르는 취소(OPEN·UNKNOWN)의 예약은 **유지**한다 — 기관에서 실제로 반환됐을 수 있으므로 timeout·NOT_FOUND·lease
  만료로 해제하지 않는다. 해제는 기관 확정 거절·전송 전 실패 확정·기관 무효과 확정에서만(승인 한도 점유 해제와 같은 근거 목록, D-CPD-002·D-AUTH-007
  구조). 소비는 기관 확정 취소 성공에서만.
- **도출(K-13)**: 예약·소비·해제와 취소 상태·거래·요청 결과·Outbox는 같은 PAYMENT 로컬 TX.
- 금액 합은 `Math.addExact`(overflow는 결함). 모든 금액은 같은 통화 minor unit이며 통화가 다르면 판정 불가(결함).
- **알려진 이론적 한계(review n-1)**: FORCED는 승인액을 넘어도 기록하므로 completed + reserved + forced가 Long 상한을 넘으면 `ArithmeticException`(결함)으로 반영이 실패한다.
  승인액 자체가 Long 범위이고 FORCED는 실제 취소 요청 금액의 재기록이라 정상 운영에서 도달하지 않는다. 코드 변경 없음.

### 4.4 취소 시작(`CancelStart`)과 흐름

```text
[가맹점 서버] POST cancel(Idempotency-Key, paymentId, merchantCancelId·금액·통화 — D-CANCEL-002)
[PAYMENT TX1] 멱등 예약 → control → payment → payment_authorization → return_balance(행 잠금) 읽기
              → 판정(§4.4 검사 순서) → PaymentCancellation OPEN + CancelAttempt PREPARED(기관 요청 ID 확정)
              + ReturnBalance 예약 RESERVED + 멱등 결과(ACCEPTED: cancellationId) — 한 로컬 TX 커밋
[PAYMENT TXd] 전송 게이트: 원 경로 프로파일·Credential 확인 → Attempt PREPARED→DISPATCHING 조건부 갱신(fencing) 커밋
[TX 밖]       가상 VAN 취소 요청(원 거래 참조·취소 금액·통화)
[PAYMENT TX2] 관찰 append + Resolution 적용(취소 상태·예약·CANCEL 거래·사건) + 멱등 결과 + Outbox — 한 로컬 TX
              (실패 시 원 취소 Attempt 조회로 복구, §4.7)
[응답]        제한 시간 안 확정이면 결과, 아니면 진행 중(202) / 조회 API / (후속) 서명 Webhook
```

검사 순서(모두 통과할 때만 상태 변경, 하나라도 실패하면 어떤 Aggregate도 바뀌지 않는다 — 관례 §12 다중 Aggregate 연산 규칙):

1. 소유·범위: 인증 MID·환경이 Payment의 OrderKey와 같다(K-20, D-CPA-001). 다르면 존재 비노출 404(application).
2. 대상 상태(§4.8): Payment AUTHORIZED ∧ Authorization AUTHORIZED ∧ AUTHORIZATION 거래 있음.
3. incident hold(§4.10, **D-CANCEL-001·006**): 이 Payment를 대상으로 한 **모든** 활성 hold(사유 무관 — 정산 말미 D "하나 해제로 다른 Hold가 사라지지 않음")가 있으면 가맹점 거절 `ORDER_ON_HOLD`, 운영자 보정만 통과.
4. 진행 중 취소(§4.3, **D-CANCEL-003**): OPEN·UNKNOWN 취소가 있으면 거절 `CANCEL_IN_PROGRESS`(같은 merchantCancelId·같은 내용이면 기존 operation — 판정은 application의 업무 키 조회가 먼저).
5. 통화 일치, `1 ≤ amount ≤ cancellable`(§4.3).
6. 범위 Capability(§4.2): PARTIAL이면 원 Plan·cardType에 대해 기관 프로파일이 부분취소를 지원.
7. 운영자 재검사(§4.11, **D-CANCEL-005**, D-ADMIN-007, review m-3): 요청자 = 승인자 → `OPERATOR_SAME_PERSON`, 사유 없음 → `OPERATOR_REASON_MISSING`, 목적 근거 참조 없음 →
   `OPERATOR_BASIS_MISSING`, 승인 대상 fingerprint(`OperatorCancelTarget`: paymentId·금액·통화·ReturnBalance revision, 잔액 없으면 0) ≠ 실행 시점 값 → `OPERATOR_TARGET_STALE`.
   취소 기한은 PG가 판정하지 않는다(**D-CANCEL-004** — 기관 거절로 FAILED).

- **확정(K-3)**: 취소는 신규 승인 권리(권리 Snapshot의 신규 결제 eligibility·lease·한도)를 요구하지 않는다. MID 신규 결제 중지·계약 철회·Snapshot
  lease 만료는 취소를 막지 않는다. 대신 유효한 Credential(가맹점) 또는 운영 권한(운영자)과 **원 경로**(원 Attempt의 기관·환경·기관 계정)가 필요하다.
- **도출(K-3 + D-AUTH-007)**: 취소 시작은 `DailyLimitUsage`를 읽지도 바꾸지도 않는다. 한도 정책 없음·한도 초과는 취소 거절 사유가 아니다.
- **도출(K-12)**: 취소 시작·완료는 `OrderPaymentControl.authorizedPaymentId`를 바꾸지 않는다(전액 취소 뒤에도 새 Payment 금지 — 기존 코드 유지).
  취소는 주문 슬롯 generation과 무관하다(현재 슬롯 검사 `ensureCurrent`를 쓰지 않는다 — 취소 대상은 paymentId로 정해진 승인 성공 Payment).

### 4.5 취소 요청 멱등

- **확정(K-22)**: 멱등 범위 = (environment, 인증된 MID, operation `CancelPayment`, Idempotency-Key), 정규화 본문 fingerprint 비교, 같은 키·다른 내용
  409 `IDEMPOTENCY_KEY_REUSED`, 재조회에도 현재 권한 검사, 보존 30일, 409·422 업무 거절도 결과로 저장·30일 재생(D-CPC-007 선례).
- **구현 결정 CC-05 — 재요청 응답**: 같은 키 재요청은 취소 operation의 **현재 결과**(D-AUTH-004 confirm 선례: 확정 후 불변, 미확정은 진행 중/확인 중),
  시작 전 거절은 저장된 거절 재생. 근거: 취소도 confirm처럼 비동기 금융 결과를 갖는 operation이다(선례 적용 — Coordinator가 다르게 보면 질문으로 올린다).
- **구현 결정 CC-12 — 운영자 업무 키(review H-2)**: 운영자 보정 취소는 ADMIN 2인 승인 요청 ID(`OperatorApprovalId`)를 영구 업무 효과 키로 쓴다 — UNIQUE(approval_id).
  같은 approvalId·같은 금액 = 기존 operation 결과, 같은 approvalId·다른 금액 = 409 충돌(`PaymentCancellation.compareRequest`). 근거: 재시도나 같은 Case의 이중 승인이 두 번
  반환하지 않게(정산 말미 D: Case 안정키와 업무 유일키 유지). 대안 (Case 안정키, 보정 종류, Payment)은 Case 하나가 여러 보정 취소를 낳을 수 있어 택하지 않았다.
- **업무 키의 영속성**: (Payment, merchantCancelId)·approvalId UNIQUE는 **영구**다 — 멱등 기록 30일 보존(D-CPC-004)이 끝나도 같은 ID는 같은 취소로 판정한다.
- **구현 결정 CC-06 — fingerprint**: `x1:` 길이 접두 canonical SHA-256(paymentId, merchantCancelId(D-CANCEL-002), amount, currency). 사유 문구 등 표시
  정보는 비교하지 않는다(D-CPC-001 "표시 정보 비교 안 함" 선례).
- **확정 D-CANCEL-002**: `merchantCancelId` 필수(D-CPC-002 형식, D-CP-001 그대로 비교), (Payment, merchantCancelId) UNIQUE = 업무 효과 키. 같은 ID·같은 내용은
  Idempotency-Key가 달라도 같은 operation, 같은 ID·다른 내용 409 `CANCEL_REQUEST_CONFLICT`, 실패한 취소의 재시도는 새 ID. (질문 배경 기록:)
  서로 다른 취소 요청의 동일성 — 승인에서는 "다른 키·같은 주문·같은 내용 = 같은 시도 재사용"(CLAUDE.md)이 맞지만, 부분취소는
  **같은 금액의 정당한 두 번째 취소**(예: 상품 두 개를 각각 10,000원 반환)가 있어 내용만으로 같은 요청인지 알 수 없다. 키를 바꾼 가맹점 재시도가
  이중 부분취소가 될 위험과 정당한 반복 부분취소를 막을 위험이 맞선다 → §13.

### 4.6 취소 operation 상태와 거래

`PaymentCancellation` 상태: `OPEN`(TX1 — Attempt PREPARED/DISPATCHING) → `UNKNOWN` → `COMPLETED` / `FAILED`. 종료 상태(COMPLETED·FAILED)는
되돌리지 않는다(append-only, K-13).

| 현재 | 사건 | 다음 | 예약 | 비고 |
|---|---|---|---|---|
| (없음) | 취소 시작(§4.4) | OPEN, Attempt PREPARED | RESERVED | TX1 |
| OPEN | 전송 게이트 거절·회복 fencing 포기·connect 실패 예산 소진(Attempt ABANDONED_BEFORE_DISPATCH) | FAILED(`PRE_TRANSMISSION_FAILURE_CONFIRMED`) | RELEASED | 승인 §5.2와 같은 유일 출처 |
| OPEN | write 시작 이후 실패·read timeout·끊김·프레이밍/상관/무결성 실패·dispatch lease 만료·TX2 장애 뒤 회복 | UNKNOWN | RESERVED 유지 | 근거 불요(보수적, K-14) |
| OPEN·UNKNOWN | 확정 취소 성공 관찰, 조건 일치(금액·통화·원 거래 참조) | COMPLETED + CANCEL 거래 1건 | CONSUMED | `PaymentCancelled` Outbox |
| OPEN·UNKNOWN | 기관 확정 거절 | FAILED(`INSTITUTION_CONFIRMED_DECLINE`) | RELEASED | 예: 기한 경과·기관 잔액 부족 |
| OPEN·UNKNOWN | 무효과 확정(Capability `confirmsNoEffect`) | FAILED(`INSTITUTION_CONFIRMED_NO_EFFECT`) | RELEASED | NOT_FOUND는 아님 |
| UNKNOWN | NOT_FOUND·PENDING·UNKNOWN | UNKNOWN 유지 | RESERVED 유지 | 회복 예산 소모 |
| OPEN·UNKNOWN | 성공이나 금액·통화·원 거래 참조 불일치(부분 성공 포함) | UNKNOWN 유지 + 사건 | RESERVED 유지 | **D-CANCEL-006** |
| COMPLETED | 같은 성공 관찰(조건·ProviderTransactionId·기관 거래일 모두 일치) | 변화 없음 | — | 금융 효과·Outbox 하나(INV-06) |
| COMPLETED | 반대 결과·다른 식별자 성공 | 상태 유지 + 사건, 두 번째 거래 없음 | — | **D-CANCEL-006** |
| FAILED | 성공 관찰(늦은 취소 성공) | 상태 유지 + 사건, 거래 없음 | RELEASED → FORCED | **D-CANCEL-006**(초과 표시) |
| 아무 상태 | 같은 관찰 키·같은 기관 내용 | 변화 없음(DUPLICATE) | — | AUTH round2 M-2 정의 재사용 |
| 아무 상태 | 같은 관찰 키·다른 내용 | 첫 관찰 유지 + 상충 증거 append + 사건 | 유지 | AUTH review m-1 선례 |

- **확정(K-1)**: CANCEL 거래는 기관 확정 성공에서만 하나 생긴다. 거절·무효과·UNKNOWN·중복은 관찰로만.
- **확정 D-CANCEL-006 + review m-1**: 취소 사건은 새 hold 사유 `IncidentReason.CANCEL_RESULT_CONFLICT`로 연다(그 Payment의 추가 가맹점 취소 차단). 이 사유의 해제 판정과
  FORCED 출구(RELEASED/CONSUMED)는 RECON-CONTRACT-001이 정할 때까지 없다 — `releaseIncidentHold`는 이 사유를 거절한다(fail-closed, 2단계 구현됨).
- **도출(K-1 + K-19)**: 상충·늦은 성공은 판정 전 거래를 만들지 않는다(D-AUTH-003 "늦은/상충 승인은 hold 해제 판정 전 거래 미생성"과 같은 원칙).
- 종료 근거 출처 표(승인 §5.5와 같은 원칙 — 취소 FAILED의 근거는 `TerminationEvidence` 중 DECLINE·NO_EFFECT·PRE_TRANSMISSION만, TIMEOUT·NOT_FOUND·
  LEASE_EXPIRED는 관찰로만). 구현 결정 CC-07: 취소 종료 근거 enum은 `TerminationEvidence`를 재사용하되 Payment의 새 Payment 허용 판정과 섞지 않는다.

### 4.7 취소 UNKNOWN 회복

- **확정(K-14, D-AUTH-009)**: 원 취소 Attempt의 기관 요청 ID로만 조회(`CancelInquiryPort.inquire(원 요청 참조)`), 새 기관 요청 ID 생성·새 취소 재전송
  금지(기관이 같은 ID 재전송 안전성을 Capability로 확인한 경우의 같은 ID 재전송만 예외 — 허용 §5.2·시도 §4). 조회 예산(횟수·backoff·회로차단)은 버전
  있는 설정·Demo.
- **도출(AUTH §7 동형)**: 예산 소진·UNKNOWN 연령 상한·기관 확정성 창을 넘긴 반복 NOT_FOUND이면 **취소는 UNKNOWN 그대로**, 예약 유지, 대사 이관 사건
  `CancellationOutcomeUnresolved`(OPERATIONAL_RISK) Outbox. 자동 FAILED 금지. 이후 확정은 대사가 원 취소 Attempt와 일치하는 확정 기관 근거를 소유
  도메인 Recovery로 반영할 때만.
- 회복 Worker(후속 구현): 후보 선택은 jOOQ keyset(비권위) → 건별 JPA 잠금 재판정(권위). 처리 lease는 fencing으로 보호하고 lease 만료를 "호출 안 함"으로
  가정하지 않는다(A06).
- UNKNOWN 취소는 결과 확정 전까지 같은 Payment의 다음 취소를 막는다(**D-CANCEL-003**).
- 관측: 취소 UNKNOWN 수·최고 연령, 조회 예산 소진 경보(지표 label에 paymentId 금지, 원칙 §1).

### 4.8 취소 가능한 Payment·주문 상태

| Payment(Authorization) | 취소 | 근거 |
|---|---|---|
| AUTHORIZED(AUTHORIZED, 거래 있음) | 허용(§4.4 검사) | K-1·K-2 |
| REQUESTED | 카드 취소 아님 → 거절 `PAYMENT_NOT_CANCELLABLE`. 미전송이라 금융 효과가 없다. 결제창 만료·종료는 기존 경로(D-AUTHN-005) | 도출(시도 §2·L-3 "REQUESTED = 미전송") — §14-8 범위 관찰 |
| AUTHORIZING·UNKNOWN(OPEN·UNKNOWN) | 거절 `PAYMENT_NOT_CANCELLABLE`(결과 확정 전). 원 Attempt 조회·대사로 승인 결과를 먼저 확정한다 | 도출(K-14 "UNKNOWN은 원 Attempt 결과 조회/대사 우선", 반환 금액 원본 = 확정 거래) — §13 말미 "검토했으나 질문 아님" N-2 |
| FAILED·EXPIRED | 거래가 없으면 누구도 거절 `PAYMENT_NOT_CANCELLABLE`. **D-CANCEL-011**: RECON이 늦은 승인을 거래로 기록한 P1(Payment FAILED/EXPIRED 종료 상태 유지, Authorization만 `AuthorizationTransaction` 보유)은 **운영자 보정 취소만** 가능하고 가맹점 취소는 `PAYMENT_NOT_CANCELLABLE`. 가맹점 관문은 지금처럼 Payment·Authorization AUTHORIZED. P1 상태·주문당 승인 1건 규칙·`authorizedPaymentId`는 바뀌지 않는다(CX-27). **D-CANCEL-013**: 단일 늦은 승인을 REAL_APPROVAL_CONFIRMED로 해제한 뒤에도 Payment는 FAILED 유지, 반환은 Case 연결·2인 승인 운영자 보정 취소만(같은 관문 — CX-27 운영자/가맹점 행) | K-19, AUTH §8.2, D-CANCEL-010·011·013 |
| AUTHORIZED + 전액 반환 완료(cancellable 0) | 거절 `ALREADY_FULLY_CANCELLED` | §4.3 |
| 주문에 다른 generation Payment가 있음 | 무관(취소는 paymentId 대상, 슬롯과 무관) | §4.4 도출 |
| MID 신규 결제 중지·계약 철회·Snapshot lease 만료 | 허용(유효 Credential/운영 권한 필요) | K-3 |
| incident hold 활성 | 이 Payment 대상이면 가맹점 거절·운영자 보정 허용, 다른 Payment 대상이면 허용 | D-CANCEL-001·005·006 |

### 4.9 매입 전/후 취소

- **확정(K-4·K-18)**: 매입은 FINANCIAL 소유, 원 SALE 결과 불명확이면 취소 매입 HOLD, 승인보다 취소가 먼저 도착하면 보류.
- **도출(INV-02 + K-4)**: PAYMENT는 FINANCIAL의 매입 상태를 읽을 수 없으므로 취소 operation을 매입 전/후로 분기하지 않는다. PAYMENT 취소 = 가상 VAN
  취소 요청(원 경로) 하나이며, 기관이 승인 취소로 처리할지 매입 취소로 처리할지는 기관 규약(Simulator 계약)이다. FINANCIAL은 `PaymentCancelled`
  사실로 매입 제출 여부(원 거래가 이미 제출·확정됐으면 취소 레코드 제출, 미제출이면 상계 등)를 정한다 → ACQUIRING-001(§14-4). 기관이 매입 뒤 취소를
  다르게 다루는지는 ACQUIRING-001과 기관 규약이 정하며, 기관이 받지 않으면 기관 확정 거절로 FAILED가 된다(PAYMENT 사전 판정 없음 — review m-8).

### 4.10 incident hold와의 관계

- **확정(K-19)**: hold는 "주문의 추가 승인 차단"이며 자동 보상취소는 없다. 이중 승인 해제(DOUBLE_APPROVAL_CORRECTED)는 **보정(취소) 참조**가 있어야 한다.
- **확정(D-CANCEL-001·005, D-AUTH-011)**: **운영자 보정 취소는 hold가 활성인 동안에도 가능하다**(보정이 해제의 선행 조건). 보정 취소는 이 계약의 같은 취소 경로
  (CancelStart·Resolution·CANCEL 거래)를 쓰고, 그 cancellationId(또는 CANCEL 거래 ID)를 `IncidentHoldRelease.correctionRef`로 쓸 수 있다. 해제 자체는 기존 규칙(2인 + 재대사 MATCH).
- **확정 D-CANCEL-010(U-2, 사용자 확정 2026-10-17)**: 이중 승인 보정은 **늦게 확정된 P1을 취소**한다 — (1) RECON이 hold를 유지한 채 P1의 확정 승인을 거래로 기록하고,
  (2) 운영자가 이 계약의 보정 취소로 P1을 취소하며, (3) DOUBLE 해제가 그 취소를 `correctionRef`로 참조한다. P2는 그대로 둔다. 2단계 domain은 이 경로를 막지 않는다:
  운영자 보정 취소는 이 Payment 대상 hold(이중 승인 CONFLICTING·SUCCESS_AFTER_TERMINATION 포함)가 있어도 시작된다(CX-14·CX-22·CX-27).
- **확정 D-CANCEL-011(U-3)**: RECON 기록 뒤 P1은 Payment FAILED/EXPIRED 그대로, Authorization만 늦은 `AuthorizationTransaction`을 얻는다(상태 FAILED 유지). 운영자 보정 취소는
  Authorization에 거래가 있으면 시작되고, 가맹점 취소는 Payment·Authorization AUTHORIZED일 때만. `ReturnBalance.open`(거래 있음)·`reportCancellationResultConflict`(AUTHORIZED·FAILED·EXPIRED)도
  같은 규칙. RECON의 기록 Use Case가 아직 없어 domain에 모듈 internal 전이 `PaymentAuthorization.recordLateApprovalByReconciliation`(FAILED·거래 없음·기록된 기관 승인
  관찰과 일치하는 거래만)을 두었다 — **RECON 인계**(§14.1), main 호출자는 RECON domain 서비스만(ArchUnit).
- **확정 D-CANCEL-013(U-4, 사용자 확정)**: 이중 승인이 아닌 단일 늦은 승인이 REAL_APPROVAL_CONFIRMED로 해제된 경우에도 Payment는 FAILED 그대로이고, 반환은 Case에 연결된
  2인 승인 운영자 보정 취소만 가능하다(가맹점 취소 경로 없음). RECON이 기록한 거래로 D-CANCEL-011 관문을 그대로 쓴다(CX-27). 이 경로의 PaymentAuthorized는 해제 뒤 정상 발행이며
  D-CANCEL-012 예외가 아니다. RECON-CONTRACT-001 AC, 현재 코드가 이미 이 동작이다.
- **확정 D-CANCEL-012**(이중 승인 경로에 한정): RECON이 hold 중 P1의 승인을 거래로 기록하면 같은 PAYMENT TX에서 `PaymentAuthorized`를 낸다 — FINANCIAL은 P1 승인과 뒤따르는 취소를 모두 본다. AUTH §8.1
  "hold 중 PaymentAuthorized 없음"과 D-AUTH-003 "해제 판정 전 거래 없음"의 **명시적 유일한 예외**이며 구현은 RECON 몫이다.
- **확정 D-CANCEL-001**: hold 대상이 **이 Payment**면 가맹점 취소 거절(`ORDER_ON_HOLD`, 결과 저장), 운영자 보정 취소만 허용. hold가 같은 주문의 **다른 Payment**에만 있으면
  가맹점 취소 허용.
- **확정 D-CANCEL-006**: 취소 결과 사건으로 연 hold도 같은 방식으로 그 Payment의 추가 (가맹점) 취소를 막는다.
- hold 대상 Payment에 확정 거래가 없으면(FAILED + 늦은 승인) 이 계약으로 취소할 수 없다 — RECON이 hold를 유지한 채 그 승인을 거래로 먼저 기록하고(D-CANCEL-010·011), 그 뒤 운영자 보정 취소만 가능하다(§4.8).

### 4.11 개시자: 가맹점 vs 운영자

- **확정(K-24·K-3·K-20)**: 가맹점 취소는 (MID, 환경) secret API key로 인증한 공개 API — 같은 MID·환경의 Payment만.
- **확정(D-CANCEL-005)**: 개시자(`CancelInitiator.Merchant` / `CancelInitiator.Operator`)·행위자·사유·목적·approvalId·승인 대상 fingerprint를 `PaymentCancellation`에 고정하고
  감사한다(Auditability).
- **확정 D-CANCEL-005**: 운영자 취소는 사고·대사 보정 목적만, 사유·행위자 기록, 2인 승인(D-ADMIN-003 "Critical 해결" 등급) + 양쪽 검사(D-ADMIN-007). 가맹점 키 폐기 뒤 남은
  취소 의무도 이 경로. 목적은 `OperatorCancelPurpose` = {`IncidentOrReconCorrection(caseRef)`, `MerchantTerminatedObligation(terminationRef)`}(review m-4). 소유 서비스 재검사(D-ADMIN-007)는
  CancelStart가 업무 거절로 한다(§4.4-7): 요청자≠승인자·사유·근거·승인 대상 버전. 역할·Scope 검증과 ADMIN 승인 상태 조회는 ADMIN 연동 Use Case(CANCEL-001 경계).

---

## 5. 금액 규칙

### 5.1 수수료/VAT — 원승인 Snapshot

- **확정(K-7)**: 취소 수수료/VAT reversal은 원승인 FeeCalculation·Policy Snapshot 기준. 취소일의 현재 정책을 다시 적용하지 않는다.
- **도출(K-4 + D-MS2-003 + 원칙 §4)**: 수수료 계산은 FINANCIAL(정산) 소유이고 PAYMENT는 계산하지 않는다. PAYMENT의 의무는 **원승인 근거를 바꾸지 않고
  넘기는 것**: `PaymentCancelled` payload에 원 AUTHORIZATION 거래 ID(FINANCIAL 업무 키 — D-AUTH-003), 원 Authorization의 `policyAssignmentVersion`
  (`pa1:fee=…;stl=…`)·권리 revision을 **원 Authorization에서 복사**한다. 취소 시작은 정책 입력을 받지 않는다(현재 정책을 끼울 경로가 없다 — 2단계 시험).
- **확정 D-CANCEL-008(K-10 해소)**: 누적 비례 — k번째 반환 수수료 = round(원 수수료 × 누적 취소액 ÷ 승인액) − 이전 반환 합, VAT 동일, 반올림은 원승인 Snapshot 규칙,
  마지막 취소가 끝전을 정확히 흡수, 고정 수수료는 "전액 취소" 때만 반환, 별도 취소 수수료 없음. **D-CANCEL-009(U-1, 사용자 확정)**: "전액 취소" = 누적 취소액을 승인액에 닿게 하는 취소(한 번의 FULL 취소 또는 마지막 PARTIAL) — 그 취소가 고정 수수료와 그 VAT를 반환하고, 누적 비례식은 정률 부분과 그 VAT에만 적용한다. 최소 수수료 하한은 고정 부분으로 본다. PAYMENT가 주는 판정 입력: `PaymentCancelled`의 `authorizedAmount`·반환 후 누적 금액·`returnStatusAfter`(FULLY_CANCELLED = 이 취소가 "전액 취소") — domain `CancelResolutionOutcome.Completed(completion, authorized, statusAfter)`. **계산 구현 소유는 SETTLEMENT(SETTLEMENT-CONTRACT-001)**이며 PAYMENT는 계산하지
  않는다. PAYMENT 의무: `PaymentCancelled`에 원승인 정책 버전·원거래 ID(+ CC-08의 반환 순번·누적 금액). (질문 배경 기록:) 부분취소의 수수료/VAT 반환 배분(정률/정액 반환 비율, 고정비 반환 여부, minor unit 배분 시 마지막 잔액 처리 주체,
  별도 취소비). 결정 소유는 SETTLEMENT-CONTRACT-001(그 AC "미확정 요율·반올림·계산 숫자는 필요 시 질문")이며 **이 계약 2단계를 막지 않는다** — PAYMENT는
  배분에 필요한 사실(각 취소 금액, Payment 안 반환 순번, 반환 후 누적 금액)을 이벤트로 넘긴다(구현 결정 CC-08).
- 반올림: PAYMENT의 취소 금액은 가맹점이 준 minor unit 정수 그대로라 PAYMENT에는 반올림이 없다. 반올림은 FINANCIAL 배분에서 원 Snapshot의 반올림 방식으로
  한다(K-7 "Fee Rounding Method·VAT Rounding Method"는 원 Snapshot, 실제 방식 값은 미고정 — K-10).

### 5.2 통화

- **확정(K-21)**: minor unit 정수, Float/Double 금지. **도출(시도 §3 "승인된 주문의 금액·통화 … 변경하지 않는다")**: 취소 통화 = 원 승인 거래 통화.
  다른 통화 요청은 거절 `CURRENCY_MISMATCH`, 환전 없음. USD 취소는 센트 정수. 외화 정산 통화·환율은 Q-SETTLE-CURRENCY(기존 미결, 이 계약 무관).

### 5.3 정산 거래일 = 취소 완료일

- **확정(K-8)**: 취소 SettlementEntry의 거래일 = 취소일(금융사실이 실제 발생한 날짜), 원 승인일로 돌리지 않는다.
- **도출(K-8 + K-9)**: "취소 완료일"은 기관이 확정한 취소 사실의 날짜다. UNKNOWN을 며칠 뒤 조회·대사로 확정해도 PG 회복 시각이 아니라 기관 취소 거래일·
  업무일이 사실의 날짜다. 그래서 CANCEL 거래는 **기관 취소 거래일(필수)과 PG 확정 시각(`confirmedAt`)을 둘 다** 보존하고 둘을 섞지 않는다(기관 업무일은 승인 `ApprovedTerms`와 같이 domain에 두지 않음 — 필요하면 CANCEL-001 Adapter 계약에서 추가, §17.3).
- 미결(기존 Q-CALENDAR, 새 질문 아님): 날짜 출처의 시간대·기관 거래일 규약·마감·자정 경계는 SETTLEMENT-CONTRACT-001에서 정한다(정산 말미 B "적용 시간대/
  기관 거래일 규약은 별도 상세화", 대안 §6-5).

### 5.4 일 한도 미복원

- **확정(K-11)**: 취소·부분취소는 일 한도를 복원하지 않는다. 취소 경로는 `DailyLimitUsage`를 읽지도 쓰지도 않는다(§4.4). 2단계 시험: 취소 완료 전후
  사용량의 `occupiedMinor`·예약 상태가 같다. 기존 코드에 복원 연산이 없음을 유지한다.

---

## 6. 계좌환불(Refund) — 범위 판단(확정 D-CANCEL-007: v1 범위 밖)

- 기준 문서: Refund는 결제수단별 승인취소와 구분되는 반환(계좌환불 등)이며 거래 상태 원본은 PAYMENT, 지급 실행 원본은 FINANCIAL(A00, 정의 §3.2), 반환 권한·
  예약·수취인·재원·송금 통제를 별도 검사(허용 §4.3), Refund 완료일은 확정 외부 반환 사실의 업무시각(K-9). 최초 범위 표(범위 §1, D-AUTH-001 채택)에는
  카드 "취소"만 있고 계좌환불은 없다. 상세(분산 프로토콜·수취인 계좌 보안·재원 부담·은행 사후 반환)는 미결이다(검토 문서 "취소·환불", 정의 G-5).
- **권장**: v1 카드 결제의 반환은 **카드 승인취소만** 지원하고 계좌환불은 범위 밖으로 명시한다(API·운영 안내에 미지원 표기). 근거 — 최초 결제수단이 국내
  카드이고 카드 반환의 원 경로는 승인취소다(K-3 "원 경로"), 계좌환불은 수취인 계좌(민감정보)·송금 실행·재원 통제가 필요해 MASTER-PAYOUT-ACCOUNT-001·
  SETTLEMENT-001 수준의 별도 계약이 선행돼야 한다. 기관이 기한 경과로 카드 취소를 거절한 건의 처리는 D-CANCEL-004·007: 기관 거절 → FAILED, PayCore 계좌 환불 없음.
- 2단계 domain은 반환 종류를 카드 취소 하나로 두되 `ReturnBalance`의 예약을 "반환" 일반으로 이름 지어 향후 Refund가 같은 불변식(완료 + 예약 ≤ 승인액, K-16의
  `refundedAmount` 포함)에 들어올 자리를 남긴다(구현 결정 CC-02). **확정 D-CANCEL-007**: v1 범위 밖, 카드 반환은 카드 취소로만, API·운영 안내에 계좌 환불 미지원 표시.

---

## 7. Port와 Adapter 경계(후속 구현 작업 — 이 계약은 의미만)

| Port(application.required, 업무 의도만) | 연산(개념) | 결과(업무 분류, 예외 아님) |
|---|---|---|
| `CancellationPort` | `cancel(기관 취소 요청 ID, 원 경로 Snapshot, 원 거래 참조(승인번호·ProviderTransactionId·기관 거래일), 취소 Money, 범위)` | Approved(관찰) / Declined(관찰) / NoEffectConfirmed / NotSentConfirmed(connect 실패·게이트) / OutcomeUnknown(TIMEOUT·CONNECTION_LOST·FRAMING·CORRELATION·INTEGRITY) |
| `CancelInquiryPort` | `inquire(원 취소 요청 ID 참조)` | Found(관찰) / NotFound / NoEffectConfirmed / Unavailable |
| `InstitutionRouteResolver`(기존 계획 확장) | 원 경로 프로파일의 취소 operation Adapter·Capability(`partialCancelSupported`·`confirmsNoEffect`·조회 보존기간·같은 ID 재전송 안전성·취소 기한 등 — Demo) | 프로파일 또는 없음(fail-closed) |
| 저장 Port(관례 §14) | `PaymentCancellationRepository`(Aggregate, `Versioned`), `ReturnBalanceRepository`(Aggregate, 행 잠금) — 구현 `Jpa…Repository` | |
| 조회 Port | `CancellationReader`(jOOQ — 취소 이력·UNKNOWN 회복 후보, 비권위) | |

- Adapter(`payment.adapter.integration.institution`, AUTH CA-17)는 전문을 Port 결과로 바꾸기만 하고 domain 전이를 호출하지 않는다(기존 ArchUnit
  `institutionAdapterDoesNotMutateDomain`이 그대로 적용).
- 기관 취소 요청 ID = PG 생성(구현 결정 CC-09: `cr_` + ULID), namespace operation = 취소, UNIQUE(namespace).
- 가상 VAN 취소 Simulator 계약(후속): 원 승인 잔액 검증, 같은 ID·같은 payload 기존 결과/다른 payload 충돌, 부분취소 순번, 기한 경과 거절, 응답 유실·지연·
  상충·늦은 성공 시나리오, PG 재시작에도 기관 취소 사실 유지(허용 §5.5).

---

## 8. 오류·응답 계약(후속 API — 의미만, 이름·HTTP는 구현 결정)

| 판정 | HTTP(가칭) | code(가칭) | 멱등 결과 저장 |
|---|---|---|---|
| 인증 실패 | 401 | `UNAUTHENTICATED` | 안 함 |
| 형식 오류 | 400 | `INVALID_REQUEST` | 안 함 |
| Payment 없음·다른 MID/환경 | 404 | `PAYMENT_NOT_FOUND` | 안 함(존재 비노출, K-20) |
| 같은 키·다른 내용 | 409 | `IDEMPOTENCY_KEY_REUSED` | — |
| 취소 불가 상태(§4.8) | 409 | `PAYMENT_NOT_CANCELLABLE` | 저장 |
| 전액 반환 완료 | 409 | `ALREADY_FULLY_CANCELLED` | 저장 |
| 금액 초과·통화 불일치 | 422 | `CANCEL_AMOUNT_EXCEEDS_BALANCE` / `CURRENCY_MISMATCH` | 저장 |
| 부분취소 미지원(Capability) | 422 | `PARTIAL_CANCEL_NOT_SUPPORTED` | 저장 |
| 진행 중 취소 있음 | 409 | `CANCEL_IN_PROGRESS` | 저장(D-CANCEL-003) |
| incident hold | 409 | `ORDER_ON_HOLD` | 저장(D-CANCEL-001·006) |
| 같은 취소 요청 식별자·다른 내용 | 409 | `CANCEL_REQUEST_CONFLICT` | 저장(D-CANCEL-002) |
| 취소 확정(제한 시간 안) | 200 | COMPLETED / FAILED(분류: DECLINED·NOT_SENT·NO_EFFECT) | 저장 |
| 진행 중(OPEN·UNKNOWN) | 202 | `IN_PROGRESS` / `UNKNOWN`(확인 중) | 저장(operation 참조) — 새 키·새 요청으로 재시도 금지 안내 |
| 판정 불가(프로파일·Capability 없음) | 503 | `TEMPORARILY_UNAVAILABLE` | 안 함(fail-closed) |

응답 본문: paymentId, cancellationId, 상태, 취소 금액·통화, (완료) 취소 거래 ID·기관 취소 승인번호·완료 시각, 반환 후 `cancellable`·`ReturnStatus`.
카드번호·기관 원문·내부 정책 버전 없음.

---

## 9. 트랜잭션·동시성·이벤트

### 9.1 잠금 순서(후속 구현 — 구현 결정 CC-10, AUTH §4.1 순서의 확장)

멱등 기록 → `order_payment_control`(hold 판정·사건 hold 개설과 직렬화) → `payment` → `payment_authorization`(읽기) → `return_balance`(FOR UPDATE —
반환 합계 불변식의 직렬화 지점) → `payment_cancellation`. TXd는 이 순서의 부분열만 쓰고, **TX2·회복 Use Case는 항상 `order_payment_control`부터 잠근다**(CancellationResolution이 CANCEL_RESULT_CONFLICT hold를 열 수 있음 — review m-7). 승인 TX2는 `return_balance`를 건드리지 않는다
(CC-02 지연 개설) → 승인·취소 경로 사이 교착 없음.

### 9.2 동시 취소 요청(같은 Payment)

- **확정(K-15)**: 누적은 잠금/버전으로 보호, 조회 후 무보호 쓰기 금지.
- **도출**: 서로 다른 취소 operation 행 사이의 합계 불변식은 행별 `@Version`만으로 못 지킨다 → `return_balance` 한 행을 비관적 잠금(DailyLimitUsage와 같은
  이유, 관례 §12). DB 이중 방어(CANCEL-001, review H-1·m-6): `return_balance`에 `completed_minor`·`reserved_minor`·`forced_minor` 별도 컬럼과
  `CHECK(completed_minor + reserved_minor <= authorized_minor)`(forced 제외)·`UNIQUE(payment_id)`; `payment_cancellation`에 UNIQUE(payment_id, merchant_cancel_id),
  UNIQUE(approval_id), 진행 중 1건 = 종료 상태면 NULL인 생성 컬럼(payment_id) + UNIQUE; CANCEL 거래 UNIQUE(cancellation_id). 업무 키 UNIQUE는 영구.
- 실DB 시험(후속): 같은 Payment에 서로 다른 취소 N건 동시 → 완료 + 예약 합이 승인액을 넘지 않음, 같은 키 N건 → operation 1·기관 전송 1, 전액취소 2건 동시 →
  한 건만 시작. 진행 중 취소가 있으면 새 요청은 거절(**D-CANCEL-003**).

### 9.3 Outbox 이벤트(후속 구현, eventCategory는 AUTH CA-19 이름)

| 이벤트 v1 | category | 시점(같은 TX) | effect_key | 의미 |
|---|---|---|---|---|
| `PaymentCancelled` | `FINANCIAL_FACT` | TX2 COMPLETED | `PaymentCancelled:{transactionId}`(CANCEL-001이 `{cancellationId}` 검토 — m-6) | FINANCIAL: 취소 SettlementEntry·매입 취소 후보. payload = paymentId·authorizationId·**originalTransactionId**·cancellationId·transactionId·MID·Site·주문 ID·취소 Money·범위·**authorizedAmount**·Payment 안 반환 순번·반환 후 누적 금액·**returnStatusAfter**·기관 취소 승인번호·ProviderTransactionId·**기관 취소 거래일**·PG 확정 시각·**원 Authorization의 policyAssignmentVersion·권리 revision**·개시자 종류(카드번호·Secret·운영자 개인정보 없음) |
| `PaymentCancelFailed` | `INTEGRATION_NON_FINANCIAL` | TX2 FAILED | `PaymentCancelFailed:{cancellationId}` | 가맹점 통지·조회. 금융 효과 없음 |
| `CancellationOutcomeUnresolved` | `OPERATIONAL_RISK` | 회복 예산 소진·연령 초과 | `CancellationOutcomeUnresolved:{attemptId}` | RECON 대사 이관. 상태 변화 없음 |
| `CancellationIncidentDetected` | `RISK_INCIDENT` | TX2 사건 | `CancellationIncidentDetected:{cancellationId}:{occurrence}` | RECON Case(HIGH — "Unexpected refund" K-26, 승인액 초과 가능 시 CRITICAL — D-CANCEL-006). 정산 대상 아님 |

- FINANCIAL 순서: 승인보다 취소가 먼저 도착하면 보류(K-18) — payload의 originalTransactionId와 반환 순번이 선행관계를 준다. Payment 단위 전달 순서 유지,
  소비자는 Inbox + 업무 키(transactionId)로 중복 제거(INV-06).
- 범위 관찰(§14-3): 승인 완료 기준 선지급에서 진행 중 반환 예약을 빼야 하는지(예약/해제 이벤트 필요 여부)는 SETTLEMENT-CONTRACT-001이 정한다(대안 §4는 제안
  이력일 뿐).
- Kafka 미도입(ADR-001). 전달 보장은 Outbox/Inbox·멱등·대사이며 브로커가 금융 안전성을 대체하지 않는다.

---

## 10. 2단계에서 추가할 domain 타입·불변식(질문 답 전 가능한 부분 표시)

| 단계 | 내용 | 막는 질문 |
|---|---|---|
| T1 | `PaymentTransactionType.CANCEL`·sealed `PaymentTransaction`(`AuthorizationTransaction`·`CancelTransaction`), 기존 AUTHORIZATION 거래 동작 불변(CC-03) | 없음 — **구현됨** |
| T2 | `ReturnBalance`·`ReturnReservation`·`ReturnStatus`·`ReturnCompletion`: 개설(AUTHORIZED + 거래에서만), 예약·소비(완료 순번)·해제·FORCED 재기록, 별도 합(H-1), revision, overflow·통화 결함, reconstitute 검사 | 없음(D-CANCEL-006 반영) — **구현됨** |
| T3 | `PaymentCancellation`·`CancelAttempt`·`CancelObservation`·`CancelInitiator`(Merchant/Operator)·`CancelRequestKey`·`OperatorCancelPurpose`: 상태·dispatch 전이·관찰 append·같은 키 중복/상충·업무 키 비교 | 없음 — **구현됨** |
| T4 | `CancelStart`: 검사 순서 §4.4 **1~7 전부**(대상 상태·hold·진행 중·통화/금액·Capability·운영자 재검사/승인 대상), 범위 FULL/PARTIAL(CC-04), 정책 버전 복사, 한도·control 불변, 실패 시 모든 Aggregate 불변. control `CANCEL_RESULT_CONFLICT` hold 개설·해제 거절(m-1) | 없음 — **구현됨** |
| T5 | `CancellationResolution`(§4.6 판정표 전부: 완료·거절·무효과·전송 전 실패·UNKNOWN 유지·중복·상충·늦은 성공 FORCED·hold), `CancelResolutionOutcome.Completed`(순번·누적·승인액·statusAfter), `PaymentCancellation.incidentRecorded`, fixture 공식 전이 전환 | **구현됨** |
| T6 | ArchUnit: `cancelStartOnlyThroughCancelStart`(open·ReturnBalance.open/reserve), `domainTransitionsOnlyInsideDomain` 취소 전이 추가, `cancelHoldOnlyThroughCancellationResolution`, `returnBalanceRestoredOnlyByPersistence`, `cancelStartIndependentOfLimitAndEntitlement` + 위반 fixture | **구현됨** |
| T7 | 문서: `DOMAIN-DEFINITION.md`(Cancellation·Refund·PaymentTransaction·IncidentHold 행, G-5 해결, 타입 목록), `ARCHITECTURE-CONVENTIONS.md` §15, `AUTH-CONTRACT-001` §8.2·§18-6 교차 참조 | **구현됨** |

불변식 목록(2단계 시험 대상):

- INV-C1 `completed + reserved ≤ authorized`(모든 연산 뒤, forced 제외), `cancellable = max(0, authorized − completed − reserved − forced)`, `excess = max(0, completed + reserved + forced − authorized)`.
- INV-C2 COMPLETED ⇔ CANCEL 거래 정확히 하나 ⇔ 예약 CONSUMED. FAILED ⇒ 예약 RELEASED 또는 FORCED(늦은 성공, D-CANCEL-006). FORCED의 출구는 RECON-CONTRACT-001이
  정하는 해제 판정으로만(그 전까지 FORCED 유지 — 미래 경로의 자리).
- INV-C3 OPEN·UNKNOWN ⇒ 예약 RESERVED(timeout·NOT_FOUND·lease 만료로 해제 불가).
- INV-C4 취소 통화 = 원 승인 통화, 금액 ≥ 1 minor unit.
- INV-C5 취소는 `DailyLimitUsage`·`OrderPaymentControl.authorizedPaymentId`·`PaymentStatus`를 바꾸지 않는다.
- INV-C6 CANCEL 거래의 원 거래 ID = 그 Payment의 AUTHORIZATION 거래 ID, 정책 버전 = 원 Authorization 값.
- INV-C7 관찰 append-only, 같은 키·같은 내용 1회, 종료 상태 불가역.
- INV-C8 `ReturnStatus` = FULLY_CANCELLED ⇔ completed = authorized, PARTIALLY_CANCELLED ⇔ 0 < completed < authorized, NONE ⇔ completed = 0.

---

## 11. 판정표 시험 계획(2단계, Kotest BehaviorSpec)

기대값은 이 표에서 독립 기술한다(구현 식 복제 금지). 금액은 Demo(운영값 아님). 기준 Fixture: KRW 10,000 일시불 신용 승인(`AuthorizationFixture` 경유 공식
전이로 AUTHORIZED + 거래), USD 행은 1050(=10.50).

### 11.1 취소 시작(`CancelStartSpec`)

| ID | 준비 | 요청 | 기대 |
|---|---|---|---|
| CX-01 | AUTHORIZED 10,000, 반환 없음 | 10,000 | Started: OPEN·PREPARED, 범위 FULL, 예약 RESERVED 10,000, cancellable 0 |
| CX-02 | 같음 | 3,000 | Started, PARTIAL, cancellable 7,000 |
| CX-03 | 3,000 완료 | 7,000 | Started, PARTIAL(마지막 잔액) |
| CX-04 | 3,000 완료 | 7,001 | Rejected(금액 초과), 모든 Aggregate 불변 |
| CX-05 | 10,000 완료 | 1 | Rejected(전액 반환 완료) |
| CX-06 | AUTHORIZED KRW | USD 금액 | Rejected(통화 불일치) |
| CX-07 | REQUESTED / AUTHORIZING / UNKNOWN / FAILED / EXPIRED | 1,000 | Rejected(취소 불가 상태) — 5행 |
| CX-08 | 할부·Capability 부분취소 미지원(Demo) | 3,000 | Rejected(부분취소 미지원); 같은 Payment 전액 10,000은 Started |
| CX-09 | Capability 프로파일 없음 | 3,000 | 판정 불가(fail-closed) |
| CX-10 | 아무 Started | — | `DailyLimitUsage` 상태·`occupiedMinor` 불변, control `authorizedPaymentId` 불변, Payment AUTHORIZED 유지 |
| CX-11 | 원 Authorization `policyAssignmentVersion = pa1:fee=A/1;stl=B/1` | 3,000 | 취소에 같은 값 고정(입력 경로 없음) |
| CX-12 | Authorization·Balance가 다른 Payment 것 | — | 결함 예외, 불변 |
| CX-13 | 진행 중 취소 OPEN/UNKNOWN 있음 | 1,000 | Rejected `CANCEL_IN_PROGRESS`(D-CANCEL-003), 불변 |
| CX-14 | hold 활성(같은 Payment / 다른 Payment) × 개시자(가맹점/운영자 보정) | 1,000 | 같은 Payment hold: 가맹점 Rejected `ORDER_ON_HOLD`, 운영자 보정(2인·사유·근거) Started. 다른 Payment hold: 가맹점 Started(D-CANCEL-001·005) |
| CX-15 | MID 신규 결제 중지 상태 입력이 있어도 | 1,000 | Started(취소는 신규 승인 권리 불요 — `CancelStartCommand`에 eligibility·한도 입력이 없음으로 구조 고정, 실행 시험 아님 §17.3) |
| CX-16 | (H-2) 운영자 같은 approvalId 두 번 / 같은 approvalId·다른 금액 / 다른 approvalId | SAME_REQUEST / CONFLICTING_CONTENT / DIFFERENT_REQUEST(`compareRequest`), 가맹점 키와 겹치지 않음 |
| CX-17 | (m-3) 운영자: 요청자=승인자 / 사유 공백 / 근거 공백 / 다른 금액 승인 / 이전 잔액 revision 승인 | OPERATOR_SAME_PERSON / REASON_MISSING / BASIS_MISSING / TARGET_STALE / TARGET_STALE, 불변. `PaymentCancellation.open` 직접 호출도 같은 사람 거부 |
| CX-18 | (m-4) 운영자 목적 MerchantTerminatedObligation(terminationRef) | Started |
| CX-19 | (m-5) D-CANCEL-002: 형식 위반 / 같은 ID·다른 금액 / FAILED 뒤 같은 ID | 생성 거부 / CONFLICTING_CONTENT / SAME_REQUEST(옛 결과) |
| CX-20 | (m-5) D-CANCEL-004: 3년 전 승인 | Started(PG 기한 없음) |
| CX-21 | (m-5) D-CANCEL-006 순서: 3,000 거절 → 늦은 성공 | FORCED 3,000 + 이 Payment CANCEL_RESULT_CONFLICT hold → 가맹점 ORDER_ON_HOLD → 운영자 7,001 AMOUNT_EXCEEDS_BALANCE, 7,000 Started. 그 hold 해제는 거절(m-1) |
| CX-22 | (D-CANCEL-010) gen2 AUTHORIZED + 이중 승인 CONFLICTING hold(REAL 해제 뒤 gen2 승인) | 가맹점 ORDER_ON_HOLD, 운영자 보정 Started — 보정 경로를 막지 않음 |
| CX-23 | (review m-A) 10,000 전액 취소 거절 → 늦은 성공(FORCED 10,000, 완료 0, NONE) | 운영자 1원 AMOUNT_EXCEEDS_BALANCE(ALREADY_FULLY_CANCELLED 아님) |
| CX-24 | (review m-B) 같은 운영자·가맹점 키, 다른 paymentId | CONFLICTING_CONTENT |
| CX-25 | (review m-C) 승인 대상의 approvalId·요청자·승인자·목적 종류·근거 참조 중 하나만 변경 / 사유 문구만 변경 | 각각 OPERATOR_TARGET_STALE / Started |
| CX-26 | (review n-3) 취소 요청 operation ≠ `CANCEL` | 결함 예외 |
| CX-27 | (D-CANCEL-011) RECON이 거래를 기록한 늦게 확정된 P1(FAILED, hold) / 거래 없는 FAILED | P1: 운영자 보정 Started·가맹점 PAYMENT_NOT_CANCELLABLE, 시작·완료 뒤 P1 FAILED·`authorizedPaymentId`=P2 유지. 거래 없음: 운영자도 PAYMENT_NOT_CANCELLABLE. 기록 전이: FAILED·거래 없음·기록된 관찰과 일치만 |

### 11.2 취소 결과 반영(`CancellationResolutionSpec`)

| ID | 현재 | 관찰 | 기대 |
|---|---|---|---|
| CR-01 | OPEN 3,000 | APPROVED 3,000 KRW, 원 거래 참조 일치 | COMPLETED, CANCEL 거래 1(원 거래 ID·기관 거래일·confirmedAt 분리 보존), CONSUMED, completed 3,000, PARTIALLY_CANCELLED |
| CR-02 | UNKNOWN 10,000 | 같은 | COMPLETED, FULLY_CANCELLED |
| CR-03 | OPEN | DECLINED | FAILED(DECLINE), RELEASED, cancellable 복귀 |
| CR-04 | UNKNOWN | NO_EFFECT_CONFIRMED | FAILED(NO_EFFECT), RELEASED |
| CR-05 | OPEN(PREPARED) | 전송 게이트 거절 | FAILED(PRE_TRANSMISSION), RELEASED |
| CR-06 | OPEN(DISPATCHING) | TIMEOUT | UNKNOWN, RESERVED 유지 |
| CR-07 | UNKNOWN | NOT_FOUND / PENDING / UNKNOWN | UNKNOWN 유지, RESERVED 유지, FAILED 불가 |
| CR-08 | COMPLETED | 같은 APPROVED(모든 식별자 일치), 수신 시각·hash만 다름 | NoChange, 거래 1 |
| CR-09 | 아무 | 같은 관찰 키·다른 내용 | 첫 관찰 유지 + 상충 증거 + 사건, **CANCEL_RESULT_CONFLICT hold 열림 확인**(m-11) |
| CR-10 | OPEN | APPROVED 2,000(요청 3,000) | UNKNOWN + 사건, 거래 없음, RESERVED 3,000 유지, **hold 열림 확인** — D-CANCEL-006 |
| CR-11 | FAILED(RELEASED) | 늦은 APPROVED | 상태 유지 + 사건, 거래 없음, 예약 FORCED 재기록·초과 표시, 이 Payment hold — D-CANCEL-006 |
| CR-12 | COMPLETED | 다른 ProviderTransactionId APPROVED | 상태 유지 + 사건, 두 번째 거래 없음, **hold 열림 확인** — D-CANCEL-006(변이 RED: 두 번째 거래 생성) |
| CR-13 | COMPLETED | — | 전액 반환 뒤 control `decide()` = hold 없으면 `AUTHORIZED_NEW_ORDER_REQUIRED`, 취소 사건 hold가 있으면 `Blocked(INCIDENT_HOLD)`(K-12, review n-4) |

### 11.3 반환 잔액 순서 시나리오(`ReturnBalanceSpec`)

| ID | 순서 | 기대 |
|---|---|---|
| CB-01 | 10,000 → 3,000 완료 → 7,000 완료 | FULL, cancellable 0, 거래 2(PARTIAL·PARTIAL), 반환 순번 1/2·누적 3,000/10,000 |
| CB-02 | 3,000 시작 → DECLINED(RELEASED) → 10,000 시작 | Started, 범위 **FULL**(CC-04 확인 행: 거절된 취소는 완료·진행 중 반환이 아니다) |
| CB-03 | 3,000 UNKNOWN 중 7,000 요청 | Rejected `CANCEL_IN_PROGRESS`(D-CANCEL-003) |
| CB-04 | USD 1050 → 525 → 525 | FULL 완료, 센트 정수 |
| CB-05 | Long 상한 근처 승인에서 합계 | overflow 결함 예외 |
| CB-06 | reconstitute: completed + reserved > authorized, RESERVED 2건, 중복 ID, 순번 1..n 아님, revision 미달 | 거부(조용히 고치지 않음). FORCED발 초과만 허용 |
| CB-07 | (H-1) 10,000 전액 취소 거절 → 새 10,000 완료 → 첫 취소 늦은 성공 | FORCED 10,000, excess 10,000, cancellable 0, FULLY_CANCELLED. 운영자 1원도 ALREADY_FULLY_CANCELLED |
| CB-08 | (구조 확인 — 변경할 정책 입력이 없음, review n-5) 부분취소 두 건 사이 정책 변경 | 두 취소 모두 원 Authorization의 정책·권리 버전 |
| CB-09 | (review n-2) reconstitute: 초과가 있는데 EXCEEDED FORCED 없음 / revision < 업무 변경 이벤트 수(RESERVED 1, CONSUMED·RELEASED 2, FORCED 3) | 거부 |

### 11.4 Fixture(Test Fixture Policy)

Named Fixture(src/test만): `CancellationFixture`(`completedCancellation(amount)`, `unknownCancellation()`, `declinedCancellation()`),
`ReturnBalanceFixture`(`paymentWithReservedReturn()`, `partiallyCancelledPayment(cancelled)`, `fullyCancelledPayment()`) — 모두 기존
`AuthorizationFixture`의 AUTHORIZED 경로 → `CancelStart` → `CancellationResolution` 공식 전이로 만든다(reconstitute 금지). 관계 금액을 독립 random으로
만들지 않는다(K-16). Fixture Monkey는 핵심 규칙 시험에 쓰지 않는다. 상충 의도 Fixture는 이름에 의도를 적는다(`approvedWithDifferentCancelAmount`).

### 11.5 RED와 검증(2단계)

- RED: 시험 먼저(컴파일 RED) + 변이 RED(예: 예약 해제를 UNKNOWN에서 허용, 완료 시 소비 누락, 정책 버전을 입력에서 받기, 취소 완료가 control 이력 변경,
  CancelStart 검사 전 open 호출) → 대상 Spec 실패 확인 → 원복.
- 실행: `./gradlew :apps:payment-api:test`·`check`, harness VERIFY-GATE(새 fingerprint)·테스트 수 1485 이상·SELFTEST 81 이상(AC 5). 2단계는 새 의존성이 없어
  빌드 파일 불변 계획(DEPS/CVE 해당 없음 — 바뀌면 실행).

---

## 12. 구현 결정 목록(가역적, 질문 아님)

| ID | 결정 | 이유 |
|---|---|---|
| CC-01 | 취소 요청 1건 = `PaymentCancellation` Root 1개(Payment 1:N). Authorization 안에 넣지 않는다 | 무제한 컬렉션 회피(K-17), AUTH Aggregate·AUTH-001 진행 중 계약 불변 |
| CC-02 | 반환 합계 통제 `ReturnBalance`(Payment 1:1)를 별도 Root로, 첫 취소 시작에서 원 AUTHORIZATION 거래로 개설(지연 개설, 영속은 UNIQUE(payment_id) 경합 재판정). 이름은 "반환" 일반(향후 Refund 자리) | 승인 TX2 경로 불변·교착 방지(§9.1), K-16 불변식 한 곳. 대안: 승인 완료 TX2에서 개설 |
| CC-03 | `PaymentTransaction`을 승인/취소 공통 거래로 확장(타입 CANCEL 추가, 승인 전용 `ApprovedTerms`와 취소 `CancelConfirmedTerms`를 구분하는 sealed 표현). 기존 AUTHORIZATION 동작·시험 불변 | D-AUTH-003 "모든 기관 확정 효과 = PaymentTransaction" 유지 |
| CC-04 | 범위 FULL/PARTIAL은 시작 시 고정(FULL = 완료·진행 중 반환이 없는 Payment의 전액 1회) | 기관 전문 구분은 Adapter 변환 |
| CC-05 | 같은 키 재요청 = operation 현재 결과, 시작 전 거절 재생 | D-AUTH-004 선례 |
| CC-06 | fingerprint `x1:` 길이 접두 canonical, 표시 정보 제외 | CP계약 §7·AUTH CA-06 방식 |
| CC-07 | 취소 종료 근거는 `TerminationEvidence` 값 재사용(DECLINE·NO_EFFECT·PRE_TRANSMISSION만), Payment 판정과 분리 | 근거 의미 동일, 새 enum 불필요 |
| CC-08 | `PaymentCancelled`에 반환 순번·반환 후 누적 금액 포함 | D-CANCEL-008 누적 비례 배분을 FINANCIAL이 PAYMENT DB 없이 계산(INV-02) |
| CC-09 | 기관 취소 요청 ID `cr_` + ULID, namespace UNIQUE | AUTH CA-22 선례 |
| CC-10 | 잠금 순서 §9.1 | 기존 순서 확장, 교착 방지 |
| CC-11 | 취소 operation당 dispatch Attempt 1개, UNKNOWN에서 새 Attempt 없음 | AUTH CA-13 선례, K-14 |
| CC-12 | 운영자 보정 취소 영구 업무 키 = ADMIN 2인 승인 요청 ID(`OperatorApprovalId`), UNIQUE(approval_id) | review H-2, 정산 말미 D(업무 유일키) |
| CC-13 | 운영자 승인 대상 = `OperatorCancelTarget`(paymentId·금액·통화·ReturnBalance revision·approvalId·요청자·승인자·목적 종류·근거 참조, 사유 제외)의 `oct1:` fingerprint, revision은 잔액 업무 변경마다 +1(JPA version과 별개) | review m-3·m-C, D-ADMIN-007 |
| CC-14 | `compareRequest(paymentId, key, money)` — 같은 키·다른 Payment는 충돌(fail-closed) | review m-B |
| CC-15 | 기관 요청 operation 이름 `PaymentCancellation.CANCEL_OPERATION = "CANCEL"` | review n-3, 허용 §5.2 namespace |
| CC-16 | 사건 처리된 취소(`incidentRecorded`)의 이후 결과는 기록만(자동 확정·종료 없음). hold는 다른 취소 operation의 결과 반영을 막지 않는다 | D-CANCEL-006, D-AUTH-008 원칙 |
| CC-17 | RECON 늦은 승인 거래 기록의 domain 자리 = `PaymentAuthorization.recordLateApprovalByReconciliation`(모듈 internal, domain 밖 호출 ArchUnit 금지). 상태 FAILED 유지 | D-CANCEL-011, RECON 인계 |

---

## 13. 질문 후보(Coordinator → 사용자) — 답변됨(rev 1.1)

### 13.0 답변 대응표

| 질문 | 결정 | 답변 요지 | 반영 |
|---|---|---|---|
| Q-CANCEL-01 | **D-CANCEL-001** | 권장안: 같은 Payment hold만 가맹점 취소 거절, 운영자 보정 허용, 다른 Payment hold는 막지 않음 | §4.10, CX-14 |
| Q-CANCEL-02 | **D-CANCEL-002** | 권장안: merchantCancelId 필수, (Payment, ID) UNIQUE 업무 효과 키 | §4.5, CC-06 |
| Q-CANCEL-03 | **D-CANCEL-003** | 권장안: Payment당 진행 중 1건, 409 CANCEL_IN_PROGRESS, UNKNOWN 차단 | §4.3, CX-13, CB-03 |
| Q-CANCEL-04 | **D-CANCEL-004** | 권장안: PG 기한 없음, 기관 프로필(Demo)만, 기한 경과 = 기관 거절 → FAILED | §4.4-7 |
| Q-CANCEL-05 | **D-CANCEL-005** | 권장안: 보정 목적만, 사유·행위자, 2인(Critical 해결 등급), 양쪽 검사 | §4.11, CX-14 |
| Q-CANCEL-06 | **D-CANCEL-006** | 권장안: 승인 사고와 같은 처리, FORCED 재기록·초과 표시, hold는 추가 취소 차단 | §4.6, CR-09~12 |
| Q-CANCEL-07 | **D-CANCEL-007** | 권장안: 계좌 환불 v1 범위 밖 | §6 |
| Q-CANCEL-08 | **D-CANCEL-008** | "지금 확정": 누적 비례, 고정 수수료 전액 취소 때만, 별도 취소비 없음. 계산 소유 SETTLEMENT | §5.1, CC-08 |

Coordinator 판정: N-2 도출 채택, N-1·N-3~N-7 기술대로. 아래 원문은 질문 당시 기록이다.

기준 문서·결정이 답하지 않는 금융/정책 미결만 올린다. 답이 오기 전 해당 운영 동작은 구현하지 않으며 2단계는 §10 표의 막히지 않은 단계부터 진행한다.

### Q-CANCEL-01 incident hold가 활성인 주문의 취소 — 답변됨(D-CANCEL-001)

- 질문: 주문에 incident hold가 활성일 때 가맹점 취소 API를 허용하는가. hold 대상이 **이 Payment 자신**인 경우와 **같은 주문의 다른 Payment**(예: 이전
  generation 늦은 승인 hold 중 현재 generation 승인)인 경우를 구분하는가.
- 권장안: **hold 대상이 이 Payment면 가맹점 취소 거절(`ORDER_ON_HOLD`, 결과 저장), 운영자 보정 취소만 허용. hold가 같은 주문의 다른 Payment에만 있으면 이
  Payment의 가맹점 취소는 허용.** 이유 — hold는 "추가 승인 차단"(K-19)이고 확정 거래의 반환은 신규 승인이 아니다(허용 §4.3). 다만 hold 대상 Payment는 기관
  결과가 상충 중이라 반환 가능 금액 자체가 불확실하므로(예: AUTHORIZED 뒤 기관이 반대 결과 보고) 가맹점 자동 경로를 막고 조사·보정 절차에 넣는다. 이중
  승인(D-AUTH-011)에서 고객 피해를 줄이는 정상 Payment 취소는 막지 않는다. 운영자 보정 취소가 hold 중 가능해야 한다는 점은 D-AUTH-011에서 도출(§4.10).
- 대안: (b) hold가 하나라도 있으면 주문의 모든 가맹점 취소 거절(가장 보수적, 고객 반환 지연). (c) hold는 취소를 막지 않음(가장 단순, 상충 중 Payment 반환이
  대사를 더 복잡하게 함).
- 영향: 가맹점 API 응답(409 여부), 고객 반환 지연, 대사 Case 처리 순서, 운영 부담.
- 막히는 작업: CancelStart 검사 3번·CX-14 행, 취소 API 오류 매핑. 나머지 2단계는 진행 가능.

### Q-CANCEL-02 서로 다른 취소 요청을 구별하는 키 — 답변됨(D-CANCEL-002)

- 질문: 같은 Payment에 대한 두 취소 요청이 "같은 요청의 재시도"인지 "별개의 부분취소"인지를 무엇으로 판정하는가.
- 권장안: **가맹점 취소 요청 ID(`merchantCancelId`, D-CPC-002 형식 1~64자 ASCII, D-CP-001 그대로 비교)를 필수로 받고 (Payment, merchantCancelId)를 취소 업무 키로
  UNIQUE 한다.** 같은 merchantCancelId·같은 내용(금액·통화)은 Idempotency-Key가 달라도 같은 취소 operation 결과를 반환(CLAUDE.md "다른 키/같은 주문 동일 내용도
  활성 시도 재사용"의 취소판), 같은 merchantCancelId·다른 내용은 409 `CANCEL_REQUEST_CONFLICT`, 다른 merchantCancelId는 별개 취소(반환 가능 금액 안에서). 실패한
  취소의 재시도는 새 merchantCancelId. 이유 — 같은 금액의 정당한 반복 부분취소와 "timeout 뒤 새 키로 재시도"를 내용만으로 구별할 수 없고, 원칙 §1이 요청키와
  별도로 **업무효과 key**를 요구한다. 주문 키(merchantOrderId)와 같은 방식이라 가맹점이 이해하기 쉽다.
- 대안: (b) Idempotency-Key만 — 키가 다르면 항상 별개 취소(가맹점이 새 키로 재시도하면 이중 부분취소 가능, 반환 가능 금액이 상한). (c) 내용 기반 — 진행 중 같은
  금액 요청은 기존 operation 재사용(같은 금액 두 번째 취소를 진행 중에는 못 함, 완료 뒤 같은 금액은 별개로 처리되어 재시도와 구별 불가).
- 영향: 공개 API 필수 필드·DB UNIQUE, 고객 과다 반환 위험(가맹점 손실), 가맹점 연동 난이도, fingerprint 구성(CC-06).
- 막히는 작업: `PaymentCancellation` 식별 필드·CX 행 일부, 취소 API·멱등 저장. 반환 잔액·판정표 대부분은 진행 가능.

### Q-CANCEL-03 진행 중 취소와 다음 취소(UNKNOWN이 막는가) — 답변됨(D-CANCEL-003)

- 질문: 같은 Payment에 결과가 확정되지 않은 취소(OPEN·UNKNOWN)가 있을 때 새 취소 요청을 받는가.
- 권장안: **Payment당 진행 중 취소 operation 하나(직렬).** 진행 중이면 새 요청은 409 `CANCEL_IN_PROGRESS`(결과 저장, 같은 요청 식별자면 기존 operation 반환).
  UNKNOWN 취소는 조회·대사로 확정될 때까지 다음 취소를 막는다. 이유 — 기관 부분취소는 보통 기관 쪽 잔액·순번으로 검증되는데 결과 모르는 취소가 있으면 기관 잔액이
  불확실해 다음 부분취소가 거절·순번 꼬임·이중 반환 조사로 번질 수 있다. 승인의 "dispatch Attempt 동시에 하나"와 같은 보수 원칙이고 대사가 단순하다. 비용은
  UNKNOWN 동안 가맹점 대기 — UNKNOWN 연령 경보·대사 이관으로 관리.
- 대안: (b) 병행 허용 — 반환 가능 금액(완료 + 모든 예약 ≤ 승인액) 안이면 여러 건 동시 진행(가맹점 대기 없음, 기관 순번 의존성·대사 복잡). (c) 전송만 직렬 —
  dispatch 중인 건이 있으면 대기/거절하지만 UNKNOWN으로 넘어간 건은 예약만 유지하고 다음 취소 허용(중간안).
- 영향: 가맹점 경험, 기관 거절률, 대사 복잡도, 과다 반환 위험(모든 안에서 반환 가능 금액 불변식은 유지되므로 PG 원장 초과는 없음).
- 막히는 작업: CancelStart 검사 4번·CX-13·CB-03, 취소 API. 불변식 자체는 진행 가능.

### Q-CANCEL-04 취소 기한 — 답변됨(D-CANCEL-004)

- 질문: PG가 가맹점에 적용하는 자체 취소 기한(승인 후 N일 등)을 두는가, 기관 기한만 따르는가.
- 권장안: **v1은 PG 자체 기한 없음.** 기관 프로파일 Capability의 취소 기한(버전 있는 설정, Demo — D-AUTH-009)만 쓰고, 기한 경과는 기관 확정 거절로 FAILED가
  되게 한다(사전 판정은 프로파일 값이 확정된 기관에만 fail-closed 거절로 추가 가능). 이유 — 운영 기한 수치는 실제 기관 규약 확인 전 정할 수 없고(D-AUTH-009),
  PG 자체 기한은 가맹점 계약 조건이라 사용자 결정 없이 추가하지 않는다.
- 대안: (b) PG 자체 기한을 가맹점 계약 기준정보(버전 있는 정책)로 두고 사전 거절. (c) 기관 기한을 PG가 항상 사전 판정(프로파일 값 오류 시 정당한 취소를 막을 위험).
- 영향: 기한 경과 건의 반환 수단(Q-CANCEL-07 계좌환불과 연결), 가맹점 계약, 기관 불필요 호출.
- 막히는 작업: CancelStart 검사 7번 일부, Simulator 기한 시나리오. 2단계 핵심은 진행 가능.

### Q-CANCEL-05 운영자 개시 취소 — 답변됨(D-CANCEL-005)

- 질문: 내부 운영자가 취소를 개시할 수 있는 범위와 승인 요건.
- 권장안: **운영자 취소는 사건·대사 보정용으로만 허용(사유·행위자 필수, 대상 Payment의 MID·환경 기록), 2인 승인(요청자≠승인자, APPROVER — D-ADMIN-003의
  "Critical 해결" 범주로 취급)과 소유 서비스 양쪽 검사(D-ADMIN-007).** 고객 요청 대행 같은 일반 취소는 가맹점 API로만 하고 계약 종료로 가맹점 키가 폐기된 뒤의
  취소 의무(K-3 "유효한 별도 운영 권한")는 같은 운영자 보정 절차를 쓴다. 이유 — 운영자 취소는 가맹점 동의 없는 자금 반환이라 보수적으로 두고, 이중 승인 보정
  (D-AUTH-011)에는 반드시 필요하다.
- 대안: (b) 운영자 일반 취소도 허용, 1인(OPERATOR) 즉시. (c) 운영자 취소 없음(보정은 가맹점에 요청 — D-AUTH-011 보정이 가맹점 협조에 묶임).
- 영향: 관리자 화면 범위(ADMIN-BOOT-001), 감사, 내부 통제, 계약 종료 가맹점의 고객 반환.
- 막히는 작업: CX-14 운영자 행·개시자 검증, 운영자 취소 Use Case. 가맹점 경로는 진행 가능.

### Q-CANCEL-06 취소 결과 상충·늦은 취소 성공 — 답변됨(D-CANCEL-006)

- 질문: (1) 진행 중 취소에 요청과 다른 금액·통화·원거래의 성공이 오거나, (2) 확정 거절로 FAILED가 된 취소에 늦은 성공이 오거나, (3) 완료된 취소에 반대 결과·다른
  식별자 성공이 오면 어떻게 하는가.
- 권장안: **승인 사건과 같은 방식(D-AUTH-002·008·010을 취소에 적용):** 상태·거래를 덮어쓰지 않고 관찰 append + 사건 hold + `CancellationIncidentDetected`(RECON
  Case, "Unexpected refund" HIGH, 반환 합계가 승인액을 넘을 수 있으면 CRITICAL 힌트) + 자동 재취소·재승인 없음. (1) 취소는 UNKNOWN 유지·예약 유지, (2) 해제했던
  반환 예약을 **FORCED로 다시 기록**(반환 가능 금액에서 빼서 추가 취소로 과다 반환되지 않게 함, 승인액 초과여도 기록하고 초과 표시), (3) 두 번째 거래 없음.
  hold의 효과는 **해당 Payment의 추가 취소 차단**(가맹점), 해제는 2인 승인 + 재대사 MATCH. 이유 — 실제 반환이 있었을 수 있는 금액을 다시 반환 가능으로 두면
  이중 반환 위험이 있고(K-14·K-16), 늦은/상충 금융 결과의 기존 처리와 일관된다.
- 대안: (b) 관찰·Case만 남기고 hold·FORCED 없음(대사에 맡김 — 그 사이 추가 취소로 과다 반환 가능). (c) 늦은 취소 성공을 자동으로 COMPLETED로 바꾸고 거래 생성
  (확정 금융 사실 append-only·자동 Recovery/Adjustment 구분과 충돌 가능).
- 영향: 새 hold reason(예: `CANCEL_RESULT_CONFLICT`)과 그 차단 의미(추가 승인이 아니라 추가 취소), RECON Case 유형, 고객 반환 지연.
- 막히는 작업(당시): CR-09~CR-12, FORCED 예약 상태, Q-CANCEL-01과 결합된 hold 행. → D-CANCEL-006으로 해소.

### Q-CANCEL-07 계좌환불(Refund) v1 범위 — 답변됨(D-CANCEL-007)

- 질문: v1에서 계좌환불(카드 취소가 아닌 계좌 송금 반환)을 지원하는가.
- 권장안: **v1 범위 밖**(카드 결제 반환은 카드 승인취소만, API·운영 안내에 미지원 명시). 이유 §6 — 최초 범위 표에 없음, 수취인 계좌·송금·재원 통제 계약이 선행돼야
  함. 기한 경과로 카드 취소가 불가한 건은 PayCore 밖 가맹점 처리로 안내.
- 대안: (b) v1에 포함 — PAYMENT 반환 예약 → FINANCIAL 송금 → PAYMENT 결과 반영 분산 계약·지급계좌 보안(MASTER-PAYOUT-ACCOUNT-001)·은행 Simulator 확장 필요.
  (c) 기한 경과 건만 운영자 수동 계좌환불(Adjustment 성격, 별도 통제).
- 영향: 제품 범위, FINANCIAL·MASTER 작업량, 민감정보 보안 범위.
- 막히는 작업: 없음(권장안이면 2단계는 카드 취소만). 포함이면 별도 계약 작업 등록.

### Q-CANCEL-08 부분취소 수수료/VAT 반환 배분(결정 소유 SETTLEMENT-CONTRACT-001) — 답변됨(D-CANCEL-008)

- 질문: 부분취소 때 원승인 수수료·VAT를 어떻게 반환하는가 — 비례 여부, 정액 수수료 반환 여부, minor unit 배분의 마지막 잔액 처리, 별도 취소비.
- 권장안: **누적 비례 방식**: k번째 취소의 반환 수수료 = round(원 수수료 × 누적 취소액 / 승인액) − 이전 취소들의 반환 수수료 합(VAT도 같은 방식), 반올림은 원 Snapshot의
  방식. 전액이 취소되면 누적 반환 = 원 수수료가 정확히 되어 마지막 잔액은 **마지막 취소가 자연히 흡수**한다. 정액 수수료는 전액 취소 때만 반환, 별도 취소비 없음.
  이유 — 원승인 Snapshot 원칙(K-7) 안에서 반올림 누적 오차가 0으로 닫히고 순서와 무관하게 재현된다. 실제 반환 비율·정액 반환 여부는 가맹점 계약 정책이라 사용자 결정이
  필요(K-10).
- 대안: (b) 건별 비례 반올림 + 마지막 취소가 잔액 흡수(명시적 잔액 규칙). (c) 부분취소는 수수료 미반환, 전액 취소 때만 반환. (d) 별도 취소비 부과.
- 영향: 가맹점 정산 금액, FINANCIAL 계산·대사, 계약서 문구.
- 막히는 작업: SETTLEMENT-CONTRACT-001·SETTLEMENT-001. **이 계약 2단계는 막지 않는다**(CC-08로 필요한 사실을 이벤트에 넣는다). Coordinator 판단으로 SETTLEMENT-CONTRACT-001
  착수 때 질문해도 된다.

### 검토했으나 질문으로 올리지 않은 것

| ID | 항목 | 이유(근거) |
|---|---|---|
| N-1 | 일반 할부의 부분취소 허용 | 범위 §1 "기관 지원 범위 내"(D-AUTH-001 채택) → 기관 프로파일 Capability(§4.2). PG 자체 금지 규칙을 새로 만들지 않음 |
| N-2 | UNKNOWN 승인의 망취소(자동/운영자 반환 요청) | K-14 "UNKNOWN은 원 Attempt 결과 조회/대사 우선"·D-AUTH-002 "자동 보상취소 없음"·AUTH §7 → 결과 확정 전 취소 불가(§4.8). 승인 결과가 AUTHORIZED로 확정되면 일반 취소. Coordinator가 다르게 보면 질문으로 올린다 |
| N-3 | MID 중지·계약 철회 중 취소 | 허용 §4.3·D-MD-006 확정 |
| N-4 | 정산 거래일 = 취소 완료일, 원승인 수수료 Snapshot, 일 한도 미복원, 전액 취소 후 재승인 금지 | K-7·K-8·K-11·K-12 확정 |
| N-5 | 취소 완료일의 시간대·기관 거래일 출처 | 기존 Q-CALENDAR(SETTLEMENT-CONTRACT-001) 범위 — 새 질문 대신 연결 |
| N-6 | 매입 전/후 취소 구분 | PAYMENT는 매입 상태를 모름(INV-02) → FINANCIAL·기관 규약(§4.9, ACQUIRING-001) |
| N-7 | 같은 키 재요청 응답 의미 | D-AUTH-004 선례 적용(CC-05) |

---

## 14. 범위 관찰(Coordinator 확인)

**Coordinator 판정(rev 1.1)**: (1) 새 작업 **CANCEL-001** 등록(AUTH-001 뒤, API·영속·Simulator 취소·UNKNOWN 회복·운영자 보정 Use Case 경계), AUTH-001 범위 유지.
(3) 선지급 대 반환 예약 → SETTLEMENT-CONTRACT-001 AC에 추가됨. (5)(6) RECON·CP-QUERY·WEBHOOK 연결은 아래 인계 메모로 유지. 나머지 항목은 기술대로.

1. **취소 구현 작업 소유**: AUTH-001 제목이 "가상 TCP 승인·취소·조회 구현"이다. 취소 API·Use Case·migration·가상 VAN 취소·Adapter를 AUTH-001에 넣을지 새 작업
   (예: CANCEL-001, dependsOn AUTH-001·CANCEL-CONTRACT-001)으로 나눌지 결정 필요. 취소는 `payment_authorization` 영속(AUTH-001)에 의존한다.
2. **2단계 허용 경로**: tasks.json allowedWritePaths로 충분하다(domain main/test, ArchitectureSpec, archfixture, 문서 4개, commands.json). application·adapter·
   migration은 포함하지 않는다.
3. **선지급과 반환 예약**: 승인 완료 기준 선지급에서 진행 중 반환 예약을 빼야 하는지·예약/해제 이벤트가 필요한지 → SETTLEMENT-CONTRACT-001(대안 §4는 제안 이력).
4. **매입 전/후 취소 처리** → ACQUIRING-001(원 SALE 불명확 시 취소 매입 HOLD는 확정).
5. **늦은 승인 거래 기록(hold 유지 중, D-CANCEL-010·011)**과 보정 취소 참조 → RECON-CONTRACT-001 AC(Coordinator 기록됨): 보정 취소는 CANCEL-CONTRACT-001 경로, DOUBLE 해제가 그 취소를 참조.
6. **조회·Webhook**: CP-QUERY-001(GetPayment에 반환 요약·취소 이력), WEBHOOK-001(`PaymentCancelled`·`PaymentCancelFailed` 공개 계약).
   (2026-10-22 교차 참조) WEBHOOK-001 구현: `payment.cancelled`(`cancelTransactionId` = PayCore transactionId, 운영자 취소는 `initiator: OPERATOR`만 — D-WH-001)·`payment.cancel_failed`,
   P1 Payment의 운영자 취소·취소 실패는 보내지 않음(D-CPQ-002 (ii)). 명세 `docs/repository/WEBHOOK-001-MERCHANT-WEBHOOK.md` §4.
7. **운영자 취소 화면**: ADMIN-BOOT-001(D-CANCEL-005 — 2인 승인 요청 ID·승인 대상 fingerprint 발급, 양쪽 검사).
8. **REQUESTED Payment의 가맹점 중단 요청**: 카드 취소가 아니며(금융 효과 없음) 이 계약 밖. 필요하면 결제창 만료와 같은 RequestedPaymentClosing 계열 별도 작업.
9. **DOMAIN-DEFINITION G-5**: 2단계 T7에서 Cancellation 행을 "규칙 확정(이 계약, D-CANCEL-001~008)"로, Refund 행을 D-CANCEL-007(v1 범위 밖)에 맞춰 갱신.

---

### 14.1 인계 메모(review m-1·m-6·m-7)

- **CANCEL-001**: §9.2 제약 전부(UNIQUE(payment_id, merchant_cancel_id)·UNIQUE(approval_id)·진행 중 1건 생성 컬럼 UNIQUE·CANCEL 거래 UNIQUE(cancellation_id)·return_balance
  forced 별도 컬럼과 CHECK), `incident_hold` reason CHECK에 CANCEL_RESULT_CONFLICT 추가, TX2·회복 잠금은 control부터, 이벤트 effect_key `{transactionId}` vs `{cancellationId}` 결정,
  PaymentCancelled payload(authorizedAmount·returnStatusAfter·순번·누적), ReturnBalance revision 영속.
- **RECON-CONTRACT-001**: CANCEL_RESULT_CONFLICT hold의 해제 판정 종류와 FORCED 출구(실제 반환 확인 → CANCEL 거래 생성(Adjustment 계열)·CONSUMED, 무효과 확인 → RELEASED),
  FORCED 초과(excess) Case CRITICAL 처리. **D-CANCEL-010·011·012**: hold 유지 중 P1 확정 승인의 거래 기록 Use Case(domain 전이 `recordLateApprovalByReconciliation`을
  감싸는 RECON domain 서비스 + 같은 TX의 PaymentAuthorized — D-CANCEL-012 예외), 그 뒤 운영자 보정 취소 → DOUBLE 해제가 그 취소를 `correctionRef`로 참조. P1 Payment는
  FAILED/EXPIRED 유지(D-CANCEL-011). 취소 사건 hold 해제는 그 전까지 거절(fail-closed).
- **RECON-CONTRACT-001 — 사건 처리된 진행 중 취소의 출구(review m-3)**: 상충으로 사건이 기록된 OPEN/UNKNOWN 취소는 UNKNOWN + RESERVED로 남고 이후 관찰은 NoChange다(CC-16).
  그동안 D-CANCEL-003(진행 중 1건)이 **운영자 보정을 포함한 모든 다음 취소**를 막는다. RECON이 결과를 확정(→ CANCEL 거래 + CONSUMED, 또는 FAILED + RELEASED)하는 Use Case가 필요하다.
- **RECON-CONTRACT-001 — `recordLateApprovalByReconciliation` 호출자 요건(review m-4, D-CANCEL-013으로 경로별 구분)**: 호출 domain 서비스가 강제해야 한다.
  - **이중 승인 경로(D-CANCEL-010·011)**: (1) P1에 활성 SUCCESS_AFTER_TERMINATION hold, (2) 주문에 다른 승인 성공 Payment(P2) 존재, (3) 재대사 증거 참조를 필수로 받아 거래와
    함께 저장, (4) 같은 PAYMENT TX에서 PaymentAuthorized 발행 — 이 경로에서만 D-CANCEL-012 예외(hold 중 발행)다.
  - **단일 늦은 승인 REAL 경로(D-CANCEL-013)**: hold는 이미 REAL_APPROVAL_CONFIRMED로 해제됨, `authorizedPaymentId` = P1, 재대사 증거 참조는 여전히 필수, PaymentAuthorized는
    기록과 함께 **해제 뒤 정상 경로**로 발행(D-CANCEL-012 예외 아님), Payment는 FAILED 유지, 반환은 운영자 보정 취소(Case 연결·2인 승인)만 — 가맹점 취소 경로 없음.
  - domain 전이는 지금 FAILED·거래 없음·기록된 관찰 일치만 검사한다.
- **AUTH-001 / RECON-CONTRACT-001 — 거래가 기록된 FAILED 승인의 같은 조건 승인(nit 3)**: 지금 `AuthorizationResolution`(87–95행)은 거래가 이미 기록된 FAILED 승인에 새 관찰 키의
  같은 조건 APPROVED가 오면 새 hold를 연다. D-AUTH-013은 ProviderTransactionId가 다를 때만 새 hold를 요구한다 — 기록된 거래와 같은 승인(PTX·승인번호·기관 거래일 일치)이면
  NoChange로 할지 정해 고친다(이 작업은 코드 불변).
- **CANCEL-001 / AUTH-001 — 영속(nit 4)**: `PaymentCancellation.incidentRecorded`를 영속·복원한다. `PaymentAuthorization` reconstitute(AUTH-001)는 FAILED + 거래(D-CANCEL-011)를
  받아들여야 한다.
- **ADMIN 연동**: 2인 승인 요청 ID 발급·승인 대상 fingerprint(`oct1:`) 고정·역할 검사.

## 15. 8대 원칙별 완료 증거 계획

| 원칙 | 적용 | 2단계(domain) 증거(최종 — 시험 이름은 Spec·행 ID) | 후속 구현(실DB·Simulator) 증거 |
|---|---|---|---|
| Financial Consistency | 적용 | `ReturnBalanceSpec` CB-01(순번·누적)·CB-04(USD)·CB-07(H-1 FORCED 초과)·CB-06/CB-09 복원 검사, `CancelStartSpec` CX-04·05·06·23, `CancellationResolutionSpec` CR-01·02·12와 D-CANCEL-009 행("the last PARTIAL that reaches the approved amount …"), `PaymentTransactionSpec`, 변이 M1·M5·M7·M12·M14 | 거래·이벤트 1:1, DB CHECK(completed + reserved ≤ authorized)·append-only 권한 |
| Transaction Safety | 적용 | domain 원자성 — 거절은 모든 Aggregate 불변(CX-04·12·13·17), Resolution은 어긋난 입력에 관찰 기록 전 예외("inputs that do not belong together") | 실DB rollback·flush 뒤 실패 주입·외부 호출 중 TX 없음·응답 유실(CANCEL-001) |
| Idempotency | 적용 | CX-16·19(업무 키), m-B 행 CX-24("a business key looked up for another payment"), CR-08(중복 관찰), consume/release/reoccupy 멱등(`ReturnBalanceSpec`), 변이 M9 | 같은 키/다른 키/다른 내용, 기관 취소 요청 ID 재전송, 30일 재생, 재시작(CANCEL-001) |
| Concurrency Control | 해당 없음(NOT_APPLICABLE) — 순수 domain | DB가 지킬 불변식(진행 중 1건 CX-13·CB-03, 합계 INV-C1, fencing 전이 `PaymentCancellationSpec`)만 시험 | `return_balance` 행 잠금 하 동시 취소, 전액취소 2건 동시, 전송 vs 회복 fencing(CANCEL-001) |
| Recoverability | 적용 | CR-06·07(자동 FAILED 없음), 변이 M15, PREPARED 처리 행, CR-05(전송 전 포기만 PRE_TRANSMISSION) | 응답 유실·TX2 장애·재시작 → 원 취소 Attempt 조회 1건 확정, 대사 이관(CANCEL-001) |
| Reconciliation | 적용(경계) | CR-09~12(상충·늦은 성공 → hold·FORCED, 변이 M13·M16), CB-07, 취소 hold 해제 fail-closed(CX-21), CX-22·27(D-CANCEL-010·011 보정 경로), P1 사건 행(review m-1, 변이 M21) | `CancellationIncidentDetected`·`Unresolved` Outbox 원자성, Case·MATCH·FORCED 출구(RECON-001) |
| Auditability | 적용 | 개시자·approvalId·목적·승인 대상 fingerprint(CX-17·25, 변이 M8·M3·M6), 원 거래 ID·정책/권리 버전(CX-11·CB-08) | 행위자·관찰 출처·시각 저장, 감사 저장 실패 처리(CANCEL-001) |
| Observability | 해당 없음(NOT_APPLICABLE) — adapter·지표 코드 없음 | — | 취소 UNKNOWN 수·최고 연령, 조회 예산 소진, 취소 사건 hold 수(거래 ID label 금지), 로그에 카드·Secret 없음(CANCEL-001) |

---

## 16. JPA·jOOQ·BOM·Kafka/Redis(이번 변경과의 연결)

- **1단계·2단계에는 JPA 코드가 없다.** 아래는 후속 구현 작업에서 이 계약을 영속화할 때의 선택을 미리 연결한 것이며 그때 실제 코드와 함께 다시 설명한다(원칙 §3
  "처음 나올 때 설명" — 같은 설명 반복 금지).
  - **Entity/Domain 분리**: 역할 — `JpaPaymentCancellationEntity`·`JpaCancelAttemptEntity`·`JpaCancelObservationEntity`·`JpaReturnBalanceEntity`가 행 모양만,
    Mapper가 순수 domain과 변환. 예 — CANCEL 거래·관찰은 `@Immutable` insert-only Entity(`@OneToMany(cascade)` 없음). 이유 — cascade·orphanRemoval이 append-only
    금융 사실을 지우는 경로를 구조적으로 없앤다(관례 §9.1). 주의 — 분리된 domain 객체 변경은 영속성 컨텍스트가 추적하지 않으므로 Mapper가 상태·목록·version을
    함께 옮기고 stale 객체가 최신 행을 덮지 않게 한다.
  - **비관적 잠금(`PESSIMISTIC_WRITE`)**: 역할 — `return_balance` 행 `FOR UPDATE`. 예 — 같은 Payment에 3,000원·8,000원 취소가 동시에 오면 둘이 이 행에서 줄을
    서고 두 번째는 잔액 7,000원을 보고 거절된다. 이유 — 서로 다른 취소 operation 행 사이의 합계 불변식은 행별 `@Version`으로 못 지킨다. 주의 — 고정 잠금 순서(§9.1),
    lock wait timeout 분류, 기관 호출 동안 잠금 없음.
  - **`@Version`과 fencing**: 역할 — 취소 Attempt PREPARED→DISPATCHING과 회복의 PREPARED→ABANDONED가 같은 행 version 조건부 UPDATE. 이유 — "전송 전 실패
    확정"(예약 해제 근거)을 DB가 증명. 주의 — 충돌 뒤 무조건 재시도하지 않고 다시 읽어 판정.
  - **flush vs commit**: TX2의 관찰·상태·예약·거래·Outbox를 flush해 제약 위반을 TX 안에서 드러내고, commit 단계 실패는 결과 불명확(같은 키·원 취소 Attempt 조회로 확인).
- **jOOQ(CQRS)**: 취소 이력·반환 요약 조회(GetPayment)·취소 UNKNOWN 연령 목록·회복 후보 keyset은 jOOQ 조회 전용 adapter(`CancellationReader`, 쓰기 금지 ArchUnit 기존).
  조회 결과는 비권위 — 취소 가능 판정은 JPA 잠금 재판정. 시각 비교는 TZ-FIX-001의 UTC 리터럴 관례(관례 §13).
- **BOM**: 2단계 새 의존성 없음(Boot 4.1.1 BOM, Kotlin 2.3.21, Fixture Monkey 1.2.3 — `gradle/libs.versions.toml`). 개별 버전 override 없음.
- **Kafka/Redis**: 도입하지 않는다(ADR-001). 반환 가능 금액·취소 멱등·hold의 권위는 PAYMENT DB 제약·잠금·로컬 TX다. Redis 분산 lock·카운터나 Kafka 트랜잭션이
  이중 취소 차단·반환 합계 정합성·기관 exactly-once를 대신한다고 주장하지 않는다(원칙 §5).

---

## 17. 2단계 실행 기록(T1~T4 checkpoint, 구현자 보고 — Verifier/Reviewer 판정 아님)

### 17.1 변경 파일(allowedWritePaths 안, 빌드 파일·의존성·JPA·migration 없음)

| 구분 | 파일(`apps/payment-api/src/…/payment/payment/domain/` 기준) |
|---|---|
| main 새 파일 | `CancelAttempt.kt`, `CancelCapability.kt`(CancelCapability·Resolution), `CancelObservation.kt`(CancelConfirmedTerms·CancelObservationKey·CancelObservation), `CancelStart.kt`(command·CancelStartRejection·result·CancelStart), `MerchantCancelId.kt`, `OperatorCancelTarget.kt`, `PaymentCancellation.kt`(CancellationStatus·CancelScope·OperatorCancelPurpose·CancelRequestKey·CancelInitiator·CancelRequestMatch·PaymentCancellation), `ReturnBalance.kt`(ReturnReservationState·ReturnExcess·ReturnStatus·ReturnReservation·ReturnCompletion·ReturnBalance) |
| main 수정 | `PaymentTransaction.kt`(sealed interface + `AuthorizationTransaction` + `CancelTransaction`, type CANCEL), `PaymentAuthorization.kt`·`AuthorizationResolution.kt`(`AuthorizationTransaction` 타입으로 — 동작 불변), `AuthorizationIds.kt`(CancellationId·CancelAttemptId·OperatorApprovalId — 같은 private helper 공유, 관례 §14), `PaymentDomainException.kt`(InvalidCancellationTransitionException), `IncidentHold.kt`(CANCEL_RESULT_CONFLICT), `OrderPaymentControl.kt`(`reportCancellationResultConflict`, 그 사유 해제 거절) |
| test 새 파일 | `CancellationFixture.kt`, `PaymentTransactionSpec.kt`(5), `ReturnBalanceSpec.kt`(18), `PaymentCancellationSpec.kt`(18), `CancelStartSpec.kt`(25) |
| test 수정 | `archfixture/transition/DirectAuthorizationTransitions.kt`(인자 타입 `PaymentTransaction` → `AuthorizationTransaction`, 위반 대상 불변) |
| 문서 | 이 명세(rev 1.1 → 1.2) |

### 17.2 RED

- **RED-C(시험 선행, 컴파일)**: `CancellationFixture`·T1~T4 Spec 4개를 main 타입보다 먼저 작성 → `./gradlew :apps:payment-api:compileTestKotlin` **exit 1**, `e:` 426건(`CancelStart` 27·`ReturnReservationState` 21·`ReturnReservation` 19·`reserve` 17 … Unresolved reference), 2026-10-17T07:17:13Z.
- **review H-1·H-2·m-3·m-4 반영분은 시험 선행이 아니다**: 첫 구현을 쓴 뒤 review가 와서 `ReturnBalance`(FORCED 별도 합·excess·revision·완료 순번)와 `CancelInitiator`(Operator·approvalId·목적·승인 대상)를 바꾸고 시험을 같이 고쳤다. 그래서 아래 **변이 RED**로 시험이 해당 규칙을 실제로 잡는지 확인했다.
- **RED-A(실행, 시험 결함 발견)**: 첫 실행에서 CX-12 "다른 Payment의 잔액" 행이 실패 — 두 fixture(`partiallyCancelled`·`authorized`)가 같은 Demo ID(demo-pay-0001·demo-auth-0001)라 불일치가 관찰되지 않았다. 기대값은 그대로 두고 다른 ID를 갖는 2번째 generation fixture(`authorizedSecondGeneration`)로 바꿨다(기대값 완화 아님).
- **변이 RED**(`/tmp/mut_cancel.sh`: main 한 곳 변이 → 대상 Spec → 원복, `cmp` 원복 확인, 2026-10-17T07:27Z):

| 변이 | 대상 Spec | 결과 |
|---|---|---|
| M1 FORCED를 기록하지 않음(`reoccupyAfterLateCancel` 즉시 return) | ReturnBalanceSpec + CancelStartSpec | exit 1, 43건 중 5 실패 |
| M2 같은 Payment hold 우회(가맹점 hold 검사 제거) | CancelStartSpec | exit 1, 25건 중 2 실패 |
| M3 요청자 = 승인자 허용 | CancelStartSpec + PaymentCancellationSpec | exit 1, 43건 중 2 실패 |
| M4 CANCEL_IN_PROGRESS 검사 제거 | CancelStartSpec | exit 1, 25건 중 1 실패 |
| M5 cancellable에서 FORCED 제외(H-1 회귀) | ReturnBalanceSpec + CancelStartSpec | exit 1, 43건 중 2 실패 |
| M6 운영자 승인 대상 stale 검사 제거 | CancelStartSpec | exit 1, 25건 중 1 실패 |

review m-5의 "CR-12 두 번째 CANCEL 거래" 변이는 CancellationResolution(T5) 구현 뒤 실행한다.

### 17.3 명세와 다른 점·구현 결정

1. `NonNegativeMinor` 타입을 만들지 않았다 — `ReturnBalance.cancellableMinor`·`excessMinor`가 0 이상 `Long`(내부 계산 결과만, 외부 입력 아님).
2. `CancelConfirmedTerms`는 기관 취소 거래일 하나(필수)만 갖는다 — 승인 `ApprovedTerms`와 같은 모양. 기관 업무일이 필요하면 CANCEL-001 Adapter 계약에서 추가한다.
3. CX-15(신규 승인 권리 불요)는 실행 시험이 아니라 `CancelStartCommand`에 eligibility·한도 입력이 없다는 구조로 고정했다.
4. T4 시점 `CancellationFixture.completed/declined/lateCancelSuccessAfterDecline`은 domain 내부 전이(모듈 internal — 같은 모듈 시험 상태 준비, AUTH `recordAuthorized` 선례)로 상태를 만든다. T5에서 `CancellationResolution` 경로로 바꾼다.
5. 운영자 2인 승인 입력의 결함(같은 사람·사유·근거 없음)은 생성 예외가 아니라 `CancelStart`의 업무 거절이다(D-ADMIN-007 소유 서비스 재검사, review m-3). `PaymentCancellation.open`은 방어적으로 다시 거부한다.
6. `ReturnBalance.revision`은 업무 변경(예약·소비·해제·FORCED)마다 +1 — 운영자 승인 대상 버전(CC-13). JPA `@Version`과 별개로 영속한다(CANCEL-001).
7. `CancelStart`의 원 경로 검사는 확정(RESOLVED) 승인 Attempt 하나의 기관·환경·기관 계정과 비교한다(CA-13 dispatch Attempt 1개 전제).
8. T6 ArchUnit(`PaymentCancellation.open`·`ReturnBalance.reserve`는 CancelStart만, 취소 내부 전이는 domain만)은 아직 없다 — T6에서 추가.

### 17.4 실행 결과(구현자 실행)

- 환경: WSL2 Ubuntu, Docker 29.8.0(Testcontainers MySQL), 기준 `50db007` + 이 작업 미커밋 변경.
- `./gradlew --stop` exit 0 → (test-results 비움) `./gradlew :apps:payment-api:test` **exit 0**(2026-10-17T07:31:29Z → 07:37:13Z), JUnit XML 101파일 **1034건, 실패 0·오류 0·skip 0**
  (새 Spec 66건: PaymentTransactionSpec 5·ReturnBalanceSpec 18·PaymentCancellationSpec 18·CancelStartSpec 25).
- `./gradlew :apps:payment-api:check` **exit 0**(07:37:20Z), verifyNoInstancio, verifyRuntimeHasNoTestTools: runtime 119 모듈에 시험 도구 없음.
- `python3 -B -m unittest discover -s harness/tests`: **81건 OK**, exit 0.
- 기존 시험 기대값 변경 없음. `harness/commands.json` 하한은 아직 바꾸지 않았다(2단계 끝에 늘린 수만큼만 올릴 예정 — 완화 아님). VERIFY-GATE는 실행하지 않았다(Coordinator 지시).

### 17.5 T5~T7 + 중간 코드 review + D-CANCEL-009~012 실행 기록(구현자 보고 — Verifier/Reviewer 판정 아님)

**추가·변경 파일(allowedWritePaths 안, 빌드 파일·의존성·JPA·migration 없음)**: main 새 `CancellationResolution.kt`; main 수정 `PaymentCancellation.kt`(incidentRecorded·
`compareRequest(paymentId, …)`·`CANCEL_OPERATION`), `OperatorCancelTarget.kt`(승인 주체·목적 포함), `CancelStart.kt`(m-A·m-C·D-CANCEL-011 관문), `ReturnBalance.kt`(n-2 복원 검사,
open 조건 = 거래 있음), `OrderPaymentControl.kt`(취소 사건 hold 대상 AUTHORIZED·FAILED·EXPIRED), `PaymentAuthorization.kt`(`recordLateApprovalByReconciliation` — RECON 인계);
test 새 `CancellationResolutionSpec.kt`(21), archfixture `cancelstart/{DirectCancellationOpen, DirectReturnReservation, DirectReturnBalanceOpen, DirectReturnBalanceReconstitute,
CancelStartUsingLimit}`, `cancelresolution/DirectCancelHoldReport`, `transition/{DirectCancellationTransitions, DirectReturnBalanceTransitions, DirectLateApprovalRecording}`;
test 수정 `CancellationFixture`(완료·거절·늦은 성공을 `CancellationResolution` 경로로 — review 편차 4, `authorizedSecondGeneration`·`lateConfirmedFirstGeneration`·m-C 지문),
`CancelStartSpec`(CX-22~27), `PaymentCancellationSpec`(m-B·n-3), `ReturnBalanceSpec`(n-2), `ArchitectureSpec`(T6 규칙 4개 + 기존 전이 규칙 대상 추가);
문서 `DOMAIN-DEFINITION.md`·`ARCHITECTURE-CONVENTIONS.md` §15·`AUTH-CONTRACT-001` §8.1·§8.2·§18-6.

**RED**:
- RED-C T5/T6(2026-10-17T09:05:23Z): `CancellationResolutionSpec`·fixture 전환·ArchUnit 규칙 시험을 main보다 먼저 → `compileTestKotlin` exit 1, `e:` 66건(`CancelResolutionOutcome` 33 …).
- RED-C 중간 review 행(09:08:48Z): `compareRequest(paymentId, …)`·`CANCEL_OPERATION`·`OperatorCancelTarget` 새 인자 → exit 1, `e:` 35건.
- RED-C D-CANCEL-011(09:26:27Z): `recordLateApprovalByReconciliation` → exit 1, `e:` 5건.
- 행동 행은 컴파일 RED 직후 main과 함께 녹색이 되어 **변이 RED로 증명**했다(`/tmp/mut2_cancel.py`·`/tmp/mut3_cancel.py`, 변이 → 대상 Spec → 원복·`cmp`, 남은 `.mutbak` 0):

| 변이 | 결과 |
|---|---|
| M7 cancellable 0이면 ALREADY_FULLY_CANCELLED(m-A 회귀) | exit 1, 28건 중 1 실패 |
| M8 지문에서 승인 주체·목적 제외(m-C 회귀) | exit 1, 28건 중 1 실패 |
| M9 compareRequest가 paymentId 무시(m-B 회귀) | exit 1, 20건 중 1 실패 |
| M10 취소 operation 아닌 요청 허용(n-3 회귀) | exit 1, 20건 중 1 실패 |
| M11 revision을 행 수로만 검사(n-2 회귀) | exit 1, 19건 중 1 실패 |
| **M12 CR-12 COMPLETED에 두 번째 CANCEL 거래 생성**(m-5) | exit 1, 21건 중 1 실패 |
| M13 상충이 hold를 열지 않음(m-11) | exit 1, 49건 중 8 실패 |
| M14 늦은 성공이 FORCED를 기록하지 않음 | exit 1, 68건 중 5 실패 |
| M15 NOT_FOUND로 취소 FAILED | exit 1, 21건 중 3 실패 |
| M16 사건 처리된 취소가 자동 완료 | exit 1, 21건 중 1 실패 |
| M17 운영자 전용 제한 제거(D-CANCEL-011) | exit 1, 31건 중 1 실패 |
| M18 옛 관문(모두 AUTHORIZED 요구) | exit 1, 31건 중 1 실패 |
| M19 기록 전이를 FAILED 아닌 승인에도 허용 | 첫 실행 **생존** → 시험 결함(그 가드를 다른 가드가 가림): UNKNOWN 승인·관찰 없는 기록 행 추가 후 exit 1, 31건 중 1 실패 |
| M20 기록 전이의 관찰 일치 검사 제거 | exit 1, 31건 중 1 실패 |

**명세와 다른 점·구현 결정(추가)**:
1. 중간 review 편차 3(구조 고정)은 ArchUnit `cancelStartIndependentOfLimitAndEntitlement`로 바꿨다(CX-15).
2. `returnBalanceRestoredOnlyByPersistence`는 main에 복원 호출자가 아직 없어 대상 없음으로 통과한다 — 규칙이 실제로 평가됨은 위반 fixture로 확인(복원 Mapper는 CANCEL-001).
3. `recordLateApprovalByReconciliation`은 main 호출자가 없다(RECON 인계). 시험 fixture의 상태 준비와 ArchUnit 위반 fixture만 쓴다.
4. 사건 처리된 취소 이후 결과는 기록만(CC-16). 같은 Payment의 다른 취소(운영자 보정)는 hold와 무관하게 결과를 반영한다 — 시험 "a hold opened by one cancellation does not stop …".

**실행 결과(구현자 실행)**:
- 2026-10-17T09:12:37Z 시작한 **저장소 전체 `check`(--no-build-cache --rerun-tasks)는 Claude Code가 시스템 메모리 부족으로 백그라운드 작업을 중지**했다(빌드 실패 아님). WSL 안의
  Gradle은 고아로 계속 돌았고 D-CANCEL-011 시험 편집 전 컴파일본이라 수가 낡아 직접 종료했다. Coordinator 지시대로 반복 재시도하지 않았다 — 전체 수는 VERIFY-GATE로 확인 필요.
- 대신 `./gradlew --stop` exit 0 → (test-results 비움) `./gradlew :apps:payment-api:test` **exit 0**(09:30:22Z → 09:36:21Z), JUnit XML 102파일 **1067건, 실패 0·오류 0·skip 0**
  (작업 전 968 → +99: PaymentTransactionSpec 5·ReturnBalanceSpec 19·PaymentCancellationSpec 20·CancelStartSpec 31·CancellationResolutionSpec 21·ArchitectureSpec +3) →
  `./gradlew :apps:payment-api:check` **exit 0**(09:36:27Z, verifyNoInstancio, verifyRuntimeHasNoTestTools 119 모듈) → HARNESS-SELFTEST **81건 OK**.
- 다른 모듈은 이 작업이 바꾸지 않았다(실행하지 않음). `harness/commands.json` minTests는 전체 `check` 측정값이 없어 **바꾸지 않았다** — 늘어난 수는 payment-api +99(다른 모듈 불변 전제).
- 기존 시험 기대값 변경: 없음. 기존 ArchitectureSpec 전이 규칙의 대상 목록에 취소 전이·`recordLateApprovalByReconciliation`을 **추가**(새 fixture 행 추가, 기존 fixture 기대 불변).

### 17.6 최종 review(REVIEW_PASS_WITH_FIXES) 수정 기록

- **m-1**: `CancellationResolutionSpec` "the operator correction cancel of the late-confirmed P1" 2행(2,000 성공 → UNKNOWN·RESERVED + P1 CANCEL_RESULT_CONFLICT hold, 거절 뒤 늦은 성공 → FORCED +
  hold, P1 FAILED 유지). 현재 코드로 녹색이라 **변이 RED M21**(`reportCancellationResultConflict`를 AUTHORIZED 전용으로 되돌림) → exit 1, 23건 중 2 실패, 원복·`.mutbak` 0.
- **m-2**: `PaymentCancellation.recordIncident`를 `domainTransitionsOnlyInsideDomain` 대상과 `DirectCancellationTransitions`에 추가. 시험(대상 목록·fixture)을 먼저 바꿔 **RED-A**
  (2026-10-17T09:47:57Z, ArchitectureSpec 전이 규칙 1 실패, exit 1) → 규칙 대상 추가 뒤 녹색.
- **m-3·m-4·nit 3·nit 4**: §14.1 인계 항목. **nit 1**: §2.2·§4.10·§14 문구를 "hold 유지 중 거래 기록"으로. **nit 2**: AUTH-CONTRACT §18-6 "D-CANCEL-001~012". **nit 5**: §15 최종 증거표.
- **D-1(D-CANCEL-013, 2026-10-18)**: 문서만 변경 — §4.8·§4.10에 D-CANCEL-013, §14.1 호출자 요건을 이중 승인 경로(D-CANCEL-010·011·012)와 단일 REAL 경로(D-CANCEL-013)로 나눔.
  코드·시험 변경 없음(재실행하지 않음 — 게이트가 확인).

## 18. CANCEL-001 구현 편차 표시(2026-10-20 — 구현자 기록, Reviewer 확인 대상)

CANCEL-001(`docs/repository/CANCEL-001-CANCELLATION.md`)이 이 계약을 실행 구조로 구현하면서 계약 문장과 다르게 하거나 보탠 곳이다. 업무 규칙(D-CANCEL-001~013)은 바꾸지 않았다. 사용자 결정으로 계약을
구체화한 곳(D-CANCEL1-001~008)은 결정이 우선하며 아래에 위치만 표시한다. 표의 "방향"은 fail-closed(더 보수적)·추가(기존 의미 불변)·구체화(결정 반영)·미구현(허용된 예외를 쓰지 않음) 중 하나다.

| 계약 위치 | 구현 | 방향 | 근거 |
|---|---|---|---|
| §9.3 `CancellationIncidentDetected` effect_key `:{occurrence}` | `:{cancellationId}:{incidentSeq}` — 사건마다 이벤트 하나(새 hold가 없어도), `incident_count` 영속 | 구체화 | D-CANCEL1-002 |
| §9.3 같은 표 RECON Case 힌트("승인액 초과 가능 시 CRITICAL") | payload `riskHint` = 이 취소의 반환 예약이 `ReturnExcess.EXCEEDED`일 때만 CRITICAL, 아니면 HIGH. payload에 `excessAmount`(정수 minor, 이 Payment의 누적 초과 — 초과가 아니면 0) **필드 추가** | 추가(필드)·구체화 | CANCEL-001 검토 #3 n-4, 소비자 없음 |
| §4.6·CR 표 사건 동일성 | 사건이 이미 있는 진행 중 취소의 새 상충 확정 관찰도 사건 +1, 같은 기관 결정(조건까지 같음)의 다른 출처 재수신은 같은 사건 | 구체화 | D-CANCEL1-005·007, CN-31 |
| §4.7 K-14 같은 ID 재전송 예외 | 구현하지 않음 — 회복은 원 취소 요청 ID 조회만 | 미구현(보수) | CANCEL-001 CN-27, D-CANCEL1-003 |
| §4.7 이관 뒤 | 이관된 취소 UNKNOWN에 임시 출구 없음(상태·예약 유지, 이관 뒤 느린 조회 → 보존기간 경과 또는 Route 없음·최대 연령 경과 시 조회 중지). 확정은 RECON | 구체화 | D-CANCEL1-003(LIVE Gate 조건), 검토 #3 n-6 |
| §4.7 무효과 종결 | 전송 때 Attempt에 고정한 Route 프로파일 버전 = 현재 등록부 버전일 때만 종결 요청, 다르거나 NULL(보내지 않은 Attempt 포함)이면 조회·이관만 | 구체화 | D-CANCEL1-004, 검토 #3 m-1 |
| §4.11·CC-12 운영자 업무 키(같은 승인 = 기존 operation) | 같은 approvalId·같은 Payment·같은 금액이어도 **요청자·승인자가 다르면** 같은 요청이 아니다 → `APPROVAL_IDENTITY_MISMATCH` 거절 + 감사 행(기존 결과를 돌려주지 않음). 판정은 비잠금 읽기 | fail-closed | CANCEL-001 CN-32(검토 #3 n-3) — Reviewer 확인 요청 |
| §4.11 운영자 명령 거절 | 거절 판정과 같은 로컬 TX에 append-only 감사 행(`operator_cancel_rejection`), 거절 한 번 = 행 하나. 형식이 아닌 paymentId는 자리표시 `MALFORMED`·사유 `PAYMENT_ID_MALFORMED` | 구체화 | D-CANCEL1-006·008, 검토 #3 m-3 |
| §4.11 운영자 보정 진입점 | provided Use Case만, inbound 진입점 없음(ArchUnit) — ADMIN 승인 상태 동기 조회 없음 | 구체화 | D-CANCEL1-001 |
| §7 기관 Port 상관관계 | 원 승인 PTX echo가 다른 취소 승인 응답은 Adapter가 OutcomeUnknown(CORRELATION) — 결정은 원 취소 요청 ID 조회가 가져와 domain이 상충 판정 | fail-closed | CANCEL-001 D-S6-1 |
| §7 가상 기관 원 거래 echo(CANCEL-001 §6.3) | 원 거래일(`originalTransactionDate`) 불일치도 ORIGINAL_MISMATCH | fail-closed | CANCEL-001 D-S3-1 |
| §7 가상 기관 PENDING | 취소 PENDING 시나리오 없음(응답 작성기는 golden 대조용) — PG 판정은 PENDING = UNKNOWN 유지 | 미구현(Demo) | CANCEL-001 CN-23·D-S3-6 |
| §4.5·§8 멱등 저장 | CancelPayment 결과 CHECK·취소 참조 열은 V20(쓰기 경로가 생기는 단계)에, 결과가 있는 행마다 `result_code IS NOT NULL` | 구체화 | CANCEL-001 D-S2-1, 검토 #3 n-1 |
| §6 계좌환불 | v1 범위 밖 — API·운영 안내 문구는 CANCEL-001 §4.11 | 구체화(안내) | D-CANCEL-007, rev 0.3 m-8 |
| §4.7 회복 후보 선정(jOOQ keyset → JPA) | 회복 일정 행을 `FOR UPDATE SKIP LOCKED`로 선점(짧은 TX), 판정은 JPA 잠금 재판정 | 구현 방식(의미 불변) | CANCEL-001 CN-24(AUTH-001 D-S7-1 선례), 최종 review n-2 |
| §9.1 회복은 control부터 잠금 | 이관·조회 중지 TX는 `payment_cancellation` → 일정만 잠근다(잠금 순서의 접미부). 이 TX는 control·hold·예약을 건드리지 않으므로 순서 역전 없음 | 구현 방식(안전) | 최종 review n-2 |
| §8 응답 본문 | ~~가맹점 응답에 `declineCategory`(기관 거절 분류) 추가~~ — **(CP-QUERY-001 F-7 정정, 2026-10-20 Coordinator 판정 11)** 실제 구현은 가맹점 응답에 `declineCategory`를 노출하지 않는다(v1). 고정 거절 분류는 기관 메시지·required Port 결과(`CancellationCallResult.Declined`·`CancelInquiryCallResult.Found`)까지만 있고 영속·조회·응답에는 없다. 응답 본문은 §8 그대로(FAILED 분류 DECLINED·NOT_SENT·NO_EFFECT) | 계약 그대로(추가 없음) | CANCEL-001 CN-02(정정), CP-QUERY-001 §3.2 F-7 |
