package com.paycore.admin.paymentops.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 화면용 거래 조회(TXN-OPS-001 §4.1·§5). 조회자는 요청마다 계정 현재 행(ACTIVE + 조회 역할 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN — I-1 Implementer 해석)으로 판정한다.
 * - 검색: 감사 없음(D-TXOPS-002). 형식·모드 규칙(§3.1 — 기간 필수·31일 Demo 상한, 결제 ID 단독, 주문번호는 MID 필수)은 PAYMENT 호출 **전**에 검사한다.
 * - 상세: PAYMENT가 답하면(FOUND·NOT_FOUND) 감사 1행 `PAYMENT_VIEWED`를 **먼저 기록하고** 결과를 돌려준다. 감사 실패 → [TransactionLookupResult.AuditUnavailable](결과 미반환).
 * PAYMENT 호출은 DB TX 밖, 기존 `lookup` permit pool. 실패는 결과 값이다.
 */
interface TransactionLookupUseCase {
    fun search(viewerActorId: String, criteria: TransactionSearchCriteria): TransactionLookupResult

    /** 엄격 해석에 실패한 검색 본문(원문은 받지 않는다): 조회 역할이 없으면 Forbidden, 있으면 InvalidInput. 감사 없음, PAYMENT 미호출. */
    fun rejectMalformedSearch(viewerActorId: String): TransactionLookupResult

    fun detail(viewerActorId: String, paymentId: String, context: AdminRequestContext): TransactionLookupResult
}

/**
 * 검색 조건(화면 입력 원문 — 형식 검사는 Use Case). cursor는 [afterCreatedAt]·[afterPaymentId] 둘 다 있거나 둘 다 없다.
 * toString에 MID·주문번호·결제 ID를 넣지 않는다(로그·예외 비노출).
 */
data class TransactionSearchCriteria(
    val createdFrom: String? = null,
    val createdTo: String? = null,
    val environment: String? = null,
    val merchantAccountId: String? = null,
    val merchantOrderId: String? = null,
    val paymentId: String? = null,
    val status: String? = null,
    val afterCreatedAt: String? = null,
    val afterPaymentId: String? = null,
    val limit: Int? = null,
) {
    override fun toString(): String =
        "TransactionSearchCriteria(createdFrom=$createdFrom, createdTo=$createdTo, environment=$environment, merchantAccountId=${mask(merchantAccountId)}, " +
            "merchantOrderId=${mask(merchantOrderId)}, paymentId=${mask(paymentId)}, status=$status, after=${mask(afterPaymentId)}, limit=$limit)"

    private fun mask(value: String?) = if (value == null) "null" else "****"
}

sealed interface TransactionLookupResult {
    /** PAYMENT 응답을 허용 필드 모양으로 검사해 다시 만든 문서(문자열·숫자·불린·null·목록·객체만). */
    data class Found(val document: Map<String, Any?>) : TransactionLookupResult {
        override fun toString(): String = "Found"
    }

    data object InvalidInput : TransactionLookupResult

    data object Forbidden : TransactionLookupResult

    data object NotFound : TransactionLookupResult

    data object RateLimited : TransactionLookupResult

    data object AuditUnavailable : TransactionLookupResult

    data class Unavailable(val reason: String) : TransactionLookupResult
}
