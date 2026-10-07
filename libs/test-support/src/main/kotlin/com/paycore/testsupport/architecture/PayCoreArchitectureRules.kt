package com.paycore.testsupport.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * PayCore 헥사고날 패키지 관례(FOUNDATION-001, Coordinator 결정 — 가역적 구현 관례이며 업무 정책이 아니다)를 검사하는 ArchUnit 규칙.
 *
 * 관례: `<basePackage>.<module>.` 아래
 * - `domain` — 순수 Kotlin 모델
 * - `application` — use case 서비스, `application.provided`(driving port), `application.required`(driven port)
 * - `adapter.web` / `adapter.persistence`(JPA Entity·Spring Data·Mapper) / `adapter.query`(jOOQ 조회)
 *   / `adapter.integration`(VAN TCP·SFTP·은행 등 외부 기관) / `adapter.batch`(Spring Batch Job 구성)
 * 모듈 패키지 안의 모든 클래스는 위 계층 중 하나에 속한다([classesResideInKnownLayers]). `<basePackage>` 바로 아래에는
 * `@SpringBootApplication` 클래스와 그 Kotlin file facade(`<Name>Kt`)만 둔다([baseRootHoldsOnlyApplicationEntryPoint]).
 * domain/application은 `<basePackage>` 바로 아래 클래스와 app 공통 구성 패키지에 의존하지 않는다
 * ([coreDoesNotDependOnBaseRootOrCrossCutting]). 두 규칙이 함께 base root·공통 구성 경유 우회를 막는다.
 * `@SpringBootApplication`은 실행 단위마다 정확히 1개이고 `<base>` 바로 아래에 있다([springBootApplicationIsSingleAtBaseRoot]).
 * application은 허용 목록 안의 타입에만 의존하고([applicationDependsOnlyOnPorts]), 영속 기술은 adapter.persistence와 공통 구성
 * 패키지에서만 쓴다([persistenceTechnologyOnlyInPersistenceAdapter]).
 *
 * 모듈 간 공개 범위(Spring Modulith): 각 모듈의 `<module>.application.provided` 패키지만 named interface `provided`로 공개한다.
 * Kotlin에는 패키지 annotation이 없으므로 그 패키지의 Java `package-info.java`를 Gradle 표준 Java 소스 위치에 둔다
 * (`src/main/java/<...>/<module>/application/provided/package-info.java`). `src/main/kotlin` 아래 Java 파일은 Kotlin 컴파일러가
 * 참조만 하고 javac가 컴파일하지 않아 annotation이 사라진다(payment-api fixture에서 확인한 실패). 타입별 `@NamedInterface` 대신
 * 패키지 단위를 쓰는 이유: provided 패키지 전체가 한 번에 공개되고, domain 등 다른 계층 타입이 실수로 공개 범위에 들어가지 않는다.
 * `@NamedInterface`가 다른 위치(다른 패키지의 package-info, 개별 타입)에 붙지 않았는지는 [namedInterfacesOnlyOnProvidedPackages]가 검사한다.
 * ```
 * @org.springframework.modulith.NamedInterface("provided")
 * package com.paycore.payment.order.application.provided;
 * ```
 * 다른 모듈의 domain/application.required/adapter 사용은 Modulith verify가 거부한다(payment-api ModulithProvidedExposureSpec이 증명).
 * 이 규칙들의 계층 검사(integration/web 캡슐화 등)는 계층 단위이고, 모듈 간 접근은 Modulith가 담당한다.
 *
 * 규칙은 내부에서 allowEmptyShould(true)를 쓰지 않는다. 검사 대상이 비어 있으면 실패한다(ArchUnit 기본값).
 * 빈 대상을 허용할지는 각 app의 ArchitectureSpec이 규칙별로 명시적으로 결정한다.
 * 각 규칙은 libs/test-support의 meta-test(준수 fixture 통과·위반 fixture 실패)로 동작이 증명되어 있다.
 *
 * @param basePackage app 기본 패키지(예: `com.paycore.payment`).
 * @param generatedJooqPackages jOOQ 생성 코드 패키지(하위 포함). adapter.query에서만 접근 가능하고, 생성 코드 자체는 org.jooq에 의존해도 된다.
 *   계층 소속 규칙에서도 제외된다.
 * @param crossCuttingPackages 업무 모듈이 아닌 app 공통 구성 패키지(하위 포함) 허용 목록. 계층 소속 규칙에서 제외되고,
 *   영속 기술 사용 규칙에서는 DataSource 등 구성 용도로 허용된다
 *   (예: internal-admin의 `com.paycore.admin.security` — 보안 FilterChain 구성). 최소로 유지하고 app spec에 이유를 남긴다.
 *   업무 코드를 여기에 두어 계층 규칙을 우회하지 않는다(domain/application은 이 패키지에 의존할 수 없다).
 */
