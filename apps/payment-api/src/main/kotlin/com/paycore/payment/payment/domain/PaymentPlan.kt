package com.paycore.payment.payment.domain

/**
 * 결제 방식(D-AUTH-001, 사용자 확정 2026-10-15 — 범위 §1·§2): 일시불 또는 기관·카드·가맹점이 허용하는 일반 유이자 할부.
 * 무이자·부분무이자 프로모션은 초기 제외라 타입이 없다. 전문의 할부 표현(예: 00/0)은 기관 Adapter가 변환한다.
 */
sealed interface PaymentPlan {
    /** 일시불(신용·체크카드). */
    data object LumpSum : PaymentPlan

    /**
     * 일반 할부. [months]의 domain 범위는 기술 범위(2..99, 구현 결정 CA-03)뿐이며 실제 허용 개월은 버전 있는 정책
     * ([PaymentPlanPolicy], 기관 규약 확인 전 Demo 값)이 정한다.
     */
    data class Installment(val months: Int) : PaymentPlan {
        init {
            require(months in MIN_MONTHS..MAX_MONTHS) { "installment months must be within $MIN_MONTHS..$MAX_MONTHS" }
        }

        private companion object {
            const val MIN_MONTHS = 2
            const val MAX_MONTHS = 99
        }
    }
}

/**
 * 카드 종류(D-AUTH-001). 기프트·선불·직불망은 초기 제외라 값이 없다. 최종 근거는 기관 결과이며 승인 시작에서는 인증 증거의 값을 쓴다
 * (클라이언트 선택만 신뢰하지 않음 — 범위 §5).
 */
enum class CardType { CREDIT, CHECK }

/**
 * 할부 허용 정책(버전 있는 Capability 값 — D-AUTH-001·D-AUTH-009: 운영 수치는 코드에 없고 Simulator·시험은 Demo 값).
 * [installmentMonths]가 비면 할부를 허용하지 않는다. [minimumInstallmentAmount]가 null이면 최소금액 조건 없음(명시 값).
 * 정책은 한 통화에 대한 것이다([currency]).
 */
data class PaymentPlanPolicy(
    val version: String,
    val currency: CurrencyCode,
    val installmentMonths: Set<Int>,
    val minimumInstallmentAmount: Money?,
) {
    init {
        require(version.isNotEmpty()) { "plan policy version must not be empty" }
        require(minimumInstallmentAmount == null || minimumInstallmentAmount.currency == currency) {
            "minimum installment amount currency must match the policy currency"
        }
    }
}

/** 결제 방식 거절 사유(외부 노출은 고정 분류 PAYMENT_PLAN_NOT_ALLOWED만 — 명세 §10.1). */
enum class PlanRejection {
    /** 체크카드 할부(PG 단계 거절, 범위 §2). */
    CHECK_CARD_INSTALLMENT,

    /** 정책이 허용하지 않는 할부 개월. */
    INSTALLMENT_MONTHS_NOT_ALLOWED,

    /** 할부 최소금액 미만. */
    BELOW_MINIMUM_INSTALLMENT_AMOUNT,
}

/** 승인 시작의 결제 방식 검사(명세 §5.4). 기관도 같은 조건을 재검증한다(Simulator 계약). */
object PaymentPlanRule {
    /** 허용되면 null, 아니면 거절 사유. 정책 통화와 금액 통화가 다르면 호출 결함이라 [IllegalArgumentException]. */
    fun check(plan: PaymentPlan, cardType: CardType, money: Money, policy: PaymentPlanPolicy): PlanRejection? {
        require(money.currency == policy.currency) { "plan policy currency does not match the payment currency" }
        if (plan is PaymentPlan.Installment && cardType == CardType.CHECK) return PlanRejection.CHECK_CARD_INSTALLMENT
        return precheck(plan, money, policy)
    }

    /**
     * 결제창의 사전 판정(AUTHN-001 명세 §6.2·§7.1): cardType을 모르는 시점의 개월·최소금액만 본다. 체크카드 할부 거절은 cardType을 아는 승인 시작
     * ([check])과 기관의 책임이다(AC1 §5.4). 허용되면 null.
     */
    fun precheck(plan: PaymentPlan, money: Money, policy: PaymentPlanPolicy): PlanRejection? {
        require(money.currency == policy.currency) { "plan policy currency does not match the payment currency" }
        return when (plan) {
            PaymentPlan.LumpSum -> null
            is PaymentPlan.Installment -> when {
                plan.months !in policy.installmentMonths -> PlanRejection.INSTALLMENT_MONTHS_NOT_ALLOWED
                policy.minimumInstallmentAmount != null && money.amountMinor < policy.minimumInstallmentAmount.amountMinor ->
                    PlanRejection.BELOW_MINIMUM_INSTALLMENT_AMOUNT
                else -> null
            }
        }
    }
}
