# AUTH-CONTRACT-001 가상 승인·조회·UNKNOWN 회복 계약 명세 (1단계 명세 → 2단계 domain 구현 반영)

> **2단계 갱신(2026-10-15)**: §16 질문 7개는 모두 답변됐다(**D-AUTH-003~009**, `docs/DECISIONS.md`). 범위는 Coordinator가 결정했다(S1~S9, B-2·B-3 포함, §17 결함 1~6 처리·7·8은 AUTH-001, 신규 BACKLOG AUTHN-001·MASTER-SNAPSHOT-002·WEBHOOK-001·CANCEL-CONTRACT-001). 2단계 domain 구현과 명세 편차는 §19에 적었다. 아래 1단계 문장의 "미결"·"권장" 표현은 해당 D-AUTH 결정과 §19가 우선한다.

작성 2026-10-15 KST. **1단계 명세이며 코드 변경 없음.** 기준 commit `0aa3c1e`(CP-API-001, push 완료). 작업 트리에는 Coordinator 소유 상태 파일
(`docs/DECISIONS.md`·`harness/tasks.json`·`harness/progress.json`·`harness/questions.json`) 미커밋 변경이 있으며 이 문서는 읽기만 했다.
이 문서는 새 업무 정책을 채택하지 않는다 — §16 질문 후보는 답변 전 해당 운영 동작을 구현하지 않는다. 2단계 TDD(domain 계약·시험)는 Coordinator가
질문을 처리한 뒤 시작한다. 가상 VAN/인증기관 Simulator·TCP Adapter·DB migration·API는 AUTH-001 범위이며 여기서는 경계만 정한다(tasks.json
coordinatorDecisions 2026-10-15).

표시: **확정(출처)** = 채택 문서·D-* 결정·확정 계약, **도출(근거)** = 확정 규칙 둘 이상에서 논리적으로 따라 나오는 규칙(새 정책 아님 — Coordinator가
의심되면 질문으로 올린다), **구현 결정** = 금융 의미가 없는 가역적 선택(§15에 모음, 질문 아님), **미결(Q-AUTH-xx)** = §16 질문 후보,
**Demo** = Simulator·시험 전용 값(운영값 아님), **범위 관찰** = AC 해석·작업 경계에 관한 Coordinator 확인 사항(§18).

입력 문서(sha256 앞 8자…끝 4자, 2026-10-15 읽은 시점):

| 약어 | 문서 | 상태 | sha256 |
|---|---|---|---|
| 결정 | `docs/DECISIONS.md`(D-AUTH-001·002, D-CPD-*, D-CP-*, D-CPC-*, D-MD-016~019, D-MS-*, D-CPA-001) | 확정(미커밋 작업 트리) | `1d58fd5e…e920` |
| 원칙 | `docs/TECHNOLOGY-AND-PRINCIPLES.md` | 사용자 지정 | `a053e150…8c1f` |
| 작업 | `harness/tasks.json` AUTH-CONTRACT-001 AC 7개·coordinatorDecisions, AUTH-001 AC | Coordinator 소유(미커밋) | `b00df122…0c79` |
| 범위 | `docs/baseline/PayCore-최초구현범위-권장안.md` §1~§3(D-AUTH-001로 채택), §4~§6 | §1~§3 확정 | `3cf08d32…11be` |
| 시도 | `docs/baseline/PayCore-결제시도-규칙-권장안.md` | 확정 | `9151261e…f5c1` |
| 허용 | `docs/baseline/PayCore-결제허용-기관연동-권장안.md` §4·§5 | 확정 | `a8b02b77…7ae5` |
| 대안 | `docs/baseline/PayCore-금융통제-관계-자동대사-대안.md` §4 | 대안 이력(정산이 우선) | `18f353eb…4426` |
| 정산 | `docs/baseline/PayCore-Settlement-Control-Policy-v0.1.md` §17~§25 | 확정 | `68ca892b…bd69` |
| CP계약 | `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md` | 확정 계약 v1.0 | `03eecede…ae83` |
| CP-API | `docs/repository/CP-API-001-CREATE-PAYMENT-API.md`(형식·§3.6 L-2·§7 오류) | 구현 반영 명세 | `4469e8e0…78e7` |
| 정의 | `docs/repository/DOMAIN-DEFINITION.md`(G-2·G-4·§4) | 색인 | `f9faee8a…51e8` |
| 관례 | `docs/repository/ARCHITECTURE-CONVENTIONS.md` §7·§9·§11 | 관례 | `16e8543c…d9f8` |
| A02~A10 | `docs/baseline/architecture/02·03·04·05·06·07·08·10` | 아키텍처 자료 | — |
| 코드 | `payment/domain/OrderPaymentControl.kt` `eb0601a4…958b`, `Payment.kt` `1f57b3d3…3d49`, `TerminationEvidence.kt` `735cb0f3…4089`, `V1__payment_core.sql` `9e2b9dca…6545` | `0aa3c1e` | — |

---

## 1. AC 대응표(tasks.json AUTH-CONTRACT-001 → 명세 절 → 계획 시험)

| # | AC(요약) | 절 | 계획 시험(2단계 = domain, AUTH-001 = 실DB·Simulator. 이름은 가역적) |
|---|---|---|---|
| 1 | 기관 금융 사실이 PG rollback과 독립적으로 존속 | §6 | 2단계: `InstitutionObservationSpec`(append-only·중복 수렴·Payment가 AUTHORIZED에서 되돌아가지 않음). AUTH-001: `AuthorizationCommitFailureRecoverySpec`(기관 승인 뒤 TX2 장애 주입 → PG rollback → Simulator DB에 승인 존속 → 원 Attempt 조회 → AUTHORIZED 1건·`PaymentAuthorized` 1건), Simulator 별도 프로세스·DB 재시작 시험 |
| 2 | 타임아웃 후 조회·대사, 중복 승인 차단 | §7, §4.2 | 2단계: `InstitutionOutcomeResolutionSpec`(timeout·NOT_FOUND·lease 만료 → UNKNOWN 유지, 새 Attempt 불가), `AuthorizationAttemptSpec`(dispatch된 Attempt 하나, fencing). AUTH-001: `UnknownRecoveryInquirySpec`(응답 유실·끊김·지연 결과), `ConcurrentConfirmSpec`(같은/다른 키 동시 confirm N건 → 기관 승인 실행 1건), UNKNOWN 중 새 Payment·새 키·새 카드 차단 API 시험 |
| 3 | TCP Adapter와 업무 상태 전이 분리 | §9 | 2단계: ArchUnit `institutionAdapterDoesNotMutateDomain`(패키지 생기기 전에는 빈 대상 guard 포함 — §14.3), 도메인 순수성 기존 규칙. AUTH-001: Port 계약 시험(Adapter 결과 → 업무 결과 변환표), 부분 read/복수 전문/상관관계 프레이밍 시험 |
| 4 | (D-CPC-008) 한도 권위 판정은 승인 시작, 일 한도 점유 방식 | §4.5 | 2단계: `TransactionLimitSpec`(경계값·초과·해제·통화 불일치·overflow, D-AUTH-006·007). AUTH-001: 실DB 동시 confirm 한도 경합(합계가 한도를 넘지 않음) |
| 5 | (D-AUTH-001) PaymentPlan·cardType·체크 할부 거절, AuthenticationSession lifecycle과 인증 근거 1 operation 귀속, 서버 confirm | §3, §4.3, §4.4, §5.3, §5.4 | 2단계: `PaymentPlanSpec`, `AuthenticationSessionSpec`(전이표·단일 귀속·바인딩 불일치·만료가 귀속된 승인을 바꾸지 않음), `AuthorizationStartSpec`. AUTH-001: confirm API 멱등·인증 근거 재사용·callback 중복 시험 |
| 6 | (D-AUTH-002) 늦은 성공·상충 → incident hold + 대사 Case(HIGH/CRITICAL), 자동 보상취소 없음, 해제는 2인 승인+재대사 MATCH | §8 | 2단계: `InstitutionOutcomeResolutionSpec`(상충 행), `OrderPaymentControlSpec` 추가(상충 reason hold, hold 중 승인 시작 거절), (선택 B-3) `IncidentHoldReleaseSpec`. AUTH-001: `AuthorizationIncidentDetected` Outbox 원자성. 해제 실행은 RECON-CONTRACT-001·Q-ADMIN-AUTH 이후 |
| 7 | (L-2) checkAuthorizationStart + startAuthorization를 함께 강제하는 application 시험 기준 | §4.2 | 기준 정의(§4.2-3). 2단계: domain 서비스 `AuthorizationStart`와 ArchUnit `controlStartAuthorizationOnlyThroughAuthorizationStart`. AUTH-001: `ConfirmPaymentUseCase` 시험 — 같은 TX에서 잠금 순서대로 연산 후 저장, 승인 시작과 만료 경합(한쪽만 성공), 실패 주입 시 모든 행 rollback |

---

## 2. 현황 조사(2026-10-15, `0aa3c1e`)

| 영역 | 있는 것 | 없는 것(이 계약이 정의) |
|---|---|---|
| domain `payment` | `OrderPaymentControl`(decide·occupy·**startAuthorization(payment)** — L-2 단일 연산은 CP-API-001에서 구현됨, `checkAuthorizationStart`는 private·ArchUnit `paymentStartAuthorizationOnlyThroughControl`), `recordAuthorized`, `reportSuccessAfterTermination`(hold 하나, 멱등). `Payment` 전이 REQUESTED→AUTHORIZING→(UNKNOWN→)AUTHORIZED/FAILED/EXPIRED, AUTHORIZING·UNKNOWN 종료는 D-CPD-002 충분 근거 필수. `TerminationEvidence` 7종(충분 4종·불충분 3종), `Money`, `OrderIntent` v1 | Authorization·Attempt·AuthenticationSession·PaymentPlan·CardType·기관 관찰 사실·한도·상충 reason·hold 해제 |
| application | `CreatePaymentService`, `ReportSuccessAfterTerminationService`(control→Payment 잠금, UNIQUE 충돌 재판정 3회, **호출 경로 없음**·실패 분류 미정 — AUTH-001 L-6), `LocalTransactions`, `PersistenceFailure` 분류 | 승인 시작(confirm) Use Case, 전송·결과 확정·회복 Use Case, 기관 Port |
| persistence(V1·V3·V5) | `payment`(status CHECK 6종), `payment_termination_evidence`(seq·evidence만 — 출처·시각·행위자 없음), `order_payment_control`, `incident_hold`(reason CHECK `SUCCESS_AFTER_TERMINATION`만), `idempotency_request`(result_code CHECK가 CreatePayment 코드만), `outbox_event`, 권리 투영 | authorization·attempt·관찰 사실·인증 세션·한도 사용량 표, idempotency/hold CHECK 확장(AUTH-001) |
| 권리 Snapshot(MASTER-SNAPSHOT-001) | CreatePayment eligibility(계약 효력·개통·Site 확인·Site 심사·통화 활성화·MID 중지), head `FOR SHARE`, lease | 승인 시작 추가 조건: (CSA, MID, 통화) 정책 Assignment(D-MD-018), 카드사/Route 사용 가능(D-MD-017), 수수료·정산 정책 버전(허용 §4.1-9), **거래 한도 정책**(D-CPC-008) — Snapshot 확장은 MASTER 쪽 후속(§18-3) |
| 시험 | `PaymentFixture`(REQUESTED 경로 `failedWithOnlyTimeout`·`expiredWithOnlyInquiryNotFound` 등), `OrderPaymentDecisionTableSpec`, `IncidentHoldConvergenceSpec`(동시 8회 → 1건) | 승인 계약 시험 전부 |
| 실행 단위 | `apps/{payment-api, master-api, financial-batch, internal-admin}` | Simulator 실행 단위(AUTH-001), 결제창(§18-1) |

---

## 3. 모델과 용어(이 계약의 domain 타입)

모두 `com.paycore.payment.payment.domain`(같은 모듈 — 관례 §7 "승인도 같은 모듈") 아래 순수 Kotlin. 기관 DTO·Spring·JPA 의존 없음(INV-08).

| 타입 | 종류 | 의미·불변식 | 근거 |
|---|---|---|---|
| `PaymentPlan` | sealed 값 | `LumpSum`(일시불) / `Installment(months)`(기관·카드·가맹점이 허용하는 일반 유이자 할부). domain은 기술 범위만 강제(months ≥ 2, 2자리 이하 — 구현 결정 CA-03). 허용 개월·최소금액은 버전 있는 Capability 정책(Demo)이 판정. 무이자·부분무이자는 타입 없음(초기 제외) | 확정 D-AUTH-001, 범위 §1·§2 |
| `CardType` | enum | `CREDIT`, `CHECK`. 기프트·선불·직불망은 값 없음(초기 제외 → 기관 결과에 오면 상충/미지원). 최종 근거는 기관 결과, 클라이언트 선택만 신뢰하지 않음 | 확정 D-AUTH-001, 범위 §5 |
| `AuthenticationSession` | Aggregate Root(PAYMENT DB) | 고객 카드 인증 lifecycle. 한 Payment에 여러 세션 이력 가능(인증 실패 후 같은 결제창에서 재인증), **비종료(CREATED·IN_PROGRESS) 세션은 Payment당 하나**, 귀속(bound) 세션은 Payment당 최대 하나 | 확정 D-AUTH-001, 범위 §3 |
| `PaymentAuthorization` | Aggregate Root(PAYMENT DB) | Payment의 논리 승인. **Payment당 하나**(1:1, `authorizationId`). 요청 Money·PaymentPlan·인증 시 cardType·귀속 세션·승인 시작 판정 근거(권리 revision·정책 Assignment·Route 프로파일/프로토콜 버전·한도 정책 버전·evaluatedAt)를 생성 시 고정. Attempt 목록·기관 관찰 목록(append-only)을 가진다 | 확정 시도 §1, 허용 §4.1 끝·§5.1 |
| `AuthorizationAttempt` | `PaymentAuthorization` 안의 Entity | 기관 승인 실행 시도. `attemptId`, 기관 요청 ID(namespace = 기관+환경+기관 계약계정+operation+요청 ID), Route Snapshot, dispatch 상태(§5.2). **dispatch된(또는 dispatch 여부 불명확) Attempt는 Authorization당 동시에 하나** | 확정 시도 §1·§4, 허용 §5.2 |
| `InstitutionObservation` | `PaymentAuthorization` 안의 append-only 값 | 기관에서 받은 결과 한 건: 출처(RESPONSE/INQUIRY/NOTIFICATION/RECONCILIATION), 결과 분류(APPROVED/DECLINED/NO_EFFECT_CONFIRMED/PENDING/UNKNOWN/NOT_FOUND), 금액·통화·PaymentPlan·cardType(기관 판정), 승인번호, ProviderTransactionId, 기관 거래일·업무일, 원문 증거 참조(hash — 카드번호·CVC·인증 비밀 없음), 관찰 시각. 승인번호를 전역 UNIQUE로 가정하지 않음 | 확정 허용 §5.2 |
| `AuthorizationResolution` | domain 서비스 결과 | 관찰 1건 + 현재 상태 → {전이 적용 / 변화 없음(중복·지연) / UNKNOWN 유지 / 사건(IncidentReason)} 판정표(§5.6) | 확정 허용 §5.2 "먼저/마지막 응답을 무조건 정답으로 하지 않는다" |
| `TransactionLimitPolicy`·`DailyLimitUsage`·`LimitReservation` | 값/Entity | 건당·일 한도 판정과 일 한도 점유(§4.5). 정책 범위·없음 처리 = D-AUTH-006, 일 경계·해제·취소 미복원 = D-AUTH-007 | D-CPC-005·008 |
| `IncidentReason` | enum 확장 | 기존 `SUCCESS_AFTER_TERMINATION` + `CONFLICTING_INSTITUTION_RESULT`(§8.1) | 확정 D-AUTH-002 |
| `PaymentTransaction` | 값(Authorization 안) | 기관이 확정한 금융 효과 1건 = 1건(append-only). 승인 성공 = AUTHORIZATION. 거절·무효과·UNKNOWN·중복 수신은 관찰로만. 늦은/상충 승인은 hold 해제 판정 전 거래 미생성 | **확정 D-AUTH-003** |

