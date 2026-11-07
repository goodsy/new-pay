package com.paycore.admin.architecture

import com.paycore.admin.InternalAdminApplication
import com.paycore.admin.archfixture.adapter.batch.JdbcTemplateInBatchRunnerFixture
import com.paycore.admin.archfixture.adapter.web.AuditQueryInWebAdapterFixture
import com.paycore.admin.archfixture.adapter.web.EntityManagerInWebAdapterFixture
import com.paycore.admin.archfixture.application.ReaderInCommandServiceFixture
import com.paycore.admin.archfixture.application.DecisionReconciliationResendFixture
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import com.paycore.admin.security.archfixture.DslContextInSecurityFixture
import com.paycore.admin.security.archfixture.SessionRepositoryOutsideSessionPackageFixture
import com.paycore.admin.security.session.archfixture.SessionRepositoryInSessionPackageFixture
import io.kotest.matchers.collections.shouldContainAll
import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.ApplicationModules

/**
 * internal-admin 아키텍처 규칙(PayCoreArchitectureRules — 규칙 자체의 동작 증거는 libs/test-support meta-test).
 *
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. S3부터 account·audit 모듈이 있어 대부분 false이고, 아직 대상이 없는 규칙(batch·Spring Data)만
 * true로 둔다. 대상이 생기면 그 규칙의 값을 false로 바꾼다.
 */
