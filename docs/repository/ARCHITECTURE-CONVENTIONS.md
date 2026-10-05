# 아키텍처 구현 관례 (FOUNDATION-001)

작성 2026-10-04 KST. 이 문서는 **가역적인 구현 관례**(패키지·의존 방향·검사 방법)다. 업무 정책·Aggregate 경계는 정하지 않는다. 근거: docs/TECHNOLOGY-AND-PRINCIPLES.md §3–4, docs/baseline/architecture/00·02·10, docs/repository/SPLEARN-REFERENCE-REVIEW.md.

## 1. 실행 단위와 소유 DB

| 실행 단위 | 기본 패키지 | 소유 DB | 비고 |
|---|---|---|---|
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
  - jOOQ DML 금지: `adapter.query`의 jOOQ `insertInto`/`update`/`delete` 사용은 규칙으로 막지 않는다(§4 정책은 리뷰로 확인).
  - jOOQ 생성 코드의 테이블 소유권: 생성 클래스는 패키지 단위로만 `adapter.query`에 제한된다. 어느 모듈의 query adapter가 어느 테이블 클래스를 쓰는지(모듈 간 테이블 소유권)는 검사하지 않는다. 첫 PAYMENT migration 작업에서 테이블 소유 모듈 기준과 함께 정한다(gate).
  - domain의 `java.io`·`java.lang.System`(시계)·`java.lang.Thread` 등 JDK 내부 부작용 API는 허용 목록 안이라 막지 않는다. 시계·ID 생성은 리뷰로 Port/값 주입을 확인한다.
  - domain·application의 `java.lang.management`(JMX/MXBean), `ProcessBuilder`·`Runtime.exec`(둘 다 `java.lang`)는 금지 JDK 목록에 없다. `java.lang` 전체를 막을 수 없어 패키지 규칙으로 표현하지 않으며, 리뷰로 확인한다.
  - base root의 `@SpringBootApplication` 클래스 자체에 붙는 내용(예: `@Bean` 메서드)과 facade 파일의 다른 최상위 함수는 이름 규칙만으로 막지 않는다. 단 그 클래스가 영속 기술 타입에 직접 의존하면 `persistenceTechnologyOnlyInPersistenceAdapter`가 잡는다.
  - 영속 기술의 **전이** 경로: `persistenceTechnologyOnlyInPersistenceAdapter`는 직접 의존만 본다. web/batch/integration/query adapter가 공통 설정 패키지(crossCutting)나 base root(`@SpringBootApplication` 클래스의 `@Bean`)에 둔 wrapper(예: JdbcTemplate을 감싼 구성 클래스)를 거쳐 DB에 접근하는 경로는 막지 않는다. domain·application은 `coreDoesNotDependOnBaseRootOrCrossCutting`과 허용 목록이 이 경로를 막는다. 공통 설정 패키지는 최소로 두고 리뷰로 확인한다.

## 4. jOOQ 생성 코드

- 생성 위치: `build/generated-sources/jooq/**`(커밋하지 않음). Flyway migration을 실제 MySQL 8.4 컨테이너에 적용한 schema에서 생성한다.
- 생성 패키지는 Modulith 업무 모듈이 아니다. Modulith 모듈 목록에서 제외하고, 접근은 `adapter.query`로 제한한다.
- 업무 테이블에 대한 jOOQ DML은 기본 정책 밖이다(TECHNOLOGY §4). 필요하면 별도 adapter와 근거를 검토한다.

## 5. 공통 빌드·시험 기반

- `build-logic`: 실행 단위가 공유하는 Gradle convention plugin(Kotlin/JDK 25/컴파일 옵션/allOpen/BOM/테스트 설정). 버전은 `gradle/libs.versions.toml`에서만 읽는다.
- `libs/test-support`: 공통 시험 지원(MySQL 8.4.11 Testcontainers 설정, Kotest ProjectConfig, ArchUnit 규칙과 meta-test). 운영 배포물이 아니며 SBOM/CVE 검사 대상이 아니다.
- 루트 빌드: 보안 patch override(Boot 4.1.1에 고정)와 CycloneDX SBOM 설정.

## 6. 하지 않은 것

- Port 시그니처·application·adapter(CP-CONTRACT-001, CP-API-001, CP-DB-001). 첫 업무 모듈의 domain은 §7(CP-DOMAIN-001).
- Outbox/Inbox/Dispatcher 계약(ADR-001 제안을 별도 작업에서 확정).
- 소유 DB별 migration/runtime 계정 분리(CP-DB-001).

## 7. payment-api 첫 업무 모듈 `payment` (CP-DOMAIN-001)

가역적인 구현 관례다. 업무 규칙은 확정 문서(PayCore-결제시도-규칙-권장안.md)·D-CPD-001·D-CPD-002를 따른다.

