package com.paycore.simulator.acquirer.domain

/**
 * 수신 멱등·충돌 규칙(ACQUIRING-001 기준선 §5.4 "같은 ID 같은 내용 = 중복, 같은 ID 다른 내용 = 격리"). 최종 파일은 보존되므로(삭제·덮어쓰기 거부) Worker는 같은
 * 최종 파일을 회차마다 다시 본다 — 이미 받은 내용은 새 기록 없이 넘어간다.
 */
object ReceiptRule {

    sealed interface Decision {
        /** 같은 파일 ID·같은 내용을 이미 받았다(새 기록·새 ACK 없음 — 앞선 ACK가 그대로 답이다). */
        data object AlreadyReceived : Decision

        /** 그 파일 ID의 첫 수신(형식 판정 → RECEIVED 또는 FILE_REJECTED, 순번 1). */
        data object First : Decision

        /** 같은 파일 ID에 다른 내용(RECEIPT_CONFLICT — 레코드 처리 없음, ACK 순번 [ordinal]). */
        data class Conflict(val ordinal: Int) : Decision

        /** 같은 ID 다른 내용이 ACK 순번 상한(99)을 넘었다 — 기록·ACK 없이 error 로그만(운영자 확인). */
        data object Exhausted : Decision
    }

    fun decide(prior: List<AcquirerReceipt>, contentSha256: String): Decision = when {
        prior.any { it.contentSha256 == contentSha256 } -> Decision.AlreadyReceived
        prior.isEmpty() -> Decision.First
        prior.size >= AcquisitionFileRef.MAX_SEQ -> Decision.Exhausted
        else -> Decision.Conflict(prior.size + 1)
    }
}
