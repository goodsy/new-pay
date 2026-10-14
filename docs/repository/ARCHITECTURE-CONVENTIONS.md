# 아키텍처 구현 관례 (FOUNDATION-001)

작성 2026-10-04 KST. 이 문서는 **가역적인 구현 관례**(패키지·의존 방향·검사 방법)다. 업무 정책·Aggregate 경계는 정하지 않는다. 근거: docs/TECHNOLOGY-AND-PRINCIPLES.md §3–4, docs/baseline/architecture/00·02·10, docs/repository/SPLEARN-REFERENCE-REVIEW.md.

## 1. 실행 단위와 소유 DB

| 실행 단위 | 기본 패키지 | 소유 DB | 비고 |
|---|---|---|---|
| apps/master-api | `com.paycore.master` | MASTER | Business·Merchant·Site·Contract·MID·개통·정책 정의 원본(MASTER-BOOT-001에서 실행 단위·DB 기초만 생성, 업무 모듈은 MASTER-DOMAIN-001부터) |
| apps/payment-api | `com.paycore.payment` | PAYMENT | 승인·취소·환불 요청, 주문 결제 통제 |
| apps/financial-batch | `com.paycore.financial` | FINANCIAL | 매입·정산·대사·지급 Job |
| apps/internal-admin | `com.paycore.admin` | 없음 | 화면은 소유 플랫폼 API에 위임 |

다른 소유 DB를 직접 SQL/Entity로 읽거나 쓰지 않는다. 같은 프로세스여도 마찬가지다. 플랫폼 간 데이터는 Outbox/Inbox 이벤트·버전 있는 스냅샷으로만 가져온다.

## 2. 모듈 안 패키지

각 실행 단위 안에서 `com.paycore.<app>.<module>`이 Spring Modulith application module(=bounded context 단위)이다. 모듈 안은 다음과 같이 나눈다.

```
com.paycore.<app>.<module>
├── domain                    순수 Kotlin 모델: Aggregate, Value Object, 도메인 규칙·예외
├── application               Use Case 서비스(로컬 TX 경계)
│   ├── provided              driving port: 외부(어댑터·다른 모듈)가 부르는 인터페이스와 입력/출력 DTO
│   └── required              driven port: 저장·조회·외부기관·이벤트 발행 등 서비스가 필요로 하는 인터페이스
└── adapter
    ├── web                   Controller, 요청/응답 DTO(Thymeleaf/JSON)
    ├── persistence           JPA @Entity, Spring Data repository, Domain↔Entity mapper
    ├── query                 jOOQ 조회 adapter(CQRS 조회 DTO)
    ├── integration           외부 기관(VAN TCP, 매입 SFTP, 은행) 연동과 기관 DTO
    └── batch                 Spring Batch Job/Step 배선(업무 규칙은 application에 둔다)
```

- 이름은 splearn 참고 저장소의 `provided`/`required`를 따른다. Kotlin 예약어 `in`을 피한다.
- 업무 모듈(주문·결제 등)은 FOUNDATION-001에서 만들지 않는다. Aggregate 경계는 CP-DOMAIN-001에서 확정된 규칙과 함께 만든다.
- `application.provided`는 모듈의 공개 API다(§3 모듈 공개 범위).

## 3. 의존 규칙

규칙은 `libs/test-support`의 `PayCoreArchitectureRules`에 한 번 정의하고 각 앱의 `ArchitectureSpec`이 적용한다. 각 규칙은 **준수 fixture는 통과, 위반 fixture는 실패, 빈 import는 실패**하는 meta-test로 검증한다.

| 규칙 | 내용 | 강제 |
|---|---|---|
| 계층 소속 | 모듈 패키지(`<base>.<module>..`)의 모든 클래스는 `domain`·`application`(provided/required 포함)·`adapter.{web,persistence,query,integration,batch}` 중 하나에 있어야 한다. 예외: `<base>` 바로 아래(아래 base root 규칙이 따로 제한), 생성 jOOQ 패키지, 앱이 명시한 공통 설정 패키지(현재 internal-admin의 `security`만) | ArchUnit `classesResideInKnownLayers` |
| base root | `<base>` 바로 아래에는 `@SpringBootApplication` 클래스와 그 Kotlin file facade(`<Name>Kt`, `main`)만 둔다. helper·annotation·구성 클래스는 모듈 계층이나 공통 설정 패키지로 옮긴다 | ArchUnit `baseRootHoldsOnlyApplicationEntryPoint` |
| Boot Application 단일 | 실행 단위마다 `@SpringBootApplication`은 정확히 1개이고 `<base>` 바로 아래에 있다. base root의 두 번째 Application, 모듈 계층 안의 Application(다른 component scan·auto-configuration 경계)을 거부한다. 0개도 실패(검사 대상 없음) | ArchUnit `springBootApplicationIsSingleAtBaseRoot` |
| Domain 순수성(허용 목록) | `domain`은 `kotlin..`, `java..`(단 `java.sql`·`java.net`(`java.net.http` 포함)·`java.nio.channels`·`java.nio.file`·`java.rmi`·`java.util.logging`·`java.lang.foreign`(FFM: native 메모리·함수)·`java.util.prefs`(OS 설정 저장소) 제외), `org.jetbrains.annotations..`, domain 패키지(`<base>.*.domain..`, `<base>.domain..`)에만 의존한다. Kotlin 컴파일러 보조 타입(companion, `$WhenMappings`, suspend의 `kotlin.coroutines`, `by lazy`)은 통과한다(준수 fixture로 확인). 그 밖은 모두 금지: Spring, `jakarta..` 전체(`jakarta.validation`·`jakarta.transaction` 포함), `javax..`, Hibernate, jOOQ, Jackson, slf4j, 기관 DTO, `application`·`adapter`, base root, 공통 설정 패키지. 모듈 없이 `<base>.domain`에 둔 경우도 검사 대상이다 | ArchUnit `domainIsPure` |
| Domain 검증 | `jakarta.validation`은 domain에서 쓰지 않는다. 불변식은 순수 Kotlin(`init`/`require`, 값 객체)으로 둔다. domain 밖(요청 DTO 등)의 Bean Validation 사용 위치는 이 규칙이 정하지 않는다 (Coordinator 결정 2026-10-05, 가역적 구현 관례) | ArchUnit `domainIsPure`(fixture `badvalidation`) |
| Core 격리 | `domain`·`application`은 base root 클래스와 공통 설정 패키지에 의존하지 않는다(EntityManager wrapper·stereotype annotation을 거친 우회 차단) | ArchUnit `coreDoesNotDependOnBaseRootOrCrossCutting` |
| Application 격리(허용 목록) | `application`은 다음에만 의존한다: `kotlin..`, `java..`(domain과 같은 금지 JDK 제외: `java.sql`·`java.net`·`java.nio.channels`·`java.nio.file`·`java.rmi`·`java.util.logging`·`java.lang.foreign`·`java.util.prefs`), `org.jetbrains.annotations..`, `<base>..`(단 `adapter`, 생성 jOOQ, base root 직속 클래스, 공통 설정 패키지 제외), `org.springframework.transaction..`(`@Transactional`·TransactionTemplate), `org.springframework.stereotype..`(`@Service`), `org.springframework.context..`(`ApplicationEventPublisher`), `org.springframework.beans.factory.annotation..`(`@Value`), `org.springframework.modulith..`, `org.springframework.data.domain..`(`Pageable`), `org.slf4j..`. 그 밖은 모두 금지: JPA/Hibernate/JDBC, Spring Data repository, jOOQ, Spring web/http/servlet, Spring Batch, `java.net`(HttpClient·Socket), `java.nio.file`, kafka/amqp/jms 등 메시징 client, `<base>` 밖 같은 조직 패키지(예: `com.paycore.shared`)의 wrapper. 저장·조회·외부 호출·메시지 발행은 `application.required` Port 뒤에 둔다 (Coordinator 결정 round3, 가역적 구현 관례. micrometer·`kotlinx` 등은 필요 시 근거와 함께 추가) | ArchUnit `applicationDependsOnlyOnPorts` |
| 영속 기술 위치 | `jakarta.persistence`·`javax.persistence`·`org.hibernate`·`org.springframework.orm`·`org.springframework.jdbc`·`java.sql`·`javax.sql`·`org.springframework.data.repository`·`org.springframework.data.jpa` 의존은 `<module>.adapter.persistence`와 앱이 지정한 공통 설정 패키지(DataSource 구성용)에서만. web(EntityManager·JdbcTemplate 주입), batch(DataSource + JdbcCursorItemReader/RowMapper), integration(Spring Data repository 주입), base root가 Application을 우회해 DB에 접근하는 것을 막는다. `adapter.query`도 예외가 아니다: jOOQ 조회는 Boot가 구성한 `DSLContext`만 주입받으면 되므로 DataSource/`java.sql`이 필요 없다(필요해지면 근거와 함께 재검토, 가역) | ArchUnit `persistenceTechnologyOnlyInPersistenceAdapter` |
| JPA 위치 | `@Entity`·`@Embeddable`·`@MappedSuperclass`는 `adapter.persistence`에만 | ArchUnit |
| Repository 위치 | Spring Data `Repository` 하위 타입과 `@RepositoryDefinition` interface는 `adapter.persistence`에만 | ArchUnit |
| Controller 위치 | `@Controller`·`@RestController`·`@ControllerAdvice`·`@RestControllerAdvice`(meta-annotation 포함)는 `adapter.web`에만 | ArchUnit |
| jOOQ 위치 | `org.jooq`와 생성 jOOQ 클래스는 `adapter.query`(와 생성 코드 자신)에서만 | ArchUnit |
| jOOQ 쓰기 금지 | `adapter.query`는 jOOQ로 쓰지 않는다: `DSLContext`/`DSL`의 insertInto·update·delete(From)·mergeInto·truncate·batch*·loadInto·execute*(raw SQL)·query, `TableRecord`의 store·insert·update·delete·merge 호출 금지. 업무 쓰기는 application Use Case → `application.required` Port → `adapter.persistence`(JPA)만 (CP-DB-001 결정, FOUNDATION-001 L-2 이월 해소) | ArchUnit `queryAdapterDoesNotWriteWithJooq` |
| Batch 위치 | `org.springframework.batch` 의존은 `adapter.batch`에서만 | ArchUnit |
| 기관 DTO 격리 | `adapter.integration` 타입을 다른 계층이 직접 쓰지 않는다. 연동은 `application.required` Port 뒤에 둔다 | ArchUnit(계층 단위) + Modulith(모듈 간) |
| Web adapter | `adapter.web`은 `adapter.persistence`·`query`·`integration`과 `application.required`를 직접 쓰지 않고 `application.provided`를 부른다 | ArchUnit `webAdapterUsesOnlyApplication`(계층 단위) |
| Batch adapter | `adapter.batch`는 `application.provided` use case를 부른다. `adapter.persistence`·`query`·`integration`, `application.required`, Spring Data `org.springframework.data.repository`, JPA/Hibernate를 직접 쓰지 않는다(RepositoryItemReader/JpaPagingItemReader로 Application을 우회하지 않음, INV-09) | ArchUnit `batchAdapterUsesOnlyApplication`(계층 단위) |
| 모듈 공개 범위 | 다른 모듈이 쓸 수 있는 것은 그 모듈의 `application.provided`뿐이다. `application.provided` 패키지마다 `package-info.java`(`src/<sourceSet>/java/...`)에 `@NamedInterface("provided")`를 둔다. 다른 모듈의 `domain`·`application.required`·`adapter.*` 사용은 Modulith verify가 실패시킨다 | Modulith verify(fixture로 증명: annotation이 없으면 provided 사용도 실패) |
| NamedInterface 위치 | `@NamedInterface`는 `<base>.<module>.application.provided` 패키지의 `package-info`에만 붙인다. 개별 타입, 다른 패키지, provided 하위 패키지에 붙이면 실패 | ArchUnit `namedInterfacesOnlyOnProvidedPackages` |
| 모듈 순환 | `<app>.<module>` 사이 순환 의존 금지 | ArchUnit + Modulith |

