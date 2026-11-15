package com.paycore.master.architecture

import com.paycore.master.MasterApiApplication
import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.ApplicationModules

/**
 * master-api 아키텍처 규칙. PayCoreArchitectureRules의 **모든** 규칙을 적용한다(규칙 자체의 동작 증거는 libs/test-support meta-test).
 *
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. MASTER-DOMAIN-001에서 첫 업무 모듈 `merchant`(domain·application
 * provided/required·adapter.persistence·adapter.query, provided package-info, JPA Entity, Spring Data repository)가 생겨 그 규칙들의
 * 대상이 생겼으므로 false로 바꿨다. 아직 대상이 없는 규칙(web·batch adapter, Controller)만 true로 두고 사유를 적는다.
 * 아래 [ALLOW_EMPTY]의 이름 목록이 rules.all()과 같아야 한다(규칙 누락 방지).
 */
class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    // jOOQ 생성 코드(MASTER V1 업무 schema에서 생성)는 Modulith 모듈이 아니며 adapter.query만 쓴다.
    val rules = PayCoreArchitectureRules(BASE_PACKAGE, listOf(GENERATED_JOOQ_PACKAGE))

    test("every PayCoreArchitectureRules rule is applied here (no rule silently skipped)") {
        ALLOW_EMPTY.keys shouldBe rules.all().keys
    }

    test("Spring Modulith module structure is valid") {
        val modules = ApplicationModules.of(MasterApiApplication::class.java, rules.generatedCode())
        modules.verify()
        // MASTER-DOMAIN-001: 첫 업무 모듈 merchant 하나. 생성 jOOQ 패키지는 모듈로 잡히지 않는다.
        modules.getModuleByName("merchant").isPresent shouldBe true
        modules.stream().count() shouldBe 1L
    }

    test("GENERATED_JOOQ_PACKAGE matches the build's generateJooqMain package and generated tables exist") {
        System.getProperty("paycore.jooq.mainPackage") shouldBe GENERATED_JOOQ_PACKAGE
        classes.any { it.packageName.startsWith("$GENERATED_JOOQ_PACKAGE.tables") } shouldBe true
    }

    test("the base package root holds only the application entry point") {
        classes.filter { it.packageName == BASE_PACKAGE }.map { it.name }.toSet() shouldBe setOf(
            "com.paycore.master.MasterApiApplication",
            "com.paycore.master.MasterApiApplicationKt",
        )
    }

    ALLOW_EMPTY.forEach { (name, allowEmpty) ->
        test("rule $name (allowEmptyShould=$allowEmpty)") {
            rules.all().getValue(name).allowEmptyShould(allowEmpty).check(classes)
        }
    }

    test("MERCHANT-OPS-002 §8: the command controller calls only the provided onboarding use case — no storage port, adapter or service implementation") {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveSimpleName("MerchantOperationsController")
            .should().dependOnClassesThat().resideInAnyPackage("..application.required..", "..adapter.persistence..", "..adapter.query..", "..adapter.integration..")
            .orShould().dependOnClassesThat().haveSimpleName("MerchantOnboardingService")
            .allowEmptyShould(false)
            .check(classes)
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that().haveSimpleName("MerchantOperationsController")
            .should().dependOnClassesThat().haveSimpleName("MerchantOnboardingUseCase")
            .allowEmptyShould(false)
            .check(classes)
    }

    test("MERCHANT-OPS-002 §8.2 B2-2-5 / C-26 (D-MOPS-008 ②): only DemoMerchantSeedService calls the evidence-less approval and first-MID issuance") {
        evidenceLessCallersAreOnlyTheSeed().check(classes)
        // 대조군(규칙이 빈 대상으로 통과하지 않음): seed 조정자(내부 클래스·lambda 포함)는 실제로 두 메서드를 부른다.
        callsFrom(classes, "com.paycore.master.merchant.application.DemoMerchantSeedService").filter { EVIDENCE_LESS.test(it.target) }.map { it.target.name }.toSet() shouldBe
            // 기대값 변경(B3-1, C-40 — D-MOPS-008 ② 적용): 근거 없는 2인 개통 승인·Site 심사 결정도 seed 전용(seed가 실제로 부른다).
            setOf("approveContractRevision", "issueFirstDefaultMerchantAccount", "approvePaymentMethodActivation", "decideSiteReview")
    }

    test("MERCHANT-OPS-002 C-26 rule catches other callers (control: test fixtures that prepare data through the seed path are flagged when imported)") {
        val withTests = ClassFileImporter().importPackages("com.paycore.master.merchant.application")
        val error = shouldThrow<AssertionError> { evidenceLessCallersAreOnlyTheSeed().check(withTests) }
        error.message shouldContain "OnboardingFixture"
    }

    test("MERCHANT-OPS-002 §9.4 B2-2: the approval controller calls only the evidence command and the decision use case — no storage port, adapter or service implementation") {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveSimpleName("MerchantApprovalController")
            .should().dependOnClassesThat().resideInAnyPackage("..application.required..", "..adapter.persistence..", "..adapter.query..", "..adapter.integration..")
            .orShould().dependOnClassesThat().haveSimpleName("MerchantOnboardingService")
            .orShould().dependOnClassesThat().haveSimpleName("ApprovalDecisionService")
            .allowEmptyShould(false)
            .check(classes)
        // 대조군: 컨트롤러(lambda 포함)는 근거 있는 명령과 결정 조회 Use Case를 실제로 부른다.
        callsFrom(classes, "com.paycore.master.merchant.adapter.web.admin.MerchantApprovalController")
            .filter { it.target.owner.simpleName in setOf("MerchantOnboardingUseCase", "ApprovalDecisionUseCase") }.map { it.target.name }.toSet() shouldBe
            // 기대값 변경(B2-3): 근거 있는 첫 MID 발급 명령을 더한다(근거 없는 두 메서드는 여전히 호출하지 않는다).
            // 기대값 변경(MERCHANT-CODE-001 MC-1b-10): + 근거 있는 코드 변경 `changeMerchantCodeWithEvidence`(근거 없는 변경 경로는 없다 — C-11).
            setOf("approveContractRevisionWithEvidence", "issueFirstDefaultMerchantAccountWithEvidence", "changeMerchantCodeWithEvidence", "decision", "confirmDeadline")
    }

    test("MERCHANT-OPS-002 §8.3 B3-1-3: the activation controller calls only evidence commands, 1-person registrations and rejectSiteReview — no storage port or service") {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveSimpleName("MerchantActivationController")
            .should().dependOnClassesThat().resideInAnyPackage("..application.required..", "..adapter.persistence..", "..adapter.query..", "..adapter.integration..")
            .orShould().dependOnClassesThat().haveSimpleName("MerchantEntitlementService")
            .orShould().dependOnClassesThat().haveSimpleName("MerchantOnboardingService")
            .allowEmptyShould(false)
            .check(classes)
        callsFrom(classes, "com.paycore.master.merchant.adapter.web.admin.MerchantActivationController")
            .filter { it.target.owner.simpleName in setOf("MerchantOnboardingUseCase", "MerchantEntitlementUseCase") }.map { it.target.name }.toSet() shouldBe
            // 기대값 변경(B3-2, 명세 §8.4 B3-2-8): + 근거 있는 `enableCurrencyWithEvidence`(근거 없는 `enableCurrency`는 여전히 seed 전용 — B3-1F-10 규칙).
            setOf(
                "registerPaymentMethodActivation", "rejectSiteReview", "approvePaymentMethodActivationWithEvidence", "approveSiteReviewWithEvidence",
                "enableCurrencyWithEvidence",
            )
    }

    test("MERCHANT-OPS-002 §8.4 B3-1F-10 (m-6): only DemoMerchantSeedService calls any of the seven evidence-less 2-person entitlement/route methods; no admin web class calls them") {
        // 기존 C-26 규칙(B2 둘 + B3-1 둘)과 별개로 B3-1F-10 목록 7개 전부를 덮는다(B3-2·B3-3 컨트롤러 연결 전 재실행 대상).
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveNameNotMatching(SEED_COORDINATOR_NAMES)
            .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(EVIDENCE_LESS_ENTITLEMENT_SEVEN))
            .allowEmptyShould(false)
            .check(classes)
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().resideInAPackage("com.paycore.master.merchant.adapter.web..")
            .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(EVIDENCE_LESS_ENTITLEMENT_SEVEN))
            .orShould().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(EVIDENCE_LESS))
            .allowEmptyShould(false)
            .check(classes)
        // 대조군(규칙이 빈 대상으로 통과하지 않음): seed 조정자는 7개 중 지금 연결된 넷을 실제로 부른다(confirmSiteActivation·resume 둘은 아직 호출자 없음).
        callsFrom(classes, "com.paycore.master.merchant.application.DemoMerchantSeedService").filter { EVIDENCE_LESS_ENTITLEMENT_SEVEN.test(it.target) }.map { it.target.name }.toSet() shouldBe
            setOf("approvePaymentMethodActivation", "decideSiteReview", "enableCurrency", "approveCardRouteActivation")
        // 대조군: 7개 이름이 실제 provided 포트에 모두 있다(이름 오타로 규칙이 비지 않음).
        classes.filter { it.simpleName in setOf("MerchantEntitlementUseCase", "MerchantAuthorizationTermsUseCase") }.flatMap { c -> c.methods.map { it.name } }
            .filter { it in EVIDENCE_LESS_SEVEN_NAMES }.toSet() shouldBe EVIDENCE_LESS_SEVEN_NAMES
    }

    test("MERCHANT-OPS-002 §8.4 B3-1F-1: the merchant domain does not know actor names (no \"system:demo-seed\" literal, nor any \"system:\" actor prefix, in domain sources)") {
        val domainSources = java.io.File("src/main/kotlin/com/paycore/master/merchant/domain")
        val files = domainSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // 대조군: 실제 domain 소스를 읽는다(경로 오류로 0개 통과 금지).
        (files.size > 40) shouldBe true
        files.any { it.name == "PaymentMethodActivation.kt" } shouldBe true
        files.filter { it.readText(Charsets.UTF_8).contains("system:demo-seed") }.map { it.name } shouldBe emptyList()
        // B3-1F review NIT-3: 특정 seed 이름뿐 아니라 시스템 행위자 접두 자체(`system:`)도 domain에 없다(행위자 종류 판단은 application 관문).
        files.filter { it.readText(Charsets.UTF_8).contains("system:") }.map { it.name } shouldBe emptyList()
        // 대조군: 같은 검사가 seed 경로 application 소스에서는 그 문자열을 찾는다.
        java.io.File("src/main/kotlin/com/paycore/master/merchant/application").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .any { it.readText(Charsets.UTF_8).contains("system:demo-seed") } shouldBe true
        java.io.File("src/main/kotlin/com/paycore/master/merchant/application").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .any { it.readText(Charsets.UTF_8).contains("system:") } shouldBe true
    }

    test("DEMO-SEED-001 I-4·I-20: the seed coordinator uses only provided use cases and the read-only progress port") {
        demoSeedUsesOnlyProvidedUseCases().check(classes)
        classes.any { it.simpleName == "DemoMerchantSeedService" } shouldBe true
    }

    test("DEMO-SEED-001 I-4·I-20 rule catches a coordinator that reserves admin requests or touches persistence (violating fixture)") {
        val fixture = ClassFileImporter().importPackages("com.paycore.master.archfixture.demoseed")
        val error = shouldThrow<AssertionError> { demoSeedUsesOnlyProvidedUseCases().check(fixture) }
        error.message shouldContain "reserveOrLock"
        error.message shouldContain "jakarta.persistence"
    }

    test("test sources are excluded from the architecture import (DO_NOT_INCLUDE_TESTS)") {
        // MasterUtcSessionSpec은 DataSource/Hikari를 쓰는 테스트 소스다. 테스트까지 import하면 영속 기술 규칙이 이를 잡는다.
        val withTests = ClassFileImporter().importPackages(BASE_PACKAGE)
        withTests.any { it.simpleName == "MasterUtcSessionSpec" } shouldBe true
        classes.any { it.simpleName == "MasterUtcSessionSpec" } shouldBe false
        val error = shouldThrow<AssertionError> {
            rules.persistenceTechnologyOnlyInPersistenceAdapter().check(withTests)
        }
        error.message shouldContain "MasterUtcSessionSpec"
    }
}) {
    companion object {
        /**
         * DEMO-SEED-001 I-4·I-20: seed 조정자(`DemoMerchantSeedService`)는 기존 provided Use Case와 진행 판정 읽기(`AdminRequestStore.find`)만 쓴다 —
         * 영속 기술·adapter 직접 사용 금지, `reserveOrLock`(PENDING 행 INSERT) 호출 금지.
         */
        fun demoSeedUsesOnlyProvidedUseCases(): com.tngtech.archunit.lang.ArchRule {
            val noPersistence = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveSimpleName("DemoMerchantSeedService")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "jakarta.persistence..", "org.hibernate..", "org.jooq..", "org.springframework.jdbc..", "org.springframework.data..",
                )
            val noReservation = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveSimpleName("DemoMerchantSeedService")
                .should().callMethodWhere(
                    com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.base.DescribedPredicate.describe("AdminRequestStore.reserveOrLock") { t ->
                            t.name == "reserveOrLock" && t.owner.simpleName == "AdminRequestStore"
                        },
                    ),
                )
            return com.tngtech.archunit.lang.CompositeArchRule.of(noPersistence).and(noReservation).allowEmptyShould(false)
        }

        /** 근거 없는 두 Use Case 메서드(D-MOPS-008 ② — Demo seed 전용). */
        val EVIDENCE_LESS: com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget> =
            com.tngtech.archunit.base.DescribedPredicate.describe("evidence-less 2-person use case entries (B2 two + B3-1 two)") { t ->
                (t.name in setOf("approveContractRevision", "issueFirstDefaultMerchantAccount") && t.owner.simpleName in setOf("MerchantOnboardingUseCase", "MerchantOnboardingService")) ||
                    // B3-1(C-40): 근거 없는 개통 승인·Site 심사 결정(APPROVED는 seed 관문 — 1인 거절은 `rejectSiteReview`).
                    (t.name in setOf("approvePaymentMethodActivation", "decideSiteReview") && t.owner.simpleName in setOf("MerchantEntitlementUseCase", "MerchantEntitlementService"))
            }

        /** MERCHANT-OPS-002 §8.4 B3-1F-10(m-6): 근거 없는 2인 권리·Route 메서드 7개(seed 관문 또는 아직 관리 채널 미연결). */
        val EVIDENCE_LESS_SEVEN_NAMES = setOf(
            "approvePaymentMethodActivation", "decideSiteReview", "enableCurrency", "approveCardRouteActivation", "confirmSiteActivation",
            "resumePaymentMethodActivation", "resumeCardRouteActivation",
        )

        val EVIDENCE_LESS_ENTITLEMENT_SEVEN: com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget> =
            com.tngtech.archunit.base.DescribedPredicate.describe("evidence-less 2-person entitlement/route entries (B3-1F-10 seven)") { t ->
                t.name in EVIDENCE_LESS_SEVEN_NAMES && t.owner.simpleName in setOf(
                    "MerchantEntitlementUseCase", "MerchantEntitlementService", "MerchantAuthorizationTermsUseCase", "MerchantAuthorizationTermsService",
                )
            }

        /** seed 조정자와 그 내부 클래스(Kotlin lambda·inner class) 이름. */
        val SEED_COORDINATOR_NAMES = Regex.escape("com.paycore.master.merchant.application.DemoMerchantSeedService") + "(\\$.*)?"

        /** [fqcn] 클래스와 그 내부·익명 클래스(Kotlin lambda·inner class)의 메서드 호출. */
        fun callsFrom(classes: com.tngtech.archunit.core.domain.JavaClasses, fqcn: String): List<com.tngtech.archunit.core.domain.JavaMethodCall> =
            classes.filter { it.name == fqcn || it.name.startsWith("$fqcn$") }.flatMap { it.methodCallsFromSelf }

        /** C-26: 근거 없는 두 메서드의 호출자는 `DemoMerchantSeedService`(내부 클래스·lambda 포함)뿐 — 관리 채널 컨트롤러 포함 그 밖 운영 코드는 근거 있는 명령만. */
        fun evidenceLessCallersAreOnlyTheSeed(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().haveNameNotMatching(SEED_COORDINATOR_NAMES)
                .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(EVIDENCE_LESS))
                .allowEmptyShould(false)

        const val BASE_PACKAGE = "com.paycore.master"

        const val GENERATED_JOOQ_PACKAGE = "com.paycore.master.jooq.generated"

        /**
         * 규칙 이름 → allowEmptyShould 값. 모두 false(대상 있음): merchant 모듈(MASTER-DOMAIN-001, adapter.batch·integration은 MASTER-SNAPSHOT-001,
         * adapter.web은 MERCHANT-OPS-001 ADMIN 서명 채널 — 이전에는 Q-ADMIN-AUTH 미결로 비어 있어 true였다).
         */
        val ALLOW_EMPTY: Map<String, Boolean> = linkedMapOf(
            "classesResideInKnownLayers" to false,
            "baseRootHoldsOnlyApplicationEntryPoint" to false,
            "domainIsPure" to false,
            "coreDoesNotDependOnBaseRootOrCrossCutting" to false,
            "applicationDependsOnlyOnPorts" to false,
            "persistenceTechnologyOnlyInPersistenceAdapter" to false,
            "springBootApplicationIsSingleAtBaseRoot" to false,
            "jpaMappingsOnlyInPersistenceAdapter" to false,
            "springDataRepositoriesOnlyInPersistenceAdapter" to false,
            "springBatchOnlyInBatchAdapter" to false,
            "controllersOnlyInWebAdapter" to false,
            "jooqOnlyInQueryAdapter" to false,
            "queryAdapterDoesNotWriteWithJooq" to false,
            "integrationAdapterIsEncapsulated" to false,
            "webAdapterUsesOnlyApplication" to false,
            // MASTER-SNAPSHOT-001: Relay·lease 갱신 주기 실행(adapter.batch, application.provided만 호출).
            "batchAdapterUsesOnlyApplication" to false,
            "namedInterfacesOnlyOnProvidedPackages" to false,
            "modulesAreFreeOfCycles" to false,
        )
    }
}
