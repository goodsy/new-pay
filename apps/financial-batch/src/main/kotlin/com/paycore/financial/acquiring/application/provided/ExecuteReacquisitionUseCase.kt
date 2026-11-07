package com.paycore.financial.acquiring.application.provided

import com.paycore.financial.acquiring.domain.AcquisitionOpsDecision
import com.paycore.financial.acquiring.domain.ReacquisitionCommand

/**
 * 재매입 실행(ACQ-ADMIN-001 B1 — D-ACQ-011 "ADMIN 입력은 ACQ-ADMIN-001"). 명령은 ADMIN 조작 전용 서명 채널(D-ACQA-001)로만 들어온다. 한 명령 = FINANCIAL 로컬 TX 하나:
 * 결정 기록 재생 확인 → 원 파일 행·lane·결제별 행 잠금 → D-ADMIN-007 재검사 + [com.paycore.financial.acquiring.domain.ReacquisitionExecutionRule] → (통과) 새 버전 파일 봉인 +
 * 승인 기록 + 불요 기록 + 결정(EXECUTED·NOT_REQUIRED) / (거절) 결정(REJECTED)만. 전송은 기존 전송 경로(TX 밖)가 한다.
 */
interface ExecuteReacquisitionUseCase {
    fun execute(command: ReacquisitionCommand): ReacquisitionExecution

    /** 기록된 결정(없으면 null — ADMIN 회복 job의 결정 조회). */
    fun decision(approvalId: String): AcquisitionOpsDecision?

    /**
     * 기한 확인(D-OPS-009 선례): 결정이 없고 FINANCIAL 시각이 실행 기한을 지났으면 REJECTED(APPROVAL_EXECUTION_EXPIRED)를 기록한다(업무 효과 없음 — 재매입을 시작하지
     * 않는다). 기한 전이면 아무것도 쓰지 않는다([ReacquisitionExecution.NotDecided]). [command]는 그 승인의 저장된 실행 명령 그대로다(같은 내용 hash).
     */
    fun confirmDeadline(command: ReacquisitionCommand): ReacquisitionExecution
}

sealed interface ReacquisitionExecution {
    /** 커밋된 결정. [replayed] = 이미 있던 결정을 돌려줌, [confirmed] = 기한 확인이 이번에 EXPIRED를 기록함. */
    data class Decided(val decision: AcquisitionOpsDecision, val replayed: Boolean, val confirmed: Boolean = false) : ReacquisitionExecution

    /** 같은 승인 ID에 내용이 다른 결정이 이미 있다(파생 충돌 — 결정 아님, 무결성 경보 대상). */
    data object Conflict : ReacquisitionExecution

    /** 기한 확인: 아직 기한 전이고 결정이 없다(기록 없음). */
    data object NotDecided : ReacquisitionExecution
}