- 앱에 아직 해당 클래스가 없어 앱 단위 적용은 대상이 비어 있을 수 있다. 앱 spec에서 규칙마다 명시적으로 표시하고, 첫 업무 클래스가 생기는 작업(CP-DOMAIN-001)에서 그 규칙의 빈 대상 허용을 끈다.
- `package-info.java`는 Kotlin 소스 폴더(`src/*/kotlin`)에 두면 javac가 컴파일하지 않아 효과가 없다. 반드시 `src/*/java`에 둔다.
- 다른 모듈 domain 의존: `domainIsPure`는 `<base>.*.domain..`을 모두 허용한다(같은 모듈 판별은 ArchUnit 패키지 패턴으로 표현하지 않음). 모듈 간 domain 사용은 Modulith verify가 거부한다(named interface `provided`만 공개, ModulithProvidedExposureSpec `domain` 케이스).
- 다른 모듈 application 의존: `applicationDependsOnlyOnPorts`는 `<base>..`(adapter 등 제외)를 모두 허용한다. 다른 모듈의 `application.provided`는 정상 사용 경로이므로 ArchUnit에서 막지 않고(과도 차단 방지), `provided` 밖(다른 모듈의 application 서비스·`required`·domain) 사용은 Modulith verify가 거부한다(ModulithProvidedExposureSpec). 준수 fixture `appports.ok`가 다른 모듈 provided Port 사용을 포함한다.
- JPA adapter 분리(영속 기술 위치 규칙의 의미): **역할** — JPA `EntityManager`·Spring Data repository·JdbcTemplate은 `adapter.persistence` 안에서 `application.required` Port를 구현하고 Domain↔Entity 변환과 저장만 한다. **예** — fixture `persistencetech.ok`의 `OrderStoreAdapter(EntityManager, OrderRowRepository, JdbcTemplate)`. **이유** — web/batch/integration이 EntityManager나 DataSource로 직접 쓰면 Application use case의 로컬 TX 경계·멱등 검사·상태 전이 검사를 건너뛰게 되어 Financial Consistency·Transaction Safety·Idempotency 증거가 무의미해진다. **주의** — 규칙은 직접 의존만 보며(전이 경로는 아래 "강제하지 않는 것"), flush 시점·지연 로딩·낙관적 락 같은 JPA 동작 자체는 규칙이 아니라 각 persistence adapter 시험(실제 MySQL)으로 확인한다. 메시징(Kafka 등)·캐시(Redis 등)는 도입되지 않았고, application에서는 허용 목록 밖이라 Port 뒤에만 둘 수 있다. 이들의 도입이 Outbox·로컬 TX 같은 금융 안전 장치를 대체하지 않는다.
- 테스트 소스 제외: 앱 `ArchitectureSpec`은 `ImportOption.DO_NOT_INCLUDE_TESTS`로 main만 검사한다. TECH-001 smoke 테스트 소스(EntityManager/JdbcTemplate/DataSource/테스트 `@Controller`)는 검사 대상이 아니며, 각 앱 spec이 "테스트까지 import하면 규칙이 그 smoke 클래스를 잡고, 실제 import에는 없다"를 함께 확인한다.
- 모듈 root `package-info`: `classesResideInKnownLayers`는 `<base>.<module>` 바로 아래 클래스도 계층 밖으로 보므로, 모듈 root의 `package-info`(예: `@ApplicationModule(type = OPEN)`로 Modulith 공개 범위를 여는 시도)를 거부한다(fixture `knownlayers.badmoduleinfo`). 이는 OPEN 모듈 선언을 통한 공개 범위 우회를 막는 효과가 있다. 모듈 간 허용 의존을 `@ApplicationModule(allowedDependencies = ...)`로 좁혀야 할 때는 이 규칙에 "모듈 root `package-info`만, `type = OPEN` 금지" 예외를 함께 설계해 다시 검토한다.
- 강제하지 않는 것:
  - `application.provided` 패키지에 `@NamedInterface`가 **빠진** 경우 자체를 검사하는 규칙은 없다(빠지면 첫 모듈 간 사용에서 Modulith verify가 실패한다). 잘못된 위치에 붙인 경우는 위 규칙이 잡는다.
  - application의 파일 API: `java.io`(`File`·`FileInputStream` 등)와 `kotlin.io`(`readText`·`writeText` 등)는 `java..`/`kotlin..` 허용 목록 안이라 `applicationDependsOnlyOnPorts`가 막지 않는다(금지 JDK 목록은 `java.nio.file`만 포함). application에서 파일을 읽고 쓰지 않고 `application.required` Port 뒤에 두는지는 리뷰로 확인한다 (FOUNDATION-001 round4 L-1, CP-DOMAIN-001에서 명시).
  - 입력 검증 위치: `application`(그 공개 API인 `application.provided`의 입력/출력 DTO 포함)은 허용 목록 밖인 `jakarta.validation`(Bean Validation annotation)과 Jackson annotation(`tools.jackson..`·`com.fasterxml.jackson..`)에 의존할 수 없다(RUNTIME annotation도 의존으로 잡혀 `applicationDependsOnlyOnPorts`가 실패시킨다). 따라서 형식 검증·JSON 바인딩은 `adapter.web`의 요청 DTO에서, 업무 불변식은 domain 값 객체/Aggregate 생성자(`init`/`require`)에서 한다. 어느 검증을 web DTO에 둘지/domain에 둘지의 구분 자체는 규칙이 강제하지 않으므로 리뷰로 확인한다 (FOUNDATION-001 round4 Info-2, CP-DOMAIN-001에서 명시).
  - jOOQ DML 우회: `queryAdapterDoesNotWriteWithJooq`(CP-DB-001)는 jOOQ 쓰기 API 호출을 막지만, 조회 API에 DML 문자열을 넣는 경우
    (`fetch("DELETE …")`)와 reflection은 정적 검사로 잡지 않는다(리뷰로 확인).
  - jOOQ 생성 코드의 테이블 소유권(CP-DB-001 결정): PAYMENT DB의 모든 테이블(payment, payment_termination_evidence,
    order_payment_control, incident_hold, idempotency_request, outbox_event) 소유 모듈은 `payment`다(payment-api의 유일한 업무 모듈).
    생성 클래스는 패키지 단위로 `adapter.query`에만 제한되고, "테이블 → 소유 모듈" 규칙은 **이월**한다: 지금은 다른 모듈이 없어 규칙이
    자명하게 통과할 뿐이므로, 두 번째 업무 모듈이 PAYMENT DB 테이블을 갖게 되는 작업에서 소유 맵 규칙과 위반 fixture를 함께 도입한다.
  - domain의 `java.io`·`java.lang.System`(시계)·`java.lang.Thread` 등 JDK 내부 부작용 API는 허용 목록 안이라 막지 않는다. 시계·ID 생성은 리뷰로 Port/값 주입을 확인한다.
  - domain·application의 `java.lang.management`(JMX/MXBean), `ProcessBuilder`·`Runtime.exec`(둘 다 `java.lang`)는 금지 JDK 목록에 없다. `java.lang` 전체를 막을 수 없어 패키지 규칙으로 표현하지 않으며, 리뷰로 확인한다.
  - base root의 `@SpringBootApplication` 클래스 자체에 붙는 내용(예: `@Bean` 메서드)과 facade 파일의 다른 최상위 함수는 이름 규칙만으로 막지 않는다. 단 그 클래스가 영속 기술 타입에 직접 의존하면 `persistenceTechnologyOnlyInPersistenceAdapter`가 잡는다.
  - 영속 기술의 **전이** 경로: `persistenceTechnologyOnlyInPersistenceAdapter`는 직접 의존만 본다. web/batch/integration/query adapter가 공통 설정 패키지(crossCutting)나 base root(`@SpringBootApplication` 클래스의 `@Bean`)에 둔 wrapper(예: JdbcTemplate을 감싼 구성 클래스)를 거쳐 DB에 접근하는 경로는 막지 않는다. domain·application은 `coreDoesNotDependOnBaseRootOrCrossCutting`과 허용 목록이 이 경로를 막는다. 공통 설정 패키지는 최소로 두고 리뷰로 확인한다.