| 관례 | 결정 | 근거 |
|---|---|---|
| 모듈 | `com.paycore.payment.payment` 하나에 `OrderPaymentControl`·`Payment` 두 Aggregate Root | 둘은 PAYMENT DB 같은 로컬 TX로 함께 저장되고(계약 §2·§5), control의 판정이 Payment 상태·근거를 직접 읽는다. 별도 모듈로 나누면 Modulith가 다른 모듈 domain 사용을 막아 provided DTO로 상태를 복사해야 하고 같은 TX 규칙이 모듈 경계를 넘는다. 승인(Authorization)도 "Payment당 논리 Authorization 하나"라 같은 모듈에 들어갈 예정이다. 이름이 `payment.payment`로 겹치지만 app 기본 패키지 + bounded context 관례(§2)를 그대로 따른 결과다 |
| 범위 | `domain` 패키지만. application/adapter 없음 | CP-API/CP-DB 범위 |
| 식별자 | `@JvmInline value class`(MerchantAccountId·MerchantOrderId·PaymentId·OrderIntentFingerprint), 빈 문자열만 거부, 정규화 없음 | 정규화 없음(입력 그대로 비교)은 D-CP-001 사용자 확정(DB 키 컬럼 NO PAD binary collation은 CP-DB-001). 길이·허용 문자·공백만인 값의 허용 여부는 API 계약 CP-CONTRACT-001 범위라 domain은 정하지 않는다. 빈 값 거부는 "ID가 값이 있다"는 기술 불변식 |
| 고정 입력 | 금액·통화·Site를 domain 필드로 두지 않고 불투명 `OrderIntentFingerprint` 값 비교 | 입력 규칙은 확정(D-CP-001 ID 그대로, D-CP-002 ISO 4217 통화·통화별 minor unit, 허용 통화는 기준정보·초기 KRW·USD). fingerprint에 넣을 필드·표현·버전은 CP-CONTRACT-001에서 고정하고 계산은 application 책임. 외화 정산 통화·환율(Q-SETTLE-CURRENCY)은 정산 범위로 별개 |
| Aggregate 스타일 | 정적 factory(`Payment.request`, `OrderPaymentControl.open`) + private set + 의미 있는 전이 메서드, 목록은 읽기 전용 사본 | SPLEARN 참고 관례(setter 금지·상태 전이 메서드), Spring `Assert` 대신 Kotlin `require`/도메인 예외 |
| 예외 | 업무 규칙 위반은 `PaymentDomainException` 하위(sealed): 전이 오류·stale slot·점유 거절·승인 시작 거절·Payment ID 재사용(`PaymentIdReuseException`: 현재 점유·과거 승인·hold 대상 ID로 새 점유 시도). 잘못된 인자(다른 주문의 Payment 등)는 `IllegalArgumentException` | 오류 코드/HTTP 매핑은 CP-CONTRACT-001 |
| 판정 우선순위 | 고정 입력 불일치 → incident hold(활성 시도여도 Blocked(INCIDENT_HOLD)+기존 paymentId, D-CPD-004) → 과거 승인 성공 → 현재 상태·근거(표 밖 교차 근거 불인정, D-CPD-003) | 모든 거절 경로는 새 Payment를 만들지 않으므로 순서는 응답 분류만 바꾼다(금융 효과 동일) |
| 늦은 성공 hold | `reportSuccessAfterTermination`은 (paymentId, reason)당 hold 하나(중복 보고 no-op) | 같은 늦은 성공이 응답·통보·조회로 여러 번 와도 사건은 하나다. 영속 유일성은 CP-DB-001의 UNIQUE(orderKey, paymentId, reason) |
| 슬롯 "해제" | 별도 release 연산 없음. 현재 Payment(currentPaymentId+generation 일치)의 D-CPD-002 충분 근거가 해제다 | 오래된 Payment의 결과는 현재 Payment가 아니므로 해제가 되지 않는다(CP-09). control에 상태를 중복 저장하지 않는다 |
| 복원 factory | 아직 없음 | 시험 편의 API를 production에 두지 않는다(Test Fixture Policy). 영속 복원 factory·Mapper는 CP-DB-001에서 실제 adapter와 함께 추가 |
| 시험 | 새 domain 시험은 Kotest BehaviorSpec + Named Test Fixture(`PaymentFixture`·`OrderPaymentControlFixture`·`OrderFixture`, src/test만), D-CPD-002 기대값은 시험 oracle(`TerminationEvidenceOracle`)에 독립 기술. Fixture Monkey는 이번에 도입하지 않음 | Test Fixture Policy(2026-10-06), Coordinator 결정 |

JPA와의 연결(CP-DB-001에서 할 일, 이번 변경에는 JPA 코드 없음): **역할** — `JpaPaymentEntity`·`JpaOrderPaymentControlEntity`가 adapter.persistence에서 이 domain 상태를 저장한다. **예** — control의 `generation`은 업무 의미(몇 번째 시도가 슬롯을 점유했나)이고, JPA `@Version`은 같은 행을 동시에 고쳤는지 감지하는 기술 값이라 따로 둔다. **이유** — generation 비교는 오래된 결과를 업무 규칙으로 거부하고, `@Version`/행 잠금/UNIQUE(OrderKey)는 두 요청이 같은 판정을 동시에 내리는 경쟁을 DB에서 막는다(domain 단위 시험은 이 경쟁의 증거가 아니다). **주의** — domain 객체는 JPA가 변경 감지하지 않으므로 Mapper가 상태·근거 목록(append-only)·generation·version을 함께 반영해야 하고, stale domain을 최신 Entity에 덮어써 Version 검사를 우회하면 안 된다.
