package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.PaymentId

/**
 * 취소 operation Aggregate 저장 Port(CANCEL-001 명세 §8.3 — CONVENTIONS §14 Aggregate 저장 `<Aggregate>Repository`). Attempt·관찰(키 상충 포함)·CANCEL 거래는 Aggregate 안이라
 * 별도 Port가 없고 append-only다(저장된 것을 바꾸거나 지울 수 없다). TX 없이 호출하면 거부한다(MANDATORY).
 * 잠금 순서는 control → payment → payment_authorization → return_balance → payment_cancellation(명세 §4.7) — 호출자가 앞 행을 먼저 잠근다.
 * 반환 예약 행이 취소 행을 참조하므로(FK) 새 취소는 [ReturnBalanceRepository]보다 먼저 [insertNew]한다.
 */
interface PaymentCancellationRepository {
    /**
     * 새 취소(TX1 — Attempt PREPARED 포함) INSERT. 진행 중 하나(D-CANCEL-003)·가맹점 업무 키(D-CANCEL-002)·운영자 approvalId(CC-12)·기관 요청 namespace UNIQUE 충돌은
     * [PersistenceFailure.UniqueConflict]. 최초 version.
     */
    fun insertNew(cancellation: PaymentCancellation): Long

    fun lock(id: CancellationId): Versioned<PaymentCancellation>?

    /** 비잠금 읽기(검토 #3 n-3 — 운영자 같은 요청 판정처럼 행을 바꾸지 않는 비교용). 바꾸려면 [lock]으로 다시 읽는다. */
    fun find(id: CancellationId): PaymentCancellation?

    /** 가맹점 업무 키(영구 — 멱등 기록 보존과 무관)로 취소 ID를 찾는다(비잠금 — 같은 Payment의 쓰기는 payment 행 잠금 아래에서 일어난다). */
    fun findIdByMerchantKey(paymentId: PaymentId, merchantCancelId: MerchantCancelId): CancellationId?

    /** 운영자 2인 승인 ID(전역 영구 키)로 취소 ID를 찾는다(비잠금). */
    fun findIdByApprovalId(approvalId: OperatorApprovalId): CancellationId?

    /** 상태·사건 수·Attempt 상태를 반영하고 새 관찰·거래를 append한다. 저장된 관찰·거래가 사라지거나 바뀌었으면 결함으로 거부한다. 새 version을 돌려준다. */
    fun update(cancellation: PaymentCancellation, expectedVersion: Long): Long
}
