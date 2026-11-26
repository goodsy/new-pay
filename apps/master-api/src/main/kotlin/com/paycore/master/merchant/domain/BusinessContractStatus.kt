package com.paycore.master.merchant.domain

/**
 * 사업자 계약 상태(MERCHANT-FLOW-001 §4B.4 — **계산 값**, 저장 열 없음): 버전 기간·정지/해지 기록·판정 시각으로 [BusinessContract.statusAt]이 계산한다.
 * - [NOT_YET_EFFECTIVE] 효력 전(버전 1 시작 전) · [ACTIVE] 유효 · [SUSPENDED] 정지 · [TERMINATED] 해지(종결) · [EXPIRED] 만료(종결 — 마지막 버전 종료일 다음 날 00:00 KST부터).
 * '계약 있음'(BR-BIZ-325) = 효력 전·유효·정지([hasContract]). 새 가맹점 등록 관문(FLOW-3)은 [ACTIVE]만 통과한다(BR-BIZ-327).
 */
enum class BusinessContractStatus {
    NOT_YET_EFFECTIVE,
    ACTIVE,
    SUSPENDED,
    TERMINATED,
    EXPIRED,
    ;

    /** 종결(해지·만료) — 모든 사업자 계약 명령 거절, 새 계약은 신규 접수(BR-BIZ-342). */
    val isClosed: Boolean get() = this == TERMINATED || this == EXPIRED

    /** '계약 있음'(BR-BIZ-325 — 이때 새 접수는 변경 접수). */
    val hasContract: Boolean get() = !isClosed
}
