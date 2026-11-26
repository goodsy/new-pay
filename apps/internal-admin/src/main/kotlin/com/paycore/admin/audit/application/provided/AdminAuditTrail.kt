package com.paycore.admin.audit.application.provided

/**
 * 같은 요청의 앞선 시도 확인(MERCHANT-FLOW-001 §4A.6 리뷰 n-3 — "같은 멱등 키 재전송은 저장된 결과를 돌려주며 재검사하지 않는다"): 이 행위자가 이 action·상관 ID로 시도 감사
 * `REQUESTED` 행을 이미 남겼는가. 시도 행은 ADMIN 1차 검사를 모두 통과한 뒤에만 쓰이므로, 있으면 그 요청은 이미 MASTER로 보내졌을 수 있다(결과 불명 재전송).
 * 읽기 전용(쓰기 없음)·호출자 TX 밖 짧은 읽기. 실패는 예외(호출자는 fail-closed로 닫는다).
 */
interface AdminAuditTrail {
    fun requested(actorId: String, action: String, correlationId: String): Boolean
}
