package com.paycore.payment.payment.application.provided

/**
 * 형식 오류 감사(ADMIN-PAYMENT-OPS-001 §4.6, AC4 — 진입점은 ADMIN 채널 실행·기한 확인 컨트롤러만, ArchUnit). 서명 통과 뒤 값 타입·본문 단계에서 거절된 명령도 거절 감사 행 하나를 자기 로컬
 * TX에 남긴다(결정 행 없음 — 같은 바이트면 항상 같은 400). 원문은 저장·로그하지 않는다: 형식이 맞는 ID만 값으로, 아니면 자리표시 [MALFORMED]. 저장 실패는 [CancelPaymentFailure](503).
 */
interface OperatorCancelMalformedRecorder {
    fun record(rejection: MalformedOperatorCommand)
}

/** 형식 오류 감사 재료. 각 ID는 형식 검사를 통과한 값 또는 [OperatorCancelMalformedRecorder.MALFORMED]. */
data class MalformedOperatorCommand(
    val approvalId: String,
    val paymentId: String,
    val requestedBy: String,
    val approvedBy: String,
    val code: OperatorCancelRejectionCode,
    val correlationId: String,
) {
    init {
        require(code.malformed) { "a malformed-command audit carries a malformed code" }
    }
}

/** 자리표시(§4.6 표). */
const val MALFORMED = "MALFORMED"
