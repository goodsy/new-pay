package com.paycore.master.architecture

import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import jakarta.persistence.Column

/**
 * BUSINESS-PROFILE-001 BIZ-1-10(TC-21·ArchUnit)·BIZ-1-6(adapter 측 불변 방어):
 * - 새 Domain 타입(사업자 값 객체·profile·규칙·변경 계산·마스킹)이 `master.merchant.domain`에 있고 Spring·JPA·Hibernate·jOOQ·Jackson에 의존하지 않는다
 *   (일반 규칙 `domainIsPure`의 대상에 실제로 들어 있음을 이름으로 확인 — 대조군).
 * - 진위확인 enum 값은 NOT_CHECKED 하나(VERIFIED를 만드는 경로가 타입 수준에서 없다).
 * - 사업자 하위 행 Entity는 종료 열(effective_to·ended_by)만 `updatable = true`, 공개 setter 없음. business Entity의 `registration_number`는 `updatable = false`(TBD-04).
 * - Domain 전이 메서드에 사업자등록번호 인자가 없다(번호 변경 경로 0 — D-BIZ-005).
 */
class BusinessProfileArchitectureSpec : FunSpec({

    val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.master")
    val domain = "com.paycore.master.merchant.domain"
    val newDomainTypes = listOf(
        "BusinessType", "BusinessRegistrationStatus", "BusinessVerificationStatus", "BusinessRegistration", "BusinessRepresentative", "RepresentativeType",
        "BusinessCategory", "BusinessCategoryType", "BusinessAddress", "BusinessAddressType", "BusinessContact", "BusinessProfile", "BusinessProfileRules",
        "BusinessProfileRuleViolation", "BusinessFieldError", "BusinessFieldErrorCode", "BusinessProfileChange", "BusinessChangeEntry", "BusinessContent",
        "BusinessProfileEdit", "BusinessPiiMask", "CorporateRegistrationNumber", "EnglishBusinessName", "PersonName", "CategoryName", "PostalCode", "AddressLine",
        "PhoneNumber", "EmailAddress", "BusinessChangeReason",
    )

    test("BIZ-1-10 the new business domain types live in the pure domain package and depend on no framework") {
        newDomainTypes.map { classes.contain("$domain.$it") } shouldBe newDomainTypes.map { true }
        noClasses().that().resideInAPackage(domain)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..", "org.jooq..", "tools.jackson..", "com.fasterxml..")
            .check(classes)
    }

    test("TC-21 verification has a single value NOT_CHECKED (no success state can be constructed)") {
        com.paycore.master.merchant.domain.BusinessVerificationStatus.entries.map { it.name } shouldBe listOf("NOT_CHECKED")
    }

    test("BIZ-1-6 child row entities: only effective_to / ended_by are updatable; no public setters; business.registration_number stays updatable = false") {
        val child = listOf("JpaBusinessRepresentativeEntity", "JpaBusinessCategoryEntity", "JpaBusinessAddressEntity", "JpaBusinessContactEntity")
            .map { classes.get("com.paycore.master.merchant.adapter.persistence.$it") }
        child.associate { entity ->
            entity.simpleName to entity.fields.filter { it.isAnnotatedWith(Column::class.java) && it.getAnnotationOfType(Column::class.java).updatable }.map { it.name }.toSet()
        } shouldBe child.associate { it.simpleName to setOf("effectiveTo", "endedBy") }
        child.flatMap { e -> e.methods.filter { it.name.startsWith("set") && JavaModifier.PUBLIC in it.modifiers }.map { "${e.simpleName}.${it.name}" } }.shouldBeEmpty()
        classes.get("com.paycore.master.merchant.adapter.persistence.JpaBusinessEntity").getField("registrationNumber").getAnnotationOfType(Column::class.java).updatable shouldBe false
        classes.get("com.paycore.master.merchant.adapter.persistence.JpaBusinessRegistrationEntity").let { e ->
            listOf("businessId", "countryCode").map { e.getField(it).getAnnotationOfType(Column::class.java).updatable }
        } shouldBe listOf(false, false)
    }

    test("D-BIZ-005 / TBD-04 no Business transition takes a registration number (the number can only be set at creation)") {
        // Kotlin 수준 매개변수(값 클래스는 JVM에서 String으로 지워지므로 Kotlin reflection으로 본다).
        val transitions = com.paycore.master.merchant.domain.Business::class.members.filter { it.name in setOf("updateProfile", "completeRegistration") }
        transitions.map { it.name }.toSet() shouldBe setOf("updateProfile", "completeRegistration") // 대조군
        transitions.flatMap { m -> m.parameters.map { m.name to it.type.classifier } }
            .filter { it.second == com.paycore.master.merchant.domain.BusinessRegistrationNumber::class }.shouldBeEmpty()
    }})