용어: "승인 operation" = 하나의 `PaymentAuthorization`(논리 승인). "Attempt" ≠ 새 기관 요청 ID(기관 규약을 따름, 시도 §4). "인증 근거" = VERIFIED 세션의 검증된 증거.

---

## 4. 승인 operation

### 4.1 흐름(확정 범위 §3, 허용 §5.3을 단계로 고정)

```text
[가맹점 서버] CreatePayment(기존, REQUESTED)
[결제창]      PG 결제창(세션 토큰: 주문·Payment·MID·금액·통화·환경·만료 바인딩) → 카드사·PaymentPlan 선택
              → AuthenticationSession CREATED → 인증기관 연결 IN_PROGRESS
              → 인증 결과를 서버에서 검증(서명/기관 조회) → VERIFIED (Payment는 REQUESTED 유지)
              → 가맹점 successUrl로 redirect(paymentId + 불투명 authenticationRef)
[가맹점 서버] POST confirm(Idempotency-Key, paymentId, authenticationRef, 금액·통화·주문 ID 재진술)
[PAYMENT TX1] 멱등 예약 → head(S) → control → Payment → 인증 세션 → 한도 사용량 잠금
              → 판정(§4.6) → control.startAuthorization 경유(§4.2) Payment AUTHORIZING
              + PaymentAuthorization OPEN + Attempt PREPARED(기관 요청 ID 확정) + 세션 귀속 + 한도 점유
              + 멱등 결과(ACCEPTED: authorizationId) — 한 로컬 TX 커밋
[PAYMENT TXd] 전송 게이트: head(S) 재확인(차단/lease) → Attempt PREPARED→DISPATCHING 조건부 갱신(fencing) 커밋
[TX 밖]       TCP Adapter: 연결·전문 송신·응답 수신(잠금·DB TX 없음)
[PAYMENT TX2] 관찰 append + Resolution 적용(Payment·Authorization·control·한도) + 멱등 결과 + Outbox — 한 로컬 TX
              (실패 시 원 Attempt 조회로 복구, §7)
[응답]        confirm 응답(제한 시간 안 확정이면 결과, 아니면 진행 중) / 조회 API / (후속) 서명 Webhook
```

- **확정**: 외부 호출은 DB TX 밖, 업무 효과·요약·요청 결과·Outbox는 PAYMENT 같은 로컬 TX(CLAUDE.md, A02 §트랜잭션 경계, INV-04).
- **확정**: 기관 조회를 하는 동안 DB 잠금을 잡지 않는다(A02). 잠금은 최소 행·고정 순서(원칙 §3).
- 잠금 순서(구현 결정 CA-05, 기존 관례 확장): 멱등 기록 → `entitlement_head`(S) → `order_payment_control` → `payment` → `payment_authorization` → `authentication_session` → 한도 사용량 행. TXd·TX2·회복 Use Case도 이 순서의 부분열만 쓴다.

### 4.2 승인 시작 원자성(L-2·L-3, AC 7)

1. **domain(현재)**: `OrderPaymentControl.startAuthorization(payment)`가 슬롯 검사(stale·REQUESTED·hold·과거 승인)와 `Payment.startAuthorization()`을 한 연산으로 묶는다(CP-API-001 §3.6, 구현됨).
2. **domain(2단계 추가)**: domain 서비스 `AuthorizationStart.start(control, payment, session, admission, limitDecision, ids, at)`가 아래를 **모두 검사한 뒤에만** 상태를 바꾸고 결과 묶음 `StartedAuthorization(authorization(OPEN, attempt PREPARED), sessionBinding, limitReservation)`을 돌려준다. 검사 중 하나라도 실패하면 어떤 Aggregate도 바뀌지 않는다(전이 전 예외 또는 거절 결과).
   - 검사 순서: control 슬롯(기존 `checkAuthorizationStart`) → 세션(VERIFIED·미귀속·미만료·바인딩 일치, §5.3) → PaymentPlan·cardType(§5.4) → 판정 근거(§4.6 — application이 Port로 얻어 넘김) → 한도(§4.5).
   - 성공 시 `control.startAuthorization(payment)` → `PaymentAuthorization.open(...)`(Attempt PREPARED 포함) → `session.bindTo(authorizationId)` → `usage.reserve(...)`.
   - ArchUnit `controlStartAuthorizationOnlyThroughAuthorizationStart`: main 코드에서 `OrderPaymentControl.startAuthorization` 호출·참조는 `AuthorizationStart`만(기존 `Payment.startAuthorization` 규칙과 같은 방식, 위반 fixture 포함). 이로써 "AUTHORIZING이면 PREPARED 이상 Attempt가 있다"(L-3 "REQUESTED = 미전송")가 domain 경로에서 강제된다.
3. **application 시험 기준(AC 7 — AUTH-001이 통과해야 하는 기준)**:
   - (a) confirm Use Case는 TX1 안에서 §4.1 잠금 순서로 읽고 `AuthorizationStart.start`만 호출한다. Use Case 단위 시험은 실제 MySQL에서 TX1 각 Port flush 직후 실패 주입 → payment·authorization·attempt·session 귀속·한도 점유·멱등 행이 **모두** 없음을 확인한다(rollback 원자성).
   - (b) 승인 시작 ↔ REQUESTED 만료/종료 경합: 두 TX를 control 행 잠금에서 붙잡아 교차 → 정확히 한쪽만 커밋(만료가 이기면 confirm은 `PAYMENT_TERMINATED`, 승인이 이기면 만료는 거절). 오래된 결제창의 confirm은 승인 시작 불가(시도 §3).
   - (c) 같은 Payment 동시 confirm N건(같은 키·다른 키 섞음): Attempt 1개·기관 전송 1건, 나머지는 기존 operation 결과로 수렴.
   - (d) hold·과거 승인·stale에서 confirm → 거절, 모든 행 불변.
   - (e) ArchUnit 두 규칙이 main 코드에서 통과하고 위반 fixture에서 실패.
4. **TX1에서 함께 커밋**: Attempt 생성과 Payment AUTHORIZING은 같은 TX1(AUTH-001 L-3 AC, 확정 시도 §5). 전송은 TX1 커밋 뒤.

### 4.3 인증 근거 귀속(확정 D-AUTH-001, 범위 §3)

- 같은 인증 근거는 **하나의 논리 승인 operation에만** 귀속한다. 귀속은 TX1에서 `session.bindTo(authorizationId)` 한 번이며 되돌리지 않는다(append 의미: `boundAuthorizationId`는 null → 값 한 번만).
- 귀속된 세션으로 다른 Payment·다른 Authorization의 승인 시작 불가. 같은 operation의 복구·재요청은 기존 결과를 쓰며 새 승인 권한을 만들지 않는다.
- 인증 성공 ≠ 승인 성공. 귀속 뒤 세션의 TTL 만료는 이미 시작·전송한 승인을 FAILED/EXPIRED로 바꾸지 않는다(세션은 귀속 후 종료 전이 금지, §5.3).
- 전송 전 재시도(같은 Attempt의 연결 재시도)에도 기관의 인증 근거 재사용 규약을 적용한다 — 가상 인증기관 프로파일 Capability `authenticationEvidenceReusableForResend`(Demo: 같은 기관 요청 ID 재전송에만 true).
- 바인딩 대조(서버): state/nonce, 요청 ID, 원 세션, paymentId, 금액·통화·MID·환경, 선택 PaymentPlan·카드사, 유효기간. 브라우저 successUrl 표시만 신뢰하지 않는다.

### 4.4 가맹점 서버 confirm 멱등

- 멱등 범위 = (environment, 인증된 MID, operation `ConfirmPayment`, Idempotency-Key)(확정 시도 §5, D-CP-001 그대로 비교, D-CPC-002 형식). 요청 fingerprint는 정규화된 confirm 본문(paymentId, authenticationRef, merchantOrderId, amount, currency)의 버전 있는 hash `c1:`(구현 결정 CA-06 — 계약 §7 `r1` 방식과 같은 길이 접두 canonical).
- 같은 키·같은 내용 → 과거 결과(재생 응답 = operation 현재 결과, 승인 시작 전 거절은 저장 거절 재생 — **D-AUTH-004**). 같은 키·다른 내용 → `IDEMPOTENCY_KEY_REUSED`(409). 보존 30일(D-CPC-004 operation 공통 적용 — 구현 결정 CA-07).
- **다른 키·같은 Payment·같은 내용**: 활성 operation을 재사용한다(CLAUDE.md "다른 키/같은 주문 동일 내용도 활성 시도를 재사용"). 새 Attempt·새 귀속 없음, 그 키의 결과로 같은 `authorizationId` 참조를 저장.
- **다른 키·같은 Payment·다른 내용**(금액·통화·주문 ID·authenticationRef 불일치): 409 `CONFIRM_CONTENT_CONFLICT`(구현 결정 이름) — 승인 시작 없음.
- confirm 본문의 금액·통화·주문 ID 재진술을 Payment 생성 불변 값과 대조하고 불일치면 거절(구현 결정 CA-08 — 가맹점 서버가 보존 금액을 확인했다는 범위 §3-6의 서버 대조. 더 엄격한 fail-closed이며 승인 정책을 추가하지 않는다).
- confirm 호출 timeout은 새 결제의 근거가 아니다(범위 §3-6). 가맹점은 같은 키로 재확인하거나 조회 API를 쓴다.
- 승인 시작 단계의 업무 거절(§10.1의 409·422)은 그 키의 결과로 저장하고 30일 재생한다(D-CPC-007 선례 적용 — 구현 결정 CA-09). 새 시도는 새 키로만.

### 4.5 거래 한도(D-CPC-008)

- **확정**: 권위 판정은 승인 시작(허용 §4.1-13)에서 한다. 생성 시 일 한도는 점유하지 않는다(D-CPC-008). v1 CreatePayment는 한도 사전 검사를 하지 않는다(CP계약 §4 관례). 코드에 운영 상한을 두지 않는다(D-CPC-005). 한도·지급 결정에는 권위 있는 상태를 쓴다 — replica·projection·캐시(Redis 포함) 금지(원칙 §4·§5).
- **판정 위치**: TX1 안, §4.6 허용 판정 뒤. 정책은 로컬 권리 Snapshot(확장, §18-3)의 버전 있는 `TransactionLimitPolicy`를 읽고 그 버전을 Authorization에 고정한다.
- **건당**: `amount > perTransactionLimit` → 거절 `LIMIT_EXCEEDED`(외부 노출은 고정 분류만, 한도값 미노출). 정책 통화와 요청 통화가 다르면 판정 불가(fail-closed, 결함 신호).
- **일 한도 점유(권장 계약)**: PAYMENT 소유 `DailyLimitUsage` 행(키 = 환경 + MID + 통화 + 업무일 — D-AUTH-006·007) 하나를 TX1에서 행 잠금 → `reserved + consumed + amount ≤ dailyLimit`이면 `LimitReservation(authorizationId, amount, 업무일)` append + reserved 증가, 아니면 거절 `LIMIT_EXCEEDED`. 금액 합은 `Math.addExact`(overflow는 결함).
- **점유 상태 전이(권장)**: RESERVED →(AUTHORIZED) CONSUMED, RESERVED →(FAILED: 기관 확정 거절·전송 전 실패 확정·기관 무효과 확정) RELEASED. **UNKNOWN·AUTHORIZING은 점유 유지**(대안 §2 "UNKNOWN은 점유 유지"와 같은 원칙, 시도 §4). 늦은 성공·상충 hold 건은 점유 유지(해제 금지). 해제·소비는 TX2와 같은 TX. 일 경계 = 승인 시작 시각 Asia/Seoul 날짜(점유 때 고정), 취소·환불 미복원(**D-AUTH-007**).
- **동시성**: 같은 범위·업무일 동시 confirm이 한도를 넘지 않음을 실DB 다중 연결로 검증(AUTH-001). 한 행 잠금은 MID별 핫스팟이 될 수 있다 — 초기 허용, 부하 시험 뒤 분할 카운터 검토(구현 결정 CA-10, 정합성 규칙 불변).

### 4.6 승인 시작 허용 판정(허용 §4.1 1~13 → 위치)

| §4.1 | 조건 | 판정 위치(승인 시작 TX1) | 상태 |
|---|---|---|---|
| 1 | Credential·MID·환경 범위 | confirm 인증 필터(D-CPA-001) + Payment 소유 확인(다른 MID·환경 → 404 비노출) | 기존 기반 |
| 2~5, 12 | Merchant·MID·계약·Site·Snapshot lease·긴급 차단 | 권리 Snapshot 재평가(CreatePayment 판정 재사용 + head S) — 생성 시 판정을 대신하지 않음 | 기존 Port 확장 |
| 6 | 카드사/Route 개통(D-MD-017) | `AuthorizationAdmissionPort`가 Snapshot 확장의 카드사·Route 사용 가능 목록으로 판정 | Snapshot 확장 필요(§18-3) |
| 7 | 주문·금액·통화·인증 세션 바인딩·만료 | domain(§4.3·§5.3) | 2단계 |
| 8 | Route·기관 계정·Credential 준비, 전송 전 Health | 기관 프로파일(SIMULATOR만) + 전송 게이트(TXd) | AUTH-001 |
| 9 | 수수료·정산 정책 Assignment 버전 | Snapshot의 (CSA, MID, 통화) 정책 Assignment(D-MD-018)·버전을 Authorization에 고정 | Snapshot 확장 필요 |
| 10 | 지급계좌·정산 준비 | 개통 시 검증(MASTER) — 승인 시 Snapshot의 개통 ACTIVE로 갈음. 계좌 변경이 신규 승인을 막는지는 허용 §4.1-10이 별도 운영 정책으로 남김(범위 밖 §18) | 기존 |
| 11 | 슬롯·실행 점유, 중복·UNKNOWN 없음 | domain(§4.2) | 2단계 |
| 13 | 거래 한도·인프라 | §4.5 + Attempt 영속화 성공 | 2단계 + 미결 |

판정 불가(Snapshot 없음·만료·미지원 버전·정책 없음)는 fail-closed `Unavailable`(503, 저장 없음 — CreatePayment eligibility와 같은 관례). 판정 입력·버전·시각은 Authorization에 고정(허용 §4.1 끝).

---

## 5. 상태 전이

### 5.1 Payment(요약 상태 — 기존 enum 유지)

| 현재 | 사건 | 다음 | 조건 |
|---|---|---|---|
| REQUESTED | 승인 시작(§4.2) | AUTHORIZING | Attempt PREPARED와 같은 TX1 |
| REQUESTED | 결제창 만료 | EXPIRED | `NOT_TRANSMITTED_AND_SESSION_DISCARDED`: Authorization 없음 ∧ 모든 세션 FAILED/EXPIRED(VERIFIED 미귀속 세션은 먼저 EXPIRED) |
| REQUESTED | 승인 시작 거절 | REQUESTED 유지 | 세션 미귀속, 거절은 키 결과로 저장·재생(**D-AUTH-005**) |
| AUTHORIZING | 전송 전 실패 확정(Attempt ABANDONED_BEFORE_DISPATCH) | FAILED | `PRE_TRANSMISSION_FAILURE_CONFIRMED` |
| AUTHORIZING | 승인·거절·무효과 확정 관찰 | AUTHORIZED / FAILED | §5.6 판정표 |
| AUTHORIZING | timeout·끊김(첫 byte 송신 뒤)·응답 무결성 실패·dispatch lease 만료·TX2 장애 뒤 회복 | UNKNOWN | 근거 불요(보수적) |
| UNKNOWN | 조회·통보·대사로 확정 관찰 | AUTHORIZED / FAILED | §5.6 판정표. NOT_FOUND·timeout·lease 만료로는 종료 불가(기존 domain 강제) |
| AUTHORIZED·FAILED·EXPIRED | 이후 관찰 | 변화 없음 | 종료 상태. 늦은 성공·상충은 §8 |

