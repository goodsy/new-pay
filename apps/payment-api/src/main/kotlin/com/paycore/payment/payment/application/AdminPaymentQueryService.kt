package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.AdminPaymentCursor
import com.paycore.payment.payment.application.provided.AdminPaymentDetail
import com.paycore.payment.payment.application.provided.AdminPaymentPage
import com.paycore.payment.payment.application.provided.AdminPaymentQuery
import com.paycore.payment.payment.application.provided.AdminPaymentSearchCriteria
import com.paycore.payment.payment.application.required.AdminPaymentReader
import org.springframework.stereotype.Service

/**
 * [AdminPaymentQuery](TXN-OPS-001 §3): 비권위 jOOQ 읽기를 그대로 전달한다. 다음 쪽은 `limit + 1`행으로 판정하고 cursor는 그 쪽 마지막 행의 `(createdAt, paymentId)`다.
 * 실패는 [com.paycore.payment.payment.application.provided.OperatorQueryFailure](기존 관리 조회와 같은 분류).
 */
@Service
class AdminPaymentQueryService(private val reader: AdminPaymentReader) : AdminPaymentQuery {

    override fun search(criteria: AdminPaymentSearchCriteria): AdminPaymentPage = OperatorDecisionViews.guard {
        val rows = reader.search(criteria, criteria.limit + 1)
        val page = rows.take(criteria.limit)
        AdminPaymentPage(page, if (rows.size > criteria.limit) page.last().let { AdminPaymentCursor(it.createdAt, it.paymentId) } else null)
    }

    override fun detail(paymentId: String): AdminPaymentDetail? = OperatorDecisionViews.guard { reader.detail(paymentId) }
}
