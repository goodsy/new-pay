package com.paycore.admin.approval.application

import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.audit.application.provided.AuditRecord
import java.time.Instant

/**
 * approval 감사 행의 대상 열 세 개(`target_actor_id`·`target_ref`·`target_merchant_id`)를 만드는 **유일한 곳**(MERCHANT-CODE-001 MC-3a §5.6.4 — rev 0.4.7 MAJOR-1, C-36).
 * MC-3a 뒤 가맹점 승인의 표기(`target.ref`)는 코드라서, 내부 키 없이 표기만 넘기는 작성 지점이 하나라도 있으면 V15 CHECK ③(코드 표기 ⇒ 내부 키 필수)에 막혀
 * 결과 적용(TX2)·회복·만료·철회/거절이 영구 실패한다 → 모든 approval 감사 작성은 [auditRecord] 하나로만 [AuditRecord]를 만든다(소스 검사 ApprovalAuditTargetSourceSpec).
 * - [of]: 저장된 승인 행의 대상 — 계정 → actorId만, Payment·매입 파일 → 표기만, 가맹점 → 표기(새 행 코드, MC-3a 전 행 mer_) + 내부 키(mer_).
 * - [ofMerchantKey]: **승인 행이 없는** 요청 단계 거절(요청 TX rollback) — 저장된 표기가 없으므로 코드를 새로 정하지 않고 mer_ + mer_(C-32 표).
 * - [ofPaymentRef]·[ofFileRef]: 승인 행 밖에서 형식 검사한 소유 참조(대사·근거 조회) — 표기만(값 불변 대조군).
 * Payment 회복 경로에서 문자열로 가맹점 키를 만들지 않는다(rev 0.4.8 MINOR-1) — 가맹점 여부는 언제나 승인 행의 [ApprovalTarget]이 정한다.
 * `audit` 모듈은 approval을 모른다(의존 방향 approval → audit 유지) — [AuditRecord]에는 열 `targetMerchantId`만 더했다.
 */
class ApprovalAuditTarget private constructor(val targetActorId: String?, val targetRef: String?, val targetMerchantId: String?) {

    override fun equals(other: Any?): Boolean =
        other is ApprovalAuditTarget && targetActorId == other.targetActorId && targetRef == other.targetRef && targetMerchantId == other.targetMerchantId

    override fun hashCode(): Int = listOf(targetActorId, targetRef, targetMerchantId).hashCode()

    override fun toString(): String = "ApprovalAuditTarget(actor=$targetActorId, ref=$targetRef, merchant=$targetMerchantId)"

    companion object {
        /** 대상 열 모두 NULL(대상 없는 감사 — 승인 행 자체가 없는 무결성 보고 등, 지금 동작과 같음). */
        val NONE = ApprovalAuditTarget(null, null, null)

        fun of(target: ApprovalTarget): ApprovalAuditTarget = when (target) {
            is ApprovalTarget.AdminAccount -> ApprovalAuditTarget(target.actorId, null, null)
            is ApprovalTarget.Merchant -> ApprovalAuditTarget(null, target.ref, target.merchantKey)
            is ApprovalTarget.Payment, is ApprovalTarget.AcquisitionFile -> ApprovalAuditTarget(null, target.ref, null)
        }

        fun ofMerchantKey(merchantId: String): ApprovalAuditTarget {
            require(ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId)) { "merchant key format (mer_ + ULID)" }
            return ApprovalAuditTarget(null, merchantId, merchantId)
        }

        fun ofPaymentRef(paymentRef: String?): ApprovalAuditTarget = ApprovalAuditTarget(null, paymentRef, null)

        fun ofFileRef(fileId: String?): ApprovalAuditTarget = ApprovalAuditTarget(null, fileId, null)
    }
}

/**
 * approval 감사 행 하나(대상 열은 [ApprovalAuditTarget]에서만 — null이면 [ApprovalAuditTarget.NONE]). 다른 열은 호출자가 그대로 넘긴다.
 */
@Suppress("LongParameterList")
internal fun ApprovalAuditTarget?.auditRecord(
    occurredAt: Instant,
    actorKind: String,
    actorId: String?,
    actorDisplayName: String?,
    action: String,
    resultCode: String,
    correlationId: String,
    executorActorId: String? = null,
    approvalId: String? = null,
    remoteIp: String? = null,
    detail: String? = null,
): AuditRecord {
    val target = this ?: ApprovalAuditTarget.NONE
    return AuditRecord(
        occurredAt = occurredAt, actorKind = actorKind, actorId = actorId, actorDisplayName = actorDisplayName, action = action, resultCode = resultCode,
        correlationId = correlationId, executorActorId = executorActorId, approvalId = approvalId, remoteIp = remoteIp, detail = detail,
        targetActorId = target.targetActorId, targetRef = target.targetRef, targetMerchantId = target.targetMerchantId,
    )
}
