package com.paycore.master.merchant.application

import com.paycore.master.merchant.domain.BusinessChangeEntry

/** 사업자 변경 이력 `changes` 항목 모양(값은 Domain이 마스킹한 값 — §9). 1인 수정(BusinessProfileService)과 근거 실행(BusinessCriticalChangeService)이 같은 모양을 쓴다. */
internal object BusinessHistoryEntries {
    /** 생성은 이전 값 키를 두지 않는다(before 없음). */
    fun of(entry: BusinessChangeEntry, created: Boolean): Map<String, Any?> = when (entry) {
        is BusinessChangeEntry.Value -> if (created) {
            mapOf("field" to entry.field, "after" to entry.after)
        } else {
            mapOf("field" to entry.field, "before" to entry.before, "after" to entry.after)
        }
        is BusinessChangeEntry.Rows -> if (created) {
            mapOf("field" to entry.field, "added" to entry.added)
        } else {
            mapOf("field" to entry.field, "ended" to entry.ended, "added" to entry.added)
        }
    }

    /** 이력 detail의 1인 근거 표시(R3-m2 — 원문 없음). */
    fun evidenceFlag(present: Boolean): String = if (present) "PRESENT" else "ABSENT"
}
