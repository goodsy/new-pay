package com.paycore.admin.approval.application

import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult

/**
 * MASTER 승인 명령 응답 → ADMIN 결과 반영 값(MERCHANT-OPS-002 §9.5 매핑 표·C-28 — 웹 TX2와 회복 job 공용).
 * - 200 SUCCEEDED → EXECUTED(`ctr_…#n`), REJECTED → OWNER_REJECTED(code) — 재생 포함 확정.
 * - 409 `IDEMPOTENCY_KEY_REUSED` → EXECUTION_UNKNOWN + 무결성 플래그(한 번만 — 실행 POST 중단, 조회·기한 확인으로만 수렴).
 * - 400·413 → EXECUTION_UNKNOWN(lastFailure = code) — 호출자가 재전송 중단 `OWNER_MALFORMED`를 기록한다(같은 바이트는 계속 거부).
 * - 401·404·503·timeout·해석 불가 → EXECUTION_UNKNOWN(lastFailure). 효과 유무를 모르므로 종결하지 않는다.
 * - 실행 응답에 NONE·NOT_YET_DUE가 오는 일은 계약상 없다 → 해석 불가와 같이 UNKNOWN.
 */
internal object MerchantOutcomes {
    const val KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val OWNER_MALFORMED = "OWNER_MALFORMED"
    const val DECISION_CONTENT_MISMATCH = "DECISION_CONTENT_MISMATCH"

    fun outcome(result: MerchantApprovalResult): OwnerOutcomeApplier.Outcome = when (result) {
        is MerchantApprovalResult.Decided ->
            if (result.succeeded) OwnerOutcomeApplier.Outcome.Started(requireNotNull(result.reference)) else OwnerOutcomeApplier.Outcome.Rejected(requireNotNull(result.rejection))
        MerchantApprovalResult.KeyReused -> OwnerOutcomeApplier.Outcome.Unknown(KEY_REUSED, KEY_REUSED)
        is MerchantApprovalResult.Malformed -> OwnerOutcomeApplier.Outcome.Unknown(result.code, null)
        is MerchantApprovalResult.Unknown -> OwnerOutcomeApplier.Outcome.Unknown(result.failure, null)
        MerchantApprovalResult.None, MerchantApprovalResult.NotYetDue -> OwnerOutcomeApplier.Outcome.Unknown("RESPONSE_UNEXPECTED", null)
    }

    /** 경보 code(`paycore.admin.ops.integrity_alert{code}`): 409·400·413(무결성), 401·404(운영 — MASTER 판정 전 거부), ADMIN 경로에서 나오면 결함인 근거 거절. 그 밖 null. */
    fun alert(result: MerchantApprovalResult): String? = when (result) {
        MerchantApprovalResult.KeyReused -> KEY_REUSED
        is MerchantApprovalResult.Malformed -> result.code
        is MerchantApprovalResult.Unknown -> result.failure.takeIf { result.alert }
        is MerchantApprovalResult.Decided -> result.rejection?.takeIf { it in DEFECT_REJECTIONS }
        else -> null
    }

    /** ADMIN 승인함 경로에서는 나오면 안 되는 MASTER 근거 거절(§9.5 문구 표 — 경보). `APPROVAL_EXPIRED`는 정상 종결이라 제외. */
    private val DEFECT_REJECTIONS = setOf("APPROVAL_SAME_ACTOR", "APPROVAL_WINDOW_INVALID", "APPROVAL_ID_ALREADY_USED", "APPROVER_IS_REVISION_REGISTRANT")
}