- **구현 결정 CA-11**: 승인 경로의 기관 무효과 확정은 FAILED(`INSTITUTION_CONFIRMED_NO_EFFECT`)로 표현하고 EXPIRED는 REQUESTED 결제창 만료에만 쓴다(domain은 EXPIRED+무효과도 허용하지만 의미 단순화).
- **L-3(AUTH-001 AC — Coordinator가 2단계 B-2로 포함, 구현 상태는 §19-3)**: REQUESTED 종료 근거 출처 제한 — REQUESTED에서는 `NOT_TRANSMITTED_AND_SESSION_DISCARDED`(EXPIRED)와 근거 없음만 허용하고 `INSTITUTION_CONFIRMED_DECLINE`·`INSTITUTION_CONFIRMED_NO_EFFECT`·`TIMEOUT`·`INQUIRY_NOT_FOUND`·`LEASE_EXPIRED`는 거부(전송한 적 없는 Payment에 기관·전송 근거가 있을 수 없다). `PRE_TRANSMISSION_FAILURE_CONFIRMED`는 Attempt가 있어야 하므로 AUTHORIZING에서만. 이에 따라 `PaymentFixture.failedWithOnlyTimeout`·`expiredWithOnlyInquiryNotFound`·`failedBeforeTransmission`(이미 AUTHORIZING 경유)와 `OrderPaymentDecisionTableSpec` 행을 같은 변경에서 갱신한다. `TIMEOUT`·`INQUIRY_NOT_FOUND`·`LEASE_EXPIRED`는 이후 종료 근거가 아니라 **관찰**(§3)로 남고, enum 값은 V1 CHECK·기존 행 복원 호환을 위해 지우지 않는다.

### 5.2 PaymentAuthorization·AuthorizationAttempt

Authorization 상태: `OPEN`(AUTHORIZING에 대응) → `UNKNOWN` → `AUTHORIZED` / `FAILED`. Payment 상태와 같은 TX에서 함께 바뀐다(요약 일치 불변식: Payment AUTHORIZING ⇔ Authorization OPEN 등 — 2단계 시험).

Attempt dispatch 상태(구현 결정 CA-12 이름, 의미는 허용 §5.3-2 확정):

| 현재 | 사건 | 다음 | 의미 |
|---|---|---|---|
| (없음) | TX1 | PREPARED | 기관 요청 ID·Route Snapshot 확정. 아직 전송 안 함(증명 가능) |
| PREPARED | TXd 전송 게이트 통과(조건부 갱신) | DISPATCHING | 이 커밋 뒤에만 소켓을 연다. 커밋 뒤 모든 실패는 "전송했을 수 있음" |
| PREPARED | 게이트 거절(차단·lease 만료·Health) / 회복 Worker의 fencing 포기 | ABANDONED_BEFORE_DISPATCH | 같은 행 조건부 갱신이라 DISPATCHING과 동시에 이길 수 없다 → `PRE_TRANSMISSION_FAILURE_CONFIRMED`의 유일한 출처 |
| DISPATCHING | 연결 실패(connect 단계, byte 미송신) | DISPATCHING 유지 → 같은 Attempt·같은 기관 요청 ID로 예산 안 재연결 / 예산 소진 시 ABANDONED_BEFORE_DISPATCH | connect 실패만 미송신 증명. write 시도 이후는 아래 |
| DISPATCHING | write 시작 이후 실패·read timeout·끊김·프레이밍/상관관계/무결성 실패·dispatch lease 만료 | OUTCOME_UNKNOWN | 성공 가능성 보존(허용 §5.3) |
| DISPATCHING·OUTCOME_UNKNOWN | 확정 관찰(APPROVED/DECLINED/NO_EFFECT_CONFIRMED) | RESOLVED | 관찰 append + §5.6 |

- 초기 계약: **Authorization당 dispatch되는 Attempt는 1개**(구현 결정 CA-13). ABANDONED_BEFORE_DISPATCH 뒤에는 Authorization·Payment FAILED(`PRE_TRANSMISSION_FAILURE_CONFIRMED`) — 고객 새 시도는 새 Payment. 같은 Authorization의 두 번째 Attempt(기관 재요청 안전성 Capability가 확인된 경우, 시도 §4)는 AUTH-001 이후 확장이며 그때도 "동시 실행 하나"를 domain이 강제한다.
- UNKNOWN에서 새 Attempt·다른 VAN failover·고객 새 시도 금지(확정 시도 §4, A04).

### 5.3 AuthenticationSession lifecycle(확정 상태 이름 D-AUTH-001)

| 현재 | 사건 | 다음 | 조건·불변식 |
|---|---|---|---|
| — | 결제창이 인증 시작 | CREATED | Payment REQUESTED, 같은 Payment의 비종료 세션 없음. 바인딩(paymentId·OrderKey·Money·PaymentPlan·카드사·환경·만료) 고정 |
| CREATED | 인증기관 연결 | IN_PROGRESS | |
| IN_PROGRESS | 서버 검증 성공 | VERIFIED | 증거의 바인딩 일치·유효기간 내. 증거 = 불투명 참조 + cardType + issuer/acquirer/authenticationProvider 식별(가상 카드 참조, PAN·CVC 없음) |
| CREATED·IN_PROGRESS | 인증 실패·사용자 취소·바인딩 불일치·state 불일치 | FAILED | 같은 Payment에서 새 세션 시작 가능 |
| CREATED·IN_PROGRESS·VERIFIED(미귀속) | TTL 만료 / Payment 종료 | EXPIRED | |
| VERIFIED(미귀속) | 승인 시작 TX1 | VERIFIED(귀속 `boundAuthorizationId`) | 한 번만. 귀속 뒤 FAILED·EXPIRED 전이 금지 |
| VERIFIED | 같은 증거 중복 callback | 변화 없음 | 멱등 |
| VERIFIED | 다른 증거 callback | 거절(보안 사건 로그) | 덮어쓰지 않음 |
| FAILED·EXPIRED | 늦은 인증 성공 callback | 변화 없음(기록만) | 승인 권한 생성 안 함 |

- 상태는 5개로 유지하고 귀속은 속성으로 표현(구현 결정 CA-14 — D-AUTH-001 상태 목록을 바꾸지 않는다).
- TTL 수치는 버전 있는 설정, 코드 운영 기본값 없음, 시험은 Demo 값(**D-AUTH-009**).

### 5.4 PaymentPlan·cardType 검증

| 검사 | 위치 | 결과 |
|---|---|---|
| `CHECK` + `Installment` | 승인 시작(cardType = 인증 증거) | 거절 `PAYMENT_PLAN_NOT_ALLOWED`, 전송 없음(확정 범위 §2 "PG 단계에서 거절") |
| 개월 수·최소금액·카드/상품·MID 계약 지원 | 승인 시작, 버전 있는 Capability 정책(Demo) | 미지원 → 같은 거절 |
| 기관 재검증 | 가상 VAN이 체크 할부·미지원 개월을 DECLINED로 응답(Simulator 계약) | 거절은 정상 FAILED(`INSTITUTION_CONFIRMED_DECLINE`) |
| 기관이 다른 조건으로 승인(개월·cardType·체크+할부 승인·금액·통화 차이) | TX2 Resolution | 상충 결과 → §8(조용히 덮어쓰지 않음, 범위 §2) |

PaymentPlan·카드사는 인증 세션·승인 요청·기관 결과·Route 근거에 연결한다(범위 §2). 승인 이후 조건 변경은 새 구매 흐름.

### 5.5 FAILED/EXPIRED 확정 근거(termination evidence) 출처 표

| 근거(D-CPD-002) | 상태 | 유일한 출처(이 계약) |
|---|---|---|
| `INSTITUTION_CONFIRMED_DECLINE` | FAILED | 원 Attempt에 연결된 DECLINED 관찰(응답·조회·통보 중 무결성 검증된 것) |
| `PRE_TRANSMISSION_FAILURE_CONFIRMED` | FAILED | Attempt ABANDONED_BEFORE_DISPATCH(§5.2) |
| `INSTITUTION_CONFIRMED_NO_EFFECT` | FAILED(·EXPIRED) | NO_EFFECT_CONFIRMED 관찰: 기관 프로파일 Capability `confirmsNoEffect = true`인 기관의 명시적 무효과 확정 응답만. 단순 NOT_FOUND는 아님(확정 시도 §2) |
| `NOT_TRANSMITTED_AND_SESSION_DISCARDED` | EXPIRED | REQUESTED + Authorization 없음 + 모든 세션 FAILED/EXPIRED |
| `TIMEOUT`·`INQUIRY_NOT_FOUND`·`LEASE_EXPIRED` | — | 종료 근거 아님. 관찰로만 기록 |

`payment_termination_evidence`에 출처(관찰 ID·Attempt ID)·시각·행위자(서비스 계정/운영자) 컬럼 추가는 AUTH-001 migration(V1 주석의 예고).

### 5.6 Resolution 판정표(관찰 → 상태, 2단계 domain 시험의 기준)

| 현재 Authorization | 관찰 | 결과 |
|---|---|---|
| OPEN·UNKNOWN | APPROVED, 금액·통화·Plan·cardType 일치 | AUTHORIZED, Payment AUTHORIZED, control `recordAuthorized`, 한도 CONSUMED, `PaymentAuthorized` Outbox |
| OPEN·UNKNOWN | DECLINED | FAILED(`INSTITUTION_CONFIRMED_DECLINE`), 한도 RELEASED |
| OPEN·UNKNOWN | NO_EFFECT_CONFIRMED(Capability 있음) | FAILED(`INSTITUTION_CONFIRMED_NO_EFFECT`), 한도 RELEASED |
| OPEN | TIMEOUT 등 불명확 | UNKNOWN(관찰 기록) |
| UNKNOWN | NOT_FOUND·PENDING·UNKNOWN | UNKNOWN 유지(관찰 기록), 회복 예산 소모 |
| OPEN·UNKNOWN | APPROVED이나 금액·통화·Plan·cardType 불일치, 또는 부분 승인 | UNKNOWN 유지 + hold `CONFLICTING_INSTITUTION_RESULT`, 거래·AUTHORIZED 없음, 한도 점유 유지(**D-AUTH-008**) |
| AUTHORIZED | **같은 APPROVED** = 승인 조건 전체가 확정 거래와 같음: 금액·통화·Plan·cardType **그리고 ProviderTransactionId·승인번호·기관 거래일** 모두 일치(중복 응답·통보·조회·대사, review M-1) | 변화 없음(금융 효과·Outbox 하나 — INV-06) |
| AUTHORIZED | DECLINED·NO_EFFECT, 또는 위 정의의 "같은 APPROVED"가 아닌 APPROVED(조건이 같아도 PTX·승인번호·기관 거래일 중 하나라도 다르면 별개 승인일 수 있음) | 상태 유지 + hold `CONFLICTING_INSTITUTION_RESULT` + 사건 Outbox(§8), 두 번째 거래 없음 |
| FAILED·EXPIRED | APPROVED | 상태 유지 + hold `SUCCESS_AFTER_TERMINATION`(기존) + 사건 Outbox, 거래 없음. 해제했던 일 한도는 승인 시작일 날짜에 **다시 기록**(FORCED — 한도 초과여도 거부하지 않고 초과 표시, 새 승인의 한도 판정 합에 포함 — D-AUTH-010, §19.8) |
| (현재 generation) OPEN·UNKNOWN, 주문 승인 이력이 **다른 Payment**(이전 generation 늦은 승인 REAL 해제 등) | 일치 APPROVED | 거래 + AUTHORIZED 기록, 주문 승인 이력은 기존 유지, 이중 승인 hold `CONFLICTING_INSTITUTION_RESULT`(`Authorized.incident`), DOUBLE_APPROVAL_CORRECTED로 해제(round2 M-3, D-AUTH-011과 같은 처리) |
| 같은 Payment hold를 해제한 뒤(판정 무관) | 새 키 APPROVED(다른 PTX)·반대 결과 | **새 hold**(occurrence + 1, 기존 hold 재사용 없음) + Incident + 주문 차단. 승인이고 점유가 RELEASED면 FORCED 재점유(NO_EFFECT 뒤), REAL·DOUBLE 뒤 CONSUMED면 재점유 없음(D-AUTH-012·D-AUTH-013) |
| FAILED | KEY_CONFLICT APPROVED | CONFLICTING hold + FORCED 재점유(D-AUTH-010, round2 m-7) |
| (현재 generation) OPEN·UNKNOWN, 주문에 **이전 generation** hold 활성 | 일치 APPROVED | AUTHORIZED + 거래·PaymentAuthorized 기록, 주문 hold 유지(D-AUTH-011). 이 Payment 자신의 CONFLICTING hold가 있으면 기록만(§19.2-6) |
| 아무 상태 | 같은 ObservationKey·같은 **기관 내용**(attemptId·source·sourceResultId·outcome·approved terms — observedAt·evidenceHash는 비교 안 함, round2 M-2) | 효과 없음(DUPLICATE) |
| 아무 상태 | 같은 ObservationKey·**다른 내용** | 첫 관찰 유지 + 상충 증거(`keyConflictingObservations`)로 append + hold `CONFLICTING_INSTITUTION_RESULT`. 진행 중이면 UNKNOWN으로(review m-1). 같은 키·같은 내용만 효과 없음 |
| FAILED | 같은 DECLINED 중복 | 변화 없음 |

관찰은 판정과 무관하게 append(유일 키 (attemptId, 출처, 기관 결과 식별자) — 구현 결정 CA-15). 같은 키·다른 내용은 조용히 버리지 않고 상충 사건으로 처리한다(review m-1 결정: 결함 예외 대신 사건 — 예외는 TX rollback·재시도 반복만 낳고 증거를 남기지 못한다. 기관이 같은 결과 식별자로 다른 내용을 보낸 것은 상충 결과다).

---

## 6. 기관 금융 사실 대 PG 상태(AC 1)

1. **기관 사실의 원본은 기관(Simulator)이다.** 가상 VAN·인증기관은 PG와 별도 프로세스·별도 영속 저장소를 갖고 PG 재시작·DB rollback으로 이미 발생한 승인이 사라지지 않는다(확정 허용 §5.5). PG DB rollback은 기관 승인을 취소하지 않는다(A02).
2. **PG의 관찰은 append-only.** `InstitutionObservation`은 수정·삭제하지 않는다(DB: runtime 계정 SELECT·INSERT만 — 관례 §9.2). Payment 상태는 관찰에서 파생된 요약이며 AUTHORIZED는 되돌리지 않는다.
3. **TX 경계**: TX1(점유·Attempt PREPARED) → TXd(DISPATCHING) → TX 밖 호출 → TX2(관찰 + 상태 + 한도 + 멱등 결과 + Outbox). TX2 실패 시 관찰도 함께 rollback되지만 기관 원본이 남으므로 원 Attempt 조회로 같은 관찰을 다시 얻는다(§7). 관찰만 먼저 별도 TX로 저장하는 이중 단계는 쓰지 않는다(구현 결정 CA-16 — 업무 효과·요약·Outbox 같은 TX 원칙 유지, 증거 원본은 기관).
4. **응답 검증**: 응답을 요청에 연결(기관 요청 ID·상관 키)하고 금액·통화·원 참조·무결성을 검증한다. 검증 실패 응답은 성공으로 처리하지 않고 OUTCOME_UNKNOWN + 원문 증거 참조(허용 §5.3-4).
5. **증명 시험(AUTH-001)**: Simulator가 승인 기록 → PG TX2 직전·commit 중 장애 주입 → PG 행 없음/AUTHORIZING → 회복 → AUTHORIZED 1, `PaymentAuthorized` 1, Simulator 승인 1(재승인 없음). PG 재시작·Simulator 재시작 각각 포함.

---

## 7. UNKNOWN 회복(AC 2)

