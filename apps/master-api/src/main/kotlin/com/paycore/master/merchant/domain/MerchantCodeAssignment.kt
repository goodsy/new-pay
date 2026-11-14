package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 가맹점 코드 배정 한 행(MERCHANT-CODE-001 §3.2 — append-only `merchant_code_assignment`). seq 1 = 첫 배정(등록·채우기), seq 2 = 유일한 변경(MC-1b — 이 배치에는 만드는 경로가 없고
 * 저장된 행 복원만 받는다). 불변식은 V13 CHECK와 같다: seq ∈ {1, 2}, seq 1에는 변경 필드(`replacesCode`·`approvalId`·`reasonCode`)가 없고 source는 REGISTRATION·BACKFILL,
 * seq 2는 변경 필드가 모두 있고 source = CHANGE, 바꾸기 전 코드와 다르다. [assignedAt]·[assignedBy]는 잠금 뒤 시각·행위자다.
 */
class MerchantCodeAssignment private constructor(
    val seq: Int,
    val code: MerchantCode,
    val replacesCode: MerchantCode?,
    val source: MerchantCodeSource,
    val approvalId: String?,
    val reasonCode: String?,
    val assignedAt: Instant,
    val assignedBy: ActorRef,
) {
    init {
        when (seq) {
            FIRST -> require(replacesCode == null && approvalId == null && reasonCode == null && source != MerchantCodeSource.CHANGE) {
                "a first assignment has no change fields and comes from registration or backfill"
            }
            CHANGE -> require(
                replacesCode != null && approvalId != null && reasonCode != null && source == MerchantCodeSource.CHANGE && replacesCode != code,
            ) { "the single change carries the replaced code, approval and reason, and differs from the replaced code" }
            else -> throw IllegalArgumentException("assignment seq must be 1 or 2")
        }
    }

    override fun toString(): String = "MerchantCodeAssignment(seq=$seq, source=$source)"

    companion object {
        const val FIRST = 1
        const val CHANGE = 2

        /** B1 등록의 첫 배정(§3.3). */
        fun registration(code: MerchantCode, at: Instant, by: ActorRef) = MerchantCodeAssignment(FIRST, code, null, MerchantCodeSource.REGISTRATION, null, null, at, by)

        /** 저장 행 복원(persistence Mapper). */
        @Suppress("LongParameterList")
        fun reconstitute(
            seq: Int, code: MerchantCode, replacesCode: MerchantCode?, source: MerchantCodeSource, approvalId: String?, reasonCode: String?, at: Instant, by: ActorRef,
        ) = MerchantCodeAssignment(seq, code, replacesCode, source, approvalId, reasonCode, at, by)
    }
}
