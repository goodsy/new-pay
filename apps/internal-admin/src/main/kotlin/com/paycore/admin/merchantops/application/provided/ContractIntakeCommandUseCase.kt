package com.paycore.admin.merchantops.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 화면 계약 접수 건 명령(MERCHANT-FLOW-001 §4B.7·§4B.11 표 2b 행 — 접수 건 생성·전이·계약 완료). [BusinessProfileUseCase]와 같은 순서·결과 틀([BusinessProfileResult]):
 * 앞단(ACTIVE + 업무 조회 역할 — 아니면 403, 감사 없음) → 권한 상수(`BusinessPermissions.CONTRACT_INTAKE` = OPERATOR — 아니면 403 + 감사 `FORBIDDEN`) → 형식·ADMIN 1차 업무 검사
 * (`ContractIntakeInputRules` + 계약 담당자 ACTIVE OPERATOR — 400 + fieldErrors, MASTER 미호출) → 명령 창 → 시도 감사 `REQUESTED` → MASTER(TX 밖, 키 `mo1.<계정>.<submissionId>`) → 결과 감사 → 응답.
 * 결과 불명([BusinessProfileResult.OutcomeUnknown]·[BusinessProfileResult.RetrySameKey]·[BusinessProfileResult.AuditUnavailable])은 **같은 submissionId로 다시** 보내 확인한다.
 */
interface ContractIntakeCommandUseCase {
    fun execute(actorId: String, request: ContractIntakeRequest, context: AdminRequestContext): BusinessProfileResult

    /** 본문이 엄격 해석에 실패했다(구조 위반 — 본문 원문은 받지 않는다). 역할이 형식보다 먼저다(403은 감사, 형식 위반은 지표만 — 사업자 수정 관례). */
    fun rejectMalformed(actorId: String, operation: ContractIntakeOperation, businessId: String?, context: AdminRequestContext): BusinessProfileResult
}

/** 계약 접수 건 명령 종류(감사 action / MASTER 경로·operation — §4B.7). 감사 action은 사업자 관리 감사 이름공간(`BUSINESS_…`)을 쓴다. */
enum class ContractIntakeOperation(val auditAction: String, val masterPath: String, val masterOperation: String) {
    OPEN("BUSINESS_CONTRACT_INTAKE_OPEN", "open", "OpenContractIntake"),
    MOVE("BUSINESS_CONTRACT_INTAKE_MOVE", "move", "MoveContractIntake"),
    COMPLETE("BUSINESS_CONTRACT_INTAKE_COMPLETE", "complete", "CompleteContractIntake"),
}