- **확정**: timeout·조회 NOT_FOUND·lease 만료만으로 효과 없음을 단정하지 않는다. UNKNOWN은 원 Attempt 결과 조회·통보·대사 우선(CLAUDE.md, 시도 §4, INV-11). 복구 조회와 신규 금융 요청을 분리하고 재생·조회 복구가 기관 승인을 새로 실행하지 않는다(시도 §5).
- **회복 Worker(AUTH-001)**: 후보 = DISPATCHING이 dispatch 기한을 넘긴 Attempt(→ 먼저 OUTCOME_UNKNOWN 로컬 TX), OUTCOME_UNKNOWN Attempt, PREPARED가 기한을 넘긴 Attempt(→ fencing 포기 시도). 후보 선택은 jOOQ 조회(keyset, 비권위) → 건별 Use Case가 JPA 잠금으로 재판정(권위). 처리 lease는 fencing 토큰으로 보호하고 lease 만료를 "호출 안 함"으로 가정하지 않는다(A06).
- **조회**: `AuthorizationInquiryPort.inquire(원 기관 요청 ID 참조)`만 사용. 새 기관 요청 ID 생성 금지. 결과는 관찰로 append 후 §5.6.
- **재시도 예산과 대사 이관(계약 — 수치는 버전 있는 설정, D-AUTH-009)**: 조회는 operation별 예산(횟수·backoff·jitter·회로차단) 안에서 반복. 예산 소진, UNKNOWN 연령 상한 초과, 또는 기관 확정성 창을 넘긴 반복 NOT_FOUND이면 **Payment는 UNKNOWN 그대로 두고** 대사 이관 사건(`AuthorizationOutcomeUnresolved` Outbox, §11)을 낸다 — 자동 FAILED 금지. 이후 확정은 대사가 원 Attempt와 일치하는 확정 기관 근거를 소유 도메인 Recovery로 반영할 때만(대안 §4 "원 Attempt와 일치하는 확정 기관 성공·내부 UNKNOWN" 행, 정산 §16 exact-match Recovery).
- **중복 승인 차단(겹 방어)**: (1) control 슬롯 — UNKNOWN에서 새 Payment 금지(기존 `decide`: UseExistingPayment, 다른 내용 충돌), (2) Authorization당 dispatch Attempt 1개 + Attempt 조건부 상태 갱신, (3) Payment당 Authorization 1개(DB UNIQUE paymentId — AUTH-001), (4) 기관 요청 ID UNIQUE(namespace), (5) 가상 기관의 같은 ID·같은 payload 기존 결과 반환/다른 payload 충돌(허용 §5.2), (6) 대사.
- **UNKNOWN에서 새 결제 금지**: 새 키·새 카드·다른 VAN 요청 모두 차단(시도 §6-5). confirm은 기존 operation 상태(진행 중)를 돌려준다.
- 관측: UNKNOWN 수·최고 연령(지표 label에 paymentId 금지), 조회 예산 소진 경보(A07).

---

## 8. 늦은 성공과 상충 결과(D-AUTH-002, AC 6)

### 8.1 PAYMENT 쪽(이 계약·AUTH-001)

- 대상: (a) FAILED·EXPIRED Payment에 대한 APPROVED 관찰(`SUCCESS_AFTER_TERMINATION`, 기존), (b) AUTHORIZED Payment에 대한 반대·다른 금액 관찰, (c) 진행 중 승인의 조건 불일치 승인(D-AUTH-008) → `CONFLICTING_INSTITUTION_RESULT`(신규 reason).
- 동작: Payment 상태를 덮어쓰지 않는다(확정 금융 사실 append-only). 관찰 append + control incident hold(멱등: (paymentId, reason)당 하나) + `AuthorizationIncidentDetected` Outbox를 **같은 TX2**. 자동 보상취소·자동 재승인 없음(확정 D-AUTH-002).
- hold 효과(기존·확정): 새 Payment·승인 시작 차단, 같은 입력 재요청은 Blocked(INCIDENT_HOLD) + 기존 paymentId(D-CPD-004). hold 중 늦은 승인은 `PaymentAuthorized`(정산 대상 금융 사실 이벤트)로 내보내지 않는다 — FINANCIAL이 매입·정산 후보를 만들지 않게 한다(사건 이벤트로만 전달). 한도 점유는 유지.
- **예외 교차 참조(2026-10-17)**: D-CANCEL-012가 위 "hold 중 PaymentAuthorized 없음"(그리고 D-AUTH-003 "해제 판정 전 거래 없음")의 **유일한 예외**다 — 이중 승인 보정을 위해 RECON이 hold 중 늦게 확정된 P1의 승인을 거래로 기록하는 TX. 상세는 `CANCEL-CONTRACT-001` §4.10·§14.1.
- 대사 Case 위험 등급(HIGH/CRITICAL)의 최종 분류는 RECON 소유. PAYMENT 사건 이벤트는 사실(reason·관찰·원 Attempt)만 담고 권장 등급 힌트를 둘 수 있다(정산 §18 "Unexplained external success" = CRITICAL 예시). 둘 다 Hold + 수동 검토라 PAYMENT 동작은 같다.

### 8.2 해제 경계(PAYMENT ↔ RECON)

| 단계 | 소유 | 내용 |
|---|---|---|
| Case 생성·위험 판정·수동 검토 | FINANCIAL/RECON(RECON-CONTRACT-001) | PAYMENT 사건 이벤트 Inbox → Case(안정 키 = 원 Attempt + reason) |
| 보정(취소 요청·Adjustment 등) | 각 소유 도메인 Use Case | 자동 보상취소 없음. 운영자 명령 + 승인 규칙. 취소는 Cancellation 계약(G-5 → `CANCEL-CONTRACT-001` 운영자 보정 취소, D-CANCEL-005·010: 늦게 확정된 P1을 취소하고 DOUBLE 해제가 그 취소를 참조) |
| 재대사 MATCH 확인 | RECON | MATCH 증거 ID를 발행 |
| 해제 승인 | 운영 도구 → PAYMENT Use Case | **2인 승인**(요청자 ≠ 승인자, 역할·Scope, 승인한 입력 버전 = 실행 입력, 정산 §24·마지막 절) + MATCH 증거 참조 필수 |
| hold 해제 기록 | PAYMENT(control) | append-only `IncidentHoldRelease`(holdId, requestedBy, approvedBy, reconMatchRef, 사유, 시각). hold 행은 지우지 않는다. 여러 hold 중 하나 해제가 다른 hold를 없애지 않는다 |

- **도출(시도 §2 + 결정 D-CPD-002)**: 해제 뒤 주문 효과 — 재대사가 늦은 승인이 **실제 기관 승인**이었음을 확인했다면 그 주문은 승인 성공 이력이 있는 주문이다(취소 여부와 무관하게 새 Payment 금지, 재구매는 새 주문 ID). 기관 무효과가 확인됐다면 원 종료 근거가 유지되고 D-CPD-002대로 새 Payment가 가능하다. 현재 코드는 늦은 승인을 성공 이력으로 남기지 않아 해제 기능이 생기면 첫 경우에 새 Payment를 허용하게 된다(§17-1). 해제 연산은 이 판정 결과(`REAL_APPROVAL_CONFIRMED`/`NO_EFFECT_CONFIRMED`)를 입력으로 받아 control에 반영해야 한다.
- 해제 실행 의존: RECON-CONTRACT-001(MATCH 증거 형식), Q-ADMIN-AUTH(운영자 인증·역할 — PROPOSED). 둘 전까지 hold는 해제되지 않는다(fail-closed, 현재 동작 유지).

---

## 9. Port와 Adapter 경계(AC 3)

### 9.1 required Port(application.required, 업무 의도만)

| Port | 연산(개념) | 결과(업무 분류) |
|---|---|---|
| `CardAuthenticationPort` | `start(session 바인딩)` → 인증 진입 정보, `verify(provider 결과 참조)` → 검증된 증거 | Verified(증거: 불투명 참조·cardType·issuer/acquirer/provider) / Failed(사유 분류) / Unverifiable(일시 — 세션 상태 불변) |
| `AuthorizationPort` | `authorize(기관 요청 ID, Route Snapshot, Money, PaymentPlan, 인증 증거 참조)` | Approved(관찰) / Declined(관찰) / NoEffectConfirmed / NotSentConfirmed(connect 실패·게이트) / OutcomeUnknown(사유: TIMEOUT·CONNECTION_LOST·FRAMING·CORRELATION·INTEGRITY) — 업무 예외를 던지지 않는다 |
| `AuthorizationInquiryPort` | `inquire(원 기관 요청 ID 참조)` | Found(관찰) / NotFound / NoEffectConfirmed / Unavailable |
| `AuthorizationAdmissionPort` | 승인 시작 허용 판정(§4.6, 로컬 Snapshot만, 호출자 TX 안, 쓰기·원격 호출 금지) | Admitted(근거 버전 묶음) / Refused(고정 분류) / Unavailable |
| `InstitutionRouteResolver` | 기관 프로파일(operation별 Adapter ID·프로토콜 버전·Capability·timeout 참조, backend SIMULATOR/REAL) | 프로파일 또는 없음(fail-closed) |
| 저장 Port | `PaymentAuthorizationStore`·`AuthenticationSessionStore`·`DailyLimitUsageStore`(Versioned·잠금·MANDATORY TX — 관례 §9) | |

Adapter(`adapter.institution` — 구현 결정 CA-17 패키지)는 TCP/전문/SDK DTO를 Port 결과로 바꾸기만 하고 **domain 상태 전이 메서드를 호출하지 않는다**(ArchUnit). 전이는 application Use Case만 한다.

### 9.2 TCP Adapter(가상 VAN 소켓) 계약 — 개념 수준

- TCP stream 한 번의 read를 전문 하나로 가정하지 않는다. 길이 접두 프레이밍·부분 read/write·복수 전문·동시 요청의 응답 상관관계·재연결을 계약화(허용 §5.3). 라이브러리 write 성공 ≠ 기관 접수·승인.
- connect / read / 전체 deadline 구분. read timeout은 거절이 아니다. connect 실패만 미송신 증명(§5.2).
- 전문 인증(Simulator용 HMAC 시험 키, 운영 키 없음)·TLS는 Simulator 프로파일로 시험. **실제 바이트 형식·필드 배치·코드표는 AUTH-001(또는 Simulator 작업)에서 `ProviderContractVersion`과 함께 정한다.** 이 계약은 의미(요청 ID·상관 키·금액·통화·Plan·결과 분류·관찰 필드)만 고정한다.
- 할부의 전문 표현(예: 00/0)은 Adapter에서 변환(범위 §2). domain에 전문 값 없음.

### 9.3 가상 인증기관·가상 VAN Simulator 역할

| 역할 | 책임 | 비고 |
|---|---|---|
| 가상 인증기관 | 가상 카드 참조로 국내 신용/체크·지원/미지원 상품·인증 성공/실패/사용자 취소/만료 재현, issuer/acquirer/authenticationProvider 구분, 서명된 일회성 증거 또는 서버 조회 가능 opaque token, 중복 callback·잘못된 금액/MID·state 불일치·늦은 결과·confirm 유실·근거 재사용 시나리오 | 범위 §4. 카드사 앱을 복제했다고 주장하지 않는다 |
| 가상 VAN | 승인/거절/조회(취소는 Cancellation 계약), 같은 요청 ID·같은 payload 기존 결과·다른 payload 충돌, 부분 전문·지연·끊김·중복·성공 후 응답 유실·늦은 결과·상충 결과, 체크 할부·미지원 개월 재검증 거절, Capability 프로파일(멱등 지원/미지원, `confirmsNoEffect` 유무, 조회 보존기간) | 허용 §5.2·§5.5 |
| 공통 | PG와 별도 프로세스·영속 저장소, 결정 가능한 시나리오·seed·가상 시계·장애 주입, 인증 사실과 승인 원장 분리(같은 배포여도) | 실행 단위 구성은 AUTH-001 구현 결정 |

### 9.4 Simulator/REAL 격리(확정 D-CPD-001, 허용 §5.5)

- 가맹점 TEST/LIVE와 실행 backend SIMULATOR/REAL은 별도 축. 현재 어느 환경도 REAL로 나가지 않는다. TEST는 영구히 REAL 금지.
- 방어 겹: REAL Adapter bean 없음(프로파일 resolver가 REAL이면 기동 실패/전송 거부), Simulator endpoint·시험 키만 설정, 네트워크 egress 허용 목록(Simulator host만 — 배포 구성), 시험 `RealBackendIsolationSpec`(REAL 프로파일 주입 → 기동 실패·전송 0).
- 실제 카드번호·CVC·인증 비밀번호를 쓰지 않는다. 가상 카드 참조만. 로그·이벤트·관찰 증거에 카드 데이터·Secret 없음(A05).

---

## 10. 오류·응답 계약(confirm, 승인 결과 조회)

### 10.1 confirm(`POST /v1/payments/{paymentId}/confirm` — 경로·HTTP 코드는 구현 결정 CA-18, AUTH-001 구현)

| 판정 | HTTP | code | 멱등 결과 저장 | 비고 |
|---|---|---|---|---|
| 인증 실패 | 401 | `UNAUTHENTICATED` | 안 함 | 기존 필터 |
| 본문·헤더 형식 오류 | 400 | `INVALID_REQUEST` 등 | 안 함 | 기존 파서 관례 |
| Payment 없음·다른 MID/환경 | 404 | `PAYMENT_NOT_FOUND` | 안 함 | 존재 비노출 |
| 같은 키·다른 내용 | 409 | `IDEMPOTENCY_KEY_REUSED` | — | |
| 같은 키 처리 중(잠금 대기 timeout) | 409 | `IDEMPOTENCY_REQUEST_IN_PROGRESS` | — | Retry-After |
| confirm 재진술 불일치(금액·통화·주문 ID) / 다른 키·다른 내용 | 409 | `CONFIRM_CONTENT_CONFLICT` | 저장 | |
| 인증 근거 무효(없음·미검증·만료·다른 Payment·이미 다른 operation 귀속·바인딩 불일치) | 409 | `AUTHENTICATION_NOT_VERIFIED` | 저장 | 세부 사유는 내부 로그만 |
| 체크 할부·미지원 Plan | 422 | `PAYMENT_PLAN_NOT_ALLOWED` | 저장 | |
| 승인 허용 조건 미충족(§4.6 Refused) | 422 | `PAYMENT_NOT_ELIGIBLE`(고정 reason) | 저장 | |
| 한도 초과 | 422 | `LIMIT_EXCEEDED` | 저장 | 한도값 미노출. Payment REQUESTED 유지(D-AUTH-005) |
| incident hold | 409 | `ORDER_ON_HOLD` | 저장 | 기존 코드 재사용 |
| Payment 종료(FAILED·EXPIRED) | 409 | `PAYMENT_TERMINATED` | 저장 | 새 시도는 CreatePayment(새 키) |
| 이미 승인됨 | 200 | 결과 AUTHORIZED | 저장 | 기존 성공 반환(재승인 없음) |
| 승인 확정(제한 시간 안) | 200 | 결과 AUTHORIZED / FAILED(reason 분류: DECLINED·NOT_SENT·NO_EFFECT) | 저장 | |
| 진행 중(AUTHORIZING·UNKNOWN) | 202 | 결과 `IN_PROGRESS`/`UNKNOWN`(확인 중) | 저장(operation 참조) | 새 키·새 Payment로 재시도 금지 안내 |
| 판정 불가(Snapshot·정책 없음) | 503 | `TEMPORARILY_UNAVAILABLE` | 안 함 | fail-closed |
| commit 결과 불명확 | 503 | `TEMPORARILY_UNAVAILABLE` | — | 같은 키로 확인, 새 키 금지(CP-API 관례) |

응답 본문: paymentId, authorizationId, 결과 상태, (AUTHORIZED) 승인 금액·통화·PaymentPlan·cardType·승인번호·승인 시각. 카드번호·인증 증거·기관 원문·내부 버전 없음. 같은 키 재요청은 operation 현재 결과(**D-AUTH-004**), 현재 MID/환경 권한을 다시 확인한다.

### 10.2 승인 결과 조회와 전달 경계

- 조회 API(GetPayment, jOOQ query adapter — CP-QUERY-001 범위)가 Payment·Authorization 현재 상태를 MID·환경 범위 강제로 제공. UNKNOWN은 "확인 중"으로 표시(범위 §3-8, A04).
- 서명 Webhook: Outbox 통합 이벤트를 소비하는 별도 전달 작업(범위 관찰 §18-4). Webhook payload는 이 계약의 Outbox payload를 그대로 노출하지 않는 별도 공개 계약. 서명·재전송 방지·SSRF 방어는 그 작업에서(A05).
- 가맹점은 승인 확정(AUTHORIZED) 뒤에만 주문 완료·상품 제공(범위 §3-8). 이 안내는 API 문서 규칙.

---

## 11. Outbox 이벤트와 금융 이벤트 분류

