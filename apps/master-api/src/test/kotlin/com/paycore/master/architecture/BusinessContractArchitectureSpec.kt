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
 * MERCHANT-FLOW-001 FLOW-2a(§4B.12 Financial Consistency — ArchUnit ①②④, §4B.13 `BusinessContractArchitectureSpec`, §4A.15 구조):
 * - 사업자 계약 Domain 타입은 순수 Domain 패키지(Spring·JPA·Hibernate·jOOQ·Jackson 비의존), JPA Entity·저장 구현은 adapter.persistence, Port는 application.required
 *   (ARCHITECTURE-CONVENTIONS §14 — Aggregate 저장 Port `BusinessContractRepository` → `JpaBusinessContractRepository`).
 * - ① 권리 타입(`Entitlement*` — Snapshot 조립·발행 포함)은 `BusinessContract*`·`MerchantRegistrationBasis*`에 의존하지 않는다(사업자 계약 → 권리·Snapshot 경로 없음 — BR-BIZ-313).
 *   payment-api는 master-api와 Gradle project 의존이 없어 이 타입을 볼 수 없다(모듈 경계 — 별도 시험 불필요).
 * - ② 사업자 계약 Port를 쓰는 Application 서비스는 `OutboxStore`·`EntitlementSnapshotPublisher`·`Work.record`·`Work.publishEntitlement`를 부르지 않는다(`recordHistory`만 — M-1).
 *   2a에는 그런 서비스가 아직 없다(명령은 2b·2c) — 규칙을 지금 고정하고, 같은 필터가 기존 Outbox 사용 서비스를 실제로 잡는 대조군으로 공허 통과가 아님을 보인다.
 * - ④ `MerchantRegistrationBasis*`(FLOW-3 근거 기록)를 판정 코드가 읽지 않는다 — 의존을 허용하는 곳은 그 기록의 저장 Port·Reader·adapter뿐(2a에는 타입이 없어 대상 0 — FLOW-3이 대상 생김을 확인).
 *   ③(가맹점 등록 경로의 메서드 호출 단위 규칙 — MINOR-H)은 관문 배치 FLOW-3에서 둔다.
 * - Entity: 새 표 Entity는 모두 `@Immutable`·updatable 열 없음·공개 setter 없음(INSERT 전용 — V22 권한과 이중 방어).
 */
