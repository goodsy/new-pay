package com.paycore.master.merchant.domain

/**
 * 접수 건 생성·전이 결과: 저장할 새 접수 건 상태([intake] — 전이면 version +1), 그 변화의 전이 이력 행([transition]), 계약 완료 전이면 사업자 계약 변경([contract] —
 * 신규 = 계약 + 버전 1, 변경 = 새 버전 + 닫기 — BR-BIZ-314·324, 그 밖 null). Application이 사업자 행 잠금 아래 같은 MASTER 로컬 TX에 모두 저장한다(FLOW-2b).
 * 효력 없음 — 권리·Snapshot·Outbox를 만들지 않는다(§4A.2, BR-BIZ-313).
 */
data class ContractIntakeChange(val intake: ContractIntake, val transition: ContractIntakeTransition, val contract: BusinessContractChange? = null) {
    init {
        require((contract != null) == (transition.from != null && transition.to == ContractIntakeStatus.COMPLETED)) { "only a completion carries a business contract change" }
    }
}
