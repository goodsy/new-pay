package com.paycore.payment.payment.application.provided

import java.time.Duration
import java.time.Instant

/**
 * 관리자 거래 조회(TXN-OPS-001 §3 — 진입점은 ADMIN 채널 `AdminPaymentController`만, ArchUnit). **비권위** 읽기 — 어떤 판정에도 쓰지 않는다. 저장소 실패·질의 시간 초과는
 * [OperatorQueryFailure]. 목록은 주문명 앞 10자만(D-TXOPS-003), 상세는 한 읽기 전용 REPEATABLE READ TX(§3.4). 기관 내부 계정 값·운영자 사유 문구는 어느 타입에도 없다.
 */
interface AdminPaymentQuery {
    /** 최신순 keyset 한 쪽. */
    fun search(criteria: AdminPaymentSearchCriteria): AdminPaymentPage

    /** 없으면 null. */
    fun detail(paymentId: String): AdminPaymentDetail?
}

/** keyset 위치 — `(createdAt, paymentId)` 보다 **작은** 행이 다음 쪽이다(최신순). */
data class AdminPaymentCursor(val createdAt: Instant, val paymentId: String)

/**
 * 검색 조건(§3.1 — 형식 검사는 진입 adapter의 엄격 해석기가 하고, 여기서는 모드·기간 불변식을 다시 확인한다). 기간은 `[createdFrom, createdTo)`.
 * 모드: [paymentId] 정확 조회(다른 조건 없음) / [merchantOrderId](+ [merchantAccountId] 필수, 기간 선택) / 그 밖은 기간 필수(I-3).
 */
data class AdminPaymentSearchCriteria(
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
    val environment: String? = null,
    val merchantAccountId: String? = null,
    val merchantOrderId: String? = null,
    val paymentId: String? = null,
    val status: String? = null,
    val after: AdminPaymentCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit" }
        require((createdFrom == null) == (createdTo == null)) { "period needs both ends" }
        if (createdFrom != null && createdTo != null) {
            require(createdFrom < createdTo && !createdTo.isAfter(createdFrom.plus(MAX_PERIOD))) { "period" }
        }
        if (paymentId != null) {
            require(createdFrom == null && environment == null && merchantAccountId == null && merchantOrderId == null && status == null && after == null) { "exact lookup" }
        } else if (merchantOrderId != null) {
            require(merchantAccountId != null) { "merchantOrderId needs merchantAccountId" }
        } else {
            require(createdFrom != null) { "period is required" }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        /** 기간 상한(D-TXOPS-001 — **Demo 값, 운영값 아님**, LIVE Gate). */
        val MAX_PERIOD: Duration = Duration.ofDays(31)

        /** 목록 주문명 표시 길이(D-TXOPS-003 — code point). */
        const val ORDER_NAME_PREVIEW = 10
    }
}

data class AdminPaymentPage(val items: List<AdminPaymentListItem>, val nextCursor: AdminPaymentCursor?)

/** 목록 항목(§3.3). [orderNamePreview]는 앞 10자(없으면 null), [orderNameTruncated]는 10자를 넘는가. */
data class AdminPaymentListItem(
    val paymentId: String,
    val environment: String,
    val merchantAccountId: String,
    val merchantOrderId: String,
    val orderNamePreview: String?,
    val orderNameTruncated: Boolean,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val createdAt: Instant,
) {
    override fun toString(): String = "AdminPaymentListItem(status=$status)"
}

/** 상세(§3.4). 같은 스냅샷에서 읽은 값만. */
data class AdminPaymentDetail(
    val payment: Header,
    val onHold: Boolean,
    val terminationEvidence: List<String>,
    val authorization: Authorization?,
    val transactions: List<Transaction>,
    val cancellations: List<Cancellation>,
    val returnBalance: ReturnBalance?,
) {
    override fun toString(): String = "AdminPaymentDetail(status=${payment.status})"

    data class Header(
        val paymentId: String,
        val environment: String,
        val merchantAccountId: String,
        val merchantSiteId: String,
        val merchantOrderId: String,
        val orderName: String?,
        val amountMinor: Long,
        val currency: String,
        val paymentMethod: String,
        val status: String,
        val createdAt: Instant,
    ) {
        override fun toString(): String = "Header(status=$status)"
    }

    data class Authorization(
        val authorizationId: String,
        val status: String,
        val failureEvidence: String?,
        val planKind: String,
        val installmentMonths: Int?,
        val cardType: String,
        val openedAt: Instant,
    )

    data class Transaction(
        val transactionId: String,
        val type: String,
        val amountMinor: Long,
        val currency: String,
        val planKind: String?,
        val installmentMonths: Int?,
        val cardType: String?,
        val approvalNumber: String?,
        val providerTransactionId: String,
        val cancellationId: String?,
        val originalTransactionId: String?,
        val cancelScope: String?,
        val institutionTransactionDate: String,
        val institutionBusinessDate: String?,
        val confirmedAt: Instant,
        val recordedBy: String,
    ) {
        override fun toString(): String = "Transaction(type=$type)"
    }

    /** 취소 이력 — 개시 주체는 종류만(I-2: 운영자 사유·근거·요청자·승인자 없음). */
    data class Cancellation(
        val cancellationId: String,
        val initiatorKind: String,
        val merchantCancelId: String?,
        val scope: String,
        val status: String,
        val failureEvidence: String?,
        val amountMinor: Long,
        val currency: String,
        val openedAt: Instant,
    )

    /** 저장된 반환 잔액 값만(I-6 — 취소 가능액 식을 다시 만들지 않는다). */
    data class ReturnBalance(
        val currency: String,
        val authorizedMinor: Long,
        val completedMinor: Long,
        val reservedMinor: Long,
        val forcedMinor: Long,
        val revision: Long,
    )
}
