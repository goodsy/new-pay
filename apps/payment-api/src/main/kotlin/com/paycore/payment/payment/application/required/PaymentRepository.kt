package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import java.time.Instant

/** domain Payment에 없는 생성 시 불변 속성(CP-CONTRACT-001 §5·§12). 표시 정보(orderName)는 고정 입력이 아니다(D-CPC-001). */
data class PaymentCreationAttributes(
    val paymentKey: PaymentKey,
    val merchantSiteId: MerchantSiteId,
    val money: Money,
    val paymentMethod: PaymentMethod,
    val orderName: String?,
    val eligibilitySnapshotVersion: EligibilitySnapshotVersion,
    val createdAt: Instant,
    /**
     * 생성 행위자(요청한 Credential keyId, D-CPA-001·Auditability). 새 Payment는 항상 값이 있다. null은 V5 이전에 만들어진 행(행위자 컬럼
     * 도입 전)뿐이다.
     */
    val createdByCredentialId: CredentialKeyId?,
)

/** Payment 생성 시 불변 값(재생 응답·이벤트 원천). 현재 상태는 넣지 않는다(D-CP-003). */
data class PaymentCreationRecord(
    val paymentId: PaymentId,
    val orderKey: OrderKey,
    val slotGeneration: Long,
    val attributes: PaymentCreationAttributes,
)

/** Payment 저장소. TX 없이 호출하면 거부한다. */
interface PaymentRepository {
    /** 새 REQUESTED Payment를 삽입한다. PK·payment_key·(주문, generation) 충돌은 [PersistenceFailure.UniqueConflict]. 최초 version을 돌려준다. */
    fun insertNew(payment: Payment, attributes: PaymentCreationAttributes): Long

    /** 잠가 읽는다(없으면 null). */
    fun lock(paymentId: PaymentId): Versioned<Payment>?

    /** 상태 전이·종료 근거 추가를 반영한다(근거는 append-only). 생성 시 불변 값은 바꾸지 않는다. 새 version을 돌려준다. */
    fun update(payment: Payment, expectedVersion: Long): Long

    fun findCreationRecord(paymentId: PaymentId): PaymentCreationRecord?
}
