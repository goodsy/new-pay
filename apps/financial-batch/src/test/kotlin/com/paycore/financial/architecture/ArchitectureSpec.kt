package com.paycore.financial.architecture

import com.paycore.financial.FinancialBatchApplication
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import org.springframework.modulith.core.ApplicationModules

class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.paycore")

    test("Spring Modulith module structure is valid") {
        ApplicationModules.of(FinancialBatchApplication::class.java).verify()
    }

    test("domain does not depend on Spring, JPA, jOOQ, or Batch") {
        // 아직 domain 패키지가 없어 이 규칙은 빈 대상이다(allowEmptyShould). 금융 Domain 작업에서 실제 대상이 생긴다.
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "org.hibernate..",
                "org.jooq..",
            )
            .allowEmptyShould(true)
            .check(classes)
    }
})
