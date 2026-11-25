# 백엔드 소스 컨벤션 진단 (SOURCE-CONVENTION-REVIEW)

- 작성일 2026-11-25 KST. 기준 커밋 `41139db`(main). 작업 트리의 미커밋 변경(`harness/tasks.json`, `docs/rules/`, `RULE-GOV-001`)은 소스가 아니라서 결과에 영향이 없다.
- 이 문서는 진단 결과다. 소스·DB·git·시험은 바꾸지 않았다. 시험과 게이트도 실행하지 않았다. 아래 수치는 모두 정적 집계(grep, 스크립트)다.
- 대상: `apps/` 아래 실행 단위 5개(master-api, payment-api, financial-batch, internal-admin, institution-simulator), `libs/test-support`, `build-logic`, `gradle/libs.versions.toml`.
  - Kotlin main 1,620파일 / 100,910줄
  - Kotlin test 755파일 / 105,117줄
- 표기
  - **[사실]**: 파일·줄·집계로 확인한 것
  - **[추정]**: 해석이나 영향 판단
- 기존 진단(`docs/assessment/COMPLEXITY-ASSESSMENT.md`, `DOMAIN-ASSESSMENT.md`, `REBUILD-VS-REFACTOR.md`, 기준 `378aff7`)은 다시 쓰지 않았다. 같은 문제가 `41139db`에도 남아 있으면 그 사실을 확인한 뒤 ID를 붙였다(§6 대응표).
- ID `SC-nn`은 이 문서의 Source Convention 번호다. 질문 ID `Q-SC-n`(SITE-CONTRACT-CODE-001)과는 관계가 없다.

## 1. 결론 요약

1. **강제 장치가 있는 규칙은 거의 다 지켜진다.** [사실]
   - ArchUnit·Modulith가 강제하는 의존 방향·계층 소속·영속 기술 위치에서 위반 0건을 확인했다.
   - Domain의 금지 import는 0/313파일이다.
   - application에서 adapter·JPA·jOOQ·web을 import하는 곳은 0건이다.
2. **명명 표준은 강제 장치가 없는데도 핵심 범주는 거의 지켜진다.** [사실]
   - JPA Entity는 119/119가 `Jpa…Entity`다.
   - Spring Data 인터페이스는 75/75가 `…JpaRepository`다.
   - `…Repository` 구현은 25/25, `…Store` 구현은 55/55가 `Jpa…`다.
   - `…Reader` 구현은 35/37이 `Jooq…`다.
   - 다만 required Port 216개 중 101개(47%)는 §14가 이름을 정하지 않은 범주(시계·식별자·설정·지표·외부 호출)다. 이 범주에서는 이름이 앱마다 다르다(`…Port` 13, `…Gateway` 8).
3. **금융·보안 정합성이 걸린 틈은 "강제되지 않는 규칙"과 "같은 규칙의 복제"에 몰려 있다.** [사실 + 추정]
   - 영속 거절 코드(enum 이름)의 변경 금지는 주석으로만 지킨다(SC-01).
   - "외부 호출은 TX 밖" 규칙의 실행 시점 확인이 앱마다 다르다(SC-04).
   - Simulator·admin에는 공통 ArchUnit 규칙이 일부 빠져 있다(SC-02, SC-03).
   - 카드번호 탐지 정규식(SC-05), `LocalTransactions`(SC-09), D-CPC-002 형식(SC-10)이 여러 벌 복제돼 있고, 복제본끼리 일치하는지 확인하는 시험이 없다.
4. **P0(현재 금융 정합성·보안·데이터 손상 결함)은 찾지 못했다.** 정적 분석 범위의 결론이다. 실행 시험은 하지 않았다.

### 1.1 문제 수

| 우선순위 | 건수 | ID |
|---|---|---|
| P0 | 0 | — |
| P1 | 1 | SC-01 |
| P2 | 10 | SC-02 ~ SC-11 |
| P3 | 1 | SC-12 (관리자 화면 — 백엔드 범위 밖 참고) |
| P4 | 10 | SC-13 ~ SC-22 |
| **합계** | **22** | |

### 1.2 상위 10

| 순위 | ID | P | 한 줄 요약 |
|---|---|---|---|
| 1 | SC-01 | P1 | 영속 거절 enum(MasterRejection 82개 등)의 이름 고정을 주석으로만 지킨다. 이름을 바꾸면 같은 키 재요청의 저장 결과 재생(`valueOf`)이 깨진다 |
| 2 | SC-04 | P2 | "외부 호출 TX 밖"의 실행 시점 확인은 원격 adapter 12개 중 admin 4개에만 있다. ArchUnit 규칙은 없다 |
| 3 | SC-02 | P2 | institution-simulator는 공통 ArchUnit 18개 중 10개만 적용한다. Modulith도 없다(모듈 4개, integration 16파일) |
| 4 | SC-03 | P2 | internal-admin에는 jOOQ 쓰기 금지 규칙(`queryAdapterDoesNotWriteWithJooq`)이 빠져 있다(adapter.query 8파일, 감사 조회 포함) |
| 5 | SC-05 | P2 | 카드번호 탐지 정규식이 Kotlin 3곳과 TS 1곳에 복제돼 있다. 복제본을 묶는 공통 시험 벡터가 없다 |
| 6 | SC-06 | P2 | 관리자 감사 action 코드가 자유 문자열이다(형식 정규식만 검사, 닫힌 목록 없음, DB CHECK 없음) |
| 7 | SC-09 | P2 | 로컬 TX 장치 `LocalTransactions`가 11벌이고 admin 안에만 6벌이다. 금융 TX 의미(격리 수준·commit 결과 불명)가 복제본마다 갈라질 수 있다 |
| 8 | SC-08 | P2 | admin 승인 서비스 4종이 보조 함수를 복제한다. 권한 재확인 `holdsNow`도 3벌이다(기존 진단의 문제가 그대로 남음) |
| 9 | SC-10 | P2 | 사용자 확정 형식 D-CPC-002가 payment-api 안에 4벌 있다(domain 1 + web 정규식 3) |
| 10 | SC-07 | P2 | admin application이 시계 Port를 두고도 `Instant.now()`·`System.currentTimeMillis()`를 직접 쓴다(9파일 12곳, 감사 시각 포함) |

## 2. 기존 규칙과 강제 상태

### 2.1 규칙 출처

| 출처 | 내용 |
|---|---|
| CLAUDE.md "필수 기술와 8대 원칙", "핵심 규칙", "Test Fixture Policy" | Domain 순수성, Port 이름(`<Aggregate>Repository`/`Jpa…`, `…Store`, `…Reader`), 외부 호출 TX 밖, Fixture 정책 |
| ARCHITECTURE-CONVENTIONS §2(패키지), §3(의존 규칙과 "강제하지 않는 것"), §9(로컬 TX), §14(명명), §23(acquiring) | 패키지·의존·명명 관례 |
| ARCH-STRUCTURE-001-MAPPING | 개명 29건 매핑, 하위 패키지 보류 |
| TECHNOLOGY-AND-PRINCIPLES §3·§4 | JPA·헥사고날, CQRS, OSIV off, Entity data class 금지 |
| 08-testing-verification-architecture "Test Fixture Policy" | Fixture 우선순위, Fixture Monkey, Instancio 금지, `src/test` 한정 |
| CLAUDE-ARCHITECTURE-REFACTORING-GUIDE §3·§4 | 파일 하나에 주요 타입 하나, 역할별 이름 |

