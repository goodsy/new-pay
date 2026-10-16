# ARCH-STRUCTURE-001 도메인 중심 파일 분리·역할별 명명 매핑

작성 2026-10-16 KST. 지시서 `docs/CLAUDE-ARCHITECTURE-REFACTORING-GUIDE.md`(사용자 문서)의 단계 A 산출물이며 실행 결과를 함께 적는다.
기준 revision `24e6f68`(로컬 커밋). 작업 상태 판정(DONE)은 Verifier·Reviewer·Coordinator가 한다. 이 문서는 구현자 기록이다.

## 0. 범위와 원칙

- **바꾼 것**: 파일 경계(주요 타입당 파일 하나), 역할이 이름과 맞지 않던 타입 29개의 이름, 그 import·참조, 현행 관례 문서의 이름.
- **바꾸지 않은 것**: 패키지(`master.merchant`, `payment.payment`, `payment.entitlement`의 모든 계층 패키지 그대로), Modulith 모듈·공개 범위,
  `@NamedInterface` 위치(`application.provided/package-info.java`), Domain 타입 이름, JPA Entity 타입 이름(JPQL이 simple name을 씀),
  DB schema·migration(주석 포함 — 적용된 Flyway migration은 checksum 대상), HTTP 경로·JSON·오류 코드·이벤트, 멱등 fingerprint, 직렬화,
  시간대, TX 경계·잠금 순서·재시도, Outbox/Inbox, 의존성, ArchUnit 규칙 패턴, 테스트 기대값·하한.
- 같은 패키지 안의 파일 분리는 Kotlin 이름 해석을 바꾸지 않는다. 확인한 위험: top-level `private` 선언(분리 도구가 다른 파일에서 쓰이면 중단),
  `@file:JvmName`(main 코드에 없음), `*Kt` facade(참조는 각 앱 `<App>ApplicationKt`와 test-support fixture뿐 — 이번에 바뀌지 않음).
- 가시성: 분리 대상 중 `internal object`인 `AuthorizationTermsMapper`·`EntitlementPersistenceMapper`(master)와 `JacksonTree`(payment entitlement)는
  **원래부터 `internal`**이었다(review round1 m-1, Coordinator가 `git grep -nE "object (AuthorizationTermsMapper|EntitlementPersistenceMapper|JacksonTree)" HEAD -- apps`로 확인 —
  HEAD의 `JpaAuthorizationTermsStores.kt:84`, `JpaEntitlementStores.kt:57`, `JpaEntitlementProjectionStore.kt:191`). 이번 분리로 `private`→`internal` 확대는 없다.
- 코드 줄 보존 검사(분리 비교 도구). 도구는 저장소 밖 WSL `/tmp/arch/conserve.py`(커밋 대상 아님, sha256 `94b997f92ea88dd44b85a9fc2a9883dc2ec6aa9c97fcd6e550b188cbfcab91be`)이며,
  `git status --porcelain --untracked-files=all -- apps libs`로 바뀐·지운·새 main `.kt` 파일을 모아 디렉터리별로 HEAD 쪽과 현재 쪽의 줄 multiset을 비교한다
  (빈 줄·`import`·`package` 줄 제외, 줄 끝 공백 제거). 실행 위치는 저장소 루트 기준(도구가 `~/workspace/paycore`로 이동).
  - 단계 B 직후(개명 전): `python3 /tmp/arch/conserve.py` → `dirs checked 19 bad 0`.
  - 단계 C 이후(현재 상태): `python3 /tmp/arch/conserve.py --apply-renames`(HEAD 쪽에 `/tmp/arch/renames.json`의 29개 whole-word 개명을 먼저 적용,
    sha256 `b3c8e85fdef40ec43d17a1ef9d5e583e86b6798eb5d60ef36a9586c20251ba2b`) → `dirs checked 19 bad 3`. 개명 정규화 없이 돌리면 개명된 식별자 줄이 차이로 나오는 것이 정상이다.
  - 도구는 주석 줄과 코드 줄을 구분하지 않는다. 남은 차이는 커밋 뒤 같은 비교를 커밋 사이로 옮긴 `/tmp/arch/conserve_rev.py 24e6f68 273068a --apply-renames`
    (커밋 대상 아님)로 다시 셌다: `dirs checked 19 bad 3 diff lines 16`. 3개 디렉터리(master `adapter.persistence` 6줄, master `application.required` 4줄,
    payment `adapter.persistence` 6줄)이고, 16줄 모두 KDoc 줄(`*`로 시작)이다. 바뀐 내용은 §5·n-1의 머리말 문구(공통 관례 참조 위치, `Jpa*Store` → `Jpa*Repository·Jpa*Store`)다.
    "코드 줄 차이 0"은 이 출력에서 `*`로 시작하지 않는 차이 줄이 없다는 뜻이다(round2 n-1).
  - 도구 내용은 위 hash로 고정한다. 저장소에서 다시 확인하려면 같은 비교를 `git show HEAD:<옛 경로>`와 새 파일로 수행하면 된다.

## 0.1 변경 전후 패키지 트리

패키지(디렉터리) 변경은 **없다**. 같은 트리에서 디렉터리별 main 소스 파일 수(`.kt`·`.java`)만 바뀌었다. 전체 136 → 371.

```text
apps/master-api/src/main/kotlin/com/paycore/master                          1 →  1  (MasterApiApplication)
└── merchant
    ├── domain                                                              11 → 56
    ├── application                                                         10 → 13
    │   ├── provided                                                         4 → 12  (+ src/main/java package-info 1 → 1)
    │   └── required                                                         7 → 29
    └── adapter
        ├── batch                                                            1 →  1
        ├── integration                                                      3 →  6
        ├── persistence                                                     14 → 80
        └── query                                                            3 →  3
apps/payment-api/src/main/kotlin/com/paycore/payment                        1 →  1  (PaymentApiApplication)
├── payment
│   ├── domain                                                              18 → 37
│   ├── application                                                          8 →  9
│   │   ├── provided                                                         4 → 20  (+ package-info 1 → 1)
│   │   └── required                                                         7 → 11
│   └── adapter
│       ├── integration                                                      2 →  2
│       ├── persistence                                                      9 → 24
│       └── web                                                              8 → 16
└── entitlement
    ├── domain                                                               4 →  7
    ├── application                                                          4 →  7
    │   ├── provided                                                         2 →  4  (+ package-info 1 → 1)
    │   └── required                                                         1 →  6
    └── adapter
        ├── integration                                                      1 →  3
        ├── persistence                                                      2 → 10
        ├── query                                                            1 →  1
        └── web                                                              1 →  3
apps/financial-batch, apps/internal-admin, libs/test-support (main)         7 →  7  (변경 없음)
```

## 1. 역할별 명명 결정

`UseCase`·`Port`·`Store`를 일괄 치환하지 않았다. 각 Port의 저장 대상·제공 기능·호출 관계를 보고 지시서 §4 표로 판정했다.

### 1.1 바꾼 이름 (29개)

| 현재 타입 (패키지) | 도메인 의미·역할 | 목표 타입 / 파일 | 변경 이유 | 호출자(main/test 파일 수, 변경 후) | 영향받는 검사·설정 |
|---|---|---|---|---|---|
| `PaymentStore` (`payment.payment.application.required`) | Payment Aggregate 저장(insertNew·lock·update·생성 기록 조회) | `PaymentRepository` / `PaymentRepository.kt` | Aggregate 저장 Port = Repository(지시서 §4) | 4/2 | 없음(타입 이름 기반 규칙 없음). bean 이름이 클래스 이름에서 파생 — 문자열 bean 참조 없음 |
| `OrderPaymentControlStore` (같음) | 주문 결제 통제 Aggregate 저장(openOrLock·lock·update) | `OrderPaymentControlRepository` | 같음 | 4/2 | 같음 |
| `JpaPaymentStore` (`…adapter.persistence`) | 위 Port의 JPA 구현 | `JpaPaymentRepository` | JPA 저장 구현 = `Jpa<Aggregate>Repository` | 1/11 | `FaultInjectionConfig`·`CreatePaymentRollbackSpec` 등 시험이 타입으로 참조 — 함께 변경 |
| `JpaOrderPaymentControlStore` (같음) | 같음 | `JpaOrderPaymentControlRepository` | 같음 | 1/8 | 같음 |
| `AuthenticateMerchantUseCase` (`payment.payment.application.provided`) | 가맹점 Bearer API 키 인증(D-CPA-001) | `MerchantAuthenticator` / `MerchantAuthenticator.kt` | 제공 포트: 인증 = `<대상>Authenticator`. 고객 카드 인증(`AuthenticationSession`)과 이름으로 구별 | 4/1 | provided 공개 범위 그대로(같은 패키지) |
| `BusinessStore`, `MerchantStore`, `MerchantSiteStore`, `ContractStore`, `MerchantAccountStore` (`master.merchant.application.required`) | MASTER 당사자·계약·MID Aggregate 저장 | `BusinessRepository`, `MerchantRepository`, `MerchantSiteRepository`, `ContractRepository`, `MerchantAccountRepository` | Aggregate 저장 Port = Repository | 3/1, 6/1, 4/1, 4/0, 5/1 | 없음 |
| `PaymentMethodActivationStore`, `SiteActivationConfirmationStore`, `CurrencyEnablementStore` (같음) | 개통·Site 적용 확인(철회 포함)·통화 활성화 — 자기 식별자(`…Id`)로 load/save되는 domain 객체. 개통·통화는 `Versioned` 갱신, Site 적용 확인은 append-only라 `Versioned` 없이 식별자로 `find`·`insert`·철회(§9 기준) | `PaymentMethodActivationRepository`, `SiteActivationConfirmationRepository`, `CurrencyEnablementRepository` | 같음 | 5/1, 3/1, 4/1 | 없음 |
| `TransactionLimitPolicyStore`, `PolicyVersionStore`, `PolicyAssignmentStore`, `CardRouteActivationStore` (같음) | 승인 시작 조건 원본 Aggregate 저장 | `…Repository` 4개 | 같음 | 각 3/0 | 없음 |
| `JpaBusinessStore` … `JpaCardRouteActivationStore` (`master.merchant.adapter.persistence`, 12개) | 위 12개 Port의 JPA 구현 | `JpaBusinessRepository` … `JpaCardRouteActivationRepository` | JPA 저장 구현 = `Jpa<Aggregate>Repository` | 1~2/0~1 | 없음 |