class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    // 계층 소속 규칙 예외(허용 목록): security는 업무 모듈이 아닌 app 공통 보안 구성(SecurityFilterChain)이다.
    // 공통 규칙은 crossCutting 패키지의 영속 기술을 허용하므로, security의 영속 기술 금지는 아래 교체 규칙(F-1)이 따로 강제한다.
    val rules = PayCoreArchitectureRules(BASE_PACKAGE, listOf(GENERATED_JOOQ_PACKAGE), crossCuttingPackages = listOf("$BASE_PACKAGE.security"))

    // ADMIN-BOOT-001 §3.3 F-1(Coordinator 승인 기대값 변경 — D-ADMIN-005 ADMIN DB 소유): 이전 규칙 "internal-admin has no database access
    // dependencies"(전체 패키지의 java.sql·javax.sql·JPA·Hibernate·jOOQ·Spring JDBC 금지)를 **허용 패키지 정확 열거**로 교체한다.
    // - 영속 기술: <module>.adapter.persistence만(공통 규칙 persistenceTechnologyOnlyInPersistenceAdapter).
    // - jOOQ: <module>.adapter.query만(공통 규칙 jooqOnlyInQueryAdapter).
    // - security(공통 구성, security.session 포함): 영속 기술·jOOQ 금지(아래 securityUsesNoPersistence). S0 n-D 결과 security.session의
    //   Spring Session JDBC 구성에 DataSource·spring-jdbc 타입이 필요 없어 한정 허용 행을 두지 않는다.
    // - Spring Session 타입(org.springframework.session..): security.session만(아래 springSessionOnlyInSessionPackage).
    // - 부트스트랩·복구 runner(account.adapter.batch): 공통 규칙(영속 기술 금지·provided만).
    val securityUsesNoPersistence = noClasses().that().resideInAPackage("$BASE_PACKAGE.security..")
        .should().dependOnClassesThat().resideInAnyPackage(*SECURITY_FORBIDDEN_PACKAGES)
        .`as`("security (incl. security.session) uses no persistence technology and no jOOQ")
    val springSessionOnlyInSessionPackage = noClasses().that().resideInAPackage("$BASE_PACKAGE..")
        .and().resideOutsideOfPackage("$BASE_PACKAGE.security.session..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework.session..")
        .`as`("Spring Session types are used only in $BASE_PACKAGE.security.session")

    // D-80(S3 리뷰 m-3): 감사 조회 범위의 단일 경로 — audit 조회 API(AdminAuditQuery)는 account.application(AuditViewService)만 의존한다.
    // S4 리뷰 M-1: 처음 작성본은 식별자가 점으로 시작하고 FQN에 기본 패키지가 빠져(셸 치환으로 `$BASE_PACKAGE` 소실) 아무 클래스도 검사하지 않았다.
    val auditQuerySinglePath = noClasses().that().resideInAPackage("$BASE_PACKAGE..")
        .and().resideOutsideOfPackages("$BASE_PACKAGE.account.application..", "$BASE_PACKAGE.audit..")
        .should().dependOnClassesThat().haveFullyQualifiedName("$BASE_PACKAGE.audit.application.provided.AdminAuditQuery")
        .`as`("AdminAuditQuery is used only by account.application (AuditViewUseCase) and audit itself")

    // S5(§5·§20.3 이월): jOOQ 조회 Port(`<module>.application.required.*Reader`)는 조회 서비스(`*QueryService`·감사 조회 `AuditViewService`)와 그 구현
    // (adapter.query)만 쓴다 — 명령 판정은 잠근 Aggregate(Repository)로만. 예외 1건(D-81, S4 결정): 로그인 비밀번호 단계 (a)의 비잠금 읽기
    // `AdminLoginService → AdminCredentialReader`(BCrypt를 잠금 밖에서 — (b) 잠금 TX가 security_version으로 다시 판정).
    val readerPortsOnlyInQueryServices = ArchRuleDefinition.classes().that().resideInAPackage("$BASE_PACKAGE..")
        .should(
            object : ArchCondition<JavaClass>("use jOOQ Reader ports only from query services (D-81 exception: AdminLoginService → AdminCredentialReader)") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    item.directDependenciesFromSelf.map { it.targetClass }.filter(::isReaderPort).distinctBy { it.name }.forEach { reader ->
                        if (!mayUseReader(item, reader)) events.add(SimpleConditionEvent.violated(item, "${item.name} depends on Reader port ${reader.name}"))
                    }
                }
            },
        )

    val paymentOpsClientOnlyFromAllowedServices = noClasses().that().resideInAPackage("$BASE_PACKAGE..")
        .and().haveNameNotMatching("^" + Regex.escape("$BASE_PACKAGE.approval.application.") + "(OwnerServiceApprovalService|OwnerExecutionRecoveryService)(\\$.*)?$")
        .and().resideOutsideOfPackage("$BASE_PACKAGE.paymentops.application..")
        .should().dependOnClassesThat().haveFullyQualifiedName("$BASE_PACKAGE.paymentops.application.provided.PaymentOpsClient")
        .`as`("PaymentOpsClient is used only by approval.application OwnerServiceApprovalService / OwnerExecutionRecoveryService and paymentops.application")

    // S7 리뷰 L-2: 대사(이름에 Reconciliation이 든 클래스)는 결정 목록 조회 전용 Port만 쓰고 PAYMENT에 명령(실행 POST·기한 확인)을 보내지 않는다 — 대사는 상태를 바꾸지 않는다(§4.17).
    val reconciliationNeverCommandsPayment = noClasses().that().resideInAPackage("$BASE_PACKAGE..").and().haveSimpleNameContaining("Reconciliation")
        .should().callMethodWhere(
            object : DescribedPredicate<JavaMethodCall>("PaymentOpsClient.execute or PaymentOpsClient.confirmDeadline") {
                override fun test(call: JavaMethodCall): Boolean =
                    call.targetOwner.name == "$BASE_PACKAGE.paymentops.application.provided.PaymentOpsClient" && call.name in setOf("execute", "confirmDeadline")
            },
        )
        .`as`("reconciliation never sends a PAYMENT command (execute / confirmDeadline)")

    test("Spring Modulith module structure is valid") {
        ApplicationModules.of(InternalAdminApplication::class.java, rules.generatedCode()).verify()
    }

    test("security (incl. security.session) uses no persistence technology and no jOOQ") {
        securityUsesNoPersistence.check(classes)
    }

    test("Spring Session types are used only in security.session") {
        springSessionOnlyInSessionPackage.check(classes)
    }

    test("F-1 violation fixtures fail their rule; the security.session positive control passes") {
        val importer = ClassFileImporter()
        shouldThrow<AssertionError> {
            rules.persistenceTechnologyOnlyInPersistenceAdapter().check(importer.importClasses(EntityManagerInWebAdapterFixture::class.java))
        }.message shouldContain "EntityManagerInWebAdapterFixture"
        shouldThrow<AssertionError> {
            rules.persistenceTechnologyOnlyInPersistenceAdapter().check(importer.importClasses(JdbcTemplateInBatchRunnerFixture::class.java))
        }.message shouldContain "JdbcTemplateInBatchRunnerFixture"
        shouldThrow<AssertionError> {
            securityUsesNoPersistence.check(importer.importClasses(DslContextInSecurityFixture::class.java))
        }.message shouldContain "DslContextInSecurityFixture"
        shouldThrow<AssertionError> {
            springSessionOnlyInSessionPackage.check(importer.importClasses(SessionRepositoryOutsideSessionPackageFixture::class.java))
        }.message shouldContain "SessionRepositoryOutsideSessionPackageFixture"
        // 허용 대조: security.session의 Spring Session 구성은 영속 기술 금지를 통과하고, Spring Session 규칙에서는 검사 대상 밖이다(빈 대상 명시 허용).
        val allowed = importer.importClasses(SessionRepositoryInSessionPackageFixture::class.java)
        securityUsesNoPersistence.check(allowed)
        springSessionOnlyInSessionPackage.allowEmptyShould(true).check(allowed)
    }

    test("GENERATED_JOOQ_PACKAGE matches the build's generateJooqMain package; business tables are generated, Spring Session tables are not") {
        System.getProperty("paycore.jooq.mainPackage") shouldBe GENERATED_JOOQ_PACKAGE
        val generated = classes.filter { it.packageName == "$GENERATED_JOOQ_PACKAGE.tables" }.map { it.simpleName }.toSet()
        generated shouldContainAll setOf("AdminAccount", "AdminApprovalRequest", "AdminAuditEvent", "AdminRoleEvent", "AdminEnrollmentCode")
        generated.filter { it.startsWith("AdminSession") } shouldBe emptyList()
    }

    test("classes in module packages reside in known layers") {
        // 대상 있음: console.adapter.web(SPA 서빙, ADMIN-BOOT-001). security는 허용 목록으로 제외.
        rules.classesResideInKnownLayers().allowEmptyShould(false).check(classes)
    }

    test("base package root holds only the application entry point") {
        // 대상 있음: InternalAdminApplication과 Kotlin facade InternalAdminApplicationKt.
        rules.baseRootHoldsOnlyApplicationEntryPoint().allowEmptyShould(false).check(classes)
    }

    test("domain is pure Kotlin") {
        // 대상 있음(S3): account.domain·audit.domain.
        rules.domainIsPure().allowEmptyShould(false).check(classes)
    }

    test("domain/application do not depend on base root or cross-cutting packages") {
        // 대상 있음(S3): account·audit의 domain/application.
        rules.coreDoesNotDependOnBaseRootOrCrossCutting().allowEmptyShould(false).check(classes)
    }

    test("application depends only on ports") {
        // 대상 있음(S3): account.application·audit.application.
        rules.applicationDependsOnlyOnPorts().allowEmptyShould(false).check(classes)
    }

    test("persistence technology (JPA/JDBC/DataSource/Spring Data repository) only in adapter.persistence") {
        // 대상 있음: InternalAdminApplication(+Kt)·console. security(공통 구성)는 이 규칙에서 제외되고 securityUsesNoPersistence가 금지한다.
        rules.persistenceTechnologyOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("exactly one @SpringBootApplication, directly in the base package") {
        rules.springBootApplicationIsSingleAtBaseRoot().allowEmptyShould(false).check(classes)
    }

    test("test sources (TechSmoke) are excluded from the architecture import (DO_NOT_INCLUDE_TESTS)") {
        // TechSmokeApiController(@RestController 대역, 테스트 소스 — ADMIN-BOOT-001 §3.3 F-3)는 테스트까지 import하면
        // controllersOnlyInWebAdapter가 잡는다.
        val withTests = ClassFileImporter().importPackages(BASE_PACKAGE)
        withTests.any { it.packageName == "$BASE_PACKAGE.techsmoke" } shouldBe true
        classes.any { it.packageName.startsWith("$BASE_PACKAGE.techsmoke") } shouldBe false
        val error = shouldThrow<AssertionError> {
            rules.controllersOnlyInWebAdapter().check(withTests)
        }
        error.message shouldContain "TechSmokeApiController"
    }

    test("JPA mappings only in adapter.persistence") {
        // 대상 있음(S3 — S2 리뷰 n-3): account·audit adapter.persistence의 JPA Entity(JpaAdminAccountEntity 등).
        rules.jpaMappingsOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("Spring Data repositories only in adapter.persistence") {
        // 빈 대상: Spring Data Repository를 쓰지 않는다(EntityManager 직접 — 잠금 읽기·조건부 UPDATE를 Port 안에서 명시하려고, §5).
        rules.springDataRepositoriesOnlyInPersistenceAdapter().allowEmptyShould(true).check(classes)
    }

    test("Spring Batch types only in adapter.batch") {
        rules.springBatchOnlyInBatchAdapter().allowEmptyShould(false).check(classes)
    }

    test("controllers only in adapter.web") {
        // 대상 있음: console.adapter.web.SpaFallbackController(TechSmoke API 대역은 테스트 소스라 제외).
        rules.controllersOnlyInWebAdapter().allowEmptyShould(false).check(classes)
    }

    test("jOOQ only in adapter.query") {
        rules.jooqOnlyInQueryAdapter().allowEmptyShould(false).check(classes)
    }

    test("integration adapter types are not used outside adapter.integration") {
        rules.integrationAdapterIsEncapsulated().allowEmptyShould(false).check(classes)
    }

    test("web adapter does not use persistence/query/integration adapters or application.required") {
        // 대상 있음: console.adapter.web.
        rules.webAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    test("batch adapter uses only application.provided (no adapters/required ports/Spring Data/JPA)") {
        // 대상 있음(S5·S6): approval.adapter.batch(만료 job), account.adapter.batch(1회성 명령 runner·명령 줄·출력).
        rules.batchAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    test("@NamedInterface only on application.provided package-info") {
        // 대상 있음(S3): account·audit application.provided package-info.
        rules.namedInterfacesOnlyOnProvidedPackages().allowEmptyShould(false).check(classes)
    }

    test("the audit query API is reached only through account's AuditViewUseCase (S3 review m-3 — one scope path from the current account row)") {
        // 대상 있음: account.application(AuditViewService)·audit 밖의 모든 클래스(웹 adapter·security 등) — 빈 대상 금지.
        auditQuerySinglePath.allowEmptyShould(false).check(classes)
    }

    test("D-80 violation fixture (web adapter injecting AdminAuditQuery) fails the single-path rule (S4 review M-1)") {
        shouldThrow<AssertionError> {
            auditQuerySinglePath.check(ClassFileImporter().importClasses(AuditQueryInWebAdapterFixture::class.java))
        }.message shouldContain "AuditQueryInWebAdapterFixture"
    }

    test("jOOQ Reader ports are used only by query services and their adapter.query implementations (S5 — §5 CQRS)") {
        // 대상 있음: ApprovalRequestListReader·AdminAuditReader·AdminSessionReader·AdminCredentialReader와 그 사용처.
        readerPortsOnlyInQueryServices.allowEmptyShould(false).check(classes)
        classes.filter { isReaderPort(it) }.map { it.simpleName }.toSet() shouldContainAll
            setOf("ApprovalRequestListReader", "AdminAuditReader", "AdminSessionReader", "AdminCredentialReader")
    }

    test("Reader violation fixture (a command service deciding from the query model) fails the Reader rule") {
        shouldThrow<AssertionError> {
            readerPortsOnlyInQueryServices.check(
                ClassFileImporter().importClasses(ReaderInCommandServiceFixture::class.java, com.paycore.admin.approval.application.required.ApprovalRequestListReader::class.java),
            )
        }.message shouldContain "ReaderInCommandServiceFixture"
    }

    test("account never depends on approval (dependency direction approval → account·audit — §4.1)") {
        noClasses().that().resideInAPackage("$BASE_PACKAGE.account..")
            .should().dependOnClassesThat().resideInAPackage("$BASE_PACKAGE.approval..")
            .allowEmptyShould(false).check(classes)
    }

    test("ADMIN-PAYMENT-OPS-001 §4.19 ①: oct1 is computed by ADMIN's own paymentops.domain — no PAYMENT code is on the classpath or referenced") {
        runCatching { Class.forName("com.paycore.payment.payment.domain.OperatorCancelTarget") }.isFailure shouldBe true
        noClasses().that().resideInAPackage("$BASE_PACKAGE..")
            .should().dependOnClassesThat().resideInAPackage("com.paycore.payment..")
            .allowEmptyShould(false).check(classes)
        classes.filter { it.packageName == "$BASE_PACKAGE.paymentops.domain" }.map { it.simpleName }.toSet() shouldContainAll setOf("OperatorCancelTargetFingerprint")
    }

    test("ADMIN-PAYMENT-OPS-001 §5: the new owner-service domain and JPA adapter types are in place (domain purity itself is the 'domain is pure Kotlin' rule)") {
        val persistence = classes.filter { it.packageName == "$BASE_PACKAGE.approval.adapter.persistence" }.map { it.simpleName }.toSet()
        persistence shouldContainAll setOf("JpaOperatorCancelCommandEntity", "JpaOwnerExecutionEntity", "JpaOwnerExecutionRepository", "JpaOperatorCancelCommandStore")
        val domain = classes.filter { it.packageName == "$BASE_PACKAGE.approval.domain" }.map { it.simpleName }.toSet()
        domain shouldContainAll setOf("ApprovalTarget", "OwnerExecution", "OperatorCancelRequestSnapshot", "PinnedPaymentTarget", "ApprovalExecutionModel", "ApprovalTargetKind")
    }

    test("ADMIN-PAYMENT-OPS-001 §4.7: the PAYMENT client port is used only by OwnerServiceApprovalService, OwnerExecutionRecoveryService (S6) and paymentops.application") {
        // S7 리뷰 L-2: 대사 서비스는 PaymentOpsClient가 아니라 결정 목록 조회 전용 Port(PaymentDecisionListQuery)만 의존한다.
        paymentOpsClientOnlyFromAllowedServices.allowEmptyShould(false).check(classes)
        classes.single { it.simpleName == "OperatorDecisionReconciliationService" }.directDependenciesFromSelf.map { it.targetClass.simpleName }.toSet() shouldContainAll
            setOf("PaymentDecisionListQuery")
    }

    test("S7 review L-2: reconciliation never calls PaymentOpsClient.execute / confirmDeadline; the violation fixture fails the rule") {
        reconciliationNeverCommandsPayment.allowEmptyShould(false).check(classes)
        shouldThrow<AssertionError> {
            reconciliationNeverCommandsPayment.check(ClassFileImporter().importClasses(DecisionReconciliationResendFixture::class.java))
        }.message shouldContain "DecisionReconciliationResendFixture"
    }

    test("ADMIN-PAYMENT-OPS-001 S4 review L-1: approval never reaches into paymentops internals (domain, adapters) — only paymentops.application.provided") {
        noClasses().that().resideInAPackage("$BASE_PACKAGE.approval..")
            .should().dependOnClassesThat().resideInAnyPackage("$BASE_PACKAGE.paymentops.domain..", "$BASE_PACKAGE.paymentops.adapter..", "$BASE_PACKAGE.paymentops.application.required..")
            .allowEmptyShould(false).check(classes)
    }

    test("ACQ-ADMIN-001 B1-A: the FINANCIAL operation client is used only by AcquiringOpsApprovalService, OwnerExecutionRecoveryService and financialops.application") {
        noClasses().that().resideInAPackage("$BASE_PACKAGE..")
            .and().haveNameNotMatching("^" + Regex.escape("$BASE_PACKAGE.approval.application.") + "(AcquiringOpsApprovalService|OwnerExecutionRecoveryService)(\\$.*)?$")
            .and().resideOutsideOfPackage("$BASE_PACKAGE.financialops.application..")
            .should().dependOnClassesThat().haveFullyQualifiedName("$BASE_PACKAGE.financialops.application.provided.AcquiringOpsClient")
            .allowEmptyShould(false).check(classes)
        // approval은 financialops 내부(domain·adapter·required)에 닿지 않는다 — provided만(Modulith named interface).
        noClasses().that().resideInAPackage("$BASE_PACKAGE.approval..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "$BASE_PACKAGE.financialops.domain..", "$BASE_PACKAGE.financialops.adapter..", "$BASE_PACKAGE.financialops.application.required..",
            )
            .allowEmptyShould(false).check(classes)
    }

    test("no cycles between modules") {
        // 대상 있음: security 패키지.
        rules.modulesAreFreeOfCycles().allowEmptyShould(false).check(classes)
    }
}) {
    companion object {
        const val BASE_PACKAGE = "com.paycore.admin"
        const val GENERATED_JOOQ_PACKAGE = "com.paycore.admin.jooq.generated"

        /** jOOQ 조회 Port: `<module>.application.required` 안의 `*Reader` 인터페이스. */
        fun isReaderPort(c: JavaClass): Boolean = c.isInterface && c.packageName.endsWith(".application.required") && c.simpleName.endsWith("Reader")

        fun mayUseReader(user: JavaClass, reader: JavaClass): Boolean {
            val topLevel = user.name.substringBefore('$').substringAfterLast('.')
            val pkg = user.packageName
            return when {
                pkg.endsWith(".adapter.query") || pkg.endsWith(".application.required") -> true
                pkg.endsWith(".application") && (topLevel.endsWith("QueryService") || topLevel == "AuditViewService") -> true
                pkg == "$BASE_PACKAGE.account.application" && topLevel == "AdminLoginService" && reader.simpleName == "AdminCredentialReader" -> true
                else -> false
            }
        }

        /**
         * security가 쓰면 안 되는 패키지: 공통 규칙의 영속 기술 목록(PayCoreArchitectureRules — companion이 private이라 여기 같은 값을 둔다)과 jOOQ.
         * 공통 목록이 바뀌면 함께 바꾼다.
         */
        val SECURITY_FORBIDDEN_PACKAGES = arrayOf(
            "jakarta.persistence..", "javax.persistence..", "org.hibernate..", "org.springframework.orm..", "org.springframework.jdbc..",
            "java.sql..", "javax.sql..", "org.springframework.data.repository..", "org.springframework.data.jpa..", "org.jooq..",
        )
    }
}
