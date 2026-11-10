package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.ApprovalWindow

/**
 * 승인 근거가 붙은 2인 관리 명령(MERCHANT-OPS-002 §9.3·§9.4 — B2-2 계약 승인, B2-3 첫 MID 발급이 같은 틀을 쓴다). 실행(`executeApproved`)과 기한 확인
 * (`confirmDeadline`)은 **같은 operation·같은 멱등 키(`mop1.<approvalId>`)·같은 요청 지문**을 쓴다 — 그래서 둘이 직렬화되고 이후 실행 재전송은 기한 확인 결과를 재생한다.
 *
 * [request]의 actor는 승인자다(C-17 — 실행자 = 승인자, 본문에 `actor` 필드 없음). [window]는 web adapter가 설정에서 넣는다(기본값 없음 — 비웹 seed 컨텍스트에는 bean이 없다).
 * [commandFields]는 §9.4 표 순서의 명령 필드 문자열 — `mop1` 지문의 끝 줄과 요청 지문에 같은 순서로 들어간다.
 */
sealed interface ApprovedCommand {
    val request: AdminRequest
    val evidence: ApprovalEvidence
    val window: ApprovalWindow
    val operation: AdminOperation
    val commandFields: List<String>
}