Spring Data 인터페이스(`<Entity>JpaRepository`)와 이름이 비슷하므로 구분 규칙을 남긴다: `Jpa<X>Repository`(접두 `Jpa`) = required Port 구현 bean,
`<X>JpaRepository`(접미 `JpaRepository`) = adapter 내부 Spring Data 기술 타입. 지시서 §4 표 그대로다.

### 1.2 유지한 이름과 이유

| 현재 이름 | 판정 | 이유 |
|---|---|---|
| `SubmitCreatePaymentUseCase` / `SubmitCreatePaymentService` | 유지 | **진입 facade**: 인증 주체(행위자)·서버 시계·서버 생성 ID를 붙이고 ID 충돌 시 한 번 재시도한다. TX를 열지 않는다. web이 부른다 |
| `CreatePaymentUseCase` / `CreatePaymentService` | 유지 | **로컬 TX 소유**: 멱등 기록 → eligibility → control → Payment → Outbox를 한 PAYMENT TX로 처리한다. facade만 부른다 |
| (위 두 쌍) | 후속 | 역할 명사형(`PaymentCreator` 등)으로 바꾸면 facade와 TX 소유 Port 중 하나만 "Creator"가 되고, 다른 쪽 후보(`…Transaction`·`…Processor`)는 PayCore 업무 용어(`PaymentTransaction`, 결제 processor)와 겹친다. 두 역할을 합치지 않는 이름 체계를 AUTH-001(같은 2단 구조가 생김)과 함께 정하는 편이 낫다 → 후속 ARCH-NAMING-002 후보(§7) |
| `MerchantOnboardingUseCase`, `MerchantEntitlementUseCase`, `MerchantAuthorizationTermsUseCase`, `ReceiveEntitlementSnapshotUseCase`, `EntitlementRelayUseCase`, `EntitlementLeaseUseCase`, `ReportSuccessAfterTerminationUseCase` | 유지 | 여러 명령을 묶은 provided 명령 Port다. `MerchantRegister` 같은 역할 명사는 발급·승인·개통까지 포함하는 범위를 줄여 말한다. Port를 역할별로 쪼개는 것은 bean 배선·공개 API 변경이라 이번 범위 밖(§7) |
| `MerchantStructureQuery`, `AuthorizationTermsCoverageQuery`, `EntitlementPropagationQuery`, `EntitlementFreshnessQuery`, `AuthorizationTermsQuery`, `EntitlementEvaluationQuery` | 유지 | DTO·판정을 돌려주는 조회 Port다. 지시서 §4 "Finder는 DTO 조회 Port와 책임 구별"에 따라 Aggregate 조회가 아니므로 `Finder`로 바꾸지 않는다 |
| `IdempotencyRecordStore`, `OutboxStore`(양 앱), `AdminRequestStore`, `MasterChangeLog`, `EntitlementIssueStore`, `EntitlementRelayStore`, `EntitlementSourceStore`, `EntitlementProjectionStore`, `MerchantCredentialStore` | 유지 | 프로토콜·작업 기록·투영 저장 Port(지시서 §4 "Aggregate Repository로 강제 개명하지 않음") |
| `SiteReviewStore` | 유지 | Site 심사 **결정 로그**다. `SiteReviewDecision`에는 자기 식별자가 없고 (Site, seq)로 쌓이며 Port는 `history(siteId)`·`append`뿐, 상태는 로그에서 계산한다. 같은 append-only여도 `SiteActivationConfirmation`은 `SiteActivationConfirmationId`로 찾고 철회가 그 식별자에 붙어 Repository다(§9 기준) |
| `PaymentEligibilityPort`, `AuthorizationAdmissionPort` | 유지 | entitlement 모듈 provided를 감싸는 반부패 Port다. 역할 명사(`…Evaluator`)는 entitlement domain의 `EntitlementEvaluator`·`AuthorizationTermsEvaluator`와 겹친다. `AuthorizationAdmissionPort`는 `ArchitectureSpec`이 FQN으로 구현 위치를 검사한다 → 후속 |
| `MerchantStructureReader`, `AuthorizationTermsCoverageReader`, `EntitlementPropagationReader`, `EntitlementFreshnessReader` | 유지 | jOOQ CQRS 조회 required Port(지시서 §4 표와 일치) |
| `EntitlementDispatcher`, `MasterOperationalMetrics`, `EntitlementMetrics`, `MasterClock`·`PaymentClock`·`EntitlementClock`, `MasterIdGenerator`·`PaymentIdentifierGenerator` | 유지 | 외부 전달·지표·시계·ID 생성 Port. 이미 역할 명사 |
| `Jpa*Entity` 전부 | 유지 | 지시서 §4 형식과 일치. JPQL이 Entity simple name을 쓰므로 바꾸지 않은 것이 안전하다 |
| Domain 타입 | 유지 | 이번 범위 밖(모델 경계·용어). 용어사전 대응은 §6 |
| `payment.payment`, `adapter.web` | 유지 | 지시서 §3 |

## 2. 파일 분리 매핑

모든 행의 패키지는 그대로다(파일만 이동). "호출자"는 첫 타입을 참조하는 main/test 파일 수(변경 후 기준, 증거용 샘플)다.
영향받는 검사: 같은 패키지라 import·ArchUnit FQN·Modulith 모듈 판정에 영향 없음. top-level `private`가 있는 파일은 §3 예외 처리.

