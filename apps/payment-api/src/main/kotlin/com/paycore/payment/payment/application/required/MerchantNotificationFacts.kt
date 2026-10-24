package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantNotificationType
import java.time.Instant

/**
 * 가맹점 Webhook 본문의 종류별 사실(WEBHOOK-001 §4.2.0 rev 0.2 — GET 응답의 엄격한 부분집합, m-1). 값은 Outbox payload(이벤트 시점)와 불변 행(취소 개시자·가맹점 취소 ID — H-2·F-8)에서
 * persistence adapter가 채운다. 카드번호·Secret·기관 원문·PTX·기관 일자·정책 버전·운영자 정보·hold·실패/만료 시각은 담지 않는다. 금액은 minor 정수.
 */
sealed interface MerchantNotificationFacts {

    /** 승인 terms(GET `authorization` AUTHORIZED와 같은 값). */
    data class Authorized(
        val authorizationId: String,
        val approvedAmountMinor: Long,
        val currency: String,
        val planKind: String,
        val installmentMonths: Int?,
        val cardType: String,
        val approvalNumber: String,
        val approvedAt: Instant,
    ) : MerchantNotificationFacts {
        init {
            require(planKind in PLAN_KINDS) { "plan kind must be LUMP_SUM or INSTALLMENT" }
            require((planKind == "INSTALLMENT") == (installmentMonths != null)) { "installment months exist exactly for an installment plan" }
            require(approvedAmountMinor >= 1) { "approved amount must be positive" }
        }
    }

    /** 승인 실패(GET `authorization` FAILED — `authorizationId`·`failureCategory`). */
    data class AuthorizationFailed(val authorizationId: String, val failureCategory: String) : MerchantNotificationFacts {
        init {
            require(failureCategory in FAILURE_CATEGORIES) { "failure category must be a public category" }
        }
    }

    /** 만료(GET은 상태만 보인다 — 만료 시각 없음). */
    data object Expired : MerchantNotificationFacts

    /** 완료된 취소(GET `cancellations[]` COMPLETED 항목 + 이벤트 시점 반환 요약). [cancelTransactionId]는 PayCore 거래 ID(PTX 아님 — n-5). */
    data class Cancelled(
        val cancellationId: String,
        val initiator: String,
        val merchantCancelId: String?,
        val amountMinor: Long,
        val currency: String,
        val cancelTransactionId: String,
        val cancelApprovalNumber: String?,
        val cancelledAt: Instant,
        val authorizedAmountMinor: Long,
        val cancelledAmountMinor: Long,
        val returnStatus: String,
    ) : MerchantNotificationFacts {
        init {
            requireInitiator(initiator, merchantCancelId)
            require(amountMinor >= 1 && cancelledAmountMinor >= amountMinor) { "cancel amounts must be positive and cumulative" }
            require(returnStatus in RETURN_STATUSES) { "return status after a completed cancel is PARTIALLY_CANCELLED or FULLY_CANCELLED" }
        }
    }

    /** 실패한 취소(GET `cancellations[]` FAILED 항목 — 실패 시각 없음). */
    data class CancelFailed(
        val cancellationId: String,
        val initiator: String,
        val merchantCancelId: String?,
        val amountMinor: Long,
        val currency: String,
        val failureCategory: String,
    ) : MerchantNotificationFacts {
        init {
            requireInitiator(initiator, merchantCancelId)
            require(failureCategory in FAILURE_CATEGORIES) { "failure category must be a public category" }
        }
    }

    companion object {
        private val PLAN_KINDS = setOf("LUMP_SUM", "INSTALLMENT")
        private val FAILURE_CATEGORIES = setOf("DECLINED", "NOT_SENT", "NO_EFFECT")
        private val RETURN_STATUSES = setOf("PARTIALLY_CANCELLED", "FULLY_CANCELLED")

        /** 가맹점 취소만 가맹점 취소 ID를 가진다(D-WH-001 — 운영자 취소는 initiator만). */
        private fun requireInitiator(initiator: String, merchantCancelId: String?) {
            require(initiator == "MERCHANT" || initiator == "OPERATOR") { "initiator must be MERCHANT or OPERATOR" }
            require((initiator == "MERCHANT") == (merchantCancelId != null)) { "a merchant cancel id exists exactly for a merchant cancellation" }
        }

        /** 분류된 종류와 사실 종류가 맞는지(아니면 fan-out이 그 행을 실패로 둔다). */
        fun matches(type: MerchantNotificationType, facts: MerchantNotificationFacts): Boolean = when (type) {
            MerchantNotificationType.PAYMENT_AUTHORIZED -> facts is Authorized
            MerchantNotificationType.PAYMENT_FAILED -> facts is AuthorizationFailed
            MerchantNotificationType.PAYMENT_EXPIRED -> facts is Expired
            MerchantNotificationType.PAYMENT_CANCELLED -> facts is Cancelled
            MerchantNotificationType.PAYMENT_CANCEL_FAILED -> facts is CancelFailed
        }
    }
}