## 4. jOOQ 생성 코드

- 생성 위치: `build/generated-sources/jooq/**`(커밋하지 않음). Flyway migration을 실제 MySQL 8.4 컨테이너에 적용한 schema에서 생성한다.
- 생성 패키지는 Modulith 업무 모듈이 아니다. Modulith 모듈 목록에서 제외하고, 접근은 `adapter.query`로 제한한다.
- 업무 테이블에 대한 jOOQ DML은 기본 정책 밖이다(TECHNOLOGY §4). `adapter.query`의 jOOQ 쓰기는 ArchUnit `queryAdapterDoesNotWriteWithJooq`가 막는다(§3, CP-DB-001). 필요하면 별도 adapter와 근거를 검토한다.

## 5. 공통 빌드·시험 기반

- `build-logic`: 실행 단위가 공유하는 Gradle convention plugin(Kotlin/JDK 25/컴파일 옵션/allOpen/BOM/테스트 설정). 버전은 `gradle/libs.versions.toml`에서만 읽는다.
- `libs/test-support`: 공통 시험 지원(MySQL 8.4.11 Testcontainers 설정, Kotest ProjectConfig, ArchUnit 규칙과 meta-test). 운영 배포물이 아니며 SBOM/CVE 검사 대상이 아니다.
- 루트 빌드: 보안 patch override(Boot 4.1.1에 고정)와 CycloneDX SBOM 설정.

## 6. 하지 않은 것

- web adapter·eligibility Port 구현·ID 생성기·요청 fingerprint r1 계산·오류 HTTP 매핑(CP-API-001). 영속 Port·application TX는 §9(CP-DB-001).
- Outbox Relay(lease·재발행·published 표시)·Inbox·Dispatcher(ADR-001 제안을 별도 작업에서 확정). Outbox append는 §9.
- 소유 DB별 migration/runtime 계정 분리: 로컬 compose에는 MASTER/PAYMENT 적용됨(MASTER-BOOT-001). MASTER 업무 표의 runtime 표 단위 권한은 MASTER-DOMAIN-001(§10). 남은 것 — payment-api Flyway 계정 바인딩·표 단위 권한(append-only 표 SELECT/INSERT만)은 PAYMENT-DBACCOUNT-001, FINANCIAL은 Q-BATCH-META 답변 후, 운영 Secret 주입은 운영 작업(권한 경계 결정·시험은 §9, CP-DB-001).

## 7. payment-api 첫 업무 모듈 `payment` (CP-DOMAIN-001)

가역적인 구현 관례다. 업무 규칙은 확정 문서(PayCore-결제시도-규칙-권장안.md)·D-CPD-001·D-CPD-002를 따른다.

| 관례 | 결정 | 근거 |
|---|---|---|
| 모듈 | `com.paycore.payment.payment` 하나에 `OrderPaymentControl`·`Payment` 두 Aggregate Root | 둘은 PAYMENT DB 같은 로컬 TX로 함께 저장되고(계약 §2·§5), control의 판정이 Payment 상태·근거를 직접 읽는다. 별도 모듈로 나누면 Modulith가 다른 모듈 domain 사용을 막아 provided DTO로 상태를 복사해야 하고 같은 TX 규칙이 모듈 경계를 넘는다. 승인(Authorization)도 "Payment당 논리 Authorization 하나"라 같은 모듈에 들어갈 예정이다. 이름이 `payment.payment`로 겹치지만 app 기본 패키지 + bounded context 관례(§2)를 그대로 따른 결과다 |
| 범위 | `domain` 패키지만. application/adapter 없음 | CP-API/CP-DB 범위 |
| 식별자 | `@JvmInline value class`(MerchantAccountId·MerchantOrderId·PaymentId·OrderIntentFingerprint), 정규화 없음 | merchantOrderId의 정규화 없음(입력 그대로 비교)은 D-CP-001 사용자 확정(DB 키 컬럼 NO PAD binary collation은 CP-DB-001), 형식은 D-CPC-002(CP-CONTRACT-001에서 생성자 강제로 변경, §8). MerchantAccountId(MID)의 비정규화·그대로 비교는 D-CP-001이 아니라 **구현 관례**(fail-closed: 정규화로 다른 MID가 같아지는 일을 막는다, MID는 PG가 발급하는 값)이며 MID 형식은 MASTER 발급 규칙(MASTER-DOMAIN-001)이라 domain은 빈 값(과 CP-CONTRACT-001 이후 짝 없는 서로게이트, §8)만 거부한다 |
| 고정 입력 | control·Payment는 불투명 `OrderIntentFingerprint` 값만 비교 | 구성 필드는 D-CPC-001(merchantSiteId + amount + currency)로 확정됐고 계산은 CP-CONTRACT-001에서 domain `OrderIntent.fingerprint()`(v1)로 고정했다(§8). 외화 정산 통화·환율(Q-SETTLE-CURRENCY)은 정산 범위로 별개 |
| Aggregate 스타일 | 정적 factory(`Payment.request`, `OrderPaymentControl.open`) + private set + 의미 있는 전이 메서드, 목록은 읽기 전용 사본 | SPLEARN 참고 관례(setter 금지·상태 전이 메서드), Spring `Assert` 대신 Kotlin `require`/도메인 예외 |
| 예외 | 업무 규칙 위반은 `PaymentDomainException` 하위(sealed): 전이 오류·stale slot·점유 거절·승인 시작 거절·Payment ID 재사용(`PaymentIdReuseException`: 현재 점유·과거 승인·hold 대상 ID로 새 점유 시도). 잘못된 인자(다른 주문의 Payment 등)는 `IllegalArgumentException` | 오류 코드/HTTP 매핑은 CP-CONTRACT-001 |
| 판정 우선순위 | 고정 입력 불일치 → incident hold(활성 시도여도 Blocked(INCIDENT_HOLD)+기존 paymentId, D-CPD-004) → 과거 승인 성공 → 현재 상태·근거(표 밖 교차 근거 불인정, D-CPD-003) | 모든 거절 경로는 새 Payment를 만들지 않으므로 순서는 응답 분류만 바꾼다(금융 효과 동일) |
| 늦은 성공 hold | `reportSuccessAfterTermination`은 (paymentId, reason)당 hold 하나(중복 보고 no-op) | 같은 늦은 성공이 응답·통보·조회로 여러 번 와도 사건은 하나다. 영속 유일성은 CP-DB-001의 UNIQUE(orderKey, paymentId, reason) |
| 슬롯 "해제" | 별도 release 연산 없음. 현재 Payment(currentPaymentId+generation 일치)의 D-CPD-002 충분 근거가 해제다 | 오래된 Payment의 결과는 현재 Payment가 아니므로 해제가 되지 않는다(CP-09). control에 상태를 중복 저장하지 않는다 |
| 복원 factory | `Payment.reconstitute`·`OrderPaymentControl.reconstitute`(CP-DB-001) — persistence Mapper 전용. 저장 상태가 domain에서 존재할 수 없으면(종료 근거가 FAILED/EXPIRED 밖, generation < 1, 중복 hold) 거부 | 시험 편의 API를 production에 두지 않는다(Test Fixture Policy): Named Test Fixture는 계속 `request`·`open`·전이 메서드로 상태를 만든다. 복원 factory는 실제 adapter의 Mapper가 쓰는 공식 경로 |
| 시험 | 새 domain 시험은 Kotest BehaviorSpec + Named Test Fixture(`PaymentFixture`·`OrderPaymentControlFixture`·`OrderFixture`, src/test만), D-CPD-002 기대값은 시험 oracle(`TerminationEvidenceOracle`)에 독립 기술. Fixture Monkey는 이번에 도입하지 않음 | Test Fixture Policy(2026-10-06), Coordinator 결정 |