| 이벤트 v1 | eventCategory(구현 결정 CA-19 이름) | 시점(같은 TX) | effect_key | 소비자·의미 |
|---|---|---|---|---|
| `PaymentAuthorized` | `FINANCIAL_FACT` | TX2 AUTHORIZED | `PaymentAuthorized:{transactionId}` | FINANCIAL: 매입·정산 후보. payload = paymentId·authorizationId·MID·Site·주문 ID·금액·통화·PaymentPlan·cardType·issuer/acquirer 코드·승인번호·ProviderTransactionId·기관 거래일/업무일·승인 시각·정책 Assignment 버전·권리 revision(카드번호·Secret 없음). **effect_key·FINANCIAL 업무 키 = PaymentTransaction id(D-AUTH-003)** — 구현 시 `PaymentAuthorized:{transactionId}`로 둔다 |
| `PaymentAuthorizationFailed` | `INTEGRATION_NON_FINANCIAL` | TX2 FAILED | `PaymentAuthorizationFailed:{authorizationId}` | 가맹점 통지·조회 projection. 금융 효과 없음 |
| `PaymentExpired`(**AUTHN-001 추가**, D-AUTHN-008) | `INTEGRATION_NON_FINANCIAL` | REQUESTED → EXPIRED 만료 TX(결제창 시각 경과 — 주기 작업·CreatePayment/토큰 발급 inline·confirm TX1 inline) | `PaymentExpired:{paymentId}` | 가맹점 통지·조회 projection. 금융 효과 없음(미전송). payload·envelope은 AUTHN-001 명세 §7.5.7(paymentKey·카드정보·Secret·개인정보 없음) |
| `AuthorizationOutcomeUnresolved` | `OPERATIONAL_RISK` | 회복 예산 소진·연령 초과(§7) | `AuthorizationOutcomeUnresolved:{attemptId}` | RECON: 대사 이관(Case 후보). 상태 변화 없음 |
| `AuthorizationIncidentDetected` | `RISK_INCIDENT` | TX2 hold 생성 | `AuthorizationIncidentDetected:{paymentId}:{reason}` | RECON: Case(HIGH/CRITICAL). 관찰(금액·통화·승인번호 등) 포함. **정산 대상 아님** |
| (없음) | — | TX1 승인 시작·인증 세션 전이·한도 점유 | — | 내부 상태. 필요 시 관측 로그 |

- 기존 `PaymentRequested`(INTEGRATION_NON_FINANCIAL)와 이름·의미를 구분(D-CPC-003). aggregateType = `PaymentAuthorization`, aggregateVersion = Authorization JPA version(관례 §9 선례).
- 순서: Payment 단위 전달 순서 유지, 소비자는 Inbox(consumer, eventId) + 업무 키로 중복 제거(A03, INV-06). Relay·FINANCIAL Inbox는 후속 작업. Kafka 미도입(ADR-001) — 전달 보장은 Outbox/Inbox·멱등·대사가 하며 브로커가 금융 안전성을 대체하지 않는다.

---

## 12. 8대 원칙별 완료 증거 계획

| 원칙 | 적용 | 2단계(domain) 증거 | AUTH-001(실DB·Simulator) 증거 |
|---|---|---|---|
| Financial Consistency | 적용 | 요청 전액 승인만·금액/통화/Plan/cardType 일치 판정표, 관찰 append-only, AUTHORIZED 불가역, 한도 합 불변식(경계값 독립 기대값) | 승인 1건당 `PaymentAuthorized` 1건·금액 보존, DB가 관찰 UPDATE/DELETE 거부(1142) |
| Transaction Safety | 적용 | `AuthorizationStart` 실패 시 모든 Aggregate 불변 | TX1·TX2 각 flush 뒤 실패 주입 rollback, 외부 호출 중 TX·잠금 미보유 검사, commit 후 응답 유실 |
| Idempotency | 적용 | 세션 귀속 1회, 중복 관찰 no-op, hold (paymentId, reason) 하나 | confirm 같은 키/다른 키/다른 내용, 같은 기관 요청 ID 재전송, 재시작 후 재요청, 30일 재생 |
| Concurrency Control | 적용 | Attempt fencing(PREPARED→DISPATCHING vs ABANDON) 전이 규칙 | 동시 confirm N건 → 전송 1, 승인 시작 vs 만료 경합, 한도 동시 점유 합계, 차단 Snapshot 적용 vs TXd 직렬화 |
| Recoverability | 적용 | timeout·NOT_FOUND·lease 만료 → UNKNOWN 유지, 종료 근거 출처 표 | 응답 유실·끊김·부분 전문·지연 결과·TX2 장애·PG/Simulator 재시작 → 원 Attempt 조회로 1건 확정 |
| Reconciliation | 적용(경계) | 상충·늦은 성공 판정 → 사건 | `AuthorizationIncidentDetected`·`Unresolved` Outbox 원자성. Case·MATCH·해제는 RECON-001(N/A 이유: FINANCIAL 소유) |
| Auditability | 적용 | 판정 근거 버전 고정, 해제 2인(요청자≠승인자) 불변식(B-3 선택 시) | 종료 근거 출처·행위자 컬럼, 판정 입력·버전·시각 저장, 운영 명령 감사 |
| Observability | 적용 | N/A(domain) — 이유: 지표는 adapter | UNKNOWN 수·최고 연령, 조회 예산 소진, hold 수, 승인 지연 지표(거래 ID label 금지), 로그에 카드·Secret·인증 증거 없음 |

---

## 13. JPA·jOOQ·BOM·Kafka/Redis(이번 변경과의 연결)

- **2단계에는 JPA 코드가 없다.** domain 타입·전이·시험만 추가한다(Entity·migration은 AUTH-001). 아래는 AUTH-001에서 이 계약을 영속화할 때의 선택을 미리 연결한 것이며 그때 실제 코드와 함께 다시 설명한다(원칙 §3 "처음 나올 때 설명").
  - **Entity/Domain 분리**: 역할 — `JpaPaymentAuthorizationEntity`·`JpaAuthorizationAttemptEntity`·`JpaInstitutionObservationEntity`가 행 모양만, Mapper가 domain과 변환. 예 — 관찰은 `@Immutable` insert-only Entity로 저장(`@OneToMany(cascade)` 없음). 이유 — cascade·orphanRemoval이 append-only 사실을 지우는 경로를 구조적으로 없앤다(관례 §9.1 선례). 주의 — 목록은 query adapter에서 조회.
  - **`@Version`과 fencing**: 역할 — Attempt PREPARED→DISPATCHING과 회복의 PREPARED→ABANDONED가 같은 행의 version 조건부 UPDATE. 예 — 둘이 동시에 version 3을 읽으면 먼저 커밋한 쪽만 성공하고 다른 쪽은 `OptimisticConflict` → DISPATCHING이 진 경우 소켓을 열지 않는다. 이유 — "전송 전 실패 확정"을 DB가 증명. 주의 — 충돌 뒤 무조건 재시도하지 말고 다시 읽어 판정, 외부 호출 동안 잠금 없음.
  - **비관적 잠금**: 역할 — 한도 사용량 행·control·Payment를 `FOR UPDATE`. 예 — 같은 MID 동시 confirm 둘이 사용량 행에서 줄을 선다. 이유 — 서로 다른 Authorization 행 사이 합계 불변식은 version만으로 못 지킨다. 주의 — 고정 순서(§4.1), lock wait timeout 분류(관례 §9).
  - **flush vs commit**: TX2의 관찰·상태·Outbox를 Port 안에서 flush해 제약 위반을 TX 안에서 드러내고, commit 단계 실패는 OutcomeUnknown(같은 키·원 Attempt 조회로 확인).
- **jOOQ(CQRS)**: 승인 결과 조회(GetPayment)·UNKNOWN 연령 목록·회복 후보 keyset 조회는 jOOQ 조회 전용 adapter(쓰기 금지 ArchUnit 기존). 조회 결과는 비권위 — 상태 변경은 JPA 잠금 재판정.
- **BOM**: 2단계 새 의존성 없음(Boot 4.1.1 BOM, Kotlin 2.3.21, Modulith 2.1.1, Fixture Monkey 1.2.3 — `gradle/libs.versions.toml`). AUTH-001의 TCP는 JDK 소켓/NIO 우선(새 의존성 없음 — 구현 결정 CA-20). Netty 등을 쓰게 되면 Boot BOM 관리 버전만 쓰고 개별 override하지 않는다.
- **Kafka/Redis**: 도입하지 않는다(ADR-001). 한도 점유·슬롯·멱등·hold의 권위는 PAYMENT DB 제약·잠금·로컬 TX다. Redis 카운터·분산 lock이나 Kafka 트랜잭션이 중복 승인 차단·한도 정합성·기관 exactly-once를 대신한다고 주장하지 않는다(원칙 §5).

---

## 14. 2단계(이 작업) 범위 대 AUTH-001 범위

### 14.1 2단계에서 할 것(domain 계약 타입·상태 전이·불변식 시험, 순수 Kotlin)

| 단계 | 내용 | 결정(2단계 상태는 §19-1) |
|---|---|---|
| S1 | `PaymentPlan`, `CardType`, Plan·cardType 검증(체크 할부 거절, Capability 판정 인터페이스 + Demo 시험 fixture) | 없음 |
| S2 | `AuthenticationSession` 전이표·바인딩·단일 귀속·만료 규칙 | 없음(TTL은 시각 입력) |
| S3 | `PaymentAuthorization`·`AuthorizationAttempt` dispatch 상태·fencing 전이, Payment 요약 일치 | 없음 |
| S4 | `InstitutionObservation`·Resolution 판정표(§5.6), `IncidentReason.CONFLICTING_INSTITUTION_RESULT`, control 상충 hold | D-AUTH-008 |
| S5 | `AuthorizationStart` domain 서비스 + ArchUnit 규칙(§4.2) | D-AUTH-005 |
| S6 | `TransactionLimitPolicy`·`DailyLimitUsage`·`LimitReservation` | D-AUTH-006·007 |
| S7(선택 B-2) | L-3 REQUESTED 종료 근거 출처 제한 + Fixture·판정표 갱신 | **AUTH-001로 이관**(Coordinator 결정, §19.3) |
| S8(선택 B-3) | `IncidentHoldRelease` domain 불변식(2인·MATCH 참조·다른 hold 유지·해제 결과별 control 반영 §8.2) | 포함 결정(domain 불변식만) |
| S9 | `DOMAIN-DEFINITION.md`(AuthenticationSession·PaymentPlan 채택 반영, G-2·G-4 갱신)·`ARCHITECTURE-CONVENTIONS.md` 새 절, 이 명세 2단계 편차 기록 | — |

질문 답이 없는 단계(S4 일부·S5 거절 경로·S6)는 해당 부분만 구현하지 않고 나머지를 진행한다.

### 14.2 AUTH-001로 넘길 것

migration(authorization·attempt·관찰·세션·한도 사용량 표, idempotency·hold·termination evidence CHECK 확장, runtime 권한), JPA Store, confirm·회복·전송 Use Case와 application 시험(§4.2-3 기준), 가상 VAN·인증기관 Simulator 실행 단위·DB, TCP Adapter·프레이밍, Simulator/REAL 격리, confirm API·오류 매핑, Outbox 이벤트 append, L-6(`ReportSuccessAfterTerminationService` 실패 분류·호출 경로 = TX2 Resolution 경로로 흡수 권장), 관측 지표. 결제창(Thymeleaf)·인증 세션 endpoint는 §18-1.

### 14.3 2단계 allowedWritePaths 제안

```text
apps/payment-api/src/main/kotlin/com/paycore/payment/payment/domain/**
apps/payment-api/src/test/kotlin/com/paycore/payment/payment/domain/**
apps/payment-api/src/test/kotlin/com/paycore/payment/architecture/ArchitectureSpec.kt
apps/payment-api/src/test/kotlin/com/paycore/archfixture/**
docs/repository/AUTH-CONTRACT-001-AUTHORIZATION-CONTRACT.md
docs/repository/DOMAIN-DEFINITION.md
docs/repository/ARCHITECTURE-CONVENTIONS.md
harness/commands.json   (테스트 수 하한만 — 기대값 완화 금지)
```

현재 tasks.json 허용 경로(`…/**/domain/**`, `src/test/**`, `docs/repository/**`, `harness/commands.json`, `harness/tests/**`)의 부분집합이다. application·adapter·migration·`src/main/resources`는 포함하지 않는다. ArchUnit의 adapter 규칙은 `adapter.institution` 패키지가 아직 없으므로 "대상 없음 허용 + 패키지 생기면 적용" 형태로 넣고, 규칙이 실제로 평가됨은 archfixture 위반 클래스로 확인한다.

### 14.4 2단계 Fixture 계획(Test Fixture Policy)

Named Fixture(src/test만): `AuthenticationSessionFixture`(`verifiedUnboundSession()`, `sessionBoundTo(authorization)`, `expiredSession()`), `AuthorizationFixture`(`authorizingWithPreparedAttempt()`, `dispatchingAttempt()`, `unknownAfterTimeout()`, `authorizedWithMatchingApproval()`, `declinedByInstitution()`), `InstitutionObservationFixture`(`approvedMatching(payment)`, `approvedWithDifferentAmount(...)` — Invalid/상충 의도 이름), Demo `LimitPolicyFixture`. 상태는 공식 factory·전이로만 만든다(reconstitute 금지). 기대값은 명세 표에서 독립 기술(구현 식 복제 금지). Fixture Monkey는 핵심 규칙 시험에 쓰지 않으며, 쓰면 관찰·금액을 Domain Factory로 일관되게 생성하는 프로젝트 Fixture 안에서만(seed 고정).

---

## 15. 구현 결정 목록(가역적, 질문 아님)

| ID | 결정 | 이유 |
|---|---|---|
| CA-01 | Authorization·세션·한도 타입을 기존 `payment` 모듈 domain에 둔다 | 같은 TX·같은 판정(관례 §7) |
| CA-02 | `PaymentAuthorization`을 Payment와 별도 Root(1:1)로 둔다. Payment는 요약 상태 유지 | Payment·control 기존 계약 불변, Attempt·관찰 목록 격리 |
| CA-03 | `Installment.months` 기술 범위 2..99(domain), 실제 허용 개월은 Capability 정책 | 운영 수치를 코드에 두지 않음(D-AUTH-001 Demo) |
| CA-04 | Capability 판정은 domain 인터페이스 + 버전 있는 정책 값 입력(Demo fixture), 정책 원본은 MASTER Snapshot 확장 | 기관 프로파일 allowlist(D-AUTH-001) |
| CA-05 | 잠금 순서 §4.1 | 기존 순서 확장, 교착 방지 |
| CA-06 | confirm 요청 fingerprint `c1:` 길이 접두 canonical SHA-256 | CP계약 §7 방식 재사용 |
| CA-07 | confirm 멱등 보존 30일(operation 공통) | D-CPC-004 값 재사용 |
| CA-08 | confirm 본문에 금액·통화·주문 ID 재진술, 불일치 거절 | 서버 대조(범위 §3-6), fail-closed |
| CA-09 | 승인 시작 업무 거절을 키 결과로 저장·재생 | D-CPC-007 선례 |
| CA-10 | 일 한도는 범위·업무일당 한 행 잠금(분할 카운터는 부하 시험 뒤) | 정합성 우선 |
| CA-11 | 승인 경로 무효과 확정 = FAILED, EXPIRED는 결제창 만료 전용 | 의미 단순화(D-CPD-002 범위 내) |
| CA-12 | Attempt 상태 이름 PREPARED/DISPATCHING/ABANDONED_BEFORE_DISPATCH/OUTCOME_UNKNOWN/RESOLVED | 허용 §5.3-2 의미의 이름 |
| CA-13 | 초기 Authorization당 dispatch Attempt 1개 | 시도 §1 상한 안의 보수적 시작 |
| CA-14 | 세션 귀속은 상태가 아니라 속성 | D-AUTH-001 상태 목록 유지 |
| CA-15 | 관찰 유일 키 (attemptId, 출처, 기관 결과 식별자/수신 순번) | 중복 응답·통보 수렴 |
| CA-16 | 관찰을 상태와 같은 TX2로 저장(선저장 TX 없음) | 같은 TX 원칙, 증거 원본은 기관 |
| CA-17 | 기관 Adapter 패키지 `payment.adapter.integration.institution`(2단계 갱신 — 기존 계층 규칙의 알려진 adapter 하위 `integration` 안. ArchUnit은 `..adapter..institution..` 전체를 본다) | 관례 §2·ArchUnit `classesResideInKnownLayers`(adapter.{web,persistence,query,integration,batch}) |
| CA-18 | confirm 경로 `POST /v1/payments/{paymentId}/confirm`, 진행 중 202 | 공개 API 관례 |
| CA-19 | eventCategory 이름 `FINANCIAL_FACT`·`RISK_INCIDENT`·`OPERATIONAL_RISK` | 기존 `INTEGRATION_NON_FINANCIAL`과 구분 |
| CA-20 | TCP는 JDK 소켓/NIO, 새 의존성 없음 | BOM 밖 의존성 회피 |
| CA-21 | confirm 동기 대기 후 진행 중 응답, 남은 처리는 회복 Worker | 요청 스레드 종료에도 UNKNOWN 회복 경로 존재 |
| CA-22 | 기관 요청 ID = PG 생성(`ar_` + ULID), namespace 컬럼과 함께 UNIQUE | 내부 업무 ID와 기관 namespace 분리(시도 §4) |

