package com.paycore.payment.architecture

import com.paycore.payment.PaymentApiApplication
import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.ApplicationModules

/**
 * payment-api 아키텍처 규칙(PayCoreArchitectureRules — 규칙 자체의 동작 증거는 libs/test-support meta-test).
 *
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. CP-DOMAIN-001에서 첫 업무 모듈 `payment`의 `domain` 패키지가 생겨
 * 계층 소속·domain 순수성·core 격리·모듈 순환 규칙의 대상이 생겼으므로 false로 바꿨다. CP-DB-001에서 `application`(provided/required,
 * Use Case 서비스)·`adapter.persistence`(JPA Entity·Spring Data repository)·provided package-info가 생겨 그 규칙도 false로 바꿨다.
 * 아직 대상이 없는 규칙(web·batch·query adapter, Controller)만 true로 두고 사유를 적는다.
 */
class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    val rules = PayCoreArchitectureRules(BASE_PACKAGE, listOf(GENERATED_JOOQ_PACKAGE))

    test("Spring Modulith module structure is valid (generated jOOQ package excluded)") {
        val modules = ApplicationModules.of(PaymentApiApplication::class.java, rules.generatedCode())
        modules.verify()
        // 생성 코드가 생겨도 `jooq`라는 application module로 잡히지 않는다(제외 동작 증거: ModulithGeneratedCodeExclusionSpec).
        modules.getModuleByName("jooq").isPresent shouldBe false
        // CP-DOMAIN-001: 첫 업무 모듈(Order/Payment 활성 규칙). verify가 빈 모듈 목록에서 통과한 것이 아님을 보인다.
        modules.getModuleByName("payment").isPresent shouldBe true
        // MASTER-SNAPSHOT-001: 권리 투영 모듈(payment는 entitlement.application.provided만 쓴다 — verify가 공개 범위를 강제).
        modules.getModuleByName("entitlement").isPresent shouldBe true
    }

    test("the entitlement module uses no in-process cache (every evaluation reads the PAYMENT DB — inbox commit = all instances applied, spec §3.4-3)") {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().resideInAPackage("$BASE_PACKAGE.entitlement..")
            // review n-2: CreatePayment의 운영 Port 구현도 캐시하지 않는다(판정마다 entitlement 판정 → PAYMENT DB).
            .or().haveFullyQualifiedName("$BASE_PACKAGE.payment.adapter.integration.EntitlementEligibilityAdapter")
            // MASTER-SNAPSHOT-002: 승인 시작 허용 판정 Port 구현도 캐시하지 않는다(판정마다 head FOR SHARE).
            .or().haveFullyQualifiedName("$BASE_PACKAGE.payment.adapter.integration.EntitlementAuthorizationAdmissionAdapter")
            // AUTHN-001: 결제창 반환 URL host Port 구현도 캐시하지 않는다(판정마다 head FOR SHARE).
            .or().haveFullyQualifiedName("$BASE_PACKAGE.payment.adapter.integration.EntitlementSiteWebHostAdapter")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.cache..", "com.github.benmanes.caffeine..", "javax.cache..")
            .`as`("entitlement does not use Spring cache, Caffeine or JCache")
            .check(classes)
        classes.filter {
            it.packageName.startsWith("$BASE_PACKAGE.entitlement") || it.simpleName in setOf("EntitlementEligibilityAdapter", "EntitlementAuthorizationAdmissionAdapter", "EntitlementSiteWebHostAdapter")
        }
            .flatMap { c -> c.methods.filter { m -> m.annotations.any { it.rawType.packageName.startsWith("org.springframework.cache") } } } shouldBe emptyList()
    }

    test("authorization admission (MASTER-SNAPSHOT-002 §6.1) is implemented only in adapter.integration and reads only entitlement.application.provided") {
        val port = "$BASE_PACKAGE.payment.application.required.AuthorizationAdmissionPort"
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that().implement(port)
            .should().resideInAPackage("$BASE_PACKAGE.payment.adapter.integration..")
            .check(classes)
        classes.filter { c -> c.interfaces.any { it.name == port } }.map { it.simpleName } shouldBe listOf("EntitlementAuthorizationAdmissionAdapter")
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().implement(port)
            .should().dependOnClassesThat().resideInAnyPackage(
                "$BASE_PACKAGE.entitlement.domain..", "$BASE_PACKAGE.entitlement.application.required..", "$BASE_PACKAGE.entitlement.adapter..",
            )
            .check(classes)
    }

    test("GENERATED_JOOQ_PACKAGE matches the build's generateJooqMain package") {
        System.getProperty("paycore.jooq.mainPackage") shouldBe GENERATED_JOOQ_PACKAGE
    }

    test("classes in module packages reside in known layers") {
        // 대상 있음(CP-DOMAIN-001): com.paycore.payment.payment.domain.
        rules.classesResideInKnownLayers().allowEmptyShould(false).check(classes)
    }

    test("base package root holds only the application entry point") {
        // 대상 있음: PaymentApiApplication과 Kotlin facade PaymentApiApplicationKt.
        rules.baseRootHoldsOnlyApplicationEntryPoint().allowEmptyShould(false).check(classes)
    }

    test("domain is pure Kotlin") {
        // 대상 있음(CP-DOMAIN-001): payment.domain(OrderKey·Payment·OrderPaymentControl 등).
        rules.domainIsPure().allowEmptyShould(false).check(classes)
    }

    test("domain/application do not depend on base root or cross-cutting packages") {
        // 대상 있음: payment.domain(CP-DOMAIN-001), payment.application(CP-DB-001).
        rules.coreDoesNotDependOnBaseRootOrCrossCutting().allowEmptyShould(false).check(classes)
    }

    test("application depends only on ports") {
        // 대상 있음(CP-DB-001): CreatePaymentService·LocalTransactions·application.provided/required.
        rules.applicationDependsOnlyOnPorts().allowEmptyShould(false).check(classes)
    }

    test("persistence technology (JPA/JDBC/DataSource/Spring Data repository) only in adapter.persistence") {
        // 대상 있음: PaymentApiApplication(+Kt), payment 모듈 전체(CP-DB-001: JPA는 adapter.persistence에만). 공통 구성 패키지 없음.
        rules.persistenceTechnologyOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("exactly one @SpringBootApplication, directly in the base package") {
        rules.springBootApplicationIsSingleAtBaseRoot().allowEmptyShould(false).check(classes)
    }

    test("test sources (TechSmoke) are excluded from the architecture import (DO_NOT_INCLUDE_TESTS)") {
        // TechSmokeJpaSpec은 JdbcTemplate을 쓰는 테스트 소스다. 테스트까지 import하면 영속 기술 규칙이 이를 잡으므로,
        // 앱 규칙 통과가 테스트 소스 제외 덕분임(그리고 제외가 실제로 동작함)을 함께 보인다.
        val withTests = ClassFileImporter().importPackages(BASE_PACKAGE)
        withTests.any { it.packageName == "$BASE_PACKAGE.techsmoke" } shouldBe true
        classes.any { it.packageName.startsWith("$BASE_PACKAGE.techsmoke") } shouldBe false
        val error = shouldThrow<AssertionError> {
            rules.persistenceTechnologyOnlyInPersistenceAdapter().check(withTests)
        }
        error.message shouldContain "TechSmokeJpaSpec"
    }

    test("JPA mappings only in adapter.persistence") {
        // 대상 있음(CP-DB-001): Jpa*Entity, @Embeddable 복합 키(TECH-001 smoke Entity는 테스트 소스라 제외).
        rules.jpaMappingsOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("Spring Data repositories only in adapter.persistence") {
        // 대상 있음(CP-DB-001): *JpaRepository.
        rules.springDataRepositoriesOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("Spring Batch types only in adapter.batch") {
        rules.springBatchOnlyInBatchAdapter().allowEmptyShould(false).check(classes)
    }

    test("controllers only in adapter.web") {
        // 대상 있음(MASTER-SNAPSHOT-001): entitlement.adapter.web의 내부 Inbox Controller.
        rules.controllersOnlyInWebAdapter().allowEmptyShould(false).check(classes)
    }

    test("jOOQ and generated jOOQ code only in adapter.query") {
        rules.jooqOnlyInQueryAdapter().allowEmptyShould(false).check(classes)
    }

    test("adapter.query does not write with jOOQ (DML only through application Port -> adapter.persistence)") {
        // 대상 있음(MASTER-SNAPSHOT-001): entitlement.adapter.query 신선도 조회.
        rules.queryAdapterDoesNotWriteWithJooq().allowEmptyShould(false).check(classes)
    }

    test("CP-DB-001 main jOOQ classes are generated from the PAYMENT migration but not used outside adapter.query") {
        // 생성 클래스가 실제로 있어 jooqOnlyInQueryAdapter 통과가 빈 대상 덕분이 아님을 보인다.
        classes.any { it.packageName.startsWith("$GENERATED_JOOQ_PACKAGE.tables") } shouldBe true
    }

    test("integration adapter types are not used outside adapter.integration") {
        rules.integrationAdapterIsEncapsulated().allowEmptyShould(false).check(classes)
    }

    test("web adapter does not use persistence/query/integration adapters or application.required") {
        // 대상 있음(MASTER-SNAPSHOT-001): entitlement.adapter.web(application.provided만 호출).
        rules.webAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    test("batch adapter uses only application.provided (no adapters/required ports/Spring Data/JPA)") {
        // 대상 있음(AUTHN-001 S11): 결제창 만료 주기 작업 trigger `payment.adapter.batch.RequestedPaymentExpiryScheduler`(이전: 빈 대상 허용).
        rules.batchAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    test("@NamedInterface only on application.provided package-info") {
        // 대상 있음(CP-DB-001): payment.application.provided.package-info.
        rules.namedInterfacesOnlyOnProvidedPackages().allowEmptyShould(false).check(classes)
    }

    test("authorization start is one domain operation: main code calls Payment.startAuthorization only from OrderPaymentControl (CP-DOMAIN-001 review L-2)") {
        val rule = paymentStartAuthorizationOnlyThroughControl()
        rule.check(classes)
        // 실제 호출자가 있다(빈 대상 통과가 아님): OrderPaymentControl.startAuthorization.
        classes.get("$BASE_PACKAGE.payment.domain.OrderPaymentControl").methodCallsFromSelf
            .any { it.target.name == "startAuthorization" && it.target.owner.simpleName == "Payment" } shouldBe true
        // 위반 fixture(시험 소스): 직접 호출, Kotlin 호출 가능 참조, Java 메서드 참조 모두 실패한다.
        listOf(
            com.paycore.archfixture.startauth.DirectStartAuthorization::class.java,
            com.paycore.archfixture.startauth.KotlinReferenceStartAuthorization::class.java,
            com.paycore.archfixture.startauth.JavaReferenceStartAuthorization::class.java,
        ).forEach { fixture ->
            val violation = ClassFileImporter().importPackages("com.paycore.archfixture.startauth")
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("fixture ${fixture.simpleName}") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    c.name == fixture.name || c.name.startsWith(fixture.name + "$")
                })
            shouldThrow<AssertionError> { rule.check(violation) }.message shouldContain fixture.simpleName
        }
    }

    test("authorization start is one domain operation with its attempt, session binding and limit reservation: main code reaches them only through AuthorizationStart (AUTH-CONTRACT-001 L-2/L-3)") {
        val rule = controlStartAuthorizationOnlyThroughAuthorizationStart()
        rule.check(classes)
        // 실제 호출자가 있다(빈 대상 통과가 아님): AuthorizationStart가 네 연산을 모두 부른다.
        val calls = classes.get("$BASE_PACKAGE.payment.domain.AuthorizationStart").accessesFromSelf
            .map { it.target.owner.simpleName to kotlinSourceName(it.target.name) }.toSet()
        calls.containsAll(
            listOf("OrderPaymentControl" to "startAuthorization", "Companion" to "open", "AuthenticationSession" to "bindTo", "DailyLimitUsage" to "reserve"),
        ) shouldBe true
        // review m-4: 대상마다 위반 fixture 하나 — 각 fixture는 정확히 자기 대상 하나로 실패한다.
        mapOf(
            com.paycore.archfixture.startauth.DirectControlStartAuthorization::class.java to "$DOMAIN.OrderPaymentControl.startAuthorization",
            com.paycore.archfixture.startauth.DirectAuthorizationOpen::class.java to "$DOMAIN.PaymentAuthorization\$Companion.open",
            com.paycore.archfixture.startauth.DirectSessionBinding::class.java to "$DOMAIN.AuthenticationSession.bindTo",
            com.paycore.archfixture.startauth.DirectLimitReservation::class.java to "$DOMAIN.DailyLimitUsage.reserve",
        ).forEach { (fixture, target) ->
            violationsOf(rule, "com.paycore.archfixture.startauth", fixture).let { details ->
                details.size shouldBe 1
                details.single() shouldContain fixture.simpleName
                details.single() shouldContain target
            }
        }
    }

    test("result transitions (Payment markAuthorized/markUnknown/fail/expire/addTerminationEvidence, control completion, limit consume/release) are called only inside the domain (AUTH-CONTRACT-001 §17-4·5)") {
        val rule = domainTransitionsOnlyInsideDomain()
        rule.check(classes)
        classes.get("$BASE_PACKAGE.payment.domain.AuthorizationResolution").accessesFromSelf
            .any { it.target.owner.simpleName == "OrderPaymentControl" && kotlinSourceName(it.target.name) == "completeAuthorization" } shouldBe true
        kotlinSourceName("reserve-jHxBwU8") shouldBe "reserve"
        kotlinSourceName("markUnknown\$payment_api") shouldBe "markUnknown"
        // review m-4: 대상 소유자별 fixture, 대상 메서드마다 위반 한 건이 보고되는지 확인한다(internal·value class 이름 변형 포함).
        mapOf(
            com.paycore.archfixture.transition.DirectPaymentTransitions::class.java to
                listOf("markUnknown", "markAuthorized", "fail", "expire", "addTerminationEvidence").map { "$DOMAIN.Payment.$it" },
            com.paycore.archfixture.transition.DirectControlTransitions::class.java to
                listOf("recordAuthorized", "completeAuthorization", "releaseIncidentHold").map { "$DOMAIN.OrderPaymentControl.$it" },
            com.paycore.archfixture.transition.DirectLimitTransitions::class.java to
                listOf("consume", "release", "reoccupyAfterLateApproval", "settleForced").map { "$DOMAIN.DailyLimitUsage.$it" },
            com.paycore.archfixture.transition.DirectAuthorizationTransitions::class.java to
                listOf("recordObservation", "markUnknown", "authorize", "fail").map { "$DOMAIN.PaymentAuthorization.$it" } +
                listOf("beginDispatch", "abandon", "markOutcomeUnknown", "resolve").map { "$DOMAIN.AuthorizationAttempt.$it" },
            // CANCEL-CONTRACT-001 T6: 취소 내부 전이·반환 예약 전이·취소 사건 hold.
            com.paycore.archfixture.transition.DirectCancellationTransitions::class.java to
                listOf("recordObservation", "markUnknown", "complete", "fail", "recordIncident").map { "$DOMAIN.PaymentCancellation.$it" } +
                listOf("beginDispatch", "abandon", "markOutcomeUnknown", "resolve").map { "$DOMAIN.CancelAttempt.$it" },
            com.paycore.archfixture.transition.DirectReturnBalanceTransitions::class.java to
                listOf("consume", "release", "reoccupyAfterLateCancel").map { "$DOMAIN.ReturnBalance.$it" },
            com.paycore.archfixture.transition.DirectLateApprovalRecording::class.java to listOf("$DOMAIN.PaymentAuthorization.recordLateApprovalByReconciliation"),
        ).forEach { (fixture, targets) ->
            val details = violationsOf(rule, "com.paycore.archfixture.transition", fixture)
            details.size shouldBe targets.size
            targets.forEach { target -> details.count { it.contains(target) && targetNameEnds(it, target) } shouldBe 1 }
        }
    }

    test("a cancel start is one domain operation: main code opens a cancellation and reserves a return only through CancelStart (CANCEL-CONTRACT-001 T6)") {
        val rule = cancelStartOnlyThroughCancelStart()
        rule.check(classes)
        // 실제 호출자가 있다(빈 대상 통과가 아님): CancelStart가 두 연산을 부른다.
        val calls = classes.get("$BASE_PACKAGE.payment.domain.CancelStart").accessesFromSelf
            .map { it.target.owner.simpleName to kotlinSourceName(it.target.name) }.toSet()
        calls.containsAll(listOf("Companion" to "open", "ReturnBalance" to "reserve")) shouldBe true
        calls.any { (owner, name) -> owner == "Companion" && name == "open" } shouldBe true
        // 결과 반영 domain 서비스가 취소 전이를 실제로 부른다.
        classes.get("$BASE_PACKAGE.payment.domain.CancellationResolution").accessesFromSelf
            .any { it.target.owner.simpleName == "ReturnBalance" && kotlinSourceName(it.target.name) == "consume" } shouldBe true
        mapOf(
            com.paycore.archfixture.cancelstart.DirectCancellationOpen::class.java to "$DOMAIN.PaymentCancellation\$Companion.open",
            com.paycore.archfixture.cancelstart.DirectReturnReservation::class.java to "$DOMAIN.ReturnBalance.reserve",
            com.paycore.archfixture.cancelstart.DirectReturnBalanceOpen::class.java to "$DOMAIN.ReturnBalance\$Companion.open",
        ).forEach { (fixture, target) ->
            violationsOf(rule, "com.paycore.archfixture.cancelstart", fixture).let { details ->
                details.size shouldBe 1
                details.single() shouldContain fixture.simpleName
                details.single() shouldContain target
            }
        }
    }

    test("the cancel-result hold is opened only by CancellationResolution, and a return balance is restored only by the persistence adapter (CANCEL-CONTRACT-001 T6)") {
        val holdRule = cancelHoldOnlyThroughCancellationResolution()
        holdRule.check(classes)
        classes.get("$BASE_PACKAGE.payment.domain.CancellationResolution").accessesFromSelf
            .any { it.target.owner.simpleName == "OrderPaymentControl" && kotlinSourceName(it.target.name) == "reportCancellationResultConflict" } shouldBe true
        violationsOf(holdRule, "com.paycore.archfixture.cancelresolution", com.paycore.archfixture.cancelresolution.DirectCancelHoldReport::class.java).let { details ->
            details.size shouldBe 1
            details.single() shouldContain "$DOMAIN.OrderPaymentControl.reportCancellationResultConflict"
        }
        // 복원 Mapper는 CANCEL-001에서 생긴다 — 지금 main에는 호출자가 없어 규칙은 대상 없음으로 통과하고, 실제 평가는 위반 fixture가 확인한다.
        val restoreRule = returnBalanceRestoredOnlyByPersistence()
        restoreRule.check(classes)
        violationsOf(restoreRule, "com.paycore.archfixture.cancelstart", com.paycore.archfixture.cancelstart.DirectReturnBalanceReconstitute::class.java).let { details ->
            details.size shouldBe 1
            details.single() shouldContain "$DOMAIN.ReturnBalance\$Companion.reconstitute"
        }
    }

    test("a cancel start input does not depend on the daily limit, entitlement or authorization-admission types (K-3, D-AUTH-007, review deviation 3)") {
        cancelStartIndependentOfLimitAndEntitlement(setOf("$DOMAIN.CancelStart", "$DOMAIN.CancelStartCommand")).check(classes)
        val fixture = com.paycore.archfixture.cancelstart.CancelStartUsingLimit::class.java
        val details = violationsOf(cancelStartIndependentOfLimitAndEntitlement(setOf(fixture.name)), "com.paycore.archfixture.cancelstart", fixture)
        details.isNotEmpty() shouldBe true
        details.all { it.contains("DailyLimitUsage") } shouldBe true
    }

    test("authentication sessions are created only through AuthenticationSessionStart, which supersedes earlier sessions first (AUTHN-001 §7.1·§17.3)") {
        val rule = sessionCreateOnlyThroughAuthenticationSessionStart()
        rule.check(classes)
        // 실제 호출자가 있다(빈 대상 통과가 아님).
        classes.get("$DOMAIN.AuthenticationSessionStart").accessesFromSelf
            .any { it.target.owner.simpleName == "Companion" && kotlinSourceName(it.target.name) == "create" && it.target.owner.fullName.startsWith("$DOMAIN.AuthenticationSession") } shouldBe true
        violationsOf(rule, "com.paycore.archfixture.session", com.paycore.archfixture.session.DirectSessionCreate::class.java).let { details ->
            details.size shouldBe 1
            details.single() shouldContain "DirectSessionCreate"
            details.single() shouldContain "$DOMAIN.AuthenticationSession\$Companion.create"
        }
    }

    test("authentication session transitions (start/verify/fail/expire) are called only from application or domain (AUTHN-001 §17.3)") {
        val rule = authenticationTransitionsOnlyInApplicationOrDomain()
        rule.check(classes)
        val details = violationsOf(rule, "com.paycore.archfixture.session", com.paycore.archfixture.session.WebAdapterVerifiesSession::class.java)
        val targets = listOf("start", "verify", "fail", "expire").map { "$DOMAIN.AuthenticationSession.$it" }
        details.size shouldBe targets.size
        targets.forEach { target -> details.count { it.contains(target) && targetNameEnds(it, target) } shouldBe 1 }
    }

    test("main code verifies an authentication session only through AuthenticationResultCheck (the R3·R6·R5·R7… order cannot be skipped — review n-3)") {
        val rule = sessionVerifyOnlyThroughAuthenticationResultCheck()
        rule.check(classes)
        classes.get("$DOMAIN.AuthenticationResultCheck").accessesFromSelf
            .any { it.target.owner.simpleName == "AuthenticationSession" && kotlinSourceName(it.target.name) == "verify" } shouldBe true
        violationsOf(rule, "com.paycore.archfixture.session", com.paycore.archfixture.session.DirectSessionVerify::class.java).let { details ->
            details.size shouldBe 1
            details.single() shouldContain "DirectSessionVerify"
            details.single() shouldContain "$DOMAIN.AuthenticationSession.verify"
        }
    }

    test("CardAuthenticationPort is implemented only by the Simulator adapter in adapter.integration.institution — no REAL adapter (AUTHN-001 §13.6, D-AUTHN-004)") {
        val rule = cardAuthenticationPortImplementedOnlyBySimulatorAdapter()
        rule.check(classes)
        classes.filter { c -> c.interfaces.any { it.name == CARD_AUTHENTICATION_PORT } }.map { it.simpleName } shouldBe listOf("SimulatorCardAuthenticationAdapter")
        val fixture = com.paycore.archfixture.institution.RealCardAuthenticationAdapter::class.java
        shouldThrow<AssertionError> { rule.check(fixtureClasses("com.paycore.archfixture.institution", fixture)) }.message shouldContain fixture.simpleName
    }

    test("AuthorizationPort and AuthorizationInquiryPort are implemented only by the Simulator adapter in adapter.integration.institution — no REAL VAN adapter (AUTH-001 §5.6)") {
        val rule = authorizationPortsImplementedOnlyBySimulatorAdapter()
        rule.check(classes)
        classes.filter { c -> c.interfaces.any { it.name == AUTHORIZATION_PORT || it.name == AUTHORIZATION_INQUIRY_PORT } }.map { it.simpleName } shouldBe
            listOf("SimulatorVanAuthorizationAdapter")
        val fixture = com.paycore.archfixture.institution.RealVanAuthorizationAdapter::class.java
        shouldThrow<AssertionError> { rule.check(fixtureClasses("com.paycore.archfixture.institution", fixture)) }.message shouldContain fixture.simpleName
    }

    test("institution (TCP) adapters do not depend on mutable domain aggregates or domain services (AUTH-CONTRACT-001 AC 3)") {
        val rule = institutionAdapterDoesNotMutateDomain()
        // AUTHN-001 S7: adapter.integration.institution(Simulator 인증 adapter·설정)이 생겨 대상이 있다. 위반 fixture로도 평가를 본다.
        rule.allowEmptyShould(false).check(classes)
        val fixture = com.paycore.archfixture.adapter.institution.InstitutionAdapterMutatingPayment::class.java
        shouldThrow<AssertionError> {
            rule.allowEmptyShould(false).check(fixtureClasses("com.paycore.archfixture.adapter.institution", fixture))
        }.message shouldContain fixture.simpleName
    }

    test("checkout web classes depend only on provided Use Cases, their own package and the web stack (D-AUTHN-006, AUTHN-001 §17.3 m-2)") {
        val rule = checkoutWebUsesOnlyProvidedUseCases("$BASE_PACKAGE.payment.adapter.web")
        rule.allowEmptyShould(false).check(classes)
        // 대상이 있다(빈 대상 통과가 아님): 토큰 발급 Controller·결제창 Controller.
        classes.filter { it.packageName == "$BASE_PACKAGE.payment.adapter.web" && it.simpleName.startsWith("Checkout") }.map { it.simpleName }.let { names ->
            names.contains("CheckoutTokenController") shouldBe true
            names.contains("CheckoutPageController") shouldBe true
        }
        val fixtures = listOf(
            com.paycore.archfixture.checkoutweb.CheckoutControllerUsingDomain::class.java to "$DOMAIN.PaymentId",
            com.paycore.archfixture.checkoutweb.CheckoutControllerUsingRequiredPort::class.java to "$BASE_PACKAGE.payment.application.required.PaymentRepository",
            // 검토 #3 m-A: Spring 전체가 아니라 web 스택만 — JDBC 직접 접근은 위반.
            com.paycore.archfixture.checkoutweb.CheckoutControllerUsingJdbc::class.java to "org.springframework.jdbc.core.JdbcTemplate",
            // 검토 #3 m-A: 같은 adapter.web이어도 목록 밖 helper(domain을 쓰는 CreatePayment 파서)를 거쳐 domain에 닿지 못한다.
            com.paycore.archfixture.checkoutweb.CheckoutControllerUsingDomainHelper::class.java to "$BASE_PACKAGE.payment.adapter.web.CreatePaymentRequestParser",
            // 검토 #4 n-1: JDK의 JDBC(`java.sql`)와 Boot 전체(예: `boot.jdbc`)는 허용하지 않는다.
            com.paycore.archfixture.checkoutweb.CheckoutControllerUsingSql::class.java to "java.sql.Connection",
            com.paycore.archfixture.checkoutweb.CheckoutControllerUsingBootJdbc::class.java to "org.springframework.boot.jdbc.DataSourceBuilder",
        )
        fixtures.forEach { (fixture, target) ->
            val details = violationsOf(checkoutWebUsesOnlyProvidedUseCases("com.paycore.archfixture.checkoutweb"), "com.paycore.archfixture.checkoutweb", fixture)
            details.isNotEmpty() shouldBe true
            details.any { it.contains(fixture.simpleName) && it.contains(target) } shouldBe true
        }
    }

    test("review #3 m-A: only the checkout connector classes may use the Tomcat connector API; the shared web helpers checkout classes use do not depend on domain or ports") {
        checkoutConnectorClassesUseOnlyWebStackAndTomcatConnector().allowEmptyShould(false).check(classes)
        checkoutWebHelpersDoNotReachDomain().allowEmptyShould(false).check(classes)
        // Tomcat connector 의존은 connector 구성 클래스 밖의 Checkout 클래스에 허용되지 않는다.
        val fixture = com.paycore.archfixture.checkoutweb.CheckoutControllerUsingTomcat::class.java
        violationsOf(checkoutWebUsesOnlyProvidedUseCases("com.paycore.archfixture.checkoutweb"), "com.paycore.archfixture.checkoutweb", fixture)
            .any { it.contains(fixture.simpleName) && it.contains("org.apache.catalina.connector.Connector") } shouldBe true
    }

    test("Thymeleaf is used only by the web adapter (AUTHN-001 §17.3)") {
        val rule = thymeleafOnlyInWebAdapter()
        rule.check(classes)
        val fixture = com.paycore.archfixture.web.DomainUsesThymeleaf::class.java
        violationsOf(rule, "com.paycore.archfixture.web", fixture).let { details ->
            details.isNotEmpty() shouldBe true
            details.all { it.contains(fixture.simpleName) } shouldBe true
        }
    }

    test("test-only fixture tooling (Fixture Monkey, Instancio) is not referenced from any production class (Test Fixture Policy)") {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("com.navercorp.fixturemonkey..", "org.instancio..")
            .`as`("production code does not use Fixture Monkey or Instancio")
            .check(classes)
    }

    test("no cycles between modules") {
        // 대상 있음(CP-DOMAIN-001): 모듈 slice `payment`.
        rules.modulesAreFreeOfCycles().allowEmptyShould(false).check(classes)
    }
}) {
    companion object {
        const val BASE_PACKAGE = "com.paycore.payment"

        /**
         * L-2 규칙: `Payment.startAuthorization`에 접근(호출 **및 메서드 참조** `Payment::startAuthorization` — review round1 m-2)하는 클래스는
         * `OrderPaymentControl`뿐이다(검사·전이를 한 연산으로 묶는다). `callMethod`는 참조를 보지 않으므로 접근 대상 규칙으로 쓴다.
         */
        fun paymentStartAuthorizationOnlyThroughControl(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName("$BASE_PACKAGE.payment.domain.OrderPaymentControl")
                .should().accessTargetWhere(
                    object : com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaAccess<*>>("target is Payment.startAuthorization") {
                        override fun test(access: com.tngtech.archunit.core.domain.JavaAccess<*>): Boolean =
                            access.target.owner.fullName == "$BASE_PACKAGE.payment.domain.Payment" && access.target.name == "startAuthorization"
                    },
                )
                .`as`("only OrderPaymentControl calls or references Payment.startAuthorization (slot checks and the transition are one operation)")

        const val DOMAIN = "$BASE_PACKAGE.payment.domain"

        private fun accessTo(description: String, targets: Map<String, Set<String>>) =
            object : com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaAccess<*>>(description) {
                override fun test(access: com.tngtech.archunit.core.domain.JavaAccess<*>): Boolean =
                    targets[access.target.owner.fullName]?.contains(kotlinSourceName(access.target.name)) == true
            }

        /**
         * JVM 메서드 이름 → Kotlin 소스 이름. value class 인자를 받는 함수는 `name-<hash>`, `internal` 멤버는 `name$<module>`으로 바뀐다
         * (예: `DailyLimitUsage.reserve(AuthorizationId, …)` → `reserve-jHxBwU8`). 정확한 이름만 비교하면 규칙이 위반을 놓친다.
         */
        fun kotlinSourceName(jvmName: String): String = jvmName.substringBefore('-').substringBefore('$')

        /** 위반 fixture 하나를 규칙으로 평가해 위반 상세 줄들을 돌려준다(위반이 없으면 빈 목록). */
        fun violationsOf(rule: com.tngtech.archunit.lang.ArchRule, pkg: String, fixture: Class<*>): List<String> =
            rule.evaluate(fixtureClasses(pkg, fixture)).failureReport.details

        /**
         * 상세 줄에서 [target] 바로 뒤가 소스 이름의 끝(`(`·`-`·`$`)인지 — `Payment.fail`이 `Payment.failSomething`에, `release`가
         * `releaseIncidentHold`에 맞지 않게 한다.
         */
        fun targetNameEnds(detail: String, target: String): Boolean {
            var i = detail.indexOf(target)
            while (i >= 0) {
                val next = detail.getOrNull(i + target.length)
                if (next == '(' || next == '-' || next == '$') return true
                i = detail.indexOf(target, i + 1)
            }
            return false
        }

        /** 시험 소스 위반 fixture 하나(와 그 내부 클래스)만 import한다. */
        fun fixtureClasses(pkg: String, fixture: Class<*>): com.tngtech.archunit.core.domain.JavaClasses =
            ClassFileImporter().importPackages(pkg)
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("fixture ${fixture.simpleName}") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    c.name == fixture.name || c.name.startsWith(fixture.name + "$")
                })

        /**
         * L-3 규칙(AUTH-CONTRACT-001 §4.2): Payment AUTHORIZING 전이(control.startAuthorization), Authorization 생성(Attempt PREPARED 포함),
         * 인증 근거 귀속, 일 한도 점유는 `AuthorizationStart`만 부른다 — 한 domain 연산이어야 "AUTHORIZING이면 Attempt가 있다"가 성립한다.
         * 호출과 메서드 참조 모두 접근 대상 규칙으로 본다.
         */
        fun controlStartAuthorizationOnlyThroughAuthorizationStart(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName("$DOMAIN.AuthorizationStart")
                .should().accessTargetWhere(
                    accessTo(
                        "target is an authorization-start step",
                        mapOf(
                            "$DOMAIN.OrderPaymentControl" to setOf("startAuthorization"),
                            "$DOMAIN.PaymentAuthorization" to setOf("open"),
                            "$DOMAIN.PaymentAuthorization\$Companion" to setOf("open"),
                            "$DOMAIN.AuthenticationSession" to setOf("bindTo"),
                            "$DOMAIN.DailyLimitUsage" to setOf("reserve"),
                        ),
                    ),
                )
                .`as`("only AuthorizationStart starts an authorization (AUTHORIZING + attempt + session binding + limit reservation are one operation)")

        /**
         * CANCEL-CONTRACT-001 §4.4·T6: 취소 operation 생성(Attempt PREPARED 포함)과 반환 예약은 `CancelStart`만 부른다 — 한 domain 연산이어야
         * "OPEN·UNKNOWN 취소 ⇔ RESERVED 예약"(INV-C3)과 진행 중 1건(D-CANCEL-003)·운영자 재검사(D-CANCEL-005)를 건너뛸 수 없다.
         */
        fun cancelStartOnlyThroughCancelStart(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName("$DOMAIN.CancelStart")
                .should().accessTargetWhere(
                    accessTo(
                        "target is a cancel-start step",
                        mapOf(
                            "$DOMAIN.PaymentCancellation" to setOf("open"),
                            "$DOMAIN.PaymentCancellation\$Companion" to setOf("open"),
                            "$DOMAIN.ReturnBalance" to setOf("reserve"),
                            "$DOMAIN.ReturnBalance\$Companion" to setOf("open"),
                        ),
                    ),
                )
                .`as`("only CancelStart starts a cancellation (cancellation + attempt + return reservation are one operation)")

        /** 취소 사건 hold(D-CANCEL-006)는 취소 결과 반영 domain 서비스만 연다 — 관찰·예약 상태와 hold가 어긋나지 않게 한다. */
        fun cancelHoldOnlyThroughCancellationResolution(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName("$DOMAIN.CancellationResolution")
                .should().accessTargetWhere(accessTo("target opens a cancel-result hold", mapOf("$DOMAIN.OrderPaymentControl" to setOf("reportCancellationResultConflict"))))
                .`as`("only CancellationResolution opens a CANCEL_RESULT_CONFLICT hold")

        /** 반환 잔액 영속 복원은 persistence adapter(Mapper)만 — 시험 편의 생성 경로가 아니다. */
        fun returnBalanceRestoredOnlyByPersistence(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("$BASE_PACKAGE.payment.adapter.persistence..")
                .should().accessTargetWhere(accessTo("target restores a return balance", mapOf("$DOMAIN.ReturnBalance\$Companion" to setOf("reconstitute"))))
                .allowEmptyShould(true)
                .`as`("only the persistence adapter restores a ReturnBalance")

        /** 취소 시작은 일 한도·신규 승인 권리(entitlement)·승인 시작 판정 근거에 의존하지 않는다(K-3, D-AUTH-007). */
        fun cancelStartIndependentOfLimitAndEntitlement(names: Set<String>): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("cancel start types") { c: com.tngtech.archunit.core.domain.JavaClass -> c.name in names })
                .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe("daily limit, entitlement or admission types") { c: com.tngtech.archunit.core.domain.JavaClass ->
                        c.name in setOf(
                            "$DOMAIN.DailyLimitUsage", "$DOMAIN.LimitUsageKey", "$DOMAIN.LimitReservation", "$DOMAIN.TransactionLimitPolicy",
                            "$DOMAIN.LimitPolicyResolution", "$DOMAIN.AuthorizationAdmission",
                        ) || c.packageName.startsWith("$BASE_PACKAGE.entitlement")
                    },
                )
                .`as`("cancel start does not depend on limits or new-approval entitlement")

        /**
         * 결과 전이는 domain 서비스(AuthorizationResolution·RequestedPaymentExpiry)와 Aggregate 안에서만 한다(§17-4·5): main 코드의 domain 밖
         * 클래스는 아래 메서드를 호출·참조하지 않는다. 시험 소스는 대상이 아니다(DO_NOT_INCLUDE_TESTS — 상태 준비용 시험은 계속 쓸 수 있다).
         */
        fun domainTransitionsOnlyInsideDomain(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("$DOMAIN..")
                .should().accessTargetWhere(
                    accessTo(
                        "target is a domain result transition",
                        mapOf(
                            "$DOMAIN.Payment" to setOf("markUnknown", "markAuthorized", "fail", "expire", "addTerminationEvidence"),
                            "$DOMAIN.OrderPaymentControl" to setOf("recordAuthorized", "completeAuthorization", "releaseIncidentHold"),
                            "$DOMAIN.DailyLimitUsage" to setOf("consume", "release", "reoccupyAfterLateApproval", "settleForced"),
                            "$DOMAIN.PaymentAuthorization" to setOf("recordObservation", "markUnknown", "authorize", "fail", "recordLateApprovalByReconciliation"),
                            "$DOMAIN.AuthorizationAttempt" to setOf("beginDispatch", "abandon", "markOutcomeUnknown", "resolve"),
                            "$DOMAIN.PaymentCancellation" to setOf("recordObservation", "markUnknown", "complete", "fail", "recordIncident"),
                            "$DOMAIN.CancelAttempt" to setOf("beginDispatch", "abandon", "markOutcomeUnknown", "resolve"),
                            "$DOMAIN.ReturnBalance" to setOf("consume", "release", "reoccupyAfterLateCancel"),
                        ),
                    ),
                )
                .`as`("result transitions are applied only by domain services (Payment + Authorization + control + limit together)")

        /**
         * AUTHN-001 §7.1: main의 `AuthenticationSession.create` 호출·참조는 `AuthenticationSessionStart`만 — 새 세션 전에 같은 Payment의 미폐기·미귀속
         * 세션 대체(AN-07)를 건너뛰지 못하게 한다.
         */
        fun sessionCreateOnlyThroughAuthenticationSessionStart(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName("$DOMAIN.AuthenticationSessionStart")
                .and().doNotHaveFullyQualifiedName("$DOMAIN.AuthenticationSession")
                .and().doNotHaveFullyQualifiedName("$DOMAIN.AuthenticationSession\$Companion")
                .should().accessTargetWhere(accessTo("target is AuthenticationSession.create", mapOf("$DOMAIN.AuthenticationSession\$Companion" to setOf("create"))))
                .`as`("only AuthenticationSessionStart creates authentication sessions (earlier sessions are superseded first)")

        const val CARD_AUTHENTICATION_PORT = "$BASE_PACKAGE.payment.application.required.CardAuthenticationPort"

        /** AUTHN-001 §13.6·§17.3: `CardAuthenticationPort` 구현은 `..adapter.integration.institution..`의 `Simulator*`만(REAL 인증 adapter 없음). */
        fun cardAuthenticationPortImplementedOnlyBySimulatorAdapter(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that().implement(CARD_AUTHENTICATION_PORT)
                .should().resideInAPackage("$BASE_PACKAGE..adapter.integration.institution..").andShould().haveSimpleNameStartingWith("Simulator")
                .`as`("only the Simulator adapter in adapter.integration.institution implements CardAuthenticationPort (no REAL adapter)")

        const val AUTHORIZATION_PORT = "$BASE_PACKAGE.payment.application.required.AuthorizationPort"
        const val AUTHORIZATION_INQUIRY_PORT = "$BASE_PACKAGE.payment.application.required.AuthorizationInquiryPort"

        /** AUTH-001 §5.6 겹 1: 승인·조회 Port 구현은 `..adapter.integration.institution..`의 `Simulator*`만(REAL VAN adapter 없음). */
        fun authorizationPortsImplementedOnlyBySimulatorAdapter(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that().implement(AUTHORIZATION_PORT).or().implement(AUTHORIZATION_INQUIRY_PORT)
                .should().resideInAPackage("$BASE_PACKAGE..adapter.integration.institution..").andShould().haveSimpleNameStartingWith("Simulator")
                .`as`("only the Simulator adapter in adapter.integration.institution implements AuthorizationPort/AuthorizationInquiryPort (no REAL VAN adapter)")

        /** AUTHN-001 검토 n-3: main의 `AuthenticationSession.verify` 호출·참조는 `AuthenticationResultCheck`만(판정 순서를 건너뛰지 못하게). */
        fun sessionVerifyOnlyThroughAuthenticationResultCheck(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName("$DOMAIN.AuthenticationResultCheck")
                .and().doNotHaveFullyQualifiedName("$DOMAIN.AuthenticationSession")
                .should().accessTargetWhere(accessTo("target is AuthenticationSession.verify", mapOf("$DOMAIN.AuthenticationSession" to setOf("verify"))))
                .`as`("only AuthenticationResultCheck verifies authentication sessions")

        /**
         * D-AUTHN-006(승인 편차 조건, 검토 m-2): 결제창 web 클래스(`adapter.web`의 `Checkout*`)는 provided Use Case·자기 패키지·JDK/Kotlin·Spring·Thymeleaf·Servlet·SLF4J에만
         * 의존한다 — domain·required Port·persistence에 닿지 않아 실행 단위 분리(A01 `payment-window`) 때 provided를 API로 노출하면 된다. 명세 목록에 더한 것(§24.12):
         * JSON 파서(`tools.jackson`, 토큰 발급 API 엄격 파서)·지표(`io.micrometer`)·Kotlin 컴파일러 annotation(`org.jetbrains.annotations`)·Tomcat connector
         * (`org.apache.catalina.connector`, 결제창 전용 connector 구성) — 모두 domain·required가 아니다.
         */
        fun checkoutWebUsesOnlyProvidedUseCases(webPackage: String): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                .that().resideInAPackage(webPackage).and().haveSimpleNameStartingWith("Checkout")
                .and().haveSimpleNameNotStartingWith("CheckoutConnector")
                .should().onlyDependOnClassesThat(checkoutWebAllowed(webPackage, tomcatConnector = false))
                .`as`("checkout web classes use only provided Use Cases, checkout web classes, listed web helpers and the web stack (D-AUTHN-006, review #3 m-A)")

        /** 검토 #3 m-A: Tomcat connector API는 결제창 connector 구성(`CheckoutConnector*`)만. */
        fun checkoutConnectorClassesUseOnlyWebStackAndTomcatConnector(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                .that().resideInAPackage(MAIN_WEB).and().haveSimpleNameStartingWith("CheckoutConnector")
                .should().onlyDependOnClassesThat(checkoutWebAllowed(MAIN_WEB, tomcatConnector = true))
                .`as`("checkout connector classes use only the web stack and the Tomcat connector API")

        /** 검토 #3 m-A: 결제창 클래스가 쓰는 공용 web helper는 domain·required Port·persistence에 의존하지 않는다(위임으로 우회 불가). */
        fun checkoutWebHelpersDoNotReachDomain(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("checkout web helpers") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    c.packageName == MAIN_WEB && topLevelName(c) in CHECKOUT_WEB_HELPERS
                })
                .should().dependOnClassesThat().resideInAnyPackage("$BASE_PACKAGE..domain..", "$BASE_PACKAGE..application.required..", "$BASE_PACKAGE..adapter.persistence..")
                .`as`("web helpers shared with checkout classes do not depend on domain, required ports or persistence")

        const val MAIN_WEB = "$BASE_PACKAGE.payment.adapter.web"

        /** 결제창 클래스가 쓸 수 있는 같은 패키지의 공용 helper(고정 문구 오류·응답 작성·설정 — domain 없음, 위 규칙이 확인). */
        val CHECKOUT_WEB_HELPERS = setOf("ApiResponses", "ApiError", "ApiErrorCode", "RequestRejected", "SafeThrowable", "PaymentApiSettings", "PaymentApiSettingsKt")

        private val CHECKOUT_WEB_ALLOWED_PREFIXES = listOf(
            "java.", "kotlin.", "org.thymeleaf.", "jakarta.servlet.", "org.slf4j.", "tools.jackson.", "io.micrometer.", "org.jetbrains.annotations.",
        ) + listOf("web", "http", "stereotype", "context", "core", "beans", "security", "ui", "util", "validation").map { "org.springframework.$it." } +
            // 검토 #4 n-1: Boot는 필요한 곳만(설정 바인딩·web server·자동 구성) — boot.jdbc 등은 허용하지 않는다.
            listOf("context.properties", "web", "autoconfigure").map { "org.springframework.boot.$it." }

        /** 검토 #4 n-1: JDK 안에서도 JDBC(`java.sql`·`javax.sql`)는 결제창 web 클래스에 허용하지 않는다. */
        private val CHECKOUT_WEB_DENIED_PREFIXES = listOf("java.sql.", "javax.sql.")

        private fun topLevelName(c: com.tngtech.archunit.core.domain.JavaClass): String = c.name.substringAfterLast('.').substringBefore('$')

        private fun checkoutWebAllowed(webPackage: String, tomcatConnector: Boolean) =
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "provided Use Cases, Checkout web classes, listed web helpers, JDK/Kotlin, the Spring web stack, Thymeleaf, Servlet, SLF4J, Jackson, Micrometer" +
                    if (tomcatConnector) ", Tomcat connector" else "",
            ) { target: com.tngtech.archunit.core.domain.JavaClass ->
                val c = generateSequence(target) { if (it.isArray) it.componentType else null }.last()
                val pkg = c.packageName + "."
                c.isPrimitive ||
                    pkg.startsWith("$BASE_PACKAGE.payment.application.provided.") ||
                    (c.packageName == webPackage && topLevelName(c).startsWith("Checkout")) ||
                    (c.packageName == MAIN_WEB && (topLevelName(c).startsWith("Checkout") || topLevelName(c) in CHECKOUT_WEB_HELPERS)) ||
                    (CHECKOUT_WEB_ALLOWED_PREFIXES.any { pkg.startsWith(it) } && CHECKOUT_WEB_DENIED_PREFIXES.none { pkg.startsWith(it) }) ||
                    (tomcatConnector && (pkg.startsWith("org.apache.catalina.connector.") || pkg.startsWith("org.springframework.boot.tomcat.")))
            }

        /** AUTHN-001 §17.3: Thymeleaf(`org.thymeleaf..`) 의존은 web adapter만. */
        fun thymeleafOnlyInWebAdapter(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("..adapter.web..")
                .should().dependOnClassesThat().resideInAPackage("org.thymeleaf..")
                .`as`("only the web adapter depends on Thymeleaf")

        /** AUTHN-001 §17.3: 인증 세션 상태 전이는 application·domain에서만 부른다(web·adapter가 세션을 직접 바꾸지 않는다). */
        fun authenticationTransitionsOnlyInApplicationOrDomain(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackages("$BASE_PACKAGE..application..", "$BASE_PACKAGE..domain..")
                .should().accessTargetWhere(
                    accessTo("target is an authentication session transition", mapOf("$DOMAIN.AuthenticationSession" to setOf("start", "verify", "fail", "expire"))),
                )
                .`as`("authentication session transitions are applied only by application services or domain services")

        /** 기관 Adapter(`..adapter..institution..`)는 상태를 가진 domain Aggregate·domain 서비스에 의존하지 않는다 — Port 결과만 돌려준다. */
        fun institutionAdapterDoesNotMutateDomain(): com.tngtech.archunit.lang.ArchRule {
            val mutable = listOf(
                "Payment", "OrderPaymentControl", "PaymentAuthorization", "AuthorizationAttempt", "AuthenticationSession", "DailyLimitUsage",
                "AuthorizationStart", "AuthorizationResolution", "RequestedPaymentExpiry",
                // AUTHN-001(검토 n-1): 인증 시작·결과 판정 domain 서비스도 기관 adapter가 부르지 않는다(Port 결과만 돌려준다).
                "AuthenticationResultCheck", "AuthenticationSessionStart",
            ).map { "$DOMAIN.$it" }
            return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..adapter..institution..")
                .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe("a mutable payment domain aggregate or domain service") { c: com.tngtech.archunit.core.domain.JavaClass ->
                        mutable.any { c.name == it || c.name.startsWith("$it$") }
                    },
                )
                .`as`("institution adapters return port results and never change domain state")
        }

        /** build.gradle.kts의 mainJooqPackage(generateJooqMain)와 같은 값이어야 한다(위 테스트가 비교). */
        const val GENERATED_JOOQ_PACKAGE = "com.paycore.payment.jooq.generated"
    }
}
