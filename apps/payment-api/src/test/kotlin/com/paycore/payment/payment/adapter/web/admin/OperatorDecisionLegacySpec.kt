package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.EXECUTE
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.body
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.deadline
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration

/**
 * 결정 행 없는 옛 운영자 취소 행(ADMIN-PAYMENT-OPS-001 I-29·§4.4.2 rev 0.3 L-d — 이 작업 전 시험 데이터 호환): 결정 조회는 그 취소 행으로 `STARTED, legacy=true`(내용 hash 없음), 기한 확인은
 * EXPIRED를 기록하지 않고 같은 legacy STARTED, 같은 명령 재전송은 기존 `compareRequest`로 재생(existing=true)·다른 내용은 `APPROVAL_CONFLICT`이며 어느 경우도 결정 행을 보충하지 않는다.
 * 옛 데이터는 공식 실행 경로로 만든 뒤 결정 행만 root 연결로 지워 재현한다(운영 runtime 계정은 DELETE 권한이 없다 — V28).
 */
@PaymentApiTest
class OperatorDecisionLegacySpec(
    private val internal: InternalConnector,
    private val clock: MutablePaymentClock,
    private val db: DbAccess,
    admission: ScriptedAdmissionPort,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager)
    val api = AdminOpsTestClient(internal.localPort)

    afterEach { clock.reset() }

    fun decisions(apr: String) = fixture.count("SELECT COUNT(*) FROM operator_approval_decision WHERE approval_id = ?", apr)

    Given("an operator cancellation row without a decision row (data from before D-OPS-005)") {
        Then("decision lookup, deadline confirmation, same and different resends follow the legacy rules and never add a decision row") {
            val a = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
            val b = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            val cancellationId = api.post(EXECUTE, b).json.get("cancellationId").asString()
            db.withRoot { c -> c.prepareStatement("DELETE FROM operator_approval_decision WHERE approval_id = ?").use { it.setString(1, apr); it.executeUpdate() } }
            decisions(apr) shouldBe 0

            val d = api.get("$EXECUTE/$apr")
            d.status shouldBe 200
            d.json.get("decision").asString() shouldBe "STARTED"
            d.json.get("legacy").asBoolean() shouldBe true
            d.json.get("cancellationId").asString() shouldBe cancellationId
            d.json.has("contentHash") shouldBe false

            clock.set(ConfirmFixture.T0.plus(Duration.ofDays(2)))
            api.post(deadline(apr), b).let {
                it.status shouldBe 200
                it.json.get("decision").asString() shouldBe "STARTED"
                it.json.get("legacy").asBoolean() shouldBe true
                it.json.get("confirmed").asBoolean() shouldBe false
            }
            clock.reset()
            api.post(EXECUTE, b).let { it.status shouldBe 200; it.json.get("existing").asBoolean() shouldBe true }
            api.post(EXECUTE, body(a, approvalId = apr, amountMinor = 1000)).let { it.status shouldBe 409; it.json.get("error").asString() shouldBe "APPROVAL_CONFLICT" }
            decisions(apr) shouldBe 0
            fixture.cancellationRows(a) shouldBe 1
        }
    }
})