---

## 16. 질문 후보(Coordinator → 사용자) — 답변 정리(2026-10-15)

1단계 질문 7개는 모두 답변됐다. 원문 질문·권장안·대안은 `harness/questions.json`(Coordinator 소유)에 있다.

| 질문 | 답변 | 결정 | 이 명세 반영 |
|---|---|---|---|
| Q-AUTH-01 PaymentTransaction 정의(G-2) | 기관 확정 금융 효과 1건 = 거래 1건(append-only), 거절·무효과·UNKNOWN·중복 수신은 관찰, 늦은/상충 승인은 해제 판정 전 거래 없음 | **D-AUTH-003** | §3, §5.6, §11, 코드 `PaymentTransaction` |
| Q-AUTH-02 confirm 같은 키 재요청 응답 | operation 현재 결과, 승인 시작 전 거절은 저장 거절 재생, 권한 재확인 | **D-AUTH-004** | §4.4, §10.1(구현은 AUTH-001) |
| Q-AUTH-03 승인 시작 거절 시 Payment | REQUESTED 유지, 세션 미귀속, 만료 시 EXPIRED | **D-AUTH-005** | §5.1, 코드 `AuthorizationStart`·`RequestedPaymentExpiry` |
| Q-AUTH-04 한도 범위·출처·없음 | MID+통화, 사용량 환경별, 원본 MASTER TransactionLimitPolicy, 없음/모호 차단, 무제한 명시 값만(전파 MASTER-SNAPSHOT-002) | **D-AUTH-006** | §4.5, 코드 `TransactionLimitPolicies`·`LimitAmount` |
| Q-AUTH-05 일 한도 날짜·해제 | 승인 시작 시각 Asia/Seoul 날짜 고정, AUTHORIZING·UNKNOWN·hold 점유 유지, 무효과 확정 해제, 승인 소비, 취소 미복원 | **D-AUTH-007** | §4.5, 코드 `LimitBusinessDate`·`DailyLimitUsage` |
| Q-AUTH-06 종결 전 상충 승인 | UNKNOWN 유지 + hold(CONFLICTING_INSTITUTION_RESULT), 거래·AUTHORIZED 없음, 한도 점유 유지 | **D-AUTH-008** | §5.6, 코드 `AuthorizationResolution` |
| Q-AUTH-07 시간·재시도 수치 | 버전 있는 설정, 코드 운영 기본값 없음, Demo만 | **D-AUTH-009** | §5.3, §7 |

### 2단계·review에서 나온 질문 — 답변됨

| Q-AUTH-10 NO_EFFECT 해제 뒤 같은 Payment에 새 승인 증거 | 다시 hold: 해제 판정과 모순되는 증거로 새 hold 기록(기존 hold 재사용 없음), FORCED 재점유, 새 Payment 다시 차단, 해제는 다시 2인 + MATCH | **D-AUTH-012** | §5.6, §19.9, 코드 `OrderPaymentControl.openHold`(occurrence) |

| Q-AUTH-11 REAL·DOUBLE 해제 뒤 같은 Payment에 새 관찰 키의 승인(다른 PTX)·반대 결과 | 다시 hold(D-AUTH-012와 같은 처리): 새 hold(occurrence + 1), 승인이고 점유가 RELEASED면 FORCED 재점유(CONSUMED면 재점유 대상 없음), 주문 차단, 해제는 다시 2인 + MATCH | **D-AUTH-013** | §5.6, §19.9, 코드 `OrderPaymentControl.openHold` |

#### (기록) Q-AUTH-11 원래 질문 — 답변됨(D-AUTH-013, 권장안 채택)
- 질문: D-AUTH-012는 NO_EFFECT 해제 뒤만 정했다. "실제 승인"으로 해제한 뒤 같은 Payment에 새 관찰 키의 승인(예: 다른 ProviderTransactionId — 또 다른 실제 승인일 수 있음)이나 반대 결과가 오면 hold를 다시 여는가.
- 권장안: **다시 hold(D-AUTH-012와 같은 처리)** — 새 hold(occurrence + 1), 한도는 이미 CONSUMED이면 그대로, 해제는 2인 + MATCH. 해제 판정이 실제 승인 1건을 확인한 것이지 그 뒤 들어온 다른 승인까지 설명하지 않기 때문(보수적).
- 대안: 새 hold 없이 관찰만 기록(현재 2단계 동작 — Incident 없음·NoChange, 대사가 잡도록 둠).
- 영향: 같은 Payment의 세 번째 기관 승인을 놓칠 위험 vs 운영 부담.
- 막히는 부분: 없음(현재 동작이 대안으로 고정돼 있고 시험이 그것을 명시). 답이 권장안이면 `openHold`의 REAL/DOUBLE 분기 한 줄과 시험 2행을 바꾼다.

| 질문 | 답변 | 결정 | 반영 |
|---|---|---|---|
| Q-AUTH-08 확정 거절로 해제한 일 한도에 늦은 승인이 오면 다시 점유하는가 | 다시 점유(승인 시작일 날짜, 한도 초과여도 강제 기록·초과 표시, 승인 차단 근거로 쓰지 않음), 해제 판정 NO_EFFECT → 재해제, REAL_APPROVAL → 소비 | **D-AUTH-010** | §5.6, §19.7, 코드 `DailyLimitUsage.reoccupyAfterLateApproval`·`settleForced`, `IncidentHoldSettlement` |
| Q-AUTH-09 이전 generation 늦은 승인 hold 중 현재 generation 승인 확정(이중 승인 가능성) | 현재 승인은 거래·PaymentAuthorized 기록, 주문 hold 유지, 해제 판정에 "실제 이중 승인 확인 + 보정(취소) 참조" 추가, 보정 후 재대사 MATCH + 2인 승인으로 해제. 보정·해제 실행은 RECON-CONTRACT-001 | **D-AUTH-011** | §5.6, §19.7, 코드 `HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED`·`IncidentHoldRelease.correctionRef` |

(검토했으나 질문으로 올리지 않은 것: hold 해제 뒤 주문 효과 — §8.2 도출로 2단계에 구현(실제 승인 확인 → 승인 성공 이력, 무효과 확인 → D-CPD-002). 상충 hold 뒤 들어온 "일치 승인"을 자동 확정하지 않음 — D-AUTH-008 "해소는 D-AUTH-002 방식"에서 따라 나옴.)

---

## 17. 기존 코드와 계약 사이의 모순·결함

1. **늦은 승인이 주문 성공 이력으로 남지 않는다**(`OrderPaymentControl.reportSuccessAfterTermination`). 지금은 hold 해제 연산이 없어 안전하지만, 해제가 생기면 `decide()`가 hold 없음 → `authorizedPaymentId == null` → FAILED + 충분 근거로 `CreateNewPayment`를 돌려 **실제 기관 승인이 있는 주문에 새 Payment를 허용**한다(시도 §2 "승인 성공 후 재승인 금지" 위반). 해제 연산은 §8.2의 해제 판정 결과를 control에 반영해야 한다(2단계 B-3 또는 RECON 작업).
2. **상충 결과 reason 없음**: `IncidentReason`은 `SUCCESS_AFTER_TERMINATION`만, DB `ck_incident_hold_reason`도 같다. D-AUTH-002의 "상충 결과"와 AUTHORIZED 뒤 반대 관찰을 표현 못 함 → domain(2단계) + CHECK 확장(AUTH-001). `reportSuccessAfterTermination`은 FAILED/EXPIRED만 받는다.
3. **REQUESTED 종료 근거 출처 무검증(L-3)**: REQUESTED에서 `fail(TIMEOUT)`·`expire(INQUIRY_NOT_FOUND)`·`fail(INSTITUTION_CONFIRMED_DECLINE)`이 허용된다(Payment KDoc이 인정). `PaymentFixture.failedWithOnlyTimeout`·`expiredWithOnlyInquiryNotFound`는 이 계약에서 존재할 수 없는 상태다(§5.1 L-3). → **AUTH-001로 이관**(Coordinator 결정 2026-10-15, §19.3).
4. **`PRE_TRANSMISSION_FAILURE_CONFIRMED`의 증명 연결 없음**: AUTHORIZING에서 이 근거로 FAILED가 되지만 Attempt dispatch 상태와 연결이 없다 → §5.2 fencing으로 유일 출처를 정의.
5. **승인 성공 기록이 두 호출로 나뉨**: `payment.markAuthorized()`와 `control.recordAuthorized(payment)`가 별도다(L-2와 같은 짝 강제 문제). `decide()`가 `current.status == AUTHORIZED`도 보므로 즉시 위험은 낮지만 2단계 Resolution 적용에서 한 연산으로 묶을 것을 권장.
6. **`OrderPaymentControl.startAuthorization`이 Attempt 없이 호출 가능**: L-2는 Payment 전이를 control로 묶었지만 L-3(Attempt와 AUTHORIZING 같은 TX)은 domain에서 강제되지 않는다 → §4.2-2 domain 서비스 + ArchUnit.
7. **스키마 제약이 CreatePayment 전용**: `idempotency_request.ck_idempotency_request_result`의 result_code 목록, `payment_termination_evidence`의 출처·시각·행위자 부재 → AUTH-001 migration. **(AUTH-001로 이관)**
8. **`ReportSuccessAfterTerminationService`**: 호출 경로 없음, 대상 없음 시 `NoSuchElementException`(분류 없음), Outbox 사건 이벤트 없음(대사 Case 연결 불가) → AUTH-001 L-6에서 TX2 Resolution 경로로 흡수 권장. **(AUTH-001로 이관)**
9. **문서 현행화**: `DOMAIN-DEFINITION.md` §1·AuthenticationSession 행이 "채택 전(Q-AUTH-SCOPE)", G-4가 "미결"로 남아 D-AUTH-001·002 이후와 다르다 → 2단계 S9.
10. **권리 Snapshot에 승인 시작 조건 데이터 없음**: 카드사/Route(D-MD-017), (CSA, MID, 통화) 정책 Assignment(D-MD-018), 수수료·정산 정책 버전(허용 §4.1-9), 거래 한도 — 전파되기 전에는 승인 시작이 fail-closed(Unavailable)여야 한다(§18-3).

---

## 18. 범위 관찰·범위 밖(Coordinator 확인)

1. **결제창·인증 세션 endpoint** → **AUTHN-001 BACKLOG 등록됨**(Coordinator, 2026-10-15). confirm은 VERIFIED 세션이 있어야 동작한다.
2. **B-2(L-3 앞당김)** → 2단계 포함 결정 뒤 **철회, AUTH-001로 이관**(Coordinator 2026-10-15: 허용 경로 밖 시험 6개 파일 재설계와 함께 AUTH-001 AC에 기록, §19.3).
3. **MASTER Snapshot 확장** → **MASTER-SNAPSHOT-002 BACKLOG 등록됨**(승인 시작 조건 §17-10과 TransactionLimitPolicy 전파, D-AUTH-006). AUTH-001이 의존한다.
4. **Webhook 전달** → **WEBHOOK-001 BACKLOG 등록됨**. 서명 방식은 그 작업의 보안 질문 후보.
5. **B-3(hold 해제 domain)** → 2단계에 domain 불변식만 포함(§19-1 S8). 실행 Use Case는 RECON-CONTRACT-001·Q-ADMIN-AUTH 이후.
6. **취소** → **CANCEL-CONTRACT-001**(2단계 domain 구현 — `PaymentTransaction`은 sealed: `AuthorizationTransaction`·`CancelTransaction`, D-CANCEL-001~012; 구현은 CANCEL-001)(부분취소·반환 가능 금액 G-5, CANCEL 거래 타입).
7. 범위 밖: 매입·정산·대사 Case 구현(FINANCIAL), 운영자 인증(Q-ADMIN-AUTH), 지급계좌 변경의 신규 승인 차단 여부(허용 §4.1-10 별도 운영 정책), 실제 기관 전문·인증서·인증 시험(허용 §5.6), 외화 정산(Q-SETTLE-CURRENCY), 무이자·부분무이자·해외 카드(초기 제외).

---

## 19. 2단계 구현 편차·결정 기록(2026-10-15)

### 19.1 S별 상태(구현자 보고 — Verifier/Reviewer 판정 아님)

| 단계 | 상태 | 코드(`apps/payment-api/src/main/kotlin/com/paycore/payment/payment/domain/`) | 시험(`src/test/.../payment/domain/`, `architecture/`) |
|---|---|---|---|
| S1 PaymentPlan·CardType | 구현 | `PaymentPlan.kt`(PaymentPlan·CardType·PaymentPlanPolicy·PlanRejection·PaymentPlanRule) | `PaymentPlanSpec` |
| S2 AuthenticationSession | 구현 | `AuthenticationSession.kt` | `AuthenticationSessionSpec` |
| S3 Authorization·Attempt | 구현 | `PaymentAuthorization.kt`(+ `AuthorizationIds.kt`) | `AuthorizationResolutionSpec`(dispatch 전이 묶음) |
| S4 관찰·판정표·상충 hold | 구현(D-AUTH-008 반영) | `PaymentAuthorization.kt`(InstitutionObservation·PaymentTransaction), `AuthorizationResolution.kt`, `OrderPaymentControl.kt`(CONFLICTING_INSTITUTION_RESULT·reportConflictingResult·completeAuthorization) | `AuthorizationResolutionSpec` |
| S5 AuthorizationStart + ArchUnit | 구현(D-AUTH-005 반영) | `AuthorizationStart.kt`, control `checkAuthorizationStart` internal | `AuthorizationStartSpec`, `ArchitectureSpec` 3개 규칙 + 위반 fixture 4개 |
| S6 한도 | 구현(D-AUTH-006·007 반영) | `TransactionLimit.kt` | `TransactionLimitSpec` |
| S7 L-3(B-2) | **AUTH-001로 이관**(Coordinator 결정, §19.3) | 변경 없음 | — |
| S8 IncidentHoldRelease(B-3) | 구현(domain 불변식만) | `OrderPaymentControl.kt`(IncidentHoldRelease·HoldReleaseFinding·releaseIncidentHold·activeIncidentHolds·reconstitute 기본 인자) | `IncidentHoldReleaseSpec` |
| S9 문서 | 구현 | 이 명세 §16·§18·§19, `DOMAIN-DEFINITION.md`, `ARCHITECTURE-CONVENTIONS.md` §12 | — |
| 결함 §17-1~6 | 1·2·4·5·6 처리, 3 = AUTH-001 이관(7·8도 AUTH-001) | 1: 해제 `REAL_APPROVAL_CONFIRMED` → 승인 성공 이력. 2: 상충 reason. 4: `AbandonCause`만 PRE_TRANSMISSION 출처 + ArchUnit. 5: `completeAuthorization`. 6: `AuthorizationStart` + ArchUnit | 위 시험 |
| 추가 | `RequestedPaymentExpiry`(결제창 만료가 세션 폐기 조건을 직접 만족) | `RequestedPaymentExpiry.kt` | `RequestedPaymentExpirySpec` |

### 19.2 명세와 다른 점·구현 결정

