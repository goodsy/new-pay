package com.paycore.master.merchant.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/*
 * 승인 시작 조건 원본(MASTER-SNAPSHOT-002 명세 §3): 거래 한도 정책(D-AUTH-006·D-MS2-001·002), 수수료·정산 정책 버전 등록부(D-MS2-003),
 * (CSA, MID, 통화) 정책 Assignment(D-MD-018), 카드사(매입사)/Route 개통(D-MD-017·D-MS2-004·005). 순수 Kotlin — JPA·Spring 없음.
 * 운영 수치(한도 금액 등)는 코드에 없다(D-CPC-005). 시험·Simulator 값은 Demo fixture에만.
 */

@JvmInline
value class TransactionLimitPolicyId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "limitPolicyId")
    }

    companion object {
        const val PREFIX = "tlp"
    }
}

@JvmInline
value class PolicyVersionId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "policyVersionId")
    }

    companion object {
        const val PREFIX = "pv"
    }
}

@JvmInline
value class PolicyAssignmentId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "policyAssignmentId")
    }

    companion object {
        const val PREFIX = "pas"
    }
}

@JvmInline
value class CardRouteActivationId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "cardRouteActivationId")
    }

    companion object {
        const val PREFIX = "cra"
    }
}

/** 매입사(acquirer) 코드(D-MS2-004 — 카드사 개통의 키). 1~32자 `[A-Z0-9_]`. */
@JvmInline
value class AcquirerCode(val value: String) {
    init {
        require(value.length in 1..MAX && value.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }) { "acquirerCode must be 1..$MAX characters of [A-Z0-9_]" }
    }

    private companion object {
        const val MAX = 32
    }
}

/** PAYMENT 로컬 기관 프로파일 ID(SIMULATOR/REAL의 권위는 PAYMENT 프로파일 — 명세 §3.4). 1~64자 `[A-Za-z0-9._:-]`. */
@JvmInline
value class RouteRef(val value: String) {
    init {
        require(value.length in 1..MAX && value.all(::isRefChar)) { "routeRef must be 1..$MAX characters of [A-Za-z0-9._:-]" }
    }

    private companion object {
        const val MAX = 64
    }
}

/**
 * 기관 계약계정(기관 가맹점번호 등, D-MS2-005): Secret은 아니지만 **내부 식별자 등급** — 로그·지표·응답·예외 메시지에 원문을 쓰지 않는다.
 * 그래서 [toString]은 가린 값이다. 1~64자 `[A-Za-z0-9._:-]`.
 */
@JvmInline
value class InstitutionMerchantRef(val value: String) {
    init {
        require(value.length in 1..MAX && value.all(::isRefChar)) { "institutionMerchantRef must be 1..$MAX characters of [A-Za-z0-9._:-]" }
    }

    override fun toString(): String = "InstitutionMerchantRef(****)"

    private companion object {
        const val MAX = 64
    }
}

/** 정책 코드(수수료·정산 정책의 이름, D-MS2-003). 1~32자 `[A-Z0-9_-]`. */
@JvmInline
value class PolicyCode(val value: String) {
    init {
        require(value.length in 1..MAX && value.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) { "policyCode must be 1..$MAX characters of [A-Z0-9_-]" }
    }

    private companion object {
        const val MAX = 32
    }
}