### 2.2 강제 장치별 적용 현황 [사실]

공통 규칙은 `libs/test-support/src/main/kotlin/com/paycore/testsupport/architecture/PayCoreArchitectureRules.kt:331-350`(`all()` 18개)에 있다.

| 규칙 | master | payment | financial | admin | simulator | 위반 |
|---|---|---|---|---|---|---|
| 공통 ArchUnit 18개 | 18(`rules.all()` + ALLOW_EMPTY 일치 검사) | 18 | 18 | **17**(jOOQ 쓰기 금지 없음) | **10** | 0 |
| Modulith `verify()` | ○ (모듈 1개라 실효 없음) | ○ | ○ | ○ | **×**(의존성 없음) | — |
| provided `package-info`(`@NamedInterface`) | 1/1 | 2/2 | **1/2**(acquiring 없음) | 6/6 | **0/4** | 존재 여부는 미강제 |
| Instancio 금지(`verifyNoInstancio`, kotlin-base:81-93) | ○ | ○ | ○ | ○ | ○ | 0 |
| runtime에 시험 도구 없음(`verifyRuntimeHasNoTestTools`, kotlin-app:35-61) | ○ | ○ | ○ | ○ | ○ | 0 |
| production에서 Fixture Monkey 참조 금지(ArchUnit) | × | ○(ArchitectureSpec:534) | × | × | × | 0 (`testImplementation`이라 컴파일 자체가 불가) |
| Reader는 조회 서비스에서만 | × | × | × | ○(ArchitectureSpec:236, 368-375) | × | — |

### 2.3 강제되지 않는 규칙 (리뷰·관례에만 의존) [사실]

| 규칙 | 근거 | 측정 결과 |
|---|---|---|
| §14 명명(Repository/Store/Reader/UseCase/Query/Service) | §14 표. ArchUnit에 이름 규칙 없음(admin Reader 규칙만 이름에 의존) | §3.2 참조. 핵심 범주 준수율 95~100% |
| 파일 하나에 주요 타입 하나, 파일 이름 = 타입 이름 | §14 "파일" 행 | 이탈 13파일(SC-16) |
| 외부 호출 TX 밖 | CLAUDE.md 핵심 규칙, TECH §3 | TX 블록 142개에서 원격 Port 호출 0건(직접 + 1단계 간접, 휴리스틱). 실행 시점 확인은 불균일(SC-04) |
| 시계·ID는 Port로 주입 | §3 "강제하지 않는 것" | domain 0곳, admin application 12곳(SC-07) |
| application 파일 API(`java.io`·`kotlin.io`) 금지 | §3 "강제하지 않는 것" | 0곳 |
| 영속 enum 이름 변경 금지 | 각 enum KDoc(예 MasterRejection.kt:3-6) | 이름 전체 고정 시험 없음(SC-01) |
| 거절·예외 모델링 관례 | 문서에 절 없음(CONVENTIONS의 "Rejection"은 Entity 이름 언급뿐) | 앱마다 방식이 다름(§3.7) |
| Domain/JPA 분리 세부(OSIV off, Entity data class 금지) | TECH §3 | OSIV `false` 5/5, `ddl-auto: validate` 5/5, `data class Jpa…` 0 |

## 3. 평가 항목별 현황

### 3.1 패키지 구조 [사실]

- 모든 실행 단위가 `com.paycore.<app>.<module>.{domain, application.{provided,required}, adapter.*}`를 따른다. `classesResideInKnownLayers`가 5/5 앱에서 강제한다.
- 업무 모듈 수: master 1, payment 2, financial 2, admin 7, simulator 4.
- §2 트리에 없는 하위 패키지:
  - `adapter.web.admin`: master 34, payment 24, financial 16파일
  - `adapter.integration.institution`: payment 15파일
  - admin 공통 구성 `security`(+`session`): 13파일
  - 규칙상 허용되지만 §2 그림에는 없다(SC-21).
- master `merchant.domain`은 88파일이 한 패키지에 있다. 하위 패키지는 매핑 §7에서 보류됐다(기존 진단 그대로).

### 3.2 명명 규칙 [사실]

| 대상 | 표준(§14) | 준수 | 이탈 |
|---|---|---|---|
| JPA Entity | `Jpa<Name>Entity` | 119/119 | 0 |
| Spring Data | `<Entity>JpaRepository` | 75/75 | 0 |
| Aggregate 저장 Port → 구현 | `<A>Repository` → `Jpa<A>Repository` | 25/25 | 0 |
| 기록 저장 Port → 구현 | `…Store` → `Jpa…Store` | 55/55 (Port 53) | 0 |
| jOOQ 조회 Port → 구현 | `…Reader` → `Jooq…Reader` (adapter.query) | 35/37 | 2 (SC-14) |
| required Port 이름 범주 | Repository·Store·Reader | 115/216 (53%) | 나머지 101개는 표준 범주가 없음(SC-13) |
| provided 이름 | `…UseCase`·`…Query`·`<대상>Authenticator` | 106/128 (82.8%) | 22 (SC-13) |
| application 서비스 bean | `<업무><책임>Service` | 120/146 (82.2%) | 26: Port 구현 17(SC-15) + 보조 bean 9 |

- `Versioned`를 쓰는 Port는 `…Repository` 18개, `…Store` 2개(`CheckoutSessionStore`, `CheckoutTokenStore`)다. 두 Store는 §14 ③ "보안 프로토콜 기록"으로 분류돼 이름은 규칙에 맞는다. 다만 상태 전이 위치가 문제다(SC-11).

### 3.3 의존 방향 [사실]

- Domain이 Spring·`jakarta`·Hibernate·jOOQ·Jackson·slf4j를 import하는 곳: **0/313파일**.
- Domain의 `Instant.now`·`UUID.randomUUID`·`Clock.system`·`SecureRandom()` 직접 사용: **0**.
- application이 `adapter` 또는 JPA·jOOQ·web·batch를 import하는 곳: **0**.
- web이 `application.required`를 import하는 곳: **0**.
  - web이 domain 값 객체(ID·`Money`·거절 enum)를 import하는 파일은 30/152다. 규칙이 허용한다(`webAdapterUsesOnlyApplication`은 domain을 막지 않는다).
- provided 파일 200개 중 45개가 domain 타입을 노출한다. 다른 모듈이 그 타입을 직접 쓰면 Modulith verify가 막는다(payment·financial·admin). simulator는 Modulith가 없어 이 보호가 없다(SC-02).

### 3.4 Aggregate 책임 [사실 + 추정]

