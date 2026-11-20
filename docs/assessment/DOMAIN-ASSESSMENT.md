# PayCore 도메인·Aggregate 진단 (DOMAIN-ASSESSMENT)

- 작성: 2026-11-20, 기준 커밋 `378aff7`. 커밋 안 된 SCODE-1 부분 구현은 제외. 분석 단계 — 소스 무변경.
- 표기: **[사실]** = 코드(파일:줄)·명령으로 확인, **[추정]** = 해석·제안.

## 1. 결론 요약

1. **금융 도메인 규칙은 순수 Domain에 잘 모여 있고, 규칙마다 시험·ArchUnit 접근 제한이 붙어 있다.** 결제 주문 슬롯, UNKNOWN 유지, 취소 잔액, 일 한도, Hold, append-only 거래 사실, MID–Site·개통 규칙, 2인 승인 상태 기계가 여기에 해당한다. 이 부분이 이 프로젝트의 **가장 값진 보존 자산**이다. [사실]
2. **비용은 도메인이 아니라 그 주변 구조에서 나온다.** 어떤 비용인지는 아래와 같다. [사실 + 추정]
   - persistence(21.5K줄)가 domain(17.7K줄)보다 크다.
   - required Port 215개 중 84% 이상이 구현 1개뿐이다.
   - 시계 Port 11개가 같은 일을 한다.
   - 관리자 승인 서비스 4개가 같은 틀을 복사해 쓴다.
3. 서비스 사이의 규칙 복제(입력 규칙, Snapshot JSON, host 문법)는 **의도된 경계 복제**다. golden·벡터 시험으로 일치를 확인한다. 같은 앱·모듈 안의 중복과는 구분해서 봐야 한다. [사실]
4. 정산(수수료·VAT 계산)·지급·대사 도메인은 **아직 없다**. 수수료 Snapshot은 지금 PAYMENT의 `policyAssignmentVersion` 고정으로만 존재한다. [사실]

## 2. 계층별 규모 [사실]

| 계층(5개 앱 main 합계) | 줄 수 |
|---|---|
| domain | 17,661 |
| application 서비스 | 20,985 |
| application provided(제공 Port) | 6,999 |
| application required(필요 Port) | 6,280 |
| adapter.persistence | 21,507 |
| adapter.web | 10,882 |
| adapter.integration | 9,611 |
| adapter.query(jOOQ) | 3,189 |
| adapter.batch | 2,356 |

domain을 뺀 구조 코드가 domain의 약 4.6배다.

## 3. 서비스별 Aggregate와 핵심 규칙 [사실]

### 3.1 payment-api (`payment.domain` 78파일, 6,233줄)

| Aggregate·도메인 서비스 | 위치 | 지키는 규칙 | 시험 |
|---|---|---|---|
| Payment | Payment.kt:29-203 | REQUESTED→AUTHORIZING→(UNKNOWN)→AUTHORIZED/FAILED/EXPIRED. AUTHORIZING·UNKNOWN은 충분한 근거로만 종료. 종료 근거 append-only + 종류별 출처 표(:137-146). `reconstitute`는 불가능한 상태 거부 | PaymentSpec, TerminationEvidenceSpec, ReconstitutionSpec |
| OrderPaymentControl | OrderPaymentControl.kt:97-138 | 주문 key당 활성 Payment 1건(REQUESTED 포함). 고정 입력이 다르면 충돌. 과거 AUTHORIZED면 재승인 금지. FAILED·EXPIRED는 안전 종료 근거가 있을 때만 새 Payment. hold 중 차단. generation으로 오래된 결과 거부 | OrderPaymentControlSpec, DecisionTableSpec, CreatePaymentConcurrency/RollbackSpec |
| PaymentAuthorization + AuthorizationStart/Resolution | PaymentAuthorization.kt:29-36, AuthorizationResolution.kt:36-47 | 금액·Plan·세션 고정. Attempt 1개. UNKNOWN에서 새 Attempt 금지. timeout·NOT_FOUND·lease 만료면 UNKNOWN 유지. 상충 결과는 hold | AuthorizationResolutionSpec, 결과·회복 동시성 Spec |
| PaymentCancellation + CancelStart/Resolution | PaymentCancellation.kt:101-125 | 원승인 정책·권리 버전 복사 고정(K-7). 검사 순서 hold→진행 중→잔액→Capability→2인 승인. 늦은 성공은 FORCED + hold | PaymentCancellationSpec, CancelStartSpec |
| ReturnBalance | ReturnBalance.kt:40-62 | INV-C1 `completed+reserved ≤ authorized`. 진행 중 반환 1건. 업무 revision(JPA version과 별개) | ReturnBalanceSpec, 취소 동시성 Spec |
| DailyLimitUsage | DailyLimitUsage.kt:25-54 | 일 한도 합계. 취소해도 한도를 복원하지 않음(D-AUTH-007) | TransactionLimitSpec |
| IncidentHold/Release | IncidentHold.kt:22-23 | append-only. 해제는 2인 승인 + 재대사 MATCH로만. 자동 보상취소 없음 | IncidentHoldRelease Spec |
| PaymentTransaction | PaymentTransaction.kt:9-41 | 확정 효과 1건 = 거래 1건(append-only). CANCEL은 원 AUTHORIZATION 참조 | PaymentTransactionSpec |
| AuthenticationSession | AuthenticationSession.kt:97-107 | 비종료 세션 1개. 인증 근거는 승인 1건에만 귀속 | AuthenticationSessionSpec |
| EntitlementSnapshot (entitlement 모듈) | EntitlementSnapshot.kt(632줄) | MASTER 계약 v1~v4를 자체 타입으로 해석. MASTER 코드에 의존하지 않고 golden으로 일치 확인 | golden 시험 |

