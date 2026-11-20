package com.paycore.master.merchant.domain

/**
 * SITE-CONTRACT-CODE-001 §3.1·C-1·C-8: Site·계약 사람용 코드의 가맹점·종류별 순번 표기. 범위 1..9999, **최소 2자리 0 채움**(01..99, 100..9999).
 * `padStart(2, '0')`은 길이가 2 이상이면 그대로라 100 이상을 자르지 않는다 — DB 식 `IF(seq < 100, LPAD(seq, 2, '0'), seq)`(V19 CHECK·백필)와 같은 의미다(LPAD만 쓰면 절단).
 */
internal object HumanCodeSequence {
    const val MIN = 1
    const val MAX = 9999

    /** 순번 문법(01..99 | 100..9999) — 정규식 조각(Site·계약 FORMAT 공용). */
    const val PATTERN = "(0[1-9]|[1-9][0-9]{1,3})"

    fun text(seq: Int): String {
        require(seq in MIN..MAX) { "code sequence must be within $MIN..$MAX" }
        return seq.toString().padStart(2, '0')
    }

    /** 코드 문자열 `<prefix>-<가맹점코드>-<순번>`을 (가맹점 코드, 순번)으로 나눈다(가맹점 코드에 '-'가 없어 마지막 '-' 기준 분리가 모호하지 않다). 형식 검사는 호출자 몫. */
    fun split(value: String, prefix: String): Pair<MerchantCode, Int> {
        val body = value.removePrefix("$prefix-")
        return MerchantCode(body.substringBeforeLast('-')) to body.substringAfterLast('-').toInt()
    }
}