- Domain에 `private`/`internal constructor` 클래스 39개, `reconstitute` 32개가 있다.
- application·adapter에서 `copy(status=…)`로 상태를 직접 바꾸는 곳은 0이다. 상태 전이는 domain 메서드로 모여 있다.
- 행위 없는 domain 파일 59개는 대부분 enum·sealed 결과다. 빈약한 Aggregate는 찾지 못했다.
- 서비스 비대
  - application 파일 중 400줄 초과 8개, 300줄 초과 15개
  - application 줄 수 / domain 줄 수: admin **2.65**, payment 1.11, financial 0.86, master 0.79, simulator 0.46
  - [추정] admin이 큰 원인은 승인 서비스 4종의 복제(SC-08)다.
- 상태 기계가 domain 밖에 있는 곳: `CheckoutSessionStore.kt`, `CheckoutTokenStore.kt`(application.required)(SC-11).

### 3.5 Domain/JPA Entity 분리 [사실]

- `@Entity`·`@Embeddable`·Spring Data의 위치는 ArchUnit이 5/5 앱에서 강제하고 위반은 0이다.
- Entity 119개, Spring Data 75개(admin은 EntityManager 직접 사용), 전용 Mapper 11개. 기존 진단 수치와 같다. 사용자 지정 원칙이라 축소는 이 문서의 범위가 아니다(REBUILD-VS-REFACTOR R-4).
- `@Enumerated`는 0곳이다. enum은 모두 이름 문자열로 저장·`valueOf`로 복원한다. persistence의 `valueOf` 호출은 121곳, enum 종류는 81개다. SC-01이 이와 관련된다.

### 3.6 DTO 분리 [사실]

- 웹 요청·응답: 대부분 `ResponseEntity<ByteArray>`(115곳)로 직접 직렬화하고, 엄격 파서(`*RequestParser`)를 쓴다.
- 기관 DTO는 `integrationAdapterIsEncapsulated`가 막는다(simulator는 미적용 — SC-02).
- 내부 API(ADMIN ↔ MASTER/PAYMENT/FINANCIAL)
  - 호출 측 admin은 응답을 `*ResponseShape`로 **정확한 키 집합 + 값 형식**까지 검사한 뒤 `Map<String, Any?>`로 넘긴다(`PaymentResponseShape.kt:6-20`).
  - provided 결과 6개가 타입 없는 Map이다(SC-22).
- 서비스 사이 서명·응답 형식 복제(`AdminCallSignature` 3벌, `AdminChannelResponses` 3벌, `*CallSigner` 3벌)는 golden 계약 시험으로 맞춘 **의도된 복제**다(§23, DOMAIN-ASSESSMENT §5).

### 3.7 예외·거절 처리 [사실]

| 앱 | 방식 |
|---|---|
| master | 단일 enum `MasterRejection`(82개, 영속 이름) + 예외 `MasterRuleViolation`(domain 82곳, application 41곳). 필드 오류는 `BusinessProfileRuleViolation`. web은 컨트롤러마다 `catch (RuntimeException)` → 503(11곳) |
| payment | Use Case별 provided enum(`CreatePaymentRejection` 4, `ConfirmRejection` 8, `CancelRejection` 8, `CheckoutTokenRejection` 8) + domain enum(`LimitRejection`, `BindingRejection`, `CancelStartRejection`, `PlanRejection`, `ReturnUrlRejection`). `@ExceptionHandler` 2곳 |
| admin | 상위 서비스 거절 코드는 정규식만 통과시킨다(`^[A-Z][A-Z0-9_]{0,47}$`, MasterResponseShape.kt:184). 감사 action은 문자열(SC-06) |
| simulator | `SimulatorRejection(RejectionReason)` 예외 |

- application의 catch-all(`RuntimeException`/`Exception`/`Throwable`)은 96곳이다. admin 표본 12곳은 모두 지표를 남긴 뒤 다시 던지거나, 의도된 행 건너뛰기(ApprovalService.kt:335-338)였다. 삼키는 사례는 표본에서 발견되지 않았다.
- 거절 모델링 방식 자체를 정한 관례 절은 없다. 앱마다 다른 것은 결함이 아니라 문서 공백이다. SC-01의 영속성 위험만 별도 행으로 다룬다.

### 3.8 트랜잭션 경계 [사실]

- 업무 TX는 `LocalTransactions` 계열(READ COMMITTED, 이미 TX 안이면 거부, commit 중 예외 = `CommitOutcomeUnknownException`)이 소유한다. 사용 위치는 `transactions.execute` 138곳, `tx.execute` 12곳이다.
- persistence Port 구현 79/79가 `@Transactional(MANDATORY)`다(읽기 전용 REQUIRED 예외 2곳: `JpaMerchantWebhookEventStore.kt:37`, `JpaMerchantNotificationFanoutStore.kt:36`).
- TX 블록 안의 원격 Port 호출은 0건이다. 원격 Port 17종(Gateway·Authorization/Cancellation/Inquiry/CardAuthentication Port·WebhookSender·FileChannel·Dispatcher)을 대상으로 TX 블록 142개의 직접 호출과 1단계 간접 호출을 휴리스틱으로 확인했다.
- simulator는 `@Transactional` 기본값을 4곳에서 쓴다. Simulator는 외부 기관 대역이라 관례 차이일 뿐 PayCore 금융 TX가 아니다.

### 3.9 테스트 구성 [사실]

| 앱 | Spec | Named Fixture 파일 | Fixture Monkey 사용 | MockK | 동시성 기본 도구 사용 파일 |
|---|---|---|---|---|---|
| payment | 204 | 45 | 3파일 | 6 | 31 |
| master | 110 | 15 | 1파일 | 0 | 19 |
| admin | 152 | 16 | 0 (의존성 없음) | 0 | 22 |
| financial | 41 | 4 | 0 (의존성 없음) | 0 | 9 |
| simulator | 25 | 4 | 0 (의존성 없음) | 0 | 2 |

- Instancio는 의존성 0, 코드 0이다(빌드 관문으로 강제).
- `src/main`의 Fixture 코드는 0이다. "Fixture" 단어는 주석 5곳뿐이다.
- `libs/test-support/src/main`은 7파일로 시험 기반(컨테이너·ArchUnit·Kotest 설정)만 있고 Fixture는 없다.
- Fixture Monkey 사용 4파일은 모두 프로젝트 Fixture 객체 안에 숨겨져 있다(정책 5·6). 공식 생성 경로로 불변식을 지킨다(`RandomCreatePaymentFixture.kt:12-36`).
- Domain 시험 133파일 중 Spring을 import하는 4파일은 `ClassPathResource`로 golden 파일만 읽는다. Testcontainers·JPA를 import하는 domain 시험은 0이다.
- reflection으로 상태를 주입하는 곳은 0이다.

### 3.10 공통 코드 중복 (실행 단위 간·모듈 간) [사실]

