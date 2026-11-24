package com.paycore.admin.merchantops.application.provided

/**
 * 사업자 명령 결과(BUSINESS-PROFILE-001 §7.3). [Decided]는 MASTER가 저장한 판정(200): 성공만 [Decided.reference], 거절은 [Decided.rejection]만. [Decided.existing]은
 * **중복 거절 + 행위자가 사업자 조회 권한 보유**일 때만(D-BIZ-003 — 기존 사업자 상호·원문 번호(BRN-UNMASK-001 — D-BIZ-007)·내부 키·등록 상태, 화면은 내부 키를 그리지 않고 상세 링크에만 쓴다).
 */
sealed interface BusinessProfileResult {
    data class Decided(val outcome: String, val reference: String?, val rejection: String?, val replayed: Boolean, val existing: Map<String, Any?>? = null) : BusinessProfileResult {
        /** NIT-5: existing의 상호는 가린다(개인사업자 상호에 이름 — L-3). */
        override fun toString(): String = "Decided(outcome=$outcome, reference=$reference, rejection=$rejection, replayed=$replayed, existing=${if (existing == null) "null" else "***"})"
    }

    /** 400 — [fieldErrors]는 path·code만(값 없음). 비어 있으면 구조·식별자 위반(필드 오류 없음). MASTER 400 `BUSINESS_VALIDATION_FAILED`도 여기다(효과 없음 확정 — C-16 보충). */
    data class InvalidInput(val fieldErrors: List<BusinessFieldError> = emptyList()) : BusinessProfileResult

    data object Forbidden : BusinessProfileResult

    data object RateLimited : BusinessProfileResult

    /** 같은 MASTER 키·다른 본문(409 — 결함 신호, 효과 없음). */
    data object KeyReused : BusinessProfileResult

    /** 쓰기 permit 없음(ADMIN 또는 MASTER) — MASTER Use Case 미호출. */
    data object MasterBusy : BusinessProfileResult

    /** MASTER가 확정하지 못했다(진행 중·커밋 불명·일시 장애 — 중복 요약 조회 실패 포함, m-1) — 같은 키로 다시. */
    data object RetrySameKey : BusinessProfileResult

    /** timeout·연결 끊김·응답 해석 불가 — 효과 유무를 모른다(효과 없음으로 단정하지 않는다). */
    data object OutcomeUnknown : BusinessProfileResult

    /** 감사 기록 실패(시도 행 실패면 MASTER 미호출, 결과 행 실패면 MASTER 효과는 이미 있을 수 있다 — 같은 submissionId로 다시). */
    data object AuditUnavailable : BusinessProfileResult
}
