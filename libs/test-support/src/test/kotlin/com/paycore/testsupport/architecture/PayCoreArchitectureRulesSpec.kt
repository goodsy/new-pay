package com.paycore.testsupport.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * PayCoreArchitectureRules의 실행 증거(meta-test). app에는 아직 domain/application/adapter 클래스가 없으므로,
 * 규칙이 실제로 위반을 잡는지는 여기서 fixture(com.paycore.archfixture.<rule>.{ok,bad*})로 증명한다.
 *
 * - 준수 fixture: 규칙이 통과한다(대상이 비어 있지 않아야 통과하므로 빈 대상으로 우연히 통과하지 않는다).
 * - 위반 fixture: 규칙이 AssertionError로 실패하고, 메시지가 위반 클래스를 가리킨다(빈 대상 실패와 구별).
 * - 빈 import: 모든 규칙이 실패한다 → 규칙 내부에 allowEmptyShould(true)가 없다.
 */
class PayCoreArchitectureRulesSpec : FunSpec({

    fun importFixture(pkg: String): JavaClasses {
        val classes = ClassFileImporter().importPackages(pkg)
        // fixture 패키지 이름 오타로 빈 import가 되면 위반 검사가 의미 없다.
        check(pkg == EMPTY_PACKAGE || classes.isNotEmpty()) { "fixture package $pkg has no classes" }
        return classes
    }

    fun rulesFor(base: String, generated: List<String> = emptyList(), crossCutting: List<String> = emptyList()) =
        PayCoreArchitectureRules(base, generated, crossCutting)

    data class Violation(val fixture: String, val mustMention: String)

    data class Case(
        val rule: String,
        val select: (PayCoreArchitectureRules) -> ArchRule,
        val ok: String,
        val violations: List<Violation>,
        // jOOQ 생성 코드 패키지는 fixture 기준 패키지마다 `<base>.generated`로 둔다.
        val withGenerated: Boolean = false,
        // app 공통 구성 허용 목록은 fixture 기준 패키지마다 `<base>.config`로 둔다.
        val withCrossCutting: Boolean = false,
    )

    val cases = listOf(
        Case(
            "classesResideInKnownLayers", PayCoreArchitectureRules::classesResideInKnownLayers, "$F.knownlayers.ok",
            listOf(
                Violation("$F.knownlayers.badmodel", "OrderInModelPackage"),
                Violation("$F.knownlayers.badservice", "OrderServiceInServicePackage"),
                Violation("$F.knownlayers.badadaptermisc", "HelperInUnknownAdapter"),
                // round3 I-b(특성화): 모듈 root package-info(@ApplicationModule(type = OPEN))도 계층 밖이라 거부된다.
                Violation("$F.knownlayers.badmoduleinfo", "badmoduleinfo.sample.package-info"),
            ),
            withGenerated = true,
            withCrossCutting = true,
        ),
        Case(
            "baseRootHoldsOnlyApplicationEntryPoint", PayCoreArchitectureRules::baseRootHoldsOnlyApplicationEntryPoint,
            "$F.baseroot.ok",
            listOf(
                // EntityManager를 감싼 helper, Spring stereotype meta-annotation, Application이 아닌 파일의 facade(HelpersKt).
                Violation("$F.baseroot.badhelper", "TxHelper"),
                Violation("$F.baseroot.badstereotype", "CoreComponent"),
                Violation("$F.baseroot.badfacade", "HelpersKt"),
            ),
        ),
        Case(
            "domainIsPure", PayCoreArchitectureRules::domainIsPure, "$F.domainpurity.ok",
            listOf(
                Violation("$F.domainpurity.badjpa", "JpaAnnotatedOrder"),
                Violation("$F.domainpurity.badspring", "SpringManagedOrder"),
                Violation("$F.domainpurity.badinstitution", "OrderFromVan"),
                // 모듈 segment 없이 <base>.domain에 둔 클래스도 검사 대상이다.
                Violation("$F.domainpurity.badnomodule", "NoModuleDomainOrder"),
                // 허용 목록(kotlin/java 표준·자기 domain) 밖: base root의 @Component meta-annotation, jakarta.transaction, slf4j,
                // jakarta.validation(Coordinator 결정: domain 검증은 순수 Kotlin), java.net, java.sql, app 공통 구성 패키지.
                Violation("$F.domainpurity.badbaseroot", "StereotypedOrder"),
                Violation("$F.domainpurity.badtransaction", "TransactionalOrder"),
                Violation("$F.domainpurity.badslf4j", "LoggingOrder"),
                Violation("$F.domainpurity.badvalidation", "ValidatedOrder"),
                Violation("$F.domainpurity.badjavanet", "RemoteOrder"),
                Violation("$F.domainpurity.badjavasql", "TimestampedOrder"),
                Violation("$F.domainpurity.badcrosscutting", "AuditedOrder"),
                // round3 I-a: Foreign Function & Memory API, OS 설정 저장소.
                Violation("$F.domainpurity.badforeign", "OffHeapOrder"),
                Violation("$F.domainpurity.badprefs", "PreferenceOrder"),
            ),
            withCrossCutting = true,
        ),
        Case(
            "coreDoesNotDependOnBaseRootOrCrossCutting", PayCoreArchitectureRules::coreDoesNotDependOnBaseRootOrCrossCutting,
            "$F.coreisolation.ok",
            listOf(
                // base root에서 EntityManager를 감싼 helper를 application이 사용(applicationDependsOnlyOnPorts 우회).
                Violation("$F.coreisolation.badentitymanager", "SneakyPersistService"),
                // base root의 @Component meta-annotation을 domain이 사용.
                Violation("$F.coreisolation.badstereotype", "StereotypedDomainOrder"),
                // app 공통 구성 패키지를 application이 사용.
                Violation("$F.coreisolation.badcrosscutting", "OperatorAwareService"),
            ),
            withCrossCutting = true,
        ),
        Case(
            "applicationDependsOnlyOnPorts", PayCoreArchitectureRules::applicationDependsOnlyOnPorts, "$F.appports.ok",
            listOf(
                Violation("$F.appports.badadapter", "LeakyService"),
                Violation("$F.appports.badjooq", "JooqService"),
                Violation("$F.appports.badweb", "HttpAwareService"),
                Violation("$F.appports.badspringdata", "OrderRepositoryInApplication"),
                // round3 M-A(허용 목록): <base> 밖 같은 조직 패키지의 EntityManager wrapper, java.net.http, java.nio.file,
                // 허용 목록 밖 임의 외부 패키지(메시징 client 대역 com.example.messaging).
                Violation("$F.appports.badoutsidebase", "WrappedPersistService"),
                Violation("$F.appports.badjavanet", "DirectHttpService"),
                Violation("$F.appports.badjavanio", "DirectFileService"),
                Violation("$F.appports.badmessaging", "DirectPublishService"),
            ),
        ),
        Case(
            "persistenceTechnologyOnlyInPersistenceAdapter",
            PayCoreArchitectureRules::persistenceTechnologyOnlyInPersistenceAdapter, "$F.persistencetech.ok",
            listOf(
                // round3 L-A: web의 EntityManager/JdbcTemplate 주입, batch의 DataSource+JdbcCursorItemReader/RowMapper,
                // query의 DataSource 직접 JDBC, integration의 Spring Data repository 사용.
                Violation("$F.persistencetech.badwebentitymanager", "EntityManagerEndpoint"),
                Violation("$F.persistencetech.badwebjdbc", "JdbcEndpoint"),
                Violation("$F.persistencetech.badbatchjdbc", "JdbcReadingStep"),
                Violation("$F.persistencetech.badqueryjdbc", "RawJdbcQuery"),
                Violation("$F.persistencetech.badspringdatarepo", "VanResultRecorder"),
            ),
            // DataSource 구성은 공통 구성 패키지(<base>.config)에서 허용된다(준수 fixture).
            withCrossCutting = true,
        ),
        Case(
            "springBootApplicationIsSingleAtBaseRoot", PayCoreArchitectureRules::springBootApplicationIsSingleAtBaseRoot,
            "$F.bootapp.ok",
            listOf(
                // round3 I-c: base root에 두 번째 Application, 모듈 계층 안의 Application.
                Violation("$F.bootapp.badtwo", "SecondApplication"),
                Violation("$F.bootapp.badmodule", "NestedApplication"),
                // 유일한 Application이 모듈 계층에 있음(위치 조건만 위반).
                Violation("$F.bootapp.badnestedonly", "MisplacedApplication"),
            ),
        ),
        Case(
            "jpaMappingsOnlyInPersistenceAdapter", PayCoreArchitectureRules::jpaMappingsOnlyInPersistenceAdapter,
            "$F.jpamapping.ok",
            listOf(
                Violation("$F.jpamapping.baddomain", "OrderEntityInDomain"),
                Violation("$F.jpamapping.badembeddable", "AmountEmbeddableInApplication"),
                Violation("$F.jpamapping.badmapped", "BaseInQueryAdapter"),
            ),
        ),
        Case(
            "springDataRepositoriesOnlyInPersistenceAdapter",
            PayCoreArchitectureRules::springDataRepositoriesOnlyInPersistenceAdapter, "$F.repositories.ok",
            listOf(
                Violation("$F.repositories.badapplication", "RepositoryInApplication"),
                Violation("$F.repositories.baddomain", "CrudRepositoryInDomain"),
                Violation("$F.repositories.badannotated", "AnnotatedRepositoryInApplication"),
            ),
        ),
        Case(
            "springBatchOnlyInBatchAdapter", PayCoreArchitectureRules::springBatchOnlyInBatchAdapter, "$F.batch.ok",
            listOf(
                Violation("$F.batch.badweb", "JobStatusEndpoint"),
                Violation("$F.batch.badpersistence", "BatchAwareRepositoryAdapter"),
            ),
        ),
        Case(
            "controllersOnlyInWebAdapter", PayCoreArchitectureRules::controllersOnlyInWebAdapter, "$F.controllers.ok",
            listOf(
                Violation("$F.controllers.badrest", "RestControllerInApplication"),
                Violation("$F.controllers.badcontroller", "ControllerInPersistence"),
                Violation("$F.controllers.badrestadvice", "RestAdviceInApplication"),
                Violation("$F.controllers.badadvice", "AdviceInPersistence"),
            ),
        ),
        Case(
            "jooqOnlyInQueryAdapter", PayCoreArchitectureRules::jooqOnlyInQueryAdapter, "$F.jooq.ok",
            listOf(
                Violation("$F.jooq.badpersistence", "JooqWriterInPersistence"),
                Violation("$F.jooq.badgenerated", "OrderViewUsingGenerated"),
            ),
            withGenerated = true,
        ),
        Case(
            "integrationAdapterIsEncapsulated", PayCoreArchitectureRules::integrationAdapterIsEncapsulated,
            "$F.integration.ok",
            listOf(
                Violation("$F.integration.badapplication", "DirectVanService"),
                Violation("$F.integration.badweb", "VanEchoEndpoint"),
            ),
        ),
        Case(
            "webAdapterUsesOnlyApplication", PayCoreArchitectureRules::webAdapterUsesOnlyApplication, "$F.webadapter.ok",
            listOf(
                Violation("$F.webadapter.badpersistence", "RowEndpoint"),
                Violation("$F.webadapter.badquery", "QueryEndpoint"),
                Violation("$F.webadapter.badrequired", "StoreEndpoint"),
            ),
        ),
        Case(
            "batchAdapterUsesOnlyApplication", PayCoreArchitectureRules::batchAdapterUsesOnlyApplication,
            "$F.batchadapter.ok",
            listOf(
                Violation("$F.batchadapter.badpersistence", "RowReadingStep"),
                Violation("$F.batchadapter.badquery", "ViewReadingStep"),
                Violation("$F.batchadapter.badintegration", "FetchFileStep"),
                Violation("$F.batchadapter.badrequired", "PortBypassingStep"),
                Violation("$F.batchadapter.badspringdata", "RepositoryReadingStep"),
                Violation("$F.batchadapter.badjpa", "JpaReadingStep"),
            ),
        ),
        Case(
            "namedInterfacesOnlyOnProvidedPackages", PayCoreArchitectureRules::namedInterfacesOnlyOnProvidedPackages,
            "$F.namedinterface.ok",
            listOf(
                // package-info.java fixture는 src/test/java(javac 컴파일)에 있다.
                Violation("$F.namedinterface.badpackage", "badpackage.sample.domain.package-info"),
                Violation("$F.namedinterface.badsubpackage", "provided.dto.package-info"),
                Violation("$F.namedinterface.badtype", "TypeLevelExposedPort"),
            ),
        ),
        Case(
            "modulesAreFreeOfCycles", PayCoreArchitectureRules::modulesAreFreeOfCycles, "$F.cycles.ok",
            listOf(Violation("$F.cycles.bad", "Cycle detected")),
        ),
    )

    fun Case.rules(base: String) = rulesFor(
        base,
        generated = if (withGenerated) listOf("$base.generated") else emptyList(),
        crossCutting = if (withCrossCutting) listOf("$base.config") else emptyList(),
    )

    test("every rule in PayCoreArchitectureRules.all() has fixtures") {
        rulesFor("com.example").all().keys shouldContainExactlyInAnyOrder cases.map { it.rule }
    }

    cases.forEach { case ->
        test("${case.rule}: compliant fixture passes") {
            case.select(case.rules(case.ok)).check(importFixture(case.ok))
        }

        case.violations.forEach { violation ->
            test("${case.rule}: ${violation.fixture.substringAfterLast('.')} fixture fails naming ${violation.mustMention}") {
                val error = shouldThrow<AssertionError> {
                    case.select(case.rules(violation.fixture)).check(importFixture(violation.fixture))
                }
                error.message shouldContain violation.mustMention
            }
        }

        test("${case.rule}: fails on an empty import (no internal allowEmptyShould)") {
            val error = shouldThrow<AssertionError> {
                case.select(case.rules(EMPTY_PACKAGE)).check(importFixture(EMPTY_PACKAGE))
            }
            error.message shouldContain "failed to check any classes"
        }
    }

    test("domainIsPure compliant fixture includes Kotlin compiler-generated types (companion, \$WhenMappings, suspend, lazy)") {
        // 준수 fixture가 실제로 Kotlin 보조 타입을 포함해야 domainIsPure 통과가 그 타입들에 대한 증거가 된다.
        val classes = importFixture("$F.domainpurity.ok")
        val names = classes.map { it.name }
        val ledger = "$F.domainpurity.ok.sample.domain.Ledger"
        names shouldContain "$ledger\$Companion"
        names shouldContain "$ledger\$WhenMappings"
        val ledgerClass = classes.get(ledger)
        ledgerClass.allFields.map { it.rawType.name } shouldContain "kotlin.Lazy"
        ledgerClass.methods.single { it.name == "settle" }.rawParameterTypes.map { it.name } shouldContain
            "kotlin.coroutines.Continuation"
    }

    test("generatedCode() matches only the configured generated packages") {
        val classes = importFixture("$F.jooq.ok")
        val predicate = rulesFor("$F.jooq.ok", listOf("$F.jooq.ok.generated")).generatedCode()
        classes.filter(predicate::test).map { it.simpleName } shouldContainExactlyInAnyOrder listOf("OrderTable")
        classes.any(rulesFor("$F.jooq.ok").generatedCode()::test) shouldBe false
    }
}) {
    private companion object {
        const val F = "com.paycore.archfixture"

        // 클래스가 없는 패키지.
        const val EMPTY_PACKAGE = "com.paycore.archfixture.nothinghere"
    }
}
