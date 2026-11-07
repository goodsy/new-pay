package com.paycore.financial.architecture

import com.paycore.financial.FinancialBatchApplication
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
 * financial-batch 아키텍처 규칙(PayCoreArchitectureRules — 규칙 자체의 동작 증거는 libs/test-support meta-test).
 *
 * allowEmptyShould(true)는 app 수준에서만, 규칙별로 명시한다. FIN-FACT-RELAY-001로 첫 업무 모듈 `paymentfact`(domain·application·adapter.web/persistence)가 생겨
 * 대상이 생긴 규칙은 모두 false로 바꿨다. 아직 대상이 없는 규칙(adapter.batch·공개 provided package-info)만 true다.
 * Batch Job 구성은 `<module>.adapter.batch`에 두고 Application use case를 호출한다(INV-09).
 */
class ArchitectureSpec : FunSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE)
    // ACQ-VIEW-001(R-1 A): jOOQ codegen 도입 — 생성 패키지는 Modulith 모듈·계층 규칙에서 제외한다(master-api·payment-api와 같다).
    val rules = PayCoreArchitectureRules(BASE_PACKAGE, listOf(GENERATED_JOOQ_PACKAGE))

    test("GENERATED_JOOQ_PACKAGE matches the build's generateJooqMain package and generated tables are the acquisition_* tables only (no BATCH_*, no payment_fact_*)") {
        System.getProperty("paycore.jooq.mainPackage") shouldBe GENERATED_JOOQ_PACKAGE
        classes.any { it.packageName == "$GENERATED_JOOQ_PACKAGE.tables" && it.simpleName == "AcquisitionFile" } shouldBe true
        classes.any { it.packageName == "$GENERATED_JOOQ_PACKAGE.tables" && it.simpleName.startsWith("Batch") } shouldBe false
        // 리뷰 MINOR-3(명세 §5.1): 생성 대상은 acquisition_* 표만 — 결제 사실 표(paymentfact 모듈 소유)는 생성하지 않는다.
        classes.filter { it.packageName == "$GENERATED_JOOQ_PACKAGE.tables" }.map { it.simpleName }.filterNot { it.contains('$') }.all { it.startsWith("Acquisition") } shouldBe true
    }

    test("Spring Modulith module structure is valid") {
        ApplicationModules.of(FinancialBatchApplication::class.java, rules.generatedCode()).verify()
    }

    test("classes in module packages reside in known layers") {
        // 대상 있음: paymentfact 모듈(FIN-FACT-RELAY-001).
        rules.classesResideInKnownLayers().allowEmptyShould(false).check(classes)
    }

    test("base package root holds only the application entry point") {
        // 대상 있음: FinancialBatchApplication과 Kotlin facade FinancialBatchApplicationKt.
        rules.baseRootHoldsOnlyApplicationEntryPoint().allowEmptyShould(false).check(classes)
    }

    test("domain is pure Kotlin") {
        // 대상 있음: paymentfact.domain(PaymentFact·투영 판정).
        rules.domainIsPure().allowEmptyShould(false).check(classes)
    }

    test("domain/application do not depend on base root or cross-cutting packages") {
        // 대상 있음: paymentfact.domain·application.
        rules.coreDoesNotDependOnBaseRootOrCrossCutting().allowEmptyShould(false).check(classes)
    }

    test("application depends only on ports") {
        // 대상 있음: paymentfact.application(Inbox 수신 Use Case·Port).
        rules.applicationDependsOnlyOnPorts().allowEmptyShould(false).check(classes)
    }

    test("persistence technology (JPA/JDBC/DataSource/Spring Data repository) only in adapter.persistence") {
        // 대상 있음: FinancialBatchApplication(+Kt)·paymentfact. 공통 구성 패키지 없음.
        rules.persistenceTechnologyOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("exactly one @SpringBootApplication, directly in the base package") {
        rules.springBootApplicationIsSingleAtBaseRoot().allowEmptyShould(false).check(classes)
    }

    test("test sources (TechSmoke) are excluded from the architecture import (DO_NOT_INCLUDE_TESTS)") {
        // TechSmokeBatchJobConfig는 DataSource를 쓰는 테스트 소스다. 테스트까지 import하면 영속 기술 규칙이 이를 잡는다.
        val withTests = ClassFileImporter().importPackages(BASE_PACKAGE)
        withTests.any { it.packageName == "$BASE_PACKAGE.techsmoke" } shouldBe true
        classes.any { it.packageName.startsWith("$BASE_PACKAGE.techsmoke") } shouldBe false
        val error = shouldThrow<AssertionError> {
            rules.persistenceTechnologyOnlyInPersistenceAdapter().check(withTests)
        }
        error.message shouldContain "TechSmokeBatchJobConfig"
    }

    test("JPA mappings only in adapter.persistence") {
        // 대상 있음: paymentfact.adapter.persistence Jpa*Entity.
        rules.jpaMappingsOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("Spring Data repositories only in adapter.persistence") {
        // 대상 있음: paymentfact.adapter.persistence *JpaRepository.
        rules.springDataRepositoriesOnlyInPersistenceAdapter().allowEmptyShould(false).check(classes)
    }

    test("Spring Batch types only in adapter.batch") {
        rules.springBatchOnlyInBatchAdapter().allowEmptyShould(false).check(classes)
    }

    test("controllers only in adapter.web") {
        // 대상 있음: PaymentFactInboxController.
        rules.controllersOnlyInWebAdapter().allowEmptyShould(false).check(classes)
    }

    test("jOOQ only in adapter.query") {
        rules.jooqOnlyInQueryAdapter().allowEmptyShould(false).check(classes)
    }

    test("ACQ-VIEW-001: adapter.query does not write with jOOQ (insert/update/delete/merge/truncate/batch/execute or record store)") {
        // 대상 있음: acquiring.adapter.query.JooqAcquisitionFileViewReader(매입 현황 관리 조회 — 읽기 전용).
        rules.queryAdapterDoesNotWriteWithJooq().allowEmptyShould(false).check(classes)
        classes.any { it.packageName == "$BASE_PACKAGE.acquiring.adapter.query" && it.simpleName == "JooqAcquisitionFileViewReader" } shouldBe true
    }

    test("ACQ-VIEW-001: the acquisition view query is entered only from the ADMIN channel controller") {
        // 비권위 관리 조회가 매입 판정·Batch 경로에 섞이지 않게 한다(provided Query를 부르는 곳은 AdminAcquisitionController 하나).
        noClasses().that().doNotHaveFullyQualifiedName("$BASE_PACKAGE.acquiring.adapter.web.admin.AdminAcquisitionController")
            .and().doNotHaveFullyQualifiedName("$BASE_PACKAGE.acquiring.application.AcquisitionFileViewService")
            // 인터페이스 자신의 Kotlin 기본 인자 구현(AcquisitionFileViewQuery$DefaultImpls)은 호출자가 아니다.
            .and().haveNameNotMatching(".*\\.AcquisitionFileViewQuery\\$.*")
            .should().dependOnClassesThat().haveFullyQualifiedName("$BASE_PACKAGE.acquiring.application.provided.AcquisitionFileViewQuery")
            .check(classes)
    }

    test("ACQ-ADMIN-001 rev 0.3 (review m-6): the re-acquisition approval and ops decision stores are used only by the execution service and their persistence adapters") {
        // 승인 기록·결정 기록 쓰기 경로는 조작 서명 채널 → ReacquisitionExecutionService(재검사 + 같은 로컬 TX) 하나뿐이다(D-ACQ-011·D-ADMIN-007, V18 INSERT 개방의 대체 방어).
        val rule = noClasses()
            .that().haveNameNotMatching("${Regex.escape("$BASE_PACKAGE.acquiring.application.ReacquisitionExecutionService")}(\\$.*)?")
            .and().haveNameNotMatching("${Regex.escape("$BASE_PACKAGE.acquiring.adapter.persistence.")}(JpaReacquisitionApprovalStore|JpaAcquisitionOpsDecisionStore)(\\$.*)?")
            // Port 자신(Kotlin DefaultImpls 등)은 호출자가 아니다.
            .and().haveNameNotMatching("${Regex.escape("$BASE_PACKAGE.acquiring.application.required.")}(ReacquisitionApprovalStore|AcquisitionOpsDecisionStore)(\\$.*)?")
            .should().dependOnClassesThat().haveFullyQualifiedName("$BASE_PACKAGE.acquiring.application.required.ReacquisitionApprovalStore")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("$BASE_PACKAGE.acquiring.application.required.AcquisitionOpsDecisionStore")
        rule.check(classes)
        // 비어 있지 않음: 실행 서비스가 실제로 두 Port에 의존한다.
        classes.get("$BASE_PACKAGE.acquiring.application.ReacquisitionExecutionService").directDependenciesFromSelf.map { it.targetClass.simpleName }.toSet()
            .containsAll(setOf("ReacquisitionApprovalStore", "AcquisitionOpsDecisionStore")) shouldBe true
        // 규칙이 실제로 잡는다: 시험 소스까지 import하면 Port를 직접 쓰는 DB 시험이 위반으로 나온다.
        val error = shouldThrow<AssertionError> { rule.check(ClassFileImporter().importPackages("$BASE_PACKAGE.acquiring")) }
        error.message shouldContain "ReacquisitionExecutionDbSpec"
    }

    test("integration adapter types are not used outside adapter.integration") {
        rules.integrationAdapterIsEncapsulated().allowEmptyShould(false).check(classes)
    }

    test("web adapter does not use persistence/query/integration adapters or application.required") {
        // 대상 있음: paymentfact.adapter.web.
        rules.webAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    test("batch adapter uses only application.provided (no adapters/required ports/Spring Data/JPA)") {
        // 대상 있음(ACQUIRING-001): acquiring.adapter.batch 매입 제출 Job(provided Use Case만 호출).
        rules.batchAdapterUsesOnlyApplication().allowEmptyShould(false).check(classes)
    }

    test("@NamedInterface only on application.provided package-info") {
        // 대상 있음(ACQUIRING-001): paymentfact.application.provided package-info(acquiring 모듈이 쓰는 공개 API).
        rules.namedInterfacesOnlyOnProvidedPackages().allowEmptyShould(false).check(classes)
    }

    test("no cycles between modules") {
        // 대상 있음: paymentfact·acquiring 두 slice(acquiring → paymentfact.provided 한 방향).
        rules.modulesAreFreeOfCycles().allowEmptyShould(false).check(classes)
    }

    test("ACQUIRING-001 2-D: Apache MINA SSHD (org.apache.sshd) is used only in adapter.integration") {
        // 대상 있음: SftpAcquirerFileChannel·AcquiringTransportSettings. Domain·application·다른 adapter는 기술 중립 Port(AcquirerFileChannel)만 본다.
        noClasses().that().resideOutsideOfPackage("..adapter.integration..").should().dependOnClassesThat().resideInAPackage("org.apache.sshd..")
            .allowEmptyShould(false).check(classes)
        classes.any { c -> c.packageName.endsWith("adapter.integration") && c.directDependenciesFromSelf.any { it.targetClass.packageName.startsWith("org.apache.sshd") } } shouldBe true
    }
}) {
    companion object {
        const val BASE_PACKAGE = "com.paycore.financial"
        const val GENERATED_JOOQ_PACKAGE = "com.paycore.financial.jooq.generated"
    }
}
