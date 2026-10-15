package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlStore
import com.paycore.payment.payment.adapter.persistence.JpaPaymentStore
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** API 시험 공통 도구(src/test 전용). */
class ApiSpecSupport(
    private val db: DbAccess,
    private val payments: JpaPaymentStore,
    private val controls: JpaOrderPaymentControlStore,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    fun json(body: String): JsonNode = MAPPER.readTree(body)

    fun newOrderId(): String = "ord-" + UUID.randomUUID().toString().substring(0, 12)

    fun newKey(): String = "idem-" + UUID.randomUUID().toString().substring(0, 12)

    /** 업무 표 행 수(MID 범위). */
    fun rows(mid: String): Map<String, Long> = linkedMapOf(
        "payment" to db.count("SELECT COUNT(*) FROM payment WHERE merchant_account_id = ?", mid),
        "order_payment_control" to db.count("SELECT COUNT(*) FROM order_payment_control WHERE merchant_account_id = ?", mid),
        "idempotency_request" to db.count("SELECT COUNT(*) FROM idempotency_request WHERE merchant_account_id = ?", mid),
        "outbox_event" to db.count("SELECT COUNT(*) FROM outbox_event WHERE payload->>'$.merchantAccountId' = ?", mid),
    )

    fun rows(payment: Long, control: Long, idempotency: Long, outbox: Long) = linkedMapOf(
        "payment" to payment, "order_payment_control" to control, "idempotency_request" to idempotency, "outbox_event" to outbox,
    )

    /** 저장된 Payment·control을 domain 전이로 바꾼다(승인·종료는 AUTH 범위라 시험 준비만 Port로 — control → Payment 잠금 순서). */
    fun change(credential: TestCredentials.Issued, orderId: String, paymentId: String, block: (Payment, OrderPaymentControl) -> Unit) {
        val order = OrderKey(credential.environment, MerchantAccountId(credential.mid), MerchantOrderId(orderId))
        tx.executeWithoutResult {
            val control = controls.lock(order)!!
            val payment = payments.lock(PaymentId(paymentId))!!
            block(payment.value, control.value)
            payments.update(payment.value, payment.version)
            controls.update(control.value, control.version, Instant.parse("2026-10-14T01:02:03.456Z"))
        }
    }

    fun scalar(sql: String, vararg args: Any?): Any? = db.withApp { c -> with(db) { c.scalar(sql, *args) } }

    companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()

        fun environmentOf(issued: TestCredentials.Issued): Environment = issued.environment
    }
}