private fun isRefChar(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == ':' || c == '-'

/** 일 한도 날짜 기준(D-AUTH-007)과 같은 Asia/Seoul 자정 경계(D-MS2-002). */
object SeoulMidnight {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun isBoundary(at: Instant): Boolean = at.nano == 0 && at.atZone(ZONE).toLocalTime() == LocalTime.MIDNIGHT
}

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

/**
 * 한도 정책 대상 확인(소속 검사를 domain에서 한 번만). CSA 기본: 계약이 그 Merchant 것이고 CSA가 그 계약에 있다. MID Override: MID가 그 Merchant
 * 것이고 ACTIVE다.
 */
class LimitTarget private constructor(val merchantId: MerchantId, val scope: LimitScope) {
    companion object {
        fun csaDefault(merchant: Merchant, contract: Contract, agreementId: ContractServiceAgreementId): LimitTarget {
            merchant.requireActive()
            if (contract.merchantId != merchant.id) throw MasterRuleViolation(MasterRejection.CONTRACT_MERCHANT_MISMATCH)
            contract.agreement(agreementId) ?: throw MasterRuleViolation(MasterRejection.AGREEMENT_NOT_FOUND)
            return LimitTarget(merchant.id, LimitScope.CsaDefault(agreementId))
        }

        fun midOverride(merchant: Merchant, account: MerchantAccount): LimitTarget {
            merchant.requireActive()
            require(account.merchantId == merchant.id) { "MID must belong to the merchant" }
            account.requireActive()
            return LimitTarget(merchant.id, LimitScope.MidOverride(account.id))
        }
    }
}

enum class PolicyKind { FEE_SCHEDULE, SETTLEMENT_POLICY }

/**
 * 정책 내용 상태(D-MS2-003): 이번 등록부에는 계산 내용 모델이 없다. UNDEFINED·DEMO 모두 "내용 없는 버전"이며 TEST/Simulator 전용, LIVE 허용
 * Gate에서 차단 대상이다. 내용 모델은 SETTLEMENT-CONTRACT-001.
 */
enum class PolicyContentStatus { UNDEFINED, DEMO }

/** 수수료·정산 정책 버전 식별 등록부(D-MS2-003, append-only). 같은 (종류, 코드)의 버전 번호는 1부터 단조 증가. */
class PolicyVersion private constructor(
    val id: PolicyVersionId,
    val kind: PolicyKind,
    val code: PolicyCode,
    val versionNo: Int,
    val contentStatus: PolicyContentStatus,
    val contentHash: String?,
    val reason: DecisionReason,
    val registeredAt: Instant,
    val registeredBy: ActorRef,
) {
    init {
        require(versionNo >= 1) { "policy version numbers start at 1" }
        require(contentHash == null || CONTENT_HASH.matches(contentHash)) { "contentHash must be 64 lower-case hex characters" }
    }

    override fun toString(): String = "PolicyVersion(id=${id.value}, kind=$kind, code=${code.value}, versionNo=$versionNo)"

    companion object {
        private val CONTENT_HASH = Regex("^[0-9a-f]{64}$")

        /** 새 버전. [existing]은 같은 (종류, 코드)의 기존 버전 전체. 번호 = 최대 + 1(동시 등록은 UNIQUE가 막고 재시도에서 다음 번호). */
        @Suppress("LongParameterList")
        fun register(
            id: PolicyVersionId,
            kind: PolicyKind,
            code: PolicyCode,
            existing: List<PolicyVersion>,
            contentStatus: PolicyContentStatus,
            contentHash: String?,
            reason: DecisionReason,
            at: Instant,
            by: ActorRef,
        ): PolicyVersion {
            require(existing.all { it.kind == kind && it.code == code }) { "existing versions must be of the same kind and code" }
            if (contentHash != null && !CONTENT_HASH.matches(contentHash)) throw MasterRuleViolation(MasterRejection.POLICY_CONTENT_HASH_INVALID)
            return PolicyVersion(id, kind, code, (existing.maxOfOrNull { it.versionNo } ?: 0) + 1, contentStatus, contentHash, reason, at, by)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: PolicyVersionId,
            kind: PolicyKind,
            code: PolicyCode,
            versionNo: Int,
            contentStatus: PolicyContentStatus,
            contentHash: String?,
            reason: DecisionReason,
            registeredAt: Instant,
            registeredBy: ActorRef,
        ) = PolicyVersion(id, kind, code, versionNo, contentStatus, contentHash, reason, registeredAt, registeredBy)
    }
}

/**
 * (CSA, MID, 통화, 종류) 정책 Assignment(D-MD-018, 명세 §3.3). CSA는 **MID의 발급 CSA와 같아야 한다**(도출 — Coordinator 확인). 대상 정책 버전은
 * 불변, 바꾸려면 종료 + 새 Assignment. 기간: 소급 금지, 같은 (MID, 통화, 종류) 중첩 금지, 종료 한 번(D-MS2-002 자정 제한은 한도 정책에만).
 */
class PolicyAssignment private constructor(
    val id: PolicyAssignmentId,
    val merchantId: MerchantId,
    val agreementId: ContractServiceAgreementId,
    val merchantAccountId: MerchantAccountId,
    val currency: CurrencyCode,
    val kind: PolicyKind,
    val policyVersionId: PolicyVersionId,
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
        require(!period.from.isBefore(createdAt)) { "a stored assignment cannot start before it was recorded" }
        require((endedAt == null) == (endedBy == null)) { "end time and actor are recorded together" }
    }

    fun end(effectiveTo: Instant, at: Instant, by: ActorRef) {
        if (period.to != null) throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_ALREADY_ENDED)
        if (effectiveTo.isBefore(at)) throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_RETROACTIVE)
        period = EffectivePeriod.ofMillis(period.from, effectiveTo)
        endedAt = at
        endedBy = by
    }

    override fun toString(): String = "PolicyAssignment(id=${id.value}, kind=$kind, currency=${currency.value}, period=$period)"

    companion object {
        /** [existing]은 같은 MID의 기존 Assignment 전체(merchant 잠금 아래). */
        @Suppress("LongParameterList")
        fun assign(
            id: PolicyAssignmentId,
            account: MerchantAccount,
            agreementId: ContractServiceAgreementId,
            currency: CurrencyUnit,
            version: PolicyVersion,
            period: EffectivePeriod,
            existing: List<PolicyAssignment>,
            reason: DecisionReason,
            at: Instant,
            by: ActorRef,
        ): PolicyAssignment {
            require(existing.all { it.merchantAccountId == account.id }) { "existing assignments must belong to the MID" }
            account.requireActive()
            if (agreementId != account.issuingBasis.agreementId) throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_AGREEMENT_MISMATCH)
            if (period.from.isBefore(at)) throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_RETROACTIVE)
            existing.firstOrNull { it.currency == currency.code && it.kind == version.kind && it.period.overlaps(period) }?.let {
                throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_OVERLAP, it.id.value)
            }
            return PolicyAssignment(
                id, account.merchantId, agreementId, account.id, currency.code, version.kind, version.id, period, reason, at, by, null, null,
            )
        }

        /** 공백 없는 교체: [old]를 [switchAt]에 끝내고 같은 (MID, 통화, 종류)에 [version]을 [switchAt]부터 건다. */
        @Suppress("LongParameterList")
        fun replace(
            old: PolicyAssignment,
            newId: PolicyAssignmentId,
            account: MerchantAccount,
            version: PolicyVersion,
            switchAt: Instant,
            existing: List<PolicyAssignment>,
            currency: CurrencyUnit,
            reason: DecisionReason,
            at: Instant,
            by: ActorRef,
        ): PolicyAssignment {
            require(old.merchantAccountId == account.id && old.currency == currency.code) { "a replacement keeps the MID and currency" }
            if (old.kind != version.kind) throw MasterRuleViolation(MasterRejection.POLICY_KIND_MISMATCH)
            old.end(switchAt, at, by)
            return assign(newId, account, old.agreementId, currency, version, EffectivePeriod(switchAt), existing, reason, at, by)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: PolicyAssignmentId,
            merchantId: MerchantId,
            agreementId: ContractServiceAgreementId,
            merchantAccountId: MerchantAccountId,
            currency: CurrencyCode,
            kind: PolicyKind,
            policyVersionId: PolicyVersionId,
            period: EffectivePeriod,
            reason: DecisionReason,
            createdAt: Instant,
            createdBy: ActorRef,
            endedAt: Instant?,
            endedBy: ActorRef?,
        ) = PolicyAssignment(
            id, merchantId, agreementId, merchantAccountId, currency, kind, policyVersionId, period, reason, createdAt, createdBy, endedAt, endedBy,
        )
    }
}

