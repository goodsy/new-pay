package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentStatus
import java.time.Instant

/**
 * 생성 결과의 Payment 생성 시점 값(D-CP-003). 현재 상태는 넣지 않는다 — [lifecycleAtCreation]은 항상 REQUESTED.
 * [merchantCode]: 그 MID 가맹점의 코드(MERCHANT-CODE-001 §6 — 출력 전용, 코드 없는 MID는 null → 응답 필드 생략). 생성 결과와 함께 멱등 기록에 저장한 값이며 재생도 그 값이다
 * (C-37(MC-4), D-CP-003). Payment 값이 아니다(Payment 행·요청 지문·판정에 없음).
 */
data class CreatedPayment(
    val paymentId: PaymentId,
    val paymentKey: PaymentKey,
    val lifecycleAtCreation: PaymentStatus,
    val paymentCreatedAt: Instant,
    val merchantOrderId: MerchantOrderId,
    val money: Money,
    val merchantCode: String? = null,
)

/**
 * 업무 결과. [Accepted]·[Rejected]는 commit된(또는 저장된 결과를 재생한) 결과, [IdempotencyKeyReused]·[EligibilityRefused]는 아무것도
 * 저장하지 않은 결과다. [replayed] = 같은 키·같은 내용의 저장 결과 재생.
 */
sealed interface CreatePaymentResult {
    val replayed: Boolean

    data class Accepted(val outcome: CreatePaymentOutcome, val payment: CreatedPayment, override val replayed: Boolean) : CreatePaymentResult

    /** 409 업무 거절(D-CPC-007: 저장·30일 재생). [referencePaymentId]는 같은 MID 자기 주문의 Payment다. */
    data class Rejected(
        val rejection: CreatePaymentRejection,
        val referencePaymentId: PaymentId,
        override val replayed: Boolean,
    ) : CreatePaymentResult

    /** 같은 키·다른 내용(IDEMPOTENCY_KEY_REUSED). 기존 결과·내용을 노출하지 않으며 아무것도 바꾸지 않았다. */
    data object IdempotencyKeyReused : CreatePaymentResult {
        override val replayed: Boolean = false
    }

    /**
     * eligibility 평가가 통과하지 못했다(계약 §9 eligibility 행 404/422/503). TX 전체가 rollback되어 멱등 기록·Payment·Outbox 어느 것도
     * 남지 않았다 — 같은 키 재요청은 다시 평가된다. 재생 결과가 아니다.
     */
    data class EligibilityRefused(val refusal: EligibilityRefusal) : CreatePaymentResult {
        override val replayed: Boolean = false
    }
}

/**
 * eligibility 거절 분류(계약 §13 `EligibilityResult`의 Eligible이 아닌 세 종류). 응답에는 고정 code만 쓰고 내부 버전·정책 값은 내보내지
 * 않는다(§9.1). HTTP 매핑은 CP-API-001.
 */
sealed interface EligibilityRefusal {
    /** Site 없음·다른 Merchant/MID의 Site·Assignment 무효(없음과 권한 없음을 구분하지 않음, 404 MERCHANT_SITE_NOT_FOUND). */
    data object NotFoundOrForbidden : EligibilityRefusal

    /** 통화·결제수단 미활성, 계약 효력 밖, MID 신규 결제 중지(422 PAYMENT_NOT_ELIGIBLE). */
    data class NotEligible(val reason: NotEligibleReason) : EligibilityRefusal

    /** Snapshot 누락·만료·확인 불가 버전(fail-closed, 503 ELIGIBILITY_UNAVAILABLE, 같은 키로 재시도). */
    data class Unavailable(val reason: EligibilityUnavailableReason) : EligibilityRefusal
}