1. **슬롯 위반은 결과 값이 아니라 기존 예외**: `AuthorizationStart`는 stale·REQUESTED 아님·hold·과거 승인을 기존 예외(`StaleSlotException`·`InvalidPaymentTransitionException`·`AuthorizationStartRejectedException`)로 던지고, 인증 근거·Plan·한도 정책·한도 거절만 `Rejected`로 돌려준다(CreatePayment 관례와 같다). application(AUTH-001)이 둘 다 저장 거절로 매핑한다.
2. **`checkAuthorizationStart`를 private → internal**: 다른 Aggregate를 바꾸기 전에 슬롯 거절을 먼저 판정하려고 모듈 internal로 바꿨다(상태를 바꾸지 않는 검사). 공개 연산은 여전히 `startAuthorization` 하나.
3. **BindingRejection에 AMOUNT_MISMATCH 추가**: 세션 바인딩 금액이 승인 금액(Payment 고정 입력)과 다르면 귀속하지 않는다. 바인딩 전체 불일치는 verify 단계에서 세션 FAILED(BINDING_MISMATCH).
4. **AuthorizationStart가 Payment 고정 입력을 대조**: 명령의 `OrderIntent`(Site·Money)를 `payment.intent` fingerprint와 비교한다 — application이 다른 금액을 끼울 수 없다. 일 한도 사용량 키(환경·MID·통화·Asia/Seoul 날짜)도 domain이 계산해 대조한다.
5. **PaymentTransaction은 Authorization 안의 값**: D-AUTH-003의 거래 1건을 `PaymentAuthorization.transaction`으로 두고 AUTHORIZED ⇔ 거래 존재를 강제한다. 거래 ID는 application 생성기가 넘긴다. 이벤트 effect_key는 `PaymentAuthorized:{transactionId}`로 바꿨다(§11).
6. **상충 hold 뒤 결과는 기록만**: 같은 Payment에 CONFLICTING hold가 활성이면 이후 관찰은 기록만 하고 자동 확정·종료하지 않는다(D-AUTH-008 "해소는 D-AUTH-002 방식"에서 도출).
7. **보내지 않은 Attempt의 확정 결과**: PREPARED Attempt에 APPROVED/DECLINED/NO_EFFECT가 오면 계약 가정 위반으로 UNKNOWN + CONFLICTING hold. NOT_FOUND 등은 기록만.
8. **늦은 승인과 해제된 한도** → Q-AUTH-08 답변 **D-AUTH-010** 반영(round1 n-1): FORCED 재기록·초과 표시·차단 근거 아님, 해제 판정으로 RELEASED/CONSUMED(§19.7).
9. **해제 기록 영속화 없음**: `OrderPaymentControl.reconstitute`에 `incidentHoldReleases` 기본값(빈 목록)을 추가해 adapter Mapper를 바꾸지 않았다. 해제 Use Case·표는 RECON 이후이며 그 전에는 복원된 hold가 활성으로 남는다(fail-closed). `incidentHolds`는 계속 전체 목록(해제 포함, append-only)이고 Store의 append-only 검사도 그대로다.
10. **`PaymentAuthorization.reconstitute` 미구현**: 영속 표가 AUTH-001이라 Mapper와 함께 추가한다(지금 만들면 시험 없는 production API가 생긴다).
11. **ArchUnit 이름 비교**: value class 인자를 받는 Kotlin 함수는 JVM 이름이 `reserve-jHxBwU8`처럼 바뀐다. 처음 규칙은 정확한 이름으로 비교해 **실제 실행에서 양성 확인이 실패**했다(아래 RED-A1) → `kotlinSourceName`으로 소스 이름을 비교한다. 이 문제는 기존 `paymentStartAuthorizationOnlyThroughControl`(인자 Payment — value class 아님)에는 해당하지 않는다.
12. **기관 Adapter 패키지**: `payment.adapter.institution`은 기존 계층 규칙(adapter.{web,persistence,query,integration,batch})에 없어 CA-17을 `payment.adapter.integration.institution`으로 바꿨다. 규칙은 `..adapter..institution..`을 본다.

### 19.3 S7(L-3, B-2) — AUTH-001로 이관(Coordinator 결정 2026-10-15)

**결정**: S7과 §17 결함 3은 AUTH-001로 넘어갔다(아래 6개 시험 파일 재설계와 함께 AUTH-001 AC에 기록). §17 결함 7·8도 AUTH-001. 이 작업의 domain 코드는 REQUESTED 종료 근거를 제한하지 않는다. 아래는 이관 근거가 된 영향 분석이다.

REQUESTED 종료 근거 출처 제한(REQUESTED에서 `INSTITUTION_CONFIRMED_DECLINE`·`INSTITUTION_CONFIRMED_NO_EFFECT`·`PRE_TRANSMISSION_FAILURE_CONFIRMED`·`TIMEOUT`·`INQUIRY_NOT_FOUND`·`LEASE_EXPIRED` 거부)은 domain 몇 줄이지만, 허용 경로 **밖** 시험이 REQUESTED Payment를 이 근거들로 종료해 상태를 준비한다. 컴파일 문제가 아니라 시험 기대·설계 변경이라 지시대로 고치기 전에 멈췄다.

| 파일(허용 경로 밖) | 위치 | 현재 준비 | 필요한 변경 |
|---|---|---|---|
| `src/test/.../application/CreatePaymentServiceSpec.kt` | 274, 287 | REQUESTED에서 `fail(TIMEOUT)`, `fail(PRE_TRANSMISSION_FAILURE_CONFIRMED)` | 274행(TIMEOUT 종료 → 새 Payment 차단)은 **도달 불가 상태**가 되므로 legacy 행 복원(reconstitute) 또는 행 삭제·재설계, 287행은 승인 시작 경유 |
| `src/test/.../application/CreatePaymentRollbackSpec.kt` | 63 | REQUESTED `fail(DECLINE)` | 승인 시작 경유(`c.startAuthorization(p)` 후 fail) |
| `src/test/.../adapter/web/CreatePaymentApiSpec.kt` | 180, 209–212, 215, 249 | REQUESTED `fail(DECLINE)`, `fail/expire(TIMEOUT·INQUIRY_NOT_FOUND·LEASE_EXPIRED)`, `fail(PRE_TRANSMISSION)` | 209–212행(불충분 근거 4종 → 409)은 도달 불가 상태 — 기대값 행 재설계 필요(예: legacy 행 직접 삽입), 나머지는 승인 시작 경유 |
| `src/test/.../adapter/web/ApiFailureSpec.kt` | 373, 379 | REQUESTED `fail(TIMEOUT)`, `fail(DECLINE)` | 373 재설계, 379 승인 시작 경유 |
| `src/test/.../adapter/persistence/UtcStorageSpec.kt` | 113 | REQUESTED `fail(PRE_TRANSMISSION)` | 승인 시작 경유 |
| `src/test/.../adapter/persistence/PaymentStoreAdapterSpec.kt` | 228 | REQUESTED `fail(DECLINE)` | 승인 시작 경유 |
| (허용 경로 안) `PaymentFixture`·`OrderPaymentDecisionTableSpec`·`PaymentSpec`·`TerminationEvidenceSpec` | REQUESTED 경로 fixture·판정표 행 | 같은 변경에서 갱신 |

`expire(null)`·`expire(NOT_TRANSMITTED_AND_SESSION_DISCARDED)`·`fail(null)` 준비(IncidentHoldConvergenceSpec·DatabaseAccountSeparationSpec·PaymentStoreAdapterSpec 162·262, CreatePaymentApiSpec 194)는 제한 뒤에도 유효하다. **Coordinator 결정 필요**: (a) 위 6개 파일을 이 작업 허용 경로에 추가해 S7을 진행, (b) S7을 AUTH-001(원래 AC 소유)로 되돌림. 어느 쪽이든 "불충분 근거 종료 → 새 Payment 차단" 시험 행(CP-06)은 정상 경로로 만들 수 없게 되므로 legacy 행(reconstitute) 시험으로 바꾸는 것이 기대값 완화가 아님을 Reviewer가 확인해야 한다.

### 19.4 RED 확인

- **RED-C(컴파일)**: 새 시험 파일은 대상 타입이 없으면 컴파일되지 않는다 — 다만 이번에는 main 타입을 먼저 쓰고 시험을 뒤에 썼으므로 엄격한 시험 선행 RED가 아니다(CP-API-001 §20-13과 같은 방식으로 변이 RED를 대신 실행).
- **RED-A1(실행)**: ArchUnit 양성 확인(`AuthorizationStart`가 네 연산을 부른다)이 JVM 이름 변형 때문에 실패 → `kotlinSourceName` 도입 후 통과(§19.2-11).
- **RED-A2(위반 fixture)**: `DirectControlStartAuthorization`, `DirectSessionBindingAndReservation`(startauth), `DirectMarkAuthorized`(transition), `InstitutionAdapterMutatingPayment`(adapter.institution)를 각 규칙에 넣으면 `AssertionError`(메시지에 fixture 이름) — 시험이 이 실패를 기대값으로 확인한다.
- **변이 RED**(main 변이 → 대상 Spec 실행 → 원복, `/tmp/mut.sh`, 2026-10-15 13:1x KST):

| 변이 | 대상 Spec | 결과 |
|---|---|---|
| M1 `AuthorizationStart`가 검사 전에 control.startAuthorization 호출 | AuthorizationStartSpec | 10건 중 6 실패 |
| M2 NOT_FOUND를 무효과 확정으로 종료 | AuthorizationResolutionSpec | 19건 중 1 실패 |
| M3 일 한도가 CONSUMED를 세지 않음 | TransactionLimitSpec | 10건 중 1 실패 |
| M4 해제 `REAL_APPROVAL_CONFIRMED`가 승인 이력을 남기지 않음(§17-1 결함 재현) | IncidentHoldReleaseSpec | 9건 중 1 실패 |
| M5 조건 불일치 승인을 상충으로 보지 않음 | AuthorizationResolutionSpec | 19건 중 2 실패 |
| M6 귀속 세션 재귀속 허용 | AuthenticationSessionSpec | 14건 중 1 실패 |

변이는 모두 원복했고 아래 전체 실행은 원복 뒤다.

### 19.5 실행 결과(구현자 실행, Verifier 판정 아님)

- 환경: WSL2 Ubuntu, Docker Desktop 실행 중(Testcontainers MySQL 8.4.11), 기준 commit `0aa3c1e` + 이 작업의 미커밋 변경.
- `./gradlew --stop` exit 0 (2026-10-15 13:19:24 KST) → `./gradlew :apps:payment-api:test` **exit 0**, JUnit XML 54파일 **677건, 실패 0·오류 0·skip 0**(이전 602건 → +75) → `./gradlew :apps:payment-api:check` **exit 0**(verifyNoInstancio, verifyRuntimeHasNoTestTools: runtime 113 모듈에 시험 도구 없음), 종료 13:23:54 KST.
- 기존 시험(application·persistence·web 포함) 전부 통과 — 허용 경로 밖 파일은 수정하지 않았다(adapter Mapper도 그대로 컴파일됨: `reconstitute` 새 인자는 기본값).
- `python3 -B -m unittest discover -s harness/tests`: 71건 OK.
- `harness/commands.json`: GRADLE-CHECK `minTests`·TEST-SUMMARY 하한 999 → 1074(이번 증가분 75만 더함 — 다른 모듈 수는 이번에 다시 세지 않았다). 전체 VERIFY-GATE·HARNESS-SELFTEST 판정은 Verifier 몫.

### 19.6 8대 원칙 증거(2단계분)

| 원칙 | 2단계 증거 | 남은 증거(작업) |
|---|---|---|
| Financial Consistency | 전액 승인만 거래(`matchesRequest`), AUTHORIZED ⇔ 거래 1건, 중복 승인 관찰은 NoChange, 상충·늦은 승인은 거래 없음, 한도 합 불변식·overflow(`AuthorizationResolutionSpec`·`TransactionLimitSpec`) | 금액 보존·DB append-only(AUTH-001) |
| Transaction Safety | 다중 Aggregate 연산은 검사 후 변경 — 거절·결함 시 모든 Aggregate 불변(`AuthorizationStartSpec` shouldBeUntouched, M1 RED) | TX1/TX2 rollback·응답 유실(AUTH-001) |
| Idempotency | 세션 귀속 1회·중복 callback·관찰 키 중복·hold (paymentId, reason) 하나·한도 consume/release 멱등 | confirm 키 재요청(AUTH-001) |
| Concurrency Control | 해당 없음(domain 단일 스레드). 경합 규칙(fencing 전이, 한도 합)만 정의 | 실DB 동시 confirm·전송 vs 회복·한도 경합(AUTH-001) |
| Recoverability | timeout·NOT_FOUND·PENDING·lease 만료 → UNKNOWN 유지, PREPARED는 UNKNOWN 불가, 전송 전 포기만 PRE_TRANSMISSION 근거(M2 RED) | 원 Attempt 조회·재시작(AUTH-001) |
| Reconciliation | 상충·늦은 승인 → Incident 결과, 해제는 재대사 MATCH 참조 필수, 실제 승인/무효과 판정별 주문 효과(M4 RED) | Case·MATCH·해제 Use Case(RECON-001, Q-ADMIN-AUTH) |
| Auditability | 승인 근거 버전(권리·정책 Assignment·Route·프로토콜·Plan/한도 정책)·판정 시각 고정, 해제 요청자≠승인자·사유, 관찰·거래 출처 키 | 행위자 컬럼·감사 저장(AUTH-001) |
| Observability | 해당 없음(domain). `UnknownCause` 진단 분류만 제공 | UNKNOWN 연령·hold 지표(AUTH-001) |

### 19.7 review round1(REVIEW_FAIL, `d0adde81…`) 수정 — 수정 시도 2/3(2026-10-15)

정책·기대값·게이트 기준은 완화하지 않았다. n-1의 기대값 변경은 사용자 답변 D-AUTH-010 반영이다(이전 기대값 "RELEASED 유지"는 미결 상태의
잠정 동작이었다). 이월(손대지 않음): m-2 실행·m-3 → RECON-CONTRACT-001, n-2·n-3·n-4 → AUTH-001, n-7 수용.

