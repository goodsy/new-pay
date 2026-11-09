package com.paycore.admin.merchantops.application.provided

/**
 * 등록 결과(MERCHANT-OPS-002 §4.3). [Decided]는 MASTER가 저장한 판정(200) — 성공만 [reference], 거절은 [rejection]만(D-MOPS-006 ③: 거절에 기존 ID 없음).
 * 결과 불명([RetrySameKey]·[OutcomeUnknown]·[AuditUnavailable])은 **같은 submissionId로 다시** 보내 확인한다(새 제출 금지 — 효과가 이미 있을 수 있다).
 */
sealed interface MerchantRegistrationResult {
    data class Decided(val outcome: String, val reference: String?, val rejection: String?, val replayed: Boolean) : MerchantRegistrationResult

    data object InvalidInput : MerchantRegistrationResult

    data object Forbidden : MerchantRegistrationResult

    data object RateLimited : MerchantRegistrationResult

    /** 같은 MASTER 키·다른 본문(409 — 결함 신호, 효과 없음). */
    data object KeyReused : MerchantRegistrationResult

    /** 쓰기 permit 없음(ADMIN 또는 MASTER) — MASTER Use Case 미호출. */
    data object MasterBusy : MerchantRegistrationResult

    /** MASTER가 확정하지 못했다(진행 중·커밋 불명·일시 장애) — 같은 키로 다시. */
    data object RetrySameKey : MerchantRegistrationResult

    /** timeout·연결 끊김·해석 불가 — 효과 유무를 모른다. */
    data object OutcomeUnknown : MerchantRegistrationResult

    /** 감사 기록 실패(시도 행 실패면 MASTER 미호출, 결과 행 실패면 MASTER 효과는 이미 있을 수 있다 — "결과 확인 필요"). */
    data object AuditUnavailable : MerchantRegistrationResult
}
