package com.paycore.master.architecture

import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import jakarta.persistence.Column

/**
 * SITE-CONTRACT-CODE-001 §7 JPA 주의 ①·C-12(ArchUnit): 코드 Entity 두 개는 `@Immutable`, 모든 열 `updatable = false`, 공개 setter 0, 운영 코드의 setter 호출 0(UPDATE 경로 0 — runtime 권한도 SELECT, INSERT).
 * 코드는 Aggregate 필드가 아니다: `MerchantSite`·`Contract`에 코드 필드·메서드가 없고, Domain 값 객체 `SiteCode`·`ContractCode`는 Spring·JPA·jOOQ에 의존하지 않는다.
 */
class SiteContractCodeArchitectureSpec : FunSpec({

    val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.master")
    val entities = mapOf(
        "com.paycore.master.merchant.adapter.persistence.JpaMerchantSiteCodeEntity" to setOf("merchantSiteId", "merchantId", "codeSeq", "issuedMerchantCode", "siteCode", "source", "assignedAt", "assignedBy"),
        "com.paycore.master.merchant.adapter.persistence.JpaContractCodeEntity" to setOf("contractId", "merchantId", "codeSeq", "issuedMerchantCode", "contractCode", "source", "assignedAt", "assignedBy"),
    )

    test("C-12 / JPA ① the code entities are @Immutable, every mapped column is updatable = false, no public setter and no production setter call") {
        entities.forEach { (name, columnNames) ->
            withClue(name) {
                val entity = classes.get(name)
                entity.isAnnotatedWith("org.hibernate.annotations.Immutable") shouldBe true
                val columns = entity.fields.filter { it.isAnnotatedWith(Column::class.java) }
                columns.map { it.name }.toSet() shouldBe columnNames
                columns.filter { it.getAnnotationOfType(Column::class.java).updatable }.map { it.name }.shouldBeEmpty()
                entity.methods.filter { it.name.startsWith("set") && JavaModifier.PUBLIC in it.modifiers }.map { it.name }.shouldBeEmpty()
                classes.flatMap { it.methodCallsFromSelf }
                    .filter { it.target.owner.name == name && it.target.name.startsWith("set") && !it.originOwner.name.startsWith(name) }
                    .map { "${it.originOwner.simpleName}.${it.origin.name} -> ${it.target.name}" }
                    .shouldBeEmpty()
            }
        }
    }

    test("C-12 the code is not an Aggregate field: MerchantSite and Contract have no code member; SiteCode / ContractCode depend on no framework") {
        listOf("com.paycore.master.merchant.domain.MerchantSite", "com.paycore.master.merchant.domain.Contract").forEach { name ->
            val c = classes.get(name)
            withClue(name) {
                (c.fields.map { it.name } + c.methods.map { it.name }).filter { it.contains("siteCode", ignoreCase = true) || it.contains("contractCode", ignoreCase = true) || it == "getCode" }.shouldBeEmpty()
            }
        }
        listOf("com.paycore.master.merchant.domain.SiteCode", "com.paycore.master.merchant.domain.ContractCode").forEach { name ->
            withClue(name) {
                classes.get(name).directDependenciesFromSelf.map { it.targetClass.packageName }
                    .filter { it.startsWith("org.springframework") || it.startsWith("jakarta.persistence") || it.startsWith("org.jooq") || it.startsWith("org.hibernate") }
                    .shouldBeEmpty()
            }
        }
    }
})
