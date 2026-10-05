package com.paycore.financial.architecture

import com.paycore.financial.FinancialBatchApplication
import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.ApplicationModules

/**
 * financial-batch 아키텍처 규칙(PayCoreArchitectureRules — 규칙 자체의 동작 증거는 libs/test-support meta-test).
 *
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. 현재 app main에는 금융 업무 모듈이 아직 없어 해당 규칙의
 * 검사 대상이 비어 있다. 첫 업무 모듈이 생기면 그 규칙의 값을 false로 바꾼다. 대상이 이미 있는 규칙은 false다.
 * Batch Job 구성은 `<module>.adapter.batch`에 두고 Application use case를 호출한다(INV-09).
 */
class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    // financial-batch는 아직 jOOQ codegen이 없다(생성 패키지 없음).
    val rules = PayCoreArchitectureRules(BASE_PACKAGE)

    test("Spring Modulith module structure is valid") {
        ApplicationModules.of(FinancialBatchApplication::class.java, rules.generatedCode()).verify()
    }

    test("classes in module packages reside in known layers") {
        // 빈 대상: 모듈 패키지 클래스가 아직 없다.
        rules.classesResideInKnownLayers().allowEmptyShould(true).check(classes)
    }

    test("base package root holds only the application entry point") {
        // 대상 있음: FinancialBatchApplication과 Kotlin facade FinancialBatchApplicationKt.
        rules.baseRootHoldsOnlyApplicationEntryPoint().allowEmptyShould(false).check(classes)
    }

    test("domain is pure Kotlin") {
        // 빈 대상: domain 패키지가 아직 없다.
        rules.domainIsPure().allowEmptyShould(true).check(classes)
    }

    test("domain/application do not depend on base root or cross-cutting packages") {
        // 빈 대상: domain/application 패키지가 아직 없다.
        rules.coreDoesNotDependOnBaseRootOrCrossCutting().allowEmptyShould(true).check(classes)
    }

    test("application depends only on ports") {
        // 빈 대상: application 패키지가 아직 없다.
        rules.applicationDependsOnlyOnPorts().allowEmptyShould(true).check(classes)
    }

    test("persistence technology (JPA/JDBC/DataSource/Spring Data repository) only in adapter.persistence") {
        // 대상 있음: FinancialBatchApplication(+Kt). 공통 구성 패키지 없음.
        rules.persistenceTechnologyOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("exactly one @SpringBootApplication, directly in the base package") {
        rules.springBootApplicationIsSingleAtBaseRoot().allowEmptyShould(false).check(classes)
    }

    test("test sources (TechSmoke) are excluded from the architecture import (DO_NOT_INCLUDE_TESTS)") {
        // TechSmokeBatchJobConfig는 DataSource를 쓰는 테스트 소스다. 테스트까지 import하면 영속 기술 규칙이 이를 잡는다.
        val withTests = ClassFileImporter().importPackages(BASE_PACKAGE)
        withTests.any { it.packageName == "$BASE_PACKAGE.techsmoke" } shouldBe true
        classes.any { it.packageName.startsWith("$BASE_PACKAGE.techsmoke") } shouldBe false
        val error = shouldThrow<AssertionError> {
            rules.persistenceTechnologyOnlyInPersistenceAdapter().check(withTests)
        }
        error.message shouldContain "TechSmokeBatchJobConfig"
    }

    test("JPA mappings only in adapter.persistence") {
        // 빈 대상: JPA Entity가 아직 없다.
        rules.jpaMappingsOnlyInPersistenceAdapter().allowEmptyShould(true).check(classes)
    }

    test("Spring Data repositories only in adapter.persistence") {
        // 빈 대상: Spring Data Repository가 아직 없다.
        rules.springDataRepositoriesOnlyInPersistenceAdapter().allowEmptyShould(true).check(classes)
    }

    test("Spring Batch types only in adapter.batch") {
        rules.springBatchOnlyInBatchAdapter().allowEmptyShould(false).check(classes)
    }

    test("controllers only in adapter.web") {
        // 빈 대상: Controller가 아직 없다.
        rules.controllersOnlyInWebAdapter().allowEmptyShould(true).check(classes)
    }

    test("jOOQ only in adapter.query") {
        rules.jooqOnlyInQueryAdapter().allowEmptyShould(false).check(classes)
    }

    test("integration adapter types are not used outside adapter.integration") {
        rules.integrationAdapterIsEncapsulated().allowEmptyShould(false).check(classes)
    }

    test("web adapter does not use persistence/query/integration adapters or application.required") {
        // 빈 대상: adapter.web이 아직 없다.
        rules.webAdapterUsesOnlyApplication().allowEmptyShould(true).check(classes)
    }

    test("batch adapter uses only application.provided (no adapters/required ports/Spring Data/JPA)") {
        // 빈 대상: adapter.batch가 아직 없다.
        rules.batchAdapterUsesOnlyApplication().allowEmptyShould(true).check(classes)
    }

    test("@NamedInterface only on application.provided package-info") {
        // 빈 대상: 공개 provided 패키지(package-info)가 아직 없다.
        rules.namedInterfacesOnlyOnProvidedPackages().allowEmptyShould(true).check(classes)
    }

    test("no cycles between modules") {
        // 빈 대상: 기본 패키지 아래 모듈 패키지가 아직 없다.
        rules.modulesAreFreeOfCycles().allowEmptyShould(true).check(classes)
    }
}) {
    companion object {
        const val BASE_PACKAGE = "com.paycore.financial"
    }
}
