package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 직전 버전을 닫는 기록(MERCHANT-FLOW-001 §4B.3 — BR-BIZ-341, 표 `business_contract_version_close`, INSERT 전용): 새 버전 [closedByVersionNo] = [versionNo] + 1이 생길 때 같은 TX에
 * 1행. [closedAt] = 새 버전 시작 시각 — 닫힌 버전의 실제 끝(종료일은 닫기로 대체 — I16). 버전 내용 자체는 바꾸지 않는다(BR-BIZ-329).
 */
data class BusinessContractVersionClose(
    val versionNo: Int,
    val closedAt: Instant,
    val closedByVersionNo: Int,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    init {
        require(versionNo >= 1 && closedByVersionNo == versionNo + 1) { "a close row is written by the next version" }
    }
}