/**
 * 카드사(매입사)/Route 개통(D-MD-017, D-MS2-004): (MID, 결제수단, 매입사)당 종료 아닌 개통 하나 = Route 하나(대체 Route 없음). 전이 규칙은
 * 수단 개통과 같다(D-MS-003 준용 — D-MS2-004): 등록 UNDER_REVIEW, 승인 ACTIVE(기관 근거 필수), 정지 SUSPENDED(사유), 재개 ACTIVE(이력 전체와 다른
 * 새 근거), 종료 비가역(재개통은 새 행). 권리를 넓히는 전이는 MID ACTIVE일 때만, 줄이는 전이는 언제나.
 * [institutionMerchantRef]는 내부 식별자 등급(D-MS2-005) — [toString]에 넣지 않는다.
 */
class CardRouteActivation private constructor(
    val id: CardRouteActivationId,
    val merchantAccountId: MerchantAccountId,
    val paymentMethod: PaymentMethod,
    val acquirerCode: AcquirerCode,
    val routeRef: RouteRef,
    val institutionMerchantRef: InstitutionMerchantRef,
    val registeredAt: Instant,
    val registeredBy: ActorRef,
    status: ActivationStatus,
    evidenceRef: EvidenceRef?,
    statusChangedAt: Instant,
) {
    var status: ActivationStatus = status
        private set

    var evidenceRef: EvidenceRef? = evidenceRef
        private set

    var statusChangedAt: Instant = statusChangedAt
        private set

    val isTerminated: Boolean get() = status == ActivationStatus.TERMINATED

    init {
        require(status != ActivationStatus.ACTIVE || evidenceRef != null) { "an ACTIVE card route carries its institution evidence reference" }
        require(!statusChangedAt.isBefore(registeredAt)) { "status cannot change before registration" }
    }

    fun approve(account: MerchantAccount, evidence: EvidenceRef, at: Instant, by: ActorRef): ActivationTransition {
        requireSameAccount(account)
        account.requireActive()
        return transition(ActivationStatus.UNDER_REVIEW, ActivationStatus.ACTIVE, evidence, null, at, by)
    }

    fun suspend(reason: DecisionReason, at: Instant, by: ActorRef): ActivationTransition =
        transition(ActivationStatus.ACTIVE, ActivationStatus.SUSPENDED, null, reason, at, by)

    /** SUSPENDED → ACTIVE. [pastEvidence]는 이 개통의 전이 이력에 남은 모든 근거 — 그 어느 것과도 달라야 한다(D-MS-003 준용). */
    fun resume(account: MerchantAccount, evidence: EvidenceRef, pastEvidence: Collection<EvidenceRef>, at: Instant, by: ActorRef): ActivationTransition {
        requireSameAccount(account)
        account.requireActive()
        if (status == ActivationStatus.SUSPENDED && (evidence == evidenceRef || evidence in pastEvidence)) {
            throw MasterRuleViolation(MasterRejection.CARD_ROUTE_EVIDENCE_NOT_NEW)
        }
        return transition(ActivationStatus.SUSPENDED, ActivationStatus.ACTIVE, evidence, null, at, by)
    }

    fun terminate(reason: DecisionReason, at: Instant, by: ActorRef): ActivationTransition {
        if (isTerminated) throw MasterRuleViolation(MasterRejection.CARD_ROUTE_TRANSITION_NOT_ALLOWED)
        return apply(ActivationTransition(status, ActivationStatus.TERMINATED, null, reason, at, by))
    }

    private fun transition(
        expected: ActivationStatus,
        to: ActivationStatus,
        evidence: EvidenceRef?,
        reason: DecisionReason?,
        at: Instant,
        by: ActorRef,
    ): ActivationTransition {
        if (status != expected) throw MasterRuleViolation(MasterRejection.CARD_ROUTE_TRANSITION_NOT_ALLOWED)
        return apply(ActivationTransition(status, to, evidence, reason, at, by))
    }

    private fun apply(t: ActivationTransition): ActivationTransition {
        require(!t.at.isBefore(statusChangedAt)) { "a transition cannot precede the previous one" }
        status = t.to
        if (t.evidenceRef != null) evidenceRef = t.evidenceRef
        statusChangedAt = t.at
        return t
    }

    private fun requireSameAccount(account: MerchantAccount) {
        require(account.id == merchantAccountId) { "card route belongs to another MID" }
    }

    override fun toString(): String =
        "CardRouteActivation(id=${id.value}, method=$paymentMethod, acquirer=${acquirerCode.value}, route=${routeRef.value}, status=$status)"

    companion object {
        /**
         * 새 카드사 개통(UNDER_REVIEW). 수단 개통(MID, 수단)이 종료되지 않은 상태여야 하고(도출 — 수단 개통이 상위 조건), 같은 (MID, 수단, 매입사)의
         * 종료 아닌 개통이 없어야 한다(D-MS2-004). [existing]은 같은 MID의 카드사 개통 전체, [methodActivation]은 그 수단의 종료 아닌 개통.
         */
        @Suppress("LongParameterList")
        fun register(
            id: CardRouteActivationId,
            account: MerchantAccount,
            methodActivation: PaymentMethodActivation?,
            acquirer: AcquirerCode,
            route: RouteRef,
            institutionMerchantRef: InstitutionMerchantRef,
            existing: List<CardRouteActivation>,
            at: Instant,
            by: ActorRef,
        ): Pair<CardRouteActivation, ActivationTransition> {
            require(existing.all { it.merchantAccountId == account.id }) { "existing card routes must belong to the MID" }
            account.requireActive()
            if (methodActivation == null || methodActivation.isTerminated || methodActivation.merchantAccountId != account.id) {
                throw MasterRuleViolation(MasterRejection.CARD_ROUTE_METHOD_NOT_ACTIVATED)
            }
            existing.firstOrNull { !it.isTerminated && it.paymentMethod == methodActivation.paymentMethod && it.acquirerCode == acquirer }?.let {
                throw MasterRuleViolation(MasterRejection.CARD_ROUTE_DUPLICATE, it.id.value)
            }
            val route0 = CardRouteActivation(
                id, account.id, methodActivation.paymentMethod, acquirer, route, institutionMerchantRef, at, by, ActivationStatus.UNDER_REVIEW, null, at,
            )
            return route0 to ActivationTransition(null, ActivationStatus.UNDER_REVIEW, null, null, at, by)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: CardRouteActivationId,
            merchantAccountId: MerchantAccountId,
            paymentMethod: PaymentMethod,
            acquirerCode: AcquirerCode,
            routeRef: RouteRef,
            institutionMerchantRef: InstitutionMerchantRef,
            registeredAt: Instant,
            registeredBy: ActorRef,
            status: ActivationStatus,
            evidenceRef: EvidenceRef?,
            statusChangedAt: Instant,
        ) = CardRouteActivation(
            id, merchantAccountId, paymentMethod, acquirerCode, routeRef, institutionMerchantRef, registeredAt, registeredBy, status, evidenceRef,
            statusChangedAt,
        )
    }
}
