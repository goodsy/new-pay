package com.paycore.payment.architecture

import com.paycore.payment.PaymentApiApplication
import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.ApplicationModules

/**
 * payment-api 아키텍처 규칙(PayCoreArchitectureRules — 규칙 자체의 동작 증거는 libs/test-support meta-test).
 *
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. CP-DOMAIN-001에서 첫 업무 모듈 `payment`의 `domain` 패키지가 생겨
 * 계층 소속·domain 순수성·core 격리·모듈 순환 규칙의 대상이 생겼으므로 false로 바꿨다. application·adapter(web/persistence/batch)·
 * provided package-info는 아직 없어(CP-API/CP-DB 범위) 그 규칙만 true로 두고 사유를 적는다. 대상이 이미 있는 규칙은 false다.
 */
class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    val rules = PayCoreArchitectureRules(BASE_PACKAGE, listOf(GENERATED_JOOQ_PACKAGE))

    test("Spring Modulith module structure is valid (generated jOOQ package excluded)") {
        val modules = ApplicationModules.of(PaymentApiApplication::class.java, rules.generatedCode())
        modules.verify()
        // 생성 코드가 생겨도 `jooq`라는 application module로 잡히지 않는다(제외 동작 증거: ModulithGeneratedCodeExclusionSpec).
        modules.getModuleByName("jooq").isPresent shouldBe false
        // CP-DOMAIN-001: 첫 업무 모듈(Order/Payment 활성 규칙). verify가 빈 모듈 목록에서 통과한 것이 아님을 보인다.
        modules.getModuleByName("payment").isPresent shouldBe true
    }

    test("GENERATED_JOOQ_PACKAGE matches the build's generateJooqMain package") {
        System.getProperty("paycore.jooq.mainPackage") shouldBe GENERATED_JOOQ_PACKAGE
    }

    test("classes in module packages reside in known layers") {
        // 대상 있음(CP-DOMAIN-001): com.paycore.payment.payment.domain.
        rules.classesResideInKnownLayers().allowEmptyShould(false).check(classes)
    }

    test("base package root holds only the application entry point") {
        // 대상 있음: PaymentApiApplication과 Kotlin facade PaymentApiApplicationKt.
        rules.baseRootHoldsOnlyApplicationEntryPoint().allowEmptyShould(false).check(classes)
    }

    test("domain is pure Kotlin") {
        // 대상 있음(CP-DOMAIN-001): payment.domain(OrderKey·Payment·OrderPaymentControl 등).
        rules.domainIsPure().allowEmptyShould(false).check(classes)
    }

    test("domain/application do not depend on base root or cross-cutting packages") {
        // 대상 있음(CP-DOMAIN-001): payment.domain. application은 아직 없다(CP-API).
        rules.coreDoesNotDependOnBaseRootOrCrossCutting().allowEmptyShould(false).check(classes)
    }

    test("application depends only on ports") {
        // 빈 대상: application 패키지가 아직 없다(CP-DOMAIN-001은 domain만, application은 CP-API 범위).
        rules.applicationDependsOnlyOnPorts().allowEmptyShould(true).check(classes)
    }

    test("persistence technology (JPA/JDBC/DataSource/Spring Data repository) only in adapter.persistence") {
        // 대상 있음: PaymentApiApplication(+Kt). 공통 구성 패키지 없음.
        rules.persistenceTechnologyOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("exactly one @SpringBootApplication, directly in the base package") {
        rules.springBootApplicationIsSingleAtBaseRoot().allowEmptyShould(false).check(classes)
    }

    test("test sources (TechSmoke) are excluded from the architecture import (DO_NOT_INCLUDE_TESTS)") {
        // TechSmokeJpaSpec은 JdbcTemplate을 쓰는 테스트 소스다. 테스트까지 import하면 영속 기술 규칙이 이를 잡으므로,
        // 앱 규칙 통과가 테스트 소스 제외 덕분임(그리고 제외가 실제로 동작함)을 함께 보인다.
        val withTests = ClassFileImporter().importPackages(BASE_PACKAGE)
        withTests.any { it.packageName == "$BASE_PACKAGE.techsmoke" } shouldBe true
        classes.any { it.packageName.startsWith("$BASE_PACKAGE.techsmoke") } shouldBe false
        val error = shouldThrow<AssertionError> {
            rules.persistenceTechnologyOnlyInPersistenceAdapter().check(withTests)
        }
        error.message shouldContain "TechSmokeJpaSpec"
    }

    test("JPA mappings only in adapter.persistence") {
        // 빈 대상: JPA Entity가 아직 없다(TECH-001 smoke Entity는 테스트 소스. payment persistence adapter는 CP-DB 범위).
        rules.jpaMappingsOnlyInPersistenceAdapter().allowEmptyShould(true).check(classes)
    }

    test("Spring Data repositories only in adapter.persistence") {
        // 빈 대상: Spring Data Repository가 아직 없다(CP-DB 범위).
        rules.springDataRepositoriesOnlyInPersistenceAdapter().allowEmptyShould(true).check(classes)
    }

    test("Spring Batch types only in adapter.batch") {
        rules.springBatchOnlyInBatchAdapter().allowEmptyShould(false).check(classes)
    }

    test("controllers only in adapter.web") {
        // 빈 대상: Controller가 아직 없다(CP-API 범위).
        rules.controllersOnlyInWebAdapter().allowEmptyShould(true).check(classes)
    }

    test("jOOQ and generated jOOQ code only in adapter.query") {
        rules.jooqOnlyInQueryAdapter().allowEmptyShould(false).check(classes)
    }

    test("integration adapter types are not used outside adapter.integration") {
        rules.integrationAdapterIsEncapsulated().allowEmptyShould(false).check(classes)
    }

    test("web adapter does not use persistence/query/integration adapters or application.required") {
        // 빈 대상: adapter.web이 아직 없다(CP-API 범위).
        rules.webAdapterUsesOnlyApplication().allowEmptyShould(true).check(classes)
    }

    test("batch adapter uses only application.provided (no adapters/required ports/Spring Data/JPA)") {
        // 빈 대상: adapter.batch가 아직 없다(payment-api에 Batch 작업 계획 없음).
        rules.batchAdapterUsesOnlyApplication().allowEmptyShould(true).check(classes)
    }

    test("@NamedInterface only on application.provided package-info") {
        // 빈 대상: 공개 provided 패키지(package-info)가 아직 없다(application.provided는 CP-API 범위).
        rules.namedInterfacesOnlyOnProvidedPackages().allowEmptyShould(true).check(classes)
    }

    test("no cycles between modules") {
        // 대상 있음(CP-DOMAIN-001): 모듈 slice `payment`.
        rules.modulesAreFreeOfCycles().allowEmptyShould(false).check(classes)
    }
}) {
    companion object {
        const val BASE_PACKAGE = "com.paycore.payment"

        /** build.gradle.kts의 mainJooqPackage(generateJooqMain)와 같은 값이어야 한다(위 테스트가 비교). */
        const val GENERATED_JOOQ_PACKAGE = "com.paycore.payment.jooq.generated"
    }
}
