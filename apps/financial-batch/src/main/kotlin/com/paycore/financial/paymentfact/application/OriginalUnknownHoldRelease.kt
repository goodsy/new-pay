package com.paycore.financial.paymentfact.application

import com.paycore.financial.paymentfact.application.required.PaymentFactProjectionStore
import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.HoldReleaseOutcome
import com.paycore.financial.paymentfact.domain.HoldReleaseRule
import com.paycore.financial.paymentfact.domain.HoldReleaseTrigger
import com.paycore.financial.paymentfact.domain.PaymentFactHistory
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * D-FFR-003 자동 해제 한 번(호출자 TX 안, 결제별 직렬화 행 잠금 아래). Inbox 원승인 수신 TX와 SWEEP이 같은 절차를 쓴다:
 * 원승인을 가리키는 해제 기록 없는 ORIGINAL_UNKNOWN 보류 → [HoldReleaseRule] → append-only 해제 기록. 보류 원본·사실 행은 바꾸지 않는다.
 * 반환 = (RELEASED 수, REVIEW_REQUIRED 수).
 */
internal class OriginalUnknownHoldRelease(private val projection: PaymentFactProjectionStore) {
    private val log = LoggerFactory.getLogger(OriginalUnknownHoldRelease::class.java)

    fun apply(
        history: PaymentFactHistory,
        authorization: AuthorizedPaymentFact,
        authorizationHeld: Boolean,
        trigger: HoldReleaseTrigger,
        at: Instant,
    ): Pair<Int, Int> {
        val paymentId = authorization.identity.paymentId
        val open = projection.unreleasedOriginalUnknownHolds(paymentId, authorization.transactionId)
        if (open.isEmpty()) return 0 to 0
        val decisions = HoldReleaseRule.decide(history.owner, authorization, authorizationHeld, history.cancellations, open.map { it.cancellationTransactionId })
            .associateBy { it.cancellationTransactionId }
        val paired = open.map { it to decisions.getValue(it.cancellationTransactionId) }
        projection.insertHoldReleases(paymentId, authorization.transactionId, paired, trigger, at)
        val released = paired.count { it.second.outcome == HoldReleaseOutcome.RELEASED }
        val review = paired.size - released
        if (review > 0) {
            // 운영자 검토 대상(강제 Hold 해제 = 2인 승인 — D-ADMIN-003, 입력 경로는 후속 작업). 사유 이름만 남긴다(금액·키 없음).
            log.warn(
                "original-unknown hold needs operator review paymentId={} authorizationTransactionId={} trigger={} mismatches={}",
                paymentId, authorization.transactionId, trigger,
                paired.filter { it.second.outcome == HoldReleaseOutcome.REVIEW_REQUIRED }.flatMap { it.second.mismatches }.toSortedSet().joinToString(","),
            )
        }
        if (released > 0) log.info("original-unknown hold released paymentId={} count={} trigger={}", paymentId, released, trigger)
        return released to review
    }
}