| 대상 | 벌 수 | 시험으로 묶임 | 판정 |
|---|---|---|---|
| 카드번호 정규식 `(?<!\p{Nd})(?:\p{Nd}[ -]?){12,18}\p{Nd}(?!\p{Nd})` | Kotlin 3 + TS 1 | × | SC-05 |
| `LocalTransactions`/`CommitOutcomeUnknownException` | 11 / 8 | × | SC-09 |
| D-CPC-002 요청 ID 형식 | payment 안 4 | × | SC-10 |
| UTF-16 well-formed·제어문자 검사 | 11파일(admin 5, master 3) | 부분 | SC-19 |
| 시계 Port + System 구현 | 11 + 10 | — | SC-15 |
| 승인 서비스 보조 함수 | 3~5 | — | SC-08 |
| jOOQ codegen 생성기 + Gradle 블록 | 4 | — | SC-20 |
| 서명·응답 형식·Snapshot JSON(`CanonicalJson` 2벌) | 2~3 | ○ golden | 의도된 복제 — 유지 |

## 4. 문제 목록

열: ID | P | 기존 규칙(문서§, 강제 여부) | 문제 | 실제 파일 경로(file:line) | 위반 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향

### 4.1 P1

| ID | P | 기존 규칙 | 문제 | 실제 파일 경로 | 위반 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| SC-01 | P1 | MasterRejection KDoc "이름은 영속 값이라 바꾸지 않는다(추가만)", CLAUDE.md 멱등성("같은 요청키는 과거 결과"). **미강제**(주석) | 저장된 거절 결과를 enum 이름으로 복원하는데, 이름 전체를 고정하는 시험이 없다. MASTER `admin_request.result_code`는 DB CHECK도 없다 | `apps/master-api/.../domain/MasterRejection.kt:3-6`, `.../adapter/persistence/JpaAdminRequestStore.kt:44`(`MasterRejection.valueOf`), `apps/master-api/src/main/resources/db/migration/master/V1__master_core.sql:278`(VARCHAR, CHECK 없음), `apps/payment-api/.../adapter/persistence/JpaIdempotencyRecordStore.kt:133,140,146` | 이름 일부만 확인하는 시험 1곳(`MerchantCodeChangeSpec.kt:71-75`, REGISTRANT·MERCHANT_CODE_ 접두사만). payment CreatePayment 결과 코드만 DB CHECK IN으로 고정(`V1__payment_core.sql:122-124`) | 영속 enum 81종, persistence `valueOf` 121곳. 거절 enum: MasterRejection 82 · CancelRejection 8 · ConfirmRejection 8 | 하 | [추정] 이름을 바꾸거나 지우면 같은 키 재요청 때 `IllegalArgumentException`이 난다. 과거 결과 재생 대신 오류를 돌려줘 Idempotency 원칙이 깨진다. 현재 결함은 아님(잠재) | 영속 enum마다 `entries.map { it.name }` 전체 목록을 고정하는 Spec을 **추가**한다(기존 시험 완화 없음). 추가만 허용하는지 검사 | 영속 enum 이름 snapshot Spec 1종(공통 helper). 거절 코드 열에 형식 CHECK 추가 여부는 DB 변경이라 별도 작업·결정 |

### 4.2 P2