class PayCoreArchitectureRules(
    private val basePackage: String,
    generatedJooqPackages: List<String> = emptyList(),
    crossCuttingPackages: List<String> = emptyList(),
) {
    init {
        require(basePackage.isNotBlank() && !basePackage.endsWith(".")) { "basePackage must be a package name: '$basePackage'" }
    }

    private val generated: Array<String> = generatedJooqPackages.map { "$it.." }.toTypedArray()
    private val crossCutting: Array<String> = crossCuttingPackages.map { "$it.." }.toTypedArray()

    // 검사 대상(제한을 받는 쪽)은 넓게(base 아래 어디든), 허용 위치는 관례대로 좁게(<base>.<module>.adapter.X) 잡는다.
    private val anyDomain = "$basePackage..domain.."
    private val anyApplication = "$basePackage..application.."
    private val anyAdapter = "$basePackage..adapter.."
    private val anyRequiredPort = "$basePackage..application.required.."
    private val webAdapter = "$basePackage.*.adapter.web.."
    private val persistenceAdapter = "$basePackage.*.adapter.persistence.."
    private val queryAdapter = "$basePackage.*.adapter.query.."
    private val integrationAdapter = "$basePackage.*.adapter.integration.."
    private val batchAdapter = "$basePackage.*.adapter.batch.."

    /** Modulith `ApplicationModules.of(app, ignored)` 등에 넘길 생성 코드 판별식. 생성 패키지가 없으면 항상 false. */
    fun generatedCode(): DescribedPredicate<JavaClass> =
        if (generated.isEmpty()) {
            DescribedPredicate.alwaysFalse()
        } else {
            resideInAnyPackage(*generated)
        }

    /**
     * 모듈 패키지(`<base>.<module>..`) 안의 모든 클래스는 domain / application(provided·required 포함) /
     * adapter.{web,persistence,query,integration,batch} 중 하나에 있다. 계층 밖 패키지(`model`, `service`, `adapter.misc` 등)에
     * 두어 다른 규칙을 우회하는 것을 막는다. 예외: `<base>` 바로 아래 클래스([baseRootHoldsOnlyApplicationEntryPoint]가 따로 제한),
     * 생성 jOOQ 코드, [crossCuttingPackages].
     */
    fun classesResideInKnownLayers(): ArchRule =
        classes().that().resideInAPackage("$basePackage.*..")
            .and().resideOutsideOfPackages(*generated, *crossCutting)
            .should().resideInAnyPackage(
                "$basePackage.*.domain..",
                "$basePackage.*.application..",
                webAdapter,
                persistenceAdapter,
                queryAdapter,
                integrationAdapter,
                batchAdapter,
            )
            .`as`("classes in $basePackage.<module> reside in domain, application or adapter.{web,persistence,query,integration,batch}")

    /**
     * `<base>` 바로 아래에는 `@SpringBootApplication` 클래스와 그 Kotlin file facade(`<Name>Kt`, `main` 함수 파일)만 둔다.
     * helper·annotation·구성 클래스를 두면 계층 규칙 밖에 놓이므로 모듈 계층이나 app 공통 구성 패키지로 옮긴다.
     */
    fun baseRootHoldsOnlyApplicationEntryPoint(): ArchRule =
        classes().that().resideInAPackage(basePackage)
            .should(beSpringBootApplicationOrItsFacade())
            .`as`("classes directly in $basePackage are only the @SpringBootApplication class and its Kotlin facade (<Name>Kt)")

    /**
     * Domain은 허용 목록 안의 타입에만 의존한다(순수 Kotlin):
     * - `kotlin..`, `java..`(단 [FORBIDDEN_JDK_PACKAGES]: JDBC·네트워크·파일 채널·RMI·JUL 로깅·FFM·prefs 제외), `org.jetbrains.annotations..`
     * - domain 패키지(`<base>.*.domain..`, 모듈 없이 둔 `<base>.domain..`)
     *
     * 그 밖은 모두 금지다: Spring, `jakarta..` 전체(`jakarta.validation`·`jakarta.transaction` 포함 — Coordinator 결정: domain 검증은
     * 순수 Kotlin), `javax..`, Hibernate, jOOQ(생성 코드 포함), Jackson, slf4j, application/adapter(기관 DTO 포함),
     * `<base>` 바로 아래 클래스, app 공통 구성 패키지.
     * 다른 모듈의 domain은 이 규칙에서는 허용하고, 모듈 간 접근은 Modulith verify가 막는다(named interface `provided`만 공개).
     */
    fun domainIsPure(): ArchRule {
        val allowed = resideInAnyPackage(
            "kotlin..",
            "java..",
            "org.jetbrains.annotations..",
            "$basePackage.*.domain..",
            "$basePackage.domain..",
        ).and(not(resideInAnyPackage(*FORBIDDEN_JDK_PACKAGES, *crossCutting, *generated)))
            .`as`(
                "reside in kotlin.., java.. (except ${FORBIDDEN_JDK_PACKAGES.joinToString()}), org.jetbrains.annotations.. " +
                    "or $basePackage.*.domain.. / $basePackage.domain..",
            )
        return classes().that().resideInAPackage(anyDomain)
            .should().onlyDependOnClassesThat(allowed)
            .`as`("domain ($anyDomain) is pure Kotlin: depends only on kotlin/java standard types and domain packages (allowlist)")
    }

    /**
     * domain·application은 `<base>` 바로 아래 클래스와 app 공통 구성 패키지([crossCuttingPackages])에 의존하지 않는다.
     * 그곳에 EntityManager wrapper나 Spring stereotype meta-annotation을 두어 Port·순수성 규칙을 우회하는 것을 막는다.
     */
    fun coreDoesNotDependOnBaseRootOrCrossCutting(): ArchRule {
        val baseRootOrCrossCutting = resideInAPackage(basePackage).or(resideInAnyPackage(*crossCutting))
            .`as`("reside directly in $basePackage or in cross-cutting packages ${crossCutting.toList()}")
        return noClasses().that().resideInAnyPackage(anyDomain, anyApplication)
            .should().dependOnClassesThat(baseRootOrCrossCutting)
            .`as`("domain/application do not depend on classes directly in $basePackage or in cross-cutting packages")
    }

    /**
     * Application은 Port로만 바깥에 접근한다. 허용 목록(Coordinator 결정 round3, 가역적 구현 관례) 안의 타입에만 의존한다:
     * - `kotlin..`, `java..`(단 [FORBIDDEN_JDK_PACKAGES]), `org.jetbrains.annotations..`
     * - `<base>..`(자기·다른 모듈의 domain/application). 단 `adapter`, 생성 jOOQ, `<base>` 바로 아래 클래스, app 공통 구성 패키지 제외
     * - TX 경계·Bean 선언·이벤트 발행: `org.springframework.transaction..`, `org.springframework.stereotype..`,
     *   `org.springframework.context..`, `org.springframework.beans.factory.annotation..`, `org.springframework.modulith..`
     * - paging 값 타입 `org.springframework.data.domain..`, 로깅 `org.slf4j..`
     *
     * 그 밖(JPA/Hibernate/JDBC, Spring Data repository, jOOQ, Spring web/http/servlet, Spring Batch, java.net·java.nio.file,
     * kafka/amqp/jms 등 메시징 client, `<base>` 밖 같은 조직 패키지의 wrapper(예: com.paycore.shared))은 모두 금지다.
     * 저장·조회·외부 호출·메시지 발행은 `application.required` Port 뒤에 둔다.
     *
     * 다른 모듈의 application 사용: `<base>..` 전체를 허용하고 이 규칙에서는 모듈을 구분하지 않는다. 모듈 간 공개 범위
     * (`application.provided`만 사용 가능)는 Modulith verify가 판정한다(payment-api ModulithProvidedExposureSpec). ArchUnit 패키지
     * 패턴으로 "같은 모듈"을 표현하지 않아, 여기서 막으면 provided 사용까지 과도하게 차단된다.
     */
    fun applicationDependsOnlyOnPorts(): ArchRule {
        val allowed = resideInAnyPackage(
            "kotlin..",
            "java..",
            "org.jetbrains.annotations..",
            "$basePackage..",
            *APPLICATION_ALLOWED_FRAMEWORK_PACKAGES,
        ).and(not(resideInAnyPackage(*FORBIDDEN_JDK_PACKAGES, anyAdapter, *generated, *crossCutting)))
            .and(not(resideInAPackage(basePackage)))
            .`as`(
                "reside in kotlin.., java.. (except ${FORBIDDEN_JDK_PACKAGES.joinToString()}), org.jetbrains.annotations.., " +
                    "$basePackage.. (except adapters, generated jOOQ, base root, cross-cutting) or " +
                    APPLICATION_ALLOWED_FRAMEWORK_PACKAGES.joinToString(),
            )
        return classes().that().resideInAPackage(anyApplication)
            .should().onlyDependOnClassesThat(allowed)
            .`as`("application ($anyApplication) depends only on ports, domain and allowed framework packages (allowlist)")
    }

    /**
     * 영속 기술(JPA·Hibernate·Spring ORM/JDBC·`java.sql`·`javax.sql`·Spring Data repository/JPA)은 `<module>.adapter.persistence`와
     * app 공통 구성 패키지([crossCuttingPackages], DataSource 구성용)에서만 쓴다. web/batch/integration/query adapter와 base root가
     * EntityManager·JdbcTemplate·DataSource·JdbcCursorItemReader(RowMapper) 등으로 Application을 우회해 DB에 접근하는 것을 막는다.
     * `adapter.query`도 예외가 아니다: jOOQ 조회는 Boot가 구성한 `DSLContext`만 주입받으면 되고 DataSource/java.sql이 필요 없다
     * (필요해지면 근거와 함께 재검토). 생성 jOOQ 코드도 검사 대상에 포함한다(현재 생성 코드는 java.sql을 쓰지 않는다).
     */
    fun persistenceTechnologyOnlyInPersistenceAdapter(): ArchRule =
        noClasses().that().resideInAPackage("$basePackage..")
            .and().resideOutsideOfPackages(persistenceAdapter, *crossCutting)
            .should().dependOnClassesThat().resideInAnyPackage(*PERSISTENCE_TECHNOLOGY_PACKAGES)
            .`as`(
                "persistence technology (${PERSISTENCE_TECHNOLOGY_PACKAGES.joinToString()}) is used only from $persistenceAdapter " +
                    "and cross-cutting packages ${crossCutting.toList()}",
            )

    /**
     * 실행 단위마다 `@SpringBootApplication`은 정확히 1개이고 `<base>` 바로 아래에 있다. 두 번째 Boot 구성(다른 component scan·
     * auto-configuration 경계)을 모듈 계층이나 base root에 추가하는 것을 막는다. 검사 대상이 없으면(0개) 실패한다.
     */
    fun springBootApplicationIsSingleAtBaseRoot(): ArchRule =
        classes().that().areAnnotatedWith(SPRING_BOOT_APPLICATION)
            .should().resideInAPackage(basePackage)
            .andShould(beTheOnlySpringBootApplication())
            .`as`("exactly one @SpringBootApplication class exists and it resides directly in $basePackage")

    /** JPA `@Entity`/`@Embeddable`/`@MappedSuperclass`는 `<module>.adapter.persistence`에만 있다(순수 Domain과 Entity 분리). */
    fun jpaMappingsOnlyInPersistenceAdapter(): ArchRule =
        classes().that().areAnnotatedWith("jakarta.persistence.Entity")
            .or().areAnnotatedWith("jakarta.persistence.Embeddable")
            .or().areAnnotatedWith("jakarta.persistence.MappedSuperclass")
            .should().resideInAPackage(persistenceAdapter)
            .`as`("JPA @Entity/@Embeddable/@MappedSuperclass reside only in $persistenceAdapter")

    /**
     * Spring Data `Repository` 하위 타입(JpaRepository 등)과 `@RepositoryDefinition` interface는 `<module>.adapter.persistence`에만
     * 있다(Application은 required Port만 안다).
     */
    fun springDataRepositoriesOnlyInPersistenceAdapter(): ArchRule =
        classes().that().areAssignableTo("org.springframework.data.repository.Repository")
            .or().areAnnotatedWith("org.springframework.data.repository.RepositoryDefinition")
            .should().resideInAPackage(persistenceAdapter)
            .`as`("Spring Data Repository subtypes and @RepositoryDefinition interfaces reside only in $persistenceAdapter")

    /** Spring Batch 타입(`org.springframework.batch..`)은 `<module>.adapter.batch`에서만 쓴다(Batch는 실행 조정만, INV-09). */
    fun springBatchOnlyInBatchAdapter(): ArchRule =
        noClasses().that().resideOutsideOfPackage(batchAdapter)
            .should().dependOnClassesThat().resideInAPackage("org.springframework.batch..")
            .`as`("Spring Batch types are used only from $batchAdapter")

    /**
     * `@Controller`(및 이를 meta-annotation으로 가진 `@RestController`)와 `@ControllerAdvice`(및 `@RestControllerAdvice`)는
     * `<module>.adapter.web`에만 있다.
     */
    fun controllersOnlyInWebAdapter(): ArchRule =
        classes().that().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
            .or().areMetaAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
            .should().resideInAPackage(webAdapter)
            .`as`("@Controller/@RestController/@ControllerAdvice/@RestControllerAdvice reside only in $webAdapter")

    /** org.jooq와 생성된 jOOQ 코드는 `<module>.adapter.query`(와 생성 코드 자신)만 접근한다(CQRS 조회 전용). */
    fun jooqOnlyInQueryAdapter(): ArchRule =
        noClasses().that().resideOutsideOfPackages(queryAdapter, *generated)
            .should().dependOnClassesThat().resideInAnyPackage("org.jooq..", *generated)
            .`as`("org.jooq and generated jOOQ code are used only from $queryAdapter")

    /**
     * `<module>.adapter.query`(jOOQ CQRS 조회)는 jOOQ로 쓰지 않는다(TECHNOLOGY §4: 업무 테이블 jOOQ DML은 기본 정책 밖, CP-DB-001 결정 —
     * FOUNDATION-001 L-2 이월). 쓰기는 application Use Case → `application.required` Port → `adapter.persistence`(JPA, 로컬 TX·version·
     * Outbox 규칙) 경로만 쓴다. 대량 Batch·조건부 갱신이 꼭 필요하면 별도 adapter와 근거를 검토해 이 규칙을 그 위치에 한해 다시 정한다.
     *
     * 검사 대상 호출: `DSLContext`/`DSL`의 insertInto·update·delete(From)·mergeInto·truncate·batch*·loadInto·execute*(raw SQL 포함)·query,
     * `TableRecord`/`UpdatableRecord`의 store·insert·update·delete·merge. 한계: `fetch("<SQL 문자열>")`처럼 조회 API에 DML 문자열을 넣는
     * 우회와 reflection은 정적 검사로 잡지 않는다(리뷰로 확인).
     */
    fun queryAdapterDoesNotWriteWithJooq(): ArchRule =
        noClasses().that().resideInAPackage(queryAdapter)
            .should().callMethodWhere(JOOQ_WRITE_CALL)
            .`as`("$queryAdapter does not issue jOOQ DML (insert/update/delete/merge/truncate/batch/execute or record store)")

    /**
     * 외부 기관 adapter(`<module>.adapter.integration`)의 타입은 그 밖에서 쓰지 않는다. 다른 계층은 `application.required` Port로만
     * 기관 기능에 접근하고, 기관 DTO는 adapter 안에서 Domain/Port 타입으로 변환한다. (계층 단위 규칙: 다른 모듈의 integration
     * adapter 사용은 Modulith verify가 막는다.)
     */
    fun integrationAdapterIsEncapsulated(): ArchRule =
        noClasses().that().resideOutsideOfPackage(integrationAdapter)
            .should().dependOnClassesThat().resideInAPackage(integrationAdapter)
            .`as`("types in $integrationAdapter are not used outside it (access through application.required ports)")

    /**
     * Web adapter는 persistence/query/integration adapter와 `application.required`(driven port)를 직접 쓰지 않고
     * application.provided Port를 호출한다.
     */
    fun webAdapterUsesOnlyApplication(): ArchRule =
        noClasses().that().resideInAPackage(webAdapter)
            .should().dependOnClassesThat()
            .resideInAnyPackage(persistenceAdapter, queryAdapter, integrationAdapter, anyRequiredPort)
            .`as`("$webAdapter does not depend on persistence/query/integration adapters or application.required ports directly")

    /**
     * Batch adapter(Job/Step 배선)는 application.provided use case를 호출한다(INV-09: Batch는 실행 조정만). persistence/query/
     * integration adapter, `application.required`, Spring Data repository(`org.springframework.data.repository`), JPA/Hibernate를
     * 직접 쓰지 않는다. RepositoryItemReader/JpaPagingItemReader 방식으로 Application을 우회해 데이터를 읽고 쓰는 것을 막는다.
     */
    fun batchAdapterUsesOnlyApplication(): ArchRule =
        noClasses().that().resideInAPackage(batchAdapter)
            .should().dependOnClassesThat().resideInAnyPackage(
                persistenceAdapter,
                queryAdapter,
                integrationAdapter,
                anyRequiredPort,
                "org.springframework.data.repository..",
                "jakarta.persistence..",
                "javax.persistence..",
                "org.hibernate..",
            )
            .`as`(
                "$batchAdapter does not depend on persistence/query/integration adapters, application.required ports, " +
                    "Spring Data repositories or JPA/Hibernate directly",
            )

    /**
     * Modulith `@NamedInterface`는 `<base>.<module>.application.provided` 패키지의 `package-info`에만 붙인다(타입 단위, 다른 패키지,
     * provided 하위 패키지는 금지). 공개 범위가 provided 패키지 하나로 고정된다.
     */
    fun namedInterfacesOnlyOnProvidedPackages(): ArchRule =
        classes().that().areAnnotatedWith(NAMED_INTERFACE)
            .should().haveSimpleName("package-info")
            .andShould().resideInAPackage("$basePackage.*.application.provided")
            .`as`("@NamedInterface is declared only on package-info of $basePackage.<module>.application.provided")

    /** `<basePackage>.<module>` slice 사이에 순환 의존이 없다. */
    fun modulesAreFreeOfCycles(): ArchRule =
        slices().matching("$basePackage.(*)..")
            .should().beFreeOfCycles()
            .`as`("no dependency cycles between $basePackage.<module> slices")

    /** 모든 규칙(이름 → 규칙). meta-test가 빠짐없이 검사하는 데 쓴다. */
    fun all(): Map<String, ArchRule> = linkedMapOf(
        "classesResideInKnownLayers" to classesResideInKnownLayers(),
        "baseRootHoldsOnlyApplicationEntryPoint" to baseRootHoldsOnlyApplicationEntryPoint(),
        "domainIsPure" to domainIsPure(),
        "coreDoesNotDependOnBaseRootOrCrossCutting" to coreDoesNotDependOnBaseRootOrCrossCutting(),
        "applicationDependsOnlyOnPorts" to applicationDependsOnlyOnPorts(),
        "persistenceTechnologyOnlyInPersistenceAdapter" to persistenceTechnologyOnlyInPersistenceAdapter(),
        "springBootApplicationIsSingleAtBaseRoot" to springBootApplicationIsSingleAtBaseRoot(),
        "jpaMappingsOnlyInPersistenceAdapter" to jpaMappingsOnlyInPersistenceAdapter(),
        "springDataRepositoriesOnlyInPersistenceAdapter" to springDataRepositoriesOnlyInPersistenceAdapter(),
        "springBatchOnlyInBatchAdapter" to springBatchOnlyInBatchAdapter(),
        "controllersOnlyInWebAdapter" to controllersOnlyInWebAdapter(),
        "jooqOnlyInQueryAdapter" to jooqOnlyInQueryAdapter(),
        "queryAdapterDoesNotWriteWithJooq" to queryAdapterDoesNotWriteWithJooq(),
        "integrationAdapterIsEncapsulated" to integrationAdapterIsEncapsulated(),
        "webAdapterUsesOnlyApplication" to webAdapterUsesOnlyApplication(),
        "batchAdapterUsesOnlyApplication" to batchAdapterUsesOnlyApplication(),
        "namedInterfacesOnlyOnProvidedPackages" to namedInterfacesOnlyOnProvidedPackages(),
        "modulesAreFreeOfCycles" to modulesAreFreeOfCycles(),
    )

    private companion object {
        const val SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication"
        const val NAMED_INTERFACE = "org.springframework.modulith.NamedInterface"

        /**
         * domain·application 허용 목록의 `java..` 중 제외하는 I/O·JDBC·원격 호출·로깅·native 메모리(FFM)·OS 설정 저장소 패키지.
         * `java.lang.management`, `ProcessBuilder`/`Runtime.exec`(java.lang)는 제외하지 않는다(문서 "강제하지 않는 것").
         */
        val FORBIDDEN_JDK_PACKAGES = arrayOf(
            "java.sql..",
            "java.net..",
            "java.nio.channels..",
            "java.nio.file..",
            "java.rmi..",
            "java.util.logging..",
            "java.lang.foreign..",
            "java.util.prefs..",
        )

        /** application 허용 목록의 Spring·로깅 패키지(Coordinator 결정 round3). micrometer 등은 필요 시 근거와 함께 추가(가역). */
        val APPLICATION_ALLOWED_FRAMEWORK_PACKAGES = arrayOf(
            "org.springframework.transaction..",
            "org.springframework.stereotype..",
            "org.springframework.context..",
            "org.springframework.beans.factory.annotation..",
            "org.springframework.modulith..",
            "org.springframework.data.domain..",
            "org.slf4j..",
        )

        /** adapter.persistence(와 공통 구성 패키지)에서만 쓰는 영속 기술 패키지. */
        val PERSISTENCE_TECHNOLOGY_PACKAGES = arrayOf(
            "jakarta.persistence..",
            "javax.persistence..",
            "org.hibernate..",
            "org.springframework.orm..",
            "org.springframework.jdbc..",
            "java.sql..",
            "javax.sql..",
            "org.springframework.data.repository..",
            "org.springframework.data.jpa..",
        )

        /** jOOQ 쓰기 진입점(DSLContext/DSL). */
        val JOOQ_DSL_WRITE_METHODS = setOf(
            "insertInto", "insertQuery", "update", "updateQuery", "delete", "deleteFrom", "deleteQuery", "mergeInto", "truncate",
            "truncateTable", "batch", "batchInsert", "batchUpdate", "batchDelete", "batchStore", "batchMerge", "loadInto", "execute",
            "executeInsert", "executeUpdate", "executeDelete", "query",
        )

        /** jOOQ Active Record 쓰기(TableRecord/UpdatableRecord). */
        val JOOQ_RECORD_WRITE_METHODS = setOf("store", "insert", "update", "delete", "merge")

        val JOOQ_WRITE_CALL: DescribedPredicate<JavaMethodCall> =
            object : DescribedPredicate<JavaMethodCall>("a jOOQ write call (DSLContext/DSL DML or record store)") {
                override fun test(call: JavaMethodCall): Boolean {
                    val owner = call.targetOwner
                    return when {
                        owner.isAssignableTo("org.jooq.DSLContext") || owner.name == "org.jooq.impl.DSL" -> call.name in JOOQ_DSL_WRITE_METHODS
                        owner.isAssignableTo("org.jooq.TableRecord") -> call.name in JOOQ_RECORD_WRITE_METHODS
                        else -> false
                    }
                }
            }

        /** 검사 대상 전체에서 `@SpringBootApplication` 클래스가 정확히 1개인지 본다(대상 수는 init에서 받는다). */
        fun beTheOnlySpringBootApplication(): ArchCondition<JavaClass> =
            object : ArchCondition<JavaClass>("be the only @SpringBootApplication class") {
                private var all: List<String> = emptyList()

                override fun init(allObjectsToTest: MutableCollection<JavaClass>) {
                    all = allObjectsToTest.map { it.name }.sorted()
                }

                override fun check(item: JavaClass, events: ConditionEvents) {
                    val satisfied = all.size == 1
                    val message = if (satisfied) {
                        "${item.name} is the only @SpringBootApplication class"
                    } else {
                        "${item.name} is one of ${all.size} @SpringBootApplication classes $all in ${item.sourceCodeLocation}"
                    }
                    events.add(SimpleConditionEvent(item, satisfied, message))
                }
            }

        fun beSpringBootApplicationOrItsFacade(): ArchCondition<JavaClass> =
            object : ArchCondition<JavaClass>("be the @SpringBootApplication class or its Kotlin file facade (<Name>Kt)") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    val isApplication = item.isAnnotatedWith(SPRING_BOOT_APPLICATION)
                    val isFacade = item.simpleName.endsWith("Kt") &&
                        item.`package`.classes.any {
                            it.simpleName == item.simpleName.removeSuffix("Kt") && it.isAnnotatedWith(SPRING_BOOT_APPLICATION)
                        }
                    val satisfied = isApplication || isFacade
                    val message = if (satisfied) {
                        "${item.name} is the application entry point"
                    } else {
                        "${item.name} is neither a @SpringBootApplication class nor its Kotlin facade in ${item.sourceCodeLocation}"
                    }
                    events.add(SimpleConditionEvent(item, satisfied, message))
                }
            }
    }
}
