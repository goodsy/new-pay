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
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.cache..", "com.github.benmanes.caffeine..", "javax.cache..")
            .`as`("entitlement does not use Spring cache, Caffeine or JCache")
            .check(classes)
        classes.filter {
            it.packageName.startsWith("$BASE_PACKAGE.entitlement") || it.simpleName in setOf("EntitlementEligibilityAdapter", "EntitlementAuthorizationAdmissionAdapter")
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
        // 빈 대상: adapter.batch가 아직 없다(payment-api에 Batch 작업 계획 없음).
        rules.batchAdapterUsesOnlyApplication().allowEmptyShould(true).check(classes)
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
        ).forEach { (fixture, targets) ->
            val details = violationsOf(rule, "com.paycore.archfixture.transition", fixture)
            details.size shouldBe targets.size
            targets.forEach { target -> details.count { it.contains(target) && targetNameEnds(it, target) } shouldBe 1 }
        }
    }

    test("institution (TCP) adapters do not depend on mutable domain aggregates or domain services (AUTH-CONTRACT-001 AC 3)") {
        val rule = institutionAdapterDoesNotMutateDomain()
        // 빈 대상: adapter.institution(또는 adapter.integration.institution)은 AUTH-001에서 생긴다. 규칙이 실제로 평가됨은 위반 fixture로 본다.
        rule.allowEmptyShould(true).check(classes)
        val fixture = com.paycore.archfixture.adapter.institution.InstitutionAdapterMutatingPayment::class.java
        shouldThrow<AssertionError> {
            rule.allowEmptyShould(false).check(fixtureClasses("com.paycore.archfixture.adapter.institution", fixture))
        }.message shouldContain fixture.simpleName
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
                            "$DOMAIN.PaymentAuthorization" to setOf("recordObservation", "markUnknown", "authorize", "fail"),
                            "$DOMAIN.AuthorizationAttempt" to setOf("beginDispatch", "abandon", "markOutcomeUnknown", "resolve"),
                        ),
                    ),
                )
                .`as`("result transitions are applied only by domain services (Payment + Authorization + control + limit together)")

        /** 기관 Adapter(`..adapter..institution..`)는 상태를 가진 domain Aggregate·domain 서비스에 의존하지 않는다 — Port 결과만 돌려준다. */
        fun institutionAdapterDoesNotMutateDomain(): com.tngtech.archunit.lang.ArchRule {
            val mutable = listOf(
                "Payment", "OrderPaymentControl", "PaymentAuthorization", "AuthorizationAttempt", "AuthenticationSession", "DailyLimitUsage",
                "AuthorizationStart", "AuthorizationResolution", "RequestedPaymentExpiry",
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