| 현재 파일 (패키지 `com.paycore.<app>.<module>.<layer>`) | 도메인 의미 | 목표 파일: 타입 (패키지 유지) | 호출자(예: 첫 타입 참조 파일 main/test) |
|---|---|---|---|
| `master.merchant.domain` `Party.kt` | 당사자 Aggregate(Business·Merchant·MerchantSite)와 값(등록번호·채널·생명주기) | `BusinessRegistrationNumber.kt`: BusinessRegistrationNumber<br>`Lifecycle.kt`: Lifecycle<br>`Business.kt`: Business<br>`Merchant.kt`: Merchant<br>`SiteChannel.kt`: ChannelType, SiteChannel<br>`MerchantSite.kt`: MerchantSite | 6/3 |
| `master.merchant.domain` `AuthorizationTerms.kt` | 승인 시작 조건 원본: 한도 정책·정책 버전·정책 Assignment·카드사 Route 개통과 그 식별자·코드 | `TransactionLimitPolicyId.kt`: TransactionLimitPolicyId<br>`PolicyVersionId.kt`: PolicyVersionId<br>`PolicyAssignmentId.kt`: PolicyAssignmentId<br>`CardRouteActivationId.kt`: CardRouteActivationId<br>`AcquirerCode.kt`: AcquirerCode<br>`PolicyCode.kt`: PolicyCode<br>`SeoulMidnight.kt`: SeoulMidnight<br>`TransactionLimitPolicy.kt`: LimitValue, LimitScopeKind, LimitScope, TransactionLimitPolicy<br>`LimitTarget.kt`: LimitTarget<br>`PolicyVersion.kt`: PolicyKind, PolicyContentStatus, PolicyVersion<br>`PolicyAssignment.kt`: PolicyAssignment<br>`CardRouteActivation.kt`: CardRouteActivation<br>`InstitutionRefs.kt`: RouteRef, InstitutionMerchantRef, isRefChar | 8/5 |
| `master.merchant.domain` `MasterIdentifiers.kt` | MASTER 식별자(`<prefix>_`+ULID)와 공통 값(근거·사유·통화·행위자·표시명) | `MasterIdFormat.kt`: MasterIdFormat<br>`BusinessId.kt`: BusinessId<br>`MerchantId.kt`: MerchantId<br>`MerchantSiteId.kt`: MerchantSiteId<br>`MerchantAccountId.kt`: MerchantAccountId<br>`ContractId.kt`: ContractId<br>`ContractServiceAgreementId.kt`: ContractServiceAgreementId<br>`SiteAssignmentId.kt`: SiteAssignmentId<br>`PaymentMethodActivationId.kt`: PaymentMethodActivationId<br>`SiteActivationConfirmationId.kt`: SiteActivationConfirmationId<br>`CurrencyEnablementId.kt`: CurrencyEnablementId<br>`EvidenceRef.kt`: EvidenceRef<br>`DecisionReason.kt`: DecisionReason<br>`CurrencyCode.kt`: CurrencyCode<br>`ActorRef.kt`: ActorRef<br>`DisplayName.kt`: DisplayName | 15/0 |
| `master.merchant.domain` `Contract.kt` | 계약 Aggregate와 Revision·Scope·CSA·유효 기간 | `EffectivePeriod.kt`: EffectivePeriod<br>`ContractScope.kt`: ContractScope<br>`ContractRevision.kt`: RevisionApproval, ContractRevision<br>`ContractServiceAgreement.kt`: ServiceKind, ContractServiceAgreement<br>`Contract.kt`(유지): 나머지 | 17/21 |
| `master.merchant.domain` `Activation.kt` | 결제수단 개통(PaymentMethodActivation)과 승인 판정 | `PaymentMethodActivation.kt`: ActivationStatus, ActivationTransition, PaymentMethodActivation<br>`ActivationApproval.kt`: ActivationApprovalResult, ActivationApproval | 9/14 |
| `master.merchant.domain` `CurrencyEnablement.kt` | 통화 활성화와 통화 단위 기준정보 | `CurrencyUnit.kt`: CurrencyUnit<br>`CurrencyEnablement.kt`(유지): 나머지 | 12/7 |
| `master.merchant.domain` `EntitlementSnapshot.kt` | 권리 Snapshot(통합 계약 v2)과 조립·정규 JSON | `CanonicalJson.kt`: CanonicalJson<br>`ContractWindows.kt`: ContractWindows<br>`EntitlementSource.kt`: EntitlementSource, AccountSource<br>`EntitlementSnapshotAssembler.kt`: EntitlementSnapshotAssembler<br>`EntitlementSnapshot.kt`(유지): 나머지 | 3/4 |
| `master.merchant.domain` `MasterRules.kt` | MASTER 업무 거절 분류와 최초 Default MID 발급 규칙 | `MasterRejection.kt`: MasterRejection, MasterRuleViolation<br>`MerchantAccountIssuance.kt`: FirstDefaultIssuance, MerchantAccountIssuance | 26/14 |
| `master.merchant.domain` `MerchantAccount.kt` | MID(MerchantAccount)·Default 지정·MID–Site Assignment·결제수단 | `SiteAssignment.kt`: SiteAssignmentRole, SiteAssignmentStatus, SiteAssignment, SiteAssignmentRules<br>`PaymentMethod.kt`: PaymentMethod<br>`MerchantAccount.kt`(유지): 나머지 | 6/6 |
| `master.merchant.domain` `SiteReview.kt` | Site 심사 결정(append-only)과 상태 판정 | `SiteReviewDecision.kt`: SiteReviewStatus, SiteReviewDecision, SiteReviewRules | 8/11 |
| `master.merchant.application` `EntitlementDeliveryServices.kt` | 권리 Snapshot Relay·lease 갱신·전파 조회 서비스 | `EntitlementRelayService.kt`: EntitlementRelayService<br>`EntitlementLeaseService.kt`: EntitlementLeaseService<br>`EntitlementPropagationQueryService.kt`: EntitlementPropagationQueryService | 1/1 |
| `master.merchant.application` `MerchantAuthorizationTermsService.kt` | 승인 시작 조건 명령 서비스와 커버리지 조회 서비스 | `AuthorizationTermsCoverageService.kt`: AuthorizationTermsCoverageService<br>`MerchantAuthorizationTermsService.kt`(유지): 나머지 | 1/0 |
| `master.merchant.application.provided` `MerchantAuthorizationTermsUseCase.kt` | 승인 시작 조건 명령 Port·command와 커버리지 조회 Port | `AuthorizationTermsCoverageQuery.kt`: AuthorizationTermsCoverageQuery, TermsCoverageOverview<br>`MerchantAuthorizationTermsUseCase.kt`(유지): 나머지 | 3/1 |
| `master.merchant.application.provided` `MerchantEntitlementUseCase.kt` | 개통·통화·Site 확인 명령 Port, Relay·lease Port, 전파 조회 Port | `EntitlementRelayUseCase.kt`: EntitlementRelayUseCase, RelayReport<br>`EntitlementLeaseUseCase.kt`: EntitlementLeaseUseCase, RenewalReport<br>`EntitlementPropagationQuery.kt`: EntitlementPropagationQuery, EffectBasis, MerchantPropagationView, PropagationOverview<br>`MerchantEntitlementUseCase.kt`(유지): 나머지 | 3/0 |
| `master.merchant.application.provided` `MerchantOnboardingUseCase.kt` | 가맹점 온보딩 명령 Port·command와 관리 명령 멱등 키·결과 | `AdminIdempotencyKey.kt`: AdminIdempotencyKey<br>`AdminRequest.kt`: AdminRequest<br>`AdminOperation.kt`: AdminOperation<br>`AdminCommandResult.kt`: AdminCommandResult, AdminCommandFailure<br>`MerchantOnboardingUseCase.kt`(유지): 나머지 | 3/1 |
| `master.merchant.application.required` `AuthorizationTermsPorts.kt` | 승인 시작 조건 저장 Port와 커버리지 jOOQ 조회 Port | `TransactionLimitPolicyRepository.kt`: TransactionLimitPolicyRepository<br>`PolicyVersionRepository.kt`: PolicyVersionRepository<br>`PolicyAssignmentRepository.kt`: PolicyAssignmentRepository<br>`AuthorizationTermsCoverageReader.kt`: AuthorizationTermsCoverageReader<br>`CardRouteActivationRepository.kt`: CardRouteActivationRepository | 3/0 |
| `master.merchant.application.required` `EntitlementPorts.kt` | 권리 Snapshot 발행·전달 Port(시각·원천·issue·relay·dispatcher·지표·전파 조회) | `EntitlementTiming.kt`: EntitlementTiming<br>`EntitlementSourceStore.kt`: EntitlementSourceStore<br>`EntitlementIssueStore.kt`: SnapshotIssueRecord, SnapshotPublication, EntitlementIssueStore<br>`EntitlementRelayStore.kt`: RelayEnvelope, SnapshotAck, RelayMarkResult, EntitlementRelayStore<br>`EntitlementDispatcher.kt`: DispatchOutcome, EntitlementDispatcher<br>`MasterOperationalMetrics.kt`: MasterOperationalMetrics<br>`EntitlementPropagationReader.kt`: EntitlementPropagationReader | 9/1 |
| `master.merchant.application.required` `MasterRecords.kt` | 관리 명령 멱등 기록·변경 이력·Outbox·ID 생성 Port(프로토콜·작업 기록) | `AdminRequestStore.kt`: AdminRequestScope, AdminReservation, StoredAdminResult, StoredAdminRequest, AdminReservationOutcome, AdminRequestStore<br>`MasterChangeLog.kt`: ChangeRecord, MasterChangeLog<br>`OutboxStore.kt`: OutboxEvent, OutboxStore<br>`MasterIdGenerator.kt`: MasterIdGenerator | 3/1 |
| `master.merchant.application.required` `MasterStores.kt` | MASTER Aggregate 저장 Port와 `Versioned` | `Versioned.kt`: Versioned<br>`BusinessRepository.kt`: BusinessRepository<br>`MerchantRepository.kt`: MerchantRepository<br>`MerchantSiteRepository.kt`: MerchantSiteRepository<br>`ContractRepository.kt`: ContractRepository<br>`MerchantAccountRepository.kt`: MerchantAccountRepository<br>`PaymentMethodActivationRepository.kt`: PaymentMethodActivationRepository<br>`SiteActivationConfirmationRepository.kt`: SiteActivationConfirmationRepository<br>`CurrencyEnablementRepository.kt`: CurrencyEnablementRepository<br>`SiteReviewStore.kt`: SiteReviewStore | 22/2 |
| `master.merchant.adapter.integration` `HttpEntitlementDispatcher.kt` | 권리 Snapshot HTTP 전달과 전송 봉투(서명 대상 JSON) | `EntitlementEnvelope.kt`: EntitlementEnvelope<br>`HttpEntitlementDispatcher.kt`(유지): 나머지 | 2/3 |
| `master.merchant.adapter.integration` `MicrometerMasterOperationalMetrics.kt` | MASTER 운영 지표(Micrometer)와 gauge | `AuthorizationTermsCoverageGauges.kt`: AuthorizationTermsCoverageGauges<br>`EntitlementPropagationGauges.kt`: EntitlementPropagationGauges<br>`MicrometerMasterOperationalMetrics.kt`(유지): 나머지 | 1/0 |
| `master.merchant.adapter.persistence` `JpaAccountEntities.kt` | MID·Default·Assignment·개통 JPA Entity | `JpaMerchantAccountEntity.kt`: JpaMerchantAccountEntity<br>`JpaDefaultDesignationEntity.kt`: JpaDefaultDesignationEntity<br>`JpaSiteAssignmentEntity.kt`: JpaSiteAssignmentEntity<br>`JpaPaymentMethodActivationEntity.kt`: JpaPaymentMethodActivationEntity | 4/1 |
| `master.merchant.adapter.persistence` `JpaAuthorizationTermsEntities.kt` | 승인 시작 조건 JPA Entity | `JpaTransactionLimitPolicyEntity.kt`: JpaTransactionLimitPolicyEntity<br>`JpaPolicyVersionEntity.kt`: JpaPolicyVersionEntity<br>`JpaPolicyAssignmentEntity.kt`: JpaPolicyAssignmentEntity<br>`JpaCardRouteActivationEntity.kt`: JpaCardRouteActivationEntity<br>`JpaCardRouteEventEntity.kt`: CardRouteEventKey, JpaCardRouteEventEntity | 4/1 |
| `master.merchant.adapter.persistence` `JpaAuthorizationTermsStores.kt` | 승인 시작 조건 Spring Data·Mapper·Port 구현 | `TransactionLimitPolicyJpaRepository.kt`: TransactionLimitPolicyJpaRepository<br>`PolicyVersionJpaRepository.kt`: PolicyVersionJpaRepository<br>`PolicyAssignmentJpaRepository.kt`: PolicyAssignmentJpaRepository<br>`CardRouteActivationJpaRepository.kt`: CardRouteActivationJpaRepository<br>`CardRouteEventJpaRepository.kt`: CardRouteEventJpaRepository<br>`AuthorizationTermsMapper.kt`: AuthorizationTermsMapper<br>`JpaTransactionLimitPolicyRepository.kt`: JpaTransactionLimitPolicyRepository<br>`JpaPolicyVersionRepository.kt`: JpaPolicyVersionRepository<br>`JpaPolicyAssignmentRepository.kt`: JpaPolicyAssignmentRepository<br>`JpaCardRouteActivationRepository.kt`: JpaCardRouteActivationRepository | 2/0 |
| `master.merchant.adapter.persistence` `JpaContractEntities.kt` | 계약·Revision·Scope Site·승인·CSA JPA Entity | `JpaContractEntity.kt`: JpaContractEntity<br>`JpaContractRevisionEntity.kt`: RevisionKey, JpaContractRevisionEntity<br>`JpaContractRevisionScopeSiteEntity.kt`: ScopeSiteKey, JpaContractRevisionScopeSiteEntity<br>`JpaContractRevisionApprovalEntity.kt`: JpaContractRevisionApprovalEntity<br>`JpaContractServiceAgreementEntity.kt`: JpaContractServiceAgreementEntity | 4/1 |
| `master.merchant.adapter.persistence` `JpaEntitlementEntities.kt` | 개통 이력·Site 확인·철회·통화·심사·Snapshot issue/ack JPA Entity | `JpaActivationEventEntity.kt`: ActivationEventKey, JpaActivationEventEntity<br>`JpaSiteActivationConfirmationEntity.kt`: JpaSiteActivationConfirmationEntity<br>`JpaConfirmationRevocationEntity.kt`: JpaConfirmationRevocationEntity<br>`JpaCurrencyUnitEntity.kt`: JpaCurrencyUnitEntity<br>`JpaCurrencyEnablementEntity.kt`: JpaCurrencyEnablementEntity<br>`JpaSiteReviewDecisionEntity.kt`: SiteReviewKey, JpaSiteReviewDecisionEntity<br>`JpaSnapshotIssueEntity.kt`: SnapshotIssueKey, JpaSnapshotIssueEntity<br>`JpaSnapshotAckEntity.kt`: JpaSnapshotAckEntity | 3/0 |
| `master.merchant.adapter.persistence` `JpaEntitlementStores.kt` | 권리 입력 저장 Port 구현과 Mapper | `EntitlementPersistenceMapper.kt`: EntitlementPersistenceMapper<br>`JpaPaymentMethodActivationRepository.kt`: JpaPaymentMethodActivationRepository<br>`JpaSiteActivationConfirmationRepository.kt`: JpaSiteActivationConfirmationRepository<br>`JpaCurrencyEnablementRepository.kt`: JpaCurrencyEnablementRepository<br>`JpaSiteReviewStore.kt`: JpaSiteReviewStore<br>`JpaEntitlementSourceStore.kt`: JpaEntitlementSourceStore<br>`JpaEntitlementIssueStore.kt`: JpaEntitlementIssueStore<br>`JpaEntitlementRelayStore.kt`: JpaEntitlementRelayStore | 7/0 |
| `master.merchant.adapter.persistence` `JpaMasterStores.kt` | 당사자·계약·MID 저장 Port 구현과 잠금 읽기 helper | `PessimisticRowLock.kt`: lockRow<br>`JpaBusinessRepository.kt`: JpaBusinessRepository<br>`JpaMerchantRepository.kt`: JpaMerchantRepository<br>`JpaMerchantSiteRepository.kt`: JpaMerchantSiteRepository<br>`JpaContractRepository.kt`: JpaContractRepository<br>`JpaMerchantAccountRepository.kt`: JpaMerchantAccountRepository | 10/0 |
| `master.merchant.adapter.persistence` `JpaPartyEntities.kt` | Business·Merchant·Site JPA Entity | `JpaBusinessEntity.kt`: JpaBusinessEntity<br>`JpaMerchantEntity.kt`: JpaMerchantEntity<br>`JpaMerchantSiteEntity.kt`: JpaMerchantSiteEntity | 5/1 |
| `master.merchant.adapter.persistence` `JpaRecordEntities.kt` | 관리 요청·변경 이력·Outbox JPA Entity | `JpaAdminRequestEntity.kt`: JpaAdminRequestEntity<br>`JpaMasterChangeHistoryEntity.kt`: JpaMasterChangeHistoryEntity<br>`JpaOutboxEventEntity.kt`: JpaOutboxEventEntity | 3/1 |
| `master.merchant.adapter.persistence` `JpaRecordStores.kt` | 관리 요청·변경 이력·Outbox Port 구현 | `JpaAdminRequestStore.kt`: JpaAdminRequestStore<br>`JpaMasterChangeLog.kt`: JpaMasterChangeLog<br>`JpaOutboxStore.kt`: JpaOutboxStore | 1/1 |
| `master.merchant.adapter.persistence` `MasterJpaRepositories.kt` | MASTER Spring Data repository 22개 | `BusinessJpaRepository.kt`: BusinessJpaRepository<br>`MerchantJpaRepository.kt`: MerchantJpaRepository<br>`MerchantSiteJpaRepository.kt`: MerchantSiteJpaRepository<br>`ContractJpaRepository.kt`: ContractJpaRepository<br>`ContractRevisionJpaRepository.kt`: ContractRevisionJpaRepository<br>`ContractRevisionScopeSiteJpaRepository.kt`: ContractRevisionScopeSiteJpaRepository<br>`ContractRevisionApprovalJpaRepository.kt`: ContractRevisionApprovalJpaRepository<br>`ContractServiceAgreementJpaRepository.kt`: ContractServiceAgreementJpaRepository<br>`MerchantAccountJpaRepository.kt`: MerchantAccountJpaRepository<br>`DefaultDesignationJpaRepository.kt`: DefaultDesignationJpaRepository<br>`SiteAssignmentJpaRepository.kt`: SiteAssignmentJpaRepository<br>`PaymentMethodActivationJpaRepository.kt`: PaymentMethodActivationJpaRepository<br>`ActivationEventJpaRepository.kt`: ActivationEventJpaRepository<br>`SiteActivationConfirmationJpaRepository.kt`: SiteActivationConfirmationJpaRepository<br>`ConfirmationRevocationJpaRepository.kt`: ConfirmationRevocationJpaRepository<br>`CurrencyUnitJpaRepository.kt`: CurrencyUnitJpaRepository<br>`CurrencyEnablementJpaRepository.kt`: CurrencyEnablementJpaRepository<br>`SiteReviewDecisionJpaRepository.kt`: SiteReviewDecisionJpaRepository<br>`SnapshotIssueJpaRepository.kt`: SnapshotIssueJpaRepository<br>`SnapshotAckJpaRepository.kt`: SnapshotAckJpaRepository<br>`OutboxEventJpaRepository.kt`: OutboxEventJpaRepository<br>`AdminRequestJpaRepository.kt`: AdminRequestJpaRepository | 2/0 |
| `payment.entitlement.adapter.integration` `EntitlementIntegration.kt` | PAYMENT 권리 투영 설정·지표·신선도 gauge | `EntitlementSettings.kt`: EntitlementSettings, EntitlementSettingsConfiguration<br>`MicrometerEntitlementMetrics.kt`: MicrometerEntitlementMetrics<br>`EntitlementFreshnessGauges.kt`: EntitlementFreshnessGauges | 3/1 |
| `payment.entitlement.adapter.persistence` `JpaEntitlementEntities.kt` | 권리 Inbox·head·Snapshot·MID 소유 JPA Entity | `JpaInboxEventEntity.kt`: JpaInboxEventEntity<br>`JpaEntitlementHeadEntity.kt`: JpaEntitlementHeadEntity<br>`JpaEntitlementSnapshotEntity.kt`: SnapshotKey, JpaEntitlementSnapshotEntity<br>`JpaMidOwnerEntity.kt`: JpaMidOwnerEntity | 3/0 |
| `payment.entitlement.adapter.persistence` `JpaEntitlementProjectionStore.kt` | 권리 투영 저장 Port 구현과 Spring Data | `InboxEventJpaRepository.kt`: InboxEventJpaRepository<br>`EntitlementHeadJpaRepository.kt`: EntitlementHeadJpaRepository<br>`EntitlementSnapshotJpaRepository.kt`: EntitlementSnapshotJpaRepository<br>`MidOwnerJpaRepository.kt`: MidOwnerJpaRepository<br>`JacksonTree.kt`: JacksonTree<br>`JpaEntitlementProjectionStore.kt`(유지): 나머지 | 2/0 |
| `payment.entitlement.adapter.web` `EntitlementInboxController.kt` | 내부 Inbox HTTP 진입점·서명 검증·설정 | `EntitlementInboxSettings.kt`: EntitlementInboxSettings, EntitlementInboxConfiguration<br>`EntitlementSignature.kt`: EntitlementSignature<br>`EntitlementInboxController.kt`(유지): 나머지 | 3/1 |
| `payment.entitlement.application` `EntitlementServices.kt` | Inbox 수신·eligibility 평가·신선도 조회 서비스와 시계 | `EntitlementInboxService.kt`: EntitlementInboxService<br>`EntitlementEvaluationService.kt`: EntitlementEvaluationService<br>`EntitlementFreshnessService.kt`: EntitlementFreshnessService<br>`SystemEntitlementClock.kt`: SystemEntitlementClock | 1/0 |
| `payment.entitlement.application.provided` `EntitlementUseCases.kt` | Snapshot 수신 Port, eligibility 평가 Port, 신선도 조회 Port | `ReceiveEntitlementSnapshotUseCase.kt`: ReceiveEntitlementSnapshotUseCase, InboundSnapshotEnvelope, ReceiveResult, ReceiveFailure<br>`EntitlementEvaluationQuery.kt`: EntitlementEvaluationQuery, EntitlementEvaluationFailure, EligibilityRequest, EligibilityDecision, EligibilityRefusalReason, EligibilityUnavailableCause<br>`EntitlementFreshnessQuery.kt`: EntitlementFreshnessQuery, FreshnessOverview | 3/0 |
| `payment.entitlement.application.required` `EntitlementPorts.kt` | 권리 투영 저장·시계·timing·지표·신선도 조회 Port, 저장 실패 | `EntitlementProjectionStore.kt`: StoredInbox, InboxRecord, EntitlementProjectionStore<br>`EntitlementClock.kt`: EntitlementClock<br>`EntitlementTiming.kt`: EntitlementTiming<br>`EntitlementMetrics.kt`: EntitlementMetrics<br>`EntitlementFreshnessReader.kt`: EntitlementFreshnessReader<br>`EntitlementPersistenceFailure.kt`: EntitlementPersistenceFailure | 2/0 |
| `payment.entitlement.domain` `EntitlementHead.kt` | 권리 head와 Snapshot 적용 규칙 | `SnapshotApplicationRule.kt`: ApplicationDecision, SnapshotApplicationRule<br>`EligibilityQuestion.kt`: EligibilityQuestion<br>`EntitlementHead.kt`(유지): 나머지 | 1/0 |
| `payment.entitlement.domain` `EntitlementSnapshot.kt` | PAYMENT 쪽 Snapshot 해석 모델(schema 1·2) | `SnapshotSchema.kt`: SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS, TERMS_SNAPSHOT_SCHEMA_VERSION, isSupportedSnapshotSchema, SnapshotContractViolation<br>`EntitlementSnapshot.kt`(유지): 나머지 | 1/0 |
| `payment.payment.adapter.persistence` `JpaMerchantCredentialStore.kt` | 가맹점 API Credential 투영(읽기 전용) Entity·repository·Port 구현 | `JpaMerchantCredentialEntity.kt`: JpaMerchantCredentialEntity<br>`MerchantCredentialJpaRepository.kt`: MerchantCredentialJpaRepository<br>`JpaMerchantCredentialStore.kt`(유지): 나머지 | 2/0 |
| `payment.payment.adapter.persistence` `JpaPaymentEntities.kt` | Payment·종료 근거·주문 통제·사건 보류 JPA Entity | `JpaPaymentEntity.kt`: JpaPaymentEntity<br>`JpaPaymentTerminationEvidenceEntity.kt`: TerminationEvidenceKey, JpaPaymentTerminationEvidenceEntity<br>`OrderKeyColumns.kt`: OrderKeyColumns<br>`JpaOrderPaymentControlEntity.kt`: JpaOrderPaymentControlEntity<br>`JpaIncidentHoldEntity.kt`: JpaIncidentHoldEntity | 4/0 |
| `payment.payment.adapter.persistence` `JpaRequestEntities.kt` | 멱등 기록·Outbox JPA Entity | `JpaIdempotencyRequestEntity.kt`: JpaIdempotencyRequestEntity<br>`JpaOutboxEventEntity.kt`: JpaOutboxEventEntity | 3/0 |
| `payment.payment.adapter.persistence` `JpaStores.kt` | Payment·주문 통제·멱등 기록·Outbox Port 구현 | `JpaPaymentRepository.kt`: JpaPaymentRepository<br>`JpaOrderPaymentControlRepository.kt`: JpaOrderPaymentControlRepository<br>`JpaIdempotencyRecordStore.kt`: JpaIdempotencyRecordStore<br>`JpaOutboxStore.kt`: JpaOutboxStore | 1/11 |
| `payment.payment.adapter.persistence` `PaymentJpaRepositories.kt` | PAYMENT Spring Data repository 6개 | `PaymentJpaRepository.kt`: PaymentJpaRepository<br>`PaymentTerminationEvidenceJpaRepository.kt`: PaymentTerminationEvidenceJpaRepository<br>`OrderPaymentControlJpaRepository.kt`: OrderPaymentControlJpaRepository<br>`IncidentHoldJpaRepository.kt`: IncidentHoldJpaRepository<br>`IdempotencyRequestJpaRepository.kt`: IdempotencyRequestJpaRepository<br>`OutboxEventJpaRepository.kt`: OutboxEventJpaRepository | 2/0 |
| `payment.payment.adapter.web` `ApiErrorController.kt` | 오류 응답 Controller와 firewall 설정 | `ApiFirewallConfiguration.kt`: ApiFirewallConfiguration<br>`ApiErrorController.kt`(유지): 나머지 | 1/0 |
| `payment.payment.adapter.web` `ApiErrors.kt` | 공개 API 오류 코드·본문·응답 작성 | `ApiErrorCode.kt`: ApiErrorCode<br>`ApiError.kt`: ApiError<br>`ApiResponses.kt`: ApiResponses | 10/2 |
| `payment.payment.adapter.web` `CreatePaymentController.kt` | CreatePayment HTTP 진입점과 안전한 예외 요약 | `SafeThrowable.kt`: SafeThrowable<br>`CreatePaymentController.kt`(유지): 나머지 | 4/0 |
| `payment.payment.adapter.web` `InternalConnectorConfiguration.kt` | /internal 전용 connector와 진입 경로 필터 | `ApiEntryFilter.kt`: ApiEntryFilter<br>`InternalConnectorConfiguration.kt`(유지): 나머지 | 3/0 |
| `payment.payment.adapter.web` `PaymentApiSecurityConfiguration.kt` | 가맹점 Bearer 인증 보안 체인·필터·token·401 응답 | `MerchantAuthenticationToken.kt`: MerchantAuthenticationToken<br>`MerchantBearerAuthenticationFilter.kt`: MerchantBearerAuthenticationFilter<br>`UnauthenticatedEntryPoint.kt`: UnauthenticatedEntryPoint<br>`PaymentApiSecurityConfiguration.kt`(유지): 나머지 | 2/0 |
| `payment.payment.application` `SecureRandomPaymentIdentifierGenerator.kt` | 서버 생성 식별자와 시스템 시계 구현 | `SystemPaymentClock.kt`: SystemPaymentClock<br>`SecureRandomPaymentIdentifierGenerator.kt`(유지): 나머지 | 1/0 |
| `payment.payment.application.provided` `CreatePaymentUseCase.kt` | CreatePayment 로컬 TX Port와 command·결과·실패 분류, 늦은 성공 보고 Port | `CreatePaymentCommand.kt`: NewPaymentIdentifiers, CreatePaymentCommand<br>`OrderNameRule.kt`: OrderNameRule<br>`LineSafeText.kt`: LineSafeText<br>`CreatePaymentResult.kt`: CreatedPayment, CreatePaymentResult, EligibilityRefusal<br>`StoreDiagnostic.kt`: StoreDiagnostic, StoreDiagnosticSource<br>`FailureKind.kt`: FailureKind, FailureKindSource<br>`CreatePaymentFailure.kt`: CreatePaymentFailure, GeneratedIdentifier<br>`ReportSuccessAfterTerminationUseCase.kt`: ReportSuccessAfterTerminationUseCase<br>`CreatePaymentUseCase.kt`(유지): 나머지 | 4/2 |
| `payment.payment.application.provided` `CreatePaymentValues.kt` | 요청 fingerprint·eligibility 버전·서버 생성 ID·결과/거절 분류 값 | `RequestFingerprint.kt`: RequestFingerprint<br>`EligibilitySnapshotVersion.kt`: EligibilitySnapshotVersion<br>`CanonicalUuidIdentifiers.kt`: IdempotencyRecordId, EventId, CanonicalUuid<br>`NotEligibleReason.kt`: NotEligibleReason<br>`EligibilityUnavailableReason.kt`: EligibilityUnavailableReason<br>`CreatePaymentOutcome.kt`: CreatePaymentOutcome<br>`CreatePaymentRejection.kt`: CreatePaymentRejection | 4/2 |
| `payment.payment.application.provided` `MerchantAuthentication.kt` | 가맹점 API 키 인증 Port·결과·주체 | `CredentialKeyId.kt`: CredentialKeyId<br>`MerchantAuthenticator.kt`: AuthenticationFailureReason, MerchantAuthenticationResult, MerchantAuthenticationUnavailable, MerchantAuthenticator<br>`MerchantAuthentication.kt`(유지): 나머지 | 8/4 |
| `payment.payment.application.required` `ApiSupportPorts.kt` | 시계·ID 생성·Credential 투영 Port | `PaymentClock.kt`: PaymentClock<br>`PaymentIdentifierGenerator.kt`: PaymentIdentifierGenerator<br>`MerchantCredentialStore.kt`: CredentialStatus, StoredMerchantCredential, MerchantCredentialStore | 5/2 |
| `payment.payment.application.required` `PaymentAggregateStores.kt` | Payment·주문 통제 Aggregate 저장 Port와 `Versioned` | `Versioned.kt`: Versioned<br>`OrderPaymentControlRepository.kt`: ControlAcquisition, OrderPaymentControlRepository<br>`PaymentRepository.kt`: PaymentCreationAttributes, PaymentCreationRecord, PaymentRepository | 22/2 |
| `payment.payment.domain` `Money.kt` | 금액과 통화·통화 단위 | `CurrencyCode.kt`: CurrencyCode<br>`CurrencyUnit.kt`: CurrencyUnit<br>`Money.kt`(유지): 나머지 | 24/21 |
| `payment.payment.domain` `OrderIntent.kt` | 주문 고정 입력과 Site 식별자 | `MerchantSiteId.kt`: MerchantSiteId<br>`OrderIntent.kt`(유지): 나머지 | 27/20 |
| `payment.payment.domain` `OrderKey.kt` | 주문 key(environment+MID+merchantOrderId)와 요청 식별자·형식 | `Environment.kt`: Environment<br>`MerchantAccountId.kt`: MerchantAccountId<br>`MerchantOrderId.kt`: MerchantOrderId<br>`IdempotencyKey.kt`: IdempotencyKey<br>`MerchantRequestIdFormat.kt`: MerchantRequestIdFormat<br>`OrderIntentFingerprint.kt`: OrderIntentFingerprint<br>`PaymentId.kt`: PaymentId<br>`OrderKey.kt`(유지): 나머지 | 13/16 |
| `payment.payment.domain` `OrderPaymentControl.kt` | 주문 결제 통제 Aggregate와 슬롯 판정·사건 보류 | `PaymentSlotDecision.kt`: PaymentSlotDecision, BlockReason<br>`IncidentHold.kt`: IncidentReason, IncidentHold<br>`IncidentHoldRelease.kt`: HoldReleaseFinding, IncidentHoldRelease<br>`OrderPaymentControl.kt`(유지): 나머지 | 4/5 |
| `payment.payment.domain` `PaymentAuthorization.kt` | 승인 Aggregate와 Attempt·기관 관찰·거래 | `AuthorizationAttempt.kt`: ProviderRequestRef, AttemptState, AbandonCause, UnknownCause, AuthorizationAttempt<br>`InstitutionObservation.kt`: ObservationSource, InstitutionOutcome, ApprovedTerms, ObservationRecord, ObservationKey, InstitutionObservation<br>`PaymentTransaction.kt`: PaymentTransactionType, PaymentTransaction<br>`PaymentAuthorization.kt`(유지): 나머지 | 3/2 |
| `payment.payment.domain` `PaymentKey.kt` | 외부 결제 key와 결제수단 | `PaymentMethod.kt`: PaymentMethod<br>`PaymentKey.kt`(유지): 나머지 | 22/20 |
| `payment.payment.domain` `TerminationEvidence.kt` | 종료 근거와 Payment 상태 | `PaymentStatus.kt`: PaymentStatus<br>`TerminationEvidence.kt`(유지): 나머지 | 13/15 |
| `payment.payment.domain` `TransactionLimit.kt` | 거래 한도 정책 해석과 일 한도 사용량 Aggregate | `TransactionLimitPolicy.kt`: LimitAmount, TransactionLimitPolicy, LimitPolicyResolution, TransactionLimitPolicies<br>`DailyLimitUsage.kt`: LimitBusinessDate, LimitUsageKey, LimitRejection, LimitReservationState, LimitExcess, LimitReservation, DailyLimitUsage | 3/6 |

