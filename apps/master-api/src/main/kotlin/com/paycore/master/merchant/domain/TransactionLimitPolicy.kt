package com.paycore.master.merchant.domain

import java.time.Instant

/*
 * 승인 시작 조건 원본(MASTER-SNAPSHOT-002 명세 §3): 거래 한도 정책(D-AUTH-006·D-MS2-001·002), 수수료·정산 정책 버전 등록부(D-MS2-003),
 * (CSA, MID, 통화) 정책 Assignment(D-MD-018), 카드사(매입사)/Route 개통(D-MD-017·D-MS2-004·005). 순수 Kotlin — JPA·Spring 없음.
 * 운영 수치(한도 금액 등)는 코드에 없다(D-CPC-005). 시험·Simulator 값은 Demo fixture에만.
 */

/** 한도 값. 무제한은 명시 값만(D-AUTH-006). 금액은 통화 minor unit 정수(0이면 모든 거래 거절). */
sealed interface LimitValue {
    data object Unlimited : LimitValue

    data class Limited(val amountMinor: Long) : LimitValue {
        init {
            require(amountMinor >= 0) { "limit amount must not be negative" }
        }
    }
}

enum class LimitScopeKind { CSA_DEFAULT, MID_OVERRIDE }

/** 한도 정책 범위(D-AUTH-006): CSA 기본(그 CSA를 발급 근거로 가진 MID들) 또는 MID Override. */
sealed interface LimitScope {
    val kind: LimitScopeKind

    /** 같은 키(범위 종류 + 대상)끼리만 기간 중첩을 검사한다(D-MS2-001 "같은 수준"). */
    val key: String

    data class CsaDefault(val agreementId: ContractServiceAgreementId) : LimitScope {
        override val kind get() = LimitScopeKind.CSA_DEFAULT
        override val key get() = "CSA|${agreementId.value}"
    }

    data class MidOverride(val merchantAccountId: MerchantAccountId) : LimitScope {
        override val kind get() = LimitScopeKind.MID_OVERRIDE
        override val key get() = "MID|${merchantAccountId.value}"
    }
}

/**
 * 거래 한도 정책(D-AUTH-006, D-MS2-001·002). 행 하나 = 버전 하나: 범위·통화·값은 불변이고 바꾸려면 종료 + 새 정책(Replace). 효력 기간 규칙:
 * - 시작·종료는 **Asia/Seoul 자정**만(D-MS2-002 — 일 한도 날짜당 적용 정책 하나).
 * - 소급 시작·소급 종료 금지(from·to ≥ 명령 시각, D-MD-005 유추 — Coordinator 확인), 미래 종료 예약 허용, 종료는 한 번.
 * - 같은 키(같은 수준 + 대상 + 통화) 기간 중첩 금지(D-MS2-001). CSA 기본과 MID Override 사이의 겹침은 허용 — 해석은 Override 우선(PAYMENT).
 */
