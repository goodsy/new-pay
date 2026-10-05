package com.paycore.payment.architecture

import com.paycore.testsupport.architecture.PayCoreArchitectureRules
import com.tngtech.archunit.base.DescribedPredicate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.modulith.core.Violations

/**
 * jOOQ 생성 패키지를 Modulith에서 제외하는 방식이 실제로 동작하는지, 생성 클래스가 존재하는 fixture로 증명한다.
 * (payment-api main schema는 아직 비어 있어 main 생성 클래스가 0개이므로 app 자체로는 증명할 수 없다.)
 *
 * fixture(com.paycore.modulithfixture, 테스트 소스): `order` 모듈의 query adapter가 `jooq.generated.tables.OrderTable`
 * (jOOQ 생성 코드 모양)을 쓴다. 제외하지 않으면 `jooq`가 application module이 되고, 그 내부 타입 사용이 모듈 경계 위반이 된다.
 * 테스트 소스 import 방식: [TestSourceApplicationModules].
 */
class ModulithGeneratedCodeExclusionSpec : FunSpec({

    val rules = PayCoreArchitectureRules(FIXTURE, listOf("$FIXTURE.jooq.generated"))

    test("without exclusion: generated package becomes module 'jooq' and its use from 'order' violates module boundaries") {
        val modules = TestSourceApplicationModules(FIXTURE, DescribedPredicate.alwaysFalse())

        modules.getModuleByName("jooq").isPresent shouldBe true
        val violations = shouldThrow<Violations> { modules.verify() }
        violations.message shouldContain "OrderTable"
    }

    test("with PayCoreArchitectureRules.generatedCode(): no 'jooq' module and verification passes") {
        val modules = TestSourceApplicationModules(FIXTURE, rules.generatedCode())

        modules.getModuleByName("jooq").isPresent shouldBe false
        modules.getModuleByName("order").isPresent shouldBe true
        modules.verify()
    }
}) {
    private companion object {
        const val FIXTURE = "com.paycore.modulithfixture"
    }
}