이 밖에 기존 이름 그대로 남은 파일(이미 타입당 하나이거나 §3 예외): `PaymentDomainException.kt`(sealed 계층), `AuthorizationIds.kt`, `PaymentPlan.kt`,
`AuthenticationSession.kt`, `AuthorizationStart.kt`, `AuthorizationResolution.kt`, `IdempotencyRecordStore.kt`, `OutboxStore.kt`, `PersistenceFailure.kt` 등.

## 3. 같은 파일에 둔 예외(지시서 §4 "종속된 작은 타입·private helper")

| 파일 | 함께 둔 타입 | 이유 |
|---|---|---|
| master `domain/InstitutionRefs.kt` | `RouteRef`, `InstitutionMerchantRef`, `private fun isRefChar` | 두 값 객체가 같은 문자 규칙(private helper)을 공유한다. `internal`로 넓히지 않았다 |
| payment `application/provided/CanonicalUuidIdentifiers.kt` | `IdempotencyRecordId`, `EventId`, `private object CanonicalUuid` | provided 패키지에 helper를 `internal`로 두면 bytecode상 public이라 Modulith named interface 표면이 넓어진다. private 유지가 공개 범위 보존 |
| payment `domain/AuthorizationIds.kt` | 승인 식별자 5개 + `private requireIdentifier` | 같은 이유(private helper 공유) |
| payment `entitlement/domain/EntitlementSnapshot.kt` | Snapshot 해석 모델(`EntitlementContent`·`IncomingSnapshot`·entry 타입·`CanonicalJson`·private JSON helper) | `CanonicalJson`과 파서가 private helper(`TIMESTAMP` 등)를 공유한다. schema 버전 상수·`isSupportedSnapshotSchema`만 `SnapshotSchema.kt`로 분리. 파일 이름은 업무 개념(권리 Snapshot) |
| 각 provided Port 파일 | Port + 그 command/result DTO (`MerchantOnboardingUseCase.kt`의 command 등) | 그 Port만 쓰는 입력·출력. 여러 Port가 함께 쓰는 `AdminCommandResult`·`AdminRequest` 등은 분리 |
| `@Embeddable` 키 | `RevisionKey`→`JpaContractRevisionEntity.kt` 등 | 그 Entity 전용 복합 키. 여러 Entity가 쓰는 `OrderKeyColumns`는 분리 |

