package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import java.time.Instant

/** [OrderPaymentControlRepository.openOrLock] 결과. 어느 쪽이든 이 TX가 control 행을 잠그고 있다. */
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
interface OrderPaymentControlRepository {
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
