package com.paycore.admin.approval.domain

/**
 * 승인 대상(ADMIN-PAYMENT-OPS-001 §4.9 sealed `ApprovalTarget`). [version]은 요청 시점 대상 버전으로, 같은 DB 열 `target_security_version`에 둔다(I-13):
 * 계정은 `security_version`, Payment는 반환 잔액 `balanceRevision`(PAYMENT가 실행 때 다시 비교한다 — JPA `@Version`과 무관).
 */
sealed interface ApprovalTarget {
    val kind: ApprovalTargetKind

    /** 대상 식별자(계정 actorId 또는 paymentId). */
    val ref: String

    val version: Long

    /** ADMIN 계정 대상(ADMIN 소유 6종). */
    data class AdminAccount(val actorId: String, val securityVersion: Long) : ApprovalTarget {
        init {
            require(actorId.isNotBlank()) { "account target id" }
        }

        override val kind: ApprovalTargetKind get() = ApprovalTargetKind.ADMIN_ACCOUNT
        override val ref: String get() = actorId
        override val version: Long get() = securityVersion
    }

    /** PAYMENT Payment 대상(`OPERATOR_CORRECTION_CANCEL`). ID 형식 `pay_` + ULID(I-07), revision ≥ 0. */
    data class Payment(val paymentId: String, val balanceRevision: Long) : ApprovalTarget {
        init {
            require(PAYMENT_ID.matches(paymentId)) { "payment id format (pay_ + ULID)" }
            require(balanceRevision >= 0) { "balance revision must not be negative" }
        }

        override val kind: ApprovalTargetKind get() = ApprovalTargetKind.PAYMENT
        override val ref: String get() = paymentId
        override val version: Long get() = balanceRevision

        private companion object {
            val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
        }
    }
}