| ID | P | 기존 규칙 | 문제 | 실제 파일 경로 | 위반 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| SC-02 | P2 | CONVENTIONS §3 "규칙은 PayCoreArchitectureRules에 한 번 정의하고 각 앱의 ArchitectureSpec이 적용". **부분 강제** | simulator가 공통 18개 중 10개만 적용한다. 빠진 것: `coreDoesNotDependOnBaseRootOrCrossCutting`, `integrationAdapterIsEncapsulated`, `modulesAreFreeOfCycles`, `namedInterfacesOnlyOnProvidedPackages`(대상 없는 4개 — Batch 2, jOOQ 2 — 는 제외). Modulith 의존성이 없다 | `apps/institution-simulator/src/test/kotlin/com/paycore/simulator/architecture/SimulatorArchitectureSpec.kt:18-62`, `apps/institution-simulator/build.gradle.kts:7-20`(modulith 없음) | master는 `rules.all()`과 ALLOW_EMPTY 일치 검사로 누락을 막는다(master ArchitectureSpec.kt:19,30,55). simulator에는 그 장치가 없다 | 모듈 4개, integration 16파일, provided 13파일. 모듈 간 경계는 수동 규칙 6개(Spec:62-155)로만 지킨다 | 하 | [추정] Simulator는 TEST 전용이지만 golden 계약 상대다. 기관 DTO 누출이나 모듈 순환이 생겨도 잡히지 않는다 | master 방식(`rules.all()` + 대상 없는 규칙 명시)으로 Spec 보강 뒤 통과 확인 | Simulator Spec을 `rules.all()` 기반으로 바꾼다. Modulith 도입은 의존성 추가라 별도 결정 |
| SC-03 | P2 | CONVENTIONS §3 "jOOQ 쓰기 금지 — ArchUnit `queryAdapterDoesNotWriteWithJooq`"(CP-DB-001). **admin 미적용** | internal-admin이 jOOQ 쓰기 금지 규칙을 적용하지 않는다. 다른 jOOQ 앱 3곳은 적용한다 | `apps/internal-admin/src/test/kotlin/com/paycore/admin/architecture/ArchitectureSpec.kt:137-220`(18개 중 이 규칙만 없음), `apps/internal-admin/src/main/kotlin/com/paycore/admin/{account,approval,audit}/adapter/query/`(8파일) | 공통 규칙 목록 `PayCoreArchitectureRules.kt:343` | admin adapter.query 8파일. 감사 조회 `JooqAdminAuditReader` 포함. 현재 jOOQ DML 호출 0(grep) | 하 | [추정] 조회 adapter가 감사·승인 표에 쓰는 경로가 생겨도 정적 검사가 없다. DB 권한(append-only)이 2차 방어 | 규칙 시험 1개 추가 후 통과 확인 | admin Spec에 규칙 추가(빈 대상 false) |
| SC-04 | P2 | CLAUDE.md "외부 호출은 DB TX 밖", TECH §3. **ArchUnit 없음, 실행 시점 확인 불균일** | 원격 adapter 진입부의 "TX 활성이면 거부" 확인이 admin Gateway 4개에만 있다. payment 기관·Webhook·결제 사실 전송, master Entitlement 전송, financial SFTP에는 adapter 확인이 없다 | 있음: `admin/paymentops/adapter/integration/HttpPaymentOpsGateway.kt:331-332`, `HttpFinancialOpsGateway.kt`, `HttpMasterOpsGateway.kt`, `HttpMasterBusinessGateway.kt`. 없음: `payment/payment/adapter/integration/institution/SimulatorCardAuthenticationAdapter.kt`, `SimVanTransport.kt`, `VanSocketConnector.kt`, `payment/payment/adapter/integration/HttpMerchantWebhookSender.kt`, `HttpFinancialFactDispatcher.kt`, `master/merchant/adapter/integration/HttpEntitlementDispatcher.kt`, `financial/acquiring/adapter/integration/SftpAcquirerFileChannel.kt` | financial은 application 진입에서 확인한다(`AcquisitionTransmissionService.kt:104` 등 3곳). payment·master는 `LocalTransactions`의 중첩 거부(payment `LocalTransactions.kt:41`)와 정적 설계에 의존한다 | 원격 adapter 12개 중 adapter 확인 4개(admin). 정적 스캔: TX 블록 142개에서 원격 호출 0 | 중 | [추정] 현재 위반은 없다. 새 호출 경로가 TX 안에서 기관을 부르면 잠금 유지·불명확 결과 위험(Transaction Safety)이 정적·실행 어느 쪽에서도 보장되지 않는다 | (a) 원격 Port 구현 진입부에 TX 비활성 확인 추가 후 기존 시험 통과 (b) ArchUnit: 원격 Port 호출 클래스가 `LocalTransactions.execute` 람다 안에서 부르지 않음은 정적 표현이 어려우므로 (a)를 기준으로 | 원격 Port 구현 공통 관례 "진입부 `check(!isActualTransactionActive())`"를 §9 또는 §14에 적고 adapter 8개에 적용 |
| SC-05 | P2 | CLAUDE.md "Secret·카드정보 비노출". 복제는 관례상 golden으로 묶는다(§23, DOMAIN-ASSESSMENT §5 "의도된 복제 — golden·벡터 시험"). **미강제** | 카드번호 탐지 정규식이 4벌이다. 복제본 사이의 공통 시험 벡터·golden이 없다. 위치도 domain/application으로 갈린다 | `apps/financial-batch/.../acquiring/domain/CardNumberPattern.kt:10`, `apps/internal-admin/.../approval/domain/OperatorCancelInputRules.kt:15`, `apps/internal-admin/.../merchantops/application/RegistrationInputRules.kt:63-64`(주석에 "같은 규칙 복제"), `apps/internal-admin/frontend/src/components/ReacquisitionRequest.tsx:43` | 같은 앱 안의 복제(admin 2벌, 모듈 경계 때문)도 묶는 시험이 없다. `RegistrationInputRules` 전용 Spec 없음 | 3앱 계층, 4파일 | 하 | [추정] 한 벌만 고쳐지면 근거·사유 입력으로 카드번호가 저장·전달되는 경로가 생긴다(보안) | 공통 벡터 파일(일치·불일치 사례)을 `contracts/`에 두고 4벌이 같은 판정을 내리는지 확인하는 시험 추가 | 벡터 golden 도입. admin 안 2벌은 한 곳으로 모을지 Modulith 경계를 검토(가역) |
| SC-06 | P2 | 8대 원칙 Auditability("누가 무엇을 왜"). **형식만 강제** | 관리자 감사 action·result 코드가 자유 문자열이다. domain은 대문자 형식만 검사하고 닫힌 목록이 없다. DB도 VARCHAR 형식뿐이다 | `apps/internal-admin/.../audit/domain/AdminAuditEvent.kt:15,17,39,59`, `.../db/migration/admin/V1__admin_core.sql:150`, 리터럴 예 `"APPROVAL_DENIED"` 18회(ApprovalService·MerchantOpsApprovalService·OwnerServiceApprovalService·AcquiringOpsApprovalService) | 감사 action 목록 enum·시험 없음(grep `AUDIT_ACTIONS`·`ActionCatalog` 0) | admin 감사 기록 경로 전체. 승인 계열 action 문자열 12종 이상 | 중 | [추정] 오타·변형이 새 action으로 조용히 저장된다. action 기준 감사 조회·경보가 그 행을 놓친다 | 현재 코드의 action 리터럴 집합을 추출해 닫힌 목록과 비교하는 시험 추가 | action enum(또는 상수 목록) + 시험. 저장 문자열은 그대로(DB 무변경) |
| SC-07 | P2 | CONVENTIONS §3 "시계·ID 생성은 리뷰로 Port/값 주입을 확인". **미강제** | admin application이 시계 Port(`AdminClock`, `AuditClock`)를 두고도 현재 시각을 직접 읽는다. 감사 `occurredAt`과 분당 조회 한도 창이 시험에서 통제되지 않는다 | `admin/approval/application/OperatorCancelJustificationService.kt:82`, `admin/merchantops/application/MerchantRegistrationService.kt:191`, `MerchantLookupService.kt:179,194`, `BusinessAudit.kt:36`, `admin/financialops/application/AcquisitionLookupService.kt:137,150`, `admin/paymentops/application/PaymentTargetLookupService.kt:32`, `HomeKpiService.kt:53`, `TransactionLookupService.kt:147,161`, `PaymentOpsService.kt:116` | 다른 앱 application 0곳(ID 생성기 구현 제외) | admin 9파일 12곳 | 하 | [추정] 감사 시각·한도 경계 시험이 실제 시계에 의존해 불안정 시험의 원인이 될 수 있다(COMPLEXITY §2.2 불안정 시험 약 10건과의 관련은 확인 안 함) | ArchUnit: application이 `Instant.now()`·`System.currentTimeMillis()`를 부르지 않음(규칙 추가) | 기존 시계 Port 주입으로 바꾸고 위 규칙 추가 |
| SC-08 | P2 | REFACTORING-GUIDE §4(책임이 드러나는 서비스), DOMAIN-ASSESSMENT §5(개선 대상). **미강제** | admin 승인 서비스 4종이 같은 보조 함수를 복제한다. 권한 재확인 `holdsNow`, `guardedTx1`, `converge`, `invalidate`가 3벌, `guarded`가 4벌, `audit`가 4+1벌이다 | `admin/approval/application/MerchantOpsApprovalService.kt:674,680,801,810,830,870`, `AcquiringOpsApprovalService.kt:256,262,274,283,300,338`, `OwnerServiceApprovalService.kt:262,277,309,318,337,375`, `ApprovalService.kt:364,407`, `OwnerOutcomeApplier.kt:191` | `holdsNow` 본문이 같다(Acquiring:274 = MerchantOps:801). `guardedTx1`은 이름·줄바꿈만 다르다 | 4파일 2,295줄(937+539+433+386). 기존 추정 300~400줄 | 중 | [추정] 권한 재확인·감사 분기를 한 곳만 고치면 승인 종류별로 보안 판정이 갈라진다 | 추출 전후 기존 승인 Flow·Http·경쟁 Spec 전부 통과(기대값 무변경) | 같은 모듈(approval) 안 공통 helper로 추출. TX 경계·감사 순서는 유지 |
| SC-09 | P2 | §9 "TX 경계: application `LocalTransactions`가 TX 소유", §20 "모듈마다 LocalTransactions". **복제를 관례로 정함, 일치 미강제** | 금융 TX 의미(READ COMMITTED, 중첩 거부, commit 결과 불명 분류)를 담은 클래스가 11벌이다. admin 6벌은 서로 2~6줄만 다르다 | `admin/{paymentops,financialops,audit,account,merchantops,approval}/application/LocalTransactions.kt:14-62`, `master/merchant/application/LocalTransactions.kt:23,74`, `payment/payment/application/LocalTransactions.kt:25,81`, `payment/entitlement/application/EntitlementLocalTransactions.kt`, `financial/acquiring/application/AcquiringLocalTransactions.kt`, `financial/paymentfact/application/PaymentFactLocalTransactions.kt` | 원인: `coreDoesNotDependOnBaseRootOrCrossCutting`(application은 공통 설정 패키지 의존 금지)와 Modulith internal 경계가 공유를 막는다 | 11파일 733줄, `CommitOutcomeUnknownException` 8벌 | 중 | [추정] 한 벌만 고쳐지면(재시도·격리·rollback 실패 보존) 모듈마다 Transaction Safety가 달라진다 | 복제본 동일성 시험(정규화 diff) 또는 공통 계약 Spec을 모든 복제본에 적용 | 앱별 공유 위치 예외(규칙 개정 + 위반 fixture)를 설계하거나, 동일성 시험으로 묶는다. 규칙 개정은 가역 관례라 Coordinator 결정 |
| SC-10 | P2 | D-CPC-002(사용자 확정 형식), CONVENTIONS §3 "형식 검증은 web DTO, 업무 불변식은 domain". **미강제** | 같은 모듈 안에서 D-CPC-002 형식이 domain `MerchantRequestIdFormat` 1벌과 web 정규식 3벌로 4번 구현돼 있다 | `payment/payment/domain/MerchantRequestIdFormat.kt:7-17`, `payment/payment/adapter/web/CancelPaymentRequestParser.kt:27`, `ConfirmPaymentRequestParser.kt:30`, `CreatePaymentRequestParser.kt:207` | web 파서가 domain 규칙을 참조하지 않고 별도 정규식을 둔다(같은 앱·같은 모듈) | payment 4파일. 주문 key·멱등키 형식 | 하 | [추정] 형식이 바뀌면 web 400과 domain `require` 거부가 엇갈린다. 엇갈리면 500이나 키 동일성 판정 차이로 이어진다 | 파서별 형식 시험에 domain `isValid`와 같은 결과인지 확인하는 표 시험 추가 | web 파서가 domain `MerchantRequestIdFormat.isValid`를 쓰게 한다(`internal`이면 같은 앱 안이라 가능 여부 확인) |
| SC-11 | P2 | CONVENTIONS §2 "domain = Aggregate·VO·도메인 규칙", §14 ③(이름은 Store 허용). **미강제** | 결제창 세션·토큰의 상태 enum·불변식·전이(`closed`, `consumed`)가 application.required의 Port 파일 안에 있다 | `payment/payment/application/required/CheckoutSessionStore.kt:12-60`, `CheckoutTokenStore.kt:15,49-62` | application의 `…Status` enum 8개 중 전이 메서드를 가진 것은 이 2개뿐이다. `domainIsPure`·접근 규칙의 보호 범위 밖 | 2파일, 보안 프로토콜 기록(AN-14) | 중 | [추정] 상태 전이 접근 제한(payment ArchitectureSpec의 domain 메서드 호출자 제한 방식)을 적용할 수 없다 | 이동 후 Checkout 시험 전부 통과. 필요하면 전이 호출자 제한 ArchUnit 추가 | 기록 타입을 domain으로 옮기고 Port는 저장 계약만 둔다(파일 이동 — 관례 결정) |

