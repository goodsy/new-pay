package com.paycore.simulator.architecture

import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * institution-simulator 아키텍처 규칙(AUTHN-001 §17.3 — PayCoreArchitectureRules 공통 규칙을 base `com.paycore.simulator`로 적용; 규칙 자체의 동작 증거는
 * libs/test-support meta-test).
 *
 * allowEmptyShould는 규칙별로 명시한다. S6에서 업무 모듈 `authentication`(domain·application·adapter.web·adapter.persistence·adapter.integration)이 생겨
 * 모든 계층 규칙의 대상이 있다(false). Simulator는 PayCore 앱 코드에 의존하지 않는다(Gradle project 의존 없음은 harness test_simulator_isolation, 패키지
 * 의존 없음은 아래 규칙).
 */
class SimulatorArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    val rules = PayCoreArchitectureRules(BASE_PACKAGE)

    test("the simulator does not depend on PayCore application packages (external institution stand-in)") {
        noClasses().that().resideInAPackage("$BASE_PACKAGE..")
            .should().dependOnClassesThat().resideInAnyPackage("com.paycore.payment..", "com.paycore.master..", "com.paycore.financial..", "com.paycore.admin..")
            .check(classes)
    }

    test("base package root holds only the application entry point") {
        rules.baseRootHoldsOnlyApplicationEntryPoint().allowEmptyShould(false).check(classes)
    }

    test("exactly one @SpringBootApplication, directly in the base package") {
        rules.springBootApplicationIsSingleAtBaseRoot().allowEmptyShould(false).check(classes)
    }

    test("classes in module packages reside in known layers") {
        rules.classesResideInKnownLayers().allowEmptyShould(false).check(classes)
    }

    test("domain is pure Kotlin") {
        rules.domainIsPure().allowEmptyShould(false).check(classes)
    }

    test("application depends only on ports") {
        rules.applicationDependsOnlyOnPorts().allowEmptyShould(false).check(classes)
    }

    test("persistence technology only in adapter.persistence") {
        rules.persistenceTechnologyOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("JPA mappings and Spring Data repositories only in adapter.persistence") {
        rules.jpaMappingsOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
        rules.springDataRepositoriesOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("controllers only in adapter.web") {
        rules.controllersOnlyInWebAdapter().allowEmptyShould(false).check(classes)
    }

    test("web adapter does not use persistence/integration adapters or application.required") {
        rules.webAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    // AUTH-001 §5.3-3·AU-13: 가상 VAN은 인증 근거를 인증 모듈 provided 조회로만 읽는다(같은 JVM·같은 DB여도 모듈 경계 유지). 인증 모듈은 VAN을 모른다.
    test("the van module uses the authentication module only through authentication.application.provided") {
        noClasses().that().resideInAPackage("$BASE_PACKAGE.van..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "$BASE_PACKAGE.authentication.domain..", "$BASE_PACKAGE.authentication.application.required..", "$BASE_PACKAGE.authentication.adapter..",
            )
            .check(classes)
        noClasses().that().resideInAPackage("$BASE_PACKAGE.van..")
            .should().dependOnClassesThat().resideInAPackage("$BASE_PACKAGE.authentication.application")
            .check(classes)
        noClasses().that().resideInAPackage("$BASE_PACKAGE.authentication..")
            .should().dependOnClassesThat().resideInAPackage("$BASE_PACKAGE.van..")
            .check(classes)
    }

    test("the virtual VAN ledger has no update or delete path (append-only store, immutable entity)") {
        val store = classes.get("$BASE_PACKAGE.van.application.required.VanLedgerStore")
        store.methods.map { it.name }.toSet() shouldBe setOf("find", "insert")
        classes.get("$BASE_PACKAGE.van.adapter.persistence.JpaVanLedgerEntryEntity").isAnnotatedWith(org.hibernate.annotations.Immutable::class.java) shouldBe true
    }

    test("the simulator has no REAL institution client and no card-number field (virtual card references only)") {
        classes.filter { it.simpleName.startsWith("Real") }.map { it.name } shouldBe emptyList()
        classes.flatMap { c -> c.fields.map { it.name.lowercase() } }.filter { it.contains("cardnumber") || it == "pan" || it.contains("cvc") } shouldBe emptyList()
    }
}) {
    companion object {
        const val BASE_PACKAGE = "com.paycore.simulator"
    }
}
