package com.paycore.master.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import jakarta.persistence.Column
import org.hibernate.annotations.Immutable

/**
 * MERCHANT-FLOW-001 FLOW-1(§4A.14 Financial Consistency·Auditability — ArchUnit, 리뷰 n-7·3차 MAJOR-2, §4A.15 구조):
 * - 접수 건 Domain 타입이 순수 Domain 패키지에 있고 Spring·JPA·Hibernate·jOOQ·Jackson에 의존하지 않는다(JPA Entity는 adapter.persistence에만 — 분리).
 * - ① 권리 관련 타입(`Entitlement*`)은 접수 건 타입(`ContractIntake*`)에 의존하지 않는다(접수 건 → 권리·Snapshot 경로 없음).
 * - ② 접수 건 Port를 쓰는 Application 서비스는 `OutboxStore`·`EntitlementSnapshotPublisher`·`Work.record`·`Work.publishEntitlement`를 부르지 않는다(이력은 FLOW-2 `recordHistory`만 — M-1).
 *   FLOW-1에는 그런 서비스가 아직 없다(명령은 FLOW-2) — 규칙은 지금부터 고정해 FLOW-2 서비스에 그대로 적용된다.
 *   범위는 `application`과 하위 패키지. 대상이 0개라 공허하게 통과하지 않음을 대조군(같은 필터가 기존 Outbox·`publishEntitlement` 사용 서비스의 위반을 잡음)으로 보인다(리뷰 MINOR-2).
 * - ③ 가맹점 등록 경로(`MerchantOnboardingService`·`Merchant`)는 FLOW-1에서 접수 건 타입을 전혀 참조하지 않는다(관문은 FLOW-3 — 그때 `ContractIntakeRepository.latestStatus`와
 *   상태 enum만 허용하도록 이 규칙을 좁힌다).
 * - Entity: 전이 이력 Entity는 `@Immutable`·모든 열 `updatable = false`, 접수 건 Entity는 상태·갱신 시각·행위자·version + **접수 항목 5열**(FLOW-2d 열린 건 접수 항목 수정 —
 *   D-FLOW-007 ④·D-FLOW-009 ①)만 수정 가능(종류·대상·이관 표시·소속·순번·생성 값은 수정 불가), 공개 setter 없음.
 */