도메인 이벤트 타입은 없다. 이벤트는 Outbox 레코드로만 표현한다(`ApplicationEventPublisher`·`@EventListener` 0건).

### 3.2 master-api (`merchant.domain` 88파일, 3,629줄, 하위 패키지 없음)

| 대상 | 규칙 |
|---|---|
| Merchant | 관계 변경의 직렬화 지점(잠금). entitlementRevision 단조 증가 |
| Business | 2축 상태(lifecycle / DRAFT·REGISTERED·INACTIVE). 사업자번호 불변 |
| Contract(+Revision, ServiceAgreement) | Revision append-only(승인 사실 포함) |
| MerchantAccount(MID) + Issuance | 발급 ≠ 개통 ≠ 결제 허용. 최초 MID 근거는 승인된 Revision. 첫 연결은 PRIMARY |
| SiteAssignment(+Rules) | 소급 시작 금지. 같은 MID–Site 기간 중첩 금지. PRIMARY 1개 |
| PaymentMethod/CardRoute Activation, SiteActivationConfirmation | 상태 기계. 권리를 넓히는 전이는 MID ACTIVE일 때만. 결제 허용 = Assignment ∧ Activation ∧ 확인 |
| Policy·TransactionLimit·CurrencyEnablement | 버전 불변(교체 = 종료 + 신규). 소급·중첩 금지. **정책 내용은 UNDEFINED/DEMO만** |
| ApprovalEvidence, ActivationApproval | 요청자 ≠ 승인자, 실행 창, 만료를 MASTER가 다시 판정. 등록자 ≠ 승인자 |
| MerchantCodeAssignment | append-only. seq 1·2(DB CHECK와 같음) |

domain Spec 28개. 동시성 Spec 5종. FaultInjection.

### 3.3 financial-batch (domain 31파일, 2,301줄)

PaymentFact(투영만 하고 사실을 만들지 않음, effectKey UNIQUE), HoldReleaseRule(ORIGINAL_UNKNOWN 자동 해제), AcquisitionFile(전송·ACK·결과 3축을 서로 추정하지 않음, 봉인 뒤 바이트 불변), AcquisitionRecord(SALE ≥ 1, CANCEL ≤ 원승인, KRW만), ReacquisitionRule(2인 승인, 대상 버전, UNKNOWN은 대사 전 거절). **정산·대사 도메인 없음.**

### 3.4 internal-admin (domain 60파일, 3,074줄)

ApprovalRequest(2인 승인 상태 기계 — ADMIN 소유 종류는 단일 TX, 소유 서비스 종류는 PENDING→EXECUTING→(EXECUTION_UNKNOWN)→종료), OwnerExecution(저장한 본문 바이트 그대로 재전송, integrityFlag 1회), AdminAccount(sessionGeneration·securityVersion), AdminAuditEvent(append-only). 경쟁·잠금 순서 Spec 5종.

### 3.5 institution-simulator (domain 56파일, 2,424줄)

VanAuthorization(순수 판정, 같은 namespace + 다른 payload면 REQUEST_CONFLICT), AuthenticationTransaction(결과 1회 확정), 장부·매입사 규칙. golden 계약 시험 6종.

## 4. 구조 장치 진단

### 4.1 Port [사실]

| 앱 | required Port | 구현 1개 | provided interface | 구현 1개 |
|---|---|---|---|---|
| payment | 74 | 61 | 42 | 27 |
| master | 39 | 35 | 16 | 10 |
| financial | 21 | 19 | 11 | 11 |
| admin | 57 | 43 | 45 | 15 |
| simulator | 24 | 22 | 13 | 11 |

