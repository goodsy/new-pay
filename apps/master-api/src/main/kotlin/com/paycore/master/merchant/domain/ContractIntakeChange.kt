package com.paycore.master.merchant.domain

/**
 * 접수 건 생성·전이 결과: 저장할 새 접수 건 상태([intake] — 전이면 version +1)와 그 변화의 전이 이력 행([transition]). Application이 사업자 행 잠금 아래 같은 MASTER 로컬 TX에
 * 둘을 저장한다(FLOW-2). 효력 없음 — 권리·Snapshot·Outbox를 만들지 않는다(§4A.2).
 */
data class ContractIntakeChange(val intake: ContractIntake, val transition: ContractIntakeTransition)