class BusinessContractArchitectureSpec : FunSpec({

    val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.master")
    val base = "com.paycore.master.merchant"
    val domainTypes = listOf(
        "BusinessContract", "BusinessContractId", "BusinessContractVersion", "BusinessContractVersionClose", "BusinessContractEvent", "BusinessContractEventKind",
        "BusinessContractStatus", "BusinessContractVersionPhase", "BusinessContractChange", "BusinessContractTerms", "ContractIntakeKind", "ContractDocumentRef",
        "ContractIntakeTransitionInput", "ContractIntakeCompletion",
    )
    val entities = listOf(
        "JpaBusinessContractEntity", "JpaBusinessContractVersionEntity", "JpaBusinessContractVersionServiceEntity", "JpaBusinessContractVersionCloseEntity",
        "JpaBusinessContractEventEntity",
    )

    fun isContractType(c: JavaClass) = c.packageName.startsWith(base) &&
        (c.simpleName.startsWith("BusinessContract") || c.simpleName.startsWith("JpaBusinessContract") || c.simpleName.startsWith("MerchantRegistrationBasis"))

    test("FLOW-2a §4B.2 the business contract domain types are pure domain; port in application.required, JPA entities and repository in adapter.persistence") {
        domainTypes.map { classes.contain("$base.domain.$it") } shouldBe domainTypes.map { true }
        noClasses().that().resideInAPackage("$base.domain")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..", "org.jooq..", "tools.jackson..", "com.fasterxml..")
            .check(classes)
        classes.get("$base.application.required.BusinessContractRepository").isInterface shouldBe true
        (entities + "JpaBusinessContractRepository").map { classes.get("$base.adapter.persistence.$it").packageName } shouldBe List(entities.size + 1) { "$base.adapter.persistence" }
        classes.get("$base.adapter.persistence.JpaBusinessContractRepository").rawInterfaces.map { it.simpleName } shouldBe listOf("BusinessContractRepository")
    }

    test("FLOW-2a §4B.12 ① entitlement types never depend on business-contract / registration-basis types") {
        val entitlement = classes.filter { it.packageName.startsWith(base) && it.simpleName.startsWith("Entitlement") }
        entitlement.flatMap { c -> c.directDependenciesFromSelf.filter { isContractType(it.targetClass) }.map { "${c.simpleName} -> ${it.targetClass.simpleName}" } }
            .shouldBeEmpty()
        (entitlement.size > 0) shouldBe true // 대조군: 규칙 대상이 실제로 있다
        // 접수 건·사업자 계약 Domain도 권리 타입을 부르지 않는다(반대 방향).
        classes.filter { it.packageName == "$base.domain" && (isContractType(it) || it.simpleName.startsWith("ContractIntake")) }
            .flatMap { c -> c.directDependenciesFromSelf.filter { it.targetClass.simpleName.startsWith("Entitlement") }.map { "${c.simpleName} -> ${it.targetClass.simpleName}" } }
            .shouldBeEmpty()
    }

    test("FLOW-2a §4B.12 ② application services using the business contract port call neither the Outbox, the snapshot publisher, Work.record nor Work.publishEntitlement") {
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
        forbidden(users(setOf("$base.application.required.BusinessContractRepository"))).shouldBeEmpty()
        // 대조군(검출력): 같은 필터가 기존 Outbox 사용 서비스의 위반을 잡는다.
        forbidden(users(setOf("$base.application.required.OutboxStore"))) shouldContainAll listOf("AdminCommandExecutor -> OutboxStore", "BusinessProfileService calls Work.record")
    }

    test("FLOW-2b review NIT-2: command services never read the section reader (Reader results are display only — judgement uses the locked JPA ports)") {
        fun inApplication(c: JavaClass) = c.packageName == "$base.application" || c.packageName.startsWith("$base.application.")
        val commandPorts = setOf("$base.application.required.ContractIntakeRepository", "$base.application.required.BusinessContractRepository")
        val readerTypes = setOf(
            "$base.application.required.ContractIntakeReader", "$base.application.required.ContractIntakeSection", "$base.adapter.query.JooqContractIntakeReader",
        )
        val byOwner = classes.filter(::inApplication).groupBy { it.name.substringBefore('$') }
        fun dependsOn(group: List<JavaClass>, targets: Set<String>) = group.any { c -> c.directDependenciesFromSelf.any { it.targetClass.fullName in targets } }
        val commandSide = byOwner.filterValues { dependsOn(it, commandPorts) }
        commandSide.keys shouldContainAll setOf("$base.application.ContractIntakeCommandService")
        commandSide.filterValues { dependsOn(it, readerTypes) }.keys.shouldBeEmpty()
        // 대조군(검출력): 같은 판정이 Reader를 쓰는 조회 서비스를 실제로 잡는다.
        byOwner.filterValues { dependsOn(it, readerTypes) }.keys shouldContainAll setOf("$base.application.BusinessProfileQueryService")
    }

    test("FLOW-2a §4B.12 ④ no code outside the registration-basis store / reader / persistence adapter depends on MerchantRegistrationBasis types") {
        fun allowed(c: JavaClass) = c.simpleName.startsWith("MerchantRegistrationBasis") || c.simpleName.contains("RegistrationBasisStore") ||
            c.simpleName.contains("RegistrationBasisReader") || c.packageName.startsWith("$base.adapter.persistence") || c.packageName.startsWith("$base.adapter.query")
        classes.filter { it.packageName.startsWith(base) && !allowed(it) }
            .flatMap { c -> c.directDependenciesFromSelf.filter { it.targetClass.simpleName.startsWith("MerchantRegistrationBasis") }.map { "${c.simpleName} -> ${it.targetClass.simpleName}" } }
            .shouldBeEmpty()
    }

    test("FLOW-2a §4B.12 INSERT-only entities: @Immutable, no updatable column, no public setter, no @Version; generated keys not mapped") {
        val loaded = entities.map { classes.get("$base.adapter.persistence.$it") }
        loaded.filterNot { it.isAnnotatedWith(Immutable::class.java) }.map { it.simpleName }.shouldBeEmpty()
        loaded.flatMap { e -> e.fields.filter { it.isAnnotatedWith(Column::class.java) && it.getAnnotationOfType(Column::class.java).updatable }.map { "${e.simpleName}.${it.name}" } }
            .shouldBeEmpty()
        loaded.flatMap { e -> e.methods.filter { it.name.startsWith("set") && JavaModifier.PUBLIC in it.modifiers }.map { "${e.simpleName}.${it.name}" } }.shouldBeEmpty()
        loaded.flatMap { e -> e.fields.filter { it.isAnnotatedWith(jakarta.persistence.Version::class.java) }.map { "${e.simpleName}.${it.name}" } }.shouldBeEmpty()
        loaded.flatMap { e -> e.fields.map { it.name } }.filter { it in setOf("terminateKey", "openKey", "activeKey") }.shouldBeEmpty()
    }
})
