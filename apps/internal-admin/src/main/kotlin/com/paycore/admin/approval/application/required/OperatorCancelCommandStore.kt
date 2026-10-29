package com.paycore.admin.approval.application.required

import com.paycore.admin.approval.domain.OperatorCancelRequestSnapshot

/**
 * 운영자 보정 취소 요청 고정 값 저장 Port(ADMIN-PAYMENT-OPS-001 §4.11 `admin_operator_cancel_command` — INSERT-only, §14 기록 Store ③: 승인 Aggregate의 생성 불변 부속 기록이며
 * 자체 생명주기가 없다). 호출자 TX 안에서만(MANDATORY). 갱신·삭제 연산이 없다(DB 권한 1142, Entity `@Immutable`).
 * - [insert]: 승인 행 INSERT와 같은 TX(§4.12 4단계). 같은 승인에 두 번째면 PK 1062(기술 실패 그대로 — 요청 서비스가 한 번만 부른다).
 * - [find]: 승인 TX1의 `pinned_target` 비교·실행 본문 생성 재료.
 */
interface OperatorCancelCommandStore {
    fun insert(command: OperatorCancelRequestSnapshot)

    fun find(approvalId: String): OperatorCancelRequestSnapshot?
}
