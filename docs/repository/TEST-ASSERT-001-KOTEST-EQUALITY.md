# TEST-ASSERT-001 Kotest 동등 비교 공허 통과 전수 점검·교체 명세 (rev 1.1 — 리뷰 CHANGES_REQUIRED M-1·L-1·L-2·N-1 반영)

작성 2026-10-26 KST, 기준 commit `514a747`(branch `feat/foundation-setup`). 이 문서는 새 업무 정책을 채택하지 않는다. 운영 코드 동작·API·스키마·의존성 변경 없음
(운영 코드 변경은 internal-admin `SecurityConfig` 주석 1곳뿐). 기대값은 하나도 바꾸지 않았다.

## 1. 범위

- **대상**: `apps/*/src/test`, `libs/*/src/test`의 Kotest 동등 단언(`shouldBe`·`shouldNotBe`·`shouldContain`·`shouldContainExactly` 등 Kotest `eq`를 쓰는 matcher).
- **입력**: ADMIN-BOOT-001 S8 리뷰(High, 작업 간 공통) — internal-admin `KotestEqualityProbeSpec` 실측(§25.11)에서 `Pair`/data class 안 `data object` 기대값이 공허 통과.
- **산출**: ① Kotest 6.2.5 동작 고정 meta 시험(libs/test-support), ② 공허 꼴 전수 검색·교체(변이 RED 증거), ③ 재발 방지 장치, ④ DONE 작업 증거 영향,
  ⑤ ADMIN-BOOT-001 final nit(SecurityConfig 주석).
- **범위 밖**: 업무 정책·기대값·게이트 기준 변경, 새 의존성, 빌드 파일, harness 상태 파일.

## 2. 실측 표 (Kotest 6.2.5, `libs/test-support` `KotestEqualityProbeSpec`)

"통과"는 **실제로는 다른 값인데 단언이 통과**한다는 뜻(= 공허). 시험용 타입: `sealed interface Outcome { data class Refused(code); data class Rejected(code); data object Lost; data object Expired }`,
`data class Wrapper(outcome)`, `Outer(wrapper)`, `Outer2(outer)`, `Holder(List<Wrapper>)`, `Holder2(List<Outcome>)`, `Two(outcome, n)`.
아래 "기본"은 Kotest 기본 설정(`kotest.assertions.show-data-class-diffs=true`), "가드"는 §7의 공유 ProjectConfig 설정(`false`)이다.

| # | 꼴 | 기본 | 가드 | `shouldBeEqual` |
|---|---|---|---|---|
| 1 | `Pair(1, Refused(X)) shouldBe Pair(1, Lost)` | **통과(공허)** | 실패 | 실패 |
| 2 | `W(Refused(X)) shouldBe W(Lost)` — 사용자 data class가 data object를 감쌈(CreatePaymentEligibilitySpec 꼴) | **통과(공허)** | 실패 | 실패 |
| 3 | `Triple(1, Refused(X), "s") shouldBe Triple(1, Lost, "s")` | **통과(공허)** | 실패 | 실패 |
| 4 | `Two(Refused(X), 1) shouldBe Two(Lost, 1)` — 다른 필드는 같음 | **통과(공허)** | 실패 | — |
| 5 | 깊이 2·3: `Outer(W(Refused(X))) shouldBe Outer(W(Lost))`, `Outer2(…)` | **통과(공허)** | 실패 | 실패(깊이 3) |
| 6 | `listOf(1 to Refused(X)) shouldBe listOf(1 to Lost)` — List 원소가 data class | **통과(공허)** | 실패 | 실패 |
| 7 | `mapOf("k" to W(Refused(X))) shouldBe mapOf("k" to W(Lost))` — Map 값이 data class | **통과(공허)** | 실패 | 실패 |
| 8 | `Holder(listOf(W(Refused(X)))) shouldBe Holder(listOf(W(Lost)))` — data class 안 List 필드 | **통과(공허)** | 실패 | 실패 |
| 9 | `listOf(1 to Refused(X)) shouldContain (1 to Lost)`, `shouldContainExactly listOf(1 to Lost)` | **통과(공허)** | 실패 | — |
| 10 | 방향 반대(실제값 쪽이 data object): `W(Lost) shouldBe W(Refused(X))`, Pair, 깊이 2·3, List of Pair | 실패(엄격) | 실패 | — |
| 11 | 같은 속성 이름·값의 다른 하위형: `W(Refused(X))` vs `W(Rejected(X))`, Pair | 실패(엄격) | — | — |
| 12 | 서로 다른 data object: `W(Lost) shouldBe W(Expired)` | 실패(엄격) | — | — |
| 13 | 다른 필드도 다름: `Two(Refused(X), 1) shouldBe Two(Lost, 2)` | 실패(엄격) | — | — |
| 14 | 직접 비교(양방향), `listOf(Refused(X))`·`mapOf("k" to Refused(X))`·`Holder2(listOf(Refused(X)))` — sealed 값 자체가 원소/값 | 실패(엄격) | — | — |
| 15 | enum 필드, 일반 `object`(data 아님) 필드, 기대값 `null` 필드 | 실패(엄격) | — | — |
| 16 | 서로 다른 최상위 data class(`W(…) as Any` vs `OtherWrapper(…)`) | 실패(엄격) | — | — |
| 17 | `shouldBeIn`(equals) — Pair, ApprovalRaceSpec `Pair<Any, Any>` 꼴 | 실패(엄격) | — | — |
| 18 | `shouldNotBe`를 공허 꼴에 쓰면 | 실패(값이 다른데도 — 시끄럽게 틀림, 조용한 통과 아님) | — | — |
| 19 | `shouldBeEqual`로 같은 값 비교 | 통과(정상) | — | 통과 |

