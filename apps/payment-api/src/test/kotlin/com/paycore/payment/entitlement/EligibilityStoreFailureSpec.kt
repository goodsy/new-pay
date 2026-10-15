package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.T0
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.application.provided.EligibilityRequest
import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationQuery
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.payment.adapter.integration.EntitlementEligibilityAdapter
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.application.CreatePaymentFixture
import com.paycore.payment.payment.application.provided.CreatePaymentFailure
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.FailureKind
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentMethod
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.SQLException
import java.util.UUID

/**
 * CP-API-001 G-1: eligibility(권리 head `FOR SHARE`) 경로의 저장소 일시 실패가 분류된다. 이전에는 entitlement 내부 실패 타입이 payment 서비스
 * 분류를 거치지 않아 500이 됐다. 이제 head 잠금 대기 timeout은 같은 멱등키 경합이 아니므로 TEMPORARILY_UNAVAILABLE(503)이고 TX 전체가 rollback된다.
 * 운영 eligibility 구현(EligibilityTestConfig 없음)·실제 MySQL·runtime 계정.
 */
@EntitlementDbTest
class EligibilityStoreFailureSpec(
    private val context: WebApplicationContext,
    private val createPayment: CreatePaymentUseCase,
    private val db: DbAccess,
) : BehaviorSpec({

    val mvc = MockMvcBuilders.webAppContextSetup(context).build()

    Given("an Inbox transaction holds the merchant's entitlement head exclusively") {
        Then("CreatePayment waits on head FOR SHARE, times out, is TemporarilyUnavailable(LOCK_TIMEOUT) and leaves nothing; afterwards it succeeds") {
            val ids = SnapshotFixture.ids()
            send(mvc, body(SnapshotFixture.entitled(ids, revision = 1), SnapshotFixture.leaseAt(T0))).status shouldBe 200
            val order = OrderKey(Environment.LIVE, MerchantAccountId(ids.mid), MerchantOrderId("ord-" + UUID.randomUUID().toString().substring(0, 12)))
            val command = CreatePaymentFixture.command(order, OrderIntent(MerchantSiteId(ids.siteId), Money(10_000, CurrencyCode("KRW"))), at = T0.plusSeconds(5))

            db.root().use { holder ->
                holder.autoCommit = false
                holder.prepareStatement("SELECT revision FROM entitlement_head WHERE merchant_id = ? FOR UPDATE").use {
                    it.setString(1, ids.merchantId)
                    it.executeQuery().close()
                }
                val failure = shouldThrow<CreatePaymentFailure.TemporarilyUnavailable> { createPayment.create(command) }
                failure.kind shouldBe FailureKind.LOCK_TIMEOUT
                holder.rollback()
            }
            db.count("SELECT COUNT(*) FROM idempotency_request WHERE merchant_account_id = ?", ids.mid) shouldBe 0L
            db.count("SELECT COUNT(*) FROM payment WHERE merchant_account_id = ?", ids.mid) shouldBe 0L
            createPayment.create(command).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        }
    }

    Given("the eligibility adapter receives entitlement store failures") {
        val query = mockk<EntitlementEvaluationQuery>()
        val adapter = EntitlementEligibilityAdapter(query)
        fun evaluate() = adapter.evaluate(
            Environment.LIVE, MerchantAccountId("mid-x"), MerchantSiteId("site-x"), CurrencyCode("KRW"), PaymentMethod.CARD, T0,
        )
        Then("deadlock, lock wait timeout and unavailability map to the payment store failure classes (retry / 503 / 503)") {
            val cause = SQLException("x", "40001", 1213)
            every { query.evaluate(any<EligibilityRequest>(), any()) } throws EntitlementEvaluationFailure.Deadlock(cause)
            shouldThrow<PersistenceFailure.Deadlock> { evaluate() }
            every { query.evaluate(any<EligibilityRequest>(), any()) } throws EntitlementEvaluationFailure.LockTimeout(cause)
            shouldThrow<PersistenceFailure.LockTimeout> { evaluate() }
            every { query.evaluate(any<EligibilityRequest>(), any()) } throws EntitlementEvaluationFailure.Unavailable(cause)
            shouldThrow<PersistenceFailure.Unavailable> { evaluate() }
        }
    }
})