- required Port 215개 중 **최소 180개(84%)가 구현 1개**다. grep 근사라 실제 비율은 더 높을 수 있다.
- 시험용 fake 구현 파일은 8개뿐이다. 시험 대부분은 Testcontainers 실제 DB를 쓴다.
- [추정] Port의 실익은 교체 가능성이 아니다. ① ArchUnit이 의존 방향을 강제하는 근거이고 ② 기관(VAN·인증·SFTP) 구현의 경계다. ②는 실제로 Simulator ↔ 향후 실기관 교체 지점이므로 유지할 가치가 있다.

### 4.2 Domain ↔ JPA 이중 모델 [사실]

- `@Entity` 119개, Spring Data repository 75개(admin은 0 — EntityManager 직접), 전용 Mapper 11개(약 1,245줄), 그 밖은 저장소 구현 안에서 변환한다.
- 막아 주는 것: domain이 JPA·Spring·jOOQ에 의존하지 않음(ArchUnit `domainIsPure`), 불가능한 저장 상태를 `reconstitute`가 거부, JPA version과 업무 revision 분리.
- 비용: Aggregate 하나에 Domain + Jpa*Entity(여러 개) + Mapper + Port + Jpa 구현 + Spring Data로 최소 5~6개 파일이 든다. persistence > domain.
- **이 분리는 사용자 지정 원칙(CLAUDE.md "순수 Domain과 JPA Entity adapter를 분리", TECHNOLOGY §3)이다.** 줄이려면 사용자 결정이 필요하다. [사실]

### 4.3 아키텍처 시험 [사실]

- 공통 18개 규칙(PayCoreArchitectureRules.kt:331-350)이 있고 meta-test가 규칙 자체를 검증한다.
- Modulith `verify()`는 payment·master·admin·financial에 적용된다.
- payment 전용 접근 규칙(ArchitectureSpec.kt 1,111줄): 예를 들어 `Payment.startAuthorization`은 OrderPaymentControl에서만, `ReturnBalance.reserve`는 CancelStart에서만 부를 수 있고, 기관 Port 구현은 Simulator 하나만 허용한다.
- 틈:
  - simulator에는 Modulith·순환 규칙이 없다.
  - financial `acquiring`에는 provided package-info가 없다.
  - master는 모듈이 `merchant` 하나라 Modulith 경계 검사가 사실상 아무것도 하지 않는다.

## 5. 과한 추상화·중복 후보

| 항목 | 근거 [사실] | 판정 |
|---|---|---|
| admin 승인 서비스 4종 공통 helper(`guardedTx1`, `guarded`, `audit`, `holdsNow`, `converge`, `invalidate` …) | MerchantOps(:801-857)·AcquiringOps(:274-351)·OwnerService(:309-395)에서 거의 같음. diff상 이름·줄바꿈만 다름. 4파일 2,291줄 | **실제 중복** — 가장 확실한 개선 대상(약 300~400줄 절감 [추정]) |
| 순수 시계 Port 11개 + System 구현 11개 | AuditClock 주석: "AdminClock과 같은 역할이지만 Modulith 경계상 따로" | `java.time.Clock` 주입 하나로 대체 가능 [추정], 약 22파일·250줄 |
| master `DisplayName` 생성자 vs `BusinessTextRule.wellFormed` | 같은 모듈에서 같은 규칙을 두 번 구현 | 실제 중복(약 70줄) |
| admin 내부 UTF-16·카드번호 검사 5~6곳 | RegistrationInputRules, BusinessProfileInputRules, OperatorCancelInputRules 등 | 앱 안 중복 — 공통 값 하나로 [추정] |
| 순수 위임 Query 서비스(13~23줄, *BacklogService 4종 등) | "web은 required Port 금지" 규칙 때문에 생긴 계층 | 신중한 축소 후보 |
| 서비스 간 입력 규칙·Snapshot JSON·host 문법 복제 | 주석에 "서비스 경계로 복제" 명시, golden·벡터 시험 | **의도된 복제 — 유지** |
| LIKE escape | MasterSqlText 하나를 공유 | 중복 아님 |

### 5.1 큰 파일 [사실]

상위: MerchantOpsApprovalService 936줄, EntitlementSnapshot 632, ApprovalService 538, MerchantOnboardingService 464, MerchantEntitlementService 462, PayCoreArchitectureRules 455, MasterResponseShape 446, CancelStartService 444, AdminCommandExecutor 437, OwnerServiceApprovalService 432.

