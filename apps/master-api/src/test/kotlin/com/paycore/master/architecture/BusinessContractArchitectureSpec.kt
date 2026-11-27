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
 * - ② 사업자 계약·접수 건 **명령 서비스**(사업자 계약 Port를 쓰는 Application 서비스 중 가맹점 등록 서비스 제외)는 `OutboxStore`·`EntitlementSnapshotPublisher`·`Work.record`·
 *   `Work.publishEntitlement`를 부르지 않는다(`recordHistory`만 — M-1). 같은 필터가 기존 Outbox 사용 서비스를 실제로 잡는 대조군으로 공허 통과가 아님을 보인다.
 *   가맹점 등록 서비스(`MerchantOnboardingService`)는 기존 `MerchantRegistered` 이력·Outbox를 그대로 쓰는 서비스라 ②가 아니라 ③이 메서드 단위로 고정한다(FLOW-3).
 * - ③(FLOW-3 — MINOR-H, 리뷰 m-3): 가맹점 등록 경로(`MerchantOnboardingService`·`Merchant`)가 부르는 사업자 계약 메서드는 판정용 읽기·`BusinessContractRepository.latest`·
 *   근거 `MerchantRegistrationBasisStore.insert`뿐이고, 그 서비스 안에서 `latest`·`insert` 호출 원점은 `registerMerchant`(와 그 lambda)뿐이다.
 * - ④(FLOW-3, 리뷰 m-2): `MerchantRegistrationBasis*`(Domain·Port·jOOQ 생성 표/레코드)에 의존하는 곳은 기록 타입 자신·저장 Port·JPA adapter(`JpaMerchantRegistrationBasis*`)·
 *   쓰는 쪽 `MerchantOnboardingService`·표시 Reader `JooqMerchantDetailReader`뿐(판정 코드가 읽지 않는다). jOOQ 생성 `BusinessContract*` 표는 섹션 Reader(`JooqContractIntakeReader`)만 쓴다.
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
        // 리뷰 m-1(FLOW-3): 등록 근거 표 Entity도 INSERT 전용 규칙 대상.
        "JpaMerchantRegistrationBasisEntity",
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
        // 기대값 변경(FLOW-3 §4B.12 — 범위 명확화): ②의 대상은 사업자 계약·접수 건 **명령 서비스**다. 가맹점 등록 서비스(MerchantOnboardingService)는 최신 계약을 읽기만 하고
        // 기존 MerchantRegistered 이력·Outbox를 그대로 쓰므로 ②가 아니라 ③(메서드 호출 단위)이 고정한다.
        forbidden(users(setOf("$base.application.required.BusinessContractRepository")).filterKeys { it != "$base.application.MerchantOnboardingService" }).shouldBeEmpty()
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

    test("FLOW-3 §4B.12 ④ (review m-2): only the record types, their port, their JPA adapter, the registration service and the detail reader depend on MerchantRegistrationBasis types (domain and jOOQ generated)") {
        val generated = "com.paycore.master.jooq.generated"
        fun basisTarget(c: JavaClass) = c.simpleName.startsWith("MerchantRegistrationBasis") && (c.packageName.startsWith(base) || c.packageName.startsWith(generated))
        fun allowed(c: JavaClass) = c.packageName.startsWith(generated) || c.name.substringBefore('$').substringAfterLast('.').let { top ->
            top.startsWith("MerchantRegistrationBasis") || top.startsWith("JpaMerchantRegistrationBasis") || top == "MerchantOnboardingService" || top == "JooqMerchantDetailReader"
        }
        fun dependents(target: (JavaClass) -> Boolean): Set<String> = classes.filter { it.packageName.startsWith("com.paycore.master") && !it.packageName.startsWith(generated) }
            .filter { c -> c.directDependenciesFromSelf.any { target(it.targetClass) && it.targetClass.name.substringBefore('$') != c.name.substringBefore('$') } }
            .map { it.name.substringBefore('$').substringAfterLast('.') }.toSet()
        classes.filter { it.packageName.startsWith("com.paycore.master") && !allowed(it) }
            .flatMap { c -> c.directDependenciesFromSelf.filter { basisTarget(it.targetClass) }.map { "${c.simpleName} -> ${it.targetClass.name}" } }
            .shouldBeEmpty()
        // 대조군(검출력): 허용된 쓰는 곳·읽는 곳이 실제로 의존한다(jOOQ 생성 표 포함).
        dependents(::basisTarget) shouldContainAll setOf("MerchantOnboardingService", "JooqMerchantDetailReader", "JpaMerchantRegistrationBasisStore")
        dependents { it.packageName.startsWith(generated) && it.simpleName.startsWith("MerchantRegistrationBasis") } shouldBe setOf("JooqMerchantDetailReader")
        // jOOQ 생성 BusinessContract* 표는 섹션 Reader만(판정은 잠금 뒤 JPA Port — §4B.8).
        dependents { it.packageName.startsWith(generated) && it.simpleName.startsWith("BusinessContract") } shouldBe setOf("JooqContractIntakeReader")
    }

    test("FLOW-3 §4B.12 ③ (MINOR-H — method calls): the registration path calls only read methods of BusinessContract, latest() of the port and insert() of the basis store") {
        val path = listOf("$base.application.MerchantOnboardingService", "$base.domain.Merchant")
        val calls = path.flatMap { name ->
            classes.filter { it.name == name || it.name.startsWith("$name$") }.flatMap { c ->
                c.methodCallsFromSelf.filter { isContractType(it.targetOwner) || it.targetOwner.simpleName.endsWith("BusinessContractRepository") }
                    .map { "${it.targetOwner.simpleName}.${it.name}" }
            }
        }.toSet()
        val allowedReads = setOf("statusAt", "applyingVersionAt", "judgedAt", "hasContractAt", "getId", "getVersions", "getServices", "getVersionNo", "getBusinessId", "getContractSeq")
        calls.filterNot { call ->
            val (owner, method) = call.split('.')
            (owner.startsWith("BusinessContract") && !owner.endsWith("Repository") && (allowedReads.any { method == it || method.startsWith("$it-") } || method.startsWith("component") || method.startsWith("box-") || method.startsWith("unbox"))) ||
                call.startsWith("BusinessContractRepository.latest") || owner.startsWith("MerchantRegistrationBasis")
        }.shouldBeEmpty()
        // 대조군: 실제로 관문 판정 호출이 있다(공허 통과 방지).
        (calls.any { it.endsWith(".statusAt") } && calls.any { it.startsWith("BusinessContractRepository.latest") }) shouldBe true
        classes.get("$base.application.MerchantOnboardingService").directDependenciesFromSelf.map { it.targetClass.simpleName }.toSet() shouldContainAll
            setOf("BusinessContractRepository", "MerchantRegistrationBasisStore")
        // 리뷰 m-3: 서비스 안에서 latest·insert 호출 원점은 registerMerchant(와 그 lambda — Kotlin은 `registerMerchant$lambda…` 합성 메서드)뿐.
        val origins = classes.filter { it.name == "$base.application.MerchantOnboardingService" || it.name.startsWith("$base.application.MerchantOnboardingService$") }
            .flatMap { c ->
                c.methodCallsFromSelf.filter {
                    (it.targetOwner.simpleName == "BusinessContractRepository" && (it.name == "latest" || it.name.startsWith("latest-"))) || (it.targetOwner.simpleName == "MerchantRegistrationBasisStore" && it.name == "insert")
                }.map { "${it.targetOwner.simpleName}.${it.name.substringBefore('-')}<-${it.origin.name}" }
            }.toSet()
        origins.map { it.substringBefore("<-") }.toSet() shouldBe setOf("BusinessContractRepository.latest", "MerchantRegistrationBasisStore.insert")
        origins.filterNot { Regex("^registerMerchant(\\$.*)?$").matches(it.substringAfter("<-")) }.shouldBeEmpty()
        // 저장소 전체에서 근거 insert를 부르는 곳은 이 서비스뿐(adapter 구현 자신 제외).
        classes.filter { it.packageName.startsWith(base) }.flatMap { c ->
            c.methodCallsFromSelf.filter { it.targetOwner.simpleName.endsWith("MerchantRegistrationBasisStore") && it.name == "insert" }.map { c.name.substringBefore('$').substringAfterLast('.') }
        }.toSet() shouldBe setOf("MerchantOnboardingService")
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
