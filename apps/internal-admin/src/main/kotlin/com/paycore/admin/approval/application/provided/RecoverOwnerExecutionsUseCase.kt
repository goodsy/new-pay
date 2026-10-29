package com.paycore.admin.approval.application.provided

/**
 * 소유 서비스 실행 회복(ADMIN-PAYMENT-OPS-001 §4.16, D-OPS-003·006·007·009 — `approval.adapter.batch.OwnerExecutionRecoveryScheduler`가 부른다).
 * 선점(짧은 TX, `SKIP LOCKED`) → 행마다 TX 밖에서: ① 기록 결정 조회 → 있으면 내용 대조 후 반영 ② 기한 + 여유 경과면 기한 확인 전용 호출 ③ 참여자 재검사(정지·역할 상실 →
 * 재전송 중단, 조회만) ④ 무결성 플래그면 POST 안 함 ⑤ 같은 저장 본문 재전송. 결과 반영은 승인 행 → 실행 행 잠금 아래(웹 TX2와 같은 규칙). 처리한(선점한) 승인 수.
 */
interface RecoverOwnerExecutionsUseCase {
    fun recoverDue(): Int
}