## 4. 영향받는 검사·설정 점검 결과

| 항목 | 결과 |
|---|---|
| ArchUnit 규칙 패턴 | 변경 없음(패키지·FQN·메서드 이름 기반 규칙이 가리키는 타입은 이름·패키지 그대로). 따라서 위반 fixture 재확인 대상 없음 |
| `ArchitectureSpec` 문자열 FQN | `EntitlementEligibilityAdapter`·`EntitlementAuthorizationAdmissionAdapter`·`AuthorizationAdmissionPort`·domain FQN — 모두 불변 |
| Spring bean 이름 | 이름 바꾼 구현 bean의 기본 이름이 바뀐다(`jpaPaymentStore` → `jpaPaymentRepository` 등). `@Qualifier`·문자열 `getBean`·bean 이름 기반 health 없음(명시 이름은 `paymentDbSessionTimeZone`뿐, 불변) |
| JPA entity name·JPQL·named query | Entity 타입 불변, JPQL 문자열 불변 |
| 리플렉션·문자열 클래스 참조 | `FirstDefaultIssuanceAtomicitySpec`의 `AdminCommandExecutor` 로거 이름, `EligibilityPortWiringSpec`의 adapter FQN — 불변 |
| Flyway migration 주석 | `V2__master_runtime_privileges.sql`·`V4__entitlement_runtime_privileges.sql`(master), `V2__payment_runtime_privileges.sql`(payment) 주석이 옛 이름(`JpaBusinessStore.insert` 등)을 쓴다. **적용된 migration은 checksum 대상이라 고치지 않는다.** 대응표는 §1.1 |
| 과거 명세·검증 기록 | `docs/repository/CP-*`, `AUTH-CONTRACT-001`, `MASTER-*`, `harness/tasks.json` 등의 옛 이름은 당시 코드 기록이라 그대로 둔다. 현행 관례 문서 `ARCHITECTURE-CONVENTIONS.md`만 현재 이름으로 바꿨다(§14에 기록) |

