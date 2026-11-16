package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.CancelPaymentCommand
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorCancelTarget
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 취소 시험 Named Fixture(CANCEL-001 S5, src/test 전용). 승인된 Payment는 **운영 confirm 경로**(TX1 → TXd → stub VAN 승인 → TX2)로 만든다 — 취소 Capability가 있는 sim-van-2
 * 시험 Route(`SIMVANC` 기본, Admission 대역이 이 MID에만 그 routeRef를 고른다)로 승인해야 원 경로 Capability가 해석된다. 취소 금액은 기본 원 승인 전액이고 시험이 바꾸는 값만 인자로
 * 고친다(독립 난수 없음). hold는 공식 domain 전이(`reportCancellationResultConflict`)로만 연다.
 */
class CancelFixture(
    val confirmFixture: ConfirmFixture,
    private val confirm: ConfirmPaymentUseCase,
    private val admission: ScriptedAdmissionPort,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    transactionManager: PlatformTransactionManager,
    /** 체크카드 승인처럼 stub VAN이 요청 cardType을 echo해야 하는 시험만 넘긴다(기본 응답은 golden CREDIT). */
    private val van: ProgrammableVan? = null,
) {
    private val tx = TransactionTemplate(transactionManager)

    data class Authorized(val prepared: ConfirmFixture.Prepared, val authorizationId: String) {
        val paymentId: PaymentId get() = prepared.paymentId
        val money: Money get() = prepared.money
        val mid: MerchantAccountId get() = prepared.order.merchantAccountId

        fun command(
            key: String = newKey(),
            merchantCancelId: String = newMerchantCancelId(),
            money: Money = this.money,
            paymentId: String = this.paymentId.value,
            mid: MerchantAccountId = this.mid,
        ) = CancelPaymentCommand(
            environment = prepared.order.environment,
            merchantAccountId = mid,
            idempotencyKey = IdempotencyKey(key),
            paymentId = paymentId,
            merchantCancelId = MerchantCancelId(merchantCancelId),
            money = money,
            correlationId = "req-cancel-$key".take(128),
        )

        /** 운영자 보정 취소 명령. [fingerprintRevision]은 승인 시점의 잔액 revision(첫 취소 = 0). */
        @Suppress("LongParameterList")
        fun operatorCommand(
            approvalId: String = "apr-" + UUID.randomUUID().toString().replace("-", "").take(20),
            requestedBy: String = "opr-requester-01",
            approvedBy: String = "opr-approver-02",
            money: Money = this.money,
            reason: String = "Demo correction after reconciliation case review",
            purpose: OperatorCancelPurpose = OperatorCancelPurpose.IncidentOrReconCorrection("case-demo-0001"),
            fingerprintRevision: Long = 0,
            fingerprintMoney: Money = money,
            paymentId: String = this.paymentId.value,
            // ADMIN-PAYMENT-OPS-001 D-OPS-002: 승인 시각은 시험 서버 시각 1분 전, 기한은 그 24시간 뒤(Demo 상한 안).
            approvedAt: java.time.Instant = ConfirmFixture.T0.truncatedTo(ChronoUnit.MILLIS).minusSeconds(60),
            executeBefore: java.time.Instant = approvedAt.plus(java.time.Duration.ofHours(24)),
        ) = OperatorCorrectionCancelCommand(
            paymentId = paymentId,
            money = money,
            requestedBy = OperatorId(requestedBy),
            approvedBy = OperatorId(approvedBy),
            approvalId = OperatorApprovalId(approvalId),
            reason = reason,
            purpose = purpose,
            approvedTargetFingerprint = OperatorCancelTarget(
                PaymentId(paymentId), fingerprintMoney, fingerprintRevision, OperatorApprovalId(approvalId), OperatorId(requestedBy), OperatorId(approvedBy), purpose,
            ).fingerprint(),
            approvedAt = approvedAt,
            executeBefore = executeBefore,
            correlationId = "req-operator-$approvalId",
        )
    }

    /** confirm 운영 경로로 승인된 Payment(기본 Route `SIMVANC` — 부분취소 신용·체크 일시불·신용 할부, 취소 무효과 확정 있음). */
    fun authorized(
        amount: Long = 10_000,
        cardType: CardType = CardType.CREDIT,
        plan: PaymentPlan = PaymentPlan.LumpSum,
        routeRef: String = ROUTE_C,
        order: com.paycore.payment.payment.domain.OrderKey = com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder(),
        paymentIdValue: String? = null,
        /** ADMIN-HOME-KPI-001: 준비 시각 기준(기본 T0 — [ConfirmFixture.prepared]의 base). 확정 시각은 시험 시계가 정하므로 시계를 같은 값으로 맞춘 시험만 바꾼다. */
        base: java.time.Instant = ConfirmFixture.T0,
    ): Authorized {
        val p = confirmFixture.prepared(amount = amount, cardType = cardType, plan = plan, order = order, paymentIdValue = paymentIdValue, base = base)
        admission.respondFor(p.order.merchantAccountId) { ScriptedAdmissionPort.admitted(it, routeRef = routeRef) }
        if (cardType != CardType.CREDIT) {
            val stub = checkNotNull(van) { "a non-credit fixture needs the stub VAN to echo the card type" }
            stub.onAuth(p.order.merchantAccountId.value) { r -> ProgrammableVan.Script(stub.approved(r) { it.put("cardType", cardType.name) }) }
        }
        val accepted = confirm.confirm(p.command()) as ConfirmPaymentResult.Accepted
        check(accepted.view.status == AuthorizationViewStatus.AUTHORIZED) { "the fixture payment must be authorized: ${accepted.view.status}" }
        return Authorized(p, accepted.view.authorizationId.value)
    }

    /** 이 Payment에 취소 결과 사건 hold를 연다(공식 domain 전이 — D-CANCEL-006 경로와 같은 hold). */
    fun holdOn(a: Authorized) {
        tx.executeWithoutResult {
            val control = checkNotNull(controls.lock(a.prepared.order))
            val payment = checkNotNull(payments.lock(a.paymentId))
            control.value.reportCancellationResultConflict(payment.value)
            controls.update(control.value, control.version, ConfirmFixture.T0.truncatedTo(ChronoUnit.MILLIS))
        }
    }

    fun count(sql: String, vararg args: Any?): Long = confirmFixture.count(sql, *args)

    fun scalar(sql: String, vararg args: Any?): Any? = confirmFixture.scalar(sql, *args)

    fun cancellationRows(a: Authorized): Long = count("SELECT COUNT(*) FROM payment_cancellation WHERE payment_id = ?", a.paymentId.value)

    fun idempotencyRows(a: Authorized): List<Map<String, Any?>> = confirmFixture.cancelIdempotencyRows(a.mid)

    companion object {
        const val ROUTE_C = "SIMVANC"
        const val ROUTE_CB = "SIMVANCB"

        fun newKey(): String = "xk-" + UUID.randomUUID().toString().replace("-", "").take(24)

        fun newMerchantCancelId(): String = "mc-" + UUID.randomUUID().toString().replace("-", "").take(20)
    }
}
