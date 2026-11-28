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

    /**
     * MERCHANT-CODE-001 MC-1b(§5.1 — 리뷰 m-3): 가맹점 내부 키(`target_merchant_id`). 가맹점 대상만 값이 있다 — **모든 가맹점 대상 요청 생성 경로가 이 한 곳에서 채운다**(종류별 누락 방지).
     * 미해결 키는 이 값 기준이다(`COALESCE(target_merchant_id, target_ref)` — 코드가 바뀌어도 같은 키).
     */
    val merchantKey: String? get() = null

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
     * MASTER 사업자 대상(`BUSINESS_CRITICAL_CHANGE` — BIZ-CRIT-APPROVAL-001 §3.1). 대상 버전 열은 0 고정이다: 권위 기준 version은 명령 행 `base_version`이고 승인 TX1이 MASTER 재조회 version과
     * 비교하며(다르면 INVALIDATED(TARGET_CHANGED)), MASTER 실행 TX가 다시 비교한다.
     */
    data class Business(val businessId: String) : ApprovalTarget {
        init {
            require(BUSINESS_ID.matches(businessId)) { "business id format (biz_ + ULID)" }
        }

        override val kind: ApprovalTargetKind get() = ApprovalTargetKind.BUSINESS
        override val ref: String get() = businessId
        override val version: Long get() = 0

        companion object {
            val BUSINESS_ID = Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")
        }
    }

    /**
     * MASTER 가맹점 대상(`MERCHANT_CONTRACT_APPROVAL` — MERCHANT-OPS-002 B2-2 §9.5). 대상 버전 열은 0 고정(C-16): 명령 필드 자체(`contractId#revisionNo`)가 고정값이고, 승인 TX1이
     * 명령 행 `pinned_target`과 MASTER 재조회 값을 비교하며(INVALIDATED), MASTER가 실행 TX에서 업무 규칙을 다시 판정한다.
     */
    /**
     * MERCHANT-CODE-001 MC-3a(§5.6.4 — C-31·C-36, rev 0.4.7 m-5): [ref](저장·감사 표기) = 요청 시점 가맹점 코드 [code], 없으면 내부 키(MC-3a 전 행). [merchantKey](내부 키) = [merchantId] —
     * MASTER 호출·명령 본문·미해결 키·대사 발견은 언제나 이 값을 쓴다(코드로 MASTER를 부르지 않는다). 상태 판정의 대상 동일성은 `is` 검사 + [merchantKey] 비교만 쓰고
     * data class `equals`(코드 포함)를 쓰지 않는다. 새 요청은 [forNewRequest](코드 non-null — MASTER 상세에 코드가 없으면 요청 거절이지 mer_ 표기로 대체하지 않는다),
     * `code = null`은 저장 행 재구성([reconstitute] — MC-3a 전 행)에서만.
     */
    data class Merchant(val merchantId: String, val code: String? = null) : ApprovalTarget {
        init {
            require(MERCHANT_ID.matches(merchantId)) { "merchant id format (mer_ + ULID)" }
            require(code == null || CODE.matches(code)) { "merchant code format (§3.1 ^[a-z][a-z0-9]{5,13}$)" }
        }

        override val kind: ApprovalTargetKind get() = ApprovalTargetKind.MERCHANT
        override val ref: String get() = code ?: merchantId
        override val version: Long get() = 0
        override val merchantKey: String get() = merchantId

        companion object {
            val MERCHANT_ID = Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")

            /** 가맹점 코드 형식(MERCHANT-CODE-001 §3.1 — 정규화 없음, `_` 없음 → `xxx_<ULID>`·ACQ 참조와 겹치지 않는다). */
            val CODE = Regex("^[a-z][a-z0-9]{5,13}$")

            /** 새 가맹점 대상 요청(MC-3a 뒤): 표기 = 요청 시점 MASTER 상세의 현재 코드(필수). */
            fun forNewRequest(merchantId: String, code: String): Merchant = Merchant(merchantId, code)

            /**
             * 저장 행 재구성: 내부 키 = `target_merchant_id`(MC-1b 뒤 행) 또는 `target_ref`(MC-1b 전 행 — mer_), 코드 = `target_ref`가 코드 형식일 때만(MC-3a 뒤 행).
             * 코드 표기인데 내부 키가 없는 행은 재구성 실패(fail-closed — ADMIN은 코드로 가맹점을 해석하지 않는다, C-30; DB는 V13 ③이 막는다).
             */
            fun reconstitute(targetMerchantId: String?, targetRef: String): Merchant =
                Merchant(targetMerchantId ?: targetRef, code = targetRef.takeIf { CODE.matches(it) })
        }
    }
}
