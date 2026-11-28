package com.paycore.admin.approval.application.required

import com.paycore.admin.approval.domain.BusinessChangeRequestSnapshot

/**
 * 사업자 중요정보 변경 승인 요청 고정 값 저장 Port(BIZ-CRIT-APPROVAL-001 §3.1 `admin_business_change_command` — INSERT 전용, §14 기록 Store ③: 승인 Aggregate의 생성 불변 부속 기록).
 * 호출자 TX 안에서만(MANDATORY). [insert]: 승인 행 INSERT와 같은 TX(같은 승인·같은 제안 두 번째면 PK·UNIQUE 1062 — 기술 실패 그대로). [find]: TX1 대조·실행 본문 재료·회복·원문 프록시.
 */
interface BusinessChangeCommandStore {
    fun insert(command: BusinessChangeRequestSnapshot)

    fun find(approvalId: String): BusinessChangeRequestSnapshot?
}
