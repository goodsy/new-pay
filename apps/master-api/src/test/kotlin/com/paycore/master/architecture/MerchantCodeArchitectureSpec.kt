package com.paycore.master.architecture

import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import jakarta.persistence.Column

/**
 * MERCHANT-CODE-001 MC-1-7(§3.2·§3.7.4 — ArchUnit): 배정 Entity `JpaMerchantCodeAssignmentEntity`는 `@Immutable`, 모든 열 `updatable = false`, 비공개가 아닌 setter 0,
 * 운영 코드 어디에서도 그 setter를 부르지 않는다(UPDATE 경로 0). Domain에서 코드를 바꾸는 메서드는 0(MC-1 — 유일한 변경 메서드 `changeCodeWithEvidence`는 MC-1b에서만 생긴다):
 * `Merchant`·`MerchantCodeAssignment`의 공개 메서드 집합 고정.
 */
class MerchantCodeArchitectureSpec : FunSpec({

    val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.master")
    val entityName = "com.paycore.master.merchant.adapter.persistence.JpaMerchantCodeAssignmentEntity"

    test("MC-1-7 the assignment entity is @Immutable, every mapped column is updatable = false and it has no public setter (Kotlin JPA classes are open: setters are protected — only Hibernate proxies, never callers)") {
        val entity = classes.get(entityName)
        entity.isAnnotatedWith("org.hibernate.annotations.Immutable") shouldBe true
        val columns = entity.fields.filter { it.isAnnotatedWith(Column::class.java) }
        columns.map { it.name }.toSet() shouldBe setOf(
            "merchantId", "assignmentSeq", "merchantCode", "replacesCode", "source", "approvalId", "reasonCode", "assignedAt", "assignedBy",
        )
        columns.filter { it.getAnnotationOfType(Column::class.java).updatable }.map { it.name }.shouldBeEmpty()
        entity.methods.filter { it.name.startsWith("set") && JavaModifier.PUBLIC in it.modifiers }.map { it.name }.shouldBeEmpty()
    }

    test("MC-1-7 no production class calls a setter of the assignment entity (no UPDATE path)") {
        classes.flatMap { it.methodCallsFromSelf }
            .filter { it.target.owner.name == entityName && it.target.name.startsWith("set") && !it.originOwner.name.startsWith(entityName) }
            .map { "${it.originOwner.simpleName}.${it.origin.name} -> ${it.target.name}" }
            .shouldBeEmpty()
    }

    test("MC-1-7 the domain has no code change method in MC-1 (public methods of Merchant and MerchantCodeAssignment are fixed)") {
        fun publicMethods(name: String) = classes.get(name).methods.filter { JavaModifier.PUBLIC in it.modifiers && !it.name.contains('$') }.map { it.name.substringBefore('-') }.toSet()
        publicMethods("com.paycore.master.merchant.domain.Merchant") shouldBe setOf(
            "getId", "getBusinessId", "getDisplayName", "getLifecycle", "getEntitlementRevision", "getCreatedAt", "getCreatedBy", "getCodeAssignments",
            "getCurrentCode", "requireActive", "recordEntitlementChange", "toString",
        )
        publicMethods("com.paycore.master.merchant.domain.MerchantCodeAssignment") shouldBe setOf(
            "getSeq", "getCode", "getReplacesCode", "getSource", "getApprovalId", "getReasonCode", "getAssignedAt", "getAssignedBy", "toString",
        )
    }
})
