package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.MerchantId
import java.time.Instant

/**
 * 전파·효력 확인 조회(CQRS, jOOQ — 명세 §3.4-5, §4.8). 발행(PUBLISHED)만으로 효력 확인을 표시하지 않는다: revision r의 전체 효력 확인 =
 * (a) appliedRevision ≥ r(또는 r 이상 revision의 UNSUPPORTED — PAYMENT fail-closed)인 ACK 시각, (b) r 미만 revision lease 최댓값 경과 중
 * 이른 쪽. 운영 화면은 Q-ADMIN-AUTH 이후.
 */
interface EntitlementPropagationQuery {
    fun merchant(merchantId: MerchantId): MerchantPropagationView?

    fun overview(): PropagationOverview
}

enum class EffectBasis { ACK, LEASE_EXPIRY }

data class MerchantPropagationView(
    val merchantId: String,
    val latestRevision: Long,
    val latestRevisionIssuedAt: Instant,
    val latestAckRevision: Long?,
    val latestAckAt: Instant?,
    val priorLeaseMaxUntil: Instant?,
    val effectConfirmedAt: Instant?,
    val effectBasis: EffectBasis?,
)

data class PropagationOverview(
    val pendingSnapshots: Long,
    val oldestPendingCreatedAt: Instant?,
    val quarantinedSnapshots: Long,
    val unconfirmedLatestRevisions: Long,
)
