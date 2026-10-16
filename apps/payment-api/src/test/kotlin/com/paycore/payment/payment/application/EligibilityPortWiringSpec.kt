package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentEligibilityPort
import com.paycore.payment.payment.application.required.PaymentRepository
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager

/**
 * eligibility Port 배선 증거(DB 없음).
 *
 * CP-DB-001 M-1 결정: "모두 Unavailable" 같은 기본 구현을 두지 않고, Port bean이 없으면 [CreatePaymentService]가 기동하지 못한다(fail-closed).
 * MASTER-SNAPSHOT-001(CP-DB-001 review2 L-11): 운영 구현이 생겼으므로 "main 구현 0개" 기대를 **"main 구현 정확히 1개
 * (`payment.adapter.integration.EntitlementEligibilityAdapter`)"**로 바꿨다. bean 부재 시 기동 실패 시험은 유지한다. 운영 구현으로 실제 MySQL에
 * 기동하는 smoke는 PaymentApiContextSmokeSpec(EligibilityTestConfig 없음)이다.
 */
class EligibilityPortWiringSpec : FunSpec({

    test("main sources have exactly one PaymentEligibilityPort implementation: the entitlement adapter") {
        val main = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.payment")
        main.filter { it.isAssignableTo(PaymentEligibilityPort::class.java) && !it.isInterface }.map { it.name } shouldBe
            listOf("com.paycore.payment.payment.adapter.integration.EntitlementEligibilityAdapter")
    }

    test("a context with CreatePaymentService but without a PaymentEligibilityPort bean fails to start") {
        ApplicationContextRunner()
            .withBean(PlatformTransactionManager::class.java, { mockk<PlatformTransactionManager>() })
            .withBean(IdempotencyRecordStore::class.java, { mockk<IdempotencyRecordStore>() })
            .withBean(OrderPaymentControlRepository::class.java, { mockk<OrderPaymentControlRepository>() })
            .withBean(PaymentRepository::class.java, { mockk<PaymentRepository>() })
            .withBean(OutboxStore::class.java, { mockk<OutboxStore>() })
            .withConfiguration(UserConfigurations.of(CreatePaymentService::class.java))
            .run { context ->
                val failure = context.startupFailure
                (failure != null) shouldBe true
                var cause: Throwable? = failure
                while (cause != null && cause !is NoSuchBeanDefinitionException) cause = cause.cause
                (cause as NoSuchBeanDefinitionException).message shouldContain PaymentEligibilityPort::class.java.name
            }
    }
})