JPA와의 연결(CP-DB-001에서 할 일, 이번 변경에는 JPA 코드 없음): **역할** — `JpaPaymentEntity`·`JpaOrderPaymentControlEntity`가 adapter.persistence에서 이 domain 상태를 저장한다. **예** — control의 `generation`은 업무 의미(몇 번째 시도가 슬롯을 점유했나)이고, JPA `@Version`은 같은 행을 동시에 고쳤는지 감지하는 기술 값이라 따로 둔다. **이유** — generation 비교는 오래된 결과를 업무 규칙으로 거부하고, `@Version`/행 잠금/UNIQUE(OrderKey)는 두 요청이 같은 판정을 동시에 내리는 경쟁을 DB에서 막는다(domain 단위 시험은 이 경쟁의 증거가 아니다). **주의** — domain 객체는 JPA가 변경 감지하지 않으므로 Mapper가 상태·근거 목록(append-only)·generation·version을 함께 반영해야 하고, stale domain을 최신 Entity에 덮어써 Version 검사를 우회하면 안 된다.

## 8. CreatePayment 계약 관례 (CP-CONTRACT-001)

가역적인 구현 관례다. 업무 규칙은 D-CP-001~003, D-CPC-001~009이며 계약 원문은 `docs/repository/CP-CONTRACT-001-CREATE-PAYMENT.md`.

| 관례 | 결정 | 근거 |
|---|---|---|
| 요청 ID 형식 위치 | merchantOrderId·멱등키 형식(D-CPC-002)을 domain 값 객체 생성자(`MerchantOrderId`, `IdempotencyKey`, 공통 `MerchantRequestIdFormat`)에서 강제. web adapter는 같은 규칙으로 먼저 400 | 영구 DB 키라 web 외 경로(Batch·재처리)로도 형식 밖 키가 생기면 안 된다. application.provided DTO의 jakarta.validation 금지(§ 강제하지 않는 것)와 일치. 형식 축소는 기존 키 복원을 깨므로 사용자 결정+migration 필요 |
| 오류 메시지 | 값 객체 검증 예외 메시지에 입력값을 넣지 않는다(필드명·규칙만) | 로그 주입·입력 노출 방지 |
| MID·Site 형식 | 빈 값과 짝 없는 UTF-16 서로게이트만 거부, 입력 그대로 비교 | PG(MASTER) 발급 값의 fail-closed 비정규화. 형식은 MASTER-DOMAIN-001. 서로게이트 거부는 UTF-8/JDBC `?` 치환으로 서로 다른 값이 같은 fingerprint·DB 키가 되는 것을 막는 기술 불변식(계약 §3.1, Reviewer L-3) |
| UTF-8 인코딩 | fingerprint의 token 길이·hash 입력은 `Utf8Text.encode`(malformed REPORT, 치환 금지). `String.toByteArray(UTF_8)` 직접 사용 금지 | 계약 §6.2. well-formed 입력은 표준 UTF-8과 같아 v1 golden vector 불변 |
| 생성 시 한도 | v1 CreatePayment는 건당·일 한도 사전 검사를 하지 않는다. eligibility NotEligible에 한도 reason 없음 | D-CPC-008상 생성 시 건당 검사는 선택, 권위 판정은 승인 시작(AUTH-CONTRACT-001). Coordinator 결정·가역적(추가 시 계약 개정) |
| Money | `Money(amountMinor: Long, currency: CurrencyCode)`, 1 이상. 허용 통화 목록 없음. minor unit은 기준정보 `CurrencyUnit(code, 0..4)` | D-CP-002, D-CPC-005. 0·음수 금액이 필요한 개념은 별도 타입 |
| 고정 입력 계산 | domain `OrderIntent(merchantSiteId, money).fingerprint()` v1(길이 접두 canonical + SHA-256, `v1:` 접두). JDK `MessageDigest`/`HexFormat`만 사용 | API·DB·domain 동일성 단일화(초안 CP-12). domain 순수성 규칙의 java.. 허용 범위 |
| 멱등 요청 fingerprint | 계약만(`r1:`), 구현은 CP-API-001 application | 요청 전체 비교는 API 입력 모델에 묶여 있다 |
| HTTP 매핑 | 신규 201, 같은 키 재요청·활성 재사용 200, 업무 거절 409, eligibility 404/422/503 (계약 §9) | 금융 의미 없는 응답 표현 선택 |

JPA와의 연결(CP-DB-001에서 할 일, 이번 변경에는 JPA 코드 없음): **역할** — `JpaPaymentEntity`가 `Money`를 `amount_minor BIGINT` + `currency CHAR(3)` 두 컬럼으로, `OrderIntentFingerprint`를 문자열 컬럼으로 저장하고 Mapper가 domain 값 객체로 되돌린다. **예** — DB에서 읽은 `amount_minor=1050, currency='USD'`는 Mapper에서 `Money(1050, CurrencyCode("USD"))`가 되며 생성자 검증을 다시 통과한다. **이유** — domain에 `@Embeddable`·`AttributeConverter`를 붙이지 않아 순수성을 지키고, 컬럼 CHECK·collation(D-CP-001)과 생성자 검증이 같은 규칙을 이중으로 지킨다. **주의** — JPA가 `BigDecimal`·`Double` 매핑을 기본 제안해도 금액은 `Long`만 쓴다. 저장 키를 복원할 때 형식 검증이 실패하면(규칙 축소 등) 조용히 건너뛰지 말고 오류로 드러낸다. dirty checking은 Entity만 추적하므로 Mapper가 값 객체를 Entity 필드로 다시 반영해야 한다. 짝 없는 서로게이트가 든 문자열은 JDBC 드라이버의 UTF-8 변환에서 `?`로 바뀌어 저장될 수 있어 `mid\uD800`과 `mid?`가 같은 키 컬럼 값이 된다. 그래서 Entity에 넣기 전 domain 생성자(`MerchantAccountId`·`MerchantSiteId`)가 거부한다 — UNIQUE 제약은 이미 바뀐 값만 보므로 이 병합을 막지 못한다.

## 9. PAYMENT 영속화·로컬 TX 관례 (CP-DB-001)

가역적인 구현 관례다. 업무 규칙은 D-CP-001·D-CP-002·D-CPC-002~007·D-CPD-002~004와 CP-CONTRACT-001 §8–§12이며 새 금융 정책은 없다.
코드: `apps/payment-api/src/main/resources/db/migration/payment/V1__payment_core.sql`, `payment.application`(provided/required/서비스), `payment.adapter.persistence`.

