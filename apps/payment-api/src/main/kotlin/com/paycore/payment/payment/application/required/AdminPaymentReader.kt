package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.AdminPaymentDetail
import com.paycore.payment.payment.application.provided.AdminPaymentListItem
import com.paycore.payment.payment.application.provided.AdminPaymentSearchCriteria

/**
 * 관리자 거래 조회 Port(TXN-OPS-001 §3 — 구현 `adapter.query.JooqAdminPaymentReader`, 쓰기·잠금 없음, 비권위). 모든 SELECT에 질의 시간 상한 hint(ADMIN-PAYMENT-OPS-001 §4.7).
 * 질의 시간 초과는 [QueryTimedOut], 그 밖 접근 실패는 [PersistenceFailure.Unavailable].
 */
interface AdminPaymentReader {
    /** 최신순(`created_at DESC, payment_id DESC`) 최대 [rows]행 — 다음 쪽 판정은 호출자가 `limit + 1`로 한다. */
    fun search(criteria: AdminPaymentSearchCriteria, rows: Int): List<AdminPaymentListItem>

    /** 한 읽기 전용 REPEATABLE READ TX의 상세(§3.4). 없으면 null. */
    fun detail(paymentId: String): AdminPaymentDetail?
}
