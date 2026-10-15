package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import java.time.Instant

/**
 * 영속 상태와 그것을 읽었을 때의 동시 수정 감지 version(JPA `@Version`). [version]은 업무 값이 아니다(업무 generation은 domain).
 * 갱신 Port는 이 version을 기대값으로 받아, 그 사이 다른 TX가 바꿨으면 [PersistenceFailure.OptimisticConflict]로 거부한다
 * (오래된 domain 객체로 최신 행을 덮어쓰지 않는다).
 */
data class Versioned<out T>(val value: T, val version: Long)

/** [OrderPaymentControlStore.openOrLock] 결과. 어느 쪽이든 이 TX가 control 행을 잠그고 있다. */
sealed interface ControlAcquisition {
    /** control이 없어서 이 TX가 [Versioned.value](첫 Payment로 연 슬롯)를 삽입했다. */
    data class Opened(val control: Versioned<OrderPaymentControl>) : ControlAcquisition

    /** 이미 있던 control(다른 TX가 커밋). 이 TX가 넘긴 초기 control은 버려졌다. */
    data class Existing(val control: Versioned<OrderPaymentControl>) : ControlAcquisition
}

/**
 * 주문 결제 통제 저장소. 잠금 순서(CP-CONTRACT-001 §11): 멱등 기록 → control → Payment. 같은 슬롯을 바꾸는 다른 Use Case도
 * control → Payment 순서를 따른다. TX 없이 호출하면 거부한다.
 */
interface OrderPaymentControlStore {
    /**
     * OrderKey PK에 [initial]을 먼저 INSERT 시도하고(목록 SELECT 후 INSERT 금지) 이미 있으면 그 행을 잠가 읽는다. 최초 insert 경쟁은
     * PK 충돌 대기로 직렬화된다: 늦은 TX는 먼저 삽입한 TX의 커밋/rollback을 기다린 뒤 Existing(커밋됨) 또는 Opened(rollback됨)를 받는다.
     */
    fun openOrLock(initial: OrderPaymentControl, at: Instant): ControlAcquisition

    /** 잠가 읽는다(없으면 null). */
    fun lock(orderKey: OrderKey): Versioned<OrderPaymentControl>?

    /** domain 변경(점유·성공 이력·사건 보류 추가)을 반영한다. 새 version을 돌려준다. 사건 보류는 append-only로 추가만 한다. */
    fun update(control: OrderPaymentControl, expectedVersion: Long, at: Instant): Long
}

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
interface PaymentStore {
    /** 새 REQUESTED Payment를 삽입한다. PK·payment_key·(주문, generation) 충돌은 [PersistenceFailure.UniqueConflict]. 최초 version을 돌려준다. */
    fun insertNew(payment: Payment, attributes: PaymentCreationAttributes): Long

    /** 잠가 읽는다(없으면 null). */
    fun lock(paymentId: PaymentId): Versioned<Payment>?

    /** 상태 전이·종료 근거 추가를 반영한다(근거는 append-only). 생성 시 불변 값은 바꾸지 않는다. 새 version을 돌려준다. */
    fun update(payment: Payment, expectedVersion: Long): Long

    fun findCreationRecord(paymentId: PaymentId): PaymentCreationRecord?
}