| 관례 | 결정 | 근거 |
|---|---|---|
| migration | 운영 첫 migration `V1__payment_core.sql`(main). 시험 전용 `db/testmigration/payment/V900__tech_smoke.sql`과 버전이 겹치지 않는다. 모든 문자열 컬럼 `utf8mb4` + `utf8mb4_0900_bin`(NO PAD binary)을 테이블 기본값과 키·fingerprint 컬럼에 명시. 시각은 UTC `DATETIME(3)` | D-CP-001(서버 기본 `utf8mb4_0900_ai_ci` 상속 금지). `PaymentSchemaSpec`이 information_schema와 실제 INSERT(대소문자·후행 공백이 다른 키는 다른 키)로 검사 |
| DB 제약 | `CHECK(amount_minor >= 1)`, `currency CHAR(3)` + `REGEXP_LIKE('^[A-Z]{3}$','c')`, environment·status·method·결과 종류 CHECK, `expires_at = created_at + 30일` CHECK, UNIQUE: 멱등 범위(environment, MID, operation, key)·`payment_key`·(주문, `slot_generation`)·outbox `event_id`/`effect_key`·incident hold(OrderKey, paymentId, reason). payment에 (environment, MID, orderId) UNIQUE 없음 | 계약 §12. (주문, slot_generation) UNIQUE는 새 정책이 아니라 domain `occupy`(generation +1당 Payment 하나)의 DB 안전망이며 주문 조회 인덱스를 겸한다 |
| FK | 자식 행(종료 근거 → payment, hold → control)만 FK. control.current_payment_id·멱등 result_payment_id에는 FK 없음 | 잠금 순서(멱등 → control → Payment)대로 삽입하므로 참조가 먼저 생긴다(MySQL에 지연 FK 없음). 원자성은 같은 로컬 TX, 고아 없음은 rollback 시험 |
| 잠금 순서 | 멱등 기록 → **entitlement_head(S — eligibility 판정 `FOR SHARE`, MASTER-SNAPSHOT-001)** → OrderPaymentControl → Payment. 권리 Inbox는 entitlement_head(X — `FOR UPDATE`)만 잡고 control·payment를 잡지 않는다(순환 없음). 같은 슬롯을 바꾸는 다른 Use Case(늦은 성공 보고 등)도 control → Payment | 계약 §11. 역순 TX와의 교착은 InnoDB가 감지하고 서비스가 재시도한다(`CreatePaymentConcurrencySpec`). head S/X 직렬화로 차단 Snapshot 적용은 진행 중인 생성 TX를 기다리고, ACK 뒤에는 이전 권리로 평가된 생성이 커밋될 수 없다(`EntitlementEligibilitySpec`) |
| 최초 insert 경쟁 | `INSERT … ON DUPLICATE KEY UPDATE <no-op>`(native) 후 `SELECT … FOR UPDATE`. 목록 SELECT 후 INSERT 금지 | 행이 이미 있으면(다른 TX가 미커밋이어도) 그 행 잠금을 기다리고 커밋 뒤 최신 값을 읽는다. JPA persist로 중복을 시도하면 flush 실패가 TX를 rollback 전용으로 만들어 같은 TX에서 수렴할 수 없다. mutation(SELECT 후 INSERT)은 경합 시험에서 1062로 실패했다 |
| 격리 수준 | Use Case TX는 READ COMMITTED | 판정 행은 잠금 읽기로 최신 커밋을 보고, 비잠금 읽기도 TX 시작 스냅샷에 묶이지 않는다. 갭 잠금을 줄인다 |
| TX 경계 | application `LocalTransactions`가 TX를 소유(이미 TX 안이면 거부). `PlatformTransactionManager`를 직접 쓴다(TransactionTemplate 아님) | TransactionTemplate은 rollback 실패 예외로 원래 예외를 덮어써(연결 끊김 시 `JpaSystemException`) application이 분류할 수 없었다. commit 전 실패는 원래 예외 보존(rollback 실패는 suppressed) |
| DB 충돌 예외 처리(FOUNDATION round4 L-2) | persistence adapter가 Port 메서드 안에서 flush하고 `PersistenceFailures`가 원인 사슬의 MySQL 코드로 `PersistenceFailure`(application.required)로 바꾼다: 1062 → `UniqueConflict(StoreConstraint)`(키 값은 버림), JPA version 충돌 → `OptimisticConflict`, 1213 → `Deadlock`, 1205 → `LockTimeout`, SQLState 08xxx/연결 예외 → `Unavailable`. CHECK 위반 등 결함은 분류하지 않는다. application은 `org.springframework.dao`를 모른다 | **충돌만 수렴**: 교착·내부 경합(오래된 판정·version 충돌·같은 generation 선점)만 DB rollback 확인 뒤 새 TX로 같은 의도를 재실행(최대 3회, 기술 설정). 같은 키 대기 timeout → `RequestInProgress`(409 IN_PROGRESS), 그 밖 timeout·연결 오류·재시도 소진 → `TemporarilyUnavailable`(503), 서버 생성 ID 충돌 → `IdentifierCollision`(사용자 payload 충돌 아님), **commit 단계 예외 → `OutcomeUnknown`**(같은 키 재요청으로 확인, 새 키 금지). HTTP 매핑은 CP-API-001 |
| 갱신 Port와 version | Port는 `Versioned(value, version)`를 돌려주고 갱신 Port는 기대 version을 받는다. 다르면 `OptimisticConflict` | 오래된 domain 객체로 최신 행을 덮어쓰지 않는다(mutation으로 확인). 자식 행만 늘면 `PESSIMISTIC_FORCE_INCREMENT`로 Aggregate version을 올린다 |
| 멱등 기록 | 예약은 같은 TX의 `PENDING` 행(커밋된 PENDING은 결함으로 본다). 결과: `SUCCESS`(CREATED/EXISTING_ACTIVE_PAYMENT) 또는 `REJECTED`(domain 판정 이름) + Payment 참조. 재생 응답은 Payment의 생성 시 불변 컬럼(`updatable=false`)에서 다시 읽는다. 만료(요청 시각 ≥ expires_at) 행은 삭제 전이라도 재생하지 않고 같은 잠긴 행을 새 요청으로 갱신(`request_record_id` 교체) | D-CPC-004·007, D-CP-003. 응답 JSON을 저장하지 않아 저장 값과 Payment가 어긋날 여지가 없다. `request_record_id` = 이벤트 causationId |
| eligibility | required Port `PaymentEligibilityPort.evaluate(environment, authenticatedMid, merchantSiteId, currency, paymentMethod, evaluatedAt)`(계약 §13 모양)를 `CreatePaymentService`가 주입받아 **command 값**과 요청 시각(ms)으로 멱등 예약 뒤·주문 판정 전 같은 TX에서 호출(재생이면 호출 안 함, 교착 재시도 TX마다 다시 평가). Eligible이 아니면 TX 전체 rollback 후 `CreatePaymentResult.EligibilityRefused(NotFoundOrForbidden / NotEligible(4종) / Unavailable(3종))` — 저장 없음. Eligible이라도 `validUntil ≤ evaluatedAt`이면 Unavailable(SNAPSHOT_EXPIRED)(fail-closed 방어), `currencyUnit.code ≠ 요청 통화`면 Port 결함으로 실패(저장 없음). Port 예외는 rollback 후 그대로 전달. Payment `eligibility_snapshot_version`은 Eligible 결과에서만 | 계약 §3.2 순서·§9(eligibility 거절은 저장 안 함)·§13. Port 구현은 로컬 Snapshot만 읽고 원격 호출 금지(DB 잠금 보유 중). **운영 구현(MASTER-SNAPSHOT-001)**: `payment.adapter.integration.EntitlementEligibilityAdapter` → `entitlement.application.provided.EntitlementEvaluationQuery`(호출자 TX MANDATORY, PAYMENT 로컬 투영만, 캐시 없음 — ArchUnit). main 구현은 정확히 1개, "모두 Unavailable" 같은 기본 구현은 없다 — Port가 필수 생성자 인자라 bean이 없으면 컨텍스트 기동이 실패한다. 업무 시험은 `EligibilityTestConfig`(`RecordingEligibilityPort`, `@Primary`, Demo Snapshot)를 Import하고, 운영 구현 smoke는 `EntitlementDbTest`(EligibilityTestConfig 없음). 증거 `CreatePaymentEligibilitySpec`·`EligibilityPortWiringSpec`·`EntitlementEligibilitySpec` |
| Outbox | CREATED일 때만 PaymentRequested v1을 같은 TX로 append(`PENDING`). `aggregateVersion` = Payment 최초 JPA version **0**, `effect_key` = `PaymentRequested:{paymentId}`, payload는 계약 필드만(paymentKey·orderName 제외, amount는 JSON 정수, 시각 UTC ms) | D-CPC-003, 계약 §10. Relay·Inbox는 후속. Kafka/Redis는 도입하지 않았다(ADR-001) — 금융 안전성은 DB 제약·로컬 TX·Outbox가 보장하며 브로커/캐시가 이를 대체하지 않는다 |
| incident hold | `incident_hold` append-only 행 + UNIQUE(OrderKey, paymentId, reason). 보고 Use Case는 control 잠금으로 직렬화하고 domain no-op으로 수렴, UNIQUE 충돌은 재판정 재시도 | CP-DOMAIN-001 M-3. 동시 8회 보고 → 1건(`IncidentHoldConvergenceSpec`) |
| 계정 분리·runtime 권한 | 소유 DB마다 migration 계정(해당 DB ALL, GRANT OPTION 없음, Flyway 전용)과 runtime 계정(DB 단위 권한 없음, runtime role의 **표 단위** 권한만)을 분리한다. 표 단위 권한은 권한 migration이 부여한다 — 규칙은 §9.2 | PAYMENT-DBACCOUNT-001(D1·D2). payment-api는 `spring.flyway.user/password`(PAYCORE_PAYMENT_DB_MIGRATION_*)와 `init-sqls: SET time_zone = '+00:00'`을 쓴다(master-api와 같다). `DatabaseAccountSeparationSpec`(payment-api)·`MasterDatabaseAccountSpec`(master-api)이 실제 초기화 스크립트로 SHOW GRANTS 전체와 거부 행위를 고정한다. FINANCIAL은 Q-BATCH-META 답변 전까지 단일 계정. TECH-BASELINE §7.1 참고 |
| 자동 Fixture | Fixture Monkey(테스트 전용)는 프로젝트 Fixture(`RandomCreatePaymentFixture`) 안에서만 쓰고, 공식 생성자로 정상 값을 만든다(seed 고정). Instancio 금지. runtime 유입은 `verifyRuntimeHasNoTestTools`, Instancio는 `verifyNoInstancio`(check 의존) | Test Fixture Policy. 핵심 업무 규칙 시험은 명시 Fixture |
| 시험 기반 | `@PaymentDbTest`(실제 MySQL 8.4.11 Testcontainers, 시험 전용 lock wait 8초·pool 16). PAYMENT-DBACCOUNT-001부터 모든 payment-api DB 컨텍스트는 `PaymentMySqlContainerConfig`(로컬 초기화 스크립트를 넣은 컨테이너, OS 시간대 Asia/Seoul, 앱 = runtime 계정, Flyway = migration 계정)를 쓴다 — 업무 시험 전체가 운영과 같은 표 단위 권한 안에서 돈다. `FaultInjectionConfig`(Port 성공 직후 hook — 장애 주입·경합 붙잡기), 잠금 대기 관찰은 `performance_schema.data_lock_waits` | `information_schema.INNODB_TRX`는 0.1초 유휴 뒤에만 갱신되는 캐시라 짧은 간격 관찰에서 오래된 값을 보여 경합 관찰이 실패했다. Mock 단독은 경합 증거로 쓰지 않는다 |