class ContractIntakeArchitectureSpec : FunSpec({

    val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.master")
    val base = "com.paycore.master.merchant"
    val domainTypes = listOf(
        "ContractIntake", "ContractIntakeStatus", "ContractIntakeChannel", "ContractIntakeItems", "ContractIntakeTransition", "ContractIntakeChange", "DesiredService",
    )
    fun isIntakeType(c: JavaClass) = c.packageName.startsWith(base) && (c.simpleName.startsWith("ContractIntake") || c.simpleName == "DesiredService" ||
        c.simpleName.startsWith("JpaContractIntake") || c.simpleName.startsWith("JooqContractIntake"))

    test("FLOW-1 §4A.15 the intake domain types are in the pure domain package; JPA entities only in adapter.persistence") {
        domainTypes.map { classes.contain("$base.domain.$it") } shouldBe domainTypes.map { true }
        noClasses().that().resideInAPackage("$base.domain")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..", "org.jooq..", "tools.jackson..", "com.fasterxml..")
            .check(classes)
        listOf("JpaContractIntakeEntity", "JpaContractIntakeTransitionEntity", "JpaContractIntakeRepository", "JpaContractIntakeTransitionStore")
            .map { classes.get("$base.adapter.persistence.$it").packageName } shouldBe List(4) { "$base.adapter.persistence" }
        classes.get("$base.adapter.query.JooqContractIntakeReader").packageName shouldBe "$base.adapter.query"
    }

    test("FLOW-1 §4A.14 ① entitlement types never depend on contract-intake types (no path from an intake to rights or a snapshot)") {
        val violations = classes.filter { it.packageName.startsWith(base) && it.simpleName.startsWith("Entitlement") }
            .flatMap { c -> c.directDependenciesFromSelf.filter { isIntakeType(it.targetClass) }.map { "${c.simpleName} -> ${it.targetClass.simpleName}" } }
        violations.shouldBeEmpty()
        // 대조군: 규칙 대상(권리 타입)이 실제로 있다.
        (classes.count { it.packageName.startsWith(base) && it.simpleName.startsWith("Entitlement") } > 0) shouldBe true
    }

    test("FLOW-1 §4A.14 ② application services using the intake ports call neither the Outbox, the snapshot publisher, Work.record nor Work.publishEntitlement") {
        // 범위: `$base.application`과 그 하위 패키지(리뷰 MINOR-2 — 정확 일치는 하위 패키지 서비스를 빠뜨린다). 중첩·익명 클래스(`Outer$x`)는 바깥 클래스 단위로 묶는다.
        fun inApplication(c: JavaClass) = c.packageName == "$base.application" || c.packageName.startsWith("$base.application.")
        fun topLevel(c: JavaClass) = c.name.substringBefore('$')
        fun users(ports: Set<String>): Map<String, List<JavaClass>> = classes.filter(::inApplication).groupBy(::topLevel)
            .filterValues { group -> group.any { c -> c.directDependenciesFromSelf.any { it.targetClass.fullName in ports } } }
        fun forbidden(users: Map<String, List<JavaClass>>): List<String> = users.flatMap { (owner, group) ->
            val name = owner.substringAfterLast('.')
            group.flatMap { c ->
                c.directDependenciesFromSelf.filter { it.targetClass.simpleName in setOf("OutboxStore", "EntitlementSnapshotPublisher") }.map { "$name -> ${it.targetClass.simpleName}" } +
                    c.methodCallsFromSelf.filter { it.targetOwner.simpleName == "Work" && it.name in setOf("record", "publishEntitlement") }.map { "$name calls Work.${it.name}" }
            }.distinct()
        }

        val intakePorts = setOf("$base.application.required.ContractIntakeRepository", "$base.application.required.ContractIntakeTransitionStore")
        forbidden(users(intakePorts)).shouldBeEmpty()

        // 대조군 1(범위): 필터가 하위 패키지 클래스를 포함한다(정확 일치였다면 provided·required 아래 타입이 빠진다).
        classes.filter(::inApplication).map { it.packageName }.toSet() shouldContainAll setOf("$base.application", "$base.application.provided", "$base.application.required")
        // 대조군 2(검출력): 같은 필터·같은 위반 판정을 기존 Outbox 사용 서비스에 적용하면 실제로 위반을 잡는다 — 대상이 생기면 규칙이 공허하게 통과하지 않는다.
        val outboxUsers = users(setOf("$base.application.required.OutboxStore"))
        outboxUsers.keys shouldContainAll setOf("$base.application.AdminCommandExecutor", "$base.application.BusinessProfileService", "$base.application.MerchantEntitlementService")
        forbidden(outboxUsers) shouldContainAll listOf("AdminCommandExecutor -> OutboxStore", "BusinessProfileService calls Work.record", "MerchantEntitlementService -> OutboxStore")
        forbidden(users(setOf("$base.application.AdminCommandExecutor"))).any { it.endsWith("calls Work.publishEntitlement") } shouldBe true
    }

    test("FLOW-1 §4A.14 ③ the merchant registration path does not reference contract-intake types before the FLOW-3 gate") {
        listOf("$base.application.MerchantOnboardingService", "$base.domain.Merchant").associateWith { name ->
            classes.get(name).directDependenciesFromSelf.filter { isIntakeType(it.targetClass) }.map { it.targetClass.simpleName }.toSet()
        } shouldBe mapOf("$base.application.MerchantOnboardingService" to emptySet(), "$base.domain.Merchant" to emptySet())
    }

    test("FLOW-1 §4A.15 + FLOW-2d entities: the history entity is @Immutable with no updatable column; the intake entity updates only status / updated_* / version and the 5 item columns (kind, target, migrated, key and creation columns stay fixed); no public setters") {
        val history = classes.get("$base.adapter.persistence.JpaContractIntakeTransitionEntity")
        history.isAnnotatedWith(Immutable::class.java) shouldBe true
        val intake = classes.get("$base.adapter.persistence.JpaContractIntakeEntity")
        fun updatable(e: JavaClass) = e.fields.filter { it.isAnnotatedWith(Column::class.java) && it.getAnnotationOfType(Column::class.java).updatable }.map { it.name }.toSet()
        updatable(history) shouldBe emptySet()
        // 기대값 변경(MERCHANT-FLOW-001 FLOW-2d — §4A.3 D-FLOW-007 ④·D-FLOW-009 ① 열린 건 접수 항목 수정, §4B.10 "kind·target_contract_seq는 updatable = false — 전이·항목 수정
        // UPDATE에 들어가지 않게"): 접수 항목 5열이 수정 가능으로 열렸다. 종류·대상·이관 표시·소속·순번·생성 값은 여전히 수정 불가(아래 단언).
        updatable(intake) shouldBe setOf("status", "updatedAt", "updatedBy", "version", "receivedOn", "intakeChannel", "contractManager", "desiresCardAuth", "desiresCardKeyin")
        listOf("kind", "targetContractSeq", "migrated", "businessId", "intakeSeq", "createdAt", "createdBy").forEach { intake.getField(it).getAnnotationOfType(Column::class.java).updatable shouldBe false }
        listOf(history, intake).flatMap { e -> e.methods.filter { it.name.startsWith("set") && JavaModifier.PUBLIC in it.modifiers }.map { "${e.simpleName}.${it.name}" } }
            .shouldBeEmpty()
    }
})