class TransactionLimitPolicy private constructor(
    val id: TransactionLimitPolicyId,
    val merchantId: MerchantId,
    val scope: LimitScope,
    val currency: CurrencyCode,
    val perTransaction: LimitValue,
    val daily: LimitValue,
    period: EffectivePeriod,
    val reason: DecisionReason,
    val createdAt: Instant,
    val createdBy: ActorRef,
    endedAt: Instant?,
    endedBy: ActorRef?,
) {
    var period: EffectivePeriod = period
        private set

    var endedAt: Instant? = endedAt
        private set

    var endedBy: ActorRef? = endedBy
        private set

    init {
        require(!period.from.isBefore(createdAt)) { "a stored limit policy cannot start before it was recorded" }
        require(SeoulMidnight.isBoundary(period.from) && (period.to == null || SeoulMidnight.isBoundary(period.to))) {
            "limit policy boundaries are Asia/Seoul midnights"
        }
        require((endedAt == null) == (endedBy == null)) { "end time and actor are recorded together" }
    }

    /** 열린 정책에 종료 시각을 정한다. [effectiveTo]는 Seoul 자정, ≥ [at](소급 종료 금지), > from. */
    fun end(effectiveTo: Instant, at: Instant, by: ActorRef) {
        if (period.to != null) throw MasterRuleViolation(MasterRejection.LIMIT_POLICY_ALREADY_ENDED)
        if (!SeoulMidnight.isBoundary(effectiveTo)) throw MasterRuleViolation(MasterRejection.LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT)
        if (effectiveTo.isBefore(at)) throw MasterRuleViolation(MasterRejection.LIMIT_POLICY_RETROACTIVE)
        period = EffectivePeriod.ofMillis(period.from, effectiveTo)
        endedAt = at
        endedBy = by
    }

    override fun toString(): String = "TransactionLimitPolicy(id=${id.value}, scope=${scope.kind}, currency=${currency.value}, period=$period)"

    companion object {
        /**
         * 새 정책. [existing]은 같은 Merchant의 기존 정책 전체(호출자가 merchant 잠금 아래 읽는다). 대상 소속 확인은 [target]이 한다.
         */
        @Suppress("LongParameterList")
        fun register(
            id: TransactionLimitPolicyId,
            target: LimitTarget,
            currency: CurrencyUnit,
            perTransaction: LimitValue,
            daily: LimitValue,
            period: EffectivePeriod,
            existing: List<TransactionLimitPolicy>,
            reason: DecisionReason,
            at: Instant,
            by: ActorRef,
        ): TransactionLimitPolicy {
            require(existing.all { it.merchantId == target.merchantId }) { "existing policies must belong to the merchant" }
            if (!SeoulMidnight.isBoundary(period.from) || (period.to != null && !SeoulMidnight.isBoundary(period.to))) {
                throw MasterRuleViolation(MasterRejection.LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT)
            }
            if (period.from.isBefore(at)) throw MasterRuleViolation(MasterRejection.LIMIT_POLICY_RETROACTIVE)
            existing.firstOrNull { it.scope.key == target.scope.key && it.currency == currency.code && it.period.overlaps(period) }?.let {
                throw MasterRuleViolation(MasterRejection.LIMIT_POLICY_OVERLAP, it.id.value)
            }
            return TransactionLimitPolicy(id, target.merchantId, target.scope, currency.code, perTransaction, daily, period, reason, at, by, null, null)
        }

        /**
         * 공백 없는 교체(명세 §3.1 Replace): [old]를 [switchAt]에 끝내고 같은 범위·통화의 새 정책을 [switchAt]부터 시작한다. [old]는 열린 정책이어야
         * 한다. [existing]에는 [old]가 들어 있다(같은 객체 — 종료가 반영된 기간으로 중첩을 검사한다).
         */
        @Suppress("LongParameterList")
        fun replace(
            old: TransactionLimitPolicy,
            newId: TransactionLimitPolicyId,
            target: LimitTarget,
            currency: CurrencyUnit,
            perTransaction: LimitValue,
            daily: LimitValue,
            switchAt: Instant,
            existing: List<TransactionLimitPolicy>,
            reason: DecisionReason,
            at: Instant,
            by: ActorRef,
        ): TransactionLimitPolicy {
            require(old.scope == target.scope && old.currency == currency.code) { "a replacement keeps the scope and currency" }
            old.end(switchAt, at, by)
            return register(newId, target, currency, perTransaction, daily, EffectivePeriod(switchAt), existing, reason, at, by)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: TransactionLimitPolicyId,
            merchantId: MerchantId,
            scope: LimitScope,
            currency: CurrencyCode,
            perTransaction: LimitValue,
            daily: LimitValue,
            period: EffectivePeriod,
            reason: DecisionReason,
            createdAt: Instant,
            createdBy: ActorRef,
            endedAt: Instant?,
            endedBy: ActorRef?,
        ) = TransactionLimitPolicy(id, merchantId, scope, currency, perTransaction, daily, period, reason, createdAt, createdBy, endedAt, endedBy)
    }
}