internal-admin `KotestEqualityProbeSpec`(S8)의 기존 4행은 같은 결과로 유지하고(기본값을 명시해 재도록 수정), 가드 확인 1행을 더했다.

## 3. 원인

`kotest-assertions-core` 6.2.5 `io.kotest.assertions.eq.DataClassEq`(바이트코드 확인 + §2 실측):

1. 양쪽이 data class(Kotlin `data object`도 `isData`)이면 `DefaultEqResolver`가 `DataClassEq`를 고른다(`show-data-class-diffs` 기본 true일 때만).
2. `DataClassEq`는 `equals`가 false이면 필드별 차이를 계산하고, **차이 목록이 비면 성공**으로 판정한다.
3. 필드 값이 서로 다르고 둘 다 data class이며 한쪽이라도 속성이 있으면 그 값을 재귀로 비교하는데, 재귀는 **한쪽 값의 클래스의 주 생성자 속성만 나열**한다.
   실측상 기대값 쪽 중첩 값이 `data object`(속성 0개)이면 나열할 속성이 없어 차이 0개 → 그 필드는 "같다"가 된다(깊이 1~3 모두, §2 #1~#9).
4. 최상위에서는 "차이 0개 + 실제값 속성 0개 → 실패" 보호가 있어 직접 비교는 엄격하다(#14). List·Map 원소는 원소마다 최상위 비교를 다시 하므로,
   원소가 sealed 값 자체이면 엄격(#14)이고 원소가 data class이면 그 안의 data object가 같은 함정에 걸린다(#6·#7·#8).

## 4. 금지 꼴

**eq 기반 단언의 기대값 안 깊이 1 이상**(data class·`Pair`·`Triple` 필드, 또는 그 안의 List/Map을 거쳐 닿는 data class 필드)에 `data object`가 있고,
같은 자리의 실제값이 **속성 있는 data class**일 수 있는 비교. 기대값이 리터럴이든 변수·fixture 함수 결과든 같다.
대체: `shouldBeEqual`(Kotest `io.kotest.matchers.equals`, `equals` 기반 — #19) 또는 하위형·필드를 직접 단언. 기본 설정에서는 `shouldBeIn`도 엄격하다.

## 5. 검색 방법과 결과

### 5.1 방법

| 단계 | 방법 | 규모 |
|---|---|---|
| A | 모든 `apps/*/src`, `libs/*/src`(main+test)의 `data object` 선언 수집(이름·상위 sealed 타입) | 선언 128개(새 probe의 2개 제외), 서로 다른 이름 약 60개, 상위 sealed 70개 |
| B | 테스트의 eq 계열 단언문(이어지는 줄 포함 최대 12줄)에서 기대값 쪽에 data object 이름이 **감싸인 채**(`to`·`Pair`·`Triple`·`listOf`·`mapOf`·`setOf`·생성자 호출 안) 나오는 곳 | 30건 |
| C | data object 변형과 속성 있는 data class 변형을 함께 가진 sealed 타입("가림 가능" 68개)과, 그 타입을 필드로 (전이적으로) 가진 data class("위험 컨테이너" 59개)를 계산하고, 기대값 쪽에서 위험 컨테이너 생성자를 호출하는 단언 | 18건(B와 일부 중복) |
| D | 위험 컨테이너를 반환하는 테스트 fixture 함수(`evidence`·`approvedTerms`·`binding`·`limitPolicy` 등 42개)를 기대값 쪽에서 호출하는 단언 | 6건 |
| E | `to`/`Pair`/`Triple`이 기대값에 있는 나머지 단언 306건을 사람이 훑어 원소 타입 확인(문자열·숫자·enum·Set 등은 제외) | 추가 공허 0건 |
| F | 이름 기반 grep(`binding`·`plan`·`LumpSum`·`Unlimited`·`JNull`·`SystemRecovery`·`AuditScope`·`SiteChannelEntry`) | 추가 1건(D와 중복) |
| G | **동적 확인**: §7 가드(전 모듈 `shouldBe`를 equals 판정으로)를 켠 채 모든 모듈 시험 전체 실행 — 정적 검색이 못 보는 꼴에서 실제값이 기대값과 **다르게 숨어 있던** 단언이 있으면 실패로 드러난다 | §8.3 |
| H | **타입 정보 기반 검토(2차, 리뷰 M-1 반영)**: 기대값이 변수·fixture·상수인 비교를 대상으로, 실제값 쪽 마지막 속성/함수 이름 → 선언 타입(main+test의 `val x: T`, `fun f(): T`, 지역 `val x = …` 초기식)으로 정적 타입을 구하고, 그 타입이 위험 컨테이너(또는 그 List·Pair)이면 후보로 잡았다(161건, 이름 충돌 포함). 기대값이 `null`·`emptyList()`·리터럴 생성자(B~D에서 이미 판정)인 것을 빼고 남은 47건을 사람이 선언·fixture를 읽어 판정했다. 실제값 쪽이 앞 줄에서 이어지는 단언(`} shouldBe x`, `) shouldBe x`)과 이름으로 타입을 못 정한 지역 변수 57건도 따로 읽었다 | 추가 VACUOUS 15건(§5.2 #22~#36, 모두 교체) |

1차(A~F)는 문법 기반 과대 근사여서 기대값이 변수인 비교를 가드에만 맡겼으나, 그 판정은 **타입을 읽으면 가능**했다(리뷰 지적). 2차 H에서 그 비교를
모두 선언 타입·fixture 값으로 판정했고, 금지 꼴은 전부 교체했다(§5.2). **가드에만 의존하는(GUARD-ONLY) 곳은 남기지 않았다** — 교체하지 않은 곳은
모두 기대값 쪽 해당 자리에 data object가 없어 §2 실측상 엄격한(STRICT) 곳이다. 가드와 G는 앞으로 생길 꼴과 판정 실수에 대한 이중 안전장치다.

### 5.2 판정 표(후보 전부)

판정: **VACUOUS** = 금지 꼴(**REPLACED** — 교체함), **STRICT** = 기대값 쪽 해당 자리에 data object가 없어 §2 실측상 엄격(교체 안 함), **NOISE** = 검색 잡음.
GUARD-ONLY(가드에만 의존) 판정은 없다. #1~#21 줄은 기준 commit `514a747` 기준, #22~ 줄은 1차 교체 뒤 파일 기준(1차에서 import가 1~2줄 늘어난 파일 포함).

| # | 위치 | 꼴 | 판정 | 근거(§2 행) |
|---|---|---|---|---|
| 1 | payment-api `CreatePaymentEligibilitySpec:75` | `EligibilityRefused(EligibilityRefusal.NotFoundOrForbidden)` | **VACUOUS** | #2 |
| 2 | payment-api `CreatePaymentEligibilitySpec:98` | `EligibilityRefused(refusal)`, 첫 행 `refusal = NotFoundOrForbidden` | **VACUOUS** | #2 |
| 3 | payment-api `EntitlementAuthorizationAdmissionAdapterSpec:59` | `Resolved(TransactionLimitPolicy(…, LimitAmount.Unlimited))` | **VACUOUS** | #5(깊이 2) |
| 4 | payment-api `SimulatorCardAuthenticationAdapterSpec:125` | `Found(ProviderAuthenticationResult(… ProviderBindingEcho(… PaymentPlan.LumpSum …)))` | **VACUOUS** | #5(깊이 3) |
| 5 | payment-api `SimulatorVanAuthorizationAdapterSpec:108` | `ApprovedTerms(…, PaymentPlan.LumpSum, …)` | **VACUOUS** | #2 |
| 6 | payment-api `AuthenticationResultCheckSpec:181` | `VerifiedAuthentication(…, s.binding(plan = LumpSum), …)` | **VACUOUS** | #5(깊이 2) |
| 7 | payment-api `AuthenticationSessionSpec:291` | `.verified shouldBe evidence(b)`(binding plan LumpSum) | **VACUOUS** | #5 |
| 8 | payment-api `AuthenticationSessionSpec:305` | `v.verified shouldBe evidence(v.binding)` | **VACUOUS** | #5 |
| 9 | payment-api `AuthorizationResolutionSpec:198` | `terms shouldBe approvedTerms()`(plan LumpSum) | **VACUOUS** | #2 |
| 10 | payment-api `EntitlementContractGoldenSpec:86` | `Map<SiteId, SiteChannelEntry>`, 값 `SiteChannelEntry.App` | STRICT | #14(Map 값이 sealed 값 자체) |
| 11 | internal-admin `ApprovalRaceSpec:172` | `(r0 to r1) shouldBeIn listOf<Pair<Any, Any>>(Expired to 0, AlreadyDecided to 1)` | STRICT | #17 |
| 12 | payment-api `EntitlementEligibilitySpec:93,100,168,203` | `EligibilityRefused(NotEligible(enum))`·`EligibilityRefused(Unavailable(enum))` | STRICT | #10·#11·#15(기대값 쪽에 data object 없음) |
| 13 | payment-api `AuthorizationAdmissionSpec:99` | `Resolved(TransactionLimitPolicy(…, Limited, Limited))` | STRICT | #10 |
| 14 | payment-api `ConfiguredInstitutionRouteRegistrySpec:82` | `CancelCapabilityResolution.Resolved(CancelCapability(…))` | STRICT | 기대값에 data object 없음 |
| 15 | payment-api `TransactionLimitSpec:31` | `Resolved(krw)`, `krw = limitPolicy()`(Limited/Limited) | STRICT | 기대값에 data object 없음 |
| 16 | payment-api `EntitlementContentV3Spec:78` | `(Lifecycle.TERMINATED to SiteChannelEntry.Web(…))` | STRICT | #10 |
| 17 | internal-admin `EnrollmentFlowSpec:295,316` | Pair of `Succeeded(id)`·`Failed(enum)`·`Completed(id)` | STRICT | #10 |
| 18 | internal-admin `AdminOwnedCommandSpec:251`, `ApprovalLockOrderSpec:243,260`, `ApprovalDecisionTableSpec:37`, `LoginServiceSpec:100,105,117`, `AdminAccountSpec:305,317` | List/Set/List of List의 원소가 sealed 값 자체 | STRICT | #14 |
| 19 | payment-api `CheckoutExpiryConcurrencySpec:235` | `started::class shouldBeIn …`(KClass) | STRICT | equals |
| 20 | internal-admin `RecoveryCommandSpec:315,333`, `ApprovalLockOrderSpec:148`, `AdminSchemaSpec:143`, payment-api `CancelFingerprintsSpec:30`, `MerchantWebhookPersistenceSpec:97` | 주석 문장·변수명이 data object 이름과 겹침 | NOISE | — |
| 21 | 단계 E 306건 | 문자열·숫자·enum·Set·`null` 원소 Pair/Triple, HTTP (status to body) 등 | STRICT | #15 |
| 22 | payment-api `PaymentAuthorizationReconstitutionSpec:37` | `restored.observations shouldBe original.observations` — `List<InstitutionObservation>`, 원소 `approved: ApprovedTerms?`(plan `LumpSum`) | **VACUOUS → REPLACED** `shouldBeEqual` | #6·#8(List 원소 data class 안 data object) |
| 23 | 같은 spec `:38` | `keyConflictingObservations` 같은 타입(늦은 승인 시나리오에서 approved 있음) | **VACUOUS → REPLACED** `shouldBeEqual` | #6 |
| 24 | 같은 spec `:39` | `restored.transaction shouldBe original.transaction` — `AuthorizationTransaction?`(terms.plan `LumpSum`, 시나리오에 따라 null) | **VACUOUS → REPLACED** `shouldBeEqualNullable` | #5 |
| 25 | payment-api `AuthorizationResolutionSpec:167` | `….approved shouldBe termsB`(`approvedTerms().copy(…)`, plan `LumpSum`) | **VACUOUS → REPLACED** `shouldBeEqualNullable` | #2 |
| 26 | 같은 spec `:201`(리뷰 지적) | `s.authorization.transaction shouldBe outcome.transaction` | **VACUOUS → REPLACED** | #5 |
| 27 | 같은 spec `:288` | `s.authorization.transaction shouldBe first`(authorized fixture) | **VACUOUS → REPLACED** | #5 |
| 28 | 같은 spec `:378` | `gen2.authorization.transaction shouldBe outcome.transaction` | **VACUOUS → REPLACED** | #5 |
| 29 | 같은 spec `:580` | `s.authorization.transaction shouldBe first` | **VACUOUS → REPLACED** | #5 |
| 30 | payment-api `AuthenticationSessionSpec:98` | `s.verified shouldBe ev`(VerifiedAuthentication, binding plan `LumpSum`) | **VACUOUS → REPLACED** | #5 |
| 31 | 같은 spec `:106` | 같은 꼴 | **VACUOUS → REPLACED** | #5 |
| 32 | payment-api `CheckoutPersistenceSpec:144` | `verified.value.verified shouldBe loaded.value.verified`(영속 왕복) | **VACUOUS → REPLACED** | #5 |
| 33 | payment-api `AuthorizationPersistenceSpec:247` | `transaction!!.terms shouldBe terms(p)`(fixture plan `LumpSum`) | **VACUOUS → REPLACED** `shouldBeEqual` | #2 |
| 34 | payment-api `CancellationPersistenceSpec:336` | `authAfter.transaction shouldBe authBefore.transaction` | **VACUOUS → REPLACED** | #5 |
| 35 | 같은 spec `:338` | `views.find(…) shouldBe viewBefore` — `AuthorizationViewRow?`(`approved: ApprovedTerms?`) | **VACUOUS → REPLACED** | #2 |
| 36 | payment-api `PaymentStatusViewsSpec:68` | `v.authorization?.approved shouldBe TERMS`(상수, plan `LumpSum`) | **VACUOUS → REPLACED** | #2 |
| 37 | payment-api `AuthorizationResolutionSpec:309`, `CancellationResolutionSpec:321`, `PaymentCancellationSpec:109` | `keyConflictingObservations shouldBe listOf(conflicting)` — `conflicting = observation(DECLINED)`(approved `null`) 또는 취소 관측(`CancelConfirmedTerms` — 가림 가능 타입 없음) | STRICT | #15(기대 null) / 위험 타입 아님 |
| 38 | payment-api `CancellationResolutionSpec:41,225,303,381`, `PaymentCancellationSpec:98,108`, `CancellationPersistenceSpec:315` | 취소 `transaction`·`observations`·`terms` — `CancelTransaction`·`CancelObservation`·`CancelConfirmedTerms`에는 가림 가능 sealed 필드가 없다 | STRICT | 위험 타입 아님 |
| 39 | payment-api `SnapshotApplicationRuleSpec:25` | `appliedContent shouldBe content`, `content = entitled()` — schema v1(채널·승인 조건 없음)이라 기대값 안에 data object 없음 | STRICT | 기대값에 data object 없음 |
| 40 | payment-api `SimulatorCardAuthenticationAdapterSpec:115` | `register(…) shouldBe expected` — `RegistrationResult`(Registered·Failed(enum)만, data object 변형 없음) | STRICT | 위험 타입 아님 |
| 41 | payment-api `PaymentAuthorizationReconstitutionSpec:34·35`, `IncidentHoldUnexplainedApprovalSpec:45`, `OrderPaymentDecisionTableSpec:98` 등 `decide(…)` 결과 | `ProviderRequestRef`·enum Triple/Pair, `IncidentHold`, `PaymentSlotDecision`(data object 변형 없음) | STRICT | 위험 타입 아님 |
| 42 | internal-admin `AdminAccountCommandSpec:164`, `AdminAccountSpec:109` | `pending shouldBe …pending` — `PendingEnrollment(enum, String)` | STRICT | 위험 타입 아님(이름 충돌로 잡힘) |
| 43 | master-api `MerchantAuthorizationTermsServiceSpec:320` | `issuesOf(…).last()` = `IssueRow`(원시 값) | STRICT | 위험 타입 아님 |
| 44 | H의 나머지(이름 충돌: `find`·`decide`·`approve`·`of`·`row`·`evidence` 등, 지역 변수 57건 — 문자열·숫자·Set·enum·Map 행) | — | STRICT/NOISE | 위험 타입 아님 |

master-api·institution-simulator·financial-batch·libs/test-support(기존 시험)에서는 금지 꼴이 나오지 않았다(H 포함).

## 6. 교체와 변이 RED 증거

### 6.1 1차(9곳)

교체: 9곳 모두 `shouldBe` → `shouldBeEqual`(nullable 실제값은 `.shouldNotBeNull() shouldBeEqual` — 기대값이 non-null이므로 의미 동일). 기대값 식은 글자 그대로 유지.

변이: **실제값 쪽**만 바꿔 "기대값이 data object인 자리에 속성 있는 data class가 온" 결함을 흉내 낸다(기대값을 다른 하위형으로 바꾸면 방향이 반대(#10)라
옛 꼴도 실패하므로 공허를 증명하지 못한다). 기대값은 건드리지 않는다. 변이 스크립트는 시험 파일 사본을 쓰고 끝나면 복원했다(복원 후 `cmp` 동일 확인).

| 변이 | 위치 | 실제값 변이 |
|---|---|---|
| M1 | CreatePaymentEligibilitySpec:75 | 대역 `ownSiteOnly`가 남의 Site에 `NotEligible(entries.first())` 응답(→ `EligibilityRefused(NotEligible(CURRENCY_NOT_ENABLED))`) |
| M2 | CreatePaymentEligibilitySpec:98 | `cases` 첫 행 port 응답을 `NotEligible(entries.first())`로(기대 `refusal`은 그대로 `NotFoundOrForbidden`) |
| M3 | EntitlementAuthorizationAdmissionAdapterSpec:59 | 입력 daily `LimitBound.Unlimited` → `Limited(9)` |
| M4 | SimulatorCardAuthenticationAdapterSpec:125 | 실제 `Found`의 binding plan → `Installment(3)` |
| M5 | SimulatorVanAuthorizationAdapterSpec:108 | `o.approved.copy(plan = Installment(3))` |
| M6 | AuthenticationResultCheckSpec:181 | `s.verified`의 binding plan → `Installment(3)` |
| M7 | AuthenticationSessionSpec:291 | `.verified`의 binding plan → `Installment(3)` |
| M8 | AuthenticationSessionSpec:305 | `v.verified`의 binding plan → `Installment(3)` |
| M9 | AuthorizationResolutionSpec:198 | `terms.copy(plan = Installment(3))` |

실행(같은 8개 spec, `./gradlew --console=plain --no-build-cache :apps:payment-api:test --tests …` × 8, 146건):

| 실행 | 단언 꼴 | 가드 | 결과 |
|---|---|---|---|
| GREEN(변이 없음) | 새 꼴 | 켬 | exit 0, 146건 실패 0(2026-10-26T12:19Z) |
| R-old | 옛 꼴 `shouldBe` + M1~M9 | 끔(Kotest 기본) | **exit 0, 146건 실패 0** — 9곳 모두 결함을 놓침(공허 확인)(12:21Z) |
| R-new | 새 꼴 `shouldBeEqual` + M1~M9 | 끔 | **exit 1, 실패 9 = 변이 9곳**, 각 실패 위치가 교체한 줄(새 파일 기준 76·99·60·126·110·183·290(문장 시작)·307·199)(12:22Z) |
| R-guard | 옛 꼴 `shouldBe` + M1~M9 | 켬 | **exit 1, 실패 9 = 변이 9곳**, 위치 75·98·59·125·108·181·288(문장 시작)·305·198 — 가드만으로도 잡힘(12:23Z) |

실패 메시지 예: R-new `EligibilityRefused(refusal=NotEligible(reason=CURRENCY_NOT_ENABLED)) should be equal to EligibilityRefused(refusal=NotFoundOrForbidden)`,
R-guard `expected:<EligibilityRefused(refusal=NotFoundOrForbidden)> but was:<EligibilityRefused(refusal=NotEligible(…))>`.
**교체로 드러난 실제 결함은 없다**(GREEN에서 9곳 모두 실제값 = 기대값).

### 6.2 2차(15곳, 리뷰 M-1 (c) — §5.2 #22~#36)

교체: non-null 실제값은 Kotest `shouldBeEqual`, nullable 실제값(기대값도 null일 수 있는 복원·조회 비교)은 새 공유 helper
`com.paycore.testsupport.kotest.shouldBeEqualNullable`(libs/test-support main — `this != expected`이면 `AssertionError`, 새 의존성 없음,
probe에서 Kotest 기본 설정으로도 엄격함과 null 처리를 고정). 기대값 식은 글자 그대로 유지.

변이(실제값 쪽만, 1차와 같은 방법): plan `LumpSum` 자리에 `Installment(3)`가 오게 실제값을 `copy`한다. 한 시험 블록 안의 여러 자리는 앞 단언이 먼저 실패해
뒤 자리를 가리므로, 같은 블록의 자리를 서로 다른 묶음(A·B·C)에 나눠 묶음마다 따로 실행했다(묶음 안의 자리는 모두 다른 시험 블록).

| 변이 | 묶음 | 위치(§5.2) | 실제값 변이 |
|---|---|---|---|
| N1 | A | ReconstitutionSpec:37 (#22) | `restored.observations.map { o -> o.copy(approved = o.approved?.copy(plan = Installment(3))) }` |
| N2 | B | ReconstitutionSpec:38 (#23) | `keyConflictingObservations` 같은 변이 |
| N3 | C | ReconstitutionSpec:39 (#24) | `restored.transaction?.let { it.copy(terms = it.terms.copy(plan = Installment(3))) }` |
| N4 | A | ResolutionSpec:167 (#25) | `….approved?.copy(plan = Installment(3))` |
| N5 | A | ResolutionSpec:201 (#26) | transaction terms plan 변이 |
| N6 | A | ResolutionSpec:288 (#27) | 같은 변이 |
| N7 | B | ResolutionSpec:378 (#28) | 같은 변이 |
| N8 | B | ResolutionSpec:580 (#29) | 같은 변이 |
| N9 | A | AuthenticationSessionSpec:98 (#30) | `s.verified?.let { it.copy(binding = it.binding.copy(plan = Installment(3))) }` |
| N10 | B | AuthenticationSessionSpec:106 (#31) | 같은 변이 |
| N11 | A | CheckoutPersistenceSpec:144 (#32) | `verified.value.verified` binding plan 변이 |
| N12 | A | AuthorizationPersistenceSpec:247 (#33) | `terms.copy(plan = Installment(3))` |
| N13 | A | CancellationPersistenceSpec:336 (#34) | transaction terms plan 변이 |
| N14 | B | CancellationPersistenceSpec:338 (#35) | `views.find(…)?.let { it.copy(approved = it.approved?.copy(plan = Installment(3))) }` |
| N15 | A | PaymentStatusViewsSpec:68 (#36) | `approved?.copy(plan = Installment(3))` |

실행(7개 spec + payment-api `TestInfrastructureSpec`, `./gradlew --console=plain --no-build-cache :apps:payment-api:test --tests …` × 8, 151건). 변이 실행은 모두 **가드 끔**:

| 실행 | 단언 꼴 | 결과 |
|---|---|---|
| GREEN(변이 없음, 가드 켬) | 새 꼴 | exit 0, 151건 실패 0(13:04–13:05Z) |
| old-A | 옛 꼴 `shouldBe` + N1·N4·N5·N6·N9·N11·N12·N13·N15 | 151건 중 실패 1 = `TestInfrastructureSpec` 가드 확인 행뿐(가드를 껐으므로 의도된 실패 — 이 행이 가드 누락을 잡는다는 증거). **9곳 모두 통과(공허)**(13:05–13:07Z) |
| new-A | 새 꼴 + 같은 변이 | 실패 11 = 가드 행 1 + **9곳 전부**(N1은 승인 거래가 있는 두 시나리오 — AUTHORIZED, 늦은 승인 기록), 실패 위치 = 교체한 줄(새 파일 39·168·202·289·99·145·248·337·69)(13:07–13:08Z) |
| old-B | 옛 꼴 + N2·N7·N8·N10·N14 | 실패 1 = 가드 행뿐. **5곳 모두 통과(공허)**(13:08–13:10Z) |
| new-B | 새 꼴 + 같은 변이 | 실패 6 = 가드 행 1 + **5곳 전부**(N2는 key 충돌 늦은 승인 시나리오), 위치 40·379·581·107·339(13:10–13:11Z) |
| old-C | 옛 꼴 + N3 | 실패 1 = 가드 행뿐. **N3 통과(공허)**(13:11–13:13Z) |
| new-C | 새 꼴 + N3 | 실패 3 = 가드 행 1 + N3(거래가 있는 두 시나리오), 위치 41(13:13–13:14Z) |

변이 스크립트는 끝에 새 꼴 파일과 `ProjectConfig`를 복원했고 `cmp`로 동일함을 확인했다. **2차 교체로 드러난 실제 결함도 없다**(GREEN 151/0).

## 7. 재발 방지 결정

| 후보 | 결정 | 이유 |
|---|---|---|
| 공유 `ProjectConfig`에서 `kotest.assertions.show-data-class-diffs=false` | **채택(구현)** | Kotest가 공식으로 읽는 설정(`AssertionsConfig.showDataClassDiff`, 호출마다 sysprop 조회). 끄면 `DataClassEq`를 고르지 않고 `equals`로만 판정 → 금지 꼴이 문법·타입과 무관하게 **전 모듈에서 사라진다**(§2 "가드" 열, 변수 기대값 포함). 새 의존성 없음, 1줄. ProjectConfig는 이미 모든 app 시험이 공유(각 app `TestInfrastructureSpec`이 로드 위치 확인). 대가: data class 불일치 실패 메시지의 필드별 diff가 사라지고 `expected:<…> but was:<…>` 전체 문자열 비교만 남는다. data class 안 배열 필드는 `equals`(참조)로 비교되므로 내용 비교가 필요한 시험은 배열을 직접 단언해야 한다 — 전 모듈 실행(§8.3)으로 기존 시험 영향 확인 |
| 고정 시험 | 채택 | libs/test-support `KotestEqualityProbeSpec`: Kotest 기본 동작 20행 + 가드 2행(엔진이 로드한 속성 값 확인, 공허 꼴 10종이 가드 아래에서 실패) + helper 1행. internal-admin probe에 가드 1행. Kotest를 올려 동작·설정 이름이 바뀌면 이 시험이 실패한다 |
| 개별 단언 `shouldBeEqual`·`shouldBeEqualNullable` | 채택(1차 9곳 + 2차 15곳) | 가드와 독립적으로 엄격(R-new, §6.2). 가드가 사라져도 해당 줄은 안전. 가드에만 의존하는 곳 없음(§5.2) |
| 가드 적용 확인(리뷰 M-1 (a)(b)) | 채택 | probe의 가드 행은 `ProjectConfig`를 직접 생성하지 않고 엔진이 로드한 결과(System property)만 잰다. 각 app `TestInfrastructureSpec`(payment-api·master-api·financial-batch·internal-admin, institution-simulator는 새로 만듦)에 같은 확인 1행 — 모듈마다 실제로 가드가 걸렸음을 그 모듈 시험이 증명한다 |
| 동시성 주의(리뷰 L-2) | 기록 | probe는 JVM 전역 System property를 잠시 바꿨다 되돌린다. 지금은 Kotest 기본 순차 실행이라 안전하며, 같은 JVM 안 spec/test 병렬을 켜려면 probe를 별도 JVM/task로 먼저 분리해야 한다(`ProjectConfig` KDoc) |
| ArchUnit 규칙 | 기각 | 바이트코드에서 `shouldBe` 호출은 보이지만 인자 값의 런타임 타입·중첩 data object 여부는 알 수 없다 — 정확한 검출 불가, 과대 검출은 소음 |
| detekt 규칙 | 기각 | 저장소에 detekt 없음(새 의존성·빌드 변경 필요). 타입 해석 규칙도 sealed 하위형의 런타임 값(변수·fixture)을 판정하지 못한다 |
| 소스 스캔 시험(정규식) | 기각 | §5 B~D처럼 과대 근사이며 변수 기대값을 못 본다. 가드가 더 넓고 정확하다 |

## 8. 실행 기록

환경: WSL Ubuntu, JDK 25(Gradle toolchain), Gradle 9.8.0, Kotest 6.2.5, Docker Desktop 29.8.0(MySQL Testcontainers). 명령은 WSL 안에서 분리 실행(`setsid nohup`).

### 8.1 probe RED → GREEN (libs/test-support)

- 첫 실측(12:07Z, 12:08Z): 예측과 다른 행을 실측대로 고침(`shouldContain`·`shouldContainExactly` on Pair, 깊이 2·3 기대값 쪽, data class 안 List 필드, 다른 필드 같은 2필드 → 공허).
  고정 시험 이름에 실측값을 적었다(정책·기대값이 아니라 Kotest 동작 기록).
- 가드 RED(12:10Z): `:libs:test-support:test --tests …KotestEqualityProbeSpec` → exit 1, 22건 중 실패 2 = 가드 2행(`Expected "false" but actual was null`, 공허 꼴 통과).
- 가드 GREEN(12:11Z): `ProjectConfig` 수정 뒤 `:libs:test-support:test` → exit 0, PayCoreArchitectureRulesSpec 112 + KotestEqualityProbeSpec 22 = 134건, 실패·skip 0.
- 2차(리뷰 M-1 (a)): 가드 행에서 `ProjectConfig()` 직접 생성을 지웠다 — 이제 엔진이 로드한 설정의 결과만 잰다. helper 행 추가 → 23건. `:libs:test-support:test` → exit 0, 112 + 23 = 135건, 실패·skip 0(13:04Z).
  각 app `TestInfrastructureSpec`의 같은 확인 행은 가드를 끈 변이 실행(§6.2 old/new 모든 묶음)에서 실제로 실패했다 — 확인이 자기실현적이지 않다는 증거.

### 8.2 교체·변이

§6 표.

### 8.3 1차 전 모듈 시험(가드 켬, 동적 확인)

모듈마다 따로 `./gradlew --console=plain --no-build-cache <module>:test`(순서대로, 시험 소스 = 교체·가드 적용 후, 변이 없음 — 복원 `cmp` 확인 뒤 시작).

| 모듈 | exit | 실행(UTC) | spec | 시험 | skip | 실패·오류 |
|---|---|---|---|---|---|---|
| libs/test-support | 0 | 12:25:29–12:25:30 (입력 불변으로 12:11 실행 결과 재사용, UP-TO-DATE) | 2 | 134 | 0 | 0 |
| apps/internal-admin | 0 | 12:25:30–12:33:33 | 46 | 385 | 0 | 0 |
| apps/institution-simulator | 0 | 12:33:33–12:35:57 | 19 | 210 | 0 | 0 |
| apps/financial-batch | 0 | 12:35:57–12:36:38 | 4 | 26 | 0 | 0 |
| apps/master-api | 0 | 12:36:38–12:39:10 | 35 | 320 | 0 | 0 |
| apps/payment-api | 0 | 12:39:10–12:49:08 | 158 | 1766 | 0 | 0 |
| 합계 | | | 264 | **2841** | 0 | 0 |

결론: 모든 eq 단언을 `equals`로 판정해도 실패가 없다 → 정적 검색이 못 본 꼴(변수 기대값 등)을 포함해 **공허 통과 뒤에 숨어 있던 실제 불일치는 없다**.
data class 필드별 비교(배열 내용 비교 등)에 기대던 기존 시험도 없다. 이 실행은 구현자 확인이며 게이트 판정(VERIFY-GATE·Vitest·SELFTEST)은 Verifier 몫이다.

### 8.4 2차 전 모듈 시험(가드 켬, 리뷰 반영 후)

같은 방식, 시험 소스 = 2차 교체·helper·`TestInfrastructureSpec` 확인 행 반영 후(변이 복원 `cmp` 확인 뒤 시작).

| 모듈 | exit | 실행(UTC) | spec | 시험 | skip | 실패·오류 |
|---|---|---|---|---|---|---|
| libs/test-support | 0 | 13:14:26–13:14:29 (입력 불변으로 13:04 실행 결과 재사용, UP-TO-DATE) | 2 | 135 | 0 | 0 |
| apps/internal-admin | 0 | 13:14:29–13:24:23 | 46 | 386 | 0 | 0 |
| apps/institution-simulator | 0 | 13:24:23–13:27:55 | 20 | 212 | 0 | 0 |
| apps/financial-batch | 0 | 13:27:55–13:28:52 | 4 | 27 | 0 | 0 |
| apps/master-api | 0 | 13:28:52–13:31:54 | 35 | 321 | 0 | 0 |
| apps/payment-api | 0 | 13:31:54–13:42:51 | 158 | 1767 | 0 | 0 |
| 합계 | | | 265 | **2848** | 0 | 0 |

1차 합계 2841 대비 +7 = probe helper 1행 + 기존 `TestInfrastructureSpec` 가드 확인 4행(payment-api·master-api·financial-batch·internal-admin) +
institution-simulator 새 `TestInfrastructureSpec` 2행. Coordinator가 올린 하한 2841 이상이다. 게이트 판정은 Verifier 몫이다.

## 9. DONE 작업 증거 영향

| 작업 | 영향받은 단언 | 영향 |
|---|---|---|
| CP-DB-001 | CreatePaymentEligibilitySpec:75·98 | NotFoundOrForbidden 거절 분류 단언이 다른 거절 하위형(NotEligible/Unavailable)으로의 오분류를 잡지 못했다. 다른 거절 행(NotEligible·Unavailable)은 엄격했다 |
| MASTER-SNAPSHOT-002 | EntitlementAuthorizationAdmissionAdapterSpec:59 | daily `Unlimited` → `Limited(n)` 오변환을 잡지 못했다(per-transaction `Limited`는 엄격) |
| AUTH-001 | SimulatorVanAuthorizationAdapterSpec:108 | 기관 승인 조건의 일시불 → 할부 오변환을 잡지 못했다 |
| AUTHN-001 | SimulatorCardAuthenticationAdapterSpec:125, AuthenticationResultCheckSpec:181, AuthenticationSessionSpec:291·305 | 인증 결과 binding의 plan(일시불 → 할부) 불일치를 잡지 못했다 |
| AUTH-CONTRACT-001 | AuthorizationResolutionSpec:198 | 승인 거래 조건의 plan 불일치를 잡지 못했다 |
| AUTH-001(2차) | PaymentAuthorizationReconstitutionSpec:37·38·39, AuthorizationResolutionSpec:167, AuthorizationPersistenceSpec:247 | 복원·영속 왕복·충돌 증거 보존 단언이 plan 차이를 잡지 못했다 |
| AUTH-CONTRACT-001(2차) | AuthorizationResolutionSpec:201·288·378·580, AuthenticationSessionSpec:98·106 | "거래가 바뀌지 않음/기록된 거래와 같음"·인증 근거 단언이 plan 차이를 잡지 못했다 |
| AUTHN-001(2차) | CheckoutPersistenceSpec:144 | 인증 근거 영속 왕복이 binding plan 차이를 잡지 못했다 |
| CANCEL-001(2차) | CancellationPersistenceSpec:336·338 | 취소 거래 저장 뒤 승인 거래·승인 view 불변 단언이 plan 차이를 잡지 못했다 |
| CP-QUERY-001(2차) | PaymentStatusViewsSpec:68 | 조회 view의 승인 조건 단언이 plan 차이를 잡지 못했다 |
| ADMIN-BOOT-001 | 없음(S8에서 internal-admin 범위 처리) | internal-admin probe를 가드와 맞게 수정(측정 결과 불변) |

결론: 위 단언들은 해당 하위형 방향의 **변이 민감도가 없었다**(증거 약함). 엄격한 판정으로 다시 실행한 결과 실제값은 모두 기대값과 같아 **실제 결함은 드러나지 않았다**
(§6 GREEN, §8.3). 각 작업의 다른 단언(DB 행·상태·감사·다른 하위형 행)은 영향이 없다. DONE 상태 재판정은 Coordinator/Verifier 몫이다.

## 10. 원칙·기술 확인

| 원칙 | 적용 | 근거 |
|---|---|---|
| Financial Consistency | 간접(증거 강화) | 승인 조건 plan(일시불/할부)·한도(무제한/제한)를 비교하는 단언이 이제 실제로 다름을 잡는다(M3·M5·M9) |
| Transaction Safety | 해당 없음 | 운영 TX 경계 무변경 |
| Idempotency | 해당 없음 | 멱등 경로 무변경(CreatePaymentEligibilitySpec의 rollback·같은 키 재시도 단언은 원래 엄격) |
| Concurrency Control | 해당 없음 | 경쟁 시험(ApprovalRaceSpec:172 `shouldBeIn`)은 원래 엄격(§2 #17) |
| Recoverability | 해당 없음 | 운영 코드 무변경 |
| Reconciliation | 해당 없음 | — |
| Auditability | 간접 | 인증 근거(VerifiedAuthentication) 보존 단언(AuthenticationSessionSpec:291·305)이 binding 차이를 잡는다 |
| Observability | 해당 없음 | — |

기술: 시험 코드와 시험 지원 라이브러리만 바뀌었다. JPA·jOOQ·Batch 코드, Domain/JPA Entity 분리, 빌드 파일·의존성·Boot BOM 조합 변경 없음
(`shouldBeEqual`·`shouldNotBeNull`은 이미 쓰던 `kotest-assertions-core` 6.2.5, `shouldBeEqualNullable`은 test-support의 의존성 없는 Kotlin 함수). Kafka/Redis 무관(ADR-001 — 이 작업의 안전성 근거도 아니다).

## 11. 기타

- ADMIN-BOOT-001 final nit: internal-admin `SecurityConfig` management 체인 `anyRequest().denyAll()` 위에 "프레임워크 기본 거부와 동치(이 줄을 지운 변이 M9-4가
  `~/gate-logs/s9-mutations.json`에서 exit 0으로 살아남은 이유), 방어 심층으로 명시" 주석. 동작 변경 없음.
- `harness/commands.json`: 구현자는 수정하지 않았다. **Coordinator가 Kotlin 하한을 2841로 올렸다**(TEST-SUMMARY argv 포함 — §8.3 1차 합계). 2차 변경 뒤 실제 합계는 §8.4(하한 이상).