## 5. 주석 정리

분리 도구는 파일 머리 공통 주석을 첫 타입에 붙인다. 다음은 뜻에 맞는 파일로 옮기거나 문구를 맞췄다(코드 변경 없음).

- 승인 시작 조건 저장 구현 공통 주석: `TransactionLimitPolicyJpaRepository.kt` → `JpaTransactionLimitPolicyRepository.kt`, 권리 입력 저장 구현 공통 주석:
  `EntitlementPersistenceMapper.kt` → `JpaPaymentMethodActivationRepository.kt`. "공통 관례는 JpaMasterStores와 같다" → "`PessimisticRowLock.kt` 머리말과 같다".
- master 승인 시작 조건 domain 머리말: `TransactionLimitPolicyId.kt` → `TransactionLimitPolicy.kt`. payment 권리 Snapshot 머리말: `SnapshotSchema.kt` → `EntitlementSnapshot.kt`.
- `Versioned.kt`(master required) 머리말 = MASTER 저장 Port 공통 관례임을 명시, `BusinessJpaRepository.kt`·`PaymentJpaRepository.kt` 머리말 = Spring Data 공통 관례임을 명시.

## 6. 용어사전 대응

| 용어사전(DOMAIN-DEFINITION §3·§5) | 코드 | 판정 |
|---|---|---|
| MerchantAccountSiteAssignment (MID–Site 연결) | `SiteAssignment`(`master.merchant.domain`) | 유지. merchant 모듈 안에서는 MID 쪽 연결만 존재해 짧은 이름이 모호하지 않다. Domain 이름 변경은 이번 범위 밖 |
| MerchantAccount(MID) | `MerchantAccount`, `MerchantAccountId` | 일치(축약 `Account` 사용 안 함) |
| ServiceAgreement·Agreement | `ContractServiceAgreement` | 일치(D-MD-001·002) |