### 4.3 P3

| ID | P | 기존 규칙 | 문제 | 실제 파일 경로 | 위반 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| SC-12 | P3 | 거절 코드 표시 관례 없음. **미강제** (백엔드 범위 밖 참고) | 관리자 화면에 MASTER 거절 코드 메시지 표가 3개로 나뉘어 있다. MasterRejection 82개 중 화면 소스(시험 제외)에 나오는 것은 51개다. 없는 코드는 일반 문구로 대체된다 | `apps/internal-admin/frontend/src/components/RegistrationStep.tsx:17,207`, `MerchantContractApprovalDetail.tsx:39`, `errors/messages.ts`(`ACTIVATION_REJECTION_MESSAGES`) | 대체 문구가 있어 오작동은 없다 | 화면 3곳 | 하 | 운영자가 구체 사유 대신 일반 문구를 본다 | 백엔드 enum 목록과 화면 표의 차집합 시험 | 화면 작업(ADMIN 계열)에서 표 단일화. 이 문서 범위 밖 |

### 4.4 P4

| ID | P | 기존 규칙 | 문제 | 실제 파일 경로 | 위반 근거 | 영향 범위 | 난이도 | 예상 영향 | 검증 방법 | 개선 방향 |
|---|---|---|---|---|---|---|---|---|---|---|
| SC-13 | P4 | §14(저장·조회·제공·인증만 정의), GUIDE §4 "외부 전달 포트 `EntitlementDispatcher`·`EmailSender`, `Port` 접미사에만 의존하지 않음". **미강제** | required Port 101/216개가 표준 범주 밖이다. 같은 역할(다른 서비스·기관·모듈 호출)에 `…Port` 13개(payment 12, master 1)와 `…Gateway` 8개(admin)가 섞여 있다. 설정·지표 Port 30개(Metrics 12, Timings 5, Limits 5, Policy 5, Settings 3)는 이름 규칙이 없다. provided 22/128은 UseCase/Query/Authenticator 밖이고, 그중 `…Client` 4개(admin provided)는 바깥 호출처럼 읽힌다 | 예: `payment/payment/application/required/AuthorizationPort.kt:51`, `PaymentEligibilityPort.kt:25`, `master/merchant/application/required/DeploymentModePort.kt:8`, `admin/merchantops/application/provided/MerchantApprovalClient.kt`, `admin/financialops/application/provided/AcquiringOpsClient.kt` | §14에 해당 행 없음 | Port 101 + provided 22 | 하 | 이름으로 역할을 찾기 어렵다. 동작 영향 없음 | 범주별 이름 규칙 ArchUnit(선택) | §14에 "외부·모듈 간 호출", "설정·지표", "식별자·시계" 행을 추가한다. 기존 이름 일괄 변경은 하지 않는다(GUIDE §4 "일괄 치환 금지") |
| SC-14 | P4 | §14 "`…Reader`(required) → `Jooq…Reader`(adapter.query)". **미강제** | `…Reader` 구현 2개가 JPA다. 하나는 문서화된 예외이고, 하나는 문서에 없는 예외로 Store·Reader를 한 클래스가 구현한다 | `financial/acquiring/adapter/persistence/JpaAcquisitionBacklogReader.kt:18`(§23 C-2 문서화), `payment/payment/adapter/persistence/JpaMerchantWebhookEventStore.kt:22,37`(→ `MerchantWebhookEventReader.kt`) | 37개 중 35개 준수 | 2파일 | 하 | 이름과 구현 기술이 다르다 | — | 두 번째 예외를 §19에 적거나 Port 이름을 Store 쪽으로 정리(가역) |
| SC-15 | P4 | §2 "application = Use Case 서비스", Port 구현은 adapter. DOMAIN-ASSESSMENT §5(Clock 11). **미강제** | required Port 구현 21개 중 17개(`System…Clock` 9, `SecureRandom…/Random…Generator` 8)가 application 패키지에 있다. admin 4개만 adapter.integration에 있다. 시계 Port는 11개, 구현은 10개다 | 예: `payment/payment/application/SystemPaymentClock.kt`, `.../SecureRandomPaymentIdentifierGenerator.kt`, `master/merchant/application/RandomMasterIdGenerator.kt:17,40` | 같은 역할의 배치가 앱마다 다르다 | 21파일 + 시계 Port 11 | 하 | 계층 탐색 혼란. 동작 영향 없음 | — | 배치 기준 하나를 §14에 적는다. `java.time.Clock` 통합은 기존 진단 권고(별도) |
| SC-16 | P4 | §14 "파일: 주요 타입 하나당 파일 하나, 파일 이름 = 타입 이름". **미강제** | (1) `EntitlementSnapshot.kt`는 632줄에 최상위 타입 28개인데 `EntitlementSnapshot` 타입이 없다(`EntitlementContent`:219, `JsonValue`:525, `CanonicalJson`:586 포함). (2) 한 파일에 Port 여러 개: `MerchantApprovalClient.kt`(4), `AcquiringOpsClient.kt`(3), `OperatorCancelContract.kt`(2), `AcquirerFileChannel.kt`(2). (3) Scheduler + JobConfiguration 2 bean 파일 8개 | `apps/payment-api/.../entitlement/domain/EntitlementSnapshot.kt:16-586`, `apps/payment-api/.../payment/adapter/batch/*Scheduler.kt`(7), `master/merchant/adapter/batch/EntitlementSchedules.kt` | ID 묶음 파일(SimulatorIds·MerchantWebhookIds·CheckoutIds)은 private helper를 공유해 §14 예외에 해당(준수) | 13파일 | 하 | 이름으로 파일을 찾을 수 없다 | 파일 이름 ≠ 최상위 공개 타입 검사 스크립트 | `EntitlementSnapshot.kt` 분리는 ARCH-STRUCTURE 후속 작업으로 |
| SC-17 | P4 | §3 "`application.provided` 패키지마다 `package-info.java`에 `@NamedInterface`". **존재는 미강제**(§3 "강제하지 않는 것" 첫 항) | provided 15패키지 중 5개에 `package-info`가 없다: financial `acquiring`, simulator 4개 | `apps/financial-batch/src/main/java/`(paymentfact만), `apps/institution-simulator/src/main/java` 없음 | 기존 진단 §4.3 "틈"이 그대로다 | 5패키지 | 하 | 첫 모듈 간 사용 때 Modulith가 실패한다(financial). simulator는 무효과 | — | acquiring에 추가. simulator는 SC-02와 함께 |
| SC-18 | P4 | admin ArchitectureSpec "Reader는 조회 서비스에서만"(S5), TECH §4 "결정에는 권위 있는 상태". **admin만 강제** | payment·financial command 서비스 11개가 jOOQ View Reader를 주입한다. 표본 확인 결과 잠금 키를 찾는 데만 쓰고, 권위 상태는 잠금 아래에서 다시 읽는다(`AuthorizationResultService.kt:118-123`). 결함은 아니지만 규칙이 앱마다 다르고 문서에 없다 | `payment/payment/application/AuthorizationResultService.kt:45,118`, `CancellationResultService.kt`, `AuthorizationDispatchService.kt`, `CancellationDispatchService.kt`, `OperatorDeadlineConfirmationService.kt:40`, `RequestedPaymentExpiryService.kt:29,48` 외 | admin `ArchitectureSpec.kt:236,368-375` | payment 10 + financial 1 | 하 | 새 서비스가 View를 판정에 쓰는 것을 막지 못한다(payment) | — | §14 또는 §18에 "command 서비스의 Reader는 잠금 전 키 탐색만" 관례를 적는다. 규칙화는 선택 |
| SC-19 | P4 | DOMAIN-ASSESSMENT §5 "DisplayName vs BusinessTextRule 중복". **미강제** | 문자열 well-formed(UTF-16 짝)·제어문자 검사가 11파일에 따로 있다. master는 같은 모듈 안에 3벌이다 | `master/merchant/domain/DisplayName.kt:7-18` ↔ `BusinessTextRule.kt:28-40`, `DecisionReason.kt`. admin: `EnrollmentService.kt`, `OperatorCancelInputRules.kt`, `BusinessProfileInputRules.kt`, `RegistrationInputRules.kt`, `OperatorCancelTargetFingerprint.kt`. payment: `Utf8Text.kt`, `CheckoutTokenRequestParser.kt`. financial: `PaymentFact.kt` | 서비스 사이 복제 일부는 경계 복제(의도). 같은 모듈 안 복제는 아니다 | 11파일(같은 모듈 안 중복: master 2~3, admin 3~4) | 하 | 규칙 차이로 입력 판정이 갈라질 수 있다 | 표 시험으로 각 구현 결과 비교 | 같은 모듈 안에서는 한 함수로 모은다 |
| SC-20 | P4 | CONVENTIONS §5 "build-logic: 공유 Gradle 관례". **미강제** | (1) 의존성 그래프 BFS가 두 convention script에 복제돼 있다. (2) 시험 의존성 7종이 앱 5곳에 반복된다(35줄). (3) jOOQ codegen Gradle 블록과 생성기 `JooqCodegen.kt`가 4벌이다(약 84줄씩, 이름 정규화 뒤 diff 12줄). (4) 형식 검사 도구(ktlint·detekt·editorconfig)가 없다. main에 180자 넘는 줄 3,146개(대부분 KDoc), 200자 넘는 코드 줄 20개 | `build-logic/src/main/kotlin/paycore.kotlin-base.gradle.kts:67-79` ↔ `paycore.kotlin-app.gradle.kts:49-58`, `apps/*/build.gradle.kts`, `apps/{payment-api,master-api,financial-batch,internal-admin}/src/jooqCodegen/kotlin/**/JooqCodegen.kt` | `allWarningsAsErrors = true`(kotlin-base:18)만 강제 | 빌드 파일 6 + 생성기 4 | 하 | 빌드 변경 때 4~5곳을 고쳐야 한다 | `./gradlew check` 결과가 같은지 | BFS를 `com.paycore.buildlogic`으로 옮기고, 시험 의존성·jOOQ codegen을 convention plugin으로. 형식 도구 도입은 의존성 추가라 별도 결정 |
| SC-21 | P4 | 문서 정합성(COMPLEXITY §3.2 낡은 서술). **미강제** | (1) `settings.gradle.kts:36`이 internal-admin을 "DB 없음"이라고 적는데, 실제로는 ADMIN DB를 소유한다(`apps/internal-admin/build.gradle.kts:2`, CONVENTIONS §1). (2) CONVENTIONS §1 표에 institution-simulator가 없다. (3) §2 트리에 `adapter.web.admin`·`adapter.integration.institution`·admin `security`가 없다. (4) §23 C-2 행(`:552` "financial-batch에는 jOOQ codegen을 도입하지 않았다")이 아래 ACQ-VIEW-001 행과 어긋난다. (5) §3 "각 앱 ArchitectureSpec이 적용"과 달리 실제로는 simulator 10/18, admin 17/18이다 | 각 줄 표기 | 기존 진단이 짚은 낡은 서술 중 (1)이 그대로 남아 있다 | 문서 2개, 5곳 | 하 | 새 작업자가 틀린 전제로 시작할 수 있다 | 문서 대조 | 각 서술 정정. (5)는 SC-02·SC-03과 함께 |
| SC-22 | P4 | TECH §3 "화면/API에는 DTO를 전달", CONVENTIONS §2 "provided 입력/출력 DTO". **미강제** | admin provided 결과 6개가 `Map<String, Any?>` 문서를 그대로 넘긴다. 형식은 adapter의 `*ResponseShape`(정확한 키 집합 + 값 형식, fail-closed)가 보장한다 | `admin/paymentops/application/provided/HomeKpiUseCase.kt:14`, `TransactionLookupUseCase.kt:45` 외 4, `admin/paymentops/adapter/integration/PaymentResponseShape.kt:6-20` | 보안상 허용 목록 방어는 있다(누출 위험 낮음) | admin provided 6 + required 5 | 중 | 컴파일러가 필드를 확인하지 못해 화면 계약 변경을 시험으로만 잡는다 | — | 유지해도 된다. 바꾼다면 화면별 typed DTO는 화면 작업과 함께 |

