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

    // 기대값 변경(MC-1b — 명세 §3.7.4·§8 MC-1b-10): 유일한 변경 메서드 `changeCodeWithEvidence`가 생긴다(근거 없는 변경 메서드는 여전히 0).
    test("MC-1-7 / MC-1b-10 the domain has exactly one code change method, changeCodeWithEvidence (public methods of Merchant and MerchantCodeAssignment are fixed)") {
        fun publicMethods(name: String) = classes.get(name).methods.filter { JavaModifier.PUBLIC in it.modifiers && !it.name.contains('$') }.map { it.name.substringBefore('-') }.toSet()
        publicMethods("com.paycore.master.merchant.domain.Merchant") shouldBe setOf(
            "getId", "getBusinessId", "getDisplayName", "getLifecycle", "getEntitlementRevision", "getCreatedAt", "getCreatedBy", "getCodeAssignments",
            "getCurrentCode", "changeCodeWithEvidence", "requireActive", "recordEntitlementChange", "toString",
        )
        publicMethods("com.paycore.master.merchant.domain.MerchantCodeAssignment") shouldBe setOf(
            "getSeq", "getCode", "getReplacesCode", "getSource", "getApprovalId", "getReasonCode", "getAssignedAt", "getAssignedBy", "toString",
        )
    }

    test("MC-1b-10 changeMerchantCodeWithEvidence is called only by the management channel controller (MerchantApprovalController); the seed never calls it") {
        val callers = classes.flatMap { it.methodCallsFromSelf }
            .filter { it.target.name == "changeMerchantCodeWithEvidence" && it.target.owner.simpleName in setOf("MerchantOnboardingUseCase", "MerchantOnboardingService") }
            .map { it.originOwner.name.substringBefore('$') }.toSet()
        callers shouldBe setOf("com.paycore.master.merchant.adapter.web.admin.MerchantApprovalController")
        classes.filter { it.name.startsWith("com.paycore.master.merchant.application.DemoMerchantSeedService") }
            .flatMap { it.methodCallsFromSelf }.filter { it.target.name.contains("ChangeMerchantCode", ignoreCase = true) || it.target.name.contains("changeCode") }.shouldBeEmpty()
        // 대조군: 규칙 대상 이름이 실제 Use Case에 있다(오타로 빈 집합 통과 금지).
        classes.get("com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase").methods.map { it.name } .contains("changeMerchantCodeWithEvidence") shouldBe true
    }

    test("MC-1b-12 lock-order premise: Contract.approve / approveWithEvidence are called only from MerchantOnboardingService (approveRevision), ContractRepository.update callers are fixed") {
        val approveCallers = classes.flatMap { it.methodCallsFromSelf }
            .filter { it.target.owner.name == "com.paycore.master.merchant.domain.Contract" && it.target.name.substringBefore('-') in setOf("approve", "approveWithEvidence") }
            .map { "${it.originOwner.name.substringBefore('$')}.${it.origin.name.substringBefore('$').substringBefore('-')}" }.toSet()
        // 리뷰 NIT-4: 클래스뿐 아니라 메서드까지 고정 — 두 승인 경로가 approveRevision에 넘기는 lambda(근거 경로 approveWithEvidence, seed 경로 approve) — 그 밖 메서드에서 부르면 RED.
        approveCallers shouldBe APPROVE_CALLERS
        val updateCallers = classes.flatMap { it.methodCallsFromSelf }
            .filter { it.target.owner.name == "com.paycore.master.merchant.application.required.ContractRepository" && it.target.name == "update" }
            .map { "${it.originOwner.simpleName.substringBefore('$')}.${it.origin.name.substringBefore('$').substringBefore('-')}" }.toSet()
        // registerContractRevision·addServiceAgreement는 승인 사실을 만들지 않는다(Contract.approve 호출자는 approveRevision뿐 — 위 단언). 새 호출자가 생기면 RED → 그 경로가 merchant를 먼저 잠그는지 검토한다(§3.7.3 경합 직렬화의 전제).
        updateCallers shouldBe UPDATE_CALLERS
    }
}) {
    private companion object {
        /** 두 승인 경로가 `approveRevision`(merchant → contract 잠금)에 넘기는 판정 lambda — Kotlin은 lambda를 감싼 메서드 이름으로 컴파일한다. */
        val APPROVE_CALLERS = setOf(
            "com.paycore.master.merchant.application.MerchantOnboardingService.approveContractRevision",
            "com.paycore.master.merchant.application.MerchantOnboardingService.approveContractRevisionWithEvidence",
        )
        val UPDATE_CALLERS = setOf(
            "MerchantOnboardingService.registerContractRevision", "MerchantOnboardingService.addServiceAgreement", "MerchantOnboardingService.approveRevision",
        )
    }
}