[추정] domain 파일(PaymentCancellation, AdminAccount, EntitlementSnapshot)은 규칙이 밀집해 커졌으므로 문제가 아니다. 승인 서비스 4종은 같은 틀이 반복돼 커진 것이라 정리 대상이다.

### 5.2 모듈 분할 [사실 + 추정]

- master: domain 타입 88개가 한 패키지에 있다. 하위 패키지로 나누어도 순환이 없다는 판정이 있다(ARCH-SUBPKG-MASTER, BACKLOG).
- payment: domain을 지금 나누면 순환이 생긴다(sealed 예외·ID). 비권장.
- admin: 모듈 7개의 의존이 촘촘하다(approval→account 16파일, merchantops→account 16, account→audit 11). [추정] account·audit이 사실상 공통 커널이고, 이 분할이 Clock·입력 규칙 복제를 낳았다.

## 6. 분류: 유지 / 개선 / 재구현

| 분류 | 대상 | 규모 |
|---|---|---|
| **유지** | payment domain 전체 | 78파일 / 6.2K줄 + domain Spec 약 60 |
| 유지 | master·financial·admin·simulator domain | 235파일 / 11.4K줄 |
| 유지 | ArchUnit 공통 규칙 + payment 접근 규칙, golden 계약, 동시성·rollback·FaultInjection 시험 | 1.5K줄 + 시험 |
| 유지 | 서비스 경계 복제(입력 규칙, Snapshot 해석, CanonicalJson) | — |
| 유지 | 기관 Port(VAN·인증·SFTP) | 실기관 교체 지점 |
| **개선** | admin 승인 서비스 공통 helper 추출 | 4파일 중 300~400줄 |
| 개선 | 순수 Clock Port 11개 → `java.time.Clock` | 22파일 / 250줄 |
| 개선 | master DisplayName·BusinessTextRule 통합, admin 내부 UTF-16·카드 검사 공통화 | 약 8파일 |
| 개선 | simulator Modulith·순환 규칙, financial acquiring provided package-info | 시험 1~2개 |
| 개선 | master domain 하위 패키지(이미 판정된 BACKLOG) | import 수백 줄 |
| **재구현 후보(사용자 결정 필요)** | 1:1 Mapper + 다중 Jpa*Entity 구조 단순화 | 119 Entity + 11 Mapper. 원칙 변경 |
| 재구현 후보(신중) | 순수 위임 provided interface + Service 쌍 | 10여 파일 |
| **재구현 비권장** | 금융 Aggregate 자체, payment domain 하위 패키지 분할 | — |

## 7. 보존 자산 목록 (재구성·재개발 어느 쪽이든 반드시 가져갈 것)

1. Payment·OrderPaymentControl·OrderKey·종료 근거 출처 표
2. PaymentAuthorization·AuthorizationStart/Resolution(UNKNOWN 유지, 상충 hold, FORCED 한도)
3. PaymentCancellation·CancelStart/Resolution·ReturnBalance(INV-C1, revision), 원승인 정책 버전 복사
4. DailyLimitUsage, IncidentHold/Release(2인 승인 + MATCH)
5. PaymentTransaction append-only, AuthenticationSession 귀속 1회
6. master Assignment·Activation·Confirmation·Revision·Policy 기간 규칙, ApprovalEvidence, MerchantCodeAssignment
7. financial PaymentFact 투영·HoldReleaseRule·AcquisitionFile 3축·봉인 불변·ReacquisitionRule
8. admin ApprovalRequest 상태 기계·OwnerExecution 같은 바이트 재전송·AdminAccount securityVersion·AdminAuditEvent
9. Simulator 판정 함수와 golden 계약 파일 전체(apps/*/src/test/resources/contracts)
10. ArchUnit 규칙(의도), 동시성·rollback·FaultInjection 시험군
11. DB 권한 설계(append-only 표 UPDATE/DELETE 미부여 — 리뷰가 실제 결함을 잡아 강화한 장치)

## 8. 사실 vs 추정

- 사실: 모든 파일:줄 인용, 줄 수·계층 합계, Port·Entity·Mapper·Spring Data 수, 승인 helper 중복(diff 확인), simulator 규칙 누락, 정산·대사 미구현, 도메인 이벤트 타입 없음, 공유 라이브러리로 도메인이 새지 않음.
- 추정: 개선·재구현 분류와 절감 규모, Port 실익 해석, java.time.Clock 대체 가능성, admin account·audit 공통 커널 해석.
- 확인 안 함: 시험 실행(존재만 확인), ※ 일부 서비스 본문, baseline 도메인 문서 본문 전체.