## 7. 하위 패키지 정비안(후속 — 이번에 실행하지 않음)

같은 DB·같은 TX라는 이유만으로 같은 Aggregate로 보지 않았다. 아래 "참조 방향"은 분리 후 main 코드의 타입 참조를 그룹 단위로 센 결과(도구 분석, 주석 제외)다.

### 7.1 master `merchant.domain` — 순환 없음, 후속에서 실행 가능

| 후보 하위 패키지 | 대상 타입 | Aggregate 판정 | 참조 방향 |
|---|---|---|---|
| `domain.party` | Business·Merchant·MerchantSite(+ID·등록번호·채널·생명주기), SiteReviewDecision | 각각 별도 Root | → value |
| `domain.contract` | Contract(Revision·Scope·CSA 포함), EffectivePeriod | Contract Root(Revision·CSA는 내부) | → party, value |
| `domain.account` | MerchantAccount·DefaultDesignation·SiteAssignment, MerchantAccountIssuance(다중 Aggregate domain 서비스), PaymentMethod | MerchantAccount Root, SiteAssignment 별도 | → contract, party, value |
| `domain.activation` | PaymentMethodActivation·ActivationApproval, SiteActivationConfirmation, CurrencyEnablement·CurrencyUnit | 각각 별도 Root | → account, contract, party, value |
| `domain.terms` | TransactionLimitPolicy·PolicyVersion·PolicyAssignment·CardRouteActivation(+ID·코드) | 각각 별도 Root | → activation, account, contract, party, value |
| `domain.snapshot` | EntitlementSnapshot·Assembler·Source·CanonicalJson·ContractWindows | 통합 계약 값(Aggregate 아님) | → 전부(단방향) |
| 공통 값 | ActorRef·DecisionReason·EvidenceRef·CurrencyCode·DisplayName·MasterIdFormat·MasterRejection | 값 객체 | 의존 없음 |

판정: 그룹 간 상호 참조 0건이라 순환 없이 나눌 수 있다. 다만 공통 값의 패키지 이름이 `common`류 보관소가 되지 않게 이름·범위를 먼저 정해야 하고,
application·adapter·시험 import 수백 줄이 바뀐다. Modulith 공개 범위는 변하지 않는다(모듈 내부 하위 패키지). 다중 Aggregate 서비스
`MerchantAccountIssuance`는 `account`에 두면 단방향이 유지된다.

### 7.2 payment `payment.domain` — 지금 나누면 순환

| 후보 | 대상 | 상호 참조(분석) |
|---|---|---|
| `order` | OrderKey·OrderIntent·OrderPaymentControl·슬롯 판정·사건 보류 | ↔ payment(control이 Payment 상태·근거를 직접 읽고, Payment는 OrderKey·IncidentHold를 씀), ↔ authorization(`OperatorId`) |
| `payment` | Payment·PaymentStatus·TerminationEvidence·Money·`PaymentDomainException`(sealed) | ↔ order, ↔ authorization, ↔ limit(sealed 예외 계층과 ID가 양쪽에 걸침) |
| `authorization` | PaymentAuthorization·Attempt·Observation·Transaction·AuthenticationSession·PaymentPlan | ↔ payment, ↔ order, ↔ limit |
| `limit` | TransactionLimitPolicy·DailyLimitUsage | ↔ authorization(`AuthorizationId`), ↔ payment |
| (domain 서비스) | AuthorizationStart·AuthorizationResolution·RequestedPaymentExpiry·IncidentHoldSettlement | 모든 그룹 참조(단방향) |

판정: 순환 5쌍. 원인은 (1) 모든 업무 예외가 한 sealed `PaymentDomainException`에 있음(Kotlin sealed는 같은 패키지 제약), (2) 식별자(`AuthorizationId`·`OperatorId`·`PaymentId`)가 Aggregate 파일에 흩어짐,
(3) control↔Payment 직접 협력(CP-DOMAIN-001 §7 결정 — Splearn의 "다른 Aggregate는 조회만" 규칙과 다르다: PayCore는 `OrderPaymentControl.startAuthorization(payment)`처럼
같은 TX의 domain 연산이 두 Aggregate를 함께 바꾸며, 이것을 ArchUnit `paymentStartAuthorizationOnlyThroughControl`·`controlStartAuthorizationOnlyThroughAuthorizationStart`가 지킨다).
하위 패키지로 나누려면 예외 계층 분할·식별자 위치·`ArchitectureSpec`의 domain FQN(`$DOMAIN.Payment` 등)과 위반 fixture 갱신이 함께 필요하다 → 별도 설계 작업.

### 7.3 provided 하위 패키지

`application.<업무>.provided`·`application.provided.<업무>`로의 이동은 `@NamedInterface` 위치 규칙(`namedInterfacesOnlyOnProvidedPackages`)과 Modulith 공개 범위를 바꾸므로 이번에 하지 않았다(지시서 §3).