### 9.1 JPA 선택 설명(이번 코드 기준: 역할 → 예 → 이유 → 주의)

- **Entity와 순수 Domain 분리.** 역할: `JpaPaymentEntity`·`JpaOrderPaymentControlEntity` 등은 행 모양만 갖고 `PaymentPersistenceMapper`가 domain과 변환한다. 예: `amount_minor=1050, currency='USD'` → `Money(1050, CurrencyCode("USD"))`(생성자 재검증), 형식 밖 저장 키(`'bad order'`)는 복원 시 `IllegalArgumentException`. 이유: domain에 JPA annotation이 없어 순수성 규칙과 DB 제약이 같은 규칙을 이중으로 지킨다. 주의: Entity는 data class가 아니고 equals/hashCode는 바뀌지 않는 식별자(업무 ID·자연 키)만, toString은 식별자만. 필드는 `protected set`(Hibernate가 채우는 필드를 final로 두지 않음), 생성 불변 컬럼은 `updatable=false`.
- **변경 감지(dirty checking).** 역할: 잠가 읽은 Entity는 영속성 컨텍스트가 관리하므로 `row.status = …`만으로 flush 때 UPDATE가 나간다(save 불필요). 예: `JpaPaymentStore.update`. 이유: 바뀐 컬럼만 UPDATE하고 `@Version` 조건을 자동으로 붙인다. 주의: domain 객체는 추적되지 않으므로 Store가 Mapper로 값을 옮겨야 하고, 옮기기 전에 기대 version을 비교한다. Spring Data `save`는 version이 있는 Entity에 merge(덮어쓰기)가 될 수 있어 새 행은 `persist`만 쓴다.
- **flush와 commit의 차이.** 역할: 각 쓰기 Port 끝의 `flush()`가 SQL을 이 TX 연결로 보내 제약 위반·잠금 충돌을 Port 안에서 드러낸다. 예: 중복 `payment_key` → Port 안에서 `UniqueConflict(PAYMENT_KEY)`. 이유: commit 시점 예외는 원인 분류가 어렵고 "커밋 불명확"과 섞인다. 주의: flush된 행도 commit 전에는 다른 TX에 보이지 않고 rollback되면 사라진다(`CreatePaymentRollbackSpec`이 각 Port flush 뒤 실패를 주입해 확인). flush 실패 뒤 그 TX는 rollback 전용이라 같은 TX에서 재시도하지 않는다.
- **`@Version`(동시 수정 감지)과 generation(업무).** 역할: `version`은 JPA가 `UPDATE … WHERE version = ?`로 올리는 기술 값, `generation`/`slot_generation`은 몇 번째 시도가 슬롯을 점유했는지의 업무 값. 예: 새 Payment 점유 시 control generation 1 → 2(업무)와 version 0 → 1(기술)이 각각 바뀐다. 이유: generation 비교는 오래된 결과를 업무 규칙으로 거부하고, version·행 잠금·UNIQUE는 같은 판정을 동시에 내리는 경쟁을 DB에서 막는다. 주의: 이벤트 `aggregateVersion`은 JPA version 최초값 0이다(업무 generation이 아님).
- **비관적 잠금 읽기(`@Lock(PESSIMISTIC_WRITE)`).** 역할: 판정에 쓰는 멱등 행·control·현재 Payment를 `SELECT … FOR UPDATE`로 읽는다. 예: 다른 키 8개가 같은 새 주문을 동시에 요청하면 7개가 control 행에서 기다렸다가 먼저 커밋된 Payment를 재사용한다. 이유: 낙관적 version만으로는 "활성 Payment 하나"를 지키지 못한다(서로 다른 Payment 행 사이의 경쟁). 주의: 잠금은 최소 행·고정 순서로만, 외부 호출 동안 쥐지 않는다. timeout(1205)은 문장만 되돌릴 수 있으므로 TX 전체를 rollback한다.
- **연관 매핑·LAZY 미사용.** 역할: 종료 근거·incident hold는 `@OneToMany`가 아니라 별도 Entity로 adapter가 INSERT만 한다. 이유: cascade·orphanRemoval이 append-only 사실을 지우거나 LAZY 로딩이 N+1·직렬화 문제를 만드는 경로가 구조적으로 없다. 주의: 조회 화면용 목록은 jOOQ query adapter(조회 전용 규칙)에서 만든다. OSIV는 꺼져 있다.
- **`@Transactional(MANDATORY)` Port.** 역할: Store는 application이 연 TX 안에서만 동작한다. 예: TX 없이 `lock()` 호출 → `IllegalTransactionStateException`. 이유: TX 경계·재시도·rollback 판단을 application이 소유한다. 주의: Use Case 자체는 이미 열린 TX 안에서 호출되면 거부한다(바깥 TX에 섞이지 않음).
- **시각 매핑(UTC 저장, CP-DB-001 M-2).** 역할: `Instant` ↔ `DATETIME(3)`(시간대 없는 벽시계 값)을 항상 UTC 벽시계로 쓰고 읽는다. 서비스가 ms로 자른다(30일 CHECK와 Java 계산이 같은 값). 예: `2026-10-07T01:02:03.456Z`는 JVM이 Asia/Seoul이어도 `2026-10-07 01:02:03.456`으로 저장되고 멱등 `expires_at`은 `2026-11-06 01:02:03.456`(`UtcStorageSpec`, 원문은 별도 연결의 `CAST(... AS CHAR)`). 이유: 두 겹으로 고정한다 — (1) `hibernate.jdbc.time_zone=UTC`: Hibernate가 JPA·native query 파라미터를 UTC Calendar로 바인딩(이것만으로 JPA 경로는 처음부터 UTC였다), (2) Hikari driver 속성 `connectionTimeZone=UTC` + `forceConnectionTimeZoneToSession=true`: Hibernate를 거치지 않는 plain JDBC(jOOQ 조회·raw SQL)도 UTC로 변환하고 세션 `time_zone`을 `+00:00`으로 맞춘다. (2) 없이는 Asia/Seoul JVM에서 plain JDBC가 `10:02`로 쓰고 `01:02`을 전날 `16:02Z`로 읽었다(RED). URL이 아니라 driver 속성이라 시험(ServiceConnection URL)·운영(환경변수 URL) 모두 같은 값이 적용된다. 주의: 시간대 이름 테이블이 없는 MySQL에서도 동작하도록 driver가 offset(`+00:00`)으로 세션을 설정한다. DB 기본값 `CURRENT_TIMESTAMP`는 쓰지 않는다(시각은 서버 clock 값을 애플리케이션이 넣음). jOOQ 생성 코드의 `DATETIME` 필드(`LocalDateTime`)는 **UTC 벽시계가 아니다**: driver(`connectionTimeZone=UTC`)가 값을 UTC 순간으로 해석한 뒤 jOOQ가 **JVM 기본 시간대**로 `LocalDateTime`을 만든다(MASTER-DOMAIN-001에서 Asia/Seoul JVM이 02:00Z를 11:00으로 읽은 RED — `MerchantStructureQuerySpec`). 그래서 `ZoneOffset.UTC`로 바꾸면 9시간 어긋난다(이전 이 문단의 안내는 실행 검증 없이 쓴 것이라 정정한다). 조회 adapter는 시각 컬럼을 SQL `DATE_FORMAT(col, '%Y-%m-%dT%H:%i:%s.%fZ')` 문자열로 읽어 `Instant.parse`한다(저장 UTC 벽시계 그대로 — driver·JVM·세션 시간대 변환 없음, `java.sql` 타입 불필요). 시스템 시간대 의존 변환은 금지.

### 9.2 runtime 표 단위 권한 관례 (PAYMENT-DBACCOUNT-001 — MASTER-DOMAIN-001 이후 모든 소유 DB가 따른다)

가역적인 기술 관례다(Coordinator D1 방향: runtime DB 단위 DML 제거·표 단위 권한·append-only 표 SELECT/INSERT만). 업무 정책은 바꾸지 않는다.

