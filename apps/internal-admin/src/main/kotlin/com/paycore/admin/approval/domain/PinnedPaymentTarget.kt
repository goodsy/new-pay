package com.paycore.admin.approval.domain

/**
 * 요청 시점에 고정한 PAYMENT 대상 값(ADMIN-PAYMENT-OPS-001 §4.11 `pinned_target` — 승인 TX1이 재조회 값과 비교하는 **권위** 기준, §4.13 B).
 * PAYMENT 대상 조회 응답(§4.3 — 상태·통화·원 승인액·반환 가능액·revision·hold)에서 온다. 진행 중 취소 여부는 요청 advisory라 고정하지 않는다.
 * [toSnapshotJson]은 결정적 JSON(같은 값 = 같은 바이트, 필드 순서 고정)이며 승인 행 `target_snapshot`(표시용 사본)에도 같은 값을 넣는다.
 * 값은 모두 형식이 제한된 식별자·코드·숫자라 JSON 이스케이프가 필요 없다(형식은 생성 시 검사).
 */
data class PinnedPaymentTarget(
    val paymentId: String,
    val paymentStatus: String,
    val currency: String?,
    val authorizedMinor: Long?,
    val cancellableMinor: Long,
    val balanceRevision: Long,
    val onHold: Boolean,
) {
    init {
        require(PAYMENT_ID.matches(paymentId)) { "payment id format (pay_ + ULID)" }
        require(STATUS.matches(paymentStatus)) { "payment status format" }
        require(currency == null || CURRENCY.matches(currency)) { "currency format" }
        require(authorizedMinor == null || authorizedMinor >= 0) { "authorized amount" }
        require(cancellableMinor >= 0) { "cancellable amount" }
        require(balanceRevision >= 0) { "balance revision" }
    }

    fun toSnapshotJson(): String =
        "{\"paymentId\":\"$paymentId\",\"paymentStatus\":\"$paymentStatus\",\"currency\":${currency?.let { "\"$it\"" } ?: "null"}," +
            "\"authorizedMinor\":${authorizedMinor ?: "null"},\"cancellableMinor\":$cancellableMinor,\"balanceRevision\":$balanceRevision,\"onHold\":$onHold}"

    private companion object {
        val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
        val STATUS = Regex("^[A-Z][A-Z_]{0,31}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}