## 8. 후속 항목과 발견 사항

| ID(제안) | 내용 | 근거 |
|---|---|---|
| ARCH-NAMING-002 | facade/TX 소유 Port 이름 체계(CreatePayment·AUTH-001 공통), 다명령 provided Port 역할별 분할 여부, `*Port` 접미 반부패 Port 이름. 시험 쪽 옛 이름(review n-4·n-5, 이번에 하지 않음): `MasterFaultInjection`의 `faultInjecting…Store` bean 메서드(이제 `…Repository`를 돌려줌), `PaymentStoreAdapterSpec` 클래스명 | §1.2 |
| ARCH-SUBPKG-MASTER | master domain 하위 패키지(§7.1, 순환 없음 확인) | §7.1 |
| ARCH-SUBPKG-PAYMENT | payment domain 예외 계층·식별자 정비 후 하위 패키지(§7.2) | §7.2 |
| DOC-ALIGN | 범위 확정: 옛 타입 이름이 나오는 과거 명세 문서(`CP-API-001`·`AUTH-CONTRACT-001` 등)에 "타입 이름 대응은 ARCH-STRUCTURE-001 매핑 §1.1" **머리말 한 줄만 추가**한다. 본문 치환은 금지(당시 기록 보존). 적용된 migration 주석은 고정 | §4 |

정비 중 발견한 기존 기능 버그: 없음. 단, 이번 작업은 파일 경계·이름만 다뤘고 업무 로직을 버그 탐색 목적으로 검토하지 않았다
(분리 단계는 코드 줄 보존을 도구로 확인, 명명 단계는 §1.1 식별자의 whole-word 치환만).

## 9. 8대 원칙·JPA·jOOQ·BOM 확인 (이번 변경 기준)

이번 변경은 동작을 바꾸지 않는 구조 정비라 새 원칙 증거를 만들지 않는다. 각 원칙의 **기존 증거가 그대로 유효한지**를 아래처럼 확인했다.

| 원칙 | 이번 변경의 영향 | 확인 방법 |
|---|---|---|
| Financial Consistency | 금액·상태·판정 코드 줄 불변(분리 보존 검사 차이 0) | master/payment 전체 시험 통과(§10) |
| Transaction Safety | `LocalTransactions`·`@Transactional(MANDATORY)`·TX 경계 불변. Port 구현 클래스 이름만 변경 | `CreatePaymentRollbackSpec`(각 Port flush 뒤 장애 주입 — `FaultInjectionConfig`가 바뀐 구현 타입을 참조), `FirstDefaultIssuanceAtomicitySpec` 통과 |
| Idempotency | 멱등 fingerprint(r1·m1)·`IdempotencyRecordStore` 불변 | `CreatePaymentServiceSpec`, `ApiFailureSpec`(commit 불명확 → 같은 키 재요청) 통과 |
| Concurrency Control | 잠금 순서·`lockRow`(이제 `PessimisticRowLock.kt`)·`@Lock` 쿼리 불변 | `CreatePaymentConcurrencySpec`, `IncidentHoldConvergenceSpec`, `EntitlementEligibilitySpec`(head S/X) 통과 |
| Recoverability | 재시도·OutcomeUnknown 분류 불변 | `ApiFailureSpec` 통과 |
| Reconciliation | 대사 코드 없음(해당 없음) | — |
| Auditability | 변경 이력·생성 행위자 저장 코드 불변 | master/payment 전체 시험 통과 |
| Observability | 로거 이름(`AdminCommandExecutor` FQN — 클래스 불변)·지표 bean 불변. gauge 클래스는 파일만 분리 | `FirstDefaultIssuanceAtomicitySpec` 로그 문자열 검사 통과 |

JPA와의 연결(이번 변경 — 역할 → 예 → 이유 → 주의):
- **역할** — `Jpa<Aggregate>Repository`는 application.required `<Aggregate>Repository`를 구현하는 adapter.persistence bean이고, `<Entity>JpaRepository`는 그 안에서만 쓰는 Spring Data 기술 타입이다.
  순수 Domain(`Payment`)과 행 모양(`JpaPaymentEntity`)·변환(`PaymentPersistenceMapper`)의 분리는 그대로다.
- **예** — `JpaPaymentRepository.update`(옛 `JpaPaymentStore.update`)는 잠가 읽은 Entity의 기대 version을 비교한 뒤 Mapper로 값을 옮겨 변경 감지 UPDATE(`WHERE version = ?`)를 낸다.
  이름만 바뀌었고 이 순서·`flush`·`PESSIMISTIC_FORCE_INCREMENT`는 같은 줄 그대로다.
- **이유** — 이전에는 Aggregate 저장 Port와 멱등·Outbox 같은 기록 저장 Port가 모두 `…Store`라 "어느 것이 Aggregate의 version·잠금 계약을 갖는가"가 이름에 드러나지 않았다.
  판정 기준(AUTHN-001부터 새 저장 Port에 그대로 적용, CONVENTIONS §14):
  - **Repository** = Aggregate **식별자로 load/save**하는 Port. 변경 가능한 Aggregate면 `Versioned`(읽은 version)와 기대 version 갱신을 쓴다.
  - **Store** = 자기 Aggregate 식별자가 **없는** 결정 로그·프로토콜 기록·투영 기록(멱등 기록, Outbox, 관리 요청, 권리 투영·issue·relay, Credential 투영, Site 심사 결정 로그).
  - 경계 사례: `SiteActivationConfirmationRepository`는 append-only지만 `SiteActivationConfirmationId`로 `find`·`insert`하고 철회가 그 식별자에 붙는 생명주기라 Repository(append-only라 `Versioned` 없음).
    `SiteReviewStore`는 `SiteReviewDecision`에 자기 식별자가 없고 (Site, seq) 로그를 `history(siteId)`·`append`로만 다루며 상태를 로그에서 계산하므로 Store.
- **주의** — Spring Data `save`를 쓰는 습관이 이름(`Repository`) 때문에 생기지 않게 한다: 새 행은 계속 `persist`만(§9.1·§9.2 — append-only 표에 merge는 1142).
  `<X>JpaRepository`를 application에 주입하는 것은 계속 ArchUnit(`applicationDependsOnlyOnPorts`)이 막는다. JPQL은 Entity simple name을 쓰므로 Entity 이름을 바꿀 때는 JPQL을 함께 바꿔야 한다(이번에는 Entity 이름 불변).

jOOQ(CQRS): 조회 Port(`…Reader`)·adapter(`Jooq…Reader`)·조회 전용 규칙(`queryAdapterDoesNotWriteWithJooq`) 불변. BOM·의존성: 빌드 파일 변경 없음 → GRADLE-DEPS·CVE-SCAN 재실행 대상 아님.
Kafka/Redis: 도입하지 않았다. 이 정비는 브로커·캐시로 금융 안전성(로컬 TX·Outbox·DB 제약)을 대체하지 않는다.

## 10. 검증 기록

구현자 실행 기록이다(WSL Ubuntu, Docker 29.8.0, MySQL 8.4.11 Testcontainers). 등록된 VERIFY-GATE·독립 검증은 Verifier·Coordinator 몫이다.

| 단계 | 명령 | exit | 결과 |
|---|---|---|---|
| B 분리 후 | `./gradlew :apps:master-api:test :apps:payment-api:test` | 0 | master 314 / payment 739, 실패·skip 0 (보고서 23:26Z·23:29Z) |
| C 개명 후 | 같음 | 0 | master 314 / payment 739, 실패·skip 0 (보고서 23:34Z·23:38Z) |
| 최종 1 | `./gradlew --stop` → `./gradlew --no-daemon --console=plain --no-build-cache check` | 0 | 모든 `:test`가 UP-TO-DATE(C 이후 소스 변경 없음)라 **새 실행 증거 아님** |
| 최종 2 | `./gradlew --no-daemon --console=plain --no-build-cache --rerun-tasks check` (2026-10-15T23:41:13Z–23:50:47Z) | 0 | 5개 모듈 `:test` 실행. master-api 314, payment-api 739, financial-batch 26, internal-admin 29, test-support 112 = **1220**, 실패·오류·skip 0 |
| 최종 2 판정 | `python3 harness/scripts/test-summary.py --not-before 2026-10-15T23:41:13Z 1103 <5개 보고서 디렉터리>` | 0 | `total tests=1220 failures=0 errors=0 skipped=0` PASS |
| harness | `python3 -B -m unittest discover -s harness/tests` | 0 | `Ran 72 tests` `OK` |

fingerprint: 마지막 문서 수정 뒤 `harness/scripts/fingerprint.sh` 값은 작업 보고에 적는다(이 문서도 fingerprint 대상이라 여기 적으면 값이 바뀐다).
삭제된 추적 파일 36개는 fingerprint의 "tracked file missing" 방지를 위해 `git rm --cached`로 index에 반영했다(커밋 아님).