| 항목 | 관례 | 이유 |
|---|---|---|
| 계정·role | 소유 DB `<db>`마다 runtime 계정 `<db>`(기본 role `<db>_runtime`만), role `<db>_runtime`(표 단위 권한만), migration 계정 `<db>_migration`(`<db>.*` ALL, GRANT OPTION 없음), 잠긴 grantor `<db>_grantor@localhost`(`<db>.*` DML WITH GRANT OPTION). 인프라(로컬: `docker/mysql/init/01-owned-databases.sh`, 운영: DBA가 같은 내용 설치)가 만든다 | role이면 runtime 계정 교체(새 계정에 role만 부여) 때 migration을 다시 돌릴 필요가 없다. MySQL 8.4는 `activate_all_roles_on_login=OFF`가 기본이라 **`SET DEFAULT ROLE`이 없으면 접속 세션에 role이 활성화되지 않는다**(시험: `CURRENT_ROLE()` = `` `<db>_runtime`@`%` ``) |
| DB 단위 권한 제거 | runtime 계정에 `<db>.*` 권한을 주지 않고, 스크립트 실행마다 `REVOKE IF EXISTS ALL PRIVILEGES ON <db>.*`와 `REVOKE IF EXISTS GRANT OPTION ON <db>.*`로 지운다(표 단위 권한은 그대로). migration 계정도 `<db>.*` GRANT OPTION을 REVOKE해 'ALL(GRANT OPTION 없음)'으로 끝낸다. grantor는 매 실행 `ALTER USER … ACCOUNT LOCK` | MySQL 권한은 합산이다. DB 단위 권한이 남으면 표 단위 권한을 아무리 좁혀도 append-only 표 UPDATE/DELETE가 통과한다. MASTER-BOOT-001 이전 volume의 runtime 계정은 `<db>.*` ALL(DDL 포함)이라 DML 4개만 회수하면 DROP·ALTER가 남는다(review MAJOR-1) — 그 상태를 root로 재현한 뒤 스크립트 재실행이 정확한 SHOW GRANTS로 수렴함을 시험한다 |
| 권한 부여 경로 | migration은 `CALL ${flyway:database}_privilege.set_runtime_table_privileges('<table>', '<set>')`만 쓴다. procedure는 `<db>_privilege` schema에 있고 SQL SECURITY DEFINER(DEFINER = 잠긴 grantor)다. 받는 쪽은 `<db>_runtime`로 **고정**(인자 없음), 대상은 `<db>`의 base table만(이름 `^[a-z][a-z0-9_]{0,63}$` + 줄바꿈 문자(LF·CR) 거부, `flyway_schema_history` 제외, view 거부), 집합은 `'SELECT'`·`'SELECT, INSERT'`·`'SELECT, INSERT, UPDATE'`·`'SELECT, INSERT, UPDATE, DELETE'`만(대소문자 구분). 호출은 그 표의 runtime 권한을 **교체**한다: 목표 집합을 먼저 GRANT하고 그다음 여집합 DML만 `REVOKE IF EXISTS`(운영 중 재실행해도 유지되는 권한이 사라지는 틈이 없다 — 좁히기·넓히기 모두 정확한 집합으로 끝남을 시험) | migration 계정에 GRANT OPTION을 주면 MySQL은 받는 계정을 제한할 수 없어 PAYMENT 권한을 MASTER·FINANCIAL 계정 등 아무 계정에나 줄 수 있다. procedure로 받는 쪽을 고정하고 migration 계정에는 그 procedure의 EXECUTE만 준다(직접 GRANT → 1142, role 부여·계정 생성 → 1227, procedure DROP → 1370). procedure를 `<db>` 안에 두지 않는 이유: Flyway는 routine이 있는 schema를 비어 있지 않다고 보고 첫 migrate를 거부한다(실제 확인). `${flyway:database}`는 Flyway 기본 placeholder(접속 DB 이름)라 migration에 DB·계정·role 이름이 없다 |
| 위치·버전 | 권한 migration은 업무 schema와 다른 위치 `db/runtime-privilege/<owner>`에 두고 버전은 같은 순서 공간을 쓴다: 테이블을 만든 migration Vn 다음 버전에 그 표들의 권한(PAYMENT: V1 테이블 → V2 권한). 적용된 migration은 고치지 않는다(권한 변경은 새 버전) | jOOQ 생성기는 업무 schema 위치만 적용하고 생성기 컨테이너(`test` 계정)에는 procedure가 없다. 시험 전용 표의 권한도 별도 위치(`db/testprivilege/payment`)다 |
| 표별 권한 도출 | SELECT는 모든 업무 표. 쓰기는 **현재 main 코드의 실제 쓰기 경로**만(아래 표). DELETE 경로가 없으면 DELETE 없음. 새 쓰기 경로를 만드는 작업이 새 권한 migration을 같이 추가한다 | append-only 확정 사실 표에 UPDATE/DELETE가 필요해 보이면 정책 의미 문제이므로 구현하지 않고 Coordinator 질문으로 돌린다 |
| 누락 감지 | `DatabaseAccountSeparationSpec`이 information_schema의 업무 base table 목록과 role의 `mysql.tables_priv`를 비교한다(권한 migration 없는 새 표는 실패). 업무 시험 전체가 runtime 계정으로 돌기 때문에 권한이 모자라면 1142로 실패한다 | 권한 과다(예: DELETE 추가)는 고정 기대값과 1142 행위 시험이 잡는다 |
| 기존 환경·운영 설치 | 초기화 스크립트는 재실행 안전(IF NOT EXISTS, REVOKE IF EXISTS, grantor 잠금 재적용, procedure 재생성). 기존 compose volume: 재생성 또는 컨테이너에서 스크립트 1회 실행(.env.example). 운영은 DBA가 같은 SQL을 설치한다(REVOKE·ACCOUNT LOCK 포함) | 스크립트는 빈 데이터 디렉터리의 첫 기동에만 자동 실행된다 |
| schema 객체 감시 | 소유 DB(`paycore_payment`, `paycore_master`)에는 trigger·routine·event·view가 없다(root로 information_schema 확인). 쓰려면 시험 기대값을 바꾸는 검토된 변경이 필요하다 | migration 계정은 `<db>.*` ALL이라 DEFINER가 자신인 trigger/view를 만들 수 있다 — runtime 경로에 숨은 권한 우회를 리뷰 없이 넣지 못하게 한다 |

PAYMENT 표별 runtime 권한(V2__payment_runtime_privileges.sql, 2026-10-08 main 코드 기준):

| 표 | 성격 | runtime 권한 | 코드 경로(근거) | 없는 권한 |
|---|---|---|---|---|
| `payment` | Payment Aggregate root | SELECT, INSERT, UPDATE | INSERT `JpaPaymentStore.insertNew`(persist). UPDATE `JpaPaymentStore.update`(status, `@Version`, 근거만 늘 때 `PESSIMISTIC_FORCE_INCREMENT`), `PaymentJpaRepository.lockById`(`SELECT … FOR UPDATE`는 SELECT + UPDATE 권한 필요) | DELETE(경로 없음) |
| `payment_termination_evidence` | **append-only 확정 사실**(D-CPD-002) | SELECT, INSERT | INSERT `JpaPaymentStore.update`의 새 근거 persist. 읽기 `findByPaymentIdOrdered`(잠금 없음) | UPDATE, DELETE, 잠금 읽기(1142) |
| `order_payment_control` | 주문 슬롯(영구 보존) — 명시 검토 대상 | SELECT, INSERT, UPDATE | INSERT+UPDATE `insertIfAbsent`(`INSERT … ON DUPLICATE KEY UPDATE version = version`은 두 권한 필요). UPDATE `lockById`(FOR UPDATE), `JpaOrderPaymentControlStore.update`(current_payment_id·generation·authorized_payment_id·updated_at·version) | DELETE(슬롯 삭제 경로 없음, 영구 보존). 생성 불변 컬럼(fixed_intent_fingerprint, created_at)의 UPDATE는 표 단위라 DB가 막지 않는다 — Entity `updatable=false`·Store `check`가 막는다(열 단위 권한은 후속 후보) |
| `incident_hold` | **append-only** 사건 보류 | SELECT, INSERT | INSERT `JpaOrderPaymentControlStore.update`의 새 hold persist. 읽기 `findByOrder`(잠금 없음) | UPDATE, DELETE(보류 해제 경로 없음 — 생기면 정책 질문) |
| `idempotency_request` | API 멱등 기록 | SELECT, INSERT, UPDATE | INSERT+UPDATE `insertPendingIfAbsent`(IODKU). UPDATE `lockByScope`(FOR UPDATE), `renewExpired`, `complete` | DELETE(30일 경과 삭제 Job은 후속 — 그 작업이 DELETE 권한 migration 추가) |
| `outbox_event` | 통합 이벤트 Outbox | SELECT, INSERT | INSERT `JpaOutboxStore.append`(persist, IDENTITY 키는 generated keys로 받아 SELECT 불필요 — SELECT는 조회·시험용) | UPDATE(Relay published 표시는 Relay 작업이 추가), DELETE |
| `flyway_schema_history` | Flyway 소유 | 없음 | 앱은 읽지 않는다(Flyway는 migration 계정) | 전부(1142) |

JPA 동작과 DB 권한의 연결(이번 변경에서 확인한 것: 역할 → 예 → 이유 → 주의):