| 지적·결정 | 수정 | 증거(시험) · RED |
|---|---|---|
| M-1 AUTHORIZED 뒤 "같은 승인" | 같은 승인 = `ApprovedTerms` 전체 일치(금액·통화·Plan·cardType·**ProviderTransactionId·승인번호·기관 거래일**). 하나라도 다르면 상태 유지 + CONFLICTING hold, 두 번째 거래 없음. §5.6 표에 정의 명시 | `AuthorizationResolutionSpec` "same terms but a different ProviderTransactionId, approval number or institution date"(3행)·"all identifiers and terms equal". RED R1(조건만 비교로 되돌림) → 1 실패 |
| m-1 같은 키·다른 내용 | 조용히 버리지 않는다: 첫 관찰 유지 + `keyConflictingObservations`에 append + CONFLICTING hold(진행 중이면 UNKNOWN). 같은 상충 내용의 재수신만 효과 없음. 결함 예외 대신 사건을 택한 이유는 §5.6 아래 문단. `reportConflictingResult`가 FAILED도 받는다 | `AuthorizationResolutionSpec` "same observation key arriving with different content" 2개. RED R2(다른 내용도 중복으로 취급) → 2 실패 |
| m-4 ArchUnit fixture | 규칙 1: 대상마다 fixture 하나(`DirectControlStartAuthorization`·`DirectAuthorizationOpen`(Companion.open)·`DirectSessionBinding`·`DirectLimitReservation`), 각 fixture의 위반이 정확히 1건이고 대상 FQN이 상세에 있음. 규칙 2: 소유자별 fixture(`DirectPaymentTransitions` 5·`DirectControlTransitions` 3(recordAuthorized·completeAuthorization·releaseIncidentHold)·`DirectLimitTransitions` 4(consume·release·reoccupyAfterLateApproval·settleForced)·`DirectAuthorizationTransitions` 8(PaymentAuthorization/Attempt internal 전이)), 대상마다 위반 1건(이름 끝 경계 검사 — `release`가 `releaseIncidentHold`에 맞지 않음). 규칙 2 대상 목록에 releaseIncidentHold·reoccupyAfterLateApproval·settleForced 추가 | `ArchitectureSpec`. RED R7(규칙에서 releaseIncidentHold 제거) → 1 실패, R8(규칙에서 Companion.open 제거) → 1 실패 |
| m-5 이관 기록 | §19.3에 S7 이관 결정, §17 결함 3·7·8에 "AUTH-001로 이관", §18-2·§19.1 갱신 | 문서 |
| n-1 / D-AUTH-010 | 늦은 승인 → `DailyLimitUsage.reoccupyAfterLateApproval`: RELEASED → **FORCED**(승인 시작일 날짜 = 같은 사용량 행, 초과 여부 `LimitExcess` WITHIN/EXCEEDED/UNDETERMINED 기록). (이 행의 첫 구현은 FORCED를 새 승인 판정 합에서 뺐으나 **§19.8에서 정정** — 지금은 포함.) 해제는 `IncidentHoldSettlement.release`가 control 해제 + `settleForced`(NO_EFFECT → RELEASED, REAL·DOUBLE → CONSUMED)를 한 연산으로. `applyObservation`에 `limitPolicy`(초과 표시용, nullable) 인자 추가 | `AuthorizationResolutionSpec` FAILED 묶음 3행(185–186 기대값 = FORCED/WITHIN, 초과 EXCEEDED·차단 제외, 정책 없음 UNDETERMINED·중복 없음), `TransactionLimitSpec` 3행, `IncidentHoldReleaseSpec` "hold release settles the forced limit". RED R3(재점유 호출 제거) → 4 실패. R6은 §19.8에서 반대 방향으로 다시 확인 |
| D-AUTH-011 이중 승인 | (현재 동작 고정) 이전 generation hold 중 현재 generation 일치 승인 → 거래·AUTHORIZED, hold 유지 — 코드 변경 없이 시험으로 고정. 해제 판정 `DOUBLE_APPROVAL_CORRECTED` 추가: `IncidentHoldRelease.correctionRef`(보정·취소 참조) 필수(그 밖의 판정에는 금지), MATCH 참조·2인 승인은 그대로. 해제 시 주문에 이 hold 대상이 아닌 승인 성공 이력이 있어야 하고 그 이력을 바꾸지 않는다(새 Payment 계속 금지). REAL_APPROVAL_CONFIRMED는 다른 승인 이력이 있으면 거절(메시지가 DOUBLE 판정을 안내) | `AuthorizationResolutionSpec` "earlier generation on hold while the current generation's approval arrives", `IncidentHoldReleaseSpec` double approval 4행. RED R4(DOUBLE이 다른 승인 이력 검사 생략) → 1 실패 |
| n-5 recordAuthorized | `internal`로 바꿈(같은 모듈 시험의 상태 준비만 사용, main 호출자 없음 — ArchUnit 규칙 2 대상 유지) | 컴파일·기존 시험 통과 |
| n-6 reconstitute 일관성 | 복원 시 REAL 해제 → `authorizedPaymentId == 해제 대상`, DOUBLE 해제 → 다른 승인 이력 존재를 검사 | `IncidentHoldReleaseSpec` "stored releases must match". RED R5(REAL 검사 제거) → 1 실패 |

**해석 주의**: 이 round의 첫 구현은 "승인 차단 근거로 쓰지 않음"을 판정 합 제외로 읽었다 — §19.8에서 Coordinator가 정정했다.

변이 RED는 `/tmp/mut2.sh`·`/tmp/mut3.sh`(main 또는 시험 규칙 변이 → 대상 Spec → 원복). 원복 뒤 아래 전체 실행.

**실행 결과(구현자 실행, Verifier 판정 아님)**: `./gradlew --stop` exit 0(2026-10-15 17:03:23 KST) → `./gradlew :apps:payment-api:test` **exit 0**, JUnit XML 54파일 **694건, 실패 0·오류 0·skip 0**(round1 전 677 → +17) → `./gradlew :apps:payment-api:check` **exit 0**(verifyNoInstancio, verifyRuntimeHasNoTestTools: runtime 113 모듈에 시험 도구 없음), 종료 17:06:59. `harness/commands.json` 하한 1074 → **1091**(+17). 허용 경로 밖 변경 없음.

### 19.8 D-AUTH-010 해석 정정(Coordinator 2026-10-15) — FORCED는 새 승인 한도 판정 합에 포함

- **정정 근거**: 사용자가 고른 Q-AUTH-08 선택지 원문은 "기관에 실제 승인이 있을 수 있는 동안 한도에 반영"이다. DECISIONS의 "승인 차단 근거로
  쓰지 않음"은 "한도를 넘었다는 이유로 늦은 승인 기록·재점유 자체를 거부하지 않는다"는 뜻이었다(Coordinator가 D-AUTH-010 문구를 고침). 따라서
  §19.7의 판정 합 제외는 결정 원문과 달랐고, 이번 기대값 변경은 완화가 아니라 사용자 결정 원문 반영이다.
- **코드**: `DailyLimitUsage.occupiedMinor` = RESERVED + CONSUMED + **FORCED**(`forcedMinor`는 그중 FORCED 부분 표시). `reoccupyAfterLateApproval`은
  한도 초과여도 기록하고 `LimitExcess`(WITHIN/EXCEEDED/UNDETERMINED)를 남긴다(초과 판정 = 기존 점유 합 + 이 금액 > 일 한도). 해제 판정 NO_EFFECT →
  RELEASED(합에서 빠짐), REAL·DOUBLE → CONSUMED(합에 남음, 취소 미복원). FORCED가 한도를 넘기면 그동안 새 승인은 DAILY_LIMIT_EXCEEDED로 거절된다.
- **시험 기대값 변경**: `AuthorizationResolutionSpec`(FAILED 늦은 승인: `occupiedMinor` 0 → 10,000, 일 5,000원 한도에서 1원도 거절, NO_EFFECT 정리 뒤
  5,000원 허용), `TransactionLimitSpec`(FORCED 행: `occupiedMinor` 1,000,000 → 2,000,000, 추가 1원 DAILY_LIMIT_EXCEEDED). 시험 이름도 "counts against
  new authorizations"로 바꿨다.
- **표시 기준(round2 n-8)**: `LimitExcess`는 호출자가 넘긴 **현재** 적용 정책 기준이며 Authorization에 고정된 `limitPolicyVersion`(승인 시작 시 정책)과 다를 수 있다 — 표시 전용이고 판정·차단에 쓰지 않는다.
- **변이 RED(R6′, `/tmp/mut4.sh`)**: `occupiedMinor`에서 FORCED를 빼는 변이 → `TransactionLimitSpec`+`AuthorizationResolutionSpec` 39건 중 3 실패(exit 1),
  원복 확인(cmp 동일).

- **실행 결과(구현자 실행, Verifier 판정 아님)**: ./gradlew --stop exit 0(2026-10-15 17:11:02 KST) → ./gradlew :apps:payment-api:test **exit 0**, JUnit XML 54파일 **694건, 실패 0·오류 0·skip 0**(시험 수 변화 없음 — 기존 행의 기대값만 정정) → ./gradlew :apps:payment-api:check **exit 0**(verifyNoInstancio, verifyRuntimeHasNoTestTools: runtime 113 모듈), 종료 17:14:57. 시험 수가 그대로라 harness/commands.json 하한(1091)은 바꾸지 않았다.

### 19.9 review round2(REVIEW_FAIL, `3685fe1e…`) 수정 — 수정 시도 3/3(2026-10-15)

round1 지적은 해소됐다(Coordinator). 정책·기대값·게이트는 완화하지 않았다. 기존 시험의 기대값 변경은 1건 — `IncidentHoldReleaseSpec`의 "release 뒤 같은 보고는
새 hold를 열지 않는다"를 D-AUTH-012(사용자 답변 "다시 hold")에 맞게 "NO_EFFECT 해제 뒤 새 hold(occurrence 2)"로 바꿨다. 이월(손대지 않음): m-8 → AUTH-001·RECON
Use Case 시험 기준, sourceResultId 정의 → AUTH-001 Adapter 계약.

| 지적·결정 | 수정 | 증거(시험) · RED(`/tmp/mut5.sh`, 수정 전 동작으로 되돌리는 변이 → 대상 Spec → 원복·cmp 확인) |
|---|---|---|
| M-2 같은 키 재수신이 상충으로 오판(회귀) | `InstitutionObservation.sameInstitutionContent`: attemptId·source·sourceResultId·outcome·approved terms만 비교. observedAt(PG 수신 시각)과 evidenceHash는 비교 제외, 증거로만 보존. evidenceHash 제외 근거: 원문(전문·통보 본문)에 기관 발송 시각·전송 순번·서명 nonce처럼 수신마다 달라질 수 있는 값이 없다는 보장이 없다(가상 기관 전문 형식은 AUTH-001에서 정함) — 같은 결과를 hash 차이로 상충 처리하면 거짓 hold가 생긴다. 내용이 실제로 다르면 outcome·terms에서 드러난다 | `AuthorizationResolutionSpec` "same institution content but different receipt metadata" 4행(OPEN·UNKNOWN·AUTHORIZED·FAILED, observedAt·evidenceHash 다름 → NoChange, hold·상충 증거 없음). 변이 M2(data class 전체 비교로 되돌림) → 4 실패 |
| M-3 REAL 해제 뒤 다른 generation 승인이 기록되지 않음 | (b) `OrderPaymentControl.completeAuthorization`이 다른 승인 이력이 있어도 예외 없이 Payment AUTHORIZED + 이중 승인 CONFLICTING hold(주문 이력 기존 유지)를 하고 true를 돌려준다. `ResolutionOutcome.Authorized(transaction, incident)`로 거래와 사건을 함께 알린다(application은 PaymentAuthorized + AuthorizationIncidentDetected). 해제는 DOUBLE_APPROVAL_CORRECTED | `AuthorizationResolutionSpec` "approval for the current generation after the previous generation's hold was released as REAL": gen1 FAILED → gen2 UNKNOWN → gen1 늦은 승인 hold → REAL 해제 → gen2 APPROVED = 거래 + AUTHORIZED + hold, 예외 없음, 이력 gen1, DOUBLE 해제 뒤에도 새 Payment 금지. 변이 M3(다른 이력이면 예외) → 1 실패 |
| m-6 D-AUTH-012 | `openHold`: 활성 hold 있으면 멱등, 이전 hold가 모두 해제됐고 마지막 해제가 NO_EFFECT면 새 hold(`IncidentHold.occurrence` + 1), REAL·DOUBLE이면 열지 않음(Q-AUTH-11). Resolution은 **활성 hold가 있을 때만** Incident를 돌려주고 그때만 FORCED 재점유한다. `IncidentHoldSettlement`는 같은 Payment에 다른 활성 hold가 남으면 FORCED를 정리하지 않는다 — 활성 hold ⇔ FORCED ⇔ Incident 일치. `reconstitute`는 (paymentId, reason)별 occurrence 1..n 검사 | `AuthorizationResolutionSpec` "new approval evidence after a NO_EFFECT release" 2행, `IncidentHoldReleaseSpec` D-AUTH-012·REAL 뒤·occurrence 검사 3행. 변이 m6(NO_EFFECT 뒤 새 hold 안 엶) → 2 실패 |
| m-7 FAILED KEY_CONFLICT 승인 | KEY_CONFLICT가 FAILED 상태의 APPROVED면 hold와 함께 한도 재점유(D-AUTH-010) | `AuthorizationResolutionSpec` "FAILED authorization receiving a KEY_CONFLICT approval" 2행(승인 → FORCED, 비승인 → RELEASED 유지). 변이 m7(재점유 삭제) → 1 실패 |
| n-8 LimitExcess 기준 | §19.8에 "현재 정책 기준, 고정 limitPolicyVersion과 다를 수 있음, 표시 전용" 한 줄 | 문서 |

**영속 영향(AUTH-001로 이관)**: `incident_hold`의 UNIQUE(OrderKey, payment_id, reason)는 D-AUTH-012의 두 번째 hold를 거부한다 → AUTH-001 migration에서
`occurrence` 컬럼(≥ 1)을 추가하고 UNIQUE에 넣는다. `JpaOrderPaymentControlStore.update`의 append-only 검사(현재 (paymentId, reason) 쌍 비교)와 Mapper(현재
occurrence 기본값 1로 복원)도 occurrence를 다뤄야 하며, hold 해제 기록 표(`incident_hold_release`: hold 참조·요청자·승인자·MATCH·보정 참조·판정·시각)와
`DailyLimitUsage`의 FORCED·excess 컬럼도 같은 작업 범위다. 지금 main의 application은 hold를 해제하지 않으므로 두 번째 hold가 저장 경로에 도달하지 않는다.
`ReportSuccessAfterTerminationService`(관찰 키 없이 보고)는 D-AUTH-012 이후 해제 뒤 재보고를 새 hold로 만든다 — AUTH-001 L-6에서 이 서비스를 관찰 키를 가진
`AuthorizationResolution` 경로로 흡수해야 중복 보고가 새 hold가 되지 않는다.

**실행 결과(구현자 실행, Verifier 판정 아님)**: ./gradlew --stop exit 0(2026-10-15 18:47:41 KST) → ./gradlew :apps:payment-api:test **exit 0**, JUnit XML 54파일 **705건, 실패 0·오류 0·skip 0**(694 → +11) → ./gradlew :apps:payment-api:check **exit 0**(verifyNoInstancio, verifyRuntimeHasNoTestTools: runtime 113 모듈), 종료 18:51:20. harness/commands.json 하한 1091 → **1102**(+11). 허용 경로 밖 변경 없음(application의 ReportSuccessAfterTerminationService는 반환값을 무시하는 기존 호출 그대로 컴파일).

#### 19.9.1 D-AUTH-013 반영(Q-AUTH-11 사용자 답변 "다시 hold", 2026-10-15 — 결함 수정이 아니라 결정 반영)

- **근거**: 해제 판정(REAL·DOUBLE)은 그때 재대사로 확인한 승인만 설명한다. 그 뒤 같은 Payment에 새 관찰 키의 승인(다른 PTX — 또 다른 실제 승인일 수 있음)이나
  반대 결과가 오면 판정이 설명하지 못하는 새 증거이므로 D-AUTH-012와 같이 다시 hold한다.
- **코드**: `OrderPaymentControl.openHold`에서 "마지막 해제가 REAL·DOUBLE이면 열지 않음" 분기를 지웠다 — 이전 hold가 모두 해제됐으면 판정과 무관하게
  새 hold(occurrence + 1). 호출은 Resolution의 새 관찰 키 경로에서만 일어난다(같은 키·같은 내용은 DUPLICATE로 먼저 걸러진다).
- **일관성**: Incident ⇔ 활성 hold(항상). FORCED는 승인 증거이고 점유가 RELEASED일 때만 생긴다 — REAL·DOUBLE 해제 뒤 점유는 이미 CONSUMED라 재점유 대상이
  없고(`reoccupyAfterLateApproval`은 RELEASED가 아니면 효과 없음), 일 승인 총액에 이미 들어 있어 한도 반영은 유지된다. 따라서 "FORCED ⇒ 활성 hold"는
  성립하고 "활성 hold ⇒ FORCED"는 성립하지 않을 수 있다(CONSUMED 경우) — D-AUTH-013 문언과 같다.
- **시험 기대값 변경 2행**: `IncidentHoldReleaseSpec` "after a REAL release a new success report also opens a NEW hold"(false → true, occurrence 2, 주문 차단, 2인 검사),
  `AuthorizationResolutionSpec` "after a REAL release new approval evidence opens a new hold + Incident"(NoChange → Incident, CONSUMED 유지·FORCED 0). 추가 1행:
  DOUBLE 해제 뒤 AUTHORIZED gen2에 다른 PTX 승인 → 새 CONFLICTING hold(occurrence 2), 거래 1건 유지.
- **변이 RED(`/tmp/mut6.sh`)**: 이전 동작(REAL·DOUBLE 뒤 열지 않음) 분기를 되돌리면 대상 Spec 실패 — 결과는 아래 실행 결과 줄.

- **실행 결과(구현자 실행, Verifier 판정 아님)**: 변이(REAL·DOUBLE 뒤 열지 않음 복원) → AuthorizationResolutionSpec+IncidentHoldReleaseSpec exit 1, 54건 중 3 실패, 원복 cmp 확인. ./gradlew --stop exit 0(2026-10-15 18:57:35 KST) → ./gradlew :apps:payment-api:test **exit 0**, 54파일 **706건, 실패 0·오류 0·skip 0**(705 → +1) → ./gradlew :apps:payment-api:check **exit 0**(verifyNoInstancio, verifyRuntimeHasNoTestTools 113 모듈), 종료 19:01:14. harness/commands.json 하한 1102 → **1103**(+1).
