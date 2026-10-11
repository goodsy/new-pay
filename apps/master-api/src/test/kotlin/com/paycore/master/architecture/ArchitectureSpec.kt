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
        const val BASE_PACKAGE = "com.paycore.master"

        const val GENERATED_JOOQ_PACKAGE = "com.paycore.master.jooq.generated"

        /**
         * 규칙 이름 → allowEmptyShould 값. true(빈 대상)의 사유: Controller·adapter.web(관리자 화면/API는 Q-ADMIN-AUTH 미결이라 만들지 않음)과
         * adapter.batch(계획 없음)가 없다. 그 밖은 merchant 모듈(MASTER-DOMAIN-001)로 대상이 있다.
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
            "batchAdapterUsesOnlyApplication" to true,
            "namedInterfacesOnlyOnProvidedPackages" to false,
            "modulesAreFreeOfCycles" to false,
        )
    }
}
