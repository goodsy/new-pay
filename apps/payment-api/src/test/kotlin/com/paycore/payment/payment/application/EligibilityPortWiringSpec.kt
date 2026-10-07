package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.OrderPaymentControlStore
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentEligibilityPort
import com.paycore.payment.payment.application.required.PaymentStore
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager

/**
 * CP-DB-001 M-1 bean 부재 처리 결정의 증거(DB 없음).
 *
 * 결정: 운영 main에는 [PaymentEligibilityPort] 구현(bean)을 두지 않는다(구현은 MASTER-SNAPSHOT-001 → CP-API-001). "모두 Unavailable"
 * 같은 기본 구현도 만들지 않는다 — 운영 동작을 가진 대역이 운영 코드에 남는 것을 피한다. 대신 [CreatePaymentService]가 Port를 필수
 * 생성자 인자로 받으므로 Port bean이 없는 컨텍스트는 **기동 자체가 실패**한다(eligibility 검사 없이 결제가 생성되는 상태가 없다,
 * fail-closed). 시험 컨텍스트는 [EligibilityTestConfig]의 [RecordingEligibilityPort]를 등록한다.
 */
class EligibilityPortWiringSpec : FunSpec({

    test("main sources have no PaymentEligibilityPort implementation (no production bean until MASTER-SNAPSHOT-001)") {
        val main = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.paycore.payment")
        main.filter { it.isAssignableTo(PaymentEligibilityPort::class.java) && !it.isInterface }.map { it.name }.shouldBeEmpty()
    }

    test("a context with CreatePaymentService but without a PaymentEligibilityPort bean fails to start") {
        ApplicationContextRunner()
            .withBean(PlatformTransactionManager::class.java, { mockk<PlatformTransactionManager>() })
            .withBean(IdempotencyRecordStore::class.java, { mockk<IdempotencyRecordStore>() })
            .withBean(OrderPaymentControlStore::class.java, { mockk<OrderPaymentControlStore>() })
            .withBean(PaymentStore::class.java, { mockk<PaymentStore>() })
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
