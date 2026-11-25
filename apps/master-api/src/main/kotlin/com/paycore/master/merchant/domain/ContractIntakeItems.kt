package com.paycore.master.merchant.domain

import java.time.LocalDate

/**
 * 접수 항목(§4A.6): 접수일(KST 달력 날짜), 접수 경로, 계약 담당자(관리자 계정 참조 — MASTER는 형식만 안다, ACTIVE OPERATOR 검사는 ADMIN), 희망 서비스(효력 없음).
 * 영업대행사는 FLOW-4에서 추가한다(리뷰 m-2). 이관 접수 건(§4A.9)에는 접수 항목이 없다(근거·담당자 '이관' 표시 — R-6).
 * 입력 규칙(미래 접수일 거절 — KST 이중 검사, '영업대행사' 경로 막음)은 접수 건 생성 명령(FLOW-2)이 적용한다.
 */
data class ContractIntakeItems(
    val receivedOn: LocalDate,
    val channel: ContractIntakeChannel,
    val contractManager: ActorRef,
    val desiredServices: Set<DesiredService>,
)
