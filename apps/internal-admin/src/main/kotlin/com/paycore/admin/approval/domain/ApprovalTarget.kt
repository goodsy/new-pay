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

    /**
     * FINANCIAL 매입 원 파일 대상(`ACQ_REACQUISITION` — ACQ-ADMIN-001 B1-A, D-ACQA-003 승인 하나 = 원 파일 하나). ID 문법 = FINANCIAL 파일 계약 v1. 대상 버전 열은 0 고정이다:
     * 권위 대상 버전은 명령 행 `pinned_target`의 레코드별 (세대, 상태)이고 승인 TX1이 FINANCIAL 재조회 값과 비교한다(FINANCIAL 실행 TX가 다시 비교).
     */
    data class AcquisitionFile(val fileId: String) : ApprovalTarget {
        init {
            require(FILE_ID.matches(fileId)) { "acquisition file id format (ACQ.<acquirer>.<env>.<yyyyMMdd>.<nnn>)" }
        }

        override val kind: ApprovalTargetKind get() = ApprovalTargetKind.ACQUISITION_FILE
        override val ref: String get() = fileId
        override val version: Long get() = 0

        companion object {
            val FILE_ID = Regex("^ACQ\\.[A-Z0-9]{1,32}\\.(TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3}$")
        }
    }

    /**
     * MASTER 가맹점 대상(`MERCHANT_CONTRACT_APPROVAL` — MERCHANT-OPS-002 B2-2 §9.5). 대상 버전 열은 0 고정(C-16): 명령 필드 자체(`contractId#revisionNo`)가 고정값이고, 승인 TX1이
     * 명령 행 `pinned_target`과 MASTER 재조회 값을 비교하며(INVALIDATED), MASTER가 실행 TX에서 업무 규칙을 다시 판정한다.
     */
    data class Merchant(val merchantId: String) : ApprovalTarget {
        init {
            require(MERCHANT_ID.matches(merchantId)) { "merchant id format (mer_ + ULID)" }
        }

        override val kind: ApprovalTargetKind get() = ApprovalTargetKind.MERCHANT
        override val ref: String get() = merchantId
        override val version: Long get() = 0

        companion object {
            val MERCHANT_ID = Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")
        }
    }
}
