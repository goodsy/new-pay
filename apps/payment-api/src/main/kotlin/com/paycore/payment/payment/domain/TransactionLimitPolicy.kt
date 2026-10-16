package com.paycore.payment.payment.domain

/**
 * 한도 값. 무제한은 명시 값([Unlimited])으로만 표현한다 — 정책이 없는 것을 무제한으로 해석하지 않는다(D-AUTH-006).
 * 수치는 MASTER 기준정보(Simulator·시험은 Demo 값, D-AUTH-009)이며 코드에 운영 상한이 없다(D-CPC-005).
 */
sealed interface LimitAmount {
    data object Unlimited : LimitAmount

    /** 통화 minor unit 정수 상한(0이면 모든 거래 거절). */
    data class Limited(val amountMinor: Long) : LimitAmount {
        init {
            require(amountMinor >= 0) { "limit amount must not be negative" }
        }
    }
}

/**
 * 거래 한도 정책(D-AUTH-006, 사용자 확정 2026-10-15): 범위 = MID + 통화, 원본 = MASTER 버전 정책(CSA 기본 + MID Override, 효력 기간)이며
 * 권리 Snapshot으로 전파된다(MASTER-SNAPSHOT-002). 정책은 TEST/LIVE 공통이고 사용량은 환경별로 나눈다([LimitUsageKey]).
 * domain은 효력 기간 선택이 끝난 적용 후보를 받는다고 가정한다.
 */
data class TransactionLimitPolicy(
    val version: String,
    val merchantAccountId: MerchantAccountId,
    val currency: CurrencyCode,
    val perTransaction: LimitAmount,
    val daily: LimitAmount,
) {
    init {
        require(version.isNotEmpty()) { "limit policy version must not be empty" }
    }
}

/** 적용 정책 해석 결과. 정확히 하나만 적용하며 없음·모호는 승인 시작 차단(fail-closed, D-AUTH-006). */
sealed interface LimitPolicyResolution {
    data class Resolved(val policy: TransactionLimitPolicy) : LimitPolicyResolution

    data object Missing : LimitPolicyResolution

    data object Ambiguous : LimitPolicyResolution
}

object TransactionLimitPolicies {
    /** [candidates] 중 [merchantAccountId]·[currency]에 맞는 정책이 정확히 하나면 Resolved, 0개면 Missing, 둘 이상이면 Ambiguous. */
    fun resolve(candidates: List<TransactionLimitPolicy>, merchantAccountId: MerchantAccountId, currency: CurrencyCode): LimitPolicyResolution {
        val applicable = candidates.filter { it.merchantAccountId == merchantAccountId && it.currency == currency }
        return when (applicable.size) {
            0 -> LimitPolicyResolution.Missing
            1 -> LimitPolicyResolution.Resolved(applicable.single())
            else -> LimitPolicyResolution.Ambiguous
        }
    }
}
