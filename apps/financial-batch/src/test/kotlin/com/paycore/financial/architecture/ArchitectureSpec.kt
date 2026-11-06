package com.paycore.financial.architecture

import com.paycore.financial.FinancialBatchApplication
import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.ApplicationModules

/**
 * financial-batch 아키텍처 규칙(PayCoreArchitectureRules — 규칙 자체의 동작 증거는 libs/test-support meta-test).
 *
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. FIN-FACT-RELAY-001로 첫 업무 모듈 `paymentfact`(domain·application·adapter.web/persistence)가 생겨
 * 대상이 생긴 규칙은 모두 false로 바꿨다. 아직 대상이 없는 규칙(adapter.batch·공개 provided package-info)만 true다.
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
        // 대상 있음: paymentfact 모듈(FIN-FACT-RELAY-001).
        rules.classesResideInKnownLayers().allowEmptyShould(false).check(classes)
    }

    test("base package root holds only the application entry point") {
        // 대상 있음: FinancialBatchApplication과 Kotlin facade FinancialBatchApplicationKt.
        rules.baseRootHoldsOnlyApplicationEntryPoint().allowEmptyShould(false).check(classes)
    }

    test("domain is pure Kotlin") {
        // 대상 있음: paymentfact.domain(PaymentFact·투영 판정).
        rules.domainIsPure().allowEmptyShould(false).check(classes)
    }

    test("domain/application do not depend on base root or cross-cutting packages") {
        // 대상 있음: paymentfact.domain·application.
        rules.coreDoesNotDependOnBaseRootOrCrossCutting().allowEmptyShould(false).check(classes)
    }

    test("application depends only on ports") {
        // 대상 있음: paymentfact.application(Inbox 수신 Use Case·Port).
        rules.applicationDependsOnlyOnPorts().allowEmptyShould(false).check(classes)
    }

    test("persistence technology (JPA/JDBC/DataSource/Spring Data repository) only in adapter.persistence") {
        // 대상 있음: FinancialBatchApplication(+Kt)·paymentfact. 공통 구성 패키지 없음.
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
        // 대상 있음: paymentfact.adapter.persistence Jpa*Entity.
        rules.jpaMappingsOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("Spring Data repositories only in adapter.persistence") {
        // 대상 있음: paymentfact.adapter.persistence *JpaRepository.
        rules.springDataRepositoriesOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("Spring Batch types only in adapter.batch") {
        rules.springBatchOnlyInBatchAdapter().allowEmptyShould(false).check(classes)
    }

    test("controllers only in adapter.web") {
        // 대상 있음: PaymentFactInboxController.
        rules.controllersOnlyInWebAdapter().allowEmptyShould(false).check(classes)
    }

    test("jOOQ only in adapter.query") {
        rules.jooqOnlyInQueryAdapter().allowEmptyShould(false).check(classes)
    }

    test("integration adapter types are not used outside adapter.integration") {
        rules.integrationAdapterIsEncapsulated().allowEmptyShould(false).check(classes)
    }

    test("web adapter does not use persistence/query/integration adapters or application.required") {
        // 대상 있음: paymentfact.adapter.web.
        rules.webAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    test("batch adapter uses only application.provided (no adapters/required ports/Spring Data/JPA)") {
        // 대상 있음(ACQUIRING-001): acquiring.adapter.batch 매입 제출 Job(provided Use Case만 호출).
        rules.batchAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    test("@NamedInterface only on application.provided package-info") {
        // 대상 있음(ACQUIRING-001): paymentfact.application.provided package-info(acquiring 모듈이 쓰는 공개 API).
        rules.namedInterfacesOnlyOnProvidedPackages().allowEmptyShould(false).check(classes)
    }

    test("no cycles between modules") {
        // 대상 있음: paymentfact·acquiring 두 slice(acquiring → paymentfact.provided 한 방향).
        rules.modulesAreFreeOfCycles().allowEmptyShould(false).check(classes)
    }

    test("ACQUIRING-001 2-D: Apache MINA SSHD (org.apache.sshd) is used only in adapter.integration") {
        // 대상 있음: SftpAcquirerFileChannel·AcquiringTransportSettings. Domain·application·다른 adapter는 기술 중립 Port(AcquirerFileChannel)만 본다.
        noClasses().that().resideOutsideOfPackage("..adapter.integration..").should().dependOnClassesThat().resideInAPackage("org.apache.sshd..")
            .allowEmptyShould(false).check(classes)
        classes.any { c -> c.packageName.endsWith("adapter.integration") && c.directDependenciesFromSelf.any { it.targetClass.packageName.startsWith("org.apache.sshd") } } shouldBe true
    }
}) {
    companion object {
        const val BASE_PACKAGE = "com.paycore.financial"
    }
}
