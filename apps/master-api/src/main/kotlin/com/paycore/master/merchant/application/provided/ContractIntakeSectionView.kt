package com.paycore.master.merchant.application.provided

import java.time.Instant
import java.time.LocalDate

/**
 * 사업자 상세 '계약 진행' 섹션(MERCHANT-FLOW-001 §6 FLOW-1 — 읽기 전용, jOOQ CQRS 조회). 접수 건은 **최신(생성 순번 큰 것)부터**, 각 건의 전이 이력은 순번 오름차순.
 *
 * - 효력 없는 영업 기록이다(R-1·R-2): 결제 허용·MID 발급 근거(가맹점 계약 Revision 승인)와 무관하다 — 화면은 "계약 진행: 계약 완료"와 "계약 승인"을 다른 용어로 보인다.
 * - 이관 건([Intake.migrated] true — §4A.9)은 접수 항목·근거 참조·계약 담당자가 없다(null). 화면은 "계약 완료(이관) · 근거: 이관"으로 표시한다(R-6 — ADMIN 표시).
 * - [Intake.contractManager]는 관리자 계정 참조(actorId)만 — 표시명은 ADMIN이 자기 `admin_account`에서 채운다(리뷰 m-11, `login_name` 비노출).
 * - [Transition.documentRef]는 계약서 수령 근거 참조 원문(D-FLOW-004 ④ — 사업자 조회 역할 전체, 열람은 사업자 상세 열람 감사에 포함). 목록·현황에는 넣지 않는다.
 * - '사유 및 비고' 원문은 이 섹션에 싣지 않는다(이력 탭 — `master_change_history`, PII #33).
 * - [Intake.version]은 다음 전이 명령(FLOW-2)의 기대값(낙관적 잠금)이다.
 * - [Intake.kind]는 접수 건 종류(`NEW`·`CHANGE` — FLOW-2a V21, BR-BIZ-324·325). ADMIN 응답 생성기·golden `contract-intake-section-v1`에는 아직 싣지 않는다(ADMIN 바이트 동일 사본 —
 *   golden 새 판·사업자 계약 칸은 FLOW-2b).
 */
data class ContractIntakeSectionView(val businessId: String, val intakes: List<Intake>) {

    data class Intake(
        val intakeSeq: Int,
        val status: String,
        val kind: String,
        val migrated: Boolean,
        val receivedOn: LocalDate?,
        val intakeChannel: String?,
        val contractManager: String?,
        val desiredServices: List<String>,
        val version: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
        val transitions: List<Transition>,
    )

    data class Transition(
        val transitionSeq: Int,
        val fromStatus: String?,
        val toStatus: String,
        val migrated: Boolean,
        val documentRef: String?,
        val actorRef: String,
        val occurredAt: Instant,
    ) {
        /** 근거 참조 원문을 로그·예외에 남기지 않는다. */
        override fun toString(): String =
            "Transition(transitionSeq=$transitionSeq, fromStatus=$fromStatus, toStatus=$toStatus, migrated=$migrated, documentRef=${documentRef?.let { "PRESENT" } ?: "ABSENT"})"
    }

    override fun toString(): String = "ContractIntakeSectionView(businessId=$businessId, intakes=${intakes.size})"
}