## 5. 규칙 적용률 요약 [사실]

| 영역 | 지표 | 값 |
|---|---|---|
| 의존 방향(ArchUnit) | 공통 규칙 적용 수 / (5앱 × 18) | 81/90. 빠진 9개 중 5개가 실제 틈(SC-02 4, SC-03 1), 4개는 대상 없음 |
| Domain 순수성 | 금지 import 파일 | 0/313 |
| Application → Port | adapter·영속·web import | 0 |
| Modulith | verify 적용 앱 | 4/5 (master는 모듈 1개라 실효 없음) |
| provided 공개 경계 | `package-info` 존재 | 10/15 |
| 명명 — Entity·Spring Data | 준수 | 119/119, 75/75 |
| 명명 — Repository·Store·Reader 구현 | 준수 | 25/25, 55/55, 35/37 |
| 명명 — required Port 표준 범주 | 범주 안 | 115/216 (53%) |
| 명명 — provided | 범주 안 | 106/128 (82.8%) |
| 명명 — 서비스 bean | `…Service` | 120/146 (82.2%) |
| 파일 규칙 | 이탈 파일 | 13 / 1,620 |
| TX 경계 | persistence MANDATORY | 79/79 |
| TX 경계 | TX 블록 안 원격 호출(휴리스틱) | 0/142 |
| TX 경계 | 원격 adapter 실행 시점 확인 | 4/12 |
| 시계 주입 | application 직접 시각 | admin 12곳, 나머지 0 |
| JPA 주의 | OSIV off / `ddl-auto: validate` / data class Entity | 5/5, 5/5, 0 |
| Fixture 정책 | Instancio / production Fixture 코드 / Fixture Monkey 사용 앱 | 0 / 0 / 2/5(4파일) |
| Fixture 정책 | Named Fixture 파일 | 84 (5/5 앱) |
| 영속 enum 이름 고정 | 전체 이름 snapshot 시험 | 영속 enum 81종 중 전체 고정 확인 없음(부분 1, DB CHECK IN 145개는 일부 열만) |

