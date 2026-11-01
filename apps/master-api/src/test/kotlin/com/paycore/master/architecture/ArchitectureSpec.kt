package com.paycore.master.architecture

import com.paycore.master.MasterApiApplication
import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.ApplicationModules

/**
 * master-api 아키텍처 규칙. PayCoreArchitectureRules의 **모든** 규칙을 적용한다(규칙 자체의 동작 증거는 libs/test-support meta-test).
 *
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. MASTER-DOMAIN-001에서 첫 업무 모듈 `merchant`(domain·application
 * provided/required·adapter.persistence·adapter.query, provided package-info, JPA Entity, Spring Data repository)가 생겨 그 규칙들의
 * 대상이 생겼으므로 false로 바꿨다. 아직 대상이 없는 규칙(web·batch adapter, Controller)만 true로 두고 사유를 적는다.
 * 아래 [ALLOW_EMPTY]의 이름 목록이 rules.all()과 같아야 한다(규칙 누락 방지).
 */
class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    // jOOQ 생성 코드(MASTER V1 업무 schema에서 생성)는 Modulith 모듈이 아니며 adapter.query만 쓴다.
    val rules = PayCoreArchitectureRules(BASE_PACKAGE, listOf(GENERATED_JOOQ_PACKAGE))

    test("every PayCoreArchitectureRules rule is applied here (no rule silently skipped)") {
        ALLOW_EMPTY.keys shouldBe rules.all().keys
    }

    test("Spring Modulith module structure is valid") {
        val modules = ApplicationModules.of(MasterApiApplication::class.java, rules.generatedCode())
        modules.verify()
        // MASTER-DOMAIN-001: 첫 업무 모듈 merchant 하나. 생성 jOOQ 패키지는 모듈로 잡히지 않는다.
        modules.getModuleByName("merchant").isPresent shouldBe true
        modules.stream().count() shouldBe 1L
    }

    test("GENERATED_JOOQ_PACKAGE matches the build's generateJooqMain package and generated tables exist") {
        System.getProperty("paycore.jooq.mainPackage") shouldBe GENERATED_JOOQ_PACKAGE
        classes.any { it.packageName.startsWith("$GENERATED_JOOQ_PACKAGE.tables") } shouldBe true
    }

    test("the base package root holds only the application entry point") {
        classes.filter { it.packageName == BASE_PACKAGE }.map { it.name }.toSet() shouldBe setOf(
            "com.paycore.master.MasterApiApplication",
            "com.paycore.master.MasterApiApplicationKt",
        )
    }

    ALLOW_EMPTY.forEach { (name, allowEmpty) ->
        test("rule $name (allowEmptyShould=$allowEmpty)") {
            rules.all().getValue(name).allowEmptyShould(allowEmpty).check(classes)
        }
    }

    test("DEMO-SEED-001 I-4·I-20: the seed coordinator uses only provided use cases and the read-only progress port") {
        demoSeedUsesOnlyProvidedUseCases().check(classes)
        classes.any { it.simpleName == "DemoMerchantSeedService" } shouldBe true
    }

    test("DEMO-SEED-001 I-4·I-20 rule catches a coordinator that reserves admin requests or touches persistence (violating fixture)") {
        val fixture = ClassFileImporter().importPackages("com.paycore.master.archfixture.demoseed")
        val error = shouldThrow<AssertionError> { demoSeedUsesOnlyProvidedUseCases().check(fixture) }
        error.message shouldContain "reserveOrLock"
        error.message shouldContain "jakarta.persistence"
    }

    test("test sources are excluded from the architecture import (DO_NOT_INCLUDE_TESTS)") {
        // MasterUtcSessionSpec은 DataSource/Hikari를 쓰는 테스트 소스다. 테스트까지 import하면 영속 기술 규칙이 이를 잡는다.
        val withTests = ClassFileImporter().importPackages(BASE_PACKAGE)
        withTests.any { it.simpleName == "MasterUtcSessionSpec" } shouldBe true
        classes.any { it.simpleName == "MasterUtcSessionSpec" } shouldBe false
        val error = shouldThrow<AssertionError> {
            rules.persistenceTechnologyOnlyInPersistenceAdapter().check(withTests)
        }
        error.message shouldContain "MasterUtcSessionSpec"
    }
}) {
    companion object {
        /**
         * DEMO-SEED-001 I-4·I-20: seed 조정자(`DemoMerchantSeedService`)는 기존 provided Use Case와 진행 판정 읽기(`AdminRequestStore.find`)만 쓴다 —
         * 영속 기술·adapter 직접 사용 금지, `reserveOrLock`(PENDING 행 INSERT) 호출 금지.
         */
        fun demoSeedUsesOnlyProvidedUseCases(): com.tngtech.archunit.lang.ArchRule {
            val noPersistence = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveSimpleName("DemoMerchantSeedService")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "jakarta.persistence..", "org.hibernate..", "org.jooq..", "org.springframework.jdbc..", "org.springframework.data..",
                )
            val noReservation = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveSimpleName("DemoMerchantSeedService")
                .should().callMethodWhere(
                    com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.base.DescribedPredicate.describe("AdminRequestStore.reserveOrLock") { t ->
                            t.name == "reserveOrLock" && t.owner.simpleName == "AdminRequestStore"
                        },
                    ),
                )
            return com.tngtech.archunit.lang.CompositeArchRule.of(noPersistence).and(noReservation).allowEmptyShould(false)
        }

        const val BASE_PACKAGE = "com.paycore.master"

        const val GENERATED_JOOQ_PACKAGE = "com.paycore.master.jooq.generated"

        /**
         * 규칙 이름 → allowEmptyShould 값. true(빈 대상)의 사유: Controller·adapter.web(관리자 화면/API는 Q-ADMIN-AUTH 미결이라 만들지 않음)이
         * 없다. 그 밖은 merchant 모듈(MASTER-DOMAIN-001, adapter.batch·integration은 MASTER-SNAPSHOT-001)로 대상이 있다.
         */
        val ALLOW_EMPTY: Map<String, Boolean> = linkedMapOf(
            "classesResideInKnownLayers" to false,
            "baseRootHoldsOnlyApplicationEntryPoint" to false,
            "domainIsPure" to false,
            "coreDoesNotDependOnBaseRootOrCrossCutting" to false,
            "applicationDependsOnlyOnPorts" to false,
            "persistenceTechnologyOnlyInPersistenceAdapter" to false,
            "springBootApplicationIsSingleAtBaseRoot" to false,
            "jpaMappingsOnlyInPersistenceAdapter" to false,
            "springDataRepositoriesOnlyInPersistenceAdapter" to false,
            "springBatchOnlyInBatchAdapter" to false,
            "controllersOnlyInWebAdapter" to true,
            "jooqOnlyInQueryAdapter" to false,
            "queryAdapterDoesNotWriteWithJooq" to false,
            "integrationAdapterIsEncapsulated" to false,
            "webAdapterUsesOnlyApplication" to true,
            // MASTER-SNAPSHOT-001: Relay·lease 갱신 주기 실행(adapter.batch, application.provided만 호출).
            "batchAdapterUsesOnlyApplication" to false,
            "namedInterfacesOnlyOnProvidedPackages" to false,
            "modulesAreFreeOfCycles" to false,
        )
    }
}
