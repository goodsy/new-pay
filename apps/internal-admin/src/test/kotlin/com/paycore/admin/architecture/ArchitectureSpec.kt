package com.paycore.admin.architecture

import com.paycore.admin.InternalAdminApplication
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
        ApplicationModules.of(InternalAdminApplication::class.java).verify()
    }

    test("internal-admin has no database access dependencies (no DB ownership)") {
        noClasses().that().resideInAPackage("com.paycore.admin..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "javax.sql..",
                "java.sql..",
                "jakarta.persistence..",
                "org.hibernate..",
                "org.jooq..",
                "org.springframework.jdbc..",
            )
            .check(classes)
    }

    test("domain does not depend on Spring, JPA, jOOQ, or Batch") {
        // 아직 domain 패키지가 없어 이 규칙은 빈 대상이다(allowEmptyShould).
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
