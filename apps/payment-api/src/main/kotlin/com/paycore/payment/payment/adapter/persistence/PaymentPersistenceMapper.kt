package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.application.required.PaymentCreationRecord
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.IncidentHold
import com.paycore.payment.payment.domain.IncidentReason
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderIntentFingerprint
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence

/**
 * Domain ↔ JPA Entity 변환. domain 값 객체 생성자를 **다시 통과**시켜 복원한다(예: `amount_minor=1050, currency='USD'` →
 * `Money(1050, CurrencyCode("USD"))`). 저장 값이 현재 규칙에 맞지 않으면(형식 축소·수동 수정·enum 밖 값) 조용히 건너뛰지 않고
 * IllegalArgumentException/IllegalStateException으로 드러낸다. 복원은 domain 공식 복원 factory(`reconstitute`)만 쓴다.
 *
 * 주의(JPA 변경 감지): domain 객체는 영속성 컨텍스트가 추적하지 않는다. 바뀐 값은 Store가 이 Mapper로 Entity 필드에 다시 옮겨야 하며
 * 그때 기대 version(읽을 때 값)을 먼저 비교한다 — 오래된 domain으로 최신 Entity를 덮어쓰지 않는다.
 */
internal object PaymentPersistenceMapper {

    fun orderKeyOf(environment: String, merchantAccountId: String, merchantOrderId: String): OrderKey =
        OrderKey(Environment.valueOf(environment), MerchantAccountId(merchantAccountId), MerchantOrderId(merchantOrderId))

    fun orderKeyColumns(orderKey: OrderKey): OrderKeyColumns =
        OrderKeyColumns.of(orderKey.environment.name, orderKey.merchantAccountId.value, orderKey.merchantOrderId.value)

    fun newPaymentRow(payment: Payment, attributes: PaymentCreationAttributes): JpaPaymentEntity {
        require(payment.status == PaymentStatus.REQUESTED && payment.terminationEvidence.isEmpty()) {
            "a new payment row must be REQUESTED without evidence: ${payment.status}"
        }
        return JpaPaymentEntity.newRow(
            paymentId = payment.id.value,
            paymentKey = attributes.paymentKey.value,
            environment = payment.orderKey.environment.name,
            merchantAccountId = payment.orderKey.merchantAccountId.value,
            merchantOrderId = payment.orderKey.merchantOrderId.value,
            merchantSiteId = attributes.merchantSiteId.value,
            amountMinor = attributes.money.amountMinor,
            currency = attributes.money.currency.value,
            paymentMethod = attributes.paymentMethod.name,
            orderName = attributes.orderName,
            orderIntentFingerprint = payment.intent.value,
            slotGeneration = payment.slotGeneration,
            status = payment.status.name,
            eligibilitySnapshotVersion = attributes.eligibilitySnapshotVersion.value,
            createdAt = attributes.createdAt,
            createdByCredentialId = attributes.createdByCredentialId?.value,
        )
    }

    fun toPayment(row: JpaPaymentEntity, evidence: List<JpaPaymentTerminationEvidenceEntity>): Payment {
        val ordered = evidence.sortedBy { it.key.seq }
        check(ordered.map { it.key.seq } == ordered.indices.toList()) { "termination evidence of ${row.paymentId} is not contiguous" }
        return Payment.reconstitute(
            id = PaymentId(row.paymentId),
            orderKey = orderKeyOf(row.environment, row.merchantAccountId, row.merchantOrderId),
            intent = OrderIntentFingerprint(row.orderIntentFingerprint),
            slotGeneration = row.slotGeneration,
            status = PaymentStatus.valueOf(row.status),
            terminationEvidence = ordered.map { TerminationEvidence.valueOf(it.evidence) },
        )
    }

    fun toCreationRecord(row: JpaPaymentEntity): PaymentCreationRecord =
        PaymentCreationRecord(
            paymentId = PaymentId(row.paymentId),
            orderKey = orderKeyOf(row.environment, row.merchantAccountId, row.merchantOrderId),
            slotGeneration = row.slotGeneration,
            attributes = PaymentCreationAttributes(
                paymentKey = PaymentKey(row.paymentKey),
                merchantSiteId = MerchantSiteId(row.merchantSiteId),
                money = Money(row.amountMinor, CurrencyCode(row.currency)),
                paymentMethod = PaymentMethod.valueOf(row.paymentMethod),
                orderName = row.orderName,
                eligibilitySnapshotVersion = EligibilitySnapshotVersion(row.eligibilitySnapshotVersion),
                createdAt = row.createdAt,
                createdByCredentialId = row.createdByCredentialId?.let(::CredentialKeyId),
            ),
        )

    /** 갱신 대상 Entity가 같은 Payment(같은 주문·고정 입력·generation)인지 확인한다. 다르면 Mapper 사용 오류다. */
    fun requireSameIdentity(payment: Payment, row: JpaPaymentEntity) {
        check(
            row.paymentId == payment.id.value &&
                orderKeyOf(row.environment, row.merchantAccountId, row.merchantOrderId) == payment.orderKey &&
                row.orderIntentFingerprint == payment.intent.value &&
                row.slotGeneration == payment.slotGeneration,
        ) { "payment ${payment.id.value} does not match its stored identity" }
    }

    fun toControl(row: JpaOrderPaymentControlEntity, holds: List<JpaIncidentHoldEntity>): OrderPaymentControl =
        OrderPaymentControl.reconstitute(
            orderKey = orderKeyOf(row.orderKey.environment, row.orderKey.merchantAccountId, row.orderKey.merchantOrderId),
            fixedIntent = OrderIntentFingerprint(row.fixedIntentFingerprint),
            currentPaymentId = PaymentId(row.currentPaymentId),
            generation = row.generation,
            authorizedPaymentId = row.authorizedPaymentId?.let(::PaymentId),
            incidentHolds = holds.map { IncidentHold(PaymentId(it.paymentId), IncidentReason.valueOf(it.reason)) },
        )
}
