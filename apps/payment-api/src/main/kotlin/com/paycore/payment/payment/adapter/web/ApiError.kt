package com.paycore.payment.payment.adapter.web

/** 오류 응답 하나(필드는 계약 §9 관례 + 명세 §7.1). */
data class ApiError(
    val code: ApiErrorCode,
    val field: String? = null,
    val reason: String? = null,
    val referencePaymentId: String? = null,
)