- **변경 감지 UPDATE.** 역할: managed Entity 필드 변경이 flush 때 `UPDATE … SET <updatable 컬럼> … WHERE id = ? AND version = ?`가 된다. 예: `JpaPaymentStore.update`가 `row.status`를 바꾸면 `payment`에 UPDATE 권한이 필요하다. 이유: 표 단위 UPDATE가 없으면 1142로 flush가 실패해 Port 안에서 드러난다. 주의: Hibernate는 기본으로 updatable 컬럼 전체를 SET에 넣으므로 열 단위 권한을 도입하려면 `updatable=false`가 아닌 모든 컬럼을 허용해야 한다(현재 미도입).
- **`@Version`·`PESSIMISTIC_FORCE_INCREMENT`.** 역할: 자식 행(근거·hold)만 늘어도 부모의 version을 UPDATE한다. 예: 종료 근거 추가 → `payment.version` UPDATE + `payment_termination_evidence` INSERT. 이유: 동시에 근거를 덧붙이는 다른 TX가 version으로 충돌한다. 주의: 그래서 append-only 자식 표는 INSERT만이어도 부모 표에는 UPDATE가 필요하다.
- **`@Lock(PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`.** 역할: 판정 행 잠금 읽기. 예: `lockById`·`lockByScope`. 이유: MySQL은 잠금 읽기에 SELECT와 UPDATE(또는 DELETE·LOCK TABLES) 권한을 요구한다. 주의: append-only 표는 잠가 읽지 않는다(시험: 종료 근거 FOR UPDATE → 1142) — 직렬화는 부모(control·payment) 잠금이 한다.
- **`persist`(IDENTITY)와 native IODKU.** 역할: 새 행은 persist(INSERT만), 최초 경쟁은 `INSERT … ON DUPLICATE KEY UPDATE`. 예: outbox는 INSERT만으로 충분, control·멱등 기록은 IODKU라 INSERT+UPDATE. 이유: MySQL 문서상 IODKU는 INSERT 권한과 함께 ON DUPLICATE KEY UPDATE가 갱신하는 열의 UPDATE 권한을 요구한다(이번에 권한을 줄여 따로 시험하지는 않았다). 주의: Spring Data `save`는 merge(SELECT 후 UPDATE)가 될 수 있어 append-only 표에 쓰면 1142가 날 수 있다 — 새 행은 `persist`만 쓴다는 기존 관례가 권한과도 맞는다.
- **Kafka/Redis.** 이 권한 구조는 DB 안의 금융 사실 보호다. 브로커·캐시 도입(ADR-001, 미도입)은 이를 대체하지 않는다.

## 10. master-api 첫 업무 모듈 `merchant` (MASTER-DOMAIN-001)

가역적인 구현 관례다. 업무 규칙은 구조 확정본·초기 확정사항·D-MD-001~014이며 명세는 `docs/repository/MASTER-DOMAIN-001-MASTER-DOMAIN.md`.
코드: `apps/master-api/src/main/resources/db/migration/master/V1__master_core.sql`, `db/runtime-privilege/master/V2__master_runtime_privileges.sql`,
`com.paycore.master.merchant.{domain, application(provided/required), adapter.persistence, adapter.query}`.

| 관례 | 결정 | 근거 |
|---|---|---|
| 모듈 | `com.paycore.master.merchant` 하나(Business·Merchant·Site·Contract·MID·연결·개통). 정책 정의는 후속 `policy` 모듈 | 최초 Default 발급이 MID·Default·연결·계약 확인을 한 TX에서 한다(payment 모듈과 같은 이유, §7) |
| 식별자 | `<prefix>_` + ULID 26자(Crockford 대문자): `biz_`·`mer_`·`site_`·`mid_`·`ctr_`·`csa_`·`asg_`·`act_`. domain 생성자와 DB CHECK가 형식 강제, 확장만 허용 | PAYMENT MID·Site 컬럼(VARCHAR(64) bin)과 값 객체에 맞음. 생성기 `RandomMasterIdGenerator`(application, JDK `SecureRandom`만) |
| 관계 이중 방어 | 같은 Merchant(MID·Site·Default·연결)·당사자 Business(Scope·MID)·승인된 Revision만 발급 근거를 **복합 FK**로, 열린 PRIMARY·열린 MID–Site·활성 채널 유일성을 **생성 컬럼 UNIQUE**로 | domain 검사와 같은 규칙을 DB가 다시 지킨다(`MasterSchemaSpec`). 기간 배제 제약이 없어 닫힌 기간 중첩은 merchant 잠금 + domain |
| 잠금 순서 | admin_request → business → merchant → contract → merchant_account → (MASTER-SNAPSHOT-001) payment_method_activation / currency_enablement 대상 행. 소속 등 불변 값은 먼저 비잠금으로 읽어 순서를 지킨다. Relay는 outbox_event만(`FOR UPDATE SKIP LOCKED` claim TX, 결과 TX는 행 `FOR UPDATE`), lease 갱신은 merchant 행만 | 교착 방지. merchant 행이 Merchant 안 관계·권리 입력 변경의 직렬화 지점(다른 키 동시 최초 발급 → 1건, 같은 Merchant 갱신 동시 → 1건) |
| 잠금 읽기 | Store가 `find` 후 `EntityManager.refresh(row, PESSIMISTIC_WRITE)` | 같은 TX의 앞선 비잠금 읽기로 영속성 컨텍스트에 있던 Entity를 최신 커밋 값으로 덮는다(JPQL `@Lock` 조회는 이미 관리되는 Entity의 상태를 다시 읽지 않는다) |
| 관리 명령 멱등 | 범위 UNIQUE(operation, key), 요청 fingerprint `m1:`(길이 접두 canonical + SHA-256), 같은 키·같은 내용 재생·다른 내용 IdempotencyKeyReused, 업무 거절도 저장·재생, **삭제 경로 없음(무기한)** | D-CPC-004(30일)는 CreatePayment 결정이라 자동 적용하지 않음(Coordinator 확인) |
| 판정 후 쓰기 | 본문은 잠금·읽기 → domain 판정 → `beginWrites()` → 저장. 쓰기 시작 뒤 규칙 위반은 결함으로 전체 rollback | 업무 거절과 일부 쓰기가 함께 커밋되지 않게 |
| 재시도 | 교착·낙관적 충돌·**업무 유일키 충돌**(등록번호·채널 등 동시 등록)만 새 TX로 재판정(최대 3회) | 다음 TX의 판정이 커밋된 행을 보고 업무 거절로 수렴(`FirstDefaultIssuanceAtomicitySpec` 등록번호 경합) |
| entitlement_revision | merchant 행의 단조 값. 발급·Revision 승인·개통 등록 TX에서 +1 | MASTER-SNAPSHOT-001의 차단 버전 비교용(JPA version과 별개) |
| Outbox·이력 | 모든 성공 명령이 같은 TX로 `master_change_history`(append-only, 등록번호는 마스킹만)와 `outbox_event`(PENDING, `INTEGRATION_MASTER_REFERENCE`, 등록번호 없음)를 남긴다. Relay는 MASTER-SNAPSHOT-001 | A02 TX 경계 1, D-MD-008 |
| 개통 | (MASTER-SNAPSHOT-001, D-MD-017·D-MS-003) `payment_method_activation.status` UNDER_REVIEW/ACTIVE/SUSPENDED/TERMINATED(UPDATE, `@Version`) + append-only `payment_method_activation_event`. 잠정 UNIQUE(MID, 결제수단)(MASTER-DOMAIN-001 m-5)는 V3에서 **"TERMINATED 아닌 행 하나" 생성 컬럼 UNIQUE**(`uk_payment_method_activation_open_method`)로 해소 — 재개통은 새 행. 결제 허용은 ACTIVE + Site 적용 확인(append-only, 철회도 append-only) | D-MS-003 |
| 서버 시계 | application.required `MasterClock`(운영 `SystemMasterClock`). 명령 시각은 요청 필드가 아니라 Port에서 오며 ms로 자른다. 명령 기간 입력은 `EffectivePeriod.ofMillis` | 호출자가 시각을 넣어 적용 Revision(D-MD-011)·소급 금지(D-MD-005)를 우회하지 못하게. MySQL `DATETIME(3)`은 소수 초를 반올림하므로 ms 미만은 경계에서 버린다 |
| 발급 근거 | 발급 시각에 적용되는 Revision(D-MD-011: 승인·기간 포함 중 번호 최대)만(D-MD-012), 적용 Revision이 없으면(효력 전) 자기 시작 시각에 적용될 승인 Revision만(선발급, D-MD-015), 계약 대상 Merchant = MID Merchant(D-MD-013, `contract.merchant_id` 복합 FK) | 시각 판정은 DB 제약으로 표현하지 않고 contract 행 잠금 아래 domain이 판정 |
| 운영 로그 | `AdminCommandExecutor`가 결과 불명확·재시도 소진·서버 생성 ID 충돌을 WARN 구조화 로그(`operation`·`correlationId`·종류)로 남긴다. Hibernate JDBC 오류 원문 로그(`org.hibernate.orm.jdbc.error`·`SqlExceptionHelper`)는 OFF | MySQL 1062 원문에 키 값(등록번호·채널·ID)이 들어 있다(D-MD-008, review M-2 — 로그 수집 시험) |
| 조회(CQRS) | jOOQ `JooqMerchantStructureReader`(adapter.query, required Port `MerchantStructureReader` 구현) — 업무 schema 위치만으로 codegen(권한 위치 제외). 한 구조 조회의 문장들은 `@Transactional(readOnly, REPEATABLE_READ)` 하나에서 실행(한 InnoDB 스냅샷), 시각 컬럼은 `DATE_FORMAT` 문자열로 읽는다(§9.1) | §4, AC, review m-1·m-2 |
| 시험 | 모든 master DB 시험은 runtime 계정(`MasterDbTest`, 시험 전용 lock wait 8초·pool 16), 장애 주입 `MasterFaultInjectionConfig`, Named Fixture `MasterFixture`·`OnboardingFixture`, Fixture Monkey는 `RandomOnboardingFixture` 안에만 | Test Fixture Policy |
