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
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. 아직 업무 모듈이 없어(MASTER-DOMAIN-001에서 생성) 모듈 패키지가 대상인
 * 규칙은 비어 있다. 대상이 이미 있는 규칙(기반 패키지 root·@SpringBootApplication·영속/jOOQ/Batch/integration 기술 위치)은 false다.
 * 첫 업무 모듈이 생기면 그 규칙을 false로 바꾼다. 아래 [ALLOW_EMPTY]의 이름 목록이 rules.all()과 같아야 한다(규칙 누락 방지).
 */
class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    // jOOQ codegen은 첫 MASTER 테이블과 함께 추가한다(MASTER-DOMAIN-001). 지금은 생성 패키지가 없다.
    val rules = PayCoreArchitectureRules(BASE_PACKAGE)

    test("every PayCoreArchitectureRules rule is applied here (no rule silently skipped)") {
        ALLOW_EMPTY.keys shouldBe rules.all().keys
    }

    test("Spring Modulith module structure is valid") {
        val modules = ApplicationModules.of(MasterApiApplication::class.java, rules.generatedCode())
        modules.verify()
        // 업무 모듈은 MASTER-DOMAIN-001에서 생긴다. 지금은 모듈이 없다는 현재 상태를 고정한다(생기면 이 기대값을 바꾼다).
        modules.count() shouldBe 0
    }

    test("the base package holds the application entry point (rule targets are not empty)") {
        classes.map { it.name }.toSet() shouldBe setOf(
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

        /**
         * 규칙 이름 → allowEmptyShould 값. true(빈 대상)의 사유: 업무 모듈 패키지(domain·application·adapter.*, provided package-info,
         * JPA Entity, Spring Data repository, Controller)가 아직 없다(MASTER-DOMAIN-001 이후 생성).
         * false: 검사 대상이 base package의 진입점(MasterApiApplication + Kotlin facade)으로 이미 존재한다.
         */
        val ALLOW_EMPTY: Map<String, Boolean> = linkedMapOf(
            "classesResideInKnownLayers" to true,
            "baseRootHoldsOnlyApplicationEntryPoint" to false,
            "domainIsPure" to true,
            "coreDoesNotDependOnBaseRootOrCrossCutting" to true,
            "applicationDependsOnlyOnPorts" to true,
            "persistenceTechnologyOnlyInPersistenceAdapter" to false,
            "springBootApplicationIsSingleAtBaseRoot" to false,
            "jpaMappingsOnlyInPersistenceAdapter" to true,
            "springDataRepositoriesOnlyInPersistenceAdapter" to true,
            "springBatchOnlyInBatchAdapter" to false,
            "controllersOnlyInWebAdapter" to true,
            "jooqOnlyInQueryAdapter" to false,
            "queryAdapterDoesNotWriteWithJooq" to true,
            "integrationAdapterIsEncapsulated" to false,
            "webAdapterUsesOnlyApplication" to true,
            "batchAdapterUsesOnlyApplication" to true,
            "namedInterfacesOnlyOnProvidedPackages" to true,
            "modulesAreFreeOfCycles" to true,
        )
    }
}
