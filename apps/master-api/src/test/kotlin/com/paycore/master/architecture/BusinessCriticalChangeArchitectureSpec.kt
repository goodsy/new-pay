package com.paycore.master.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import jakarta.persistence.Column
import org.hibernate.annotations.Immutable

/**
 * BIZ-CRIT-APPROVAL-001 S2(§3.1·§3.8·§7.2 ArchUnit):
 * - `BusinessRepository.update` 호출자 = 1인 경로 `BusinessProfileService`와 근거 실행 `BusinessCriticalChangeService`뿐(그 밖 경로가 사업자 정보를 바꾸지 않는다 — P-10·P-12).
 * - 근거 실행 Use Case(`applyWithEvidence`) 호출자 = 근거 controller(`MerchantApprovalController`) 하나(seed·다른 명령 경로 미사용 — §3.9).
 * - 명령 서비스는 조회 Reader(`BusinessChangeProposalReader`·jOOQ 구현)를 쓰지 않는다(Reader 판정 금지 — 판정은 잠금 뒤 JPA Port).
 * - 제안·1인 근거 Entity는 `@Immutable`이고 모든 열 `updatable = false`(INSERT 전용 — DB 권한도 SELECT·INSERT만).
 */
class BusinessCriticalChangeArchitectureSpec : FunSpec({

    val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.master")
    val base = "com.paycore.master.merchant"
    fun top(c: JavaClass) = c.name.substringBefore('$')

    fun callers(owner: String, method: String): Set<String> = classes.flatMap { c -> c.methodCallsFromSelf.filter { it.targetOwner.name == owner && it.name == method }.map { top(c) } }
        .toSet()

    test("BusinessRepository.update callers are only the 1-person service and the evidence execution service") {
        callers("$base.application.required.BusinessRepository", "update") shouldBe
            setOf("$base.application.BusinessProfileService", "$base.application.BusinessCriticalChangeService")
    }

    test("applyWithEvidence is called only by the approval controller (no seed or other path)") {
        (callers("$base.application.provided.BusinessCriticalChangeUseCase", "applyWithEvidence") + callers("$base.application.BusinessCriticalChangeService", "applyWithEvidence")) shouldBe
            setOf("$base.adapter.web.admin.MerchantApprovalController")
    }

    test("command services never depend on the proposal reader (display only); the query service does (control)") {
        val readerTypes = setOf("$base.application.required.BusinessChangeProposalReader", "$base.adapter.query.JooqBusinessChangeProposalReader")
        fun dependsOnReader(owner: String) = classes.filter { top(it) == owner }.any { c -> c.directDependenciesFromSelf.any { it.targetClass.name in readerTypes } }
        listOf("$base.application.BusinessProfileService", "$base.application.BusinessCriticalChangeService", "$base.application.BusinessCriticalChangeQueryService")
            .map(::dependsOnReader) shouldBe listOf(false, false, true)
    }

    test("proposal and evidence entities are @Immutable with every column updatable = false") {
        listOf("JpaBusinessChangeProposalEntity", "JpaBusinessChangeEvidenceEntity").map { name ->
            val e = classes.get("$base.adapter.persistence.$name")
            e.isAnnotatedWith(Immutable::class.java) to e.fields.filter { it.isAnnotatedWith(Column::class.java) }.map { it.getAnnotationOfType(Column::class.java).updatable }.toSet()
        } shouldBe listOf(true to setOf(false), true to setOf(false))
    }
})
