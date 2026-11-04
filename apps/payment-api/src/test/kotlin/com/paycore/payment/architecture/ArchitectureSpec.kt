package com.paycore.payment.architecture

import com.paycore.payment.PaymentApiApplication
import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
        // CANCEL-001 F-1: 취소 operation 복원도 persistence adapter만(복원 Mapper는 S2).
        val cancellationRestoreRule = paymentCancellationRestoredOnlyByPersistence()
        cancellationRestoreRule.check(classes)
        violationsOf(cancellationRestoreRule, "com.paycore.archfixture.cancelstart", com.paycore.archfixture.cancelstart.DirectCancellationReconstitute::class.java).let { details ->
            details.size shouldBe 1
            details.single() shouldContain "$DOMAIN.PaymentCancellation\$Companion.reconstitute"
        }
    }

    test("a cancel start input does not depend on the daily limit, entitlement or authorization-admission types (K-3, D-AUTH-007, review deviation 3)") {
        cancelStartIndependentOfLimitAndEntitlement(setOf("$DOMAIN.CancelStart", "$DOMAIN.CancelStartCommand")).check(classes)
        val fixture = com.paycore.archfixture.cancelstart.CancelStartUsingLimit::class.java
        val details = violationsOf(cancelStartIndependentOfLimitAndEntitlement(setOf(fixture.name)), "com.paycore.archfixture.cancelstart", fixture)
        details.isNotEmpty() shouldBe true
        details.all { it.contains("DailyLimitUsage") } shouldBe true
    }

    // CANCEL-001 §4.2 K-3(application 쪽): 취소 단계 서비스는 신규 승인 권리·허용 판정·일 한도 Port에 의존하지 않는다.
    test("the cancel services do not depend on admission, entitlement head, eligibility or daily limit ports (K-3)") {
        val names = setOf(
            "$BASE_PACKAGE.payment.application.CancelStartService", "$BASE_PACKAGE.payment.application.CancelPaymentService", "$BASE_PACKAGE.payment.application.OperatorCorrectionCancelService",
            // 검토 #3 n-2: 전송·결과 반영·회복 서비스도.
            "$BASE_PACKAGE.payment.application.CancellationDispatchService", "$BASE_PACKAGE.payment.application.CancellationResultService",
            "$BASE_PACKAGE.payment.application.CancellationRecoveryService",
        )
        names.forEach { classes.contain(it) shouldBe true }
        cancelServicesIndependentOfAdmission(names).check(classes)
    }

    // ADMIN-PAYMENT-OPS-001 §4.8(AC3, CANCEL-001 m-5 완화): 운영자 보정 취소 진입은 인증된 ADMIN 채널 adapter의 **정확 FQN**만. 기대값 변경 기록: 이전 시험 "nothing in main calls … (no entry
    // point)" → "only the authenticated admin entry adapter calls …" — 허용은 정확 FQN 1개씩만 늘었다(Kotlin 합성 중첩 클래스 `<FQN>$…`는 같은 허용). 규칙 11개 + 서명 비교 규칙(checkpoint L-4).
    test("only the authenticated admin entry adapter calls the operator cancel ports; each fixture violates exactly its own rule (ADMIN-PAYMENT-OPS-001 §4.8)") {
        val rules = operatorEntryRules()
        rules.values.forEach { it.check(classes) }
        // 실제 진입 adapter가 있다(빈 대상 통과가 아님).
        ADMIN_ENTRY_CONTROLLERS.forEach { classes.contain(it) shouldBe true }
        val fx = "com.paycore.archfixture.operatorcancel"
        val fixtures = mapOf(
            "operatorCorrectionCancelOnlyFromAdminEntry" to listOf(
                com.paycore.archfixture.operatorcancel.OperatorCancelEntryPoint::class.java,
                com.paycore.archfixture.operatorcancel.web.admin.AdminOperatorCancellationController::class.java,
            ),
            "operatorCancelTargetQueryOnlyFromAdminEntry" to listOf(com.paycore.archfixture.operatorcancel.TargetQueryCaller::class.java),
            "operatorDecisionQueryOnlyFromAdminEntry" to listOf(com.paycore.archfixture.operatorcancel.DecisionQueryCaller::class.java),
            "adminPaymentQueryOnlyFromAdminEntry" to listOf(com.paycore.archfixture.operatorcancel.AdminPaymentQueryCaller::class.java),
            "operatorCancelMalformedRecorderOnlyFromAdminEntry" to listOf(com.paycore.archfixture.operatorcancel.RecorderCaller::class.java),
            "deadlineConfirmationOnlyFromAdminEntry" to listOf(com.paycore.archfixture.operatorcancel.DeadlineConfirmationCaller::class.java),
            "startOperatorOnlyFromOperatorService" to listOf(com.paycore.archfixture.operatorcancel.DirectStartOperatorCaller::class.java),
            "operatorServiceConcreteOnlyFromSpringConfig" to listOf(com.paycore.archfixture.operatorcancel.ConcreteServiceCaller::class.java),
            "operatorCommandConstructedOnlyAtEntry" to listOf(
                com.paycore.archfixture.operatorcancel.OperatorCommandFactory::class.java, com.paycore.archfixture.operatorcancel.OperatorCommandCopier::class.java,
            ),
            "adminEntryControllersNotReferenced" to listOf(com.paycore.archfixture.operatorcancel.ControllerReferrer::class.java),
        )
        fixtures.forEach { (name, list) ->
            list.forEach { fixture ->
                rules.forEach { (other, rule) ->
                    val pkg = fixture.packageName
                    withClue("$other on ${fixture.simpleName}") { violationsOf(rule, pkg, fixture).isNotEmpty() shouldBe (other == name) }
                }
            }
        }
        // L-2: 대상 FQN을 fixture로 바꾼 변형 규칙(같은 FQN이 아니므로).
        val starting = com.paycore.archfixture.operatorcancel.DeadlineConfirmationStartingCancel::class.java
        violationsOf(deadlineConfirmationNeverStartsCancel(starting.name), fx, starting).let { details ->
            details.any { "PaymentCancellationRepository.lock" in it } shouldBe true
            details.any { "CancelStartService" in it } shouldBe true
        }
        val locking = com.paycore.archfixture.operatorcancel.DeadlineConfirmationLockingTarget::class.java
        violationsOf(deadlineConfirmationNeverStartsCancel(locking.name), fx, locking).any { "OrderPaymentControlRepository" in it } shouldBe true
        deadlineConfirmationNeverStartsCancel("$BASE_PACKAGE.payment.application.OperatorDeadlineConfirmationService").check(classes)
        classes.contain("$BASE_PACKAGE.payment.application.OperatorDeadlineConfirmationService") shouldBe true
        // 경로 규칙: 4개 진입 컨트롤러는 @RestController + 클래스 @RequestMapping이 /internal/v1/admin/로 시작. fixture(/v1/…)는 위반.
        adminEntryMappingDefects(ADMIN_ENTRY_CONTROLLERS.map { classes.get(it) }) shouldBe emptyList()
        adminEntryMappingDefects(listOf(fixtureClasses(fx, com.paycore.archfixture.operatorcancel.PublicPathOperatorCancelController::class.java).single())).size shouldBe 1
    }

    // checkpoint L-4: 서명 비교는 상수 시간 MessageDigest.isEqual이고 문자열·배열 동등 비교(String.equals·contentEquals·Arrays.equals·Kotlin ==)를 쓰지 않는다.
    test("AdminCallSignature compares with MessageDigest.isEqual and never with String/array equality (checkpoint L-4)") {
        val target = "$BASE_PACKAGE.payment.adapter.web.admin.AdminCallSignature"
        adminSignatureConstantTimeCompare(target).check(classes)
        classes.get(target).methodCallsFromSelf.any { it.target.owner.fullName == "java.security.MessageDigest" && it.target.name == "isEqual" } shouldBe true
        val fixture = com.paycore.archfixture.operatorcancel.StringCompareSignature::class.java
        violationsOf(adminSignatureConstantTimeCompare(fixture.name), "com.paycore.archfixture.operatorcancel", fixture).isNotEmpty() shouldBe true
    }

    // CP-QUERY-001 CQ-06: 결제 상태 조회 Port(비권위 — 한 읽기 스냅샷, 잠금 없음)는 조회 서비스만 쓴다. 명령 판정은 JPA 잠금 재판정이 한다(원칙 §4).
    test("only PaymentStatusQueryService uses PaymentStatusReader (the non-authoritative view never drives a command — CQ-06)") {
        classes.contain("$BASE_PACKAGE.payment.application.PaymentStatusQueryService") shouldBe true
        paymentStatusReaderOnlyForQueries().check(classes)
        val fixture = com.paycore.archfixture.statusreader.CommandServiceUsingStatusReader::class.java
        violationsOf(paymentStatusReaderOnlyForQueries(), "com.paycore.archfixture.statusreader", fixture).isNotEmpty() shouldBe true
    }

    // CP-QUERY-001 검토 #1 n-5: 결제 상태 조회 Use Case는 공개 web adapter만 부른다.
    test("only adapter.web calls PaymentStatusQuery (n-5)") {
        paymentStatusQueryOnlyFromWeb().check(classes)
        val fixture = com.paycore.archfixture.statusquery.SchedulerUsingStatusQuery::class.java
        violationsOf(paymentStatusQueryOnlyFromWeb(), "com.paycore.archfixture.statusquery", fixture).isNotEmpty() shouldBe true
    }

    // WEBHOOK-001 WH-02·검토 #1 n-6: 업무 Use Case(생성·confirm·취소·만료·회복…)는 가맹점 Webhook Port·타입에 의존하지 않는다 — Webhook은 Outbox를 읽는 별도 fan-out TX뿐(업무 TX 무영향, T-F2의 구조 증거).
    test("business application services do not depend on merchant webhook ports or types (WEBHOOK-001 WH-02, n-6)") {
        classes.contain("$BASE_PACKAGE.payment.application.MerchantNotificationFanoutService") shouldBe true
        businessServicesIndependentOfMerchantWebhook().check(classes)
        val fixture = com.paycore.archfixture.webhook.CancelServiceTouchingWebhook::class.java
        violationsOf(businessServicesIndependentOfMerchantWebhook(), "com.paycore.archfixture.webhook", fixture).isNotEmpty() shouldBe true
    }

    // WEBHOOK-001 검토 m-5: 전송 HttpClient는 시스템 속성(프록시·JVM 전역 trustStore)을 읽지 않는다 — `useSystemProperties()` 호출 금지.
    test("no HttpClient is built with useSystemProperties() (WEBHOOK-001 m-5)") {
        httpClientWithoutSystemProperties().check(classes)
        val fixture = com.paycore.archfixture.webhooktransport.SystemPropertiesHttpClient::class.java
        violationsOf(httpClientWithoutSystemProperties(), "com.paycore.archfixture.webhooktransport", fixture).isNotEmpty() shouldBe true
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

    // CANCEL-001 §6.6: 취소·취소 조회 Port도 Simulator adapter만 구현한다(REAL 없음).
    test("CancellationPort and CancelInquiryPort are implemented only by the Simulator adapter in adapter.integration.institution — no REAL VAN adapter (CANCEL-001 §6.6)") {
        val rule = cancellationPortsImplementedOnlyBySimulatorAdapter()
        rule.check(classes)
        classes.filter { c -> c.interfaces.any { it.name == CANCELLATION_PORT || it.name == CANCEL_INQUIRY_PORT } }.map { it.simpleName } shouldBe
            listOf("SimulatorVanCancellationAdapter")
        val fixture = com.paycore.archfixture.institution.RealVanCancellationAdapter::class.java
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

    test("Thymeleaf is not on the payment-api classpath and no class depends on it (CHECKOUT-REACT-001 §9 — replaces AUTHN-001 §17.3 web-adapter-only rule)") {
        // 결제창은 React shell(D-FE-001)이라 Thymeleaf 의존을 뺐다. 기존 규칙(web adapter만 Thymeleaf)보다 강한 주장: 어떤 클래스도 의존하지 않고, 라이브러리가 classpath에 없다.
        noThymeleaf().check(classes)
        io.kotest.assertions.throwables.shouldThrow<ClassNotFoundException> { Class.forName("org.thymeleaf.TemplateEngine") }
        io.kotest.assertions.throwables.shouldThrow<ClassNotFoundException> { Class.forName("org.thymeleaf.spring6.SpringTemplateEngine") }
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

    // DEMO-SEED-001 I-22: Demo Credential 쓰기 Port 두 개에 의존하는 타입은 발급 서비스(와 중첩 타입)·그 JPA 구현뿐. 기존 읽기 경로 타입은 무변경(fingerprint는 Reviewer).
    test("only DemoCredentialIssueService (and the two JPA implementations) depend on the Demo credential write ports (DEMO-SEED-001 I-22)") {
        demoCredentialWritersOnlyFromIssueService().check(classes)
        // 실제 사용자가 있다(빈 대상 통과가 아님).
        classes.get("$BASE_PACKAGE.payment.application.DemoCredentialIssueService").directDependenciesFromSelf
            .map { it.targetClass.name }.containsAll(listOf(DEMO_PROJECTION_WRITER, DEMO_ISSUANCE_STORE)) shouldBe true
        val fixture = com.paycore.archfixture.democredential.SideDoorCredentialWriter::class.java
        violationsOf(demoCredentialWritersOnlyFromIssueService(), "com.paycore.archfixture.democredential", fixture).let { details ->
            details.any { it.contains(fixture.simpleName) && it.contains("DemoCredentialProjectionWriter") } shouldBe true
            details.any { it.contains(fixture.simpleName) && it.contains("DemoCredentialIssuanceStore") } shouldBe true
        }
    }
}) {
    companion object {
        const val BASE_PACKAGE = "com.paycore.payment"

        const val DEMO_PROJECTION_WRITER = "$BASE_PACKAGE.payment.application.required.DemoCredentialProjectionWriter"
        const val DEMO_ISSUANCE_STORE = "$BASE_PACKAGE.payment.application.required.DemoCredentialIssuanceStore"

        /** DEMO-SEED-001 I-22: 쓰기 Port 사용처 제한. 허용 = 발급 서비스(중첩·companion 포함)·두 JPA 구현(중첩 포함). 대상 범위는 `com.paycore..`(위반 fixture 포함). */
        fun demoCredentialWritersOnlyFromIssueService(): com.tngtech.archunit.lang.ArchRule {
            val allowed = listOf(
                "$BASE_PACKAGE.payment.application.DemoCredentialIssueService",
                "$BASE_PACKAGE.payment.adapter.persistence.JpaDemoCredentialProjectionWriter",
                "$BASE_PACKAGE.payment.adapter.persistence.JpaDemoCredentialIssuanceStore",
            )
            val permitted = com.tngtech.archunit.base.DescribedPredicate.describe<com.tngtech.archunit.core.domain.JavaClass>("the issue service or its JPA implementations") { c ->
                allowed.any { c.name == it || c.name.startsWith("$it$") } || c.name == DEMO_PROJECTION_WRITER || c.name == DEMO_ISSUANCE_STORE
            }
            return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().resideInAPackage("com.paycore..")
                .and(com.tngtech.archunit.base.DescribedPredicate.not(permitted))
                .should().dependOnClassesThat().haveFullyQualifiedName(DEMO_PROJECTION_WRITER)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(DEMO_ISSUANCE_STORE)
                .`as`("only DemoCredentialIssueService and its JPA implementations depend on the Demo credential write ports")
        }

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

        /** 취소 operation 영속 복원은 persistence adapter(Mapper)만(CANCEL-001 F-1). */
        fun paymentCancellationRestoredOnlyByPersistence(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("$BASE_PACKAGE.payment.adapter.persistence..")
                .should().accessTargetWhere(accessTo("target restores a cancellation", mapOf("$DOMAIN.PaymentCancellation\$Companion" to setOf("reconstitute"))))
                .allowEmptyShould(true)
                .`as`("only the persistence adapter restores a PaymentCancellation")

        fun cancelServicesIndependentOfAdmission(names: Set<String>): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("cancel services") { c: com.tngtech.archunit.core.domain.JavaClass -> c.name in names })
                .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe("admission, entitlement head, eligibility or daily limit ports") { c: com.tngtech.archunit.core.domain.JavaClass ->
                        c.name in setOf(
                            "$BASE_PACKAGE.payment.application.required.AuthorizationAdmissionPort", "$BASE_PACKAGE.payment.application.required.EntitlementHeadLockPort",
                            "$BASE_PACKAGE.payment.application.required.PaymentEligibilityPort", "$BASE_PACKAGE.payment.application.required.DailyLimitUsageRepository",
                        ) || c.packageName.startsWith("$BASE_PACKAGE.entitlement")
                    },
                )
                .`as`("cancel services never evaluate new-payment rights or daily limits (K-3)")

        private const val APP = "$BASE_PACKAGE.payment.application"
        private const val ADMIN_WEB = "$BASE_PACKAGE.payment.adapter.web.admin"

        /** ADMIN-PAYMENT-OPS-001 §4.8: 인증된 ADMIN 진입 컨트롤러(정확 FQN) — 4개 + TXN-OPS-001 거래 조회 1개. */
        val ADMIN_ENTRY_CONTROLLERS = listOf(
            "$ADMIN_WEB.OperatorCancelTargetController", "$ADMIN_WEB.AdminOperatorCancellationController", "$ADMIN_WEB.OperatorDecisionController",
            "$ADMIN_WEB.OperatorDeadlineConfirmationController", "$ADMIN_WEB.AdminPaymentController",
        )

        /** 정확 FQN 또는 그 Kotlin 합성 중첩 클래스(`<FQN>$…` — Companion·WhenMappings·람다)인가. */
        private fun exactly(c: com.tngtech.archunit.core.domain.JavaClass, names: Collection<String>) = names.any { c.name == it || c.name.startsWith("$it\$") }

        /** [target]에 의존하는 클래스는 [allowed](정확 FQN + 합성 중첩)뿐. */
        private fun onlyFrom(target: String, allowed: List<String>, description: String): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("classes other than ${allowed.map { it.substringAfterLast('.') }}") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    !exactly(c, allowed + target)
                })
                .should().dependOnClassesThat().haveFullyQualifiedName(target)
                .allowEmptyShould(true)
                .`as`(description)

        /**
         * §4.8 규칙 11개 중 의존 제한 9개(이름 → 규칙). 경로 규칙([adminEntryMappingDefects])과 기한 확인 미시작 규칙([deadlineConfirmationNeverStartsCancel] — 대상 FQN 인자)은 따로.
         * `operatorCommandConstructedOnlyAtEntry`의 허용에 명세 목록 외 정확 FQN 2개(엄격 본문 해석기·기한 확인 컨트롤러)를 더했다(I-57 — 명령을 만드는 곳이 해석기이고 기한 확인도 같은 명령을 쓴다).
         */
        fun operatorEntryRules(): Map<String, com.tngtech.archunit.lang.ArchRule> = linkedMapOf(
            "operatorCorrectionCancelOnlyFromAdminEntry" to onlyFrom(
                "$APP.provided.OperatorCorrectionCancelUseCase", listOf("$APP.OperatorCorrectionCancelService", "$ADMIN_WEB.AdminOperatorCancellationController"),
                "only the authenticated admin entry adapter calls OperatorCorrectionCancelUseCase",
            ),
            "operatorCancelTargetQueryOnlyFromAdminEntry" to onlyFrom(
                "$APP.provided.OperatorCancelTargetQuery", listOf("$APP.OperatorCorrectionCancelService", "$ADMIN_WEB.OperatorCancelTargetController"),
                "only the authenticated admin entry adapter calls OperatorCancelTargetQuery",
            ),
            "operatorDecisionQueryOnlyFromAdminEntry" to onlyFrom(
                "$APP.provided.OperatorDecisionQuery", listOf("$APP.OperatorDecisionQueryService", "$ADMIN_WEB.OperatorDecisionController"),
                "only the authenticated admin entry adapter calls OperatorDecisionQuery",
            ),
            // TXN-OPS-001 §3: 관리자 거래 조회(비권위 읽기)는 인증된 ADMIN 진입 adapter의 정확 FQN만 부른다.
            "adminPaymentQueryOnlyFromAdminEntry" to onlyFrom(
                "$APP.provided.AdminPaymentQuery", listOf("$APP.AdminPaymentQueryService", "$ADMIN_WEB.AdminPaymentController"),
                "only the authenticated admin entry adapter calls AdminPaymentQuery (TXN-OPS-001)",
            ),
            "operatorCancelMalformedRecorderOnlyFromAdminEntry" to onlyFrom(
                "$APP.provided.OperatorCancelMalformedRecorder",
                listOf("$APP.OperatorCancelMalformedAuditService", "$ADMIN_WEB.AdminOperatorCancellationController", "$ADMIN_WEB.OperatorDeadlineConfirmationController"),
                "only the authenticated admin entry adapters record malformed operator commands",
            ),
            "deadlineConfirmationOnlyFromAdminEntry" to onlyFrom(
                "$APP.provided.ConfirmOperatorApprovalDeadlineUseCase", listOf("$APP.OperatorDeadlineConfirmationService", "$ADMIN_WEB.OperatorDeadlineConfirmationController"),
                "only the authenticated admin entry adapter calls ConfirmOperatorApprovalDeadlineUseCase (rev 0.4 L-1)",
            ),
            "startOperatorOnlyFromOperatorService" to com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("classes other than the operator service") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    !exactly(c, listOf("$APP.OperatorCorrectionCancelService", "$APP.CancelStartService"))
                })
                .should().accessTargetWhere(accessTo("CancelStartService.startOperator", mapOf("$APP.CancelStartService" to setOf("startOperator"))))
                .allowEmptyShould(true).`as`("only OperatorCorrectionCancelService calls CancelStartService.startOperator"),
            "operatorServiceConcreteOnlyFromSpringConfig" to com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("classes other than the services and Spring configuration") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    !exactly(c, listOf("$APP.OperatorCorrectionCancelService", "$APP.OperatorCancelMalformedAuditService")) &&
                        !c.isAnnotatedWith("org.springframework.context.annotation.Configuration")
                })
                .should().dependOnClassesThat().haveFullyQualifiedName("$APP.OperatorCorrectionCancelService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("$APP.OperatorCancelMalformedAuditService")
                .allowEmptyShould(true).`as`("only the services themselves and Spring configuration depend on the concrete operator services"),
            "operatorCommandConstructedOnlyAtEntry" to operatorCommandConstructedOnlyAtEntry(),
            "adminEntryControllersNotReferenced" to com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("classes other than the entry controllers") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    !exactly(c, ADMIN_ENTRY_CONTROLLERS)
                })
                .should().dependOnClassesThat().haveNameMatching(ADMIN_ENTRY_CONTROLLERS.joinToString("|", "^(", ")(\\$.*)?$") { java.util.regex.Pattern.quote(it) })
                .allowEmptyShould(true).`as`("no main class references the admin entry controllers"),
        )

        /**
         * 명령·운영자 개시자를 **만드는 모든 호출**(생성자, data class `copy`·`copy$default`, 그 타입을 반환하는 메서드 — rev 0.3 L-c)은 진입 경로·조정 서비스·두 타입 자신·domain·persistence
         * Mapper뿐.
         */
        fun operatorCommandConstructedOnlyAtEntry(): com.tngtech.archunit.lang.ArchRule {
            val types = setOf("$APP.provided.OperatorCorrectionCancelCommand", "$DOMAIN.CancelInitiator\$Operator")
            val allowed = listOf(
                "$ADMIN_WEB.AdminOperatorCancellationController", "$ADMIN_WEB.AdminOperatorCancellationRequestParser", "$ADMIN_WEB.OperatorDeadlineConfirmationController",
                "$APP.OperatorCorrectionCancelService", "$APP.CancelStartService",
            ) + types
            val creates = com.tngtech.archunit.base.DescribedPredicate.describe("creates an operator command or initiator") { call: com.tngtech.archunit.core.domain.JavaCall<*> ->
                val t = call.target
                val owner = t.owner.fullName
                val returns = t.rawReturnType.fullName
                (owner in types && (t.name == "<init>" || kotlinSourceName(t.name) == "copy")) || returns in types
            }
            return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("classes outside the entry, services, domain and persistence") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    !exactly(c, allowed) && !c.packageName.startsWith(DOMAIN) && !c.packageName.startsWith("$BASE_PACKAGE.payment.adapter.persistence")
                })
                .should().callCodeUnitWhere(creates)
                .allowEmptyShould(true).`as`("only the admin entry, the operator services, the two types, the domain and persistence mappers create operator commands")
        }

        /**
         * rev 0.4 L-2: [target](+ 합성 중첩)은 취소를 시작·잠금·예약하지 않는다 — 쓰기·잠금 계열 메서드 호출과 시작 단계 클래스 의존 금지(조회 `findIdByApprovalId`·`find`는 허용).
         */
        fun deadlineConfirmationNeverStartsCancel(target: String): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("the deadline confirmation service") { c: com.tngtech.archunit.core.domain.JavaClass -> exactly(c, listOf(target)) })
                .should().accessTargetWhere(
                    accessTo(
                        "cancel writes, locks or starts",
                        mapOf(
                            "$APP.required.PaymentCancellationRepository" to setOf("insertNew", "lock", "update"),
                            "$APP.required.ReturnBalanceRepository" to setOf("insertNew", "update", "lockByPaymentId"),
                            "$APP.required.CancellationRecoveryScheduleStore" to setOf("insert"),
                            "$DOMAIN.PaymentCancellation\$Companion" to setOf("open"), "$DOMAIN.PaymentCancellation" to setOf("open"),
                            "$DOMAIN.ReturnBalance\$Companion" to setOf("open"), "$DOMAIN.ReturnBalance" to setOf("reserve", "open"),
                        ),
                    ),
                )
                .orShould().dependOnClassesThat().haveNameMatching(
                    listOf(
                        "$DOMAIN.CancelStart", "$APP.CancelStartService", "$APP.CancellationDispatchService",
                        // review L-1: 대상 잠금·Payment·승인 저장소 자체에 의존하지 않는다(취소를 시작할 재료를 쥘 수 없다).
                        "$APP.required.OrderPaymentControlRepository", "$APP.required.PaymentRepository", "$APP.required.PaymentAuthorizationRepository",
                    ).joinToString("|", "^(", ")(\\$.*)?$") { java.util.regex.Pattern.quote(it) },
                )
                .allowEmptyShould(true).`as`("the deadline confirmation never starts, locks or reserves a cancellation (rev 0.4 L-2)")

        /** §4.8 `adminEntryMappedUnderAdminPath`: 결함 목록(빈 목록 = 통과) — @RestController가 아니거나 클래스 @RequestMapping이 `/internal/v1/admin/`로 시작하지 않음. */
        fun adminEntryMappingDefects(controllers: List<com.tngtech.archunit.core.domain.JavaClass>): List<String> = controllers.mapNotNull { c ->
            val rest = c.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            val paths = c.tryGetAnnotationOfType(org.springframework.web.bind.annotation.RequestMapping::class.java).orElse(null)
                ?.let { (it.value.toList() + it.path.toList()) }.orEmpty()
            if (rest && paths.isNotEmpty() && paths.all { it.startsWith("/internal/v1/admin/") }) null else c.name
        }

        /** checkpoint L-4: [target](+ 합성 중첩)은 String·배열 동등 비교(Kotlin `==` 포함)를 호출하지 않는다. */
        fun adminSignatureConstantTimeCompare(target: String): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("the admin call signature") { c: com.tngtech.archunit.core.domain.JavaClass -> exactly(c, listOf(target)) })
                .should().callMethodWhere(
                    com.tngtech.archunit.base.DescribedPredicate.describe("String/array equality") { call: com.tngtech.archunit.core.domain.JavaMethodCall ->
                        val owner = call.target.owner.fullName
                        val name = kotlinSourceName(call.target.name)
                        (owner == "java.lang.String" && name in setOf("equals", "contentEquals", "equalsIgnoreCase")) ||
                            (owner == "java.util.Arrays" && name == "equals") ||
                            (owner == "kotlin.jvm.internal.Intrinsics" && name == "areEqual") ||
                            (owner.startsWith("kotlin.text.StringsKt") && name in setOf("equals", "contentEquals")) ||
                            (owner.startsWith("kotlin.collections.ArraysKt") && name in setOf("contentEquals"))
                    },
                )
                .allowEmptyShould(true).`as`("the admin call signature compares only with MessageDigest.isEqual")

        /**
         * CP-QUERY-001 CQ-06: `PaymentStatusReader`(비권위 조회 Port)에 의존하는 main 클래스는 Port 자신·조회 서비스·jOOQ 구현뿐이다(명령 서비스·다른 adapter가 조회 view로 판정하지 못하게).
         * 시험 소스는 대상이 아니다(classes는 DO_NOT_INCLUDE_TESTS).
         */
        fun paymentStatusReaderOnlyForQueries(): com.tngtech.archunit.lang.ArchRule {
            val port = "$BASE_PACKAGE.payment.application.required.PaymentStatusReader"
            return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName(port)
                .and().doNotHaveFullyQualifiedName("$BASE_PACKAGE.payment.application.PaymentStatusQueryService")
                .and().doNotHaveFullyQualifiedName("$BASE_PACKAGE.payment.adapter.query.JooqPaymentStatusReader")
                .should().dependOnClassesThat().haveFullyQualifiedName(port)
                .`as`("only PaymentStatusQueryService (and the jOOQ implementation) depends on PaymentStatusReader")
        }

        /** WEBHOOK-001 m-5: Apache HttpClient의 `useSystemProperties()`를 부르는 클래스가 없다. */
        fun httpClientWithoutSystemProperties(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe("HttpClient useSystemProperties()") { call: com.tngtech.archunit.core.domain.JavaMethodCall ->
                    call.target.name == "useSystemProperties" && call.target.owner.name.startsWith("org.apache.hc.")
                })
                .`as`("no HttpClient reads JVM system properties (proxy, trust store)")

        /** 가맹점 Webhook 타입(이름 접두로 식별 — 같은 payment 모듈 안). */
        private val WEBHOOK_TYPE_PREFIXES = listOf(
            "MerchantNotification", "MerchantWebhook", "FanOutMerchantNotifications", "OutboxNotification", "NotificationSubject", "NotifiedPayment",
            "PendingOutboxNotification", "UnreadableNotificationSource", "SecureRandomMerchantWebhook", "WebhookRetrySchedule",
            // S3 delivery 타입(Webhook 경로 내부 — 업무 서비스가 의존하면 안 된다).
            "ClaimedDelivery", "DeliveryAttemptResult", "DeliveryRecordOutcome", "StoredMerchantWebhookEvent", "WebhookSigningKey", "DeliverMerchantWebhooks", "WebhookAddress",
        )

        private fun isWebhookType(c: com.tngtech.archunit.core.domain.JavaClass): Boolean {
            val top = c.name.substringAfterLast('.').substringBefore('$')
            return WEBHOOK_TYPE_PREFIXES.any { top.startsWith(it) }
        }

        /** WEBHOOK-001 WH-02·n-6: application(업무 서비스)의 Webhook 밖 클래스는 Webhook 타입에 의존하지 않는다(시험 fixture 패키지도 같은 규칙으로 검사). */
        fun businessServicesIndependentOfMerchantWebhook(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("non-webhook application or fixture classes") { c: com.tngtech.archunit.core.domain.JavaClass ->
                    (c.packageName.startsWith("$BASE_PACKAGE.payment.application") || c.packageName.startsWith("com.paycore.archfixture.webhook")) && !isWebhookType(c)
                })
                .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe("merchant webhook types") { c: com.tngtech.archunit.core.domain.JavaClass -> isWebhookType(c) })
                .`as`("business application services do not depend on merchant webhook ports or types")

        /** CP-QUERY-001 검토 #1 n-5: `PaymentStatusQuery`에 의존하는 main 클래스는 Use Case 자신·구현 서비스·`payment.adapter.web` 아래뿐(공개 조회 진입점 전용). */
        fun paymentStatusQueryOnlyFromWeb(): com.tngtech.archunit.lang.ArchRule {
            val query = "$BASE_PACKAGE.payment.application.provided.PaymentStatusQuery"
            return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName(query)
                .and().doNotHaveFullyQualifiedName("$BASE_PACKAGE.payment.application.PaymentStatusQueryService")
                .and().resideOutsideOfPackage("$BASE_PACKAGE.payment.adapter.web..")
                .should().dependOnClassesThat().haveFullyQualifiedName(query)
                .`as`("only payment.adapter.web calls PaymentStatusQuery")
        }

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
        const val CANCELLATION_PORT = "$BASE_PACKAGE.payment.application.required.CancellationPort"
        const val CANCEL_INQUIRY_PORT = "$BASE_PACKAGE.payment.application.required.CancelInquiryPort"

        fun cancellationPortsImplementedOnlyBySimulatorAdapter(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that().implement(CANCELLATION_PORT).or().implement(CANCEL_INQUIRY_PORT)
                .should().resideInAPackage("$BASE_PACKAGE..adapter.integration.institution..").andShould().haveSimpleNameStartingWith("Simulator")
                .`as`("only the Simulator adapter in adapter.integration.institution implements CancellationPort/CancelInquiryPort (no REAL VAN adapter)")

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
         * D-AUTHN-006(승인 편차 조건, 검토 m-2): 결제창 web 클래스(`adapter.web`의 `Checkout*`)는 provided Use Case·자기 패키지·JDK/Kotlin·Spring·Servlet·SLF4J에만
         * (Thymeleaf는 CHECKOUT-REACT-001에서 제거 — 허용 목록에서도 뺐다)
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
            "java.", "kotlin.", "jakarta.servlet.", "org.slf4j.", "tools.jackson.", "io.micrometer.", "org.jetbrains.annotations.",
        ) + listOf("web", "http", "stereotype", "context", "core", "beans", "security", "ui", "util", "validation").map { "org.springframework.$it." } +
            // 검토 #4 n-1: Boot는 필요한 곳만(설정 바인딩·web server·자동 구성) — boot.jdbc 등은 허용하지 않는다.
            listOf("context.properties", "web", "autoconfigure").map { "org.springframework.boot.$it." }

        /** 검토 #4 n-1: JDK 안에서도 JDBC(`java.sql`·`javax.sql`)는 결제창 web 클래스에 허용하지 않는다. */
        private val CHECKOUT_WEB_DENIED_PREFIXES = listOf("java.sql.", "javax.sql.")

        private fun topLevelName(c: com.tngtech.archunit.core.domain.JavaClass): String = c.name.substringAfterLast('.').substringBefore('$')

        private fun checkoutWebAllowed(webPackage: String, tomcatConnector: Boolean) =
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "provided Use Cases, Checkout web classes, listed web helpers, JDK/Kotlin, the Spring web stack, Servlet, SLF4J, Jackson, Micrometer" +
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

        /** CHECKOUT-REACT-001 §9: payment-api는 Thymeleaf를 쓰지 않는다(AUTHN-001 §17.3 "web adapter만" 규칙을 대체 — 범위가 더 넓다). */
        fun noThymeleaf(): com.tngtech.archunit.lang.ArchRule =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().resideInAPackage("org.thymeleaf..")
                .`as`("no payment-api class depends on Thymeleaf")

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
                // CANCEL-001 §6.6: 취소 Aggregate·전이 domain 서비스도 기관 adapter가 부르지 않는다(관찰만 돌려준다).
                "PaymentCancellation", "CancelAttempt", "ReturnBalance", "CancelStart", "CancellationResolution",
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