Fixture Monkey를 쓰는 앱이 2/5인 것은 정책 위반이 아니다. 정책은 "자동 생성이 필요할 때 Fixture Monkey를 우선" 쓰라는 것이다. 나머지 앱은 명시 Fixture와 `SecureRandom` 기반 고유 ID만 쓰며, 자동 생성 도구를 이중화한 곳(Kotest `Arb` 등)은 0이다.

## 6. 기존 진단과의 대응 (기준 `378aff7` → `41139db`)

| 기존 진단 항목 | 현재 상태 | 이 문서 |
|---|---|---|
| DOMAIN §5 admin 승인 서비스 4종 helper 복제 | 남음(줄 위치만 바뀜) | SC-08 |
| DOMAIN §5 시계 Port 11 + 구현 11 | 남음(Port 11, System 구현 10) | SC-15 |
| DOMAIN §5 master DisplayName·BusinessTextRule | 남음 | SC-19 |
| DOMAIN §5 admin 내부 UTF-16·카드 검사 5~6곳 | 남음(UTF-16 5파일, 카드 2벌) | SC-05, SC-19 |
| DOMAIN §4.3 simulator Modulith·순환 규칙 없음 | 남음(공통 규칙 10/18로 더 넓게 확인) | SC-02 |
| DOMAIN §4.3 financial acquiring package-info 없음 | 남음 | SC-17 |
| COMPLEXITY §3.2 settings.gradle.kts "ADMIN DB 없음" | 남음 | SC-21 |
| DOMAIN §4.2 Domain↔JPA 이중 모델 | 원칙(R-4) — 다루지 않음 | — |
| DOMAIN §4.1 단일 구현 Port 84% | 원칙과 연결 — 다루지 않음 | — |
| (새로 확인) | 영속 enum 이름 보호, TX 확인 불균일, admin jOOQ 쓰기 규칙, 감사 코드, 직접 시각, LocalTransactions, D-CPC-002 복제, Checkout 상태 위치, 명명 범주 공백, 빌드 중복 | SC-01·03·04·06·07·09·10·11·13·14·16·18·20·22 |

## 7. 측정 방법과 한계

- **정적 집계**: Python 스크립트(저장소 밖 scratchpad, 커밋 안 함)로 `apps/*/src/main/kotlin`의 1,620파일을 읽었다.
  - 최상위 선언은 정규식 `^(modifiers)(class|interface|object|typealias) Name`으로 뽑았다.
  - 패키지 → 계층은 `com.paycore.<app>.<module>.<layer>` 위치로 판정했다.
  - Port 구현 여부는 클래스 머리의 `: Port` 문자열로 판정했다.
  - TX 블록 검사는 `<LocalTransactions 필드>.execute…{…}` 중괄호 범위 안에서 원격 Port 필드 호출과 1단계 private 메서드 호출을 찾았다. 2단계 이상 간접 호출, 람다로 넘긴 호출, 다른 bean을 거친 호출은 놓칠 수 있다.
- **grep 집계**: 금지 import, `Instant.now`, 정규식 복제(고정 문자열 검색), `isActualTransactionActive`, `valueOf`, `@Transactional` 종류, 시험 도구 사용을 셌다.
- **표본 확인**: catch-all 처리(admin 12곳), Reader 사용(payment 3곳), 승인 helper diff(2쌍), `LocalTransactions` diff(admin 6벌)를 직접 읽었다.
- **하지 않은 것**
  - 시험·게이트·빌드 실행: 따라서 "실행 검증 완료"를 주장하지 않는다.
  - 프런트 코드 전수: SC-12만 집계.
  - DB migration 전수: 관련 열만 확인.
  - 기존 진단의 정책 수준 판단(R-1~R-6) 재평가.
- **오차 가능성**
  - 정규식 파서라 중첩 선언·여러 줄 클래스 머리를 놓칠 수 있다. 예: Port 구현 판정은 `:` 이후 1,500자 범위만 본다.
  - 수치는 하한·근사로 읽는다. 위반 0이라고 적은 항목은 ArchUnit 통과(기존 게이트 기록)와 grep 결과가 둘 다 0인 것이다.
