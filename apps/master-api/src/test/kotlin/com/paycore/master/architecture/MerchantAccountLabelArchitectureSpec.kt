package com.paycore.master.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * TXN-LIST-ENRICH-001 E-2 T-E2-11(ArchUnit — §5): 라벨 조회 query adapter는 쓰기 문장을 만들지 않는다(DSLContext insert·update·delete·merge·execute·batch 호출 0, 쓰기 금지 —
 * 읽기 전용 TX와 함께 T-E2-6 대조), 라벨 Query(provided)는 ADMIN 채널 컨트롤러와 그 구현 서비스만, 라벨 Reader(required)는 그 서비스와 jOOQ 구현만 쓴다. 컨트롤러는 저장 Port·adapter를
 * 모른다(provided만).
 */
class MerchantAccountLabelArchitectureSpec : FunSpec({

    val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.master")
    val base = "com.paycore.master.merchant"
    val reader = "$base.adapter.query.JooqMerchantAccountLabelReader"
    val writes = setOf("insertInto", "update", "deleteFrom", "delete", "mergeInto", "execute", "batch", "batchInsert", "batchUpdate", "batchStore", "batchDelete", "truncate")

    fun users(target: String): Set<String> = classes.filter { c -> c.directDependenciesFromSelf.any { it.targetClass.name == target } }
        .map { it.name.substringBefore('$') }.filter { it != target }.toSet()

    test("T-E2-11 the label query adapter issues no write statement (no DSLContext write method call)") {
        classes.get(reader).isAnnotatedWith("org.springframework.stereotype.Component") shouldBe true
        classes.filter { it.name.startsWith(reader) }.flatMap { it.methodCallsFromSelf }
            .filter { it.target.owner.name == "org.jooq.DSLContext" && it.target.name in writes }
            .map { "${it.origin.name} -> ${it.target.name}" }
            .shouldBeEmpty()
        // 대조군: 실제로 DSLContext 조회(select)를 부른다(규칙 대상이 비어 통과하지 않는다).
        classes.filter { it.name.startsWith(reader) }.flatMap { it.methodCallsFromSelf }.any { it.target.owner.name == "org.jooq.DSLContext" && it.target.name == "select" } shouldBe true
    }

    test("T-E2-11 the label query (provided) is used only by the admin-channel label controller and its service; the reader port only by that service and the jOOQ adapter") {
        users("$base.application.provided.MerchantAccountLabelQuery") shouldBe setOf(
            "$base.adapter.web.admin.MerchantAccountLabelController", "$base.application.MerchantAccountLabelQueryService",
        )
        users("$base.application.required.MerchantAccountLabelReader") shouldBe setOf("$base.application.MerchantAccountLabelQueryService", reader)
    }

    test("T-E2-11 the label controller depends on no storage port or adapter (provided use case only)") {
        noClasses().that().haveNameMatching("^" + Regex.escape("$base.adapter.web.admin.MerchantAccountLabel") + ".*")
            .should().dependOnClassesThat().resideInAnyPackage("..application.required..", "..adapter.persistence..", "..adapter.query..", "..adapter.integration..")
            .allowEmptyShould(false).check(classes)
    }
})
